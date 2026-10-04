package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the same predicate used for progress, failure, success and final cleanup admission. */
class MediaSaveRequestTest {
    private val camera = CameraInfo(true, "Synthetic camera", "TEST-SAVE-0001", "fixture")

    @Test fun onlyTheCurrentRequestCanUpdateEvenWithinTheSameCameraSession() {
        val old = MediaSaveRequest(3L, camera)
        val current = MediaSaveRequest(3L, camera)
        assertTrue(current.owns(current, 3L, camera))
        assertFalse(old.owns(current, 3L, camera))
        assertFalse(current.owns(null, 3L, camera))
    }

    @Test fun changedGenerationRejectsLateUpdatesBeforeConnectionInfoChanges() {
        val request = MediaSaveRequest(3L, camera)
        assertFalse(request.owns(request, 4L, camera))
    }

    @Test fun EqualCameraDescriptionsCannotReplaceTheOriginalConnectionOwner() {
        val request = MediaSaveRequest(3L, camera)
        val replacement = camera.copy()
        assertEquals(camera, replacement)
        assertFalse(request.owns(request, 3L, replacement))
        assertFalse(request.owns(request, 3L, null))
    }
}
