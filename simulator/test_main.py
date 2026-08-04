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


def test_canon_recordable_information_tracks_photo_and_movie_context() -> None:
    initial = client.get("/ccapi/ver100/shooting/information/recordable")
    client.post("/ccapi/capture/still", json={"af": True})
    after_capture = client.get("/ccapi/ver100/shooting/information/recordable")
    client.post("/ccapi/movie-mode", json={"action": "on"})
    movie = client.get("/ccapi/ver100/shooting/information/recordable")

    assert initial.json() == {"recordableshots": 2_418, "remainingtime": None}
    assert after_capture.json() == {"recordableshots": 2_417, "remainingtime": None}
    assert movie.json() == {"recordableshots": None, "remainingtime": 7_200}


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
        "event_cursor": 0,
        "event_poll_count": 0,
        "event_delivery_count": 0,
        "event_delete_count": 0,
        "event_active_requests": 0,
    }


def test_canonical_lens_and_temperature_status_match_documented_shapes() -> None:
    discovery = client.get("/ccapi").json()["ver100"]
    lens = client.get("/ccapi/ver100/devicestatus/lens")
    temperature = client.get("/ccapi/ver100/devicestatus/temperature")
    changed = client.post(
        "/ccapi/test/temperature?status=frameratedown_and_restrictionmovierecording"
    )
    updated = client.get("/ccapi/ver100/devicestatus/temperature")
    invalid = client.post("/ccapi/test/temperature?status=hot")

    assert {"path": "/devicestatus/lens", "get": True} in discovery
    assert {"path": "/devicestatus/temperature", "get": True} in discovery
    assert lens.json() == {"mount": True, "name": "RF24-105mm F4 L IS USM"}
    assert temperature.json() == {"status": "normal"}
    assert changed.status_code == 200
    assert updated.json() == {"status": "frameratedown_and_restrictionmovierecording"}
    assert invalid.status_code == 422


def test_temperature_restrictions_block_starts_but_allow_stops() -> None:
    client.post("/ccapi/test/temperature?status=disablerelease")
    legacy_capture = client.post("/ccapi/capture/still", json={"af": True})
    canonical_capture = client.post(
        "/ccapi/ver100/shooting/control/shutterbutton",
        json={"af": True},
    )
    assert legacy_capture.status_code == 409
    assert canonical_capture.status_code == 409
    assert state["capture_count"] == 0

    client.post("/ccapi/test/temperature?status=normal")
    assert client.post("/ccapi/record/start", json={}).status_code == 200
    client.post("/ccapi/test/temperature?status=restrictionmovierecording")
    assert client.post("/ccapi/record/start", json={}).status_code == 409
    assert client.post("/ccapi/record/stop", json={}).status_code == 200
    assert state["record_start_count"] == 1
    assert state["record_stop_count"] == 1

    client.post("/ccapi/test/temperature?status=normal")
    assert client.post(
        "/ccapi/ver100/shooting/liveview",
        json={"cameradisplay": "on"},
    ).status_code == 204
    client.post("/ccapi/test/temperature?status=disableliveview")
    assert client.post(
        "/ccapi/ver100/shooting/liveview",
        json={"cameradisplay": "on"},
    ).status_code == 409
    assert client.delete("/ccapi/ver100/shooting/liveview").status_code == 204


def test_canonical_ccapi_event_polling_delivers_partial_changes_and_stops() -> None:
    discovery = client.get("/ccapi").json()
    changed = client.patch("/ccapi/exposure", json={"iso": "3200"})
    event = client.get("/ccapi/ver110/event/polling?timeout=long")
    stopped = client.delete("/ccapi/ver110/event/polling")
    test_state = client.get("/ccapi/test/state").json()["canonical"]

    assert {"path": "/event/polling", "get": True, "delete": True} in discovery["ver110"]
    assert changed.status_code == 200
    assert event.status_code == 200
    assert event.json() == {"shootingsettings": {}}
    assert stopped.status_code == 204
    assert test_state["event_cursor"] == 1
    assert test_state["event_poll_count"] == 1
    assert test_state["event_delivery_count"] == 1
    assert test_state["event_delete_count"] == 1
    assert test_state["event_active_requests"] == 0


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


