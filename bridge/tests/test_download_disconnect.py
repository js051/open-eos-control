"""HTTP disconnect must retire camera preparation, not merely the browser fetch."""

import asyncio
import sys
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.gphoto2 import GPhoto2Engine, GPhotoCommand, SubprocessByteStream

from .fakes import FakeRunner


@pytest.mark.parametrize("playback", [False, True])
@pytest.mark.parametrize("cancel_task", [False, True])
@pytest.mark.parametrize("queued", [False, True])
def test_disconnect_during_preparation_reaps_child_and_allows_new_download(
    tmp_path, monkeypatch, playback, cancel_task, queued
):
    import importlib

    app_module = importlib.import_module("open_eos_bridge.app")
    monkeypatch.setattr(app_module.tempfile, "tempdir", str(tmp_path))
    release = tmp_path / "release"
    peer = tmp_path / "peer.py"
    peer.write_text(
        "import pathlib,sys,time\np=pathlib.Path(sys.argv[1])\n"
        "while not p.exists(): time.sleep(.01)\n"
        "sys.stdout.buffer.write(b'declaredvideo')\n"
    )

    class Runner(FakeRunner):
        active = None

        def open_stream(self, arguments, *, timeout=300):
            self.active = SubprocessByteStream(
                GPhotoCommand((sys.executable, str(peer)), "native"), [str(release)], timeout=5
            )
            return self.active

        def stream(self, arguments, *, timeout=300):
            active = self.open_stream(arguments, timeout=timeout)
            try:
                yield from active
            finally:
                active.close()

    runner = Runner()
    runner.uploaded_files[("/store_00010001/DCIM/100CANON", "CANCEL.MP4")] = b"declaredvideo"
    app = create_app(engine=GPhoto2Engine(runner), token="test-token")
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        sid = client.post("/v1/session", headers=headers, json={}).json()["id"]
        items = client.get(f"/v1/session/{sid}/media", headers=headers).json()["items"]
        item = next(x for x in items if x["name"] == "CANCEL.MP4")
        path = f"/v1/session/{sid}/media/{item['id']}"
        download_path = path
        if playback:
            ticket = client.post(path + "/playback", headers=headers).json()
            path = ticket["url"]

        async def exercise():
            asyncio.get_running_loop().set_default_executor(ThreadPoolExecutor(max_workers=1))
            second = None
            disconnect = asyncio.Event()

            async def receive():
                await disconnect.wait()
                return {"type": "http.disconnect"}

            messages = []

            async def send(message):
                messages.append(message)

            scope = {
                "type": "http",
                "asgi": {"version": "3.0", "spec_version": "2.4"},
                "http_version": "1.1",
                "method": "GET",
                "scheme": "http",
                "path": path,
                "raw_path": path.encode(),
                "query_string": b"",
                "root_path": "",
                "headers": [(b"authorization", b"Bearer test-token")],
                "client": ("127.0.0.1", 1234),
                "server": ("testserver", 80),
            }
            task = asyncio.create_task(app(scope, receive, send))
            try:
                for _ in range(100):
                    if runner.active is not None:
                        break
                    await asyncio.sleep(0.01)
                assert runner.active is not None, "fixture child never started"
                assert runner.active._process.poll() is None, "fixture must block before cancellation"
                if queued:
                    second = asyncio.create_task(app(scope, receive, send))
                    await asyncio.sleep(0.05)
                if cancel_task:
                    task.cancel()
                    if second is not None:
                        second.cancel()
                else:
                    disconnect.set()
                for _ in range(100):
                    if task.done():
                        break
                    await asyncio.sleep(0.01)
                ended = task.done()
                reaped = runner.active._process.poll() is not None
                partials = list(tmp_path.glob("open-eos-media-*"))
            finally:
                release.touch()
                try:
                    await asyncio.wait_for(task, 3)
                except asyncio.CancelledError:
                    assert cancel_task
                if second is not None:
                    try:
                        await asyncio.wait_for(second, 3)
                    except asyncio.CancelledError:
                        assert cancel_task
            assert ended, "disconnect left server preparing camera file"
            assert reaped, "disconnect left camera subprocess running"
            assert not partials, "cancelled staging file survived"

        asyncio.run(exercise())
        response = client.get(download_path, headers=headers)
        assert response.status_code == 200, response.text
        assert response.content == b"declaredvideo"


