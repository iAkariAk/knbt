package net.benwoodworth.knbt.internal

internal fun String.encodeToMUtf8ByteArray(): ByteArray {
    var byteCount = 0L
    for (char in this) {
        byteCount += when (char.code) {
            0 -> 2
            in 0x0001..0x007F -> 1
            in 0x0080..0x07FF -> 2
            else -> 3
        }
    }
    if (byteCount > Int.MAX_VALUE) {
        throw NbtEncodingException("String too long to encode")
    }

    val mutf8 = ByteArray(byteCount.toInt())
    var index = 0
    for (char in this) {
        val codeUnit = char.code
        when (codeUnit) {
            0 -> {
                mutf8[index++] = 0xC0.toByte()
                mutf8[index++] = 0x80.toByte()
            }

            in 0x0001..0x007F -> {
                mutf8[index++] = codeUnit.toByte()
            }

            in 0x0080..0x07FF -> {
                mutf8[index++] = (0xC0 or (codeUnit shr 6)).toByte()
                mutf8[index++] = (0x80 or (codeUnit and 0x3F)).toByte()
            }

            else -> {
                mutf8[index++] = (0xE0 or (codeUnit shr 12)).toByte()
                mutf8[index++] = (0x80 or ((codeUnit shr 6) and 0x3F)).toByte()
                mutf8[index++] = (0x80 or (codeUnit and 0x3F)).toByte()
            }
        }
    }

    return mutf8
}

internal fun ByteArray.decodeFromMUtf8ByteArray(): String {
    val result = StringBuilder(size)
    var index = 0

    fun continuationByteAt(continuationIndex: Int): Int {
        if (continuationIndex >= size) {
            throw NbtDecodingException("Truncated modified UTF-8 sequence at byte $index")
        }

        val byte = this[continuationIndex].toInt() and 0xFF
        if (byte and 0xC0 != 0x80) {
            throw NbtDecodingException("Malformed modified UTF-8 sequence at byte $index")
        }
        return byte
    }

    while (index < size) {
        val first = this[index].toInt() and 0xFF
        when {
            first and 0x80 == 0 -> {
                result.append(first.toChar())
                index++
            }

            first and 0xE0 == 0xC0 -> {
                val second = continuationByteAt(index + 1)
                result.append((((first and 0x1F) shl 6) or (second and 0x3F)).toChar())
                index += 2
            }

            first and 0xF0 == 0xE0 -> {
                val second = continuationByteAt(index + 1)
                val third = continuationByteAt(index + 2)
                result.append(
                    (((first and 0x0F) shl 12) or
                            ((second and 0x3F) shl 6) or
                            (third and 0x3F)).toChar()
                )
                index += 3
            }

            else -> throw NbtDecodingException("Malformed modified UTF-8 sequence at byte $index")
        }
    }

    return result.toString()
}
