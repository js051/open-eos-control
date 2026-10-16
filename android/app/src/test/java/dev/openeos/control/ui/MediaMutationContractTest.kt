package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaMutationContractTest {
    private fun field(value: String): MediaMetadataField = when(value) {
        "rating" -> MediaMetadataField.RATING
        "rotation" -> MediaMetadataField.ROTATION
        "protection" -> MediaMetadataField.PROTECTION
        "archive" -> MediaMetadataField.ARCHIVE
        else -> error("Unknown field")
    }
    private fun decode(profile: String, field: String, raw: Any?): Any? = when (field) {
        "rating" -> if (profile == "canon") canonicalMediaMetadataRating(raw) else normalizedMediaMetadataInteger(raw, (0..5).toSet())
        "rotation" -> if (profile == "canon") canonicalMediaMetadataRotation(raw) else normalizedMediaMetadataInteger(raw, setOf(0,90,180,270))
        "protection", "archive" -> if (profile == "canon") canonicalMediaMetadataFlag(raw) else normalizedMediaMetadataBoolean(raw)
        else -> error("Unknown field")
    }
    @Test fun all200MetadataVectorsRequireProfileSpecificTypedEvidence() {
        val vectors = loadMediaMutationFixture().metadata
        assertEquals(200, vectors.size)
        vectors.forEach { assertEquals(it.id, it.expected, decode(it.profile, it.field, it.raw)) }
    }
    @Test fun all66ReadbackVectorsRequireSameExactIdAndTarget() {
        val vectors = loadMediaMutationFixture().readbacks
        assertEquals(66, vectors.size)
        vectors.forEach { vector ->
            val boundId = if (vector.profile == "canon" && !vector.returnedIdPresent) vector.requestedId
                else vector.returnedId as? String ?: ""
            val decoded = decode(vector.profile, vector.field, vector.raw)
            val evidence = when(vector.field) {
                "rating" -> MediaMetadataReadback(boundId, rating = decoded as? Int)
                "rotation" -> MediaMetadataReadback(boundId, rotationDegrees = decoded as? Int)
                "protection" -> MediaMetadataReadback(boundId, protected = decoded as? Boolean)
                else -> MediaMetadataReadback(boundId, archived = decoded as? Boolean)
            }
            val confirmed = MediaMetadataTarget(field(vector.field), vector.target).matches(vector.requestedId, evidence)
            assertEquals(vector.id, vector.expected, if (confirmed) "confirmed" else "uncertain")
        }
    }
    @Test fun all10BatchVectorsRunTheProductionExecutor() = runBlocking {
        val vectors = loadMediaMutationFixture().batches
        assertEquals(10, vectors.size)
        for (vector in vectors) {
            val target = MediaMetadataTarget(MediaMetadataField.RATING, 3)
            val requests = vector.events.indices.map { MediaMetadataRequest(it.toString(), "SAME.JPG", target) }
            var receipt: MediaMetadataBatchResult? = null
            val dispatched = mutableListOf<Int>()
            var current = true
            val job = launch {
                val self = currentCoroutineContext()
                executeMediaMetadataBatch(requests, { current }, { request ->
                    when(vector.events[request.id.toInt()]) {
                        "failed-not-sent" -> MediaMetadataReason.NOT_WRITABLE
                        "not-started" -> MediaMetadataReason.UNLOADED
                        "cancel-before" -> { self.cancel(); null }
                        else -> null
                    }
                }, { request ->
                    val index = request.id.toInt(); dispatched += index
                    when(vector.events[index]) {
                        "uncertain" -> MediaMetadataReadback(request.id, rating = 2)
                        "cancel-during-error" -> throw CancellationException("cancel during dispatch")
                        "cancel-during-confirmed" -> { self.cancel(); MediaMetadataReadback(request.id, rating = 3) }
                        "owner-lost-during" -> { current = false; MediaMetadataReadback(request.id, rating = 3) }
                        else -> MediaMetadataReadback(request.id, rating = 3)
                    }
                }, { it }, onChanged = { receipt = it })
            }
            job.join()
            assertEquals(vector.id, vector.dispatched, dispatched)
            assertEquals(vector.id, vector.outcomes, receipt?.rows?.map { when(it.outcome) {
                MediaMetadataOutcome.IN_FLIGHT -> "in-flight"
                MediaMetadataOutcome.CONFIRMED -> "confirmed"
                MediaMetadataOutcome.UNCERTAIN -> "uncertain"
                MediaMetadataOutcome.FAILED_NOT_SENT -> "failed-not-sent"
                MediaMetadataOutcome.NOT_STARTED -> "not-started"
            } })
        }
    }
    @Test fun all3RosterVectorsUseExactIdentityAndKeepUnavailableRows() {
        val vectors = loadMediaMutationFixture().rosters
        assertEquals(3, vectors.size)
        vectors.forEach { vector ->
            val result = mediaMetadataRoster(vector.selected, vector.loaded, vector.visible)
            assertEquals(vector.id, vector.roster, result.ids)
            assertEquals(vector.id, vector.eligible, result.eligibleIds)
            assertEquals(vector.id, vector.hidden, result.hiddenCount)
            assertEquals(vector.id, vector.unloaded, result.unloadedCount)
        }
    }
}
