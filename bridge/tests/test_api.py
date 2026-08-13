from __future__ import annotations

import asyncio
import importlib
import tempfile
import threading
from pathlib import Path

from fastapi.testclient import TestClient
from starlette.requests import Request

from open_eos_bridge.app import _run_media_upload, create_app
from open_eos_bridge.edsdk import EdsdkEngine
from open_eos_bridge.edsdk_loader import EdsdkProviderLoadResult
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine, SubprocessGPhotoRunner
from open_eos_bridge.models import CameraDescriptor
from open_eos_bridge.rtp import RtpAudioChunk

from .fakes import JPEG, MEDIA_BYTES, THUMBNAIL, FakeRunner


def test_media_upload_disconnect_signals_server_worker_cancellation(tmp_path: Path) -> None:
    stopped = threading.Event()

    def upload(filename, source, size_bytes, content_type, cancelled):
        del filename, source, size_bytes, content_type
        if not cancelled.wait(timeout=2.0):
            raise AssertionError("Upload worker never received cancellation.")
        stopped.set()
        raise BridgeError("UPLOAD_CANCELLED", "Upload cancelled.", status_code=409)

    async def disconnected() -> dict[str, str]:
        return {"type": "http.disconnect"}

    async def exercise() -> None:
        request = Request({"type": "http", "method": "POST", "path": "/"}, disconnected)
        source = tmp_path / "payload"
        source.write_bytes(b"x")
        try:
            await _run_media_upload(request, upload, "PHOTO.JPG", source, 1, "image/jpeg")
        except BridgeError as error:
            assert error.code == "UPLOAD_CANCELLED"
        else:
            raise AssertionError("Disconnected upload unexpectedly succeeded.")

    asyncio.run(exercise())
    assert stopped.is_set()


def test_desktop_control_ui_and_assets_are_served_without_api_credentials() -> None:
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        page = client.get("/")
        script = client.get("/app/app.js")
        media_transfer = client.get("/app/media-transfer.js")
        styles = client.get("/app/styles.css")
        icon = client.get("/app/app-icon.png")
        protected_api = client.get("/v1/cameras")

    assert page.status_code == 200
    assert "Open EOS Control" in page.text
    assert 'src="/app/app.js"' in page.text
    assert "default-src 'self'" in page.headers["content-security-policy"]
    assert script.status_code == 200
    assert script.headers["content-type"].startswith(("text/javascript", "application/javascript"))
    assert "Bearer ${state.token}" in script.text
    assert "CAMERA_CLOCK_SYNC" in script.text
    assert "/clock/sync" in script.text
    assert "SENSOR_CLEANING" in script.text
    assert "/maintenance/sensor-cleaning" in script.text
    assert "CAMERA_SLEEP" in script.text
    assert "/power/sleep" in script.text
    assert "DIRECTORY_CONTROL" in script.text
    assert "/directories" in script.text
    assert "MEDIA_UPLOAD" in script.text
    assert "XMLHttpRequest" in script.text
    assert "media?filename=" in script.text
    assert media_transfer.status_code == 200
    assert media_transfer.headers["content-type"].startswith(("text/javascript", "application/javascript"))
    assert "readResponse" in media_transfer.text
    assert styles.status_code == 200
    assert styles.headers["content-type"].startswith("text/css")
    assert icon.status_code == 200
    assert icon.headers["content-type"].startswith("image/png")
    assert protected_api.status_code == 401


