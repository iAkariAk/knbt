package net.benwoodworth.knbt

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ModifiedUtf8JvmTest {
    private val nbt = Nbt {
        variant = NbtVariant.Java
        compression = NbtCompression.None
        mutf8 = true
    }

    private val text = "A\u0000\u007F\u0080\u07FF\u0800\u9999\uD83D\uDE00Z\uD800x\uDC00"

    private fun encodeNbtWithDataOutputStream(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(10)
            output.writeUTF(text)
            output.writeByte(8)
            output.writeUTF(text)
            output.writeUTF(text)
            output.writeByte(0)
        }
        return bytes.toByteArray()
    }

    private fun nbtTag(): NbtTag = buildNbtCompound(text) {
        put(text, text)
    }

    @Test
    fun Should_encode_strings_like_DataOutputStream_writeUTF() {
        assertContentEquals(
            expected = encodeNbtWithDataOutputStream(),
            actual = nbt.encodeToByteArray(NbtTag.serializer(), nbtTag()),
        )
    }

    @Test
    fun Should_decode_strings_written_by_DataOutputStream_writeUTF() {
        assertEquals(
            expected = nbtTag(),
            actual = nbt.decodeFromByteArray(NbtTag.serializer(), encodeNbtWithDataOutputStream()),
        )
    }
}
