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
import uuid
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path, PureWindowsPath
from typing import Protocol

from .errors import BridgeError, unsupported
from .local_media import LocalCaptureStore, default_capture_directory, is_host_media_id
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
    LiveViewMagnificationResult,
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
MAX_BRIDGE_LIVE_VIEW_FPS = 30
MAX_PREVIEW_FALLBACK_FPS = 5
MAX_LIVE_VIEW_FRAME_BYTES = 16 * 1024 * 1024
MAX_LIVE_VIEW_BUFFER_BYTES = MAX_LIVE_VIEW_FRAME_BYTES + 64 * 1024
LIVE_VIEW_FIRST_FRAME_TIMEOUT_SECONDS = 10.0
LIVE_VIEW_FRAME_TIMEOUT_SECONDS = 10.0
LIVE_VIEW_STREAM_TIMEOUT_SECONDS = 24 * 60 * 60


@dataclass(frozen=True)
class CommandOutput:
    stdout: bytes
    stderr: str = ""

    @property
    def text(self) -> str:
        return _decode_process_text(self.stdout)


@dataclass(frozen=True)
class GPhotoCommand:
    prefix: tuple[str, ...]
    host_mode: str
    wsl_distro: str | None = None

    @property
    def display(self) -> str:
        if self.host_mode == "wsl":
            distro = f" ({self.wsl_distro})" if self.wsl_distro else ""
            return f"gphoto2 via WSL{distro}"
        return self.prefix[0]


@dataclass(frozen=True)
class WslHostState:
    distributions: tuple[str, ...] = ()
    usbipd_available: bool = False
    error: str | None = None


def resolve_gphoto_command(
    binary: str | None = None,
    *,
    environment: Mapping[str, str] | None = None,
    platform_name: str | None = None,
    which: Callable[[str], str | None] = shutil.which,
) -> GPhotoCommand:
    configured_environment = environment if environment is not None else os.environ
    explicit = binary or configured_environment.get("OPEN_EOS_GPHOTO2")
    if explicit:
        return GPhotoCommand((explicit,), "native")

    native = which("gphoto2")
    if native:
        return GPhotoCommand((native,), "native")

    if (platform_name or os.name) == "nt":
        wsl = which("wsl.exe")
        if wsl:
            distro = configured_environment.get("OPEN_EOS_GPHOTO2_WSL_DISTRO") or None
            prefix = [wsl]
            if distro:
                prefix.extend(("--distribution", distro))
            prefix.extend(("--exec", "gphoto2"))
            return GPhotoCommand(tuple(prefix), "wsl", distro)

    return GPhotoCommand(("gphoto2",), "native")


class GPhotoRunner(Protocol):
    def health(self) -> tuple[bool, str | None, str | None]: ...

    def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput: ...

    def host_path(self, path: Path) -> str: ...

    def open_stream(self, arguments: list[str], *, timeout: float = 300.0) -> ClosableByteStream: ...

    def stream(self, arguments: list[str], *, timeout: float = 300.0) -> Iterator[bytes]: ...


class ClosableByteStream(Protocol):
    def __iter__(self) -> Iterator[bytes]: ...

    def __next__(self) -> bytes: ...

    def close(self) -> None: ...


class SubprocessByteStream:
    def __init__(self, command: GPhotoCommand, arguments: list[str], *, timeout: float) -> None:
        self._command = command
        self._arguments = arguments
        self._timeout = timeout
        self._started_at = time.monotonic()
        self._stdout_queue: queue.Queue[bytes | object] = queue.Queue(maxsize=8)
        self._stdout_complete = object()
        self._stop_reading = threading.Event()
        self._closed = threading.Event()
        self._close_lock = threading.Lock()
        self._stderr_parts: list[bytes] = []
        self._stderr_size = 0
        try:
            self._process = subprocess.Popen(
                [*command.prefix, *arguments],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=_command_environment(),
            )
        except FileNotFoundError as error:
            raise _engine_unavailable(command.display) from error

        self._stdout_thread = threading.Thread(
            target=self._drain_stdout,
            name="gphoto2-stdout",
            daemon=True,
        )
        self._stderr_thread = threading.Thread(
            target=self._drain_stderr,
            name="gphoto2-stderr",
            daemon=True,
        )
        self._stdout_thread.start()
        self._stderr_thread.start()

    def __iter__(self) -> SubprocessByteStream:
        return self

    def __next__(self) -> bytes:
        while not self._closed.is_set():
            remaining = self._timeout - (time.monotonic() - self._started_at)
            if remaining <= 0:
                self.close()
                raise BridgeError(
                    "ENGINE_TIMEOUT",
                    f"gphoto2 media transfer exceeded {self._timeout:g} seconds.",
                    status_code=504,
                    engine=ENGINE_NAME,
                )
            try:
                chunk = self._stdout_queue.get(timeout=min(remaining, 0.25))
            except queue.Empty:
                continue
            if chunk is self._stdout_complete:
                with self._close_lock:
                    if self._closed.is_set():
                        raise StopIteration
                    return_code = self._finish_process(terminate=False)
                    self._closed.set()
                if return_code != 0:
                    raise _command_error(self._arguments, return_code, self._stderr_text())
                raise StopIteration
            assert isinstance(chunk, bytes)
            return chunk
        raise StopIteration

    def close(self) -> None:
        with self._close_lock:
            if self._closed.is_set():
                return
            self._closed.set()
            self._stop_reading.set()
            self._finish_process(terminate=True)

    def _drain_stdout(self) -> None:
        assert self._process.stdout is not None
        try:
            while not self._stop_reading.is_set():
                chunk = self._process.stdout.read(64 * 1024)
                if not chunk:
                    break
                while not self._stop_reading.is_set():
                    try:
                        self._stdout_queue.put(chunk, timeout=0.1)
                        break
                    except queue.Full:
                        continue
        finally:
            while not self._stop_reading.is_set():
                try:
                    self._stdout_queue.put(self._stdout_complete, timeout=0.1)
                    break
                except queue.Full:
                    continue

    def _drain_stderr(self) -> None:
        assert self._process.stderr is not None
        while chunk := self._process.stderr.read(16 * 1024):
            self._stderr_parts.append(chunk)
            self._stderr_size += len(chunk)
            while self._stderr_size > 256 * 1024 and len(self._stderr_parts) > 1:
                self._stderr_size -= len(self._stderr_parts.pop(0))

    def _finish_process(self, *, terminate: bool) -> int:
        self._stop_reading.set()
        if terminate and self._process.poll() is None:
            self._process.terminate()
        try:
            return_code = self._process.wait(timeout=5.0)
        except subprocess.TimeoutExpired:
            self._process.kill()
            return_code = self._process.wait(timeout=5.0)
        if threading.current_thread() is not self._stdout_thread:
            self._stdout_thread.join(timeout=1.0)
        if threading.current_thread() is not self._stderr_thread:
            self._stderr_thread.join(timeout=1.0)
        return return_code

    def _stderr_text(self) -> str:
        return _decode_process_text(b"".join(self._stderr_parts))


