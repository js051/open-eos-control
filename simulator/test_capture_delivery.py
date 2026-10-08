import asyncio
import hashlib
from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from main import app, canon_media, initial_state, state, uploaded_media_payloads

client = TestClient(app)
CONTENTS = "/ccapi/ver100/contents"
CAPTURE = "/ccapi/ver100/shooting/control/shutterbutton"
CONFIGURE = "/ccapi/test/capture-delivery"
CAPTURED_ID = "SIM_0003.JPG"
CAPTURED_PATH = f"{CONTENTS}/card1/100CANON/{CAPTURED_ID}"


def setup_function() -> None:
    state.clear()
    state.update(initial_state())
    uploaded_media_payloads.clear()


def test_capture_delivery_is_opt_in_and_reset_restores_default_contract() -> None:
    assert client.get("/ccapi/test/state").json()["capture_delivery"]["enabled"] is False
    client.post(CAPTURE, json={"af": True})
    default_original = client.get(CAPTURED_PATH).content
    assert client.get(f"{CAPTURED_PATH}?kind=thumbnail").content == default_original
    assert client.get(f"{CAPTURED_PATH}?kind=display").content == default_original
    assert CAPTURED_PATH in client.get(CONTENTS).json()["path"]

    client.post(CONFIGURE, json={"listing_failures": 2, "hidden_listing_reads": 3})
    client.post(CAPTURE, json={"af": True})
    assert client.get(CONTENTS).status_code == 503
    assert client.post("/ccapi/test/reset").json() == {"ok": True}
    fixture = client.get("/ccapi/test/state").json()["capture_delivery"]
    assert fixture["enabled"] is False
    assert fixture["captured_ids"] == []
    assert fixture["listing_get_count"] == 0
    assert fixture["representations"] == {}
    assert client.get(CONTENTS).status_code == 200


def test_capture_delivery_delays_visibility_after_one_shutter_and_recovers_with_get_only() -> None:
    configured = client.post(CONFIGURE, json={"listing_failures": 1, "hidden_listing_reads": 2})
    assert configured.status_code == 200
    assert client.get(f"{CONTENTS}?kind=number").status_code == 200
    assert client.get(CONTENTS).status_code == 200
    assert client.post(CAPTURE, json={"af": True}).status_code == 204

    assert client.get(f"{CONTENTS}?kind=number").status_code == 503
    assert client.get(f"{CONTENTS}?kind=number").json() == {"pagenumber": 1}
    assert CAPTURED_PATH not in client.get(f"{CONTENTS}?page=1").json()["path"]
    assert client.get(f"{CONTENTS}?kind=number").status_code == 200
    assert client.get(f"{CONTENTS}?page=2").json() == {"path": []}
    assert CAPTURED_PATH not in client.get(f"{CONTENTS}?page=1").json()["path"]
    assert CAPTURED_PATH in client.get(f"{CONTENTS}?page=1").json()["path"]

    observed = client.get("/ccapi/test/state").json()
    assert observed["capture_count"] == 1
    assert observed["canonical"]["shutter_af_requests"] == [True]
    fixture = observed["capture_delivery"]
    assert fixture["captured_ids"] == [CAPTURED_ID]
    assert fixture["listing_get_count"] == 9
    assert fixture["listing_failure_count"] == 1
    assert fixture["listing_failures_remaining"] == 0
    assert fixture["hidden_listing_reads_remaining"] == 0
    assert fixture["hidden_listing_read_count"] == 2


def test_capture_delivery_can_release_same_capture_without_resetting_observed_counters() -> None:
    client.post(CONFIGURE, json={"listing_failures": 1, "hidden_listing_reads": 20, "thumbnail_failures": 1})
    client.post(CAPTURE, json={"af": False})
    assert client.get(CONTENTS).status_code == 503
    for _ in range(4):
        assert CAPTURED_PATH not in client.get(CONTENTS).json()["path"]
    before = client.get("/ccapi/test/state").json()["capture_delivery"]
    assert before["hidden_listing_reads_remaining"] == 16

    updated = client.post(CONFIGURE, json={"hidden_listing_reads": 0, "listing_failures": 0}).json()
    assert updated["captured_ids"] == before["captured_ids"]
    assert updated["listing_get_count"] == before["listing_get_count"]
    assert updated["listing_failure_count"] == before["listing_failure_count"]
    assert updated["hidden_listing_read_count"] == 4
    assert updated["hidden_listing_reads_remaining"] == 0
    assert updated["thumbnail_failures"] == 1
    assert updated["representation_failures_remaining"]["thumbnail"] == 1
    assert CAPTURED_PATH in client.get(CONTENTS).json()["path"]
    assert client.get("/ccapi/test/state").json()["capture_count"] == 1


def test_capture_delivery_hidden_first_page_keeps_later_pages_consistent() -> None:
    client.post("/ccapi/test/media-pagination", json={"page_size": 1})
    client.post(CONFIGURE, json={"hidden_listing_reads": 1})
    client.post(CAPTURE, json={"af": True})
    assert client.get(f"{CONTENTS}?kind=number").json() == {"pagenumber": 2}
    assert client.get(f"{CONTENTS}?page=1").json()["path"] == [f"{CONTENTS}/card1/100CANON/SIM_0002.PNG"]
    assert client.get(f"{CONTENTS}?page=2").json()["path"] == [f"{CONTENTS}/card1/100CANON/SIM_0001.PNG"]
    assert client.get(f"{CONTENTS}?kind=number").json() == {"pagenumber": 3}
    assert client.get(f"{CONTENTS}?page=1").json()["path"] == [CAPTURED_PATH]
    assert client.get(f"{CONTENTS}?page=2").json()["path"] == [f"{CONTENTS}/card1/100CANON/SIM_0002.PNG"]


