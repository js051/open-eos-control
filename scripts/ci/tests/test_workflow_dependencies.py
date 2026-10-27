"""Source dependency guards, not a replacement for GitHub/Gradle/Swift execution.

Read the actual consumer declarations and require their inputs in the existing
selectors. Deliberately support only literal paths and trailing /** coverage;
this is not a second implementation of GitHub or picomatch glob semantics.
"""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github/workflows"


def literal_list(source: str, label: str, indent: int) -> list[str]:
    marker = " " * indent + label + ":\n"
    if source.count(marker) != 1:
        raise ValueError(f"Expected one {label} list at indentation {indent}")
    tail = source.split(marker, 1)[1]
    values = []
    for line in tail.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        match = re.fullmatch(" " * (indent + 2) + r"- '([^']+)'", line)
        if match is None:
            raise ValueError(f"Unsupported {label} declaration: {line!r}")
        values.append(match.group(1))
    if not values or len(values) != len(set(values)):
        raise ValueError(f"Empty or duplicate {label} declarations")
    return values


def pr_paths(surface: str) -> list[str]:
    return literal_list((WORKFLOWS / "android.yml").read_text(), surface, 12)


def dev_paths(workflow: str) -> list[str]:
    return literal_list((WORKFLOWS / workflow).read_text(), "paths", 4)


def covers(patterns: list[str], path: str) -> bool:
    return any(pattern == path or
               (pattern.endswith("/**") and path.startswith(pattern[:-2]))
               for pattern in patterns if not pattern.startswith("!"))


def android_fixtures(name: str) -> list[str]:
    source = (ROOT / "android/app/build.gradle.kts").read_text()
    block = source.split(f"val {name} = listOf(\n", 1)[1].split("\n)", 1)[0]
    paths = re.findall(r'"\.\./\.\./(contracts/[^"\n]+\.json)"', block)
    if not paths:
        raise ValueError(f"Missing actual Gradle fixture manifest {name}")
    return paths


def contract_literals(source: str) -> set[str]:
    values = set(re.findall(r'''["'](?:\.\./)*(contracts/[^"'\n]+)["']''', source))
    segmented = list(re.finditer(r'''["']contracts["']''', source))
    handled = set()
    for call in re.finditer(r"path\.join\(\s*__dirname\s*,([^()]*)\)", source):
        references = [index for index, item in enumerate(segmented)
                      if call.start() <= item.start() < call.end()]
        if not references:
            continue
        parts = []
        for argument in call.group(1).split(","):
            literal = re.fullmatch(r'''\s*["']([^"']+)["']\s*''', argument)
            if literal is None:
                raise ValueError("Dynamic segmented contract path requires an explicit dependency review")
            parts.append(literal.group(1))
        suffix = parts[parts.index("contracts"):]
        if any(part in {".", ".."} or "/" in part or "\\" in part for part in suffix):
            raise ValueError("Unsupported segmented contract path")
        values.add("/".join(suffix))
        handled.update(references)
    if len(handled) != len(segmented):
        raise ValueError("Unsupported standalone contracts segment requires an explicit dependency review")
    return values


def source_fixtures(directory: str) -> list[str]:
    """Resolve supported direct/segmented literals and directory + filename loaders."""
    paths = set()
    for source in (ROOT / directory).rglob("*"):
        if source.suffix not in {".swift", ".js", ".cjs", ".py"}:
            continue
        for value in contract_literals(source.read_text()):
            target = ROOT / value
            if value.endswith(".json"):
                paths.add(value)
            elif value.endswith("/") and target.is_dir():
                paths.update(str(p.relative_to(ROOT)) for p in target.glob("*.json"))
            else:
                raise ValueError(f"Unsupported contract reference: {value}")
    if not paths:
        raise ValueError(f"No shared fixture consumers found in {directory}")
    return sorted(paths)


def apple_app_fixtures() -> list[str]:
    project = (ROOT / "ios/OpenEOSControl/project.yml").read_text()
    paths = []
    for declaration in re.findall(r"^\s+- path: (.*contracts.*)$", project, re.MULTILINE):
        literal = re.fullmatch(r"\.\./\.\./(contracts/[a-zA-Z0-9_./-]+\.json)", declaration)
        if literal is None:
            raise ValueError(f"Unsupported App fixture declaration: {declaration}")
        paths.append(literal.group(1))
    if not paths or len(paths) != len(set(paths)):
        raise ValueError("Missing or duplicate App contract resource declarations")
    return paths