def test_zoom_contract_uses_integer_value_and_mutates_both_simulator_routes() -> None:
    client.post("/ccapi/test/reset")

    ability = client.get("/ccapi/ver100/shooting/control/zoom")
    canonical = client.post("/ccapi/ver100/shooting/control/zoom", json={"value": 75})
    simulator = client.post("/ccapi/zoom", json={"value": 25})
    invalid = client.post("/ccapi/ver100/shooting/control/zoom", json={"value": "50"})
    test_state = client.get("/ccapi/test/state").json()

    assert ability.json() == {"value": 50, "ability": {"min": 0, "max": 100, "step": 1}}
    assert canonical.json() == {"value": 75}
    assert simulator.json() == {"value": 25}
    assert invalid.status_code == 422
    assert test_state["zoom"] == {"value": 25, "update_count": 2}


def test_sound_recording_level_contract_uses_integer_value_and_mutates_both_routes() -> None:
    client.post("/ccapi/test/reset")

    discovery = client.get("/ccapi").json()["ver100"]
    ability = client.get("/ccapi/ver100/shooting/settings/soundrecording/level")
    canonical = client.put(
        "/ccapi/ver100/shooting/settings/soundrecording/level",
        json={"value": 48},
    )
    simulator = client.put("/ccapi/sound-recording-level", json={"value": 24})
    invalid_string = client.put(
        "/ccapi/ver100/shooting/settings/soundrecording/level",
        json={"value": "32"},
    )
    invalid_bool = client.put(
        "/ccapi/ver100/shooting/settings/soundrecording/level",
        json={"value": True},
    )
    invalid_range = client.put(
        "/ccapi/ver100/shooting/settings/soundrecording/level",
        json={"value": 64},
    )
    test_state = client.get("/ccapi/test/state").json()

    assert {
        "path": "/shooting/settings/soundrecording/level",
        "get": True,
        "put": True,
    } in discovery
    assert ability.json() == {"value": 32, "ability": {"min": 0, "max": 63, "step": 1}}
    assert canonical.json() == {"value": 48}
    assert simulator.status_code == 204
    assert invalid_string.status_code == 400
    assert invalid_bool.status_code == 400
    assert invalid_range.status_code == 400
    assert invalid_range.json() == {"message": "Invalid parameter"}
    assert test_state["sound_recording_level"] == {"value": 24, "update_count": 2}


def test_device_function_settings_use_documented_values_and_mutate_both_routes() -> None:
    client.post("/ccapi/test/reset")

    discovery = client.get("/ccapi").json()["ver100"]
    beep = client.get("/ccapi/ver100/functions/beep")
    display = client.get("/ccapi/ver100/functions/displayoff")
    canonical_beep = client.put("/ccapi/ver100/functions/beep", json={"value": "disable"})
    simulator_beep = client.put("/ccapi/device-settings/beep", json={"value": "disabletouch"})
    canonical_display = client.put("/ccapi/ver100/functions/displayoff", json={"value": "120"})
    simulator_display = client.put("/ccapi/device-settings/display-off", json={"value": "20"})
    invalid_beep = client.put("/ccapi/ver100/functions/beep", json={"value": "future"})
    invalid_display = client.put("/ccapi/ver100/functions/displayoff", json={"value": 60})
    client.post("/ccapi/record/start")
    busy = client.put("/ccapi/ver100/functions/beep", json={"value": "enable"})
    test_state = client.get("/ccapi/test/state").json()

    assert {"path": "/functions/beep", "get": True, "put": True} in discovery
    assert {"path": "/functions/displayoff", "get": True, "put": True} in discovery
    assert beep.json() == {"value": "enable", "ability": ["enable", "disable", "disabletouch"]}
    assert display.json() == {"value": "60", "ability": ["10", "20", "30", "60", "120", "180"]}
    assert canonical_beep.json() == {"value": "disable"}
    assert simulator_beep.status_code == 204
    assert canonical_display.json() == {"value": "120"}
    assert simulator_display.status_code == 204
    assert invalid_beep.status_code == 400
    assert invalid_display.status_code == 400
    assert busy.status_code == 503
    assert test_state["beep"] == {"value": "disabletouch", "update_count": 2}
    assert test_state["display_off"] == {"value": "20", "update_count": 2}


