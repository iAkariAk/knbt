package net.benwoodworth.knbt.internal

import net.benwoodworth.knbt.internal.CharSource.ReadResult
import net.benwoodworth.knbt.internal.CharSource.ReadResult.Companion.EOF
import net.benwoodworth.knbt.internal.NbtTagType.*
import okio.Closeable

private data class ParsedSnbtInteger(
    val type: NbtTagType,
    val value: Long,
)

private object SnbtIntegerParser {
    private val decimalMagnitude = Regex("""(?:0|[1-9][0-9]*(?:_+[0-9]+)*)""")
    private val hexadecimalMagnitude = Regex("""[0-9a-f]+(?:_+[0-9a-f]+)*""", RegexOption.IGNORE_CASE)
    private val binaryMagnitude = Regex("""[01]+(?:_+[01]+)*""")

    fun parse(value: String, defaultType: NbtTagType = TAG_Int): ParsedSnbtInteger? {
        if (value.isEmpty()) return null

        val signLength = if (value.first() == '+' || value.first() == '-') 1 else 0
        val radix = when {
            value.length > signLength + 2 && value.regionMatches(signLength, "0x", 0, 2, ignoreCase = true) -> 16
            value.length > signLength + 2 && value.regionMatches(signLength, "0b", 0, 2, ignoreCase = true) -> 2
            else -> 10
        }
        val prefixLength = if (radix == 10) 0 else 2

        val last = value.last().lowercaseChar()
        val explicitSign = value.getOrNull(value.lastIndex - 1)?.lowercaseChar().takeIf { it == 's' || it == 'u' }
        val hasTypeSuffix = last == 's' || last == 'i' || last == 'l' ||
            last == 'b' && (radix != 16 || explicitSign != null)
        val suffixLength = when {
            !hasTypeSuffix -> 0
            explicitSign != null -> 2
            else -> 1
        }

        val magnitudeStart = signLength + prefixLength
        val magnitudeEnd = value.length - suffixLength
        if (magnitudeStart >= magnitudeEnd) return null

        val magnitudeText = value.substring(magnitudeStart, magnitudeEnd)
        val validMagnitude = when (radix) {
            2 -> binaryMagnitude.matches(magnitudeText)
            10 -> decimalMagnitude.matches(magnitudeText)
            16 -> hexadecimalMagnitude.matches(magnitudeText)
            else -> error("Unsupported radix: $radix")
        }
        if (!validMagnitude) return null

        val magnitude = magnitudeText.replace("_", "").toULongOrNull(radix)
            ?: throw NbtDecodingException("Integer value is out of range: '$value'")
        val negative = signLength == 1 && value.first() == '-'
        val unsigned = when (explicitSign) {
            's' -> false
            'u' -> true
            else -> radix != 10
        }
        if (unsigned && negative) {
            throw NbtDecodingException("Unsigned integer cannot be negative: '$value'")
        }

        val type = when (last.takeIf { hasTypeSuffix }) {
            'b' -> TAG_Byte
            's' -> TAG_Short
            'l' -> TAG_Long
            'i' -> TAG_Int
            else -> defaultType
        }
        val bits = when (type) {
            TAG_Byte -> 8
            TAG_Short -> 16
            TAG_Int -> 32
            TAG_Long -> 64
            else -> error("Unsupported integer type: $type")
        }
        val maximumMagnitude = when {
            unsigned && bits == 64 -> ULong.MAX_VALUE
            unsigned -> (1UL shl bits) - 1UL
            negative -> 1UL shl (bits - 1)
            else -> (1UL shl (bits - 1)) - 1UL
        }
        if (magnitude > maximumMagnitude) {
            val signDescription = if (unsigned) "Unsigned" else "Signed"
            throw NbtDecodingException("$signDescription $type value is out of range: '$value'")
        }

        val signedValue = when {
            negative && bits == 64 && magnitude == (1UL shl 63) -> Long.MIN_VALUE
            negative -> -magnitude.toLong()
            type == TAG_Byte -> magnitude.toByte().toLong()
            type == TAG_Short -> magnitude.toShort().toLong()
            type == TAG_Int -> magnitude.toInt().toLong()
            else -> magnitude.toLong()
        }
        return ParsedSnbtInteger(type, signedValue)
    }
}

