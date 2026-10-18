"""Pinned CLI-format receipts through the production upload path; no real camera."""
from __future__ import annotations

import threading
from pathlib import Path

import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import CommandOutput, GPhoto2Engine, _media_id
from open_eos_bridge.models import CameraFeature

from .fakes import FakeRunner

FOLDER = "/store_00010001"
NAME = "SYNTHETIC.JPG"
INFO_HEADER = f"Information on file '{NAME}' (folder '{FOLDER}'):\n"
INFO_SUFFIX = "Thumbnail:\n  None available.\nAudio data:\n  None available.\n"


def assert_one_write_and_exact_info(runner: FakeRunner) -> None:
    commands = [runner._without_camera(list(command)) for command in runner.commands]
    upload_index = next(index for index, command in enumerate(commands) if "--upload-file" in command)
    assert sum("--upload-file" in command for command in commands) == 1
    assert commands[upload_index + 1:] == [
        ["--recurse", "--list-files"],
        ["--folder", FOLDER, "--show-info", NAME],
    ]
    assert not any("--get-file" in command or "--delete-file" in command for command in commands)


@pytest.mark.parametrize("size", [1, 1023, 1024, 1025, 2047, 2048, 2049])
def test_upload_reads_exact_bytes_after_rounded_kb_listing(size: int, tmp_path: Path) -> None:
    runner = FakeRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"x" * size)

    uploaded = session.upload_media(NAME, source, size, "image/jpeg")

    assert uploaded.id == _media_id(FOLDER, NAME)
    assert uploaded.size_bytes == size
    assert uploaded.folder_id is not None and uploaded.folder_label == FOLDER.lstrip("/")
    assert session._media_cache[uploaded.id].size_bytes == size
    assert CameraFeature.MEDIA_UPLOAD in session.capabilities().evidence.observed_features
    assert_one_write_and_exact_info(runner)


@pytest.mark.parametrize("delta", [-1, 1])
def test_upload_rejects_one_byte_mismatch_within_same_rounded_kb(delta: int, tmp_path: Path) -> None:
    class ChangedBytesRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            result = super().run(arguments, timeout=timeout)
            if "--upload-file" in arguments:
                self.uploaded_files[(FOLDER, NAME)] = b"x" * (1026 + delta)
            return result

    runner = ChangedBytesRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"x" * 1026)

    with pytest.raises(BridgeError) as failure:
        session.upload_media(NAME, source, 1026, "image/jpeg")

    assert failure.value.code == "UPLOAD_VERIFY_SIZE_MISMATCH"
    assert f"{1026 + delta} bytes" in failure.value.message
    assert CameraFeature.MEDIA_UPLOAD not in session.capabilities().evidence.observed_features
    assert_one_write_and_exact_info(runner)


@pytest.mark.parametrize("output, expected_code", [
    ("File:\n  None available.\nThumbnail:\n  Size: 1024 byte(s)\nAudio data:\n", "UPLOAD_VERIFY_FAILED"),
    ("File:\n  Mime type: 'image/jpeg'\nThumbnail:\nAudio data:\n  Size: 1024 byte(s)\n", "UPLOAD_VERIFY_FAILED"),
    ("File:\n  Size: 1 KB\n" + INFO_SUFFIX, "UPLOAD_VERIFY_FAILED"),
    ("File:\n  Size: -1024 byte(s)\n" + INFO_SUFFIX, "UPLOAD_VERIFY_FAILED"),
    ("File:\n  Size: 1023 byte(s)\nThumbnail:\n  Size: 1024 byte(s)\nAudio data:\n", "UPLOAD_VERIFY_SIZE_MISMATCH"),
    ("unexpected output\n", "UPLOAD_VERIFY_FAILED"),
])
def test_upload_never_replaces_missing_or_wrong_primary_size_with_listing_or_preview(
    output: str, expected_code: str, tmp_path: Path,
) -> None:
    class InvalidPrimaryRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--folder", FOLDER, "--show-info", NAME]:
                self.commands.append(tuple(arguments))
                return CommandOutput((INFO_HEADER + output).encode())
            return super().run(arguments, timeout=timeout)

    runner = InvalidPrimaryRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"x" * 1024)

    with pytest.raises(BridgeError) as failure:
        session.upload_media(NAME, source, 1024, "image/jpeg")

    assert failure.value.code == expected_code
    assert CameraFeature.MEDIA_UPLOAD not in session.capabilities().evidence.observed_features
    assert_one_write_and_exact_info(runner)


