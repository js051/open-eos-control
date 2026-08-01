import asyncio
import json
import struct
import zlib
from datetime import datetime
from email.utils import format_datetime
from io import BytesIO
from typing import Literal

from fastapi import FastAPI, HTTPException, Query, Response
from fastapi.responses import JSONResponse
from PIL import Image, ImageDraw
from pydantic import BaseModel, Field, StrictInt

TemperatureStatusValue = Literal[
    "normal",
    "warning",
    "frameratedown",
    "disableliveview",
    "disablerelease",
    "stillqualitywarning",
    "restrictionmovierecording",
    "warning_and_restrictionmovierecording",
    "frameratedown_and_restrictionmovierecording",
    "disableliveview_and_restrictionmovierecording",
    "disablerelease_and_restrictionmovierecording",
    "stillqualitywarning_and_restrictionmovierecording",
]

app = FastAPI(title="Open EOS Control Fake Camera")


class FocusRequest(BaseModel):
    x: float = Field(ge=0.0, le=1.0)
    y: float = Field(ge=0.0, le=1.0)


class FocusDriveRequest(BaseModel):
    direction: Literal["near", "far"]
    step: Literal["small", "medium", "large"]


class ExposureUpdate(BaseModel):
    iso: str | None = None
    shutter: str | None = None
    aperture: str | None = None


class WhiteBalanceUpdate(BaseModel):
    white_balance: str


class ZoomUpdate(BaseModel):
    value: StrictInt = Field(ge=0, le=100)


class MovieModeUpdate(BaseModel):
    action: Literal["off", "on"]


capabilities = {
    "iso": ["100", "200", "400", "800", "1600", "3200", "6400"],
    "shutter": ["1/25", "1/50", "1/60", "1/100", "1/125"],
    "aperture": ["1.8", "2.0", "2.8", "4.0", "5.6"],
    "white_balance": ["auto", "daylight", "cloudy", "tungsten", "kelvin"],
}

ZOOM_ABILITY = {"min": 0, "max": 100, "step": 1}
CARD_SELECTION_ABILITY = ["none", "card1", "card2"]

def initial_state() -> dict[str, object]:
    return {
        "event_sequence": 0,
        "event_history": [],
        "recording": False,
        "record_start_count": 0,
        "record_stop_count": 0,
        "movie_mode": "off",
        "movie_mode_update_count": 0,
        "still_card_selection": "card1",
        "movie_card_selection": "card2",
        "card_selection_update_count": 0,
        "temperature_status": "normal",
        "capture_count": 0,
        "clock_sync_count": 0,
        "camera_datetime": None,
        "mode": "movie",
        "half_pressed": False,
        "half_press_count": 0,
        "shutter_release_count": 0,
        "bulb_exposure_active": False,
        "bulb_start_count": 0,
        "bulb_stop_count": 0,
        "focus_x": 0.5,
        "focus_y": 0.5,
        "focus_count": 0,
        "focus_drive_count": 0,
        "focus_drive_direction": None,
        "focus_drive_step": None,
        "click_wb_x": 0.5,
        "click_wb_y": 0.5,
        "click_wb_count": 0,
        "zoom": 50,
        "zoom_update_count": 0,
        "canonical_af_start_count": 0,
        "canonical_af_stop_count": 0,
        "canonical_focus_position": None,
        "canonical_click_wb_position": None,
        "canonical_live_view_active": False,
        "canonical_live_view_start_count": 0,
        "canonical_live_view_stop_count": 0,
        "canonical_live_view_size_rejections": 0,
        "canonical_reject_live_view_size_once": True,
        "canonical_event_cursor": 0,
        "canonical_event_poll_count": 0,
        "canonical_event_delivery_count": 0,
        "canonical_event_delete_count": 0,
        "canonical_event_cancel_generation": 0,
        "canonical_event_active_requests": 0,
        "canonical_datetime": {
            "datetime": format_datetime(datetime.now().astimezone()),
            "dst": False,
        },
        "exposure": {
            "iso": "800",
            "shutter": "1/50",
            "aperture": "2.8",
            "white_balance": "auto",
        },
        "media": [
            {
                "id": "SIM_0002.PNG",
                "name": "SIM_0002.PNG",
                "kind": "image",
                "capture_time": "2026-07-21T10:00:02+08:00",
            },
            {
                "id": "SIM_0001.PNG",
                "name": "SIM_0001.PNG",
                "kind": "image",
                "capture_time": "2026-07-21T10:00:01+08:00",
            },
        ],
    }


state = initial_state()


def publish_event(*keys: str) -> None:
    state["event_sequence"] += 1
    state["event_history"].append(
        {"sequence": state["event_sequence"], "keys": sorted(set(keys))}
    )
    del state["event_history"][:-64]


