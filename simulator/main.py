import struct
import zlib

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

state = {
    "recording": False,
    "capture_count": 0,
    "focus_x": 0.5,
    "focus_y": 0.5,
    "exposure": {
        "iso": "800",
        "shutter": "1/50",
        "aperture": "2.8",
        "white_balance": "auto",
    },
}


def camera_status() -> dict[str, object]:
    return {
        "connected": True,
        "battery": {"level": 82, "status": "normal"},
        "recording": state["recording"],
        "capture_count": state["capture_count"],
        "mode": "movie",
        "media": {"available": True, "remaining_minutes": 120},
        "exposure": state["exposure"],
    }


def validate_setting(key: str, value: str) -> None:
    if value not in capabilities[key]:
        raise HTTPException(status_code=422, detail=f"Unsupported {key}: {value}")


@app.get("/health")
async def health() -> dict[str, bool | str]:
    return {"ok": True, "service": "open-eos-control-simulator"}


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


@app.get("/ccapi/capabilities")
async def get_capabilities() -> dict[str, list[str]]:
    return capabilities


@app.patch("/ccapi/exposure")
async def update_exposure(payload: ExposureUpdate) -> dict[str, object]:
    for key, value in payload.model_dump(exclude_none=True).items():
        validate_setting(key, value)
        state["exposure"][key] = value
    return camera_status()


@app.patch("/ccapi/white-balance")
async def update_white_balance(payload: WhiteBalanceUpdate) -> dict[str, object]:
    validate_setting("white_balance", payload.white_balance)
    state["exposure"]["white_balance"] = payload.white_balance
    return camera_status()


@app.post("/ccapi/record/start")
async def record_start() -> dict[str, bool]:
    state["recording"] = True
    return {"ok": True, "recording": True}


@app.post("/ccapi/record/stop")
async def record_stop() -> dict[str, bool]:
    state["recording"] = False
    return {"ok": True, "recording": False}


@app.post("/ccapi/capture/still")
async def capture_still() -> dict[str, bool | int]:
    state["capture_count"] += 1
    return {"ok": True, "capture_count": state["capture_count"]}


@app.post("/ccapi/focus/tap")
async def tap_focus(payload: FocusRequest) -> dict[str, float | bool]:
    state["focus_x"] = payload.x
    state["focus_y"] = payload.y
    return {"ok": True, "x": payload.x, "y": payload.y}


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
            pixels[offset:offset + 3] = bytes(color)

    inner_row = bytes((31, 41, 55)) * 856
    for y in range(52, 488):
        offset = y * stride + 1 + 52 * 3
        pixels[offset:offset + len(inner_row)] = inner_row

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
            if 40 ** 2 <= distance_squared <= 46 ** 2:
                set_pixel(x, y, focus_color)

    status_color = (233, 75, 75) if state["recording"] else (88, 199, 123)
    for y in range(72, 92):
        for x in range(72, 92):
            set_pixel(x, y, status_color)

    def chunk(kind: bytes, payload: bytes) -> bytes:
        checksum = zlib.crc32(kind + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", checksum)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(pixels), 6)) + chunk(b"IEND", b"")