def test_sound_recording_controls_use_documented_string_abilities_and_mode_gates() -> None:
    client.post("/ccapi/test/reset")

    discovery = client.get("/ccapi").json()["ver100"]
    sound = client.get("/ccapi/ver100/shooting/settings/soundrecording")
    wind = client.get("/ccapi/ver100/shooting/settings/soundrecording/windfilter")
    attenuator = client.get("/ccapi/ver100/shooting/settings/soundrecording/attenuator")
    wind_update = client.put(
        "/ccapi/ver100/shooting/settings/soundrecording/windfilter",
        json={"value": "enable"},
    )
    attenuator_update = client.put("/ccapi/attenuator", json={"value": "manual"})
    invalid = client.put("/ccapi/wind-filter", json={"value": "on"})
    disable = client.put(
        "/ccapi/ver100/shooting/settings/soundrecording",
        json={"value": "disable"},
    )
    unavailable_wind = client.get("/ccapi/ver100/shooting/settings/soundrecording/windfilter")
    unavailable_level = client.get("/ccapi/ver100/shooting/settings/soundrecording/level")
    simulator_capabilities = client.get("/ccapi/capabilities").json()
    test_state = client.get("/ccapi/test/state").json()

    for path in (
        "/shooting/settings/soundrecording",
        "/shooting/settings/soundrecording/windfilter",
        "/shooting/settings/soundrecording/attenuator",
    ):
        assert {"path": path, "get": True, "put": True} in discovery
    assert sound.json() == {"value": "manual", "ability": ["auto", "manual", "disable"]}
    assert wind.json() == {"value": "auto", "ability": ["auto", "enable", "disable"]}
    assert attenuator.json() == {
        "value": "disable",
        "ability": ["enable", "disable", "auto", "manual"],
    }
    assert wind_update.json() == {"value": "enable"}
    assert attenuator_update.status_code == 204
    assert invalid.status_code == 400
    assert disable.json() == {"value": "disable"}
    assert unavailable_wind.status_code == 503
    assert unavailable_level.status_code == 503
    assert "windfilter" not in simulator_capabilities
    assert "attenuator" not in simulator_capabilities
    assert "soundrecordinglevel" not in simulator_capabilities
    assert test_state["sound_recording"] == {"value": "disable", "update_count": 1}
    assert test_state["wind_filter"] == {"value": "enable", "update_count": 1}
    assert test_state["attenuator"] == {"value": "manual", "update_count": 1}


