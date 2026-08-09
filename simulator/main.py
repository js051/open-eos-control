import asyncio
import json
import re
import struct
import zlib
from datetime import datetime
from email.utils import format_datetime
from io import BytesIO
from typing import Literal

from fastapi import FastAPI, HTTPException, Query, Response
from fastapi.responses import JSONResponse, StreamingResponse
from PIL import Image, ImageDraw
from pydantic import BaseModel, Field, StrictBool, StrictInt

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


class SensorCleaningUpdate(BaseModel):
    autopoweroff: StrictBool


class DirectoryCreateUpdate(BaseModel):
    directoryname: str = Field(default="", pattern=r"^(?:[A-Z0-9_]{5})?$")


capabilities = {
    "iso": ["100", "200", "400", "800", "1600", "3200", "6400"],
    "shutter": ["1/25", "1/50", "1/60", "1/100", "1/125"],
    "aperture": ["1.8", "2.0", "2.8", "4.0", "5.6"],
    "white_balance": ["auto", "daylight", "cloudy", "tungsten", "kelvin"],
}

ZOOM_ABILITY = {"min": 0, "max": 100, "step": 1}
SOUND_RECORDING_LEVEL_ABILITY = {"min": 0, "max": 63, "step": 1}
SOUND_RECORDING_ABILITY = ["auto", "manual", "disable"]
WIND_FILTER_ABILITY = ["auto", "enable", "disable"]
ATTENUATOR_ABILITY = ["enable", "disable", "auto", "manual"]
CARD_SELECTION_ABILITY = ["none", "card1", "card2"]
BEEP_ABILITY = ["enable", "disable", "disabletouch"]
DISPLAY_OFF_ABILITY = ["10", "20", "30", "60", "120", "180"]
AUTO_POWER_OFF_SETTING_ABILITY = ["30", "60", "120", "180", "300", "600", "disable"]
AUTO_POWER_OFF_ABILITY = [*AUTO_POWER_OFF_SETTING_ABILITY, "immediately"]
FOCUS_BRACKETING_ABILITY = ["enable", "disable"]
FOCUS_BRACKETING_SHOTS_ABILITY = {"min": 2, "max": 999, "step": 1}
FOCUS_BRACKETING_INCREMENT_ABILITY = {"min": 1, "max": 10, "step": 1}
MOVIE_QUALITY_ABILITY = [
    "3840x2160_5994_ipb_standard",
    "1920x1080_2997_ipb_standard",
]
ENABLE_DISABLE_ABILITY = ["enable", "disable"]
MOVIE_FORMAT_ABILITY = ["raw", "mp4"]
STILL_FILENAME_MODE_ABILITY = ["preset_code", "usersetting1", "usersetting2"]
MOVIE_REEL_ABILITY = {"min": 1, "max": 9999, "step": 1}
MOVIE_CLIP_ABILITY = {"min": 1, "max": 999, "step": 1}
FILE_NAMING_PATHS = {
    "stills/filename": ("still-filename-mode", "value"),
    "stills/usersetting1": ("still-user-setting-1", "usersetting1"),
    "stills/usersetting2": ("still-user-setting-2", "usersetting2"),
    "movies/index": ("movie-index", "index"),
    "movies/reelnum": ("movie-reel-number", "value"),
    "movies/clipnum": ("movie-clip-number", "value"),
    "movies/userdefined": ("movie-user-defined", "userdefined"),
}

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
        "directories": ["100EOSXX", "101EOSXX"],
        "directory_selection": "100EOSXX",
        "directory_create_count": 0,
        "directory_selection_update_count": 0,
        "still_filename_mode": "preset_code",
        "still_user_setting_1": "IMG_",
        "still_user_setting_2": "IMG",
        "movie_index": "A_",
        "movie_reel_number": 1,
        "movie_clip_number": 1,
        "movie_user_defined": "CANON",
        "file_naming_update_count": 0,
        "beep": "enable",
        "beep_update_count": 0,
        "display_off": "60",
        "display_off_update_count": 0,
        "auto_power_off": "180",
        "auto_power_off_update_count": 0,
        "camera_sleep_count": 0,
        "sensor_cleaning_count": 0,
        "sensor_cleaning_auto_power_off": False,
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
        "sound_recording_level": 32,
        "sound_recording_level_update_count": 0,
        "sound_recording": "manual",
        "sound_recording_update_count": 0,
        "wind_filter": "auto",
        "wind_filter_update_count": 0,
        "attenuator": "disable",
        "attenuator_update_count": 0,
        "focus_bracketing": "disable",
        "focus_bracketing_update_count": 0,
        "focus_bracketing_shots": 100,
        "focus_bracketing_shots_update_count": 0,
        "focus_bracketing_increment": 4,
        "focus_bracketing_increment_update_count": 0,
        "focus_bracketing_exposure_smoothing": "disable",
        "focus_bracketing_exposure_smoothing_update_count": 0,
        "movie_quality": MOVIE_QUALITY_ABILITY[0],
        "movie_quality_update_count": 0,
        "high_frame_rate": "disable",
        "high_frame_rate_update_count": 0,
        "movie_cropping": "disable",
        "movie_cropping_update_count": 0,
        "movie_format": "mp4",
        "movie_format_update_count": 0,
        "canonical_af_start_count": 0,
        "canonical_af_stop_count": 0,
        "canonical_focus_position": None,
        "canonical_click_wb_position": None,
        "canonical_live_view_active": False,
        "canonical_live_view_start_count": 0,
        "canonical_live_view_stop_count": 0,
        "canonical_multipart_active": False,
        "canonical_multipart_start_count": 0,
        "canonical_multipart_stop_count": 0,
        "canonical_multipart_frame_count": 0,
        "canonical_live_view_size_rejections": 0,
        "canonical_reject_live_view_size_once": True,
        "canonical_event_cursor": 0,
        "canonical_event_poll_count": 0,
        "canonical_event_delivery_count": 0,
        "canonical_event_delete_count": 0,
        "canonical_event_cancel_generation": 0,
        "canonical_event_active_requests": 0,
        "media_metadata_update_count": 0,
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
                "protect": False,
                "rating": 0,
                "rotate": 0,
            },
            {
                "id": "SIM_0001.PNG",
                "name": "SIM_0001.PNG",
                "kind": "image",
                "capture_time": "2026-07-21T10:00:01+08:00",
                "protect": True,
                "rating": 3,
                "rotate": 90,
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
        "directories": state["directories"],
        "directory_selection": state["directory_selection"],
        "directory_create_count": state["directory_create_count"],
        "directory_selection_update_count": state["directory_selection_update_count"],
        "file_naming": file_naming_state(),
        "file_naming_update_count": state["file_naming_update_count"],
        "beep": {
            "value": state["beep"],
            "update_count": state["beep_update_count"],
        },
        "display_off": {
            "value": state["display_off"],
            "update_count": state["display_off_update_count"],
        },
        "auto_power_off": {
            "value": state["auto_power_off"],
            "update_count": state["auto_power_off_update_count"],
        },
        "camera_sleep_count": state["camera_sleep_count"],
        "sensor_cleaning": {
            "count": state["sensor_cleaning_count"],
            "auto_power_off": state["sensor_cleaning_auto_power_off"],
        },
        "multipart_live_view": {
            "active": state["canonical_multipart_active"],
            "start_count": state["canonical_multipart_start_count"],
            "stop_count": state["canonical_multipart_stop_count"],
            "frame_count": state["canonical_multipart_frame_count"],
        },
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
        "sound_recording_level": {
            "value": state["sound_recording_level"],
            "update_count": state["sound_recording_level_update_count"],
        },
        "sound_recording": {
            "value": state["sound_recording"],
            "update_count": state["sound_recording_update_count"],
        },
        "wind_filter": {
            "value": state["wind_filter"],
            "update_count": state["wind_filter_update_count"],
        },
        "attenuator": {
            "value": state["attenuator"],
            "update_count": state["attenuator_update_count"],
        },
        "focus_bracketing": {
            "value": state["focus_bracketing"],
            "update_count": state["focus_bracketing_update_count"],
        },
        "focus_bracketing_shots": {
            "value": state["focus_bracketing_shots"],
            "update_count": state["focus_bracketing_shots_update_count"],
        },
        "focus_bracketing_increment": {
            "value": state["focus_bracketing_increment"],
            "update_count": state["focus_bracketing_increment_update_count"],
        },
        "focus_bracketing_exposure_smoothing": {
            "value": state["focus_bracketing_exposure_smoothing"],
            "update_count": state["focus_bracketing_exposure_smoothing_update_count"],
        },
        "movie_quality": {
            "value": state["movie_quality"],
            "update_count": state["movie_quality_update_count"],
        },
        "high_frame_rate": {
            "value": state["high_frame_rate"],
            "update_count": state["high_frame_rate_update_count"],
        },
        "movie_cropping": {
            "value": state["movie_cropping"],
            "update_count": state["movie_cropping_update_count"],
        },
        "movie_format": {
            "value": state["movie_format"],
            "update_count": state["movie_format_update_count"],
        },
        "exposure": dict(state["exposure"]),
        "media_ids": [item["id"] for item in state["media"]],
        "media_metadata_update_count": state["media_metadata_update_count"],
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
    result = {
        **capabilities,
        "moviemode": {"status": state["movie_mode"], "ability": ["off", "on"]},
        "zoom": {"value": state["zoom"], "ability": ZOOM_ABILITY},
        "soundrecording": {"value": state["sound_recording"], "ability": SOUND_RECORDING_ABILITY},
        "cardselectionstillimage": {
            "value": state["still_card_selection"],
            "ability": CARD_SELECTION_ABILITY,
        },
        "cardselectionmovie": {
            "value": state["movie_card_selection"],
            "ability": CARD_SELECTION_ABILITY,
        },
        "directoryselection": {
            "value": state["directory_selection"],
            "ability": state["directories"],
        },
        "fileNaming": file_naming_state(),
        "focusbracketing": {
            "value": state["focus_bracketing"],
            "ability": FOCUS_BRACKETING_ABILITY,
        },
        "focusbracketingnumberofshots": {
            "value": state["focus_bracketing_shots"],
            "ability": FOCUS_BRACKETING_SHOTS_ABILITY,
        },
        "focusbracketingfocusincrement": {
            "value": state["focus_bracketing_increment"],
            "ability": FOCUS_BRACKETING_INCREMENT_ABILITY,
        },
        "focusbracketingexposuresmoothing": {
            "value": state["focus_bracketing_exposure_smoothing"],
            "ability": FOCUS_BRACKETING_ABILITY,
        },
        "moviequality": {"value": state["movie_quality"], "ability": MOVIE_QUALITY_ABILITY},
        "highframerate": {"value": state["high_frame_rate"], "ability": ENABLE_DISABLE_ABILITY},
        "moviecropping": {"value": state["movie_cropping"], "ability": ENABLE_DISABLE_ABILITY},
        "movieformat": {"value": state["movie_format"], "ability": MOVIE_FORMAT_ABILITY},
        "beep": {"value": state["beep"], "ability": BEEP_ABILITY},
        "displayoff": {"value": state["display_off"], "ability": DISPLAY_OFF_ABILITY},
        "autopoweroff": {"value": state["auto_power_off"], "ability": AUTO_POWER_OFF_ABILITY},
    }
    if state["sound_recording"] != "disable":
        result["windfilter"] = {"value": state["wind_filter"], "ability": WIND_FILTER_ABILITY}
        result["attenuator"] = {"value": state["attenuator"], "ability": ATTENUATOR_ABILITY}
    if state["sound_recording"] == "manual":
        result["soundrecordinglevel"] = {
            "value": state["sound_recording_level"],
            "ability": SOUND_RECORDING_LEVEL_ABILITY,
        }
    return result


def create_directory(directoryname: str) -> str:
    base_name = directoryname or "EOSXX"
    next_number = max(int(item[:3]) for item in state["directories"]) + 1
    if next_number > 999:
        raise HTTPException(status_code=503, detail="No directory number is available")
    full_name = f"{next_number:03d}{base_name}"
    state["directories"].append(full_name)
    state["directory_selection"] = full_name
    state["directory_create_count"] += 1
    publish_event("directoryselection")
    return base_name


def update_directory_selection(payload: dict[str, object]) -> bool:
    value = payload.get("value")
    if set(payload) != {"value"} or not isinstance(value, str) or value not in state["directories"]:
        return False
    state["directory_selection"] = value
    state["directory_selection_update_count"] += 1
    publish_event("directoryselection")
    return True


def file_naming_state() -> dict[str, object]:
    return {
        "stillFilenameMode": state["still_filename_mode"],
        "stillFilenameModeOptions": STILL_FILENAME_MODE_ABILITY,
        "stillUserSetting1": state["still_user_setting_1"],
        "stillUserSetting2": state["still_user_setting_2"],
        "movieIndex": state["movie_index"],
        "movieReelNumber": state["movie_reel_number"],
        "movieReelRange": {
            "minimum": MOVIE_REEL_ABILITY["min"],
            "maximum": MOVIE_REEL_ABILITY["max"],
            "step": MOVIE_REEL_ABILITY["step"],
        },
        "movieClipNumber": state["movie_clip_number"],
        "movieClipRange": {
            "minimum": MOVIE_CLIP_ABILITY["min"],
            "maximum": MOVIE_CLIP_ABILITY["max"],
            "step": MOVIE_CLIP_ABILITY["step"],
        },
        "movieUserDefined": state["movie_user_defined"],
    }


def canonical_file_naming_value(field: str) -> dict[str, object]:
    if field == "still-filename-mode":
        return {"value": state["still_filename_mode"], "ability": STILL_FILENAME_MODE_ABILITY}
    if field == "movie-reel-number":
        return {"value": state["movie_reel_number"], "ability": MOVIE_REEL_ABILITY}
    if field == "movie-clip-number":
        return {"value": state["movie_clip_number"], "ability": MOVIE_CLIP_ABILITY}
    state_key = field.replace("-", "_")
    response_key = next(value[1] for value in FILE_NAMING_PATHS.values() if value[0] == field)
    return {response_key: state[state_key]}


def update_file_naming(field: str, value: object) -> bool:
    valid = False
    if field == "still-filename-mode":
        valid = isinstance(value, str) and value in STILL_FILENAME_MODE_ABILITY
    elif field == "still-user-setting-1":
        valid = isinstance(value, str) and re.fullmatch(r"[A-Z0-9][A-Z0-9_]{3}", value) is not None
    elif field == "still-user-setting-2":
        valid = isinstance(value, str) and re.fullmatch(r"[A-Z0-9][A-Z0-9_]{2}", value) is not None
    elif field == "movie-index":
        valid = isinstance(value, str) and re.fullmatch(r"[A-Z0-9][A-Z0-9_]", value) is not None
    elif field == "movie-user-defined":
        valid = isinstance(value, str) and re.fullmatch(r"[A-Z0-9]{5}", value) is not None
    elif field == "movie-reel-number":
        valid = type(value) is int and MOVIE_REEL_ABILITY["min"] <= value <= MOVIE_REEL_ABILITY["max"]
    elif field == "movie-clip-number":
        valid = type(value) is int and MOVIE_CLIP_ABILITY["min"] <= value <= MOVIE_CLIP_ABILITY["max"]
    if not valid:
        return False
    state[field.replace("-", "_")] = value
    state["file_naming_update_count"] += 1
    publish_event("filename")
    return True


@app.put("/ccapi/file-naming/{field}")
async def simulator_set_file_naming(field: str, payload: dict[str, object]) -> Response:
    value = payload.get("value")
    normalized: object = value
    if field in {"movie-reel-number", "movie-clip-number"} and isinstance(value, str) and value.isdigit():
        normalized = int(value)
    if set(payload) != {"value"} or field not in {item[0] for item in FILE_NAMING_PATHS.values()}:
        return JSONResponse(status_code=422, content={"detail": "Unsupported file-naming field"})
    if not update_file_naming(field, normalized):
        return JSONResponse(status_code=422, content={"detail": "Invalid file-naming value"})
    return JSONResponse(content=file_naming_state())


@app.post("/ccapi/directory")
async def simulator_create_directory(payload: DirectoryCreateUpdate) -> dict[str, str]:
    return {"directoryname": create_directory(payload.directoryname)}


@app.put("/ccapi/directory-selection", status_code=204)
async def simulator_select_directory(payload: dict[str, object]) -> Response:
    if not update_directory_selection(payload):
        raise HTTPException(status_code=422, detail="Unsupported directory selection")
    return Response(status_code=204)


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


def update_sound_recording_level(payload: dict[str, object]) -> bool:
    value = payload.get("value")
    if set(payload) != {"value"} or isinstance(value, bool) or not isinstance(value, int):
        return False
    if value < SOUND_RECORDING_LEVEL_ABILITY["min"] or value > SOUND_RECORDING_LEVEL_ABILITY["max"]:
        return False
    state["sound_recording_level"] = value
    state["sound_recording_level_update_count"] += 1
    publish_event("soundrecordinglevel")
    return True


def update_string_setting(
    payload: dict[str, object],
    *,
    state_key: str,
    ability: list[str],
    event_key: str,
) -> bool:
    value = payload.get("value")
    if set(payload) != {"value"} or not isinstance(value, str) or value not in ability:
        return False
    state[state_key] = value
    state[f"{state_key}_update_count"] += 1
    publish_event(event_key)
    return True


def update_integer_setting(
    payload: dict[str, object],
    *,
    state_key: str,
    ability: dict[str, int],
    event_key: str,
) -> bool:
    value = payload.get("value")
    if set(payload) != {"value"} or isinstance(value, bool) or not isinstance(value, int):
        return False
    if value < ability["min"] or value > ability["max"]:
        return False
    if (value - ability["min"]) % ability["step"] != 0:
        return False
    state[state_key] = value
    state[f"{state_key}_update_count"] += 1
    publish_event(event_key)
    return True


def focus_bracketing_unavailable() -> JSONResponse | None:
    if state["recording"]:
        return JSONResponse(status_code=503, content={"message": "During shooting or recording"})
    if state["movie_mode"] == "on":
        return JSONResponse(status_code=503, content={"message": "Mode not supported"})
    return None


def movie_setting_unavailable() -> JSONResponse | None:
    if state["recording"]:
        return JSONResponse(status_code=503, content={"message": "During shooting or recording"})
    if state["mode"] != "movie":
        return JSONResponse(status_code=503, content={"message": "Mode not supported"})
    return None


def sound_recording_unavailable(*, requires_manual: bool = False) -> JSONResponse | None:
    if state["recording"]:
        return JSONResponse(status_code=503, content={"message": "During shooting or recording"})
    if state["sound_recording"] == "disable" or (requires_manual and state["sound_recording"] != "manual"):
        return JSONResponse(status_code=503, content={"message": "Mode not supported"})
    return None


def device_function_unavailable() -> JSONResponse | None:
    if state["recording"]:
        return JSONResponse(status_code=503, content={"message": "During shooting or recording"})
    return None


@app.put("/ccapi/device-settings/beep", status_code=204)
async def update_simulator_beep(payload: dict[str, object]) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(payload, state_key="beep", ability=BEEP_ABILITY, event_key="beep"):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/device-settings/display-off", status_code=204)
async def update_simulator_display_off(payload: dict[str, object]) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="display_off",
        ability=DISPLAY_OFF_ABILITY,
        event_key="displayoff",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/device-settings/auto-power-off", status_code=204)
async def update_simulator_auto_power_off(payload: dict[str, object]) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="auto_power_off",
        ability=AUTO_POWER_OFF_SETTING_ABILITY,
        event_key="autopoweroff",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.post("/ccapi/camera-sleep", status_code=204)
async def simulator_camera_sleep() -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    state["camera_sleep_count"] += 1
    publish_event("autopoweroff")
    return Response(status_code=204)


@app.post("/ccapi/sensor-cleaning", status_code=204)
async def simulator_sensor_cleaning(payload: SensorCleaningUpdate) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    state["sensor_cleaning_count"] += 1
    state["sensor_cleaning_auto_power_off"] = payload.autopoweroff
    publish_event("sensorcleaning")
    return Response(status_code=204)


@app.put("/ccapi/sound-recording", status_code=204)
async def update_simulator_sound_recording(payload: dict[str, object]) -> Response:
    if state["recording"]:
        return JSONResponse(status_code=503, content={"message": "During shooting or recording"})
    if not update_string_setting(
        payload,
        state_key="sound_recording",
        ability=SOUND_RECORDING_ABILITY,
        event_key="soundrecording",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/wind-filter", status_code=204)
async def update_simulator_wind_filter(payload: dict[str, object]) -> Response:
    unavailable = sound_recording_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="wind_filter",
        ability=WIND_FILTER_ABILITY,
        event_key="windfilter",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/attenuator", status_code=204)
async def update_simulator_attenuator(payload: dict[str, object]) -> Response:
    unavailable = sound_recording_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="attenuator",
        ability=ATTENUATOR_ABILITY,
        event_key="attenuator",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/sound-recording-level", status_code=204)
async def update_simulator_sound_recording_level(payload: dict[str, object]) -> Response:
    unavailable = sound_recording_unavailable(requires_manual=True)
    if unavailable is not None:
        return unavailable
    if not update_sound_recording_level(payload):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/focus-bracketing", status_code=204)
async def update_simulator_focus_bracketing(payload: dict[str, object]) -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="focus_bracketing",
        ability=FOCUS_BRACKETING_ABILITY,
        event_key="focusbracketing",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/focus-bracketing/number-of-shots", status_code=204)
async def update_simulator_focus_bracketing_shots(payload: dict[str, object]) -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_integer_setting(
        payload,
        state_key="focus_bracketing_shots",
        ability=FOCUS_BRACKETING_SHOTS_ABILITY,
        event_key="focusbracketingnumberofshots",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/focus-bracketing/focus-increment", status_code=204)
async def update_simulator_focus_bracketing_increment(payload: dict[str, object]) -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_integer_setting(
        payload,
        state_key="focus_bracketing_increment",
        ability=FOCUS_BRACKETING_INCREMENT_ABILITY,
        event_key="focusbracketingfocusincrement",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


@app.put("/ccapi/focus-bracketing/exposure-smoothing", status_code=204)
async def update_simulator_focus_bracketing_smoothing(payload: dict[str, object]) -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="focus_bracketing_exposure_smoothing",
        ability=FOCUS_BRACKETING_ABILITY,
        event_key="focusbracketingexposuresmoothing",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return Response(status_code=204)


def update_movie_setting(
    payload: dict[str, object],
    *,
    state_key: str,
    ability: list[str],
    event_key: str,
) -> JSONResponse | None:
    unavailable = movie_setting_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(payload, state_key=state_key, ability=ability, event_key=event_key):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return None


@app.put("/ccapi/movie-settings/quality", status_code=204)
async def update_simulator_movie_quality(payload: dict[str, object]) -> Response:
    error = update_movie_setting(
        payload,
        state_key="movie_quality",
        ability=MOVIE_QUALITY_ABILITY,
        event_key="moviequality",
    )
    return error or Response(status_code=204)


@app.put("/ccapi/movie-settings/high-frame-rate", status_code=204)
async def update_simulator_high_frame_rate(payload: dict[str, object]) -> Response:
    error = update_movie_setting(
        payload,
        state_key="high_frame_rate",
        ability=ENABLE_DISABLE_ABILITY,
        event_key="highframerate",
    )
    return error or Response(status_code=204)


@app.put("/ccapi/movie-settings/cropping", status_code=204)
async def update_simulator_movie_cropping(payload: dict[str, object]) -> Response:
    error = update_movie_setting(
        payload,
        state_key="movie_cropping",
        ability=ENABLE_DISABLE_ABILITY,
        event_key="moviecropping",
    )
    return error or Response(status_code=204)


@app.put("/ccapi/movie-settings/format", status_code=204)
async def update_simulator_movie_format(payload: dict[str, object]) -> Response:
    error = update_movie_setting(
        payload,
        state_key="movie_format",
        ability=MOVIE_FORMAT_ABILITY,
        event_key="movieformat",
    )
    return error or Response(status_code=204)


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
        0,
        {
            "id": name,
            "name": name,
            "kind": "image",
            "capture_time": None,
            "protect": False,
            "rating": 0,
            "rotate": 0,
        },
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
    item = canonical_media_item(item_id)
    if kind == "info":
        return JSONResponse(content=media_info(item))
    if kind not in {None, "main", "thumbnail", "display"}:
        raise HTTPException(status_code=422, detail="Unsupported media representation")
    return Response(content=camera_frame_png(), media_type="image/png")


@app.put("/ccapi/media/{item_id}")
async def media_modify(item_id: str, payload: dict[str, object]) -> Response:
    item = canonical_media_item(item_id)
    update_media_metadata(item, payload)
    return JSONResponse(content={})


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
        {"path": "/shooting/settings/soundrecording", "get": True, "put": True},
        {"path": "/shooting/settings/soundrecording/level", "get": True, "put": True},
        {"path": "/shooting/settings/soundrecording/windfilter", "get": True, "put": True},
        {"path": "/shooting/settings/soundrecording/attenuator", "get": True, "put": True},
        {"path": "/shooting/settings/focusbracketing", "get": True, "put": True},
        {"path": "/shooting/settings/focusbracketing/numberofshots", "get": True, "put": True},
        {"path": "/shooting/settings/focusbracketing/focusincrement", "get": True, "put": True},
        {"path": "/shooting/settings/focusbracketing/exposuresmoothing", "get": True, "put": True},
        {"path": "/shooting/settings/moviequality", "get": True, "put": True},
        {"path": "/functions/datetime", "get": True, "put": True},
        {"path": "/functions/sensorcleaning", "post": True},
        {"path": "/functions/beep", "get": True, "put": True},
        {"path": "/functions/displayoff", "get": True, "put": True},
        {"path": "/functions/autopoweroff", "get": True, "put": True},
        {"path": "/functions/cardselection/stillimage", "get": True, "put": True},
        {"path": "/functions/cardselection/movie", "get": True, "put": True},
        {"path": "/functions/directory/createdirectory", "post": True},
        {"path": "/functions/directory/directoryselection", "get": True, "put": True},
        {"path": "/functions/filename/stills/filename", "get": True, "put": True},
        {"path": "/functions/filename/stills/usersetting1", "get": True, "put": True},
        {"path": "/functions/filename/stills/usersetting2", "get": True, "put": True},
        {"path": "/functions/filename/movies/index", "get": True, "put": True},
        {"path": "/functions/filename/movies/reelnum", "get": True, "put": True},
        {"path": "/functions/filename/movies/clipnum", "get": True, "put": True},
        {"path": "/functions/filename/movies/userdefined", "get": True, "put": True},
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
        {"path": "/shooting/liveview/multipart", "get": True, "delete": True},
        {"path": "/shooting/liveview/afframeposition", "put": True},
        {"path": "/shooting/liveview/clickwb", "post": True},
        {"path": "/contents", "get": True, "put": True, "delete": True},
    ],
    "ver110": [
        {"path": "/event/polling", "get": True, "delete": True},
        {"path": "/shooting/settings/highframerate", "get": True, "put": True},
        {"path": "/shooting/settings/moviecropping", "get": True, "put": True},
        {"path": "/shooting/settings/movieformat", "get": True, "put": True},
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
        "moviequality": {"value": state["movie_quality"], "ability": MOVIE_QUALITY_ABILITY},
        "highframerate": {"value": state["high_frame_rate"], "ability": ENABLE_DISABLE_ABILITY},
        "moviecropping": {"value": state["movie_cropping"], "ability": ENABLE_DISABLE_ABILITY},
        "movieformat": {"value": state["movie_format"], "ability": MOVIE_FORMAT_ABILITY},
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


def media_info(item: dict[str, object]) -> dict[str, object]:
    return {
        "filesize": len(camera_frame_jpeg()),
        "protect": "enable" if item.get("protect") is True else "disable",
        "archive": "disable",
        "rotate": str(item.get("rotate", 0)),
        "rating": "off" if item.get("rating", 0) == 0 else str(item["rating"]),
        "lastmodifieddate": item.get("capture_time"),
    }


def update_media_metadata(item: dict[str, object], payload: dict[str, object]) -> None:
    action = payload.get("action")
    value = payload.get("value")
    if action == "protect" and value in {"enable", "disable"}:
        item["protect"] = value == "enable"
    elif action == "rating" and value in {"off", "1", "2", "3", "4", "5"}:
        item["rating"] = 0 if value == "off" else int(value)
    elif action == "rotate" and value in {"0", "90", "180", "270"}:
        item["rotate"] = int(value)
    else:
        raise HTTPException(status_code=400, detail="Invalid media metadata parameter")
    state["media_metadata_update_count"] += 1
    publish_event("contents")


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
    if key == "soundrecording":
        if state["recording"]:
            return JSONResponse(status_code=503, content={"message": "During shooting or recording"})
        if not update_string_setting(
            payload,
            state_key="sound_recording",
            ability=SOUND_RECORDING_ABILITY,
            event_key="soundrecording",
        ):
            return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
        return JSONResponse(content={"value": state["sound_recording"]})
    if key == "focusbracketing":
        unavailable = focus_bracketing_unavailable()
        if unavailable is not None:
            return unavailable
        if not update_string_setting(
            payload,
            state_key="focus_bracketing",
            ability=FOCUS_BRACKETING_ABILITY,
            event_key="focusbracketing",
        ):
            return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
        return JSONResponse(content={"value": state["focus_bracketing"]})
    if key == "moviequality":
        error = update_movie_setting(
            payload,
            state_key="movie_quality",
            ability=MOVIE_QUALITY_ABILITY,
            event_key="moviequality",
        )
        if error is not None:
            return error
        return JSONResponse(content={"value": state["movie_quality"]})
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


@app.get("/ccapi/ver100/shooting/settings/soundrecording")
async def canon_get_sound_recording() -> dict[str, object]:
    if state["recording"]:
        raise HTTPException(status_code=503, detail="During shooting or recording")
    return {"value": state["sound_recording"], "ability": SOUND_RECORDING_ABILITY}


@app.get("/ccapi/ver100/shooting/settings/soundrecording/level")
async def canon_get_sound_recording_level() -> dict[str, object]:
    unavailable = sound_recording_unavailable(requires_manual=True)
    if unavailable is not None:
        return unavailable
    return {
        "value": state["sound_recording_level"],
        "ability": SOUND_RECORDING_LEVEL_ABILITY,
    }


@app.put("/ccapi/ver100/shooting/settings/soundrecording/level")
async def canon_set_sound_recording_level(payload: dict[str, object]) -> Response:
    unavailable = sound_recording_unavailable(requires_manual=True)
    if unavailable is not None:
        return unavailable
    if not update_sound_recording_level(payload):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["sound_recording_level"]})


@app.get("/ccapi/ver100/shooting/settings/soundrecording/windfilter")
async def canon_get_wind_filter() -> Response:
    unavailable = sound_recording_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(content={"value": state["wind_filter"], "ability": WIND_FILTER_ABILITY})


@app.put("/ccapi/ver100/shooting/settings/soundrecording/windfilter")
async def canon_set_wind_filter(payload: dict[str, object]) -> Response:
    unavailable = sound_recording_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="wind_filter",
        ability=WIND_FILTER_ABILITY,
        event_key="windfilter",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["wind_filter"]})


