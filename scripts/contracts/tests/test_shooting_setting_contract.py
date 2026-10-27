"""Cross-platform ordinary shooting setting fixture identity and evidence boundary."""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]


class ShootingSettingContractTests(unittest.TestCase):
    def test_shared_shooting_setting_vectors_are_exact_and_complete(self):
        payload = (ROOT / "contracts/shooting-setting/v1/shooting-setting-vectors.json").read_bytes()
        assert hashlib.sha256(payload).hexdigest() == "31d0ee62c2875490a26d54dad0d435a990d074d7fc04598d34a52963a07d8864"
        data = json.loads(payload)
        assert data["version"] == 1
        assert {key: len(data[key]) for key in ("wire", "ownership", "outcome")} == {"wire": 24, "ownership": 64, "outcome": 24}
        assert len({row["id"] for row in data["wire"]}) == 24

    def test_later_observation_never_upgrades_unknown_write_evidence(self):
        data = json.loads((ROOT / "contracts/shooting-setting/v1/shooting-setting-vectors.json").read_text())
        for row in data["outcome"]:
            x, y = row["input"], row["expected"]
            expected = "not-sent" if not x["dispatched"] else "response-received" if x["responseReceived"] else "unknown"
            assert y["writeEvidence"] == expected
        for row in data["ownership"]:
            if row["expected"]["canConsumeDraft"]:
                assert row["input"]["responseReceived"] and row["input"]["readbackMatched"]
