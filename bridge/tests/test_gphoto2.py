from __future__ import annotations

import sys
import threading
import time
from pathlib import Path

import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import (
    CommandOutput,
    GPhoto2Engine,
    GPhotoCommand,
    MjpegFrameParser,
    SubprocessGPhotoRunner,
    WslHostState,
    _windows_path_to_wsl,
    parse_abilities,
    parse_auto_detect,
    parse_config_dump,
    parse_media_info,
    parse_media_list,
    parse_storage_info,
    parse_wait_event_keys,
    resolve_gphoto_command,
    summary_supports_file_upload,
)
from open_eos_bridge.local_media import LocalCaptureStore, default_capture_directory, preview_content_type
from open_eos_bridge.models import (
    CameraFeature,
    CameraModelFamily,
    CameraModelPriority,
    LiveViewStartRequest,
)

from .fakes import (
    ABILITIES,
    AUTO_DETECT,
    JPEG,
    MEDIA,
    MEDIA_BYTES,
    MEDIA_INFO,
    STORAGE,
    SUMMARY,
    THUMBNAIL,
    FakeRunner,
)


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


def test_cancellable_runner_terminates_an_active_process() -> None:
    runner = SubprocessGPhotoRunner(command=GPhotoCommand((sys.executable,), "native"))
    cancelled = threading.Event()
    timer = threading.Timer(0.1, cancelled.set)
    started_at = time.monotonic()
    timer.start()
    try:
        with pytest.raises(BridgeError) as failure:
            runner.run_cancellable(
                ["-c", "import time; time.sleep(30)"],
                timeout=10.0,
                cancelled=cancelled,
            )
    finally:
        timer.cancel()

    assert failure.value.code == "UPLOAD_CANCELLED"
    assert time.monotonic() - started_at < 5.0


def test_capture_paths_map_to_wsl_without_using_a_shell() -> None:
    assert _windows_path_to_wsl(r"D:\OpenEOSControl\Camera Captures") == ("/mnt/d/OpenEOSControl/Camera Captures")

    with pytest.raises(BridgeError) as rejected:
        _windows_path_to_wsl(r"\\server\share\Captures")

    assert rejected.value.code == "UNSUPPORTED_CAPTURE_DIRECTORY"


def test_default_capture_directory_is_platform_scoped_and_requires_absolute_override(tmp_path: Path) -> None:
    assert (
        default_capture_directory(
            environment={"LOCALAPPDATA": str(tmp_path)},
            platform_name="win32",
            home=tmp_path,
        )
        == tmp_path / "OpenEOSControl" / "Captures"
    )
    assert default_capture_directory(environment={}, platform_name="linux", home=tmp_path) == (
        tmp_path / ".local" / "share" / "open-eos-control" / "captures"
    )

    with pytest.raises(BridgeError) as rejected:
        default_capture_directory(
            environment={"OPEN_EOS_CAPTURE_DIR": "relative-captures"},
            platform_name="linux",
            home=tmp_path,
        )

    assert rejected.value.code == "INVALID_CAPTURE_DIRECTORY"


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
        "Using gphoto2 in WSL distribution 'Ubuntu'. Install usbipd-win before attaching a Windows USB camera to WSL."
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
        "/main/settings/datetimeutc\n"
        "Label: Camera Date and Time\nReadonly: 0\nType: DATE\nCurrent: 1768044194\nEND\n"
        "/main/settings/ownername\n"
        "Label: Owner Name\nReadonly: 0\nType: TEXT\nCurrent:  Studio A  \nEND\n"
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
    assert configs["/main/settings/datetimeutc"].kind == "DATE"
    assert configs["/main/settings/datetimeutc"].current == "1768044194"
    assert configs["/main/settings/ownername"].current == " Studio A  "


def test_storage_and_media_parsers_handle_r6_mark_iii_shapes() -> None:
    storage = parse_storage_info(STORAGE)
    media = parse_media_list(MEDIA)

    assert storage.devices == 2
    assert storage.total_bytes == 639_922_864_128
    assert storage.free_bytes == 499_332_284_416
    assert storage.free_images == 3210
    assert [(entry.storage_id, entry.description, entry.writable) for entry in storage.entries] == [
        ("00010001", "CFe", True),
        ("00020001", "SD", True),
    ]
    assert [item.name for item in media] == ["IMG_0001.JPG", "IMG_0001.CR3"]
    assert media[0].size_bytes == 6
    assert media[0].content_type == "image/jpeg"
    assert (media[0].width_pixels, media[0].height_pixels) == (6000, 4000)
    assert media[0].capture_time == "2026-07-21T02:13:21Z"
    assert media[0].preview_available is True
    assert media[1].preview_available is False
    assert summary_supports_file_upload(SUMMARY) is True
    assert summary_supports_file_upload(SUMMARY.replace("File Upload", "File Transfer")) is False
    assert summary_supports_file_upload(SUMMARY.replace("File Upload", "No File Upload")) is False


def test_media_parser_does_not_truncate_more_than_five_hundred_items() -> None:
    output = "\n".join(
        ["There are 501 files in folder '/store_00010001/DCIM/100CANON'."]
        + [f"#{index} IMG_{index:04}.JPG rd 1 KB image/jpeg 1784600000" for index in range(1, 502)]
    )

    items = parse_media_list(output)

    assert len(items) == 501
    assert items[0].name == "IMG_0501.JPG"
    assert items[-1].name == "IMG_0001.JPG"


def test_local_media_store_does_not_truncate_more_than_five_hundred_items(tmp_path: Path) -> None:
    for index in range(1, 502):
        (tmp_path / f"IMG_{index:04}.JPG").write_bytes(b"x")

    items = LocalCaptureStore(tmp_path).list_items()

    assert len(items) == 501
    assert {item.name for item in items} == {f"IMG_{index:04}.JPG" for index in range(1, 502)}


def test_media_info_parser_reads_only_the_primary_file_section() -> None:
    info = parse_media_info(MEDIA_INFO)

    assert info.file_section_available is True
    assert info.content_type == "image/jpeg"
    assert info.size_bytes == 6
    assert (info.width_pixels, info.height_pixels) == (16, 12)
    assert info.capture_time is not None

    missing = parse_media_info("Information on file 'EMPTY.JPG':\nFile:\n  None available.\n")
    assert missing.file_section_available is True
    assert missing.content_type is None
    assert missing.size_bytes is None
    assert missing.width_pixels is None
    assert missing.height_pixels is None
    assert missing.capture_time is None

    unquoted = parse_media_info("File:\n  Mime type: image/jpeg\n")
    half_quoted = parse_media_info("File:\n  Mime type: 'image/jpeg\n")
    assert unquoted.content_type is None
    assert half_quoted.content_type is None


