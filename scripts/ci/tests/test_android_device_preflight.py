"""Source guards for the supplemental pre-KVM compile barrier, not native evidence."""
from pathlib import Path
import re
import unittest
import importlib.util
import json
import signal
import subprocess
import sys
import tempfile
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/android-device-dev.yml"


def jobs(source: str) -> dict[str, str]:
    boundaries = list(re.finditer(r"^  ([a-z][a-z0-9-]*):$", source, re.MULTILINE))
    return {
        match.group(1): source[match.end():boundaries[index + 1].start() if index + 1 < len(boundaries) else len(source)]
        for index, match in enumerate(boundaries)
    }


class AndroidDevicePreflightTests(unittest.TestCase):
    def setUp(self):
        self.source = WORKFLOW.read_text()
        self.jobs = jobs(self.source)

    def test_compile_is_a_shared_fail_closed_barrier(self):
        compile_job = self.jobs["instrumentation-compile"]
        self.assertIn("    needs: secret-scan\n", compile_job)
        self.assertIn("working-directory: android", compile_job)
        self.assertIn("set -euo pipefail", compile_job)
        self.assertIn("./gradlew :app:compileDebugAndroidTestKotlin -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true", compile_job)
        self.assertIn('tee "$RUNNER_TEMP/android-instrumentation-compile.log"', compile_job)
        self.assertNotIn("continue-on-error", self.source)
        self.assertNotIn(" -x ", compile_job)
        for name in ("android-ui", "android-ui-api36"):
            self.assertIn("    needs: instrumentation-compile\n", self.jobs[name])
            self.assertNotRegex(self.jobs[name], re.compile(r"^    if:", re.MULTILINE), name)

    def test_compile_never_uses_emulator_or_security_changes(self):
        preflight = self.jobs["instrumentation-compile"]
        for forbidden in ("sudo ", "udevadm", "android-emulator-runner", "connectedDebugAndroidTest"):
            self.assertNotIn(forbidden, preflight)
        self.assertNotRegex(preflight, re.compile(r"^\s+ref:", re.MULTILINE), "Checkout must use the event SHA")

    def test_original_two_device_jobs_and_full_suite_are_retained(self):
        self.assertEqual(2, self.source.count("- name: Enable KVM access"))
        for name, api in (("android-ui", 34), ("android-ui-api36", 36)):
            job = self.jobs[name]
            self.assertIn(f"api-level: {api}", job)
            self.assertIn('script: python ../scripts/ci/run_android_device_health.py --evidence-dir "$RUNNER_TEMP/android-device-health" -- ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true', job)
            self.assertIn("${{ runner.temp }}/android-device-health", job)
            self.assertNotIn("android.testInstrumentationRunnerArguments.class", job)
            self.assertNotIn("android.testInstrumentationRunnerArguments.notClass", job)
            self.assertNotIn("continue-on-error", job)

    def test_one_shot_guard_retains_exact_prior_head_and_first_attempt(self):
        self.assertIn("if: github.event.before == 'a78e7a08840cf063a60a13d3fdfc8fe2ef99349e' && github.run_attempt == 1", self.jobs["secret-scan"])
        self.assertIn("branches: [dev]", self.source)
        self.assertIn("      - '.github/workflows/android-device-dev.yml'", self.source)
        self.assertNotIn("workflow_dispatch", self.source)
        self.assertIn("cancel-in-progress: false", self.source)


HEALTH_SPEC = importlib.util.spec_from_file_location(
    "android_device_health", ROOT / "scripts/ci/run_android_device_health.py")
health = importlib.util.module_from_spec(HEALTH_SPEC)
sys.modules[HEALTH_SPEC.name] = health
HEALTH_SPEC.loader.exec_module(health)

FOCUS = "  mCurrentFocus=Window{123 u0 Application Not Responding: com.android.systemui}"
NORMAL = "  mCurrentFocus=Window{123 u0 dev.openeos.control/.MainActivity}"


class FakeChild:
    pid = 12345

    def __init__(self, codes=(None, 0)):
        self.codes = iter(codes)
        self.last = None

    def poll(self):
        self.last = next(self.codes, self.last)
        return self.last

    def wait(self, timeout):
        return self.last


class AndroidDeviceHealthTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.normal = health.Observation("2026-10-20T11:00:00Z", NORMAL)
        self.anr = health.Observation("2026-10-20T11:00:05Z", FOCUS, FOCUS)

    def invoke(self, observations, child=None, **overrides):
        self.child = child or FakeChild((0,))
        self.start = mock.Mock(return_value=self.child)
        self.stop = mock.Mock(return_value=[])
        self.collect = mock.Mock(return_value={"synthetic": "evidence"})
        options = dict(observe=mock.Mock(side_effect=observations), start=self.start,
                       stop=self.stop, collect=self.collect, sleep=lambda _: None,
                       monotonic=mock.Mock(side_effect=range(100)))
        options.update(overrides)
        return health.run_guard(["synthetic-test", "original-argument"], self.path, **options)

    def report(self):
        return json.loads((self.path / "device-health.json").read_text())

    def test_exact_focus_matches_and_preserves_original_line(self):
        self.assertEqual(FOCUS, health.focused_systemui_anr("other\n" + FOCUS + "\n"))

    def test_historical_other_package_and_nonfocus_text_do_not_match(self):
        for line in (FOCUS.replace("mCurrentFocus", "mLastFocus"),
                     FOCUS.replace("com.android.systemui}", "com.android.systemui.fake}"),
                     FOCUS.replace("com.android.systemui", "dev.openeos.control"),
                     "logcat: Application Not Responding: com.android.systemui",
                     "mCurrentFocus=null", NORMAL):
            with self.subTest(line=line):
                self.assertIsNone(health.focused_systemui_anr(line))

    def test_preflight_anr_does_not_launch_command(self):
        self.assertEqual(86, self.invoke([self.anr]))
        self.start.assert_not_called()
        self.stop.assert_not_called()
        self.assertEqual(FOCUS, (self.path / "window-focus.txt").read_text())
        self.assertEqual("2026-10-20T11:00:05Z", self.report()["last_observed_at"])
        self.assertFalse(self.report()["command_started"])

    def test_observer_error_does_not_launch_command(self):
        self.assertEqual(87, self.invoke([health.Observation("now", "", error="offline")]))
        self.start.assert_not_called()
        self.assertEqual("environment_observation_error", self.report()["outcome"])

    def test_observer_exception_does_not_launch_command(self):
        self.assertEqual(87, self.invoke([OSError("synthetic observer failure")]))
        self.start.assert_not_called()

    def test_normal_child_completion_preserves_exit_and_full_command(self):
        self.assertEqual(0, self.invoke([self.normal, self.normal]))
        self.start.assert_called_once_with(["synthetic-test", "original-argument"])
        self.stop.assert_not_called()
        self.assertEqual("child_completed", self.report()["outcome"])
        self.assertEqual(2, self.report()["observation_count"])

    def test_ordinary_child_failure_is_not_hidden(self):
        self.assertEqual(7, self.invoke([self.normal, self.normal], FakeChild((7,))))
        self.assertEqual(7, self.report()["child_returncode"])
        self.assertEqual("child_failed", self.report()["outcome"])

    def test_signal_failure_maps_to_nonzero(self):
        self.assertEqual(143, self.invoke([self.normal, self.normal], FakeChild((-15,))))
        self.assertEqual(-15, self.report()["child_returncode"])

    def test_final_observation_preserves_prior_child_failure(self):
        self.assertEqual(86, self.invoke([self.normal, self.anr], FakeChild((7,))))
        self.assertEqual(7, self.report()["child_returncode"])
        self.assertEqual("unhealthy", self.report()["outcome"])

    def test_midrun_anr_stops_owned_child_and_preserves_existing_xml(self):
        xml = self.path / "existing-failed-results.xml"
        xml.write_text('<testsuite failures="1"><testcase><failure/></testcase></testsuite>')
        before = xml.read_bytes()
        self.assertEqual(86, self.invoke([self.normal, self.anr], FakeChild((None,)), poll_seconds=1))
        self.stop.assert_called_once_with(self.child)
        self.assertEqual(before, xml.read_bytes())
        self.assertEqual("unhealthy", self.report()["outcome"])

    def test_midrun_observer_failure_stops_owned_child(self):
        self.assertEqual(87, self.invoke([self.normal, OSError("lost adb")], FakeChild((None,)), poll_seconds=1))
        self.stop.assert_called_once_with(self.child)

    def test_diagnostic_failure_cannot_mask_anr_or_cleanup(self):
        collect = mock.Mock(side_effect=OSError("capture failed"))
        self.assertEqual(86, self.invoke([self.normal, self.anr], FakeChild((None,)),
                                         poll_seconds=1, collect=collect))
        self.stop.assert_called_once_with(self.child)
        self.assertIn("capture failed", self.report()["diagnostics"]["error"])

    def test_cleanup_failure_is_reported_and_nonzero(self):
        self.assertEqual(86, self.invoke([self.normal, self.anr], FakeChild((None,)),
                         poll_seconds=1, stop=mock.Mock(side_effect=OSError("cleanup failed"))))
        self.assertIn("cleanup failed", self.report()["cleanup_errors"][0])

    def test_start_failure_is_nonzero(self):
        self.assertEqual(87, self.invoke([self.normal], start=mock.Mock(side_effect=OSError("missing command"))))
        self.assertFalse(self.report()["command_started"])

    def test_interrupt_stops_owned_child(self):
        self.assertEqual(143, self.invoke([self.normal, health.GuardInterrupted(signal.SIGTERM)],
                                         FakeChild((None,)), poll_seconds=1))
        self.stop.assert_called_once_with(self.child)
        self.assertEqual("interrupted", self.report()["outcome"])

    def test_adb_observation_is_bounded_and_read_only(self):
        runner = mock.Mock(return_value=health.CommandCapture(0, ("unrelated synthetic window details\n" + FOCUS).encode()))
        result = health.observe_device(runner)
        self.assertEqual(FOCUS, result.focused_anr)
        self.assertNotIn("unrelated synthetic window details", result.window_text)
        runner.assert_called_once_with(["adb", "shell", "dumpsys", "-t", "1", "window", "displays"], 5.0)

    def test_empty_nonzero_and_timeout_observations_fail_closed(self):
        for result in (health.CommandCapture(0), health.CommandCapture(0, b"permission denied"),
                       health.CommandCapture(1, NORMAL.encode()),
                       health.CommandCapture(None, NORMAL.encode(), error="timeout")):
            with self.subTest(result=result):
                self.assertIsNotNone(health.observe_device(lambda *_: result).error)

    def test_diagnostics_preserve_binary_screenshot_and_narrow_commands(self):
        png = b"\x89PNG\r\n\x1a\nsynthetic"
        runner = mock.Mock(side_effect=[health.CommandCapture(0, png), health.CommandCapture(0, b"synthetic ANR")])
        result = health.collect_diagnostics(self.path, runner)
        self.assertEqual(png, (self.path / "systemui-anr.png").read_bytes())
        self.assertEqual("systemui-anr.png", result["systemui-anr.png"]["artifact"])
        self.assertEqual([mock.call(["adb", "exec-out", "screencap", "-p"], 5.0),
                          mock.call(["adb", "shell", "dumpsys", "activity", "lastanr"], 5.0)], runner.call_args_list)

    def test_diagnostic_errors_continue_to_next_capture(self):
        result = health.collect_diagnostics(self.path, mock.Mock(side_effect=[OSError("bad png"), health.CommandCapture(1, stderr=b"unavailable")]))
        self.assertIn("bad png", result["systemui-anr.png"]["error"])
        self.assertEqual(1, result["lastanr.txt"]["returncode"])

    def test_initial_evidence_write_failure_does_not_launch(self):
        with mock.patch.object(health, "write_report", return_value=False):
            self.assertEqual(87, self.invoke([]))
        self.start.assert_not_called()

    def test_midrun_evidence_write_failure_still_stops_owned_child(self):
        with mock.patch.object(health, "write_report", side_effect=[True, True, True, False, False]):
            self.assertEqual(87, self.invoke([self.normal, self.normal], FakeChild((None,)), poll_seconds=1))
        self.stop.assert_called_once_with(self.child)

    def test_command_start_persistence_failure_stops_owned_child(self):
        with mock.patch.object(health, "write_report", side_effect=[True, True, False, False]):
            self.assertEqual(87, self.invoke([self.normal], FakeChild((None,))))
        self.stop.assert_called_once_with(self.child)

    def test_capture_timeout_uses_bounded_owned_group_cleanup(self):
        child = mock.Mock()
        child.wait.side_effect = subprocess.TimeoutExpired("synthetic adb", 5)
        child.poll.return_value = -15
        with mock.patch.object(health.subprocess, "Popen", return_value=child) as start, \
             mock.patch.object(health, "stop_process_group", return_value=[]) as stop:
            result = health.run_capture(["synthetic-adb"], 5)
        self.assertIn("timed out", result.error)
        stop.assert_called_once_with(child, grace_seconds=0.25, kill_seconds=0.5)
        self.assertTrue(start.call_args.kwargs["start_new_session"])
        self.assertIs(health.limit_capture_file_size, start.call_args.kwargs["preexec_fn"])

    def test_capture_file_limit_never_raises_inherited_hard_limit(self):
        for hard, expected in ((health.resource.RLIM_INFINITY, health.CAPTURE_LIMIT_BYTES + 1),
                               (1024, 1024)):
            with self.subTest(hard=hard), \
                 mock.patch.object(health.resource, "getrlimit", return_value=(hard, hard)), \
                 mock.patch.object(health.resource, "setrlimit") as limit:
                health.limit_capture_file_size()
                limit.assert_called_once_with(health.resource.RLIMIT_FSIZE, (expected, expected))

    def test_capture_output_overflow_is_non_success_and_retained_bytes_are_bounded(self):
        child = mock.Mock()
        child.wait.return_value = 0
        child.poll.return_value = 0

        def fake_start(*args, **kwargs):
            kwargs["stdout"].write(b"12345")
            kwargs["stdout"].flush()
            return child

        with mock.patch.object(health, "CAPTURE_LIMIT_BYTES", 4), \
             mock.patch.object(health.subprocess, "Popen", side_effect=fake_start), \
             mock.patch.object(health, "stop_process_group", return_value=[]):
            result = health.run_capture(["synthetic-adb"])
        self.assertEqual(b"1234", result.stdout)
        self.assertIn("exceeded capture limit", result.error)

    def test_owned_group_escalation_is_bounded(self):
        child = mock.Mock(pid=23456)
        child.wait.side_effect = subprocess.TimeoutExpired("synthetic", 1)
        with mock.patch.object(health.os, "killpg") as kill, \
             mock.patch.object(health.time, "monotonic", side_effect=[0, 3]):
            errors = health.stop_process_group(child)
        self.assertEqual([mock.call(23456, signal.SIGTERM), mock.call(23456, 0),
                          mock.call(23456, 0), mock.call(23456, signal.SIGKILL)], kill.call_args_list)
        child.wait.assert_called_once_with(timeout=1.0)
        self.assertTrue(errors)

    def test_capture_reaps_owned_descendants_even_after_leader_exits(self):
        child = mock.Mock()
        child.wait.return_value = 0
        child.poll.return_value = 0
        with mock.patch.object(health.subprocess, "Popen", return_value=child), \
             mock.patch.object(health, "stop_process_group", return_value=[]) as stop:
            result = health.run_capture(["synthetic-adb"], 5)
        self.assertEqual(0, result.returncode)
        stop.assert_called_once_with(child, grace_seconds=0.25, kill_seconds=0.5)

    def test_missing_owned_group_is_harmless(self):
        child = mock.Mock(pid=23456)
        with mock.patch.object(health.os, "killpg", side_effect=ProcessLookupError):
            self.assertEqual([], health.stop_process_group(child))


if __name__ == "__main__":
    unittest.main()
