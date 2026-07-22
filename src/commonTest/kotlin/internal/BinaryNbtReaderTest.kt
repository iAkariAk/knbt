@file:Suppress("TestFunctionName")

package net.benwoodworth.knbt.internal

import com.benwoodworth.parameterize.parameter
import net.benwoodworth.knbt.*
import net.benwoodworth.knbt.NbtVariant.Java
import net.benwoodworth.knbt.file.nbtFiles
import okio.use
import kotlin.test.*

@OptIn(OkioApi::class)
class BinaryNbtReaderTest {
    @Test
    fun Should_decode_all_binary_NBT_strings_as_MUTF8_when_enabled() {
        val mutf8Nbt = Nbt {
            variant = Java
            compression = NbtCompression.None
            mutf8 = true
        }
        val text = "A\u0000\u007F\u0080\u07FF\u0800\u9999\uD83D\uDE00Z"
        val encoded = byteArrayOf(
            10,
            0, 2, 0xC0.toByte(), 0x80.toByte(),
            8,
            0, 2, 0xC0.toByte(), 0x80.toByte(),
            0, 21,
            0x41,
            0xC0.toByte(), 0x80.toByte(),
            0x7F,
            0xC2.toByte(), 0x80.toByte(),
            0xDF.toByte(), 0xBF.toByte(),
            0xE0.toByte(), 0xA0.toByte(), 0x80.toByte(),
            0xE9.toByte(), 0xA6.toByte(), 0x99.toByte(),
            0xED.toByte(), 0xA0.toByte(), 0xBD.toByte(),
            0xED.toByte(), 0xB8.toByte(), 0x80.toByte(),
            0x5A,
            0,
        )

        val actual = mutf8Nbt.decodeFromByteArray(NbtTag.serializer(), encoded)

        assertEquals(
            expected = buildNbtCompound("\u0000") {
                put("\u0000", text)
            },
            actual = actual,
        )
    }

    @Test
    fun Should_keep_using_UTF8_when_MUTF8_is_disabled() {
        val utf8Nbt = Nbt {
            variant = Java
            compression = NbtCompression.None
            mutf8 = false
        }
        val encoded = byteArrayOf(
            10,
            0, 1, 0,
            8,
            0, 1, 0,
            0, 5,
            0, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(),
            0,
        )

        val actual = utf8Nbt.decodeFromByteArray(NbtTag.serializer(), encoded)

        assertEquals(
            expected = buildNbtCompound("\u0000") {
                put("\u0000", "\u0000\uD83D\uDE00")
            },
            actual = actual,
        )
    }

    @Test
    fun Should_fail_to_decode_malformed_MUTF8() {
        val mutf8Nbt = Nbt {
            variant = Java
            compression = NbtCompression.None
            mutf8 = true
        }
        val encoded = byteArrayOf(
            10, 0, 0,
            8, 0, 0,
            0, 2, 0xC0.toByte(), 0x41,
            0,
        )

        assertFailsWith<NbtDecodingException> {
            mutf8Nbt.decodeFromByteArray(NbtTag.serializer(), encoded)
        }
    }

    @Test
    fun Should_decode_to_class_correctly() = parameterizeTest {
        val nbtFile by parameter(nbtFiles)

        assertEquals(
            expected = nbtFile.value,
            actual = nbtFile.asSource().use { source ->
                nbtFile.nbt.decodeFromSource(nbtFile.valueSerializer, source)
            },
        )
    }

    @Test
    fun Should_decode_to_NbtTag_correctly() = parameterizeTest {
        val nbtFile by parameter(nbtFiles)

        assertEquals(
            expected = nbtFile.nbtTag,
            actual = nbtFile.asSource().use { source ->
                nbtFile.nbt.decodeFromSource(NbtTag.serializer(), source)
            },
        )
    }

    @Test
    fun Should_not_read_more_from_source_than_necessary() = parameterizeTest {
        val nbtFile by parameter(nbtFiles)

        TestSource(nbtFile.asSource()).use { source ->
            nbtFile.nbt.decodeFromSource(NbtTag.serializer(), source)
            assertFalse(source.readPastEnd)
        }
    }

    @Test
    fun Should_not_close_source() = parameterizeTest {
        val nbtFile by parameter(nbtFiles)

        TestSource(nbtFile.asSource()).use { source ->
            nbtFile.nbt.decodeFromSource(NbtTag.serializer(), source)
            assertFalse(source.isClosed)
        }
    }

    @Test
    fun Should_fail_with_incorrect_NbtCompression_and_specify_mismatched_compressions() = parameterizeTest {
        val data = buildNbtCompound("root") {
            put("string", "String!")
        }

        val compressions = setOf(
            NbtCompression.None,
            NbtCompression.Gzip,
            NbtCompression.Zlib,
        )

        val configuredCompression by parameter(compressions)
        val fileCompression by parameter {
            compressions - configuredCompression
        }

        val decodingNbt = Nbt {
            variant = Java
            compression = configuredCompression
        }

        val encodingNbt = Nbt(decodingNbt) {
            compression = fileCompression
        }

        val encoded = encodingNbt.encodeToByteArray(NbtTag.serializer(), data)

        val error = assertFailsWith<NbtDecodingException> {
            decodingNbt.decodeFromByteArray(NbtTag.serializer(), encoded)
        }

        val errorMessage = error.message
        assertNotNull(errorMessage)
        assertContains(
            errorMessage,
            configuredCompression.toString(),
        )
        assertContains(
            errorMessage,
            fileCompression.toString(),
        )
    }
}
