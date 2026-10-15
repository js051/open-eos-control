package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class MaintenanceJsonTest {
    @Test fun hostileJsonCannotBecomeResponseProof() {
        val hostile = listOf("{\"version\":1,\"version\":1}", "{}{}", "{version:1}", "{\"a\":NaN}", "{\"a\":1,}", "[}", "{\"x\":\"\\uD800\"}")
        hostile.forEach { text -> assertTrue(text, runCatching { readShootingSettingResponseText(ByteArrayInputStream(text.toByteArray()), text.toByteArray().size.toLong()) }.isFailure) }
    }
    @Test fun invalidUtf8TruncationAndOversizeFailClosed() {
        for ((bytes, length) in listOf(byteArrayOf(0xC3.toByte(), 0x28) to 2L, "{}".toByteArray() to 3L,
            ("{\"x\":\"" + "x".repeat(65_536) + "\"}").toByteArray() to -1L)) {
            assertTrue(runCatching { readShootingSettingResponseText(ByteArrayInputStream(bytes), length) }.isFailure)
        }
    }
    @Test fun ambiguousFramingAndPartialSuccessAreRejected() {
        assertTrue(runCatching { requireShootingSettingHttpResponse(206, emptyList(), emptyList(), emptyList()) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, listOf("bytes 0-9/10"), emptyList(), emptyList()) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, emptyList(), listOf("2", "2"), emptyList()) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, emptyList(), listOf("2"), listOf("chunked")) }.isFailure)
        assertTrue(runCatching { requireShootingSettingHttpResponse(200, emptyList(), emptyList(), listOf("gzip")) }.isFailure)
    }
    @Test fun strictClockRecoveryDoesNotAcceptCoercedNumbersOrMissingNullableFields() {
        val base = mapOf<String, Any?>("epochSeconds" to 1_700_000_000, "utcOffsetMinutes" to 480, "daylightSaving" to false)
        assertNotNull(maintenanceClockObservation(base))
        for (bad in listOf<Any?>(true, 1_700_000_000.0, "1700000000", null)) assertNull(maintenanceClockObservation(base + ("epochSeconds" to bad)))
        assertNull(maintenanceClockObservation(base - "daylightSaving"))
        assertNull(maintenanceClockObservation(base + ("utcOffsetMinutes" to null)))
    }
    @Test fun reachableRequiresExactBoundEnvelopeAndNullClock() {
        val r = MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING)
        val body = mapOf<String, Any?>("version" to 1, "engine" to "ccapi", "operation" to "sensor-cleaning", "observationEvidence" to "reachable", "clockObservation" to null)
        assertNotNull(maintenanceBridgeReadback("ccapi", r, body))
        for (change in listOf("version" to 1.0, "engine" to "other", "operation" to "sleep", "observationEvidence" to "observed", "clockObservation" to emptyMap<String, Any?>())) {
            assertNull(maintenanceBridgeReadback("ccapi", r, body + change))
        }
        assertNull(maintenanceBridgeReadback("ccapi", r, body - "clockObservation"))
    }
    @Test fun requiredBatteryProbeNeverAcceptsMissingOrCoercedOptionalStatus() {
        assertTrue(validMaintenanceBatteryLevels(listOf(82)))
        assertTrue(validMaintenanceBatteryLevels(listOf("full", "low")))
        for (levels in listOf(emptyList(), listOf(null), listOf("82"), listOf(82.0), listOf(true), listOf(-1), listOf(101), List(33) { 82 })) {
            assertFalse(levels.toString(), validMaintenanceBatteryLevels(levels))
        }
    }
    @Test fun invalidReadFieldsDoNotEraseBoundResponseProof() {
        val r = MaintenanceRequest(MaintenanceAction.CLOCK_SYNC, MaintenanceClockTarget(1_700_000_000_000, 0, false))
        val e = MaintenanceEvidence(r); e.beforeDispatch()
        assertTrue(applyMaintenanceBridgeOutcome(e, "ccapi", 200, mapOf("version" to 1, "engine" to "ccapi", "operation" to "clock-sync",
            "autoPowerOff" to false, "responseEvidence" to "response-received", "clockReference" to "invalid", "clockReadEvidence" to "matched")))
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, e.snapshot().command)
        assertEquals(MaintenanceClockEvidence.UNAVAILABLE, e.snapshot().clockRead)
    }
    @Test fun eventStopRequiresExactEmpty204() {
        requireMaintenanceEmptyResponse(204, emptyList(), emptyList(), "")
        requireMaintenanceEmptyResponse(204, listOf("0"), emptyList(), "")
        for (status in listOf(200, 201, 202, 206, 302, 500))
            assertTrue(runCatching { requireMaintenanceEmptyResponse(status, emptyList(), emptyList(), "") }.isFailure)
        for ((lengths, encoding, body) in listOf<Triple<List<String>, List<String>, String>>(Triple(listOf("2"), emptyList(), "{}"), Triple(listOf("2"), emptyList(), ""),
            Triple(emptyList(), emptyList(), "{}"), Triple(listOf("0", "0"), emptyList(), ""), Triple(emptyList(), listOf("chunked"), "")))
            assertTrue(runCatching { requireMaintenanceEmptyResponse(204, lengths, encoding, body) }.isFailure)
    }

}
