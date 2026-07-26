"""Verify that all product version declarations match a release tag."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VERSION_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+"


def declared_version(path: str, pattern: str) -> str:
    content = (ROOT / path).read_text(encoding="utf-8")
    match = re.search(pattern, content, flags=re.MULTILINE)
    if match is None:
        raise SystemExit(f"Unable to find a version in {path}")
    return match.group(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="Release tag such as vX.Y.Z")
    args = parser.parse_args()

    declarations = {
        "android/app/build.gradle.kts": declared_version(
            "android/app/build.gradle.kts",
            rf'versionName\s*=\s*"({VERSION_PATTERN})"',
        ),
        "bridge/pyproject.toml": declared_version(
            "bridge/pyproject.toml",
            rf'^version\s*=\s*"({VERSION_PATTERN})"',
        ),
        "bridge/open_eos_bridge/__init__.py": declared_version(
            "bridge/open_eos_bridge/__init__.py",
            rf'^__version__\s*=\s*"({VERSION_PATTERN})"',
        ),
        "simulator/pyproject.toml": declared_version(
            "simulator/pyproject.toml",
            rf'^version\s*=\s*"({VERSION_PATTERN})"',
        ),
        "ios/OpenEOSControl/project.yml": declared_version(
            "ios/OpenEOSControl/project.yml",
            rf'^\s*MARKETING_VERSION:\s*({VERSION_PATTERN})\s*$',
        ),
    }

    versions = set(declarations.values())
    if len(versions) != 1:
        details = "\n".join(f"  {path}: {version}" for path, version in declarations.items())
        raise SystemExit(f"Product versions do not match:\n{details}")

    version = versions.pop()
    if args.tag is not None:
        expected_tag = f"v{version}"
        if args.tag != expected_tag:
            raise SystemExit(f"Tag {args.tag!r} does not match declared version {expected_tag!r}")

    release_notes = ROOT / "docs" / "releases" / f"v{version}.md"
    if not release_notes.is_file():
        raise SystemExit(f"Missing release notes: {release_notes.relative_to(ROOT)}")

    print(version)


if __name__ == "__main__":
    main()
