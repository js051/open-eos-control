from __future__ import annotations

import importlib
from pathlib import Path

import pytest

from open_eos_bridge.app import _stage_media
from open_eos_bridge.models import MediaItem


def test_completed_staging_removes_unowned_file_when_iterator_close_fails(tmp_path: Path, monkeypatch) -> None:
    bridge_app = importlib.import_module("open_eos_bridge.app")
    monkeypatch.setattr(bridge_app.tempfile, "tempdir", str(tmp_path))
    payload = b"complete-video"
    close_error = RuntimeError("Synthetic media iterator close failure.")
    closed = []

    class FailingCloseIterator:
        def __init__(self) -> None:
            self.chunks = iter([payload])

        def __iter__(self):
            return self

        def __next__(self) -> bytes:
            return next(self.chunks)

        def close(self) -> None:
            staged_paths = list(tmp_path.glob("open-eos-media-*.bin"))
            assert len(staged_paths) == 1
            assert staged_paths[0].read_bytes() == payload
            closed.append(True)
            raise close_error

    item = MediaItem(id="synthetic-video", name="CLIP.MP4", kind="video", size_bytes=len(payload))
    with pytest.raises(RuntimeError) as failure:
        _stage_media(item, FailingCloseIterator())

    assert failure.value is close_error
    assert closed == [True]
    assert not list(tmp_path.glob("open-eos-media-*.bin")), "Failed iterator close orphaned a completed staging file."


def test_iterator_construction_failure_does_not_allocate_staging(tmp_path: Path, monkeypatch) -> None:
    bridge_app = importlib.import_module("open_eos_bridge.app")
    monkeypatch.setattr(bridge_app.tempfile, "tempdir", str(tmp_path))
    original = RuntimeError("Synthetic iterator construction failure.")

    class InvalidChunks:
        def __iter__(self):
            raise original

    item = MediaItem(id="synthetic-video", name="CLIP.MP4", kind="video", size_bytes=10)
    with pytest.raises(RuntimeError) as failure:
        _stage_media(item, InvalidChunks())
    assert failure.value is original
    assert not list(tmp_path.glob("open-eos-media-*.bin"))


def test_failed_staging_preserves_length_error_when_iterator_close_also_fails(tmp_path: Path, monkeypatch) -> None:
    from open_eos_bridge.errors import BridgeError

    bridge_app = importlib.import_module("open_eos_bridge.app")
    monkeypatch.setattr(bridge_app.tempfile, "tempdir", str(tmp_path))
    closed = []

    class TruncatedChunks:
        def __iter__(self):
            return self

        def __next__(self):
            raise StopIteration

        def close(self):
            closed.append(True)
            raise RuntimeError("Synthetic secondary cleanup failure.")

    item = MediaItem(id="synthetic-video", name="CLIP.MP4", kind="video", size_bytes=10)
    with pytest.raises(BridgeError) as failure:
        _stage_media(item, TruncatedChunks())
    assert failure.value.code == "MEDIA_LENGTH_MISMATCH"
    assert closed == [True]
    assert not list(tmp_path.glob("open-eos-media-*.bin"))


def test_staging_allocation_failure_closes_iterator_and_preserves_error(monkeypatch) -> None:
    bridge_app = importlib.import_module("open_eos_bridge.app")
    original = OSError("Synthetic staging allocation failure.")
    closed = []

    class Chunks:
        def __iter__(self):
            return self

        def __next__(self):
            raise StopIteration

        def close(self):
            closed.append(True)
            raise RuntimeError("Synthetic secondary close failure.")

    def fail_allocation(**kwargs):
        raise original

    monkeypatch.setattr(bridge_app.tempfile, "mkstemp", fail_allocation)
    item = MediaItem(id="synthetic-video", name="CLIP.MP4", kind="video", size_bytes=10)
    with pytest.raises(OSError) as failure:
        _stage_media(item, Chunks())
    assert failure.value is original
    assert closed == [True]


def test_descriptor_wrapper_failure_releases_staging_and_iterator(tmp_path: Path, monkeypatch) -> None:
    bridge_app = importlib.import_module("open_eos_bridge.app")
    monkeypatch.setattr(bridge_app.tempfile, "tempdir", str(tmp_path))
    original = OSError("Synthetic descriptor wrapper failure.")
    real_close = bridge_app.os.close
    descriptors = []
    closed = []

    class Chunks:
        def __iter__(self):
            return self

        def __next__(self):
            raise StopIteration

        def close(self):
            closed.append(True)

    def fail_wrapper(descriptor, mode):
        raise original

    def record_close(descriptor):
        descriptors.append(descriptor)
        real_close(descriptor)

    monkeypatch.setattr(bridge_app.os, "fdopen", fail_wrapper)
    monkeypatch.setattr(bridge_app.os, "close", record_close)
    item = MediaItem(id="synthetic-video", name="CLIP.MP4", kind="video", size_bytes=10)
    with pytest.raises(OSError) as failure:
        _stage_media(item, Chunks())
    assert failure.value is original
    assert closed == [True]
    assert len(descriptors) == 1
    assert not list(tmp_path.glob("open-eos-media-*.bin"))
