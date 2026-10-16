package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

internal data class RecordingObservationVector(val name: String, val raw: Map<String, Any?>, val valid: Boolean)
internal data class RecordingOwnerVector(val name: String, val input: Map<String, Any?>, val expected: Map<String, Any?>)
internal data class RecordingSharedFixture(val observations: List<RecordingObservationVector>, val ownership: List<RecordingOwnerVector>)

class RecordingLifecycleContractTest {
    @Test fun allSharedObservationVectorsUseExactTypesAndMatchingSourceEngine() {
        val rows = loadRecordingFixture().observations
        assertTrue(rows.size >= 60)
        rows.forEach { row -> assertEquals(row.name, row.valid,
            recordingWireObservation(row.raw, row.raw["engine"] as? String ?: "ccapi") != null) }
    }
    @Test fun allSharedOwnersPreserveSafetyDutyWhileGatingLatePublicationAndReview() {
        val rows = loadRecordingFixture().ownership
        assertTrue(rows.size >= 80)
        rows.forEach { row ->
            val i = row.input
            val observation = if (i["observationEvidence"] == "observed") RecordingObservation(i["recording"] as? Boolean,
                1, RecordingObservationSource.BRIDGE_STATUS) else null
            val receipt = RecordingReceipt(RecordingCommand.valueOf((i["operation"] as String).uppercase()),
                when (i["responseEvidence"]) {
                    "response-received" -> RecordingResponseEvidence.RESPONSE_RECEIVED
                    "unconfirmed" -> RecordingResponseEvidence.UNKNOWN
                    "rejected" -> RecordingResponseEvidence.REJECTED
                    else -> RecordingResponseEvidence.NOT_REQUIRED
                }, observation, i["retired"] as Boolean, i["cancelled"] as Boolean,
                i["sameOwner"] as Boolean, i["priorStopRequired"] as Boolean, i["sameAttempt"] as Boolean)
            assertEquals(row.name, row.expected["canPublish"], receipt.canPublish)
            assertEquals(row.name, row.expected["stopRequired"], receipt.stopRequired)
            assertEquals(row.name, row.expected["canReview"], receipt.mayReviewStoppedVideo)
            // No receipt grants automatic Start replay; only a new deliberate user action can create it.
            assertEquals(row.name, false, row.expected["retryStart"])
        }
    }
}