class SubprocessGPhotoRunner:
    def __init__(
        self,
        binary: str | None = None,
        *,
        command: GPhotoCommand | None = None,
        wsl_probe: Callable[[GPhotoCommand], WslHostState] | None = None,
    ) -> None:
        self.command = command or resolve_gphoto_command(binary)
        self.binary = self.command.prefix[0]
        self._wsl_probe = wsl_probe or _probe_wsl_host

    def health(self) -> tuple[bool, str | None, str | None]:
        resolved = shutil.which(self.command.prefix[0])
        if resolved is None:
            return False, None, f"Host executable '{self.command.prefix[0]}' was not found on PATH."
        wsl_state: WslHostState | None = None
        if self.command.host_mode == "wsl":
            wsl_state = self._wsl_probe(self.command)
            if wsl_state.error:
                return False, None, wsl_state.error
        try:
            output = self.run(["--version"], timeout=5.0)
        except BridgeError as error:
            if self.command.host_mode == "wsl":
                distro = self.command.wsl_distro or "the default WSL distribution"
                return (
                    False,
                    None,
                    f"gphoto2 is not runnable in {distro}. Install it there with "
                    f"'sudo apt update && sudo apt install gphoto2 usbutils'. {error.message}",
                )
            return False, None, error.message
        first_line = next((line.strip() for line in output.text.splitlines() if line.strip()), None)
        detail = None
        if wsl_state is not None:
            distro = self.command.wsl_distro or wsl_state.distributions[0]
            detail = f"Using gphoto2 in WSL distribution '{distro}'."
            if not wsl_state.usbipd_available:
                detail += " Install usbipd-win before attaching a Windows USB camera to WSL."
        return True, first_line, detail

    def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
        command = [*self.command.prefix, *arguments]
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                check=False,
                timeout=timeout,
                env=_command_environment(),
            )
        except FileNotFoundError as error:
            raise _engine_unavailable(self.command.display) from error
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
        stderr = _decode_process_text(completed.stderr)
        if completed.returncode != 0:
            raise _command_error(arguments, completed.returncode, stderr)
        return CommandOutput(stdout=completed.stdout, stderr=stderr)

    def host_path(self, path: Path) -> str:
        resolved = path.resolve(strict=False)
        if self.command.host_mode != "wsl":
            return os.fspath(resolved)
        return _windows_path_to_wsl(os.fspath(resolved))

    def open_stream(self, arguments: list[str], *, timeout: float = 300.0) -> ClosableByteStream:
        return SubprocessByteStream(self.command, arguments, timeout=timeout)

    def stream(self, arguments: list[str], *, timeout: float = 300.0) -> Iterator[bytes]:
        stream = self.open_stream(arguments, timeout=timeout)

        def iterator() -> Iterator[bytes]:
            try:
                yield from stream
            finally:
                stream.close()

        return iterator()


def _command_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment["LC_ALL"] = "C"
    environment["LANG"] = "C"
    return environment


def _windows_path_to_wsl(value: str) -> str:
    windows_path = PureWindowsPath(value)
    drive = windows_path.drive
    if len(drive) != 2 or drive[1] != ":":
        raise BridgeError(
            "UNSUPPORTED_CAPTURE_DIRECTORY",
            "WSL capture storage must be on a local Windows drive.",
            status_code=500,
            feature=CameraFeature.STILL_CAPTURE.value,
            engine=ENGINE_NAME,
        )
    relative_parts = windows_path.parts[1:]
    suffix = "/".join(relative_parts)
    return f"/mnt/{drive[0].lower()}/{suffix}" if suffix else f"/mnt/{drive[0].lower()}"


def _decode_process_text(value: bytes) -> str:
    if not value:
        return ""
    if value.startswith((b"\xff\xfe", b"\xfe\xff")) or value.count(b"\x00") > len(value) // 8:
        try:
            encoding = "utf-16" if value.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-16-le"
            return value.decode(encoding, errors="replace").replace("\ufeff", "")
        except UnicodeError:
            pass
    return value.decode("utf-8", errors="replace")


