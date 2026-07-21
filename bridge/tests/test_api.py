from __future__ import annotations

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.gphoto2 import GPhoto2Engine, SubprocessGPhotoRunner

from .fakes import JPEG, MEDIA_BYTES, FakeRunner


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
        setting = client.post(
            f"/v1/session/{session_id}/settings/iso",
            headers=headers,
            json={"value": "800"},
        )
        live_start = client.post(
            f"/v1/session/{session_id}/liveview/start",
            headers=headers,
            json={"fps": 15, "size": "MEDIUM", "source": "DESKTOP_BRIDGE_STREAM"},
        )
        live_frame = client.get(f"/v1/session/{session_id}/liveview/frame", headers=headers)
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
        download = client.get(f"/v1/session/{session_id}/media/{media_id}", headers=headers)
        deleted = client.delete(f"/v1/session/{session_id}", headers=headers)

    assert health.status_code == 200
    assert health.json()["engines"]["libgphoto2"]["available"] is True
    assert unauthorized.status_code == 401
    assert cameras.json()["cameras"][0]["model"] == "Canon EOS R6 Mark III"
    assert created.status_code == 201
    assert info.json()["serial"] == "TEST-SERIAL-0001"
    assert status.json()["battery"]["level"] == 82
    assert "LIVE_VIEW" in capabilities.json()["supported"]
    assert setting.json()["exposure"]["iso"] == "800"
    assert live_start.json() == {"active": True, "requestedFps": 5}
    assert live_frame.content == JPEG
    assert focus.json()["accepted"] is True
    assert unsupported_tap.status_code == 409
    assert unsupported_tap.json()["error"]["code"] == "UNSUPPORTED_FEATURE"
    assert download.content == MEDIA_BYTES
    assert download.headers["content-type"].startswith("image/jpeg")
    assert deleted.status_code == 204


def test_camera_cannot_be_opened_by_two_bridge_sessions() -> None:
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="test-token")) as client:
        first = client.post("/v1/session", headers=headers, json={})
        second = client.post("/v1/session", headers=headers, json={})

    assert first.status_code == 201
    assert second.status_code == 409
    assert second.json()["error"]["code"] == "CAMERA_BUSY"


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
