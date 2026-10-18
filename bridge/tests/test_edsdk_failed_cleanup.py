"""Synthetic providers exercise the production API's unresolved USB ownership."""
from __future__ import annotations

import asyncio
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
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


@contextmanager
def quarantined_client(app):
    """Expect only shutdown's pending-owner error; never consume a body failure."""
    client = TestClient(app)
    client.__enter__()
    try:
        yield client
    finally:
        with pytest.raises(BridgeError) as shutdown:
            client.__exit__(None, None, None)
        assert shutdown.value.code == "CAMERA_BUSY"


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
    with quarantined_client(app) as client:
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
        assert provider.sessions[0].close_calls == 1
        assert "provider-private-close-detail" not in rejected.text + attempted.text
    assert provider.sessions[0].close_calls == 1


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
    with quarantined_client(app) as client:
        client.headers["Authorization"] = "Bearer test-token"
        yield client, provider, runner
    assert all(session.close_calls == 1 for session in provider.sessions if isinstance(session, FailedCleanupSession))


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
def test_explicit_reconnect_never_retries_failed_owner_even_if_later_close_would_succeed(cleanup_api, next_engine):
    client, provider, runner = cleanup_api
    original = reject_open(client, provider)
    provider.wrong_camera = False
    original.allow_close = True
    before_provider = provider.health_calls, provider.discover_calls
    before_commands = list(runner.commands)
    payload = {"engine": next_engine}
    if next_engine != "libgphoto2":
        payload["cameraId"] = provider.camera.id
    refused = client.post("/v1/session", json=payload)
    assert refused.status_code == 409
    assert refused.json()["error"]["code"] == "CAMERA_BUSY"
    assert "Reconnecting cannot clear" in refused.json()["error"]["message"]
    assert original.close_calls == 1
    assert original.closed is False
    assert provider.open_count == 1
    assert (provider.health_calls, provider.discover_calls) == before_provider
    assert runner.commands == before_commands


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


def test_shutdown_never_retries_or_clears_failed_rejected_owner(cleanup_api):
    client, provider, _ = cleanup_api
    original = reject_open(client, provider)
    manager = client.app.state.session_manager
    for later_close_would_succeed in (False, True):
        original.allow_close = later_close_would_succeed
        with pytest.raises(BridgeError) as failed:
            manager.close_all()
        assert failed.value.code == "CAMERA_BUSY"
        assert original.close_calls == 1
        assert original.closed is False
        assert client.get("/v1/cameras").status_code == 409
    assert client.get("/health").json()["engines"]["edsdk"]["available"] is False


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


@pytest.mark.parametrize("later_close_would_succeed", [False, True])
def test_concurrent_reconnects_never_retry_failed_owner(cleanup_api, later_close_would_succeed):
    client, provider, runner = cleanup_api
    original = reject_open(client, provider)
    original.allow_close = later_close_would_succeed
    before_commands = list(runner.commands)
    before_provider = provider.health_calls, provider.discover_calls
    ready = threading.Barrier(3)

    def connect(engine):
        ready.wait(timeout=3)
        return client.post("/v1/session", json={"engine": engine, "cameraId": provider.camera.id})

    with ThreadPoolExecutor(max_workers=3) as pool:
        requests = [pool.submit(connect, engine) for engine in ("edsdk", "auto", "libgphoto2")]
        for request in requests:
            refused = request.result(timeout=3)
            assert refused.status_code == 409
            assert refused.json()["error"]["code"] == "CAMERA_BUSY"
    assert client.get("/v1/cameras").status_code == 409
    assert client.get("/health").status_code == 200
    assert original.close_calls == 1
    assert original.closed is False
    assert provider.open_count == 1
    assert runner.commands == before_commands
    assert (provider.health_calls, provider.discover_calls) == before_provider


@pytest.mark.parametrize("response", [None, object()], ids=["none", "missing-close"])
def test_missing_cleanup_operation_cannot_clear_retained_owner(response):
    provider = WrongCameraProvider()
    provider.open = Mock(return_value=response)
    engine = EdsdkEngine(provider)
    runner = FakeRunner()
    app = create_app(engine=GPhoto2Engine(runner), edsdk_engine=engine)
    with quarantined_client(app) as client:
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
    assert len(engine._pending_cleanup) == 1


def test_published_retirement_preserves_rejected_owner_quarantine(cleanup_api):
    client, provider, runner = cleanup_api
    provider.wrong_camera = False
    opened = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
    assert opened.status_code == 201
    active = provider.sessions[-1]
    provider.wrong_camera = True
    rejected = client.post("/v1/session", json={
        "engine": "edsdk", "cameraId": "edsdk-different-requested-camera",
    })
    assert rejected.status_code == 502
    assert rejected.json()["error"]["code"] == "INVALID_PROVIDER_RESPONSE"
    pending = provider.sessions[-1]
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


def test_reconnect_cannot_use_second_noop_close_as_release_confirmation():
    provider = WrongCameraProvider()
    original_open = provider.open

    def open_with_consumed_close(camera_id=None, profile_hint=None):
        session = original_open(camera_id, profile_hint)

        def consumed_close():
            session.close_calls += 1
            if session.close_calls == 1:
                raise RuntimeError("Synthetic first release failed")
            # A closed/no-op guard can return normally without confirming release.
            return None

        session.close = consumed_close
        return session

    provider.open = open_with_consumed_close
    engine = EdsdkEngine(provider)
    app = create_app(engine=GPhoto2Engine(FakeRunner()), edsdk_engine=engine)
    with quarantined_client(app) as client:
        rejected = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
        assert rejected.status_code == 502
        original = provider.sessions[0]
        assert original.close_calls == 1
        provider.wrong_camera = False
        reconnect = client.post("/v1/session", json={"engine": "libgphoto2"})
        assert reconnect.status_code == 409
        assert reconnect.json()["error"]["code"] == "CAMERA_BUSY"
        assert original.close_calls == 1
        assert original.closed is False
        assert len(engine._pending_cleanup) == 1
    assert original.close_calls == 1


