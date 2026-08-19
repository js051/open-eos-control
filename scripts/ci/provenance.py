"""Verify that a commit is promoted from an already successful workflow run."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class ProvenanceError(RuntimeError):
    """Raised when a commit cannot be traced to a successful workflow run."""


def require_sha(value: str, label: str) -> str:
    normalized = value.lower()
    if SHA_PATTERN.fullmatch(normalized) is None:
        raise ProvenanceError(f"{label} must be a full 40-character Git SHA.")
    return normalized


def require_repository(value: str) -> str:
    if REPOSITORY_PATTERN.fullmatch(value) is None:
        raise ProvenanceError("Repository must use the OWNER/NAME form.")
    return value


def github_get_json(repository: str, path: str, token: str) -> Any:
    if not token:
        raise ProvenanceError("GITHUB_TOKEN is required for provenance verification.")
    repository = require_repository(repository)
    url = f"https://api.github.com/repos/{repository}/{path.lstrip('/')}"
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "open-eos-control-provenance",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
        raise ProvenanceError(f"GitHub API request failed for {path}: {error}") from error


def select_merged_pull_request(
    pulls: Sequence[Mapping[str, Any]],
    commit: str,
    base_branch: str,
) -> Mapping[str, Any]:
    matches = [
        pull
        for pull in pulls
        if pull.get("merged_at")
        and str(pull.get("merge_commit_sha", "")).lower() == commit
        and pull.get("base", {}).get("ref") == base_branch
    ]
    if len(matches) != 1:
        raise ProvenanceError(
            f"Expected one merged pull request for {commit} on {base_branch}; found {len(matches)}."
        )
    return matches[0]


def select_latest_workflow_run(
    payload: Mapping[str, Any],
    head_sha: str,
    event: str,
) -> Mapping[str, Any]:
    runs = [
        run
        for run in payload.get("workflow_runs", [])
        if str(run.get("head_sha", "")).lower() == head_sha and run.get("event") == event
    ]
    if not runs:
        raise ProvenanceError(f"No {event} workflow run exists for {head_sha}.")
    latest = max(runs, key=lambda run: (str(run.get("created_at", "")), int(run.get("id", 0))))
    if latest.get("status") != "completed" or latest.get("conclusion") != "success":
        raise ProvenanceError(
            f"Latest {event} workflow run {latest.get('id')} for {head_sha} is "
            f"{latest.get('status')}/{latest.get('conclusion')}."
        )
    return latest


def git_output(arguments: Sequence[str]) -> str:
    completed = subprocess.run(
        ["git", *arguments],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def require_matching_pull_request_tree(
    pull_number: int,
    pull_head_sha: str,
    merge_commit: str,
    git: Callable[[Sequence[str]], str] = git_output,
) -> str:
    remote_ref = f"refs/remotes/origin/oec-promotion-pr-{pull_number}"
    git(["fetch", "--quiet", "--no-tags", "origin", f"+refs/pull/{pull_number}/head:{remote_ref}"])
    fetched_head = require_sha(git(["rev-parse", remote_ref]), "Fetched pull-request head")
    if fetched_head != pull_head_sha:
        raise ProvenanceError(
            f"Pull request #{pull_number} head changed: API={pull_head_sha}, fetched={fetched_head}."
        )
    pull_tree = require_sha(git(["rev-parse", f"{remote_ref}^{{tree}}"]), "Pull-request tree")
    merge_tree = require_sha(git(["rev-parse", f"{merge_commit}^{{tree}}"]), "Merge tree")
    if pull_tree != merge_tree:
        raise ProvenanceError(
            f"Merged tree {merge_tree} differs from verified pull-request tree {pull_tree}."
        )
    return merge_tree


def workflow_runs_path(workflow: str, event: str, head_sha: str) -> str:
    workflow_id = urllib.parse.quote(workflow, safe="")
    query = urllib.parse.urlencode(
        {"event": event, "head_sha": head_sha, "per_page": "100"}
    )
    return f"actions/workflows/{workflow_id}/runs?{query}"


def write_outputs(path: Path | None, values: Mapping[str, str | int]) -> None:
    if path is None:
        return
    with path.open("a", encoding="utf-8", newline="\n") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def verify_pull_request_promotion(
    repository: str,
    commit: str,
    workflow: str,
    base_branch: str,
    token: str,
    api_get: Callable[[str, str, str], Any] = github_get_json,
    git: Callable[[Sequence[str]], str] = git_output,
) -> dict[str, str | int]:
    repository = require_repository(repository)
    commit = require_sha(commit, "Merge commit")
    pulls = api_get(repository, f"commits/{commit}/pulls?per_page=100", token)
    pull = select_merged_pull_request(pulls, commit, base_branch)
    pull_number = int(pull["number"])
    pull_head_sha = require_sha(str(pull["head"]["sha"]), "Pull-request head")
    tree_sha = require_matching_pull_request_tree(
        pull_number,
        pull_head_sha,
        commit,
        git=git,
    )
    runs = api_get(
        repository,
        workflow_runs_path(workflow, "pull_request", pull_head_sha),
        token,
    )
    run = select_latest_workflow_run(runs, pull_head_sha, "pull_request")
    return {
        "pull_request": pull_number,
        "pull_head_sha": pull_head_sha,
        "tree_sha": tree_sha,
        "workflow_run_id": int(run["id"]),
    }


def verify_workflow_run(
    repository: str,
    commit: str,
    workflow: str,
    event: str,
    token: str,
    api_get: Callable[[str, str, str], Any] = github_get_json,
) -> dict[str, str | int]:
    repository = require_repository(repository)
    commit = require_sha(commit, "Commit")
    runs = api_get(repository, workflow_runs_path(workflow, event, commit), token)
    run = select_latest_workflow_run(runs, commit, event)
    return {"workflow_run_id": int(run["id"]), "verified_commit": commit}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    promote = subparsers.add_parser("promote")
    promote.add_argument("--repository", required=True)
    promote.add_argument("--commit", required=True)
    promote.add_argument("--workflow", required=True)
    promote.add_argument("--base", default="main")
    promote.add_argument("--output", type=Path)

    run = subparsers.add_parser("run")
    run.add_argument("--repository", required=True)
    run.add_argument("--commit", required=True)
    run.add_argument("--workflow", required=True)
    run.add_argument("--event", required=True)
    run.add_argument("--output", type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    token = os.environ.get("GITHUB_TOKEN", "")
    try:
        if args.command == "promote":
            result = verify_pull_request_promotion(
                args.repository,
                args.commit,
                args.workflow,
                args.base,
                token,
            )
        else:
            result = verify_workflow_run(
                args.repository,
                args.commit,
                args.workflow,
                args.event,
                token,
            )
    except (KeyError, TypeError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Invalid provenance data: {error}") from error
    except ProvenanceError as error:
        raise SystemExit(str(error)) from error

    write_outputs(args.output, result)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
