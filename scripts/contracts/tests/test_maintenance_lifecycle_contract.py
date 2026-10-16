import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

def test_maintenance_contract_shape():
    data = json.loads((ROOT / "contracts/maintenance-lifecycle/v1/maintenance-vectors.json").read_text())
    assert data["version"] == 1
    assert len(data["clock"]) == 32
    assert len(data["wire"]) == 28
    assert len(data["ownership"]) == 70

def test_maintenance_contract_boundary_examples():
    data = json.loads((ROOT / "contracts/maintenance-lifecycle/v1/maintenance-vectors.json").read_text())
    cases = {row["name"]: row for row in data["clock"]}
    assert cases["epoch-only"]["matched"]
    assert not cases["epoch-observation-cannot-prove-zone"]["matched"]
    assert not cases["wrong-zone"]["matched"]
    assert not cases["wrong-dst"]["matched"]
