from __future__ import annotations

import base64
import json
import mimetypes
import os
import queue
import re
import shutil
import subprocess
import threading
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol

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

ENGINE_NAME = "libgphoto2"
MAX_COMMAND_OUTPUT_BYTES = 32 * 1024 * 1024
MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024
MAX_MEDIA_ITEMS = 500
MAX_CAPABILITY_EVIDENCE_ITEMS = 256
MAX_CAPABILITY_EVIDENCE_ITEM_CHARS = 512
CONFIG_REFRESH_SECONDS = 1.0
MAX_BRIDGE_LIVE_VIEW_FPS = 5


@dataclass(frozen=True)
class CommandOutput:
    stdout: bytes
    stderr: str = ""

    @property
    def text(self) -> str:
        return self.stdout.decode("utf-8", errors="replace")


class GPhotoRunner(Protocol):
    def health(self) -> tuple[bool, str | None, str | None]: ...

    def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput: ...

    def stream(self, arguments: list[str], *, timeout: float = 300.0) -> Iterator[bytes]: ...


class SubprocessGPhotoRunner:
    def __init__(self, binary: str | None = None) -> None:
        self.binary = binary or os.environ.get("OPEN_EOS_GPHOTO2", "gphoto2")

    def health(self) -> tuple[bool, str | None, str | None]:
        resolved = shutil.which(self.binary)
        if resolved is None:
            return False, None, f"gphoto2 executable '{self.binary}' was not found on PATH."
        try:
            output = self.run(["--version"], timeout=5.0)
        except BridgeError as error:
            return False, None, error.message
        first_line = next((line.strip() for line in output.text.splitlines() if line.strip()), None)
        return True, first_line, None

    def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
        command = [self.binary, *arguments]
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                check=False,
                timeout=timeout,
                env=_command_environment(),
            )
        except FileNotFoundError as error:
            raise _engine_unavailable(self.binary) from error
        except subprocess.TimeoutExpired as error:
            raise BridgeError(
                "ENGINE_TIMEOUT",
                f"gphoto2 did not finish within {timeout:g} seconds.",
                status_code=504,
                engine=ENGINE_NAME,
            ) from error
        if len(completed.stdout) > MAX_COMMAND_OUTPUT_BYTES:
            raise BridgeError(
                "ENGINE_OUTPUT_LIMIT",
                f"gphoto2 returned more than {MAX_COMMAND_OUTPUT_BYTES} bytes of command output.",
                status_code=502,
                engine=ENGINE_NAME,
            )
        stderr = completed.stderr.decode("utf-8", errors="replace")
        if completed.returncode != 0:
            raise _command_error(arguments, completed.returncode, stderr)
        return CommandOutput(stdout=completed.stdout, stderr=stderr)

    def stream(self, arguments: list[str], *, timeout: float = 300.0) -> Iterator[bytes]:
        command = [self.binary, *arguments]

        def iterator() -> Iterator[bytes]:
            try:
                process = subprocess.Popen(
                    command,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    env=_command_environment(),
                )
            except FileNotFoundError as error:
                raise _engine_unavailable(self.binary) from error

            stderr_parts: list[bytes] = []
            stderr_size = 0
            stdout_queue: queue.Queue[bytes | object] = queue.Queue(maxsize=8)
            stdout_complete = object()
            stop_reading = threading.Event()

            def drain_stdout() -> None:
                assert process.stdout is not None
                try:
                    while not stop_reading.is_set():
                        chunk = process.stdout.read(64 * 1024)
                        if not chunk:
                            break
                        while not stop_reading.is_set():
                            try:
                                stdout_queue.put(chunk, timeout=0.1)
                                break
                            except queue.Full:
                                continue
                finally:
                    while not stop_reading.is_set():
                        try:
                            stdout_queue.put(stdout_complete, timeout=0.1)
                            break
                        except queue.Full:
                            continue

            def drain_stderr() -> None:
                nonlocal stderr_size
                assert process.stderr is not None
                while chunk := process.stderr.read(16 * 1024):
                    stderr_parts.append(chunk)
                    stderr_size += len(chunk)
                    while stderr_size > 256 * 1024 and len(stderr_parts) > 1:
                        stderr_size -= len(stderr_parts.pop(0))

            stdout_thread = threading.Thread(target=drain_stdout, name="gphoto2-stdout", daemon=True)
            stderr_thread = threading.Thread(target=drain_stderr, name="gphoto2-stderr", daemon=True)
            stdout_thread.start()
            stderr_thread.start()
            deadline = time.monotonic() + timeout
            completed_normally = False
            try:
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise BridgeError(
                            "ENGINE_TIMEOUT",
                            f"gphoto2 media transfer exceeded {timeout:g} seconds.",
                            status_code=504,
                            engine=ENGINE_NAME,
                        )
                    try:
                        chunk = stdout_queue.get(timeout=min(remaining, 0.25))
                    except queue.Empty:
                        continue
                    if chunk is stdout_complete:
                        break
                    assert isinstance(chunk, bytes)
                    yield chunk
                completed_normally = True
            finally:
                stop_reading.set()
                if process.poll() is None:
                    process.terminate()
                try:
                    return_code = process.wait(timeout=5.0)
                except subprocess.TimeoutExpired:
                    process.kill()
                    return_code = process.wait(timeout=5.0)
                stdout_thread.join(timeout=1.0)
                stderr_thread.join(timeout=1.0)
                if completed_normally and return_code != 0:
                    stderr = b"".join(stderr_parts).decode("utf-8", errors="replace")
                    raise _command_error(arguments, return_code, stderr)

        return iterator()


