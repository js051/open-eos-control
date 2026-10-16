from __future__ import annotations

import json
import os
import queue
import signal
import sys
import threading
import time
from pathlib import Path
from unittest.mock import patch

import httpx
import pytest
from fastapi.testclient import TestClient

from open_eos_bridge import gphoto_listing
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine, GPhotoCommand, SubprocessGPhotoRunner, parse_media_list
from open_eos_bridge.models import CameraFeature, MediaItem

from .fakes import FakeRunner
from .test_media_listing import ACCEPT, app_for, serving, wait_until

PEER = Path(__file__).parent / "fixtures" / "gphoto_listing_peer.py"


def session_for(tmp_path, mode="success", count=3, host_mode="native"):
    session = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path / "captures").open()
    session.runner = SubprocessGPhotoRunner(command=GPhotoCommand(
        (sys.executable, str(PEER), mode, str(tmp_path), str(count)), host_mode,
    ))
    return session


def browse(session, *, limit=None, progress=lambda items: None, cancelled=lambda: False):
    return session.list_media_progressive(limit, on_progress=progress, cancelled=cancelled)


def run_background(session, **kwargs):
    result, errors = [], []

    def work():
        try:
            result.extend(browse(session, **kwargs))
        except BaseException as error:
            errors.append(error)

    worker = threading.Thread(target=work)
    worker.start()
    return worker, result, errors


def assert_stopped(worker, errors, code):
    worker.join(3)
    assert not worker.is_alive()
    assert len(errors) == 1 and isinstance(errors[0], BridgeError)
    assert errors[0].code == code


@pytest.mark.parametrize("limit,expected", [(None, 1101), (61, 61)])
def test_real_cli_first_progress_precedes_exit_and_recent_preserves_newest(tmp_path, limit, expected):
    session = session_for(tmp_path, count=1101)
    snapshots = []
    worker, result, errors = run_background(session, limit=limit, progress=lambda items: snapshots.append(list(items)))
    try:
        assert wait_until(lambda: bool(snapshots))
        assert worker.is_alive()
        assert snapshots[0][0].name == "測試_0001.JPG"
        assert snapshots[0][0].folder_label == "store_00010001/DCIM/100CANON"
        assert session._media_cache == {}
    finally:
        (tmp_path / "release").touch()
        worker.join(5)
    assert not worker.is_alive() and errors == []
    assert len(result) == expected
    assert result[0].name == "IMG_1101.JPG"
    assert snapshots[-1] == result
    assert set(session._media_cache) == {item.id for item in result}
    assert CameraFeature.MEDIA_BROWSER in session._observed
    assert json.loads((tmp_path / "arguments.json").read_text()) == [
        "--port", "usb:001,007", "--recurse", "--list-files",
    ]


@pytest.mark.parametrize("mode", ["success", "ignore-term", "stdout-eof", "stderr-flood"])
def test_cancel_stops_live_child_and_reaps_before_return(tmp_path, mode):
    session = session_for(tmp_path, mode=mode)
    cancelled = threading.Event()
    snapshots, processes = [], []
    original = gphoto_listing.subprocess.Popen

    def capture(*args, **kwargs):
        process = original(*args, **kwargs)
        processes.append(process)
        return process

    with patch.object(gphoto_listing.subprocess, "Popen", capture):
        worker, _, errors = run_background(session, cancelled=cancelled.is_set, progress=snapshots.append)
        assert wait_until(lambda: bool(snapshots))
        cancelled.set()
        assert_stopped(worker, errors, "MEDIA_LIST_CANCELLED")
    assert len(processes) == 1 and processes[0].poll() is not None
    if mode == "ignore-term" and os.name != "nt":
        assert processes[0].returncode == -signal.SIGKILL
    assert processes[0].stdout.closed and processes[0].stderr.closed
    assert session._media_cache == {}
    assert session._lock.acquire(timeout=0.2)
    session._lock.release()


