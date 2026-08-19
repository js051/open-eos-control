from __future__ import annotations

import unittest

from scripts.ci.provenance import (
    ProvenanceError,
    require_matching_pull_request_tree,
    select_latest_workflow_run,
    select_merged_pull_request,
    verify_pull_request_promotion,
)

MERGE_SHA = "a" * 40
HEAD_SHA = "b" * 40
TREE_SHA = "c" * 40


class ProvenanceTests(unittest.TestCase):
    def test_selects_exact_merged_pull_request(self) -> None:
        pull = select_merged_pull_request(
            [
                {
                    "number": 7,
                    "merged_at": "2026-08-19T00:00:00Z",
                    "merge_commit_sha": MERGE_SHA,
                    "base": {"ref": "main"},
                }
            ],
            MERGE_SHA,
            "main",
        )
        self.assertEqual(pull["number"], 7)

    def test_rejects_ambiguous_pull_request_provenance(self) -> None:
        with self.assertRaises(ProvenanceError):
            select_merged_pull_request([], MERGE_SHA, "main")

    def test_latest_workflow_run_must_succeed(self) -> None:
        payload = {
            "workflow_runs": [
                {
                    "id": 10,
                    "head_sha": HEAD_SHA,
                    "event": "pull_request",
                    "created_at": "2026-08-19T00:00:00Z",
                    "status": "completed",
                    "conclusion": "success",
                },
                {
                    "id": 11,
                    "head_sha": HEAD_SHA,
                    "event": "pull_request",
                    "created_at": "2026-08-19T01:00:00Z",
                    "status": "completed",
                    "conclusion": "failure",
                },
            ]
        }
        with self.assertRaisesRegex(ProvenanceError, "failure"):
            select_latest_workflow_run(payload, HEAD_SHA, "pull_request")

    def test_requires_identical_pull_request_and_merge_trees(self) -> None:
        outputs = iter(["", HEAD_SHA, TREE_SHA, TREE_SHA])

        def fake_git(_arguments: list[str]) -> str:
            return next(outputs)

        tree = require_matching_pull_request_tree(9, HEAD_SHA, MERGE_SHA, git=fake_git)
        self.assertEqual(tree, TREE_SHA)

    def test_verifies_pull_request_tree_and_ci_run(self) -> None:
        def fake_api(_repository: str, path: str, _token: str):
            if path.startswith("commits/"):
                return [
                    {
                        "number": 12,
                        "merged_at": "2026-08-19T00:00:00Z",
                        "merge_commit_sha": MERGE_SHA,
                        "base": {"ref": "main"},
                        "head": {"sha": HEAD_SHA},
                    }
                ]
            return {
                "workflow_runs": [
                    {
                        "id": 44,
                        "head_sha": HEAD_SHA,
                        "event": "pull_request",
                        "created_at": "2026-08-19T00:00:00Z",
                        "status": "completed",
                        "conclusion": "success",
                    }
                ]
            }

        outputs = iter(["", HEAD_SHA, TREE_SHA, TREE_SHA])
        result = verify_pull_request_promotion(
            "owner/repo",
            MERGE_SHA,
            "android.yml",
            "main",
            "token",
            api_get=fake_api,
            git=lambda _arguments: next(outputs),
        )
        self.assertEqual(result["pull_request"], 12)
        self.assertEqual(result["workflow_run_id"], 44)
        self.assertEqual(result["tree_sha"], TREE_SHA)


if __name__ == "__main__":
    unittest.main()
