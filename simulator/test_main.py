from fastapi.testclient import TestClient

from main import app, initial_state, state

client = TestClient(app)


def setup_function() -> None:
    state.clear()
    state.update(initial_state())


def test_state_endpoint_is_sanitized_and_resettable() -> None:
    client.patch("/ccapi/exposure", json={"iso": "1600"})
    client.post("/ccapi/record/start", json={})
    client.post("/ccapi/capture/still", json={"af": True})

    changed = client.get("/ccapi/test/state")
    reset = client.post("/ccapi/test/reset")
    restored = client.get("/ccapi/test/state")

    assert changed.status_code == 200
    assert changed.json()["exposure"]["iso"] == "1600"
    assert changed.json()["recording"] is True
    assert changed.json()["record_start_count"] == 1
    assert changed.json()["record_stop_count"] == 0
    assert changed.json()["capture_count"] == 1
    assert "event_history" not in changed.json()
    assert reset.json() == {"ok": True}
    assert restored.json()["exposure"]["iso"] == "800"
    assert restored.json()["recording"] is False
    assert restored.json()["record_start_count"] == 0
    assert restored.json()["record_stop_count"] == 0
    assert restored.json()["capture_count"] == 0


def test_half_press_and_release_are_stateful() -> None:
    half_press = client.post("/ccapi/shutter/half-press", json={})
    release = client.post("/ccapi/shutter/release", json={})

    assert half_press.status_code == 200
    assert half_press.json()["half_pressed"] is True
    assert release.status_code == 200
    assert release.json()["half_pressed"] is False
    assert state["half_pressed"] is False


def test_clock_sync_records_time_and_publishes_change() -> None:
    response = client.post("/ccapi/clock/sync", json={})
    test_state = client.get("/ccapi/test/state").json()
    event = client.get("/ccapi/events?after=0").json()

    assert response.status_code == 200
    assert response.json()["clock_sync_count"] == 1
    assert response.json()["camera_datetime"]
    assert test_state["clock_sync_count"] == 1
    assert test_state["camera_datetime"] == response.json()["camera_datetime"]
    assert event["keys"] == ["datetime"]


def test_bulb_start_and_stop_are_stateful() -> None:
    started = client.post("/ccapi/bulb/start", json={})
    active_status = client.get("/ccapi/status")
    stopped = client.post("/ccapi/bulb/stop", json={})

    assert started.status_code == 200
    assert active_status.json()["bulb_exposure_active"] is True
    assert stopped.status_code == 200
    assert state["bulb_exposure_active"] is False


def test_click_white_balance_records_the_point_and_updates_status() -> None:
    response = client.post("/ccapi/whitebalance/click", json={"x": 0.4, "y": 0.6})

    assert response.status_code == 200
    assert response.json()["exposure"]["white_balance"] == "click"
    assert (state["click_wb_x"], state["click_wb_y"], state["click_wb_count"]) == (
        0.4,
        0.6,
        1,
    )


def test_capture_adds_a_downloadable_media_item() -> None:
    capture = client.post("/ccapi/capture/still", json={"af": True})
    media = client.get("/ccapi/media")
    added = media.json()["items"][0]
    download = client.get(f"/ccapi/media/{added['id']}")

    assert capture.status_code == 200
    assert capture.json()["capture_count"] == 1
    assert added["name"] == "SIM_0003.PNG"
    assert added["size_bytes"] > 0
    assert download.status_code == 200
    assert download.headers["content-type"] == "image/png"
    assert download.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_unknown_media_returns_not_found() -> None:
    response = client.get("/ccapi/media/DOES_NOT_EXIST.PNG")

    assert response.status_code == 404


def test_media_thumbnail_uses_canon_kind_query() -> None:
    response = client.get("/ccapi/media/SIM_0001.PNG?kind=thumbnail")

    assert response.status_code == 200
    assert response.headers["content-type"] == "image/png"
    assert response.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_media_display_preview_uses_canon_kind_query() -> None:
    response = client.get("/ccapi/media/SIM_0001.PNG?kind=display")

    assert response.status_code == 200
    assert response.headers["content-type"] == "image/png"
    assert response.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_media_delete_removes_only_the_requested_item() -> None:
    response = client.delete("/ccapi/media/SIM_0002.PNG")
    media = client.get("/ccapi/media")

    assert response.status_code == 204
    assert [item["id"] for item in media.json()["items"]] == ["SIM_0001.PNG"]
    assert client.delete("/ccapi/media/SIM_0002.PNG").status_code == 404


def test_events_return_changes_after_sequence() -> None:
    client.patch("/ccapi/exposure", json={"iso": "1600"})
    first = client.get("/ccapi/events?after=0").json()
    empty = client.get(f"/ccapi/events?after={first['sequence']}").json()

    assert first == {"sequence": 1, "keys": ["shootingsettings"]}
    assert empty == {"sequence": 1, "keys": []}


def test_events_coalesce_multiple_camera_changes() -> None:
    client.post("/ccapi/record/start", json={})
    client.post("/ccapi/capture/still", json={"af": True})

    response = client.get("/ccapi/events?after=0").json()

    assert response["sequence"] == 2
    assert response["keys"] == ["contents", "recbutton"]
