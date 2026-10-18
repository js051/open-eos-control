"""Synthetic providers exercise the production API's unresolved USB ownership."""
from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.edsdk import EdsdkEngine
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraDescriptor

from .fakes import FakeRunner
from .test_bulb_request_ownership import Provider as RecoverySession
from .test_bulb_request_ownership import status
from .test_edsdk import _Provider, _Session


class FailedCleanupSession(_Session):
    def __init__(self, camera):
        super().__init__(camera)
        self.close_calls = 0
        self.allow_close = False

    def close(self):
        self.close_calls += 1
        if not self.allow_close:
            raise RuntimeError("provider-private-close-detail")
        super().close()


class WrongCameraProvider(_Provider):
    def __init__(self):
        super().__init__()
        self.health_calls = 0
        self.discover_calls = 0
        self.wrong_camera = True

    def health(self):
        self.health_calls += 1
        return super().health()

    def discover(self):
        self.discover_calls += 1
        return super().discover()

    def open(self, camera_id=None, profile_hint=None):
        if not self.wrong_camera:
            return super().open(camera_id, profile_hint)
        self.open_count += 1
        session = FailedCleanupSession(self.camera.model_copy(update={"id": "edsdk-other-camera"}))
        self.sessions.append(session)
        return session


@pytest.mark.parametrize("next_engine", ["edsdk", "libgphoto2", "auto"])
def test_rejected_provider_close_failure_blocks_another_local_open(next_engine):
    provider = WrongCameraProvider()
    runner = FakeRunner()
    app = create_app(engine=GPhoto2Engine(runner), edsdk_engine=EdsdkEngine(provider), token="test-token")
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        try:
            rejected = client.post("/v1/session", headers=headers,
                                   json={"engine": "edsdk", "cameraId": provider.camera.id})
            assert rejected.status_code == 502
            assert rejected.json()["error"]["code"] == "INVALID_PROVIDER_RESPONSE"
            assert provider.sessions[0].closed is False
            before_commands = list(runner.commands)
            before_provider = provider.health_calls, provider.discover_calls
            payload = {"engine": next_engine}
            if next_engine == "auto":
                payload["cameraId"] = provider.camera.id
            attempted = client.post("/v1/session", headers=headers, json=payload)
            assert attempted.status_code == 409
            assert attempted.json()["error"]["code"] == "CAMERA_BUSY"
            assert provider.open_count == 1
            assert runner.commands == before_commands
            assert (provider.health_calls, provider.discover_calls) == before_provider
            assert provider.sessions[0].close_calls == 2
            assert "provider-private-close-detail" not in rejected.text + attempted.text
        finally:
            for session in provider.sessions:
                session.allow_close = True


@pytest.fixture
def cleanup_api():
    provider = WrongCameraProvider()
    runner = FakeRunner()
    network = Mock()
    network.name = "ccapi"
    network.health.return_value = (True, "synthetic", None)
    network_session = _Session(CameraDescriptor(id="ccapi-test", model="Synthetic", port="network", engine="ccapi"))
    network_session.engine_name = "ccapi"
    network.open_connection.return_value = network_session
    app = create_app(engine=GPhoto2Engine(runner), edsdk_engine=EdsdkEngine(provider),
                     ccapi_engine=network, token="test-token")
    with TestClient(app) as client:
        client.headers["Authorization"] = "Bearer test-token"
        try:
            yield client, provider, runner
        finally:
            for session in provider.sessions:
                if isinstance(session, FailedCleanupSession):
                    session.allow_close = True


def reject_open(client, provider):
    rejected = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
    assert rejected.status_code == 502
    assert rejected.json()["error"]["code"] == "INVALID_PROVIDER_RESPONSE"
    return provider.sessions[-1]


def test_polling_does_not_retry_close_or_dispatch_local_discovery(cleanup_api):
    client, provider, runner = cleanup_api
    original = reject_open(client, provider)
    before_provider = provider.health_calls, provider.discover_calls
    before_commands = list(runner.commands)
    for _ in range(2):
        discovery = client.get("/v1/cameras")
        assert discovery.status_code == 409
        assert discovery.json()["error"]["code"] == "CAMERA_BUSY"
        assert runner.commands == before_commands
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["engines"]["edsdk"]["available"] is False
    assert original.close_calls == 1
    assert (provider.health_calls, provider.discover_calls) == before_provider