def camera_status() -> dict[str, object]:
    recordable_shots, remaining_recording_seconds = recordable_status()
    return {
        "connected": True,
        "battery": {"level": 82, "status": "normal"},
        "recording": state["recording"],
        "bulb_exposure_active": state["bulb_exposure_active"],
        "capture_count": state["capture_count"],
        "clock_sync_count": state["clock_sync_count"],
        "camera_datetime": state["camera_datetime"],
        "mode": state["mode"],
        "lens": {"mounted": True, "name": "RF24-105mm F4 L IS USM"},
        "temperature": state["temperature_status"],
        "recordable_shots": recordable_shots,
        "remaining_recording_seconds": remaining_recording_seconds,
        "media": {
            "available": True,
            "remaining_minutes": 120,
            "total_bytes": 128_000_000_000,
            "free_bytes": 84_000_000_000,
            "free_images": 2_418,
            "devices": 2,
        },
        "exposure": state["exposure"],
    }


def recordable_status() -> tuple[int | None, int | None]:
    movie_mode = state["movie_mode"] == "on" or bool(state["recording"])
    if movie_mode:
        return None, 7_200
    return max(0, 2_418 - int(state["capture_count"])), None


def validate_setting(key: str, value: str) -> None:
    if value not in capabilities[key]:
        raise HTTPException(status_code=422, detail=f"Unsupported {key}: {value}")


def require_temperature_allows(operation: Literal["liveview", "release", "movie"]) -> None:
    status = str(state["temperature_status"])
    token = {
        "liveview": "disableliveview",
        "release": "disablerelease",
        "movie": "restrictionmovierecording",
    }[operation]
    if token in status:
        raise HTTPException(status_code=409, detail="Camera temperature restriction")


@app.get("/health")
async def health() -> dict[str, bool | str]:
    return {"ok": True, "service": "open-eos-control-simulator"}


@app.post("/ccapi/test/reset")
async def reset_test_state() -> dict[str, bool]:
    state.clear()
    state.update(initial_state())
    return {"ok": True}


@app.get("/ccapi/test/state")
async def get_test_state() -> dict[str, object]:
    return {
        "recording": state["recording"],
        "record_start_count": state["record_start_count"],
        "record_stop_count": state["record_stop_count"],
        "movie_mode": state["movie_mode"],
        "movie_mode_update_count": state["movie_mode_update_count"],
        "still_card_selection": state["still_card_selection"],
        "movie_card_selection": state["movie_card_selection"],
        "card_selection_update_count": state["card_selection_update_count"],
        "temperature_status": state["temperature_status"],
        "capture_count": state["capture_count"],
        "clock_sync_count": state["clock_sync_count"],
        "camera_datetime": state["camera_datetime"],
        "mode": state["mode"],
        "half_pressed": state["half_pressed"],
        "half_press_count": state["half_press_count"],
        "shutter_release_count": state["shutter_release_count"],
        "bulb_exposure_active": state["bulb_exposure_active"],
        "bulb_start_count": state["bulb_start_count"],
        "bulb_stop_count": state["bulb_stop_count"],
        "focus": {
            "x": state["focus_x"],
            "y": state["focus_y"],
            "count": state["focus_count"],
        },
        "focus_drive": {
            "count": state["focus_drive_count"],
            "direction": state["focus_drive_direction"],
            "step": state["focus_drive_step"],
        },
        "click_white_balance": {
            "x": state["click_wb_x"],
            "y": state["click_wb_y"],
            "count": state["click_wb_count"],
        },
        "zoom": {
            "value": state["zoom"],
            "update_count": state["zoom_update_count"],
        },
        "exposure": dict(state["exposure"]),
        "media_ids": [item["id"] for item in state["media"]],
        "canonical": {
            "af_start_count": state["canonical_af_start_count"],
            "af_stop_count": state["canonical_af_stop_count"],
            "focus_position": state["canonical_focus_position"],
            "click_wb_position": state["canonical_click_wb_position"],
            "live_view_active": state["canonical_live_view_active"],
            "live_view_start_count": state["canonical_live_view_start_count"],
            "live_view_stop_count": state["canonical_live_view_stop_count"],
            "live_view_size_rejections": state["canonical_live_view_size_rejections"],
            "event_cursor": state["canonical_event_cursor"],
            "event_poll_count": state["canonical_event_poll_count"],
            "event_delivery_count": state["canonical_event_delivery_count"],
            "event_delete_count": state["canonical_event_delete_count"],
            "event_active_requests": state["canonical_event_active_requests"],
        },
    }


@app.post("/ccapi/test/mode")
async def set_test_mode(mode: Literal["movie", "Bulb"]) -> dict[str, object]:
    state["mode"] = mode
    publish_event("shootingsettings")
    return camera_status()


@app.post("/ccapi/test/temperature")
async def set_test_temperature(status: TemperatureStatusValue) -> dict[str, str]:
    state["temperature_status"] = status
    publish_event("temperature")
    return {"status": status}


