package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun portraitAndLandscapeUseStableDisplayOrientations() {
        val portrait = resolveCameraOrientation(0)
        val reverseLandscape = resolveCameraOrientation(90)
        val landscape = resolveCameraOrientation(270)

        assertEquals(CameraDisplayOrientation.PORTRAIT, portrait.displayOrientation)
        assertEquals(CameraDisplayOrientation.REVERSE_LANDSCAPE, reverseLandscape.displayOrientation)
        assertEquals(CameraDisplayOrientation.LANDSCAPE, landscape.displayOrientation)
        assertEquals(0f, portrait.controlRotationDegrees)
        assertEquals(0f, reverseLandscape.controlRotationDegrees)
        assertEquals(0f, landscape.controlRotationDegrees)
    }

    @Test
    fun upsideDownKeepsPortraitLayoutAndRotatesOnlyControls() {
        val decision = resolveCameraOrientation(180)

        assertEquals(CameraDisplayOrientation.PORTRAIT, decision.displayOrientation)
        assertEquals(180f, decision.controlRotationDegrees)
    }
}