def test_media_info_rejects_an_unrecognized_gphoto2_response() -> None:
    class InvalidInfoRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if "--show-info" in self._without_camera(arguments):
                self.commands.append(tuple(arguments))
                return CommandOutput(b"unexpected output\n")
            return super().run(arguments, timeout=timeout)

    runner = InvalidInfoRunner()
    session = GPhoto2Engine(runner).open()
    item = session.list_media()[0]
    session.start_live_view(LiveViewStartRequest(fps=15))
    assert session.live_view_frame() == JPEG

    with pytest.raises(BridgeError) as failure:
        session.media_info(item.id)

    assert failure.value.code == "INVALID_MEDIA_INFO"
    assert failure.value.status_code == 502
    assert session.live_view_frame() == JPEG
    assert len(runner.movie_streams) == 2
    session.stop_live_view()


def test_media_info_does_not_reuse_stale_listing_fields_when_gphoto2_reports_none() -> None:
    class MissingInfoRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if "--show-info" in self._without_camera(arguments):
                self.commands.append(tuple(arguments))
                return CommandOutput(b"File:\n  None available.\nThumbnail:\n  Mime type: 'image/jpeg'\n")
            return super().run(arguments, timeout=timeout)

    session = GPhoto2Engine(MissingInfoRunner()).open()
    listed = session.list_media()[0]
    assert listed.size_bytes == 6
    assert listed.content_type == "image/jpeg"

    refreshed = session.media_info(listed.id)

    assert refreshed.size_bytes == 0
    assert refreshed.content_type == "application/octet-stream"
    assert refreshed.capture_time is None
    assert refreshed.width_pixels is None
    assert refreshed.height_pixels is None
    assert refreshed.preview_available is False


def test_media_info_temporarily_releases_and_restores_persistent_live_view() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()
    item = session.list_media()[0]
    session.start_live_view(LiveViewStartRequest(fps=15))

    assert session.live_view_frame() == JPEG
    assert session.media_info(item.id).name == "IMG_0001.JPG"
    assert runner.movie_streams[0].closed is True
    assert session.live_view_frame() == JPEG
    assert len(runner.movie_streams) == 2

    session.stop_live_view()


def test_media_info_uses_one_read_only_camera_command() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()
    item = session.list_media()[0]
    command_count = len(runner.commands)

    session.media_info(item.id)

    assert runner.commands[command_count:] == [
        (
            "--port",
            "usb:001,007",
            "--folder",
            "/store_00010001/DCIM/100CANON",
            "--show-info",
            "IMG_0001.JPG",
        )
    ]


def test_storage_parser_keeps_libgphoto2_summary_compatibility() -> None:
    storage = parse_storage_info(
        """store_00010001:
    StorageDescription: CFe=primary
    VolumeLabel: EOS_DIGITAL
    Access Capability: Read-Write
    Maximum Capability: 512090963968 (488368 MB)
    Free Space (Bytes): 440194695168 (419802 MB)
    Free Space (Images): 2048
"""
    )

    assert storage.devices == 1
    assert storage.total_bytes == 512_090_963_968
    assert storage.entries[0].base_dir == "/store_00010001"
    assert storage.entries[0].description == "CFe=primary"
    assert storage.entries[0].label == "EOS_DIGITAL"
    assert storage.entries[0].writable is True

    numbered = parse_storage_info(
        """Storage #1:
    StorageDescription: Legacy card
    Maximum Capacity: 4096
    Free Space (Bytes): 1024
"""
    )
    assert numbered.devices == 1
    assert numbered.total_bytes == 4096
    assert numbered.entries[0].storage_id is None


def test_media_preview_validation_requires_complete_jpeg_or_png_markers() -> None:
    complete_png = b"\x89PNG\r\n\x1a\n\x00\x00\x00\x00IEND\xaeB`\x82"

    assert preview_content_type(JPEG) == "image/jpeg"
    assert preview_content_type(complete_png) == "image/png"
    assert preview_content_type(complete_png[:-1]) is None
    assert preview_content_type(b"\xff\xd8truncated") is None


def test_wait_event_parser_maps_stable_gphoto2_markers_to_refresh_hints() -> None:
    assert parse_wait_event_keys(
        "Waiting for events from camera.\n"
        "UNKNOWN PTP Property d105 changed\n"
        "FILEADDED IMG_0002.JPG /store_00010001/DCIM/100CANON\n"
        "FOLDERADDED 101CANON /store_00010001/DCIM\n"
        "FILECHANGED IMG_0001.JPG /store_00010001/DCIM/100CANON\n"
        "CAPTURECOMPLETE\n"
    ) == ["shooting", "contents", "storage"]
    assert parse_wait_event_keys("Waiting for 250 milliseconds for events from camera.\n") == []


def test_gphoto2_event_probe_preserves_events_and_gates_the_runtime_capability() -> None:
    class EventRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            command = self._without_camera(arguments)
            if command == ["--wait-event", "1ms"]:
                self.commands.append(tuple(arguments))
                return CommandOutput(b"FILEADDED IMG_0002.JPG /store_00010001/DCIM/100CANON\n")
            if command == ["--wait-event", "250ms"]:
                self.commands.append(tuple(arguments))
                return CommandOutput(b"UNKNOWN PTP Property d105 changed\nCAPTURECOMPLETE\n")
            return super().run(arguments, timeout=timeout)

    session = GPhoto2Engine(EventRunner()).open()

    assert CameraFeature.EVENT_POLLING in session.capabilities().supported
    assert session.poll_event().changed_keys == ["contents", "storage"]
    assert session.poll_event().changed_keys == ["shooting", "contents", "storage"]
    assert CameraFeature.EVENT_POLLING in session.capabilities().evidence.observed_features

    class NoEventRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--wait-event", "1ms"]:
                self.commands.append(tuple(arguments))
                raise BridgeError("ENGINE_COMMAND_FAILED", "wait-event is not supported")
            return super().run(arguments, timeout=timeout)

    unavailable = GPhoto2Engine(NoEventRunner()).open()
    capabilities = unavailable.capabilities()
    assert CameraFeature.EVENT_POLLING not in capabilities.supported
    assert CameraFeature.EVENT_POLLING in capabilities.planned
    assert "wait-event probe failed" in capabilities.reasons[CameraFeature.EVENT_POLLING.value]
    with pytest.raises(BridgeError) as rejected:
        unavailable.poll_event()
    assert rejected.value.code == "UNSUPPORTED_FEATURE"
    unavailable.stop_event_polling()


