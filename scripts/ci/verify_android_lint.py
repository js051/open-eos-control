#!/usr/bin/env python3
"""Prove that all four previously skipped AndroidX Lint registries execute."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


EXPECTED = {
    "UnusedBoxWithConstraintsScope": "androidx.compose.foundation.lint.FoundationIssueRegistry",
    "UnrememberedMutableState": "androidx.compose.runtime.lint.RuntimeIssueRegistry",
    "ModifierFactoryExtensionFunction": "androidx.compose.ui.lint.UiIssueRegistry",
    "NullSafeMutableLiveData": "androidx.lifecycle.lint.LiveDataCoreIssueRegistry",
}
BROKEN_CHECKS = {"ObsoleteLintCustomCheck", "LintError", "UnknownIssueId"}


class CanaryError(ValueError):
    pass


def fingerprint_sources(repo: Path) -> dict[str, str]:
    """Hash only this repository's app production/test sources, never symlink targets."""
    source_root = repo / "android/app/src"
    if (source_root.is_symlink() or not source_root.is_dir()
            or not source_root.resolve().is_relative_to(repo.resolve())):
        raise CanaryError("App source root is missing or resolves outside the repository")

    def fail_unreadable(error: OSError) -> None:
        raise error

    fingerprint = {}
    for directory, directories, files in os.walk(
            source_root, followlinks=False, onerror=fail_unreadable):
        for name in directories + files:
            path = Path(directory) / name
            if path.is_symlink():
                raise CanaryError("Refusing to fingerprint a symlink in app sources")
        for name in files:
            path = Path(directory) / name
            fingerprint[path.relative_to(source_root).as_posix()] = hashlib.sha256(
                path.read_bytes()
            ).hexdigest()
    return fingerprint


def verify_sources_unchanged(repo: Path, before: dict[str, str]) -> dict:
    after = fingerprint_sources(repo)
    unchanged = before == after
    if not unchanged:
        raise CanaryError("App source paths or contents changed during the Lint canary")
    digest = hashlib.sha256(
        json.dumps(after, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return {"unchanged": unchanged, "root": "android/app/src", "files": len(after),
            "sha256": digest}


def verify_report(report: Path, fixture: Path, returncode: int) -> dict:
    """Require genuine findings at our generated source, never just a quiet log."""
    if not report.is_file():
        raise CanaryError("Lint did not produce the canary XML report")
    root = ET.parse(report).getroot()
    issues = root.findall("issue")
    broken = sorted({issue.get("id", "") for issue in issues} & BROKEN_CHECKS)
    if broken:
        raise CanaryError(f"Lint registry/analysis failure: {', '.join(broken)}")
    if returncode != 1:
        raise CanaryError(f"Synthetic errors must fail Gradle (exit 1), got {returncode}")
    findings = {}
    for issue in issues:
        issue_id = issue.get("id")
        if issue_id not in EXPECTED:
            continue
        for location in issue.findall("location"):
            reported = Path(location.get("file", ""))
            # absolutePaths is explicitly enabled in the invocation-only init file.
            if reported.is_absolute() and reported.resolve() == fixture.resolve():
                if int(location.get("line", "0")) > 0:
                    findings[issue_id] = {
                        "registry": EXPECTED[issue_id],
                        "line": int(location.get("line")),
                        "severity": issue.get("severity"),
                    }
    missing = sorted(EXPECTED.keys() - findings.keys())
    if missing:
        raise CanaryError(f"Missing generated-source detector findings: {', '.join(missing)}")
    return {"engine": root.get("by"), "fixture": str(fixture), "findings": findings}


def init_script(work: Path, fixture_dir: Path, report: Path) -> str:
    # JSON strings are also valid Groovy string literals and escape local paths.
    return f"""// Applied only to this canary invocation, never a normal app build.
if (gradle.startParameter.taskNames != [':app:lintDebug']) {{
    throw new GradleException('Canary init script only supports :app:lintDebug')
}}
gradle.beforeProject {{ project ->
    if (project.path == ':app') {{
        // Compiled canary classes and all app outputs stay outside app/build.
        project.layout.buildDirectory.set(new File({json.dumps(str(work / 'app-build'))}))
        project.plugins.withId('com.android.application') {{
            project.androidComponents.finalizeDsl {{ android ->
                android.sourceSets.getByName('main').java.srcDir({json.dumps(str(fixture_dir))})
                android.lint.absolutePaths = true
                android.lint.xmlReport = true
                android.lint.xmlOutput = new File({json.dumps(str(report))})
                android.lint.htmlReport = false
                android.lint.abortOnError = true
            }}
        }}
    }}
}}
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-runner", type=Path, help="Existing environment-specific Gradle shell helper")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    output = repo / "android/build/lint-registry-canary"
    output.mkdir(parents=True, exist_ok=True)
    for previous in ("verified.json", "lint-results.xml"):
        (output / previous).unlink(missing_ok=True)
    # A fresh run cannot accidentally pass using a stale XML report.
    with tempfile.TemporaryDirectory(prefix="run-", dir=output) as temporary:
        work = Path(temporary)
        fixture_dir = work / "sources"
        fixture_dir.mkdir()
        fixture = fixture_dir / "RegistryCanary.kt"
        shutil.copyfile(Path(__file__).with_name("fixtures") / "lint-registry-canary.kt", fixture)
        report = work / "lint-results.xml"
        init = work / "canary.init.gradle"
        init.write_text(init_script(work, fixture_dir, report), encoding="utf-8")
        if args.gradle_runner:
            command = ["bash", str(args.gradle_runner.resolve())]
        else:
            command = ["bash", str(repo / "android/gradlew"), "-p", str(repo / "android"),
                       "--no-daemon", "--console=plain"]
        command += [":app:lintDebug", "--init-script", str(init), "--max-workers=2"]
        if args.offline:
            command.append("--offline")
        env = dict(os.environ, OEC_BUILD_ROOT=str(repo))
        log = output / "gradle.log"
        try:
            sources_before = fingerprint_sources(repo)
            try:
                with log.open("w", encoding="utf-8") as stream:
                    result = subprocess.run(command, cwd=repo, env=env, stdout=stream,
                                            stderr=subprocess.STDOUT, timeout=900, check=False)
            finally:
                source_evidence = verify_sources_unchanged(repo, sources_before)
            if report.exists():
                shutil.copyfile(report, output / "lint-results.xml")
            evidence = verify_report(report, fixture, result.returncode)
        except (CanaryError, OSError, ET.ParseError, subprocess.TimeoutExpired) as error:
            print(f"Android Lint canary FAILED: {error}; see {log}")
            return 1
        evidence["syntheticGradleExitCode"] = result.returncode
        evidence["productionSourcesUnchanged"] = source_evidence["unchanged"]
        evidence["sourceFingerprint"] = source_evidence
        (output / "verified.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(f"Android Lint canary passed: {evidence['engine']}; all four registries executed")
        for issue_id, finding in evidence["findings"].items():
            print(f"  {issue_id}: generated RegistryCanary.kt:{finding['line']}")
        # TemporaryDirectory removes generated sources and compiled outputs even on failure.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
