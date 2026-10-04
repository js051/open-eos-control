from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from scripts.ci.verify_android_lint import (
    CanaryError, EXPECTED, fingerprint_sources, init_script, verify_report,
    verify_sources_unchanged,
)


class AndroidLintCanaryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        self.fixture = self.work / "sources/RegistryCanary.kt"
        self.report = self.work / "lint-results.xml"

    def write_report(self, *, omit: str = "", file: Path | None = None, extra: str = "") -> None:
        root = ET.Element("issues", {"by": "lint 8.8.2"})
        for issue_id in EXPECTED:
            if issue_id != omit:
                issue = ET.SubElement(root, "issue", {"id": issue_id, "severity": "Error"})
                ET.SubElement(issue, "location", {"file": str(file or self.fixture), "line": "13"})
        if extra:
            ET.SubElement(root, "issue", {"id": extra, "severity": "Warning"})
        ET.ElementTree(root).write(self.report, encoding="utf-8")

    def test_requires_findings_from_all_four_registries(self) -> None:
        self.write_report()
        evidence = verify_report(self.report, self.fixture, 1)
        self.assertEqual(set(evidence["findings"]), set(EXPECTED))
        self.assertEqual(evidence["engine"], "lint 8.8.2")

    def test_each_missing_registry_fails_closed(self) -> None:
        for issue_id in EXPECTED:
            with self.subTest(issue=issue_id):
                self.write_report(omit=issue_id)
                with self.assertRaisesRegex(CanaryError, issue_id):
                    verify_report(self.report, self.fixture, 1)

    def test_real_app_findings_cannot_substitute_for_canary(self) -> None:
        self.write_report(file=self.work / "app/src/main/RegistryCanary.kt")
        with self.assertRaisesRegex(CanaryError, "Missing"):
            verify_report(self.report, self.fixture, 1)

    def test_relative_basename_cannot_substitute_for_generated_path(self) -> None:
        self.write_report(file=Path("RegistryCanary.kt"))
        with self.assertRaisesRegex(CanaryError, "Missing"):
            verify_report(self.report, self.fixture, 1)

    def test_analysis_and_registry_failures_are_not_ignored(self) -> None:
        for failure in ("LintError", "ObsoleteLintCustomCheck", "UnknownIssueId"):
            with self.subTest(failure=failure):
                self.write_report(extra=failure)
                with self.assertRaisesRegex(CanaryError, failure):
                    verify_report(self.report, self.fixture, 1)

    def test_requires_fresh_report_and_expected_gradle_failure(self) -> None:
        with self.assertRaisesRegex(CanaryError, "did not produce"):
            verify_report(self.report, self.fixture, 1)
        self.write_report()
        for returncode in (0, 2, -9):
            with self.subTest(returncode=returncode):
                with self.assertRaisesRegex(CanaryError, "exit 1"):
                    verify_report(self.report, self.fixture, returncode)

    def test_init_limits_tasks_and_isolates_canary_outputs(self) -> None:
        script = init_script(self.work, self.fixture.parent, self.report)
        self.assertIn("taskNames != [':app:lintDebug']", script)
        self.assertIn(str(self.work / "app-build"), script)
        self.assertIn("android.lint.abortOnError = true", script)
        self.assertNotIn("checkOnly", script)
        self.assertNotIn("disable", script)


class SourceFingerprintTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.sources = self.repo / "android/app/src"
        for name in ("main", "test", "androidTest", "debug"):
            directory = self.sources / name
            directory.mkdir(parents=True)
            (directory / "Synthetic.kt").write_text(f"// {name} source\n", encoding="utf-8")

    def test_unchanged_source_paths_and_contents_have_verified_fingerprint(self) -> None:
        before = fingerprint_sources(self.repo)
        evidence = verify_sources_unchanged(self.repo, before)
        self.assertTrue(evidence["unchanged"])
        self.assertEqual(evidence["root"], "android/app/src")
        self.assertEqual(evidence["files"], 4)
        self.assertEqual(len(evidence["sha256"]), 64)
        self.assertEqual(evidence, verify_sources_unchanged(self.repo, dict(reversed(list(before.items())))))

    def test_source_content_change_fails_even_when_size_is_unchanged(self) -> None:
        before = fingerprint_sources(self.repo)
        (self.sources / "main/Synthetic.kt").write_text("// MAIN source\n", encoding="utf-8")
        with self.assertRaisesRegex(CanaryError, "changed"):
            verify_sources_unchanged(self.repo, before)

    def test_added_source_fails(self) -> None:
        before = fingerprint_sources(self.repo)
        (self.sources / "test/Added.kt").write_text("// synthetic\n", encoding="utf-8")
        with self.assertRaisesRegex(CanaryError, "changed"):
            verify_sources_unchanged(self.repo, before)

    def test_removed_source_fails(self) -> None:
        before = fingerprint_sources(self.repo)
        (self.sources / "androidTest/Synthetic.kt").unlink()
        with self.assertRaisesRegex(CanaryError, "changed"):
            verify_sources_unchanged(self.repo, before)

    def test_renamed_source_fails_even_when_contents_are_unchanged(self) -> None:
        before = fingerprint_sources(self.repo)
        (self.sources / "debug/Synthetic.kt").rename(self.sources / "debug/Renamed.kt")
        with self.assertRaisesRegex(CanaryError, "changed"):
            verify_sources_unchanged(self.repo, before)

    def test_files_outside_app_sources_are_not_read_or_fingerprinted(self) -> None:
        before = fingerprint_sources(self.repo)
        (self.repo / "unrelated.txt").write_text("synthetic non-source", encoding="utf-8")
        self.assertEqual(before, fingerprint_sources(self.repo))

    def test_symlink_files_and_directories_are_rejected_without_following(self) -> None:
        for directory in (False, True):
            with self.subTest(directory=directory):
                link = self.sources / "main/link"
                link.symlink_to(self.repo / "not-a-real-target", target_is_directory=directory)
                try:
                    with self.assertRaisesRegex(CanaryError, "symlink"):
                        fingerprint_sources(self.repo)
                finally:
                    link.unlink()

    def test_missing_source_root_fails_closed(self) -> None:
        with self.assertRaisesRegex(CanaryError, "missing"):
            fingerprint_sources(self.repo / "missing")


if __name__ == "__main__":
    unittest.main()