@app.get("/ccapi/ver100/shooting/settings/soundrecording/attenuator")
async def canon_get_attenuator() -> Response:
    unavailable = sound_recording_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(content={"value": state["attenuator"], "ability": ATTENUATOR_ABILITY})


@app.put("/ccapi/ver100/shooting/settings/soundrecording/attenuator")
async def canon_set_attenuator(payload: dict[str, object]) -> Response:
    unavailable = sound_recording_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="attenuator",
        ability=ATTENUATOR_ABILITY,
        event_key="attenuator",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["attenuator"]})


@app.get("/ccapi/ver100/shooting/settings/focusbracketing")
async def canon_get_focus_bracketing() -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(
        content={"value": state["focus_bracketing"], "ability": FOCUS_BRACKETING_ABILITY}
    )


@app.get("/ccapi/ver100/shooting/settings/focusbracketing/numberofshots")
async def canon_get_focus_bracketing_shots() -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(
        content={"value": state["focus_bracketing_shots"], "ability": FOCUS_BRACKETING_SHOTS_ABILITY}
    )


@app.put("/ccapi/ver100/shooting/settings/focusbracketing/numberofshots")
async def canon_set_focus_bracketing_shots(payload: dict[str, object]) -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_integer_setting(
        payload,
        state_key="focus_bracketing_shots",
        ability=FOCUS_BRACKETING_SHOTS_ABILITY,
        event_key="focusbracketingnumberofshots",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["focus_bracketing_shots"]})


