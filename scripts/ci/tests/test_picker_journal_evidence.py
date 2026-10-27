"""Only exact synthetic lifecycle stages may leave the fresh test app container."""
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
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

    def opening_source(self, slot):
        return self.source.with_name(f"eos-picker-lifecycle-opening-{slot}.txt")

    def opening_output(self, slot):
        return self.output / f"picker-lifecycle-opening-{slot}.txt"

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

    def test_identity_flags_preserve_each_boundary_within_existing_caps(self):
        identity_stages = (
            "identity-picker-matches", "identity-requested-host-alive",
            "identity-requested-host-attached", "identity-original-presenter-known",
            "identity-original-presenter-alive", "identity-current-is-requested-host",
            "identity-current-is-host-ancestor", "identity-ancestry-complete",
            "identity-current-is-original-presenter",
        )
        self.assertEqual(tuple(stage for stage in evidence.PICKER_STAGES
                               if stage.startswith("identity-")), identity_stages)
        lines = []
        for boundary in ("presentation-completed", "selected", "completion-end"):
            lines.append(self.v2.replace(b"stage=selected", f"stage={boundary}".encode("ascii")))
            for index, stage in enumerate(identity_stages):
                lines.append(self.v2.replace(b"stage=selected", f"stage={stage}".encode("ascii"))
                             .replace(b"flag=0", f"flag={index % 2}".encode("ascii")))
        data = b"".join(lines)
        self.assertLessEqual(len(lines), 128)
        self.assertLessEqual(len(data), 16_384)
        self.source.write_bytes(data)
        self.opening_source(1).write_bytes(data)
        self.collect()
        self.assertEqual((self.output / "picker-lifecycle.txt").read_bytes(), data)
        self.assertEqual(self.opening_output(1).read_bytes(), data)
        self.assertEqual(self.report["warnings"], [])

    def test_identity_records_do_not_admit_object_metadata_or_nonboolean_flags(self):
        line = self.v2.replace(b"stage=selected", b"stage=identity-picker-matches")
        invalid = [line.rstrip(b"\n") + b" " + field + b"\n" for field in (
            b"address=synthetic", b"class=synthetic", b"device=synthetic", b"filename=synthetic")]
        invalid += [line.replace(b"flag=0", value) for value in (b"flag=2", b"flag=true", b"flag=unknown")]
        invalid.append(line.replace(b"identity-picker-matches", b"identity-private-selector"))
        for data in invalid:
            with self.subTest(data=data):
                self.source.write_bytes(data)
                self.opening_source(1).write_bytes(data)
                self.collect()
                self.assertFalse((self.output / "picker-lifecycle.txt").exists())
                self.assertFalse(self.opening_output(1).exists())

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

    def test_missing_optional_openings_are_silent_but_aggregate_stays_required(self):
        self.collect()
        self.assertEqual(self.report["warnings"], ["No synthetic-picker-lifecycle journal"])
        self.report["warnings"].clear()
        self.source.write_bytes(self.v2)
        self.collect()
        self.assertEqual(self.report["warnings"], [])
        self.assertEqual(len(self.report["files"]), 1)

    def test_only_first_sixteen_fixed_openings_export_without_enumeration(self):
        self.source.write_bytes(self.v2)
        expected = {"picker-lifecycle.txt": self.v2}
        for slot in range(1, 17):
            data = self.v2.replace(b"ticket=1", f"ticket={slot}".encode("ascii"))
            self.opening_source(slot).write_bytes(data)
            expected[self.opening_output(slot).name] = data
        for slot in (0, 17, 32, "private"):
            self.opening_source(slot).write_bytes(b"unrelated private data\n")
        with patch.object(Path, "iterdir", side_effect=AssertionError("No enumeration")), \
             patch.object(Path, "glob", side_effect=AssertionError("No enumeration")), \
             patch.object(Path, "rglob", side_effect=AssertionError("No enumeration")), \
             patch.object(evidence.os, "scandir", side_effect=AssertionError("No enumeration")), \
             patch.object(evidence.os, "listdir", side_effect=AssertionError("No enumeration")):
            self.collect()
        self.assertEqual(list(evidence.PICKER_OPENING_SLOTS), list(range(1, 17)))
        self.assertEqual({item["file"] for item in self.report["files"]}, set(expected))
        for filename, data in expected.items():
            self.assertEqual((self.output / filename).read_bytes(), data)
        self.assertEqual(self.report["bytes"], sum(map(len, expected.values())))
        self.assertEqual(self.report["warnings"], [])

    def test_earlier_failed_opening_survives_later_aggregate_records(self):
        failed = self.v2.replace(b"stage=selected", b"stage=presenter-disappeared")
        self.opening_source(1).write_bytes(failed)
        later = self.v2.replace(b"ticket=1", b"ticket=17").replace(b"success", b"cancel")
        self.source.write_bytes(later * 64)
        self.collect()
        self.assertEqual(self.opening_output(1).read_bytes(), failed)
        self.assertEqual((self.output / "picker-lifecycle.txt").read_bytes(), later * 64)
        self.assertEqual(self.report["warnings"], [])

    def test_opening_rejects_other_tickets_legacy_and_private_fields(self):
        invalid = (
            self.v2.replace(b"ticket=1", b"ticket=2"),
            self.v2.replace(b"ticket=1", b"ticket=17"),
            self.line,
            self.v2 + self.v2.replace(b"ticket=1", b"ticket=2"),
            self.v2.rstrip(b"\n") + b" filename=private\n",
            self.v2.rstrip(b"\n") + b" url=https://example.invalid/private\n",
            self.v2.rstrip(b"\n") + b" owner=123\n",
        )
        for data in invalid:
            with self.subTest(data=data):
                self.opening_source(1).write_bytes(data)
                self.collect()
                self.assertFalse(self.opening_output(1).exists())
        self.assertIn("Fixture journal contains unsupported fields", self.report["warnings"])

    def test_opening_size_line_count_empty_and_nonascii_limits_are_preserved(self):
        for data in (b"", b"x" * (evidence.MAX_TEXT_BYTES + 1), self.v2 * 129, self.v2 + b"\xff\n"):
            with self.subTest(size=len(data)):
                self.opening_source(1).write_bytes(data)
                self.collect()
                self.assertFalse(self.opening_output(1).exists())

    def test_optional_opening_symlinks_and_nonregular_types_are_rejected(self):
        target = self.root / "target"
        target.write_bytes(self.v2)
        source = self.opening_source(1)
        for target_path in (target, self.root / "missing"):
            source.symlink_to(target_path)
            self.collect()
            self.assertFalse(self.opening_output(1).exists())
            self.assertIn("Unsafe fixture journal path", self.report["warnings"])
            source.unlink()
        source.mkdir()
        self.report["warnings"].clear()
        self.collect()
        self.assertFalse(self.opening_output(1).exists())
        self.assertTrue(any(warning in self.report["warnings"] for warning in (
            "Unsupported fixture journal size/type", "Fixture journal could not be safely read")))
        self.assertEqual(target.read_bytes(), self.v2)

    def test_opening_cannot_overwrite_existing_destination(self):
        self.opening_source(1).write_bytes(self.v2)
        self.opening_output(1).write_bytes(b"retained")
        self.collect()
        self.assertEqual(self.opening_output(1).read_bytes(), b"retained")

    def test_openings_share_unchanged_artifact_byte_and_file_caps(self):
        self.assertEqual(evidence.MAX_FILES, 64)
        self.assertEqual(evidence.MAX_ARTIFACT_BYTES, 24 * 1024 * 1024)
        self.opening_source(1).write_bytes(self.v2)
        self.opening_source(2).write_bytes(self.v2.replace(b"ticket=1", b"ticket=2"))
        budget = evidence.MAX_ARTIFACT_BYTES - evidence.SUMMARY_RESERVE
        for cap in ("bytes", "files"):
            with self.subTest(cap=cap):
                self.report = {"files": [], "bytes": 0, "warnings": []}
                if cap == "bytes":
                    self.report["bytes"] = budget - len(self.v2)
                else:
                    self.report["files"] = [{}] * (evidence.MAX_FILES - 1)
                self.collect()
                self.assertTrue(self.opening_output(1).exists())
                self.assertFalse(self.opening_output(2).exists())
                self.assertLessEqual(self.report["bytes"], budget)
                self.assertLessEqual(len(self.report["files"]), evidence.MAX_FILES)
                self.assertIn("Fixture journal omitted at artifact size/count limit", self.report["warnings"])
                self.opening_output(1).unlink()

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
        self.assertIn("static let openingOrdinals = 1...16", source)
        self.assertIn('return "eos-picker-lifecycle-opening-\\(ordinal).txt"', source)
        self.assertIn("appendOpening(firstLine, ticket: ticket, aggregateURL: url)", source)
        self.assertIn("appendOpening(line, ticket: ticket, aggregateURL: url)", source)
        self.assertIn("PickerFixtureJournal.boundedOpeningJournal(lines, ordinal: openingOrdinal)", source)
        self.assertIn("lastOrdinal = ticket.ordinal", source)
        self.assertIn("highestOccupiedOpening(in: url.deletingLastPathComponent())", source)
        self.assertNotIn("contentsOfDirectory", source)
        self.assertNotIn("enumerator(", source)

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
        self.assertIn("[self, session]", picker)
        self.assertIn("[weak self, weak session]", picker)
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
