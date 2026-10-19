"""Supplementary source guards only; NOT Swift execution or physical-camera proof."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore/DesktopBridgeClient.swift"
APP = ROOT / "ios/OpenEOSControl/App/CameraAppState.swift"
NATIVE = ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/DesktopBridgeShutterRecoveryTests.swift"


def independent_stop(source):
    stop = source.split("public func stopHeldAutofocus(owner:", 1)[1].split("public func liveViewFocusInfo()", 1)[0]
    return (
        "await start.result" not in stop
        and "focusStartTask?.cancel()" in stop
        and "BridgeBoundedExchange.run" in stop
        and "transport.sendMaintenance(frozenRequest)" in stop
    )


class IOSFocusRetirementSourceTests(unittest.TestCase):
    def test_owner_stop_is_independent_coalesced_and_data_only_bounded(self):
        source = CORE.read_text()
        self.assertTrue(independent_stop(source))
        stop = source.split("private func releaseFocusHold", 1)[1].split("public func liveViewFocusInfo", 1)[0]
        for token in [
            "if let task = focusStopTask",
            "focusStopID == id",
            "sessionGeneration == hold.generation",
            "url: hold.stopURL",
            '["ownerId": hold.owner.uuidString]',
            'Self.maintenanceBody(response, method: "POST")',
            'body["ownerId"] as? String == hold.owner.uuidString',
            'Self.strictBool(body["active"]) == false',
            'Self.strictBool(body["stopUnconfirmed"]) == false',
        ]:
            self.assertIn(token, stop)
        self.assertNotIn("requestJSON(", stop)
        original = source.replace(
            "focusStartTask?.cancel()", "if let start = focusStartTask { _ = await start.result }"
        )
        self.assertFalse(independent_stop(original))

    def test_pending_start_keeps_live_owner_and_task_identity(self):
        source = CORE.read_text()
        self.assertIn("if focusStartTask != nil { focusHold?.stopAcknowledged = true }", source)
        self.assertIn("if sessionGeneration == hold.generation, focusStartID == id", source)
        self.assertNotIn("focusHold?.owner == hold.owner || sessionGeneration == hold.generation", source)
        admission = source.split("private func validateMutation(", 1)[1].split("private func ", 1)[0]
        self.assertIn("ownedBulb == nil && shutterRelease == nil && focusHold == nil", admission)
        self.assertIn("if focusHold?.stopAcknowledged == true { return }", source)

    def test_both_close_routes_attempt_frozen_stop_without_start_join(self):
        source = CORE.read_text()
        for start, end in [
            ("public func closeWithShutterReleaseState()", "public func connectSnapshot()"),
            ("private func finishMaintenanceClose()", "private func stopMaintenanceLiveView"),
        ]:
            close = source.split(start, 1)[1].split(end, 1)[0]
            self.assertIn("focusStartTask?.cancel()", close)
            self.assertNotIn("await start.result }\n        if let hold = focusHold", close)
            self.assertIn("if let hold = focusHold { try? await releaseFocusHold(hold) }", close)
            self.assertIn("BridgeBoundedExchange.run", close)
            self.assertIn("focusHold != nil", close)
        self.assertIn("ownedBulbLifecycleUsed || focusLifecycleUsed", source)

    def test_app_retains_direct_ccapi_join_and_owner_publication_fence(self):
        release = (
            APP.read_text()
            .split("func releaseHeldAutofocus(owner", 1)[1]
            .split("private var canReadCameraFocusInfo", 1)[0]
        )
        self.assertIn("if case .ccapi = session, let start, case let .failure(error) = await start.result", release)
        self.assertIn("try await session.stopHeldAutofocus(owner: owner)", release)
        self.assertIn("generation == self.sessionGeneration, self.heldAutofocusOwner == owner", release)

    def test_late_app_settlement_checks_observation_and_never_restarts(self):
        observer = (
            APP.read_text()
            .split("private func observeRetiredBridgeFocus(", 1)[1]
            .split("private var canReadCameraFocusInfo", 1)[0]
        )
        self.assertIn("_ = await start.result", observer)
        self.assertEqual(observer.count("self.shutterReleaseObservationRevision == observation"), 2)
        self.assertEqual(observer.count("self.heldAutofocusRetirementID == id"), 2)
        self.assertIn("!release.releaseRequired, !release.releaseUnconfirmed", observer)
        self.assertIn("release.bulbExposureActive == false", observer)
        self.assertIn("self.applyShutterReleaseState(release, pauseLiveView: false)", observer)
        self.assertNotIn("resumeLiveView", observer)
        native = (ROOT / "ios/OpenEOSControl/Tests/BridgeFocusRetirementStateTests.swift").read_text()
        self.assertIn("testLateSettledStartClearsOnlyItsOwnWarningWithoutRestart", native)
        self.assertIn("testLateSettledStartCannotClearNewerWarningObservation", native)
        self.assertIn("await state.refresh()", native)

    def test_native_test_names_are_unique(self):
        names = re.findall(r"^    func (test\w+)\(", NATIVE.read_text(), re.M)
        self.assertEqual(len(names), len(set(names)))

    def test_native_regressions_hold_replies_past_close_and_use_production_deadline(self):
        native = NATIVE.read_text()
        for name in [
            "testHeldAFStartStaysHeldThroughCloseAndCannotClearReusedSessionTask",
            "testHeldAFPendingStartStopACKRetainsLiveMutationReservation",
            "testHeldAF409IsNotProofAndOnlyOwnerMatchedRetryClearsWarning",
            "testHeldAFStopTimeoutRetiresWhileBothTransportRepliesRemainHeld",
            "testHeldAFMaintenanceCloseRetiresWithStartStillHeldAnd409Warning",
            "testHeldAFConfirmedReleaseStillUsesBoundedOriginalRetirement",
        ]:
            self.assertIn("func " + name, native)
        held = native.split("func testHeldAFStartStaysHeldThroughCloseAndCannotClearReusedSessionTask", 1)[1].split(
            "private var focusStartPath", 1
        )[0]
        self.assertLess(held.index("let closed = await close.value"), held.index('await peer.resume("focus-start"'))
        self.assertIn("await fulfillment(of: [retired], timeout: 20)", native)
        self.assertIn("XCTAssertTrue(startHeld && stopHeld)", native)


if __name__ == "__main__":
    unittest.main()
