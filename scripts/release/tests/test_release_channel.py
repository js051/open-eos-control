from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"


class ReleaseChannelTests(unittest.TestCase):
    def test_workflow_can_publish_only_development_prereleases(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")

        self.assertTrue(workflow.startswith("name: Release development preview\n"))
        self.assertIn('title="Open EOS Control ${GITHUB_REF_NAME#v} Development Preview"', workflow)
        self.assertEqual(workflow.count("--prerelease"), 2)
        self.assertNotIn("--latest", workflow)


if __name__ == "__main__":
    unittest.main()
