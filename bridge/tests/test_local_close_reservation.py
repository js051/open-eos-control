from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock, patch

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.models import BatteryStatus, CameraDescriptor, CameraStatus, ExposureState, StorageStatus


def _camera(engine_name: str, identifier: str | None = None) -> CameraDescriptor:
    return CameraDescriptor(
        id=identifier or f"synthetic-{engine_name}", model="Synthetic", port="usb:synthetic", engine=engine_name,
    )


def _engine(name: str) -> Mock:
    engine = Mock()
    engine.name = name
    engine.health.return_value = (True, "synthetic", "Synthetic engine")
    engine.discover.return_value = [_camera(name)]
    engine.open.side_effect = lambda camera_id, _profile: Mock(engine_name=name, camera=_camera(name, camera_id))
    return engine


def _network_engine() -> Mock:
    engine = Mock()
    engine.open_connection.return_value = Mock(
        engine_name="ccapi",
        camera=CameraDescriptor(
            id="synthetic-network", model="Synthetic", port="http://camera.invalid", engine="ccapi",
        ),
    )
    return engine


@pytest.mark.parametrize("retirement", ["delete", "shutdown"])
@pytest.mark.parametrize("retiring_engine", ["libgphoto2", "edsdk"])
def test_local_engine_remains_reserved_until_close_finishes(
    retirement: str, retiring_engine: str,
) -> None:
    entered = threading.Event()
    release = threading.Event()

    def blocked_close() -> None:
        entered.set()
        assert release.wait(timeout=5.0), "The synthetic close was not released."

    engines = {name: _engine(name) for name in ("libgphoto2", "edsdk")}
    other_engine = "edsdk" if retiring_engine == "libgphoto2" else "libgphoto2"
    network = _network_engine()
    app = create_app(
        engine=engines["libgphoto2"], edsdk_engine=engines["edsdk"], ccapi_engine=network, token="test-token",
    )
    headers = {"Authorization": "Bearer test-token"}

    with TestClient(app) as client, ThreadPoolExecutor(max_workers=1) as executor:
        created = client.post("/v1/session", headers=headers, json={"engine": retiring_engine})
        assert created.status_code == 201
        session_id = created.json()["id"]
        retiring = app.state.session_manager.get(session_id)
        retiring.close.side_effect = blocked_close
        if retirement == "delete":
            closing = executor.submit(client.delete, f"/v1/session/{session_id}", headers=headers)
        else:
            closing = executor.submit(app.state.session_manager.close_all)
        try:
            assert entered.wait(timeout=5.0), "The synthetic close did not start."
            # Registration is already gone, but physical cleanup has not returned.
            assert client.get(f"/v1/session/{session_id}/status", headers=headers).status_code == 404
            assert client.delete(f"/v1/session/{session_id}", headers=headers).status_code == 404
            for request in (
                {"engine": other_engine},
                {"engine": retiring_engine, "cameraId": retiring.camera.id},
                {"engine": "auto", "cameraId": f"synthetic-{other_engine}"},
                {"engine": "auto", "cameraId": retiring.camera.id},
                {"engine": "auto"},
            ):
                blocked = client.post("/v1/session", headers=headers, json=request)
                assert blocked.status_code == 409
                assert blocked.json()["error"]["code"] == "CAMERA_BUSY"
                assert blocked.json()["error"]["engine"] == request["engine"]
            discovery = client.get("/v1/cameras", headers=headers)
            assert discovery.status_code == 409
            assert discovery.json()["error"]["code"] == "CAMERA_BUSY"
            engines[other_engine].open.assert_not_called()
            engines[retiring_engine].open.assert_called_once_with(None, None)
            for engine in engines.values():
                engine.health.assert_not_called()
                engine.discover.assert_not_called()
            retiring.close.assert_called_once_with()
            assert not closing.done()

            with patch("open_eos_bridge.sessions.secrets.token_urlsafe", return_value=session_id):
                connected = client.post(
                    "/v1/session", headers=headers, json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid"},
                )
            assert connected.status_code == 201
            network.open_connection.assert_called_once_with("http://camera.invalid", "", "")
            # A new CCAPI registration may reuse the identifier, but it does not
            # own the old local reservation or prove that physical close ended.
            reused = client.post(
                "/v1/session", headers=headers,
                json={"engine": retiring_engine, "cameraId": "synthetic-after-token-reuse"},
            )
            assert reused.status_code == 409
            assert reused.json()["error"]["code"] == "CAMERA_BUSY"
            engines[retiring_engine].open.assert_called_once_with(None, None)
        finally:
            release.set()
            result = closing.result(timeout=5.0)
        if retirement == "delete":
            assert result.status_code == 204
        else:
            assert result is None

        # Successful cleanup releases the local exclusion even while CCAPI stays registered.
        opened = client.post("/v1/session", headers=headers, json={"engine": other_engine})
        assert opened.status_code == 201
        engines[other_engine].open.assert_called_once_with(None, None)
        retiring.close.assert_called_once_with()


@pytest.mark.parametrize("engine_name", ["libgphoto2", "edsdk"])
def test_active_same_engine_still_allows_explicit_auto_and_default_cameras(engine_name: str) -> None:
    engine = _engine(engine_name)
    engine.discover.return_value = [_camera(engine_name, "synthetic-second")]
    app = create_app(engine=engine, edsdk_engine=engine, ccapi_engine=_network_engine(), token="test-token")
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        for request in (
            {"engine": engine_name, "cameraId": "synthetic-first"},
            {"engine": "auto", "cameraId": "synthetic-second"},
            {"engine": "auto"},
        ):
            created = client.post("/v1/session", headers=headers, json=request)
            assert created.status_code == 201
        assert engine.open.call_count == 3