def test_stop_event_polling_suppresses_an_inflight_gphoto2_result() -> None:
    class BlockingEventRunner(FakeRunner):
        def __init__(self) -> None:
            super().__init__()
            self.started = threading.Event()
            self.release = threading.Event()

        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--wait-event", "250ms"]:
                self.commands.append(tuple(arguments))
                self.started.set()
                assert self.release.wait(timeout=2.0)
                return CommandOutput(b"FILEADDED IMG_0002.JPG /store_00010001/DCIM/100CANON\n")
            return super().run(arguments, timeout=timeout)

    runner = BlockingEventRunner()
    session = GPhoto2Engine(runner).open()
    result: list[list[str]] = []
    poller = threading.Thread(target=lambda: result.append(session.poll_event().changed_keys))
    poller.start()
    assert runner.started.wait(timeout=1.0)

    session.stop_event_polling()
    runner.release.set()
    poller.join(timeout=2.0)

    assert not poller.is_alive()
    assert result == [[]]
    assert session.poll_event().changed_keys == ["contents", "storage"]


def test_event_polling_pauses_without_interrupting_gphoto2_live_view() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()
    session.start_live_view(LiveViewStartRequest(fps=15))
    stream = runner.movie_streams[-1]
    wait_commands_before = sum("--wait-event" in command for command in runner.commands)

    assert session.poll_event().changed_keys == []

    assert sum("--wait-event" in command for command in runner.commands) == wait_commands_before
    assert stream.closed is False
    session.stop_live_view()


def test_live_view_frame_wait_does_not_block_camera_controls() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()
    session.start_live_view(LiveViewStartRequest(fps=15))
    stream = runner.movie_streams[-1]
    assert session.live_view_frame() == JPEG

    result: list[bytes] = []
    reader = threading.Thread(target=lambda: result.append(session.live_view_frame()))
    reader.start()
    assert stream.waiting.wait(timeout=1.0)

    focus = session.drive_focus("near", "large")
    reader.join(timeout=2.0)

    assert focus.accepted is True
    assert runner.values["/main/actions/manualfocusdrive"] == "Near 3"
    assert stream.closed is True
    assert not reader.is_alive()
    assert result == [JPEG]
    session.stop_live_view()


