from __future__ import annotations

import base64
import json
import mimetypes
import re
import threading
import time
from collections import deque
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass
from typing import BinaryIO, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import SplitResult, urlsplit, urlunsplit
from urllib.request import Request, urlopen

from .errors import BridgeError, unsupported
from .models import (
    BatteryStatus,
    CameraCapabilities,
    CameraDescriptor,
    CameraFeature,
    CameraInfo,
    CameraProfile,
    CameraSetting,
    CameraStatus,
    CapabilityEvidence,
    ExposureState,
    FocusResult,
    LiveViewCapabilities,
    LiveViewStartRequest,
    MediaItem,
    StorageStatus,
)

ENGINE_NAME = "ccapi"
ENGINE_VERSION = "CCAPI HTTP"
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_ERROR_BYTES = 2_000
MAX_LIVE_VIEW_SCAN_BYTES = 16 * 1024 * 1024
MAX_LIVE_VIEW_FRAME_BYTES = 12 * 1024 * 1024
MAX_MEDIA_ITEMS = 500
MAX_MEDIA_PAGES = 100
MAX_MEDIA_TREE_DEPTH = 4
MAX_CAPABILITY_EVIDENCE_ITEMS = 256
MAX_CAPABILITY_EVIDENCE_ITEM_CHARS = 512
TRANSFER_CHUNK_BYTES = 64 * 1024
HALF_PRESS_SECONDS = 0.35
HTTP_METHODS = ("GET", "PUT", "POST", "DELETE")
COMMAND_METHODS = {"PUT", "POST"}
PRIMARY_SETTING_KEYS = {"iso", "shutter", "aperture", "whitebalance"}
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
    "shutter": "Tv",
    "aperture": "Av",
    "whitebalance": "WB",
    "afmethod": "AF method",
    "afoperation": "AF operation",
    "drivemode": "Drive mode",
    "meteringmode": "Metering",
    "picturestyle": "Picture style",
    "shootingmode": "Shooting mode",
    "stillimagequality": "Image quality",
    "moviequality": "Movie quality",
    "colortemperature": "Color temperature",
    "exposurecompensation": "Exposure compensation",
    "ae": "AE mode",
}


@dataclass(frozen=True)
class CcapiOperation:
    method: str
    path: str


