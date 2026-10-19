"""Android upload integration guards. These do not execute Android, HTTP, USB, or Compose."""

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "android/app/src/main"
DATA = MAIN / "java/dev/openeos/control/data"
UI = MAIN / "java/dev/openeos/control/ui"


def upload_section(path, next_method="    suspend fun deleteMedia("):
    source = path.read_text()
    start = source.index("    suspend fun uploadMedia(")
    return source[start : source.index(next_method, start)]


class AndroidUploadOutcomeSourceTests(unittest.TestCase):
    def test_no_inventory_guess_or_automatic_cancel_reconciliation(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        section = vm[vm.index("    fun uploadMedia(") : vm.index("    fun deleteMedia(")]
        self.assertNotIn("reconcileCancelledBridgeUpload", vm)
        self.assertNotIn("items.any", section)
        self.assertNotIn("firstOrNull", section)
        self.assertNotIn("repository.deleteMedia", section)
        self.assertEqual(1, section.count("repository.uploadMedia("))
        cancel = section[section.index("    fun cancelMediaUpload()") :]
        self.assertIn("mediaUploadAttempt?.cancel()", cancel)
        self.assertNotIn("listMedia", cancel)

    def test_actual_http_boundaries_and_bounded_strict_receipts(self):
        for filename, size_key in (("DesktopBridgeClient.kt", "sizeBytes"), ("CcapiClient.kt", "size_bytes")):
            section = upload_section(DATA / filename)
            self.assertLess(section.index("evidence.beforeDispatch()"), section.index("call.execute()"))
            self.assertIn("uploadContext.ensureActive()", section)
            self.assertIn("readStrictMediaJsonText", section)
            self.assertLess(section.index("StrictMediaJson(responseBody)"), section.index("JSONObject(responseBody)"))
            self.assertIn(f'strictReceipt.topLevelToken("{size_key}")', section)
            self.assertLess(section.index("validUploadReceipt("), section.index("evidence.onConfirmed(it)"))
        bridge = upload_section(DATA / "DesktopBridgeClient.kt")
        self.assertIn("MAX_UPLOAD_RESPONSE_BYTES", bridge)
        self.assertGreater(
            bridge.rindex("session?.let(::requireCurrentSession)"), bridge.index("JSONObject(responseBody)")
        )

    def test_usb_dispatch_is_before_send_object_info_and_exact_handle_verification_is_retained(self):
        ptp = (DATA / "PtpProtocol.kt").read_text()
        section = ptp[ptp.index("    suspend fun uploadObject(") : ptp.index("    suspend fun executeOperation(")]
        self.assertLess(section.index("beforeDispatch()"), section.index("transport.send("))
        backend = (DATA / "UsbPtpCameraBackend.kt").read_text()
        section = backend[
            backend.index("    override suspend fun uploadMedia(") : backend.index(
                "    private suspend fun abortUploadSession"
            )
        ]
        self.assertIn("beforeDispatch = { evidence.beforeDispatch(); dispatchStarted = true }", section)
        self.assertIn("if (dispatchStarted) abortUploadSession(ptp)", section)
        self.assertIn("ptp.objectInfo(sent.objectHandle)", section)
        self.assertIn("readback.storageId != sent.storageId", section)
        self.assertIn("readback.parentObject != sent.parentObject", section)
        self.assertIn("readback.sizeBytes != sizeBytes", section)
        self.assertIn("ignoreCase = false", section)
        self.assertIn(".also(evidence.onConfirmed)", section)

    def test_all_callback_publications_and_finally_share_owner_and_attempt_fence(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        section = vm[vm.index("    fun uploadMedia(") : vm.index("    fun deleteMedia(")]
        self.assertIn("ownsMediaUpload(", section)
        self.assertIn(
            "attempt, mediaUploadAttempt, state.info, current.info, generation, cameraSessionGeneration", section
        )
        self.assertEqual(1, section.count("_uiState.update"))
        self.assertIn("_uiState.update { if (owns(it)) transform(it) else it }", section)
        self.assertIn("onConfirmed = attempt::confirm", section)
        self.assertIn("attempt.confirmedResult()", section)
        self.assertIn("afterFinally = {", section)
        self.assertIn("MediaUploadStatus.UNCERTAIN && state.transport == CameraTransport.USB_PTP", section)

    def test_session_retirement_closes_the_synchronized_gate_before_generation_change(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        lines = vm.splitlines()
        increments = [i for i, line in enumerate(lines) if "cameraSessionGeneration += 1" in line]
        self.assertEqual(3, len(increments))
        for index in increments:
            self.assertEqual("mediaUploadAttempt?.cancel()", lines[index - 1].strip())

    def test_optional_refresh_cannot_demote_or_confirm_upload(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        section = vm[vm.index("    fun uploadMedia(") : vm.index("    fun deleteMedia(")]
        self.assertIn("repository.listMedia(maximumItemsFor(state.mediaLibraryScope))", section)
        self.assertIn("mediaUploadRefreshFailed = outcome == MediaUploadStatus.CONFIRMED && !refreshed", section)
        self.assertLess(section.index("attempt.confirmedResult()"), section.index("repository.listMedia("))
        self.assertNotIn("repository.refreshCapabilities()", section)

    def test_receipts_require_201_and_integer_wire_tokens_without_unicode_folding(self):
        source = (DATA / "MediaUploadAttempt.kt").read_text()
        for guard in (
            "httpStatus == 201",
            "bytesTransferred == expectedSize",
            "sizeToken.toLongOrNull() == expectedSize",
            'Regex("[1-9][0-9]*")',
            "expectedAttempt === currentAttempt",
            "expectedConnection === currentConnection",
        ):
            self.assertIn(guard, source)
        self.assertNotIn("ignoreCase", source)
        self.assertNotIn("lowercase", source)
        self.assertNotIn("trim()", source)
        self.assertIn("if (finished || receipt != null) return", source)
        self.assertIn("if (cancelled || finished) throw CancellationException", source)

    def test_existing_single_use_picker_owner_is_retained(self):
        source = (UI / "CameraMediaPickerRequests.kt").read_text()
        self.assertIn("request.generation == generation && request.connection === connection", source)
        self.assertIn("pending = null", source)
        vm = (UI / "CameraViewModel.kt").read_text()
        picker = vm[
            vm.index("    internal fun completeMediaPicker(") : vm.index(
                "    internal fun", vm.index("    internal fun completeMediaPicker(") + 5
            )
        ]
        self.assertLess(picker.index("mediaPickerRequests.consume("), picker.index("uploadMedia(context, uri)"))

    def test_bilingual_ui_distinguishes_preparing_not_sent_unknown_and_manual_inspection(self):
        required = {
            "preparing_media_upload",
            "media_upload_not_sent",
            "media_upload_uncertain",
            "media_upload_usb_uncertain",
            "media_upload_refresh_failed",
            "media_upload_inspect",
        }
        for folder in ("values", "values-zh-rTW"):
            values = {
                item.attrib["name"]: item.text
                for item in ET.parse(MAIN / "res" / folder / "strings.xml").getroot()
                if item.tag == "string"
            }
            self.assertTrue(required <= values.keys())
        screen = (UI / "MediaScreen.kt").read_text()
        self.assertIn("TextButton(onClick = actions.refreshMedia", screen)
        self.assertIn("state.mediaUploadStatus == MediaUploadStatus.PREPARING", screen)
        self.assertIn("state.mediaUploadStatus == MediaUploadStatus.UNCERTAIN", screen)


if __name__ == "__main__":
    unittest.main()
