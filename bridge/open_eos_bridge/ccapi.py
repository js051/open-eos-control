from __future__ import annotations

import base64
import json
import mimetypes
import re
import threading
import time
from collections import deque
from collections.abc import Callable, Iterable, Iterator, Mapping
from contextlib import suppress
from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import format_datetime, parsedate_to_datetime
from typing import BinaryIO, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import SplitResult, unquote, urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen

from .errors import BridgeError, unsupported
from .models import (
    BatteryStatus,
    CameraCapabilities,
    CameraDescriptor,
    CameraEvent,
    CameraFeature,
    CameraInfo,
    CameraSetting,
    CameraStatus,
    CameraTemperatureStatus,
    CapabilityEvidence,
    ExposureState,
    FocusResult,
    LensStatus,
    LiveViewCapabilities,
    LiveViewMagnificationResult,
    LiveViewStartRequest,
    MediaItem,
    StorageStatus,
    camera_profile,
)
from .rtp import (
    RtpAudioChunk,
    RtpError,
    RtpLiveViewSession,
    RtpSessionFactory,
    create_udp_rtp_session,
    parse_sdp,
    pyav_decoder_available,
    resolve_local_ipv4,
)

ENGINE_NAME = "ccapi"
ENGINE_VERSION = "CCAPI HTTP"
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_ERROR_BYTES = 2_000
MAX_LIVE_VIEW_SCAN_BYTES = 16 * 1024 * 1024
MAX_LIVE_VIEW_FRAME_BYTES = 12 * 1024 * 1024
MAX_RTP_SESSION_DESCRIPTION_BYTES = 64 * 1024
RTP_AUDIO_FEATURE = "LIVE_VIEW_RTP_AUDIO"
MAX_MEDIA_ITEMS = 500
MAX_MEDIA_PAGES = 100
MAX_MEDIA_TREE_DEPTH = 4
MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024
MAX_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024
MAX_CAPABILITY_EVIDENCE_ITEMS = 256
MAX_CAPABILITY_EVIDENCE_ITEM_CHARS = 512
MAX_DEVICE_STATUS_TEXT_CHARS = 512
MAX_EVENT_BYTES = 256 * 1024
MAX_EVENT_KEYS = 64
MAX_EVENT_KEY_CHARS = 128
TRANSFER_CHUNK_BYTES = 64 * 1024
HALF_PRESS_SECONDS = 0.35
HTTP_METHODS = ("GET", "PUT", "POST", "DELETE")
COMMAND_METHODS = {"PUT", "POST"}
PRIMARY_SETTING_KEYS = {"iso", "shutter", "aperture", "whitebalance"}
IMAGE_QUALITY_SETTING_KEY = "stillimagequality"
IMAGE_QUALITY_FIELDS = ("raw", "jpeg", "heif")
WB_SHIFT_SETTING_KEY = "wbshift"
WB_SHIFT_FIELDS = ("ba", "mg")
ZOOM_SETTING_KEY = "zoom"
ZOOM_PATH_SUFFIX = "/shooting/control/zoom"
MOVIE_MODE_SETTING_KEY = "moviemode"
MOVIE_MODE_PATH_SUFFIX = "/shooting/control/moviemode"
MOVIE_MODE_VALUES = ("off", "on")
STILL_CARD_SELECTION_SETTING_KEY = "cardselectionstillimage"
MOVIE_CARD_SELECTION_SETTING_KEY = "cardselectionmovie"
CARD_SELECTION_VALUES = ("none", "card1", "card2")
CARD_SELECTION_ENDPOINTS = {
    STILL_CARD_SELECTION_SETTING_KEY: "/functions/cardselection/stillimage",
    MOVIE_CARD_SELECTION_SETTING_KEY: "/functions/cardselection/movie",
}
SOUND_RECORDING_LEVEL_SETTING_KEY = "soundrecordinglevel"
SOUND_RECORDING_LEVEL_PATH_SUFFIX = "/shooting/settings/soundrecording/level"
SOUND_RECORDING_SETTING_KEY = "soundrecording"
WIND_FILTER_SETTING_KEY = "windfilter"
ATTENUATOR_SETTING_KEY = "attenuator"
SOUND_RECORDING_ENDPOINTS = {
    SOUND_RECORDING_SETTING_KEY: (
        "/shooting/settings/soundrecording",
        frozenset({"auto", "manual", "disable"}),
    ),
    WIND_FILTER_SETTING_KEY: (
        "/shooting/settings/soundrecording/windfilter",
        frozenset({"auto", "enable", "disable"}),
    ),
    ATTENUATOR_SETTING_KEY: (
        "/shooting/settings/soundrecording/attenuator",
        frozenset({"enable", "disable", "auto", "manual"}),
    ),
}
MAX_STRUCTURED_SETTING_OPTIONS = 256
MAX_FOCUS_BRACKETING_OPTIONS = 1024
FOCUS_BRACKETING_SETTING_KEY = "focusbracketing"
FOCUS_BRACKETING_NUMBER_SETTING_KEY = "focusbracketingnumberofshots"
FOCUS_BRACKETING_INCREMENT_SETTING_KEY = "focusbracketingfocusincrement"
FOCUS_BRACKETING_SMOOTHING_SETTING_KEY = "focusbracketingexposuresmoothing"
FOCUS_BRACKETING_STRING_ENDPOINTS = {
    FOCUS_BRACKETING_SETTING_KEY: (
        "/shooting/settings/focusbracketing",
        frozenset({"enable", "disable"}),
    ),
    FOCUS_BRACKETING_SMOOTHING_SETTING_KEY: (
        "/shooting/settings/focusbracketing/exposuresmoothing",
        frozenset({"enable", "disable"}),
    ),
}
FOCUS_BRACKETING_INTEGER_ENDPOINTS = {
    FOCUS_BRACKETING_NUMBER_SETTING_KEY: "/shooting/settings/focusbracketing/numberofshots",
    FOCUS_BRACKETING_INCREMENT_SETTING_KEY: "/shooting/settings/focusbracketing/focusincrement",
}
FOCUS_BRACKETING_SETTING_KEYS = FOCUS_BRACKETING_STRING_ENDPOINTS.keys() | FOCUS_BRACKETING_INTEGER_ENDPOINTS.keys()
CCAPI_NO_API_LIST_VALUE = "No list of APIs"
CCAPI_DEVELOPER_API_PATH = "/ccapi/ver100/topurlfordev"
SETTING_ALIASES = {
    "iso": "iso",
    "tv": "shutter",
    "shutter": "shutter",
    "shutterspeed": "shutter",
    "av": "aperture",
    "aperture": "aperture",
    "wb": "whitebalance",
    "whitebalance": "whitebalance",
    "white_balance": "whitebalance",
}
SETTING_LABELS = {
    "iso": "ISO",
    STILL_CARD_SELECTION_SETTING_KEY: "Still-image card",
    MOVIE_CARD_SELECTION_SETTING_KEY: "Movie card",
    SOUND_RECORDING_LEVEL_SETTING_KEY: "Sound recording level",
    SOUND_RECORDING_SETTING_KEY: "Sound recording",
    WIND_FILTER_SETTING_KEY: "Wind filter",
    ATTENUATOR_SETTING_KEY: "Attenuator",
    FOCUS_BRACKETING_SETTING_KEY: "Focus bracketing",
    FOCUS_BRACKETING_NUMBER_SETTING_KEY: "Focus bracketing shots",
    FOCUS_BRACKETING_INCREMENT_SETTING_KEY: "Focus increment",
    FOCUS_BRACKETING_SMOOTHING_SETTING_KEY: "Exposure smoothing",
    "shutter": "Tv",
    "aperture": "Av",
    "whitebalance": "WB",
    "afmethod": "AF method",
    "afoperation": "AF operation",
    "drivemode": "Drive mode",
    "meteringmode": "Metering",
    "picturestyle": "Picture style",
    "moviemode": "Movie mode",
    "shootingmode": "Shooting mode",
    "stillimagequality": "Image quality",
    "stillimagequality.raw": "RAW quality",
    "stillimagequality.jpeg": "JPEG quality",
    "stillimagequality.heif": "HEIF quality",
    "wbshift.ba": "WB shift B/A",
    "wbshift.mg": "WB shift M/G",
    "moviequality": "Movie quality",
    "colortemperature": "Color temperature",
    "exposurecompensation": "Exposure compensation",
    "alomode": "Auto Lighting Optimizer",
    "ae": "AE mode",
    "zoom": "Zoom",
}
_DEFAULT_RTP_FACTORY = object()


@dataclass(frozen=True)
class CcapiOperation:
    method: str
    path: str


@dataclass(frozen=True)
class CcapiResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes


@dataclass(frozen=True)
class CcapiLiveViewGeometry:
    position_x: int
    position_y: int
    position_width: int
    position_height: int

    def camera_position(self, normalized_x: float, normalized_y: float) -> tuple[int, int]:
        x = self.position_x + int(normalized_x * self.position_width)
        y = self.position_y + int(normalized_y * self.position_height)
        return (
            min(max(x, self.position_x), self.position_x + self.position_width - 1),
            min(max(y, self.position_y), self.position_y + self.position_height - 1),
        )


@dataclass
class CcapiStreamResponse:
    status: int
    headers: Mapping[str, str]
    body: BinaryIO

    def close(self) -> None:
        self.body.close()


class CcapiTransport(Protocol):
    def request(
        self,
        method: str,
        url: str,
        *,
        body: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        timeout: float = 15.0,
        max_bytes: int = MAX_JSON_BYTES,
    ) -> CcapiResponse: ...

    def open_stream(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        timeout: float = 60.0,
    ) -> CcapiStreamResponse: ...

    def close(self) -> None: ...


class UrllibCcapiTransport:
    def __init__(self, username: str = "", password: str = "") -> None:
        self._authorization: str | None = None
        if username:
            credential = base64.b64encode(f"{username}:{password}".encode()).decode()
            self._authorization = f"Basic {credential}"

    def request(
        self,
        method: str,
        url: str,
        *,
        body: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        timeout: float = 15.0,
        max_bytes: int = MAX_JSON_BYTES,
    ) -> CcapiResponse:
        response = self._open(method, url, body=body, headers=headers, timeout=timeout)
        try:
            payload = response.body.read(max_bytes + 1)
            if len(payload) > max_bytes:
                raise BridgeError(
                    "CCAPI_RESPONSE_TOO_LARGE",
                    f"The camera response exceeded the {max_bytes}-byte safety limit.",
                    status_code=502,
                    engine=ENGINE_NAME,
                )
            return CcapiResponse(response.status, response.headers, payload)
        finally:
            response.close()

    def open_stream(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        timeout: float = 60.0,
    ) -> CcapiStreamResponse:
        return self._open(method, url, headers=headers, timeout=timeout)

    def close(self) -> None:
        self._authorization = None

    def _open(
        self,
        method: str,
        url: str,
        *,
        body: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        timeout: float,
    ) -> CcapiStreamResponse:
        request_headers = {
            "Accept": "application/json,*/*",
            "Cache-Control": "no-cache",
            "User-Agent": "Open-EOS-Control-Bridge/0.1",
            **(headers or {}),
        }
        if self._authorization:
            request_headers["Authorization"] = self._authorization
        if body is not None:
            request_headers.setdefault("Content-Type", "application/json; charset=utf-8")
        request = Request(url, data=body, headers=request_headers, method=method)
        try:
            response = urlopen(request, timeout=timeout)  # noqa: S310 - user-selected camera origin is intentional.
        except HTTPError as error:
            response = error
        except (OSError, TimeoutError, URLError) as error:
            raise BridgeError(
                "CCAPI_UNREACHABLE",
                f"Could not reach the camera CCAPI endpoint: {_network_error_detail(error)}",
                status_code=502,
                engine=ENGINE_NAME,
            ) from error
        response_headers = {key.casefold(): value for key, value in response.headers.items()}
        return CcapiStreamResponse(int(response.status), response_headers, response)


class _CcapiHTTPError(BridgeError):
    def __init__(self, method: str, path: str, response: CcapiResponse) -> None:
        preview = response.body.decode("utf-8", errors="replace").strip()[:MAX_ERROR_BYTES]
        suffix = f" Body: {preview}" if preview else ""
        super().__init__(
            "CCAPI_HTTP_ERROR",
            f"Camera request {method} {path} returned HTTP {response.status}.{suffix}",
            status_code=502,
            engine=ENGINE_NAME,
        )
        self.camera_status = response.status


class CcapiEngine:
    name = ENGINE_NAME

    def __init__(
        self,
        transport_factory: Callable[[str, str], CcapiTransport] | None = None,
        *,
        sleeper: Callable[[float], None] = time.sleep,
        rtp_session_factory: RtpSessionFactory | None | object = _DEFAULT_RTP_FACTORY,
        route_resolver: Callable[[str], str | None] = resolve_local_ipv4,
    ) -> None:
        self._transport_factory = transport_factory or UrllibCcapiTransport
        self._sleeper = sleeper
        if rtp_session_factory is _DEFAULT_RTP_FACTORY:
            self._rtp_session_factory = create_udp_rtp_session if pyav_decoder_available() else None
        else:
            self._rtp_session_factory = rtp_session_factory
        self._route_resolver = route_resolver

    def health(self) -> tuple[bool, str | None, str | None]:
        rtp = "PyAV RTP decoder ready." if self._rtp_session_factory else "PyAV RTP decoder unavailable."
        return True, ENGINE_VERSION, f"Enter a camera CCAPI URL to use the network engine. {rtp}"

    def open_connection(self, base_url: str, username: str = "", password: str = "") -> CcapiSession:
        normalized = normalize_base_url(base_url)
        transport = self._transport_factory(username, password)
        session = CcapiSession(
            normalized,
            transport,
            sleeper=self._sleeper,
            rtp_session_factory=self._rtp_session_factory,
            rtp_destination_address=self._route_resolver(normalized),
        )
        try:
            session.initialize()
            info = session.info()
            session.camera = CameraDescriptor(
                id=_camera_id(normalized),
                model=info.model,
                port=normalized,
                engine=self.name,
            )
            return session
        except Exception:
            session.close()
            raise