class WorkflowDependencyTests(unittest.TestCase):
    def assert_covered(self, patterns: list[str], paths: list[str]) -> None:
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(path=path):
                self.assertTrue((ROOT / path).is_file(), f"Missing consumer input: {path}")
                self.assertTrue(covers(patterns, path), f"CI selector misses consumer input: {path}")
                exclusions = [pattern[1:] for pattern in patterns if pattern.startswith("!")]
                self.assertFalse(covers(exclusions, path), f"Ambiguous excluded consumer input: {path}")

    def test_android_pr_tracks_actual_unit_fixture_manifest(self):
        self.assert_covered(pr_paths("android"), android_fixtures("unitContractFixtures"))

    def test_android_ui_tracks_only_its_actual_instrumentation_fixtures(self):
        patterns = pr_paths("android_ui")
        paths = android_fixtures("androidContractFixtures")
        self.assert_covered(patterns, paths)
        unit_only = set(android_fixtures("unitContractFixtures")) - set(paths)
        self.assertTrue(unit_only)
        for path in unit_only:
            self.assertFalse(covers(patterns, path), f"Unit-only input must not add KVM fanout: {path}")

    def test_android_host_dev_tracks_actual_unit_fixture_manifest(self):
        self.assert_covered(dev_paths("android-host-dev.yml"),
                            android_fixtures("unitContractFixtures"))

    def test_apple_core_pr_tracks_its_actual_core_test_inputs(self):
        self.assert_covered(pr_paths("ios_core"), source_fixtures("ios/OpenEOSCore/Tests"))

    def test_apple_app_pr_tracks_its_actual_project_resources_not_all_core_tests(self):
        paths = apple_app_fixtures()
        self.assert_covered(pr_paths("ios_app"), paths)
        for path in set(source_fixtures("ios/OpenEOSCore/Tests")) - set(paths):
            self.assertFalse(covers(pr_paths("ios_app"), path),
                             f"Core-only test input must not add App fanout: {path}")

    def test_desktop_pr_tracks_shared_test_inputs(self):
        self.assert_covered(pr_paths("desktop"), source_fixtures("bridge/tests"))

    def test_supplemental_workflows_track_actionlint_script_they_execute(self):
        script = "scripts/ci/run-actionlint.sh"
        for workflow in ("android-host-dev.yml", "desktop-dev.yml", "windows-dev.yml"):
            with self.subTest(workflow=workflow):
                self.assertIn(f"run: bash {script}", (WORKFLOWS / workflow).read_text())
                self.assert_covered(dev_paths(workflow), [script])

    def test_desktop_workflows_track_linted_validation_sources(self):
        inputs = sorted(str(p.relative_to(ROOT))
                        for p in (ROOT / "scripts/validation").rglob("*.py"))
        for patterns in (pr_paths("desktop"), dev_paths("desktop-dev.yml")):
            self.assert_covered(patterns, inputs)

    def test_existing_apple_and_desktop_dev_contract_coverage_is_retained(self):
        self.assert_covered(dev_paths("apple-dev.yml"), source_fixtures("ios/OpenEOSCore/Tests"))
        self.assert_covered(dev_paths("apple-dev.yml"), apple_app_fixtures())
        self.assert_covered(dev_paths("desktop-dev.yml"), source_fixtures("bridge/tests"))

    def test_segmented_contract_paths_are_discovered_and_dynamic_inputs_fail_closed(self):
        expected = "contracts/live-view-lut/v1/numeric-vectors.json"
        source = 'path.join(__dirname, "..", "..", "contracts", "live-view-lut", "v1", "numeric-vectors.json")'
        self.assertEqual(contract_literals(source), {expected})
        self.assertIn(expected, source_fixtures("bridge/tests"))
        for unsupported in (
            'path.join(__dirname, "..", "contracts", directory, "vectors.json")',
            'path.resolve(__dirname, "..", "contracts", "vectors.json")',
            'path.join(__dirname, "..", "contracts", "..", "vectors.json")',
        ):
            with self.assertRaises(ValueError):
                contract_literals(unsupported)

    def test_device_dev_one_shot_scope_does_not_expand(self):
        source = (WORKFLOWS / "android-device-dev.yml").read_text()
        self.assertEqual(dev_paths("android-device-dev.yml"),
                         [".github/workflows/android-device-dev.yml"])
        self.assertIn("github.event.before == 'a78e7a08840cf063a60a13d3fdfc8fe2ef99349e' && github.run_attempt == 1", source)
        self.assertNotIn("workflow_dispatch", source)

    def test_literal_reader_fails_closed_and_coverage_is_path_bounded(self):
        for source in ("paths:\n  - unquoted\n", "paths:\n  - 'a'\n  - 'a'\n", "paths: []\n"):
            with self.assertRaises(ValueError):
                literal_list(source, "paths", 0)
        self.assertFalse(covers(["contracts/media/**"], "contracts/media-extra/a.json"))
        self.assertFalse(covers(["!contracts/**"], "contracts/a.json"))
        self.assertFalse(covers(["contracts/*.json"], "contracts/a.json"))
        self.assertTrue(covers(["contracts/media/**"], "contracts/media/v1/a.json"))


if __name__ == "__main__":
    unittest.main()
