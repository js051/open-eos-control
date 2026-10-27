import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]


class RecordingLifecycleContractTests(unittest.TestCase):
    def test_recording_contract_shape_and_cancellation(self):
        data = json.loads((ROOT / "contracts/recording-lifecycle/v1/recording-vectors.json").read_text())
        assert data["version"] == 1
        assert len(data["observations"]) == 60
        assert len(data["ownership"]) == 80
        rows = {row["name"]: row for row in data["ownership"]}
        for name in ("cancelled-start-False-unconfirmed", "retired-start-False-response-received"):
            assert rows[name]["expected"]["stopRequired"] is True
            assert rows[name]["expected"]["canPublish"] is False

    def test_recording_contract_never_auto_retries_start(self):
        data = json.loads((ROOT / "contracts/recording-lifecycle/v1/recording-vectors.json").read_text())
        assert all(row["expected"]["retryStart"] is False for row in data["ownership"])
        assert all(not row["expected"]["canReview"] for row in data["ownership"]
                   if row["input"]["observationEvidence"] != "observed" or row["input"]["recording"] is not False)