def _command_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment["LC_ALL"] = "C"
    environment["LANG"] = "C"
    return environment


def _engine_unavailable(binary: str) -> BridgeError:
    return BridgeError(
        "ENGINE_UNAVAILABLE",
        f"gphoto2 executable '{binary}' is not installed or is not on PATH.",
        status_code=503,
        engine=ENGINE_NAME,
    )


def _command_error(arguments: list[str], return_code: int, stderr: str) -> BridgeError:
    useful_lines = [
        line.strip()
        for line in stderr.splitlines()
        if line.strip() and "For debugging messages" not in line and "Please make sure" not in line
    ]
    detail = " ".join(useful_lines[-8:])[-2000:] or f"gphoto2 exited with code {return_code}."
    operation = next((item for item in reversed(arguments) if item.startswith("--")), "command")
    return BridgeError(
        "ENGINE_COMMAND_FAILED",
        f"gphoto2 {operation} failed: {detail}",
        status_code=502,
        engine=ENGINE_NAME,
    )


@dataclass
class GPhotoConfig:
    path: str
    label: str = ""
    readonly: bool = True
    kind: str = "TEXT"
    current: str = ""
    choices: list[str] = field(default_factory=list)
    bottom: float | None = None
    top: float | None = None
    step: float | None = None

    def selectable_values(self) -> list[str]:
        if self.kind in {"RADIO", "MENU"}:
            return list(dict.fromkeys(self.choices))
        if self.kind == "RANGE" and self.bottom is not None and self.top is not None and self.step:
            span = self.top - self.bottom
            if span < 0 or self.step <= 0:
                return []
            intervals = round(span / self.step)
            if intervals > 255:
                return []
            return [_format_number(self.bottom + self.step * index) for index in range(intervals + 1)]
        return []


@dataclass(frozen=True)
class GPhotoAbilities:
    model: str = ""
    capture_image: bool = False
    capture_preview: bool = False
    trigger_capture: bool = False
    configuration: bool = False
    delete_files: bool = False
    file_preview: bool = False


@dataclass(frozen=True)
class StorageSnapshot:
    available: bool | None
    total_bytes: int | None
    free_bytes: int | None
    free_images: int | None
    devices: int


@dataclass(frozen=True)
class ConfigSpec:
    key: str
    label: str
    suffixes: tuple[str, ...]
    core: bool = False


CONFIG_SPECS = (
    ConfigSpec("iso", "ISO", ("iso",), core=True),
    ConfigSpec("shutter", "Shutter speed", ("shutterspeed", "exposuretime"), core=True),
    ConfigSpec("aperture", "Aperture", ("aperture", "f-number"), core=True),
    ConfigSpec("whitebalance", "White balance", ("whitebalance",), core=True),
    ConfigSpec("exposurecompensation", "Exposure compensation", ("exposurecompensation",)),
    ConfigSpec("afoperation", "Focus mode", ("focusmode",)),
    ConfigSpec("afmethod", "AF method", ("afmethod",)),
    ConfigSpec("drivemode", "Drive mode", ("drivemode",)),
    ConfigSpec("meteringmode", "Metering mode", ("meteringmode",)),
    ConfigSpec("picturestyle", "Picture style", ("picturestyle",)),
    ConfigSpec("stillimagequality", "Image quality", ("imageformat", "imagequality")),
    ConfigSpec("shootingmode", "Shooting mode", ("autoexposuremode",)),
    ConfigSpec("colortemperature", "Color temperature", ("colortemperature",)),
    ConfigSpec("colorspace", "Color space", ("colorspace",)),
    ConfigSpec("highisonr", "High ISO noise reduction", ("highisonr",)),
    ConfigSpec("continuousaf", "Continuous AF", ("continuousaf",)),
    ConfigSpec("movieservoaf", "Movie Servo AF", ("movieservoaf",)),
    ConfigSpec("aeb", "Auto exposure bracketing", ("aeb",)),
)


def parse_auto_detect(output: str) -> list[CameraDescriptor]:
    cameras: list[CameraDescriptor] = []
    pattern = re.compile(r"^(?P<model>.+?)\s{2,}(?P<port>(?:usb|ptpip|serial|disk|usbscsi):.*)$", re.I)
    for line in output.splitlines():
        match = pattern.match(line.rstrip())
        if not match:
            continue
        model = match.group("model").strip()
        port = match.group("port").strip()
        cameras.append(CameraDescriptor(id=_camera_id(port), model=model, port=port))
    return cameras


def parse_summary(output: str) -> dict[str, str]:
    result: dict[str, str] = {}
    aliases = {
        "manufacturer": "manufacturer",
        "model": "model",
        "serial number": "serial",
        "version": "device_version",
        "device version": "device_version",
    }
    for line in output.splitlines():
        if ":" not in line:
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        normalized = key.lower()
        target = aliases.get(normalized)
        if target and value and value != "(null)":
            result[target] = value
    return result


