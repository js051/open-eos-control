from __future__ import annotations

import sys
import time

import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import (
    CommandOutput,
    GPhoto2Engine,
    GPhotoCommand,
    MjpegFrameParser,
    SubprocessGPhotoRunner,
    WslHostState,
    parse_abilities,
    parse_auto_detect,
    parse_config_dump,
    parse_media_list,
    parse_storage_info,
    resolve_gphoto_command,
)
from open_eos_bridge.models import CameraFeature, LiveViewStartRequest

from .fakes import ABILITIES, AUTO_DETECT, JPEG, MEDIA, MEDIA_BYTES, STORAGE, THUMBNAIL, FakeRunner


def test_gphoto_command_resolution_prefers_native_and_supports_wsl_distribution() -> None:
    paths = {
        "gphoto2": "/usr/bin/gphoto2",
        "wsl.exe": "C:\\Windows\\System32\\wsl.exe",
    }

    native = resolve_gphoto_command(
        environment={},
        platform_name="nt",
        which=paths.get,
    )
    wsl = resolve_gphoto_command(
        environment={"OPEN_EOS_GPHOTO2_WSL_DISTRO": "Ubuntu-24.04"},
        platform_name="nt",
        which=lambda name: paths.get(name) if name == "wsl.exe" else None,
    )
    explicit = resolve_gphoto_command(
        "D:\\Tools\\gphoto2.exe",
        environment={},
        platform_name="nt",
        which=lambda _: None,
    )

    assert native == GPhotoCommand(("/usr/bin/gphoto2",), "native")
    assert wsl == GPhotoCommand(
        (
            "C:\\Windows\\System32\\wsl.exe",
            "--distribution",
            "Ubuntu-24.04",
            "--exec",
            "gphoto2",
        ),
        "wsl",
        "Ubuntu-24.04",
    )
    assert explicit == GPhotoCommand(("D:\\Tools\\gphoto2.exe",), "native")


def test_wsl_runner_health_is_actionable_and_decodes_windows_utf16() -> None:
    version_command = GPhotoCommand(
        (sys.executable, "-c", "print('gphoto2 2.5.33')"),
        "wsl",
        "Ubuntu",
    )
    runner = SubprocessGPhotoRunner(
        command=version_command,
        wsl_probe=lambda _: WslHostState(distributions=("Ubuntu",), usbipd_available=False),
    )

    available, version, detail = runner.health()

    assert available is True
    assert version == "gphoto2 2.5.33"
    assert detail == (
        "Using gphoto2 in WSL distribution 'Ubuntu'. "
        "Install usbipd-win before attaching a Windows USB camera to WSL."
    )
    assert CommandOutput("Ubuntu\r\n".encode("utf-16-le")).text == "Ubuntu\r\n"


def test_wsl_runner_rejects_missing_distribution_before_launching_gphoto2() -> None:
    runner = SubprocessGPhotoRunner(
        command=GPhotoCommand((sys.executable,), "wsl"),
        wsl_probe=lambda _: WslHostState(error="Install a WSL distribution."),
    )

    available, version, detail = runner.health()

    assert available is False
    assert version is None
    assert detail == "Install a WSL distribution."


def test_wsl_runner_explains_how_to_install_missing_gphoto2_package() -> None:
    runner = SubprocessGPhotoRunner(
        command=GPhotoCommand(
            (
                sys.executable,
                "-c",
                "import sys; sys.stderr.write('gphoto2: command not found'); raise SystemExit(1)",
            ),
            "wsl",
            "Ubuntu",
        ),
        wsl_probe=lambda _: WslHostState(distributions=("Ubuntu",), usbipd_available=True),
    )

    available, version, detail = runner.health()

    assert available is False
    assert version is None
    assert detail is not None
    assert "sudo apt update && sudo apt install gphoto2 usbutils" in detail
    assert "gphoto2: command not found" in detail