def test_session_capabilities_and_controls_are_backed_by_real_commands(tmp_path: Path) -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()

    capabilities = session.capabilities()
    assert capabilities.profile.family is CameraModelFamily.EOS_R
    assert capabilities.profile.priority is CameraModelPriority.PRIMARY
    assert CameraFeature.STILL_CAPTURE in capabilities.supported
    assert CameraFeature.BULB_EXPOSURE in capabilities.supported
    assert CameraFeature.LIVE_VIEW in capabilities.supported
    assert CameraFeature.SHUTTER_HALF_PRESS in capabilities.supported
    assert CameraFeature.AUTOFOCUS in capabilities.supported
    assert CameraFeature.VIDEO_RECORDING in capabilities.supported
    assert CameraFeature.FOCUS_DRIVE in capabilities.supported
    assert CameraFeature.LIVE_VIEW_MAGNIFICATION in capabilities.supported
    assert CameraFeature.MEDIA_DELETE in capabilities.supported
    assert CameraFeature.MEDIA_UPLOAD in capabilities.supported
    assert CameraFeature.MEDIA_THUMBNAIL in capabilities.supported
    assert CameraFeature.MEDIA_PREVIEW in capabilities.supported
    assert CameraFeature.EVENT_POLLING in capabilities.supported
    assert CameraFeature.CAMERA_CLOCK_SYNC in capabilities.supported
    assert CameraFeature.TAP_FOCUS in capabilities.planned
    assert CameraFeature.CLICK_WHITE_BALANCE in capabilities.planned
    assert CameraFeature.MEDIA_ARCHIVE in capabilities.planned
    assert CameraFeature.MEDIA_ARCHIVE not in capabilities.supported
    assert "No verified libgphoto2 contract" in capabilities.reasons[CameraFeature.MEDIA_ARCHIVE.value]
    assert next(setting for setting in capabilities.settings if setting.key == "iso").values == [
        "Auto",
        "100",
        "400",
        "800",
    ]
    assert capabilities.evidence.source == (
        "gphoto2 --abilities + --list-all-config + --storage-info + --wait-event probe"
    )
    assert "CAPTURE_PREVIEW" in capabilities.evidence.advertised_commands
    assert "CAPTURE_MOVIE_STDOUT" in capabilities.evidence.advertised_commands
    assert "CAPTURE_IMAGE_AND_DOWNLOAD" in capabilities.evidence.advertised_commands
    assert "AUTOFOCUS_DRIVE_CANCEL" in capabilities.evidence.advertised_commands
    assert "SHUTTER_HALF_PRESS" in capabilities.evidence.advertised_commands
    assert "GPHOTO2_WAIT_EVENT" in capabilities.evidence.advertised_commands
    assert "CAMERA_CLOCK_ACTION_WITH_DATE_READBACK" in capabilities.evidence.advertised_commands
    assert capabilities.live_view.max_fps == 30
    assert "/main/imgsettings/iso" in capabilities.evidence.writable_settings

    status = session.status()
    assert status.media.free_images == 46_822
    assert status.raw["remainingShotsSource"] == "gphoto2-config:/main/status/availableshots"
    assert status.raw["eventPollingTransport"] == "GPHOTO2_WAIT_EVENT"
    assert session.poll_event().changed_keys == []

    status = session.set_setting("iso", "800")
    assert status.exposure.iso == "800"

    status = session.set_setting("whitebalance", "daylight")
    assert status.exposure.white_balance == "Daylight"

    session.sync_camera_clock()
    assert runner.values["/main/actions/syncdatetimeutc"] == "1"
    clock_action_index = next(
        index
        for index, command in enumerate(runner.commands)
        if "/main/actions/syncdatetimeutc=1" in command
    )
    assert any(
        index > clock_action_index and command[-1] == "--list-all-config"
        for index, command in enumerate(runner.commands)
    )

    session.set_setting("capturetarget", "Memory card")
    assert runner.values["/main/settings/capturetarget"] == "Memory card"
    session.capture_still()
    started_bulb = session.start_bulb_exposure()
    assert started_bulb.bulb_exposure_active is True
    assert runner.values["/main/actions/eosremoterelease"] == "Press Full"
    stopped_bulb = session.stop_bulb_exposure()
    assert stopped_bulb.bulb_exposure_active is False
    assert runner.values["/main/actions/eosremoterelease"] == "Release Full"
    session.half_press_shutter()
    session.autofocus()
    assert runner.values["/main/actions/eosremoterelease"] == "Release Half"
    assert runner.values["/main/actions/autofocusdrive"] == "1"
    assert runner.values["/main/actions/autofocuscancel"] == "1"
    autofocus_drive = next(
        index for index, command in enumerate(runner.commands) if "/main/actions/autofocusdrive=1" in command
    )
    autofocus_cancel = next(
        index for index, command in enumerate(runner.commands) if "/main/actions/autofocuscancel=1" in command
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
    magnification = session.set_live_view_magnification(5)
    assert magnification.accepted is True
    assert magnification.value == 5
    assert runner.values["/main/actions/eoszoom"] == "5"
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
    media_info = session.media_info(media[0].id)
    thumbnail, thumbnail_type = session.media_thumbnail(media[0].id)
    preview, preview_type = session.media_preview(media[0].id)
    with pytest.raises(BridgeError) as raw_preview:
        session.media_preview(media[1].id)
    item, chunks = session.download_media(media[0].id)
    assert thumbnail == THUMBNAIL
    assert thumbnail_type == "image/jpeg"
    assert preview == JPEG
    assert preview_type == "image/jpeg"
    assert raw_preview.value.code == "MEDIA_PREVIEW_UNAVAILABLE"
    assert media_info.name == "IMG_0001.JPG"
    assert media_info.size_bytes == 6
    assert media_info.content_type == "image/jpeg"
    assert media_info.preview_available is True
    assert any(command[-4:] == (
        "--folder",
        "/store_00010001/DCIM/100CANON",
        "--show-info",
        "IMG_0001.JPG",
    ) for command in runner.commands)
    assert item.name == "IMG_0001.JPG"
    assert b"".join(chunks) == MEDIA_BYTES
    session.delete_media(media[0].id)
    upload_payload = b"uploaded-jpeg"
    (tmp_path / "upload.jpg").write_bytes(upload_payload)
    uploaded = session.upload_media(
        "UPLOADED.JPG",
        tmp_path / "upload.jpg",
        len(upload_payload),
        "image/jpeg",
        threading.Event(),
    )
    assert uploaded.name == "UPLOADED.JPG"
    assert uploaded.size_bytes == len(upload_payload)
    assert runner.uploaded_files[("/store_00010001", "UPLOADED.JPG")] == upload_payload
    assert any("--upload-file" in command for command in runner.cancellable_commands)
    observed = set(session.capabilities().evidence.observed_features)
    assert any("/main/imgsettings/iso=800" in command for command in runner.commands)
    assert any("/main/actions/eoszoom=5" in command for command in runner.commands)
    assert any("--trigger-capture" in command for command in runner.commands)
    assert any("--delete-file" in command for command in runner.commands)
    assert {
        CameraFeature.DESKTOP_BRIDGE,
        CameraFeature.CAMERA_IDENTITY,
        CameraFeature.EXPOSURE_CONTROL,
        CameraFeature.WHITE_BALANCE_CONTROL,
        CameraFeature.STILL_CAPTURE,
        CameraFeature.BULB_EXPOSURE,
        CameraFeature.AUTOFOCUS,
        CameraFeature.SHUTTER_HALF_PRESS,
        CameraFeature.VIDEO_RECORDING,
        CameraFeature.CAMERA_CLOCK_SYNC,
        CameraFeature.FOCUS_DRIVE,
        CameraFeature.LIVE_VIEW_MAGNIFICATION,
        CameraFeature.LIVE_VIEW,
        CameraFeature.LIVE_VIEW_JPEG_POLLING,
        CameraFeature.MEDIA_BROWSER,
        CameraFeature.MEDIA_THUMBNAIL,
        CameraFeature.MEDIA_PREVIEW,
        CameraFeature.MEDIA_DOWNLOAD,
        CameraFeature.MEDIA_DELETE,
        CameraFeature.MEDIA_UPLOAD,
        CameraFeature.EVENT_POLLING,
    } <= observed


def test_gphoto2_clock_sync_falls_back_to_matching_local_action_and_date_widget() -> None:
    class LocalClockRunner(FakeRunner):
        def __init__(self) -> None:
            super().__init__()
            self.values["/main/actions/syncdatetime"] = "0"
            self.values["/main/settings/datetime"] = "1700000000"

        def _config_dump(self) -> str:
            self.values["/main/actions/syncdatetimeutc"] = self.values["/main/actions/syncdatetime"]
            self.values["/main/settings/datetimeutc"] = self.values["/main/settings/datetime"]
            return (
                super()
                ._config_dump()
                .replace("/main/actions/syncdatetimeutc", "/main/actions/syncdatetime")
                .replace("/main/settings/datetimeutc", "/main/settings/datetime")
            )

    runner = LocalClockRunner()
    session = GPhoto2Engine(runner).open()

    assert CameraFeature.CAMERA_CLOCK_SYNC in session.capabilities().supported
    session.sync_camera_clock()

    assert runner.values["/main/actions/syncdatetime"] == "1"
    assert CameraFeature.CAMERA_CLOCK_SYNC in session.capabilities().evidence.observed_features


def test_gphoto2_clock_sync_is_hidden_without_a_matching_date_readback() -> None:
    class ActionOnlyClockRunner(FakeRunner):
        def _config_dump(self) -> str:
            date_block = self._date("/main/settings/datetimeutc", "Camera Date and Time")
            return super()._config_dump().replace(f"\n{date_block}", "")

    runner = ActionOnlyClockRunner()
    session = GPhoto2Engine(runner).open()

    capabilities = session.capabilities()
    assert CameraFeature.CAMERA_CLOCK_SYNC not in capabilities.supported
    assert CameraFeature.CAMERA_CLOCK_SYNC in capabilities.planned

    with pytest.raises(BridgeError) as failure:
        session.sync_camera_clock()

    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert not any("syncdatetime" in part and "=1" in part for command in runner.commands for part in command)


def test_gphoto2_clock_sync_rejects_mismatched_readback_without_observation() -> None:
    class MismatchedClockRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            output = super().run(arguments, timeout=timeout)
            command = self._without_camera(arguments)
            if command and command[0] == "--set-config-value" and "syncdatetimeutc=1" in command[1]:
                self.values["/main/settings/datetimeutc"] = "1"
            return output

    runner = MismatchedClockRunner()
    session = GPhoto2Engine(runner).open()

    with pytest.raises(BridgeError) as failure:
        session.sync_camera_clock()

    assert failure.value.code == "CAMERA_CLOCK_VERIFY_FAILED"
    assert failure.value.feature == CameraFeature.CAMERA_CLOCK_SYNC.value
    assert CameraFeature.CAMERA_CLOCK_SYNC not in session.capabilities().evidence.observed_features


def test_gphoto2_clock_sync_does_not_observe_a_rejected_action() -> None:
    class RejectedClockRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            command = self._without_camera(arguments)
            if command and command[0] == "--set-config-value" and "syncdatetimeutc=1" in command[1]:
                raise BridgeError("CAMERA_REQUEST_FAILED", "Clock action rejected.", status_code=502)
            return super().run(arguments, timeout=timeout)

    runner = RejectedClockRunner()
    session = GPhoto2Engine(runner).open()

    with pytest.raises(BridgeError, match="Clock action rejected"):
        session.sync_camera_clock()

    assert CameraFeature.CAMERA_CLOCK_SYNC not in session.capabilities().evidence.observed_features


def test_gphoto2_clock_sync_does_not_observe_a_failed_post_write_refresh() -> None:
    class RefreshFailureClockRunner(FakeRunner):
        fail_clock_refresh = False

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            command = self._without_camera(arguments)
            if command == ["--list-all-config"] and self.fail_clock_refresh:
                raise BridgeError("CAMERA_REQUEST_FAILED", "Clock readback failed.", status_code=502)
            output = super().run(arguments, timeout=timeout)
            if command and command[0] == "--set-config-value" and "syncdatetimeutc=1" in command[1]:
                self.fail_clock_refresh = True
            return output

    runner = RefreshFailureClockRunner()
    session = GPhoto2Engine(runner).open()

    with pytest.raises(BridgeError, match="Clock readback failed"):
        session.sync_camera_clock()

    runner.fail_clock_refresh = False
    assert CameraFeature.CAMERA_CLOCK_SYNC not in session.capabilities().evidence.observed_features


def test_gphoto2_close_releases_an_active_bulb_exposure(tmp_path: Path) -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()

    session.start_bulb_exposure()
    session.close()

    assert runner.values["/main/actions/eosremoterelease"] == "Release Full"


def test_failed_gphoto2_bulb_press_still_attempts_release(tmp_path: Path) -> None:
    class RejectPressRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if any(value.endswith("eosremoterelease=Press Full") for value in arguments):
                self.commands.append(tuple(arguments))
                raise BridgeError("CAMERA_COMMAND_FAILED", "press response lost")
            return super().run(arguments, timeout=timeout)

    runner = RejectPressRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()

    with pytest.raises(BridgeError):
        session.start_bulb_exposure()

    release_commands = [
        command
        for command in runner.commands
        if any(value.endswith("eosremoterelease=Release Full") for value in command)
    ]
    assert len(release_commands) == 1
    assert runner.values["/main/actions/eosremoterelease"] == "Release Full"
    assert session.status().bulb_exposure_active is False


def test_live_view_magnification_requires_active_live_view_and_writable_eoszoom() -> None:
    session = GPhoto2Engine(FakeRunner()).open()

    with pytest.raises(BridgeError) as inactive:
        session.set_live_view_magnification(5)

    assert inactive.value.code == "LIVE_VIEW_REQUIRED"

    class NoEosZoomRunner(FakeRunner):
        def _config_dump(self) -> str:
            zoom = self._text("/main/actions/eoszoom", "Canon EOS Zoom", readonly=False)
            return super()._config_dump().replace(f"\n{zoom}", "")

    unavailable = GPhoto2Engine(NoEosZoomRunner()).open()
    assert CameraFeature.LIVE_VIEW_MAGNIFICATION not in unavailable.capabilities().supported
    unavailable.start_live_view(LiveViewStartRequest())

    with pytest.raises(BridgeError) as missing:
        unavailable.set_live_view_magnification(5)

    assert missing.value.code == "UNSUPPORTED_FEATURE"
    unavailable.stop_live_view()


def test_session_falls_back_to_storage_info_when_available_shots_is_unknown() -> None:
    runner = FakeRunner()
    runner.values["/main/status/availableshots"] = "4294967295"
    session = GPhoto2Engine(runner).open()

    status = session.status()

    assert status.media.free_images == 3210
    assert status.raw["remainingShotsSource"] == "gphoto2-storage-info"


def test_capture_downloads_host_ram_and_exposes_local_media_lifecycle(tmp_path: Path) -> None:
    class HostOnlyRunner(FakeRunner):
        def _config_dump(self) -> str:
            return super()._config_dump().replace("Choice: 1 Memory card\n", "")

    runner = HostOnlyRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()

    session.capture_still()

    assert runner.values["/main/settings/capturetarget"] == "Internal RAM"
    assert any("--capture-image-and-download" in command for command in runner.commands)
    assert not any("--trigger-capture" in command for command in runner.commands)
    local_item = next(item for item in session.list_media() if item.id.startswith("gphoto2-host:"))
    assert local_item.name.startswith("OEC_")
    assert local_item.content_type == "image/jpeg"
    assert local_item.preview_available is True
    thumbnail, thumbnail_type = session.media_thumbnail(local_item.id)
    preview, preview_type = session.media_preview(local_item.id)
    downloaded_item, chunks = session.download_media(local_item.id)
    assert thumbnail.startswith(b"\xff\xd8")
    assert thumbnail_type == "image/jpeg"
    assert preview.startswith(b"\xff\xd8") and preview.endswith(b"\xff\xd9")
    assert preview_type == "image/jpeg"
    assert downloaded_item == local_item
    assert b"".join(chunks).startswith(b"\xff\xd8")

    local_path = tmp_path / local_item.name
    original_size = local_path.stat().st_size
    local_path.write_bytes(local_path.read_bytes() + b"refreshed")
    refreshed_local_item = session.media_info(local_item.id)
    assert refreshed_local_item.size_bytes == original_size + len(b"refreshed")
    assert not any("--show-info" in command for command in runner.commands)

    session.delete_media(local_item.id)

    assert not any(item.id.startswith("gphoto2-host:") for item in session.list_media())
    assert not list(tmp_path.glob("*.JPG"))
    assert "CAPTURE_IMAGE_AND_DOWNLOAD" in session.capabilities().evidence.advertised_commands


def test_host_capture_promotes_multiple_downloaded_files(tmp_path: Path) -> None:
    class RawAndJpegRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            output = super().run(arguments, timeout=timeout)
            command = self._without_camera(arguments)
            if "--capture-image-and-download" in command:
                pattern = command[command.index("--filename") + 1]
                raw_target = Path(pattern.replace("%%", "%").replace("%04n", "0002").replace("%C", "CR3"))
                raw_target.write_bytes(b"test-canon-raw")
            return output

    session = GPhoto2Engine(RawAndJpegRunner(), capture_directory=tmp_path).open()

    session.capture_still()

    host_items = [item for item in session.list_media() if item.id.startswith("gphoto2-host:")]
    assert {Path(item.name).suffix for item in host_items} == {".JPG", ".CR3"}
    assert next(item for item in host_items if item.name.endswith(".CR3")).content_type == "image/x-canon-cr3"


def test_failed_host_capture_discards_staged_partial_media(tmp_path: Path) -> None:
    class FailingDownloadRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            command = self._without_camera(arguments)
            if "--capture-image-and-download" in command:
                pattern = command[command.index("--filename") + 1]
                partial = Path(pattern.replace("%%", "%").replace("%04n", "0001").replace("%C", "JPG"))
                partial.parent.mkdir(parents=True, exist_ok=True)
                partial.write_bytes(b"partial")
                raise BridgeError(
                    "ENGINE_COMMAND_FAILED",
                    f"Download failed while writing {pattern}",
                    status_code=502,
                )
            return super().run(arguments, timeout=timeout)

    session = GPhoto2Engine(FailingDownloadRunner(), capture_directory=tmp_path).open()

    with pytest.raises(BridgeError, match="Download failed") as rejected:
        session.capture_still()

    assert str(tmp_path) not in rejected.value.message
    assert "<capture-directory>" in rejected.value.message
    assert not [path for path in tmp_path.iterdir() if path.is_file()]
    assert not list((tmp_path / ".staging").glob("capture-*"))
    assert not any(item.id.startswith("gphoto2-host:") for item in session.list_media())


def test_capture_refuses_host_ram_without_capture_image_ability(tmp_path: Path) -> None:
    class TriggerOnlyHostRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if arguments[-1:] == ["--abilities"]:
                output = ABILITIES.replace("                                 : Image\n", "")
                return CommandOutput(output.encode())
            return super().run(arguments, timeout=timeout)

        def _config_dump(self) -> str:
            return super()._config_dump().replace("Choice: 1 Memory card\n", "")

    runner = TriggerOnlyHostRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()

    with pytest.raises(BridgeError) as rejected:
        session.capture_still()

    assert rejected.value.code == "UNSAFE_CAPTURE_TARGET"
    assert CameraFeature.STILL_CAPTURE not in session.capabilities().supported
    assert CameraFeature.STILL_CAPTURE not in session.capabilities().evidence.observed_features
    assert not any("--trigger-capture" in command for command in runner.commands)


def test_trigger_only_capture_falls_back_from_host_ram_to_advertised_card(tmp_path: Path) -> None:
    class TriggerOnlyRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if arguments[-1:] == ["--abilities"]:
                return CommandOutput(ABILITIES.replace("                                 : Image\n", "").encode())
            return super().run(arguments, timeout=timeout)

    runner = TriggerOnlyRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()

    capabilities = session.capabilities()
    session.capture_still()

    assert CameraFeature.STILL_CAPTURE in capabilities.supported
    assert not any(setting.key == "capturetarget" for setting in capabilities.settings)
    assert runner.values["/main/settings/capturetarget"] == "Memory card"
    assert any("--trigger-capture" in command for command in runner.commands)


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
        "alomode",
        "autopoweroff",
        "stillimagequalitysd",
        "stillimagequalitycf",
        "capturetarget",
        "capturestorage",
    }
    assert expected <= settings.keys()
    assert settings["autopoweroff"].values == ["15", "30", "60", "180", "300", "600", "1800", "0"]
    assert settings["alomode"].values == ["Standard", "Low", "High", "Off"]

    writes = {
        "whitebalanceadjusta": ("9", "/main/imgsettings/whitebalanceadjusta", "9"),
        "whitebalanceadjustb": ("-9", "/main/imgsettings/whitebalanceadjustb", "-9"),
        "aspectratio": ("16:9", "/main/capturesettings/aspectratio", "16:9"),
        "zoomspeed": ("15", "/main/capturesettings/zoomspeed", "15"),
        "alomode": ("High", "/main/capturesettings/alomode", "High"),
        "autopoweroff": ("1800", "/main/settings/autopoweroff", "1800"),
        "stillimagequalitysd": ("cRAW", "/main/imgsettings/imageformatsd", "cRAW"),
        "stillimagequalitycf": (
            "Large Fine JPEG",
            "/main/imgsettings/imageformatcf",
            "Large Fine JPEG",
        ),
        "capturetarget": ("Memory card", "/main/settings/capturetarget", "Memory card"),
        "capturestorage": ("SD", "/main/capturesettings/storageid", "00020001"),
    }
    for key, (value, path, expected_value) in writes.items():
        session.set_setting(key, value)
        assert runner.values[path] == expected_value

    with pytest.raises(BridgeError) as rejected:
        session.set_setting("autopoweroff", "4294967295")
    assert rejected.value.code == "INVALID_SETTING_VALUE"
    assert runner.values["/main/settings/autopoweroff"] == "1800"
    assert settings["capturestorage"].value == "CFe"
    assert settings["capturestorage"].values == ["CFe", "SD"]
    assert "SET_CURRENT_STORAGE" in session.capabilities().evidence.advertised_commands
    assert "/main/capturesettings/storageid" in session.capabilities().evidence.writable_settings

    unsafe_runner = FakeRunner()
    unsafe_runner.values["/main/settings/autopoweroff"] = "4294967295"
    unsafe_session = GPhoto2Engine(unsafe_runner).open()
    unsafe_current = next(
        setting for setting in unsafe_session.capabilities().settings if setting.key == "autopoweroff"
    )
    assert unsafe_current.value == "-"