@app.get("/ccapi/ver100/shooting/settings/focusbracketing/focusincrement")
async def canon_get_focus_bracketing_increment() -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(
        content={
            "value": state["focus_bracketing_increment"],
            "ability": FOCUS_BRACKETING_INCREMENT_ABILITY,
        }
    )


@app.put("/ccapi/ver100/shooting/settings/focusbracketing/focusincrement")
async def canon_set_focus_bracketing_increment(payload: dict[str, object]) -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_integer_setting(
        payload,
        state_key="focus_bracketing_increment",
        ability=FOCUS_BRACKETING_INCREMENT_ABILITY,
        event_key="focusbracketingfocusincrement",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["focus_bracketing_increment"]})


@app.get("/ccapi/ver100/shooting/settings/focusbracketing/exposuresmoothing")
async def canon_get_focus_bracketing_smoothing() -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(
        content={
            "value": state["focus_bracketing_exposure_smoothing"],
            "ability": FOCUS_BRACKETING_ABILITY,
        }
    )


@app.put("/ccapi/ver100/shooting/settings/focusbracketing/exposuresmoothing")
async def canon_set_focus_bracketing_smoothing(payload: dict[str, object]) -> Response:
    unavailable = focus_bracketing_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="focus_bracketing_exposure_smoothing",
        ability=FOCUS_BRACKETING_ABILITY,
        event_key="focusbracketingexposuresmoothing",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["focus_bracketing_exposure_smoothing"]})


