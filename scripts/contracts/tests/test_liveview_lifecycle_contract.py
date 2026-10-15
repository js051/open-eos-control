import json
from pathlib import Path

def test_liveview_contract_has_unique_complete_cases():
    root=Path(__file__).resolve().parents[3]
    data=json.loads((root/'contracts/liveview-lifecycle/v1/liveview-vectors.json').read_text())
    assert data['version']==1 and len(data['cases'])==99
    assert len({c['name'] for c in data['cases']})==len(data['cases'])
    assert all(set(c['expected'])=={'canPublishInputReady','canPublishDecoded','canPublishPresented','canFallback','stopRequired'} for c in data['cases'])
    assert all(all(type(v) is bool for v in c['expected'].values()) for c in data['cases'])
