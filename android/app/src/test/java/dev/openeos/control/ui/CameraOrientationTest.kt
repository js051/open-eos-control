package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun everyNonZeroSystemSettingMeansAutoRotationIsEnabled() {
        assertEquals(false, isSystemAutoRotationSettingEnabled(0))
        assertEquals(true, isSystemAutoRotationSettingEnabled(1))
        assertEquals(true, isSystemAutoRotationSettingEnabled(2))
    }

    @Test
    fun systemRotationLockAlignsControlsButKeepsPhysicalPosture() {
        val policy = CameraOrientationPolicy()

        policy.setSystemAutoRotation(true)
        assertEquals(true, policy.shouldListen(activityStarted = true, canDetectOrientation = true))
        policy.onSensorOrientation(90)
        assertEquals(-90f, policy.resolveControlRotation(displayRotationDegrees = 0))

        policy.setSystemAutoRotation(false)
        assertEquals(true, policy.shouldListen(activityStarted = true, canDetectOrientation = true))
        assertEquals(0f, policy.resolveControlRotation(displayRotationDegrees = 0))

        policy.onSensorOrientation(270)
        assertEquals(0f, policy.resolveControlRotation(displayRotationDegrees = 0))

        policy.setSystemAutoRotation(true)
        assertEquals(90f, policy.resolveControlRotation(displayRotationDegrees = 0))
    }

    @Test
    fun everySensorEventReconcilesTheCurrentSystemRotationSetting() {
        val policy = CameraOrientationPolicy()

        policy.onSensorOrientation(sensorDegrees = 90, systemAutoRotationEnabled = true)
        assertEquals(-90f, policy.resolveControlRotation(displayRotationDegrees = 0))

        policy.onSensorOrientation(sensorDegrees = 270, systemAutoRotationEnabled = false)
        assertEquals(0f, policy.resolveControlRotation(displayRotationDegrees = 0))

        policy.onSensorOrientation(sensorDegrees = 270, systemAutoRotationEnabled = true)
        assertEquals(90f, policy.resolveControlRotation(displayRotationDegrees = 0))
    }

    @Test
    fun orientationListenerTracksPostureWhileActivityIsStarted() {
        val policy = CameraOrientationPolicy()

        assertEquals(false, policy.shouldListen(activityStarted = false, canDetectOrientation = true))
        assertEquals(false, policy.shouldListen(activityStarted = true, canDetectOrientation = false))
        assertEquals(true, policy.shouldListen(activityStarted = true, canDetectOrientation = true))

        policy.setSystemAutoRotation(false)
        assertEquals(true, policy.shouldListen(activityStarted = true, canDetectOrientation = true))
    }

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
    fun complexSettingsUseWideSurfaceOnlyForQuarterTurns() {
        assertEquals(false, cameraSettingsUsesLandscapeSurface(0f))
        assertEquals(true, cameraSettingsUsesLandscapeSurface(90f))
        assertEquals(false, cameraSettingsUsesLandscapeSurface(180f))
        assertEquals(true, cameraSettingsUsesLandscapeSurface(270f))
        assertEquals(true, cameraSettingsUsesLandscapeSurface(-90f))
    }

    @Test
    fun equivalentRotationUsesTheShortestAnimationPath() {
        assertEquals(-180f, nearestEquivalentCameraRotation(currentDegrees = -90f, targetDegrees = 180f))
        assertEquals(-270f, nearestEquivalentCameraRotation(currentDegrees = -180f, targetDegrees = 90f))
        assertEquals(720f, nearestEquivalentCameraRotation(currentDegrees = 720f, targetDegrees = 0f))
        assertEquals(720f, nearestEquivalentCameraRotation(currentDegrees = 630f, targetDegrees = 0f))
    }

    @Test
    fun cameraHudNameKeepsModelGenerationInACompactSlot() {
        assertEquals("R6 III", "R6 Mark III".toCameraHudName())
        assertEquals("R5", "R5".toCameraHudName())
    }
}
