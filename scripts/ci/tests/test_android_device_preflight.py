"""Source guards for the supplemental pre-KVM compile barrier, not native evidence."""
from pathlib import Path
import re
import unittest

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
            self.assertIn("script: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true", job)
            self.assertNotIn("android.testInstrumentationRunnerArguments.class", job)
            self.assertNotIn("android.testInstrumentationRunnerArguments.notClass", job)
            self.assertNotIn("continue-on-error", job)

    def test_one_shot_guard_retains_exact_prior_head_and_first_attempt(self):
        self.assertIn("if: github.event.before == '575eb20d006072a133bbb8df6f7f95b3975a6985' && github.run_attempt == 1", self.jobs["secret-scan"])
        self.assertIn("branches: [dev]", self.source)
        self.assertIn("      - '.github/workflows/android-device-dev.yml'", self.source)
        self.assertNotIn("workflow_dispatch", self.source)
        self.assertIn("cancel-in-progress: false", self.source)


if __name__ == "__main__":
    unittest.main()
