package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMediaPickerRequestsTest {
    private val camera = CameraInfo(true, "Synthetic camera", "TEST-PICKER-0001", "fixture")
    private val first = CameraMediaItem("shared-id-1", "SYNTHETIC_0001.CR3", "raw", 10)
    private val second = CameraMediaItem("shared-id-2", "SYNTHETIC_0002.CR3", "raw", 20)

    @Test
    fun everyPickerConsumesTheOriginalRequestExactlyOnce() {
        for (kind in CameraMediaPickerKind.entries) {
            val requests = CameraMediaPickerRequests()
            val items = itemsFor(kind)
            val id = requireNotNull(requests.begin(kind, 3L, camera, items))
            assertEquals(CameraMediaPickerRequests.Result.Ready(items), requests.consume(kind, id, 3L, camera))
            assertEquals(CameraMediaPickerRequests.Result.Expired, requests.consume(kind, id, 3L, camera))
            assertNotNull(requests.begin(kind, 3L, camera, items))
        }
    }

    @Test
    fun disconnectedReturnExpiresBeforeAnyTransferCanBeAdmitted() {
        for (kind in CameraMediaPickerKind.entries) {
            val requests = CameraMediaPickerRequests()
            val id = requireNotNull(requests.begin(kind, 3L, camera, itemsFor(kind)))
            assertEquals(CameraMediaPickerRequests.Result.Expired, requests.consume(kind, id, 3L, null))
            assertNotNull(requests.begin(kind, 4L, camera, itemsFor(kind)))
        }
    }

    @Test
    fun reconnectingTheSameDeviceWithEqualMetadataIsADifferentSession() {
        val requests = CameraMediaPickerRequests()
        val id = requireNotNull(requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
        val replacement = camera.copy()
        assertEquals(camera, replacement)
        assertEquals(
            CameraMediaPickerRequests.Result.Expired,
            requests.consume(CameraMediaPickerKind.UPLOAD, id, 3L, replacement),
        )
    }

    @Test
    fun changedGenerationExpiresEvenBeforeOldConnectionDescriptionIsCleared() {
        val requests = CameraMediaPickerRequests()
        val id = requireNotNull(requests.begin(CameraMediaPickerKind.DOWNLOAD_DOCUMENT, 3L, camera, listOf(first)))
        assertEquals(
            CameraMediaPickerRequests.Result.Expired,
            requests.consume(CameraMediaPickerKind.DOWNLOAD_DOCUMENT, id, 4L, camera),
        )
    }

    @Test
    fun replacementViewModelCannotRecoverARequestFromItsSavedId() {
        val previous = CameraMediaPickerRequests()
        val id = requireNotNull(previous.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
        val replacement = CameraMediaPickerRequests()
        assertEquals(
            CameraMediaPickerRequests.Result.Expired,
            replacement.consume(CameraMediaPickerKind.UPLOAD, id, 3L, camera),
        )
        assertNotNull(replacement.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
    }

    @Test
    fun outstandingPickerCannotBeReplacedByAnotherLaunchOrNewSession() {
        val requests = CameraMediaPickerRequests()
        val id = requireNotNull(requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
        for (kind in CameraMediaPickerKind.entries) {
            assertNull(requests.begin(kind, 4L, camera.copy(), itemsFor(kind)))
        }
        assertTrue(requests.cancel(CameraMediaPickerKind.UPLOAD, id))
        assertNotNull(requests.begin(CameraMediaPickerKind.DOWNLOAD_FOLDER, 4L, camera.copy(), listOf(first)))
    }

    @Test
    fun cancellationAndLaunchFailureReleaseOnlyTheirOwnRequest() {
        val requests = CameraMediaPickerRequests()
        val id = requireNotNull(requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
        assertFalse(requests.cancel(CameraMediaPickerKind.UPLOAD, null))
        assertFalse(requests.cancel(CameraMediaPickerKind.UPLOAD, "older-request"))
        assertFalse(requests.cancel(CameraMediaPickerKind.DOWNLOAD_DOCUMENT, id))
        assertNull(requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
        assertTrue(requests.cancel(CameraMediaPickerKind.UPLOAD, id))
        val next = requireNotNull(requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
        assertNotEquals(id, next)
        assertFalse(requests.cancel(CameraMediaPickerKind.UPLOAD, id))
        assertEquals(
            CameraMediaPickerRequests.Result.Ready(emptyList()),
            requests.consume(CameraMediaPickerKind.UPLOAD, next, 3L, camera),
        )
    }

    @Test
    fun missingOrMismatchedCallbackDoesNotConsumeALaterRequest() {
        val requests = CameraMediaPickerRequests()
        val id = requireNotNull(requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
        assertEquals(CameraMediaPickerRequests.Result.Ignored, requests.consume(CameraMediaPickerKind.UPLOAD, null, 3L, camera))
        assertEquals(CameraMediaPickerRequests.Result.Expired, requests.consume(CameraMediaPickerKind.UPLOAD, "old", 3L, camera))
        assertEquals(CameraMediaPickerRequests.Result.Expired, requests.consume(CameraMediaPickerKind.DOWNLOAD_FOLDER, id, 3L, camera))
        assertEquals(CameraMediaPickerRequests.Result.Ready(emptyList()), requests.consume(CameraMediaPickerKind.UPLOAD, id, 3L, camera))
    }

    @Test
    fun requestOwnsItsSelectionSnapshotIndependentOfLaterLibraryOrScopeChanges() {
        val requests = CameraMediaPickerRequests()
        val selection = mutableListOf(first, second, first)
        val id = requireNotNull(requests.begin(CameraMediaPickerKind.DOWNLOAD_FOLDER, 3L, camera, selection))
        selection.clear()
        selection += first.copy(name = "DIFFERENT_SELECTION.CR3")
        assertEquals(
            CameraMediaPickerRequests.Result.Ready(listOf(first, second)),
            requests.consume(CameraMediaPickerKind.DOWNLOAD_FOLDER, id, 3L, camera),
        )
    }

    @Test
    fun invalidSelectionsDoNotLeaveAnOutstandingRequest() {
        val requests = CameraMediaPickerRequests()
        assertThrows(IllegalArgumentException::class.java) {
            requests.begin(CameraMediaPickerKind.DOWNLOAD_DOCUMENT, 3L, camera)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requests.begin(CameraMediaPickerKind.DOWNLOAD_DOCUMENT, 3L, camera, listOf(first, second))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requests.begin(CameraMediaPickerKind.DOWNLOAD_FOLDER, 3L, camera)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera, listOf(first))
        }
        assertNotNull(requests.begin(CameraMediaPickerKind.UPLOAD, 3L, camera))
    }

    private fun itemsFor(kind: CameraMediaPickerKind): List<CameraMediaItem> = when (kind) {
        CameraMediaPickerKind.DOWNLOAD_DOCUMENT -> listOf(first)
        CameraMediaPickerKind.DOWNLOAD_FOLDER -> listOf(first, second)
        CameraMediaPickerKind.UPLOAD -> emptyList()
    }
}
