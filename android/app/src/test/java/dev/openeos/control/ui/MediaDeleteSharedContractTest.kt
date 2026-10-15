package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal data class DeleteEligibilityVector(val id: String, val source: String, val loaded: Boolean, val capability: Boolean?, val protected: Boolean?, val writable: Boolean?, val allowed: Boolean, val reason: String, val unknown: Boolean)
internal data class DeleteAdmissionVector(val id: String, val opening: String, val current: String?, val confirmed: Boolean, val consumed: Boolean, val busy: Boolean, val allowed: Boolean)
internal data class DeleteOutcomeVector(val id: String, val started: Boolean, val dispatched: Boolean, val confirmed: Boolean, val rejected: Boolean, val skipped: Boolean, val status: String)
internal data class DeleteHttpVector(val id: String, val code: Int, val complete: Boolean, val framing: Boolean, val bytes: Int, val status: String)
internal data class DeleteSelectionVector(val id: String, val opening: String, val current: String?, val openingToken: String, val currentToken: String?, val selected: List<String>, val confirmed: List<String>, val expected: List<String>)
internal data class DeleteRosterVector(val id: String, val selected: List<String>, val allowed: Boolean, val expected: List<String>)
internal data class DeleteBatchVector(val id: String, val eligible: List<Boolean>, val responses: List<String>, val cancel: Int?, val statuses: List<String>, val dispatches: List<Int>)
internal data class DeleteFixture(val eligibility: List<DeleteEligibilityVector>, val admission: List<DeleteAdmissionVector>, val outcome: List<DeleteOutcomeVector>, val http: List<DeleteHttpVector>, val selection: List<DeleteSelectionVector>, val roster: List<DeleteRosterVector>, val batch: List<DeleteBatchVector>)

class MediaDeleteSharedContractTest {
    private fun status(value: MediaDeleteOutcome) = value.name.lowercase().replace('_', '-')
    private fun source(value: String) = when (value) {
        "camera" -> MediaDeleteSource.CAMERA_CARD
        "android-host" -> MediaDeleteSource.ANDROID_PRIVATE
        "desktop-host" -> MediaDeleteSource.DESKTOP_HOST
        else -> MediaDeleteSource.UNKNOWN
    }
    @Test fun all216EligibilityVectorsUseTheNativeAdmissionVeto() {
        val vectors = loadMediaDeleteFixture().eligibility; assertEquals(216, vectors.size)
        vectors.forEach { v ->
            val row = MediaDeleteRequest("id", "SAME.JPG", source(v.source), v.protected, v.writable)
            val reason = mediaDeletePreflight(row, v.capability == true, "id".takeIf { v.loaded }, source(v.source), v.protected, v.writable)
            assertEquals(v.id, v.allowed, reason == null)
            val code = when (reason) {
                null -> "eligible"; MediaDeleteReason.UNLOADED -> "not-loaded"; MediaDeleteReason.UNSUPPORTED -> "no-capability"
                MediaDeleteReason.SOURCE_UNKNOWN -> "unknown-source"; MediaDeleteReason.PROTECTED -> "protected"
                MediaDeleteReason.NOT_WRITABLE -> "not-writable"; else -> "unexpected"
            }
            assertEquals(v.id, v.reason, code)
            assertEquals(v.id, v.unknown, reason == null && (v.protected == null || v.writable == null))
        }
    }
    @Test fun all24AdmissionVectorsRequireSameOwnerAndUnusedExplicitConfirmation() {
        val vectors = loadMediaDeleteFixture().admission; assertEquals(24, vectors.size)
        vectors.forEach { v ->
            val owner = Any()
            assertEquals(v.id, v.allowed, canAdmitMediaDeletion(MediaDeleteOwner(owner, 1),
                if (v.opening == v.current) owner else Any(), 1, v.confirmed, v.consumed, v.busy))
        }
    }
    @Test fun all32OutcomeVectorsPreserveDispatchAndAckEvidence() {
        val vectors = loadMediaDeleteFixture().outcome; assertEquals(32, vectors.size)
        vectors.forEach { v -> assertEquals(v.id, v.status, status(reduceMediaDeleteOutcome(v.started, v.dispatched, v.confirmed, v.rejected, v.skipped))) }
    }
    @Test fun all192HttpVectorsUseTransportSpecificTerminalEvidence() {
        val vectors = loadMediaDeleteFixture().http; assertEquals(192, vectors.size)
        vectors.forEach { v -> assertEquals(v.id, v.status, status(mediaDeleteHttpOutcome(v.code, v.complete, v.framing, v.bytes))) }
    }
    @Test fun all54SelectionVectorsConsumeOnlyOriginalExactIds() {
        val vectors = loadMediaDeleteFixture().selection; assertEquals(54, vectors.size)
        vectors.forEach { v ->
            val owner = Any(); val tokens = v.selected.associateWith { v.currentToken ?: "missing-token" }
            val receipt = MediaDeleteReceipt(v.id, MediaDeleteOwner(owner, 1), v.confirmed.map {
                MediaDeleteRow(MediaDeleteRequest(it, "SAME.JPG", MediaDeleteSource.CAMERA_CARD, selectionToken = v.openingToken), MediaDeleteOutcome.CONFIRMED) })
            assertEquals(v.id, v.expected, consumeConfirmedDeleteSelection(tokens, receipt,
                if (v.opening == v.current) owner else Any(), 1).keys.toList())
        }
    }
    @Test fun all10RosterVectorsRejectOversizedUniqueRosterWithoutTruncation() {
        val vectors = loadMediaDeleteFixture().roster; assertEquals(10, vectors.size)
        vectors.forEach { v ->
            val result = runCatching { mediaDeleteRoster(v.selected) }
            assertEquals(v.id, v.allowed, result.isSuccess)
            assertEquals(v.id, v.expected, result.getOrDefault(emptyList()))
        }
    }
    @Test fun all12BatchVectorsExecuteTheRealNativeCoordinator() = runBlocking {
        val vectors = loadMediaDeleteFixture().batch; assertEquals(12, vectors.size)
        for (v in vectors) {
            val control = MediaDeleteBatchControl(); if (v.cancel == 0) control.cancel()
            val dispatches = IntArray(v.eligible.size)
            val result = executeMediaDeleteBatch(v.id, MediaDeleteOwner(Any(), 1),
                v.eligible.indices.map { MediaDeleteRequest(it.toString(), "SAME.JPG", MediaDeleteSource.CAMERA_CARD) }, control,
                { true }, { row -> if (!v.eligible[row.id.toInt()]) MediaDeleteReason.PROTECTED else null },
                { row, evidence ->
                    val index = row.id.toInt()
                    if (v.responses[index] == "not-sent") error("Synthetic pre-dispatch failure")
                    evidence.beforeDispatch(); dispatches[index]++
                    when (v.responses[index]) {
                        "confirmed" -> evidence.onConfirmed()
                        "rejected" -> evidence.onRejected()
                        else -> error("Synthetic lost ACK")
                    }
                    if (v.cancel == index + 1) control.cancel()
                })
            assertEquals(v.id, v.statuses, result.rows.map { status(it.outcome) })
            assertEquals(v.id, v.dispatches, dispatches.toList())
        }
    }
}
