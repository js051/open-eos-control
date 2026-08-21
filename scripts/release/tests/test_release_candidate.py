from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.release.release_candidate import (
    CandidateError,
    create_candidate,
    expected_asset_names,
    verify_candidate,
)

VERSION = "1.2.3"
COMMIT = "a" * 40
HEAD = "b" * 40


class ReleaseCandidateTests(unittest.TestCase):
    def test_expected_assets_include_versioned_camera_import_contract(self) -> None:
        names = expected_asset_names(VERSION)
        self.assertIn("open-eos-camera-import-contract-1.1.0.zip", names)
        self.assertIn("open-eos-camera-import-contract-kotlin-1.1.0.jar", names)

    def create_assets(self, directory: Path) -> None:
        for index, name in enumerate(expected_asset_names(VERSION)):
            (directory / name).write_bytes(f"asset-{index}".encode())

    def test_round_trips_candidate_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.create_assets(directory)
            created = create_candidate(directory, VERSION, COMMIT, 17, HEAD, 99)
            verified = verify_candidate(directory, VERSION, COMMIT)
            self.assertEqual(verified, created)
            self.assertEqual(verified["sourcePullRequest"], 17)

    def test_rejects_modified_asset(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.create_assets(directory)
            create_candidate(directory, VERSION, COMMIT, 17, HEAD, 99)
            (directory / expected_asset_names(VERSION)[0]).write_bytes(b"modified")
            with self.assertRaisesRegex(CandidateError, "hashes or sizes"):
                verify_candidate(directory, VERSION, COMMIT)

    def test_rejects_unexpected_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.create_assets(directory)
            create_candidate(directory, VERSION, COMMIT, 17, HEAD, 99)
            (directory / "extra.txt").write_text("unexpected", encoding="utf-8")
            with self.assertRaisesRegex(CandidateError, "Unexpected"):
                verify_candidate(directory, VERSION, COMMIT)


if __name__ == "__main__":
    unittest.main()