@app.get("/ccapi/ver100/shooting/settings/moviequality")
async def canon_get_movie_quality() -> Response:
    unavailable = movie_setting_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(content={"value": state["movie_quality"], "ability": MOVIE_QUALITY_ABILITY})


@app.get("/ccapi/ver110/shooting/settings/highframerate")
async def canon_get_high_frame_rate() -> Response:
    unavailable = movie_setting_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(content={"value": state["high_frame_rate"], "ability": ENABLE_DISABLE_ABILITY})


@app.put("/ccapi/ver110/shooting/settings/highframerate")
async def canon_set_high_frame_rate(payload: dict[str, object]) -> Response:
    error = update_movie_setting(
        payload,
        state_key="high_frame_rate",
        ability=ENABLE_DISABLE_ABILITY,
        event_key="highframerate",
    )
    if error is not None:
        return error
    return JSONResponse(content={"value": state["high_frame_rate"]})


@app.get("/ccapi/ver110/shooting/settings/moviecropping")
async def canon_get_movie_cropping() -> Response:
    unavailable = movie_setting_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(content={"value": state["movie_cropping"], "ability": ENABLE_DISABLE_ABILITY})


@app.put("/ccapi/ver110/shooting/settings/moviecropping")
async def canon_set_movie_cropping(payload: dict[str, object]) -> Response:
    error = update_movie_setting(
        payload,
        state_key="movie_cropping",
        ability=ENABLE_DISABLE_ABILITY,
        event_key="moviecropping",
    )
    if error is not None:
        return error
    return JSONResponse(content={"value": state["movie_cropping"]})


