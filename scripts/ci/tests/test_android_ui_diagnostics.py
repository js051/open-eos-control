"""Diagnostic activation preserves the unchanged full gate and packages fresh identity."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
SOURCES = ("scripts/ci/run-android-ui-tests.sh",
           "scripts/diagnostics/measure-writer-probe/package-ci-evidence.py",
           "scripts/diagnostics/measure-writer-probe/verify-run.py")
MANIFEST = "compose-1.9.3;entries=3;classWrites=17;guards=4;probe=v2"


class AndroidUiDiagnosticsTest(unittest.TestCase):
    def run_stage(self, test_exit=0, diagnostic_exit=0, mode="complete", job="android-ui", log_exit=0):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in SOURCES:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            android = root / "android"
            android.mkdir()
            binary = root / "bin"
            binary.mkdir()
            calls = root / "calls.jsonl"

            def executable(path, text):
                path.write_text(f"#!{sys.executable}\n" + textwrap.dedent(text))
                path.chmod(0o755)

            executable(binary / "git", f'''
                import json, sys
                from pathlib import Path
                args = sys.argv[1:]
                if args == ["rev-parse", "HEAD"]: print("a" * 40)
                elif args == ["rev-parse", "HEAD^{{tree}}"]: print("b" * 40)
                elif args[0] == "ls-files":
                    sys.stdout.write("\\0".join({list(SOURCES) + ["android/gradlew"]!r}) + "\\0")
                else: raise SystemExit("Unexpected Git/base/network access: " + str(args))
            ''')
            executable(android / "gradlew", f'''
                import hashlib, json, os, sys
                from pathlib import Path
                args = sys.argv[1:]
                invocation = next(arg.split("=", 1)[1] for arg in args if arg.startswith("-PeosMeasureWriterInvocation="))
                pre = json.loads((Path("app/build/eos-measure-writer-host") / invocation / "pre-run-manifest.json").read_text())
                assert pre["invocation"] == invocation and pre["command"] == ["./gradlew"] + args
                with open({str(calls)!r}, "a") as output:
                    output.write(json.dumps(["gradlew", args, pre]) + "\\n")
                if {mode!r} == "source_changed":
                    with open("../scripts/ci/run-android-ui-tests.sh", "a") as source: source.write("\\n# changed after freeze\\n")
                if {mode!r} == "pre_changed":
                    path = Path("app/build/eos-measure-writer-host") / invocation / "pre-run-manifest.json"
                    path.write_text(path.read_text() + " ")
                for relative, data in (("debug/app-debug.apk", b"synthetic app bytes"),
                                       ("androidTest/debug/app-debug-androidTest.apk", b"synthetic test bytes")):
                    if {mode!r} == "missing_apk" and "androidTest" in relative: continue
                    path = Path("app/build/outputs/apk") / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
                # Exercise collector recreation without losing the independent pre-run staging.
                collector = Path("app/build/outputs/connected_android_test_additional_output/device")
                collector.mkdir(parents=True, exist_ok=True)
                report_invocation = "stale-invocation" if {mode!r} == "stale" else invocation
                app = hashlib.sha256(b"synthetic app bytes").hexdigest()
                test = hashlib.sha256(b"synthetic test bytes").hexdigest()
                if {mode!r} == "wrong_apk": app = "c" * 64
                header = f"INVOCATION {{report_invocation}}\\nSESSION token=1 pid=2\\nAPK app={{app}} test={{test}}\\n"
                start = header + "EXPECTED {MANIFEST}\\nOUTPUT_ROUTE collectorArgument=true\\nSTART_COMPLETE\\n"
                boundary = "fatal" if {test_exit} else "finish"
                report = header + f"APPLIED {MANIFEST}\\nPROOF_VALID manifest={MANIFEST} entries=4 pre=8 post=8 broken=false\\nBOUNDARY {{boundary}}\\nREPORT_COMPLETE\\n"
                stem = f"eos-measure-writer-{{report_invocation}}-1-2-"
                (collector / (stem + "start.txt")).write_text(start)
                if {mode!r} != "missing_report":
                    suffix = ".pending" if {mode!r} == "pending" else ".txt"
                    (collector / (stem + boundary + suffix)).write_text(report)
                print("synthetic instrumentation result")
                raise SystemExit({test_exit})
            ''')
            executable(binary / "adb", f'''
                import json, sys
                with open({str(calls)!r}, "a") as output:
                    output.write(json.dumps(["adb", sys.argv[1:]]) + "\\n")
                print("EOSDateFilterDiag: synthetic phase")
                raise SystemExit({diagnostic_exit})
            ''')
            if log_exit:
                executable(binary / "tee", f'''
                    import sys
                    from pathlib import Path
                    text = sys.stdin.read()
                    Path(sys.argv[-1]).write_text(text)
                    print(text, end="")
                    raise SystemExit({log_exit})
                ''')
            environment = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                               GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="2", GITHUB_JOB=job,
                               GITHUB_SHA="a" * 40, GITHUB_REPOSITORY="synthetic/project",
                               GITHUB_REF="refs/heads/diagnostic", GITHUB_WORKFLOW="Synthetic Android")
            result = subprocess.run(["bash", str(root / SOURCES[0])], cwd=android, env=environment,
                                    capture_output=True, text=True, timeout=20)
            recorded = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
            bundles = list(android.glob("app/build/outputs/connected_android_test_additional_output/eos-measure-writer-host-*"))
            bundle = {path.name: path.read_bytes() for path in bundles[0].iterdir()} if bundles else {}
        return result, recorded, bundle

    def assert_command(self, calls):
        gradle = [call for call in calls if call[0] == "gradlew"]
        self.assertEqual(len(gradle), 1)
        invocation = gradle[0][2]["invocation"]
        self.assertRegex(invocation, r"^[0-9a-f]{32}$")
        self.assertEqual(gradle[0][1], [":app:connectedDebugAndroidTest",
                         "-Pandroid.testInstrumentationRunnerArguments.requireSimulator=true",
                         "-PeosMeasureWriterProbe=true", "-PeosMeasureWriterInvocation=" + invocation])
        return invocation

    def test_explicit_full_command_and_successful_packaging_for_both_jobs(self):
        for job in ("android-ui", "android-ui-api36"):
            with self.subTest(job=job):
                result, calls, bundle = self.run_stage(job=job)
                self.assertEqual(result.returncode, 0, result.stderr)
                invocation = self.assert_command(calls)
                self.assertEqual(len(calls), 1)  # no successful-run logcat query
                pre = json.loads(bundle["pre-run-manifest.json"])
                receipt = json.loads(bundle["run-manifest.json"])
                self.assertEqual(pre["invocation"], invocation)
                self.assertEqual(pre["ci"]["job"], job)
                self.assertEqual(pre["ci"]["run_attempt"], "2")
                self.assertNotIn("app_apk_sha256", pre)  # these APKs did not exist at prepare
                self.assertEqual(receipt["gradle_exit"], 0)
                for field, name in (("pre_run_manifest_sha256", "pre-run-manifest.json"),
                                    ("source_snapshot_sha256", "source-snapshot.json"),
                                    ("app_apk_sha256", "app-debug.apk"),
                                    ("test_apk_sha256", "app-debug-androidTest.apk"),
                                    ("build_log_sha256", "build.log")):
                    self.assertEqual(receipt[field], hashlib.sha256(bundle[name]).hexdigest())
                snapshot = json.loads(bundle["source-snapshot.json"])
                self.assertEqual(set(snapshot["files"]), set(SOURCES) | {"android/gradlew"})
                for item in snapshot["files"].values():
                    self.assertEqual(item["sha256"], hashlib.sha256(item["utf8"].encode()).hexdigest())

    def test_every_launch_gets_a_fresh_pre_recorded_invocation(self):
        first = self.assert_command(self.run_stage()[1])
        second = self.assert_command(self.run_stage()[1])
        self.assertNotEqual(first, second)

    def test_setup_failure_never_launches_gradle(self):
        result, calls, bundle = self.run_stage(job="unexpected-job")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])
        self.assertEqual(bundle, {})

    def test_pre_run_manifest_cannot_be_rewritten_by_the_run(self):
        for test_exit in (0, 29):
            with self.subTest(test_exit=test_exit):
                result, calls, bundle = self.run_stage(test_exit, mode="pre_changed")
                self.assertEqual(result.returncode, 29 if test_exit else 1)
                self.assertIn("independently recorded current job hash", result.stderr)
                self.assertNotIn("run-manifest.json", bundle)
                self.assert_command(calls)

    def test_green_test_with_missing_or_invalid_packaging_is_not_success(self):
        for mode in ("missing_apk", "stale", "missing_report", "pending", "wrong_apk", "source_changed"):
            with self.subTest(mode=mode):
                result, calls, bundle = self.run_stage(mode=mode)
                self.assertNotEqual(result.returncode, 0)
                self.assert_command(calls)
                self.assertNotIn("run-manifest.json", bundle)
                self.assertIn("pre-run-manifest.json", bundle)
                self.assertEqual(len(calls), 1)

    def test_original_failure_survives_package_and_bounded_logcat_failure(self):
        for mode in ("complete", "missing_apk", "stale", "source_changed"):
            with self.subTest(mode=mode):
                result, calls, bundle = self.run_stage(37, diagnostic_exit=41, mode=mode)
                self.assertEqual(result.returncode, 37)
                self.assert_command(calls)
                self.assertEqual(calls[1:], [["adb", ["logcat", "-d", "-v", "brief", "EOSDateFilterDiag:V", "*:S"]]])
                if mode == "complete":
                    self.assertEqual(json.loads(bundle["run-manifest.json"])["gradle_exit"], 37)
                else:
                    self.assertNotIn("run-manifest.json", bundle)

    def test_log_capture_failure_invalidates_success_but_preserves_test_failure(self):
        for test_exit in (0, 23):
            with self.subTest(test_exit=test_exit):
                result, calls, bundle = self.run_stage(test_exit, log_exit=19)
                self.assertEqual(result.returncode, 23 if test_exit else 1)
                self.assertNotIn("run-manifest.json", bundle)
                self.assert_command(calls)


if __name__ == "__main__":
    unittest.main()
