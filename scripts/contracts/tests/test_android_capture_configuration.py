"""Source wiring and fixture integrity only; not Android SDK or HTTP runtime validation."""
import hashlib
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"
UI = ROOT / "android/app/src/main/java/dev/openeos/control/ui"


class AndroidCaptureConfigurationWiringTest(unittest.TestCase):
    def test_exact_shared_fixtures_are_unchanged_and_explicitly_packaged(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        for name, digest in [
            ("capture-configuration-vectors.json", "7a3496afc0e9f0523399a41f251d1e243f59318866549f37aeb29e6733a01aa5"),
            ("exact-value-vectors.json", "0ce969f4faaf795daae1fcc680807f1841313ef487ad3fefb1f8a682a92263c7"),
        ]:
            path = ROOT / "contracts/capture-configuration/v1" / name
            self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertIn(f'"../../contracts/capture-configuration/v1/{name}"', build)

    def test_both_http_boundaries_observe_dispatch_and_validated_ack_before_return(self):
        for name in ["CcapiClient.kt", "DesktopBridgeClient.kt"]:
            text = (DATA / name).read_text()
            start = text.index("    private suspend fun requestJson(")
            end = text.index("    private suspend fun requestEventJson", start)
            boundary = text[start:end]
            self.assertLess(boundary.index("it.beforeDispatch("), boundary.index("call.execute().use"))
            self.assertIn("readCaptureConfigurationJsonText(input, it.contentLength())", boundary)
            self.assertIn("captureResponse?.invoke", boundary)
            self.assertIn(".retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)", text)
            self.assertIn(".authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).cache(null)", text)
            self.assertIn("override fun isOneShot() = true", text)
        bridge = (DATA / "DesktopBridgeClient.kt").read_text()
        self.assertIn('captureSessionEngine == "ccapi" && receipt != null', bridge)
        directory = bridge[bridge.index("    suspend fun createDirectory("):bridge.index("    suspend fun setFileNaming(")]
        self.assertLess(directory.index("val readTarget ="), directory.index("val created = postJson("))
        self.assertIn("readCaptureConfiguration(CaptureConfigurationOperation.CREATE_DIRECTORY, readTarget, owner)", directory)
        self.assertIn("captureConfigurationPostAckReadback(evidence)", directory)
        self.assertIn("check(session === owner)", bridge)


    def test_recovery_has_no_mutation_or_whole_card_listing(self):
        for name, end_marker in [("CcapiClient.kt", "    suspend fun sleepCamera"),
                                 ("DesktopBridgeClient.kt", "    suspend fun syncCameraClock")]:
            text = (DATA / name).read_text()
            start = text.index("    suspend fun readCaptureConfiguration(")
            block = text[start:text.index(end_marker, start)]
            for forbidden in ["postJson(", "putJson(", "deleteMedia(", "listMedia(", "capabilities()"]:
                self.assertNotIn(forbidden, block)
            self.assertIn("withTimeout(15_000L)", block)
        direct = (DATA / "CcapiClient.kt").read_text()
        self.assertIn("directoryReadback?.invoke(selection?.let", direct)
        self.assertIn("loadShootingSettings(directoryReadback =", direct)

    def test_drafts_history_and_recovery_are_wired_to_product_ui(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        screen = (UI / "CameraControlScreen.kt").read_text()
        workflow = (UI / "CaptureConfigurationWorkflow.kt").read_text()
        self.assertIn("val owner = backend", (DATA / "CameraRepository.kt").read_text())
        self.assertIn("captureConfigurationAttempts[id] ?: return", vm)
        self.assertIn("state.captureConfigurationHistory.none { it.id == id }", vm)
        self.assertIn("captureConfigurationAttempts.keys.retainAll(retainedIds)", vm)
        self.assertIn(".take(20)", workflow)
        self.assertIn("mergeCaptureConfigurationDirectorySettings", vm)
        self.assertIn("captureConfigurationDirectorySummary(receipt, current.lastCreatedDirectoryName)", vm)
        self.assertIn("if (!receipt.cancelled && receipt.draft.request.operation", workflow)
        self.assertIn("remember(draftToken) { mutableStateOf(value.stillUserSetting1) }", screen)
        self.assertIn("remember(draftToken) { mutableStateOf(value.stillUserSetting2) }", screen)
        self.assertIn('testTag(if (history) "capture-configuration-recover-${receipt.id}"', screen)