@app.get("/ccapi/ver110/shooting/settings/movieformat")
async def canon_get_movie_format() -> Response:
    unavailable = movie_setting_unavailable()
    if unavailable is not None:
        return unavailable
    return JSONResponse(content={"value": state["movie_format"], "ability": MOVIE_FORMAT_ABILITY})


@app.put("/ccapi/ver110/shooting/settings/movieformat")
async def canon_set_movie_format(payload: dict[str, object]) -> Response:
    error = update_movie_setting(
        payload,
        state_key="movie_format",
        ability=MOVIE_FORMAT_ABILITY,
        event_key="movieformat",
    )
    if error is not None:
        return error
    return JSONResponse(content={"value": state["movie_format"]})


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


@app.post("/ccapi/ver100/functions/sensorcleaning")
async def canon_sensor_cleaning(payload: SensorCleaningUpdate) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    state["sensor_cleaning_count"] += 1
    state["sensor_cleaning_auto_power_off"] = payload.autopoweroff
    publish_event("sensorcleaning")
    return JSONResponse(status_code=200, content={})


@app.get("/ccapi/ver100/functions/beep")
async def canon_get_beep() -> dict[str, object]:
    return {"value": state["beep"], "ability": BEEP_ABILITY}


@app.put("/ccapi/ver100/functions/beep")
async def canon_set_beep(payload: dict[str, object]) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(payload, state_key="beep", ability=BEEP_ABILITY, event_key="beep"):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["beep"]})


