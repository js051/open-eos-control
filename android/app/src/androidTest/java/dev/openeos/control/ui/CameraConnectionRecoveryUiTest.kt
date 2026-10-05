package dev.openeos.control.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.services.storage.TestStorage
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertThrows
import kotlin.math.ceil
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import dev.openeos.control.data.ConnectionFailureReason
import dev.openeos.control.data.UsbCameraDevice
import dev.openeos.control.data.UsbCameraInterface
import dev.openeos.control.data.UsbPtpDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Injected-state layout and action tests; production HTTP recovery has separate session tests. */
class CameraConnectionRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var layoutScenario = "initial"

    @Test fun cancelStaysTouchableWhileTheNarrowLargeTextFormIsScrolled() {
        val state = mutableStateOf(CameraUiState())
        val size = mutableStateOf(DpSize(320.dp, 480.dp))
        val locale = mutableStateOf(LocaleList("en"))
        var cancelled = 0
        var connections = 0
        var scans = 0
        val actions = connectionRecoveryTestActions().copy(
            cancelConnectionAttempt = {
                cancelled++
                state.value = state.value.copy(pendingOperations = emptySet())
            },
            connect = { connections++ }, connectBridge = { connections++ }, scanDesktopBridge = { scans++ },
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(locale.value)) {
                        ConnectionRecoveryTestContent(state.value, actions)
                    }
                }
            }
        }
        for (language in listOf("en", "zh-TW")) {
            for (viewport in listOf(DpSize(320.dp, 480.dp), DpSize(480.dp, 320.dp))) {
                for (operation in listOf(CameraOperation.CONNECT, CameraOperation.BRIDGE)) {
                    layoutScenario = "language=$language viewport=$viewport operation=$operation"
                    compose.runOnIdle {
                        size.value = viewport
                        locale.value = LocaleList(language)
                        state.value = CameraUiState(
                            connectionTarget = if (operation == CameraOperation.BRIDGE) ConnectionTarget.DESKTOP_BRIDGE else ConnectionTarget.CCAPI,
                            pendingOperations = setOf(operation),
                        )
                    }
                    val retryTag = if (operation == CameraOperation.BRIDGE) "connection-bridge-scan" else "connection-connect"
                    compose.onNodeWithTag(retryTag).performScrollTo().assertIsNotEnabled()
                    compose.onNodeWithTag("connection-offline-preview").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("connection-cancel").assertIsDisplayed().assertIsEnabled()
                        .assertHeightIsAtLeast(48.dp).performTouchInput { click(center) }
                    compose.onNodeWithTag("connection-cancel").assertDoesNotExist()
                    compose.onNodeWithTag(retryTag).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                    assertTextFits(retryTag)
                }
            }
        }
        compose.runOnIdle {
            assertEquals(8, cancelled)
            assertEquals(0, connections)
            assertEquals(0, scans)
            state.value = CameraUiState(shutterReleaseUnconfirmed = true)
        }
        // A safety interlock is not an in-flight setup job. Never show an inert Cancel.
        compose.onNodeWithTag("connection-cancel").assertDoesNotExist()
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsNotEnabled()
    }

    @Test fun authenticationFailureOpensEditableFieldsAndDismissDoesNotRetry() {
        val state = mutableStateOf(CameraUiState(connectionRecovery = ConnectionRecovery(
            ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.AUTHENTICATION_REJECTED,
        )))
        var connects = 0
        var dismissed = 0
        val actions = connectionRecoveryTestActions().copy(
            setUsername = { state.value = state.value.copy(username = it) },
            setPassword = { state.value = state.value.copy(password = it) },
            connect = { connects++ },
            clearError = { dismissed++; state.value = state.value.copy(connectionRecovery = null, error = null) },
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 480.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    ConnectionRecoveryTestContent(state.value, actions)
                }
            }
        }
        compose.onNodeWithTag("connection-username").performScrollTo().assertIsDisplayed()
            .performTextReplacement("synthetic-user")
        compose.onNodeWithTag("connection-password").performScrollTo().assertIsDisplayed()
            .performTextReplacement("synthetic-password")
        compose.onNodeWithTag("connection-recovery-dismiss").performScrollTo().assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        compose.onNodeWithTag("connection-recovery").assertDoesNotExist()
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("synthetic-user", state.value.username)
            assertEquals("synthetic-password", state.value.password)
            assertEquals(1, dismissed)
            assertEquals(1, connects)
        }
    }

    @Test fun localizedRecoveryAndConnectionChoicesWrapAtLargeText() {
        val state = mutableStateOf(CameraUiState())
        val locale = mutableStateOf(LocaleList("en"))
        val size = mutableStateOf(DpSize(320.dp, 480.dp))
        var retries = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(locale.value)) {
                        ConnectionRecoveryTestContent(state.value, connectionRecoveryTestActions().copy(connect = { retries++ }))
                    }
                }
            }
        }
        val cases = listOf(
            Triple(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.INVALID_ADDRESS, R.string.connection_recovery_invalid_address),
            Triple(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.TLS_ERROR, R.string.connection_recovery_tls),
            Triple(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.HOST_NOT_FOUND, R.string.connection_recovery_host_not_found),
            Triple(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.TIMEOUT, R.string.connection_recovery_timeout),
            Triple(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.UNREACHABLE, R.string.connection_recovery_unreachable),
            Triple(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.ENDPOINT_NOT_FOUND, R.string.connection_recovery_ccapi_endpoint),
            Triple(ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.AUTHENTICATION_REJECTED, R.string.connection_recovery_bridge_auth),
            Triple(ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.DISCOVERY_FAILED, R.string.connection_recovery_bridge_endpoint),
            Triple(ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.HTTP_ERROR, R.string.connection_recovery_http),
            Triple(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.UNKNOWN, R.string.connection_recovery_unknown),
            Triple(ConnectionAttemptTarget.USB, ConnectionFailureReason.UNKNOWN, R.string.connection_recovery_usb),
        )
        for (language in listOf("en", "zh-TW")) {
            for ((target, reason, expected) in cases) {
                layoutScenario = "language=$language target=$target reason=$reason"
                compose.runOnIdle {
                    locale.value = LocaleList(language)
                    size.value = if (language == "en") DpSize(320.dp, 480.dp) else DpSize(480.dp, 320.dp)
                    state.value = CameraUiState(
                        connectionTarget = if (target == ConnectionAttemptTarget.DESKTOP_BRIDGE) ConnectionTarget.DESKTOP_BRIDGE else ConnectionTarget.CCAPI,
                        connectionRecovery = ConnectionRecovery(target, reason),
                    )
                }
                compose.onNodeWithTag("connection-target-0").performScrollTo().assertHeightIsAtLeast(48.dp)
                assertTextFits("connection-target-0")
                assertTextFits("connection-target-1")
                compose.onNodeWithText(resourceText(expected, language)).performScrollTo().assertIsDisplayed()
                assertTextFits("connection-recovery-message")
                compose.onNodeWithTag("connection-recovery-dismiss").performScrollTo().assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp)
            }
        }
        compose.runOnIdle { assertEquals(0, retries) }
    }

    @Test fun bridgeEmptyResultAppearsOnlyAfterACompletedScan() {
        val state = mutableStateOf(CameraUiState(connectionTarget = ConnectionTarget.DESKTOP_BRIDGE))
        var scans = 0
        val actions = connectionRecoveryTestActions().copy(scanDesktopBridge = {
            scans++
            state.value = state.value.copy(bridgeScanCompleted = false, pendingOperations = setOf(CameraOperation.BRIDGE))
        })
        compose.setContent { ConnectionRecoveryTestContent(state.value, actions) }
        compose.onNodeWithTag("connection-bridge-scan-empty").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(bridgeScanCompleted = true) }
        compose.onNodeWithTag("connection-bridge-scan-empty").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection-bridge-scan").performScrollTo().performClick()
        compose.onNodeWithTag("connection-bridge-scan-empty").assertDoesNotExist()
        compose.onNodeWithTag("connection-cancel").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, scans)
            state.value = state.value.copy(pendingOperations = emptySet(), connectionRecovery = ConnectionRecovery(
                ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.UNREACHABLE,
            ))
        }
        compose.onNodeWithTag("connection-bridge-scan-empty").assertDoesNotExist()
        compose.onNodeWithTag("connection-recovery-dismiss").performScrollTo().assertIsDisplayed()
    }

    @Test fun usbExplainsUnsupportedDevicesBeforeOfferingPermission() {
        val state = mutableStateOf(CameraUiState())
        var permissions = 0
        var connections = 0
        val actions = connectionRecoveryTestActions().copy(
            requestUsbPermission = { permissions++ }, connectUsb = { _, _, _ -> connections++ },
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 480.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    ConnectionRecoveryTestContent(state.value, actions)
                }
            }
        }
        compose.onNodeWithTag("connection-usb-empty").performScrollTo().assertIsDisplayed()
        for ((device, reason) in listOf(
            usbDevice(canon = false, ptp = true, permission = false) to R.string.connection_usb_non_canon,
            usbDevice(canon = true, ptp = false, permission = false) to R.string.connection_usb_no_ptp,
        )) {
            compose.runOnIdle { state.value = state.value.copy(usbDiagnostics = UsbPtpDiagnostics(listOf(device))) }
            compose.onNodeWithText(resourceText(reason)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("connection-usb-permission").assertDoesNotExist()
            compose.onNodeWithTag("connection-usb-connect").assertDoesNotExist()
        }
        compose.runOnIdle { state.value = state.value.copy(usbDiagnostics = UsbPtpDiagnostics(listOf(usbDevice()))) }
        compose.onNodeWithTag("connection-usb-permission").performScrollTo().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).performTouchInput { click(center) }
        compose.runOnIdle { state.value = state.value.copy(usbDiagnostics = UsbPtpDiagnostics(listOf(usbDevice(permission = true)))) }
        compose.onNodeWithTag("connection-usb-permission").assertDoesNotExist()
        compose.onNodeWithTag("connection-usb-connect").performScrollTo().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, permissions); assertEquals(1, connections) }
    }

    @Test fun inlineRecoverySuppressesDuplicateErrorButKeepsShutterWarnings() {
        val state = mutableStateOf(CameraUiState(error = "Synthetic connection error", connectionRecovery = ConnectionRecovery(
            ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.TIMEOUT,
        )))
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) { CameraErrorPresentation(state.value, {}) } }
        compose.onNodeWithTag("camera-error-rotation").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(shutterReleaseUnconfirmed = true) }
        compose.onNodeWithText(resourceText(R.string.shutter_release_unconfirmed)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(shutterReleaseUnconfirmed = false, shutterDisconnectWarning = true) }
        compose.onNodeWithText(resourceText(R.string.shutter_disconnect_warning)).assertIsDisplayed()
        compose.runOnIdle { state.value = CameraUiState().withOfflinePreview().copy(error = "Synthetic connection error", connectionRecovery = state.value.connectionRecovery) }
        compose.onNodeWithText("Synthetic connection error").assertIsDisplayed()
    }

    @Test fun compactStringInWideContainerIsNotMistakenForHorizontalClipping() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(300.dp)) {
                Text("Connect", modifier = Modifier.widthIn(max = 200.dp).testTag("text-layout-canary"))
            } }
        }
        assertTextFits("text-layout-canary", captureFailure = false)
    }

    @Test fun actualHorizontalClippingIsStillRejectedEvenWhenHeightAllowsWrapping() {
        compose.setContent {
            MaterialTheme { Text("SYNTHETIC_LONG_UNWRAPPED_LABEL", softWrap = false,
                modifier = Modifier.width(24.dp).height(400.dp).testTag("text-layout-canary")) }
        }
        val failure = assertThrows(AssertionError::class.java) { assertTextFits("text-layout-canary", captureFailure = false) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("Horizontal text clipping"))
    }

    @Test fun actualVerticalClippingIsStillRejected() {
        compose.setContent {
            MaterialTheme { Text("Connect", fontSize = 24.sp,
                modifier = Modifier.width(160.dp).height(8.dp).testTag("text-layout-canary")) }
        }
        val failure = assertThrows(AssertionError::class.java) { assertTextFits("text-layout-canary", captureFailure = false) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("Vertical text clipping"))
    }

    @Test fun ellipsizedControlLabelsAreStillRejected() {
        compose.setContent {
            MaterialTheme { Text("SYNTHETIC LONG CONTROL LABEL", maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(64.dp).testTag("text-layout-canary")) }
        }
        val failure = assertThrows(AssertionError::class.java) { assertTextFits("text-layout-canary", captureFailure = false) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("Ellipsized text"))
    }

    private fun assertTextFits(tag: String, captureFailure: Boolean = true) {
        val nodes = compose.onAllNodes(
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("No text layout found under $tag", nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
            assertTrue("No text layout returned under $tag", layouts.isNotEmpty())
            layouts.forEach { layout ->
                val details = "scenario=$layoutScenario tag=$tag text=${layout.layoutInput.text} " +
                    "size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                    "constraints=${layout.layoutInput.constraints} density=${layout.layoutInput.density} " +
                    "fontSize=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight} " +
                    "lines=${layout.lineCount} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                    "nodeBounds=${node.boundsInWindow}"
                val ellipsized = (0 until layout.lineCount).any(layout::isLineEllipsized)
                // Foundation's String fast path synthesizes this semantics result with the
                // original maxWidth, but retains the tight rendered layoutSize. Paragraph.width
                // therefore includes unused space; its didOverflowWidth is not a clipping test.
                // Check occupied line extents instead, and preserve no-wrap/height/ellipsis gates.
                val lineWidths = (0 until layout.lineCount).map { line ->
                    ceil((layout.getLineRight(line) - layout.getLineLeft(line)).toDouble()).toInt()
                }
                val noWrapWidth = !layout.layoutInput.softWrap &&
                    ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth.toDouble()).toInt() > layout.size.width
                val horizontalClipping = noWrapWidth || lineWidths.any { it > layout.size.width }
                if (captureFailure && (horizontalClipping || layout.didOverflowHeight || ellipsized)) {
                    println("CONNECTION_LAYOUT_FAILURE $details")
                    // All inputs in this fixture are synthetic. Keep the original failure even
                    // if screenshot capture/storage itself is unavailable.
                    runCatching {
                        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                        TestStorage().openOutputFile("connection-layout-$tag.png").use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    }.onFailure { println("CONNECTION_LAYOUT_SCREENSHOT_UNAVAILABLE ${it.javaClass.simpleName}") }
                }
                assertFalse("Ellipsized text: $details", ellipsized)
                assertFalse("Horizontal text clipping: lineWidths=$lineWidths $details", horizontalClipping)
                assertFalse("Vertical text clipping: $details", layout.didOverflowHeight)
            }
        }
    }

    private fun resourceText(id: Int, language: String? = null): String {
        if (language == null) return compose.activity.getString(id)
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return compose.activity.createConfigurationContext(config).getString(id)
    }

    private fun usbDevice(canon: Boolean = true, ptp: Boolean = true, permission: Boolean = false) = UsbCameraDevice(
        deviceName = "synthetic-usb-device", manufacturerName = if (canon) "Canon" else "Synthetic vendor",
        productName = "Synthetic camera", vendorId = if (canon) 0x04A9 else 0x1234, productId = 0x1234,
        deviceClass = 0, deviceSubclass = 0, deviceProtocol = 0, hasPermission = permission,
        interfaces = if (ptp) listOf(UsbCameraInterface(0, 6, 1, 1, emptyList())) else emptyList(),
    )
}