def parse_abilities(output: str) -> GPhotoAbilities:
    model_match = re.search(r"^Abilities for camera\s*:\s*(.+)$", output, re.M | re.I)
    capture_lines = {
        match.group(1).strip().lower()
        for match in re.finditer(r"^\s*:\s*(Image|Preview|Trigger Capture)\s*$", output, re.M | re.I)
    }
    configuration_match = re.search(r"^Configuration support\s*:\s*(yes|no)\s*$", output, re.M | re.I)
    delete_match = re.search(r"^Delete selected files on camera\s*:\s*(yes|no)\s*$", output, re.M | re.I)
    file_preview_match = re.search(
        r"^File preview(?:\s*\(thumbnail\))? support\s*:\s*(yes|no)\s*$",
        output,
        re.M | re.I,
    )
    return GPhotoAbilities(
        model=model_match.group(1).strip() if model_match else "",
        capture_image="image" in capture_lines,
        capture_preview="preview" in capture_lines,
        trigger_capture="trigger capture" in capture_lines,
        configuration=bool(configuration_match and configuration_match.group(1).lower() == "yes"),
        delete_files=bool(delete_match and delete_match.group(1).lower() == "yes"),
        file_preview=bool(file_preview_match and file_preview_match.group(1).lower() == "yes"),
    )


def parse_config_dump(output: str) -> dict[str, GPhotoConfig]:
    configs: dict[str, GPhotoConfig] = {}
    current: GPhotoConfig | None = None
    for raw_line in output.splitlines():
        line = raw_line.rstrip("\r")
        if line.startswith("/"):
            if current is not None:
                configs[current.path] = current
            current = GPhotoConfig(path=line.strip())
            continue
        if current is None:
            continue
        if line == "END":
            configs[current.path] = current
            current = None
            continue
        if line.startswith("Choice:"):
            choice_match = re.match(r"Choice:\s+\d+\s?(.*)$", line)
            if choice_match:
                current.choices.append(choice_match.group(1))
            continue
        if ":" not in line:
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        if key == "Label":
            current.label = value
        elif key == "Readonly":
            current.readonly = value not in {"0", "false", "False"}
        elif key == "Type":
            current.kind = value.upper()
        elif key == "Current":
            current.current = value
        elif key == "Bottom":
            current.bottom = _parse_float(value)
        elif key == "Top":
            current.top = _parse_float(value)
        elif key == "Step":
            current.step = _parse_float(value)
    if current is not None:
        configs[current.path] = current
    return configs


def parse_storage_info(output: str) -> StorageSnapshot:
    device_headers = re.findall(r"^(?:Storage\s+#\d+|store_[^:]+):\s*$", output, re.M | re.I)
    capacities = _matching_ints(
        output,
        r"^\s*Maximum\s+(?:Capacity|Capability):\s*(\d+)",
        r"^\s*capacity\s*=\s*(\d+)",
    )
    free_bytes = _matching_ints(
        output,
        r"^\s*Free\s+Space\s*\(Bytes\):\s*(\d+)",
        r"^\s*free\s*=\s*(\d+)",
    )
    free_images = [
        value
        for value in _matching_ints(
            output,
            r"^\s*Free\s+Space\s*\(Images\):\s*(-?\d+)",
            r"^\s*freeimages\s*=\s*(-?\d+)",
        )
        if value >= 0
    ]
    devices = max(len(device_headers), len(capacities), len(free_bytes))
    return StorageSnapshot(
        available=devices > 0 if output.strip() else None,
        total_bytes=sum(capacities) if capacities else None,
        free_bytes=sum(free_bytes) if free_bytes else None,
        free_images=sum(free_images) if free_images else None,
        devices=devices,
    )


def parse_media_list(output: str) -> list[MediaItem]:
    current_folder = "/"
    items: list[MediaItem] = []
    folder_pattern = re.compile(r"There (?:is|are) \d+ files? in folder '([^']+)'", re.I)
    file_pattern = re.compile(
        r"^#(?P<number>\d+)\s+(?P<name>.+?)\s+"
        r"(?:(?P<access>[a-z-]{2})\s+)?(?P<size>\d+)\s+(?P<unit>[KMGT]?B)"
        r"(?:\s+\d+x\d+)?\s+(?P<mime>\S+)(?:\s+(?P<timestamp>\d{9,}))?\s*$",
        re.I,
    )
    for raw_line in output.splitlines():
        line = raw_line.strip()
        folder_match = folder_pattern.search(line)
        if folder_match:
            current_folder = folder_match.group(1)
            continue
        file_match = file_pattern.match(line)
        if not file_match:
            continue
        name = file_match.group("name").strip()
        content_type = file_match.group("mime")
        size = int(file_match.group("size")) * _size_multiplier(file_match.group("unit"))
        timestamp = file_match.group("timestamp")
        capture_time = None
        if timestamp:
            capture_time = datetime.fromtimestamp(int(timestamp), UTC).isoformat().replace("+00:00", "Z")
        items.append(
            MediaItem(
                id=_media_id(current_folder, name),
                name=name,
                kind=_media_kind(name, content_type),
                size_bytes=size,
                capture_time=capture_time,
                content_type=content_type,
            )
        )
    return list(reversed(items[-MAX_MEDIA_ITEMS:]))


