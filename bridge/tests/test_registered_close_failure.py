"""Registered cleanup failure never becomes a successful no-op close receipt."""
from __future__ import annotations

from contextlib import contextmanager

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine

from .fakes import FakeRunner
from .test_bulb_request_ownership import status
from .test_local_close_reservation import _engine, _network_engine


@contextmanager
def failed_local_close_client(app, *, raise_server_exceptions=True):
    # Only shutdown is expected to fail; never consume a test-body exception.
    client = TestClient(app, raise_server_exceptions=raise_server_exceptions)
    client.__enter__()
    try:
        yield client
    finally:
        with pytest.raises(BridgeError) as shutdown:
            client.__exit__(None, None, None)
        assert shutdown.value.code == "ENGINE_COMMAND_FAILED"
        assert "no longer available" in shutdown.value.message
        assert "physical release" in shutdown.value.message
        assert "synthetic unknown native cleanup" not in shutdown.value.message


@pytest.mark.parametrize("retiring_engine", ["libgphoto2", "edsdk"])
def test_failed_registered_delete_must_not_admit_another_local_engine(retiring_engine):
    engines = {name: _engine(name) for name in ("libgphoto2", "edsdk")}
    other_engine = "edsdk" if retiring_engine == "libgphoto2" else "libgphoto2"
    network = _network_engine()
    network.open_connection.return_value.status.return_value = status()
    app = create_app(engine=engines["libgphoto2"], edsdk_engine=engines["edsdk"], ccapi_engine=network)
    with failed_local_close_client(app, raise_server_exceptions=False) as client:
        created = client.post("/v1/session", json={"engine": retiring_engine})
        assert created.status_code == 201
        identifier = created.json()["id"]
        original = app.state.session_manager.get(identifier)
        original.close.side_effect = OSError("synthetic unknown native cleanup")
        assert client.delete(f"/v1/session/{identifier}").status_code == 500
        assert client.delete(f"/v1/session/{identifier}").status_code == 404
        assert client.post(f"/v1/session/{identifier}/bulb/stop").status_code == 404

        connected = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid"})
        assert connected.status_code == 201
        network_path = f"/v1/session/{connected.json()['id']}"
        assert client.get(f"{network_path}/status").status_code == 200
        assert client.delete(network_path).status_code == 204
        original.close.assert_called_once_with()

        discovery = client.get("/v1/cameras")
        reopened = client.post("/v1/session", json={"engine": other_engine})
        assert (discovery.status_code, reopened.status_code, engines[other_engine].open.call_count) == (409, 409, 0)
        assert "unconfirmed" in reopened.json()["error"]["message"]
        assert "still closing" not in reopened.json()["error"]["message"]
        for request in ({"engine": retiring_engine}, {"engine": "auto"},
                        {"engine": "auto", "cameraId": original.camera.id}):
            assert client.post("/v1/session", json=request).status_code == 409
        for engine in engines.values():
            engine.discover.assert_not_called()
            engine.health.assert_not_called()
        original.close.assert_called_once_with()
    original.close.assert_called_once_with()


def test_gphoto_closed_flag_and_second_close_do_not_prove_shutter_release():
    class RejectReleaseRunner(FakeRunner):
        reject_release = False

        def run(self, arguments, *, timeout=30.0):
            if self.reject_release and any(value.endswith("eosremoterelease=Release Full") for value in arguments):
                self.commands.append(tuple(arguments))
                raise BridgeError("CAMERA_COMMAND_FAILED", "Synthetic rejected release", status_code=502)
            return super().run(arguments, timeout=timeout)

    runner = RejectReleaseRunner()
    edsdk = _engine("edsdk")
    app = create_app(engine=GPhoto2Engine(runner), edsdk_engine=edsdk)
    with failed_local_close_client(app) as client:
        created = client.post("/v1/session", json={"engine": "libgphoto2"})
        assert created.status_code == 201
        identifier = created.json()["id"]
        path = f"/v1/session/{identifier}"
        original = app.state.session_manager.get(identifier)
        assert client.post(f"{path}/bulb/start/owned", json={"ownerId": "synthetic-owner"}).status_code == 200
        runner.reject_release = True
        stopped = client.post(f"{path}/bulb/stop/owned", json={"ownerId": "synthetic-owner"})
        assert stopped.status_code == 502
        assert app.state.session_manager.get(identifier) is original
        deleted = client.delete(path)
        assert deleted.status_code == 502
        assert deleted.json()["error"]["code"] == "SHUTTER_RELEASE_UNCONFIRMED"
        assert original._closed is True
        assert original._bulb_release_values is not None
        assert client.post(f"{path}/bulb/stop").status_code == 404
        assert client.delete(path).status_code == 404
        before_retry = list(runner.commands)
        assert original.close() is None
        assert runner.commands == before_retry
        assert original._bulb_release_values is not None
        reopened = client.post("/v1/session", json={"engine": "edsdk"})
        assert (reopened.status_code, edsdk.open.call_count) == (409, 0)
        assert runner.commands == before_retry


