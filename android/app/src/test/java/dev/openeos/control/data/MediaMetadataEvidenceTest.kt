package dev.openeos.control.data

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class MediaMetadataEvidenceTest {
    @Test fun normalizedIntegersRequireExactRepresentation() {
        for (allowed in listOf((0..5).toSet(), setOf(0, 90, 180, 270))) {
            allowed.forEach { value ->
                for (input in listOf(value, value.toLong(), value.toString(), BigInteger(value.toString()))) {
                    assertEquals(value, normalizedMediaMetadataInteger(input, allowed))
                }
                for (input in listOf(value.toDouble(), "$value.0", "${value}e0", "+$value", " $value", "$value ", "0$value")) {
                    assertNull("$input", normalizedMediaMetadataInteger(input, allowed))
                }
            }
            for (input in listOf(null, true, false, "０", "٣", "-0", 3.0000000000000000001,
                BigInteger("18446744073709551616"), mapOf("value" to 0), listOf(0))) {
                assertNull("$input", normalizedMediaMetadataInteger(input, allowed))
            }
        }
    }
    @Test fun flagsAcceptOnlyProfileSpecificEvidence() {
        assertEquals(true, normalizedMediaMetadataBoolean(true))
        assertEquals(false, normalizedMediaMetadataBoolean(false))
        assertEquals(true, canonicalMediaMetadataFlag("enable"))
        assertEquals(false, canonicalMediaMetadataFlag("disable"))
        listOf(null, 0, 1, "false", "true", "disable", "enable", emptyMap<String, String>()).forEach {
            assertNull(normalizedMediaMetadataBoolean(it))
        }
        listOf(null, true, false, 0, 1, "TRUE", "disabled", " disable").forEach {
            assertNull(canonicalMediaMetadataFlag(it))
        }
    }
    @Test fun canonicalRatingAndRotationDoNotCoerceTokens() {
        assertEquals(0, canonicalMediaMetadataRating("off"))
        (1..5).forEach { assertEquals(it, canonicalMediaMetadataRating(it.toString())) }
        listOf(null, 0, 1, "0", 3.0, "+3", "03", " 3", "３", false).forEach { assertNull(canonicalMediaMetadataRating(it)) }
        setOf(0, 90, 180, 270).forEach { assertEquals(it, canonicalMediaMetadataRotation(it.toString())) }
        listOf(null, 0, 90, 90.0, "90.0", "090", "+90", " 90", true).forEach { assertNull(canonicalMediaMetadataRotation(it)) }
    }
    @Test fun exactIdentityAndAllTargetValuesAreRequired() {
        val targetCases = listOf(
            MediaMetadataTarget(MediaMetadataField.PROTECTION, 0) to MediaMetadataReadback("a", protected = false),
            MediaMetadataTarget(MediaMetadataField.PROTECTION, 1) to MediaMetadataReadback("a", protected = true),
            MediaMetadataTarget(MediaMetadataField.ARCHIVE, 0) to MediaMetadataReadback("a", archived = false),
            MediaMetadataTarget(MediaMetadataField.ARCHIVE, 1) to MediaMetadataReadback("a", archived = true),
        ) + (0..5).map { MediaMetadataTarget(MediaMetadataField.RATING, it) to MediaMetadataReadback("a", rating = it) } +
            setOf(0, 90, 180, 270).map { MediaMetadataTarget(MediaMetadataField.ROTATION, it) to MediaMetadataReadback("a", rotationDegrees = it) }
        targetCases.forEach { (target, readback) ->
            assertTrue(target.matches("a", readback))
            assertFalse(target.matches("a", readback.copy(id = "A")))
            assertFalse(target.matches("a", MediaMetadataReadback("a")))
            assertFalse(target.matches("a ", readback))
            assertFalse(target.matches("é", readback.copy(id = "e\u0301")))
        }
    }
}