def test_gphoto2_text_metadata_is_advertised_with_a_bounded_text_contract() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()

    capabilities = session.capabilities()
    settings = {setting.key: setting for setting in capabilities.settings}
    for key in ("ownername", "artist", "copyright", "nickname"):
        setting = settings[key]
        assert setting.input_kind == "text"
        assert setting.max_length == 255
        assert setting.values == []
        assert f"/main/settings/{key}" in capabilities.evidence.writable_settings
    assert "Open EOS" not in capabilities.evidence.writable_settings

    session.set_setting("ownername", " Studio A ")
    assert runner.values["/main/settings/ownername"] == " Studio A "
    write_index = next(
        index
        for index, command in enumerate(runner.commands)
        if command[-2:] == ("--set-config-value", "/main/settings/ownername= Studio A ")
    )
    assert any(
        index > write_index and command[-1] == "--list-all-config"
        for index, command in enumerate(runner.commands)
    )
    assert (
        next(setting for setting in session.capabilities().settings if setting.key == "ownername").value
        == " Studio A "
    )

    session.set_setting("nickname", "")
    assert runner.values["/main/settings/nickname"] == ""


@pytest.mark.parametrize("value", ["é", "line\nfeed", "x" * 256])
def test_gphoto2_text_metadata_rejects_non_printable_or_oversized_values(value: str) -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()

    with pytest.raises(BridgeError) as rejected:
        session.set_setting("artist", value)

    assert rejected.value.code == "INVALID_SETTING_VALUE"
    assert runner.values["/main/settings/artist"] == "Jason"
    assert not any(command[0] == "--set-config-value" for command in runner.commands)


