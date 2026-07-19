package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun portraitAndLandscapeUseStableDisplayOrientations() {
        assertEquals(CameraDisplayOrientation.PORTRAIT, resolveCameraOrientation(0).displayOrientation)
        assertEquals(CameraDisplayOrientation.REVERSE_LANDSCAPE, resolveCameraOrientation(90).displayOrientation)
        assertEquals(CameraDisplayOrientation.LANDSCAPE, resolveCameraOrientation(270).displayOrientation)
    }

    @Test
    fun upsideDownKeepsPortraitLayoutAndRotatesOnlyControls() {
        val decision = resolveCameraOrientation(180)

        assertEquals(CameraDisplayOrientation.PORTRAIT, decision.displayOrientation)
        assertEquals(180f, decision.controlRotationDegrees)
    }
}
