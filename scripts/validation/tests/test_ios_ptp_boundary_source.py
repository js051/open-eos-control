"""Source-only scope guards. These DO NOT compile Swift or execute the PTP reducer.

Lifecycle behavior lives in ImageCapturePTPStateMachineTests.swift and must be run
with Swift/XCTest. No Python mirror of the production state machine is used here.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"
TESTS = ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/ImageCapturePTPStateMachineTests.swift"


class IOSPTPBoundarySourceTests(unittest.TestCase):
    def setUp(self):
        self.port = (CORE / "ImageCapturePTPPort.swift").read_text()
        self.state = (CORE / "ImageCapturePTPStateMachine.swift").read_text()
        self.tests = TESTS.read_text()

    def test_boundary_has_no_native_sdk_or_network_import(self):
        for text in (self.port, self.state):
            self.assertEqual(re.findall(r"^import (\w+)", text, re.M), ["Foundation"])
        self.assertNotIn("URLSession", self.state)
        self.assertNotIn("CCAPIClient", self.state)
        self.assertNotIn("DesktopBridgeClient", self.state)

    def test_public_effect_surface_is_semantic_device_info_only(self):
        effects = self.port.split("public enum Effect:", 1)[1].split("\n    }", 1)[0]
        self.assertIn("case readDeviceInfo(Request)", effects)
        for forbidden in ("case send", "case capture", "case delete", "case write", "case command"):
            self.assertNotIn(forbidden, effects)
        self.assertNotRegex(effects, r"case \w+\([^\n]*Data")

    def test_outcome_type_has_no_semantic_success(self):
        outcome = self.port.split("public enum DeviceInfoOutcome:", 1)[1].split("\n    }", 1)[0]
        self.assertIn("case unvalidatedBuffers(Receipt)", outcome)
        self.assertIn("case outcomeUnknown(Request, RetirementReason)", outcome)
        self.assertIn("case notDispatched(Request, RetirementReason)", outcome)
        self.assertNotRegex(outcome, r"case (success|completed|deviceInfo)\b")

    def test_no_provider_or_privacy_activation_in_existing_app(self):
        app = ROOT / "ios/OpenEOSControl/App"
        for path in (app / "CameraSession.swift", app / "ConnectionView.swift", app / "CameraAppState.swift"):
            self.assertNotIn("ImageCapturePTP", path.read_text())
        self.assertNotIn("NSCameraUsageDescription", (app / "Info.plist").read_text())

    def test_exact_ownership_types_and_completion_buffers_remain_distinct(self):
        for name in ("Discovery", "Handle", "Session", "Request"):
            self.assertIn(f"public struct {name}: Hashable, Sendable", self.port)
        self.assertIn("public let responseData: Data", self.port)
        self.assertIn("public let ptpResponseData: Data", self.port)
        self.assertIn("public mutating func admit(", self.state)

    def test_native_test_inventory_covers_required_boundaries(self):
        names = re.findall(r"func (test\w+)\(", self.tests)
        self.assertEqual(len(names), len(set(names)))
        for required in (
            "testBothOpenReadyOrdersRequireBothSignals",
            "testCancelledOpeningQuarantinesExactObjectAndLateSuccessClosesOnlyOldSession",
            "testOneOutstandingReadAndDistinctBuffersStayUnvalidated",
            "testEmptyOrMalformedBuffersNeverBecomeDeviceInfoSuccess",
            "testTimeoutRetiresEpochAndLateReadCannotAffectNewCamera",
            "testPermissionRevocationWhileOpeningPreservesLateCleanup",
            "testCloseFailureQuarantinesObjectAndExplicitRetryUsesNewRequest",
            "testReselectionBeforeOldReleaseEffectKeepsNewSessionOwnership",
            "testReentrantCancellationBeforeOpenDispatchDoesNotInventCleanupObligation",
            "testDuplicateEffectsCannotDispatchTwiceAndUndispatchedCallbacksAreIgnored",
            "testUnresolvedCleanupBudgetBlocksNewOpensWithoutDroppingOldObligations",
        ):
            self.assertIn(required, names)


if __name__ == "__main__":
    unittest.main()