class _ObservedAdmission:
    """Keep real lock behavior and report when a second admission waits."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.waiting = threading.Event()

    def __enter__(self):
        if not self.lock.acquire(blocking=False):
            self.waiting.set()
            self.lock.acquire()
        return self

    def __exit__(self, *_args) -> None:
        self.lock.release()


@pytest.mark.parametrize("retirement", ["delete", "shutdown"])
def test_admitted_open_registers_before_retirement_without_blocking_ccapi(retirement: str) -> None:
    resolving = threading.Event()
    resolve_release = threading.Event()
    closing = threading.Event()
    close_release = threading.Event()
    engine = _engine("libgphoto2")
    network = _network_engine()
    app = create_app(engine=engine, edsdk_engine=_engine("edsdk"), ccapi_engine=network, token="test-token")
    manager = app.state.session_manager
    headers = {"Authorization": "Bearer test-token"}
    admission = _ObservedAdmission()
    manager._open_lock = admission
    resolve = manager.local_engines.resolve_name

    def blocked_resolve(*args):
        resolving.set()
        assert resolve_release.wait(timeout=5.0), "The admitted resolution was not released."
        return resolve(*args)

    def blocked_close() -> None:
        closing.set()
        assert close_release.wait(timeout=5.0), "The synthetic close was not released."

    with TestClient(app) as client, ThreadPoolExecutor(max_workers=2) as executor:
        initial = client.post("/v1/session", headers=headers, json={"engine": "libgphoto2"})
        assert initial.status_code == 201
        identifier = initial.json()["id"]
        original = manager.get(identifier)
        original.close.side_effect = blocked_close
        released = CameraStatus(
            battery=BatteryStatus(), media=StorageStatus(), exposure=ExposureState(),
            bulb_exposure_active=False, shutter_release_unconfirmed=False,
        )
        original.start_bulb_exposure.return_value = released.model_copy(update={"bulb_exposure_active": True})
        original.stop_bulb_exposure.return_value = released
        assert client.post(
            f"/v1/session/{identifier}/bulb/start/owned", headers=headers, json={"ownerId": "synthetic-owner"},
        ).status_code == 200
        with patch.object(manager.local_engines, "resolve_name", side_effect=blocked_resolve):
            opening = executor.submit(
                client.post, "/v1/session", headers=headers,
                json={"engine": "libgphoto2", "cameraId": "synthetic-second"},
            )
            try:
                assert resolving.wait(timeout=5.0)
                if retirement == "delete":
                    retiring = executor.submit(client.delete, f"/v1/session/{identifier}", headers=headers)
                else:
                    retiring = executor.submit(manager.close_all)
                assert admission.waiting.wait(timeout=5.0), "Retirement did not wait for admitted resolution."
                original.close.assert_not_called()
                assert manager.is_current(identifier, original)
                assert not opening.done()
                stopped = client.post(
                    f"/v1/session/{identifier}/bulb/stop/owned", headers=headers,
                    json={"ownerId": "synthetic-owner"},
                )
                assert stopped.status_code == 200
                assert stopped.json()["outcome"] == "released"
                original.stop_bulb_exposure.assert_called_once_with()

                # A pending local admission must not hold metadata or block CCAPI close.
                connected = client.post(
                    "/v1/session", headers=headers, json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid"},
                )
                assert connected.status_code == 201
                assert client.delete(f"/v1/session/{connected.json()['id']}", headers=headers).status_code == 204
                network.open_connection.return_value.close.assert_called_once_with()
            finally:
                resolve_release.set()
            try:
                opened = opening.result(timeout=5.0)
                assert opened.status_code == 201
                assert closing.wait(timeout=5.0)
                assert engine.open.call_count == 2
                blocked = client.post("/v1/session", headers=headers, json={"engine": "libgphoto2"})
                assert blocked.status_code == 409
                assert engine.open.call_count == 2
            finally:
                close_release.set()
                result = retiring.result(timeout=5.0)
        if retirement == "delete":
            assert result.status_code == 204
            assert manager.get(opened.json()["id"]).camera.id == "synthetic-second"
        else:
            assert result is None
            assert not manager._sessions
        original.close.assert_called_once_with()


def test_shutdown_drops_admission_before_waiting_for_delete_retirement() -> None:
    engine = _engine("libgphoto2")
    app = create_app(engine=engine, edsdk_engine=_engine("edsdk"), ccapi_engine=_network_engine(), token="test-token")
    manager = app.state.session_manager
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client, ThreadPoolExecutor(max_workers=2) as executor:
        created = client.post("/v1/session", headers=headers, json={"engine": "libgphoto2"})
        identifier = created.json()["id"]
        original = manager.get(identifier)
        guard = manager._bulb_requests_for(identifier)
        retirement = _ObservedAdmission()
        guard.retirement_lock = retirement
        with retirement:
            deleting = executor.submit(client.delete, f"/v1/session/{identifier}", headers=headers)
            assert retirement.waiting.wait(timeout=5.0), "DELETE did not wait for session retirement."
            retirement.waiting.clear()
            shutdown = executor.submit(manager.close_all)
            assert retirement.waiting.wait(timeout=5.0), "Shutdown did not reach per-session retirement."
            assert not manager.is_current(identifier, original)
            original.close.assert_not_called()
            # Shutdown must release admission before waiting on the same retirement
            # lock already awaited by DELETE; otherwise this request would deadlock.
            refused = client.post("/v1/session", headers=headers, json={"engine": "libgphoto2"})
            assert refused.status_code == 409
            assert refused.json()["error"]["code"] == "CAMERA_BUSY"
            engine.open.assert_called_once_with(None, None)
        assert deleting.result(timeout=5.0).status_code == 404
        assert shutdown.result(timeout=5.0) is None
        original.close.assert_called_once_with()
