from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=_to_camel, populate_by_name=True, extra="forbid")


class EngineName(StrEnum):
    AUTO = "auto"
    LIBGPHOTO2 = "libgphoto2"
    CCAPI = "ccapi"
    EDSDK = "edsdk"


class CameraFeature(StrEnum):
    CAMERA_IDENTITY = "CAMERA_IDENTITY"
    BATTERY_STATUS = "BATTERY_STATUS"
    STORAGE_STATUS = "STORAGE_STATUS"
    LIVE_VIEW = "LIVE_VIEW"
    LIVE_VIEW_JPEG_POLLING = "LIVE_VIEW_JPEG_POLLING"
    LIVE_VIEW_RTP = "LIVE_VIEW_RTP"
    STILL_CAPTURE = "STILL_CAPTURE"
    SHUTTER_HALF_PRESS = "SHUTTER_HALF_PRESS"
    VIDEO_RECORDING = "VIDEO_RECORDING"
    TAP_FOCUS = "TAP_FOCUS"
    FOCUS_DRIVE = "FOCUS_DRIVE"
    EXPOSURE_CONTROL = "EXPOSURE_CONTROL"
    WHITE_BALANCE_CONTROL = "WHITE_BALANCE_CONTROL"
    ADVANCED_SETTINGS = "ADVANCED_SETTINGS"
    MEDIA_BROWSER = "MEDIA_BROWSER"
    MEDIA_DOWNLOAD = "MEDIA_DOWNLOAD"
    USB_DIAGNOSTICS = "USB_DIAGNOSTICS"
    DESKTOP_BRIDGE = "DESKTOP_BRIDGE"


class EngineHealth(ApiModel):
    available: bool
    version: str | None = None
    detail: str | None = None


class HealthResponse(ApiModel):
    ok: bool = True
    service: str = "open-eos-control-bridge"
    version: str
    auth_required: bool
    loopback_only: bool
    engines: dict[str, EngineHealth]


class CameraDescriptor(ApiModel):
    id: str
    model: str
    port: str
    engine: str = EngineName.LIBGPHOTO2.value


class CameraList(ApiModel):
    cameras: list[CameraDescriptor]


class SessionCreateRequest(ApiModel):
    engine: EngineName = EngineName.AUTO
    camera_id: str | None = None
    profile_hint: str | None = None
    ccapi_url: str | None = Field(default=None, max_length=2048)
    ccapi_username: str | None = Field(default=None, max_length=256)
    ccapi_password: str | None = Field(default=None, max_length=1024, repr=False)


class SessionCreated(ApiModel):
    id: str
    engine: str
    camera: CameraDescriptor


class CameraInfo(ApiModel):
    connected: bool = True
    model: str
    serial: str
    api: str
    manufacturer: str | None = None
    device_version: str | None = None
    engine_version: str | None = None


class BatteryStatus(ApiModel):
    level: int | None = Field(default=None, ge=0, le=100)
    status: str = "unknown"


class StorageStatus(ApiModel):
    available: bool | None = None
    total_bytes: int | None = Field(default=None, ge=0)
    free_bytes: int | None = Field(default=None, ge=0)
    free_images: int | None = Field(default=None, ge=0)
    devices: int = Field(default=0, ge=0)


class ExposureState(ApiModel):
    iso: str = "-"
    shutter: str = "-"
    aperture: str = "-"
    white_balance: str = "-"


class CameraStatus(ApiModel):
    connected: bool = True
    battery: BatteryStatus
    recording: bool | None = None
    mode: str = "unknown"
    media: StorageStatus
    exposure: ExposureState
    raw: dict[str, Any] = Field(default_factory=dict)


class CameraProfile(ApiModel):
    model_name: str
    family: str
    priority: str


class CameraSetting(ApiModel):
    key: str
    label: str
    value: str
    values: list[str]


class LiveViewCapabilities(ApiModel):
    sources: list[str] = Field(default_factory=list)
    default_source: str | None = None
    sizes: list[str] = Field(default_factory=list)
    default_size: str | None = None
    min_fps: int = Field(default=1, ge=1)
    max_fps: int = Field(default=5, ge=1)


class CameraCapabilities(ApiModel):
    profile: CameraProfile
    supported: list[CameraFeature]
    planned: list[CameraFeature]
    reasons: dict[str, str] = Field(default_factory=dict)
    live_view: LiveViewCapabilities = Field(default_factory=LiveViewCapabilities)
    settings: list[CameraSetting] = Field(default_factory=list)


class SettingUpdate(ApiModel):
    value: str = Field(min_length=1, max_length=512)


class LiveViewStartRequest(ApiModel):
    fps: int = Field(default=5, ge=1, le=30)
    size: str = "MEDIUM"
    source: str = "DESKTOP_BRIDGE_STREAM"


class LiveViewState(ApiModel):
    active: bool
    requested_fps: int | None = None


class FocusDriveRequest(ApiModel):
    direction: str
    step: str


class TapFocusRequest(ApiModel):
    x: float = Field(ge=0.0, le=1.0)
    y: float = Field(ge=0.0, le=1.0)


class FocusResult(ApiModel):
    accepted: bool
    direction: str | None = None
    step: str | None = None
    x: float | None = Field(default=None, ge=0.0, le=1.0)
    y: float | None = Field(default=None, ge=0.0, le=1.0)


class MediaItem(ApiModel):
    id: str
    name: str
    kind: str
    size_bytes: int = Field(default=0, ge=0)
    capture_time: str | None = None
    content_type: str = "application/octet-stream"


class MediaList(ApiModel):
    items: list[MediaItem]


class ErrorDetail(ApiModel):
    code: str
    message: str
    feature: str | None = None
    engine: str | None = None


class ErrorResponse(ApiModel):
    error: ErrorDetail