def test_runner_executes_command_prefix_as_an_argument_array() -> None:
    runner = SubprocessGPhotoRunner(
        command=GPhotoCommand(
            (sys.executable, "-c", "import sys; print(sys.argv[1])"),
            "native",
        )
    )

    output = runner.run(["camera value"])

    assert output.text.strip() == "camera value"


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
    assert CameraFeature.AUTOFOCUS in capabilities.supported
    assert CameraFeature.VIDEO_RECORDING in capabilities.supported
    assert CameraFeature.FOCUS_DRIVE in capabilities.supported
    assert CameraFeature.MEDIA_DELETE in capabilities.supported
    assert CameraFeature.MEDIA_THUMBNAIL in capabilities.supported
    assert CameraFeature.TAP_FOCUS in capabilities.planned
    assert CameraFeature.CLICK_WHITE_BALANCE in capabilities.planned
    assert next(setting for setting in capabilities.settings if setting.key == "iso").values == [
        "Auto",
        "100",
        "400",
        "800",
    ]
    assert capabilities.evidence.source == "gphoto2 --abilities + --list-all-config"
    assert "CAPTURE_PREVIEW" in capabilities.evidence.advertised_commands
    assert "CAPTURE_MOVIE_STDOUT" in capabilities.evidence.advertised_commands
    assert "AUTOFOCUS_DRIVE_CANCEL" in capabilities.evidence.advertised_commands
    assert "SHUTTER_HALF_PRESS" in capabilities.evidence.advertised_commands
    assert capabilities.live_view.max_fps == 30
    assert "/main/imgsettings/iso" in capabilities.evidence.writable_settings

    status = session.set_setting("iso", "800")
    assert status.exposure.iso == "800"

    status = session.set_setting("whitebalance", "daylight")
    assert status.exposure.white_balance == "Daylight"

    session.capture_still()
    assert runner.values["/main/settings/capturetarget"] == "Memory card"
    capture_target_write = next(
        index
        for index, command in enumerate(runner.commands)
        if "/main/settings/capturetarget=Memory card" in command
    )
    shutter_command = next(index for index, command in enumerate(runner.commands) if "--trigger-capture" in command)
    assert capture_target_write < shutter_command
    session.half_press_shutter()
    session.autofocus()
    assert runner.values["/main/actions/eosremoterelease"] == "Release Half"
    assert runner.values["/main/actions/autofocusdrive"] == "1"
    assert runner.values["/main/actions/autofocuscancel"] == "1"
    autofocus_drive = next(
        index
        for index, command in enumerate(runner.commands)
        if "/main/actions/autofocusdrive=1" in command
    )
    autofocus_cancel = next(
        index
        for index, command in enumerate(runner.commands)
        if "/main/actions/autofocuscancel=1" in command
    )
    assert autofocus_drive < autofocus_cancel

    assert session.start_recording().recording is True
    assert session.stop_recording().recording is False

    session.start_live_view(LiveViewStartRequest(fps=15))
    assert session.requested_fps == 15
    assert runner.values["/main/actions/viewfinder"] == "1"
    assert session.live_view_frame() == JPEG
    assert any("--capture-movie" in command for command in runner.commands)
    assert not any("--capture-preview" in command for command in runner.commands)
    focus = session.drive_focus("far", "large")
    assert focus.accepted is True
    assert runner.values["/main/actions/manualfocusdrive"] == "Far 3"
    assert runner.movie_streams[0].closed is True
    assert session.live_view_frame() == JPEG
    assert len(runner.movie_streams) == 2
    session.stop_live_view()
    assert runner.values["/main/actions/viewfinder"] == "0"
    assert runner.movie_streams[-1].closed is True

    media = session.list_media()
    thumbnail, thumbnail_type = session.media_thumbnail(media[0].id)
    item, chunks = session.download_media(media[0].id)
    assert thumbnail == THUMBNAIL
    assert thumbnail_type == "image/jpeg"
    assert item.name == "IMG_0001.JPG"
    assert b"".join(chunks) == MEDIA_BYTES
    session.delete_media(media[0].id)
    observed = set(session.capabilities().evidence.observed_features)
    assert any("/main/imgsettings/iso=800" in command for command in runner.commands)
    assert any("--trigger-capture" in command for command in runner.commands)
    assert any("--delete-file" in command for command in runner.commands)
    assert {
        CameraFeature.DESKTOP_BRIDGE,
        CameraFeature.CAMERA_IDENTITY,
        CameraFeature.EXPOSURE_CONTROL,
        CameraFeature.WHITE_BALANCE_CONTROL,
        CameraFeature.STILL_CAPTURE,
        CameraFeature.AUTOFOCUS,
        CameraFeature.SHUTTER_HALF_PRESS,
        CameraFeature.VIDEO_RECORDING,
        CameraFeature.FOCUS_DRIVE,
        CameraFeature.LIVE_VIEW,
        CameraFeature.LIVE_VIEW_JPEG_POLLING,
        CameraFeature.MEDIA_BROWSER,
        CameraFeature.MEDIA_THUMBNAIL,
        CameraFeature.MEDIA_DOWNLOAD,
        CameraFeature.MEDIA_DELETE,
    } <= observed