class CcapiSession:
    engine_name = ENGINE_NAME

    def __init__(
        self,
        base_url: str,
        transport: CcapiTransport,
        *,
        sleeper: Callable[[float], None] = time.sleep,
        rtp_session_factory: RtpSessionFactory | None = None,
        rtp_destination_address: str | None = None,
    ) -> None:
        self.base_url = base_url
        self.transport = transport
        self.camera = CameraDescriptor(
            id=_camera_id(base_url),
            model="Canon Camera",
            port=base_url,
            engine=self.engine_name,
        )
        self._sleep = sleeper
        self._rtp_session_factory = rtp_session_factory
        self._rtp_destination_address = rtp_destination_address
        self._lock = threading.RLock()
        self._event_lock = threading.Lock()
        self._closed = False
        self._initialized = False
        self._api_prefixes = ["/ccapi/ver100"]
        self._preferred_prefix = "/ccapi/ver100"
        self._operations: set[CcapiOperation] = set()
        self._observed: set[CameraFeature] = {CameraFeature.DESKTOP_BRIDGE}
        self._cached_info: CameraInfo | None = None
        self._settings_cache: dict[str, object] | None = None
        self._setting_paths: dict[str, str] = {}
        self._discovery_source = "unknown"
        self._recording: bool | None = None
        self._bulb_exposure_active = False
        self._temperature_status: CameraTemperatureStatus | None = None
        self._live_view_active = False
        self._active_live_view_source: str | None = None
        self._rtp_session: RtpLiveViewSession | None = None
        self._live_view_size_control = True
        self._active_live_view_size = "MEDIUM"
        self._requested_fps = 1
        self._frame_key = 0
        self._latest_live_view_geometry: CcapiLiveViewGeometry | None = None
        self._media_cache: dict[str, MediaItem] = {}
        self._last_error: str | None = None

    def initialize(self) -> None:
        with self._lock:
            self._require_open()
            if self._initialized:
                return
            failures: list[str] = []
            for path in ("/ccapi", "/ccapi/"):
                try:
                    value = self._request_json("GET", path)
                    if not isinstance(value, dict):
                        raise BridgeError(
                            "INVALID_CCAPI_RESPONSE",
                            f"Camera discovery {path} did not return a JSON object.",
                            status_code=502,
                            engine=self.engine_name,
                        )
                    if value.get("value") == CCAPI_NO_API_LIST_VALUE:
                        value = self._request_json("GET", CCAPI_DEVELOPER_API_PATH)
                        if not isinstance(value, dict):
                            raise BridgeError(
                                "INVALID_CCAPI_RESPONSE",
                                f"Camera developer API {CCAPI_DEVELOPER_API_PATH} did not return a JSON object.",
                                status_code=502,
                                engine=self.engine_name,
                            )
                        source = f"GET {CCAPI_DEVELOPER_API_PATH} (Canon developer API fallback)"
                    else:
                        source = f"GET {path}"
                    self._parse_discovery(value, source=source)
                    self._initialized = True
                    return
                except BridgeError as error:
                    failures.append(f"GET {path}: {error.message}")

            for prefix in ("/ccapi/ver110", "/ccapi/ver100"):
                path = f"{prefix}/deviceinformation"
                try:
                    value = self._request_json("GET", path)
                    if not isinstance(value, dict):
                        raise BridgeError(
                            "INVALID_CCAPI_RESPONSE",
                            f"Camera identity {path} did not return a JSON object.",
                            status_code=502,
                            engine=self.engine_name,
                        )
                    self._api_prefixes = [prefix]
                    self._preferred_prefix = prefix
                    self._discovery_source = f"GET {path} (identity fallback)"
                    self._cached_info = self._camera_info(value)
                    self._observed.add(CameraFeature.CAMERA_IDENTITY)
                    self._initialized = True
                    return
                except BridgeError as error:
                    failures.append(f"GET {path}: {error.message}")

            detail = "\n".join(f"- {failure}" for failure in failures[-6:])
            raise BridgeError(
                "CCAPI_DISCOVERY_FAILED",
                f"Failed to discover a Canon CCAPI camera at {self.base_url}.\n{detail}",
                status_code=502,
                engine=self.engine_name,
            )

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            try:
                self.stop_event_polling()
            except BridgeError as error:
                self._last_error = error.message
            if self._bulb_exposure_active:
                manual = self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                    "POST", "/shooting/control/shutterbutton/manual"
                )
                if manual is not None:
                    try:
                        self._command_ok(manual, {"af": False, "action": "release"})
                        self._bulb_exposure_active = False
                    except BridgeError as error:
                        self._last_error = error.message
            if self._live_view_active:
                try:
                    self._stop_live_view_locked()
                except BridgeError as error:
                    self._last_error = error.message
            self._live_view_active = False
            self._active_live_view_source = None
            if self._rtp_session is not None:
                with suppress(Exception):
                    self._rtp_session.close()
                self._rtp_session = None
            self._settings_cache = None
            self._media_cache.clear()
            self.transport.close()
            self._closed = True

    def info(self) -> CameraInfo:
        with self._lock:
            self._ensure_initialized()
            if self._cached_info is not None:
                return self._cached_info
            value = self._first_json(self._versioned_paths("/deviceinformation"), required=True)
            if not isinstance(value, dict):
                raise BridgeError(
                    "INVALID_CCAPI_RESPONSE",
                    "Camera identity did not return a JSON object.",
                    status_code=502,
                    engine=self.engine_name,
                )
            self._cached_info = self._camera_info(value)
            self._observed.add(CameraFeature.CAMERA_IDENTITY)
            return self._cached_info

    def status(self) -> CameraStatus:
        with self._lock:
            self._ensure_initialized()
            battery = self._first_json(
                self._versioned_paths("/devicestatus/batterylist") + self._versioned_paths("/devicestatus/battery")
            )
            storage = self._first_json(
                self._versioned_paths("/devicestatus/storage")
                + self._versioned_paths("/devicestatus/currentstorage")
                + [self._api_path("GET", "/contents")]
            )
            recordable_operation = self._operation("GET", "/shooting/information/recordable")
            recordable_value = (
                self._first_json([recordable_operation.path]) if recordable_operation is not None else None
            )
            recordable_status = _recordable_status(recordable_value)
            if recordable_status is None:
                self._observed.discard(CameraFeature.RECORDABLE_STATUS)
            else:
                self._observed.add(CameraFeature.RECORDABLE_STATUS)
            lens_operation = self._operation("GET", "/devicestatus/lens")
            lens_value = self._first_json([lens_operation.path]) if lens_operation is not None else None
            lens_status = _lens_status(lens_value)
            if lens_status is None:
                self._observed.discard(CameraFeature.LENS_STATUS)
            else:
                self._observed.add(CameraFeature.LENS_STATUS)
            temperature_operation = self._operation("GET", "/devicestatus/temperature")
            temperature_value = (
                self._first_json([temperature_operation.path]) if temperature_operation is not None else None
            )
            refreshed_temperature = _temperature_status(temperature_value)
            if refreshed_temperature is not None:
                self._temperature_status = refreshed_temperature
                self._observed.add(CameraFeature.TEMPERATURE_STATUS)
            settings = self._load_settings(force=True)
            battery_status = _battery_status(battery)
            storage_status = _storage_status(storage)
            if battery is not None:
                self._observed.add(CameraFeature.BATTERY_STATUS)
            if storage is not None:
                self._observed.add(CameraFeature.STORAGE_STATUS)
            rtp_audio = getattr(self._rtp_session, "audio_status", None) if self._rtp_session else None
            return CameraStatus(
                battery=battery_status,
                recording=self._recording,
                bulb_exposure_active=self._bulb_exposure_active,
                mode=_setting_value(settings, "shootingmode") or "unknown",
                media=storage_status,
                exposure=ExposureState(
                    iso=_setting_value(settings, "iso") or "-",
                    shutter=_first_setting_value(settings, "tv", "shutterspeed", "shutter") or "-",
                    aperture=_first_setting_value(settings, "av", "aperture") or "-",
                    white_balance=_first_setting_value(settings, "wb", "whitebalance", "white_balance") or "-",
                ),
                recordable_shots=recordable_status[0] if recordable_status is not None else None,
                remaining_recording_seconds=recordable_status[1] if recordable_status is not None else None,
                lens=lens_status,
                temperature=self._temperature_status,
                raw={
                    "engine": self.engine_name,
                    "baseUrl": self.base_url,
                    "apiVersions": self._api_prefixes,
                    "battery": battery,
                    "storage": storage,
                    "recordable": recordable_value,
                    "lens": lens_value,
                    "temperature": temperature_value,
                    "liveViewSource": self._active_live_view_source,
                    "rtpSource": self._rtp_session.source_url if self._rtp_session else None,
                    "rtpAudio": rtp_audio,
                    "lastError": self._last_error,
                },
            )

    def capabilities(self) -> CameraCapabilities:
        with self._lock:
            self._ensure_initialized()
            settings = self._load_settings(force=False)
            controls = self._camera_settings(settings)
            supported = {CameraFeature.DESKTOP_BRIDGE, CameraFeature.CAMERA_IDENTITY, *self._observed}
            control_keys = {control.key for control in controls}
            if control_keys & {"iso", "shutter", "aperture"}:
                supported.add(CameraFeature.EXPOSURE_CONTROL)
            if "whitebalance" in control_keys:
                supported.add(CameraFeature.WHITE_BALANCE_CONTROL)
            if ZOOM_SETTING_KEY in control_keys:
                supported.add(CameraFeature.ZOOM_CONTROL)
            if MOVIE_MODE_SETTING_KEY in control_keys:
                supported.add(CameraFeature.MOVIE_MODE_CONTROL)
            if control_keys & CARD_SELECTION_ENDPOINTS.keys():
                supported.add(CameraFeature.CARD_SELECTION_CONTROL)
            if SOUND_RECORDING_LEVEL_SETTING_KEY in control_keys:
                supported.add(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
            if control_keys & SOUND_RECORDING_ENDPOINTS.keys():
                supported.add(CameraFeature.SOUND_RECORDING_CONTROL)
            if FOCUS_BRACKETING_SETTING_KEY in control_keys:
                supported.add(CameraFeature.FOCUS_BRACKETING_CONTROL)
            if control_keys - PRIMARY_SETTING_KEYS:
                supported.add(CameraFeature.ADVANCED_SETTINGS)
            jpeg_live_view_supported = self._supports_jpeg_live_view()
            rtp_live_view_supported = self._supports_rtp_live_view()
            if jpeg_live_view_supported or rtp_live_view_supported:
                supported.add(CameraFeature.LIVE_VIEW)
            if jpeg_live_view_supported:
                supported.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
            if rtp_live_view_supported:
                supported.add(CameraFeature.LIVE_VIEW_RTP)
            if self._operation("POST", "/shooting/control/recbutton") or self._operation(
                "PUT", "/shooting/control/recbutton"
            ):
                supported.add(CameraFeature.VIDEO_RECORDING)
            if (
                self._operation("POST", "/shooting/control/shutterbutton")
                or self._operation("PUT", "/shooting/control/shutterbutton/manual")
                or self._operation("POST", "/shooting/control/shutterbutton/manual")
            ):
                supported.add(CameraFeature.STILL_CAPTURE)
            if self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            ):
                supported.add(CameraFeature.SHUTTER_HALF_PRESS)
                supported.add(CameraFeature.BULB_EXPOSURE)
            if (
                self._operation("POST", "/shooting/control/af")
                or self._operation("PUT", "/shooting/control/shutterbutton/manual")
                or self._operation("POST", "/shooting/control/shutterbutton/manual")
            ):
                supported.add(CameraFeature.AUTOFOCUS)
            if self._supports_coordinate_tap_focus():
                supported.add(CameraFeature.TAP_FOCUS)
            if self._supports_coordinate_click_white_balance():
                supported.add(CameraFeature.CLICK_WHITE_BALANCE)
            if self._operation("POST", "/shooting/control/drivefocus"):
                supported.add(CameraFeature.FOCUS_DRIVE)
            if self._supports("GET", "/contents"):
                supported.update(
                    {
                        CameraFeature.MEDIA_BROWSER,
                        CameraFeature.MEDIA_THUMBNAIL,
                        CameraFeature.MEDIA_PREVIEW,
                        CameraFeature.MEDIA_DOWNLOAD,
                    }
                )
            if self._supports_media_delete():
                supported.add(CameraFeature.MEDIA_DELETE)
            if self._event_polling_operations() is not None:
                supported.add(CameraFeature.EVENT_POLLING)
            if self._camera_clock_operations() is not None:
                supported.add(CameraFeature.CAMERA_CLOCK_SYNC)

            candidates = {
                CameraFeature.RECORDABLE_STATUS,
                CameraFeature.LENS_STATUS,
                CameraFeature.TEMPERATURE_STATUS,
                CameraFeature.EVENT_POLLING,
                CameraFeature.LIVE_VIEW_RTP,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.BULB_EXPOSURE,
                CameraFeature.AUTOFOCUS,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.MOVIE_MODE_CONTROL,
                CameraFeature.VIDEO_RECORDING,
                CameraFeature.TAP_FOCUS,
                CameraFeature.CLICK_WHITE_BALANCE,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_THUMBNAIL,
                CameraFeature.MEDIA_PREVIEW,
                CameraFeature.MEDIA_DOWNLOAD,
                CameraFeature.MEDIA_DELETE,
                CameraFeature.CAMERA_CLOCK_SYNC,
                CameraFeature.ZOOM_CONTROL,
                CameraFeature.CARD_SELECTION_CONTROL,
                CameraFeature.SOUND_RECORDING_CONTROL,
                CameraFeature.SOUND_RECORDING_LEVEL_CONTROL,
                CameraFeature.FOCUS_BRACKETING_CONTROL,
            }
            live_sizes = (
                [self._active_live_view_size] if not self._live_view_size_control else ["SMALL", "MEDIUM", "LARGE"]
            )
            live_sources = []
            if rtp_live_view_supported:
                live_sources.append("CCAPI_RTP")
            if jpeg_live_view_supported:
                live_sources.append("CCAPI_JPEG_POLLING")
            model = self.info().model
            return CameraCapabilities(
                profile=camera_profile(model),
                supported=sorted(supported, key=str),
                planned=sorted(candidates - supported, key=str),
                reasons={
                    CameraFeature.RECORDABLE_STATUS.value: (
                        "The camera must advertise GET shooting/information/recordable and return Canon's "
                        "documented nullable integer payload."
                    ),
                    CameraFeature.LENS_STATUS.value: (
                        "The camera must advertise GET devicestatus/lens and return Canon's documented "
                        "mount/name payload."
                    ),
                    CameraFeature.TEMPERATURE_STATUS.value: (
                        "The camera must advertise GET devicestatus/temperature and return a documented "
                        "Canon status value."
                    ),
                    CameraFeature.EVENT_POLLING.value: (
                        "The camera must advertise both GET and DELETE for the Canon event polling endpoint."
                    ),
                    CameraFeature.CAMERA_CLOCK_SYNC.value: (
                        "The camera must advertise both GET and PUT for the Canon date-time endpoint "
                        "in the same API version."
                    ),
                    CameraFeature.LIVE_VIEW_RTP.value: (self._rtp_capability_reason()),
                    CameraFeature.FOCUS_DRIVE.value: (
                        "The camera did not advertise the verified CCAPI POST drivefocus operation."
                    ),
                    CameraFeature.ZOOM_CONTROL.value: (
                        "The camera must advertise readable and writable Canon zoom control in the same API version."
                    ),
                    CameraFeature.CARD_SELECTION_CONTROL.value: (
                        "The camera must advertise matching GET and PUT Canon card-selection endpoints "
                        "and valid card abilities."
                    ),
                    CameraFeature.SOUND_RECORDING_CONTROL.value: (
                        "The camera must advertise matching GET and PUT Canon sound-recording-setting "
                        "endpoints and valid documented abilities."
                    ),
                    CameraFeature.SOUND_RECORDING_LEVEL_CONTROL.value: (
                        "The camera must advertise matching GET and PUT Canon sound-recording-level endpoints "
                        "and a valid integer range."
                    ),
                    CameraFeature.FOCUS_BRACKETING_CONTROL.value: (
                        "The camera must advertise matching GET and PUT Canon focus-bracketing endpoints "
                        "and valid documented abilities."
                    ),
                    CameraFeature.MOVIE_MODE_CONTROL.value: (
                        "The camera must advertise readable and writable Canon movie mode control "
                        "in the same API version."
                    ),
                    CameraFeature.AUTOFOCUS.value: (
                        "The camera advertised neither CCAPI POST autofocus nor a verified manual half-press operation."
                    ),
                    CameraFeature.TAP_FOCUS.value: (
                        "The camera must advertise PUT afframeposition and detailed Live View metadata "
                        "for coordinate Tap AF."
                    ),
                    CameraFeature.CLICK_WHITE_BALANCE.value: (
                        "The camera must advertise POST clickwb and detailed Live View metadata for Click WB."
                    ),
                },
                live_view=(
                    LiveViewCapabilities(
                        sources=live_sources,
                        default_source=live_sources[0],
                        sizes=live_sizes if jpeg_live_view_supported else [],
                        default_size=(
                            "MEDIUM" if jpeg_live_view_supported and "MEDIUM" in live_sizes else live_sizes[0]
                        )
                        if jpeg_live_view_supported
                        else None,
                        min_fps=1,
                        max_fps=30,
                    )
                    if CameraFeature.LIVE_VIEW in supported
                    else LiveViewCapabilities()
                ),
                settings=controls,
                evidence=self._capability_evidence(),
            )

    def poll_event(self) -> CameraEvent:
        with self._lock:
            self._ensure_initialized()
            operations = self._event_polling_operations()
        if operations is None:
            raise unsupported(CameraFeature.EVENT_POLLING.value, self.engine_name)
        poll, _ = operations
        parameter = "timeout=long" if _path_version(poll.path) >= 110 else "continue=on"
        separator = "&" if "?" in poll.path else "?"
        with self._event_lock:
            value = self._request_json(
                "GET",
                f"{poll.path}{separator}{parameter}",
                timeout=40.0,
                max_bytes=MAX_EVENT_BYTES,
            )
        if not isinstance(value, dict):
            raise BridgeError(
                "INVALID_CCAPI_EVENT",
                "Canon event polling did not return a JSON object.",
                status_code=502,
                engine=self.engine_name,
            )
        changed_keys = _safe_event_keys(value.keys())
        with self._lock:
            if not self._closed:
                self._observed.add(CameraFeature.EVENT_POLLING)
        return CameraEvent(changed_keys=changed_keys)

    def stop_event_polling(self) -> None:
        operations = self._event_polling_operations()
        if operations is None or self._closed:
            return
        _, stop = operations
        self._request_ok("DELETE", stop.path, timeout=5.0)

    def set_setting(self, key: str, value: str) -> CameraStatus:
        with self._lock:
            self._ensure_initialized()
            canonical = SETTING_ALIASES.get(key, key)
            settings = self._load_settings(
                canonical in CARD_SELECTION_ENDPOINTS
                or canonical in SOUND_RECORDING_ENDPOINTS
                or canonical == SOUND_RECORDING_LEVEL_SETTING_KEY
                or canonical in FOCUS_BRACKETING_SETTING_KEYS
            )
            control = next(
                (item for item in self._camera_settings(settings) if item.key == canonical),
                None,
            )
            if control is None:
                raise unsupported(_feature_for_setting(canonical).value, self.engine_name)
            if value not in control.values:
                raise BridgeError(
                    "INVALID_SETTING_VALUE",
                    f"Value '{value}' is not advertised for {control.label}.",
                    status_code=422,
                    engine=self.engine_name,
                )
            structured = _structured_setting_parts(canonical)
            path = self._setting_paths.get(structured[0] if structured else canonical)
            if path is None:
                raise unsupported(_feature_for_setting(canonical).value, self.engine_name)
            if canonical == MOVIE_MODE_SETTING_KEY:
                self._request_ok("POST", path, {"action": value})
            elif canonical == ZOOM_SETTING_KEY:
                try:
                    zoom = int(value)
                except ValueError as error:
                    raise BridgeError(
                        "INVALID_SETTING_VALUE",
                        "Zoom value must be an integer advertised by the camera.",
                        status_code=422,
                        engine=self.engine_name,
                    ) from error
                if str(zoom) != value:
                    raise BridgeError(
                        "INVALID_SETTING_VALUE",
                        "Zoom value must be an integer advertised by the camera.",
                        status_code=422,
                        engine=self.engine_name,
                    )
                self._request_json("POST", path, {"value": zoom})
            elif canonical == SOUND_RECORDING_LEVEL_SETTING_KEY:
                try:
                    level = int(value)
                except ValueError as error:
                    raise BridgeError(
                        "INVALID_SETTING_VALUE",
                        "Sound recording level must be an integer advertised by the camera.",
                        status_code=422,
                        engine=self.engine_name,
                    ) from error
                if str(level) != value:
                    raise BridgeError(
                        "INVALID_SETTING_VALUE",
                        "Sound recording level must be an integer advertised by the camera.",
                        status_code=422,
                        engine=self.engine_name,
                    )
                self._request_json("PUT", path, {"value": level})
            elif canonical in FOCUS_BRACKETING_INTEGER_ENDPOINTS:
                try:
                    integer = int(value)
                except ValueError as error:
                    raise BridgeError(
                        "INVALID_SETTING_VALUE",
                        f"{control.label} must be an integer advertised by the camera.",
                        status_code=422,
                        engine=self.engine_name,
                    ) from error
                if str(integer) != value:
                    raise BridgeError(
                        "INVALID_SETTING_VALUE",
                        f"{control.label} must be an integer advertised by the camera.",
                        status_code=422,
                        engine=self.engine_name,
                    )
                self._request_json("PUT", path, {"value": integer})
            elif structured:
                base_key, field = structured
                raw = settings.get(base_key)
                current = raw.get("value") if isinstance(raw, dict) else None
                if not isinstance(current, dict):
                    raise unsupported(_feature_for_setting(canonical).value, self.engine_name)
                if base_key == WB_SHIFT_SETTING_KEY and any(
                    isinstance(current.get(item), bool) or not isinstance(current.get(item), int)
                    for item in WB_SHIFT_FIELDS
                ):
                    raise unsupported(_feature_for_setting(canonical).value, self.engine_name)
                updated = dict(current)
                updated[field] = int(value) if base_key == WB_SHIFT_SETTING_KEY else value
                if base_key == IMAGE_QUALITY_SETTING_KEY:
                    active = [
                        current_value
                        for item in IMAGE_QUALITY_FIELDS
                        if (current_value := _string_value(updated.get(item)))
                    ]
                    if active and all(item.casefold() == "none" for item in active):
                        raise BridgeError(
                            "INVALID_SETTING_VALUE",
                            "At least one still image format must remain enabled.",
                            status_code=422,
                            engine=self.engine_name,
                        )
                self._request_ok("PUT", path, {"value": updated})
            else:
                self._request_ok("PUT", path, {"value": value})
            self._settings_cache = None
            self._observed.add(_feature_for_setting(canonical))
            return self.status()

    def sync_camera_clock(self) -> CameraStatus:
        with self._lock:
            self._ensure_initialized()
            operations = self._camera_clock_operations()
            if operations is None:
                raise unsupported(
                    CameraFeature.CAMERA_CLOCK_SYNC.value,
                    self.engine_name,
                    "The camera did not advertise a readable and writable CCAPI date-time endpoint "
                    "in the same API version.",
                )
            read, write = operations
            requested = datetime.now().astimezone()
            daylight = bool(requested.dst() and requested.dst().total_seconds())
            payload = {"datetime": format_datetime(requested), "dst": daylight}
            self._validate_camera_clock_response(self._request_json(write.method, write.path, payload))
            reported, reported_daylight = self._validate_camera_clock_response(
                self._request_json(read.method, read.path)
            )
            drift = abs((reported.astimezone(UTC) - requested.astimezone(UTC)).total_seconds())
            if drift > 10 or reported_daylight != daylight:
                raise BridgeError(
                    "CAMERA_CLOCK_VERIFY_FAILED",
                    "The camera did not report the requested date, time, and daylight-saving state.",
                    status_code=502,
                    feature=CameraFeature.CAMERA_CLOCK_SYNC.value,
                    engine=self.engine_name,
                )
            self._observed.add(CameraFeature.CAMERA_CLOCK_SYNC)
            return self.status()

    def capture_still(self) -> CameraStatus:
        with self._lock:
            self._refresh_temperature_status()
            self._require_temperature_allows_still_capture()
            direct = self._operation("POST", "/shooting/control/shutterbutton")
            manual = self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            )
            if direct:
                self._command_ok(direct, {"af": True})
            elif manual:
                self._guaranteed_release(
                    manual,
                    {"af": True, "action": "full_press"},
                    {"af": False, "action": "release"},
                )
            else:
                raise unsupported(CameraFeature.STILL_CAPTURE.value, self.engine_name)
            self._observed.add(CameraFeature.STILL_CAPTURE)
            return self.status()

    def half_press_shutter(self) -> CameraStatus:
        with self._lock:
            manual = self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            )
            if manual is None:
                raise unsupported(CameraFeature.SHUTTER_HALF_PRESS.value, self.engine_name)
            self._guaranteed_release(
                manual,
                {"af": True, "action": "half_press"},
                {"af": False, "action": "release"},
                hold=HALF_PRESS_SECONDS,
            )
            self._observed.add(CameraFeature.SHUTTER_HALF_PRESS)
            return self.status()

    def start_bulb_exposure(self) -> CameraStatus:
        with self._lock:
            if self._bulb_exposure_active:
                return self.status()
            manual = self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            )
            if manual is None:
                raise unsupported(CameraFeature.BULB_EXPOSURE.value, self.engine_name)
            baseline = self.status()
            self._require_temperature_allows_still_capture()
            try:
                self._command_ok(manual, {"af": False, "action": "full_press"})
            except BridgeError as error:
                try:
                    self._command_ok(manual, {"af": False, "action": "release"})
                except BridgeError as release_error:
                    error.add_note(f"Bulb cleanup failed: {release_error.message}")
                raise
            self._bulb_exposure_active = True
            return baseline.model_copy(update={"bulb_exposure_active": True})

    def stop_bulb_exposure(self) -> CameraStatus:
        with self._lock:
            if not self._bulb_exposure_active:
                return self.status()
            manual = self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            )
            if manual is None:
                raise unsupported(CameraFeature.BULB_EXPOSURE.value, self.engine_name)
            self._command_ok(manual, {"af": False, "action": "release"})
            self._bulb_exposure_active = False
            self._observed.add(CameraFeature.BULB_EXPOSURE)
            return self.status()

    def autofocus(self) -> CameraStatus:
        with self._lock:
            operation = self._operation("POST", "/shooting/control/af")
            manual = self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            )
            if operation is not None:
                self._guaranteed_release(
                    operation,
                    {"action": "start"},
                    {"action": "stop"},
                    hold=HALF_PRESS_SECONDS,
                )
            elif manual is not None:
                self._guaranteed_release(
                    manual,
                    {"af": True, "action": "half_press"},
                    {"af": False, "action": "release"},
                    hold=HALF_PRESS_SECONDS,
                )
            else:
                raise unsupported(CameraFeature.AUTOFOCUS.value, self.engine_name)
            self._observed.add(CameraFeature.AUTOFOCUS)
            return self.status()

    def start_recording(self) -> CameraStatus:
        return self._set_recording(True)

    def stop_recording(self) -> CameraStatus:
        return self._set_recording(False)

    def drive_focus(self, direction: str, step: str) -> FocusResult:
        with self._lock:
            normalized_direction = direction.strip().upper()
            normalized_step = step.strip().upper()
            step_number = {"SMALL": 1, "MEDIUM": 2, "LARGE": 3}.get(normalized_step)
            if normalized_direction not in {"NEAR", "FAR"} or step_number is None:
                raise BridgeError("INVALID_FOCUS_DRIVE", "direction and step are invalid.", status_code=422)
            operation = self._operation("POST", "/shooting/control/drivefocus")
            if operation is None:
                raise unsupported(CameraFeature.FOCUS_DRIVE.value, self.engine_name)
            self._command_ok(operation, {"value": f"{normalized_direction.lower()}{step_number}"})
            self._observed.add(CameraFeature.FOCUS_DRIVE)
            return FocusResult(accepted=True, direction=normalized_direction, step=normalized_step)

    def set_live_view_magnification(self, value: int) -> LiveViewMagnificationResult:
        del value
        raise unsupported(
            CameraFeature.LIVE_VIEW_MAGNIFICATION.value,
            self.engine_name,
            "Canon EOS CCAPI does not advertise a Live View focus-magnification command.",
        )

    def tap_focus(self, x: float, y: float) -> FocusResult:
        with self._lock:
            operation = self._operation("PUT", "/shooting/liveview/afframeposition")
            if operation is None or not self._supports_coordinate_tap_focus():
                raise unsupported(CameraFeature.TAP_FOCUS.value, self.engine_name)
            self._ensure_live_view_geometry_for_native_stream()
            position_x, position_y = self._camera_live_view_position(x, y, CameraFeature.TAP_FOCUS)
            self._command_ok(operation, {"positionx": position_x, "positiony": position_y})
            self._observed.add(CameraFeature.TAP_FOCUS)
            return FocusResult(accepted=True, x=x, y=y)

    def click_white_balance(self, x: float, y: float) -> CameraStatus:
        with self._lock:
            operation = self._operation("POST", "/shooting/liveview/clickwb")
            if operation is None or not self._supports_coordinate_click_white_balance():
                raise unsupported(CameraFeature.CLICK_WHITE_BALANCE.value, self.engine_name)
            self._ensure_live_view_geometry_for_native_stream()
            position_x, position_y = self._camera_live_view_position(
                x,
                y,
                CameraFeature.CLICK_WHITE_BALANCE,
            )
            self._command_ok(operation, {"positionx": position_x, "positiony": position_y})
            self._observed.add(CameraFeature.CLICK_WHITE_BALANCE)
            return self.status()

    def start_live_view(self, request: LiveViewStartRequest) -> None:
        with self._lock:
            self._ensure_initialized()
            self._refresh_temperature_status()
            self._require_temperature_allows_live_view()
            self._latest_live_view_geometry = None
            source = request.source.upper()
            if source == "DESKTOP_BRIDGE_STREAM":
                source = "AUTO"
            if source not in {"AUTO", "CCAPI_RTP", "CCAPI_JPEG_POLLING"}:
                raise BridgeError("INVALID_LIVE_VIEW_SOURCE", "Unsupported CCAPI Live View source.", status_code=422)
            size = request.size.upper()
            if size not in {"SMALL", "MEDIUM", "LARGE"}:
                raise BridgeError("INVALID_LIVE_VIEW_SIZE", "Unsupported CCAPI Live View size.", status_code=422)

            jpeg_supported = self._supports_jpeg_live_view()
            rtp_supported = self._supports_rtp_live_view()
            selected = "CCAPI_RTP" if source == "AUTO" and rtp_supported else source
            if selected == "AUTO":
                selected = "CCAPI_JPEG_POLLING"
            if selected == "CCAPI_RTP":
                if not rtp_supported:
                    raise unsupported(
                        CameraFeature.LIVE_VIEW_RTP.value,
                        self.engine_name,
                        self._rtp_capability_reason(),
                    )
                try:
                    self._start_rtp_live_view(request)
                    return
                except BridgeError:
                    if source != "AUTO" or not jpeg_supported:
                        raise
            if not jpeg_supported:
                raise unsupported(CameraFeature.LIVE_VIEW.value, self.engine_name)
            self._start_jpeg_live_view(request, size)

    def _start_jpeg_live_view(self, request: LiveViewStartRequest, size: str) -> None:
        path = self._api_path("POST", "/shooting/liveview")
        try:
            self._request_ok("POST", path, {"cameradisplay": "on", "liveviewsize": size.casefold()})
            self._live_view_size_control = True
        except _CcapiHTTPError as error:
            if error.camera_status != 400:
                raise
            self._request_ok("POST", path, {"cameradisplay": "on"})
            self._live_view_size_control = False
        self._active_live_view_size = size
        self._requested_fps = max(1, min(30, request.fps))
        self._live_view_active = True
        self._active_live_view_source = "CCAPI_JPEG_POLLING"
        self._observed.update({CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_JPEG_POLLING})

    def _start_rtp_live_view(self, request: LiveViewStartRequest) -> None:
        factory = self._rtp_session_factory
        destination = self._rtp_destination_address
        if factory is None or destination is None:
            raise unsupported(CameraFeature.LIVE_VIEW_RTP.value, self.engine_name, self._rtp_capability_reason())
        description_path = self._api_path("GET", "/shooting/liveview/rtpsessiondesc")
        control_path = self._api_path("POST", "/shooting/liveview/rtp")
        response = self._request("GET", description_path, max_bytes=MAX_RTP_SESSION_DESCRIPTION_BYTES)
        session: RtpLiveViewSession | None = None
        try:
            description = parse_sdp(response.body.decode("utf-8"))
            session = factory(description, destination)
            session.set_target_fps(request.fps)
            session.start()
        except Exception as error:
            if session is not None:
                with suppress(Exception):
                    session.close()
            raise BridgeError(
                "CCAPI_RTP_START_FAILED",
                f"Could not prepare the Canon RTP receiver: {error}",
                status_code=502,
                feature=CameraFeature.LIVE_VIEW_RTP.value,
                engine=self.engine_name,
            ) from error
        assert session is not None
        try:
            self._request_ok("POST", control_path, {"action": "start", "ipaddress": destination})
            session.wait_until_ready(timeout=5.0)
        except Exception as error:
            with suppress(Exception):
                session.close()
            with suppress(BridgeError):
                self._request_ok("POST", control_path, {"action": "stop", "ipaddress": ""})
            if isinstance(error, BridgeError):
                raise
            raise BridgeError(
                "CCAPI_RTP_START_FAILED",
                f"Canon RTP started but no decoded video became ready: {error}",
                status_code=502,
                feature=CameraFeature.LIVE_VIEW_RTP.value,
                engine=self.engine_name,
            ) from error
        if self._rtp_session is not None:
            with suppress(Exception):
                self._rtp_session.close()
        self._rtp_session = session
        self._requested_fps = max(1, min(30, request.fps))
        self._live_view_active = True
        self._active_live_view_source = "CCAPI_RTP"
        self._observed.update({CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_RTP})

    def stop_live_view(self) -> None:
        with self._lock:
            self._require_open()
            self._latest_live_view_geometry = None
            if not self._live_view_active:
                return
            self._stop_live_view_locked()

    def _stop_live_view_locked(self) -> None:
        source = self._active_live_view_source
        try:
            if source == "CCAPI_RTP":
                self._request_ok(
                    "POST",
                    self._api_path("POST", "/shooting/liveview/rtp"),
                    {"action": "stop", "ipaddress": ""},
                )
            else:
                self._request_ok("DELETE", self._api_path("DELETE", "/shooting/liveview"))
        finally:
            if self._rtp_session is not None:
                with suppress(Exception):
                    self._rtp_session.close()
                self._rtp_session = None
            self._live_view_active = False
            self._active_live_view_source = None

    def live_view_frame(self) -> bytes:
        with self._lock:
            self._require_open()
            if not self._live_view_active:
                raise BridgeError(
                    "LIVE_VIEW_NOT_STARTED",
                    "Start Live View before requesting a frame.",
                    status_code=409,
                    feature=CameraFeature.LIVE_VIEW.value,
                    engine=self.engine_name,
                )
            if self._active_live_view_source == "CCAPI_RTP":
                session = self._rtp_session
                if session is None:
                    raise BridgeError(
                        "CCAPI_RTP_SESSION_MISSING",
                        "Canon RTP Live View is active without a receiver session.",
                        status_code=502,
                        feature=CameraFeature.LIVE_VIEW_RTP.value,
                        engine=self.engine_name,
                    )
            else:
                return self._jpeg_live_view_frame()
        try:
            return session.read_frame(timeout=5.0)
        except (RtpError, OSError, RuntimeError) as error:
            with self._lock:
                self._last_error = str(error)
            raise BridgeError(
                "CCAPI_RTP_FRAME_FAILED",
                str(error),
                status_code=502,
                feature=CameraFeature.LIVE_VIEW_RTP.value,
                engine=self.engine_name,
            ) from error

    def live_view_audio(self, after_generation: int = 0, timeout: float = 1.0) -> RtpAudioChunk | None:
        with self._lock:
            self._require_open()
            session = self._rtp_session
            if not self._live_view_active or self._active_live_view_source != "CCAPI_RTP" or session is None:
                raise unsupported(
                    RTP_AUDIO_FEATURE,
                    self.engine_name,
                    "Canon RTP Live View with an advertised audio stream must be active before requesting audio.",
                )
            audio_status = getattr(session, "audio_status", {})
            if not isinstance(audio_status, dict) or not audio_status.get("available"):
                reason = audio_status.get("reason") if isinstance(audio_status, dict) else None
                raise unsupported(RTP_AUDIO_FEATURE, self.engine_name, str(reason) if reason else None)
            reader = getattr(session, "read_audio", None)
            if not callable(reader):
                raise unsupported(RTP_AUDIO_FEATURE, self.engine_name, "The active RTP receiver has no audio path.")
        try:
            return reader(after_generation=after_generation, timeout=timeout)
        except (RtpError, OSError, RuntimeError) as error:
            with self._lock:
                self._last_error = str(error)
            raise BridgeError(
                "CCAPI_RTP_AUDIO_FAILED",
                str(error),
                status_code=502,
                feature=RTP_AUDIO_FEATURE,
                engine=self.engine_name,
            ) from error

    def _jpeg_live_view_frame(self) -> bytes:
        self._frame_key += 1
        candidates = self._live_view_frame_paths()
        failures: list[str] = []
        for candidate in candidates:
            separator = "&" if "?" in candidate else "?"
            path = f"{candidate}{separator}t={self._frame_key}"
            try:
                if "flipdetail" in candidate and "kind=both" in candidate:
                    self._latest_live_view_geometry = None
                response = self._request(
                    "GET",
                    path,
                    headers={
                        "Accept": "multipart/x-mixed-replace,image/jpeg,image/*,*/*",
                        "Connection": "close",
                        "Pragma": "no-cache",
                    },
                    max_bytes=MAX_LIVE_VIEW_SCAN_BYTES,
                )
                content_type = response.headers.get("content-type", "")
                if _is_text_content_type(content_type):
                    raise BridgeError(
                        "INVALID_LIVE_VIEW_FRAME",
                        f"Camera returned {content_type or 'text'} instead of image bytes.",
                        status_code=502,
                        engine=self.engine_name,
                    )
                if "flipdetail" in candidate and "kind=both" in candidate:
                    image, geometry = _parse_detailed_live_view(response.body)
                    if geometry is not None:
                        self._latest_live_view_geometry = geometry
                    if image is None:
                        raise BridgeError(
                            "INVALID_LIVE_VIEW_FRAME",
                            "Detailed Live View response did not contain an image packet.",
                            status_code=502,
                            engine=self.engine_name,
                        )
                    return image
                return _extract_jpeg(response.body)
            except BridgeError as error:
                failures.append(f"{candidate}: {error.message}")
        raise BridgeError(
            "INVALID_LIVE_VIEW_FRAME",
            "Live View failed on every advertised JPEG endpoint.\n" + "\n".join(f"- {item}" for item in failures),
            status_code=502,
            feature=CameraFeature.LIVE_VIEW.value,
            engine=self.engine_name,
        )

    def list_media(self) -> list[MediaItem]:
        with self._lock:
            self._ensure_initialized()
            if not self._supports("GET", "/contents"):
                raise unsupported(CameraFeature.MEDIA_BROWSER.value, self.engine_name)
            pending: deque[tuple[str, int]] = deque([(self._api_path("GET", "/contents"), 0)])
            visited: set[str] = set()
            media_paths: list[str] = []
            while pending and len(media_paths) < MAX_MEDIA_ITEMS:
                raw_container, depth = pending.popleft()
                container = self._normalize_resource(raw_container).split("?", 1)[0]
                if depth > MAX_MEDIA_TREE_DEPTH or container in visited:
                    continue
                visited.add(container)
                for raw_path in self._content_paths(container):
                    path = self._normalize_resource(raw_path).split("?", 1)[0]
                    if _is_media_path(path):
                        if path not in media_paths:
                            media_paths.append(path)
                    elif path not in visited:
                        pending.append((path, depth + 1))
            items = [
                MediaItem(
                    id=_media_id(path),
                    name=path.rsplit("/", 1)[-1],
                    kind=_media_kind(path),
                    content_type=mimetypes.guess_type(path)[0] or "application/octet-stream",
                    preview_available=_media_kind(path) in {"image", "raw"},
                )
                for path in media_paths[:MAX_MEDIA_ITEMS]
            ]
            self._media_cache = {item.id: item for item in items}
            self._observed.add(CameraFeature.MEDIA_BROWSER)
            return items

    def download_media(self, media_id: str) -> tuple[MediaItem, Iterator[bytes]]:
        with self._lock:
            self._ensure_initialized()
            if not self._supports("GET", "/contents"):
                raise unsupported(CameraFeature.MEDIA_DOWNLOAD.value, self.engine_name)
            path = self._normalize_resource(_decode_media_id(media_id)).split("?", 1)[0]
            cached = self._media_cache.get(media_id)
            name = path.rsplit("/", 1)[-1]
            failures: list[str] = []
            for candidate in (path, f"{path}?kind=main", f"{path}?type=main"):
                response = self.transport.open_stream(
                    "GET",
                    self._url(candidate),
                    headers={"Accept": "application/octet-stream,*/*", "Cache-Control": "no-cache"},
                    timeout=600.0,
                )
                if 200 <= response.status < 300:
                    content_type = response.headers.get("content-type", "").split(";", 1)[0]
                    if _is_text_content_type(content_type):
                        try:
                            preview = response.body.read(MAX_ERROR_BYTES).decode("utf-8", errors="replace").strip()
                            failures.append(f"{candidate}: HTTP {response.status}: {preview}")
                        finally:
                            response.close()
                        continue
                    content_type = content_type or mimetypes.guess_type(name)[0] or "application/octet-stream"
                    size = _positive_int(response.headers.get("content-length")) or (cached.size_bytes if cached else 0)
                    item = cached or MediaItem(
                        id=media_id,
                        name=name,
                        kind=_media_kind(path),
                        content_type=content_type,
                        preview_available=_media_kind(path) in {"image", "raw"},
                    )
                    item = item.model_copy(update={"size_bytes": size, "content_type": content_type})

                    def stream(active: CcapiStreamResponse = response) -> Iterator[bytes]:
                        with self._lock:
                            try:
                                while chunk := active.body.read(TRANSFER_CHUNK_BYTES):
                                    yield chunk
                                self._observed.add(CameraFeature.MEDIA_DOWNLOAD)
                            finally:
                                active.close()

                    return item, stream()
                try:
                    preview = response.body.read(MAX_ERROR_BYTES).decode("utf-8", errors="replace").strip()
                    failures.append(f"{candidate}: HTTP {response.status}: {preview}")
                finally:
                    response.close()
            raise BridgeError(
                "CCAPI_MEDIA_DOWNLOAD_FAILED",
                f"Media download failed for '{name}'.\n" + "\n".join(f"- {item}" for item in failures),
                status_code=502,
                feature=CameraFeature.MEDIA_DOWNLOAD.value,
                engine=self.engine_name,
            )

    def media_thumbnail(self, media_id: str) -> tuple[bytes, str]:
        content, content_type = self._media_image_representation(
            media_id,
            representation="thumbnail",
            max_bytes=MAX_MEDIA_THUMBNAIL_BYTES,
            feature=CameraFeature.MEDIA_THUMBNAIL,
            label="thumbnail",
        )
        return content, content_type

    def media_preview(self, media_id: str) -> tuple[bytes, str]:
        path = self._normalize_resource(_decode_media_id(media_id)).split("?", 1)[0]
        if _media_kind(path) not in {"image", "raw"}:
            raise BridgeError(
                "INVALID_MEDIA_PREVIEW",
                "Display preview is available only for camera image items.",
                status_code=422,
                feature=CameraFeature.MEDIA_PREVIEW.value,
                engine=self.engine_name,
            )
        content, content_type = self._media_image_representation(
            media_id,
            representation="display",
            max_bytes=MAX_MEDIA_PREVIEW_BYTES,
            feature=CameraFeature.MEDIA_PREVIEW,
            label="display preview",
        )
        return content, content_type

    def _media_image_representation(
        self,
        media_id: str,
        *,
        representation: str,
        max_bytes: int,
        feature: CameraFeature,
        label: str,
    ) -> tuple[bytes, str]:
        with self._lock:
            self._ensure_initialized()
            if not self._supports("GET", "/contents"):
                raise unsupported(feature.value, self.engine_name)
            path = self._normalize_resource(_decode_media_id(media_id)).split("?", 1)[0]
            parsed = urlsplit(path)
            representation_path = urlunsplit(("", "", parsed.path, urlencode({"kind": representation}), ""))
            response = self._request(
                "GET",
                representation_path,
                headers={"Accept": "image/*,application/octet-stream;q=0.5", "Cache-Control": "no-cache"},
                max_bytes=max_bytes,
            )
            if len(response.body) > max_bytes:
                raise BridgeError(
                    "CCAPI_RESPONSE_TOO_LARGE",
                    f"The camera {label} exceeded the {max_bytes}-byte safety limit.",
                    status_code=502,
                    feature=feature.value,
                    engine=self.engine_name,
                )
            content_type = response.headers.get("content-type", "").split(";", 1)[0].strip().casefold()
            detected_type = _image_content_type(response.body)
            invalid_code = (
                "INVALID_MEDIA_THUMBNAIL" if feature == CameraFeature.MEDIA_THUMBNAIL else "INVALID_MEDIA_PREVIEW"
            )
            if not response.body:
                raise BridgeError(
                    invalid_code,
                    f"Camera returned an empty media {label}.",
                    status_code=502,
                    feature=feature.value,
                    engine=self.engine_name,
                )
            if (
                _is_text_content_type(content_type)
                or _looks_like_text_payload(response.body)
                or (not content_type.startswith("image/") and detected_type is None)
            ):
                raise BridgeError(
                    invalid_code,
                    f"Camera did not return a recognized image {label}.",
                    status_code=502,
                    feature=feature.value,
                    engine=self.engine_name,
                )
            self._observed.add(feature)
            return response.body, content_type if content_type.startswith("image/") else detected_type or "image/jpeg"

    def delete_media(self, media_id: str) -> None:
        with self._lock:
            self._ensure_initialized()
            if not self._supports_media_delete():
                raise unsupported(CameraFeature.MEDIA_DELETE.value, self.engine_name)
            path = self._normalize_resource(_decode_media_id(media_id)).split("?", 1)[0]
            self._request_ok("DELETE", path)
            self._media_cache.pop(media_id, None)
            self._observed.add(CameraFeature.MEDIA_DELETE)

    @property
    def requested_fps(self) -> int:
        return self._requested_fps

    @property
    def live_view_active(self) -> bool:
        return self._live_view_active

    @property
    def live_view_source(self) -> str | None:
        return self._active_live_view_source

    def _temperature_restriction(self, feature: CameraFeature, message: str) -> BridgeError:
        return BridgeError(
            "CAMERA_TEMPERATURE_RESTRICTION",
            message,
            status_code=409,
            feature=feature.value,
            engine=self.engine_name,
        )

    def _refresh_temperature_status(self) -> None:
        operation = self._operation("GET", "/devicestatus/temperature")
        if operation is None:
            return
        refreshed = _temperature_status(self._first_json([operation.path]))
        if refreshed is not None:
            self._temperature_status = refreshed
            self._observed.add(CameraFeature.TEMPERATURE_STATUS)

    def _require_temperature_allows_live_view(self) -> None:
        if self._temperature_status is not None and not self._temperature_status.live_view_allowed:
            raise self._temperature_restriction(
                CameraFeature.LIVE_VIEW,
                "Live View is unavailable because the camera reported a temperature restriction.",
            )

    def _require_temperature_allows_still_capture(self) -> None:
        if self._temperature_status is not None and not self._temperature_status.still_capture_allowed:
            raise self._temperature_restriction(
                CameraFeature.STILL_CAPTURE,
                "Still capture is unavailable because the camera reported a temperature restriction.",
            )

    def _require_temperature_allows_movie_recording(self) -> None:
        if self._temperature_status is not None and not self._temperature_status.movie_recording_allowed:
            raise self._temperature_restriction(
                CameraFeature.VIDEO_RECORDING,
                "Movie recording is unavailable because the camera reported a temperature restriction.",
            )

    def _set_recording(self, recording: bool) -> CameraStatus:
        with self._lock:
            if recording:
                self._refresh_temperature_status()
                self._require_temperature_allows_movie_recording()
            operation = self._operation("POST", "/shooting/control/recbutton") or self._operation(
                "PUT", "/shooting/control/recbutton"
            )
            if operation is None:
                raise unsupported(CameraFeature.VIDEO_RECORDING.value, self.engine_name)
            self._command_ok(operation, {"action": "start" if recording else "stop"})
            self._recording = recording
            self._observed.add(CameraFeature.VIDEO_RECORDING)
            return self.status()

    def _guaranteed_release(
        self,
        operation: CcapiOperation,
        press: dict[str, object],
        release: dict[str, object],
        *,
        hold: float = 0.0,
    ) -> None:
        primary: Exception | None = None
        try:
            self._command_ok(operation, press)
            if hold:
                self._sleep(hold)
        except Exception as error:
            primary = error
        try:
            self._command_ok(operation, release)
        except Exception as release_error:
            if primary is None:
                raise
            self._last_error = f"{primary}; release command also failed: {release_error}"
        if primary is not None:
            raise primary

    def _command_ok(self, operation: CcapiOperation, payload: dict[str, object]) -> None:
        if operation.method not in COMMAND_METHODS:
            raise BridgeError(
                "INVALID_CCAPI_OPERATION",
                f"Unsupported command method {operation.method} for {operation.path}.",
                status_code=502,
                engine=self.engine_name,
            )
        self._request_ok(operation.method, operation.path, payload)

    def _content_paths(self, container: str) -> list[str]:
        page_info = self._first_json([f"{container}?kind=number", f"{container}?type=all,kind=number"])
        page_count = min(MAX_MEDIA_PAGES, _integer_value(page_info, "pagenumber") or 0)
        pages = range(1, page_count + 1) if page_count > 0 else (0,)
        paths: list[str] = []
        for page in pages:
            candidates = (
                [container] if page == 0 else [f"{container}?page={page}&order=desc", f"{container}?page={page}"]
            )
            value = self._first_json(candidates, required=True)
            if not isinstance(value, dict):
                continue
            raw_paths = value.get("path")
            if isinstance(raw_paths, list):
                paths.extend(item for item in raw_paths if isinstance(item, str) and item)
        return list(dict.fromkeys(paths))

    def _load_settings(self, force: bool = False) -> dict[str, object]:
        if self._settings_cache is not None and not force:
            return self._settings_cache
        merged: dict[str, object] = {}
        setting_paths: dict[str, str] = {}
        self._observed.discard(CameraFeature.CARD_SELECTION_CONTROL)
        self._observed.discard(CameraFeature.SOUND_RECORDING_CONTROL)
        self._observed.discard(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
        self._observed.discard(CameraFeature.FOCUS_BRACKETING_CONTROL)
        for path in self._versioned_paths("/shooting/settings"):
            value = self._first_json([path])
            if not isinstance(value, dict):
                continue
            prefix = path.removesuffix("/shooting/settings")
            for raw_key, setting in value.items():
                key = SETTING_ALIASES.get(raw_key, raw_key)
                if key not in merged:
                    merged[key] = setting
                    setting_path = f"{prefix}/shooting/settings/{raw_key}"
                    if (
                        key not in SOUND_RECORDING_ENDPOINTS
                        and key != SOUND_RECORDING_LEVEL_SETTING_KEY
                        and key not in FOCUS_BRACKETING_SETTING_KEYS
                        and CcapiOperation("PUT", setting_path) in self._operations
                    ):
                        setting_paths[key] = setting_path
        zoom_operations = self._zoom_operations()
        if zoom_operations is not None:
            read, write = zoom_operations
            raw = self._first_json([read.path])
            zoom = _validated_zoom_setting(raw)
            if zoom is not None:
                merged[ZOOM_SETTING_KEY] = zoom
                setting_paths[ZOOM_SETTING_KEY] = write.path
        movie_mode_operations = self._movie_mode_operations()
        if movie_mode_operations is not None:
            read, write = movie_mode_operations
            movie_mode = _validated_movie_mode_setting(self._first_json([read.path]))
            if movie_mode is not None:
                merged[MOVIE_MODE_SETTING_KEY] = movie_mode
                setting_paths[MOVIE_MODE_SETTING_KEY] = write.path
        for key, suffix in CARD_SELECTION_ENDPOINTS.items():
            operations = self._card_selection_operations(suffix)
            if operations is None:
                continue
            read, write = operations
            card_selection = _validated_card_selection_setting(self._first_json([read.path]))
            if card_selection is not None:
                merged[key] = card_selection
                setting_paths[key] = write.path
                self._observed.add(CameraFeature.CARD_SELECTION_CONTROL)
        for key, (suffix, allowed_values) in SOUND_RECORDING_ENDPOINTS.items():
            operations = self._sound_recording_operations(suffix)
            if operations is None:
                continue
            read, write = operations
            setting = _validated_string_ability_setting(self._first_json([read.path]), allowed_values)
            if setting is not None:
                merged[key] = setting
                setting_paths[key] = write.path
                self._observed.add(CameraFeature.SOUND_RECORDING_CONTROL)
        sound_operations = self._sound_recording_level_operations()
        if sound_operations is not None:
            read, write = sound_operations
            sound_recording_level = _validated_integer_range_setting(self._first_json([read.path]))
            if sound_recording_level is not None:
                merged[SOUND_RECORDING_LEVEL_SETTING_KEY] = sound_recording_level
                setting_paths[SOUND_RECORDING_LEVEL_SETTING_KEY] = write.path
                self._observed.add(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
        focus_bracketing_available = False
        for key, (suffix, allowed_values) in FOCUS_BRACKETING_STRING_ENDPOINTS.items():
            if key != FOCUS_BRACKETING_SETTING_KEY and not focus_bracketing_available:
                continue
            operations = self._read_write_setting_operations(suffix)
            if operations is None:
                continue
            read, write = operations
            setting = _validated_string_ability_setting(self._first_json([read.path]), allowed_values)
            if setting is None:
                continue
            merged[key] = setting
            setting_paths[key] = write.path
            if key == FOCUS_BRACKETING_SETTING_KEY:
                focus_bracketing_available = True
                self._observed.add(CameraFeature.FOCUS_BRACKETING_CONTROL)
        if focus_bracketing_available:
            for key, suffix in FOCUS_BRACKETING_INTEGER_ENDPOINTS.items():
                operations = self._read_write_setting_operations(suffix)
                if operations is None:
                    continue
                read, write = operations
                setting = _validated_integer_range_setting(
                    self._first_json([read.path]),
                    maximum_options=MAX_FOCUS_BRACKETING_OPTIONS,
                )
                if setting is not None:
                    merged[key] = setting
                    setting_paths[key] = write.path
        self._settings_cache = merged
        self._setting_paths = setting_paths
        return merged

    def _camera_settings(self, settings: dict[str, object]) -> list[CameraSetting]:
        controls: list[CameraSetting] = []
        for key, raw in settings.items():
            if key not in self._setting_paths:
                continue
            if not isinstance(raw, dict):
                continue
            if key == IMAGE_QUALITY_SETTING_KEY:
                controls.extend(_structured_image_quality_controls(raw))
                continue
            if key == WB_SHIFT_SETTING_KEY:
                controls.extend(_structured_wb_shift_controls(raw))
                continue
            value = _string_value(raw.get("value"))
            ability = raw.get("ability")
            values = list(dict.fromkeys(_string_value(item) for item in ability)) if isinstance(ability, list) else []
            values = [item for item in values if item]
            if not value or not values:
                continue
            controls.append(
                CameraSetting(
                    key=key,
                    label=SETTING_LABELS.get(key, _setting_label(key)),
                    value=value,
                    values=values,
                )
            )
        return sorted(
            controls,
            key=lambda item: (item.key not in PRIMARY_SETTING_KEYS, item.label.casefold(), item.key),
        )

    def _parse_discovery(self, value: dict[str, object], *, source: str) -> None:
        versions: set[str] = set()
        self._operations.clear()
        self._discovery_source = source
        api = value.get("api")
        if isinstance(api, list):
            for item in api:
                if isinstance(item, str) and (match := re.search(r"/ccapi/(ver\d+)(?:/|$)", item)):
                    versions.add(match.group(1))
        for key, entries in value.items():
            if not re.fullmatch(r"ver\d+", key) or not isinstance(entries, list):
                continue
            versions.add(key)
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                path = self._advertised_operation_path(key, entry)
                if path is None:
                    continue
                for method in HTTP_METHODS:
                    if _method_supported(entry.get(method.casefold())):
                        self._operations.add(CcapiOperation(method, path))
        version_value = value.get("version")
        if isinstance(version_value, str) and re.fullmatch(r"ver\d+", version_value):
            versions.add(version_value)
        if not versions:
            versions.add("ver100")
        ordered = sorted(versions, key=_version_number, reverse=True)
        self._api_prefixes = [f"/ccapi/{version}" for version in ordered]
        self._preferred_prefix = "/ccapi/ver100" if "/ccapi/ver100" in self._api_prefixes else self._api_prefixes[0]

    def _advertised_operation_path(
        self,
        version: str,
        entry: dict[str, object],
    ) -> str | None:
        for key in ("path", "url"):
            value = entry.get(key)
            if not isinstance(value, str) or not value.strip():
                continue
            try:
                parsed = urlsplit(value.strip())
            except ValueError:
                continue
            if parsed.fragment or parsed.username is not None or parsed.password is not None:
                continue
            if parsed.scheme or parsed.netloc:
                try:
                    path = self._normalize_resource(value.strip()).split("?", 1)[0]
                except (BridgeError, ValueError):
                    continue
            else:
                path = parsed.path
            if not path or any(segment in {".", ".."} for segment in path.split("/")):
                continue
            normalized = path if path.startswith("/ccapi/") else f"/ccapi/{version}/{path.lstrip('/')}"
            if normalized.startswith("/ccapi/") and "\r" not in normalized and "\n" not in normalized:
                return normalized
        return None

    def _capability_evidence(self) -> CapabilityEvidence:
        protocol_versions = [prefix.rsplit("/", 1)[-1] for prefix in self._api_prefixes]
        commands = sorted({self._evidence_command(operation) for operation in self._operations})
        writable_settings = sorted(
            {
                key.replace("\r", "").replace("\n", "")[:MAX_CAPABILITY_EVIDENCE_ITEM_CHARS]
                for key in self._setting_paths
            }
        )
        return CapabilityEvidence(
            source=self._discovery_source[:MAX_CAPABILITY_EVIDENCE_ITEM_CHARS],
            protocol_versions=protocol_versions[:MAX_CAPABILITY_EVIDENCE_ITEMS],
            advertised_commands=commands[:MAX_CAPABILITY_EVIDENCE_ITEMS],
            writable_settings=writable_settings[:MAX_CAPABILITY_EVIDENCE_ITEMS],
            observed_features=sorted(self._observed, key=str)[:MAX_CAPABILITY_EVIDENCE_ITEMS],
            truncated=(
                len(protocol_versions) > MAX_CAPABILITY_EVIDENCE_ITEMS
                or len(commands) > MAX_CAPABILITY_EVIDENCE_ITEMS
                or len(writable_settings) > MAX_CAPABILITY_EVIDENCE_ITEMS
                or len(self._observed) > MAX_CAPABILITY_EVIDENCE_ITEMS
            ),
        )

    @staticmethod
    def _evidence_command(operation: CcapiOperation) -> str:
        path = operation.path.split("?", 1)[0].replace("\r", "").replace("\n", "")
        return f"{operation.method} {path}"[:MAX_CAPABILITY_EVIDENCE_ITEM_CHARS]

    def _first_json(self, paths: list[str], required: bool = False) -> object | None:
        errors: list[str] = []
        for path in paths:
            try:
                return self._request_json("GET", path)
            except _CcapiHTTPError as error:
                errors.append(f"{path}: {error.message}")
        if required:
            raise BridgeError(
                "CCAPI_REQUEST_FAILED",
                "Camera request failed for every endpoint variant.\n" + "\n".join(f"- {item}" for item in errors),
                status_code=502,
                engine=self.engine_name,
            )
        return None

    def _request_json(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        *,
        timeout: float = 15.0,
        max_bytes: int = MAX_JSON_BYTES,
    ) -> object:
        response = self._request(method, path, payload, timeout=timeout, max_bytes=max_bytes)
        try:
            return json.loads(response.body)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise BridgeError(
                "INVALID_CCAPI_RESPONSE",
                f"Camera request {method} {path} returned invalid JSON.",
                status_code=502,
                engine=self.engine_name,
            ) from error

    def _request_ok(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        *,
        timeout: float = 15.0,
    ) -> None:
        self._request(method, path, payload, timeout=timeout)

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        *,
        headers: Mapping[str, str] | None = None,
        timeout: float = 15.0,
        max_bytes: int = MAX_JSON_BYTES,
    ) -> CcapiResponse:
        self._require_open()
        body = json.dumps(payload, separators=(",", ":")).encode() if payload is not None else None
        response = self.transport.request(
            method,
            self._url(path),
            body=body,
            headers=headers,
            timeout=timeout,
            max_bytes=max_bytes,
        )
        if not 200 <= response.status < 300:
            raise _CcapiHTTPError(method, path, response)
        return response

    def _camera_info(self, value: dict[str, object]) -> CameraInfo:
        return CameraInfo(
            model=_string_value(value.get("productname")) or "Canon Camera",
            serial=_string_value(value.get("serialnumber")) or "unknown",
            api=_string_value(value.get("version")) or "ccapi",
            manufacturer=_string_value(value.get("manufacturer")) or "Canon",
            device_version=_string_value(value.get("firmwareversion")),
            engine_version=ENGINE_VERSION,
        )

    def _operation(self, method: str, suffix: str) -> CcapiOperation | None:
        matches = [item for item in self._operations if item.method == method and item.path.endswith(suffix)]
        preferred = next((item for item in matches if item.path.startswith(self._preferred_prefix)), None)
        return preferred or max(matches, key=lambda item: _path_version(item.path), default=None)

    def _event_polling_operations(self) -> tuple[CcapiOperation, CcapiOperation] | None:
        gets = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith("/event/polling")
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for get in gets:
            prefix = get.path.removesuffix("/event/polling")
            delete = CcapiOperation("DELETE", f"{prefix}/event/polling")
            if delete in self._operations:
                return get, delete
        return None

    def _zoom_operations(self) -> tuple[CcapiOperation, CcapiOperation] | None:
        reads = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith(ZOOM_PATH_SUFFIX)
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for read in reads:
            write = CcapiOperation("POST", read.path)
            if write in self._operations:
                return read, write
        return None

    def _movie_mode_operations(self) -> tuple[CcapiOperation, CcapiOperation] | None:
        reads = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith(MOVIE_MODE_PATH_SUFFIX)
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for read in reads:
            write = CcapiOperation("POST", read.path)
            if write in self._operations:
                return read, write
        return None

    def _card_selection_operations(self, suffix: str) -> tuple[CcapiOperation, CcapiOperation] | None:
        reads = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith(suffix)
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for read in reads:
            write = CcapiOperation("PUT", read.path)
            if write in self._operations:
                return read, write
        return None

    def _sound_recording_level_operations(self) -> tuple[CcapiOperation, CcapiOperation] | None:
        reads = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith(SOUND_RECORDING_LEVEL_PATH_SUFFIX)
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for read in reads:
            write = CcapiOperation("PUT", read.path)
            if write in self._operations:
                return read, write
        return None

    def _sound_recording_operations(self, suffix: str) -> tuple[CcapiOperation, CcapiOperation] | None:
        reads = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith(suffix)
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for read in reads:
            write = CcapiOperation("PUT", read.path)
            if write in self._operations:
                return read, write
        return None

    def _read_write_setting_operations(self, suffix: str) -> tuple[CcapiOperation, CcapiOperation] | None:
        reads = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith(suffix)
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for read in reads:
            write = CcapiOperation("PUT", read.path)
            if write in self._operations:
                return read, write
        return None

    def _camera_clock_operations(self) -> tuple[CcapiOperation, CcapiOperation] | None:
        reads = sorted(
            (
                operation
                for operation in self._operations
                if operation.method == "GET" and operation.path.endswith("/functions/datetime")
            ),
            key=lambda operation: _path_version(operation.path),
            reverse=True,
        )
        for read in reads:
            prefix = read.path.removesuffix("/functions/datetime")
            write = CcapiOperation("PUT", f"{prefix}/functions/datetime")
            if write in self._operations:
                return read, write
        return None

    def _validate_camera_clock_response(self, value: object) -> tuple[datetime, bool]:
        if not isinstance(value, dict) or not isinstance(value.get("datetime"), str) or not isinstance(
            value.get("dst"), bool
        ):
            raise BridgeError(
                "INVALID_CCAPI_RESPONSE",
                "Canon date-time response must contain RFC 1123 datetime and boolean dst fields.",
                status_code=502,
                feature=CameraFeature.CAMERA_CLOCK_SYNC.value,
                engine=self.engine_name,
            )
        try:
            parsed = parsedate_to_datetime(value["datetime"])
        except (TypeError, ValueError) as error:
            raise BridgeError(
                "INVALID_CCAPI_RESPONSE",
                "Canon date-time response contains an invalid RFC 1123 value.",
                status_code=502,
                feature=CameraFeature.CAMERA_CLOCK_SYNC.value,
                engine=self.engine_name,
            ) from error
        if parsed.tzinfo is None:
            raise BridgeError(
                "INVALID_CCAPI_RESPONSE",
                "Canon date-time response is missing a UTC offset.",
                status_code=502,
                feature=CameraFeature.CAMERA_CLOCK_SYNC.value,
                engine=self.engine_name,
            )
        return parsed, value["dst"]

    def _live_view_frame_paths(self) -> list[str]:
        candidates: list[str] = []
        flip = self._operation("GET", "/shooting/liveview/flip")
        flip_detail = self._operation("GET", "/shooting/liveview/flipdetail")
        live_view = self._operation("GET", "/shooting/liveview")
        if flip_detail and self._needs_live_view_geometry():
            candidates.append(f"{flip_detail.path}?kind=both")
        if flip:
            candidates.append(flip.path)
        if flip_detail:
            candidates.append(f"{flip_detail.path}?kind=image")
        if live_view:
            candidates.append(live_view.path)
        return candidates

    def _supports_jpeg_live_view(self) -> bool:
        return bool(
            self._supports("POST", "/shooting/liveview")
            and self._supports("DELETE", "/shooting/liveview")
            and any(
                self._operation("GET", suffix)
                for suffix in (
                    "/shooting/liveview/flip",
                    "/shooting/liveview/flipdetail",
                    "/shooting/liveview",
                )
            )
        )

    def _supports_rtp_live_view(self) -> bool:
        return bool(
            self._supports("GET", "/shooting/liveview/rtpsessiondesc")
            and self._supports("POST", "/shooting/liveview/rtp")
            and self._rtp_destination_address
            and self._rtp_session_factory
        )

    def _rtp_capability_reason(self) -> str:
        if not self._supports("GET", "/shooting/liveview/rtpsessiondesc") or not self._supports(
            "POST", "/shooting/liveview/rtp"
        ):
            return "The camera did not advertise the verified Canon CCAPI RTP endpoints."
        if self._rtp_session_factory is None:
            return "The desktop PyAV H.264 decoder is unavailable. Reinstall the bridge runtime dependencies."
        if self._rtp_destination_address is None:
            return "No routed local IPv4 address is available for the camera to send Canon RTP video to."
        return "Canon CCAPI RTP H.264 Live View is available."

    def _ensure_live_view_geometry_for_native_stream(self) -> None:
        if self._active_live_view_source != "CCAPI_RTP" or self._latest_live_view_geometry is not None:
            return
        detail = self._operation("GET", "/shooting/liveview/flipdetail")
        if detail is None:
            return
        self._frame_key += 1
        response = self._request(
            "GET",
            f"{detail.path}?kind=both&t={self._frame_key}",
            headers={"Accept": "application/octet-stream,*/*", "Cache-Control": "no-cache"},
            max_bytes=MAX_LIVE_VIEW_SCAN_BYTES,
        )
        _, geometry = _parse_detailed_live_view(response.body)
        if geometry is None:
            raise BridgeError(
                "LIVE_VIEW_COORDINATES_UNAVAILABLE",
                "Detailed Live View did not contain Canon image position metadata.",
                status_code=409,
                engine=self.engine_name,
            )
        self._latest_live_view_geometry = geometry

    def _supports_coordinate_tap_focus(self) -> bool:
        return bool(
            self._operation("PUT", "/shooting/liveview/afframeposition")
            and self._operation("GET", "/shooting/liveview/flipdetail")
            and (self._supports_jpeg_live_view() or self._supports_rtp_live_view())
        )

    def _supports_coordinate_click_white_balance(self) -> bool:
        return bool(
            self._operation("POST", "/shooting/liveview/clickwb")
            and self._operation("GET", "/shooting/liveview/flipdetail")
            and (self._supports_jpeg_live_view() or self._supports_rtp_live_view())
        )

    def _needs_live_view_geometry(self) -> bool:
        return self._supports_coordinate_tap_focus() or self._supports_coordinate_click_white_balance()

    def _camera_live_view_position(
        self,
        x: float,
        y: float,
        feature: CameraFeature,
    ) -> tuple[int, int]:
        if not 0 <= x <= 1 or not 0 <= y <= 1:
            raise BridgeError(
                "INVALID_LIVE_VIEW_POSITION",
                "Live View coordinates must be normalized from 0 through 1.",
                status_code=422,
                feature=feature.value,
                engine=self.engine_name,
            )
        geometry = self._latest_live_view_geometry
        if geometry is None:
            raise BridgeError(
                "LIVE_VIEW_COORDINATES_UNAVAILABLE",
                "A detailed Live View frame with Canon image position metadata is required before this command.",
                status_code=409,
                feature=feature.value,
                engine=self.engine_name,
            )
        return geometry.camera_position(x, y)

    def _supports(self, method: str, suffix: str) -> bool:
        return any(item.method == method and item.path.endswith(suffix) for item in self._operations)

    def _supports_media_delete(self) -> bool:
        return any(
            item.method == "DELETE" and (item.path.endswith("/contents") or "/contents/" in item.path)
            for item in self._operations
        )

    def _api_path(self, method: str, suffix: str) -> str:
        matches = [item for item in self._operations if item.method == method and item.path.endswith(suffix)]
        preferred = next((item for item in matches if item.path.startswith(self._preferred_prefix)), None)
        selected = preferred or max(matches, key=lambda item: _path_version(item.path), default=None)
        return selected.path if selected else f"{self._preferred_prefix}{suffix}"

    def _versioned_paths(self, suffix: str) -> list[str]:
        return [f"{prefix}{suffix}" for prefix in self._api_prefixes]

    def _normalize_resource(self, value: str) -> str:
        parsed = urlsplit(value)
        if parsed.scheme or parsed.netloc:
            camera = urlsplit(self.base_url)
            if (
                parsed.scheme.casefold() != camera.scheme.casefold()
                or (parsed.hostname or "").casefold() != (camera.hostname or "").casefold()
                or _effective_port(parsed) != _effective_port(camera)
            ):
                raise BridgeError(
                    "INVALID_CAMERA_RESOURCE",
                    "Camera returned a media URL outside the active camera origin.",
                    status_code=502,
                    engine=self.engine_name,
                )
        decoded_segments = unquote(parsed.path).split("/")
        if (
            parsed.fragment
            or any(segment in {".", ".."} for segment in decoded_segments)
            or not parsed.path.startswith("/ccapi/")
        ):
            raise BridgeError(
                "INVALID_CAMERA_RESOURCE",
                "Camera returned an invalid CCAPI media path.",
                status_code=502,
                engine=self.engine_name,
            )
        return urlunsplit(("", "", parsed.path, parsed.query, ""))

    def _url(self, path: str) -> str:
        if not path.startswith("/"):
            raise BridgeError("INVALID_CCAPI_PATH", "CCAPI request path must be absolute.", status_code=500)
        return f"{self.base_url}{path}"

    def _ensure_initialized(self) -> None:
        self._require_open()
        if not self._initialized:
            self.initialize()

    def _require_open(self) -> None:
        if self._closed:
            raise BridgeError(
                "SESSION_CLOSED",
                "The camera session is closed.",
                status_code=410,
                engine=self.engine_name,
            )


def normalize_base_url(value: str) -> str:
    raw = value.strip()
    try:
        parsed = urlsplit(raw)
        _ = parsed.port
    except ValueError as error:
        raise BridgeError("INVALID_CCAPI_URL", "The camera CCAPI URL has an invalid port.", status_code=422) from error
    if parsed.scheme.casefold() not in {"http", "https"} or not parsed.hostname:
        raise BridgeError(
            "INVALID_CCAPI_URL",
            "The camera CCAPI URL must use http:// or https:// and include a host.",
            status_code=422,
            engine=ENGINE_NAME,
        )
    if parsed.username is not None or parsed.password is not None:
        raise BridgeError(
            "INVALID_CCAPI_URL",
            "Enter camera credentials in the username and password fields, not in the URL.",
            status_code=422,
            engine=ENGINE_NAME,
        )
    if parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise BridgeError(
            "INVALID_CCAPI_URL",
            "Use only the camera origin, for example http://192.168.1.2:8080.",
            status_code=422,
            engine=ENGINE_NAME,
        )
    return urlunsplit((parsed.scheme.casefold(), parsed.netloc, "", "", ""))


def _network_error_detail(error: Exception) -> str:
    reason = getattr(error, "reason", None)
    return str(reason or error).replace("\r", " ").replace("\n", " ")[:500]


def _camera_id(base_url: str) -> str:
    encoded = base64.urlsafe_b64encode(base_url.encode()).decode().rstrip("=")
    return f"ccapi-{encoded}"


def _media_id(path: str) -> str:
    encoded = base64.urlsafe_b64encode(path.encode()).decode().rstrip("=")
    return f"ccapi:{encoded}"


def _decode_media_id(media_id: str) -> str:
    if not media_id.startswith("ccapi:"):
        raise BridgeError("INVALID_MEDIA_ID", "Media ID does not belong to CCAPI.", status_code=422)
    encoded = media_id.removeprefix("ccapi:")
    try:
        path = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode()
    except (ValueError, UnicodeDecodeError) as error:
        raise BridgeError("INVALID_MEDIA_ID", "CCAPI media ID is malformed.", status_code=422) from error
    if not path.startswith("/ccapi/") or any(character in path for character in ("\x00", "\r", "\n")):
        raise BridgeError("INVALID_MEDIA_ID", "CCAPI media ID contains an invalid path.", status_code=422)
    return path


def _extract_jpeg(value: bytes) -> bytes:
    start = value.find(b"\xff\xd8")
    end = value.find(b"\xff\xd9", start + 2) if start >= 0 else -1
    if start < 0 or end < start:
        raise BridgeError(
            "INVALID_LIVE_VIEW_FRAME",
            "The camera response did not contain a complete JPEG frame.",
            status_code=502,
            engine=ENGINE_NAME,
        )
    frame = value[start : end + 2]
    if len(frame) > MAX_LIVE_VIEW_FRAME_BYTES:
        raise BridgeError(
            "INVALID_LIVE_VIEW_FRAME",
            f"The Live View JPEG exceeded {MAX_LIVE_VIEW_FRAME_BYTES} bytes.",
            status_code=502,
            engine=ENGINE_NAME,
        )
    return frame


def _parse_detailed_live_view(value: bytes) -> tuple[bytes | None, CcapiLiveViewGeometry | None]:
    offset = 0
    image: bytes | None = None
    geometry: CcapiLiveViewGeometry | None = None
    found_packet = False
    while offset + 9 <= len(value):
        if value[offset : offset + 2] != b"\xff\x00":
            break
        data_type = value[offset + 2]
        data_size = int.from_bytes(value[offset + 3 : offset + 7], byteorder="big", signed=False)
        data_start = offset + 7
        data_end = data_start + data_size
        if data_size > MAX_LIVE_VIEW_SCAN_BYTES or data_end + 2 > len(value):
            break
        if value[data_end : data_end + 2] != b"\xff\xff":
            break
        found_packet = True
        data = value[data_start:data_end]
        if data_type == 0x00:
            image = _extract_jpeg(data)
        elif data_type == 0x01:
            geometry = _parse_live_view_geometry(data)
        offset = data_end + 2
    if not found_packet:
        raise BridgeError(
            "INVALID_LIVE_VIEW_FRAME",
            "Detailed Live View response did not contain a valid Canon packet.",
            status_code=502,
            engine=ENGINE_NAME,
        )
    return image, geometry


def _parse_live_view_geometry(value: bytes) -> CcapiLiveViewGeometry | None:
    if len(value) > MAX_JSON_BYTES:
        return None
    try:
        root = json.loads(value)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None

    def find(node: object) -> CcapiLiveViewGeometry | None:
        if not isinstance(node, dict):
            return None
        image_info = node.get("image")
        if isinstance(image_info, dict):
            keys = ("positionx", "positiony", "positionwidth", "positionheight")
            if all(isinstance(image_info.get(key), int) and not isinstance(image_info.get(key), bool) for key in keys):
                width = image_info["positionwidth"]
                height = image_info["positionheight"]
                if width > 0 and height > 0:
                    return CcapiLiveViewGeometry(
                        position_x=image_info["positionx"],
                        position_y=image_info["positiony"],
                        position_width=width,
                        position_height=height,
                    )
        for child in node.values():
            result = find(child)
            if result is not None:
                return result
        return None

    return find(root)


def _lens_status(value: object | None) -> LensStatus | None:
    if not isinstance(value, dict):
        return None
    mounted = value.get("mount")
    name = value.get("name")
    if not isinstance(mounted, bool) or not isinstance(name, str):
        return None
    if (
        len(name) > MAX_DEVICE_STATUS_TEXT_CHARS
        or any(ord(character) < 32 or ord(character) == 127 for character in name)
        or (mounted and not name.strip())
    ):
        return None
    return LensStatus(mounted=mounted, name=name if mounted else "")


def _recordable_status(value: object | None) -> tuple[int | None, int | None] | None:
    if not isinstance(value, dict) or "recordableshots" not in value or "remainingtime" not in value:
        return None

    def nullable_non_negative_integer(key: str) -> tuple[bool, int | None]:
        raw = value[key]
        if raw is None:
            return True, None
        if type(raw) is not int or raw < 0:
            return False, None
        return True, raw

    shots_valid, shots = nullable_non_negative_integer("recordableshots")
    time_valid, remaining_time = nullable_non_negative_integer("remainingtime")
    if not shots_valid or not time_valid:
        return None
    return shots, remaining_time


def _temperature_status(value: object | None) -> CameraTemperatureStatus | None:
    if not isinstance(value, dict) or not isinstance(value.get("status"), str):
        return None
    try:
        return CameraTemperatureStatus(value["status"])
    except ValueError:
        return None


def _battery_status(value: object | None) -> BatteryStatus:
    entries = _object_entries(value, "batterylist", "battery")
    if not entries:
        return BatteryStatus()
    battery = entries[0]
    raw_level = battery.get("level")
    level = _bounded_percent(raw_level)
    status = _string_value(battery.get("state")) or _string_value(battery.get("quality"))
    if not status:
        status = f"{level}%" if level is not None else _string_value(raw_level) or "unknown"
    return BatteryStatus(level=level, status=status)


def _storage_status(value: object | None) -> StorageStatus:
    entries = _object_entries(value, "storagelist", "storage")
    if isinstance(value, dict) and isinstance(value.get("path"), list) and value["path"]:
        return StorageStatus(available=True, devices=1)
    if not entries:
        return StorageStatus()
    usable = [item for item in entries if _storage_available(item)]
    total_values = [_first_positive(item, "maxsize", "capacity", "totalbytes", "totalsize") for item in usable]
    free_values = [_first_positive(item, "spacesize", "free", "freebytes", "freespace") for item in usable]
    image_values = [_first_positive(item, "freeimages", "remainingimages", "numberofimages") for item in usable]
    return StorageStatus(
        available=bool(usable),
        total_bytes=sum(item for item in total_values if item is not None) or None,
        free_bytes=sum(item for item in free_values if item is not None) or None,
        free_images=sum(item for item in image_values if item is not None) or None,
        devices=len(usable),
    )


def _object_entries(value: object | None, *keys: str) -> list[dict[str, object]]:
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if not isinstance(value, dict):
        return []
    for key in keys:
        nested = value.get(key)
        if isinstance(nested, list):
            return [item for item in nested if isinstance(item, dict)]
        if isinstance(nested, dict):
            return [nested]
    return [value]


def _storage_available(value: dict[str, object]) -> bool:
    status = _string_value(value.get("status")).casefold()
    access = (_string_value(value.get("accesscapability")) or _string_value(value.get("access"))).casefold()
    if status in {"not_inserted", "none", "unavailable"}:
        return False
    if status in {"ready", "access"} or access in {"readwrite", "readonly"}:
        return True
    return _first_positive(value, "spacesize", "maxsize", "capacity", "free") is not None


def _bounded_percent(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return max(0, min(100, int(value)))
    text = _string_value(value).strip().casefold().removesuffix("%")
    mapped = {"full": 100, "middle": 50, "low": 20, "empty": 5}.get(text)
    if mapped is not None:
        return mapped
    try:
        return max(0, min(100, int(float(text))))
    except ValueError:
        return None


def _first_positive(value: dict[str, object], *keys: str) -> int | None:
    for key in keys:
        parsed = _positive_int(value.get(key))
        if parsed is not None:
            return parsed
    return None


def _positive_int(value: object) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        match = re.search(r"\d+", str(value).replace(",", ""))
        parsed = int(match.group()) if match else 0
    return parsed if parsed > 0 else None


def _setting_value(settings: dict[str, object], key: str) -> str | None:
    value = settings.get(key)
    return _string_value(value.get("value")) if isinstance(value, dict) else None


def _structured_image_quality_controls(raw: dict[str, object]) -> list[CameraSetting]:
    current = raw.get("value")
    ability = raw.get("ability")
    if not isinstance(current, dict) or not isinstance(ability, dict):
        return []
    controls: list[CameraSetting] = []
    for field in IMAGE_QUALITY_FIELDS:
        value = _string_value(current.get(field))
        raw_values = ability.get(field)
        values = list(dict.fromkeys(_string_value(item) for item in raw_values)) if isinstance(raw_values, list) else []
        values = [item for item in values if item]
        if value and len(values) >= 2:
            key = f"{IMAGE_QUALITY_SETTING_KEY}.{field}"
            controls.append(CameraSetting(key=key, label=SETTING_LABELS[key], value=value, values=values))
    return controls


def _structured_wb_shift_controls(raw: dict[str, object]) -> list[CameraSetting]:
    current = raw.get("value")
    ability = raw.get("ability")
    if not isinstance(current, dict) or not isinstance(ability, dict):
        return []
    if any(
        isinstance(current.get(field), bool) or not isinstance(current.get(field), int) for field in WB_SHIFT_FIELDS
    ):
        return []
    controls: list[CameraSetting] = []
    for field in WB_SHIFT_FIELDS:
        values = _bounded_integer_range_values(ability.get(field))
        current_value = current.get(field)
        assert isinstance(current_value, int) and not isinstance(current_value, bool)
        value = str(current_value)
        if len(values) >= 2 and value in values:
            key = f"{WB_SHIFT_SETTING_KEY}.{field}"
            controls.append(CameraSetting(key=key, label=SETTING_LABELS[key], value=value, values=values))
    return controls


def _bounded_integer_range_values(
    raw: object,
    *,
    maximum_options: int = MAX_STRUCTURED_SETTING_OPTIONS,
) -> list[str]:
    if not isinstance(raw, dict):
        return []
    minimum = raw.get("min")
    maximum = raw.get("max")
    step = raw.get("step")
    if any(isinstance(item, bool) or not isinstance(item, int) for item in (minimum, maximum, step)):
        return []
    assert isinstance(minimum, int) and isinstance(maximum, int) and isinstance(step, int)
    if step <= 0 or minimum > maximum:
        return []
    count = ((maximum - minimum) // step) + 1
    if count < 1 or count > maximum_options:
        return []
    return [str(minimum + index * step) for index in range(count)]


def _validated_integer_range_setting(
    raw: object,
    *,
    maximum_options: int = MAX_STRUCTURED_SETTING_OPTIONS,
) -> dict[str, object] | None:
    if not isinstance(raw, dict):
        return None
    current = raw.get("value")
    if isinstance(current, bool) or not isinstance(current, int):
        return None
    values = _bounded_integer_range_values(raw.get("ability"), maximum_options=maximum_options)
    current_value = str(current)
    if len(values) < 2 or current_value not in values:
        return None
    return {"value": current_value, "ability": values}


def _validated_zoom_setting(raw: object) -> dict[str, object] | None:
    return _validated_integer_range_setting(raw)


def _validated_movie_mode_setting(raw: object) -> dict[str, object] | None:
    if not isinstance(raw, dict):
        return None
    status = raw.get("status", raw.get("value"))
    if not isinstance(status, str) or status not in MOVIE_MODE_VALUES:
        return None
    return {"value": status, "ability": list(MOVIE_MODE_VALUES)}


def _validated_card_selection_setting(raw: object) -> dict[str, object] | None:
    if not isinstance(raw, dict):
        return None
    current = raw.get("value")
    ability = raw.get("ability")
    if not isinstance(current, str) or not isinstance(ability, list) or len(ability) < 2:
        return None
    if not all(isinstance(item, str) for item in ability):
        return None
    values = list(ability)
    if len(set(values)) != len(values) or any(item not in CARD_SELECTION_VALUES for item in values):
        return None
    if current not in CARD_SELECTION_VALUES or current not in values:
        return None
    return {"value": current, "ability": values}


def _validated_string_ability_setting(
    raw: object,
    allowed_values: frozenset[str],
) -> dict[str, object] | None:
    if not isinstance(raw, dict):
        return None
    current = raw.get("value")
    ability = raw.get("ability")
    if not isinstance(current, str) or not isinstance(ability, list) or len(ability) < 2:
        return None
    if not all(isinstance(item, str) for item in ability):
        return None
    values = list(ability)
    if len(set(values)) != len(values) or any(item not in allowed_values for item in values):
        return None
    if current not in values:
        return None
    return {"value": current, "ability": values}


def _structured_setting_parts(key: str) -> tuple[str, str] | None:
    for base_key, fields in (
        (IMAGE_QUALITY_SETTING_KEY, IMAGE_QUALITY_FIELDS),
        (WB_SHIFT_SETTING_KEY, WB_SHIFT_FIELDS),
    ):
        prefix = f"{base_key}."
        if key.startswith(prefix):
            field = key.removeprefix(prefix)
            return (base_key, field) if field in fields else None
    return None


def _first_setting_value(settings: dict[str, object], *keys: str) -> str | None:
    for key in keys:
        value = _setting_value(settings, SETTING_ALIASES.get(key, key))
        if value:
            return value
    return None


def _string_value(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _integer_value(value: object | None, key: str) -> int | None:
    if not isinstance(value, dict):
        return None
    try:
        return int(value.get(key, 0))
    except (TypeError, ValueError):
        return None


def _feature_for_setting(key: str) -> CameraFeature:
    if key in {"iso", "shutter", "aperture"}:
        return CameraFeature.EXPOSURE_CONTROL
    if key == "whitebalance":
        return CameraFeature.WHITE_BALANCE_CONTROL
    if key == MOVIE_MODE_SETTING_KEY:
        return CameraFeature.MOVIE_MODE_CONTROL
    if key == ZOOM_SETTING_KEY:
        return CameraFeature.ZOOM_CONTROL
    if key == SOUND_RECORDING_LEVEL_SETTING_KEY:
        return CameraFeature.SOUND_RECORDING_LEVEL_CONTROL
    if key in SOUND_RECORDING_ENDPOINTS:
        return CameraFeature.SOUND_RECORDING_CONTROL
    if key in FOCUS_BRACKETING_SETTING_KEYS:
        return CameraFeature.FOCUS_BRACKETING_CONTROL
    if key in CARD_SELECTION_ENDPOINTS:
        return CameraFeature.CARD_SELECTION_CONTROL
    return CameraFeature.ADVANCED_SETTINGS


def _setting_label(key: str) -> str:
    words = re.sub(r"([a-z])([A-Z])", r"\1 \2", key.replace("_", " ").replace("-", " "))
    return " ".join(word.capitalize() for word in words.split()) or key


def _method_supported(value: object) -> bool:
    if value is None or value is False:
        return False
    if isinstance(value, int | float):
        return value != 0
    if isinstance(value, str):
        return bool(value) and value.casefold() not in {"false", "no", "none", "unsupported"}
    return True


def _safe_event_keys(keys: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for key in keys:
        safe = str(key).replace("\r", "").replace("\n", "").strip()[:MAX_EVENT_KEY_CHARS]
        if not safe or safe in seen:
            continue
        seen.add(safe)
        result.append(safe)
        if len(result) >= MAX_EVENT_KEYS:
            break
    return result


def _version_number(value: str) -> int:
    return int(value.removeprefix("ver")) if value.removeprefix("ver").isdigit() else 0


def _path_version(path: str) -> int:
    match = re.search(r"/ccapi/ver(\d+)(?:/|$)", path)
    return int(match.group(1)) if match else 0


def _effective_port(value: SplitResult) -> int:
    if value.port is not None:
        return value.port
    return 443 if value.scheme.casefold() == "https" else 80


def _is_media_path(path: str) -> bool:
    name = path.rsplit("/", 1)[-1]
    return "." in name and not name.endswith(".")


def _media_kind(path: str) -> str:
    extension = path.rsplit(".", 1)[-1].casefold() if "." in path else ""
    if extension in {"jpg", "jpeg", "hif", "heif", "png"}:
        return "image"
    if extension in {"cr2", "cr3", "raw"}:
        return "raw"
    if extension in {"mp4", "mov"}:
        return "video"
    return "other"


def _is_text_content_type(value: str) -> bool:
    normalized = value.casefold()
    return normalized.startswith("text/") or "json" in normalized or "html" in normalized


def _image_content_type(value: bytes) -> str | None:
    if value.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if value.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if value.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if len(value) >= 12 and value.startswith(b"RIFF") and value[8:12] == b"WEBP":
        return "image/webp"
    return None


def _looks_like_text_payload(value: bytes) -> bool:
    first = value.lstrip()[:1]
    return first in {b"{", b"[", b"<"}
