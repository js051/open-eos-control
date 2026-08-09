from __future__ import annotations

import re
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


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


class CameraModelFamily(StrEnum):
    EOS_R = "EOS_R"
    EOS_DSLR = "EOS_DSLR"
    EOS_M = "EOS_M"
    POWERSHOT = "POWERSHOT"
    UNKNOWN = "UNKNOWN"


class CameraModelPriority(StrEnum):
    PRIMARY = "PRIMARY"
    SUPPORTED = "SUPPORTED"
    RESEARCH = "RESEARCH"


class CameraFeature(StrEnum):
    CAMERA_IDENTITY = "CAMERA_IDENTITY"
    CAMERA_CLOCK_SYNC = "CAMERA_CLOCK_SYNC"
    DIRECTORY_CONTROL = "DIRECTORY_CONTROL"
    FILE_NAMING_CONTROL = "FILE_NAMING_CONTROL"
    SENSOR_CLEANING = "SENSOR_CLEANING"
    CAMERA_SLEEP = "CAMERA_SLEEP"
    BATTERY_STATUS = "BATTERY_STATUS"
    STORAGE_STATUS = "STORAGE_STATUS"
    RECORDABLE_STATUS = "RECORDABLE_STATUS"
    LENS_STATUS = "LENS_STATUS"
    TEMPERATURE_STATUS = "TEMPERATURE_STATUS"
    EVENT_POLLING = "EVENT_POLLING"
    LIVE_VIEW = "LIVE_VIEW"
    LIVE_VIEW_JPEG_POLLING = "LIVE_VIEW_JPEG_POLLING"
    LIVE_VIEW_MULTIPART = "LIVE_VIEW_MULTIPART"
    LIVE_VIEW_RTP = "LIVE_VIEW_RTP"
    LIVE_VIEW_MAGNIFICATION = "LIVE_VIEW_MAGNIFICATION"
    STILL_CAPTURE = "STILL_CAPTURE"
    BULB_EXPOSURE = "BULB_EXPOSURE"
    AUTOFOCUS = "AUTOFOCUS"
    SHUTTER_HALF_PRESS = "SHUTTER_HALF_PRESS"
    MOVIE_MODE_CONTROL = "MOVIE_MODE_CONTROL"
    VIDEO_RECORDING = "VIDEO_RECORDING"
    TAP_FOCUS = "TAP_FOCUS"
    CLICK_WHITE_BALANCE = "CLICK_WHITE_BALANCE"
    FOCUS_DRIVE = "FOCUS_DRIVE"
    EXPOSURE_CONTROL = "EXPOSURE_CONTROL"
    WHITE_BALANCE_CONTROL = "WHITE_BALANCE_CONTROL"
    ZOOM_CONTROL = "ZOOM_CONTROL"
    CARD_SELECTION_CONTROL = "CARD_SELECTION_CONTROL"
    SOUND_RECORDING_CONTROL = "SOUND_RECORDING_CONTROL"
    SOUND_RECORDING_LEVEL_CONTROL = "SOUND_RECORDING_LEVEL_CONTROL"
    FOCUS_BRACKETING_CONTROL = "FOCUS_BRACKETING_CONTROL"
    MOVIE_SETTINGS_CONTROL = "MOVIE_SETTINGS_CONTROL"
    ADVANCED_SETTINGS = "ADVANCED_SETTINGS"
    MEDIA_BROWSER = "MEDIA_BROWSER"
    MEDIA_THUMBNAIL = "MEDIA_THUMBNAIL"
    MEDIA_PREVIEW = "MEDIA_PREVIEW"
    MEDIA_DOWNLOAD = "MEDIA_DOWNLOAD"
    MEDIA_UPLOAD = "MEDIA_UPLOAD"
    MEDIA_PROTECT = "MEDIA_PROTECT"
    MEDIA_RATING = "MEDIA_RATING"
    MEDIA_ROTATE = "MEDIA_ROTATE"
    MEDIA_ARCHIVE = "MEDIA_ARCHIVE"
    MEDIA_DELETE = "MEDIA_DELETE"
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


