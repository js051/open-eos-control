"""Exact identity for shared capture configuration evidence and lexical fixtures."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3] / "contracts/capture-configuration/v1"
FIXTURES = {
    "capture-configuration-vectors.json": (
        "7a3496afc0e9f0523399a41f251d1e243f59318866549f37aeb29e6733a01aa5",
        {"receipt": 49, "outcome": 48, "ownership": 128, "recovery": 16},
    ),
    "exact-value-vectors.json": (
        "0ce969f4faaf795daae1fcc680807f1841313ef487ad3fefb1f8a682a92263c7",
        {"receipt": 68, "naming": 13, "directorySelection": 7},
    ),
}


def test_exact_shared_identity_and_complete_unique_sections():
    for name, (sha, counts) in FIXTURES.items():
        payload = (ROOT / name).read_bytes()
        assert hashlib.sha256(payload).hexdigest() == sha
        data = json.loads(payload)
        assert data["version"] == 1
        for section, count in counts.items():
            assert len(data[section]) == count
            assert len({row["id"] for row in data[section]}) == count


def test_observation_never_reclassifies_write_evidence():
    data = json.loads((ROOT / "capture-configuration-vectors.json").read_text())
    for row in data["outcome"]:
        x, y = row["input"], row["expected"]
        expected = "not-sent" if not x["dispatched"] else "acknowledged" if x["validAcknowledgment"] else "unknown"
        assert y["writeEvidence"] == expected
    assert all(row["writes"] == 0 for row in data["recovery"])
