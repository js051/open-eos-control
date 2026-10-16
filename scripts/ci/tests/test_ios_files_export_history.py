"""Source wiring/privacy guards only; these never claim native Swift execution."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"


class FilesExportHistorySourceTests(unittest.TestCase):
    def test_production_entry_points_use_recorded_admission(self):
        files = ["ForegroundMediaView.swift", "MediaBatchPanel.swift", "MediaView.swift",
                 "MediaDeliveryHistoryView.swift"]
        count = 0
        for filename in files:
            source = (APP / filename).read_text()
            self.assertNotIn("filesExporter.begin(", source)
            self.assertNotIn("beginUnrecordedForTesting", source)
            count += source.count("camera.beginMediaFilesExport(filesExporter, payload:")
        self.assertEqual(count, 5)
        state = (APP / "CameraAppState.swift").read_text()
        self.assertIn("controller.beginRecorded(payload, store: mediaDeliveryStore", state)
        self.assertIn("self?.mediaDeliveryClearGeneration == generation", state)
        self.assertIn("self?.applicationActive == true", state)

    def test_picker_admission_and_source_revalidation_precede_presentation(self):
        source = (APP / "MediaFilesExport.swift").read_text()
        begin = source.split("func beginRecorded(", 1)[1].split("private func failAdmission", 1)[0]
        self.assertLess(begin.index("await store.beginFilesExportHistory"), begin.index("self.presentation = attempt"))
        self.assertLess(begin.index("guard Self.sourcesAvailable"), begin.index("self.presentation = attempt"))
        self.assertIn("expectedEpoch: epoch", begin)
        self.assertIn("!Task.isCancelled, isCurrent()", begin)
        self.assertIn("retirePendingAdmission()", source)
        self.assertIn("guard activeID == id, pickerEnded, !waitingForHistory", source)

    def test_store_strict_schema_epoch_and_cold_unknown(self):
        source = (APP / "MediaDeliveryStore.swift").read_text()
        self.assertIn("manifest!.epoch == expectedEpoch", source)
        self.assertIn("manifest!.epoch == ticket.epoch", source)
        self.assertIn("next.filesExportAttempts = []", source)
        self.assertIn("saved.filesExportAttempts[index].status = .unconfirmed", source)
        self.assertIn("next.version = max(4, next.version)", source)
        self.assertIn('required.union(["completedAt", "reportedCount"])', source)
        self.assertIn("limits.maximumRecords - filenames.count", source)
        record = (APP / "MediaFilesExport.swift").read_text().split(
            "struct MediaFilesExportHistoryRecord:", 1)[1].split("\n}", 1)[0]
        fields = set(re.findall(r"(?:let|var) (\w+):", record))
        self.assertEqual(fields, {"id", "filenames", "startedAt", "completedAt", "status", "reportedCount"})

    def test_shared_vectors_are_bounded_and_picker_claims_are_honest(self):
        data = (ROOT / "contracts/media-delivery/v1/outcome-vectors.json").read_bytes()
        self.assertLessEqual(len(data), 32768)
        contract = json.loads(data)
        vectors = contract["vectors"]
        self.assertLessEqual(len(vectors), 32)
        self.assertEqual(len({v["id"] for v in vectors}), len(vectors))
        for vector in vectors:
            self.assertLessEqual(len(vector["events"]), 12)
            if "ios_picker" in vector["targets"]:
                self.assertFalse(vector["expected"]["saveConfirmed"])
                self.assertFalse(vector["expected"].get("perFileMappingConfirmed", False))
        by_id = {v["id"]: v for v in vectors}
        for name in ["cold_picker_pending", "picker_terminal_write_failed_cold"]:
            self.assertEqual(by_id[name]["expected"]["continuation"], "new_explicit_export_required")
        project = (ROOT / "ios/OpenEOSControl/project.yml").read_text()
        self.assertIn("../../contracts/media-delivery/v1/outcome-vectors.json", project)

    def test_new_ui_strings_have_both_languages_and_no_lost_history_claim(self):
        strings = []
        for locale in ["en", "zh-Hant"]:
            text = (APP / f"Resources/{locale}.lproj/Localizable.strings").read_text()
            keys = re.findall(r'^"(media_files_export_[^"]+)"\s*=', text, re.MULTILINE)
            self.assertEqual(len(keys), len(set(keys)))
            strings.append(set(keys))
            for key in ["history_warning", "history_unavailable", "history_title", "history_help", "recording"]:
                self.assertIn("media_files_export_" + key, keys)
            self.assertNotIn("Closing this screen or restarting the app loses the export result", text)
        self.assertEqual(*strings)


if __name__ == "__main__":
    unittest.main()