internal class StringifiedNbtReader(val source: CharSource) : NbtReader, Closeable {
    private companion object {
        // TODO https://youtrack.jetbrains.com/issue/KT-49065
        // val DOUBLE = Regex("""[-+]?(?:[0-9]+\.?|[0-9]*\.[0-9]+)(?:e[-+]?[0-9]+)?d?""", RegexOption.IGNORE_CASE)
        val DOUBLE_A = Regex(
            """[-+]?[0-9]+(?:_+[0-9]+)*(?:\.(?:[0-9]+(?:_+[0-9]+)*)?(?:e[-+]?[0-9]+(?:_+[0-9]+)*)?d?|e[-+]?[0-9]+(?:_+[0-9]+)*d?|d)""",
            RegexOption.IGNORE_CASE,
        )
        val DOUBLE_B = Regex(
            """[-+]?(?:[0-9]+(?:_+[0-9]+)*)?\.[0-9]+(?:_+[0-9]+)*(?:e[-+]?[0-9]+(?:_+[0-9]+)*)?d?""",
            RegexOption.IGNORE_CASE,
        )

        // val FLOAT = Regex("""[-+]?(?:[0-9]+\.?|[0-9]*\.[0-9]+)(?:e[-+]?[0-9]+)?f""", RegexOption.IGNORE_CASE)
        val FLOAT_A = Regex(
            """[-+]?[0-9]+(?:_+[0-9]+)*\.?(?:e[-+]?[0-9]+(?:_+[0-9]+)*)?f""",
            RegexOption.IGNORE_CASE,
        )
        val FLOAT_B = Regex(
            """[-+]?(?:[0-9]+(?:_+[0-9]+)*)?\.[0-9]+(?:_+[0-9]+)*(?:e[-+]?[0-9]+(?:_+[0-9]+)*)?f""",
            RegexOption.IGNORE_CASE,
        )

        fun ReadResult.isUnquotedStringCharacter(): Boolean =
            this != EOF && toChar().let {
                it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' || it == '-' || it == '.' || it == '+'
            }

    }

    private val buffer = StringBuilder()
    private val firstEntryStack = mutableListOf<Boolean>()
    private val arrayTypeStack = mutableListOf<NbtTagType>()

    override fun close(): Unit = source.close()

    private fun ReadResult.isWhitespace(): Boolean =
        this != EOF && toChar().isWhitespace()

    private fun CharSource.skipWhitespace(): CharSource {
        while (peek().read().isWhitespace()) {
            read()
        }
        return this
    }

    private fun CharSource.bufferUnquotedString() {
        buffer.clear()

        while (peek().read().isUnquotedStringCharacter()) {
            buffer.append(read().toChar())
        }
    }

    private fun CharSource.bufferQuotedString() {
        buffer.clear()

        val quote = ReadResult(read().toChar())
        val backslash = ReadResult('\\')

        while (true) {
            when (val char = read()) {
                EOF -> throw NbtDecodingException("Unexpected EOF in String")
                quote -> break
                backslash -> when (val esc = read()) {
                    EOF -> throw NbtDecodingException("Unexpected EOF in String")
                    ReadResult('\''), ReadResult('"'), backslash -> buffer.append(esc)
                    ReadResult('b') -> buffer.append('\b')
                    ReadResult('s') -> buffer.append(' ')
                    ReadResult('t') -> buffer.append('\t')
                    ReadResult('n') -> buffer.append('\n')
                    ReadResult('f') -> buffer.append('\u000c')
                    ReadResult('r') -> buffer.append('\r')
                    ReadResult('x') -> buffer.appendUnicodeCodePoint(readHexEscape(2))
                    ReadResult('u') -> buffer.appendUnicodeCodePoint(readHexEscape(4))
                    ReadResult('U') -> buffer.appendUnicodeCodePoint(readHexEscape(8))
                    ReadResult('N') -> buffer.appendUnicodeCodePoint(readNamedUnicodeEscape())
                    else -> throw NbtDecodingException("Invalid escape: \\$esc")
                }
                else -> buffer.append(char.toChar())
            }
        }
    }

