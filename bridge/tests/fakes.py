from __future__ import annotations

import threading
from collections.abc import Iterator

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

STORAGE = """store_00010001:
    StorageDescription: CFe
    Maximum Capability: 512090963968 (488368 MB)
    Free Space (Bytes): 440194695168 (419802 MB)
    Free Space (Images): -1
store_00020001:
    StorageDescription: SD
    Maximum Capability: 127831900160 (121910 MB)
    Free Space (Bytes): 59137589248 (56398 MB)
    Free Space (Images): 3210
"""

MEDIA = """There are no files in folder '/'.
There are 2 files in folder '/store_00010001/DCIM/100CANON'.
#1 IMG_0001.CR3 rd 10 KB image/x-canon-cr3 1784600000
#2 IMG_0001.JPG rd 6 B image/jpeg 1784600001
"""

JPEG = b"\xff\xd8open-eos-control\xff\xd9"
THUMBNAIL = b"\xff\xd8open-eos-thumbnail\xff\xd9"
MEDIA_BYTES = b"jpeg!!"


class FakeMovieStream:
    def __init__(self, chunks: list[bytes] | None = None) -> None:
        self._chunks = iter(chunks or [JPEG])
        self._closed = threading.Event()

    @property
    def closed(self) -> bool:
        return self._closed.is_set()

    def __iter__(self) -> FakeMovieStream:
        return self

    def __next__(self) -> bytes:
        try:
            return next(self._chunks)
        except StopIteration:
            self._closed.wait()
            raise

    def close(self) -> None:
        self._closed.set()


class FakeRunner:
    def __init__(self) -> None:
        self.commands: list[tuple[str, ...]] = []
        self.movie_streams: list[FakeMovieStream] = []
        self.values = {
            "/main/status/eosserialnumber": "TEST-SERIAL-0001",
            "/main/status/cameramodel": "Canon EOS R6 Mark III",
            "/main/status/manufacturer": "Canon.Inc",
            "/main/status/deviceversion": "3-1.0.0",
            "/main/status/batterylevel": "82%",
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
            "/main/actions/manualfocusdrive": "None",
            "/main/actions/viewfinder": "0",
            "/main/actions/eosremoterelease": "None",
            "/main/settings/movierecordtarget": "SDRAM",
            "/main/settings/autopoweroff": "30",
        }

    def health(self) -> tuple[bool, str | None, str | None]:
        return True, "gphoto2 2.5.33", None

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
        if command == ["--recurse", "--list-files"]:
            return CommandOutput(MEDIA.encode())
        if command == ["--capture-preview", "--stdout"]:
            return CommandOutput(JPEG)
        if command == [
            "--folder",
            "/store_00010001/DCIM/100CANON",
            "--get-thumbnail",
            "IMG_0001.JPG",
            "--stdout",
        ]:
            return CommandOutput(THUMBNAIL)
        if command in (["--trigger-capture"], ["--capture-image"]):
            return CommandOutput(b"New file is in location /store_00010001/DCIM/100CANON/IMG_0002.JPG\n")
        if command == [
            "--folder",
            "/store_00010001/DCIM/100CANON",
            "--delete-file",
            "IMG_0001.JPG",
        ]:
            return CommandOutput(b"")
        if command and command[0] == "--set-config-value":
            path, value = command[1].split("=", 1)
            if path not in self.values:
                raise AssertionError(f"Unexpected config path: {path}")
            self.values[path] = value
            return CommandOutput(b"")
        raise AssertionError(f"Unexpected gphoto2 command: {command}")

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
                    "/main/capturesettings/autoexposuremode", "Canon Auto Exposure Mode", ["P", "AV", "TV", "Manual"]
                ),
                self._radio("/main/capturesettings/drivemode", "Drive Mode", ["Single", "Continuous high speed"]),
                self._radio("/main/capturesettings/aspectratio", "Aspect Ratio", ["3:2", "16:9", "1.6x"]),
                self._radio("/main/capturesettings/zoomspeed", "Zoom Speed", ["1", "8", "15"]),
                self._radio(
                    "/main/actions/manualfocusdrive",
                    "Drive Canon DSLR Manual focus",
                    ["Near 1", "Near 2", "Near 3", "None", "Far 1", "Far 2", "Far 3"],
                ),
                self._toggle("/main/actions/viewfinder", "Canon EOS Viewfinder"),
                self._radio(
                    "/main/actions/eosremoterelease",
                    "Canon EOS Remote Release",
                    ["None", "Press Half", "Press Full", "Release Half", "Release Full"],
                ),
                self._radio("/main/settings/movierecordtarget", "Recording Destination", ["Card", "None", "SDRAM"]),
                self._radio(
                    "/main/settings/autopoweroff",
                    "Auto Power Off",
                    ["15", "30", "60", "180", "300", "600", "1800", "0", "4294967295"],
                ),
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