@pytest.mark.parametrize("next_engine", ["edsdk", "libgphoto2", "auto"])
def test_explicit_reconnect_retries_exact_owner_before_new_local_open(cleanup_api, next_engine):
    client, provider, _ = cleanup_api
    original = reject_open(client, provider)
    provider.wrong_camera = False
    original.allow_close = True
    payload = {"engine": next_engine}
    if next_engine != "libgphoto2":
        payload["cameraId"] = provider.camera.id
    created = client.post("/v1/session", json=payload)
    assert created.status_code == 201, created.text
    assert original.close_calls == 2
    assert original.closed is True
    assert provider.open_count == (1 if next_engine == "libgphoto2" else 2)
    assert client.delete(f"/v1/session/{created.json()['id']}").status_code == 204
    assert original.close_calls == 2


def test_ccapi_connection_does_not_retry_or_depend_on_local_cleanup(cleanup_api):
    client, provider, _ = cleanup_api
    original = reject_open(client, provider)
    created = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://camera.test"})
    assert created.status_code == 201
    identifier = created.json()["id"]
    assert client.get(f"/v1/session/{identifier}/info").status_code == 200
    assert client.delete(f"/v1/session/{identifier}").status_code == 204
    assert original.close_calls == 1
    assert original.closed is False


def test_registered_session_stop_and_delete_remain_available_during_quarantine(cleanup_api):
    client, provider, _ = cleanup_api
    existing = RecoverySession()
    existing.stop_recording = lambda: status()
    identifier = client.app.state.session_manager._register(existing).id
    assert client.post(f"/v1/session/{identifier}/bulb/start/owned", json={"ownerId": "one"}).status_code == 200
    original = reject_open(client, provider)
    stopped = client.post(f"/v1/session/{identifier}/bulb/stop/owned", json={"ownerId": "one"})
    assert stopped.status_code == 200
    assert stopped.json()["outcome"] == "released"
    assert client.post(f"/v1/session/{identifier}/recording/stop").status_code == 200
    assert client.delete(f"/v1/session/{identifier}").status_code == 204
    assert existing.calls == ["start", "stop", "close"]
    assert original.close_calls == 1


def test_shutdown_retries_exact_rejected_owner_and_keeps_failed_cleanup(cleanup_api):
    client, provider, _ = cleanup_api
    original = reject_open(client, provider)
    manager = client.app.state.session_manager
    with pytest.raises(BridgeError) as failed:
        manager.close_all()
    assert failed.value.code == "CAMERA_BUSY"
    assert original.close_calls == 2
    assert client.get("/v1/cameras").status_code == 409
    original.allow_close = True
    manager.close_all()
    assert original.close_calls == 3
    assert original.closed is True
    assert client.get("/v1/cameras").status_code == 200
    assert original.close_calls == 3


@pytest.mark.parametrize("next_operation", ["open", "discover"])
def test_inflight_rejected_close_blocks_later_local_admission(cleanup_api, next_operation):
    client, provider, runner = cleanup_api
    entered, proceed, attempted = threading.Event(), threading.Event(), threading.Event()
    original_open = provider.open

    def blocked_open(camera_id=None, profile_hint=None):
        session = original_open(camera_id, profile_hint)
        original_close = session.close

        def blocked_close():
            entered.set()
            assert proceed.wait(3), "Synthetic cleanup was not released"
            original_close()

        session.close = blocked_close
        return session

    provider.open = blocked_open

    def next_request():
        attempted.set()
        if next_operation == "open":
            return client.post("/v1/session", json={"engine": "libgphoto2"})
        return client.get("/v1/cameras")

    with ThreadPoolExecutor(max_workers=2) as pool:
        rejected = pool.submit(reject_open, client, provider)
        try:
            assert entered.wait(1)
            before_commands = list(runner.commands)
            next_result = pool.submit(next_request)
            assert attempted.wait(1)
            assert next_result.result(1).status_code == 409
            health = client.get("/health")
            assert health.status_code == 200
            assert health.json()["engines"]["edsdk"]["available"] is False
            assert not rejected.done()
            assert runner.commands == before_commands
        finally:
            proceed.set()
        original = rejected.result(2)
        assert next_result.result(2).status_code == 409
        assert runner.commands == before_commands
        assert provider.open_count == 1
        assert original.close_calls == 1


