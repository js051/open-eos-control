"""Source wiring guards only. These do not execute Swift or prove native behavior."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"


class IOSMaintenanceRestoreTokenSourceTests(unittest.TestCase):
    def setUp(self):
        self.source = (CORE / "DesktopBridgeClient.swift").read_text()
        self.stop = self.source.split("private func stopMaintenanceLiveView(", 1)[1].split(
            "private static func validMaintenanceRestoreToken", 1
        )[0]
        self.restore = self.source.split("public func restoreMaintenanceLiveView(", 1)[1].split(
            "public func captureStill(", 1
        )[0]

    def test_prepare_only_actual_keep_power_cleaning_and_strict_current_response(self):
        self.assertIn(
            (
                "prepareRestore: intent.operation == .sensorCleaning && !intent.autoPowerOff &&"
                " attempt.liveViewRestoreRequest != nil"
            ),
            self.source,
        )
        self.assertLess(
            self.stop.index("try attempt.checkActive()", self.stop.index("transport.sendMaintenance")),
            self.stop.index("captureMaintenanceRestoreToken"),
        )
        self.assertLess(
            self.stop.index("guard liveViewRevision == revision"), self.stop.index("captureMaintenanceRestoreToken")
        )
        self.assertIn('Self.validMaintenanceRestoreToken(body["restoreToken"])', self.stop)
        self.assertIn("if prepareRestore { stop.restoreToken = nil; stop.restoreWarningRevision = nil }", self.stop)

    def test_start_consumes_attempt_and_freezes_obligation_before_await(self):
        for marker in [
            "let restoreToken = attempt.maintenanceRestoreToken",
            "stop.restoreToken = restoreToken",
            "stop.restoreWarningRevision = liveViewWarningRevision",
            "try attempt.consumeMaintenanceRestoreToken(restoreToken)",
            "liveViewStop = stop",
        ]:
            self.assertLess(self.restore.index(marker), self.restore.index("transport.sendMaintenance(request)"))
        self.assertIn('body["restoreToken"] as? String == restoreToken', self.restore)
        self.assertIn('body["restoreOutcome"] as? String == "started"', self.restore)
        self.assertIn('Self.strictBool(body["active"]) == true', self.restore)
        self.assertIn('Self.strictBool(body["liveViewStopUnconfirmed"]) == false', self.restore)

    def test_cleanup_uses_real_owner_and_receipt_preserves_new_warning(self):
        self.assertIn('stop.restoreToken.map { ["restoreToken": $0] } ?? [:]', self.stop)
        self.assertIn('body["restoreToken"] as? String == token', self.stop)
        self.assertIn('["stopped", "cancelled-before-start"].contains(outcome)', self.stop)
        self.assertIn('body["active"] == nil && body["liveViewStopUnconfirmed"] == nil', self.stop)
        self.assertIn("stop.restoreWarningRevision != liveViewWarningRevision", self.stop)
        self.assertEqual(self.stop.count("liveViewStop = fresh; liveViewStopUnconfirmed = true"), 2)
        self.assertNotIn("attempt.maintenanceRestoreToken", self.stop)
        self.assertIn("warning.maintenanceOwned = maintenanceLifecycleOwned", self.source)

    def test_direct_transport_remains_token_free_and_native_matrix_is_authored(self):
        self.assertNotIn("maintenanceRestoreToken", (CORE / "CCAPIClient.swift").read_text())
        native = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/MaintenanceControlTests.swift").read_text()
        for marker in [
            "faultPrepare",
            "faultOwnedStop",
            '"timeout"',
            '"null-outcome"',
            '"partial-state"',
            "testBridgePhysicalOwnedStopWithWarningDuringAwaitSelectsGlobalOnNextRetry",
            "testBridgeTerminalReceiptPreservesNewWarningBeforeOrDuringRetryThenUsesFreshGlobalStop",
            "testBridgeIdleAndPowerOffDoNotPreparePermit",
            "Consumed attempt cannot resend Start",
            "testAttemptCannotRestoreOrObserveThroughDifferentClientOwner",
        ]:
            self.assertIn(marker, native)
