from __future__ import annotations

from collections.abc import Callable

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.edsdk import EdsdkEngine
from open_eos_bridge.edsdk_contract import EDSDK_PROVIDER_API_VERSION
from open_eos_bridge.edsdk_loader import load_edsdk_provider
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraDescriptor, CameraInfo

from .fakes import FakeRunner


class _EntryPoint:
    name = "test-provider"

    def __init__(self, target: Callable[[], object] | object) -> None:
        self.target = target

    def load(self) -> object:
        return self.target


class _Session:
    engine_name = "edsdk"

    def __init__(self, camera: CameraDescriptor) -> None:
        self.camera = camera
        self.closed = False

    def close(self) -> None:
        self.closed = True

    def info(self) -> CameraInfo:
        return CameraInfo(
            model=self.camera.model,
            serial="TEST-EDSDK-SERIAL",
            api="desktop-bridge/v1/edsdk",
            manufacturer="Canon",
            engine_version="test-provider 1.0",
        )


class _Provider:
    api_version = EDSDK_PROVIDER_API_VERSION
    provider_name = "test-provider"
    provider_version = "1.0"

    def __init__(self, *, available: bool = True) -> None:
        self.available = available
        self.camera = CameraDescriptor(
            id="edsdk-test-camera",
            model="Canon EOS R6 Mark III",
            port="licensed-provider",
            engine="edsdk",
        )
        self.open_count = 0
        self.sessions: list[_Session] = []

    def health(self) -> tuple[bool, str | None]:
        return self.available, None if self.available else "Provider runtime is unavailable."

    def discover(self) -> list[CameraDescriptor]:
        return [self.camera]

    def open(self, camera_id: str | None = None, profile_hint: str | None = None) -> _Session:
        del profile_hint
        assert camera_id in {None, self.camera.id}
        self.open_count += 1
        session = _Session(self.camera)
        self.sessions.append(session)
        return session


def test_loader_fails_closed_when_provider_is_missing_or_ambiguous() -> None:
    missing = load_edsdk_provider([])
    ambiguous = load_edsdk_provider([_EntryPoint(_Provider), _EntryPoint(_Provider)])

    assert missing.provider is None
    assert "No licensed EDSDK provider" in missing.detail
    assert ambiguous.provider is None
    assert "More than one" in ambiguous.detail


def test_loader_validates_contract_version_without_leaking_load_error_text() -> None:
    class OldProvider(_Provider):
        api_version = 0

    def broken_factory() -> object:
        raise RuntimeError("provider-private-detail")

    old = load_edsdk_provider([_EntryPoint(OldProvider)])
    broken = load_edsdk_provider([_EntryPoint(broken_factory)])

    assert old.provider is None
    assert "API version must be 1" in old.detail
    assert broken.provider is None
    assert "RuntimeError" in broken.detail
    assert "provider-private-detail" not in broken.detail


def test_provider_health_detail_is_not_exposed() -> None:
    provider = _Provider(available=False)
    provider.health = lambda: (False, "provider-private-detail")

    available, _, detail = EdsdkEngine(provider).health()

    assert available is False
    assert detail == "The licensed EDSDK provider reported unavailable."
    assert "provider-private-detail" not in detail


def test_unavailable_provider_is_reported_without_breaking_gphoto2() -> None:
    provider = _Provider(available=False)
    app = create_app(
        engine=GPhoto2Engine(FakeRunner()),
        edsdk_engine=EdsdkEngine(provider),
        token="test-token",
    )
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(app) as client:
        health = client.get("/health")
        cameras = client.get("/v1/cameras", headers=headers)

    assert health.json()["engines"]["edsdk"]["available"] is False
    assert [camera["engine"] for camera in cameras.json()["cameras"]] == ["libgphoto2"]


def test_provider_is_reported_and_selected_by_discovered_camera_id() -> None:
    provider = _Provider()
    app = create_app(
        engine=GPhoto2Engine(FakeRunner()),
        edsdk_engine=EdsdkEngine(provider),
        token="test-token",
    )
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(app) as client:
        health = client.get("/health")
        cameras = client.get("/v1/cameras", headers=headers)
        created = client.post(
            "/v1/session",
            headers=headers,
            json={"engine": "auto", "cameraId": provider.camera.id},
        )
        session_id = created.json()["id"]
        info = client.get(f"/v1/session/{session_id}/info", headers=headers)
        deleted = client.delete(f"/v1/session/{session_id}", headers=headers)

    assert health.json()["engines"]["edsdk"] == {
        "available": True,
        "version": "test-provider 1.0",
        "detail": "Licensed EDSDK provider ready.",
    }
    assert {camera["engine"] for camera in cameras.json()["cameras"]} == {"libgphoto2", "edsdk"}
    assert created.status_code == 201
    assert created.json()["engine"] == "edsdk"
    assert info.json()["api"] == "desktop-bridge/v1/edsdk"
    assert deleted.status_code == 204
    assert provider.sessions[0].closed is True


def test_different_local_usb_engines_cannot_own_sessions_at_the_same_time() -> None:
    provider = _Provider()
    app = create_app(
        engine=GPhoto2Engine(FakeRunner()),
        edsdk_engine=EdsdkEngine(provider),
        token="test-token",
    )
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(app) as client:
        gphoto = client.post("/v1/session", headers=headers, json={"engine": "libgphoto2"})
        edsdk = client.post(
            "/v1/session",
            headers=headers,
            json={"engine": "edsdk", "cameraId": provider.camera.id},
        )
        client.delete(f"/v1/session/{gphoto.json()['id']}", headers=headers)

    assert gphoto.status_code == 201
    assert edsdk.status_code == 409
    assert edsdk.json()["error"]["code"] == "CAMERA_BUSY"
    assert edsdk.json()["error"]["engine"] == "edsdk"
    assert provider.open_count == 0


def test_invalid_provider_camera_descriptor_is_rejected() -> None:
    provider = _Provider()
    provider.camera = provider.camera.model_copy(update={"id": "shared-id"})
    engine = EdsdkEngine(provider)

    available, _, _ = engine.health()

    assert available is True
    with pytest.raises(BridgeError) as failure:
        engine.discover()

    assert failure.value.code == "INVALID_PROVIDER_RESPONSE"


def test_malformed_discovery_and_session_fail_closed() -> None:
    provider = _Provider()
    provider.discover = lambda: None

    with pytest.raises(BridgeError) as discovery_failure:
        EdsdkEngine(provider).discover()

    provider = _Provider()
    provider.open = lambda camera_id=None, profile_hint=None: object()
    with pytest.raises(BridgeError) as session_failure:
        EdsdkEngine(provider).open(provider.camera.id)

    assert discovery_failure.value.code == "INVALID_PROVIDER_RESPONSE"
    assert session_failure.value.code == "INVALID_PROVIDER_RESPONSE"
