"""Owned Bulb admission across production API controls; synthetic providers only."""
import threading
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock, patch

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine

from .test_bulb_request_ownership import Provider, manager_and_provider, status
from .test_ccapi import FakeCcapiTransport
from .test_gphoto_short_control_recovery import CASES, ShortRunner

MUTATIONS = [
    ("post", "/shutter/half-press", None, "half_press_shutter"),
    ("post", "/focus/auto", None, "autofocus"),
    ("post", "/focus/auto/start", {"ownerId": "new-af"}, "start_autofocus"),
    ("post", "/capture/still", None, "capture_still"),
    ("post", "/capture/still", {"af": False}, "capture_still"),
    ("post", "/focus/drive", {"direction": "NEAR", "step": "SMALL"}, "drive_focus"),
    ("post", "/focus/tap", {"x": 0.5, "y": 0.5}, "tap_focus"),
    ("post", "/whitebalance/click", {"x": 0.5, "y": 0.5}, "click_white_balance"),
    ("post", "/recording/start", None, "start_recording"),
    ("post", "/recording/start/outcome", None, "perform_recording_outcome"),
    ("post", "/liveview/start", {}, "start_live_view"),
    ("post", "/liveview/magnification", {"value": 5}, "set_live_view_magnification"),
    ("post", "/maintenance/control/liveview/start", {}, "maintenance_start_live_view"),
    ("post", "/settings/iso", {"value": "100"}, "set_setting"),
    ("post", "/settings/iso/outcome", {"value": "100"}, "apply_setting_outcome"),
    ("post", "/directories", {"name": "ABCDE"}, "create_directory"),
    ("put", "/file-naming/still-user-setting-1", {"value": "ABC"}, "set_file_naming"),
    ("post", "/clock/sync", None, "sync_camera_clock"),
    ("post", "/maintenance/clock-sync/outcome", {"autoPowerOff": False}, "perform_maintenance_outcome"),
    ("post", "/maintenance/sensor-cleaning", {}, "clean_sensor"),
    ("post", "/power/sleep", None, "sleep_camera"),
]


def app_for(provider):
    engine = Mock(name="synthetic-engine")
    engine.name = "edsdk"
    app = create_app(engine=engine, edsdk_engine=engine, ccapi_engine=Mock(), token="synthetic-token")
    identifier = app.state.session_manager._register(provider).id
    return app, identifier


@pytest.mark.parametrize("method,route,payload,provider_method", MUTATIONS)
def test_unresolved_owned_duty_blocks_new_control_before_provider(method, route, payload, provider_method):
    provider = Provider()
    mutation = Mock(side_effect=BridgeError("SYNTHETIC_MUTATION_REACHED", "Must not dispatch.", status_code=418))
    setattr(provider, provider_method, mutation)
    app, identifier = app_for(provider)
    with TestClient(app) as client:
        client.headers["Authorization"] = "Bearer synthetic-token"
        path = f"/v1/session/{identifier}"
        assert client.post(f"{path}/bulb/start/owned", json={"ownerId": "old-A"}).status_code == 200
        provider.stop_error = BridgeError("SYNTHETIC_STATUS_TAIL", "Release status unavailable.", status_code=502)
        assert client.post(f"{path}/bulb/stop/owned", json={"ownerId": "old-A"}).status_code == 502
        response = getattr(client, method)(path + route, **({} if payload is None else {"json": payload}))
        assert response.status_code == 409, response.text
        assert response.json()["error"]["code"] == "BULB_OWNER_BUSY"
        mutation.assert_not_called()


@pytest.mark.parametrize("case", CASES, ids=["half-press", "autofocus"])
def test_gphoto_status_tail_cannot_admit_new_short_control_for_old_stop_to_release(case, tmp_path):
    method, press_path, press_value, release_path, release_value, _ = case
    runner = ShortRunner()
    runner.start_path, runner.start_value = press_path, press_value
    runner.stop_path, runner.stop_value = release_path, release_value
    provider = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    provider._sleep = lambda _: None
    app, identifier = app_for(provider)
    path = f"/v1/session/{identifier}"
    route = "/focus/auto" if method == "autofocus" else "/shutter/half-press"
    with TestClient(app) as client:
        client.headers["Authorization"] = "Bearer synthetic-token"
        assert client.post(path + "/bulb/start/owned", json={"ownerId": "old-A"}).status_code == 200
        failure = BridgeError("SYNTHETIC_STATUS_TAIL", "Status failed.", status_code=502)
        with patch.object(provider, "status", side_effect=failure):
            assert client.post(path + "/bulb/stop/owned", json={"ownerId": "old-A"}).status_code == 502
        assert provider._bulb_release_values is None, "Physical release completed before status failed"
        assert client.get(path + "/status").json()["shutterReleaseUnconfirmed"] is False
        runner.reject_release = True
        before = list(runner.writes())
        rejected = client.post(path + route)
        assert rejected.status_code == 409, rejected.text
        assert rejected.json()["error"]["code"] == "BULB_OWNER_BUSY"
        assert runner.writes() == before and runner.active is False
        runner.reject_release = False
        assert client.post(path + "/bulb/stop/owned", json={"ownerId": "old-A"}).json()["outcome"] == "released"
        # Once A is terminal, B is admitted. An old A retry is receipt-only and
        # cannot send B's saved release, even when B has a new release warning.
        runner.reject_release = True
        assert client.post(path + route).status_code == 502
        assert runner.active is True and provider._short_control_release is not None
        before = list(runner.writes())
        cached = client.post(path + "/bulb/stop/owned", json={"ownerId": "old-A"})
        assert cached.json() == {"ownerId": "old-A", "outcome": "released"}
        assert runner.writes() == before and runner.active is True
        runner.reject_release = False
        assert client.post(path + "/bulb/stop").status_code == 200
        assert runner.active is False


