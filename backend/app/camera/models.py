from pydantic import BaseModel, Field, HttpUrl


class CameraConnectRequest(BaseModel):
    base_url: HttpUrl | None = None
    username: str = ""
    password: str = ""
    use_fake: bool = True


class CameraInfo(BaseModel):
    connected: bool
    model: str
    serial: str = "unknown"
    api: str = "ccapi"


class BatteryStatus(BaseModel):
    level: int = Field(ge=0, le=100)
    status: str


class MediaStatus(BaseModel):
    available: bool
    remaining_minutes: int = Field(ge=0)


class ExposureState(BaseModel):
    iso: str
    shutter: str
    aperture: str
    white_balance: str


class CameraStatus(BaseModel):
    connected: bool
    battery: BatteryStatus
    recording: bool
    mode: str
    media: MediaStatus
    exposure: ExposureState


class CameraCapabilities(BaseModel):
    iso: list[str]
    shutter: list[str]
    aperture: list[str]
    white_balance: list[str]


class ExposureUpdate(BaseModel):
    iso: str | None = None
    shutter: str | None = None
    aperture: str | None = None


class WhiteBalanceUpdate(BaseModel):
    white_balance: str


class FocusRequest(BaseModel):
    x: float = Field(ge=0.0, le=1.0)
    y: float = Field(ge=0.0, le=1.0)


class FocusResult(BaseModel):
    ok: bool
    x: float
    y: float


class RecordStatus(BaseModel):
    ok: bool
    recording: bool
