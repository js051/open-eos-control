"""Ensure authored Desktop Node tests reach the existing production CI commands."""
import ast
import json
import re
import tempfile
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BRIDGE = ROOT / "bridge"


def imported_test_entries(root: Path, entries: set[Path]) -> set[tuple[str, str]]:
    """Follow current literal local require/import/export edges, not arbitrary JS.

    Also resolve literal path.join/resolve(__dirname, ...) require calls. Bare
    packages and non-JavaScript resources are not traversed. Other dynamic loader
    changes need a separate review; this source guard is not execution coverage.
    """
    references = re.compile(
        r'''(?:require|import)\(\s*["']([^"']+)["']\s*\)'''
        r'''|(?:import|export)\s+[^;\n]*?\bfrom\s*["']([^"']+)["']'''
        r'''|\bimport\s*["']([^"']+)["']''',
    )
    root = root.resolve()
    entries = {path.resolve() for path in entries}
    edges = set()
    for entry in entries:
        visited = set()
        pending = [entry]
        while pending:
            current = pending.pop()
            if current in visited:
                continue
            visited.add(current)
            source = current.read_text()
            targets = []
            for match in references.finditer(source):
                value = next(group for group in match.groups() if group is not None)
                if not value.startswith("."):
                    continue
                targets.append((current.parent / value).resolve())
            for match in re.finditer(
                r"require\(\s*path\.(?:join|resolve)\(\s*__dirname\s*,([^()]*)\)\s*\)", source,
            ):
                parts = [ast.literal_eval(part.strip()) for part in match.group(1).split(",") if part.strip()]
                if not parts or any(not isinstance(part, str) for part in parts):
                    raise ValueError("Nonliteral path composition requires a dependency review")
                targets.append(current.parent.joinpath(*parts).resolve())
            for target in targets:
                if target.suffix and target.suffix not in {".js", ".cjs", ".mjs"}:
                    continue
                if not target.is_relative_to(root):
                    raise ValueError(f"Local JavaScript import escapes audited root: {target.name}")
                candidates = [target] if target.suffix else [
                    target.with_suffix(".js"), target.with_suffix(".cjs"), target.with_suffix(".mjs"),
                    target / "index.js", target / "index.cjs",
                ]
                resolved = next((path for path in candidates if path.is_file()), None)
                if resolved is None:
                    raise ValueError(f"Unresolved literal local module requires review: {current.name}: {target.name}")
                if resolved.suffix not in {".js", ".cjs", ".mjs"}:
                    continue
                if not resolved.is_relative_to(root):
                    raise ValueError(f"Local JavaScript import escapes audited root: {resolved.name}")
                if resolved in entries:
                    edges.add((str(entry.relative_to(root)), str(resolved.relative_to(root))))
                pending.append(resolved)
    return edges


class DesktopTestRegistrationTests(unittest.TestCase):
    def test_all_node_test_files_are_registered_once_in_standard_commands(self):
        scripts = json.loads((BRIDGE / "package.json").read_text())["scripts"]
        registered = []
        for name in ("test:modules", "test:browser"):
            commands = scripts[name].split(" && ")
            for command in commands:
                match = re.fullmatch(r"node (tests/[a-z0-9.-]+\.test\.(?:js|cjs))", command)
                self.assertIsNotNone(match, f"Review changed standard test command syntax: {command}")
                path = match.group(1)
                self.assertEqual(".browser.test." in path, name == "test:browser", path)
                registered.append(path)
        discovered = {
            str(path.relative_to(BRIDGE)) for path in (BRIDGE / "tests").iterdir()
            if path.name.endswith((".test.js", ".test.cjs"))
        }
        self.assertTrue(discovered, "No Desktop tests discovered")
        self.assertEqual(set(registered), discovered,
                         "Every authored Node test must be wired into a standard CI command")
        self.assertEqual({path: count for path, count in Counter(registered).items() if count != 1}, {})

    def test_standard_ci_and_desktop_dev_execute_both_registered_groups(self):
        for workflow in ("android.yml", "desktop-dev.yml"):
            source = (ROOT / ".github/workflows" / workflow).read_text()
            for group in ("test:modules", "test:browser"):
                self.assertIn(f"npm run {group}", source, workflow)

    def test_direct_entries_do_not_execute_other_test_entries_transitively(self):
        entries = {path for path in (BRIDGE / "tests").iterdir()
                   if path.name.endswith((".test.js", ".test.cjs"))}
        self.assertEqual(imported_test_entries(BRIDGE, entries), set(),
                         "Direct registration is not proof of unique execution; inspect transitive test imports")

    def test_registration_audit_follows_literal_require_and_import_edges(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first.test.js"
            second = root / "second.test.js"
            helper = root / "helper.js"
            first.write_text('require("./helper");\n')
            second.write_text('require("node:assert/strict");\n')
            helper.write_text('export { value } from "./middle.js";\n')
            (root / "middle.js").write_text('import "./second.test.js";\n')
            expected = {("first.test.js", "second.test.js")}
            self.assertEqual(imported_test_entries(root, {first, second}), expected)
            helper.write_text('import { value } from "./middle.js";\n')
            self.assertEqual(imported_test_entries(root, {first, second}), expected)
            first.write_text('const path = require("node:path"); require(path.join(__dirname, "helper.js"));\n')
            self.assertEqual(imported_test_entries(root, {first, second}), expected)
            first.write_text('const path = require("node:path"); require(path.resolve(__dirname, "helper.js"));\n')
            self.assertEqual(imported_test_entries(root, {first, second}), expected)
            helper.write_text('require("./first.test.js");\n')
            self.assertEqual(imported_test_entries(root, {first, second}),
                             {("first.test.js", "first.test.js")})
            helper.write_text('module.exports = {};\n')
            self.assertEqual(imported_test_entries(root, {first, second}), set())
            first.write_text('require(path.join(__dirname, directory, "helper.js"));\n')
            with self.assertRaises(ValueError):
                imported_test_entries(root, {first, second})
            first.write_text('require("../outside.js");\n')
            with self.assertRaisesRegex(ValueError, "escapes audited root"):
                imported_test_entries(root, {first, second})


if __name__ == "__main__":
    unittest.main()
