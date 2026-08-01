package dev.openeos.control

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class SimulatorEndToEndTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSimulator() {
        val available = runCatching { request("/health").getBoolean("ok") }.getOrDefault(false)
        val required = InstrumentationRegistry.getArguments().getString("requireSimulator") == "true"
        if (required) {
            assertTrue("The required fake camera is not reachable at $simulatorUrl", available)
        }
        assumeTrue("Start the fake camera at $simulatorUrl to run the network end-to-end test", available)
        request("/ccapi/test/reset", method = "POST")
    }

    @Test
    fun uiControlsReachTheRunningCameraSimulator() {
        compose.onNodeWithText(text(R.string.preset_simulator)).performScrollTo().performClick()
        if (simulatorPort != DEFAULT_SIMULATOR_PORT) {
            compose.onNode(hasSetTextAction()).performTextReplacement(simulatorUrl)
        }
        compose.onNodeWithText(text(R.string.connect)).performScrollTo().performClick()

        waitForNode("camera-model-status", timeoutMillis = 30_000)
        compose.onNodeWithTag("camera-model-status").assertIsDisplayed()
        waitForNode("live-view-decoded-frame", timeoutMillis = 30_000)

        compose.onNodeWithTag("exposure-control-ISO").performClick()
        waitForNode("exposure-option-4")
        compose.onNodeWithTag("exposure-option-4").performClick()
        waitForSimulatorState { state -> state.getJSONObject("exposure").getString("iso") == "1600" }
        compose.onNodeWithContentDescription(text(R.string.dismiss)).performClick()

        compose.onNodeWithContentDescription(text(R.string.capture_photo)).performClick()
        waitForSimulatorState { state -> state.getInt("capture_count") == 1 }

        compose.onNodeWithTag("live-view-frame").performTouchInput { click() }
        waitForSimulatorState { state -> state.getJSONObject("focus").getInt("count") == 1 }

        compose.onNodeWithContentDescription(text(R.string.tap_action_focus)).performClick()
        waitForContentDescription(text(R.string.tap_action_white_balance))
        compose.onNodeWithTag("live-view-frame").performTouchInput { click() }
        waitForSimulatorState { state ->
            state.getJSONObject("click_white_balance").getInt("count") == 1 &&
                state.getJSONObject("exposure").getString("white_balance") == "click"
        }

        compose.onNodeWithContentDescription(text(R.string.more_settings)).performClick()
        compose.onNodeWithTag("autofocus").performScrollTo().performClick()
        waitForSimulatorState { state ->
            state.getInt("half_press_count") == 1 &&
                state.getInt("shutter_release_count") == 1 &&
                !state.getBoolean("half_pressed")
        }
        waitForEnabledNode("shutter-half-press")
        compose.onNodeWithTag("shutter-half-press").performScrollTo().performClick()
        waitForSimulatorState { state ->
            state.getInt("half_press_count") == 2 &&
                state.getInt("shutter_release_count") == 2 &&
                !state.getBoolean("half_pressed")
        }
        waitForEnabledNode("focus-drive-FAR-LARGE")
        compose.onNodeWithTag("focus-drive-FAR-LARGE").performScrollTo().performClick()
        waitForSimulatorState { state ->
            state.getJSONObject("focus_drive").let { focusDrive ->
                focusDrive.getInt("count") == 1 &&
                    focusDrive.getString("direction") == "far" &&
                    focusDrive.getString("step") == "large"
            }
        }
        compose.onNodeWithContentDescription(text(R.string.dismiss)).performClick()

        waitForEnabledNode("capture-mode-VIDEO")
        compose.onNodeWithTag("capture-mode-VIDEO").performClick()
        waitForContentDescription(text(R.string.start_recording), useUnmergedTree = true)
        compose.onNodeWithTag("capture-button", useUnmergedTree = true)
            .performClick()
        waitForSimulatorState { state ->
            state.getBoolean("recording") &&
                state.getInt("record_start_count") == 1 &&
                state.getInt("record_stop_count") == 0
        }
        // The fixed camera surface exposes the updated action only in Compose's unmerged tree.
        waitForContentDescription(text(R.string.stop_recording), useUnmergedTree = true)
        compose.onNodeWithTag("capture-button", useUnmergedTree = true)
            .performClick()
        waitForSimulatorState { state ->
            !state.getBoolean("recording") &&
                state.getInt("record_start_count") == 1 &&
                state.getInt("record_stop_count") == 1
        }

        waitForEnabledNode("capture-mode-PHOTO")
        compose.onNodeWithTag("capture-mode-PHOTO").performClick()
        request("/ccapi/test/mode?mode=Bulb", method = "POST")
        waitForContentDescription(text(R.string.start_bulb_exposure), useUnmergedTree = true)
        compose.onNodeWithTag("capture-button", useUnmergedTree = true).performClick()
        waitForSimulatorState { state ->
            state.getBoolean("bulb_exposure_active") &&
                state.getInt("bulb_start_count") == 1 &&
                state.getInt("bulb_stop_count") == 0
        }
        waitForContentDescription(text(R.string.stop_bulb_exposure), useUnmergedTree = true)
        compose.onNodeWithTag("capture-button", useUnmergedTree = true).performClick()
        waitForSimulatorState { state ->
            !state.getBoolean("bulb_exposure_active") &&
                state.getInt("bulb_start_count") == 1 &&
                state.getInt("bulb_stop_count") == 1
        }
        waitForNode("live-view-decoded-frame", timeoutMillis = 30_000)

        compose.onNodeWithContentDescription(text(R.string.more_actions)).performClick()
        compose.onNodeWithText(text(R.string.camera_media)).performClick()
        waitForText("SIM_0003.PNG")
        compose.onNodeWithText("SIM_0003.PNG").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.preview_media, "SIM_0003.PNG")).performClick()
        waitForContentDescription(text(R.string.media_preview_content, "SIM_0003.PNG"))
        compose.onNodeWithContentDescription(text(R.string.close_media_preview)).performClick()
        compose.onNodeWithContentDescription(text(R.string.delete_media, "SIM_0003.PNG")).performClick()
        waitForText(text(R.string.delete_media_confirmation, "SIM_0003.PNG"))
        compose.onNodeWithText(text(R.string.delete)).performClick()
        waitForSimulatorState { state -> !state.hasMediaId("SIM_0003.PNG") }
        compose.onNodeWithContentDescription(text(R.string.back_to_camera)).performClick()
        compose.onNodeWithContentDescription(text(R.string.more_actions)).performClick()
        compose.onNodeWithText(text(R.string.disconnect)).performClick()
        compose.onNodeWithText(text(R.string.connect_title)).assertIsDisplayed()
    }

    @Test
    fun directCcapiUiUsesAdvertisedCanonEndpoints() {
        request(
            "/ccapi/ver100/shooting/settings/shootingmode",
            method = "PUT",
            body = """{"value":"Manual"}""",
        )
        compose.onNodeWithText(text(R.string.preset_http)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(simulatorUrl)
        compose.onNodeWithText(text(R.string.connect)).performScrollTo().performClick()

        waitForNode("camera-model-status", timeoutMillis = 30_000)
        waitForNode("live-view-decoded-frame", timeoutMillis = 30_000)
        waitForSimulatorState { state ->
            state.getJSONObject("canonical").let { canonical ->
                canonical.getInt("event_poll_count") >= 1 &&
                    canonical.getInt("event_active_requests") == 1 &&
                    canonical.getInt("live_view_start_count") == 1
            }
        }

        compose.onNodeWithTag("exposure-control-ISO").performClick()
        waitForNode("exposure-option-4")
        compose.onNodeWithTag("exposure-option-4").performClick()
        waitForSimulatorState { state -> state.getJSONObject("exposure").getString("iso") == "1600" }
        compose.onNodeWithContentDescription(text(R.string.dismiss)).performClick()

        compose.onNodeWithContentDescription(text(R.string.capture_photo)).performClick()
        waitForSimulatorState { state ->
            state.getInt("capture_count") == 1 && state.hasMediaId("SIM_0003.JPG")
        }

        request("/ccapi/exposure", method = "PATCH", body = """{"iso":"3200"}""")
        waitForText("3200", timeoutMillis = 30_000)
        waitForSimulatorState { state ->
            state.getJSONObject("canonical").getInt("event_cursor") >= 4
        }

        compose.onNodeWithContentDescription(text(R.string.more_actions)).performClick()
        compose.onNodeWithText(text(R.string.disconnect)).performClick()
        compose.onNodeWithText(text(R.string.connect_title)).assertIsDisplayed()
        waitForSimulatorState { state ->
            state.getJSONObject("canonical").let { canonical ->
                canonical.getInt("event_delete_count") >= 1 &&
                    canonical.getInt("event_active_requests") == 0 &&
                    canonical.getInt("live_view_stop_count") == 1
            }
        }
    }

    private fun waitForNode(tag: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForEnabledNode(tag: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                compose.onNodeWithTag(tag).assertIsEnabled()
            }.isSuccess
        }
    }

    private fun waitForText(value: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentDescription(
        value: String,
        timeoutMillis: Long = 15_000,
        useUnmergedTree: Boolean = false,
    ) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithContentDescription(value, useUnmergedTree).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSimulatorState(predicate: (JSONObject) -> Boolean) {
        compose.waitUntil(timeoutMillis = 30_000) {
            runCatching { predicate(request("/ccapi/test/state")) }.getOrDefault(false)
        }
    }

    private fun request(path: String, method: String = "GET", body: String? = null): JSONObject {
        val connection = URL("$simulatorUrl$path").openConnection() as HttpURLConnection
        return connection.run {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 2_000
            if (body != null || method == "POST") {
                val bytes = body.orEmpty().toByteArray(Charsets.UTF_8)
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { output -> output.write(bytes) }
            }
            try {
                val responseCode = responseCode
                check(responseCode in 200..299) { "$method $path returned HTTP $responseCode" }
                val responseBody = inputStream.bufferedReader().use { it.readText() }
                JSONObject(responseBody.ifBlank { "{}" })
            } finally {
                disconnect()
            }
        }
    }

    private fun JSONObject.hasMediaId(id: String): Boolean {
        val ids = getJSONArray("media_ids")
        return (0 until ids.length()).any { index -> ids.getString(index) == id }
    }

    private fun text(@StringRes resource: Int, vararg formatArgs: Any): String =
        compose.activity.getString(resource, *formatArgs)

    private val simulatorPort: Int
        get() = InstrumentationRegistry.getArguments().getString("simulatorPort")
            ?.toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: DEFAULT_SIMULATOR_PORT

    private val simulatorUrl: String
        get() = "http://10.0.2.2:$simulatorPort"

    companion object {
        private const val DEFAULT_SIMULATOR_PORT = 18080
    }
}
