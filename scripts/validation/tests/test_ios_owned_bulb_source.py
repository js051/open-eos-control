"""Supplementary source guards only: no Swift execution or physical-camera proof."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"


class IOSOwnedBulbSourceTests(unittest.TestCase):
    def setUp(self):
        self.source = (CORE / "DesktopBridgeClient.swift").read_text()
        self.owned = self.source.split("public func prepareOwnedBulb()", 1)[1].split(
            "public func stopBulbExposure()", 1
        )[0]

    def test_admission_freezes_owner_and_routes_before_start(self):
        self.assertIn("DesktopBridgeBulbOwner(id: UUID(), generation: sessionGeneration", self.owned)
        self.assertIn('["bulb", "start", "owned"]', self.owned)
        self.assertIn('["bulb", "stop", "owned"]', self.owned)
        self.assertIn("!ownedBulbStartDispatched", self.owned)
        self.assertNotIn("payload: [:]", self.owned)

    def test_stop_is_independent_and_coalesced(self):
        stop = self.owned.split("private func stopOwnedBulb", 1)[1]
        self.assertIn("if let task = ownedBulbStopTask", stop)
        self.assertNotIn("pendingStart.result", stop)
        self.assertNotIn("bulbStartTask.value", stop)
        self.assertIn("self.ownedBulbStopID == id", stop)
        self.assertIn("owner.generation == sessionGeneration", stop)

    def test_strict_receipt_is_only_release_proof(self):
        self.assertIn('body.string("ownerId") == owner.id.uuidString', self.owned)
        self.assertIn('["released", "cancelled-before-start"]', self.owned)
        self.assertIn('body["status"] != nil, status == nil', self.owned)
        self.assertIn('status != nil, body.string("outcome") != "released"', self.owned)
        self.assertIn("Self.provesShutterReleased(status)", self.owned)
        self.assertIn("revision == self.releaseRevision", self.owned)
        self.assertIn('return try Self.maintenanceBody(response, method: "POST")', self.owned)

    def test_total_deadline_never_joins_cancelled_worker(self):
        bounded = (CORE / "DesktopBridgeBulbOwner.swift").read_text()
        self.assertIn("seconds: Double = 15", bounded)
        self.assertIn("withCheckedThrowingContinuation", bounded)
        self.assertIn("Task.detached", bounded)
        self.assertIn("worker?.cancel(); timer?.cancel()", bounded)
        self.assertNotIn("withThrowingTaskGroup", bounded)
        self.assertNotIn("await worker", bounded)
        self.assertIn("transport.sendMaintenance(frozenRequest)", self.owned)

    def test_owned_retirement_attempts_stop_then_original_delete(self):
        close = self.source.split("public func closeWithShutterReleaseState()", 1)[1].split(
            "public func connectSnapshot()", 1
        )[0]
        self.assertLess(
            close.index("stopOwnedBulb(owner, allowClosing: true)"),
            close.index("transport.sendConnectionLifecycle(request)"),
        )
        self.assertIn("catch { ownedReleaseFailed = true }", close)
        self.assertIn("BridgeBoundedExchange.run", close)
        strict = self.source.split("private func finishMaintenanceClose()", 1)[1].split(
            "private func stopMaintenanceLiveView", 1
        )[0]
        self.assertIn("stopOwnedBulb(owner, allowClosing: true)", strict)
        self.assertIn('["maintenance", "control", "session"]', strict)
        self.assertIn("if ownedAtClose == nil, let release = shutterRelease", strict)

    def test_unowned_and_direct_recovery_remain_separate(self):
        retry = self.source.split("public func retryShutterRelease()", 1)[1].split("public func startHeldAutofocus", 1)[
            0
        ]
        self.assertLess(retry.index("if let owner = ownedBulb"), retry.index("_ = try await status()"))
        self.assertIn("ownedBulb == nil && shutterRelease == nil && focusHold == nil", self.source)
        self.assertNotIn("DesktopBridgeBulbOwner", (CORE / "CCAPIClient.swift").read_text())


if __name__ == "__main__":
    unittest.main()
