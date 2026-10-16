"""Real loopback HTTP stalls exercise the production urllib media path."""

import asyncio
import importlib
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, UrllibCcapiTransport
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraFeature

from .fakes import FakeRunner
from .liveview_jpeg_fixture import JPEG
from .test_ccapi import MEDIA, FakeCcapiTransport


@pytest.mark.parametrize("mode", ["ordinary", "single", "jpeg"])
@pytest.mark.parametrize("stall", ["headers", "body"])
@pytest.mark.parametrize("cancel_task", [False, True])
def test_ccapi_http_stall_cancel_releases_preparation_and_retry(tmp_path, monkeypatch, stall, cancel_task, mode):
    app_module = importlib.import_module("open_eos_bridge.app")
    monkeypatch.setattr(app_module.tempfile, "tempdir", str(tmp_path))
    entered, release = threading.Event(), threading.Event()
    requests = []
    payload = JPEG if mode == "jpeg" else MEDIA
    query = "" if mode == "ordinary" else "attempt=single" + ("&validation=jpeg-v1" if mode == "jpeg" else "")

    class Peer(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            requests.append(self.path)
            first = len(requests) == 1
            if first and stall == "headers":
                entered.set()
                assert release.wait(5), "fixture headers barrier unreleased"
            try:
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg" if mode == "jpeg" else "application/octet-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                if first and stall == "body":
                    self.wfile.write(payload[:1])
                    self.wfile.flush()
                    entered.set()
                    assert release.wait(5), "fixture body barrier unreleased"
                    self.wfile.write(payload[1:])
                else:
                    self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Peer)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    fake = FakeCcapiTransport()

    class Transport(UrllibCcapiTransport):
        def request(self, *args, **kwargs):
            return fake.request(*args, **kwargs)

    app = create_app(
        engine=GPhoto2Engine(FakeRunner()),
        ccapi_engine=CcapiEngine(lambda *_: Transport(), sleeper=lambda _: None),
        token="test-token",
    )
    headers = {"Authorization": "Bearer test-token"}
    try:
        with TestClient(app) as client:
            created = client.post(
                "/v1/session",
                headers=headers,
                json={"engine": "ccapi", "ccapiUrl": f"http://127.0.0.1:{server.server_port}"},
            )
            assert created.status_code == 201, created.text
            sid = created.json()["id"]
            item = client.get(f"/v1/session/{sid}/media", headers=headers).json()["items"][0]
            path = f"/v1/session/{sid}/media/{item['id']}"

            async def exercise():
                disconnect = asyncio.Event()

                async def receive():
                    await disconnect.wait()
                    return {"type": "http.disconnect"}

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
                    "query_string": query.encode(),
                    "root_path": "",
                    "headers": [(b"authorization", b"Bearer test-token")],
                    "client": ("127.0.0.1", 1234),
                    "server": ("testserver", 80),
                }
                task = asyncio.create_task(app(scope, receive, send))
                try:
                    for _ in range(100):
                        if entered.is_set():
                            break
                        await asyncio.sleep(0.01)
                    assert entered.is_set(), "fixture must reach actual HTTP stall"
                    assert not task.done()
                    if cancel_task:
                        task.cancel()
                    else:
                        disconnect.set()
                    for _ in range(100):
                        if task.done():
                            break
                        await asyncio.sleep(0.01)
                    ended = task.done()
                    partials = list(tmp_path.glob("open-eos-media-*"))
                finally:
                    release.set()
                    try:
                        await asyncio.wait_for(task, 3)
                    except asyncio.CancelledError:
                        assert cancel_task
                assert ended, "HTTP cancellation left camera preparation blocked"
                assert not partials
                assert len(requests) == 1, "cancel must not retry a media GET"

            asyncio.run(exercise())
            session = app.state.session_manager.get(sid)
            assert CameraFeature.MEDIA_DOWNLOAD not in session._observed
            response = client.get(path + ("?" + query if query else ""), headers=headers)
            assert response.status_code == 200
            assert response.content == payload
            assert len(requests) == 2
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(2)