/** Match the actual App's background as well as its color scheme in standalone layout fixtures. */
@Composable
private fun ConnectionRecoveryTestContent(state: CameraUiState, actions: CameraActions) {
    MaterialTheme(colorScheme = OpenEosColorScheme) {
        Box(Modifier.fillMaxSize().background(AppBackground)) { ConnectionScreen(state, actions) }
    }
}

internal fun connectionRecoveryTestActions() = CameraActions(
    setConnectionTarget = {}, setBaseUrl = {}, setUsername = {}, setPassword = {},
    setBridgeBaseUrl = {}, setBridgeToken = {}, scanDesktopBridge = {}, selectBridgeCamera = {},
    useHttpPreset = {}, useHttpsPreset = {}, useSimulatorPreset = {}, enterOfflinePreview = {},
    connect = {}, connectBridge = {}, disconnect = {}, refresh = {}, refreshUsb = {}, requestUsbPermission = {},
    connectUsb = { _, _, _ -> },
    setUiMode = {}, setCaptureMode = {}, setHudVisible = {}, setGridVisible = {}, setLiveViewTapAction = {},
    openPicker = {}, closePicker = {}, setIso = {}, setShutter = {}, setAperture = {}, setWhiteBalance = {},
    setCameraSetting = { _, _ -> }, captureStill = {}, autofocus = {}, driveFocus = { _, _ -> },
    setLiveViewMagnification = {}, toggleRecording = {}, tapFocus = { _, _ -> }, halfPressShutter = {},
    clickWhiteBalance = { _, _ -> }, refreshMedia = {}, loadMediaThumbnail = {}, openMediaPreview = {},
    closeMediaPreview = {}, downloadMedia = {}, deleteMedia = {}, cancelMediaDownload = {},
    refreshLiveView = {}, restartLiveView = {}, setAutoRefresh = {}, setFps = {}, setLiveViewSize = {},
    setLiveViewSource = {}, setAppLanguage = {}, clearError = {},
)
