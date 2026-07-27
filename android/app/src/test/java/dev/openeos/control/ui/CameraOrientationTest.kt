package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun disabledSystemAutoRotationKeepsControlsAlignedToDisplay() {
        assertEquals(0f, resolveCameraControlRotation(false, sensorDegrees = 0, displayRotationDegrees = 0))
        assertEquals(0f, resolveCameraControlRotation(false, sensorDegrees = 90, displayRotationDegrees = 0))
        assertEquals(0f, resolveCameraControlRotation(false, sensorDegrees = 180, displayRotationDegrees = 0))
        assertEquals(0f, resolveCameraControlRotation(false, sensorDegrees = 270, displayRotationDegrees = 0))
    }

    @Test
    fun enabledSystemAutoRotationRotatesCameraControls() {
        assertEquals(0f, resolveCameraControlRotation(true, sensorDegrees = 0, displayRotationDegrees = 0))
        assertEquals(-90f, resolveCameraControlRotation(true, sensorDegrees = 90, displayRotationDegrees = 0))
        assertEquals(180f, resolveCameraControlRotation(true, sensorDegrees = 180, displayRotationDegrees = 0))
        assertEquals(90f, resolveCameraControlRotation(true, sensorDegrees = 270, displayRotationDegrees = 0))
    }

    @Test
    fun systemRotatedDisplayDoesNotDoubleRotateControls() {
        assertEquals(0f, resolveCameraControlRotation(true, sensorDegrees = 90, displayRotationDegrees = 270))
        assertEquals(0f, resolveCameraControlRotation(true, sensorDegrees = 270, displayRotationDegrees = 90))
    }

    @Test
    fun unknownSensorOrientationKeepsControlsAligned() {
        assertEquals(0f, resolveCameraControlRotation(true, sensorDegrees = -1, displayRotationDegrees = 0))
    }

    @Test
    fun equivalentRotationUsesTheShortestAnimationPath() {
        assertEquals(-180f, nearestEquivalentCameraRotation(currentDegrees = -90f, targetDegrees = 180f))
        assertEquals(-270f, nearestEquivalentCameraRotation(currentDegrees = -180f, targetDegrees = 90f))
        assertEquals(720f, nearestEquivalentCameraRotation(currentDegrees = 720f, targetDegrees = 0f))
        assertEquals(720f, nearestEquivalentCameraRotation(currentDegrees = 630f, targetDegrees = 0f))
    }
}