def test_lock_wait_and_prelaunch_cancel_do_not_spawn(tmp_path):
    session = session_for(tmp_path)
    cancelled = threading.Event()
    with session._lock:
        worker, _, errors = run_background(session, cancelled=cancelled.is_set)
        cancelled.set()
        assert_stopped(worker, errors, "MEDIA_LIST_CANCELLED")
    assert not (tmp_path / "pid").exists()
    with pytest.raises(BridgeError, match="cancelled"):
        browse(session, cancelled=cancelled.is_set)
    assert not (tmp_path / "pid").exists()


@pytest.mark.parametrize("mode,code", [("success", "ENGINE_TIMEOUT"), ("stdout-eof", "ENGINE_TIMEOUT"),
                                      ("long-line", "MEDIA_LIST_RESOURCE_LIMIT"),
                                      ("invalid-utf8", "MEDIA_LIST_INVALID_OUTPUT")])
def test_timeout_and_resource_failures_are_bounded_and_do_not_commit(tmp_path, mode, code):
    session = session_for(tmp_path, mode=mode)
    stale = MediaItem(id="previous", name="previous.JPG", kind="image")
    session._media_cache = {stale.id: stale}
    with patch("open_eos_bridge.gphoto2.MEDIA_LIST_TIMEOUT_SECONDS", 0.25):
        started = time.monotonic()
        with pytest.raises(BridgeError) as failure:
            browse(session)
    assert failure.value.code == code
    assert time.monotonic() - started < 3
    assert session._media_cache == {stale.id: stale}


@pytest.mark.skipif(sys.platform != "linux", reason="Linux process-group and /proc fixture")
def test_exited_cli_descendant_retaining_pipes_cannot_hang_timeout(tmp_path):
    session = session_for(tmp_path, mode="inherited-pipe")
    with patch("open_eos_bridge.gphoto2.MEDIA_LIST_TIMEOUT_SECONDS", 0.4):
        started = time.monotonic()
        with pytest.raises(BridgeError) as failure:
            browse(session)
    assert failure.value.code == "ENGINE_TIMEOUT"
    assert time.monotonic() - started < 3
    pid = int((tmp_path / "child.pid").read_text())
    # An orphan may briefly be a reparented zombie; it must no longer execute.
    def stopped():
        stat = Path(f"/proc/{pid}/stat")
        return not stat.exists() or stat.read_text().split()[2] == "Z"
    assert wait_until(stopped)
    assert not any(thread.name.startswith("gphoto2-list-") for thread in threading.enumerate())


@pytest.mark.parametrize("mode,code", [("error", "ENGINE_COMMAND_FAILED"), ("duplicate", "MEDIA_LIST_INVALID_OUTPUT"),
                                      ("invalid", "MEDIA_LIST_INVALID_OUTPUT"),
                                      ("unrecognized", "MEDIA_LIST_INVALID_OUTPUT")])
def test_cli_failure_keeps_last_snapshot_without_committing_cache(tmp_path, mode, code):
    session = session_for(tmp_path, mode=mode)
    (tmp_path / "release").touch()
    snapshots = []
    with pytest.raises(BridgeError) as failure:
        browse(session, progress=snapshots.append)
    assert failure.value.code == code
    if mode in ("error", "duplicate"):
        assert len(snapshots[0]) == 1
    assert session._media_cache == {}


def test_callback_failure_cleans_up_process_and_preserves_cache(tmp_path):
    session = session_for(tmp_path)
    def fail(items):
        raise RuntimeError("fixture consumer failure")
    with pytest.raises(RuntimeError, match="fixture consumer failure"):
        browse(session, progress=fail)
    assert session._media_cache == {}
    assert session._lock.acquire(timeout=0.2)
    session._lock.release()


def test_direct_close_cancels_listing_before_waiting_on_session_lock(tmp_path):
    session = session_for(tmp_path)
    snapshots = []
    worker, _, errors = run_background(session, progress=snapshots.append)
    assert wait_until(lambda: bool(snapshots))
    session.close()
    assert_stopped(worker, errors, "MEDIA_LIST_CANCELLED")
    assert session._closed