def test_focus_bracketing_contract_uses_exact_types_ranges_and_photo_mode_gate() -> None:
    client.post("/ccapi/test/reset")

    discovery = client.get("/ccapi").json()["ver100"]
    root = client.get("/ccapi/ver100/shooting/settings/focusbracketing")
    shots = client.get("/ccapi/ver100/shooting/settings/focusbracketing/numberofshots")
    increment = client.get("/ccapi/ver100/shooting/settings/focusbracketing/focusincrement")
    smoothing = client.get("/ccapi/ver100/shooting/settings/focusbracketing/exposuresmoothing")
    root_update = client.put(
        "/ccapi/ver100/shooting/settings/focusbracketing",
        json={"value": "enable"},
    )
    shots_update = client.put(
        "/ccapi/ver100/shooting/settings/focusbracketing/numberofshots",
        json={"value": 250},
    )
    increment_update = client.put(
        "/ccapi/focus-bracketing/focus-increment",
        json={"value": 7},
    )
    smoothing_update = client.put(
        "/ccapi/focus-bracketing/exposure-smoothing",
        json={"value": "enable"},
    )
    invalid_string = client.put(
        "/ccapi/ver100/shooting/settings/focusbracketing/numberofshots",
        json={"value": "100"},
    )
    invalid_range = client.put(
        "/ccapi/ver100/shooting/settings/focusbracketing/numberofshots",
        json={"value": 1000},
    )
    client.post("/ccapi/movie-mode", json={"action": "on"})
    unavailable_get = client.get("/ccapi/ver100/shooting/settings/focusbracketing")
    unavailable_put = client.put(
        "/ccapi/focus-bracketing/number-of-shots",
        json={"value": 300},
    )
    test_state = client.get("/ccapi/test/state").json()

    for path in (
        "/shooting/settings/focusbracketing",
        "/shooting/settings/focusbracketing/numberofshots",
        "/shooting/settings/focusbracketing/focusincrement",
        "/shooting/settings/focusbracketing/exposuresmoothing",
    ):
        assert {"path": path, "get": True, "put": True} in discovery
    assert root.json() == {"value": "disable", "ability": ["enable", "disable"]}
    assert shots.json() == {"value": 100, "ability": {"min": 2, "max": 999, "step": 1}}
    assert increment.json() == {"value": 4, "ability": {"min": 1, "max": 10, "step": 1}}
    assert smoothing.json() == {"value": "disable", "ability": ["enable", "disable"]}
    assert root_update.json() == {"value": "enable"}
    assert shots_update.json() == {"value": 250}
    assert increment_update.status_code == 204
    assert smoothing_update.status_code == 204
    assert invalid_string.status_code == 400
    assert invalid_range.status_code == 400
    assert unavailable_get.status_code == 503
    assert unavailable_put.status_code == 503
    assert test_state["focus_bracketing"] == {"value": "enable", "update_count": 1}
    assert test_state["focus_bracketing_shots"] == {"value": 250, "update_count": 1}
    assert test_state["focus_bracketing_increment"] == {"value": 7, "update_count": 1}
    assert test_state["focus_bracketing_exposure_smoothing"] == {"value": "enable", "update_count": 1}


def test_movie_settings_use_versioned_string_contracts_and_movie_mode_gate() -> None:
    discovery = client.get("/ccapi").json()
    quality = client.get("/ccapi/ver100/shooting/settings/moviequality")
    high_frame_rate = client.get("/ccapi/ver110/shooting/settings/highframerate")
    cropping = client.get("/ccapi/ver110/shooting/settings/moviecropping")
    movie_format = client.get("/ccapi/ver110/shooting/settings/movieformat")

    quality_update = client.put(
        "/ccapi/ver100/shooting/settings/moviequality",
        json={"value": "1920x1080_2997_ipb_standard"},
    )
    high_frame_rate_update = client.put(
        "/ccapi/ver110/shooting/settings/highframerate",
        json={"value": "enable"},
    )
    cropping_update = client.put(
        "/ccapi/movie-settings/cropping",
        json={"value": "enable"},
    )
    format_update = client.put(
        "/ccapi/movie-settings/format",
        json={"value": "raw"},
    )
    invalid = client.put(
        "/ccapi/ver110/shooting/settings/movieformat",
        json={"value": "mov"},
    )
    client.post("/ccapi/test/mode?mode=Bulb")
    unavailable = client.get("/ccapi/ver110/shooting/settings/moviecropping")
    unavailable_update = client.put(
        "/ccapi/movie-settings/high-frame-rate",
        json={"value": "disable"},
    )
    test_state = client.get("/ccapi/test/state").json()

    assert {"path": "/shooting/settings/moviequality", "get": True, "put": True} in discovery["ver100"]
    for path in (
        "/shooting/settings/highframerate",
        "/shooting/settings/moviecropping",
        "/shooting/settings/movieformat",
    ):
        assert {"path": path, "get": True, "put": True} in discovery["ver110"]
    assert quality.json() == {
        "value": "3840x2160_5994_ipb_standard",
        "ability": ["3840x2160_5994_ipb_standard", "1920x1080_2997_ipb_standard"],
    }
    assert high_frame_rate.json() == {"value": "disable", "ability": ["enable", "disable"]}
    assert cropping.json() == {"value": "disable", "ability": ["enable", "disable"]}
    assert movie_format.json() == {"value": "mp4", "ability": ["raw", "mp4"]}
    assert quality_update.json() == {"value": "1920x1080_2997_ipb_standard"}
    assert high_frame_rate_update.json() == {"value": "enable"}
    assert cropping_update.status_code == 204
    assert format_update.status_code == 204
    assert invalid.status_code == 400
    assert unavailable.status_code == 503
    assert unavailable_update.status_code == 503
    assert test_state["movie_quality"] == {"value": "1920x1080_2997_ipb_standard", "update_count": 1}
    assert test_state["high_frame_rate"] == {"value": "enable", "update_count": 1}
    assert test_state["movie_cropping"] == {"value": "enable", "update_count": 1}
    assert test_state["movie_format"] == {"value": "raw", "update_count": 1}


