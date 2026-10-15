"""Shared corpus integrity; not platform transport or runtime acceptance."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PATH = ROOT / "contracts/media-upload/v1/upload-outcome-vectors.json"
DIGEST = "dcb90087f58db32166fa86291486190a1aca1ea368ff7e74cf3342a601c8b1fd"
COUNTS = {"outcomeCases": 48, "admissionCases": 12, "publicationCases": 20,
          "receiptCases": 48, "journeyCases": 14}


def corpus():
    return json.loads(PATH.read_text(encoding="utf-8"))


def test_corpus_checksum():
    assert hashlib.sha256(PATH.read_bytes()).hexdigest() == DIGEST


def test_schema_counts_and_unique_ids():
    data = corpus()
    assert data["schemaVersion"] == 1
    assert data["contract"] == "media-upload-outcome-v1"
    for key, count in COUNTS.items():
        assert len(data[key]) == count
        assert len({row["id"] for row in data[key]}) == count


def test_outcomes_never_promote_listing_observation():
    for row in corpus()["outcomeCases"]:
        expected = "confirmed" if row["verifiedReceiptForAttempt"] else (
            "uncertain" if row["dispatchStarted"] else "not-sent"
        )
        assert row["expectedOutcome"] == expected
        assert not row["verifiedReceiptForAttempt"] or row["dispatchStarted"]


def test_picker_admission_is_single_use_and_owner_bound():
    for row in corpus()["admissionCases"]:
        expected = (row["openingOwner"] == row["currentOwner"]
                    and row["openingToken"] == row["currentToken"]
                    and row["singleUseAvailable"] and row["featureSupported"]
                    and not row["mediaBusy"] and row["sourceSelected"])
        assert row["expectedAdmit"] == expected


def test_all_publication_stages_require_owner_and_attempt():
    rows = corpus()["publicationCases"]
    assert {r["stage"] for r in rows} == {"progress", "receipt", "refresh", "error", "finally"}
    for row in rows:
        expected = (row["attemptOwner"] == row["currentOwner"]
                    and row["attemptToken"] == row["currentToken"])
        assert row["expectedPublish"] == expected


def test_receipt_positive_controls_are_operation_bound():
    for row in corpus()["receiptCases"]:
        if row["expectedVerified"]:
            assert row["boundToAttempt"] and row["statusCode"] == 201
            body = json.loads(row["rawResponseJSON"])
            assert type(body["sizeBytes"]) is int
            assert body["sizeBytes"] == row["expectedSizeBytes"]
            assert 1 <= body["sizeBytes"] <= 4294967295


def test_journeys_have_no_replay_or_invented_dispatch():
    for row in corpus()["journeyCases"]:
        assert row["expectedDispatches"] == row["events"].count("dispatch")
        assert row["expectedDispatches"] <= 1
        assert row["automaticReplayCount"] == 0
        assert row["expectedOutcome"] in {"confirmed", "not-sent", "uncertain"}
        if row["expectedOutcome"] == "confirmed":
            assert "verified-receipt" in row["events"]


def test_limits_and_required_regressions():
    data = corpus()
    assert data["limits"] == {"maximumUploadBytes": 4294967295,
                              "maximumDispatchesPerAttempt": 1, "automaticReplay": False}
    ids = {r["id"] for r in data["journeyCases"]}
    assert {"staging-cancel-existing-match", "dispatch-cancel-existing-match",
            "picker-reconnect", "receipt-then-cancel", "receipt-refresh-reconnect"} <= ids