def test_media_upload_api_streams_exact_body_and_cleans_staging_file(monkeypatch) -> None:
    bridge_app = importlib.import_module("open_eos_bridge.app")
    runner = FakeRunner()
    created_staging_paths: list[Path] = []
    real_temporary_directory = tempfile.TemporaryDirectory

    def tracked_temporary_directory(*args, **kwargs):
        directory = real_temporary_directory(*args, **kwargs)
        created_staging_paths.append(Path(directory.name))
        return directory

    monkeypatch.setattr(bridge_app.tempfile, "TemporaryDirectory", tracked_temporary_directory)
    auth_headers = {"Authorization": "Bearer test-token"}
    headers = {
        **auth_headers,
        "Content-Type": "image/jpeg",
        "Content-Length": "12",
    }
    payload = b"api-upload!!"

    with TestClient(create_app(engine=GPhoto2Engine(runner), token="test-token")) as client:
        created = client.post("/v1/session", headers=auth_headers, json={"engine": "auto"})
        session_id = created.json()["id"]
        uploaded = client.post(
            f"/v1/session/{session_id}/media",
            params={"filename": "API-UPLOAD.JPG"},
            headers=headers,
            content=payload,
        )
        too_short = client.post(
            f"/v1/session/{session_id}/media",
            params={"filename": "SHORT.JPG"},
            headers={**headers, "Content-Length": "13"},
            content=payload,
        )
        unsafe = client.post(
            f"/v1/session/{session_id}/media",
            params={"filename": "../escape.JPG"},
            headers=headers,
            content=payload,
        )

    assert uploaded.status_code == 201
    assert uploaded.json()["name"] == "API-UPLOAD.JPG"
    assert uploaded.json()["sizeBytes"] == len(payload)
    assert runner.uploaded_files[("/store_00010001", "API-UPLOAD.JPG")] == payload
    assert too_short.status_code == 400
    assert too_short.json()["error"]["code"] == "UPLOAD_BODY_TRUNCATED"
    assert unsafe.status_code == 422
    assert unsafe.json()["error"]["code"] == "INVALID_UPLOAD_FILENAME"
    assert created_staging_paths
    assert all(not path.exists() for path in created_staging_paths)


def test_authenticated_rtp_audio_endpoint_returns_bounded_pcm_and_timeout() -> None:
    session = _AudioSession()
    headers = {"Authorization": "Bearer test-token"}
    app = create_app(
        engine=GPhoto2Engine(FakeRunner()),
        ccapi_engine=_AudioEngine(session),
        token="test-token",
    )
    with TestClient(app) as client:
        created = client.post(
            "/v1/session",
            headers=headers,
            json={"engine": "ccapi", "ccapiUrl": "http://192.0.2.1:8080"},
        )
        session_id = created.json()["id"]
        unauthorized = client.get(f"/v1/session/{session_id}/liveview/audio")
        pcm = client.get(
            f"/v1/session/{session_id}/liveview/audio?after=4&timeoutMs=250",
            headers=headers,
        )
        timeout = client.get(
            f"/v1/session/{session_id}/liveview/audio?after=5&timeoutMs=0",
            headers=headers,
        )

    assert unauthorized.status_code == 401
    assert pcm.status_code == 200
    assert pcm.content == b"\x00\x00\x01\x00"
    assert pcm.headers["content-type"] == "audio/pcm;rate=48000;channels=2;format=s16le"
    assert pcm.headers["x-open-eos-audio-generation"] == "5"
    assert pcm.headers["x-open-eos-audio-sample-rate"] == "48000"
    assert pcm.headers["x-open-eos-audio-channels"] == "2"
    assert pcm.headers["x-open-eos-audio-frames"] == "1"
    assert pcm.headers["x-open-eos-audio-discontinuity"] == "0"
    assert timeout.status_code == 204
    assert session.reads == [(4, 0.25), (5, 0.0)]


class _AudioEngine:
    name = "ccapi"

    def __init__(self, session: _AudioSession) -> None:
        self.session = session

    def open_connection(self, base_url: str, username: str = "", password: str = "") -> _AudioSession:
        assert base_url == "http://192.0.2.1:8080"
        assert username == ""
        assert password == ""
        return self.session


class _AudioSession:
    engine_name = "ccapi"
    camera = CameraDescriptor(id="rtp-audio-test", model="Canon test camera", port="network", engine="ccapi")

    def __init__(self) -> None:
        self.reads: list[tuple[int, float]] = []

    def live_view_audio(self, after_generation: int = 0, timeout: float = 1.0) -> RtpAudioChunk | None:
        self.reads.append((after_generation, timeout))
        if after_generation >= 5:
            return None
        return RtpAudioChunk(b"\x00\x00\x01\x00", 5, 48_000, 2, 1)

    def close(self) -> None:
        pass


