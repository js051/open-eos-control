package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.junit.Assert.*
import org.junit.Test

class MediaUploadContractTest {
    private val receipt = CameraMediaUploadResult(CameraMediaItem("op-receipt", "SAME.JPG", "image", 1234), 1234)
    private fun MediaUploadStatus.label() = name.lowercase().replace('_', '-')
    private fun camera() = CameraInfo(true, "Synthetic", "SYNTHETIC-UPLOAD", "fixture")

    @Test fun all48OutcomesUseOnlyDispatchAndTheSameOperationReceipt() {
        val rows = loadMediaUploadFixture().outcomes
        assertEquals(48, rows.size)
        for (row in rows) {
            val attempt = MediaUploadAttempt()
            if (row.dispatched) attempt.beforeDispatch()
            if (row.receipt) attempt.confirm(receipt)
            if (row.cancelled) attempt.cancel()
            // Every inventory observation intentionally has no path into operation evidence.
            assertEquals("${row.id}: ${row.observation}", row.expected, attempt.finish().label())
        }
    }
    @Test fun all12AdmissionsConsumeTheRealPickerOwnerBeforeUploadAdmission() {
        val rows = loadMediaUploadFixture().admissions
        assertEquals(12, rows.size)
        for (row in rows) {
            val requests = CameraMediaPickerRequests()
            val owner = camera()
            val token = requireNotNull(requests.begin(CameraMediaPickerKind.UPLOAD, 1, owner))
            if (!row.available) requests.consume(CameraMediaPickerKind.UPLOAD, token, 1, owner)
            val currentOwner = when(row.currentOwner) { null -> null; row.owner -> owner; else -> camera() }
            val currentToken = when(row.currentToken) { null -> null; row.token -> token; else -> "another-picker" }
            val ready = if (!row.selected) { requests.cancel(CameraMediaPickerKind.UPLOAD, token); false }
                else requests.consume(CameraMediaPickerKind.UPLOAD, currentToken, 1, currentOwner) is CameraMediaPickerRequests.Result.Ready
            assertEquals(row.id, row.expected, ready && canStartMediaUpload(false, row.supported, row.busy, false, false))
        }
    }
    @Test fun all20CallbacksUseReferenceOwnerAndAttemptFences() {
        val rows = loadMediaUploadFixture().publications
        assertEquals(20, rows.size)
        for (row in rows) {
            val owner = Any(); val token = Any()
            val currentOwner = when(row.currentOwner) { null -> null; row.owner -> owner; else -> Any() }
            val currentToken = when(row.currentToken) { null -> null; row.token -> token; else -> Any() }
            assertEquals(row.id, row.expected, ownsMediaUpload(token, currentToken, owner, currentOwner, 1, 1))
        }
    }
    @Test fun all48ReceiptVectorsUseStrictGrammarAndTypedOperationReceiptRules() {
        val rows = loadMediaUploadFixture().receipts
        assertEquals(48, rows.size)
        for (row in rows) {
            val verified = runCatching {
                val strict = StrictMediaJson(row.raw).also { it.validate() }
                val value = decodeUploadFixtureReceipt(row.raw)
                row.bound && validUploadReceipt(row.status, row.name, row.size, row.size,
                    value["id"], value["name"], value["sizeBytes"], value["kind"], strict.topLevelToken("sizeBytes"))
            }.getOrDefault(false)
            assertEquals(row.id, row.expected, verified)
        }
    }
    @Test fun all14JourneysPreservePickerConsumptionAttemptEvidenceAndPublicationOwnership() {
        val rows = loadMediaUploadFixture().journeys
        assertEquals(14, rows.size)
        for (row in rows) {
            val requests = CameraMediaPickerRequests()
            val owner = camera(); var currentOwner: CameraInfo? = owner
            val attempt = MediaUploadAttempt(); var picker: String? = null; var admitted = false; var dispatches = 0
            for (event in row.events) when(event) {
                "open" -> picker = requests.begin(CameraMediaPickerKind.UPLOAD, 1, owner)
                "picker-cancel" -> requests.cancel(CameraMediaPickerKind.UPLOAD, picker)
                "picker-result", "repeated-picker-result" -> {
                    val ready = requests.consume(CameraMediaPickerKind.UPLOAD, picker, 1, currentOwner) is CameraMediaPickerRequests.Result.Ready
                    if (event == "repeated-picker-result") assertFalse(row.id, ready) else admitted = ready
                }
                "dispatch" -> { assertTrue(row.id, admitted); attempt.beforeDispatch(); dispatches++ }
                "verified-receipt" -> attempt.confirm(receipt)
                "cancel" -> attempt.cancel()
                "reconnect" -> currentOwner = camera()
                "old-progress" -> assertFalse(ownsMediaUpload(attempt, attempt, owner, currentOwner, 1, 1))
                "stage", "stage-failure", "list-existing-match", "list-empty", "network-failure", "invalid-receipt",
                "refresh-failure", "refresh-start", "refresh-complete" -> Unit
                else -> error("Unknown journey event: $event")
            }
            val outcome = attempt.finish()
            assertEquals(row.id, row.dispatches, dispatches)
            assertEquals(row.id, row.outcome, outcome.label())
            assertEquals(row.id, row.publish, outcome == MediaUploadStatus.CONFIRMED &&
                ownsMediaUpload(attempt, attempt, owner, currentOwner, 1, 1))
            assertEquals(row.id, 0, row.replays)
        }
    }
}