def test_item_bound_applies_even_to_recent_and_output_bound_is_enforced(tmp_path):
    session = session_for(tmp_path)
    (tmp_path / "release").touch()
    with patch("open_eos_bridge.gphoto2.MAX_LIST_SNAPSHOT_ITEMS", 2), pytest.raises(BridgeError) as failure:
        browse(session, limit=1)
    assert failure.value.code == "MEDIA_LIST_RESOURCE_LIMIT"
    with patch.object(gphoto_listing, "MAX_LIST_OUTPUT_BYTES", 20), pytest.raises(BridgeError) as failure:
        browse(session)
    assert failure.value.code == "MEDIA_LIST_RESOURCE_LIMIT"
    assert session._media_cache == {}


def test_empty_and_host_only_preserve_legacy_results_and_emit_final_snapshot(tmp_path):
    session = session_for(tmp_path, mode="empty")
    snapshots = []
    assert browse(session, progress=snapshots.append) == []
    assert snapshots == [[]]
    fake = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path / "host").open()
    legacy = fake.list_media()
    assert browse(fake) == legacy
    fake._camera_media_supported = False
    assert browse(fake) == []


def test_incremental_parser_preserves_stable_folder_and_date_sort(tmp_path):
    session = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path).open()
    rows = """There are 2 files in folder '/card1/DCIM'.
#1 A.JPG rd 1 B image/jpeg
#2 B.JPG rd 1 B image/jpeg
There is 1 file in folder '/card2/DCIM'.
#3 C.JPG rd 1 B image/jpeg
"""
    session.runner.run_listing = lambda args, **kw: [kw["on_line"](line) for line in rows.splitlines()]
    assert browse(session) == parse_media_list(rows)


def test_wsl_failure_fences_existing_and_new_sessions_and_reports_health_recovery(tmp_path):
    session = session_for(tmp_path, host_mode="wsl")
    snapshots = []
    cancelled = threading.Event()
    worker, _, errors = run_background(session, progress=snapshots.append, cancelled=cancelled.is_set)
    assert wait_until(lambda: bool(snapshots))
    cancelled.set()
    assert_stopped(worker, errors, "MEDIA_LIST_CANCELLED")
    runner = session.runner
    with pytest.raises(BridgeError) as failure:
        session.list_media()
    assert failure.value.code == "MEDIA_LIST_CLEANUP_UNCONFIRMED"
    for action in (lambda: runner.run(["--version"]), lambda: runner.open_stream([]),
                   lambda: runner.run_cancellable([], timeout=1, cancelled=threading.Event()),
                   lambda: browse(session)):
        with pytest.raises(BridgeError) as failure:
            action()
        assert failure.value.code == "MEDIA_LIST_CLEANUP_UNCONFIRMED"
    with pytest.raises(BridgeError) as failure:
        GPhoto2Engine(runner).open()
    assert failure.value.code == "ENGINE_UNAVAILABLE"
    assert "restart the Bridge" in failure.value.message
    runner._wsl_probe = lambda _: None  # health must report the fence before any WSL probe.
    available, _, detail = runner.health()
    assert not available and "restart the Bridge" in detail


def test_wsl_prelaunch_cancel_does_not_fence_runner(tmp_path):
    session = session_for(tmp_path, host_mode="wsl", mode="empty")
    with pytest.raises(BridgeError):
        browse(session, cancelled=lambda: True)
    assert not session.runner._listing_cleanup_unconfirmed
    assert browse(session) == []


