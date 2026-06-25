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


@app.post("/ccapi/focus/tap")
async def tap_focus(payload: FocusRequest) -> dict[str, float | bool]:
    state["focus_x"] = payload.x
    state["focus_y"] = payload.y
    return {"ok": True, "x": payload.x, "y": payload.y}


@app.get("/ccapi/liveview/frame")
async def liveview_frame() -> Response:
    focus_x = int(float(state["focus_x"]) * 960)
    focus_y = int(float(state["focus_y"]) * 540)
    label = "REC" if state["recording"] else "STBY"
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 540">
  <rect width="960" height="540" fill="#111827"/>
  <rect x="52" y="52" width="856" height="436" fill="#1f2937" stroke="#64748b" stroke-width="2"/>
  <circle cx="{focus_x}" cy="{focus_y}" r="44" fill="none" stroke="#facc15" stroke-width="5"/>
  <text x="72" y="92" font-family="Arial, sans-serif" font-size="30" fill="#f8fafc">{label}</text>
  <text x="72" y="482" font-family="Arial, sans-serif" font-size="24" fill="#f8fafc">Fake Canon CCAPI frame</text>
</svg>"""
    return Response(content=svg.encode("utf-8"), media_type="image/svg+xml")