    private fun CharSource.readHexEscape(length: Int): Int {
        var value = 0u

        repeat(length) {
            val char = read()
            if (char == EOF) throw NbtDecodingException("Unexpected EOF in Unicode escape")
            val digit = char.toChar().digitToIntOrNull(16)
                ?: throw NbtDecodingException("Invalid hexadecimal digit '${char.toChar()}' in Unicode escape")
            value = value * 16u + digit.toUInt()
        }

        if (value > 0x10ffffu) {
            throw NbtDecodingException("Unicode code point is out of range: ${value.toString(16)}")
        }
        return value.toInt()
    }

    private fun CharSource.readNamedUnicodeEscape(): Int {
        expect('{')
        val name = buildString {
            while (true) {
                when (val char = read()) {
                    EOF -> throw NbtDecodingException("Unexpected EOF in named Unicode escape")
                    ReadResult('}') -> break
                    else -> append(char.toChar())
                }
            }
        }
        if (name.isEmpty()) throw NbtDecodingException("Unicode character name cannot be empty")
        return unicodeCodePointByName(name)
            ?: throw NbtDecodingException("Unknown Unicode character name: '$name'")
    }

    private fun StringBuilder.appendUnicodeCodePoint(codePoint: Int) {
        if (codePoint <= 0xffff) {
            append(codePoint.toChar())
        } else {
            val supplementary = codePoint - 0x10000
            append(((supplementary ushr 10) + 0xd800).toChar())
            append(((supplementary and 0x3ff) + 0xdc00).toChar())
        }
    }

    private fun CharSource.expect(char: Char, ignoreCase: Boolean = false) {
        val actual = read()
        if (actual == EOF) {
            throw NbtDecodingException("Expected '$char', but was EOF")
        } else if (!actual.toChar().equals(char, ignoreCase)) {
            throw NbtDecodingException("Expected '$char', but was '$actual'")
        }
    }

    private fun CharSource.peekTagType(): NbtTagType? {
        val peek = peek()
        return when (peek.read()) {
            EOF -> null
            ReadResult('[') -> when (peek.skipWhitespace().read()) {
                ReadResult('B') -> if (peek.skipWhitespace().read() == ReadResult(';')) TAG_Byte_Array else TAG_List
                ReadResult('I') -> if (peek.skipWhitespace().read() == ReadResult(';')) TAG_Int_Array else TAG_List
                ReadResult('L') -> if (peek.skipWhitespace().read() == ReadResult(';')) TAG_Long_Array else TAG_List
                else -> TAG_List
            }
            ReadResult('{') -> TAG_Compound
            ReadResult('\''), ReadResult('"') -> TAG_String
            else -> {
                peek().bufferUnquotedString()
                val integer = SnbtIntegerParser.parse(buffer.toString())
                when {
                    buffer.isEmpty() -> null
                    FLOAT_A.matches(buffer) -> TAG_Float
                    FLOAT_B.matches(buffer) -> TAG_Float
                    integer != null -> integer.type
                    DOUBLE_A.matches(buffer) -> TAG_Double
                    DOUBLE_B.matches(buffer) -> TAG_Double
                    "true".contentEquals(buffer, true) -> TAG_Byte
                    "false".contentEquals(buffer, true) -> TAG_Byte
                    else -> TAG_String
                }
            }
        }
    }

