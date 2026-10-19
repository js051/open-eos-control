"""Supplementary source guards only; no Swift execution or physical-camera proof."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"


class IOSPartialControlReceiptSourceTests(unittest.TestCase):
    def setUp(self):
        self.source = (CORE / "CCAPIClient.swift").read_text()
        self.validation = self.source.split("private func validate(_ response:", 1)[1].split(
            "private func URLForPath", 1
        )[0]

    def test_partial_receipt_gate_follows_existing_http_rejection(self):
        gate = (
            'if Self.requiresCompleteControlReceipt(method: request.httpMethod ?? "GET", path: request.url?.path ?? "")'
        )
        self.assertLess(self.validation.index("throw CCAPIError.http("), self.validation.index(gate))
        self.assertIn('response.statusCode == 206 || response.header("content-range") != nil', self.validation)
        self.assertNotIn("decodeJSONObject", self.validation)
        self.assertNotIn("response.body.isEmpty", self.validation)

    def test_route_scope_matches_existing_eight_bridge_control_pairs(self):
        policy = self.validation.split("static func requiresCompleteControlReceipt", 1)[1]
        actual = set()
        for method, case_body in re.findall(r'case "(POST|PUT|DELETE)":(.*?)(?=case |default:)', policy, re.S):
            for suffix in re.findall(r'"(/shooting/[^\"]+)"', case_body):
                actual.add((method, suffix))
        self.assertEqual(
            actual,
            {
                ("POST", "/shooting/control/shutterbutton"),
                ("POST", "/shooting/control/shutterbutton/manual"),
                ("PUT", "/shooting/control/shutterbutton/manual"),
                ("POST", "/shooting/control/af"),
                ("POST", "/shooting/liveview"),
                ("DELETE", "/shooting/liveview"),
                ("POST", "/shooting/liveview/rtp"),
                ("DELETE", "/shooting/liveview/multipart"),
            },
        )
        self.assertIn("default:\n            return false", policy)
        self.assertIn("path.hasSuffix", policy)

    def test_empty_case_insensitive_header_is_presence_evidence(self):
        response = (
            (CORE / "CameraHTTPTransport.swift").read_text().split("public struct CameraHTTPRecordingResponse", 1)[0]
        )
        self.assertIn("result[entry.key.lowercased()] = entry.value", response)
        self.assertIn("headers[name.lowercased()]", response)
        self.assertNotIn("isEmpty", self.validation)

    def test_validation_precedes_owner_discharge_on_existing_raw_control_paths(self):
        for start, end, acknowledgement in [
            (
                "private func sendOwnedShutterCommand",
                "public func startHeldAutofocus",
                "try validate(response, request: request)",
            ),
            (
                "private func performHeldAutofocusStart",
                "public func stopHeldAutofocus",
                "try validate(response, request: press)",
            ),
            (
                "private func sendLiveViewStart",
                "private func releasePendingLiveView",
                "try validate(response, request: start)",
            ),
            (
                "private func sendLiveViewStop",
                "public func currentLiveViewSource",
                "try validate(response, request: stop.stop)",
            ),
            (
                "private func performShortControl",
                "private func parseSimulatorCapabilities",
                "try validate(response, request: press)",
            ),
        ]:
            body = self.source.split(start, 1)[1].split(end, 1)[0]
            self.assertIn(acknowledgement, body)
        stop = self.source.split("private func sendLiveViewStop", 1)[1].split("public func currentLiveViewSource", 1)[0]
        self.assertLess(
            stop.index("try validate(response, request: stop.stop)"), stop.index("pendingLiveViewStop = nil")
        )

    def test_native_counterexamples_cover_retained_owner_and_full_receipt_compatibility(self):
        tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/CCAPIPartialControlReceiptTests.swift").read_text()
        for case in [
            "testPartialBulbPressAndReleaseRetainExactOwnerUntilExplicitFullRelease",
            "testHeldAFPartialReceiptsRetainExactOwnerAndIgnoreWrongOwnerStop",
            "testShortControlPartialPressAndReleaseRequireOnlyStoredReleaseRetry",
            "testDirectCaptureRejectsPartialAcknowledgementWithoutReplayingCapture",
            "testLiveViewPartialReceiptsRetainOriginalDeleteOrPostOffUntilExplicitFullACK",
            "testRTPPartialStartAndStopRetainExactOwnerUntilExplicitFullACK",
            "testFull200EmptyPlainJSONAnd204RepliesRemainValidForPhysicalControls",
            "testUnrelatedDiscoveryGETRetainsExistingPartialResponseContract",
            "testNon2xxControlStillReportsExistingHTTPRejection",
        ]:
            self.assertIn("func " + case, tests)
        self.assertIn('"cOnTeNt-RaNgE": ""', tests)
        self.assertIn("XCTAssertEqual(retry.first?.body, before.last?.body)", tests)


if __name__ == "__main__":
    unittest.main()
