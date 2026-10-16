package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.junit.Assert.*
import org.junit.Test

class ShootingSettingJsonTest {
    @Test fun rejectsAmbiguousOrIncompleteResponses() {
        listOf("{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{}{}", "{", "{\"x\":\"\\uD800\"}").forEach {
            assertTrue(it, runCatching { readShootingSettingResponseText(it.byteInputStream(), it.toByteArray().size.toLong()) }.isFailure)
        }
        assertTrue(runCatching { readShootingSettingResponseText("{}".byteInputStream(), 3) }.isFailure)
        assertTrue(runCatching { readShootingSettingResponseText(byteArrayOf(0xC3.toByte(), 0x28).inputStream(), 2) }.isFailure)
        assertTrue(runCatching { readShootingSettingResponseText("x".repeat(65537).byteInputStream(), 65537) }.isFailure)
        assertEquals("", readShootingSettingResponseText("".byteInputStream(), 0))
    }
    @Test fun rejectsPartialAndContradictoryHttpFraming() {
        assertTrue(runCatching { requireShootingSettingHttpResponse(206, emptyList(), emptyList(), emptyList()) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, listOf("bytes 0-1/3"), emptyList(), emptyList()) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, emptyList(), listOf("2", "3"), emptyList()) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, emptyList(), listOf("2"), listOf("chunked")) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, emptyList(), listOf("+2"), emptyList()) }.isFailure)
        requireShootingSettingHttpResponse(204, emptyList(), listOf("0"), emptyList())
    }
    @Test fun unicodeAndAdvertisedValueLimitsAreExact() {
        assertTrue(validShootingSettingText("🙂".repeat(512)))
        assertFalse(validShootingSettingText("🙂".repeat(513)))
        assertFalse(validShootingSettingText("\uD800")); assertFalse(validShootingSettingText("\n"))
        assertTrue(runCatching { ShootingSettingRequest("x".repeat(64), "") }.isSuccess)
        assertTrue(runCatching { ShootingSettingRequest("x".repeat(65), "") }.isFailure)
        assertTrue(runCatching { ShootingSettingRequest("ISO", "800") }.isFailure)
        assertFalse(acceptsShootingSettingObservation(CameraSettingControl("iso", "ISO", "800", listOf("400"))))
        assertFalse(acceptsShootingSettingObservation(CameraSettingControl("iso", "ISO", "", listOf(""))))
        assertFalse(acceptsShootingSettingObservation(CameraSettingControl("owner", "Owner", "", emptyList(), CameraSettingInputKind.TEXT, 256)))
    }
}