@pytest.mark.parametrize("mode,terminal", [("success", "complete"), ("error", "error")])
def test_actual_http_stream_publishes_partial_before_cli_exit_and_safe_terminal(tmp_path, mode, terminal):
    session = session_for(tmp_path, mode=mode)
    app = app_for(session)
    with serving(app) as base, httpx.Client(timeout=3, trust_env=False) as client:
        with client.stream("GET", f"{base}/v1/session/fixture/media?limit=61", headers=ACCEPT) as response:
            records = response.iter_lines()
            start, partial = json.loads(next(records)), json.loads(next(records))
            assert start["type"] == "start" and start["scope"] == "recent" and start["limit"] == 61
            assert partial["type"] == "snapshot" and partial["last"] and partial["total"] == 1
            assert not (tmp_path / "release").exists()
            (tmp_path / "release").touch()
            remaining = [json.loads(line) for line in records]
        assert remaining[-1]["type"] == terminal
        assert "synthetic-private" not in json.dumps(remaining)
        assert "x-open-eos-inventory" not in response.headers
        all_records = [start, partial, *remaining]
        assert [record["seq"] for record in all_records] == list(range(len(all_records)))
        assert sum(record["type"] in ("complete", "error") for record in all_records) == 1
    if terminal == "error":
        assert session._media_cache == {}


def test_actual_http_disconnect_reaps_worker_before_releasing_busy(tmp_path):
    session = session_for(tmp_path)
    app = app_for(session)
    with serving(app) as base, httpx.Client(timeout=3, trust_env=False) as client:
        with client.stream("GET", f"{base}/v1/session/fixture/media", headers=ACCEPT) as response:
            lines = response.iter_lines()
            assert json.loads(next(lines))["type"] == "start"
            assert json.loads(next(lines))["type"] == "snapshot"
            active = app.state.media_listings._active[id(session)]
        assert active.finished.wait(3)
        assert id(session) not in app.state.media_listings._active
        assert session._media_cache == {}
        (tmp_path / "release").touch()
        retry = client.get(f"{base}/v1/session/fixture/media", headers=ACCEPT)
        assert json.loads(retry.content.splitlines()[-1])["type"] == "complete"


def test_actual_http_retired_session_cancels_running_cli(tmp_path):
    session = session_for(tmp_path)
    app = app_for(session)
    with (
        serving(app) as base, httpx.Client(timeout=3, trust_env=False) as client,
        client.stream("GET", f"{base}/v1/session/fixture/media", headers=ACCEPT) as response,
    ):
        lines = response.iter_lines()
        next(lines)
        next(lines)
        active = app.state.media_listings._active[id(session)]
        app.state.session_manager._sessions.pop("fixture")
        assert active.finished.wait(3)
        assert list(lines) == []
    assert session._media_cache == {}


def test_gphoto_route_negotiation_keeps_json_and_rejects_strict(tmp_path):
    session = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path).open()
    with TestClient(app_for(session)) as client:
        legacy = client.get("/v1/session/fixture/media")
        progressive = client.get("/v1/session/fixture/media", headers=ACCEPT)
        strict = client.get("/v1/session/fixture/media?inventory=strict", headers=ACCEPT)
    assert legacy.headers["content-type"] == "application/json"
    assert [json.loads(line) for line in progressive.content.splitlines()][-1]["type"] == "complete"
    assert strict.status_code == 409 and strict.json()["error"]["code"] == "UNSUPPORTED_FEATURE"
    assert "x-open-eos-inventory" not in progressive.headers