def mutate(manager, identifier, entered=None, release=None):
    with manager.camera_mutation(identifier) as provider:
        provider.calls.append("mutation")
        if entered is not None:
            entered.set()
        if release is not None:
            assert release.wait(3)
        return provider


def test_queued_mutation_rechecks_owner_after_execution_lock_not_before_only():
    manager, provider, identifier = manager_and_provider()
    guard = manager._bulb_requests[identifier]
    waiting = [threading.Event(), threading.Event()]
    count = []
    original = guard._acquire_execution
    def acquire():
        index = len(count)
        count.append(index)
        waiting[index].set()
        original()
    with ThreadPoolExecutor(max_workers=2) as pool:
        guard._execution.acquire()
        externally_held = True
        try:
            with patch.object(guard, "_acquire_execution", side_effect=acquire):
                mutation = pool.submit(mutate, manager, identifier)
                assert waiting[0].wait(1)
                start = pool.submit(manager.start_bulb_exposure, identifier, "new-A")
                assert waiting[1].wait(1)
                guard._execution.release()
                externally_held = False
                with pytest.raises(BridgeError) as failure:
                    mutation.result(1)
                assert failure.value.code == "BULB_OWNER_BUSY"
                assert start.result(1).outcome == "started"
        finally:
            if externally_held:
                guard._execution.release()
    assert provider.calls == ["start"]


@pytest.mark.parametrize("cancel", [False, True])
def test_mutation_already_admitted_finishes_before_queued_start_and_stop_can_cancel(cancel):
    manager, provider, identifier = manager_and_provider()
    entered, release, waiting = threading.Event(), threading.Event(), threading.Event()
    guard = manager._bulb_requests[identifier]
    original = guard._acquire_execution
    with ThreadPoolExecutor(max_workers=2) as pool:
        mutation = pool.submit(mutate, manager, identifier, entered, release)
        assert entered.wait(1)
        def acquire():
            waiting.set()
            original()
        with patch.object(guard, "_acquire_execution", side_effect=acquire):
            start = pool.submit(manager.start_bulb_exposure, identifier, "new-A")
            assert waiting.wait(1)
            assert not provider.start_entered.is_set()
            if cancel:
                assert manager.stop_bulb_exposure(identifier, "new-A").outcome == "cancelled-before-start"
            release.set()
            assert mutation.result(1) is provider
            if cancel:
                with pytest.raises(BridgeError) as failure:
                    start.result(1)
                assert failure.value.code == "BULB_OWNER_REPLAY"
            else:
                assert start.result(1).outcome == "started"
    assert provider.calls == (["mutation"] if cancel else ["mutation", "start"])


def test_pending_start_rejects_mutation_promptly_and_stop_remains_usable():
    manager, provider, identifier = manager_and_provider()
    provider.start_release.clear()
    with ThreadPoolExecutor(max_workers=1) as pool:
        start = pool.submit(manager.start_bulb_exposure, identifier, "A")
        assert provider.start_entered.wait(1)
        try:
            with pytest.raises(BridgeError) as failure:
                mutate(manager, identifier)
            assert failure.value.code == "BULB_OWNER_BUSY"
        finally:
            provider.start_release.set()
        assert start.result(1).outcome == "started"
    assert manager.stop_bulb_exposure(identifier, "A").outcome == "released"
    assert mutate(manager, identifier) is provider
    assert provider.calls == ["start", "stop", "mutation"]


def test_mutation_timeout_is_bounded_and_does_not_dispatch_later():
    manager, provider, identifier = manager_and_provider()
    guard = manager._bulb_requests[identifier]
    with guard._execution, patch("open_eos_bridge.bulb.BULB_EXECUTION_WAIT_SECONDS", 0.01):
        with pytest.raises(BridgeError) as failure:
            mutate(manager, identifier)
        assert failure.value.code == "BULB_OWNER_PENDING"
    assert provider.calls == []
    assert mutate(manager, identifier) is provider