    private fun CharSource.readSnbtString(): String? =
        when (skipWhitespace().peek().read()) {
            EOF -> throw NbtDecodingException("Expected String, but was EOF")
            ReadResult('"'), ReadResult('\'') -> {
                bufferQuotedString()
                buffer.toString()
            }
            else -> {
                bufferUnquotedString()
                buffer.takeUnless { it.isEmpty() }?.toString()
            }
        }

    override fun beginRootTag(): NbtReader.RootTagInfo =
        NbtReader.RootTagInfo(
            source.skipWhitespace().peekTagType()
                ?: throw NbtDecodingException("Expected value, but got nothing")
        )

    override fun beginCompound() {
        source.skipWhitespace().expect('{')
        firstEntryStack.add(true)
    }

    override fun beginCompoundEntry(): NbtReader.CompoundEntryInfo {
        source.skipWhitespace()

        return if (source.peek().read() == ReadResult('}')) {
            NbtReader.CompoundEntryInfo.End
        } else {
            if (firstEntryStack.last()) {
                firstEntryStack[firstEntryStack.lastIndex] = false
            } else {
                val char = source.read()
                if (char != ReadResult(',')) throw NbtDecodingException("Expected ',' or '}', but got '$char'")
                source.skipWhitespace()
                if (source.peek().read() == ReadResult('}')) {
                    return NbtReader.CompoundEntryInfo.End
                }
            }

            val name = source.skipWhitespace().readSnbtString()
                ?: throw NbtDecodingException("Expected key but got nothing")

            source.skipWhitespace().expect(':')

            val type = source.skipWhitespace().peekTagType()
                ?: throw NbtDecodingException("Expected value but got nothing")

            return NbtReader.CompoundEntryInfo(type, name)
        }
    }

    override fun endCompound() {
        source.expect('}')
        firstEntryStack.removeLast()
    }

    private fun beginArray(type: Char, elementType: NbtTagType): NbtReader.ArrayInfo {
        source.skipWhitespace().expect('[')
        source.skipWhitespace().expect(type, true)
        source.skipWhitespace().expect(';')

        firstEntryStack.add(true)
        arrayTypeStack.add(elementType)

        val empty = source.skipWhitespace().peek().read() == ReadResult(']')
        val size = if (empty) 0 else NbtReader.UNKNOWN_SIZE

        return NbtReader.ArrayInfo(size)
    }

    private fun beginCollectionEntry(allowTrailingComma: Boolean): Boolean {
        source.skipWhitespace()

        return if (source.peek().read() == ReadResult(']')) {
            false
        } else {
            if (firstEntryStack.last()) {
                firstEntryStack[firstEntryStack.lastIndex] = false
            } else {
                val char = source.read()
                if (char != ReadResult(',')) throw NbtDecodingException("Expected ',' or ']', but got '$char'")
                if (allowTrailingComma) {
                    source.skipWhitespace()
                    if (source.peek().read() == ReadResult(']')) return false
                }
            }
            true
        }
    }

    private fun endCollection() {
        source.skipWhitespace().expect(']')
        firstEntryStack.removeLast()
    }

    override fun beginList(): NbtReader.ListInfo {
        source.skipWhitespace().expect('[')
        source.skipWhitespace()

        firstEntryStack.add(true)

        val type = source.peekTagType() ?: TAG_End
        val size = if (type == TAG_End) 0 else NbtReader.UNKNOWN_SIZE

        return NbtReader.ListInfo(type, size)
    }

    override fun beginListEntry(): Boolean =
        beginCollectionEntry(allowTrailingComma = true)

    override fun endList(): Unit =
        endCollection()

    override fun beginByteArray(): NbtReader.ArrayInfo =
        beginArray('B', TAG_Byte)

    override fun beginByteArrayEntry(): Boolean =
        beginCollectionEntry(allowTrailingComma = false)

    override fun endByteArray() {
        endCollection()
        arrayTypeStack.removeLast()
    }