@app.get("/ccapi/ver100/functions/displayoff")
async def canon_get_display_off() -> dict[str, object]:
    return {"value": state["display_off"], "ability": DISPLAY_OFF_ABILITY}


@app.put("/ccapi/ver100/functions/displayoff")
async def canon_set_display_off(payload: dict[str, object]) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    if not update_string_setting(
        payload,
        state_key="display_off",
        ability=DISPLAY_OFF_ABILITY,
        event_key="displayoff",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["display_off"]})


@app.get("/ccapi/ver100/functions/autopoweroff")
async def canon_get_auto_power_off() -> dict[str, object]:
    return {"value": state["auto_power_off"], "ability": AUTO_POWER_OFF_ABILITY}


@app.put("/ccapi/ver100/functions/autopoweroff")
async def canon_set_auto_power_off(payload: dict[str, object]) -> Response:
    unavailable = device_function_unavailable()
    if unavailable is not None:
        return unavailable
    if payload == {"value": "immediately"}:
        state["camera_sleep_count"] += 1
        publish_event("autopoweroff")
        return JSONResponse(status_code=202, content={})
    if not update_string_setting(
        payload,
        state_key="auto_power_off",
        ability=AUTO_POWER_OFF_SETTING_ABILITY,
        event_key="autopoweroff",
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["auto_power_off"]})


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


