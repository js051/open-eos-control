"""Synthetic upload inventory evidence; no physical-camera validation."""
from __future__ import annotations

from pathlib import Path

import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import CommandOutput, GPhoto2Engine
from open_eos_bridge.models import CameraFeature

from .fakes import FakeRunner

INVALID_INVENTORIES = [
    pytest.param(b"", id="empty"),
    pytest.param(b"unexpected output\n", id="unrecognized"),
    pytest.param(
        b"There are no files in folder '/store_00010001'.\nunexpected output\n", id="unknown-line-after-target"
    ),
    pytest.param(
        b"There is 1 file in folder '/store_00010001'.\n#1 \xff.JPG rd 4 B image/jpeg\n", id="invalid-utf8"
    ),
    pytest.param(b"There are no files in folder '/'.\n", id="destination-omitted"),
    pytest.param(b"#1 UPLOAD.JPG rd 4 B image/jpeg\n", id="headerless-row"),
    pytest.param(b"There is 1 file in folder '/store_00010001'.\n", id="incomplete-folder"),
    pytest.param(
        b"There is 1 file in folder '/store_00010001'.\n#1 malformed row\n", id="malformed-row"
    ),
    pytest.param(
        b"There are 2 files in folder '/store_00010001'.\n#1 UPLOAD.JPG rd 4 B image/jpeg\n",
        id="incomplete-matching-readback",
    ),
    pytest.param(
        b"There are no files in folder '/store_00010001'.\n"
        b"There are no files in folder '/store_00010001'.\n", id="repeated-folder"
    ),
    pytest.param(
        b"There are 2 files in folder '/store_00010001'.\n"
        b"#1 UPLOAD.JPG rd 4 B image/jpeg\n#2 UPLOAD.JPG rd 4 B image/jpeg\n", id="duplicate-identity"
    ),
    pytest.param(
        b"There are no files in folder '/store_00010001'.\n"
        b"#1 There are no files in folder '/shadow'.JPG rd 4 B image/jpeg\n", id="header-text-in-row"
    ),
]


@pytest.mark.parametrize("inventory", INVALID_INVENTORIES)
@pytest.mark.parametrize("invalid_call", [1, 2], ids=["admission", "readback"])
def test_upload_rejects_unproven_destination_inventory(
    tmp_path: Path, inventory: bytes, invalid_call: int,
) -> None:
    class UnprovenInventoryRunner(FakeRunner):
        def __init__(self) -> None:
            super().__init__()
            self.inventory_calls = 0

        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--recurse", "--list-files"]:
                self.inventory_calls += 1
                if self.inventory_calls == invalid_call:
                    self.commands.append(tuple(arguments))
                    return CommandOutput(inventory)
            return super().run(arguments, timeout=timeout)

    runner = UnprovenInventoryRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"new!")

    with pytest.raises(BridgeError) as failure:
        session.upload_media("UPLOAD.JPG", source, 4, "image/jpeg")

    assert failure.value.code == "MEDIA_LIST_INVALID_OUTPUT"
    assert sum("--upload-file" in command for command in runner.commands) == invalid_call - 1
    assert CameraFeature.MEDIA_UPLOAD not in session._observed


@pytest.mark.parametrize("has_host_capture", [False, True])
def test_upload_requires_camera_inventory_even_when_host_browsing_is_supported(
    tmp_path: Path, has_host_capture: bool,
) -> None:
    class NoCameraListingRunner(FakeRunner):
        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--folder", "/", "--no-recurse", "--list-files"]:
                raise BridgeError("ENGINE_COMMAND_FAILED", "Synthetic camera listing failure.")
            return super().run(arguments, timeout=timeout)

    runner = NoCameraListingRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    if has_host_capture:
        staging = session._capture_store.begin_capture()
        (staging / "HOST.JPG").write_bytes(b"synthetic-jpeg")
        session._capture_store.promote_capture(staging)
        assert session.list_media()
    source = tmp_path / "payload"
    source.write_bytes(b"new!")

    assert CameraFeature.MEDIA_UPLOAD not in session.capabilities().supported
    with pytest.raises(BridgeError) as failure:
        session.upload_media("UPLOAD.JPG", source, 4, "image/jpeg")

    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert not any("--upload-file" in command for command in runner.commands)


def test_upload_rejects_incomplete_collision_inventory_before_dispatch(tmp_path: Path) -> None:
    class IncompleteInventoryRunner(FakeRunner):
        def __init__(self) -> None:
            super().__init__()
            self.inventory_calls = 0
            self.uploaded_files[("/store_00010001", "EXISTING.JPG")] = b"old!"

        def run(self, arguments: list[str], *, timeout: float = 30.0) -> CommandOutput:
            if self._without_camera(arguments) == ["--recurse", "--list-files"]:
                self.inventory_calls += 1
                if self.inventory_calls == 1:
                    self.commands.append(tuple(arguments))
                    return CommandOutput(b"There is 1 file in folder '/store_00010001'.\n")
            return super().run(arguments, timeout=timeout)

    runner = IncompleteInventoryRunner()
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"new!")

    with pytest.raises(BridgeError) as failure:
        session.upload_media("EXISTING.JPG", source, 4, "image/jpeg")

    assert failure.value.code == "MEDIA_LIST_INVALID_OUTPUT"
    assert not any("--upload-file" in command for command in runner.commands)
    assert runner.uploaded_files[("/store_00010001", "EXISTING.JPG")] == b"old!"
