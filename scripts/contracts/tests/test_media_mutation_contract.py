"""Integrity of the shared fixture, not execution of platform production code."""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
PATH = ROOT / "contracts/media-mutation/v1/media-mutation-vectors.json"
EXPECTED_SHA256 = "2d30173f8abb0538f1aa320cfce24e2fb0e01f86ef6fc54db93a052ed2fdd401"


def corpus():
    return json.loads(PATH.read_text(encoding="utf-8"))


class MediaMutationContractTests(unittest.TestCase):
    def test_shared_corpus_checksum(self):
        assert hashlib.sha256(PATH.read_bytes()).hexdigest() == EXPECTED_SHA256

    def test_shared_corpus_schema_and_counts(self):
        data = corpus()
        assert data["schemaVersion"] == 1
        assert data["contract"] == "media-mutation-evidence-v1"
        assert len(data["metadataVectors"]) == 200
        assert len(data["readbackVectors"]) == 66
        assert len(data["batchVectors"]) == 10
        assert len(data["rosterVectors"]) == 3
        for key in ["metadataVectors", "readbackVectors", "batchVectors", "rosterVectors"]:
            assert len({row["id"] for row in data[key]}) == len(data[key])

    def test_metadata_vectors_preserve_raw_json_and_unknown(self):
        for row in corpus()["metadataVectors"]:
            assert row["profile"] in {"canon", "normalized"}
            assert row["field"] in {"rating", "rotation", "protection", "archive"}
            if row["rawValueJSON"] is not None:
                json.loads(row["rawValueJSON"])
            if not row["known"]:
                assert row["value"] is None
            elif row["field"] in {"protection", "archive"}:
                assert type(row["value"]) is bool
            else:
                assert type(row["value"]) is int

    def test_readback_vectors_retain_identity_and_target(self):
        for row in corpus()["readbackVectors"]:
            assert row["outcome"] in {"confirmed", "uncertain"}
            assert row["operation"] in {"protection", "rating", "rotation", "archive"}
            if not row["returnedIdPresent"]:
                expected = "confirmed" if row["profile"] == "canon" else "uncertain"
                assert row["outcome"] == expected

    def test_batch_vectors_preserve_entire_roster_and_no_replay(self):
        states = {"confirmed", "uncertain", "failed-not-sent", "not-started"}
        for row in corpus()["batchVectors"]:
            assert len(row["events"]) == len(row["outcomes"])
            assert set(row["outcomes"]) <= states
            writes = row["dispatchedIndices"]
            assert writes == sorted(set(writes))
            assert all(0 <= index < len(row["events"]) for index in writes)
            if "uncertain" in row["outcomes"]:
                after = row["outcomes"].index("uncertain") + 1
                assert set(row["outcomes"][after:]) <= {"not-started"}

    def test_rosters_keep_hidden_unloaded_and_byte_exact_ids(self):
        for row in corpus()["rosterVectors"]:
            assert row["rosterIds"] == list(dict.fromkeys(row["selectedIds"]))
            loaded = set(row["loadedIds"])
            visible = set(row["visibleIds"])
            assert row["eligibleIds"] == [x for x in row["rosterIds"] if x in loaded]
            assert row["hiddenCount"] == sum(x in loaded - visible for x in row["rosterIds"])
            assert row["unloadedCount"] == sum(x not in loaded for x in row["rosterIds"])

    def test_batch_limits_are_explicit(self):
        assert corpus()["limits"] == {
            "maximumUniqueItems": 1000,
            "maximumWritesPerItem": 1,
            "maximumConcurrentWrites": 1,
        }

    def test_eligibility_corpus_checksum_and_counts(self):
        path = ROOT / "contracts/media-mutation/v1/media-mutation-eligibility-vectors.json"
        digest = "c7c6e27e7455ff12989cea4fab48f97fd103344ff442987a37cc4a32e3f99a1d"
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest
        data = json.loads(path.read_text(encoding="utf-8"))
        assert data["contract"] == "media-mutation-eligibility-v1"
        assert len(data["vectors"]) == 25
        assert len(data["snapshotVectors"]) == 2
        for key in ["vectors", "snapshotVectors"]:
            assert len({row["id"] for row in data[key]}) == len(data[key])

    def test_eligibility_does_not_grant_missing_capability_or_stale_selection(self):
        path = ROOT / "contracts/media-mutation/v1/media-mutation-eligibility-vectors.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        for row in data["vectors"]:
            if not row["loaded"] or not row["supported"]:
                assert row["eligible"] is False
        for row in data["snapshotVectors"]:
            assert row["eligible"] is False
            assert row["selected"]["id"] == row["loaded"]["id"]
            assert row["selected"] != row["loaded"]
