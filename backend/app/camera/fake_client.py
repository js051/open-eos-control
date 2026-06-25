from app.camera.base import CameraClient
from app.camera.errors import UnsupportedSettingError
from app.camera.models import (
    BatteryStatus,
    CameraCapabilities,
    CameraInfo,
    CameraStatus,
    ExposureState,
    ExposureUpdate,
    FocusResult,
    MediaStatus,
)


class FakeCameraClient(CameraClient):
    def __init__(self) -> None:
        self.connected = False
        self.recording = False
        self.focus_x = 0.5
        self.focus_y = 0.5
        self.capabilities = CameraCapabilities(
            iso=["100", "200", "400", "800", "1600", "3200", "6400"],
            shutter=["1/25", "1/50", "1/60", "1/100", "1/125"],
            aperture=["1.8", "2.0", "2.8", "4.0", "5.6"],
            white_balance=["auto", "daylight", "cloudy", "tungsten", "kelvin"],
        )
        self.exposure = ExposureState(
            iso="800",
            shutter="1/50",
            aperture="2.8",
            white_balance="auto",
        )

    async def connect(self) -> CameraInfo:
        self.connected = True
        return CameraInfo(
            connected=True,
            model="Canon EOS R6 Mark III",
            serial="fake-r6m3",
            api="fake-ccapi",
        )

    async def get_status(self) -> CameraStatus:
        return CameraStatus(
            connected=self.connected,
            battery=BatteryStatus(level=82, status="normal"),
            recording=self.recording,
            mode="movie",
            media=MediaStatus(available=True, remaining_minutes=120),
            exposure=self.exposure,
        )

    async def get_capabilities(self) -> CameraCapabilities:
        return self.capabilities

    async def set_exposure(self, exposure: ExposureUpdate) -> CameraStatus:
        update = exposure.model_dump(exclude_none=True)
        for key, value in update.items():
            allowed = getattr(self.capabilities, key)
            if value not in allowed:
                raise UnsupportedSettingError(str(value))
            setattr(self.exposure, key, value)
        return await self.get_status()

    async def set_white_balance(self, value: str) -> CameraStatus:
        if value not in self.capabilities.white_balance:
            raise UnsupportedSettingError(value)
        self.exposure.white_balance = value
        return await self.get_status()

    async def start_recording(self) -> CameraStatus:
        self.recording = True
        return await self.get_status()

    async def stop_recording(self) -> CameraStatus:
        self.recording = False
        return await self.get_status()

    async def tap_focus(self, x: float, y: float) -> FocusResult:
        self.focus_x = x
        self.focus_y = y
        return FocusResult(ok=True, x=x, y=y)

    async def get_liveview_frame(self) -> bytes:
        rec_label = "REC" if self.recording else "STBY"
        rec_color = "#e11d48" if self.recording else "#d1d5db"
        focus_x = int(self.focus_x * 960)
        focus_y = int(self.focus_y * 540)
        svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 540">
  <defs>
    <linearGradient id="bg" x1="0" x2="1" y1="0" y2="1">
      <stop offset="0" stop-color="#172033"/>
      <stop offset="0.55" stop-color="#334155"/>
      <stop offset="1" stop-color="#15171c"/>
    </linearGradient>
  </defs>
  <rect width="960" height="540" fill="url(#bg)"/>
  <rect x="48" y="48" width="864" height="444" fill="none" stroke="#94a3b8" stroke-width="2" opacity="0.55"/>
  <circle cx="{focus_x}" cy="{focus_y}" r="42" fill="none" stroke="#facc15" stroke-width="5"/>
  <line x1="{focus_x - 64}" y1="{focus_y}" x2="{focus_x + 64}" y2="{focus_y}" stroke="#facc15" stroke-width="2"/>
  <line x1="{focus_x}" y1="{focus_y - 64}" x2="{focus_x}" y2="{focus_y + 64}" stroke="#facc15" stroke-width="2"/>
  <text x="58" y="84" font-family="Arial, sans-serif" font-size="28" fill="{rec_color}" font-weight="700">{rec_label}</text>
  <text x="58" y="488" font-family="Arial, sans-serif" font-size="24" fill="#f8fafc">
    ISO {self.exposure.iso}   {self.exposure.shutter}   F{self.exposure.aperture}   WB {self.exposure.white_balance}
  </text>
  <text x="625" y="84" font-family="Arial, sans-serif" font-size="24" fill="#f8fafc">Canon EOS R6 Mark III</text>
</svg>"""
        return svg.encode("utf-8")
