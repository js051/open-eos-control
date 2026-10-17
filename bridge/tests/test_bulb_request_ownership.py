from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock, patch

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import BatteryStatus, CameraDescriptor, CameraStatus, ExposureState, StorageStatus
from open_eos_bridge.sessions import SessionManager

from .ccapi_bulb_peer import BulbPeer


def status(active=False, unconfirmed=False):
    return CameraStatus(battery=BatteryStatus(), media=StorageStatus(), exposure=ExposureState(),
                        bulb_exposure_active=active, shutter_release_unconfirmed=unconfirmed)


class Provider:
    """Deliberately zero-argument methods, like an existing external EDSDK provider."""
    engine_name = "edsdk"

    def __init__(self):
        self.camera = CameraDescriptor(id="edsdk-synthetic", model="Synthetic", port="synthetic", engine="edsdk")
        self.calls = []
        self.start_entered = threading.Event()
        self.start_release = threading.Event()
        self.start_release.set()
        self.stop_entered = threading.Event()
        self.stop_release = threading.Event()
        self.stop_release.set()
        self.close_entered = threading.Event()
        self.close_release = threading.Event()
        self.close_release.set()
        self.start_error = None
        self.stop_error = None
        self.stop_status = status()

    def start_bulb_exposure(self):
        self.calls.append("start")
        self.start_entered.set()
        assert self.start_release.wait(3), "Test did not release synthetic Start"
        if self.start_error:
            raise self.start_error
        return status(True)

    def stop_bulb_exposure(self):
        self.calls.append("stop")
        self.stop_entered.set()
        assert self.stop_release.wait(3), "Test did not release synthetic Stop"
        if self.stop_error:
            raise self.stop_error
        return self.stop_status

    def close(self):
        self.calls.append("close")
        self.close_entered.set()
        assert self.close_release.wait(3), "Test did not release synthetic close"

    def maintenance_retire(self):
        self.calls.append("retire")


def manager_and_provider():
    provider = Provider()
    manager = SessionManager(Mock())
    identifier = manager._register(provider).id
    return manager, provider, identifier


@pytest.fixture
def api():
    engine = Mock(name="synthetic-engine")
    engine.name = "edsdk"
    app = create_app(engine=engine, edsdk_engine=engine, ccapi_engine=Mock(), token="synthetic-token")
    provider = Provider()
    identifier = app.state.session_manager._register(provider).id
    with TestClient(app) as client:
        client.headers["Authorization"] = "Bearer synthetic-token"
        yield client, provider, f"/v1/session/{identifier}"


def test_stop_before_start_prevents_late_provider_press(api):
    client, provider, path = api
    response = client.post(f"{path}/bulb/stop/owned", json={"ownerId": "cancelled"})
    assert response.status_code == 200, response.text
    assert response.json() == {"ownerId": "cancelled", "outcome": "cancelled-before-start"}
    response = client.post(f"{path}/bulb/start/owned", json={"ownerId": "cancelled"})
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "BULB_OWNER_REPLAY"
    assert provider.calls == []


def test_owned_routes_keep_zero_argument_provider_contract(api):
    client, provider, path = api
    start = client.post(f"{path}/bulb/start/owned", json={"ownerId": "one"})
    assert start.status_code == 200, start.text
    assert start.json()["ownerId"] == "one" and start.json()["outcome"] == "started"
    assert start.json()["status"]["bulbExposureActive"] is True
    stop = client.post(f"{path}/bulb/stop/owned", json={"ownerId": "one"})
    assert stop.status_code == 200, stop.text
    assert stop.json()["outcome"] == "released"
    assert stop.json()["status"]["bulbExposureActive"] is False
    assert stop.json()["status"]["shutterReleaseUnconfirmed"] is False
    assert provider.calls == ["start", "stop"]
    repeated = client.post(f"{path}/bulb/stop/owned", json={"ownerId": "one"})
    assert repeated.json() == {"ownerId": "one", "outcome": "released"}
    assert provider.calls == ["start", "stop"]
    assert stop.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("payload", [{}, {"ownerId": ""}, {"ownerId": 7}, {"ownerId": "a b"},
                                    {"ownerId": "x" * 129}, {"ownerId": "one", "extra": True}])
@pytest.mark.parametrize("operation", ["start", "stop"])
def test_invalid_owner_is_rejected_before_provider_io(api, payload, operation):
    client, provider, path = api
    assert client.post(f"{path}/bulb/{operation}/owned", json=payload).status_code == 422
    assert provider.calls == []


