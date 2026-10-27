"""Integrity of the shared deletion vectors; platform consumers test behavior."""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
PATH = ROOT / "contracts/media-delete/v1/delete-outcome-vectors.json"
EXPECTED_SHA = "f42c67bc2b80185ac2198d00a59a9188b4424a4e6fa21a0f9df69f26fef15a68"
COUNTS = {
    "eligibility": 216,
    "admission": 24,
    "outcome": 32,
    "httpReceipt": 192,
    "selection": 54,
    "roster": 10,
    "batch": 12,
}


def fixture():
    return json.loads(PATH.read_text())


class MediaDeleteContractTests(unittest.TestCase):
    def test_exact_shared_fixture_identity(self):
        assert hashlib.sha256(PATH.read_bytes()).hexdigest() == EXPECTED_SHA

    def test_complete_unique_sections(self):
        data = fixture()
        assert data["schemaVersion"] == 1
        identifiers = []
        for name, count in COUNTS.items():
            assert len(data[name]) == count
            identifiers += [row["id"] for row in data[name]]
            assert all(set(row) == {"id", "input", "expected"} for row in data[name])
        assert len(identifiers) == len(set(identifiers)) == 540

    def test_bounded_roster_and_history(self):
        assert fixture()["limits"] == {
            "maxRows": 1000, "maxHistoryBatches": 20, "maxHistoryRows": 2000,
        }
        rows = {row["id"]: row for row in fixture()["roster"]}
        assert rows["roster-limit"]["expected"]["allowed"]
        assert not rows["roster-over-limit"]["expected"]["allowed"]
        assert rows["roster-over-limit"]["expected"]["ids"] == []
        assert rows["roster-unicode"]["expected"]["ids"] == ["é", "e\u0301"]

    def test_known_veto_and_unknown_qualification_are_not_conflated(self):
        for row in fixture()["eligibility"]:
            x, y = row["input"], row["expected"]
            if y["allowed"]:
                assert x["loaded"] and x["capability"] is True
                assert x["source"] != "unknown"
                assert x["protected"] is not True and x["writable"] is not False
                assert y["qualificationUnknown"] == (
                    x["protected"] is None or x["writable"] is None
                )

    def test_dispatch_evidence_cannot_be_downgraded(self):
        for row in fixture()["outcome"]:
            x, y = row["input"], row["expected"]["status"]
            if x["dispatched"]:
                assert y in {"confirmed", "rejected", "uncertain"}
                if x["confirmedReceipt"]:
                    assert y == "confirmed"
            else:
                assert y in {"not-started", "not-sent", "skipped"}

    def test_only_complete_empty_204_can_confirm_http(self):
        for row in fixture()["httpReceipt"]:
            x, y = row["input"], row["expected"]["status"]
            if y == "confirmed":
                assert x == {
                    "status": 204, "responseComplete": True,
                    "framingValid": True, "bodyBytes": 0,
                }
            if x["status"] in {202, 408, 499, 500, 503}:
                assert y == "uncertain"

    def test_retired_owner_or_changed_selection_is_not_consumed(self):
        for row in fixture()["selection"]:
            x = row["input"]
            if x["operationOwner"] != x["currentOwner"] or (
                x["operationSelectionToken"] != x["currentSelectionToken"]
            ):
                assert row["expected"]["selectedIds"] == x["selectedIds"]

    def test_unknown_batch_result_stops_without_replay(self):
        for row in fixture()["batch"]:
            statuses = row["expected"]["statuses"]
            counts = row["expected"]["dispatches"]
            assert all(count in {0, 1} for count in counts)
            for index, status in enumerate(statuses):
                if status in {"uncertain", "not-sent"}:
                    assert all(s == "not-started" for s in statuses[index + 1:])
                    assert all(n == 0 for n in counts[index + 1:])
