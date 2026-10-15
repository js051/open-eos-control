"""Static delete wiring/fixture guards. Not Swift compilation or native proof."""
import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"


class IOSMediaDeleteSourceTests(unittest.TestCase):
    def test_clients_mark_once_and_do_not_use_generic_success(self):
        for filename in ["CCAPIClient.swift", "DesktopBridgeClient.swift"]:
            block = (CORE / filename).read_text().split("    public func deleteMedia(", 1)[1].split("    public func diagnosticReport(", 1)[0]
            self.assertEqual(block.count("transport.sendMediaDelete(request)"), 1)
            self.assertLess(block.index("attempt.markDispatched()"), block.index("transport.sendMediaDelete"))
            self.assertIn("attempt.record(response)", block)
            self.assertIn("item.protected != true", block)
            self.assertIn("item.writable != false", block)
            self.assertNotIn("requestOK(", block)
            self.assertNotIn("for ", block)

    def test_transport_is_separate_no_redirect_body_resupply_or_auth_replay(self):
        text = (CORE / "CameraHTTPDeleteTask.swift").read_text()
        self.assertEqual(text.count("session.uploadTask(withStreamedRequest:"), 1)
        for token in ["completionHandler(nil)", "!suppliedBody && !cancelled", "suppliedBody = true",
                      "completionHandler(allowed ? InputStream(data: Data()) : nil)", "cancelAuthenticationChallenge",
                      "NSURLAuthenticationMethodServerTrust", "urlCredentialStorage = nil"]:
            self.assertIn(token, text)
        self.assertNotIn("completionHandler(request)", text)
        generic = (CORE / "CameraHTTPTransport.swift").read_text()
        self.assertIn("CameraHTTPDeleteTask(configuration: streamConfiguration).run(request)", generic)
        self.assertIn("completionHandler(boundedListing ? nil : request)", generic)

    def test_response_contract_is_204_empty_and_explicit_refusal_only(self):
        text = (CORE / "CameraMediaDelete.swift").read_text()
        for token in ["response.body.isEmpty", 'response.header("transfer-encoding") == nil',
                      'response.header("content-range") == nil', "CameraHTTPResponseIntegrity.validate",
                      "[400, 401, 403, 404, 405, 409, 412, 413, 415, 422, 429]",
                      "guard !cancelled, !sent", "if dispatched { return confirmedReceipt"]:
            self.assertIn(token, text)
        self.assertNotIn("(200..<300)", text)
        self.assertNotIn("(400..<500)", text)

    def test_ui_real_deletion_uses_frozen_owner_review_and_destructive_confirmation(self):
        view = (APP / "MediaView.swift").read_text()
        panel = (APP / "MediaDeletePanel.swift").read_text()
        self.assertIn("pendingDeletion: MediaDeleteDraft?", view)
        self.assertIn("owner: presentation.owner", view)
        for token in ["draft.owner != camera.mediaLibrarySessionID", "camera.startMediaDelete(draft, confirmed: true)",
                      'role: .destructive', "ForEach(draft.rows)", "Text(row.item.id)", "row.source.rawValue", "row.qualificationUnknown"]:
            self.assertIn(token, panel)
        self.assertIn("MediaDeletePanel(", (APP / "MediaBatchPanel.swift").read_text())

    def test_execution_has_no_retry_unprotect_list_scan_or_selection_clear(self):
        app = (APP / "CameraAppState.swift").read_text()
        block = app.split("    private func performMediaDelete(", 1)[1].split("    var canClearMediaDeleteHistory:", 1)[0]
        self.assertEqual(block.count("session.deleteMedia("), 1)
        for forbidden in ["setMediaProtection", "listMedia(", "mediaInfo(", "selectedMediaItems = []", "removeDownloadedFile()"]:
            self.assertNotIn(forbidden, block)
        self.assertIn("outcome == .uncertain || outcome == .notSent", block)
        self.assertIn("guard owns() else { return }", block)
        self.assertIn("selectionToken: row.selectionToken", block)
        apply = app.split("    private func applyDeletedMedia(", 1)[1].split("    private func applyUpdatedMedia(", 1)[0]
        self.assertIn("CameraMediaDeletePolicy.consumesSelection", apply)
        self.assertNotIn("removeDownloadedFile()", apply)

    def test_history_retirement_and_inspection_cannot_rewrite_outcomes(self):
        app = (APP / "CameraAppState.swift").read_text()
        retire = app.split("    private func retireMediaDelete()", 1)[1].split("    private func performMediaDelete", 1)[0]
        self.assertNotIn("mediaDeleteRuns = []", retire)
        self.assertIn("mediaDeleteAttempt?.outcome ?? .notSent", retire)
        inspect = app.split("    func inspectMediaDelete(", 1)[1].split("    func diagnosticReport", 1)[0]
        self.assertIn("canInspectMediaDelete", inspect)
        self.assertIn("startMediaLibraryLoad()", inspect)
        self.assertNotIn(".status =", inspect)
        self.assertNotIn(".all", inspect)
        model = (APP / "MediaDeleteJourney.swift").read_text()
        for token in ["maximumBatches = 20", "maximumRows = 2_000", "maximumBatchRows = 1_000"]:
            self.assertIn(token, model)

    def test_sources_use_verified_bridge_engine_and_not_unknown_provider_assumptions(self):
        model = (APP / "MediaDeleteJourney.swift").read_text()
        self.assertIn('bridgeEngine == "libgphoto2" ? .desktopHost : .unknown', model)
        self.assertIn('bridgeEngine == "ccapi"', model)
        self.assertIn("return .unknown // No guessed EDSDK", model)
        state = (APP / "CameraAppState.swift").read_text()
        self.assertIn("let deleteEngine = await newSession.mediaDeleteEngine()", state)
        self.assertIn("mediaDeleteBridgeEndpoint", state)

    def test_shared_corpus_is_hash_pinned_and_native_gates_are_authored(self):
        path = ROOT / "contracts/media-delete/v1/delete-outcome-vectors.json"
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        self.assertEqual(digest, "f42c67bc2b80185ac2198d00a59a9188b4424a4e6fa21a0f9df69f26fef15a68")
        native = (APP.parent / "Tests/MediaDeleteJourneyTests.swift").read_text()
        self.assertIn(digest, native)
        for name in ["eligibility", "admission", "outcome", "httpReceipt", "selection", "roster", "batch"]:
            self.assertIn(f'"{name}"', native)
        self.assertEqual(sum(len(json.loads(data)[k]) for k in ["eligibility", "admission", "outcome", "httpReceipt", "selection", "roster", "batch"]), 540)
        wire = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionMediaDeleteTests.swift").read_text()
        self.assertIn("NWListener", wire)
        self.assertIn('XCTAssertEqual(peer.targets(), ["/exact-object"]', wire)
        ui = (APP.parent / "UITests/OpenEOSControlUITests.swift").read_text()
        self.assertIn("testExactDeletionReviewCancelMixedSourcesLostACKAndRetiredHistoryInBothLanguages", ui)

    def test_retired_receipt_is_recorded_before_current_owner_side_effect_guard(self):
        for filename, closing_guard in [("CCAPIClient.swift", "guard !closing else"),
                                         ("DesktopBridgeClient.swift", "guard generation == sessionGeneration, !isClosing else")]:
            block = (CORE / filename).read_text().split("    public func deleteMedia(", 1)[1].split("    public func diagnosticReport(", 1)[0]
            after_transport = block.split("let response = try await transport.sendMediaDelete(request)", 1)[1]
            self.assertLess(after_transport.index("try attempt.record(response)"), after_transport.index(closing_guard))
        app = (APP / "CameraAppState.swift").read_text()
        after_response = app.split("await mediaDeleteAfterResponse?()", 1)[1]
        self.assertLess(after_response.index("settleMediaDeleteReceipt(draft: draft, row: row, attempt: attempt)"), after_response.index("guard owns() else"))
        settle = app.split("    private func settleMediaDeleteReceipt(", 1)[1].split("    var canClearMediaDeleteHistory:", 1)[0]
        for token in ["$0.id == draft.id && $0.owner == draft.owner", "$0.id == row.id", "$0.attemptID == attempt.id",
                      "!mediaDeleteRuns[batch].rows[index].receiptSettled", "receiptSettled = true"]:
            self.assertIn(token, settle)
        for forbidden in [".append(", "applyDeletedMedia(", "end(.media", "session.deleteMedia("]:
            self.assertNotIn(forbidden, settle)

    def test_native_retired_receipt_gate_keeps_new_owner_selection_lock_and_cleared_history(self):
        test = (APP.parent / "Tests/MediaDeleteJourneyTests.swift").read_text()
        for token in ["testRetiredReceiptBeforeResponseAndAfterClientRecordCannotMutateReplacementOwner", "for bridge in [false, true]",
                      "for pauseAfterClientRecord in [false, true]", 'for response in ["confirmed", "rejected", "uncertain"]',
                      "XCTAssertEqual(state.busyOperations, currentBusy)", "XCTAssertEqual(oldWrites, [a.id]",
                      "XCTAssertEqual(newWrites, [a.id]", "Late receipt cannot resurrect cleared history"]:
            self.assertIn(token, test)
        self.assertNotIn("retire ? .uncertain : .confirmed", test)

    def test_history_evicts_whole_inactive_batches_and_invalidates_evicted_confirmation(self):
        state = (APP / "CameraAppState.swift").read_text()
        reserve = state.split("    private func reserveMediaDeleteHistory(", 1)[1].split("    /// Compatibility", 1)[0]
        for token in ["!mediaDeleteRuns.contains(where: { $0.running })", "!mediaDeleteRuns[evicted].running",
                      "mediaDeleteRuns.removeFirst(evicted)", "mediaDeleteHistoryEpoch = UUID()",
                      "MediaDeleteHistory.maximumBatches", "MediaDeleteHistory.maximumRows"]:
            self.assertIn(token, reserve)
        native = (APP.parent / "Tests/MediaDeleteJourneyTests.swift").read_text()
        for token in ["testHistoryEvictsWholeOldestBatchAndClearOrEvictionInvalidatesOldReview",
                      "testHistoryRowLimitEvictsWholeThousandRowBatchWithoutTruncating",
                      'historyDisposition == "evict"', "Busy admission cannot evict or shift an active batch",
                      "Evicted confirmation cannot be replayed"]:
            self.assertIn(token, native)

    def test_bilingual_keys_unique_and_complete(self):
        keys = []
        for locale in ["en", "zh-Hant"]:
            text = (APP / f"Resources/{locale}.lproj/Localizable.strings").read_text()
            found = re.findall(r'^"(media_delete_[^"]+)"\s*=', text, re.M)
            self.assertEqual(len(found), len(set(found)))
            self.assertGreaterEqual(len(found), 40)
            keys.append(set(found))
        self.assertEqual(*keys)


if __name__ == "__main__":
    unittest.main()