@app.get("/ccapi/info")
async def info() -> dict[str, bool | str]:
    return {
        "connected": True,
        "model": "Canon EOS R6 Mark III",
        "serial": "sim-r6m3",
        "api": "simulated-ccapi",
    }


@app.get("/ccapi/status")
async def status() -> dict[str, object]:
    return camera_status()


@app.get("/ccapi/events")
async def events(after: int = 0) -> dict[str, object]:
    for _ in range(20):
        matching = [event for event in state["event_history"] if event["sequence"] > after]
        if matching:
            return {
                "sequence": state["event_sequence"],
                "keys": sorted({key for event in matching for key in event["keys"]}),
            }
        await asyncio.sleep(0.05)
    return {"sequence": state["event_sequence"], "keys": []}


@app.get("/ccapi/capabilities")
async def get_capabilities() -> dict[str, object]:
    return {
        **capabilities,
        "moviemode": {"status": state["movie_mode"], "ability": ["off", "on"]},
        "zoom": {"value": state["zoom"], "ability": ZOOM_ABILITY},
        "cardselectionstillimage": {
            "value": state["still_card_selection"],
            "ability": CARD_SELECTION_ABILITY,
        },
        "cardselectionmovie": {
            "value": state["movie_card_selection"],
            "ability": CARD_SELECTION_ABILITY,
        },
    }


@app.post("/ccapi/movie-mode", status_code=204)
async def update_movie_mode(payload: MovieModeUpdate) -> Response:
    state["movie_mode"] = payload.action
    state["movie_mode_update_count"] += 1
    publish_event("moviemode")
    return Response(status_code=204)


@app.post("/ccapi/zoom")
async def update_zoom(payload: ZoomUpdate) -> dict[str, object]:
    state["zoom"] = payload.value
    state["zoom_update_count"] += 1
    publish_event("zoom")
    return {"value": state["zoom"]}


def update_card_selection(kind: Literal["stillimage", "movie"], payload: dict[str, object]) -> bool:
    value = payload.get("value")
    if set(payload) != {"value"} or not isinstance(value, str) or value not in CARD_SELECTION_ABILITY:
        return False
    state_key = "still_card_selection" if kind == "stillimage" else "movie_card_selection"
    state[state_key] = value
    state["card_selection_update_count"] += 1
    publish_event(f"cardselection{kind}")
    return True


@app.put("/ccapi/card-selection/{kind}", status_code=204)
async def update_simulator_card_selection(
    kind: Literal["stillimage", "movie"],
    payload: dict[str, object],
) -> Response:
    if not update_card_selection(kind, payload):
        return JSONResponse(status_code=400, content={"detail": "Unsupported card-selection value"})
    return Response(status_code=204)


@app.patch("/ccapi/exposure")
async def update_exposure(payload: ExposureUpdate) -> dict[str, object]:
    for key, value in payload.model_dump(exclude_none=True).items():
        validate_setting(key, value)
        state["exposure"][key] = value
    publish_event("shootingsettings")
    return camera_status()


@app.patch("/ccapi/white-balance")
async def update_white_balance(payload: WhiteBalanceUpdate) -> dict[str, object]:
    validate_setting("white_balance", payload.white_balance)
    state["exposure"]["white_balance"] = payload.white_balance
    publish_event("shootingsettings")
    return camera_status()


@app.post("/ccapi/clock/sync")
async def sync_camera_clock() -> dict[str, object]:
    state["clock_sync_count"] += 1
    state["camera_datetime"] = datetime.now().astimezone().isoformat(timespec="seconds")
    publish_event("datetime")
    return camera_status()


@app.post("/ccapi/record/start")
async def record_start() -> dict[str, bool]:
    require_temperature_allows("movie")
    state["record_start_count"] += 1
    state["recording"] = True
    publish_event("recbutton")
    return {"ok": True, "recording": True}


@app.post("/ccapi/record/stop")
async def record_stop() -> dict[str, bool]:
    state["record_stop_count"] += 1
    state["recording"] = False
    publish_event("recbutton")
    return {"ok": True, "recording": False}


@app.post("/ccapi/capture/still")
async def capture_still() -> dict[str, bool | int]:
    require_temperature_allows("release")
    state["capture_count"] += 1
    name = f"SIM_{state['capture_count'] + 2:04d}.PNG"
    state["media"].insert(
        0, {"id": name, "name": name, "kind": "image", "capture_time": None}
    )
    publish_event("contents")
    return {"ok": True, "capture_count": state["capture_count"]}


@app.post("/ccapi/bulb/start")
async def bulb_start() -> dict[str, bool]:
    require_temperature_allows("release")
    if state["mode"] != "Bulb":
        raise HTTPException(status_code=409, detail="Camera is not in Bulb mode")
    state["bulb_start_count"] += 1
    state["bulb_exposure_active"] = True
    publish_event("shutterbutton")
    return {"ok": True, "bulb_exposure_active": True}


