package net.benwoodworth.knbt

import net.benwoodworth.knbt.internal.*
import okio.BufferedSink
import okio.BufferedSource

public abstract class NbtVariant private constructor() {
    internal abstract fun getBinarySource(source: BufferedSource, mutf8: Boolean): BinarySource
    internal abstract fun getBinarySink(sink: BufferedSink, mutf8: Boolean): BinarySink

    public data object Java : NbtVariant() {
        override fun getBinarySource(source: BufferedSource, mutf8: Boolean): BinarySource =
            BigEndianBinarySource(source, mutf8)

        override fun getBinarySink(sink: BufferedSink, mutf8: Boolean): BinarySink =
            BigEndianBinarySink(sink, mutf8)
    }

    public data object Bedrock : NbtVariant() {
        override fun getBinarySource(source: BufferedSource, mutf8: Boolean): BinarySource =
            LittleEndianBinarySource(source, mutf8)

        override fun getBinarySink(sink: BufferedSink, mutf8: Boolean): BinarySink =
            LittleEndianBinarySink(sink, mutf8)
    }

    public data object BedrockNetwork : NbtVariant() {
        override fun getBinarySource(source: BufferedSource, mutf8: Boolean): BinarySource =
            LittleEndianBase128BinarySource(source, mutf8)

        override fun getBinarySink(sink: BufferedSink, mutf8: Boolean): BinarySink =
            LittleEndianBase128BinarySink(sink, mutf8)
    }
}
