"""Supplementary source guards only; not Swift compile/runtime or camera proof."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"


class IOSFocusReceiptSourceTests(unittest.TestCase):
    def setUp(self):
        self.source = (CORE / "CCAPIClient.swift").read_text()
        self.validation = self.source.split("private func validate(_ response:", 1)[1].split(
            "static func requiresCompleteFocusReceipt", 1
        )[0]
        self.policy = self.source.split("static func requiresCompleteFocusReceipt", 1)[1].split(
            "static func requiresCompleteControlReceipt", 1
        )[0]

    def test_three_pair_policy_is_separate_from_existing_release_policy(self):
        pairs = set()
        for method, body in re.findall(r'case "(POST|PUT|DELETE)":(.*?)(?=case |default:)', self.policy, re.S):
            pairs.update((method, suffix) for suffix in re.findall(r'"(/shooting/[^\"]+)"', body))
        self.assertEqual(
            pairs,
            {
                ("POST", "/shooting/control/drivefocus"),
                ("PUT", "/shooting/liveview/afframeposition"),
                ("POST", "/shooting/liveview/clickwb"),
            },
        )
        self.assertIn("path.hasSuffix", self.policy)
        self.assertIn("default:\n            return false", self.policy)
        self.assertEqual(self.source.count("Self.requiresCompleteFocusReceipt("), 1)

    def test_partial_gate_keeps_non2xx_and_body_contracts(self):
        gate = (
            'if Self.requiresCompleteFocusReceipt(method: request.httpMethod ?? "GET", path: request.url?.path ?? "")'
        )
        self.assertLess(self.validation.index("throw CCAPIError.http("), self.validation.index(gate))
        focus_gate = self.validation.split(gate, 1)[1]
        self.assertIn('response.statusCode == 206 || response.header("content-range") != nil', focus_gate)
        self.assertIn('throw CCAPIError.invalidResponse("Camera control acknowledgement was incomplete.")', focus_gate)
        for forbidden in ["decodeJSONObject", "response.body", "timeout", "transport.", "Task", "retry"]:
            self.assertNotIn(forbidden, focus_gate)

    def test_production_command_ack_precedes_success_observation(self):
        for name, end, ack, feature in [
            ("public func driveFocus(", "public func startLiveView(", "try await commandOK(", ".focusDrive"),
            (
                "public func tapFocus(",
                "public func clickWhiteBalance(",
                "try await sendCoordinateCommand(",
                ".tapFocus",
            ),
            (
                "public func clickWhiteBalance(",
                "public func driveFocus(",
                "try await sendCoordinateCommand(",
                ".clickWhiteBalance",
            ),
        ]:
            body = self.source.split(name, 1)[1].split(end, 1)[0]
            self.assertLess(body.index(ack), body.rindex("observedFeatures.insert(" + feature + ")"))
        coordinate = self.source.split("private func sendCoordinateCommand(", 1)[1].split(
            "private func ensureLiveViewGeometryForNativeStream", 1
        )[0]
        before, after = coordinate.split("let response = try await transport.send(command)", 1)
        self.assertIn("try requireLiveViewGeometryOwner(owner)", before)
        self.assertLess(
            after.index("try requireLiveViewGeometryOwner(owner)"),
            after.index("try validate(response, request: command)"),
        )
        self.assertEqual(coordinate.count("transport.send(command)"), 1)
        request_ok = self.source.split("private func requestOK(", 1)[1].split("private func beginCameraMutation", 1)[0]
        self.assertIn("try validate(response, request: request)", request_ok)

    def test_native_matrix_uses_production_client_with_real_geometry_and_no_replay(self):
        tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/CCAPIFocusReceiptTests.swift").read_text()
        for name in [
            "testFocusReceiptPolicyIsExactlyThreeMethodPathPairs",
            "testPartialReceiptsRejectAllThreeCommandsWithoutAcceptanceObservationOrReplay",
            "testFullEmptyPlainJSONAndLegacy2xxReceiptsRemainAccepted",
            "testNon2xxFocusReceiptsPreserveHTTPStatusMethodURLAndBody",
            "testCoordinateCommandsStillRequireCurrentGeometryBeforeSending",
        ]:
            self.assertIn("func " + name, tests)
        for evidence in [
            "client.driveFocus(",
            "client.tapFocus(",
            "client.clickWhiteBalance(",
            "client.startLiveView(",
            "CCAPILiveViewTestFixture.jpeg",
            '"positionx": 1600, "positiony": 3200',
            "XCTAssertEqual(writes.count, 1",
            "XCTAssertFalse(capabilities.evidence.observedFeatures.contains(command.feature))",
            '"cOnTeNt-RaNgE": ""',
            '"Content-Length": "2"',
            "statusCode: 201",
            "statusCode: 202",
            "statusCode: 205",
            "statusCode: 299",
        ]:
            self.assertIn(evidence, tests)


if __name__ == "__main__":
    unittest.main()
