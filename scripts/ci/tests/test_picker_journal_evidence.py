"""Only exact synthetic lifecycle stages may leave the fresh test app container."""
from pathlib import Path
import tempfile
import unittest
from scripts.ci import export_ios_ui_evidence as evidence


class PickerJournalEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.container = self.root / "app"
        self.source = self.container / "Library/Caches/eos-picker-lifecycle.txt"
        self.source.parent.mkdir(parents=True)
        self.output = self.root / "output"
        self.output.mkdir()
        self.report = {"files": [], "bytes": 0, "warnings": []}
        self.line = b"success selected presented=1 transitioning=0 dismissing=0\n"

    def collect(self):
        evidence.collect_picker_journal(self.container, self.output, self.report)

    def test_exact_fixed_stages_preserve_order(self):
        self.source.write_bytes(self.line + self.line.replace(b"selected", b"completion-begin"))
        self.collect()
        self.assertEqual((self.output / "picker-lifecycle.txt").read_bytes(), self.source.read_bytes())
        self.assertEqual(self.report["bytes"], self.source.stat().st_size)
        self.assertEqual(self.report["warnings"], [])

    def test_unknown_values_private_fields_and_url_are_rejected(self):
        for data in (self.line + b"url=https://example.invalid/file\n", self.line.replace(b"success", b"private"),
                     self.line.replace(b"presented=1", b"presented=2"), self.line + b"owner=123\n",
                     self.line + b"label=Private file\n", self.line + b"\xff\n"):
            with self.subTest(data=data):
                self.source.write_bytes(data)
                self.collect()
                self.assertFalse((self.output / "picker-lifecycle.txt").exists())

    def test_oversize_and_excess_lines_are_rejected(self):
        for data in (b"x" * (evidence.MAX_TEXT_BYTES + 1), self.line * 129):
            self.source.write_bytes(data)
            self.collect()
            self.assertFalse((self.output / "picker-lifecycle.txt").exists())

    def test_symlink_and_dangling_symlink_are_rejected(self):
        target = self.root / "target"
        target.write_bytes(self.line)
        for target_path in (target, self.root / "missing"):
            self.source.symlink_to(target_path)
            self.collect()
            self.assertFalse((self.output / "picker-lifecycle.txt").exists())
            self.source.unlink()
        self.assertEqual(target.read_bytes(), self.line)

    def test_parent_symlink_is_rejected(self):
        self.source.parent.rmdir()
        other = self.root / "other"
        other.mkdir()
        (other / self.source.name).write_bytes(self.line)
        self.source.parent.symlink_to(other, target_is_directory=True)
        self.collect()
        self.assertFalse((self.output / "picker-lifecycle.txt").exists())

    def test_artifact_byte_and_file_caps_are_preserved(self):
        self.source.write_bytes(self.line)
        self.report["bytes"] = evidence.MAX_ARTIFACT_BYTES - evidence.SUMMARY_RESERVE
        self.collect()
        self.assertFalse((self.output / "picker-lifecycle.txt").exists())
        self.report["bytes"] = 0
        self.report["files"] = [{}] * evidence.MAX_FILES
        self.collect()
        self.assertFalse((self.output / "picker-lifecycle.txt").exists())

    def test_existing_destination_cannot_be_overwritten(self):
        self.source.write_bytes(self.line)
        (self.output / "picker-lifecycle.txt").write_bytes(b"retained")
        self.collect()
        self.assertEqual((self.output / "picker-lifecycle.txt").read_bytes(), b"retained")

    def test_production_journal_requires_both_test_build_and_fixture(self):
        root = Path(__file__).resolve().parents[3]
        source = (root / "ios/OpenEOSControl/App/MediaUploadPicker.swift").read_text()
        self.assertIn("#if DEBUG && OEC_REQUIRE_SIMULATOR_E2E", source)
        self.assertIn('["success", "cancel", "lost-ack"].contains(scenario)', source)
        self.assertIn("lines.suffix(128)", source)
        self.assertIn("guard bytes.count <= 16_384", source)