def test_retirement_fences_queued_mutation_and_close_waits_for_admitted_mutation():
    manager, provider, identifier = manager_and_provider()
    guard = manager._bulb_requests[identifier]
    entered, release, waiting, retired = (threading.Event() for _ in range(4))
    acquire, retire = guard._acquire_execution, guard.retire
    with ThreadPoolExecutor(max_workers=3) as pool:
        first = pool.submit(mutate, manager, identifier, entered, release)
        assert entered.wait(1)
        def acquire_marked():
            waiting.set()
            acquire()
        def retire_marked():
            retire()
            retired.set()
        with (patch.object(guard, "_acquire_execution", side_effect=acquire_marked),
              patch.object(guard, "retire", side_effect=retire_marked)):
            second = pool.submit(mutate, manager, identifier)
            assert waiting.wait(1)
            closing = pool.submit(manager.delete, identifier)
            assert retired.wait(1)
            assert not provider.close_entered.is_set()
            release.set()
            first.result(1)
            with pytest.raises(BridgeError) as failure:
                second.result(1)
            assert failure.value.code == "BULB_SESSION_RETIRED"
            closing.result(1)
    assert provider.calls == ["mutation", "close"]


def test_failed_mutation_releases_execution_without_changing_owner_state():
    manager, provider, identifier = manager_and_provider()
    with pytest.raises(KeyboardInterrupt), manager.camera_mutation(identifier):
        raise KeyboardInterrupt("Synthetic provider interruption")
    assert manager.start_bulb_exposure(identifier, "A").outcome == "started"
    assert manager.stop_bulb_exposure(identifier, "A").outcome == "released"


def test_read_only_stop_and_close_routes_remain_accessible_with_owned_duty():
    provider = Provider()
    provider.status = Mock(return_value=status())
    provider.stop_recording = Mock(return_value=status())
    provider.perform_recording_outcome = Mock(return_value={"operation": "stop"})
    provider.stop_live_view = Mock()
    provider.stop_event_polling = Mock()
    app, identifier = app_for(provider)
    with TestClient(app) as client:
        client.headers["Authorization"] = "Bearer synthetic-token"
        path = f"/v1/session/{identifier}"
        assert client.post(path + "/bulb/start/owned", json={"ownerId": "A"}).status_code == 200
        assert client.get(path + "/status").status_code == 200
        assert client.post(path + "/recording/stop").status_code == 200
        assert client.post(path + "/recording/stop/outcome").status_code == 200
        assert client.post(path + "/liveview/stop").status_code == 200
        assert client.delete(path + "/events").status_code == 204
        assert client.delete(path).status_code == 204
    assert provider.calls == ["start", "close"]


def test_real_ccapi_held_af_cannot_enter_until_bulb_receipt_then_old_receipt_is_inert():
    transport = FakeCcapiTransport()
    provider = CcapiEngine(lambda *_: transport, sleeper=lambda _: None).open_connection("http://192.0.2.1")
    app, identifier = app_for(provider)
    path = f"/v1/session/{identifier}"
    with TestClient(app) as client, patch("open_eos_bridge.ccapi.threading.Timer", return_value=Mock()):
        client.headers["Authorization"] = "Bearer synthetic-token"
        assert client.post(path + "/bulb/start/owned", json={"ownerId": "A"}).status_code == 200
        failure = BridgeError("SYNTHETIC_STATUS_TAIL", "Status failed.", status_code=502)
        with patch.object(provider, "status", side_effect=failure):
            assert client.post(path + "/bulb/stop/owned", json={"ownerId": "A"}).status_code == 502
        before = list(transport.requests)
        blocked = client.post(path + "/focus/auto/start", json={"ownerId": "B"})
        assert blocked.json()["error"]["code"] == "BULB_OWNER_BUSY"
        assert transport.requests == before
        assert client.post(path + "/bulb/stop/owned", json={"ownerId": "A"}).status_code == 200
        assert client.post(path + "/focus/auto/start", json={"ownerId": "B"}).status_code == 200
        before = list(transport.requests)
        cached = client.post(path + "/bulb/stop/owned", json={"ownerId": "A"})
        assert cached.json() == {"ownerId": "A", "outcome": "released"}
        assert transport.requests == before and provider._held_autofocus_owner == "B"
        assert client.post(path + "/focus/auto/stop", json={"ownerId": "B"}).status_code == 200
        assert provider._held_autofocus_owner is None
    commands = [r.body for r in transport.requests if r.method != "GET" and "/shooting/control/" in r.path]
    assert commands == [{"af": False, "action": "full_press"}, {"af": False, "action": "release"},
                        {"action": "start"}, {"action": "stop"}]