@pytest.mark.parametrize("operation", ["start", "stop"])
def test_owned_routes_require_authentication(api, operation):
    client, provider, path = api
    client.headers.pop("Authorization")
    assert client.post(f"{path}/bulb/{operation}/owned", json={"ownerId": "one"}).status_code == 401
    assert provider.calls == []


def test_legacy_routes_and_status_shape_are_unchanged(api):
    client, provider, path = api
    for operation in ("start", "stop"):
        response = client.post(f"{path}/bulb/{operation}")
        assert response.status_code == 200, response.text
        assert "ownerId" not in response.json() and "outcome" not in response.json()
        assert "battery" in response.json()
    assert provider.calls == ["start", "stop"]


def test_dispatched_start_finishes_before_stop_and_duplicate_start_never_replays():
    manager, provider, identifier = manager_and_provider()
    provider.start_release.clear()
    with ThreadPoolExecutor(max_workers=3) as pool:
        start = pool.submit(manager.start_bulb_exposure, identifier, "one")
        assert provider.start_entered.wait(1)
        with pytest.raises(BridgeError) as caught:
            manager.start_bulb_exposure(identifier, "one")
        assert caught.value.code == "BULB_OWNER_REPLAY"
        stop = pool.submit(manager.stop_bulb_exposure, identifier, "one")
        assert not provider.stop_entered.wait(0.05)
        assert not stop.done()
        provider.start_release.set()
        assert start.result(1).outcome == "started"
        assert stop.result(1).outcome == "released"
    assert provider.calls == ["start", "stop"]


def test_queued_start_is_cancelled_before_provider_admission():
    manager, provider, identifier = manager_and_provider()
    guard = manager._bulb_requests[identifier]
    with guard._execution, ThreadPoolExecutor(max_workers=2) as pool:
        admitted = threading.Event()
        original = guard._acquire_execution

        def waiting():
            admitted.set()
            return original()

        with patch.object(guard, "_acquire_execution", side_effect=waiting):
            start = pool.submit(manager.start_bulb_exposure, identifier, "one")
            assert admitted.wait(1)
            stop = manager.stop_bulb_exposure(identifier, "one")
            assert stop.outcome == "cancelled-before-start"
            # Release the execution lock before waiting for the worker.
            guard._execution.release()
            try:
                with pytest.raises(BridgeError) as caught:
                    start.result(1)
                assert caught.value.code == "BULB_OWNER_REPLAY"
            finally:
                guard._execution.acquire()
    assert provider.calls == []


def test_old_and_unknown_stop_do_not_touch_new_owner():
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier, "one")
    manager.stop_bulb_exposure(identifier, "one")
    manager.start_bulb_exposure(identifier, "two")
    before = list(provider.calls)
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"
    assert manager.stop_bulb_exposure(identifier, "unknown").outcome == "cancelled-before-start"
    assert provider.calls == before
    with pytest.raises(BridgeError) as caught:
        manager.start_bulb_exposure(identifier, "unknown")
    assert caught.value.code == "BULB_OWNER_REPLAY"
    assert manager.stop_bulb_exposure(identifier, "two").outcome == "released"


def test_lost_start_response_keeps_owner_for_explicit_stop():
    manager, provider, identifier = manager_and_provider()
    failure = BridgeError("SYNTHETIC_LOST_ACK", "Synthetic lost response", status_code=502)
    provider.start_error = failure
    with pytest.raises(BridgeError) as caught:
        manager.start_bulb_exposure(identifier, "one")
    assert caught.value is failure
    with pytest.raises(BridgeError) as caught:
        manager.start_bulb_exposure(identifier, "two")
    assert caught.value.code == "BULB_OWNER_BUSY"
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"
    assert provider.calls == ["start", "stop"]


@pytest.mark.parametrize("active,unconfirmed", [(None, False), (True, False), (False, True)])
def test_unknown_stop_status_never_becomes_released(active, unconfirmed):
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier, "one")
    provider.stop_status = status(active, unconfirmed)
    with pytest.raises(BridgeError) as caught:
        manager.stop_bulb_exposure(identifier, "one")
    assert caught.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
    provider.stop_status = status()
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"
    assert provider.calls == ["start", "stop", "stop"]


def test_failed_stop_or_status_tail_keeps_retryable_owner():
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier, "one")
    failure = BridgeError("SYNTHETIC_STATUS_TAIL", "Synthetic status tail", status_code=502)
    provider.stop_error = failure
    with pytest.raises(BridgeError) as caught:
        manager.stop_bulb_exposure(identifier, "one")
    assert caught.value is failure
    provider.stop_error = None
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"
    assert provider.calls == ["start", "stop", "stop"]