def test_gphoto2_text_metadata_rejects_missing_same_path_readback() -> None:
    class MissingReadbackRunner(FakeRunner):
        omit_owner = False

        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            command = self._without_camera(arguments)
            if command == ["--set-config-value", "/main/settings/ownername=Studio B"]:
                result = super().run(arguments, timeout=timeout)
                self.omit_owner = True
                return result
            return super().run(arguments, timeout=timeout)

        def _config_dump(self) -> str:
            output = super()._config_dump()
            if self.omit_owner:
                output = output.replace(
                    self._text("/main/settings/ownername", "Owner Name", readonly=False),
                    "",
                )
            return output

    runner = MissingReadbackRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()

    with pytest.raises(BridgeError) as rejected:
        session.set_setting("ownername", "Studio B")

    assert rejected.value.code == "SETTING_READBACK_MISSING"
    assert CameraFeature.ADVANCED_SETTINGS not in session.capabilities().evidence.observed_features


def test_gphoto2_text_metadata_rejects_truncated_or_mismatched_readback() -> None:
    class MismatchRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            command = self._without_camera(arguments)
            if command == ["--set-config-value", "/main/settings/artist=Studio C"]:
                result = super().run(arguments, timeout=timeout)
                self.values["/main/settings/artist"] = "Studio"
                return result
            return super().run(arguments, timeout=timeout)

    runner = MismatchRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()

    with pytest.raises(BridgeError) as rejected:
        session.set_setting("artist", "Studio C")

    assert rejected.value.code == "SETTING_READBACK_MISMATCH"
    assert CameraFeature.ADVANCED_SETTINGS not in session.capabilities().evidence.observed_features


