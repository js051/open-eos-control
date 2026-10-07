package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeliveredJpegStoreTest {
    private val camera = CameraInfo(true, "Fixture EOS", "PRIVATE-FIXTURE-SERIAL", "ccapi", "Canon")
    private val item = CameraMediaItem(
        id = "private/source/IMG_0001.JPG",
        name = "IMG_0001.JPG",
        kind = "image",
        sizeBytes = 123L,
        captureTime = "2026-10-07T05:00:00Z",
        contentType = "application/octet-stream",
        widthPixels = 12,
        heightPixels = 10,
        rotationDegrees = 90,
    )

    @Test fun onlyJpegPublicationsAreRegisteredWithFrozenOriginalEvidence() {
        val store = DeliveredJpegStore()
        assertNull(store.recordPublished(camera, item.copy(name = "IMG.CR3"), publication(1)))
        assertNull(store.recordPublished(camera, item.copy(name = "MOV.MP4"), publication(2)))
        assertNull(store.recordPublished(camera, item.copy(kind = "video"), publication(3)))
        assertNull(store.recordPublished(camera, item, publication(4, mimeType = "image/png")))
        assertTrue(store.state.value.entries.isEmpty())

        val id = requireNotNull(store.recordPublished(camera, item, publication(5, sha256 = "A".repeat(64))))
        val entry = store.snapshot(setOf(id)).single()
        assertEquals(id, entry.id)
        assertEquals(CameraImportOriginalEvidence(123L, "a".repeat(64)), entry.evidence)
        assertEquals("image/jpeg", entry.item.contentType)
        assertEquals(item.id, entry.item.id)
        assertEquals(item.captureTime, entry.item.captureTime)
        assertEquals(item.rotationDegrees, entry.item.rotationDegrees)
        assertEquals(camera.serial, entry.camera.serial)
        assertNotSame(item, entry.item)
        assertNotSame(camera, entry.camera)
        assertEquals(1L, entry.publicationOrdinal)
    }

    @Test fun repeatedNotificationAndEquivalentRetainedUriAreIdempotent() {
        val store = DeliveredJpegStore()
        val original = publication(1)
        val first = store.recordPublished(camera, item, original)
        val generation = store.state.value.generation
        repeat(20) { assertEquals(first, store.recordPublished(camera, item, original)) }
        assertEquals(first, store.recordPublished(camera.copy(model = "Different model"), item, publication(1)))
        assertEquals(1, store.state.value.entries.size)
        assertEquals(generation, store.state.value.generation)
        assertEquals(camera.model, store.state.value.entries.single().camera.model)
    }

    @Test fun separatePublishedCopiesWithEqualNamesRemainDistinct() {
        val store = DeliveredJpegStore()
        val first = requireNotNull(store.recordPublished(camera, item, publication(1)))
        val second = requireNotNull(store.recordPublished(camera, item, publication(2)))
        assertNotEquals(first, second)
        assertEquals(listOf(second, first), store.state.value.entries.map { it.id })
        assertEquals(listOf(item.name, item.name), store.state.value.rows.map { it.filename })
        assertEquals(listOf(2L, 1L), store.state.value.entries.map { it.publicationOrdinal })
    }

    @Test fun the101stPublicationEvictsOnlyTheOldestAndRejectsAStaleBatch() {
        val store = DeliveredJpegStore()
        val firstEvidence = publication(1)
        val first = requireNotNull(store.recordPublished(camera, item, firstEvidence))
        val admitted = store.snapshot(setOf(first))
        val later = (2..101).map { requireNotNull(store.recordPublished(camera, item, publication(it))) }
        assertEquals(100, store.state.value.entries.size)
        assertEquals(later.reversed(), store.state.value.entries.map { it.id })
        assertEquals(1L, store.state.value.evictedCount)
        assertEquals(DeliveredJpegSnapshotFailure.STALE_SELECTION, snapshotFailure(store, setOf(first, later.last())))
        assertEquals(first, admitted.single().id)
        assertEquals(first, store.recordPublished(camera, item, firstEvidence))
        assertEquals(later.reversed(), store.state.value.entries.map { it.id })
    }

    @Test fun clearPreservesAdmittedSnapshotsAndCannotBeUndoneByRepeatedCallbacks() {
        val store = DeliveredJpegStore(capacity = 2)
        val original = publication(1)
        val first = requireNotNull(store.recordPublished(camera, item, original))
        val duplicate = publication(1)
        assertEquals(first, store.recordPublished(camera, item, duplicate))
        val admitted = store.snapshot(setOf(first))
        store.markRegistrationUnavailable()
        val oldGeneration = store.state.value.generation
        store.clear()
        assertTrue(store.state.value.entries.isEmpty())
        assertEquals(oldGeneration + 1, store.state.value.generation)
        assertEquals(0L, store.state.value.evictedCount)
        assertFalse(store.state.value.registrationUnavailable)
        assertEquals(first, admitted.single().id)
        assertEquals(DeliveredJpegSnapshotFailure.STALE_SELECTION, snapshotFailure(store, setOf(first)))

        assertEquals(first, store.recordPublished(camera, item, original))
        assertEquals(first, store.recordPublished(camera, item, duplicate))
        assertTrue(store.state.value.entries.isEmpty())
        val later = requireNotNull(store.recordPublished(camera, item, publication(2)))
        assertEquals(listOf(later), store.state.value.entries.map { it.id })
        assertEquals(2L, store.state.value.entries.single().publicationOrdinal)
    }

    @Test fun publicListsAndAdmissionAreIndependentUnmodifiableSnapshots() {
        val store = DeliveredJpegStore(capacity = 2)
        val first = requireNotNull(store.recordPublished(camera, item, publication(1)))
        val second = requireNotNull(store.recordPublished(camera, item, publication(2)))
        val oldState = store.state.value
        val ids = linkedSetOf(first, second)
        val admitted = store.snapshot(ids)
        ids.clear()
        assertEquals(listOf(second, first), admitted.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) { (admitted as MutableList<DeliveredJpeg>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (oldState.entries as MutableList<DeliveredJpeg>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (oldState.rows as MutableList<DeliveredJpegRow>).clear() }
        store.recordPublished(camera, item, publication(3))
        store.clear()
        assertEquals(listOf(second, first), oldState.entries.map { it.id })
        assertEquals(listOf(second, first), admitted.map { it.id })
    }

    @Test fun admissionNeverSilentlyDropsUnknownOrEmptySelections() {
        val store = DeliveredJpegStore()
        val first = requireNotNull(store.recordPublished(camera, item, publication(1)))
        val missing = DeliveredJpegId("missing-fixture")
        assertEquals(DeliveredJpegSnapshotFailure.EMPTY_SELECTION, snapshotFailure(store, emptySet()))
        assertEquals(DeliveredJpegSnapshotFailure.STALE_SELECTION, snapshotFailure(store, setOf(missing)))
        assertEquals(DeliveredJpegSnapshotFailure.STALE_SELECTION, snapshotFailure(store, setOf(first, missing)))
        assertEquals(listOf(first), store.state.value.entries.map { it.id })
    }

    @Test fun invalidOriginalEvidenceRecordsOnlyABoundedAvailabilityNotice() {
        val store = DeliveredJpegStore()
        listOf(
            publication(1, byteLength = 0),
            publication(2, byteLength = -1),
            publication(3, sha256 = "wrong"),
            publication(4, uriValue = "file:///private/fixture.jpg"),
            publication(5, uriValue = "content:///missing-authority"),
        ).forEach { assertNull(store.recordPublished(camera, item, it)) }
        assertTrue(store.state.value.entries.isEmpty())
        assertTrue(store.state.value.registrationUnavailable)
        val generation = store.state.value.generation
        repeat(10) { store.markRegistrationUnavailable() }
        assertEquals(generation, store.state.value.generation)
        store.recordPublished(camera, item, publication(6))
        assertEquals(1, store.state.value.entries.size)
        assertTrue(store.state.value.registrationUnavailable)
    }

    @Test fun aRetainedOwnerKeepsItsEntriesWhileFreshOwnersStartEmpty() {
        val retained = DeliveredJpegStore()
        val id = requireNotNull(retained.recordPublished(camera, item, publication(1)))
        val sameOwner = retained
        assertEquals(id, sameOwner.state.value.entries.single().id)
        assertTrue(DeliveredJpegStore().state.value.entries.isEmpty())
        assertTrue(DeliveredJpegStore().state.value.rows.isEmpty())
    }

    @Test fun configurableCapacityCannotExceedTheProductBound() {
        assertThrows(IllegalArgumentException::class.java) { DeliveredJpegStore(0) }
        assertThrows(IllegalArgumentException::class.java) { DeliveredJpegStore(101) }
        val store = DeliveredJpegStore(1)
        repeat(20) { store.recordPublished(camera, item, publication(it)) }
        assertEquals(1, store.state.value.entries.size)
        assertEquals(19L, store.state.value.evictedCount)
    }

    @Test fun publicDebugTextAndRowsExcludeSourceHandlesAndPrivateIdentifiers() {
        val store = DeliveredJpegStore()
        val evidence = publication(1)
        store.recordPublished(camera, item.copy(name = "folder/unsafe\nIMG.JPG"), evidence)
        val state = store.state.value
        val entry = state.entries.single()
        val debugText = listOf(evidence, entry, state, entry.row).joinToString()
        assertFalse(debugText.contains(camera.serial))
        assertFalse(debugText.contains(item.id))
        assertFalse(debugText.contains(evidence.uriValue))
        assertEquals("unsafe_IMG.JPG", entry.row.filename)
        assertEquals(123L, entry.row.byteLength)
        assertFalse(entry.id.value.contains("content"))
    }

    @Test fun concurrentPublicationsRemainBoundedAndConcurrentDuplicatesRecordOnce() {
        val store = DeliveredJpegStore()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val repeated = publication(1000)
            val duplicateTasks = (1..20).map {
                pool.submit<DeliveredJpegId?> {
                    start.await()
                    store.recordPublished(camera, item, repeated)
                }
            }
            start.countDown()
            val ids = duplicateTasks.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, ids.toSet().size)
            assertEquals(1, store.state.value.entries.size)
            val tasks = (1..200).map { index -> pool.submit { store.recordPublished(camera, item, publication(index)) } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val state = store.state.value
            assertEquals(100, state.entries.size)
            assertEquals(101L, state.evictedCount)
            assertEquals(100, state.entries.map { it.id }.toSet().size)
            assertEquals((201L downTo 102L).toList(), state.entries.map { it.publicationOrdinal })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun snapshotFailure(store: DeliveredJpegStore, ids: Set<DeliveredJpegId>): DeliveredJpegSnapshotFailure =
        assertThrows(DeliveredJpegSnapshotException::class.java) { store.snapshot(ids) }.reason

    private fun publication(
        index: Int,
        byteLength: Long = 123L,
        sha256: String = "a".repeat(64),
        mimeType: String = "image/jpeg",
        uriValue: String = "content://media/external_primary/images/media/$index",
    ) = PublishedGalleryOriginal(uriValue, byteLength, sha256, mimeType)
}