@app.post("/ccapi/bulb/stop")
async def bulb_stop() -> dict[str, bool]:
    state["bulb_stop_count"] += 1
    state["bulb_exposure_active"] = False
    publish_event("shutterbutton")
    return {"ok": True, "bulb_exposure_active": False}


@app.post("/ccapi/shutter/half-press")
async def shutter_half_press() -> dict[str, bool]:
    state["half_press_count"] += 1
    state["half_pressed"] = True
    publish_event("shutterbutton")
    return {"ok": True, "half_pressed": True}


@app.post("/ccapi/shutter/release")
async def shutter_release() -> dict[str, bool]:
    state["shutter_release_count"] += 1
    state["half_pressed"] = False
    publish_event("shutterbutton")
    return {"ok": True, "half_pressed": False}


@app.post("/ccapi/focus/tap")
async def tap_focus(payload: FocusRequest) -> dict[str, float | bool]:
    state["focus_x"] = payload.x
    state["focus_y"] = payload.y
    state["focus_count"] += 1
    publish_event("afframeposition")
    return {"ok": True, "x": payload.x, "y": payload.y}


@app.post("/ccapi/focus/drive")
async def drive_focus(payload: FocusDriveRequest) -> dict[str, object]:
    state["focus_drive_count"] += 1
    state["focus_drive_direction"] = payload.direction
    state["focus_drive_step"] = payload.step
    publish_event("focus")
    return {
        "ok": True,
        "direction": payload.direction,
        "step": payload.step,
    }


@app.post("/ccapi/whitebalance/click")
async def click_white_balance(payload: FocusRequest) -> dict[str, object]:
    state["click_wb_x"] = payload.x
    state["click_wb_y"] = payload.y
    state["click_wb_count"] += 1
    state["exposure"]["white_balance"] = "click"
    publish_event("shootingsettings")
    return camera_status()


@app.get("/ccapi/media")
async def media_list() -> dict[str, list[dict[str, object]]]:
    frame_size = len(camera_frame_png())
    return {
        "items": [dict(item, size_bytes=frame_size) for item in state["media"]],
    }


@app.get("/ccapi/media/{item_id}")
async def media_download(item_id: str, kind: str | None = None) -> Response:
    if not any(item["id"] == item_id for item in state["media"]):
        raise HTTPException(status_code=404, detail="Media item not found")
    if kind not in {None, "main", "thumbnail", "display"}:
        raise HTTPException(status_code=422, detail="Unsupported media representation")
    return Response(content=camera_frame_png(), media_type="image/png")


@app.delete("/ccapi/media/{item_id}", status_code=204)
async def media_delete(item_id: str) -> Response:
    item = next(
        (candidate for candidate in state["media"] if candidate["id"] == item_id), None
    )
    if item is None:
        raise HTTPException(status_code=404, detail="Media item not found")
    state["media"].remove(item)
    publish_event("contents")
    return Response(status_code=204)


@app.get("/ccapi/liveview/frame")
async def liveview_frame() -> Response:
    return Response(content=camera_frame_png(), media_type="image/png")


CANON_LIVE_VIEW_GEOMETRY = {
    "positionx": 100,
    "positiony": 200,
    "positionwidth": 6000,
    "positionheight": 4000,
}

CANON_DISCOVERY = {
    "ver100": [
        {"path": "/deviceinformation", "get": True},
        {"path": "/devicestatus/batterylist", "get": True},
        {"path": "/devicestatus/storage", "get": True},
        {"path": "/shooting/information/recordable", "get": True},
        {"path": "/devicestatus/lens", "get": True},
        {"path": "/devicestatus/temperature", "get": True},
        {"path": "/shooting/settings", "get": True},
        {"path": "/shooting/settings/iso", "put": True},
        {"path": "/shooting/settings/tv", "put": True},
        {"path": "/shooting/settings/av", "put": True},
        {"path": "/shooting/settings/wb", "put": True},
        {"path": "/shooting/settings/shootingmode", "put": True},
        {"path": "/functions/datetime", "get": True, "put": True},
        {"path": "/functions/cardselection/stillimage", "get": True, "put": True},
        {"path": "/functions/cardselection/movie", "get": True, "put": True},
        {"path": "/shooting/control/shutterbutton", "post": True},
        {"path": "/shooting/control/shutterbutton/manual", "put": True},
        {"path": "/shooting/control/af", "post": True},
        {"path": "/shooting/control/recbutton", "post": True},
        {"path": "/shooting/control/moviemode", "get": True, "post": True},
        {"path": "/shooting/control/drivefocus", "post": True},
        {"path": "/shooting/control/zoom", "get": True, "post": True},
        {"path": "/shooting/liveview", "post": True, "delete": True},
        {"path": "/shooting/liveview/flip", "get": True},
        {"path": "/shooting/liveview/flipdetail", "get": True},
        {"path": "/shooting/liveview/afframeposition", "put": True},
        {"path": "/shooting/liveview/clickwb", "post": True},
        {"path": "/contents", "get": True, "delete": True},
    ],
    "ver110": [
        {"path": "/event/polling", "get": True, "delete": True},
    ],
}