@pytest.mark.parametrize("cleanup_succeeds", [False, True])
def test_explicit_cleanup_retry_cannot_run_concurrently(cleanup_api, cleanup_succeeds):
    client, provider, runner = cleanup_api
    original = reject_open(client, provider)
    original.allow_close = cleanup_succeeds
    entered, proceed = threading.Event(), threading.Event()
    original_close = original.close

    def blocked_close():
        entered.set()
        assert proceed.wait(3), "Synthetic cleanup retry was not released"
        original_close()

    original.close = blocked_close
    before_commands = list(runner.commands)
    with ThreadPoolExecutor(max_workers=1) as pool:
        retry = pool.submit(client.post, "/v1/session", json={"engine": "libgphoto2"})
        try:
            assert entered.wait(1)
            for engine in ("edsdk", "auto", "libgphoto2"):
                refused = client.post("/v1/session", json={"engine": engine, "cameraId": provider.camera.id})
                assert refused.status_code == 409
                assert refused.json()["error"]["code"] == "CAMERA_BUSY"
            assert client.get("/v1/cameras").status_code == 409
            assert client.get("/health").status_code == 200
            assert runner.commands == before_commands
            assert original.close_calls == 1
            assert not retry.done()
        finally:
            proceed.set()
        assert retry.result(2).status_code == (201 if cleanup_succeeds else 409)
    assert original.close_calls == 2
    assert original.closed is cleanup_succeeds
    assert provider.open_count == 1


@pytest.mark.parametrize("response", [None, object()], ids=["none", "missing-close"])
def test_missing_cleanup_operation_cannot_clear_retained_owner(response):
    provider = WrongCameraProvider()
    provider.open = Mock(return_value=response)
    engine = EdsdkEngine(provider)
    runner = FakeRunner()
    app = create_app(engine=GPhoto2Engine(runner), edsdk_engine=engine)
    with pytest.raises(BridgeError) as shutdown, TestClient(app) as client:
        rejected = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
        assert rejected.status_code == 502
        before_provider = provider.health_calls, provider.discover_calls
        before_commands = list(runner.commands)
        for _ in range(3):
            retry = client.post("/v1/session", json={"engine": "auto", "cameraId": provider.camera.id})
            assert retry.status_code == 409
            assert retry.json()["error"]["code"] == "CAMERA_BUSY"
        assert client.get("/v1/cameras").status_code == 409
        assert len(engine._pending_cleanup) == 1
        assert engine._pending_cleanup[0] is response
        assert (provider.health_calls, provider.discover_calls) == before_provider
        assert runner.commands == before_commands
        provider.open.assert_called_once()
    assert shutdown.value.code == "CAMERA_BUSY"
    assert len(engine._pending_cleanup) == 1


def test_published_retirement_blocks_rejected_owner_retry(cleanup_api):
    client, provider, runner = cleanup_api
    provider.wrong_camera = False
    opened = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
    assert opened.status_code == 201
    active = provider.sessions[-1]
    provider.wrong_camera = True
    pending = reject_open(client, provider)
    entered = threading.Event()
    release = threading.Event()
    original_close = active.close

    def blocked_close():
        entered.set()
        assert release.wait(5)
        original_close()

    active.close = blocked_close
    with ThreadPoolExecutor(max_workers=1) as pool:
        deletion = pool.submit(client.delete, f"/v1/session/{opened.json()['id']}")
        try:
            assert entered.wait(2)
            before = (pending.close_calls, provider.health_calls,
                      provider.discover_calls, provider.open_count)
            commands = list(runner.commands)
            for engine in ("edsdk", "libgphoto2", "auto"):
                attempted = client.post("/v1/session", json={"engine": engine})
                assert attempted.status_code == 409
                assert attempted.json()["error"]["code"] == "CAMERA_BUSY"
            assert client.get("/v1/cameras").status_code == 409
            assert (pending.close_calls, provider.health_calls,
                    provider.discover_calls, provider.open_count) == before
            assert runner.commands == commands
        finally:
            release.set()
        assert deletion.result(timeout=2).status_code == 204
