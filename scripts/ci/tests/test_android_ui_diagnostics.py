"""Failure diagnostics must preserve the original complete instrumentation gate."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "run-android-ui-tests.sh"


class AndroidUiDiagnosticsTest(unittest.TestCase):
    def run_stage(self, test_exit, diagnostic_exit=0, diagnostic_text=""):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            calls = root / "calls.jsonl"
            for command, code, text in (
                ("gradlew", test_exit, "synthetic instrumentation result"),
                ("adb", diagnostic_exit, diagnostic_text),
            ):
                stub = root / command
                stub.write_text(
                    f"#!{sys.executable}\n"
                    "import json, sys\n"
                    f"with open({str(calls)!r}, 'a') as f: "
                    f"f.write(json.dumps([{command!r}, sys.argv[1:]]) + '\\n')\n"
                    f"print({text!r})\nraise SystemExit({code})\n"
                )
                stub.chmod(0o755)
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(
                ["bash", str(SCRIPT)], cwd=root, env=environment,
                capture_output=True, text=True, timeout=15,
            )
            recorded = [json.loads(line) for line in calls.read_text().splitlines()]
        self.assertEqual(recorded[0], ["gradlew", [
            ":app:connectedDebugAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.requireSimulator=true",
        ]])
        self.assertEqual(sum(call[0] == "gradlew" for call in recorded), 1)
        return result, recorded

    def test_success_does_not_query_logcat(self):
        result, calls = self.run_stage(0)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)

    def test_failure_exports_only_the_fixture_tag_and_keeps_failure(self):
        result, calls = self.run_stage(17, diagnostic_text="EOSDateFilterDiag: synthetic phase")
        self.assertEqual(result.returncode, 17)
        self.assertEqual(calls[1:], [["adb", ["logcat", "-d", "-v", "brief", "EOSDateFilterDiag:V", "*:S"]]])
        self.assertIn("synthetic phase", result.stdout)

    def test_empty_tag_does_not_replace_original_failure(self):
        result, calls = self.run_stage(23)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(len(calls), 2)

    def test_failed_diagnostic_does_not_replace_original_failure(self):
        result, calls = self.run_stage(37, diagnostic_exit=41)
        self.assertEqual(result.returncode, 37)
        self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()