@app.post("/ccapi/ver100/functions/directory/createdirectory")
async def canon_create_directory(payload: dict[str, object]) -> Response:
    name = payload.get("directoryname")
    if (
        set(payload) != {"directoryname"}
        or not isinstance(name, str)
        or not re.fullmatch(r"(?:[A-Z0-9_]{5})?", name)
    ):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"directoryname": create_directory(name)})


@app.get("/ccapi/ver100/functions/directory/directoryselection")
async def canon_get_directory_selection() -> dict[str, object]:
    return {"value": state["directory_selection"], "ability": state["directories"]}


@app.put("/ccapi/ver100/functions/directory/directoryselection")
async def canon_set_directory_selection(payload: dict[str, object]) -> Response:
    if not update_directory_selection(payload):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={"value": state["directory_selection"]})


@app.get("/ccapi/ver100/functions/filename/{category}/{name}")
async def canon_get_file_naming(category: str, name: str) -> Response:
    definition = FILE_NAMING_PATHS.get(f"{category}/{name}")
    if definition is None:
        return JSONResponse(status_code=404, content={"message": "Not found"})
    return JSONResponse(content=canonical_file_naming_value(definition[0]))


@app.put("/ccapi/ver100/functions/filename/{category}/{name}")
async def canon_set_file_naming(category: str, name: str, payload: dict[str, object]) -> Response:
    definition = FILE_NAMING_PATHS.get(f"{category}/{name}")
    if definition is None:
        return JSONResponse(status_code=404, content={"message": "Not found"})
    field, response_key = definition
    if set(payload) != {response_key} or not update_file_naming(field, payload.get(response_key)):
        return JSONResponse(status_code=400, content={"message": "Invalid parameter"})
    return JSONResponse(content={response_key: payload[response_key]})