def test_gphoto2_does_not_claim_unverified_immediate_camera_sleep() -> None:
    session = GPhoto2Engine(FakeRunner()).open()

    assert CameraFeature.CAMERA_SLEEP not in session.capabilities().supported
    assert CameraFeature.CAMERA_SLEEP in session.capabilities().planned
    with pytest.raises(BridgeError) as rejected:
        session.sleep_camera()

    assert rejected.value.code == "UNSUPPORTED_FEATURE"
    assert "verified immediate" in rejected.value.message


def test_auto_lighting_optimizer_hides_single_or_unknown_choices() -> None:
    class AloSnapshotRunner(FakeRunner):
        def __init__(self, choices: list[str]) -> None:
            super().__init__()
            self.choices = choices
            self.values["/main/capturesettings/alomode"] = choices[0]

        def _config_dump(self) -> str:
            original = self._radio(
                "/main/capturesettings/alomode",
                "Auto Lighting Optimizer",
                ["Standard", "Low", "High", "Off"],
            )
            replacement = self._radio(
                "/main/capturesettings/alomode",
                "Auto Lighting Optimizer",
                self.choices,
            )
            return super()._config_dump().replace(original, replacement)

    one_choice = GPhoto2Engine(AloSnapshotRunner(["x3"])).open().capabilities().settings
    assert not any(setting.key == "alomode" for setting in one_choice)

    mixed = GPhoto2Engine(AloSnapshotRunner(["Standard", "Firmware private", "High"])).open()
    alo = next(setting for setting in mixed.capabilities().settings if setting.key == "alomode")
    assert alo.values == ["Standard", "High"]

    with pytest.raises(BridgeError) as rejected:
        mixed.set_setting("alomode", "Firmware private")
    assert rejected.value.code == "INVALID_SETTING_VALUE"


def test_capture_storage_is_hidden_without_two_writable_identified_cards() -> None:
    class StorageRunner(FakeRunner):
        def __init__(self, storage_output: str) -> None:
            super().__init__()
            self.storage_output = storage_output

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if self._without_camera(arguments) == ["--storage-info"]:
                return CommandOutput(self.storage_output.encode())
            return super().run(arguments, timeout=timeout)

    variants = [
        STORAGE.replace("access=0 Read-Write", "access=1 Read-Only", 1),
        STORAGE.split("[Storage 1]", 1)[0],
        STORAGE.replace("basedir=/store_00020001", "basedir=/"),
    ]

    for output in variants:
        settings = GPhoto2Engine(StorageRunner(output)).open().capabilities().settings
        assert not any(setting.key == "capturestorage" for setting in settings)

    missing_current = StorageRunner(STORAGE)
    missing_current.values["/main/capturesettings/storageid"] = "00030001"
    assert not any(
        setting.key == "capturestorage"
        for setting in GPhoto2Engine(missing_current).open().capabilities().settings
    )


def test_capture_storage_revalidates_removed_card_without_writing() -> None:
    class MutableStorageRunner(FakeRunner):
        storage_output = STORAGE

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if self._without_camera(arguments) == ["--storage-info"]:
                return CommandOutput(self.storage_output.encode())
            return super().run(arguments, timeout=timeout)

    runner = MutableStorageRunner()
    session = GPhoto2Engine(runner).open()
    assert any(setting.key == "capturestorage" for setting in session.capabilities().settings)
    runner.storage_output = STORAGE.split("[Storage 1]", 1)[0]

    with pytest.raises(BridgeError) as rejected:
        session.set_setting("capturestorage", "SD")

    assert rejected.value.code == "INVALID_SETTING_VALUE"
    assert runner.values["/main/capturesettings/storageid"] == "00010001"
    assert not any("storageid=" in part for command in runner.commands for part in command)


def test_capture_storage_never_accepts_a_raw_storage_id_from_the_api() -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()

    with pytest.raises(BridgeError) as rejected:
        session.set_setting("capturestorage", "00020001")

    assert rejected.value.code == "INVALID_SETTING_VALUE"
    assert runner.values["/main/capturesettings/storageid"] == "00010001"
    assert not any("storageid=" in part for command in runner.commands for part in command)


def test_capture_storage_keeps_fallback_labels_bound_to_ids_after_reordering() -> None:
    duplicate_labels = STORAGE.replace("description=CFe", "description=Card").replace(
        "description=SD", "description=Card"
    )

    class ReorderingStorageRunner(FakeRunner):
        storage_output = duplicate_labels

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if self._without_camera(arguments) == ["--storage-info"]:
                return CommandOutput(self.storage_output.encode())
            return super().run(arguments, timeout=timeout)

    runner = ReorderingStorageRunner()
    session = GPhoto2Engine(runner).open()
    setting = next(setting for setting in session.capabilities().settings if setting.key == "capturestorage")
    assert setting.values == ["Card 1", "Card 2"]
    first, second = duplicate_labels.split("[Storage 1]", 1)
    runner.storage_output = "[Storage 1]" + second + first

    session.set_setting("capturestorage", "Card 2")

    assert runner.values["/main/capturesettings/storageid"] == "00020001"


def test_capture_storage_failed_write_preserves_current_value() -> None:
    class RejectingStorageRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            command = self._without_camera(arguments)
            if command and command[0] == "--set-config-value" and "storageid=" in command[1]:
                raise BridgeError("CAMERA_REQUEST_FAILED", "Storage write failed.", status_code=502)
            return super().run(arguments, timeout=timeout)

    runner = RejectingStorageRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()

    with pytest.raises(BridgeError, match="Storage write failed"):
        session.set_setting("capturestorage", "SD")

    assert runner.values["/main/capturesettings/storageid"] == "00010001"
    current = next(setting for setting in session.capabilities().settings if setting.key == "capturestorage")
    assert current.value == "CFe"