def test_capture_refuses_unhandled_host_ram_target_without_a_card_choice() -> None:
    class HostOnlyRunner(FakeRunner):
        def _config_dump(self) -> str:
            return super()._config_dump().replace("Choice: 1 Memory card\n", "")

    runner = HostOnlyRunner()
    session = GPhoto2Engine(runner).open()

    with pytest.raises(BridgeError) as rejected:
        session.capture_still()

    assert rejected.value.code == "UNSAFE_CAPTURE_TARGET"
    assert CameraFeature.STILL_CAPTURE not in session.capabilities().evidence.observed_features
    assert not any("--trigger-capture" in command for command in runner.commands)


def test_autofocus_falls_back_to_half_press_without_a_complete_action_pair() -> None:
    class NoDedicatedAutofocusRunner(FakeRunner):
        def _config_dump(self) -> str:
            cancel = self._toggle("/main/actions/autofocuscancel", "Cancel Canon DSLR Autofocus")
            return super()._config_dump().replace(f"\n{cancel}", "")

    runner = NoDedicatedAutofocusRunner()
    session = GPhoto2Engine(runner).open()

    assert CameraFeature.AUTOFOCUS in session.capabilities().supported
    session.autofocus()

    assert runner.values["/main/actions/eosremoterelease"] == "Release Half"
    assert not any("/main/actions/autofocusdrive=1" in command for command in runner.commands)
    assert not any("/main/actions/autofocuscancel=1" in command for command in runner.commands)


def test_dedicated_autofocus_does_not_require_half_press() -> None:
    class NoHalfPressRunner(FakeRunner):
        def _config_dump(self) -> str:
            release = self._radio(
                "/main/actions/eosremoterelease",
                "Canon EOS Remote Release",
                ["None", "Press Half", "Press Full", "Release Half", "Release Full"],
            )
            return super()._config_dump().replace(f"\n{release}", "")

    runner = NoHalfPressRunner()
    session = GPhoto2Engine(runner).open()
    capabilities = session.capabilities()

    assert CameraFeature.AUTOFOCUS in capabilities.supported
    assert CameraFeature.SHUTTER_HALF_PRESS not in capabilities.supported
    session.autofocus()

    assert runner.values["/main/actions/autofocusdrive"] == "1"
    assert runner.values["/main/actions/autofocuscancel"] == "1"


def test_dedicated_autofocus_attempts_cancel_after_start_failure() -> None:
    class FailingAutofocusRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            command = self._without_camera(arguments)
            if command == ["--set-config-value", "/main/actions/autofocusdrive=1"]:
                self.commands.append(tuple(arguments))
                raise BridgeError("CAMERA_REQUEST_FAILED", "Autofocus start failed.", status_code=502)
            return super().run(arguments, timeout=timeout)

    runner = FailingAutofocusRunner()
    session = GPhoto2Engine(runner).open()

    with pytest.raises(BridgeError, match="Autofocus start failed"):
        session.autofocus()

    assert runner.values["/main/actions/autofocuscancel"] == "1"
    assert any("/main/actions/autofocuscancel=1" in command for command in runner.commands)