    override fun beginIntArray(): NbtReader.ArrayInfo =
        beginArray('I', TAG_Int)

    override fun beginIntArrayEntry(): Boolean =
        beginCollectionEntry(allowTrailingComma = false)

    override fun endIntArray() {
        endCollection()
        arrayTypeStack.removeLast()
    }

    override fun beginLongArray(): NbtReader.ArrayInfo =
        beginArray('L', TAG_Long)

    override fun beginLongArrayEntry(): Boolean =
        beginCollectionEntry(allowTrailingComma = false)

    override fun endLongArray() {
        endCollection()
        arrayTypeStack.removeLast()
    }

    override fun readByte(): Byte {
        source.skipWhitespace().bufferUnquotedString()

        return when {
            buffer.contentEquals("true", true) -> 1
            buffer.contentEquals("false", true) -> 0
            else -> {
                val integer = SnbtIntegerParser.parse(buffer.toString(), arrayTypeStack.lastOrNull() ?: TAG_Int)
                if (integer?.type != TAG_Byte) throw NbtDecodingException("Expected Byte, but was '$buffer'")
                integer.value.toByte()
            }
        }
    }

    override fun readShort(): Short {
        source.skipWhitespace().bufferUnquotedString()

        val integer = SnbtIntegerParser.parse(buffer.toString())
        if (integer?.type != TAG_Short) throw NbtDecodingException("Expected Short, but was '$buffer'")
        return integer.value.toShort()
    }

    override fun readInt(): Int {
        source.skipWhitespace().bufferUnquotedString()

        val integer = SnbtIntegerParser.parse(buffer.toString(), arrayTypeStack.lastOrNull() ?: TAG_Int)
        val accepted = integer?.type == TAG_Int ||
            arrayTypeStack.lastOrNull() == TAG_Int && (integer?.type == TAG_Byte || integer?.type == TAG_Short)
        if (!accepted) {
            throw NbtDecodingException("Expected Int, but was '$buffer'")
        }
        return integer.value.toInt()
    }

    override fun readLong(): Long {
        source.skipWhitespace().bufferUnquotedString()

        val integer = SnbtIntegerParser.parse(buffer.toString(), arrayTypeStack.lastOrNull() ?: TAG_Int)
        val accepted = integer?.type == TAG_Long || arrayTypeStack.lastOrNull() == TAG_Long &&
            (integer?.type == TAG_Byte || integer?.type == TAG_Short || integer?.type == TAG_Int)
        if (!accepted) {
            throw NbtDecodingException("Expected Long, but was '$buffer'")
        }
        return integer.value
    }

    override fun readFloat(): Float {
        source.skipWhitespace().bufferUnquotedString()

        if (!FLOAT_A.matches(buffer) && !FLOAT_B.matches(buffer)) {
            throw NbtDecodingException("Expected Float, but was '$buffer'")
        }
        buffer.setLength(buffer.length - 1)
        val value = buffer.toString().replace("_", "").toFloat()
        // Kotlin/JS Float values retain JS Number precision, so force Float32 rounding when checking for overflow.
        val float32Value = Float.fromBits(value.toRawBits())
        if (!float32Value.isFinite()) throw NbtDecodingException("Float value is not finite: '$buffer'")
        return value
    }

    override fun readDouble(): Double {
        source.skipWhitespace().bufferUnquotedString()

        if (!DOUBLE_A.matches(buffer) && !DOUBLE_B.matches(buffer)) {
            throw NbtDecodingException("Expected Double, but was '$buffer'")
        }
        if (buffer.last().equals('d', true)) buffer.setLength(buffer.length - 1)
        val value = buffer.toString().replace("_", "").toDouble()
        if (!value.isFinite()) throw NbtDecodingException("Double value is not finite: '$buffer'")
        return value
    }

    override fun readString(): String =
        source.readSnbtString() ?: throw NbtDecodingException("Expected String but got nothing")
}