def canonical_settings() -> dict[str, dict[str, object]]:
    exposure = state["exposure"]
    assert isinstance(exposure, dict)
    return {
        "iso": {"value": exposure["iso"], "ability": capabilities["iso"]},
        "tv": {"value": exposure["shutter"], "ability": capabilities["shutter"]},
        "av": {"value": exposure["aperture"], "ability": capabilities["aperture"]},
        "wb": {"value": exposure["white_balance"], "ability": capabilities["white_balance"]},
        "shootingmode": {
            "value": state["mode"],
            "ability": ["Manual", "Bulb", "movie"],
        },
    }


def canonical_media_path(item_id: str) -> str:
    return f"/ccapi/ver100/contents/card1/100CANON/{item_id}"


def canonical_media_item(item_id: str) -> dict[str, object]:
    item = next((candidate for candidate in state["media"] if candidate["id"] == item_id), None)
    if item is None:
        raise HTTPException(status_code=404, detail="Media item not found")
    return item


@app.get("/ccapi")
async def canon_discovery() -> dict[str, list[dict[str, object]]]:
    return CANON_DISCOVERY


@app.get("/ccapi/ver100/deviceinformation")
async def canon_device_information() -> dict[str, str]:
    return {
        "productname": "Canon EOS R6 Mark III",
        "serialnumber": "SIMULATOR-SERIAL",
        "version": "1.0.0-simulator",
        "manufacturer": "Canon",
        "firmwareversion": "simulator",
    }


@app.get("/ccapi/ver110/event/polling")
async def canon_poll_event(
    timeout: Literal["immediately", "short", "long"] = "long",
    continue_mode: str | None = Query(default=None, alias="continue"),
) -> dict[str, object]:
    if continue_mode is not None:
        raise HTTPException(status_code=422, detail="continue is only valid for CCAPI 1.0")
    state["canonical_event_poll_count"] += 1
    state["canonical_event_active_requests"] += 1
    cancel_generation = state["canonical_event_cancel_generation"]
    attempts = {"immediately": 1, "short": 20, "long": 600}[timeout]
    try:
        for _ in range(attempts):
            matching = [
                event
                for event in state["event_history"]
                if event["sequence"] > state["canonical_event_cursor"]
            ]
            if matching:
                state["canonical_event_cursor"] = matching[-1]["sequence"]
                state["canonical_event_delivery_count"] += 1
                keys = sorted({key for event in matching for key in event["keys"]})
                return {key: {} for key in keys}
            if state["canonical_event_cancel_generation"] != cancel_generation:
                return {}
            await asyncio.sleep(0.05)
        return {}
    finally:
        state["canonical_event_active_requests"] -= 1


@app.delete("/ccapi/ver110/event/polling", status_code=204)
async def canon_stop_event_polling() -> Response:
    state["canonical_event_delete_count"] += 1
    state["canonical_event_cancel_generation"] += 1
    return Response(status_code=204)


@app.get("/ccapi/ver100/devicestatus/batterylist")
async def canon_battery_list() -> dict[str, list[dict[str, object]]]:
    return {"batterylist": [{"name": "LP-E6P", "level": 82, "quality": "good"}]}


@app.get("/ccapi/ver100/devicestatus/storage")
async def canon_storage() -> dict[str, list[dict[str, object]]]:
    return {
        "storagelist": [
            {
                "name": "card1",
                "maxsize": 128_000_000_000,
                "spacesize": 84_000_000_000,
                "remainingshots": 2_418,
            }
        ]
    }


@app.get("/ccapi/ver100/shooting/information/recordable")
async def canon_recordable_information() -> dict[str, int | None]:
    shots, remaining_time = recordable_status()
    return {"recordableshots": shots, "remainingtime": remaining_time}


@app.get("/ccapi/ver100/devicestatus/lens")
async def canon_lens() -> dict[str, object]:
    return {"mount": True, "name": "RF24-105mm F4 L IS USM"}


@app.get("/ccapi/ver100/devicestatus/temperature")
async def canon_temperature() -> dict[str, str]:
    return {"status": str(state["temperature_status"])}


@app.get("/ccapi/ver100/shooting/settings")
async def canon_shooting_settings() -> dict[str, dict[str, object]]:
    return canonical_settings()