def test_capacity_never_evicts_oldest_tombstone_and_preserves_active_stop():
    manager, provider, identifier = manager_and_provider()
    manager.stop_bulb_exposure(identifier, "oldest")
    manager.start_bulb_exposure(identifier, "active")
    for index in range(254):
        manager.stop_bulb_exposure(identifier, f"cancelled-{index}")
    guard = manager._bulb_requests[identifier]
    assert len(guard._owners) == 256
    assert manager.stop_bulb_exposure(identifier, "overflow").outcome == "cancelled-before-start"
    assert len(guard._owners) == 256
    for owner in ("oldest", "overflow", "new"):
        with pytest.raises(BridgeError) as caught:
            manager.start_bulb_exposure(identifier, owner)
        assert caught.value.code in {"BULB_OWNER_REPLAY", "BULB_OWNER_LIMIT"}
    assert manager.stop_bulb_exposure(identifier, "active").outcome == "released"
    assert provider.calls == ["start", "stop"]


@pytest.mark.parametrize("retirement", ["delete", "close_all", "retire_maintenance"])
def test_retirement_fences_obtained_guard_and_waits_for_dispatched_start(retirement):
    manager, provider, identifier = manager_and_provider()
    guard = manager._bulb_requests[identifier]
    provider.start_release.clear()
    with ThreadPoolExecutor(max_workers=2) as pool:
        start = pool.submit(manager.start_bulb_exposure, identifier, "one")
        assert provider.start_entered.wait(1)
        retired = threading.Event()
        original = guard.retire

        def mark_retired():
            original()
            retired.set()

        with patch.object(guard, "retire", side_effect=mark_retired):
            action = getattr(manager, retirement)
            closing = pool.submit(action, *([] if retirement == "close_all" else [identifier]))
            assert retired.wait(1)
            with pytest.raises(BridgeError) as caught:
                guard.start("late")
            assert caught.value.code == "BULB_SESSION_RETIRED"
            assert not closing.done()
            provider.start_release.set()
            start.result(1)
            closing.result(1)
    assert provider.calls == ["start", "retire" if retirement == "retire_maintenance" else "close"]
    assert identifier not in manager._sessions


def test_delete_preserves_camera_reservation_until_provider_close_finishes():
    manager, provider, identifier = manager_and_provider()
    provider.close_release.clear()
    with ThreadPoolExecutor(max_workers=1) as pool:
        closing = pool.submit(manager.delete, identifier)
        assert provider.close_entered.wait(1)
        with pytest.raises(BridgeError) as caught:
            manager._register(Provider())
        assert caught.value.code == "CAMERA_BUSY"
        provider.close_release.set()
        closing.result(1)
    manager._register(Provider())


def test_legacy_stop_serializes_with_owned_start_and_retires_its_owner():
    manager, provider, identifier = manager_and_provider()
    provider.start_release.clear()
    with ThreadPoolExecutor(max_workers=2) as pool:
        start = pool.submit(manager.start_bulb_exposure, identifier, "one")
        assert provider.start_entered.wait(1)
        stop = pool.submit(manager.stop_bulb_exposure, identifier)
        assert not provider.stop_entered.wait(0.05)
        provider.start_release.set()
        start.result(1)
        assert stop.result(1).bulb_exposure_active is False
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"
    assert provider.calls == ["start", "stop"]


def test_legacy_start_cannot_bypass_active_owned_duty():
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier, "one")
    with pytest.raises(BridgeError) as caught:
        manager.start_bulb_exposure(identifier)
    assert caught.value.code == "BULB_OWNER_BUSY"
    assert provider.calls == ["start"]


def test_reused_wire_id_does_not_revive_old_guard_or_reuse_its_tombstones():
    manager, provider, identifier = manager_and_provider()
    old = manager._bulb_requests[identifier]
    manager.stop_bulb_exposure(identifier, "one")
    manager.delete(identifier)
    replacement = Provider()
    with patch("open_eos_bridge.sessions.secrets.token_urlsafe", return_value=identifier):
        assert manager._register(replacement).id == identifier
    with pytest.raises(BridgeError) as caught:
        old.start("late")
    assert caught.value.code == "BULB_SESSION_RETIRED"
    assert manager.start_bulb_exposure(identifier, "one").outcome == "started"
    assert replacement.calls == ["start"]


