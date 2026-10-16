"""Shared publication guards; real input readiness remains separately tested."""
import json
from pathlib import Path

import pytest

from open_eos_bridge.liveview_lifecycle import evaluate

VECTORS = Path(__file__).parents[2] / 'contracts/liveview-lifecycle/v1/liveview-vectors.json'
CASES = json.loads(VECTORS.read_text())['cases']


@pytest.mark.parametrize('case', CASES, ids=lambda case: case['name'])
def test_shared_liveview_contract(case):
    assert evaluate(case['input']) == case['expected']