def test_r6_mark_iii_advanced_settings_use_advertised_safe_choices() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()

    settings = {setting.key: setting for setting in session.capabilities().settings}
    expected = {
        "whitebalanceadjusta",
        "whitebalanceadjustb",
        "aspectratio",
        "zoomspeed",
        "autopoweroff",
        "stillimagequalitysd",
        "stillimagequalitycf",
    }
    assert expected <= settings.keys()
    assert settings["autopoweroff"].values == ["15", "30", "60", "180", "300", "600", "1800", "0"]

    writes = {
        "whitebalanceadjusta": ("9", "/main/imgsettings/whitebalanceadjusta"),
        "whitebalanceadjustb": ("-9", "/main/imgsettings/whitebalanceadjustb"),
        "aspectratio": ("16:9", "/main/capturesettings/aspectratio"),
        "zoomspeed": ("15", "/main/capturesettings/zoomspeed"),
        "autopoweroff": ("1800", "/main/settings/autopoweroff"),
        "stillimagequalitysd": ("cRAW", "/main/imgsettings/imageformatsd"),
        "stillimagequalitycf": ("Large Fine JPEG", "/main/imgsettings/imageformatcf"),
    }
    for key, (value, path) in writes.items():
        session.set_setting(key, value)
        assert runner.values[path] == value

    with pytest.raises(BridgeError) as rejected:
        session.set_setting("autopoweroff", "4294967295")
    assert rejected.value.code == "INVALID_SETTING_VALUE"
    assert runner.values["/main/settings/autopoweroff"] == "1800"

    unsafe_runner = FakeRunner()
    unsafe_runner.values["/main/settings/autopoweroff"] = "4294967295"
    unsafe_session = GPhoto2Engine(unsafe_runner).open()
    unsafe_current = next(
        setting for setting in unsafe_session.capabilities().settings if setting.key == "autopoweroff"
    )
    assert unsafe_current.value == "-"


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

    started = time.monotonic()
    stream = runner.open_stream(
        [
            "-u",
            "-c",
            "import sys,time; sys.stdout.buffer.write(b'x'*65536); sys.stdout.flush(); time.sleep(10)",
        ],
        timeout=30.0,
    )
    assert len(next(stream)) == 65536
    stream.close()
    assert time.monotonic() - started < 3.0


def test_mjpeg_parser_handles_split_markers_junk_and_multiple_frames() -> None:
    parser = MjpegFrameParser()

    assert parser.feed(b"stderr-like-junk\xff") == []
    assert parser.feed(b"\xd8first\xff") == []
    assert parser.feed(b"\xd9more-junk\xff\xd8second\xff\xd9tail") == [
        b"\xff\xd8first\xff\xd9",
        b"\xff\xd8second\xff\xd9",
    ]


def test_failed_live_view_start_restores_camera_viewfinder() -> None:
    class FailingPreviewRunner(FakeRunner):
        def open_stream(self, arguments: list[str], *, timeout: float = 300.0):
            del arguments, timeout
            raise BridgeError("ENGINE_COMMAND_FAILED", "movie preview failed", status_code=502)

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


def test_live_view_falls_back_to_single_preview_and_caps_requested_fps() -> None:
    class NoMovieRunner(FakeRunner):
        def open_stream(self, arguments: list[str], *, timeout: float = 300.0):
            self.commands.append(tuple(arguments))
            del timeout
            raise BridgeError("ENGINE_COMMAND_FAILED", "capture-movie is unavailable", status_code=502)

    runner = NoMovieRunner()
    session = GPhoto2Engine(runner).open()

    session.start_live_view(LiveViewStartRequest(fps=30))

    assert session.requested_fps == 5
    assert session.live_view_frame() == JPEG
    assert any("--capture-movie" in command for command in runner.commands)
    assert any("--capture-preview" in command for command in runner.commands)
    assert session.status().raw["liveViewTransport"] == "GPHOTO2_CAPTURE_PREVIEW"
    assert "capture-movie is unavailable" in session.status().raw["liveViewFallbackReason"]
    session.stop_live_view()