@app.post("/ccapi/ver100/shooting/control/shutterbutton", status_code=204)
async def canon_capture_still(payload: dict[str, object]) -> Response:
    require_temperature_allows("release")
    if payload != {"af": True}:
        raise HTTPException(status_code=422, detail="Unsupported shutter payload")
    state["capture_count"] += 1
    name = f"SIM_{state['capture_count'] + 2:04d}.JPG"
    state["media"].insert(
        0,
        {
            "id": name,
            "name": name,
            "kind": "image",
            "capture_time": None,
            "protect": False,
            "rating": 0,
            "rotate": 0,
        },
    )
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
    state["canonical_multipart_active"] = False
    state["canonical_live_view_active"] = False
    state["canonical_live_view_stop_count"] += 1
    return Response(status_code=204)


@app.get("/ccapi/ver100/shooting/liveview/multipart")
async def canon_live_view_multipart() -> StreamingResponse:
    if not state["canonical_live_view_active"]:
        return JSONResponse(status_code=503, content={"message": "Live view not started"})
    if state["canonical_multipart_active"]:
        return JSONResponse(status_code=503, content={"message": "Already started"})
    state["canonical_multipart_active"] = True
    state["canonical_multipart_start_count"] += 1

    async def frames():
        while state["canonical_multipart_active"] and state["canonical_live_view_active"]:
            jpeg = camera_frame_jpeg()
            state["canonical_multipart_frame_count"] += 1
            yield (
                b"--boundary\n"
                b"Content-Type: image/jpeg\n"
                + f"Content-Length: {len(jpeg)}\n\n".encode("ascii")
                + jpeg
                + b"\n"
            )
            await asyncio.sleep(1 / 30)

    return StreamingResponse(
        frames(),
        status_code=200,
        headers={"Content-Type": "multipart/x-mixed-replace;boundary=boundary"},
    )


@app.delete("/ccapi/ver100/shooting/liveview/multipart")
async def canon_stop_live_view_multipart() -> Response:
    if not state["canonical_multipart_active"]:
        return JSONResponse(status_code=503, content={"message": "Live view not started"})
    state["canonical_multipart_active"] = False
    state["canonical_multipart_stop_count"] += 1
    return JSONResponse(status_code=200, content={})


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
    item = canonical_media_item(item_id)
    if kind == "info":
        return JSONResponse(content=media_info(item))
    if kind not in {None, "main", "thumbnail", "display"}:
        raise HTTPException(status_code=422, detail="Unsupported media representation")
    return Response(content=camera_frame_jpeg(), media_type="image/jpeg")


@app.put("/ccapi/ver100/contents/card1/100CANON/{item_id}")
async def canon_modify_media(item_id: str, payload: dict[str, object]) -> Response:
    item = canonical_media_item(item_id)
    update_media_metadata(item, payload)
    return JSONResponse(content={})


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
