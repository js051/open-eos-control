from __future__ import annotations

import sys
import time

import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import (
    CommandOutput,
    GPhoto2Engine,
    SubprocessGPhotoRunner,
    parse_abilities,
    parse_auto_detect,
    parse_config_dump,
    parse_media_list,
    parse_storage_info,
)
from open_eos_bridge.models import CameraFeature, LiveViewStartRequest

from .fakes import ABILITIES, AUTO_DETECT, JPEG, MEDIA, MEDIA_BYTES, STORAGE, THUMBNAIL, FakeRunner


def test_gphoto2_output_parsers_preserve_camera_advertised_values() -> None:
    cameras = parse_auto_detect(AUTO_DETECT)
    abilities = parse_abilities(ABILITIES)
    configs = parse_config_dump(
        "/main/imgsettings/iso\n"
        "Label: ISO Speed\nReadonly: 0\nType: RADIO\nCurrent: 400\n"
        "Choice: 0 Auto\nChoice: 1 100\nChoice: 2 400\nEND\n"
        "/main/capturesettings/exposurecompensation\n"
        "Label: Exposure Compensation\nReadonly: 0\nType: RANGE\nCurrent: 0\n"
        "Bottom: -1\nTop: 1\nStep: 0.5\nEND\n"
    )

    assert cameras[0].model == "Canon EOS R6 Mark III"
    assert cameras[0].port == "usb:001,007"
    assert parse_auto_detect("Canon EOS Camera  usb:\n")[0].port == "usb:"
    assert abilities.capture_image is True
    assert abilities.capture_preview is True
    assert abilities.trigger_capture is True
    assert abilities.delete_files is True
    assert abilities.file_preview is True
    assert configs["/main/imgsettings/iso"].choices == ["Auto", "100", "400"]
    assert configs["/main/capturesettings/exposurecompensation"].selectable_values() == [
        "-1",
        "-0.5",
        "0",
        "0.5",
        "1",
    ]


def test_storage_and_media_parsers_handle_r6_mark_iii_shapes() -> None:
    storage = parse_storage_info(STORAGE)
    media = parse_media_list(MEDIA)

    assert storage.devices == 2
    assert storage.total_bytes == 639_922_864_128
    assert storage.free_bytes == 499_332_284_416
    assert storage.free_images == 3210
    assert [item.name for item in media] == ["IMG_0001.JPG", "IMG_0001.CR3"]
    assert media[0].size_bytes == 6
    assert media[0].content_type == "image/jpeg"
    assert media[0].capture_time == "2026-07-21T02:13:21Z"


def test_session_capabilities_and_controls_are_backed_by_real_commands() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()

    capabilities = session.capabilities()
    assert CameraFeature.STILL_CAPTURE in capabilities.supported
    assert CameraFeature.LIVE_VIEW in capabilities.supported
    assert CameraFeature.SHUTTER_HALF_PRESS in capabilities.supported
    assert CameraFeature.VIDEO_RECORDING in capabilities.supported
    assert CameraFeature.FOCUS_DRIVE in capabilities.supported
    assert CameraFeature.MEDIA_DELETE in capabilities.supported
    assert CameraFeature.MEDIA_THUMBNAIL in capabilities.supported
    assert CameraFeature.TAP_FOCUS in capabilities.planned
    assert next(setting for setting in capabilities.settings if setting.key == "iso").values == [
        "Auto",
        "100",
        "400",
        "800",
    ]
    assert capabilities.evidence.source == "gphoto2 --abilities + --list-all-config"
    assert "CAPTURE_PREVIEW" in capabilities.evidence.advertised_commands
    assert "/main/imgsettings/iso" in capabilities.evidence.writable_settings

    status = session.set_setting("iso", "800")
    assert status.exposure.iso == "800"

    status = session.set_setting("whitebalance", "daylight")
    assert status.exposure.white_balance == "Daylight"

    session.capture_still()
    session.half_press_shutter()
    assert runner.values["/main/actions/eosremoterelease"] == "Release Half"

    assert session.start_recording().recording is True
    assert session.stop_recording().recording is False

    session.start_live_view(LiveViewStartRequest(fps=15))
    assert session.requested_fps == 5
    assert runner.values["/main/actions/viewfinder"] == "1"
    assert session.live_view_frame() == JPEG
    focus = session.drive_focus("far", "large")
    assert focus.accepted is True
    assert runner.values["/main/actions/manualfocusdrive"] == "Far 3"
    session.stop_live_view()
    assert runner.values["/main/actions/viewfinder"] == "0"

    media = session.list_media()
    thumbnail, thumbnail_type = session.media_thumbnail(media[0].id)
    item, chunks = session.download_media(media[0].id)
    assert thumbnail == THUMBNAIL
    assert thumbnail_type == "image/jpeg"
    assert item.name == "IMG_0001.JPG"
    assert b"".join(chunks) == MEDIA_BYTES
    session.delete_media(media[0].id)
    assert any("/main/imgsettings/iso=800" in command for command in runner.commands)
    assert any("--trigger-capture" in command for command in runner.commands)
    assert any("--delete-file" in command for command in runner.commands)