def test_bridge_contract_runs_end_to_end_through_gphoto2_adapter() -> None:
    runner = FakeRunner()
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(create_app(engine=GPhoto2Engine(runner), token="test-token")) as client:
        health = client.get("/health")
        unauthorized = client.get("/v1/cameras")
        cameras = client.get("/v1/cameras", headers=headers)
        created = client.post("/v1/session", headers=headers, json={"engine": "auto"})
        session_id = created.json()["id"]

        info = client.get(f"/v1/session/{session_id}/info", headers=headers)
        status = client.get(f"/v1/session/{session_id}/status", headers=headers)
        capabilities = client.get(f"/v1/session/{session_id}/capabilities", headers=headers)
        event = client.get(f"/v1/session/{session_id}/events", headers=headers)
        event_stopped = client.delete(f"/v1/session/{session_id}/events", headers=headers)
        setting = client.post(
            f"/v1/session/{session_id}/settings/iso",
            headers=headers,
            json={"value": "800"},
        )
        text_setting = client.post(
            f"/v1/session/{session_id}/settings/ownername",
            headers=headers,
            json={"value": " Studio A "},
        )
        storage_setting = client.post(
            f"/v1/session/{session_id}/settings/capturestorage",
            headers=headers,
            json={"value": "SD"},
        )
        clock_sync = client.post(f"/v1/session/{session_id}/clock/sync", headers=headers)
        bulb_started = client.post(f"/v1/session/{session_id}/bulb/start", headers=headers)
        bulb_stopped = client.post(f"/v1/session/{session_id}/bulb/stop", headers=headers)
        autofocus = client.post(f"/v1/session/{session_id}/focus/auto", headers=headers)
        live_start = client.post(
            f"/v1/session/{session_id}/liveview/start",
            headers=headers,
            json={"fps": 15, "size": "MEDIUM", "source": "DESKTOP_BRIDGE_STREAM"},
        )
        live_frame = client.get(f"/v1/session/{session_id}/liveview/frame", headers=headers)
        unsupported_audio = client.get(f"/v1/session/{session_id}/liveview/audio", headers=headers)
        magnification = client.post(
            f"/v1/session/{session_id}/liveview/magnification",
            headers=headers,
            json={"value": 5},
        )
        invalid_magnification = client.post(
            f"/v1/session/{session_id}/liveview/magnification",
            headers=headers,
            json={"value": 2},
        )
        focus = client.post(
            f"/v1/session/{session_id}/focus/drive",
            headers=headers,
            json={"direction": "FAR", "step": "LARGE"},
        )
        unsupported_tap = client.post(
            f"/v1/session/{session_id}/focus/tap",
            headers=headers,
            json={"x": 0.25, "y": 0.75},
        )
        media = client.get(f"/v1/session/{session_id}/media", headers=headers)
        media_id = media.json()["items"][0]["id"]
        media_info = client.get(f"/v1/session/{session_id}/media/{media_id}/info", headers=headers)
        thumbnail = client.get(f"/v1/session/{session_id}/media/{media_id}/thumbnail", headers=headers)
        preview = client.get(f"/v1/session/{session_id}/media/{media_id}/preview", headers=headers)
        download = client.get(f"/v1/session/{session_id}/media/{media_id}", headers=headers)
        media_deleted = client.delete(f"/v1/session/{session_id}/media/{media_id}", headers=headers)
        deleted = client.delete(f"/v1/session/{session_id}", headers=headers)

    assert health.status_code == 200
    assert health.json()["engines"]["libgphoto2"]["available"] is True
    assert unauthorized.status_code == 401
    assert cameras.json()["cameras"][0]["model"] == "Canon EOS R6 Mark III"
    assert created.status_code == 201
    assert info.json()["serial"] == "TEST-SERIAL-0001"
    assert status.json()["battery"]["level"] == 82
    assert "LIVE_VIEW" in capabilities.json()["supported"]
    assert "MEDIA_DELETE" in capabilities.json()["supported"]
    assert "MEDIA_THUMBNAIL" in capabilities.json()["supported"]
    assert "MEDIA_PREVIEW" in capabilities.json()["supported"]
    assert "LIVE_VIEW_MAGNIFICATION" in capabilities.json()["supported"]
    assert "EVENT_POLLING" in capabilities.json()["supported"]
    assert "CAMERA_CLOCK_SYNC" in capabilities.json()["supported"]
    assert media.json()["items"][0]["previewAvailable"] is True
    assert capabilities.json()["evidence"]["source"] == (
        "gphoto2 --abilities + --list-all-config + --storage-info + --wait-event probe"
    )
    assert "CAPTURE_IMAGE" in capabilities.json()["evidence"]["advertisedCommands"]
    assert "GPHOTO2_WAIT_EVENT" in capabilities.json()["evidence"]["advertisedCommands"]
    assert "SET_CURRENT_STORAGE" in capabilities.json()["evidence"]["advertisedCommands"]
    assert next(
        item for item in capabilities.json()["settings"] if item["key"] == "capturestorage"
    )["values"] == ["CFe", "SD"]
    owner_setting = next(item for item in capabilities.json()["settings"] if item["key"] == "ownername")
    assert owner_setting["inputKind"] == "text"
    assert owner_setting["maxLength"] == 255
    assert owner_setting["values"] == []
    assert event.json() == {"changedKeys": []}
    assert event_stopped.status_code == 204
    assert setting.json()["exposure"]["iso"] == "800"
    assert text_setting.status_code == 200
    assert runner.values["/main/settings/ownername"] == " Studio A "
    assert storage_setting.status_code == 200
    assert runner.values["/main/capturesettings/storageid"] == "00020001"
    assert clock_sync.status_code == 200
    assert runner.values["/main/actions/syncdatetimeutc"] == "1"
    assert bulb_started.json()["bulbExposureActive"] is True
    assert bulb_stopped.json()["bulbExposureActive"] is False
    assert autofocus.status_code == 200
    assert live_start.json() == {
        "active": True,
        "requestedFps": 15,
        "source": "DESKTOP_BRIDGE_STREAM",
    }
    assert live_frame.content == JPEG
    assert unsupported_audio.status_code == 409
    assert unsupported_audio.json()["error"]["feature"] == "LIVE_VIEW_RTP_AUDIO"
    assert magnification.json() == {"accepted": True, "value": 5}
    assert invalid_magnification.status_code == 422
    assert focus.json()["accepted"] is True
    assert unsupported_tap.status_code == 409
    assert unsupported_tap.json()["error"]["code"] == "UNSUPPORTED_FEATURE"
    assert media_info.status_code == 200
    assert media_info.json()["sizeBytes"] == 6
    assert media_info.json()["contentType"] == "image/jpeg"
    assert media_info.json()["previewAvailable"] is True
    assert thumbnail.content == THUMBNAIL
    assert thumbnail.headers["content-type"].startswith("image/jpeg")
    assert thumbnail.headers["cache-control"] == "private, no-store, max-age=0"
    assert preview.content == JPEG
    assert preview.headers["content-type"].startswith("image/jpeg")
    assert preview.headers["cache-control"] == "private, no-store, max-age=0"
    assert download.content == MEDIA_BYTES
    assert download.headers["content-type"].startswith("image/jpeg")
    assert media_deleted.status_code == 204
    assert deleted.status_code == 204


