package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun sensorRotationUsesHysteresisAroundQuarterTurnBoundaries() {
        assertEquals(0, snapCameraDeviceRotation(previousDegrees = 0, sensorDegrees = 49))
        assertEquals(90, snapCameraDeviceRotation(previousDegrees = 0, sensorDegrees = 50))
        assertEquals(90, snapCameraDeviceRotation(previousDegrees = 90, sensorDegrees = 41))
        assertEquals(0, snapCameraDeviceRotation(previousDegrees = 90, sensorDegrees = 40))
        assertEquals(270, snapCameraDeviceRotation(previousDegrees = 270, sensorDegrees = -1))
    }

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
    fun quarterTurnsSwapLayoutDimensions() {
        assertEquals(false, cameraRotationSwapsDimensions(0f))
        assertEquals(true, cameraRotationSwapsDimensions(90f))
        assertEquals(false, cameraRotationSwapsDimensions(180f))
        assertEquals(true, cameraRotationSwapsDimensions(270f))
        assertEquals(true, cameraRotationSwapsDimensions(-90f))
        assertEquals(false, cameraRotationSwapsDimensions(720f))
    }

    @Test
    fun settingsSurfaceDetectsEveryRotatedQuadrant() {
        assertEquals(0, cameraRotationQuadrant(0f))
        assertEquals(1, cameraRotationQuadrant(90f))
        assertEquals(2, cameraRotationQuadrant(180f))
        assertEquals(3, cameraRotationQuadrant(270f))
        assertEquals(3, cameraRotationQuadrant(-90f))
        assertEquals(0, cameraRotationQuadrant(720f))
    }

    @Test
    fun equivalentRotationUsesTheShortestAnimationPath() {
        assertEquals(-180f, nearestEquivalentCameraRotation(currentDegrees = -90f, targetDegrees = 180f))
        assertEquals(-270f, nearestEquivalentCameraRotation(currentDegrees = -180f, targetDegrees = 90f))
        assertEquals(720f, nearestEquivalentCameraRotation(currentDegrees = 720f, targetDegrees = 0f))
        assertEquals(720f, nearestEquivalentCameraRotation(currentDegrees = 630f, targetDegrees = 0f))
    }
}