class GPhoto2Engine:
    name = ENGINE_NAME

    def __init__(self, runner: GPhotoRunner | None = None) -> None:
        self.runner = runner or SubprocessGPhotoRunner()

    def health(self) -> tuple[bool, str | None, str | None]:
        return self.runner.health()

    def discover(self) -> list[CameraDescriptor]:
        available, _, detail = self.health()
        if not available:
            raise BridgeError(
                "ENGINE_UNAVAILABLE", detail or "gphoto2 is unavailable.", status_code=503, engine=self.name
            )
        return parse_auto_detect(self.runner.run(["--auto-detect"], timeout=15.0).text)

    def open(self, camera_id: str | None = None, profile_hint: str | None = None) -> GPhoto2Session:
        cameras = self.discover()
        if camera_id:
            cameras = [camera for camera in cameras if camera.id == camera_id or camera.port == camera_id]
        elif profile_hint:
            normalized_hint = profile_hint.casefold()
            preferred = [camera for camera in cameras if normalized_hint in camera.model.casefold()]
            if preferred:
                cameras = preferred
        if not cameras:
            raise BridgeError(
                "CAMERA_NOT_FOUND",
                "No matching camera was detected by gphoto2.",
                status_code=404,
                engine=self.name,
            )
        if len(cameras) > 1:
            raise BridgeError(
                "CAMERA_SELECTION_REQUIRED",
                "More than one camera is available; provide cameraId from GET /v1/cameras.",
                status_code=409,
                engine=self.name,
            )
        _, version, _ = self.health()
        return GPhoto2Session(self.runner, cameras[0], engine_version=version)


