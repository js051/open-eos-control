"""Only exact synthetic lifecycle stages may leave the fresh test app container."""
from pathlib import Path
import re
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
        self.v2 = b"v2 ticket=1 ms=0 language=english scenario=success stage=selected presented=1 transitioning=0 dismissing=0 flag=0\n"

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

    def test_v2_all_closed_stages_and_bounded_values_preserve_order(self):
        lines = [self.v2.replace(b"stage=selected", b"stage=" + stage.encode("ascii"))
                 for stage in evidence.PICKER_STAGES]
        lines.append(self.v2.replace(b"ticket=1", b"ticket=9999").replace(b"ms=0", b"ms=999999")
                     .replace(b"english", b"traditionalChinese").replace(b"success", b"lost-ack")
                     .replace(b"flag=0", b"flag=1"))
        self.source.write_bytes(self.line + b"".join(lines))
        self.collect()
        self.assertEqual((self.output / "picker-lifecycle.txt").read_bytes(), self.source.read_bytes())
        self.assertEqual(self.report["warnings"], [])

    def test_v2_rejects_out_of_range_noncanonical_or_private_fields(self):
        replacements = [
            (b"ticket=1", value) for value in
            (b"ticket=0", b"ticket=10000", b"ticket=-1", b"ticket=01", b"ticket=+1", b"ticket=1.0")
        ] + [
            (b"ms=0", value) for value in (b"ms=-1", b"ms=1000000", b"ms=00", b"ms=1.1")
        ] + [
            (b"language=english", b"language=private"),
            (b"scenario=success", b"scenario=private"),
            (b"stage=selected", b"stage=private"),
            (b"flag=0", b"flag=2"),
        ]
        for original, replacement in replacements:
            with self.subTest(replacement=replacement):
                self.source.write_bytes(self.v2.replace(original, replacement))
                self.collect()
                self.assertFalse((self.output / "picker-lifecycle.txt").exists())
        for suffix in (b" url=https://example.invalid/file", b" filename=private", b" owner=123", b"\xff"):
            self.source.write_bytes(self.v2.rstrip(b"\n") + suffix + b"\n")
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
        source = (root / "ios/OpenEOSControl/App/PickerFixtureJournal.swift").read_text()
        self.assertIn("#if DEBUG && OEC_REQUIRE_SIMULATOR_E2E", source)
        self.assertIn('process.environment["OEC_UPLOAD_JOURNEY_FIXTURE"]', source)
        self.assertIn("let scenario = Scenario(rawValue: value)", source)
        self.assertIn('process.arguments.firstIndex(of: "-app-language")', source)
        self.assertIn("let language = Language(rawValue:", source)
        self.assertIn("guard ticket.isEnabled else { return }", source)
        self.assertIn("lines.suffix(128)", source)
        self.assertIn("guard bytes.count <= 16_384", source)
        self.assertIn("records.allSatisfy(PickerFixtureJournal.isValidLine)", source)
        self.assertIn("attributes[.type] as? FileAttributeType == .typeRegular", source)
        self.assertIn("private let lock = NSLock()", source)

    def test_exporter_and_swift_stage_allowlists_match(self):
        root = Path(__file__).resolve().parents[3]
        source = (root / "ios/OpenEOSControl/App/PickerFixtureJournal.swift").read_text()
        stages = source.split("enum Stage:", 1)[1].split("enum Scenario:", 1)[0]
        values = set()
        for declaration in re.findall(r"^\s*case ([^\n]+)", stages, re.MULTILINE):
            for item in declaration.split(","):
                parts = item.strip().split(" = ")
                values.add(parts[-1].strip('"'))
        self.assertEqual(values, set(evidence.PICKER_STAGES))

    def test_diagnostic_observers_do_not_replace_admission_or_retirement(self):
        root = Path(__file__).resolve().parents[3]
        picker = (root / "ios/OpenEOSControl/App/MediaUploadPicker.swift").read_text()
        self.assertIn("[weak self, weak picker]", picker)
        self.assertIn("guard !completed, !dismissRequested else { return }", picker)
        self.assertIn("if cancelled || isPresented() { dismiss() }", picker)
        self.assertIn("guard !isPresented() else { return }", picker)
        self.assertIn("guard !delivered, !invalidated else { return }", picker)
        self.assertIn("guard !invalidated, !completed else { return }", picker)
        self.assertIn("defer { PickerFixtureJournal.record(.delegateReturning", picker)
        self.assertNotIn("asyncAfter", picker)
        self.assertNotIn("Task.sleep", picker)
        self.assertNotIn("DOCWeakProxy", picker)
        self.assertNotIn("class DiagnosticPicker", picker)
        self.assertIn("lhs.id == rhs.id && lhs.owner == rhs.owner", picker)