@dataclass(frozen=True)
class CcapiResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes


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
    ) -> None:
        self._transport_factory = transport_factory or UrllibCcapiTransport
        self._sleeper = sleeper

    def health(self) -> tuple[bool, str | None, str | None]:
        return True, ENGINE_VERSION, "Enter a camera CCAPI URL to use the network engine."

    def open_connection(self, base_url: str, username: str = "", password: str = "") -> CcapiSession:
        normalized = normalize_base_url(base_url)
        transport = self._transport_factory(username, password)
        session = CcapiSession(normalized, transport, sleeper=self._sleeper)
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
        self._lock = threading.RLock()
        self._closed = False
        self._initialized = False
        self._api_prefixes = ["/ccapi/ver100"]
        self._preferred_prefix = "/ccapi/ver100"
        self._operations: set[CcapiOperation] = set()
        self._observed: set[CameraFeature] = set()
        self._cached_info: CameraInfo | None = None
        self._settings_cache: dict[str, object] | None = None
        self._setting_paths: dict[str, str] = {}
        self._discovery_source = "unknown"
        self._recording: bool | None = None
        self._live_view_active = False
        self._live_view_size_control = True
        self._active_live_view_size = "MEDIUM"
        self._requested_fps = 1
        self._frame_key = 0
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
                    self._parse_discovery(value, source=f"GET {path}")
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
            if self._live_view_active:
                try:
                    self._request_ok("DELETE", self._api_path("DELETE", "/shooting/liveview"))
                except BridgeError as error:
                    self._last_error = error.message
            self._live_view_active = False
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
                self._versioned_paths("/devicestatus/batterylist")
                + self._versioned_paths("/devicestatus/battery")
            )
            storage = self._first_json(
                self._versioned_paths("/devicestatus/storage")
                + self._versioned_paths("/devicestatus/currentstorage")
                + [self._api_path("GET", "/contents")]
            )
            settings = self._load_settings(force=True)
            battery_status = _battery_status(battery)
            storage_status = _storage_status(storage)
            if battery is not None:
                self._observed.add(CameraFeature.BATTERY_STATUS)
            if storage is not None:
                self._observed.add(CameraFeature.STORAGE_STATUS)
            return CameraStatus(
                battery=battery_status,
                recording=self._recording,
                mode=_setting_value(settings, "shootingmode") or "unknown",
                media=storage_status,
                exposure=ExposureState(
                    iso=_setting_value(settings, "iso") or "-",
                    shutter=_first_setting_value(settings, "tv", "shutterspeed", "shutter") or "-",
                    aperture=_first_setting_value(settings, "av", "aperture") or "-",
                    white_balance=_first_setting_value(settings, "wb", "whitebalance", "white_balance") or "-",
                ),
                raw={
                    "engine": self.engine_name,
                    "baseUrl": self.base_url,
                    "apiVersions": self._api_prefixes,
                    "battery": battery,
                    "storage": storage,
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
            if control_keys - PRIMARY_SETTING_KEYS:
                supported.add(CameraFeature.ADVANCED_SETTINGS)
            live_view_supported = (
                self._supports("POST", "/shooting/liveview")
                and self._supports("DELETE", "/shooting/liveview")
                and bool(self._live_view_frame_paths())
            )
            if live_view_supported:
                supported.update({CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_JPEG_POLLING})
            if self._operation("POST", "/shooting/control/recbutton") or self._operation(
                "PUT", "/shooting/control/recbutton"
            ):
                supported.add(CameraFeature.VIDEO_RECORDING)
            if self._operation("POST", "/shooting/control/shutterbutton") or self._operation(
                "PUT", "/shooting/control/shutterbutton/manual"
            ) or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            ):
                supported.add(CameraFeature.STILL_CAPTURE)
            if self._operation("PUT", "/shooting/control/shutterbutton/manual") or self._operation(
                "POST", "/shooting/control/shutterbutton/manual"
            ):
                supported.add(CameraFeature.SHUTTER_HALF_PRESS)
            if self._operation("PUT", "/shooting/control/afpoint") or self._operation(
                "POST", "/shooting/control/afpoint"
            ):
                supported.add(CameraFeature.TAP_FOCUS)
            if self._supports("GET", "/contents"):
                supported.update({CameraFeature.MEDIA_BROWSER, CameraFeature.MEDIA_DOWNLOAD})
            if self._supports_media_delete():
                supported.add(CameraFeature.MEDIA_DELETE)

            candidates = {
                CameraFeature.LIVE_VIEW_RTP,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.VIDEO_RECORDING,
                CameraFeature.TAP_FOCUS,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_THUMBNAIL,
                CameraFeature.MEDIA_DOWNLOAD,
                CameraFeature.MEDIA_DELETE,
            }
            live_sizes = (
                [self._active_live_view_size]
                if not self._live_view_size_control
                else ["SMALL", "MEDIUM", "LARGE"]
            )
            model = self.info().model
            return CameraCapabilities(
                profile=_camera_profile(model),
                supported=sorted(supported, key=str),
                planned=sorted(candidates - supported, key=str),
                reasons={
                    CameraFeature.LIVE_VIEW_RTP.value: (
                        "CCAPI RTP decoding is not implemented; this engine uses bounded JPEG polling."
                    ),
                    CameraFeature.FOCUS_DRIVE.value: (
                        "CCAPI focus drive is not exposed without a camera-advertised, verified operation."
                    ),
                    CameraFeature.MEDIA_THUMBNAIL.value: (
                        "No verified Canon CCAPI thumbnail resource is advertised by this camera."
                    ),
                },
                live_view=(
                    LiveViewCapabilities(
                        sources=["CCAPI_JPEG_POLLING"],
                        default_source="CCAPI_JPEG_POLLING",
                        sizes=live_sizes,
                        default_size="MEDIUM" if "MEDIUM" in live_sizes else live_sizes[0],
                        min_fps=1,
                        max_fps=30,
                    )
                    if CameraFeature.LIVE_VIEW in supported
                    else LiveViewCapabilities()
                ),
                settings=controls,
                evidence=self._capability_evidence(),
            )

    def set_setting(self, key: str, value: str) -> CameraStatus:
        with self._lock:
            self._ensure_initialized()
            canonical = SETTING_ALIASES.get(key, key)
            control = next(
                (item for item in self._camera_settings(self._load_settings(False)) if item.key == canonical),
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
            path = self._setting_paths.get(canonical)
            if path is None:
                raise unsupported(_feature_for_setting(canonical).value, self.engine_name)
            self._request_ok("PUT", path, {"value": value})
            self._settings_cache = None
            return self.status()

    def capture_still(self) -> CameraStatus:
        with self._lock:
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

    def start_recording(self) -> CameraStatus:
        return self._set_recording(True)

    def stop_recording(self) -> CameraStatus:
        return self._set_recording(False)

    def drive_focus(self, direction: str, step: str) -> FocusResult:
        del direction, step
        raise unsupported(CameraFeature.FOCUS_DRIVE.value, self.engine_name)

    def tap_focus(self, x: float, y: float) -> FocusResult:
        with self._lock:
            operation = self._operation("PUT", "/shooting/control/afpoint") or self._operation(
                "POST", "/shooting/control/afpoint"
            )
            if operation is None:
                raise unsupported(CameraFeature.TAP_FOCUS.value, self.engine_name)
            self._command_ok(operation, {"x": x, "y": y})
            self._observed.add(CameraFeature.TAP_FOCUS)
            return FocusResult(accepted=True, x=x, y=y)

    def start_live_view(self, request: LiveViewStartRequest) -> None:
        with self._lock:
            self._ensure_initialized()
            if (
                not self._supports("POST", "/shooting/liveview")
                or not self._supports("DELETE", "/shooting/liveview")
                or not self._live_view_frame_paths()
            ):
                raise unsupported(CameraFeature.LIVE_VIEW.value, self.engine_name)
            source = request.source.upper()
            if source not in {"AUTO", "CCAPI_JPEG_POLLING", "DESKTOP_BRIDGE_STREAM"}:
                raise BridgeError("INVALID_LIVE_VIEW_SOURCE", "Unsupported CCAPI Live View source.", status_code=422)
            size = request.size.upper()
            if size not in {"SMALL", "MEDIUM", "LARGE"}:
                raise BridgeError("INVALID_LIVE_VIEW_SIZE", "Unsupported CCAPI Live View size.", status_code=422)
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
            self._observed.update({CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_JPEG_POLLING})

    def stop_live_view(self) -> None:
        with self._lock:
            self._require_open()
            if not self._live_view_active:
                return
            try:
                self._request_ok("DELETE", self._api_path("DELETE", "/shooting/liveview"))
            finally:
                self._live_view_active = False

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
            self._frame_key += 1
            candidates = self._live_view_frame_paths()
            failures: list[str] = []
            for candidate in candidates:
                separator = "&" if "?" in candidate else "?"
                path = f"{candidate}{separator}t={self._frame_key}"
                try:
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
                )
                for path in media_paths[:MAX_MEDIA_ITEMS]
            ]
            self._media_cache = {item.id: item for item in items}
            if items:
                self._observed.update({CameraFeature.MEDIA_BROWSER, CameraFeature.MEDIA_DOWNLOAD})
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
                    )
                    item = item.model_copy(update={"size_bytes": size, "content_type": content_type})

                    def stream(active: CcapiStreamResponse = response) -> Iterator[bytes]:
                        with self._lock:
                            try:
                                while chunk := active.body.read(TRANSFER_CHUNK_BYTES):
                                    yield chunk
                            finally:
                                active.close()

                    self._observed.add(CameraFeature.MEDIA_DOWNLOAD)
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
        del media_id
        raise unsupported(
            CameraFeature.MEDIA_THUMBNAIL.value,
            self.engine_name,
            "No verified Canon CCAPI thumbnail resource is advertised by this camera.",
        )

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

    def _set_recording(self, recording: bool) -> CameraStatus:
        with self._lock:
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
            self._last_error = f"{primary}; shutter release also failed: {release_error}"
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
        page_info = self._first_json(
            [f"{container}?kind=number", f"{container}?type=all,kind=number"]
        )
        page_count = min(MAX_MEDIA_PAGES, _integer_value(page_info, "pagenumber") or 0)
        pages = range(1, page_count + 1) if page_count > 0 else (0,)
        paths: list[str] = []
        for page in pages:
            candidates = (
                [container]
                if page == 0
                else [f"{container}?page={page}&order=desc", f"{container}?page={page}"]
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
                    if CcapiOperation("PUT", setting_path) in self._operations:
                        setting_paths[key] = setting_path
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
                if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
                    continue
                raw_path = entry["path"].strip()
                if not raw_path:
                    continue
                path = raw_path if raw_path.startswith("/ccapi/") else f"/ccapi/{key}/{raw_path.lstrip('/')}"
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
        self._preferred_prefix = (
            "/ccapi/ver100" if "/ccapi/ver100" in self._api_prefixes else self._api_prefixes[0]
        )

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
            truncated=(
                len(protocol_versions) > MAX_CAPABILITY_EVIDENCE_ITEMS
                or len(commands) > MAX_CAPABILITY_EVIDENCE_ITEMS
                or len(writable_settings) > MAX_CAPABILITY_EVIDENCE_ITEMS
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

    def _request_json(self, method: str, path: str, payload: dict[str, object] | None = None) -> object:
        response = self._request(method, path, payload)
        try:
            return json.loads(response.body)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise BridgeError(
                "INVALID_CCAPI_RESPONSE",
                f"Camera request {method} {path} returned invalid JSON.",
                status_code=502,
                engine=self.engine_name,
            ) from error

    def _request_ok(self, method: str, path: str, payload: dict[str, object] | None = None) -> None:
        self._request(method, path, payload)

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        *,
        headers: Mapping[str, str] | None = None,
        max_bytes: int = MAX_JSON_BYTES,
    ) -> CcapiResponse:
        self._require_open()
        body = json.dumps(payload, separators=(",", ":")).encode() if payload is not None else None
        response = self.transport.request(
            method,
            self._url(path),
            body=body,
            headers=headers,
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

    def _live_view_frame_paths(self) -> list[str]:
        candidates: list[str] = []
        flip = self._operation("GET", "/shooting/liveview/flip")
        flip_detail = self._operation("GET", "/shooting/liveview/flipdetail")
        live_view = self._operation("GET", "/shooting/liveview")
        if flip:
            candidates.append(flip.path)
        if flip_detail:
            candidates.append(f"{flip_detail.path}?kind=image")
        if live_view:
            candidates.append(live_view.path)
        return candidates

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
        if parsed.fragment or not parsed.path.startswith("/ccapi/"):
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
    return CameraFeature.ADVANCED_SETTINGS


def _setting_label(key: str) -> str:
    words = re.sub(r"([a-z])([A-Z])", r"\1 \2", key.replace("_", " ").replace("-", " "))
    return " ".join(word.capitalize() for word in words.split()) or key


def _camera_profile(model: str) -> CameraProfile:
    normalized = model.casefold()
    priority = "primary" if "r6 mark iii" in normalized or "r6m3" in normalized else "compatible"
    return CameraProfile(model_name=model, family="Canon EOS", priority=priority)


def _method_supported(value: object) -> bool:
    if value is None or value is False:
        return False
    if isinstance(value, int | float):
        return value != 0
    if isinstance(value, str):
        return bool(value) and value.casefold() not in {"false", "no", "none", "unsupported"}
    return True


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
