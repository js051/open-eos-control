from __future__ import annotations

import threading
import time
from collections.abc import Iterator
from io import BytesIO
from pathlib import Path

from PIL import Image

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import CommandOutput

AUTO_DETECT = """Model                          Port
----------------------------------------------------------
Canon EOS R6 Mark III         usb:001,007
"""

SUMMARY = """Camera summary:
Manufacturer: Canon.Inc
Model: Canon EOS R6 Mark III
  Version: 3-1.0.0
  Serial Number: masked-summary-serial

Device Capabilities:
    File Download, File Deletion, File Upload
    No Image Capture, No Open Capture, Canon EOS Capture, Canon EOS Capture 2
"""

ABILITIES = """Abilities for camera             : Canon EOS R6 Mark III
Serial port support              : no
USB support                      : yes
Capture choices                  :
                                 : Image
                                 : Preview
                                 : Trigger Capture
Configuration support            : yes
Delete selected files on camera  : yes
File preview (thumbnail) support : yes
"""

STORAGE = """[Storage 0]
description=CFe
basedir=/store_00010001
access=0 Read-Write
totalcapacity=500088832 KB
free=429877632 KB
[Storage 1]
description=SD
basedir=/store_00020001
access=0 Read-Write
totalcapacity=124835840 KB
free=57751552 KB
freeimages=3210
"""

MEDIA = """There are no files in folder '/'.
There are 2 files in folder '/store_00010001/DCIM/100CANON'.
#1 IMG_0001.CR3 rd 10 KB image/x-canon-cr3 1784600000
#2 IMG_0001.JPG rd 6 B image/jpeg 1784600001
"""

MEDIA_INFO = """Information on file 'IMG_0001.JPG' (folder '/store_00010001/DCIM/100CANON'):
File:
  Mime type:   'image/jpeg'
  Size:        6 byte(s)
  Width:       16 pixel(s)
  Height:      12 pixel(s)
  Downloaded:  no
  Permissions: read/delete
  Time:        Tue Jul 21 10:13:21 2026
Thumbnail:
  Mime type:   'image/png'
  Size:        633 byte(s)
  Width:       8 pixel(s)
  Height:      6 pixel(s)
Audio data:
  None available.
"""

def _jpeg_fixture(width: int, height: int, color: tuple[int, int, int]) -> bytes:
    output = BytesIO()
    Image.new("RGB", (width, height), color=color).save(output, format="JPEG")
    return output.getvalue()


JPEG = _jpeg_fixture(16, 12, (20, 120, 180))
THUMBNAIL = _jpeg_fixture(8, 6, (180, 80, 20))
MEDIA_BYTES = b"jpeg!!"


class FakeMovieStream:
    def __init__(self, chunks: list[bytes] | None = None) -> None:
        self._chunks = iter(chunks or [JPEG])
        self._closed = threading.Event()
        self.waiting = threading.Event()

    @property
    def closed(self) -> bool:
        return self._closed.is_set()

    def __iter__(self) -> FakeMovieStream:
        return self

    def __next__(self) -> bytes:
        try:
            return next(self._chunks)
        except StopIteration:
            self.waiting.set()
            self._closed.wait()
            raise

    def close(self) -> None:
        self._closed.set()


