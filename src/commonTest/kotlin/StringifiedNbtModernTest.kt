package net.benwoodworth.knbt

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.LongArraySerializer
import net.benwoodworth.knbt.internal.NbtDecodingException
import net.benwoodworth.knbt.internal.NbtEncodingException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StringifiedNbtModernTest {
    @Test
    fun decodesHexadecimalInteger() {
        assertEquals(
            NbtInt(2989),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "0xbad"),
        )
        assertEquals(
            NbtInt(51966),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "0xCAFE"),
        )
        assertEquals(
            NbtInt(-1),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "0xFFFFFFFF"),
        )
    }

    @Test
    fun decodesBinaryInteger() {
        assertEquals(
            NbtInt(5),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "0b101"),
        )
    }

    @Test
    fun decodesNumericSeparatorsBetweenDigits() {
        val examples = mapOf(
            "2_2" to NbtInt(22),
            "0b10_01" to NbtInt(9),
            "0xAB_CD" to NbtInt(43981),
            "1_2.3_4__5f" to NbtFloat(12.345f),
            "1_2e3_4" to NbtDouble(12e34),
        )

        examples.forEach { (snbt, expected) ->
            assertEquals(
                expected,
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt),
                snbt,
            )
        }
    }

    @Test
    fun decodesModernFloatingPointForms() {
        val examples = mapOf(
            ".1" to NbtDouble(0.1),
            "1." to NbtDouble(1.0),
            "1.2e3" to NbtDouble(1200.0),
            "1.2E+3" to NbtDouble(1200.0),
            "12000e-1" to NbtDouble(1200.0),
        )

        examples.forEach { (snbt, expected) ->
            assertEquals(expected, StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt), snbt)
        }
    }

    @Test
    fun rejectsFloatingPointLiteralsThatOverflowToInfinity() {
        listOf("3.5e38f", "1e309").forEach { snbt ->
            assertFailsWith<NbtDecodingException>(snbt) {
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt)
            }
        }
    }

    @Test
    fun rejectsEncodingUnsupportedNonFiniteNumbers() {
        listOf(
            NbtFloat(Float.NaN),
            NbtFloat(Float.POSITIVE_INFINITY),
            NbtFloat(Float.NEGATIVE_INFINITY),
            NbtDouble(Double.NaN),
            NbtDouble(Double.POSITIVE_INFINITY),
            NbtDouble(Double.NEGATIVE_INFINITY),
        ).forEach { tag ->
            assertFailsWith<NbtEncodingException>(tag.toString()) {
                StringifiedNbt.encodeToString(NbtTag.serializer(), tag)
            }
        }
    }

    @Test
    fun decodesExplicitSignedAndUnsignedBytes() {
        assertEquals(
            NbtByte((-16).toByte()),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "240ub"),
        )
        assertEquals(
            NbtByte((-16).toByte()),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "-16sb"),
        )
    }

    @Test
    fun decodesExtendedIntegerSuffixesAndUnsignedRanges() {
        val examples = mapOf(
            "0x11ub" to NbtByte(17),
            "65535us" to NbtShort((-1).toShort()),
            "1si" to NbtInt(1),
            "4294967295ui" to NbtInt(-1),
            "18446744073709551615ul" to NbtLong(-1),
        )

        examples.forEach { (snbt, expected) ->
            assertEquals(
                expected,
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt),
                snbt,
            )
        }
    }

    @Test
    fun decodesSignedAndUnsignedIntegerBoundaries() {
        val examples = mapOf(
            "-128sb" to NbtByte(Byte.MIN_VALUE),
            "127sb" to NbtByte(Byte.MAX_VALUE),
            "255ub" to NbtByte((-1).toByte()),
            "-32768ss" to NbtShort(Short.MIN_VALUE),
            "32767ss" to NbtShort(Short.MAX_VALUE),
            "65535us" to NbtShort((-1).toShort()),
            "-2147483648si" to NbtInt(Int.MIN_VALUE),
            "2147483647si" to NbtInt(Int.MAX_VALUE),
            "4294967295ui" to NbtInt(-1),
            "-9223372036854775808sl" to NbtLong(Long.MIN_VALUE),
            "9223372036854775807sl" to NbtLong(Long.MAX_VALUE),
            "18446744073709551615ul" to NbtLong(-1L),
        )

        examples.forEach { (snbt, expected) ->
            assertEquals(expected, StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt), snbt)
        }
    }

    @Test
    fun hexadecimalAndBinaryIntegersDefaultToUnsignedRanges() {
        val examples = mapOf(
            "0x11b" to NbtInt(283),
            "0xffub" to NbtByte((-1).toByte()),
            "0b11111111ub" to NbtByte((-1).toByte()),
            "0xffffus" to NbtShort((-1).toShort()),
            "0xffffffffui" to NbtInt(-1),
            "0xfffffffffffffffful" to NbtLong(-1L),
        )

        examples.forEach { (snbt, expected) ->
            assertEquals(expected, StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt), snbt)
        }
    }

    @Test
    fun rejectsLexicallyValidIntegersOutsideTheirDeclaredRange() {
        listOf("240sb", "-1ub", "2147483648", "18446744073709551616ul").forEach { snbt ->
            assertFailsWith<NbtDecodingException>(snbt) {
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt)
            }
        }
    }

    @Test
    fun decodesBuiltInAndNumericStringEscapes() {
        assertEquals(
            "\b \t\n\u000c\rB\u2603😀",
            StringifiedNbt.decodeFromString(
                NbtTag.serializer(),
                "\"\\b\\s\\t\\n\\f\\r\\x42\\u2603\\U0001F600\"",
            ).nbtString.value,
        )
    }

    @Test
    fun rejectsMalformedStringEscapes() {
        listOf(
            "\"\\q\"",
            "\"\\x4\"",
            "\"\\U00110000\"",
            "\"\\N{}\"",
            "\"\\N{not a character}\"",
        ).forEach { snbt ->
            assertFailsWith<NbtDecodingException>(snbt) {
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt)
            }
        }
    }

    @Test
    fun decodesNamedUnicodeEscape() {
        assertEquals(
            NbtString("☃"),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "\"\\N{Snowman}\""),
        )
        assertEquals(
            NbtString("😀"),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "\"\\N{grinning face}\""),
        )
        assertEquals(
            NbtString("一"),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "\"\\N{CJK UNIFIED IDEOGRAPHS 4E00}\""),
        )
    }

    @Test
    fun decodesUnicodeEscapeBoundariesAndNormalizedNames() {
        assertEquals(
            NbtString("\u0000\uDBFF\uDFFF"),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "\"\\x00\\U0010FFFF\""),
        )
        assertEquals(
            NbtString("😀"),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "\"\\N{ GRINNING FACE }\""),
        )
    }

    @Test
    fun decodesEitherQuoteAsAnEscapeInQuotedStrings() {
        assertEquals(
            NbtString("'"),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "\"\\'\""),
        )
        assertEquals(
            NbtString("\""),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "'\\\"'"),
        )
    }

    @Test
    fun numericArrayElementsInheritTheArrayTypeWithoutSuffixes() {
        assertEquals(
            NbtByteArray(byteArrayOf(1, 2)),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "[B;1,2]"),
        )
        assertContentEquals(
            byteArrayOf(1, 2),
            StringifiedNbt.decodeFromString(ByteArraySerializer(), "[B;1,2]"),
        )
    }

    @Test
    fun numericArrayElementsRespectInheritedTypeBoundaries() {
        val examples = mapOf(
            "[B;0xff]" to NbtByteArray(byteArrayOf(-1)),
            "[I;0xffffffff]" to NbtIntArray(intArrayOf(-1)),
            "[L;0xffffffffffffffff]" to NbtLongArray(longArrayOf(-1L)),
        )

        examples.forEach { (snbt, expected) ->
            assertEquals(expected, StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt), snbt)
        }

        assertFailsWith<NbtDecodingException> {
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "[B;128]")
        }
    }

    @Test
    fun numericArraysWidenSmallerIntegerTypes() {
        assertEquals(
            NbtIntArray(intArrayOf(1, 2, 3)),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "[I;1b,2s,3]"),
        )
        assertContentEquals(
            intArrayOf(1, 2, 3),
            StringifiedNbt.decodeFromString(IntArraySerializer(), "[I;1b,2s,3]"),
        )
        assertEquals(
            NbtLongArray(longArrayOf(1, 2, 3, 4)),
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "[L;1b,2s,3i,4]"),
        )
        assertContentEquals(
            longArrayOf(1, 2, 3, 4),
            StringifiedNbt.decodeFromString(LongArraySerializer(), "[L;1b,2s,3i,4]"),
        )
    }

    @Test
    fun numericArraysRejectLargerIntegerTypes() {
        listOf("[B;1s]", "[I;1l]").forEach { snbt ->
            assertFailsWith<NbtDecodingException>(snbt) {
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt)
            }
        }
    }

    @Test
    fun listsAndCompoundsAcceptASingleTrailingComma() {
        assertEquals(
            buildNbtList<NbtInt> { add(1); add(2) },
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "[1,2,]"),
        )
        assertEquals(
            buildNbtCompound { put("a", "b") },
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "{a:b,}"),
        )
    }

    @Test
    fun collectionsRejectMissingElementsAndRepeatedTrailingCommas() {
        listOf("[,]", "[1,,]", "{,}", "{a:b,,}", "[B;1b,]").forEach { snbt ->
            assertFailsWith<NbtDecodingException>(snbt) {
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt)
            }
        }
    }

    @Test
    fun trailingCommaRulesApplyAtEveryNestingLevel() {
        val expected = NbtList(
            listOf(
                NbtCompound(
                    mapOf("a" to NbtList(listOf(NbtInt(1), NbtInt(2)))),
                ),
            ),
        )

        assertEquals(
            expected,
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "[{a:[1,2,],},]"),
        )
        assertFailsWith<NbtDecodingException> {
            StringifiedNbt.decodeFromString(NbtTag.serializer(), "[{a:[1,2,,],},]")
        }
    }

    @Test
    fun encodingQuotesCompoundKeysThatCollideWithModernNumbers() {
        val tag = buildNbtCompound {
            put("1234", 1)
            put(".key", 2)
            put("+key", 3)
            put("-key", 2)
            put("alpha-1", 5)
        }

        assertEquals(
            "{\"1234\":1,\".key\":2,\"+key\":3,\"-key\":2,alpha-1:5}",
            StringifiedNbt.encodeToString(NbtTag.serializer(), tag),
        )
    }
}