def test_cancellation_closes_late_registration_and_does_not_touch_retired_owner():
    from open_eos_bridge.errors import BridgeError
    from open_eos_bridge.media_cancellation import MediaCancellation

    old, new = MediaCancellation(), MediaCancellation()
    closed = []

    def callback():
        closed.append("retired")

    old.register(callback)
    old.unregister(callback)
    old.cancel()
    with pytest.raises(BridgeError, match="cancelled"):
        old.register(lambda: closed.append("late"))
    new.check()
    assert closed == ["late"]


def test_wsl_uncertain_download_fences_all_later_runner_operations():
    from open_eos_bridge.errors import BridgeError
    from open_eos_bridge.gphoto2 import SubprocessGPhotoRunner

    runner = SubprocessGPhotoRunner(command=GPhotoCommand(("never-execute",), "wsl"))
    runner._download_cleanup_unconfirmed = True
    with pytest.raises(BridgeError) as error:
        runner.open_stream(["--get-file", "x"])
    assert error.value.code == "MEDIA_DOWNLOAD_CLEANUP_UNCONFIRMED"


def test_task_cancel_after_playback_cache_put_removes_ticket_and_file(tmp_path, monkeypatch):
    import importlib
    import threading

    from open_eos_bridge.media_streaming import MediaPlaybackCache

    app_module = importlib.import_module("open_eos_bridge.app")
    monkeypatch.setattr(app_module.tempfile, "tempdir", str(tmp_path))
    entered, release = threading.Event(), threading.Event()
    original = MediaPlaybackCache.put

    def held_put(self, *args, **kwargs):
        original(self, *args, **kwargs)
        entered.set()
        assert release.wait(3), "fixture put barrier was not released"

    monkeypatch.setattr(MediaPlaybackCache, "put", held_put)
    runner = FakeRunner()
    key = ("/store_00010001/DCIM/100CANON", "CANCEL.MP4")
    runner.uploaded_files[key] = runner.stream_payloads[key] = b"declaredvideo"
    app = create_app(engine=GPhoto2Engine(runner), token="test-token")
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        sid = client.post("/v1/session", headers=headers, json={}).json()["id"]
        items = client.get(f"/v1/session/{sid}/media", headers=headers).json()["items"]
        item = next(x for x in items if x["name"] == key[1])
        ticket = client.post(f"/v1/session/{sid}/media/{item['id']}/playback", headers=headers).json()
        path = ticket["url"]

        async def exercise():
            async def receive():
                await asyncio.Event().wait()

            async def send(message):
                pass

            scope = {
                "type": "http",
                "asgi": {"version": "3.0", "spec_version": "2.4"},
                "http_version": "1.1",
                "method": "GET",
                "scheme": "http",
                "path": path,
                "raw_path": path.encode(),
                "query_string": b"",
                "root_path": "",
                "headers": [],
                "client": ("127.0.0.1", 1234),
                "server": ("testserver", 80),
            }
            task = asyncio.create_task(app(scope, receive, send))
            try:
                for _ in range(100):
                    if entered.is_set():
                        break
                    await asyncio.sleep(0.01)
                assert entered.is_set()
                assert list(tmp_path.glob("open-eos-media-*")), "must prove cache file existed"
                task.cancel()
                await asyncio.sleep(0.05)
            finally:
                release.set()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(task, 3)

        asyncio.run(exercise())
        assert not list(tmp_path.glob("open-eos-media-*"))
        assert client.get(path).status_code == 404