def test_shutdown_drains_other_sessions_but_must_keep_failed_local_reservation():
    local = _engine("libgphoto2")
    other = _engine("edsdk")
    network = _network_engine()
    app = create_app(engine=local, edsdk_engine=other, ccapi_engine=network)
    with failed_local_close_client(app) as client:
        originals = []
        for identifier in ("synthetic-one", "synthetic-two"):
            created = client.post("/v1/session", json={"engine": "libgphoto2", "cameraId": identifier})
            assert created.status_code == 201
            originals.append(app.state.session_manager.get(created.json()["id"]))
        connected = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid"})
        assert connected.status_code == 201
        failure = OSError("synthetic unknown native cleanup")
        originals[0].close.side_effect = failure
        with pytest.raises(OSError) as caught:
            app.state.session_manager.close_all()
        assert caught.value is failure
        for original in originals:
            original.close.assert_called_once_with()
        network.open_connection.return_value.close.assert_called_once_with()
        assert not app.state.session_manager._sessions
        reopened = client.post("/v1/session", json={"engine": "edsdk"})
        assert (reopened.status_code, other.open.call_count) == (409, 0)
        with pytest.raises(BridgeError) as repeated:
            app.state.session_manager.close_all()
        assert repeated.value.code == "ENGINE_COMMAND_FAILED"
        for original in originals:
            original.close.assert_called_once_with()


def test_other_registered_stop_and_delete_and_later_ccapi_shutdown_still_drain():
    local = _engine("libgphoto2")
    network = _network_engine()
    app = create_app(engine=local, edsdk_engine=_engine("edsdk"), ccapi_engine=network)
    with failed_local_close_client(app, raise_server_exceptions=False) as client:
        first = client.post("/v1/session", json={"engine": "libgphoto2", "cameraId": "synthetic-one"})
        second = client.post("/v1/session", json={"engine": "libgphoto2", "cameraId": "synthetic-two"})
        first_id, second_id = first.json()["id"], second.json()["id"]
        manager = app.state.session_manager
        failed, other = manager.get(first_id), manager.get(second_id)
        other.start_bulb_exposure.return_value = status(True)
        other.stop_bulb_exposure.return_value = status()
        second_path = f"/v1/session/{second_id}"
        assert client.post(f"{second_path}/bulb/start/owned", json={"ownerId": "one"}).status_code == 200
        failed.close.side_effect = OSError("synthetic unknown native cleanup")
        assert client.delete(f"/v1/session/{first_id}").status_code == 500
        stopped = client.post(f"{second_path}/bulb/stop/owned", json={"ownerId": "one"})
        assert stopped.status_code == 200
        assert stopped.json()["outcome"] == "released"
        assert client.delete(second_path).status_code == 204
        other.close.assert_called_once_with()
        connected = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid"})
        assert connected.status_code == 201
        with pytest.raises(BridgeError) as repeated:
            manager.close_all()
        assert repeated.value.code == "ENGINE_COMMAND_FAILED"
        network.open_connection.return_value.close.assert_called_once_with()
        failed.close.assert_called_once_with()
        assert len(manager._failed_local_closes) == 1
        assert next(iter(manager._failed_local_closes.values())) == (first_id, failed)
        assert client.get("/v1/cameras").status_code == 409


@pytest.mark.parametrize("retirement", ["delete", "close_all"])
def test_original_base_exception_propagates_without_clearing_local_ownership(retirement):
    class AbortCleanup(BaseException):
        pass

    engine = _engine("libgphoto2")
    app = create_app(engine=engine, edsdk_engine=_engine("edsdk"))
    with failed_local_close_client(app) as client:
        created = client.post("/v1/session", json={"engine": "libgphoto2"})
        identifier = created.json()["id"]
        manager = app.state.session_manager
        original = manager.get(identifier)
        failure = AbortCleanup("synthetic interruption")
        original.close.side_effect = failure
        with pytest.raises(AbortCleanup) as caught:
            getattr(manager, retirement)(*([identifier] if retirement == "delete" else []))
        assert caught.value is failure
        assert client.post("/v1/session", json={"engine": "edsdk"}).status_code == 409
        assert client.get("/v1/cameras").status_code == 409
        assert client.delete(f"/v1/session/{identifier}").status_code == 404
        original.close.assert_called_once_with()


def test_failed_ccapi_close_does_not_quarantine_local_usb():
    network = _network_engine()
    local = _engine("libgphoto2")
    app = create_app(engine=local, edsdk_engine=_engine("edsdk"), ccapi_engine=network)
    with TestClient(app, raise_server_exceptions=False) as client:
        connected = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid"})
        network.open_connection.return_value.close.side_effect = OSError("synthetic network cleanup")
        assert client.delete(f"/v1/session/{connected.json()['id']}").status_code == 500
        assert client.post("/v1/session", json={"engine": "libgphoto2"}).status_code == 201
        assert not app.state.session_manager._failed_local_closes


def test_failed_close_client_does_not_consume_body_bridge_error():
    engine = _engine("libgphoto2")
    app = create_app(engine=engine)
    failure = BridgeError("ENGINE_COMMAND_FAILED", "Synthetic body failure")
    with (pytest.raises(BridgeError) as caught,
          failed_local_close_client(app, raise_server_exceptions=False) as client):
        created = client.post("/v1/session", json={"engine": "libgphoto2"})
        original = app.state.session_manager.get(created.json()["id"])
        original.close.side_effect = OSError("synthetic unknown native cleanup")
        assert client.delete(f"/v1/session/{created.json()['id']}").status_code == 500
        raise failure
    assert caught.value is failure
