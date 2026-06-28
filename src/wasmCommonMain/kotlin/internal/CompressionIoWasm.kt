package net.benwoodworth.knbt.internal

import dev.karmakrafts.kompress.compressingSink
import dev.karmakrafts.kompress.crc.CRC32
import dev.karmakrafts.kompress.crc.crc32Sink
import dev.karmakrafts.kompress.deflate.Deflater
import dev.karmakrafts.kompress.deflate.inflatingSource
import kotlinx.io.*
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSink
import kotlinx.io.okio.asOkioSource
import okio.Sink as OkioSink
import okio.Source as OkioSource

private const val GZIP_MAGIC: UShort = 0x1F8Bu

internal actual fun OkioSource.asGzipSource(): OkioSource {
    val source = this.asKotlinxIoRawSource().buffered()
    source.skipGzipHeader()
    return source.inflatingSource().asOkioSource()
}

internal actual fun OkioSink.asGzipSink(level: Int): OkioSink {
    val rawSink = this.asKotlinxIoRawSink()
    return GzipCompressingSink(rawSink, normalizeLevel(level)).asOkioSink()
}

internal actual fun OkioSource.asZlibSource(): OkioSource {
    val source = this.asKotlinxIoRawSource().buffered()
    source.skipZlibHeader()
    return source.inflatingSource().asOkioSource()
}

internal actual fun OkioSink.asZlibSink(level: Int): OkioSink {
    val rawSink = this.asKotlinxIoRawSink()
    return ZlibCompressingSink(rawSink, normalizeLevel(level)).asOkioSink()
}


private fun normalizeLevel(level: Int): Int = when {
    level < 0 -> Deflater.DEFAULT_LEVEL
    level > 9 -> 9
    else -> level
}

private fun Source.skipNullTerminated() {
    while (true) {
        try {
            if (readUByte() == 0.toUByte()) return
        } catch (e: Exception) {
            throw NbtDecodingException("Unexpected end of gzip header", cause = e)
        }
    }
}

private fun Source.skipGzipHeader() {
    val magic = readUShort()
    if (magic != GZIP_MAGIC) throw NbtDecodingException(
        "Invalid gzip magic: 0x${magic.toString(16).padStart(4, '0')}"
    )

    val cm = readUByte()
    if (cm != 8.toUByte()) throw NbtDecodingException(
        "Unsupported gzip compression method: $cm (expected 8 = DEFLATE)"
    )

    val flags = readUByte().toInt()
    skip(6L) // MTIME (4) + XFL (1) + OS (1)

    if (flags and 0x04 != 0) { // FEXTRA
        val xlen = readUByte().toInt() or (readUByte().toInt() shl 8)
        skip(xlen.toLong())
    }
    if (flags and 0x08 != 0) skipNullTerminated() // FNAME
    if (flags and 0x10 != 0) skipNullTerminated() // FCOMMENT
    if (flags and 0x02 != 0) skip(2L) // FHCRC
}

private fun writeGzipHeader(sink: RawSink) {
    val buf = Buffer()
    buf.writeUShort(GZIP_MAGIC)
    buf.writeUByte(0x08u) // CM = DEFLATE
    buf.writeUByte(0x00u) // FLG
    buf.writeIntLe(0)     // MTIME = 0
    buf.writeUByte(0x00u) // XFL
    buf.writeUByte(0xFFu) // OS = unknown
    sink.write(buf, buf.size)
}

private fun Source.skipZlibHeader() {
    skip(2L) // CMF + FLG
}

private class GzipCompressingSink(
    private val sink: RawSink,
    private val level: Int
) : RawSink {

    /** crc32Sink -> compressingSink -> [sink], created lazily. */
    private var compressSink: RawSink? = null
    private var crc32: CRC32? = null
    private var uncompressedSize = 0L
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        if (compressSink == null) initialize()
        uncompressedSize += byteCount
        compressSink!!.write(source, byteCount)
    }

    override fun flush() {
        compressSink?.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (compressSink == null) initialize()
        compressSink!!.close()

        val trailer = Buffer()
        trailer.writeIntLe(crc32!!.finalize().toInt())
        trailer.writeIntLe((uncompressedSize and 0xFFFFFFFFL).toInt())
        sink.write(trailer, trailer.size)
        sink.close()
    }

    private fun initialize() {
        writeGzipHeader(sink)
        val crc = CRC32()
        crc32 = crc
        val compressing = sink.compressingSink(Deflater(level), isSinkOwned = false)
        compressSink = compressing.crc32Sink(crc32 = crc, isSinkOwned = true)
    }
}

/**
 * A [RawSink] that computes Adler-32 alongside DEFLATE compression.
 * Mirrors kompress's [crc32Sink], but for zlib's Adler-32 checksum.
 *
 * Data flow: [Adler32Sink] -> compressingSink(Deflater) -> rawSink
 */
private class Adler32Sink(private val delegate: RawSink) : RawSink by delegate {
    var a: Long = 1L; private set
    var b: Long = 0L; private set

    val checksum: UInt get() = (((b and 0xFFFFL) shl 16) or (a and 0xFFFFL)).toUInt()

    override fun write(source: Buffer, byteCount: Long) {
        val peek = source.peek()
        val tmp = Buffer()
        var remaining = byteCount
        while (remaining > 0L) {
            tmp.clear()
            val toRead = minOf(4096L, remaining)
            val read = peek.readAtMostTo(tmp, toRead)
            if (read <= 0L) break
            repeat(read.toInt()) {
                val byte = tmp.readUByte().toInt()
                a = (a + byte) % 65521L
                b = (b + a) % 65521L
            }
            remaining -= read
        }
        delegate.write(source, byteCount)
    }
}

private class ZlibCompressingSink(
    private val sink: RawSink,
    private val level: Int
) : RawSink {

    private var compressSink: RawSink? = null
    private var adler32Sink: Adler32Sink? = null
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        if (compressSink == null) initialize()
        compressSink!!.write(source, byteCount)
    }

    override fun flush() {
        compressSink?.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (compressSink == null) initialize()
        compressSink!!.close()

        val trailer = Buffer()
        trailer.writeInt(adler32Sink!!.checksum.toInt()) // big-endian per RFC 1950
        sink.write(trailer, trailer.size)
        sink.close()
    }

    private fun initialize() {
        val buf = Buffer()
        buf.writeUByte(0x78u) // CMF: deflate, window=32K
        buf.writeUByte(0x9Cu) // FLG: default compression
        sink.write(buf, buf.size)

        val compress = sink.compressingSink(Deflater(level), isSinkOwned = false)
        compressSink = Adler32Sink(compress).also { adler32Sink = it }
    }
}
