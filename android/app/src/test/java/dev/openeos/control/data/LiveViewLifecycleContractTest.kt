package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

internal data class LiveViewLifecycleVector(val name: String, val input: Map<String, Any?>, val expected: Map<String, Any?>)

class LiveViewLifecycleContractTest {
    @Test fun all99SharedVectorsGateRealProductionEvidenceAndRetainedStopDuty() {
        val rows = loadLiveViewLifecycleFixture()
        assertEquals(99, rows.size)
        rows.forEach { row ->
            val i = row.input
            val actual = LiveViewLifecycleEvidence(
                sameOwner = i["sameOwner"] == true, sameAttempt = i["sameAttempt"] == true,
                cancelled = i["cancelled"] == true, retired = i["retired"] == true,
                foregroundRequired = i["foregroundRequired"] == true, foreground = i["foreground"] == true,
                startAdmitted = i["startAdmitted"] == true, startAcknowledged = i["startAcknowledged"] == true,
                stopEvidence = i["stopEvidence"] as? String, sourceMode = i["sourceMode"] as? String,
                terminal = i["terminal"] == true, frameEvidence = i["frameEvidence"] as? String,
            ).evaluate()
            assertEquals(row.name, row.expected["canPublishInputReady"], actual.canPublishInputReady)
            assertEquals(row.name, row.expected["canPublishDecoded"], actual.canPublishDecoded)
            assertEquals(row.name, row.expected["canPublishPresented"], actual.canPublishPresented)
            assertEquals(row.name, row.expected["canFallback"], actual.canFallback)
            assertEquals(row.name, row.expected["stopRequired"], actual.stopRequired)
        }
    }
}
