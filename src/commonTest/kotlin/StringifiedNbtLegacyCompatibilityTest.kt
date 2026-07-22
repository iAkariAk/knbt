package net.benwoodworth.knbt

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class StringifiedNbtLegacyCompatibilityTest {
    @Test
    fun invalidNumericLiteralsRemainUnquotedStrings() {
        listOf("9_", "2x", "01", "0x_1").forEach { snbt ->
            assertEquals(
                NbtString(snbt),
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt),
                snbt,
            )
        }
    }

    @Test
    fun misplacedNumericSeparatorsRemainUnquotedStrings() {
        listOf(
            "_1",
            "1_",
            "1_.0",
            "1._0",
            "1_e2",
            "1e_2",
            "0b_1",
            "0b1_",
            "0x_1",
            "0x1_",
        ).forEach { snbt ->
            assertEquals(
                NbtString(snbt),
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt),
                snbt,
            )
        }
    }

    @Test
    fun unsupportedModernNumericFormsRemainUnquotedStrings() {
        listOf("NaN", "Inf", "+Inf", "-Inf", "0x1.0p0").forEach { snbt ->
            assertEquals(
                NbtString(snbt),
                StringifiedNbt.decodeFromString(NbtTag.serializer(), snbt),
                snbt,
            )
        }
    }

    @Test
    fun encodingKeepsLegacySafeCompoundKeysUnquoted() {
        val tag = buildNbtCompound {
            put("alpha", 1)
            put("_key", 2)
        }

        assertEquals(
            "{alpha:1,_key:2}",
            StringifiedNbt.encodeToString(NbtTag.serializer(), tag),
        )
    }
}