def test_movie_mode_contract_uses_on_off_action_and_mutates_both_routes() -> None:
    client.post("/ccapi/test/reset")

    initial = client.get("/ccapi/ver100/shooting/control/moviemode")
    canonical = client.post(
        "/ccapi/ver100/shooting/control/moviemode",
        json={"action": "on"},
    )
    simulator = client.post("/ccapi/movie-mode", json={"action": "off"})
    invalid = client.post(
        "/ccapi/ver100/shooting/control/moviemode",
        json={"action": "start"},
    )
    test_state = client.get("/ccapi/test/state").json()

    assert initial.json() == {"status": "off"}
    assert canonical.status_code == 204
    assert simulator.status_code == 204
    assert invalid.status_code == 422
    assert test_state["movie_mode"] == "off"
    assert test_state["movie_mode_update_count"] == 2


def test_card_selection_contract_mutates_canonical_and_simulator_routes() -> None:
    client.post("/ccapi/test/reset")

    discovery = client.get("/ccapi").json()["ver100"]
    still = client.get("/ccapi/ver100/functions/cardselection/stillimage")
    movie = client.get("/ccapi/ver100/functions/cardselection/movie")
    canonical = client.put(
        "/ccapi/ver100/functions/cardselection/stillimage",
        json={"value": "card2"},
    )
    simulator = client.put("/ccapi/card-selection/movie", json={"value": "card1"})
    invalid = client.put(
        "/ccapi/ver100/functions/cardselection/movie",
        json={"value": "card3"},
    )
    invalid_type = client.put(
        "/ccapi/ver100/functions/cardselection/movie",
        json={"value": 2},
    )
    test_state = client.get("/ccapi/test/state").json()

    assert {"path": "/functions/cardselection/stillimage", "get": True, "put": True} in discovery
    assert {"path": "/functions/cardselection/movie", "get": True, "put": True} in discovery
    assert still.json() == {"value": "card1", "ability": ["none", "card1", "card2"]}
    assert movie.json() == {"value": "card2", "ability": ["none", "card1", "card2"]}
    assert canonical.status_code == 200
    assert canonical.json() == {"value": "card2"}
    assert simulator.status_code == 204
    assert invalid.status_code == 400
    assert invalid.json() == {"message": "Invalid parameter"}
    assert invalid_type.status_code == 400
    assert invalid_type.json() == {"message": "Invalid parameter"}
    assert test_state["still_card_selection"] == "card2"
    assert test_state["movie_card_selection"] == "card1"
    assert test_state["card_selection_update_count"] == 2
