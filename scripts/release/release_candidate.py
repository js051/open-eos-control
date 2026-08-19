"""Create and verify immutable release-candidate provenance metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
PROVENANCE_NAME = "BUILD-PROVENANCE.json"
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


class CandidateError(RuntimeError):
    """Raised when a release candidate is incomplete or does not match its provenance."""


def expected_asset_names(version: str) -> tuple[str, ...]:
    if VERSION_PATTERN.fullmatch(version) is None:
        raise CandidateError(f"Invalid release version: {version!r}")
    return (
        f"open-eos-control-android-{version}-debug.apk",
        f"open-eos-control-bridge-windows-x64-{version}.exe",
        f"open_eos_control_bridge-{version}-py3-none-any.whl",
        f"open_eos_control_bridge-{version}.tar.gz",
    )


def require_sha(value: str, label: str) -> str:
    normalized = value.lower()
    if SHA_PATTERN.fullmatch(normalized) is None:
        raise CandidateError(f"{label} must be a full 40-character Git SHA.")
    return normalized


def digest_file(path: Path) -> dict[str, str | int]:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"sha256": digest.hexdigest(), "size": path.stat().st_size}


def collect_assets(directory: Path, version: str) -> dict[str, dict[str, str | int]]:
    assets: dict[str, dict[str, str | int]] = {}
    for name in expected_asset_names(version):
        path = directory / name
        if not path.is_file() or path.is_symlink():
            raise CandidateError(f"Missing regular release asset: {name}")
        assets[name] = digest_file(path)
    return assets


def create_candidate(
    directory: Path,
    version: str,
    commit: str,
    pull_request: int,
    pull_head: str,
    ci_run_id: int,
) -> dict[str, Any]:
    if pull_request <= 0 or ci_run_id <= 0:
        raise CandidateError("Pull request and CI run identifiers must be positive.")
    payload: dict[str, Any] = {
        "schema": SCHEMA_VERSION,
        "version": version,
        "commit": require_sha(commit, "Promoted commit"),
        "sourcePullRequest": pull_request,
        "sourceHead": require_sha(pull_head, "Pull-request head"),
        "sourceCiRunId": ci_run_id,
        "assets": collect_assets(directory, version),
    }
    output = directory / PROVENANCE_NAME
    output.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return payload


def verify_candidate(directory: Path, version: str, commit: str) -> dict[str, Any]:
    provenance_path = directory / PROVENANCE_NAME
    if not provenance_path.is_file() or provenance_path.is_symlink():
        raise CandidateError(f"Missing regular {PROVENANCE_NAME} file.")
    try:
        payload = json.loads(provenance_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise CandidateError(f"Invalid {PROVENANCE_NAME}: {error}") from error

    expected_commit = require_sha(commit, "Release commit")
    if payload.get("schema") != SCHEMA_VERSION:
        raise CandidateError(f"Unsupported candidate schema: {payload.get('schema')!r}")
    if payload.get("version") != version:
        raise CandidateError(
            f"Candidate version {payload.get('version')!r} does not match {version!r}."
        )
    if payload.get("commit") != expected_commit:
        raise CandidateError(
            f"Candidate commit {payload.get('commit')!r} does not match {expected_commit}."
        )

    actual_assets = collect_assets(directory, version)
    if payload.get("assets") != actual_assets:
        raise CandidateError("Release asset hashes or sizes differ from BUILD-PROVENANCE.json.")

    allowed_names = {*expected_asset_names(version), PROVENANCE_NAME}
    unexpected = sorted(path.name for path in directory.iterdir() if path.name not in allowed_names)
    if unexpected:
        raise CandidateError(f"Unexpected release-candidate files: {', '.join(unexpected)}")
    return payload


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create")
    create.add_argument("--directory", type=Path, required=True)
    create.add_argument("--version", required=True)
    create.add_argument("--commit", required=True)
    create.add_argument("--pull-request", type=int, required=True)
    create.add_argument("--pull-head", required=True)
    create.add_argument("--ci-run-id", type=int, required=True)

    verify = subparsers.add_parser("verify")
    verify.add_argument("--directory", type=Path, required=True)
    verify.add_argument("--version", required=True)
    verify.add_argument("--commit", required=True)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        if args.command == "create":
            payload = create_candidate(
                args.directory,
                args.version,
                args.commit,
                args.pull_request,
                args.pull_head,
                args.ci_run_id,
            )
        else:
            payload = verify_candidate(args.directory, args.version, args.commit)
    except (CandidateError, OSError) as error:
        raise SystemExit(str(error)) from error
    print(json.dumps(payload, sort_keys=True))


if __name__ == "__main__":
    main()