@pytest.mark.parametrize("interruption", [KeyboardInterrupt(), SystemExit(1), asyncio.CancelledError()])
def test_interrupted_first_close_retains_quarantine_without_retry(interruption):
    provider = WrongCameraProvider()
    rejected = FailedCleanupSession(provider.camera.model_copy(update={"id": "edsdk-other-camera"}))
    rejected.close = Mock(side_effect=interruption)
    provider.open = Mock(return_value=rejected)
    engine = EdsdkEngine(provider)
    with pytest.raises(type(interruption)) as caught:
        engine.open(provider.camera.id)
    assert caught.value is interruption
    rejected.close.assert_called_once_with()
    rejected.close.side_effect = None
    before_health = provider.health_calls
    for retry in (False, True):
        with pytest.raises(BridgeError) as refused:
            engine._require_no_pending_cleanup(retry=retry)
        assert refused.value.code == "CAMERA_BUSY"
    with pytest.raises(BridgeError) as reconnect:
        engine.open(provider.camera.id)
    assert reconnect.value.code == "CAMERA_BUSY"
    assert engine.health()[0] is False
    assert engine._pending_cleanup == [rejected]
    assert provider.health_calls == before_health
    rejected.close.assert_called_once_with()
    provider.open.assert_called_once_with(provider.camera.id, None)


def test_first_successful_rejected_close_still_clears_quarantine():
    provider = WrongCameraProvider()
    original_open = provider.open

    def immediately_cleaned(camera_id=None, profile_hint=None):
        session = original_open(camera_id, profile_hint)
        session.allow_close = True
        return session

    provider.open = immediately_cleaned
    engine = EdsdkEngine(provider)
    app = create_app(engine=GPhoto2Engine(FakeRunner()), edsdk_engine=engine)
    with TestClient(app) as client:
        rejected = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
        assert rejected.status_code == 502
        original = provider.sessions[0]
        assert original.close_calls == 1
        assert original.closed is True
        assert not engine._pending_cleanup
        created = client.post("/v1/session", json={"engine": "libgphoto2"})
        assert created.status_code == 201
        assert client.delete(f"/v1/session/{created.json()['id']}").status_code == 204
    assert original.close_calls == 1


def test_quarantined_client_does_not_swallow_test_body_bridge_error():
    provider = WrongCameraProvider()
    app = create_app(engine=GPhoto2Engine(FakeRunner()), edsdk_engine=EdsdkEngine(provider))
    body_failure = BridgeError("SYNTHETIC_BODY_FAILURE", "The test body must not be swallowed.")
    with pytest.raises(BridgeError) as caught, quarantined_client(app) as client:
        rejected = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
        assert rejected.status_code == 502
        raise body_failure
    assert caught.value is body_failure
    assert provider.sessions[0].close_calls == 1


@pytest.mark.parametrize("retire", ["delete", "shutdown"])
def test_shutdown_preserves_first_error_and_drains_ccapi_with_both_quarantines(retire):
    from .test_registered_close_failure import failed_local_close_client

    provider = WrongCameraProvider()
    provider.wrong_camera = False
    network = Mock()
    network.name = "ccapi"
    network_session = _Session(CameraDescriptor(
        id="ccapi-test", model="Synthetic", port="network", engine="ccapi",
    ))
    network_session.engine_name = "ccapi"
    network_session.close = Mock()
    network.open_connection.return_value = network_session
    app = create_app(engine=GPhoto2Engine(FakeRunner()), edsdk_engine=EdsdkEngine(provider),
                     ccapi_engine=network, token="test-token")
    with failed_local_close_client(app) as client:
        client.headers["Authorization"] = "Bearer test-token"
        opened = client.post("/v1/session", json={"engine": "edsdk", "cameraId": provider.camera.id})
        assert opened.status_code == 201
        active = provider.sessions[-1]
        failure = OSError("synthetic unknown native cleanup")
        active.close = Mock(side_effect=failure)
        provider.wrong_camera = True
        rejected = client.post("/v1/session", json={
            "engine": "edsdk", "cameraId": "edsdk-different-requested-camera",
        })
        assert rejected.status_code == 502
        pending = provider.sessions[-1]
        assert client.post("/v1/session", json={
            "engine": "ccapi", "ccapiUrl": "http://camera.invalid",
        }).status_code == 201
        manager = app.state.session_manager
        with pytest.raises(OSError) as caught:
            if retire == "delete":
                client.delete(f"/v1/session/{opened.json()['id']}")
            else:
                manager.close_all()
        assert caught.value is failure
        if retire == "delete":
            network_session.close.assert_not_called()
        else:
            network_session.close.assert_called_once_with()
        active.close.assert_called_once_with()
        assert pending.close_calls == 1
        assert manager._failed_local_closes
        assert client.post("/v1/session", json={"engine": "libgphoto2"}).status_code == 409
        with pytest.raises(BridgeError) as repeated:
            manager.close_all()
        assert repeated.value.code == "ENGINE_COMMAND_FAILED"
        network_session.close.assert_called_once_with()
        with pytest.raises(BridgeError) as rejected_owner:
            manager.local_engines._require_no_pending_cleanup(retry=True)
        assert rejected_owner.value.code == "CAMERA_BUSY"
        active.close.assert_called_once_with()
        assert pending.close_calls == 1
    active.close.assert_called_once_with()
    assert pending.close_calls == 1