@pytest.mark.parametrize("task_cancel", [False, True])
def test_close_failure_still_drains_preparation_and_preserves_cancellation(task_cancel):
    import importlib
    import threading

    from open_eos_bridge.errors import BridgeError

    app_module = importlib.import_module("open_eos_bridge.app")
    entered, closing, release, drained = (threading.Event() for _ in range(4))

    def prepare(owner):
        def close():
            closing.set()
            raise RuntimeError("controlled close failure")

        owner.register(close)
        entered.set()
        try:
            assert release.wait(3)
            owner.check()
        finally:
            drained.set()

    class Request:
        async def is_disconnected(self):
            return entered.is_set() and not task_cancel

    async def exercise():
        task = asyncio.create_task(app_module._run_media_preparation(Request(), prepare))
        try:
            for _ in range(100):
                if entered.is_set():
                    break
                await asyncio.sleep(0.01)
            assert entered.is_set()
            if task_cancel:
                task.cancel()
            for _ in range(100):
                if closing.is_set():
                    break
                await asyncio.sleep(0.01)
            assert closing.is_set()
            await asyncio.sleep(0.05)
            assert not task.done(), "close failure must not abandon the worker"
        finally:
            release.set()
        with pytest.raises(asyncio.CancelledError if task_cancel else BridgeError):
            await asyncio.wait_for(task, 3)
        assert drained.is_set()

    asyncio.run(exercise())


@pytest.mark.parametrize("invalid", ["identity", "duplicate", "rounded", "missing", "zero", "utf8"])
def test_download_rejects_nonexact_primary_receipt_before_starting_stream(invalid):
    from open_eos_bridge.gphoto2 import CommandOutput

    class Runner(FakeRunner):
        streams = 0

        def run(self, arguments, *, timeout=30):
            result = super().run(arguments, timeout=timeout)
            if "--show-info" not in arguments:
                return result
            text = result.text
            if invalid == "identity":
                text = text.replace("CANCEL.MP4", "OTHER.MP4")
            elif invalid == "duplicate":
                text = text.replace("Size: 13 byte(s)", "Size: 13 byte(s)\n  Size: 13 byte(s)")
            elif invalid == "rounded":
                text = text.replace("Size: 13 byte(s)", "Size: 1 KB")
            elif invalid == "missing":
                text = text.replace("  Size: 13 byte(s)\n", "")
            elif invalid == "zero":
                text = text.replace("Size: 13 byte(s)", "Size: 0 byte(s)")
            else:
                return CommandOutput(result.stdout + b"\xff")
            return CommandOutput(text.encode())

        def open_stream(self, arguments, *, timeout=300):
            self.streams += 1
            return super().open_stream(arguments, timeout=timeout)

    runner = Runner()
    key = ("/store_00010001/DCIM/100CANON", "CANCEL.MP4")
    runner.uploaded_files[key] = runner.stream_payloads[key] = b"declaredvideo"
    with TestClient(create_app(engine=GPhoto2Engine(runner))) as client:
        sid = client.post("/v1/session", json={}).json()["id"]
        items = client.get(f"/v1/session/{sid}/media").json()["items"]
        item = next(item for item in items if item["name"] == key[1])
        assert item["sizeBytes"] == 1024
        response = client.get(f"/v1/session/{sid}/media/{item['id']}")
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "INVALID_MEDIA_INFO"
        assert runner.streams == 0


def test_download_exact_receipt_still_rejects_truncated_payload():
    runner = FakeRunner()
    key = ("/store_00010001/DCIM/100CANON", "CANCEL.MP4")
    runner.uploaded_files[key] = b"declaredvideo"
    runner.stream_payloads[key] = b"short"
    with TestClient(create_app(engine=GPhoto2Engine(runner))) as client:
        sid = client.post("/v1/session", json={}).json()["id"]
        items = client.get(f"/v1/session/{sid}/media").json()["items"]
        item = next(item for item in items if item["name"] == key[1])
        response = client.get(f"/v1/session/{sid}/media/{item['id']}")
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "MEDIA_LENGTH_MISMATCH"
        assert "5 of 13 bytes" in response.json()["error"]["message"]