class LensStatus(ApiModel):
    mounted: bool
    name: str = Field(default="", max_length=512)


class CameraTemperatureStatus(StrEnum):
    NORMAL = "normal"
    WARNING = "warning"
    FRAME_RATE_DOWN = "frameratedown"
    DISABLE_LIVE_VIEW = "disableliveview"
    DISABLE_RELEASE = "disablerelease"
    STILL_QUALITY_WARNING = "stillqualitywarning"
    RESTRICTION_MOVIE_RECORDING = "restrictionmovierecording"
    WARNING_AND_RESTRICTION_MOVIE_RECORDING = "warning_and_restrictionmovierecording"
    FRAME_RATE_DOWN_AND_RESTRICTION_MOVIE_RECORDING = "frameratedown_and_restrictionmovierecording"
    DISABLE_LIVE_VIEW_AND_RESTRICTION_MOVIE_RECORDING = "disableliveview_and_restrictionmovierecording"
    DISABLE_RELEASE_AND_RESTRICTION_MOVIE_RECORDING = "disablerelease_and_restrictionmovierecording"
    STILL_QUALITY_WARNING_AND_RESTRICTION_MOVIE_RECORDING = (
        "stillqualitywarning_and_restrictionmovierecording"
    )

    @property
    def live_view_allowed(self) -> bool:
        return "disableliveview" not in self.value

    @property
    def still_capture_allowed(self) -> bool:
        return "disablerelease" not in self.value

    @property
    def movie_recording_allowed(self) -> bool:
        return "restrictionmovierecording" not in self.value


class ExposureState(ApiModel):
    iso: str = "-"
    shutter: str = "-"
    aperture: str = "-"
    white_balance: str = "-"


class CameraStatus(ApiModel):
    connected: bool = True
    battery: BatteryStatus
    recording: bool | None = None
    bulb_exposure_active: bool | None = None
    mode: str = "unknown"
    media: StorageStatus
    exposure: ExposureState
    recordable_shots: int | None = Field(default=None, ge=0)
    remaining_recording_seconds: int | None = Field(default=None, ge=0)
    lens: LensStatus | None = None
    temperature: CameraTemperatureStatus | None = None
    raw: dict[str, Any] = Field(default_factory=dict)


class CameraEvent(ApiModel):
    changed_keys: list[str] = Field(default_factory=list, max_length=64)


class CameraProfile(ApiModel):
    model_name: str
    family: CameraModelFamily
    priority: CameraModelPriority


def camera_profile(model: str) -> CameraProfile:
    normalized = "".join(character for character in model.casefold() if character.isalnum())
    if any(alias in normalized for alias in ("r6markiii", "r6m3", "r63")):
        family = CameraModelFamily.EOS_R
        priority = CameraModelPriority.PRIMARY
    elif "eosr" in normalized:
        family = CameraModelFamily.EOS_R
        priority = CameraModelPriority.SUPPORTED
    elif "eosm" in normalized:
        family = CameraModelFamily.EOS_M
        priority = CameraModelPriority.SUPPORTED
    elif "eos" in normalized:
        family = CameraModelFamily.EOS_DSLR
        priority = CameraModelPriority.SUPPORTED
    elif "powershot" in normalized:
        family = CameraModelFamily.POWERSHOT
        priority = CameraModelPriority.RESEARCH
    else:
        family = CameraModelFamily.UNKNOWN
        priority = CameraModelPriority.RESEARCH
    return CameraProfile(model_name=model, family=family, priority=priority)


class CameraSetting(ApiModel):
    key: str
    label: str
    value: str
    values: list[str]
    input_kind: Literal["choice", "text"] = "choice"
    max_length: int | None = Field(default=None, gt=0, le=255)

    @model_validator(mode="after")
    def validate_input_contract(self) -> CameraSetting:
        if self.input_kind == "text":
            if self.values or self.max_length is None:
                raise ValueError("Text settings must expose no choices and a bounded maximum length.")
            try:
                encoded = self.value.encode("ascii")
            except UnicodeEncodeError as error:
                raise ValueError("Text setting values must be printable ASCII.") from error
            if len(encoded) > self.max_length or any(not 0x20 <= byte <= 0x7E for byte in encoded):
                raise ValueError("Text setting values must be printable ASCII and fit maxLength.")
        elif self.max_length is not None:
            raise ValueError("Choice settings must not expose maxLength.")
        return self