@app.put("/ccapi/ver100/shooting/settings/{key}", status_code=204)
async def canon_set_shooting_setting(key: str, payload: dict[str, object]) -> Response:
    value = payload.get("value")
    if not isinstance(value, str):
        raise HTTPException(status_code=422, detail="Setting value must be a string")
    if key == "shootingmode":
        if value not in {"Manual", "Bulb", "movie"}:
            raise HTTPException(status_code=422, detail=f"Unsupported shootingmode: {value}")
        state["mode"] = value
    else:
        setting_map = {
            "iso": ("iso", "iso"),
            "tv": ("shutter", "shutter"),
            "av": ("aperture", "aperture"),
            "wb": ("white_balance", "white_balance"),
        }
        mapped = setting_map.get(key)
        if mapped is None:
            raise HTTPException(status_code=404, detail="Setting not found")
        state_key, capability_key = mapped
        validate_setting(capability_key, value)
        exposure = state["exposure"]
        assert isinstance(exposure, dict)
        exposure[state_key] = value
    publish_event("shootingsettings")
    return Response(status_code=204)


@app.get("/ccapi/ver100/functions/datetime")
async def canon_get_datetime() -> dict[str, object]:
    value = state["canonical_datetime"]
    assert isinstance(value, dict)
    return value


@app.put("/ccapi/ver100/functions/datetime")
async def canon_set_datetime(payload: dict[str, object]) -> dict[str, object]:
    if not isinstance(payload.get("datetime"), str) or not isinstance(payload.get("dst"), bool):
        raise HTTPException(status_code=422, detail="datetime and dst are required")
    state["canonical_datetime"] = dict(payload)
    state["camera_datetime"] = payload["datetime"]
    state["clock_sync_count"] += 1
    publish_event("datetime")
    return dict(payload)


@app.get("/ccapi/ver100/functions/cardselection/stillimage")
async def canon_get_still_card_selection() -> dict[str, object]:
    return {"value": state["still_card_selection"], "ability": CARD_SELECTION_ABILITY}


@app.put("/ccapi/ver100/functions/cardselection/stillimage")
async def canon_set_still_card_selection(payload: dict[str, object]) -> Response:
    if not update_card_selection("stillimage", payload):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["still_card_selection"]})


@app.get("/ccapi/ver100/functions/cardselection/movie")
async def canon_get_movie_card_selection() -> dict[str, object]:
    return {"value": state["movie_card_selection"], "ability": CARD_SELECTION_ABILITY}


@app.put("/ccapi/ver100/functions/cardselection/movie")
async def canon_set_movie_card_selection(payload: dict[str, object]) -> Response:
    if not update_card_selection("movie", payload):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["movie_card_selection"]})


@app.post("/ccapi/ver100/shooting/control/shutterbutton", status_code=204)
async def canon_capture_still(payload: dict[str, object]) -> Response:
    require_temperature_allows("release")
    if payload != {"af": True}:
        raise HTTPException(status_code=422, detail="Unsupported shutter payload")
    state["capture_count"] += 1
    name = f"SIM_{state['capture_count'] + 2:04d}.JPG"
    state["media"].insert(0, {"id": name, "name": name, "kind": "image", "capture_time": None})
    publish_event("contents")
    return Response(status_code=204)


@app.put("/ccapi/ver100/shooting/control/shutterbutton/manual", status_code=204)
async def canon_manual_shutter(payload: dict[str, object]) -> Response:
    action = payload.get("action")
    autofocus = payload.get("af")
    if action == "half_press" and autofocus is True:
        state["half_pressed"] = True
        state["half_press_count"] += 1
    elif action == "full_press" and autofocus is False:
        require_temperature_allows("release")
        if state["mode"] != "Bulb":
            raise HTTPException(status_code=409, detail="Camera is not in Bulb mode")
        state["bulb_exposure_active"] = True
        state["bulb_start_count"] += 1
    elif action == "release" and autofocus is False:
        if state["bulb_exposure_active"]:
            state["bulb_exposure_active"] = False
            state["bulb_stop_count"] += 1
        else:
            state["half_pressed"] = False
            state["shutter_release_count"] += 1
    else:
        raise HTTPException(status_code=422, detail="Unsupported manual shutter payload")
    publish_event("shutterbutton")
    return Response(status_code=204)


@app.post("/ccapi/ver100/shooting/control/af", status_code=204)
async def canon_autofocus(payload: dict[str, object]) -> Response:
    action = payload.get("action")
    if action == "start":
        state["canonical_af_start_count"] += 1
    elif action == "stop":
        state["canonical_af_stop_count"] += 1
    else:
        raise HTTPException(status_code=422, detail="Unsupported autofocus action")
    return Response(status_code=204)


