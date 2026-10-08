"""Host completion publication has the same close-before-promotion contract as runtime output."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("package_ci_evidence", Path(__file__).with_name("package-ci-evidence.py"))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class PublishReceiptTest(unittest.TestCase):
    def test_success_publishes_only_closed_complete_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp)
            package.publish_receipt(target, {"synthetic": "complete"})
            self.assertEqual(json.loads((target / "run-manifest.json").read_text()), {"synthetic": "complete"})
            self.assertFalse((target / "run-manifest.pending").exists())

    def test_failure_after_complete_bytes_cannot_publish_receipt(self):
        for failure in ("flush", "close"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                target = Path(temp)
                original_open = Path.open

                class FailingOutput:
                    def __init__(self, source): self.source = source
                    def __enter__(self): return self
                    def write(self, data): return self.source.write(data)
                    def flush(self):
                        self.source.flush()
                        if failure == "flush": raise OSError("synthetic flush after complete bytes")
                    def __exit__(self, *args):
                        self.source.close()
                        if failure == "close": raise OSError("synthetic close after complete bytes")

                def open_pending(path, *args, **kwargs):
                    return FailingOutput(original_open(path, *args, **kwargs))

                with patch.object(Path, "open", open_pending), self.assertRaises(OSError):
                    package.publish_receipt(target, {"synthetic": "complete"})
                self.assertFalse((target / "run-manifest.json").exists())
                self.assertEqual(json.loads((target / "run-manifest.pending").read_text()), {"synthetic": "complete"})

    def test_promotion_failure_leaves_unaccepted_pending_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp)
            with patch.object(Path, "replace", side_effect=OSError("synthetic promotion failure")), self.assertRaises(OSError):
                package.publish_receipt(target, {"synthetic": "complete"})
            self.assertFalse((target / "run-manifest.json").exists())
            self.assertTrue((target / "run-manifest.pending").exists())

    def test_existing_final_or_pending_is_never_overwritten(self):
        for name in ("run-manifest.json", "run-manifest.pending"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                target = Path(temp)
                (target / name).write_bytes(b"older evidence")
                with self.assertRaises((ValueError, FileExistsError)):
                    package.publish_receipt(target, {"synthetic": "complete"})
                self.assertEqual((target / name).read_bytes(), b"older evidence")


if __name__ == "__main__":
    unittest.main()