def test_capture_storage_refresh_failure_never_uses_stale_card_data() -> None:
    class FailingRefreshRunner(FakeRunner):
        fail_storage = False

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            command = self._without_camera(arguments)
            if command == ["--storage-info"] and self.fail_storage:
                raise BridgeError("CAMERA_REQUEST_FAILED", "Storage refresh failed.", status_code=502)
            return super().run(arguments, timeout=timeout)

    runner = FailingRefreshRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()
    runner.fail_storage = True

    with pytest.raises(BridgeError, match="Storage refresh failed"):
        session.set_setting("capturestorage", "SD")

    assert runner.values["/main/capturesettings/storageid"] == "00010001"
    assert not any("storageid=" in part for command in runner.commands for part in command)


def test_capture_storage_config_refresh_failure_never_uses_stale_widget_data() -> None:
    class FailingConfigRunner(FakeRunner):
        fail_config = False

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            command = self._without_camera(arguments)
            if command == ["--list-all-config"] and self.fail_config:
                raise BridgeError("CAMERA_REQUEST_FAILED", "Config refresh failed.", status_code=502)
            return super().run(arguments, timeout=timeout)

    runner = FailingConfigRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()
    runner.fail_config = True

    with pytest.raises(BridgeError, match="Config refresh failed"):
        session.set_setting("capturestorage", "SD")

    assert runner.values["/main/capturesettings/storageid"] == "00010001"
    assert not any("storageid=" in part for command in runner.commands for part in command)


def test_media_thumbnail_requires_gphoto2_advertised_ability(tmp_path: Path) -> None:
    class NoThumbnailRunner(FakeRunner):
        def __init__(self) -> None:
            super().__init__()
            self.values["/main/settings/capturetarget"] = "Memory card"

        def _config_dump(self) -> str:
            return super()._config_dump().replace("Choice: 0 Internal RAM\n", "")

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if arguments[-1:] == ["--abilities"]:
                output = ABILITIES.replace(
                    "File preview (thumbnail) support : yes",
                    "File preview (thumbnail) support : no",
                )
                return CommandOutput(output.encode())
            return super().run(arguments, timeout=timeout)

    runner = NoThumbnailRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    item = session.list_media()[0]

    with pytest.raises(BridgeError) as failure:
        session.media_thumbnail(item.id)

    assert CameraFeature.MEDIA_THUMBNAIL not in session.capabilities().supported
    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert not any("--get-thumbnail" in command for command in runner.commands)


def test_media_delete_requires_gphoto2_advertised_ability(tmp_path: Path) -> None:
    class NoDeleteRunner(FakeRunner):
        def __init__(self) -> None:
            super().__init__()
            self.values["/main/settings/capturetarget"] = "Memory card"

        def _config_dump(self) -> str:
            return super()._config_dump().replace("Choice: 0 Internal RAM\n", "")

        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if arguments[-1:] == ["--abilities"]:
                output = ABILITIES.replace(
                    "Delete selected files on camera  : yes",
                    "Delete selected files on camera  : no",
                )
                return CommandOutput(output.encode())
            return super().run(arguments, timeout=timeout)

    runner = NoDeleteRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    item = session.list_media()[0]

    with pytest.raises(BridgeError) as failure:
        session.delete_media(item.id)

    assert CameraFeature.MEDIA_DELETE not in session.capabilities().supported
    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert not any("--delete-file" in command for command in runner.commands)


def test_gphoto2_media_upload_requires_summary_and_writable_storage(tmp_path: Path) -> None:
    class NoUploadSummaryRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if arguments[-1:] == ["--summary"]:
                return CommandOutput(SUMMARY.replace("File Upload", "File Transfer").encode())
            return super().run(arguments, timeout=timeout)

    class ReadOnlyStorageRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            if arguments[-1:] == ["--storage-info"]:
                return CommandOutput(STORAGE.replace("access=0 Read-Write", "access=1 Read-Only").encode())
            return super().run(arguments, timeout=timeout)

    for runner in (NoUploadSummaryRunner(), ReadOnlyStorageRunner()):
        session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
        assert CameraFeature.MEDIA_UPLOAD not in session.capabilities().supported
        with pytest.raises(BridgeError) as failure:
            session.upload_media("NOPE.JPG", tmp_path / "nope.jpg", 4, "image/jpeg")
        assert failure.value.code == "UNSUPPORTED_FEATURE"
        assert not any("--upload-file" in command for command in runner.commands)


def test_gphoto2_media_upload_requires_exact_post_upload_readback(tmp_path: Path) -> None:
    class DropUploadRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0):
            command = self._without_camera(arguments)
            if "--upload-file" in command:
                self.commands.append(tuple(arguments))
                return CommandOutput(b"Uploaded file successfully.\n")
            return super().run(arguments, timeout=timeout)

    runner = DropUploadRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "upload.jpg"
    source.write_bytes(b"four")

    with pytest.raises(BridgeError, match="did not report exactly one") as failure:
        session.upload_media("MISSING.JPG", source, 4, "image/jpeg")

    assert failure.value.code == "UPLOAD_VERIFY_FAILED"
    upload_command = next(command for command in runner.commands if "--upload-file" in command)
    assert list(upload_command[2:]) == [
        "--folder",
        "/store_00010001",
        "--filename",
        "MISSING.JPG",
        "--upload-file",
        str(source.resolve()),
    ]


def test_gphoto2_media_upload_rejects_casefold_filename_collision(tmp_path: Path) -> None:
    runner = FakeRunner()
    runner.uploaded_files[("/store_00010001", "EXISTING.JPG")] = b"existing"
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "replacement.jpg"
    source.write_bytes(b"replacement")

    with pytest.raises(BridgeError) as failure:
        session.upload_media("existing.jpg", source, len(b"replacement"), "image/jpeg")

    assert failure.value.code == "MEDIA_ALREADY_EXISTS"
    assert runner.uploaded_files[("/store_00010001", "EXISTING.JPG")] == b"existing"
    assert not any(
        "--upload-file" in command and "existing.jpg" in command
        for command in runner.commands
    )


def test_gphoto2_media_upload_finishes_readback_after_process_commit(tmp_path: Path) -> None:
    class LateCancellationRunner(FakeRunner):
        def run_cancellable(self, arguments, *, timeout, cancelled):
            output = super().run_cancellable(arguments, timeout=timeout, cancelled=cancelled)
            cancelled.set()
            return output

    runner = LateCancellationRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "committed.jpg"
    source.write_bytes(b"committed")
    cancelled = threading.Event()

    uploaded = session.upload_media(
        "COMMITTED.JPG",
        source,
        len(b"committed"),
        "image/jpeg",
        cancelled,
    )

    assert cancelled.is_set()
    assert uploaded.name == "COMMITTED.JPG"
    assert uploaded.size_bytes == len(b"committed")


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