@app.post("/ccapi/ver100/shooting/control/recbutton", status_code=204)
async def canon_recording(payload: dict[str, object]) -> Response:
    action = payload.get("action")
    if action == "start":
        require_temperature_allows("movie")
        state["recording"] = True
        state["record_start_count"] += 1
    elif action == "stop":
        state["recording"] = False
        state["record_stop_count"] += 1
    else:
        raise HTTPException(status_code=422, detail="Unsupported recording action")
    publish_event("recbutton")
    return Response(status_code=204)


@app.get("/ccapi/ver100/shooting/control/moviemode")
async def canon_get_movie_mode() -> dict[str, str]:
    return {"status": state["movie_mode"]}


@app.post("/ccapi/ver100/shooting/control/moviemode", status_code=204)
async def canon_set_movie_mode(payload: MovieModeUpdate) -> Response:
    state["movie_mode"] = payload.action
    state["movie_mode_update_count"] += 1
    publish_event("moviemode")
    return Response(status_code=204)


@app.post("/ccapi/ver100/shooting/control/drivefocus", status_code=204)
async def canon_drive_focus(payload: dict[str, object]) -> Response:
    value = payload.get("value")
    valid_values = {f"{direction}{step}" for direction in ("near", "far") for step in (1, 2, 3)}
    if not isinstance(value, str) or value not in valid_values:
        raise HTTPException(status_code=422, detail="Unsupported focus drive value")
    state["focus_drive_count"] += 1
    state["focus_drive_direction"] = value[:-1]
    state["focus_drive_step"] = {"1": "small", "2": "medium", "3": "large"}[value[-1]]
    return Response(status_code=204)


@app.get("/ccapi/ver100/shooting/control/zoom")
async def canon_get_zoom() -> dict[str, object]:
    return {"value": state["zoom"], "ability": ZOOM_ABILITY}


@app.post("/ccapi/ver100/shooting/control/zoom")
async def canon_set_zoom(payload: ZoomUpdate) -> dict[str, int]:
    state["zoom"] = payload.value
    state["zoom_update_count"] += 1
    publish_event("zoom")
    return {"value": payload.value}


@app.put("/ccapi/ver100/shooting/liveview/afframeposition", status_code=204)
async def canon_tap_focus(payload: dict[str, object]) -> Response:
    x = payload.get("positionx")
    y = payload.get("positiony")
    if isinstance(x, bool) or not isinstance(x, int) or isinstance(y, bool) or not isinstance(y, int):
        raise HTTPException(status_code=422, detail="Integer focus coordinates are required")
    state["canonical_focus_position"] = {"x": x, "y": y}
    state["focus_x"] = (x - CANON_LIVE_VIEW_GEOMETRY["positionx"]) / CANON_LIVE_VIEW_GEOMETRY["positionwidth"]
    state["focus_y"] = (y - CANON_LIVE_VIEW_GEOMETRY["positiony"]) / CANON_LIVE_VIEW_GEOMETRY["positionheight"]
    state["focus_count"] += 1
    return Response(status_code=204)


@app.post("/ccapi/ver100/shooting/liveview/clickwb", status_code=204)
async def canon_click_white_balance(payload: dict[str, object]) -> Response:
    x = payload.get("positionx")
    y = payload.get("positiony")
    if isinstance(x, bool) or not isinstance(x, int) or isinstance(y, bool) or not isinstance(y, int):
        raise HTTPException(status_code=422, detail="Integer white-balance coordinates are required")
    state["canonical_click_wb_position"] = {"x": x, "y": y}
    state["click_wb_count"] += 1
    exposure = state["exposure"]
    assert isinstance(exposure, dict)
    exposure["white_balance"] = "click"
    return Response(status_code=204)


@app.post("/ccapi/ver100/shooting/liveview", status_code=204)
async def canon_start_live_view(payload: dict[str, object]) -> Response:
    require_temperature_allows("liveview")
    if payload.get("cameradisplay") != "on":
        raise HTTPException(status_code=422, detail="Camera display must be enabled")
    if "liveviewsize" in payload and state["canonical_reject_live_view_size_once"]:
        state["canonical_reject_live_view_size_once"] = False
        state["canonical_live_view_size_rejections"] += 1
        raise HTTPException(status_code=400, detail="Invalid parameter")
    state["canonical_live_view_active"] = True
    state["canonical_live_view_start_count"] += 1
    return Response(status_code=204)


@app.delete("/ccapi/ver100/shooting/liveview", status_code=204)
async def canon_stop_live_view() -> Response:
    state["canonical_live_view_active"] = False
    state["canonical_live_view_stop_count"] += 1
    return Response(status_code=204)


@app.get("/ccapi/ver100/shooting/liveview/flip")
async def canon_live_view_flip() -> Response:
    if not state["canonical_live_view_active"]:
        raise HTTPException(status_code=409, detail="Live View is not active")
    jpeg = camera_frame_jpeg()
    body = b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + jpeg + b"\r\n--frame\r\n"
    return Response(content=body, media_type="multipart/x-mixed-replace; boundary=frame")