def test_media_thumbnail_requires_gphoto2_advertised_ability() -> None:
    class NoThumbnailRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if arguments[-1:] == ["--abilities"]:
                output = ABILITIES.replace(
                    "File preview (thumbnail) support : yes",
                    "File preview (thumbnail) support : no",
                )
                return CommandOutput(output.encode())
            return super().run(arguments, timeout=timeout)

    runner = NoThumbnailRunner()
    session = GPhoto2Engine(runner).open()
    item = session.list_media()[0]

    with pytest.raises(BridgeError) as failure:
        session.media_thumbnail(item.id)

    assert CameraFeature.MEDIA_THUMBNAIL not in session.capabilities().supported
    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert not any("--get-thumbnail" in command for command in runner.commands)


def test_media_delete_requires_gphoto2_advertised_ability() -> None:
    class NoDeleteRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if arguments[-1:] == ["--abilities"]:
                output = ABILITIES.replace(
                    "Delete selected files on camera  : yes",
                    "Delete selected files on camera  : no",
                )
                return CommandOutput(output.encode())
            return super().run(arguments, timeout=timeout)

    runner = NoDeleteRunner()
    session = GPhoto2Engine(runner).open()
    item = session.list_media()[0]

    with pytest.raises(BridgeError) as failure:
        session.delete_media(item.id)

    assert CameraFeature.MEDIA_DELETE not in session.capabilities().supported
    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert not any("--delete-file" in command for command in runner.commands)


def test_subprocess_stream_preserves_binary_output_and_enforces_timeout() -> None:
    runner = SubprocessGPhotoRunner(sys.executable)
    output = b"".join(
        runner.stream(
            ["-c", "import sys; sys.stdout.buffer.write(b'camera-bytes')"],
            timeout=5.0,
        )
    )
    assert output == b"camera-bytes"

    started = time.monotonic()
    with pytest.raises(BridgeError, match="media transfer exceeded") as failure:
        b"".join(runner.stream(["-c", "import time; time.sleep(10)"], timeout=0.1))

    assert failure.value.code == "ENGINE_TIMEOUT"
    assert time.monotonic() - started < 3.0


def test_failed_live_view_start_restores_camera_viewfinder() -> None:
    class FailingPreviewRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if arguments[-2:] == ["--capture-preview", "--stdout"]:
                raise BridgeError("ENGINE_COMMAND_FAILED", "preview failed", status_code=502)
            return super().run(arguments, timeout=timeout)

    runner = FailingPreviewRunner()
    session = GPhoto2Engine(runner).open()

    with pytest.raises(BridgeError, match="preview failed"):
        session.start_live_view(LiveViewStartRequest())

    assert runner.values["/main/actions/viewfinder"] == "0"
    assert session.live_view_active is False
