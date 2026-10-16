"""iOS LUT source/wiring guards only. These do not compile or run Swift/UIKit."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"
TESTS = APP.parent / "Tests"


def section(source, start, end):
    return source.split(start, 1)[1].split(end, 1)[0]


class IosLutImportSourceTests(unittest.TestCase):
    def test_production_reader_bounds_raw_bytes_before_decode(self):
        source = (APP / "CubeLutImport.swift").read_text()
        self.assertNotIn("Data(contentsOf:", source)
        self.assertNotIn("resourceValues", source)
        self.assertIn("handle.read(upToCount: count)", source)
        self.assertIn("let remaining = maximumCubeLutBytes - data.count", source)
        self.assertIn("read(min(64 * 1024, remaining + 1))", source)
        self.assertLess(source.index("guard chunk.count <= remaining"), source.index("data.append(chunk)"))
        self.assertLess(source.index("data.append(chunk)"), source.index("data.removeFirst(utf8BOM.count)"))
        self.assertIn("String(data: data, encoding: .utf8)", source)
        self.assertNotIn("String(decoding:", source)
        self.assertIn("guard !data.starts(with: utf8BOM)", source)

    def test_default_reader_preserves_file_access_scope_and_closes(self):
        source = (APP / "CubeLutImport.swift").read_text()
        self.assertIn("url.startAccessingSecurityScopedResource()", source)
        self.assertIn("defer { if accessed { url.stopAccessingSecurityScopedResource() } }", source)
        self.assertIn("defer { try? handle.close() }", source)
        self.assertIn("parseCubeLut(text, fallbackName: url.lastPathComponent)", source)
        self.assertGreaterEqual(source.count("try checkCancellation()"), 5)

    def test_physical_pump_has_one_active_and_one_replaceable_pending_slot(self):
        source = (APP / "CubeLutImport.swift").read_text()
        self.assertIn("private var active: Request?", source)
        self.assertIn("private var pending: Request?", source)
        self.assertNotIn("[Request]", source)
        enqueue = section(source, "private func enqueue(", "private func cancel(")
        self.assertLess(enqueue.index("if request.cancelled"), enqueue.index("pending = request"))
        self.assertIn("let replaced = pending?.continuation", enqueue)
        self.assertIn("replaced?.resume(throwing: CancellationError())", enqueue)
        self.assertIn("let start = !pumping", enqueue)
        self.assertIn("if start { Task.detached", enqueue)
        pump = section(source, "private func pump()", "private static func readFile")
        self.assertLess(pump.index("active = request"), pump.index("try read(request.url)"))
        self.assertLess(pump.index("try read(request.url)"), pump.index("active = nil"))

    def test_picker_accepts_owner_synchronously_before_task(self):
        state = (APP / "CameraAppState.swift").read_text()
        accept = section(state, "func acceptCubeLutSelection(", "func importCubeLut(from")
        self.assertIn("retireCubeLutImport()", accept)
        self.assertIn("CubeLutImportSelection(owner: cubeLutOwner, url: url)", accept)
        run = section(state, "func importCubeLut(_ selection:", "func cancelCubeLutImport()")
        self.assertLess(run.index("guard cubeLutOwner == selection.owner"), run.index("Task.detached"))
        view = (APP / "CameraSheets.swift").read_text()
        self.assertLess(view.index("camera.completeCubeLutPicker(result, owner: owner)"), view.index("Task { await camera.importCubeLut(selection) }"))

    def test_picker_ticket_cancel_and_swiftui_dismissal_are_fenced(self):
        picker = (APP / "CubeLutDocumentPicker.swift").read_text()
        self.assertIn("private let owner: UUID", picker)
        self.assertIn("guard !completed else { return }", picker)
        self.assertIn("completion(owner, result)", picker)
        self.assertIn("finish(.success(nil))", picker)
        self.assertIn("asCopy: false", picker)
        state = (APP / "CameraAppState.swift").read_text()
        result = section(state, "func completeCubeLutPicker(", "func acceptCubeLutSelection(")
        self.assertIn("guard cubeLutPickerOwner == owner", result)
        self.assertIn("guard let url else { return nil }", result)
        self.assertIn("guard !isCubeLutCancellation(error) else { return nil }", result)
        view = (APP / "CameraSheets.swift").read_text()
        self.assertIn("if lutPickerTicket?.id == presentedID", view)

    def test_cancel_remove_error_and_physical_retirement_ui_are_distinct(self):
        state = (APP / "CameraAppState.swift").read_text()
        cancel = section(state, "func cancelCubeLutImport()", "private func cancelCubeLutImport(owner")
        self.assertNotIn("monitorSettings.cubeLut = nil", cancel)
        remove = section(state, "func clearCubeLut()", "func reportCubeLutImportError")
        self.assertIn("cancelCubeLutImport()", remove)
        self.assertIn("monitorSettings.cubeLut = nil", remove)
        self.assertIn("bufferedRequestCounts.retiring > 0", state)
        view = (APP / "CameraSheets.swift").read_text()
        for identifier in ("monitor-lut-import-loading", "monitor-lut-import-cancel", "monitor-lut-import-stopping", "monitor-lut-import-error"):
            self.assertIn(identifier, view)
        for locale in ("en", "zh-Hant"):
            strings = (APP / "Resources" / f"{locale}.lproj/Localizable.strings").read_text()
            self.assertEqual(strings.count('"lut_import_loading" ='), 1)
            self.assertEqual(strings.count('"lut_import_stopping" ='), 1)

    def test_lut_failure_and_success_do_not_unconditionally_replace_camera_error(self):
        state = (APP / "CameraAppState.swift").read_text()
        run = section(state, "func importCubeLut(_ selection:", "func cancelCubeLutImport()")
        self.assertNotIn("\n            lastError = nil", run)
        publish = section(state, "private func publishCubeLutImportError", "private func clearCubeLutImportError")
        self.assertIn("cubeLutImportErrorOwner == lastErrorOwner", publish)
        self.assertIn("didSet { lastErrorOwner = UUID() }", state)
        self.assertNotIn("lastError == previousImportError", state)
        self.assertNotIn('lastError == NSLocalizedString("lut_render_failed"', state)
        self.assertIn("testIdenticalCameraErrorTextHasANewOwnerAndSurvivesLutMutations", (TESTS / "ShutterAutofocusStateTests.swift").read_text())
        self.assertIn("cubeLutImportError = message", publish)
        test = (TESTS / "ShutterAutofocusStateTests.swift").read_text()
        self.assertIn("testLutSuccessAndFailureCannotOverwriteCameraErrorRaisedDuringImport", test)
        self.assertIn("await state.captureStill() // Injected transport", test)
        self.assertIn("XCTAssertEqual(state.lastError, cameraError)", test)

    def test_diagnostics_never_forward_raw_lut_provider_error(self):
        state = (APP / "CameraAppState.swift").read_text()
        report = section(state, "func diagnosticReport()", "func canPublishExport(")
        self.assertIn('cubeLutImportErrorOwner == lastErrorOwner ? "LUT_IMPORT_FAILED" : lastError', report)
        self.assertEqual(report.count("lastError: diagnosticError"), 3)
        self.assertNotIn("lastError: lastError", report)
        ownership = (TESTS / "AsyncUIOwnershipTests.swift").read_text()
        camera = (TESTS / "ShutterAutofocusStateTests.swift").read_text()
        self.assertIn("testDisconnectedDiagnosticsNeverIncludeLutProviderErrorCanaries", ownership)
        self.assertIn("testConnectedDiagnosticReportOmitsDetailedLutFailureCanaries", camera)
        for source in (ownership, camera):
            for canary in ("SYNTHETIC_LUT_FILENAME_ONLY.cube", "SYNTHETIC_LUT_TITLE_ONLY", "/synthetic/lut-private/location"):
                self.assertIn(canary, source)
            self.assertIn("try await state.diagnosticReport()", source)

    def test_import_lifetime_is_not_bound_to_camera_or_scene(self):
        state = (APP / "CameraAppState.swift").read_text()
        background = section(state, "func setApplicationActive(_ active:", "func startLiveView()")
        self.assertNotIn("CubeLut", background)
        before_import = state.split("func beginCubeLutPicker()", 1)[0]
        self.assertNotIn("cancelCubeLutImport()", before_import)
        self.assertNotIn("clearCubeLut()", before_import)
        view = section((APP / "CameraSheets.swift").read_text(), "private struct MonitoringAssistView", "private var lutPickerPresentation")
        self.assertIn('Button("done") { dismiss() }', view)
        self.assertNotIn(".onDisappear", view)

    def test_native_tests_load_shared_bytes_into_production_decoder(self):
        fixture = json.loads((ROOT / "contracts/lut-import/v1/utf8-vectors.json").read_text())
        self.assertEqual(len(fixture["vectors"]), 16)
        self.assertEqual([v["bytes"] for v in fixture["sizeBoundaryCases"]], [16777216, 16777217])
        project = (APP.parent / "project.yml").read_text()
        resource = section(project, "../../contracts/lut-import/v1/utf8-vectors.json", "- path:")
        self.assertIn("buildPhase: resources", resource)
        tests = (TESTS / "CubeLutImportReadTests.swift").read_text()
        self.assertIn('url(forResource: "utf8-vectors", withExtension: "json")', tests)
        self.assertIn("try readBoundedCubeLutText", tests)
        self.assertIn("try parseCubeLut(text", tests)
        self.assertIn("testPhysicalPumpKeepsOneActiveAndOnlyLatestPendingAcrossFortyReplacements", tests)
        self.assertIn("testCancelShowsPhysicalRetirementAndLatestSelectionWaitsForThatRead", tests)
        self.assertNotIn("XCTSkip", tests)

    def test_existing_and_new_native_owner_contracts_remain_authored(self):
        tests = (TESTS / "AsyncUIOwnershipTests.swift").read_text()
        for name in (
            "testRetiredLutImportCannotOverwriteANewerImportOrItsWarning",
            "testClearLutRetiresPendingImportSuccessAndFailureWithoutClearingANewerWarning",
            "testCanceledLutImportDiscardsLateSuccessFailureAndCancellation",
            "testPreCanceledLutImportDoesNotReadOrRetireAnActiveImport",
            "testDisconnectRetainsAppliedLutAndDoesNotRetireTheLatestImport",
            "testLatestAcceptedSelectionWinsWhenTasksStartInReverseOrder",
            "testPickerOpenNullAndUserCancellationPreserveAcceptedWorkAcrossBackground",
            "testStalePickerSuccessFailureAndCancellationCannotRetireANewerAcceptedSelection",
        ):
            self.assertIn("func " + name, tests)


if __name__ == "__main__":
    unittest.main()
