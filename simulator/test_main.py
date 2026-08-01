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
    assert changed.json()["mode"] == "movie"
    assert "event_history" not in changed.json()
    assert reset.json() == {"ok": True}
    assert restored.json()["exposure"]["iso"] == "800"
    assert restored.json()["recording"] is False
    assert restored.json()["record_start_count"] == 0
    assert restored.json()["record_stop_count"] == 0
    assert restored.json()["capture_count"] == 0
    assert restored.json()["half_press_count"] == 0
    assert restored.json()["focus_drive"]["count"] == 0


def test_half_press_and_release_are_stateful() -> None:
    half_press = client.post("/ccapi/shutter/half-press", json={})
    release = client.post("/ccapi/shutter/release", json={})

    assert half_press.status_code == 200
    assert half_press.json()["half_pressed"] is True
    assert release.status_code == 200
    assert release.json()["half_pressed"] is False
    assert state["half_pressed"] is False
    assert state["half_press_count"] == 1
    assert state["shutter_release_count"] == 1


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
    mode = client.post("/ccapi/test/mode?mode=Bulb")
    started = client.post("/ccapi/bulb/start", json={})
    active_status = client.get("/ccapi/status")
    stopped = client.post("/ccapi/bulb/stop", json={})

    assert mode.status_code == 200
    assert mode.json()["mode"] == "Bulb"
    assert started.status_code == 200
    assert active_status.json()["bulb_exposure_active"] is True
    assert stopped.status_code == 200
    assert state["bulb_exposure_active"] is False
    assert state["bulb_start_count"] == 1
    assert state["bulb_stop_count"] == 1


def test_bulb_start_rejects_non_bulb_mode() -> None:
    response = client.post("/ccapi/bulb/start", json={})

    assert response.status_code == 409
    assert state["bulb_exposure_active"] is False
    assert state["bulb_start_count"] == 0


def test_focus_drive_records_validated_direction_and_step() -> None:
    response = client.post(
        "/ccapi/focus/drive",
        json={"direction": "far", "step": "large"},
    )

    assert response.status_code == 200
    assert response.json() == {"ok": True, "direction": "far", "step": "large"}
    assert state["focus_drive_count"] == 1
    assert state["focus_drive_direction"] == "far"
    assert state["focus_drive_step"] == "large"
    assert client.post(
        "/ccapi/focus/drive",
        json={"direction": "outside", "step": "large"},
    ).status_code == 422


def test_click_white_balance_records_the_point_and_updates_status() -> None:
    response = client.post("/ccapi/whitebalance/click", json={"x": 0.4, "y": 0.6})

    assert response.status_code == 200
    assert response.json()["exposure"]["white_balance"] == "click"
    assert (state["click_wb_x"], state["click_wb_y"], state["click_wb_count"]) == (
        0.4,
        0.6,
        1,
    )