def test_concurrent_stop_requests_join_serialization_without_duplicate_provider_stop():
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier, "one")
    provider.stop_release.clear()
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(manager.stop_bulb_exposure, identifier, "one")
        assert provider.stop_entered.wait(1)
        second = pool.submit(manager.stop_bulb_exposure, identifier, "one")
        provider.stop_release.set()
        assert first.result(1).outcome == second.result(1).outcome == "released"
    assert provider.calls == ["start", "stop"]


def test_execution_wait_is_bounded_without_claiming_release_or_replaying_start():
    manager, provider, identifier = manager_and_provider()
    provider.start_release.clear()
    with ThreadPoolExecutor(max_workers=1) as pool:
        start = pool.submit(manager.start_bulb_exposure, identifier, "one")
        assert provider.start_entered.wait(1)
        with patch("open_eos_bridge.bulb.BULB_EXECUTION_WAIT_SECONDS", 0.01):
            with pytest.raises(BridgeError) as caught:
                manager.stop_bulb_exposure(identifier, "one")
            assert caught.value.code == "BULB_OWNER_PENDING"
        assert provider.calls == ["start"]
        provider.start_release.set()
        start.result(1)
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"
    assert provider.calls == ["start", "stop"]


@pytest.mark.parametrize("field,value", [
    ("bulb_exposure_active", None), ("bulb_exposure_active", "false"), ("bulb_exposure_active", 0),
    ("shutter_release_unconfirmed", None), ("shutter_release_unconfirmed", "false"),
    ("shutter_release_unconfirmed", 0),
])
def test_owned_release_requires_literal_booleans(field, value):
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier, "one")
    provider.stop_status = status().model_copy(update={field: value})
    with pytest.raises(BridgeError) as caught:
        manager.stop_bulb_exposure(identifier, "one")
    assert caught.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
    provider.stop_status = status()
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"


@pytest.mark.parametrize("active", [None, False, 1, "true"])
def test_unconfirmed_start_reply_retains_stop_without_replay(active):
    manager, provider, identifier = manager_and_provider()
    with patch.object(provider, "start_bulb_exposure", return_value=status().model_copy(
            update={"bulb_exposure_active": active})) as start:
        with pytest.raises(BridgeError) as caught:
            manager.start_bulb_exposure(identifier, "one")
        assert caught.value.code == "BULB_START_UNCONFIRMED"
        start.assert_called_once_with()
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"


def test_legacy_active_operation_cannot_be_adopted_by_a_new_owned_start():
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier)
    with pytest.raises(BridgeError) as caught:
        manager.start_bulb_exposure(identifier, "one")
    assert caught.value.code == "BULB_OWNER_BUSY"
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "cancelled-before-start"
    assert provider.calls == ["start"]
    manager.stop_bulb_exposure(identifier)
    assert manager.start_bulb_exposure(identifier, "two").outcome == "started"


def test_failed_maintenance_retirement_preserves_original_owner_stop():
    manager, provider, identifier = manager_and_provider()
    manager.start_bulb_exposure(identifier, "one")
    failure = BridgeError("SHUTTER_RELEASE_UNCONFIRMED", "Synthetic retained release", status_code=409)
    with patch.object(provider, "maintenance_retire", side_effect=failure):
        with pytest.raises(BridgeError) as caught:
            manager.retire_maintenance(identifier)
        assert caught.value is failure
    assert manager.get(identifier) is provider
    assert manager.stop_bulb_exposure(identifier, "one").outcome == "released"


@pytest.mark.parametrize("owner", [None, "one"])
def test_failed_retirement_does_not_revive_already_queued_start(owner):
    manager, provider, identifier = manager_and_provider()
    guard = manager._bulb_requests[identifier]
    queued = threading.Event()
    proceed = threading.Event()
    acquire = guard._acquire_execution

    def pause_admission():
        queued.set()
        assert proceed.wait(3)
        acquire()

    with ThreadPoolExecutor(max_workers=1) as pool, \
            patch.object(guard, "_acquire_execution", side_effect=pause_admission):
        start = pool.submit(manager.start_bulb_exposure, identifier, owner)
        assert queued.wait(1)
        with patch.object(provider, "maintenance_retire", side_effect=OSError("synthetic retire failure")), \
                pytest.raises(OSError):
            manager.retire_maintenance(identifier)
        proceed.set()
        with pytest.raises(BridgeError) as caught:
            start.result(1)
        assert caught.value.code == "BULB_SESSION_RETIRED"
    assert provider.calls == []
    if owner is not None:
        assert manager.stop_bulb_exposure(identifier, owner).outcome == "cancelled-before-start"
    assert manager.start_bulb_exposure(identifier, "fresh").outcome == "started"


