from fastapi import FastAPI, Response
from pydantic import BaseModel, Field

app = FastAPI(title="Open EOS Control Fake Camera")


class FocusRequest(BaseModel):
    x: float = Field(ge=0.0, le=1.0)
    y: float = Field(ge=0.0, le=1.0)


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


@app.get("/health")
async def health() -> dict[str, bool | str]:
    return {"ok": True, "service": "open-eos-control-simulator"}


@app.get("/ccapi/status")
async def status() -> dict[str, object]:
    return {
        "connected": True,
        "battery": {"level": 82, "status": "normal"},
        "recording": state["recording"],
        "mode": "movie",
        "media": {"available": True, "remaining_minutes": 120},
        "exposure": state["exposure"],
    }


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
