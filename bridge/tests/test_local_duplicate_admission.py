from __future__ import annotations

from pathlib import Path
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraDescriptor

from .fakes import FakeRunner


@pytest.mark.parametrize("engine_name", ["libgphoto2", "edsdk"])
@pytest.mark.parametrize("request_engine", ["explicit", "auto"])
def test_active_explicit_camera_is_rejected_before_provider_open(engine_name: str, request_engine: str) -> None:
    camera = CameraDescriptor(
        id=f"synthetic-{engine_name}", model="Synthetic", port="usb:synthetic", engine=engine_name,
    )
    opened = []

    def open_session(camera_id, _profile):
        assert camera_id == camera.id
        session = Mock(engine_name=engine_name, camera=camera)
        opened.append(session)
        return session

    engine = Mock()
    engine.name = engine_name
    engine.health.return_value = (True, "synthetic", "Synthetic engine")
    engine.discover.return_value = [camera]
    engine.open.side_effect = open_session
    app = create_app(engine=engine, edsdk_engine=engine, ccapi_engine=Mock(), token="test-token")
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        first = client.post("/v1/session", headers=headers, json={"engine": engine_name, "cameraId": camera.id})
        assert first.status_code == 201
        requested = engine_name if request_engine == "explicit" else "auto"
        duplicate = client.post("/v1/session", headers=headers, json={"engine": requested, "cameraId": camera.id})
        assert duplicate.status_code == 409
        assert duplicate.json()["error"]["code"] == "CAMERA_BUSY"
        assert engine.open.call_count == 1, "Duplicate admission reopened the active camera before refusal."
        assert len(opened) == 1
        opened[0].close.assert_not_called()
        assert app.state.session_manager.get(first.json()["id"]) is opened[0]
        assert engine.health.call_count == (1 if request_engine == "auto" else 0)
        assert engine.discover.call_count == (1 if request_engine == "auto" else 0)


@pytest.mark.parametrize("engine_name", ["libgphoto2", "edsdk"])
def test_distinct_explicit_camera_and_unspecified_default_keep_provider_selection(engine_name: str) -> None:
    selections = []

    def open_session(camera_id, _profile):
        identifier = camera_id if camera_id is not None else "synthetic-provider-default"
        selections.append(identifier)
        # An EDSDK descriptor's provider port is not a camera-ID alias, even if
        # its text happens to match the next explicitly selected camera ID.
        port = "synthetic-second" if engine_name == "edsdk" else "usb:synthetic"
        return Mock(
            engine_name=engine_name,
            camera=CameraDescriptor(id=identifier, model="Synthetic", port=port, engine=engine_name),
        )

    engine = Mock()
    engine.name = engine_name
    engine.open.side_effect = open_session
    app = create_app(engine=engine, edsdk_engine=engine, ccapi_engine=Mock(), token="test-token")
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        for request in (
            {"engine": engine_name, "cameraId": "synthetic-first"},
            {"engine": engine_name, "cameraId": "synthetic-second"},
            {"engine": "auto"},
        ):
            created = client.post("/v1/session", headers=headers, json=request)
            assert created.status_code == 201
        assert selections == ["synthetic-first", "synthetic-second", "synthetic-provider-default"]
        assert engine.open.call_count == 3
        engine.health.assert_not_called()
        engine.discover.assert_not_called()


@pytest.mark.parametrize("request_engine", ["libgphoto2", "auto"])
def test_real_gphoto_port_alias_rejects_active_camera_before_reopen(tmp_path: Path, request_engine: str) -> None:
    engine = GPhoto2Engine(FakeRunner(), capture_directory=tmp_path)
    engine.open = Mock(wraps=engine.open)
    app = create_app(engine=engine, edsdk_engine=engine, ccapi_engine=Mock(), token="test-token")
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        first = client.post("/v1/session", headers=headers, json={"engine": "libgphoto2"})
        assert first.status_code == 201
        descriptor = first.json()["camera"]
        assert descriptor["id"] != descriptor["port"]
        engine.discover = Mock(wraps=engine.discover)
        duplicate = client.post(
            "/v1/session", headers=headers,
            json={"engine": request_engine, "cameraId": descriptor["port"]},
        )
        assert duplicate.status_code == 409
        assert duplicate.json()["error"]["code"] == "CAMERA_BUSY"
        assert engine.open.call_count == 1, "Raw port alias reopened the active gphoto2 camera."
        assert engine.discover.call_count == (1 if request_engine == "auto" else 0)
        assert not app.state.session_manager.get(first.json()["id"])._closed