def test_close_all_keeps_camera_reserved_through_provider_close():
    manager, provider, _ = manager_and_provider()
    provider.close_release.clear()
    with ThreadPoolExecutor(max_workers=1) as pool:
        close = pool.submit(manager.close_all)
        assert provider.close_entered.wait(1)
        with pytest.raises(BridgeError) as caught:
            manager._register(Provider())
        assert caught.value.code == "CAMERA_BUSY"
        provider.close_release.set()
        close.result(1)
    manager._register(Provider())


def test_shutdown_during_failed_maintenance_retirement_never_reopens_old_guard():
    manager, provider, identifier = manager_and_provider()
    guard = manager._bulb_requests[identifier]
    entered, proceed, shutdown_retired = threading.Event(), threading.Event(), threading.Event()
    retire = guard.retire
    calls = 0

    def mark_retired():
        nonlocal calls
        retire()
        calls += 1
        if calls == 2:
            shutdown_retired.set()

    def fail_retirement():
        entered.set()
        assert proceed.wait(3)
        raise OSError("synthetic local retirement failure")

    with ThreadPoolExecutor(max_workers=2) as pool, \
            patch.object(guard, "retire", side_effect=mark_retired), \
            patch.object(provider, "maintenance_retire", side_effect=fail_retirement):
        maintenance = pool.submit(manager.retire_maintenance, identifier)
        assert entered.wait(1)
        shutdown = pool.submit(manager.close_all)
        assert shutdown_retired.wait(1)
        proceed.set()
        with pytest.raises(OSError):
            maintenance.result(1)
        shutdown.result(1)
    with pytest.raises(BridgeError) as caught:
        guard.start("late")
    assert caught.value.code == "BULB_SESSION_RETIRED"
    assert provider.calls == ["close"]


def test_a_waiting_retirement_does_not_hold_the_manager_global_lock():
    manager, provider, identifier = manager_and_provider()
    other = Provider()
    other.camera = other.camera.model_copy(update={"id": "edsdk-synthetic-other"})
    other_id = manager._register(other).id
    provider.close_release.clear()
    with ThreadPoolExecutor(max_workers=2) as pool:
        closing = pool.submit(manager.delete, identifier)
        assert provider.close_entered.wait(1)
        assert pool.submit(manager.get, other_id).result(1) is other
        provider.close_release.set()
        closing.result(1)


@pytest.fixture
def ccapi_boundary():
    peer = BulbPeer()
    peer.drop_press = False
    peer.reject_release = False
    try:
        with TestClient(create_app(ccapi_engine=CcapiEngine(sleeper=lambda _: None))) as client:
            created = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": peer.origin})
            assert created.status_code == 201, created.text
            yield client, peer, f"/v1/session/{created.json()['id']}"
    finally:
        peer.close()


def test_real_ccapi_owned_cancellation_never_sends_a_late_press(ccapi_boundary):
    client, peer, path = ccapi_boundary
    assert client.post(f"{path}/bulb/stop/owned", json={"ownerId": "late"}).json() == {
        "ownerId": "late", "outcome": "cancelled-before-start",
    }
    late = client.post(f"{path}/bulb/start/owned", json={"ownerId": "late"})
    assert late.status_code == 409
    assert peer.commands == []
    assert peer.active is False


def test_real_ccapi_status_tail_requires_same_owner_stop_retry_without_repress(ccapi_boundary):
    client, peer, path = ccapi_boundary
    start = client.post(f"{path}/bulb/start/owned", json={"ownerId": "one"})
    assert start.status_code == 200, start.text
    peer.status_failure_after_release = True
    uncertain = client.post(f"{path}/bulb/stop/owned", json={"ownerId": "one"})
    assert uncertain.status_code == 502, uncertain.text
    snapshot = client.get(f"{path}/status").json()
    assert snapshot["bulbExposureActive"] is False and snapshot["shutterReleaseUnconfirmed"] is False
    blocked = client.post(f"{path}/bulb/start/owned", json={"ownerId": "two"})
    assert blocked.status_code == 409
    assert blocked.json()["error"]["code"] == "BULB_OWNER_BUSY"
    stop = client.post(f"{path}/bulb/stop/owned", json={"ownerId": "one"})
    assert stop.status_code == 200, stop.text
    assert stop.json()["outcome"] == "released"
    assert [body["action"] for _, _, body in peer.commands] == ["full_press", "release"]