class FileNamingField(StrEnum):
    STILL_FILENAME_MODE = "still-filename-mode"
    STILL_USER_SETTING_1 = "still-user-setting-1"
    STILL_USER_SETTING_2 = "still-user-setting-2"
    MOVIE_INDEX = "movie-index"
    MOVIE_REEL_NUMBER = "movie-reel-number"
    MOVIE_CLIP_NUMBER = "movie-clip-number"
    MOVIE_USER_DEFINED = "movie-user-defined"


class IntegerRange(ApiModel):
    minimum: int
    maximum: int
    step: int = Field(gt=0)

    @model_validator(mode="after")
    def validate_bounds(self) -> IntegerRange:
        if self.minimum > self.maximum:
            raise ValueError("Integer range minimum must not exceed maximum.")
        return self


class FileNamingState(ApiModel):
    still_filename_mode: str = Field(min_length=1, max_length=64)
    still_filename_mode_options: list[str] = Field(min_length=1, max_length=16)
    still_user_setting_1: str = Field(pattern=r"^[A-Z0-9][A-Z0-9_]{3}$")
    still_user_setting_2: str = Field(pattern=r"^[A-Z0-9][A-Z0-9_]{2}$")
    movie_index: str = Field(pattern=r"^[A-Z0-9][A-Z0-9_]$")
    movie_reel_number: int
    movie_reel_range: IntegerRange
    movie_clip_number: int
    movie_clip_range: IntegerRange
    movie_user_defined: str = Field(pattern=r"^[A-Z0-9]{5}$")

    @model_validator(mode="after")
    def validate_current_values(self) -> FileNamingState:
        allowed_modes = {"preset_code", "usersetting1", "usersetting2"}
        if (
            len(set(self.still_filename_mode_options)) != len(self.still_filename_mode_options)
            or any(value not in allowed_modes for value in self.still_filename_mode_options)
        ):
            raise ValueError("Still filename modes must use unique documented Canon values.")
        if self.still_filename_mode not in self.still_filename_mode_options:
            raise ValueError("Current still filename mode must be advertised.")
        if self.movie_reel_range.minimum < 1 or self.movie_reel_range.maximum > 9999:
            raise ValueError("Movie reel range is outside Canon's documented bounds.")
        if self.movie_clip_range.minimum < 1 or self.movie_clip_range.maximum > 999:
            raise ValueError("Movie clip range is outside Canon's documented bounds.")
        for value, value_range in (
            (self.movie_reel_number, self.movie_reel_range),
            (self.movie_clip_number, self.movie_clip_range),
        ):
            if not value_range.minimum <= value <= value_range.maximum:
                raise ValueError("Current filename number must be inside its range.")
            if (value - value_range.minimum) % value_range.step != 0:
                raise ValueError("Current filename number must conform to its range step.")
        return self


class LiveViewCapabilities(ApiModel):
    sources: list[str] = Field(default_factory=list)
    default_source: str | None = None
    sizes: list[str] = Field(default_factory=list)
    default_size: str | None = None
    magnifications: list[Literal[1, 5, 10]] = Field(default_factory=list)
    current_magnification: Literal[1, 5, 10] | None = None
    min_fps: int = Field(default=1, ge=1)
    max_fps: int = Field(default=5, ge=1)

    @field_validator("magnifications")
    @classmethod
    def validate_magnifications(cls, values: list[Literal[1, 5, 10]]) -> list[Literal[1, 5, 10]]:
        if len(values) != len(set(values)):
            raise ValueError("Live View magnifications must be unique.")
        if values and (len(values) < 2 or 1 not in values):
            raise ValueError("Live View magnifications must include 1x and at least one additional value.")
        return values

    @model_validator(mode="after")
    def validate_current_magnification(self) -> LiveViewCapabilities:
        if self.current_magnification is not None and self.current_magnification not in self.magnifications:
            raise ValueError("Current Live View magnification must be advertised.")
        if not self.magnifications and self.current_magnification is not None:
            raise ValueError("Current Live View magnification requires an advertised ability list.")
        return self