class GPhoto2Session:
    engine_name = ENGINE_NAME

    def __init__(
        self,
        runner: GPhotoRunner,
        camera: CameraDescriptor,
        *,
        engine_version: str | None = None,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self.runner = runner
        self.camera = camera
        self.engine_version = engine_version
        self._sleep = sleeper
        self._lock = threading.RLock()
        self._closed = False
        self._live_view_active = False
        self._cached_live_view_frame: bytes | None = None
        self._requested_fps = 1
        self._last_error: str | None = None
        self._summary_text = ""
        self._configs: dict[str, GPhotoConfig] = {}
        self._last_config_refresh = 0.0
        self._storage = StorageSnapshot(None, None, None, None, 0)
        self._media_supported = False
        self._media_cache: dict[str, MediaItem] = {}

        with self._lock:
            self._summary_text = self._optional_text(["--summary"], timeout=20.0)
            abilities_output = self._run(["--abilities"], timeout=20.0).text
            self._abilities = parse_abilities(abilities_output)
            self._refresh_configs(force=True)
            self._refresh_storage()
            self._media_supported = self._probe(["--folder", "/", "--no-recurse", "--list-files"])

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            if self._live_view_active:
                try:
                    self._set_viewfinder(False)
                except BridgeError as error:
                    self._last_error = error.message
            self._live_view_active = False
            self._cached_live_view_frame = None
            self._closed = True

    def info(self) -> CameraInfo:
        with self._lock:
            self._require_open()
            summary = parse_summary(self._summary_text)
            model = self._config_value("cameramodel") or summary.get("model") or self.camera.model
            serial = (
                self._config_value("eosserialnumber")
                or self._config_value("serialnumber")
                or summary.get("serial")
                or "unknown"
            )
            return CameraInfo(
                model=model,
                serial=serial,
                api="desktop-bridge/v1/libgphoto2",
                manufacturer=self._config_value("manufacturer") or summary.get("manufacturer"),
                device_version=self._config_value("deviceversion") or summary.get("device_version"),
                engine_version=self.engine_version,
            )

    def status(self) -> CameraStatus:
        with self._lock:
            self._require_open()
            self._refresh_configs(force=True)
            self._refresh_storage()
            battery_text = self._config_value("batterylevel")
            battery_level = _battery_level(battery_text)
            storage = self._storage
            recording_config = self._recording_config()
            return CameraStatus(
                battery=BatteryStatus(
                    level=battery_level,
                    status=_battery_status(battery_level, battery_text),
                ),
                recording=(recording_config.current.casefold() == "card") if recording_config else None,
                mode=self._config_value("autoexposuremode") or "unknown",
                media=StorageStatus(
                    available=storage.available,
                    total_bytes=storage.total_bytes,
                    free_bytes=storage.free_bytes,
                    free_images=storage.free_images,
                    devices=storage.devices,
                ),
                exposure=ExposureState(
                    iso=self._setting_value("iso"),
                    shutter=self._setting_value("shutter"),
                    aperture=self._setting_value("aperture"),
                    white_balance=self._setting_value("whitebalance"),
                ),
                raw={
                    "engine": self.engine_name,
                    "engineVersion": self.engine_version,
                    "port": self.camera.port,
                    "configCount": len(self._configs),
                    "lastError": self._last_error,
                },
            )

    def capabilities(self) -> CameraCapabilities:
        with self._lock:
            self._require_open()
            self._refresh_configs(force=False)
            settings = self._camera_settings()
            settings_by_key = {setting.key: setting for setting in settings}
            supported = {CameraFeature.DESKTOP_BRIDGE, CameraFeature.CAMERA_IDENTITY}
            if self._find_config(("batterylevel",)):
                supported.add(CameraFeature.BATTERY_STATUS)
            if self._storage.available is not None:
                supported.add(CameraFeature.STORAGE_STATUS)
            if self._abilities.capture_image or self._abilities.trigger_capture:
                supported.add(CameraFeature.STILL_CAPTURE)
            if self._abilities.capture_preview:
                supported.update({CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_JPEG_POLLING})
            if self._media_supported:
                supported.update({CameraFeature.MEDIA_BROWSER, CameraFeature.MEDIA_DOWNLOAD})
                if self._abilities.file_preview:
                    supported.add(CameraFeature.MEDIA_THUMBNAIL)
                if self._abilities.delete_files:
                    supported.add(CameraFeature.MEDIA_DELETE)
            if any(key in settings_by_key for key in ("iso", "shutter", "aperture")):
                supported.add(CameraFeature.EXPOSURE_CONTROL)
            if "whitebalance" in settings_by_key:
                supported.add(CameraFeature.WHITE_BALANCE_CONTROL)
            if any(not spec.core and spec.key in settings_by_key for spec in CONFIG_SPECS):
                supported.add(CameraFeature.ADVANCED_SETTINGS)
            if self._half_press_values() is not None:
                supported.add(CameraFeature.SHUTTER_HALF_PRESS)
            if self._recording_values() is not None:
                supported.add(CameraFeature.VIDEO_RECORDING)
            if self._focus_drive_config() is not None:
                supported.add(CameraFeature.FOCUS_DRIVE)

            planned = {
                feature
                for feature in (CameraFeature.TAP_FOCUS, CameraFeature.LIVE_VIEW_RTP)
                if feature not in supported
            }
            model = self.info().model
            return CameraCapabilities(
                profile=_camera_profile(model),
                supported=sorted(supported, key=str),
                planned=sorted(planned, key=str),
                reasons={
                    CameraFeature.TAP_FOCUS.value: (
                        "gphoto2 exposes autofocus and relative lens drive for this camera, but not a verified "
                        "normalized image-coordinate AF point command."
                    ),
                    CameraFeature.LIVE_VIEW.value: (
                        "The CLI adapter uses one gphoto2 --capture-preview transaction per HTTP frame; "
                        "a future native libgphoto2 adapter can provide a persistent stream."
                    ),
                },
                live_view=(
                    LiveViewCapabilities(
                        sources=["DESKTOP_BRIDGE_STREAM"],
                        default_source="DESKTOP_BRIDGE_STREAM",
                        sizes=["MEDIUM"],
                        default_size="MEDIUM",
                        max_fps=MAX_BRIDGE_LIVE_VIEW_FPS,
                    )
                    if CameraFeature.LIVE_VIEW in supported
                    else LiveViewCapabilities()
                ),
                settings=settings,
                evidence=self._capability_evidence(),
            )

    def set_setting(self, key: str, value: str) -> CameraStatus:
        with self._lock:
            spec = next((candidate for candidate in CONFIG_SPECS if candidate.key == key), None)
            if spec is None:
                raise unsupported(
                    CameraFeature.ADVANCED_SETTINGS.value, self.engine_name, f"Unknown setting key '{key}'."
                )
            config = self._find_config(spec.suffixes, writable=True)
            if config is None:
                feature = _feature_for_setting(key)
                raise unsupported(feature.value, self.engine_name)
            self._set_config_value(config, value, refresh=False)
            return self.status()

    def capture_still(self) -> CameraStatus:
        with self._lock:
            self._require_open()
            if self._abilities.trigger_capture:
                self._run(["--trigger-capture"], timeout=60.0)
            elif self._abilities.capture_image:
                self._run(["--capture-image"], timeout=60.0)
            else:
                raise unsupported(CameraFeature.STILL_CAPTURE.value, self.engine_name)
            return self.status()

    def half_press_shutter(self) -> CameraStatus:
        with self._lock:
            values = self._half_press_values()
            if values is None:
                raise unsupported(CameraFeature.SHUTTER_HALF_PRESS.value, self.engine_name)
            config, press_value, release_value = values
            pressed = False
            try:
                self._set_config_value(config, press_value, refresh=False)
                pressed = True
                self._sleep(0.35)
            finally:
                if pressed:
                    self._set_config_value(config, release_value, refresh=False)
            return self.status()

    def start_recording(self) -> CameraStatus:
        return self._set_recording(True)

    def stop_recording(self) -> CameraStatus:
        return self._set_recording(False)

    def drive_focus(self, direction: str, step: str) -> FocusResult:
        with self._lock:
            if not self._live_view_active:
                raise BridgeError(
                    "LIVE_VIEW_REQUIRED",
                    "Manual focus drive requires an active Live View session.",
                    status_code=409,
                    feature=CameraFeature.FOCUS_DRIVE.value,
                    engine=self.engine_name,
                )
            config = self._focus_drive_config()
            if config is None:
                raise unsupported(CameraFeature.FOCUS_DRIVE.value, self.engine_name)
            normalized_direction = direction.upper()
            normalized_step = step.upper()
            step_number = {"SMALL": 1, "MEDIUM": 2, "LARGE": 3}.get(normalized_step)
            if normalized_direction not in {"NEAR", "FAR"} or step_number is None:
                raise BridgeError("INVALID_FOCUS_DRIVE", "direction and step are invalid.", status_code=422)
            requested = f"{normalized_direction.title()} {step_number}"
            value = _case_insensitive_choice(config.choices, requested)
            if value is None:
                raise unsupported(
                    CameraFeature.FOCUS_DRIVE.value,
                    self.engine_name,
                    f"The camera did not advertise focus drive value '{requested}'.",
                )
            self._set_config_value(config, value, refresh=False)
            return FocusResult(accepted=True, direction=normalized_direction, step=normalized_step)

    def tap_focus(self, x: float, y: float) -> FocusResult:
        del x, y
        raise unsupported(
            CameraFeature.TAP_FOCUS.value,
            self.engine_name,
            "The libgphoto2 CLI engine has no verified normalized image-coordinate AF point command.",
        )

    def start_live_view(self, request: LiveViewStartRequest) -> None:
        with self._lock:
            if not self._abilities.capture_preview:
                raise unsupported(CameraFeature.LIVE_VIEW.value, self.engine_name)
            if request.source.upper() not in {"AUTO", "DESKTOP_BRIDGE_STREAM"}:
                raise BridgeError("INVALID_LIVE_VIEW_SOURCE", "Unsupported Live View source.", status_code=422)
            if request.size.upper() != "MEDIUM":
                raise BridgeError(
                    "INVALID_LIVE_VIEW_SIZE",
                    "The gphoto2 CLI adapter currently advertises only MEDIUM preview size.",
                    status_code=422,
                )
            viewfinder_enabled = self._set_viewfinder(True)
            try:
                frame = self._capture_preview()
            except BridgeError:
                if viewfinder_enabled:
                    try:
                        self._set_viewfinder(False)
                    except BridgeError as cleanup_error:
                        self._last_error = cleanup_error.message
                raise
            self._requested_fps = min(request.fps, MAX_BRIDGE_LIVE_VIEW_FPS)
            self._cached_live_view_frame = frame
            self._live_view_active = True

    def stop_live_view(self) -> None:
        with self._lock:
            self._require_open()
            try:
                if self._live_view_active:
                    self._set_viewfinder(False)
            finally:
                self._live_view_active = False
                self._cached_live_view_frame = None

    def live_view_frame(self) -> bytes:
        with self._lock:
            if not self._live_view_active:
                raise BridgeError(
                    "LIVE_VIEW_NOT_STARTED",
                    "Start Live View before requesting a frame.",
                    status_code=409,
                    feature=CameraFeature.LIVE_VIEW.value,
                    engine=self.engine_name,
                )
            if self._cached_live_view_frame is not None:
                frame = self._cached_live_view_frame
                self._cached_live_view_frame = None
                return frame
            return self._capture_preview()

    def list_media(self) -> list[MediaItem]:
        with self._lock:
            if not self._media_supported:
                raise unsupported(CameraFeature.MEDIA_BROWSER.value, self.engine_name)
            output = self._run(["--recurse", "--list-files"], timeout=60.0).text
            items = parse_media_list(output)
            self._media_cache = {item.id: item for item in items}
            return items

    def download_media(self, media_id: str) -> tuple[MediaItem, Iterator[bytes]]:
        folder, name = _decode_media_id(media_id)
        with self._lock:
            self._require_open()
            if not self._media_supported:
                raise unsupported(CameraFeature.MEDIA_DOWNLOAD.value, self.engine_name)
            cached = self._media_cache.get(media_id)
            content_type = mimetypes.guess_type(name)[0] or "application/octet-stream"
            item = cached or MediaItem(
                id=media_id,
                name=name,
                kind=_media_kind(name, content_type),
                content_type=content_type,
            )

        arguments = self._camera_arguments(["--folder", folder, "--get-file", name, "--stdout"])

        def stream() -> Iterator[bytes]:
            with self._lock:
                self._require_open()
                yield from self.runner.stream(arguments, timeout=600.0)

        return item, stream()

    def media_thumbnail(self, media_id: str) -> tuple[bytes, str]:
        folder, name = _decode_media_id(media_id)
        with self._lock:
            self._require_open()
            if not self._media_supported or not self._abilities.file_preview:
                raise unsupported(CameraFeature.MEDIA_THUMBNAIL.value, self.engine_name)
            output = self._run(
                ["--folder", folder, "--get-thumbnail", name, "--stdout"],
                timeout=60.0,
            ).stdout
            thumbnail, content_type = _validated_thumbnail(output)
            return thumbnail, content_type

    def delete_media(self, media_id: str) -> None:
        folder, name = _decode_media_id(media_id)
        with self._lock:
            self._require_open()
            if not self._media_supported or not self._abilities.delete_files:
                raise unsupported(CameraFeature.MEDIA_DELETE.value, self.engine_name)
            self._run(["--folder", folder, "--delete-file", name], timeout=60.0)
            self._media_cache.pop(media_id, None)

    @property
    def requested_fps(self) -> int:
        return self._requested_fps

    @property
    def live_view_active(self) -> bool:
        return self._live_view_active

    def _set_recording(self, recording: bool) -> CameraStatus:
        with self._lock:
            values = self._recording_values()
            if values is None:
                raise unsupported(CameraFeature.VIDEO_RECORDING.value, self.engine_name)
            config, start_value, stop_value = values
            self._set_config_value(config, start_value if recording else stop_value, refresh=False)
            return self.status()

    def _capture_preview(self) -> bytes:
        output = self._run(["--capture-preview", "--stdout"], timeout=30.0).stdout
        start = output.find(b"\xff\xd8")
        end = output.rfind(b"\xff\xd9")
        if start < 0 or end < start:
            raise BridgeError(
                "INVALID_LIVE_VIEW_FRAME",
                "gphoto2 capture-preview did not return a complete JPEG frame.",
                status_code=502,
                feature=CameraFeature.LIVE_VIEW.value,
                engine=self.engine_name,
            )
        return output[start : end + 2]

    def _camera_settings(self) -> list[CameraSetting]:
        settings: list[CameraSetting] = []
        for spec in CONFIG_SPECS:
            config = self._find_config(spec.suffixes, writable=True)
            if config is None:
                continue
            values = config.selectable_values()
            if not values:
                continue
            settings.append(
                CameraSetting(
                    key=spec.key,
                    label=config.label or spec.label,
                    value=config.current,
                    values=values,
                )
            )
        return settings

    def _setting_value(self, key: str) -> str:
        spec = next(candidate for candidate in CONFIG_SPECS if candidate.key == key)
        config = self._find_config(spec.suffixes)
        return config.current if config else "-"

    def _set_config_value(self, config: GPhotoConfig, value: str, *, refresh: bool = False) -> None:
        self._require_open()
        if config.readonly:
            raise BridgeError("READ_ONLY_SETTING", f"{config.label or config.path} is read-only.", status_code=409)
        values = config.selectable_values()
        selected_value = value
        if values:
            selected_value = _case_insensitive_choice(values, value)
            if selected_value is None:
                raise BridgeError(
                    "INVALID_SETTING_VALUE",
                    f"Value '{value}' is not advertised for {config.label or config.path}.",
                    status_code=422,
                    engine=self.engine_name,
                )
        self._run(["--set-config-value", f"{config.path}={selected_value}"], timeout=30.0)
        config.current = selected_value
        if refresh:
            self._refresh_configs(force=True)

    def _half_press_values(self) -> tuple[GPhotoConfig, str, str] | None:
        config = self._find_config(("eosremoterelease",), writable=True)
        if config is None:
            return None
        press = _first_choice(config.choices, "Press Half AF", "Press Half", "Press Half MF")
        release = _first_choice(config.choices, "Release Half", "Release")
        return (config, press, release) if press and release else None

    def _recording_values(self) -> tuple[GPhotoConfig, str, str] | None:
        config = self._recording_config()
        if config is None or config.readonly:
            return None
        start = _first_choice(config.choices, "Card")
        stop = _first_choice(config.choices, "None")
        return (config, start, stop) if start and stop else None

    def _recording_config(self) -> GPhotoConfig | None:
        return self._find_config(("movierecordtarget",))

    def _focus_drive_config(self) -> GPhotoConfig | None:
        config = self._find_config(("manualfocusdrive",), writable=True)
        if config and any(choice.casefold().startswith(("near ", "far ")) for choice in config.choices):
            return config
        return None

    def _set_viewfinder(self, enabled: bool) -> bool:
        config = self._find_config(("viewfinder",), writable=True)
        if config is None:
            return False
        self._set_config_value(config, "1" if enabled else "0", refresh=False)
        return True

    def _capability_evidence(self) -> CapabilityEvidence:
        commands: list[str] = []
        if self._abilities.capture_image:
            commands.append("CAPTURE_IMAGE")
        if self._abilities.trigger_capture:
            commands.append("TRIGGER_CAPTURE")
        if self._abilities.capture_preview:
            commands.append("CAPTURE_PREVIEW")
        if self._media_supported:
            commands.extend(("MEDIA_LIST", "MEDIA_DOWNLOAD"))
            if self._abilities.file_preview:
                commands.append("MEDIA_THUMBNAIL")
            if self._abilities.delete_files:
                commands.append("MEDIA_DELETE")
        writable_settings = sorted(
            {
                config.path.replace("\r", "").replace("\n", "")[
                    :MAX_CAPABILITY_EVIDENCE_ITEM_CHARS
                ]
                for config in self._configs.values()
                if not config.readonly and config.selectable_values()
            }
        )
        return CapabilityEvidence(
            source="gphoto2 --abilities + --list-all-config",
            protocol_versions=(
                [self.engine_version[:MAX_CAPABILITY_EVIDENCE_ITEM_CHARS]] if self.engine_version else []
            ),
            advertised_commands=commands,
            writable_settings=writable_settings[:MAX_CAPABILITY_EVIDENCE_ITEMS],
            truncated=len(writable_settings) > MAX_CAPABILITY_EVIDENCE_ITEMS,
        )

    def _refresh_configs(self, *, force: bool) -> None:
        now = time.monotonic()
        if not force and now - self._last_config_refresh < CONFIG_REFRESH_SECONDS:
            return
        try:
            output = self._run(["--list-all-config"], timeout=45.0).text
            parsed = parse_config_dump(output)
            if parsed:
                self._configs = parsed
            self._last_error = None
        except BridgeError as error:
            self._last_error = error.message
        self._last_config_refresh = now

    def _refresh_storage(self) -> None:
        try:
            output = self._run(["--storage-info"], timeout=30.0).text
            self._storage = parse_storage_info(output)
            self._last_error = None
        except BridgeError as error:
            self._last_error = error.message

    def _find_config(self, suffixes: tuple[str, ...], *, writable: bool = False) -> GPhotoConfig | None:
        candidates = [
            config
            for config in self._configs.values()
            if any(config.path.casefold().endswith(f"/{suffix.casefold()}") for suffix in suffixes)
            and (not writable or not config.readonly)
        ]
        candidates.sort(
            key=lambda config: (
                config.readonly,
                not config.path.startswith(("/main/imgsettings/", "/main/capturesettings/", "/main/actions/")),
                config.path,
            )
        )
        return candidates[0] if candidates else None

    def _config_value(self, suffix: str) -> str | None:
        config = self._find_config((suffix,))
        return config.current if config and config.current else None

    def _probe(self, arguments: list[str]) -> bool:
        try:
            self._run(arguments, timeout=20.0)
            return True
        except BridgeError as error:
            self._last_error = error.message
            return False

    def _optional_text(self, arguments: list[str], *, timeout: float) -> str:
        try:
            return self._run(arguments, timeout=timeout).text
        except BridgeError as error:
            self._last_error = error.message
            return ""

    def _run(self, arguments: list[str], *, timeout: float) -> CommandOutput:
        self._require_open()
        return self.runner.run(self._camera_arguments(arguments), timeout=timeout)

    def _camera_arguments(self, arguments: list[str]) -> list[str]:
        return ["--port", self.camera.port, *arguments]

    def _require_open(self) -> None:
        if self._closed:
            raise BridgeError(
                "SESSION_CLOSED", "The camera session is closed.", status_code=410, engine=self.engine_name
            )


def _camera_id(port: str) -> str:
    encoded = base64.urlsafe_b64encode(port.encode()).decode().rstrip("=")
    return f"gphoto2-{encoded}"


def _media_id(folder: str, name: str) -> str:
    payload = json.dumps([folder, name], separators=(",", ":")).encode()
    return "gphoto2:" + base64.urlsafe_b64encode(payload).decode().rstrip("=")


def _decode_media_id(media_id: str) -> tuple[str, str]:
    if not media_id.startswith("gphoto2:"):
        raise BridgeError("INVALID_MEDIA_ID", "Media ID does not belong to gphoto2.", status_code=422)
    encoded = media_id.removeprefix("gphoto2:")
    try:
        payload = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        folder, name = json.loads(payload)
    except (ValueError, TypeError, json.JSONDecodeError) as error:
        raise BridgeError("INVALID_MEDIA_ID", "Media ID is malformed.", status_code=422) from error
    if (
        not isinstance(folder, str)
        or not isinstance(name, str)
        or not folder.startswith("/")
        or not name
        or "/" in name
        or any(character in folder + name for character in ("\x00", "\r", "\n"))
    ):
        raise BridgeError("INVALID_MEDIA_ID", "Media ID contains an invalid camera path.", status_code=422)
    return folder, name


def _matching_ints(output: str, *patterns: str) -> list[int]:
    values: list[int] = []
    for pattern in patterns:
        values.extend(int(match) for match in re.findall(pattern, output, re.M | re.I))
    return values


def _parse_float(value: str) -> float | None:
    try:
        return float(value)
    except ValueError:
        return None


def _format_number(value: float) -> str:
    return f"{value:.6f}".rstrip("0").rstrip(".")


def _size_multiplier(unit: str) -> int:
    return {
        "B": 1,
        "KB": 1024,
        "MB": 1024**2,
        "GB": 1024**3,
        "TB": 1024**4,
    }.get(unit.upper(), 1)


def _validated_thumbnail(output: bytes) -> tuple[bytes, str]:
    if len(output) > MAX_MEDIA_THUMBNAIL_BYTES:
        raise BridgeError(
            "MEDIA_THUMBNAIL_LIMIT",
            f"gphoto2 returned a thumbnail larger than {MAX_MEDIA_THUMBNAIL_BYTES} bytes.",
            status_code=502,
            feature=CameraFeature.MEDIA_THUMBNAIL.value,
            engine=ENGINE_NAME,
        )
    jpeg_start = output.find(b"\xff\xd8")
    jpeg_end = output.rfind(b"\xff\xd9")
    if jpeg_start >= 0 and jpeg_end >= jpeg_start:
        return output[jpeg_start : jpeg_end + 2], "image/jpeg"
    if output.startswith(b"\x89PNG\r\n\x1a\n"):
        return output, "image/png"
    raise BridgeError(
        "INVALID_MEDIA_THUMBNAIL",
        "gphoto2 did not return a supported JPEG or PNG thumbnail.",
        status_code=502,
        feature=CameraFeature.MEDIA_THUMBNAIL.value,
        engine=ENGINE_NAME,
    )


def _media_kind(name: str, content_type: str) -> str:
    lowered = content_type.casefold()
    if lowered.startswith("image/"):
        return "image"
    if lowered.startswith("video/"):
        return "video"
    extension = os.path.splitext(name)[1].casefold()
    if extension in {".jpg", ".jpeg", ".png", ".heif", ".heic", ".cr2", ".cr3", ".dng"}:
        return "image"
    if extension in {".mp4", ".mov", ".avi", ".mkv"}:
        return "video"
    return "other"


def _battery_level(value: str | None) -> int | None:
    if value is None:
        return None
    match = re.search(r"(\d{1,3})\s*%", value)
    if match:
        return min(int(match.group(1)), 100)
    if value.casefold() == "full":
        return 100
    return None


def _battery_status(level: int | None, raw: str | None) -> str:
    if level is not None:
        return "low" if level <= 20 else "normal"
    return raw or "unknown"


def _first_choice(choices: list[str], *candidates: str) -> str | None:
    for candidate in candidates:
        found = _case_insensitive_choice(choices, candidate)
        if found is not None:
            return found
    return None


def _case_insensitive_choice(choices: list[str], candidate: str) -> str | None:
    normalized = candidate.casefold()
    return next((choice for choice in choices if choice.casefold() == normalized), None)


def _feature_for_setting(key: str) -> CameraFeature:
    if key in {"iso", "shutter", "aperture"}:
        return CameraFeature.EXPOSURE_CONTROL
    if key == "whitebalance":
        return CameraFeature.WHITE_BALANCE_CONTROL
    return CameraFeature.ADVANCED_SETTINGS


def _camera_profile(model: str) -> CameraProfile:
    normalized = model.casefold()
    if "eos r6 mark iii" in normalized or "eos r6m3" in normalized:
        return CameraProfile(model_name=model, family="EOS_R", priority="PRIMARY")
    if "eos r" in normalized:
        return CameraProfile(model_name=model, family="EOS_R", priority="SUPPORTED")
    if "eos m" in normalized:
        return CameraProfile(model_name=model, family="EOS_M", priority="SUPPORTED")
    if "eos" in normalized:
        return CameraProfile(model_name=model, family="EOS_DSLR", priority="SUPPORTED")
    if "powershot" in normalized:
        return CameraProfile(model_name=model, family="POWERSHOT", priority="RESEARCH")
    return CameraProfile(model_name=model, family="UNKNOWN", priority="RESEARCH")