def test_tap_focus_records_the_point_and_count() -> None:
    response = client.post("/ccapi/focus/tap", json={"x": 0.25, "y": 0.75})

    assert response.status_code == 200
    assert response.json() == {"ok": True, "x": 0.25, "y": 0.75}
    assert (state["focus_x"], state["focus_y"], state["focus_count"]) == (
        0.25,
        0.75,
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


def test_canonical_ccapi_discovery_settings_and_live_view_contract() -> None:
    discovery = client.get("/ccapi")
    settings = client.get("/ccapi/ver100/shooting/settings")
    changed = client.put(
        "/ccapi/ver100/shooting/settings/iso",
        json={"value": "1600"},
    )
    rejected_size = client.post(
        "/ccapi/ver100/shooting/liveview",
        json={"cameradisplay": "on", "liveviewsize": "medium"},
    )
    started = client.post(
        "/ccapi/ver100/shooting/liveview",
        json={"cameradisplay": "on"},
    )
    detailed = client.get("/ccapi/ver100/shooting/liveview/flipdetail?kind=both")
    stopped = client.delete("/ccapi/ver100/shooting/liveview")
    test_state = client.get("/ccapi/test/state").json()

    assert discovery.status_code == 200
    assert any(
        entry == {"path": "/shooting/control/shutterbutton", "post": True}
        for entry in discovery.json()["ver100"]
    )
    assert settings.json()["iso"] == {
        "value": "800",
        "ability": ["100", "200", "400", "800", "1600", "3200", "6400"],
    }
    assert changed.status_code == 204
    assert state["exposure"]["iso"] == "1600"
    assert rejected_size.status_code == 400
    assert started.status_code == 204
    assert detailed.status_code == 200
    assert detailed.content.startswith(b"\xff\x00\x00")
    assert b"positionwidth" in detailed.content
    assert b"\xff\xd8" in detailed.content and b"\xff\xd9" in detailed.content
    assert stopped.status_code == 204
    assert test_state["canonical"] == {
        "af_start_count": 0,
        "af_stop_count": 0,
        "focus_position": None,
        "click_wb_position": None,
        "live_view_active": False,
        "live_view_start_count": 1,
        "live_view_stop_count": 1,
        "live_view_size_rejections": 1,
    }


def test_canonical_ccapi_controls_and_media_mutate_backend_state() -> None:
    assert client.post(
        "/ccapi/ver100/shooting/control/shutterbutton",
        json={"af": True},
    ).status_code == 204
    assert client.post(
        "/ccapi/ver100/shooting/control/af",
        json={"action": "start"},
    ).status_code == 204
    assert client.post(
        "/ccapi/ver100/shooting/control/af",
        json={"action": "stop"},
    ).status_code == 204
    assert client.put(
        "/ccapi/ver100/shooting/control/shutterbutton/manual",
        json={"af": True, "action": "half_press"},
    ).status_code == 204
    assert client.put(
        "/ccapi/ver100/shooting/control/shutterbutton/manual",
        json={"af": False, "action": "release"},
    ).status_code == 204
    assert client.post(
        "/ccapi/ver100/shooting/control/recbutton",
        json={"action": "start"},
    ).status_code == 204
    assert client.post(
        "/ccapi/ver100/shooting/control/recbutton",
        json={"action": "stop"},
    ).status_code == 204
    assert client.post(
        "/ccapi/ver100/shooting/control/drivefocus",
        json={"value": "near3"},
    ).status_code == 204
    assert client.put(
        "/ccapi/ver100/shooting/liveview/afframeposition",
        json={"positionx": 4000, "positiony": 1600},
    ).status_code == 204
    assert client.post(
        "/ccapi/ver100/shooting/liveview/clickwb",
        json={"positionx": 2200, "positiony": 2800},
    ).status_code == 204

    assert client.put(
        "/ccapi/ver100/shooting/settings/shootingmode",
        json={"value": "Bulb"},
    ).status_code == 204
    assert client.put(
        "/ccapi/ver100/shooting/control/shutterbutton/manual",
        json={"af": False, "action": "full_press"},
    ).status_code == 204
    assert client.put(
        "/ccapi/ver100/shooting/control/shutterbutton/manual",
        json={"af": False, "action": "release"},
    ).status_code == 204

    contents = client.get("/ccapi/ver100/contents?page=1&order=desc").json()["path"]
    captured_path = next(path for path in contents if path.endswith("SIM_0003.JPG"))
    preview = client.get(f"{captured_path}?kind=display")
    deleted = client.delete(captured_path)
    test_state = client.get("/ccapi/test/state").json()

    assert preview.status_code == 200
    assert preview.headers["content-type"].startswith("image/jpeg")
    assert preview.content.startswith(b"\xff\xd8") and preview.content.endswith(b"\xff\xd9")
    assert deleted.status_code == 204
    assert test_state["capture_count"] == 1
    assert test_state["canonical"]["af_start_count"] == 1
    assert test_state["canonical"]["af_stop_count"] == 1
    assert test_state["half_press_count"] == 1
    assert test_state["shutter_release_count"] == 1
    assert test_state["record_start_count"] == 1
    assert test_state["record_stop_count"] == 1
    assert test_state["focus_drive"] == {
        "count": 1,
        "direction": "near",
        "step": "large",
    }
    assert test_state["canonical"]["focus_position"] == {"x": 4000, "y": 1600}
    assert test_state["canonical"]["click_wb_position"] == {"x": 2200, "y": 2800}
    assert test_state["bulb_start_count"] == 1
    assert test_state["bulb_stop_count"] == 1
    assert "SIM_0003.JPG" not in test_state["media_ids"]