class DiscoveryAttempt(ApiModel):
    endpoint: str = Field(
        min_length=1,
        max_length=128,
        pattern=r"^GET /ccapi(?:/[A-Za-z0-9._~-]+)*/?$",
    )
    outcome: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    http_status: int | None = Field(default=None, ge=100, le=599)
    response_keys: list[str] = Field(default_factory=list, max_length=32)
    protocol_versions: list[str] = Field(default_factory=list, max_length=32)
    advertised_operation_count: int = Field(default=0, ge=0)
    truncated: bool = False

    @field_validator("response_keys")
    @classmethod
    def validate_response_keys(cls, values: list[str]) -> list[str]:
        if any(re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", value) is None for value in values):
            raise ValueError("Discovery response keys must be bounded structural names.")
        return values

    @field_validator("protocol_versions")
    @classmethod
    def validate_protocol_versions(cls, values: list[str]) -> list[str]:
        if any(re.fullmatch(r"ver[0-9]+", value) is None for value in values):
            raise ValueError("Discovery protocol versions must use Canon verNNN names.")
        return values


class CapabilityEvidence(ApiModel):
    source: str = "unknown"
    protocol_versions: list[str] = Field(default_factory=list, max_length=256)
    advertised_commands: list[str] = Field(default_factory=list, max_length=256)
    writable_settings: list[str] = Field(default_factory=list, max_length=256)
    observed_features: list[CameraFeature] = Field(default_factory=list, max_length=256)
    discovery_trace: list[DiscoveryAttempt] = Field(default_factory=list, max_length=16)
    truncated: bool = False


class CameraCapabilities(ApiModel):
    profile: CameraProfile
    supported: list[CameraFeature]
    planned: list[CameraFeature]
    reasons: dict[str, str] = Field(default_factory=dict)
    live_view: LiveViewCapabilities = Field(default_factory=LiveViewCapabilities)
    settings: list[CameraSetting] = Field(default_factory=list)
    file_naming: FileNamingState | None = None
    evidence: CapabilityEvidence = Field(default_factory=CapabilityEvidence)


class SettingUpdate(ApiModel):
    value: str = Field(max_length=512)


class DirectoryCreateRequest(ApiModel):
    name: str = Field(default="", max_length=5, pattern=r"^(?:[A-Z0-9_]{5})?$")


class DirectoryCreateResult(ApiModel):
    name: str = Field(min_length=5, max_length=5, pattern=r"^[A-Z0-9_]{5}$")


class FileNamingUpdate(ApiModel):
    value: str = Field(min_length=1, max_length=16)


class SensorCleaningRequest(ApiModel):
    auto_power_off: bool = False


class LiveViewStartRequest(ApiModel):
    fps: int = Field(default=5, ge=1, le=30)
    size: str = "MEDIUM"
    source: str = "DESKTOP_BRIDGE_STREAM"


class LiveViewState(ApiModel):
    active: bool
    requested_fps: int | None = None
    source: str | None = None


class LiveViewMagnificationRequest(ApiModel):
    value: Literal[1, 5, 10]


class LiveViewMagnificationResult(ApiModel):
    accepted: bool
    value: Literal[1, 5, 10]


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
    preview_available: bool = False
    protected: bool | None = None
    rating: int | None = Field(default=None, ge=0, le=5)
    rotation_degrees: Literal[0, 90, 180, 270] | None = None
    archived: bool | None = None


class MediaProtectionUpdate(ApiModel):
    enabled: bool


class MediaRatingUpdate(ApiModel):
    value: int = Field(ge=0, le=5)


class MediaRotationUpdate(ApiModel):
    degrees: Literal[0, 90, 180, 270]


class MediaArchiveUpdate(ApiModel):
    enabled: bool


class MediaList(ApiModel):
    items: list[MediaItem]


class ErrorDetail(ApiModel):
    code: str
    message: str
    feature: str | None = None
    engine: str | None = None


class ErrorResponse(ApiModel):
    error: ErrorDetail
