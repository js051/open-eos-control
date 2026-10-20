"""iOS upload wiring guards only: not compilation or native runtime evidence."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"


class IOSMediaUploadSourceTests(unittest.TestCase):
    def test_immutable_os_picker_uses_explicit_one_shot_opening(self):
        view = (APP / "MediaView.swift").read_text()
        picker = (APP / "MediaUploadPicker.swift").read_text()
        self.assertNotIn(".fileImporter(", view)
        self.assertIn("uploadSelection = camera.beginMediaUploadSelection()", view)
        self.assertIn("startMediaUpload(url, selection: opening, access: .securityScoped)", view)
        self.assertIn("let selection: MediaUploadSelection", picker)
        self.assertIn("guard !delivered, !invalidated else { return }", picker)
        self.assertIn("delivered = true", picker)
        self.assertIn("guard !invalidated, !completed else { return }", picker)
        self.assertIn("coordinator.invalidate()", picker)
        self.assertIn("picker.modalPresentationStyle = .overFullScreen", picker)
        self.assertIn("host.present(picker, animated: true, completion: completion)", picker)
        self.assertIn("if cancelled || isPresented() { dismiss() }", picker)
        self.assertIn("if !registered { dismiss() }", picker)
        self.assertIn("guard !isPresented() else { return }", picker)
        # The stable host updates pending intent, never an existing opening's callback.
        self.assertIn("controller.update(selection: selection, completion: completion)", picker)
        opening = picker.split("private struct Opening {", 1)[1].split("final class Session", 1)[0]
        self.assertIn("let selection: MediaUploadSelection", opening)
        self.assertIn("let completion: (MediaUploadSelection, [URL]?) -> Void", opening)
        self.assertIn("Session(selection: opening.selection, completion: opening.completion)", picker)
        self.assertIn("if let selection, selection == pending?.selection { advance(); return }", picker)
        self.assertIn("presentation.present(self, session.picker) { [self, session] in", picker)
        self.assertIn("presentation.retire(session.picker, ticket) { [self, session] in", picker)
        self.assertIn("session.coordinator.retirement = { [weak self, weak session] completion in", picker)
        self.assertIn("guard session.presentationCompleted, !session.retirementStarted else { return }", picker)
        self.assertNotIn(".sheet(item: $uploadSelection", view)

    def test_app_releases_grant_once_and_never_confirms_by_listing(self):
        app = (APP / "CameraAppState.swift").read_text()
        block = app.split("    func beginMediaUploadSelection()", 1)[1].split("    func setMediaArchive(", 1)[0]
        self.assertIn("CameraMediaUploadPolicy.admits", block)
        self.assertIn("defer { access.release(fileURL) }", block)
        self.assertEqual(block.count("access.release(fileURL)"), 1)
        self.assertNotIn("reconcileCancelledMediaUpload", app)
        self.assertNotIn("caseInsensitiveCompare", block)
        self.assertNotIn("Task.detached", block)
        self.assertIn("guard attempt.outcome == .verified, !Task.isCancelled else { return }", block)
        self.assertIn("guard scope == .recent else { return }", block)
        self.assertIn("mediaUploadResult?.attemptID == attemptID", block)
        self.assertIn("libraryGeneration == mediaLibraryGeneration", block)

    def test_staging_precedes_dispatch_and_receipt_precedes_final_progress(self):
        source = (CORE / "DesktopBridgeClient.swift").read_text()
        block = source.split("    public func uploadMedia(", 1)[1].split("    public func deleteMedia", 1)[0]
        ordered = [
            "validateMutation",
            "CameraMediaUploadStagedFile.create",
            "attempt.markDispatched()",
            "transport.upload(request, from: staged.url)",
            "guard generation == sessionGeneration",
            "CameraMediaUploadReceipt.mediaItem",
            "attempt.recordVerified(item)",
        ]
        self.assertEqual([block.index(value) for value in ordered], sorted(block.index(value) for value in ordered))
        self.assertNotIn("Self.parseMediaItem", block)
        self.assertIn("guard response.statusCode == 201", block)
        self.assertEqual(block.count("transport.upload("), 1)

    def test_upload_delegate_refuses_redirect_without_changing_other_transports(self):
        text = (CORE / "CameraHTTPTransport.swift").read_text()
        upload = text.split("private final class CameraHTTPUploadTaskHandle", 1)[1]
        redirect = upload.split("willPerformHTTPRedirection", 1)[1].split("    func urlSession(", 1)[0]
        self.assertIn("completionHandler(nil)", redirect)
        self.assertNotIn("completionHandler(request)", redirect)
        self.assertIn("if error == nil, let status", upload)
        self.assertIn("completionHandler(boundedListing ? nil : request)", text)

    def test_receipt_has_strict_attempt_ascii_and_raw_integer_boundaries(self):
        text = (CORE / "CameraMediaUpload.swift").read_text()
        for guard in [
            "responseAttemptID == expectedAttemptID",
            "response.statusCode == 201",
            "size <= 4_294_967_295",
            "decodeForegroundJSONObject(response.body)",
            "token.allSatisfy",
            "currentOwner: Owner?",
            "guard !cancelled, !dispatched",
            "guard dispatched, verifiedItem == nil",
        ]:
            self.assertIn(guard, text)
        self.assertNotIn("caseInsensitiveCompare", text)
        self.assertNotIn("lowercased()", text)

    def test_callers_require_selection_and_native_vectors_handle_null_owner(self):
        paths = list((ROOT / "ios").rglob("*.swift"))
        for path in paths:
            text = path.read_text()
            for call in re.findall(r"\.startMediaUpload\([^\n]+", text):
                self.assertIn("selection:", call, str(path))
        tests = (ROOT / "ios/OpenEOSControl/Tests/MediaUploadContractTests.swift").read_text()
        self.assertNotIn('row["currentOwner"] as! String', tests)
        self.assertIn('row["currentOwner"] as? String', tests)
        for group in ["outcomeCases", "admissionCases", "publicationCases", "receiptCases", "journeyCases"]:
            self.assertIn(group, tests)

    def test_bilingual_terminal_recovery_and_previous_owner_labels_exist(self):
        keys = [
            "media_upload_not_sent",
            "media_upload_uncertain",
            "media_upload_manual_recovery",
            "media_upload_previous_connection",
            "media_upload_refresh_failed",
            "media_upload_review_files",
        ]
        for locale in ["en", "zh-Hant"]:
            text = (APP / f"Resources/{locale}.lproj/Localizable.strings").read_text()
            for key in keys:
                self.assertEqual(text.count(f'"{key}" ='), 1)

    def test_real_wire_and_os_picker_native_gates_are_authored(self):
        wire = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionMediaUploadTests.swift").read_text()
        ui = (ROOT / "ios/OpenEOSControl/UITests/OpenEOSControlUITests.swift").read_text()
        self.assertIn("NWListener", wire)
        self.assertIn("testRealHTTP307And308NeverReplaySelectedFileAtRedirectDestination", wire)
        self.assertIn("XCTAssertEqual(peer.redirectPosts(), 0)", wire)
        self.assertIn("testRealDocumentPickerCancellationAndReopeningAfterReconnectInBothLanguages", ui)
        self.assertIn("testRealPickerSelectionCancelOrLostAckKeepsUncertaintyAcrossReconnect", ui)
        self.assertIn("No delegate injection", ui)


if __name__ == "__main__":
    unittest.main()