@app.get("/ccapi/ver100/shooting/liveview/flipdetail")
async def canon_live_view_flip_detail(kind: str = "image") -> Response:
    if not state["canonical_live_view_active"]:
        raise HTTPException(status_code=409, detail="Live View is not active")
    jpeg = camera_frame_jpeg()
    if kind == "both":
        info = {"liveview": {"image": CANON_LIVE_VIEW_GEOMETRY}}
        return Response(content=detailed_live_view(jpeg, info), media_type="application/octet-stream")
    if kind != "image":
        raise HTTPException(status_code=422, detail="Unsupported detailed Live View kind")
    return Response(content=jpeg, media_type="image/jpeg")


@app.get("/ccapi/ver100/contents")
async def canon_contents(
    kind: str | None = None,
    page: int | None = None,
    order: str | None = None,
) -> dict[str, object]:
    del order
    if kind == "number":
        return {"pagenumber": 1}
    if page not in {None, 1}:
        return {"path": []}
    return {"path": [canonical_media_path(item["id"]) for item in state["media"]]}


@app.get("/ccapi/ver100/contents/card1/100CANON/{item_id}")
async def canon_media(item_id: str, kind: str | None = None) -> Response:
    canonical_media_item(item_id)
    if kind not in {None, "main", "thumbnail", "display"}:
        raise HTTPException(status_code=422, detail="Unsupported media representation")
    return Response(content=camera_frame_jpeg(), media_type="image/jpeg")


@app.delete("/ccapi/ver100/contents/card1/100CANON/{item_id}", status_code=204)
async def canon_delete_media(item_id: str) -> Response:
    item = canonical_media_item(item_id)
    state["media"].remove(item)
    publish_event("contents")
    return Response(status_code=204)


def camera_frame_jpeg() -> bytes:
    image = Image.new("RGB", (960, 540), color=(17, 24, 39))
    drawing = ImageDraw.Draw(image)
    drawing.rectangle((52, 52, 907, 487), fill=(31, 41, 55), outline=(100, 116, 139), width=2)
    focus_x = int(float(state["focus_x"]) * image.width)
    focus_y = int(float(state["focus_y"]) * image.height)
    drawing.ellipse((focus_x - 46, focus_y - 46, focus_x + 46, focus_y + 46), outline=(57, 197, 207), width=6)
    status_color = (233, 75, 75) if state["recording"] else (88, 199, 123)
    drawing.ellipse((72, 72, 92, 92), fill=status_color)
    output = BytesIO()
    image.save(output, format="JPEG", quality=88, optimize=True)
    return output.getvalue()


def detailed_live_view(jpeg: bytes, info: object) -> bytes:
    return detail_packet(0x00, jpeg) + detail_packet(0x01, json.dumps(info).encode())


def detail_packet(data_type: int, payload: bytes) -> bytes:
    return b"\xff\x00" + bytes([data_type]) + len(payload).to_bytes(4, "big") + payload + b"\xff\xff"


def camera_frame_png() -> bytes:
    width, height = 960, 540
    stride = 1 + width * 3
    pixels = bytearray()
    background_row = bytes((17, 24, 39)) * width
    for _ in range(height):
        pixels.append(0)
        pixels.extend(background_row)

    def set_pixel(x: int, y: int, color: tuple[int, int, int]) -> None:
        if 0 <= x < width and 0 <= y < height:
            offset = y * stride + 1 + x * 3
            pixels[offset : offset + 3] = bytes(color)

    inner_row = bytes((31, 41, 55)) * 856
    for y in range(52, 488):
        offset = y * stride + 1 + 52 * 3
        pixels[offset : offset + len(inner_row)] = inner_row

    border = (100, 116, 139)
    for x in range(52, 908):
        set_pixel(x, 52, border)
        set_pixel(x, 487, border)
    for y in range(52, 488):
        set_pixel(52, y, border)
        set_pixel(907, y, border)

    focus_x = int(float(state["focus_x"]) * width)
    focus_y = int(float(state["focus_y"]) * height)
    focus_color = (57, 197, 207)
    for y in range(focus_y - 48, focus_y + 49):
        for x in range(focus_x - 48, focus_x + 49):
            distance_squared = (x - focus_x) ** 2 + (y - focus_y) ** 2
            if 40**2 <= distance_squared <= 46**2:
                set_pixel(x, y, focus_color)

    status_color = (233, 75, 75) if state["recording"] else (88, 199, 123)
    for y in range(72, 92):
        for x in range(72, 92):
            set_pixel(x, y, status_color)

    def chunk(kind: bytes, payload: bytes) -> bytes:
        checksum = zlib.crc32(kind + payload) & 0xFFFFFFFF
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", checksum)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(pixels), 6))
        + chunk(b"IEND", b"")
    )
