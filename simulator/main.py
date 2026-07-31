import asyncio
import struct
import zlib
from datetime import datetime

from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel, Field

app = FastAPI(title="Open EOS Control Fake Camera")


class FocusRequest(BaseModel):
    x: float = Field(ge=0.0, le=1.0)
    y: float = Field(ge=0.0, le=1.0)


class ExposureUpdate(BaseModel):
    iso: str | None = None
    shutter: str | None = None
    aperture: str | None = None


class WhiteBalanceUpdate(BaseModel):
    white_balance: str


capabilities = {
    "iso": ["100", "200", "400", "800", "1600", "3200", "6400"],
    "shutter": ["1/25", "1/50", "1/60", "1/100", "1/125"],
    "aperture": ["1.8", "2.0", "2.8", "4.0", "5.6"],
    "white_balance": ["auto", "daylight", "cloudy", "tungsten", "kelvin"],
}

def initial_state() -> dict[str, object]:
    return {
        "event_sequence": 0,
        "event_history": [],
        "recording": False,
        "record_start_count": 0,
        "record_stop_count": 0,
        "capture_count": 0,
        "clock_sync_count": 0,
        "camera_datetime": None,
        "half_pressed": False,
        "bulb_exposure_active": False,
        "focus_x": 0.5,
        "focus_y": 0.5,
        "click_wb_x": 0.5,
        "click_wb_y": 0.5,
        "click_wb_count": 0,
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
    return {
        "connected": True,
        "battery": {"level": 82, "status": "normal"},
        "recording": state["recording"],
        "bulb_exposure_active": state["bulb_exposure_active"],
        "capture_count": state["capture_count"],
        "clock_sync_count": state["clock_sync_count"],
        "camera_datetime": state["camera_datetime"],
        "mode": "movie",
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


def validate_setting(key: str, value: str) -> None:
    if value not in capabilities[key]:
        raise HTTPException(status_code=422, detail=f"Unsupported {key}: {value}")


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
        "capture_count": state["capture_count"],
        "clock_sync_count": state["clock_sync_count"],
        "camera_datetime": state["camera_datetime"],
        "half_pressed": state["half_pressed"],
        "bulb_exposure_active": state["bulb_exposure_active"],
        "focus": {"x": state["focus_x"], "y": state["focus_y"]},
        "click_white_balance": {
            "x": state["click_wb_x"],
            "y": state["click_wb_y"],
            "count": state["click_wb_count"],
        },
        "exposure": dict(state["exposure"]),
        "media_ids": [item["id"] for item in state["media"]],
    }


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
async def get_capabilities() -> dict[str, list[str]]:
    return capabilities


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
    state["capture_count"] += 1
    name = f"SIM_{state['capture_count'] + 2:04d}.PNG"
    state["media"].insert(
        0, {"id": name, "name": name, "kind": "image", "capture_time": None}
    )
    publish_event("contents")
    return {"ok": True, "capture_count": state["capture_count"]}


@app.post("/ccapi/bulb/start")
async def bulb_start() -> dict[str, bool]:
    state["bulb_exposure_active"] = True
    publish_event("shutterbutton")
    return {"ok": True, "bulb_exposure_active": True}


@app.post("/ccapi/bulb/stop")
async def bulb_stop() -> dict[str, bool]:
    state["bulb_exposure_active"] = False
    publish_event("shutterbutton")
    return {"ok": True, "bulb_exposure_active": False}


@app.post("/ccapi/shutter/half-press")
async def shutter_half_press() -> dict[str, bool]:
    state["half_pressed"] = True
    publish_event("shutterbutton")
    return {"ok": True, "half_pressed": True}


@app.post("/ccapi/shutter/release")
async def shutter_release() -> dict[str, bool]:
    state["half_pressed"] = False
    publish_event("shutterbutton")
    return {"ok": True, "half_pressed": False}


@app.post("/ccapi/focus/tap")
async def tap_focus(payload: FocusRequest) -> dict[str, float | bool]:
    state["focus_x"] = payload.x
    state["focus_y"] = payload.y
    publish_event("afframeposition")
    return {"ok": True, "x": payload.x, "y": payload.y}


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
