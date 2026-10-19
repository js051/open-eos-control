"""Capture-mode ordering diagnostics export closed synthetic values only."""
from pathlib import Path
import tempfile
import unittest
from scripts.ci import export_ios_ui_evidence as evidence


class CaptureModeJournalEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.container = self.root / "app"
        self.source = self.container / "Library/Caches/eos-capture-mode-trace.txt"
        self.source.parent.mkdir(parents=True)
        self.output = self.root / "output"
        self.output.mkdir()
        self.report = {"files": [], "bytes": 0, "warnings": []}
        self.line = (b"english camera stage=pickerSelection requested=video current=photo "
                     b"stopRequired=false bulb=false settingBusy=false captureBusy=false recordingBusy=false\n")

    def collect(self):
        evidence.collect_capture_mode_journal(self.container, self.output, self.report)

    def test_fixed_events_preserve_exact_order(self):
        data = self.line.replace(b"pickerSelection", b"touchBegan") + self.line + self.line.replace(b"requested=video", b"requested=photo")
        self.source.write_bytes(data)
        self.collect()
        self.assertEqual((self.output / "capture-mode-trace.txt").read_bytes(), data)
        self.assertEqual(self.report["bytes"], len(data))
        self.assertEqual(self.report["warnings"], [])

    def test_unknown_private_or_unbounded_fields_are_rejected(self):
        for data in (self.line.replace(b"english", b"private"), self.line.replace(b"pickerSelection", b"other"),
                     self.line.replace(b"requested=video", b"requested=secret"), self.line + b"url=http://private\n",
                     self.line + b"owner=123\n", self.line.replace(b"false", b"2"), self.line + b"\xff\n",
                     self.line * 129, b"x" * (evidence.MAX_TEXT_BYTES + 1)):
            with self.subTest(data=data[:80]):
                self.source.write_bytes(data)
                self.collect()
                self.assertFalse((self.output / "capture-mode-trace.txt").exists())

    def test_symlink_and_dangling_symlink_are_rejected(self):
        target = self.root / "target"
        target.write_bytes(self.line)
        for target_path in (target, self.root / "missing"):
            self.source.symlink_to(target_path)
            self.collect()
            self.assertFalse((self.output / "capture-mode-trace.txt").exists())
            self.source.unlink()
        self.assertEqual(target.read_bytes(), self.line)

    def test_byte_file_caps_and_existing_destination_are_preserved(self):
        self.source.write_bytes(self.line)
        self.report["bytes"] = evidence.MAX_ARTIFACT_BYTES - evidence.SUMMARY_RESERVE
        self.collect()
        self.assertFalse((self.output / "capture-mode-trace.txt").exists())
        self.report["bytes"] = 0
        self.report["files"] = [{}] * evidence.MAX_FILES
        self.collect()
        self.assertFalse((self.output / "capture-mode-trace.txt").exists())
        self.report["files"] = []
        (self.output / "capture-mode-trace.txt").write_bytes(b"retained")
        self.collect()
        self.assertEqual((self.output / "capture-mode-trace.txt").read_bytes(), b"retained")

    def test_writer_requires_test_build_exact_loopback_and_explicit_trace(self):
        root = Path(__file__).resolve().parents[3]
        source = (root / "ios/OpenEOSControl/App/CameraAppState.swift").read_text()
        body = source.split("func traceCaptureMode(", 1)[1].split("@discardableResult", 1)[0]
        self.assertIn("#if DEBUG", body)
        self.assertIn("#if OEC_REQUIRE_SIMULATOR_E2E", body)
        self.assertIn('["OEC_CAPTURE_MODE_TRACE"] == "1"', body)
        self.assertIn('["OEC_HTTP_PRESET_URL"] == "http://127.0.0.1:18080"', body)
        self.assertIn("lines.suffix(128)", body)
        self.assertIn("guard bytes.count <= 16_384", body)

    def test_touch_probe_is_test_only_passive_and_bounded_to_picker(self):
        root = Path(__file__).resolve().parents[3]
        source = (root / "ios/OpenEOSControl/App/CaptureModeTouchProbe.swift").read_text()
        self.assertTrue(source.startswith("#if DEBUG && OEC_REQUIRE_SIMULATOR_E2E"))
        for guard in ('["OEC_CAPTURE_MODE_TRACE"] == "1"',
                      '["OEC_HTTP_PRESET_URL"] == "http://127.0.0.1:18080"',
                      "frame.contains(point)", "point.x < frame.midX ? .photo : .video",
                      "recognizer.cancelsTouchesInView = false", "recognizer.delaysTouchesBegan = false",
                      "recognizer.delaysTouchesEnded = false", "removeGestureRecognizer(observer)"):
            self.assertIn(guard, source)
        self.assertNotIn("return true", source)
        self.assertNotIn(".sendActions", source)
        self.assertNotIn(".tap()", source)