def test_disconnect_reservation_and_wsl_fence_precede_actual_cleanup(tmp_path):
    session = session_for(tmp_path, host_mode="wsl")
    app = app_for(session)
    stop_entered, release_stop = threading.Event(), threading.Event()
    original = gphoto_listing._stop_listing_process

    def held_stop(process):
        stop_entered.set()
        assert release_stop.wait(4)
        original(process)

    with patch.object(gphoto_listing, "_stop_listing_process", held_stop):
        try:
            with serving(app) as base, httpx.Client(timeout=3, trust_env=False) as client:
                with client.stream("GET", f"{base}/v1/session/fixture/media", headers=ACCEPT) as response:
                    lines = response.iter_lines()
                    next(lines)
                    next(lines)
                    active = app.state.media_listings._active[id(session)]
                assert stop_entered.wait(2)
                assert not active.finished.is_set()
                assert session.runner._listing_cleanup_unconfirmed
                with pytest.raises(BridgeError) as failure:
                    session.runner.run(["--version"])
                assert failure.value.code == "MEDIA_LIST_CLEANUP_UNCONFIRMED"
                busy = client.get(f"{base}/v1/session/fixture/media", headers=ACCEPT)
                assert busy.status_code == 409 and busy.json()["error"]["code"] == "MEDIA_LIST_BUSY"
                release_stop.set()
                assert active.finished.wait(3)
                assert id(session) not in app.state.media_listings._active
        finally:
            release_stop.set()


def test_stderr_drain_is_bounded_and_does_not_block_listing(tmp_path):
    session = session_for(tmp_path, mode="stderr-flood")
    (tmp_path / "release").touch()
    snapshots = []
    assert len(browse(session, progress=snapshots.append)) == 3
    assert snapshots[0][0].name == "測試_0001.JPG"


def test_cancel_from_final_callback_does_not_commit_success(tmp_path):
    session = session_for(tmp_path)
    (tmp_path / "release").touch()
    cancelled = threading.Event()
    def progress(items):
        if len(items) == 3:
            cancelled.set()
    with pytest.raises(BridgeError) as failure:
        browse(session, progress=progress, cancelled=cancelled.is_set)
    assert failure.value.code == "MEDIA_LIST_CANCELLED" and session._media_cache == {}



@pytest.mark.parametrize("rows", [
    "There are 2 files in folder '/card'.\n#1 A.JPG rd 1 B image/jpeg\n",
    "There are no files in folder '/card'.\n#1 A.JPG rd 1 B image/jpeg\n",
    "There are 2 files in folder '/card'.\n#1 A.JPG rd 1 B image/jpeg\nThere are no files in folder '/next'.\n",
])
def test_success_exit_with_incomplete_or_inconsistent_folder_count_is_not_complete(tmp_path, rows):
    session = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path).open()
    session.runner.run_listing = lambda args, **kw: [kw["on_line"](line) for line in rows.splitlines()]
    with pytest.raises(BridgeError) as failure:
        browse(session)
    assert failure.value.code == "MEDIA_LIST_INVALID_OUTPUT" and session._media_cache == {}


def test_host_captures_merge_in_legacy_order_without_camera_fanout(tmp_path):
    session = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path).open()
    staging = session._capture_store.begin_capture()
    (staging / "host.JPG").write_bytes(b"synthetic-jpeg")
    session._capture_store.promote_capture(staging)
    expected = session.list_media()
    snapshots = []
    assert browse(session, progress=snapshots.append) == expected
    assert snapshots[0][0].id.startswith("gphoto2-host:")
    commands_before = len(session.runner.commands)
    session._camera_media_supported = False
    assert all(item.id.startswith("gphoto2-host:") for item in browse(session))
    assert len(session.runner.commands) == commands_before


def test_native_cleanup_error_fences_runner_after_reaping_fixture(tmp_path):
    session = session_for(tmp_path)
    original = gphoto_listing._stop_listing_process

    def failed_cleanup(process):
        original(process)
        raise OSError("synthetic cleanup uncertainty")

    with (patch.object(gphoto_listing, "_stop_listing_process", failed_cleanup),
          patch("open_eos_bridge.gphoto2.MEDIA_LIST_TIMEOUT_SECONDS", 0.2),
          pytest.raises(BridgeError) as failure):
        browse(session)
    assert failure.value.code == "MEDIA_LIST_CLEANUP_UNCONFIRMED"
    assert session.runner._listing_cleanup_unconfirmed
    with pytest.raises(BridgeError) as blocked:
        session.runner.run(["--version"])
    assert blocked.value.code == "MEDIA_LIST_CLEANUP_UNCONFIRMED"