def test_host_ram_capture_runs_end_to_end_through_media_api(tmp_path: Path) -> None:
    headers = {"Authorization": "Bearer test-token"}
    engine = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path)

    with TestClient(create_app(engine=engine, token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        captured = client.post(f"/v1/session/{session_id}/capture/still", headers=headers)
        media = client.get(f"/v1/session/{session_id}/media", headers=headers)
        local_item = next(item for item in media.json()["items"] if item["id"].startswith("gphoto2-host:"))
        thumbnail = client.get(
            f"/v1/session/{session_id}/media/{local_item['id']}/thumbnail",
            headers=headers,
        )
        preview = client.get(
            f"/v1/session/{session_id}/media/{local_item['id']}/preview",
            headers=headers,
        )
        download = client.get(f"/v1/session/{session_id}/media/{local_item['id']}", headers=headers)
        deleted = client.delete(f"/v1/session/{session_id}/media/{local_item['id']}", headers=headers)

    assert captured.status_code == 200
    assert local_item["contentType"] == "image/jpeg"
    assert local_item["previewAvailable"] is True
    assert thumbnail.status_code == 200
    assert thumbnail.content.startswith(b"\xff\xd8")
    assert preview.status_code == 200
    assert preview.content.startswith(b"\xff\xd8")
    assert download.status_code == 200
    assert download.content.startswith(b"\xff\xd8")
    assert download.headers["content-length"] == str(local_item["sizeBytes"])
    assert deleted.status_code == 204
    assert not [path for path in tmp_path.iterdir() if path.is_file()]


def test_authenticated_video_playback_ticket_supports_head_range_and_revocation(tmp_path: Path) -> None:
    payload = b"0123456789-camera-video"
    source = tmp_path / "CLIP_0001.MP4"
    source.write_bytes(payload)
    engine = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path)
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(create_app(engine=engine, token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        media = client.get(f"/v1/session/{session_id}/media", headers=headers).json()["items"]
        video = next(item for item in media if item["name"] == source.name)
        ticket_path = f"/v1/session/{session_id}/media/{video['id']}/playback"

        unauthorized = client.post(ticket_path)
        issued = client.post(ticket_path, headers=headers)
        playback_url = issued.json()["url"]
        head = client.head(playback_url)
        partial = client.get(playback_url, headers={"Range": "bytes=3-9"})
        invalid = client.get(playback_url, headers={"Range": f"bytes={len(payload)}-"})
        revoked = client.delete(playback_url)
        after_revoke = client.get(playback_url)

    assert unauthorized.status_code == 401
    assert issued.status_code == 200
    assert issued.json()["expiresInSeconds"] == 900
    assert head.status_code == 200
    assert head.headers["accept-ranges"] == "bytes"
    assert head.headers["content-length"] == str(len(payload))
    assert head.headers["content-type"].startswith("video/mp4")
    assert partial.status_code == 206
    assert partial.content == payload[3:10]
    assert partial.headers["content-range"] == f"bytes 3-9/{len(payload)}"
    assert partial.headers["content-length"] == "7"
    assert partial.headers["content-type"].startswith("video/mp4")
    assert partial.headers["x-open-eos-range-mode"] == "staged-file"
    assert invalid.status_code == 416
    assert invalid.headers["content-range"] == f"bytes */{len(payload)}"
    assert revoked.status_code == 204
    assert after_revoke.status_code == 404


def test_video_playback_rejects_insufficient_temporary_storage_before_issuing_ticket(
    tmp_path: Path,
    monkeypatch,
) -> None:
    bridge_app = importlib.import_module("open_eos_bridge.app")
    source = tmp_path / "CLIP_0001.MP4"
    source.write_bytes(b"camera-video")
    engine = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path)
    headers = {"Authorization": "Bearer test-token"}
    usage = bridge_app.shutil.disk_usage(Path(tempfile.gettempdir()))
    monkeypatch.setattr(
        bridge_app.shutil,
        "disk_usage",
        lambda _: usage._replace(free=1),
    )

    with TestClient(create_app(engine=engine, token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        media = client.get(f"/v1/session/{session_id}/media", headers=headers).json()["items"]
        video = next(item for item in media if item["name"] == source.name)
        response = client.post(
            f"/v1/session/{session_id}/media/{video['id']}/playback",
            headers=headers,
        )

    assert response.status_code == 507
    assert response.json()["error"]["code"] == "MEDIA_PLAYBACK_STORAGE_UNAVAILABLE"


def test_media_response_rejects_truncated_camera_stream_before_headers() -> None:
    runner = FakeRunner()
    video_name = "CLIP_0001.MP4"
    camera_folder = "/store_00010001/DCIM/100CANON"
    runner.uploaded_files[(camera_folder, video_name)] = b"declared-video"
    runner.stream_payloads[(camera_folder, video_name)] = b"short"
    engine = GPhoto2Engine(runner)
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(create_app(engine=engine, token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        media = client.get(f"/v1/session/{session_id}/media", headers=headers).json()["items"]
        video = next(item for item in media if item["name"] == video_name)
        response = client.get(f"/v1/session/{session_id}/media/{video['id']}", headers=headers)

    assert response.status_code == 502
    assert response.json()["error"]["code"] == "MEDIA_LENGTH_MISMATCH"
    assert "truncated" in response.json()["error"]["message"]


def test_failed_video_playback_revokes_ticket() -> None:
    runner = FakeRunner()
    video_name = "CLIP_0001.MP4"
    camera_folder = "/store_00010001/DCIM/100CANON"
    runner.uploaded_files[(camera_folder, video_name)] = b"declared-video"
    runner.stream_payloads[(camera_folder, video_name)] = b"short"
    engine = GPhoto2Engine(runner)
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(create_app(engine=engine, token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        media = client.get(f"/v1/session/{session_id}/media", headers=headers).json()["items"]
        video = next(item for item in media if item["name"] == video_name)
        ticket = client.post(
            f"/v1/session/{session_id}/media/{video['id']}/playback",
            headers=headers,
        )
        playback_url = ticket.json()["url"]
        failed = client.get(playback_url)
        after_failure = client.get(playback_url)

    assert ticket.status_code == 200
    assert failed.status_code == 502
    assert failed.json()["error"]["code"] == "MEDIA_LENGTH_MISMATCH"
    assert after_failure.status_code == 404


def test_video_playback_reuses_verified_staging_for_multiple_ranges() -> None:
    runner = FakeRunner()
    video_name = "CLIP_0001.MP4"
    camera_folder = "/store_00010001/DCIM/100CANON"
    payload = b"0123456789abc"
    runner.uploaded_files[(camera_folder, video_name)] = payload
    runner.stream_payloads[(camera_folder, video_name)] = payload
    engine = GPhoto2Engine(runner)
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(create_app(engine=engine, token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        media = client.get(f"/v1/session/{session_id}/media", headers=headers).json()["items"]
        video = next(item for item in media if item["name"] == video_name)
        ticket = client.post(
            f"/v1/session/{session_id}/media/{video['id']}/playback",
            headers=headers,
        )
        playback_url = ticket.json()["url"]
        first = client.get(playback_url, headers={"Range": "bytes=0-3"})
        second = client.get(playback_url, headers={"Range": "bytes=4-7"})
        head = client.head(playback_url)
        client.delete(playback_url)

    upstream_reads = [
        command
        for command in runner.commands
        if "--get-file" in command and video_name in command
    ]
    assert first.status_code == 206
    assert first.content == payload[:4]
    assert second.status_code == 206
    assert second.content == payload[4:8]
    assert head.status_code == 200
    assert head.headers["content-length"] == str(len(payload))
    assert len(upstream_reads) == 1


def test_video_playback_ticket_rejects_images_and_expires_with_session(tmp_path: Path) -> None:
    image = tmp_path / "PHOTO_0001.JPG"
    image.write_bytes(JPEG)
    video = tmp_path / "CLIP_0001.MP4"
    video.write_bytes(b"camera-video")
    engine = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path)
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(create_app(engine=engine, token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        media = client.get(f"/v1/session/{session_id}/media", headers=headers).json()["items"]
        image_item = next(item for item in media if item["name"] == image.name)
        video_item = next(item for item in media if item["name"] == video.name)
        image_ticket = client.post(
            f"/v1/session/{session_id}/media/{image_item['id']}/playback",
            headers=headers,
        )
        issued = client.post(
            f"/v1/session/{session_id}/media/{video_item['id']}/playback",
            headers=headers,
        )
        client.delete(f"/v1/session/{session_id}", headers=headers)
        after_close = client.get(issued.json()["url"])

    assert image_ticket.status_code == 422
    assert image_ticket.json()["error"]["code"] == "MEDIA_NOT_VIDEO"
    assert after_close.status_code == 404


def test_camera_cannot_be_opened_by_two_bridge_sessions() -> None:
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        first = client.post("/v1/session", headers=headers, json={})
        second = client.post("/v1/session", headers=headers, json={})

    assert first.status_code == 201
    assert second.status_code == 409
    assert second.json()["error"]["code"] == "CAMERA_BUSY"


def test_bridge_reports_effective_fps_when_gphoto2_movie_stream_falls_back() -> None:
    class NoMovieRunner(FakeRunner):
        def open_stream(self, arguments: list[str], *, timeout: float = 300.0):
            self.commands.append(tuple(arguments))
            del timeout
            raise BridgeError("ENGINE_COMMAND_FAILED", "capture-movie is unavailable", status_code=502)

    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=GPhoto2Engine(NoMovieRunner()), token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        started = client.post(
            f"/v1/session/{session_id}/liveview/start",
            headers=headers,
            json={"fps": 30, "size": "MEDIUM", "source": "DESKTOP_BRIDGE_STREAM"},
        )

    assert started.json() == {
        "active": True,
        "requestedFps": 5,
        "source": "DESKTOP_BRIDGE_STREAM",
    }


def test_edsdk_request_is_explicitly_unavailable() -> None:
    headers = {"Authorization": "Bearer test-token"}
    edsdk = EdsdkEngine(
        load_result=EdsdkProviderLoadResult(provider=None, detail="No test provider is installed.")
    )
    with TestClient(
        create_app(engine=GPhoto2Engine(FakeRunner()), edsdk_engine=edsdk, token="test-token")
    ) as client:
        response = client.post("/v1/session", headers=headers, json={"engine": "edsdk"})

    assert response.status_code == 501
    assert response.json()["error"]["engine"] == "edsdk"


def test_gphoto2_sleep_route_is_explicitly_unsupported() -> None:
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        response = client.post(f"/v1/session/{session_id}/power/sleep", headers=headers)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "UNSUPPORTED_FEATURE"
    assert response.json()["error"]["feature"] == "CAMERA_SLEEP"


def test_gphoto2_sensor_cleaning_route_is_explicitly_unsupported() -> None:
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        response = client.post(
            f"/v1/session/{session_id}/maintenance/sensor-cleaning",
            headers=headers,
            json={"autoPowerOff": False},
        )

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "UNSUPPORTED_FEATURE"
    assert response.json()["error"]["feature"] == "SENSOR_CLEANING"


def test_request_validation_uses_stable_bridge_error_shape() -> None:
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        response = client.post(
            "/v1/session",
            headers=headers,
            json={"engine": "not-an-engine"},
        )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert "body.engine" in response.json()["error"]["message"]


def test_tap_focus_coordinates_are_validated_before_engine_dispatch() -> None:
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        created = client.post("/v1/session", headers=headers, json={})
        session_id = created.json()["id"]
        response = client.post(
            f"/v1/session/{session_id}/focus/tap",
            headers=headers,
            json={"x": 1.5, "y": 0.5},
        )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert "body.x" in response.json()["error"]["message"]


def test_health_stays_available_when_gphoto2_is_not_installed() -> None:
    engine = GPhoto2Engine(SubprocessGPhotoRunner("open-eos-control-missing-gphoto2"))
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=engine, token="test-token")) as client:
        health = client.get("/health")
        cameras = client.get("/v1/cameras", headers=headers)

    assert health.status_code == 200
    assert health.json()["engines"]["libgphoto2"]["available"] is False
    assert cameras.status_code == 503
    assert cameras.json()["error"]["code"] == "ENGINE_UNAVAILABLE"