def test_upload_info_failure_never_retries_or_grants_observed_receipt(tmp_path: Path) -> None:
    class InfoFailureRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--folder", FOLDER, "--show-info", NAME]:
                self.commands.append(tuple(arguments))
                raise BridgeError("ENGINE_TIMEOUT", "Synthetic readback timed out.")
            return super().run(arguments, timeout=timeout)

    runner = InfoFailureRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"x" * 1024)

    with pytest.raises(BridgeError) as failure:
        session.upload_media(NAME, source, 1024, "image/jpeg")

    assert failure.value.code == "ENGINE_TIMEOUT"
    assert CameraFeature.MEDIA_UPLOAD not in session.capabilities().evidence.observed_features
    assert_one_write_and_exact_info(runner)


def test_upload_finishes_exact_readback_after_committed_process_cancellation(tmp_path: Path) -> None:
    class LateCancellationRunner(FakeRunner):
        def run_cancellable(self, arguments, *, timeout, cancelled):
            result = super().run_cancellable(arguments, timeout=timeout, cancelled=cancelled)
            cancelled.set()
            return result

    runner = LateCancellationRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"x" * 1025)
    cancelled = threading.Event()

    uploaded = session.upload_media(NAME, source, 1025, "image/jpeg", cancelled)

    assert cancelled.is_set() and uploaded.size_bytes == 1025
    assert_one_write_and_exact_info(runner)


@pytest.mark.parametrize("output", [
    (INFO_HEADER + "File:\n  Size: 1023 byte(s)\n  Size: 1024 byte(s)\n" + INFO_SUFFIX).encode(),
    (INFO_HEADER + "File:\n  Size: invalid\n  Size: 1024 byte(s)\n" + INFO_SUFFIX).encode(),
    (INFO_HEADER + "File:\n  Size: 1023 byte(s)\nFile:\n  Size: 1024 byte(s)\n" + INFO_SUFFIX).encode(),
    (INFO_HEADER.replace(NAME, "OTHER.JPG") + "File:\n  Size: 1024 byte(s)\n" + INFO_SUFFIX).encode(),
    (INFO_HEADER.replace(FOLDER, "/store_00020001") + "File:\n  Size: 1024 byte(s)\n" + INFO_SUFFIX).encode(),
    INFO_HEADER.encode() + b"File:\n  Mime type: '\xff/jpeg'\n  Size: 1024 byte(s)\n" + INFO_SUFFIX.encode(),
    ("File:\n  Size: 1024 byte(s)\n" + INFO_SUFFIX).encode(),
    (INFO_HEADER + "Thumbnail:\n  None available.\nFile:\n  Size: 1024 byte(s)\nAudio data:\n").encode(),
    (INFO_HEADER + "File:\n  Unrecognized: value\n  Size: 1024 byte(s)\n" + INFO_SUFFIX).encode(),
], ids=["duplicate-size", "invalid-then-valid-size", "duplicate-file", "wrong-name", "wrong-folder",
        "invalid-utf8", "missing-header", "wrong-section-order", "unknown-primary-field"])
def test_upload_rejects_ambiguous_or_wrong_target_primary_info(output: bytes, tmp_path: Path) -> None:
    class AmbiguousInfoRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--folder", FOLDER, "--show-info", NAME]:
                self.commands.append(tuple(arguments))
                return CommandOutput(output)
            return super().run(arguments, timeout=timeout)

    runner = AmbiguousInfoRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"x" * 1024)

    with pytest.raises(BridgeError) as failure:
        session.upload_media(NAME, source, 1024, "image/jpeg")

    assert failure.value.code == "UPLOAD_VERIFY_FAILED"
    assert CameraFeature.MEDIA_UPLOAD not in session.capabilities().evidence.observed_features
    assert_one_write_and_exact_info(runner)
