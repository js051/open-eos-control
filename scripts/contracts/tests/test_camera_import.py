from __future__ import annotations

import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts.contracts.camera_import import (
    CONTRACT_DIR,
    ContractError,
    artifact_name,
    build_artifact,
    validate_contract,
    verify_lock,
)


class CameraImportContractTests(unittest.TestCase):
    def copy_contract(self, target: Path) -> Path:
        contract = target / "v1"
        shutil.copytree(CONTRACT_DIR, contract)
        return contract

    def test_committed_contract_and_fixtures_validate(self) -> None:
        validate_contract()

    def test_lock_rejects_modified_contract_source(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            contract = self.copy_contract(Path(temporary))
            readme = contract / "README.md"
            readme.write_text(readme.read_text(encoding="utf-8") + "changed\n", encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "stale"):
                verify_lock(contract)

    def test_artifact_is_byte_for_byte_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            first_artifact = build_artifact(Path(first))
            second_artifact = build_artifact(Path(second))
            self.assertEqual(first_artifact.name, artifact_name())
            self.assertEqual(
                hashlib.sha256(first_artifact.read_bytes()).digest(),
                hashlib.sha256(second_artifact.read_bytes()).digest(),
            )

    def test_receipt_schema_never_authorizes_camera_deletion(self) -> None:
        schema = json.loads((CONTRACT_DIR / "import-receipt.schema.json").read_text(encoding="utf-8"))
        self.assertNotIn("delete_camera_media", schema["properties"])
        self.assertFalse(schema["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
