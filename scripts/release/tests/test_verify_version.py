from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.release.verify_version import verify_versions


class VerifyVersionTests(unittest.TestCase):
    def create_declarations(self, root: Path, version: str = "1.2.3") -> None:
        files = {
            "android/app/build.gradle.kts": f'versionName = "{version}"\n',
            "bridge/pyproject.toml": f'version = "{version}"\n',
            "bridge/open_eos_bridge/__init__.py": f'__version__ = "{version}"\n',
            "simulator/pyproject.toml": f'version = "{version}"\n',
            "ios/OpenEOSControl/project.yml": f"  MARKETING_VERSION: {version}\n",
            "README.md": f"The current development preview is [v{version}](notes).\n",
            "README.zh-TW.md": f"目前的開發預覽版為 [v{version}](notes)。\n",
            "CHANGELOG.md": f"## [Unreleased]\n\n## [{version}] - 2026-08-19\n",
            f"docs/releases/v{version}.md": "# Release\n",
        }
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

    def test_accepts_consistent_code_and_documentation_versions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.create_declarations(root)
            self.assertEqual(verify_versions(root, "v1.2.3"), "1.2.3")

    def test_rejects_stale_readme_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.create_declarations(root)
            (root / "README.md").write_text(
                "The current development preview is [v1.2.2](notes).\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(SystemExit, "Product versions do not match"):
                verify_versions(root)

    def test_requires_release_notes_for_declared_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.create_declarations(root)
            (root / "docs/releases/v1.2.3.md").unlink()
            with self.assertRaisesRegex(SystemExit, "Missing release notes"):
                verify_versions(root)


if __name__ == "__main__":
    unittest.main()
