package dev.openeos.control.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.R
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.LiveViewCapabilities
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class CameraScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disconnectedStateShowsDedicatedConnectionScreen() {
        compose.setContent { MaterialTheme { ConnectionScreen(CameraUiState(), noOpActions()) } }
        compose.onNodeWithText(resourceText(R.string.connect_title)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.preview_interface)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.connect)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun offlinePreviewShowsCameraControlsWithoutAConnection() {
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
            }
        }

        compose.onNodeWithText(resourceText(R.string.offline_preview)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.capture_photo)).assertIsDisplayed()
        compose.onNodeWithText("800").assertIsDisplayed()
    }

    @Test
    fun offlinePreviewIncludesMediaBrowserWithoutEnablingFakeDownloads() {
        val state = CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state, noOpActions())
            }
        }

        compose.onNodeWithText(resourceText(R.string.camera_media)).assertIsDisplayed()
        compose.onNodeWithText("R6M3_0001.CR3").assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.download_media, "R6M3_0001.CR3"))
            .assertIsNotEnabled()
    }

    @Test
    fun photoStateExplainsMissingStillCaptureCapability() {
        compose.setContent { MaterialTheme { CameraControlScreen(connectedState(), noOpActions()) } }
        compose.onNodeWithContentDescription(resourceText(R.string.switch_to_video)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.capture_not_supported)).assertIsDisplayed()
    }

    @Test
    fun debugStateShowsDiagnosticSections() {
        compose.setContent { MaterialTheme { DebugScreen(connectedState().copy(uiMode = UiMode.DEBUG), noOpActions()) } }
        compose.onNodeWithText(resourceText(R.string.overview)).assertIsDisplayed()
        compose.onNodeWithText("CCAPI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("USB / PTP").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun exposureDialStaysOpenForContinuousAdjustments() {
        val picker = mutableStateOf<SettingPicker?>(null)
        var selectedIso: String? = null
        var selectedShutter: String? = null
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setIso = { selectedIso = it },
            setShutter = { selectedShutter = it },
        )
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(activeSettingPicker = picker.value),
                    actions,
                )
            }
        }

        compose.onNodeWithText("ISO").performClick()
        compose.onNodeWithText("1600").performClick()
        compose.runOnIdle { assertEquals("1600", selectedIso) }
        compose.onNodeWithTag("exposure-picker-SHUTTER").performClick()
        compose.onNodeWithText("1/60").performClick()
        compose.runOnIdle { assertEquals("1/60", selectedShutter) }
        compose.onNodeWithTag("exposure-picker-ISO").assertIsDisplayed()
    }

    @Test
    fun cleanViewHidesHudButKeepsRestoreControl() {
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(connectedState().copy(hudVisible = false), noOpActions())
            }
        }

        compose.onAllNodesWithText("Canon EOS R6 Mark III").assertCountEquals(0)
        compose.onAllNodesWithText("ISO").assertCountEquals(0)
        compose.onNodeWithContentDescription(resourceText(R.string.show_hud)).assertIsDisplayed()
    }

    @Test
    fun gridOverlayIsExposedWhenEnabled() {
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(connectedState().copy(showGrid = true), noOpActions())
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.composition_grid)).assertIsDisplayed()
    }

    @Test
    fun frameRateControlIsVisibleAndChangesThePollingRate() {
        val picker = mutableStateOf<SettingPicker?>(null)
        val fps = mutableIntStateOf(6)
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setFps = { fps.intValue = it },
        )
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(
                        activeSettingPicker = picker.value,
                        liveViewFrameRateFps = fps.intValue,
                    ),
                    actions,
                )
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.fps_control_description, 6)).performClick()
        compose.onNodeWithContentDescription(resourceText(R.string.increase_fps)).performClick()
        compose.runOnIdle { assertEquals(7, fps.intValue) }
        compose.onAllNodesWithText(resourceText(R.string.fps_value, 7)).assertCountEquals(2)
    }

    @Test
    fun languageSheetOffersAutomaticEnglishAndTraditionalChinese() {
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                LanguageSettingsSheet(
                    CameraUiState(activeSettingPicker = SettingPicker.LANGUAGE),
                    noOpActions(),
                )
            }
        }

        compose.onNodeWithText(resourceText(R.string.language_system)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.language_english)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.language_traditional_chinese)).assertIsDisplayed()
    }

    private fun connectedState() = CameraUiState(
        info = CameraInfo(true, "Canon EOS R6 Mark III", "test", "ccapi"),
        status = CameraStatus(
            connected = true,
            batteryLevel = 82,
            batteryStatus = "normal",
            recording = false,
            mode = "photo",
            mediaAvailable = true,
            remainingMinutes = 120,
            exposure = ExposureState("800", "1/50", "2.8", "auto"),
        ),
        capabilities = CameraCapabilities(
            iso = listOf("100", "200", "400", "800", "1600", "3200"),
            shutter = listOf("1/30", "1/50", "1/60"),
            aperture = listOf("2.8", "4.0", "5.6"),
            whiteBalance = listOf("auto", "daylight", "cloudy"),
            liveView = LiveViewCapabilities.ccapiNetwork(),
        ),
    )

    private fun noOpActions() = CameraActions(
        setBaseUrl = {}, setUsername = {}, setPassword = {},
        useHttpPreset = {}, useHttpsPreset = {}, useSimulatorPreset = {}, enterOfflinePreview = {},
        connect = {}, disconnect = {}, refresh = {}, refreshUsb = {}, requestUsbPermission = {},
        setUiMode = {}, setCaptureMode = {}, setHudVisible = {}, setGridVisible = {}, openPicker = {}, closePicker = {},
        setIso = {}, setShutter = {}, setAperture = {}, setWhiteBalance = {}, setCameraSetting = { _, _ -> },
        captureStill = {}, focusWithShutter = {}, toggleRecording = {}, tapFocus = { _, _ -> },
        refreshMedia = {}, downloadMedia = { _, _ -> }, refreshLiveView = {}, restartLiveView = {},
        setAutoRefresh = {}, setFps = {}, setLiveViewSize = {}, setAppLanguage = {}, clearError = {},
    )

    private fun resourceText(@StringRes resource: Int, vararg arguments: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)
}