def _probe_wsl_host(command: GPhotoCommand) -> WslHostState:
    wsl = command.prefix[0]
    try:
        completed = subprocess.run(
            [wsl, "--list", "--quiet"],
            capture_output=True,
            check=False,
            timeout=5.0,
            env=_command_environment(),
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return WslHostState(error="WSL is installed but its distribution list could not be read.")
    output = _decode_process_text(completed.stdout)
    distributions = tuple(line.strip().strip("\x00") for line in output.splitlines() if line.strip().strip("\x00"))
    if completed.returncode != 0 or not distributions:
        return WslHostState(
            error=(
                "Native gphoto2 was not found and WSL has no Linux distribution. "
                "Install one with 'wsl --install -d Ubuntu' before using PC USB control."
            )
        )
    if command.wsl_distro and command.wsl_distro.casefold() not in {
        distribution.casefold() for distribution in distributions
    }:
        return WslHostState(
            distributions=distributions,
            error=(
                f"Configured WSL distribution '{command.wsl_distro}' was not found. "
                f"Available: {', '.join(distributions)}."
            ),
        )
    return WslHostState(
        distributions=distributions,
        usbipd_available=shutil.which("usbipd.exe") is not None,
    )


def _engine_unavailable(executable: str) -> BridgeError:
    return BridgeError(
        "ENGINE_UNAVAILABLE",
        f"Host command '{executable}' is not installed or is not on PATH.",
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


class MjpegFrameParser:
    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, chunk: bytes) -> list[bytes]:
        if chunk:
            self._buffer.extend(chunk)
        frames: list[bytes] = []
        while True:
            start = self._buffer.find(b"\xff\xd8")
            if start < 0:
                if len(self._buffer) > MAX_LIVE_VIEW_BUFFER_BYTES:
                    del self._buffer[:-1]
                return frames
            if start:
                del self._buffer[:start]
            end = self._buffer.find(b"\xff\xd9", 2)
            if end < 0:
                if len(self._buffer) > MAX_LIVE_VIEW_FRAME_BYTES:
                    raise BridgeError(
                        "LIVE_VIEW_FRAME_LIMIT",
                        f"gphoto2 returned a Live View frame larger than {MAX_LIVE_VIEW_FRAME_BYTES} bytes.",
                        status_code=502,
                        feature=CameraFeature.LIVE_VIEW.value,
                        engine=ENGINE_NAME,
                    )
                return frames
            end += 2
            frames.append(bytes(self._buffer[:end]))
            del self._buffer[:end]


class GPhotoMjpegSession:
    def __init__(self, source: ClosableByteStream, *, target_fps: int) -> None:
        self._source = source
        self._target_fps = max(1, min(target_fps, MAX_BRIDGE_LIVE_VIEW_FPS))
        self._condition = threading.Condition()
        self._closed = False
        self._latest_frame: bytes | None = None
        self._frame_generation = 0
        self._delivered_generation = 0
        self._last_published_at = 0.0
        self._error: BridgeError | None = None
        self._thread = threading.Thread(target=self._pump, name="gphoto2-mjpeg", daemon=True)

    def start(self, timeout: float = LIVE_VIEW_FIRST_FRAME_TIMEOUT_SECONDS) -> None:
        self._thread.start()
        deadline = time.monotonic() + timeout
        with self._condition:
            while not self._closed and self._frame_generation == 0 and self._error is None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self._condition.wait(remaining)
            if self._frame_generation > 0:
                return
            error = self._error or BridgeError(
                "LIVE_VIEW_FIRST_FRAME_TIMEOUT",
                f"gphoto2 capture-movie did not produce a JPEG frame within {timeout:g} seconds.",
                status_code=504,
                feature=CameraFeature.LIVE_VIEW.value,
                engine=ENGINE_NAME,
            )
        self.close()
        raise error

    def read_frame(self, timeout: float = LIVE_VIEW_FRAME_TIMEOUT_SECONDS) -> bytes:
        deadline = time.monotonic() + timeout
        with self._condition:
            while not self._closed and self._frame_generation <= self._delivered_generation and self._error is None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self._condition.wait(remaining)
            if self._latest_frame is not None and self._frame_generation > self._delivered_generation:
                self._delivered_generation = self._frame_generation
                return self._latest_frame
            if self._error is not None:
                raise self._error
            raise BridgeError(
                "LIVE_VIEW_FRAME_TIMEOUT",
                f"gphoto2 capture-movie did not produce another JPEG frame within {timeout:g} seconds.",
                status_code=504,
                feature=CameraFeature.LIVE_VIEW.value,
                engine=ENGINE_NAME,
            )

    def close(self) -> None:
        with self._condition:
            if self._closed:
                return
            self._closed = True
            self._condition.notify_all()
        self._source.close()
        if threading.current_thread() is not self._thread:
            self._thread.join(timeout=3.0)

    def _pump(self) -> None:
        parser = MjpegFrameParser()
        try:
            for chunk in self._source:
                for frame in parser.feed(chunk):
                    now = time.monotonic()
                    if self._last_published_at and now - self._last_published_at < 1 / self._target_fps:
                        continue
                    with self._condition:
                        if self._closed:
                            return
                        self._latest_frame = frame
                        self._frame_generation += 1
                        self._last_published_at = now
                        self._condition.notify_all()
            with self._condition:
                if not self._closed:
                    self._error = BridgeError(
                        "LIVE_VIEW_STREAM_ENDED",
                        "gphoto2 capture-movie ended before Live View was stopped.",
                        status_code=502,
                        feature=CameraFeature.LIVE_VIEW.value,
                        engine=ENGINE_NAME,
                    )
                    self._condition.notify_all()
        except BridgeError as error:
            with self._condition:
                if not self._closed:
                    self._error = error
                    self._condition.notify_all()
        except Exception as error:
            with self._condition:
                if not self._closed:
                    self._error = BridgeError(
                        "LIVE_VIEW_STREAM_FAILED",
                        f"gphoto2 capture-movie failed: {type(error).__name__}: {error}",
                        status_code=502,
                        feature=CameraFeature.LIVE_VIEW.value,
                        engine=ENGINE_NAME,
                    )
                    self._condition.notify_all()
        finally:
            self._source.close()


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
    ConfigSpec("stillimagequalitysd", "SD image quality", ("imageformatsd",)),
    ConfigSpec("stillimagequalitycf", "CF/CFexpress image quality", ("imageformatcf",)),
    ConfigSpec("shootingmode", "Shooting mode", ("autoexposuremode",)),
    ConfigSpec("colortemperature", "Color temperature", ("colortemperature",)),
    ConfigSpec("whitebalanceadjusta", "White balance shift A", ("whitebalanceadjusta",)),
    ConfigSpec("whitebalanceadjustb", "White balance shift B", ("whitebalanceadjustb",)),
    ConfigSpec("colorspace", "Color space", ("colorspace",)),
    ConfigSpec("aspectratio", "Aspect ratio", ("aspectratio",)),
    ConfigSpec("zoomspeed", "Power zoom speed", ("zoomspeed",)),
    ConfigSpec("autopoweroff", "Auto power off", ("autopoweroff",)),
    ConfigSpec("highisonr", "High ISO noise reduction", ("highisonr",)),
    ConfigSpec("continuousaf", "Continuous AF", ("continuousaf",)),
    ConfigSpec("movieservoaf", "Movie Servo AF", ("movieservoaf",)),
    ConfigSpec("aeb", "Auto exposure bracketing", ("aeb",)),
    ConfigSpec("capturetarget", "Capture target", ("capturetarget",)),
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

    def __init__(
        self,
        runner: GPhotoRunner | None = None,
        *,
        capture_directory: Path | None = None,
        environment: Mapping[str, str] | None = None,
    ) -> None:
        self.runner = runner or SubprocessGPhotoRunner()
        self.capture_store = LocalCaptureStore(capture_directory or default_capture_directory(environment=environment))

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
        return GPhoto2Session(
            self.runner,
            cameras[0],
            engine_version=version,
            capture_store=self.capture_store,
        )


class GPhoto2Session:
    engine_name = ENGINE_NAME

    def __init__(
        self,
        runner: GPhotoRunner,
        camera: CameraDescriptor,
        *,
        engine_version: str | None = None,
        sleeper: Callable[[float], None] = time.sleep,
        capture_store: LocalCaptureStore | None = None,
    ) -> None:
        self.runner = runner
        self.camera = camera
        self.engine_version = engine_version
        self._sleep = sleeper
        self._capture_store = capture_store or LocalCaptureStore(default_capture_directory())
        self._lock = threading.RLock()
        self._closed = False
        self._live_view_active = False
        self._cached_live_view_frame: bytes | None = None
        self._live_view_stream: GPhotoMjpegSession | None = None
        self._live_view_transport: str | None = None
        self._live_view_fallback_reason: str | None = None
        self._live_view_magnification: int | None = None
        self._bulb_exposure_active = False
        self._requested_fps = 1
        self._last_error: str | None = None
        self._summary_text = ""
        self._configs: dict[str, GPhotoConfig] = {}
        self._last_config_refresh = 0.0
        self._storage = StorageSnapshot(None, None, None, None, 0)
        self._camera_media_supported = False
        self._media_cache: dict[str, MediaItem] = {}
        self._observed: set[CameraFeature] = {CameraFeature.DESKTOP_BRIDGE}

        with self._lock:
            self._summary_text = self._optional_text(["--summary"], timeout=20.0)
            abilities_output = self._run(["--abilities"], timeout=20.0).text
            self._abilities = parse_abilities(abilities_output)
            self._refresh_configs(force=True)
            self._refresh_storage()
            self._camera_media_supported = self._probe(["--folder", "/", "--no-recurse", "--list-files"])

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            if self._bulb_exposure_active:
                bulb_values = self._bulb_values()
                if bulb_values is not None:
                    try:
                        config, _, release_value = bulb_values
                        self._set_config_value(config, release_value, refresh=False)
                        self._bulb_exposure_active = False
                    except BridgeError as error:
                        self._last_error = error.message
            was_live_view_active = self._live_view_active
            self._live_view_active = False
            self._stop_movie_stream()
            if was_live_view_active:
                try:
                    self._set_viewfinder(False)
                except BridgeError as error:
                    self._last_error = error.message
            self._cached_live_view_frame = None
            self._live_view_transport = None
            self._closed = True

    def info(self) -> CameraInfo:
        with self._lock:
            self._require_open()
            self._observed.add(CameraFeature.CAMERA_IDENTITY)
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
            available_shots = _parse_available_shots(self._config_value("availableshots"))
            free_images = available_shots if available_shots is not None else storage.free_images
            recording_config = self._recording_config()
            if self._find_config(("batterylevel",)):
                self._observed.add(CameraFeature.BATTERY_STATUS)
            if storage.available is not None:
                self._observed.add(CameraFeature.STORAGE_STATUS)
            return CameraStatus(
                battery=BatteryStatus(
                    level=battery_level,
                    status=_battery_status(battery_level, battery_text),
                ),
                recording=(recording_config.current.casefold() == "card") if recording_config else None,
                bulb_exposure_active=self._bulb_exposure_active,
                mode=self._config_value("autoexposuremode") or "unknown",
                media=StorageStatus(
                    available=storage.available,
                    total_bytes=storage.total_bytes,
                    free_bytes=storage.free_bytes,
                    free_images=free_images,
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
                    "liveViewTransport": self._live_view_transport,
                    "liveViewFallbackReason": self._live_view_fallback_reason,
                    "remainingShotsSource": (
                        "gphoto2-config:/main/status/availableshots"
                        if available_shots is not None
                        else "gphoto2-storage-info"
                        if storage.free_images is not None
                        else None
                    ),
                },
            )

    def capabilities(self) -> CameraCapabilities:
        with self._lock:
            self._require_open()
            self._refresh_configs(force=False)
            settings = self._camera_settings()
            settings_by_key = {setting.key: setting for setting in settings}
            host_media_supported = self._host_capture_supported() or bool(self._capture_store.list_items())
            media_supported = self._camera_media_supported or host_media_supported
            supported = {CameraFeature.DESKTOP_BRIDGE, CameraFeature.CAMERA_IDENTITY}
            if self._find_config(("batterylevel",)):
                supported.add(CameraFeature.BATTERY_STATUS)
            if self._storage.available is not None:
                supported.add(CameraFeature.STORAGE_STATUS)
            if self._still_capture_supported():
                supported.add(CameraFeature.STILL_CAPTURE)
            if self._abilities.capture_preview:
                supported.update({CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_JPEG_POLLING})
            if media_supported:
                supported.update({CameraFeature.MEDIA_BROWSER, CameraFeature.MEDIA_DOWNLOAD})
                if self._abilities.file_preview or host_media_supported:
                    supported.add(CameraFeature.MEDIA_THUMBNAIL)
                if self._abilities.delete_files or host_media_supported:
                    supported.add(CameraFeature.MEDIA_DELETE)
            if any(key in settings_by_key for key in ("iso", "shutter", "aperture")):
                supported.add(CameraFeature.EXPOSURE_CONTROL)
            if "whitebalance" in settings_by_key:
                supported.add(CameraFeature.WHITE_BALANCE_CONTROL)
            if any(not spec.core and spec.key in settings_by_key for spec in CONFIG_SPECS):
                supported.add(CameraFeature.ADVANCED_SETTINGS)
            half_press_values = self._half_press_values()
            autofocus_configs = self._autofocus_configs()
            if half_press_values is not None:
                supported.add(CameraFeature.SHUTTER_HALF_PRESS)
            if self._bulb_values() is not None:
                supported.add(CameraFeature.BULB_EXPOSURE)
            if autofocus_configs is not None or half_press_values is not None:
                supported.add(CameraFeature.AUTOFOCUS)
            if self._recording_values() is not None:
                supported.add(CameraFeature.VIDEO_RECORDING)
            if self._focus_drive_config() is not None:
                supported.add(CameraFeature.FOCUS_DRIVE)
            if self._abilities.capture_preview and self._live_view_magnification_config() is not None:
                supported.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)

            planned = {
                feature
                for feature in (
                    CameraFeature.TAP_FOCUS,
                    CameraFeature.CLICK_WHITE_BALANCE,
                    CameraFeature.LIVE_VIEW_RTP,
                    CameraFeature.LIVE_VIEW_MAGNIFICATION,
                )
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
                    CameraFeature.CLICK_WHITE_BALANCE.value: (
                        "The libgphoto2 CLI engine has no verified Live View coordinate Click WB command."
                    ),
                    CameraFeature.LIVE_VIEW_MAGNIFICATION.value: (
                        "Requires an advertised writable Canon EOS eoszoom action and active Live View."
                    ),
                    CameraFeature.LIVE_VIEW.value: (
                        "The CLI adapter uses persistent gphoto2 --capture-movie --stdout MJPEG and "
                        "automatically falls back to bounded --capture-preview transactions when needed."
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
            values = self._setting_values(spec, config)
            selected_value = _case_insensitive_choice(values, value)
            if selected_value is None:
                raise BridgeError(
                    "INVALID_SETTING_VALUE",
                    f"Value '{value}' is not an advertised safe choice for {config.label or config.path}.",
                    status_code=422,
                    engine=self.engine_name,
                )
            self._set_config_value(config, selected_value, refresh=False)
            self._observed.add(_feature_for_setting(key))
            return self.status()

    def capture_still(self) -> CameraStatus:
        with self._lock:
            self._require_open()
            capture_target = self._find_config(("capturetarget",), writable=True)
            if capture_target is not None and _is_host_capture_target(capture_target.current):
                if self._abilities.capture_image:
                    self._capture_to_host_store()
                    self._observed.add(CameraFeature.STILL_CAPTURE)
                    return self.status()
                self._ensure_capture_target_on_card(capture_target)

            self._ensure_capture_target_on_card(capture_target)
            if self._abilities.trigger_capture:
                self._run(["--trigger-capture"], timeout=60.0)
            elif self._abilities.capture_image:
                self._run(["--capture-image"], timeout=60.0)
            else:
                raise unsupported(CameraFeature.STILL_CAPTURE.value, self.engine_name)
            self._observed.add(CameraFeature.STILL_CAPTURE)
            return self.status()

    def _capture_to_host_store(self) -> None:
        staging = self._capture_store.begin_capture()
        timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
        basename = f"OEC_{timestamp}_{uuid.uuid4().hex[:12]}_%04n.%C"
        mapped_directory = self.runner.host_path(staging).replace("%", "%%").rstrip("/\\")
        filename_pattern = f"{mapped_directory}/{basename}"
        try:
            self._run(
                ["--filename", filename_pattern, "--capture-image-and-download"],
                timeout=120.0,
            )
            promoted = self._capture_store.promote_capture(staging)
        except BridgeError as error:
            self._capture_store.discard_capture(staging)
            literal_directory = mapped_directory.replace("%%", "%")
            redacted_message = error.message.replace(literal_directory, "<capture-directory>")
            redacted_message = redacted_message.replace(os.fspath(staging), "<capture-directory>")
            raise BridgeError(
                error.code,
                redacted_message,
                status_code=error.status_code,
                feature=error.feature,
                engine=error.engine,
            ) from error
        except BaseException:
            self._capture_store.discard_capture(staging)
            raise
        self._media_cache.update({item.id: item for item in promoted})

    def _ensure_capture_target_on_card(self, config: GPhotoConfig | None = None) -> None:
        config = config or self._find_config(("capturetarget",), writable=True)
        if config is None:
            return
        card_value = _first_choice(config.choices, "Memory card", "Memory Card", "Card")
        if card_value is None:
            if config.current.casefold() in {"internal ram", "sdram"}:
                raise BridgeError(
                    "UNSAFE_CAPTURE_TARGET",
                    "The camera is targeting host RAM but did not advertise a memory-card capture target.",
                    status_code=409,
                    feature=CameraFeature.STILL_CAPTURE.value,
                    engine=self.engine_name,
                )
            return
        if config.current.casefold() != card_value.casefold():
            self._set_config_value(config, card_value, refresh=False)

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
            self._observed.add(CameraFeature.SHUTTER_HALF_PRESS)
            return self.status()

    def start_bulb_exposure(self) -> CameraStatus:
        with self._lock:
            if self._bulb_exposure_active:
                return self.status()
            values = self._bulb_values()
            if values is None:
                raise unsupported(CameraFeature.BULB_EXPOSURE.value, self.engine_name)
            baseline = self.status()
            config, press_value, release_value = values
            try:
                self._set_config_value(config, press_value, refresh=False)
            except BridgeError as error:
                try:
                    self._set_config_value(config, release_value, refresh=False)
                except BridgeError as release_error:
                    error.add_note(f"Bulb cleanup failed: {release_error.message}")
                raise
            self._bulb_exposure_active = True
            return baseline.model_copy(update={"bulb_exposure_active": True})

    def stop_bulb_exposure(self) -> CameraStatus:
        with self._lock:
            if not self._bulb_exposure_active:
                return self.status()
            values = self._bulb_values()
            if values is None:
                raise unsupported(CameraFeature.BULB_EXPOSURE.value, self.engine_name)
            config, _, release_value = values
            self._set_config_value(config, release_value, refresh=False)
            self._bulb_exposure_active = False
            self._observed.add(CameraFeature.BULB_EXPOSURE)
            return self.status()

    def autofocus(self) -> CameraStatus:
        with self._lock:
            configs = self._autofocus_configs()
            if configs is None:
                status = self.half_press_shutter()
                self._observed.add(CameraFeature.AUTOFOCUS)
                return status
            drive, cancel = configs
            primary_error: BaseException | None = None
            try:
                self._set_config_value(drive, "1", refresh=False)
                self._sleep(0.35)
            except BaseException as error:
                primary_error = error
                raise
            finally:
                try:
                    self._set_config_value(cancel, "1", refresh=False)
                except BaseException as cancel_error:
                    if primary_error is None:
                        raise
                    primary_error.add_note(f"Canon EOS autofocus cancel also failed: {cancel_error}")
            self._observed.add(CameraFeature.AUTOFOCUS)
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
            self._observed.add(CameraFeature.FOCUS_DRIVE)
            return FocusResult(accepted=True, direction=normalized_direction, step=normalized_step)

    def set_live_view_magnification(self, value: int) -> LiveViewMagnificationResult:
        with self._lock:
            if not self._live_view_active:
                raise BridgeError(
                    "LIVE_VIEW_REQUIRED",
                    "Live View magnification requires an active Live View session.",
                    status_code=409,
                    feature=CameraFeature.LIVE_VIEW_MAGNIFICATION.value,
                    engine=self.engine_name,
                )
            config = self._live_view_magnification_config()
            if config is None:
                raise unsupported(CameraFeature.LIVE_VIEW_MAGNIFICATION.value, self.engine_name)
            if value not in {1, 5}:
                raise BridgeError(
                    "INVALID_LIVE_VIEW_MAGNIFICATION",
                    "Canon EOS Live View magnification must be 1x or 5x.",
                    status_code=422,
                    feature=CameraFeature.LIVE_VIEW_MAGNIFICATION.value,
                    engine=self.engine_name,
                )
            self._set_config_value(config, str(value), refresh=False)
            self._live_view_magnification = value
            self._observed.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
            return LiveViewMagnificationResult(accepted=True, value=value)

    def tap_focus(self, x: float, y: float) -> FocusResult:
        del x, y
        raise unsupported(
            CameraFeature.TAP_FOCUS.value,
            self.engine_name,
            "The libgphoto2 CLI engine has no verified normalized image-coordinate AF point command.",
        )

    def click_white_balance(self, x: float, y: float) -> CameraStatus:
        del x, y
        raise unsupported(
            CameraFeature.CLICK_WHITE_BALANCE.value,
            self.engine_name,
            "The libgphoto2 CLI engine has no verified Live View coordinate Click WB command.",
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
            self._requested_fps = min(request.fps, MAX_BRIDGE_LIVE_VIEW_FPS)
            self._live_view_fallback_reason = None
            try:
                try:
                    self._start_movie_stream()
                    assert self._live_view_stream is not None
                    frame = self._live_view_stream.read_frame()
                    self._live_view_transport = "GPHOTO2_CAPTURE_MOVIE"
                except BridgeError as stream_error:
                    self._fallback_to_capture_preview(stream_error)
                    frame = self._capture_preview()
            except BridgeError:
                self._stop_movie_stream()
                if viewfinder_enabled:
                    try:
                        self._set_viewfinder(False)
                    except BridgeError as cleanup_error:
                        self._last_error = cleanup_error.message
                raise
            self._cached_live_view_frame = frame
            self._live_view_active = True
            self._live_view_magnification = None
            self._observed.update({CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_JPEG_POLLING})

    def stop_live_view(self) -> None:
        with self._lock:
            self._require_open()
            was_live_view_active = self._live_view_active
            self._live_view_active = False
            self._stop_movie_stream()
            try:
                if was_live_view_active:
                    self._set_viewfinder(False)
            finally:
                self._cached_live_view_frame = None
                self._live_view_transport = None
                self._live_view_magnification = None

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
            if self._live_view_transport == "GPHOTO2_CAPTURE_MOVIE":
                try:
                    if self._live_view_stream is None:
                        self._start_movie_stream()
                    assert self._live_view_stream is not None
                    return self._live_view_stream.read_frame()
                except BridgeError as stream_error:
                    self._fallback_to_capture_preview(stream_error)
            return self._capture_preview()

    def list_media(self) -> list[MediaItem]:
        with self._lock:
            host_items = self._capture_store.list_items()
            if not self._camera_media_supported and not self._host_capture_supported() and not host_items:
                raise unsupported(CameraFeature.MEDIA_BROWSER.value, self.engine_name)
            camera_items: list[MediaItem] = []
            if self._camera_media_supported:
                output = self._run(["--recurse", "--list-files"], timeout=60.0).text
                camera_items = parse_media_list(output)
            items = sorted(
                [*host_items, *camera_items],
                key=lambda item: item.capture_time or "",
                reverse=True,
            )[:MAX_MEDIA_ITEMS]
            self._media_cache = {item.id: item for item in items}
            self._observed.add(CameraFeature.MEDIA_BROWSER)
            return items

    def download_media(self, media_id: str) -> tuple[MediaItem, Iterator[bytes]]:
        if is_host_media_id(media_id):
            with self._lock:
                self._require_open()
                item, chunks = self._capture_store.stream(media_id)

            def local_stream() -> Iterator[bytes]:
                yield from chunks
                with self._lock:
                    self._observed.add(CameraFeature.MEDIA_DOWNLOAD)

            return item, local_stream()

        folder, name = _decode_media_id(media_id)
        with self._lock:
            self._require_open()
            if not self._camera_media_supported:
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
                self._observed.add(CameraFeature.MEDIA_DOWNLOAD)

        return item, stream()

    def media_thumbnail(self, media_id: str) -> tuple[bytes, str]:
        if is_host_media_id(media_id):
            with self._lock:
                self._require_open()
                thumbnail = self._capture_store.thumbnail(media_id)
                self._observed.add(CameraFeature.MEDIA_THUMBNAIL)
                return thumbnail

        folder, name = _decode_media_id(media_id)
        with self._lock:
            self._require_open()
            if not self._camera_media_supported or not self._abilities.file_preview:
                raise unsupported(CameraFeature.MEDIA_THUMBNAIL.value, self.engine_name)
            output = self._run(
                ["--folder", folder, "--get-thumbnail", name, "--stdout"],
                timeout=60.0,
            ).stdout
            thumbnail, content_type = _validated_thumbnail(output)
            self._observed.add(CameraFeature.MEDIA_THUMBNAIL)
            return thumbnail, content_type

    def media_preview(self, media_id: str) -> tuple[bytes, str]:
        del media_id
        raise unsupported(CameraFeature.MEDIA_PREVIEW.value, self.engine_name)

    def delete_media(self, media_id: str) -> None:
        if is_host_media_id(media_id):
            with self._lock:
                self._require_open()
                self._capture_store.delete(media_id)
                self._media_cache.pop(media_id, None)
                self._observed.add(CameraFeature.MEDIA_DELETE)
                return

        folder, name = _decode_media_id(media_id)
        with self._lock:
            self._require_open()
            if not self._camera_media_supported or not self._abilities.delete_files:
                raise unsupported(CameraFeature.MEDIA_DELETE.value, self.engine_name)
            self._run(["--folder", folder, "--delete-file", name], timeout=60.0)
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
        return "DESKTOP_BRIDGE_STREAM" if self._live_view_active else None

    def _set_recording(self, recording: bool) -> CameraStatus:
        with self._lock:
            values = self._recording_values()
            if values is None:
                raise unsupported(CameraFeature.VIDEO_RECORDING.value, self.engine_name)
            config, start_value, stop_value = values
            self._set_config_value(config, start_value if recording else stop_value, refresh=False)
            self._observed.add(CameraFeature.VIDEO_RECORDING)
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

    def _start_movie_stream(self) -> None:
        if self._live_view_stream is not None:
            return
        source = self.runner.open_stream(
            self._camera_arguments(["--capture-movie", "--stdout"]),
            timeout=LIVE_VIEW_STREAM_TIMEOUT_SECONDS,
        )
        stream = GPhotoMjpegSession(source, target_fps=self._requested_fps)
        try:
            stream.start()
        except BaseException:
            stream.close()
            raise
        self._live_view_stream = stream

    def _stop_movie_stream(self) -> None:
        stream = self._live_view_stream
        self._live_view_stream = None
        if stream is not None:
            stream.close()

    def _fallback_to_capture_preview(self, error: BridgeError) -> None:
        self._stop_movie_stream()
        self._live_view_transport = "GPHOTO2_CAPTURE_PREVIEW"
        self._live_view_fallback_reason = error.message
        self._last_error = error.message
        self._requested_fps = min(self._requested_fps, MAX_PREVIEW_FALLBACK_FPS)

    def _camera_settings(self) -> list[CameraSetting]:
        settings: list[CameraSetting] = []
        for spec in CONFIG_SPECS:
            config = self._find_config(spec.suffixes, writable=True)
            if config is None:
                continue
            values = self._setting_values(spec, config)
            if not values or (not spec.core and len(values) < 2):
                continue
            current_value = _case_insensitive_choice(values, config.current) or "-"
            settings.append(
                CameraSetting(
                    key=spec.key,
                    label=config.label or spec.label,
                    value=current_value,
                    values=values,
                )
            )
        return settings

    def _setting_values(self, spec: ConfigSpec, config: GPhotoConfig) -> list[str]:
        values = config.selectable_values()
        if spec.key == "autopoweroff":
            return [value for value in values if value.casefold() not in {"4294967295", "0xffffffff"}]
        if spec.key == "capturetarget":
            return [
                value
                for value in values
                if _is_card_capture_target(value) or (_is_host_capture_target(value) and self._abilities.capture_image)
            ]
        return values

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

    def _bulb_values(self) -> tuple[GPhotoConfig, str, str] | None:
        config = self._find_config(("eosremoterelease",), writable=True)
        if config is None:
            return None
        press = _first_choice(config.choices, "Press Full AF", "Press Full", "Press Full MF")
        release = _first_choice(config.choices, "Release Full", "Release")
        return (config, press, release) if press and release else None

    def _autofocus_configs(self) -> tuple[GPhotoConfig, GPhotoConfig] | None:
        drive = self._find_config(("autofocusdrive",), writable=True)
        cancel = self._find_config(("autofocuscancel",), writable=True)
        return (drive, cancel) if drive is not None and cancel is not None else None

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

    def _live_view_magnification_config(self) -> GPhotoConfig | None:
        return self._find_config(("eoszoom",), writable=True)

    def _set_viewfinder(self, enabled: bool) -> bool:
        config = self._find_config(("viewfinder",), writable=True)
        if config is None:
            return False
        self._set_config_value(config, "1" if enabled else "0", refresh=False)
        return True

    def _host_capture_supported(self) -> bool:
        config = self._find_config(("capturetarget",), writable=True)
        return bool(
            self._abilities.capture_image
            and config is not None
            and any(_is_host_capture_target(choice) for choice in config.choices)
        )

    def _still_capture_supported(self) -> bool:
        if not (self._abilities.capture_image or self._abilities.trigger_capture):
            return False
        config = self._find_config(("capturetarget",), writable=True)
        if config is None or not _is_host_capture_target(config.current):
            return True
        if self._abilities.capture_image:
            return True
        return _first_choice(config.choices, "Memory card", "Memory Card", "Card") is not None

    def _capability_evidence(self) -> CapabilityEvidence:
        commands: list[str] = []
        if self._abilities.capture_image:
            commands.append("CAPTURE_IMAGE")
        if self._abilities.trigger_capture:
            commands.append("TRIGGER_CAPTURE")
        if self._abilities.capture_preview:
            commands.append("CAPTURE_PREVIEW")
            commands.append("CAPTURE_MOVIE_STDOUT")
        if self._host_capture_supported():
            commands.extend(
                (
                    "CAPTURE_IMAGE_AND_DOWNLOAD",
                    "HOST_MEDIA_LIST",
                    "HOST_MEDIA_DOWNLOAD",
                    "HOST_MEDIA_THUMBNAIL",
                    "HOST_MEDIA_DELETE",
                )
            )
        if self._camera_media_supported:
            commands.extend(("MEDIA_LIST", "MEDIA_DOWNLOAD"))
            if self._abilities.file_preview:
                commands.append("MEDIA_THUMBNAIL")
            if self._abilities.delete_files:
                commands.append("MEDIA_DELETE")
        if self._autofocus_configs() is not None:
            commands.append("AUTOFOCUS_DRIVE_CANCEL")
        if self._half_press_values() is not None:
            commands.append("SHUTTER_HALF_PRESS")
        if self._bulb_values() is not None:
            commands.append("BULB_PRESS_RELEASE")
        if self._abilities.capture_preview and self._live_view_magnification_config() is not None:
            commands.append("LIVE_VIEW_MAGNIFICATION_1X_5X")
        writable_settings = sorted(
            {
                config.path.replace("\r", "").replace("\n", "")[:MAX_CAPABILITY_EVIDENCE_ITEM_CHARS]
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
            observed_features=sorted(self._observed, key=str)[:MAX_CAPABILITY_EVIDENCE_ITEMS],
            truncated=(
                len(writable_settings) > MAX_CAPABILITY_EVIDENCE_ITEMS
                or len(self._observed) > MAX_CAPABILITY_EVIDENCE_ITEMS
            ),
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
        self._stop_movie_stream()
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


def _parse_available_shots(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        parsed = int(value.strip(), 10)
    except ValueError:
        return None
    return parsed if 0 <= parsed < 0xFFFF_FFFF else None


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


def _is_host_capture_target(value: str) -> bool:
    return value.strip().casefold() in {"internal ram", "sdram"}


def _is_card_capture_target(value: str) -> bool:
    return value.strip().casefold() in {"memory card", "card"}


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
