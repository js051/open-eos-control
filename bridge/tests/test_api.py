from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine, SubprocessGPhotoRunner

from .fakes import JPEG, MEDIA_BYTES, THUMBNAIL, FakeRunner


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
    assert media_transfer.status_code == 200
    assert media_transfer.headers["content-type"].startswith(("text/javascript", "application/javascript"))
    assert "readResponse" in media_transfer.text
    assert styles.status_code == 200
    assert styles.headers["content-type"].startswith("text/css")
    assert icon.status_code == 200
    assert icon.headers["content-type"].startswith("image/png")
    assert protected_api.status_code == 401


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
    assert event.json() == {"changedKeys": []}
    assert event_stopped.status_code == 204
    assert setting.json()["exposure"]["iso"] == "800"
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
    assert magnification.json() == {"accepted": True, "value": 5}
    assert invalid_magnification.status_code == 422
    assert focus.json()["accepted"] is True
    assert unsupported_tap.status_code == 409
    assert unsupported_tap.json()["error"]["code"] == "UNSUPPORTED_FEATURE"
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
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        response = client.post("/v1/session", headers=headers, json={"engine": "edsdk"})

    assert response.status_code == 501
    assert response.json()["error"]["engine"] == "edsdk"


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
