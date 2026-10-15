package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

internal data class NativeRtpMonitorVector(val name: String, val input: Map<String, Any?>, val expected: Map<String, Any?>)

class NativeRtpMonitorContractTest {
    @Test fun sharedVectorsGateTheProductionMonitorEvidence() {
        val rows = loadNativeRtpMonitorFixture()
        assertEquals(112, rows.size)
        rows.forEach { row ->
            val i = row.input
            fun integer(key: String): Long? = when (val n = i[key]) { is Int -> n.toLong(); is Long -> n; else -> null }
            fun number(key: String): Double? = (i[key] as? Number)?.toDouble()
            val actual = NativeRtpMonitorEvidence(
                active = i["active"] as? Boolean, sameOwner = i["sameOwner"] as? Boolean,
                sameRenderer = i["sameRenderer"] as? Boolean, foregroundRequired = i["foregroundRequired"] as? Boolean,
                foreground = i["foreground"] as? Boolean, terminal = i["terminal"] as? Boolean,
                stopPending = i["stopPending"] as? Boolean,
                sampleFrame = integer("sampleFrame"), currentFrame = integer("currentFrame"),
                sampleLut = integer("sampleLut"), currentLut = integer("currentLut"),
                sampleAgeMs = number("sampleAgeMs"), frameAgeMs = number("frameAgeMs"), maxAgeMs = number("maxAgeMs"),
                evidence = i["evidence"] as? String,
            ).evaluate()
            assertEquals(row.name, row.expected["canPublishDecodedAnalysis"], actual.canPublishDecodedAnalysis)
            assertEquals(row.name, row.expected["canPublishSubmittedAnalysis"], actual.canPublishSubmittedAnalysis)
            assertEquals(row.name, row.expected["canClaimPresentation"], actual.canClaimPresentation)
        }
    }
}