def test_capture_delivery_returns_distinct_valid_jpegs_and_exact_original_checksum() -> None:
    client.post(CONFIGURE, json={"thumbnail_failures": 1, "display_failures": 1, "original_failures": 1})
    client.post(CAPTURE, json={"af": True})
    expected = client.get("/ccapi/test/state").json()["capture_delivery"]["representations"]
    payloads = {}
    for representation in ("thumbnail", "display", "original"):
        path = CAPTURED_PATH if representation == "original" else f"{CAPTURED_PATH}?kind={representation}"
        assert client.get(path).status_code == 503
        downloaded = client.get(path)
        assert downloaded.status_code == 200
        assert downloaded.headers["content-type"] == "image/jpeg"
        payloads[representation] = downloaded.content
        assert len(downloaded.content) == expected[representation]["byte_count"]
        assert hashlib.sha256(downloaded.content).hexdigest() == expected[representation]["sha256"]
        with Image.open(BytesIO(downloaded.content)) as decoded:
            decoded.load()
            assert decoded.format == "JPEG"
            assert decoded.size == (expected[representation]["width"], expected[representation]["height"])
    assert len(set(payloads.values())) == 3
    assert client.get(f"{CAPTURED_PATH}?kind=main").content == payloads["original"]
    assert client.get(f"{CAPTURED_PATH}?kind=info").json()["filesize"] == len(payloads["original"])

    observed = client.get("/ccapi/test/state").json()
    assert observed["capture_count"] == 1
    fixture = observed["capture_delivery"]
    assert fixture["representation_get_counts"] == {"thumbnail": 2, "display": 2, "original": 3, "info": 1}
    assert fixture["representation_failure_counts"] == {"thumbnail": 1, "display": 1, "original": 1}
    assert fixture["representation_failures_remaining"] == {"thumbnail": 0, "display": 0, "original": 0}


def test_capture_delivery_fixture_is_stable_and_does_not_change_existing_media() -> None:
    existing_path = f"{CONTENTS}/card1/100CANON/SIM_0002.PNG"
    existing_bytes = client.get(existing_path).content
    client.post(CONFIGURE, json={"original_failures": 1})
    client.post(CAPTURE, json={"af": True})
    assert client.get(existing_path).content == existing_bytes
    assert client.get(f"{existing_path}?kind=thumbnail").content == existing_bytes
    assert client.get(CAPTURED_PATH).status_code == 503
    original = client.get(CAPTURED_PATH).content
    client.post("/ccapi/focus/tap", json={"x": 0.1, "y": 0.8})
    client.post("/ccapi/record/start", json={})
    assert client.get(CAPTURED_PATH).content == original
    assert client.get("/ccapi/test/state").json()["capture_delivery"]["representation_get_counts"]["original"] == 3


def test_capture_delivery_original_delay_can_be_cancelled_then_retried(monkeypatch) -> None:
    client.post(CONFIGURE, json={"original_delay_ms": 60_000})
    client.post(CAPTURE, json={"af": True})

    async def exercise_cancelled_get() -> None:
        started = asyncio.Event()

        async def delayed(seconds: float) -> None:
            assert seconds == 60
            started.set()
            await asyncio.Future()

        monkeypatch.setattr("main.asyncio.sleep", delayed)
        request = asyncio.create_task(canon_media(CAPTURED_ID))
        await started.wait()
        assert state["capture_delivery"]["representation_get_counts"]["original"] == 1
        assert not request.done()
        request.cancel()
        try:
            await request
        except asyncio.CancelledError:
            pass
        else:
            raise AssertionError("Delayed original GET should propagate cancellation")

    asyncio.run(exercise_cancelled_get())
    monkeypatch.undo()
    updated = client.post(CONFIGURE, json={"original_delay_ms": 0}).json()
    assert updated["captured_ids"] == [CAPTURED_ID]
    assert updated["representation_get_counts"]["original"] == 1
    assert client.get(CAPTURED_PATH).status_code == 200
    observed = client.get("/ccapi/test/state").json()
    assert observed["capture_delivery"]["representation_get_counts"]["original"] == 2
    assert observed["capture_count"] == 1


def test_capture_delivery_rejects_invalid_settings_without_mutating_scenario() -> None:
    configured = client.post(CONFIGURE, json={"hidden_listing_reads": 2}).json()
    for invalid in (
        {"enabled": "true"},
        {"listing_failures": True},
        {"listing_failures": -1},
        {"hidden_listing_reads": 21},
        {"thumbnail_failures": "1"},
        {"display_failures": 0.5},
        {"original_failures": 21},
        {"original_delay_ms": True},
        {"original_delay_ms": 60_001},
        {"unknown_setting": 1},
    ):
        assert client.post(CONFIGURE, json=invalid).status_code == 422
        assert client.get("/ccapi/test/state").json()["capture_delivery"] == configured