class FakeRunner:
    def __init__(self) -> None:
        self.commands: list[tuple[str, ...]] = []
        self.cancellable_commands: list[tuple[str, ...]] = []
        self.movie_streams: list[FakeMovieStream] = []
        self.uploaded_files: dict[tuple[str, str], bytes] = {}
        self.values = {
            "/main/status/eosserialnumber": "TEST-SERIAL-0001",
            "/main/status/cameramodel": "Canon EOS R6 Mark III",
            "/main/status/manufacturer": "Canon.Inc",
            "/main/status/deviceversion": "3-1.0.0",
            "/main/status/batterylevel": "82%",
            "/main/status/availableshots": "46822",
            "/main/imgsettings/iso": "400",
            "/main/imgsettings/whitebalance": "Auto",
            "/main/imgsettings/imageformat": "RAW",
            "/main/imgsettings/imageformatsd": "RAW",
            "/main/imgsettings/imageformatcf": "RAW",
            "/main/imgsettings/whitebalanceadjusta": "0",
            "/main/imgsettings/whitebalanceadjustb": "0",
            "/main/capturesettings/aperture": "2.8",
            "/main/capturesettings/shutterspeed": "1/50",
            "/main/capturesettings/autoexposuremode": "Manual",
            "/main/capturesettings/drivemode": "Single",
            "/main/capturesettings/aspectratio": "3:2",
            "/main/capturesettings/zoomspeed": "8",
            "/main/capturesettings/alomode": "Standard",
            "/main/capturesettings/storageid": "00010001",
            "/main/actions/manualfocusdrive": "None",
            "/main/actions/autofocusdrive": "0",
            "/main/actions/autofocuscancel": "0",
            "/main/actions/viewfinder": "0",
            "/main/actions/eoszoom": "0",
            "/main/actions/eosremoterelease": "None",
            "/main/actions/syncdatetimeutc": "0",
            "/main/settings/datetimeutc": "1700000000",
            "/main/settings/movierecordtarget": "SDRAM",
            "/main/settings/autopoweroff": "30",
            "/main/settings/capturetarget": "Internal RAM",
            "/main/settings/ownername": "Open EOS",
            "/main/settings/artist": "Jason",
            "/main/settings/copyright": "2026 Open EOS",
            "/main/settings/nickname": "R6M3",
        }

    def health(self) -> tuple[bool, str | None, str | None]:
        return True, "gphoto2 2.5.33", None

    @staticmethod
    def host_path(path: Path) -> str:
        return str(path.resolve(strict=False))

    def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
        del timeout
        self.commands.append(tuple(arguments))
        command = self._without_camera(arguments)
        if command == ["--auto-detect"]:
            return CommandOutput(AUTO_DETECT.encode())
        if command == ["--summary"]:
            return CommandOutput(SUMMARY.encode())
        if command == ["--abilities"]:
            return CommandOutput(ABILITIES.encode())
        if command == ["--list-all-config"]:
            return CommandOutput(self._config_dump().encode())
        if command == ["--storage-info"]:
            return CommandOutput(STORAGE.encode())
        if command == ["--folder", "/", "--no-recurse", "--list-files"]:
            return CommandOutput(b"There are no files in folder '/'.\n")
        if command in (["--wait-event", "1ms"], ["--wait-event", "250ms"]):
            return CommandOutput(b"")
        if command == ["--recurse", "--list-files"]:
            extra = "".join(
                f"There is 1 file in folder '{folder}'.\n"
                f"#99 {name} rd {len(payload)} B {self._content_type(name)} 1784600002\n"
                for (folder, name), payload in self.uploaded_files.items()
            )
            return CommandOutput((MEDIA + extra).encode())
        if command == [
            "--folder",
            "/store_00010001/DCIM/100CANON",
            "--show-info",
            "IMG_0001.JPG",
        ]:
            return CommandOutput(MEDIA_INFO.encode())
        if command == ["--capture-preview", "--stdout"]:
            return CommandOutput(JPEG)
        if (
            len(command) == 5
            and command[:3]
            == [
                "--folder",
                "/store_00010001/DCIM/100CANON",
                "--get-thumbnail",
            ]
            and command[3] in {"IMG_0001.JPG", "IMG_0001.CR3"}
            and command[4] == "--stdout"
        ):
            return CommandOutput(THUMBNAIL)
        if command == [
            "--folder",
            "/store_00010001/DCIM/100CANON",
            "--get-file",
            "IMG_0001.JPG",
            "--stdout",
        ]:
            return CommandOutput(JPEG)
        if command in (["--trigger-capture"], ["--capture-image"]):
            return CommandOutput(b"New file is in location /store_00010001/DCIM/100CANON/IMG_0002.JPG\n")
        if "--capture-image-and-download" in command:
            filename_index = command.index("--filename") + 1
            target = Path(
                command[filename_index]
                .replace("%%", "%")
                .replace("%04n", "0001")
                .replace("%C", "JPG")
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            Image.new("RGB", (8, 6), color=(20, 120, 180)).save(target, format="JPEG")
            return CommandOutput(f"Saving file as {target}\n".encode())
        if command == [
            "--folder",
            "/store_00010001/DCIM/100CANON",
            "--delete-file",
            "IMG_0001.JPG",
        ]:
            return CommandOutput(b"")
        if (
            len(command) == 6
            and command[0] == "--folder"
            and command[2] == "--filename"
            and command[4] == "--upload-file"
        ):
            folder = command[1]
            name = command[3]
            payload = Path(command[5]).read_bytes()
            self.uploaded_files[(folder, name)] = payload
            return CommandOutput(b"Uploaded file successfully.\n")
        if command and command[0] == "--set-config-value":
            path, value = command[1].split("=", 1)
            if path not in self.values:
                raise AssertionError(f"Unexpected config path: {path}")
            self.values[path] = value
            clock_readback = {
                "/main/actions/syncdatetimeutc": "/main/settings/datetimeutc",
                "/main/actions/syncdatetime": "/main/settings/datetime",
            }.get(path)
            if value == "1" and clock_readback in self.values:
                self.values[clock_readback] = str(int(time.time()))
            return CommandOutput(b"")
        raise AssertionError(f"Unexpected gphoto2 command: {command}")

    def run_cancellable(
        self,
        arguments: list[str],
        *,
        timeout: float,
        cancelled: threading.Event,
    ) -> CommandOutput:
        if cancelled.is_set():
            raise BridgeError("UPLOAD_CANCELLED", "Upload cancelled.", status_code=409)
        self.cancellable_commands.append(tuple(arguments))
        return self.run(arguments, timeout=timeout)

    @staticmethod
    def _content_type(name: str) -> str:
        return "video/mp4" if name.casefold().endswith((".mp4", ".mov")) else "image/jpeg"

    def stream(self, arguments: list[str], *, timeout: float = 300.0) -> Iterator[bytes]:
        del timeout
        self.commands.append(tuple(arguments))
        command = self._without_camera(arguments)
        assert command == [
            "--folder",
            "/store_00010001/DCIM/100CANON",
            "--get-file",
            "IMG_0001.JPG",
            "--stdout",
        ]
        yield MEDIA_BYTES[:3]
        yield MEDIA_BYTES[3:]

    def open_stream(self, arguments: list[str], *, timeout: float = 300.0) -> FakeMovieStream:
        del timeout
        self.commands.append(tuple(arguments))
        command = self._without_camera(arguments)
        assert command == ["--capture-movie", "--stdout"]
        stream = FakeMovieStream()
        self.movie_streams.append(stream)
        return stream

    @staticmethod
    def _without_camera(arguments: list[str]) -> list[str]:
        if arguments[:2] == ["--port", "usb:001,007"]:
            return arguments[2:]
        return arguments

    def _config_dump(self) -> str:
        return "\n".join(
            [
                self._text("/main/status/eosserialnumber", "Serial Number", readonly=True),
                self._text("/main/status/cameramodel", "Camera Model", readonly=True),
                self._text("/main/status/manufacturer", "Camera Manufacturer", readonly=True),
                self._text("/main/status/deviceversion", "Device Version", readonly=True),
                self._text("/main/status/batterylevel", "Battery Level", readonly=True),
                self._text("/main/status/availableshots", "Available Shots", readonly=True),
                self._radio("/main/imgsettings/iso", "ISO Speed", ["Auto", "100", "400", "800"]),
                self._radio("/main/imgsettings/whitebalance", "WhiteBalance", ["Auto", "Daylight", "Cloudy"]),
                self._radio("/main/imgsettings/imageformat", "Image Format", ["JPEG", "RAW", "cRAW"]),
                self._radio(
                    "/main/imgsettings/imageformatsd",
                    "Image Format SD",
                    ["Large Fine JPEG", "RAW", "cRAW"],
                ),
                self._radio(
                    "/main/imgsettings/imageformatcf",
                    "Image Format CF",
                    ["Large Fine JPEG", "RAW", "cRAW"],
                ),
                self._radio(
                    "/main/imgsettings/whitebalanceadjusta",
                    "WhiteBalance Adjust A",
                    ["-9", "0", "9"],
                ),
                self._radio(
                    "/main/imgsettings/whitebalanceadjustb",
                    "WhiteBalance Adjust B",
                    ["-9", "0", "9"],
                ),
                self._radio("/main/capturesettings/aperture", "Aperture", ["2.8", "4", "5.6"]),
                self._radio("/main/capturesettings/shutterspeed", "Shutter Speed", ["1/25", "1/50", "1/100"]),
                self._radio(
                    "/main/capturesettings/autoexposuremode",
                    "Canon Auto Exposure Mode",
                    ["P", "AV", "TV", "Manual", "Bulb"],
                ),
                self._radio("/main/capturesettings/drivemode", "Drive Mode", ["Single", "Continuous high speed"]),
                self._radio("/main/capturesettings/aspectratio", "Aspect Ratio", ["3:2", "16:9", "1.6x"]),
                self._radio("/main/capturesettings/zoomspeed", "Zoom Speed", ["1", "8", "15"]),
                self._radio(
                    "/main/capturesettings/alomode",
                    "Auto Lighting Optimizer",
                    ["Standard", "Low", "High", "Off"],
                ),
                self._text("/main/capturesettings/storageid", "Storage Device", readonly=False),
                self._radio(
                    "/main/actions/manualfocusdrive",
                    "Drive Canon DSLR Manual focus",
                    ["Near 1", "Near 2", "Near 3", "None", "Far 1", "Far 2", "Far 3"],
                ),
                self._toggle("/main/actions/autofocusdrive", "Drive Canon DSLR Autofocus"),
                self._toggle("/main/actions/autofocuscancel", "Cancel Canon DSLR Autofocus"),
                self._toggle("/main/actions/viewfinder", "Canon EOS Viewfinder"),
                self._text("/main/actions/eoszoom", "Canon EOS Zoom", readonly=False),
                self._radio(
                    "/main/actions/eosremoterelease",
                    "Canon EOS Remote Release",
                    ["None", "Press Half", "Press Full", "Release Half", "Release Full"],
                ),
                self._toggle("/main/actions/syncdatetimeutc", "Synchronize UTC date and time"),
                self._date("/main/settings/datetimeutc", "Camera Date and Time"),
                self._radio("/main/settings/movierecordtarget", "Recording Destination", ["Card", "None", "SDRAM"]),
                self._radio(
                    "/main/settings/autopoweroff",
                    "Auto Power Off",
                    ["15", "30", "60", "180", "300", "600", "1800", "0", "4294967295"],
                ),
                self._radio(
                    "/main/settings/capturetarget",
                    "Capture Target",
                    ["Internal RAM", "Memory card"],
                ),
                self._text("/main/settings/ownername", "Owner Name", readonly=False),
                self._text("/main/settings/artist", "Artist", readonly=False),
                self._text("/main/settings/copyright", "Copyright", readonly=False),
                self._text("/main/settings/nickname", "Nickname", readonly=False),
            ]
        )

    def _text(self, path: str, label: str, *, readonly: bool) -> str:
        return (
            f"{path}\nLabel: {label}\nReadonly: {1 if readonly else 0}\nType: TEXT\nCurrent: {self.values[path]}\nEND"
        )

    def _radio(self, path: str, label: str, choices: list[str]) -> str:
        choice_lines = "\n".join(f"Choice: {index} {value}" for index, value in enumerate(choices))
        return f"{path}\nLabel: {label}\nReadonly: 0\nType: RADIO\nCurrent: {self.values[path]}\n{choice_lines}\nEND"

    def _toggle(self, path: str, label: str) -> str:
        return f"{path}\nLabel: {label}\nReadonly: 0\nType: TOGGLE\nCurrent: {self.values[path]}\nEND"

    def _date(self, path: str, label: str) -> str:
        return f"{path}\nLabel: {label}\nReadonly: 0\nType: DATE\nCurrent: {self.values[path]}\nEND"
