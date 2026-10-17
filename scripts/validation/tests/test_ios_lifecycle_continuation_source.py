"""Narrow Swift caller/source wiring guards. NOT native Swift/App/GPU execution."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App/CameraAppState.swift"
NATIVE = ROOT / "ios/OpenEOSControl/Tests/CameraLifecycleContinuationTests.swift"


def event_admission_yields(source):
    event = source.split("private func refreshMediaAfterEvent(", 1)[1].split("private func pauseLiveViewForBulb()", 1)[0]
    blocked = re.search(r"guard begin\(\.mediaLibrary\) else \{(.*?)\n\s*\}", event, re.S)
    return bool(blocked and "try await Task.sleep(nanoseconds: 50_000_000)" in blocked[1]
                and blocked[1].index("try await Task.sleep") < blocked[1].index("continue"))


def restore_rearms_rtp(source):
    restore = source.split("func restoreMaintenance(_ id: UUID) async {", 1)[1].split("private func finishMaintenance(", 1)[0]
    rtp = restore.split("if self.activeLiveViewSource == .ccapiRTP {", 1)[1].split("} else if", 1)[0]
    return "self.beginLiveViewRenderWatch()" in rtp


class IOSLifecycleContinuationSourceTests(unittest.TestCase):
    def test_failed_event_admission_suspends_instead_of_spinning(self):
        source = APP.read_text()
        self.assertTrue(event_admission_yields(source))
        event = source.split("private func refreshMediaAfterEvent(", 1)[1].split("private func pauseLiveViewForBulb()", 1)[0]
        self.assertIn("while generation == eventGeneration, !Task.isCancelled", event)
        self.assertIn("if screen != .media { return true }", event)
        self.assertLess(event.index("guard begin(.mediaLibrary)"), event.index("mediaLibraryLoading = true"))
        self.assertIn("if error is CancellationError { return true }", event)
        admission = source.split("private func begin(_ operation:", 1)[1].split("private func end(", 1)[0]
        self.assertIn("if liveViewRetiring, operation != .refresh { return false }", admission)

    def test_starvation_negative_control_detects_original_synchronous_retry(self):
        source = APP.read_text()
        original = re.sub(r"guard begin\(\.mediaLibrary\) else \{.*?\n\s*\}",
                          "guard begin(.mediaLibrary) else { continue }", source, count=1, flags=re.S)
        self.assertFalse(event_admission_yields(original))

    def test_successful_restore_rearms_existing_owner_guarded_rtp_watch(self):
        source = APP.read_text()
        self.assertTrue(restore_rearms_rtp(source))
        restore = source.split("func restoreMaintenance(_ id: UUID) async {", 1)[1].split("private func finishMaintenance(", 1)[0]
        self.assertLess(restore.index("try Task.checkCancellation()"), restore.index("self.beginLiveViewRenderWatch()"))
        self.assertLess(restore.index("guard self.sameMaintenanceOwner(record)"), restore.index("self.beginLiveViewRenderWatch()"))
        self.assertLess(restore.index("self.maintenanceControlsPaused = false"), restore.index("self.beginLiveViewRenderWatch()"))
        watch = source.split("private func beginLiveViewRenderWatch()", 1)[1].split("func captureStill()", 1)[0]
        for token in ["guard autoRefresh, applicationActive, activeLiveViewSource == .ccapiRTP",
                      "self.sessionGeneration == generation", "self.liveViewLifetime == lifetime",
                      "!self.maintenanceControlsPaused", "videoProgressFailure()", "self.retireLiveView(reason:"]:
            self.assertIn(token, watch)

    def test_restore_negative_control_detects_original_missing_watch(self):
        source = APP.read_text()
        restore = source.split("func restoreMaintenance(_ id: UUID) async {", 1)[1].split("private func finishMaintenance(", 1)[0]
        original = source.replace(restore, restore.replace("self.beginLiveViewRenderWatch()", ""), 1)
        self.assertFalse(restore_rearms_rtp(original))

    def test_native_regressions_are_in_required_test_target_and_use_production_calls(self):
        tests = NATIVE.read_text()
        for token in ["state.setApplicationActive(false)", "await old.emitContents()",
                      "state.info?.model != originalModel", "state.liveViewRetiring",
                      'boundary == "disconnect"', 'boundary == "leave-gallery"',
                      "await state.cleanSensor(autoPowerOff: false)", "await state.restoreMaintenance(id)",
                      "state.cancelMaintenance()", "state.hasLiveViewRenderWatchTask",
                      "CCAPIClient(", "DesktopBridgeClient(", "CheckedContinuation"]:
            self.assertIn(token, tests)
        self.assertEqual(len(re.findall(r"^    func test", tests, re.M)), 6)
        project = (ROOT / "ios/OpenEOSControl/project.yml").read_text()
        self.assertIn("      - path: Tests\n", project)
        self.assertNotIn("CameraLifecycleContinuationTests.swift", project)  # Included by required Tests directory.
        self.assertIn("not exercise a camera", tests)


if __name__ == "__main__":
    unittest.main()
