package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraVideoPlaybackTest {
    @Test
    fun identifiesKnownCameraVideoContainers() {
        assertEquals("MP4", cameraVideoContainerLabel("MVI_0001.MP4"))
        assertEquals("QuickTime MOV", cameraVideoContainerLabel("clip.mov"))
        assertEquals("M4V", cameraVideoContainerLabel("clip.m4v"))
        assertEquals("AVI", cameraVideoContainerLabel("clip.avi"))
        assertEquals("Matroska MKV", cameraVideoContainerLabel("clip.mkv"))
        assertEquals("BIN", cameraVideoContainerLabel("clip.bin"))
        assertEquals("VIDEO", cameraVideoContainerLabel("clip"))
    }

    @Test
    fun retriesTransportAndStorageFailuresButNotMissingCodec() {
        assertTrue(CameraVideoPlaybackFailure.TRANSFER.retryable)
        assertTrue(CameraVideoPlaybackFailure.STORAGE.retryable)
        assertFalse(CameraVideoPlaybackFailure.CODEC.retryable)
    }
}