@pytest.mark.parametrize("mode", ["error", "ignore-term"])
def test_wsl_nonzero_and_timeout_also_fence_runner(tmp_path, mode):
    session = session_for(tmp_path, mode=mode, host_mode="wsl")
    if mode == "error":
        (tmp_path / "release").touch()
    with (patch("open_eos_bridge.gphoto2.MEDIA_LIST_TIMEOUT_SECONDS", 0.2),
          pytest.raises(BridgeError)):
        browse(session)
    assert session.runner._listing_cleanup_unconfirmed


def test_missing_cli_does_not_claim_uncertain_cleanup(tmp_path):
    session = session_for(tmp_path, host_mode="wsl")
    session.runner.command = GPhotoCommand((str(tmp_path / "missing-synthetic-cli"),), "wsl")
    with pytest.raises(BridgeError) as failure:
        browse(session)
    assert failure.value.code == "ENGINE_UNAVAILABLE"
    assert not session.runner._listing_cleanup_unconfirmed


@pytest.mark.parametrize("available,error,result", [(3, 0, b"abc"), (0, 0, None),
                                                    (0, 109, b""), (0, 232, b"")])
def test_windows_pipe_inspection_never_reads_without_available_bytes(available, error, result):
    import ctypes
    from ctypes import wintypes
    from types import SimpleNamespace
    from unittest.mock import Mock

    def peek(handle, buffer, size, read, ready, left):
        assert handle == 123
        ctypes.cast(ready, ctypes.POINTER(wintypes.DWORD)).contents.value = available
        return not error

    peek_mock = Mock(side_effect=peek)
    read = Mock(return_value=b"abc")
    windows_os = SimpleNamespace(name="nt", read=read)
    with (patch.object(gphoto_listing, "os", windows_os),
          patch.dict(sys.modules, {"msvcrt": SimpleNamespace(get_osfhandle=lambda fd: 123)}),
          patch.object(ctypes, "WinDLL", return_value=SimpleNamespace(PeekNamedPipe=peek_mock), create=True),
          patch.object(ctypes, "get_last_error", return_value=error, create=True)):
        assert gphoto_listing._pipe_read(8) == result
    if available:
        read.assert_called_once_with(8, 3)
    else:
        read.assert_not_called()



def test_saturated_progress_queue_checks_cli_deadline_and_reaps_before_terminal(tmp_path):
    from starlette.requests import Request

    from open_eos_bridge.media_listing import MediaListingStreams

    session = session_for(tmp_path, count=1101)
    (tmp_path / "release").touch()
    owner = MediaListingStreams()
    response = owner.response(Request({"type": "http"}), "fixture", session, None,
                              session.list_media_progressive, lambda: True)
    active = response.listing
    active.started = True
    cleaned = threading.Event()
    original = gphoto_listing._stop_listing_process

    def cleanup(process):
        original(process)
        assert process.poll() is not None
        cleaned.set()

    with (patch.object(gphoto_listing, "_stop_listing_process", cleanup),
          patch("open_eos_bridge.gphoto2.MEDIA_LIST_TIMEOUT_SECONDS", 0.25)):
        worker = threading.Thread(target=active.run)
        worker.start()
        try:
            # Deliberately consume no HTTP frames until the process deadline fires.
            assert cleaned.wait(3)
            assert session._lock.acquire(timeout=0.2)
            session._lock.release()
            records = []
            while not active.finished.is_set() or not active.frames.empty():
                try:
                    records.append(json.loads(active.frames.get(timeout=0.1)))
                except queue.Empty:
                    continue
            worker.join(1)
            assert not worker.is_alive()
            assert records[-1]["type"] == "error" and records[-1]["code"] == "ENGINE_TIMEOUT"
            assert not any(record["type"] == "complete" for record in records)
            assert session._media_cache == {}
        finally:
            active.cancel()
            worker.join(3)
            owner.close()
