"""Guard the actual unittest entrypoint without importing optional schema tools."""
import ast
import re
import unittest
from pathlib import Path

from scripts.ci.tests.test_workflow_dependencies import android_fixtures, covers, dev_paths, pr_paths


ROOT = Path(__file__).resolve().parents[3]


def contract_source_inputs():
    """Current literal consumers, not a general Python dependency evaluator.

    Include both Gradle manifests read by the packaging contract, direct JSON
    literals, uniquely named production Kotlin sources, and Kotlin loader trees
    scanned by that contract. No optional schema package imports are needed.
    """
    inputs = set(android_fixtures("unitContractFixtures"))
    inputs.update(android_fixtures("androidContractFixtures"))
    for source in (ROOT / "scripts/contracts/tests").glob("test_*.py"):
        for node in ast.walk(ast.parse(source.read_text())):
            if not isinstance(node, ast.Constant) or not isinstance(node.value, str):
                continue
            value = node.value
            if value.startswith("contracts/") and value.endswith(".json"):
                inputs.add(value)
            elif re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*\.kt", value):
                matches = list((ROOT / "android/app/src/main").rglob(value))
                if len(matches) != 1:
                    raise ValueError(f"Ambiguous or missing Kotlin source input: {value}")
                inputs.add(str(matches[0].relative_to(ROOT)))
            elif value in {"src/test", "src/androidTest"}:
                directory = ROOT / "android/app" / value
                paths = list(directory.rglob("*.kt"))
                if not paths:
                    raise ValueError(f"Missing source-loader inputs: {value}")
                inputs.update(str(path.relative_to(ROOT)) for path in paths)
    return sorted(inputs)


def browser_repository_fixtures():
    """Resolve literal repository-root readFileSync inputs in the real journey."""
    source = (ROOT / "bridge/tests/ccapi.browser.test.js").read_text()
    paths = []
    for call in re.finditer(r"fs\.readFileSync\(path\.join\(\s*REPOSITORY_ROOT\s*,([^()]*)\)", source):
        parts = [ast.literal_eval(part.strip()) for part in call.group(1).split(",") if part.strip()]
        if not parts or any(not isinstance(part, str) or part in {".", ".."} or "\\" in part
                            for part in parts):
            raise ValueError("Unsupported repository-root browser fixture path")
        paths.append("/".join(parts))
    if not paths:
        raise ValueError("Browser fixture consumers were not found")
    return paths


class ContractTestDiscoveryTests(unittest.TestCase):
    def test_contract_assertions_are_not_hidden_in_undiscoverable_top_level_tests(self):
        hidden = []
        discovered = []
        for path in sorted((ROOT / "scripts/contracts/tests").glob("test_*.py")):
            tree = ast.parse(path.read_text())
            for node in tree.body:
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_"):
                    hidden.append(f"{path.name}:{node.name}")
                if isinstance(node, ast.ClassDef):
                    methods = [method.name for method in node.body
                               if isinstance(method, ast.FunctionDef) and method.name.startswith("test_")]
                    if methods:
                        self.assertTrue(any(ast.unparse(base) == "unittest.TestCase" for base in node.bases),
                                        f"{path.name}:{node.name} must use the standard unittest TestCase")
                        discovered.extend(f"{path.name}:{node.name}.{method}" for method in methods)
        self.assertTrue(discovered, "Contract test source roster is empty")
        self.assertEqual(hidden, [], "unittest silently ignores these authored test functions")

    def test_existing_ci_entrypoints_still_use_standard_unittest_discovery(self):
        command = 'python3 -m unittest discover -s scripts/contracts/tests -p "test_*.py"'
        for name in ("android.yml", "apple-dev.yml"):
            self.assertIn(command, (ROOT / ".github/workflows" / name).read_text())

    def test_contract_pr_selector_tracks_actual_fixture_and_android_source_inputs(self):
        patterns = pr_paths("camera_import_contract")
        for path in contract_source_inputs():
            with self.subTest(path=path):
                self.assertTrue((ROOT / path).is_file(), f"Missing contract input: {path}")
                self.assertTrue(covers(patterns, path), f"Contract selector misses consumed input: {path}")
        # Packaging also inspects resource/asset filename collisions via rglob
        # and stat, not open(). Exact current Kotlin filenames are insufficient.
        for tree in ("android/app/src/test", "android/app/src/androidTest"):
            with self.subTest(recursive_tree=tree):
                self.assertTrue(covers(patterns, f"{tree}/__future_resource_or_asset__"),
                                f"Contract selector must retain recursive loader/collision scope: {tree}")

    def test_desktop_selectors_track_repository_root_browser_fixtures(self):
        for path in browser_repository_fixtures():
            for patterns in (pr_paths("desktop"), dev_paths("desktop-dev.yml")):
                with self.subTest(path=path, patterns=patterns):
                    self.assertTrue((ROOT / path).is_file(), f"Missing browser input: {path}")
                    self.assertTrue(covers(patterns, path), f"Desktop selector misses consumed input: {path}")


if __name__ == "__main__":
    unittest.main()
