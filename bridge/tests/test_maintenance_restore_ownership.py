"""Production API/manager/provider restore ordering; camera HTTP is synthetic."""
from __future__ import annotations

import re
import threading
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import LiveViewStartRequest

from .test_ccapi import DISCOVERY
from .test_maintenance_lifecycle import MaintenanceTransport


@pytest.fixture
def api():
    transport = MaintenanceTransport(discovery=DISCOVERY)
    app = create_app(ccapi_engine=CcapiEngine(lambda _url, _password: transport))
    with TestClient(app) as client:
        identifier = client.post('/v1/session', json={
            'engine': 'ccapi', 'ccapiUrl': 'http://192.0.2.55:8080'}).json()['id']
        manager = app.state.session_manager
        session = manager.get(identifier)
        session.capabilities()
        yield client, manager, session, transport, identifier


def path(api, action):
    return f'/v1/session/{api[-1]}/maintenance/control/liveview/{action}'


def start_original(api):
    api[2].start_live_view(LiveViewStartRequest(source='CCAPI_JPEG_POLLING', size='MEDIUM', fps=4))


def prepare(api):
    start_original(api)
    result = api[0].post(path(api, 'stop'), json={'prepareRestore': True})
    assert result.status_code == 200, result.text
    assert result.json()['active'] is False and result.json()['liveViewStopUnconfirmed'] is False
    token = result.json()['restoreToken']
    assert re.fullmatch(r'[A-Za-z0-9_-]{24,64}', token)
    return token


def start(api, token):
    return api[0].post(path(api, 'start'), json={
        'restoreToken': token, 'source': 'AUTO', 'size': 'SMALL', 'fps': 1})


def stop(api, token):
    return api[0].post(path(api, 'stop'), json={'restoreToken': token})


def camera_controls(transport):
    return [(r.method, r.path, r.body) for r in transport.requests
            if r.method in {'POST', 'DELETE'} and r.path.endswith('/shooting/liveview')]


def test_owned_restore_freezes_original_source_and_is_one_shot(api):
    token = prepare(api)
    with patch.object(api[2], 'start_live_view', wraps=api[2].start_live_view) as dispatched:
        restored = start(api, token)
    frozen = dispatched.call_args.args[0]
    assert (frozen.source, frozen.size, frozen.fps) == ('CCAPI_JPEG_POLLING', 'MEDIUM', 4)
    assert restored.status_code == 200, restored.text
    assert restored.json() == {'active': True, 'liveViewStopUnconfirmed': False, 'requestedFps': 4,
                               'source': 'CCAPI_JPEG_POLLING',
                               'restoreToken': token, 'restoreOutcome': 'started'}
    before = len(api[3].requests)
    assert start(api, token).status_code == 409
    assert len(api[3].requests) == before
    stopped = stop(api, token)
    assert stopped.json() == {'active': False, 'liveViewStopUnconfirmed': False,
                              'restoreToken': token, 'restoreOutcome': 'stopped'}
    assert stopped.headers['cache-control'] == 'no-store'
    assert not api[2].live_view_active
    assert not api[2].live_view_stop_unconfirmed


def test_stop_before_start_and_repeat_are_receipts_without_current_state(api):
    token = prepare(api)
    before = len(api[3].requests)
    expected = {'restoreToken': token, 'restoreOutcome': 'cancelled-before-start'}
    assert stop(api, token).json() == expected
    assert stop(api, token).json() == expected
    assert start(api, token).status_code == 409
    assert len(api[3].requests) == before
    # A terminal receipt must not refresh or clear unrelated later warnings.
    api[2]._live_view_stop_unconfirmed = True
    assert stop(api, token).json() == expected
    assert api[2].live_view_stop_unconfirmed
    api[2]._live_view_stop_unconfirmed = False


def test_cached_stopped_receipt_does_not_stop_or_describe_new_preview(api):
    token = prepare(api)
    assert start(api, token).status_code == 200
    assert stop(api, token).json()['active'] is False
    start_original(api)
    before = len(api[3].requests)
    assert stop(api, token).json() == {'restoreToken': token, 'restoreOutcome': 'stopped'}
    assert len(api[3].requests) == before and api[2].live_view_active


def test_displaced_owner_cannot_stop_new_preview(api):
    token = prepare(api)
    assert start(api, token).status_code == 200
    api[2].stop_live_view()  # Existing explicit global Stop, then an unrelated Start.
    start_original(api)
    before = len(api[3].requests)
    assert stop(api, token).json() == {'restoreToken': token, 'restoreOutcome': 'stopped'}
    assert len(api[3].requests) == before and api[2].live_view_active


@pytest.mark.parametrize('action', ['start', 'stop'])
def test_unknown_or_other_session_token_rejects_without_camera_io(api, action):
    before = len(api[3].requests)
    result = api[0].post(path(api, action), json={'restoreToken': 'x' * 32})
    assert result.status_code == 409
    assert result.json()['error']['code'] == 'MAINTENANCE_RESTORE_UNKNOWN'
    assert len(api[3].requests) == before


@pytest.mark.parametrize('payload', [{'prepareRestore': 'true'}, {'prepareRestore': 1},
    {'prepareRestore': True, 'restoreToken': 'x' * 32}, {'restoreToken': 'x' * 23},
    {'restoreToken': 'x' * 65}, {'restoreToken': 'x' * 24 + '/'}, {'restoreToken': 123}])
def test_stop_schema_rejects_ambiguous_or_invalid_owner_before_io(api, payload):
    before = len(api[3].requests)
    assert api[0].post(path(api, 'stop'), json=payload).status_code == 422
    assert len(api[3].requests) == before


def test_idle_prepare_does_not_fabricate_restore_permission(api):
    result = api[0].post(path(api, 'stop'), json={'prepareRestore': True})
    assert result.status_code == 200
    assert result.json() == {'active': False, 'liveViewStopUnconfirmed': False}
    assert not api[1].maintenance_live_view(api[-1])._permits


def test_legacy_stop_and_restore_payloads_remain_compatible(api):
    start_original(api)
    assert api[0].post(path(api, 'stop')).json() == {'active': False, 'liveViewStopUnconfirmed': False}
    result = api[0].post(path(api, 'start'), json={'source': 'AUTO', 'fps': 1})
    assert result.status_code == 200 and result.json()['source'] == 'CCAPI_JPEG_POLLING'
    assert 'restoreToken' not in result.json()
    # Existing callers may run a second maintenance journey with no token.
    assert api[0].post(path(api, 'stop'), json={}).status_code == 200
    assert api[0].post(path(api, 'start'), json={}).status_code == 200


def test_lost_initial_stop_reply_then_no_token_cleanup_revokes_the_unseen_permit(api):
    token = prepare(api)  # Simulate the caller losing this response/token.
    result = api[0].post(path(api, 'stop'), json={})
    assert result.json() == {'active': False, 'liveViewStopUnconfirmed': False}
    before = len(api[3].requests)
    assert start(api, token).status_code == 409
    # New owned prepare never populated the legacy restore slot either.
    assert api[0].post(path(api, 'start'), json={}).status_code == 409
    assert len(api[3].requests) == before


def test_unknown_initial_stop_never_issues_permission(api):
    start_original(api)
    with patch.object(api[3], 'request', return_value=CcapiResponse(503, {}, b'{}')):
        assert api[0].post(path(api, 'stop'), json={'prepareRestore': True}).status_code == 409
    controls = api[1].maintenance_live_view(api[-1])
    assert not controls._permits and controls._current is None
    assert api[2].live_view_stop_unconfirmed
    assert api[0].post(path(api, 'start'), json={}).status_code == 409
    assert api[0].post(path(api, 'stop'), json={}).status_code == 200


def test_second_prepare_cannot_replace_unresolved_permit(api):
    token = prepare(api)
    before = len(api[3].requests)
    assert api[0].post(path(api, 'stop'), json={'prepareRestore': True}).status_code == 409
    assert len(api[3].requests) == before
    assert start(api, token).status_code == 200


def test_token_table_is_bounded_and_keeps_tombstones(api):
    controls = api[1].maintenance_live_view(api[-1])
    with patch('open_eos_bridge.maintenance_live_view.MAX_MAINTENANCE_RESTORE_TOKENS', 2):
        tokens = []
        for _ in range(2):
            tokens.append(prepare(api))
            assert stop(api, tokens[-1]).status_code == 200
        start_original(api)
        assert api[0].post(path(api, 'stop'), json={'prepareRestore': True}).status_code == 409
        assert len(controls._permits) == 2
        # The ceiling never prevents explicit global cleanup.
        assert api[0].post(path(api, 'stop'), json={}).status_code == 200
        assert all(start(api, token).status_code == 409 for token in tokens)


def test_queued_owned_start_is_cancelled_without_waiting_for_execution(api):
    token = prepare(api)
    camera = api[1]._bulb_requests[api[-1]]
    entered = threading.Event()
    original = camera._acquire_execution
    def waiting():
        entered.set()
        return original()
    before = len(api[3].requests)
    with ThreadPoolExecutor(max_workers=1) as pool:
        camera._execution.acquire()
        try:
            with patch.object(camera, '_acquire_execution', side_effect=waiting):
                pending = pool.submit(start, api, token)
                assert entered.wait(2)
                assert stop(api, token).json() == {'restoreToken': token, 'restoreOutcome': 'cancelled-before-start'}
        finally:
            camera._execution.release()
        assert pending.result(3).status_code == 409
    assert len(api[3].requests) == before


@pytest.mark.parametrize('owned', [False, True])
def test_dispatched_start_cannot_run_after_cleanup_stop_and_local_retirement(api, owned):
    if owned:
        token = prepare(api)
    else:
        start_original(api)
        assert api[0].post(path(api, 'stop')).status_code == 200
        token = None
    entered, resume, stop_called, stop_dispatched = (threading.Event() for _ in range(4))
    method = 'maintenance_start_owned_live_view' if owned else 'maintenance_start_live_view'
    original = getattr(api[2], method)
    def delayed(*args):
        entered.set()
        assert resume.wait(3)
        return original(*args)
    stop_method = 'maintenance_stop_owned_live_view' if owned else 'maintenance_stop_live_view'
    original_stop = getattr(api[2], stop_method)
    def observed_stop(*args):
        stop_dispatched.set()
        return original_stop(*args)
    def cleanup():
        stop_called.set()
        return stop(api, token) if owned else api[0].post(path(api, 'stop'))
    before = len(camera_controls(api[3]))
    with (patch.object(api[2], method, side_effect=delayed),
          patch.object(api[2], stop_method, side_effect=observed_stop),
          ThreadPoolExecutor(max_workers=2) as pool):
        pending_start = (pool.submit(start, api, token) if owned else
                         pool.submit(api[0].post, path(api, 'start'), json={}))
        assert entered.wait(2)
        pending_stop = pool.submit(cleanup)
        assert stop_called.wait(2)
        try:
            assert not stop_dispatched.wait(0.1), "Stop overtook admitted Start before provider dispatch"
            assert not pending_stop.done()
        finally:
            resume.set()
        assert pending_start.result(3).status_code == 200
        assert pending_stop.result(3).json()['active'] is False
    controls = camera_controls(api[3])[before:]
    assert [c[0] for c in controls] == ['POST', 'DELETE']
    assert not api[2].live_view_active and not api[2].live_view_stop_unconfirmed
    count = len(api[3].requests)
    assert api[0].delete(f'/v1/session/{api[-1]}/maintenance/control/session').status_code == 204
    assert len(api[3].requests) == count and api[2]._closed


def test_cleanup_remains_available_with_an_owned_bulb_duty(api):
    token = prepare(api)
    assert start(api, token).status_code == 200
    camera = api[1]._bulb_requests[api[-1]]
    # Model the manager-level unresolved duty left by a failed Bulb status tail.
    with camera._metadata:
        camera._owners['synthetic-bulb'] = 'dispatched'
        camera._active_owner = 'synthetic-bulb'
    assert stop(api, token).json()['active'] is False
    assert camera._active_owner == 'synthetic-bulb'


def test_lost_start_reply_is_not_replayed_and_keeps_cleanup_owner(api):
    token = prepare(api)
    original = api[2].maintenance_start_owned_live_view
    def lost(*args):
        original(*args)
        raise BridgeError('SYNTHETIC_LOST_REPLY', 'Synthetic response lost.', status_code=502)
    with patch.object(api[2], 'maintenance_start_owned_live_view', side_effect=lost):
        assert start(api, token).status_code == 502
    assert start(api, token).status_code == 409
    assert stop(api, token).json()['active'] is False


def test_lost_stop_reply_keeps_retryable_owner_and_does_not_claim_release(api):
    token = prepare(api)
    assert start(api, token).status_code == 200
    with patch.object(api[3], 'request', return_value=CcapiResponse(503, {}, b'{}')):
        result = stop(api, token)
        assert result.status_code == 409
    assert api[2].live_view_stop_unconfirmed
    assert stop(api, token).json()['active'] is False
    assert stop(api, token).json() == {'restoreToken': token, 'restoreOutcome': 'stopped'}


def test_retired_controls_cannot_start_on_a_new_same_identifier_session(api):
    token = prepare(api)
    controls = api[1].maintenance_live_view(api[-1])
    before = len(api[3].requests)
    api[1].retire_maintenance(api[-1])
    with pytest.raises(BridgeError):
        controls.start(LiveViewStartRequest(), token)
    assert len(api[3].requests) == before
    assert start(api, token).status_code == 404


def test_refused_local_retirement_fences_prepared_token_but_keeps_explicit_stop_available(api):
    from open_eos_bridge.models import CameraFeature
    token = prepare(api)
    api[2]._short_control_release = (None, None, CameraFeature.AUTOFOCUS)
    try:
        assert api[0].delete(f'/v1/session/{api[-1]}/maintenance/control/session').status_code == 409
        assert start(api, token).status_code == 409
        assert stop(api, token).json() == {'restoreToken': token, 'restoreOutcome': 'cancelled-before-start'}
        assert api[0].post(path(api, 'stop'), json={}).status_code == 200
    finally:
        api[2]._short_control_release = None


def test_confirmed_restore_can_begin_new_journey_only_after_physical_stop(api):
    old = prepare(api)
    assert start(api, old).status_code == 200
    failed = CcapiResponse(503, {}, b'{}')
    with patch.object(api[3], 'request', return_value=failed):
        assert api[0].post(path(api, 'stop'), json={'prepareRestore': True}).status_code == 409
    controls = api[1].maintenance_live_view(api[-1])
    assert controls._current == old and len(controls._permits) == 1
    # Failed Stop removed active proof, so explicit recovery cannot invent source.
    assert stop(api, old).json()['active'] is False
    new = prepare(api)
    assert new != old
    assert start(api, new).status_code == 200
    before = len(api[3].requests)
    result = api[0].post(path(api, 'stop'), json={'prepareRestore': True})
    assert result.status_code == 200 and result.json()['restoreToken'] not in {old, new}
    assert len(api[3].requests) > before
    assert stop(api, new).json() == {'restoreToken': new, 'restoreOutcome': 'stopped'}


def test_no_token_cleanup_revokes_inflight_prepare_before_token_is_issued(api):
    start_original(api)
    entered, resume, cleanup_admitted = (threading.Event() for _ in range(3))
    original = api[2].maintenance_stop_owned_live_view
    camera = api[1]._bulb_requests[api[-1]]
    original_acquire = camera._acquire_execution
    def delayed(*args):
        entered.set()
        assert resume.wait(3)
        return original(*args)
    def waiting():
        cleanup_admitted.set()
        return original_acquire()
    with patch.object(api[2], 'maintenance_stop_owned_live_view', side_effect=delayed), \
            ThreadPoolExecutor(max_workers=2) as pool:
        pending = pool.submit(api[0].post, path(api, 'stop'), json={'prepareRestore': True})
        assert entered.wait(2)
        with patch.object(camera, '_acquire_execution', side_effect=waiting):
            cleanup = pool.submit(api[0].post, path(api, 'stop'), json={})
            assert cleanup_admitted.wait(2)
            resume.set()
            assert pending.result(3).status_code == 409
            assert cleanup.result(3).json() == {'active': False, 'liveViewStopUnconfirmed': False}
    assert not api[1].maintenance_live_view(api[-1])._permits
    assert not api[2].live_view_active


@pytest.mark.parametrize('ordinary', [False, True])
def test_global_cleanup_tombstones_queued_owned_start(api, ordinary):
    token = prepare(api)
    controls = api[1].maintenance_live_view(api[-1])
    camera = api[1]._bulb_requests[api[-1]]
    start_admitted, stop_admitted = threading.Event(), threading.Event()
    original = camera._acquire_execution
    def waiting():
        if not start_admitted.is_set():
            start_admitted.set()
        else:
            stop_admitted.set()
        return original()
    with ThreadPoolExecutor(max_workers=2) as pool:
        camera._execution.acquire()
        try:
            with patch.object(camera, '_acquire_execution', side_effect=waiting):
                pending = pool.submit(start, api, token)
                assert start_admitted.wait(2)
                target = f'/v1/session/{api[-1]}/liveview/stop' if ordinary else path(api, 'stop')
                cleanup = pool.submit(api[0].post, target, json={})
                assert stop_admitted.wait(2)
                assert controls._permits[token].state == 'cancelled-before-start'
        finally:
            camera._execution.release()
        assert pending.result(3).status_code == 409
        assert cleanup.result(3).json()['active'] is False
    assert not api[2].live_view_active


def test_retirement_fences_queued_start_before_provider_dispatch(api):
    token = prepare(api)
    controls = api[1].maintenance_live_view(api[-1])
    camera = api[1]._bulb_requests[api[-1]]
    start_admitted, retired = threading.Event(), threading.Event()
    original, original_retire = camera._acquire_execution, controls.retire
    def waiting():
        start_admitted.set()
        return original()
    def retire():
        original_retire()
        retired.set()
    before = len(api[3].requests)
    with ThreadPoolExecutor(max_workers=2) as pool:
        camera._execution.acquire()
        try:
            with (patch.object(camera, '_acquire_execution', side_effect=waiting),
                  patch.object(controls, 'retire', side_effect=retire)):
                pending = pool.submit(start, api, token)
                assert start_admitted.wait(2)
                retirement = pool.submit(api[1].retire_maintenance, api[-1])
                assert retired.wait(2)
        finally:
            camera._execution.release()
        assert pending.result(3).status_code == 409
        retirement.result(3)
    assert len(api[3].requests) == before and api[2]._closed


def test_refused_retirement_keeps_dispatched_owner_cleanup_retryable(api):
    from open_eos_bridge.models import CameraFeature
    token = prepare(api)
    assert start(api, token).status_code == 200
    api[2]._short_control_release = (None, None, CameraFeature.AUTOFOCUS)
    try:
        assert api[0].delete(f'/v1/session/{api[-1]}/maintenance/control/session').status_code == 409
        assert stop(api, token).json()['active'] is False
    finally:
        api[2]._short_control_release = None


def test_ordinary_stop_revokes_restore_after_an_unrelated_preview(api):
    token = prepare(api)
    ordinary = f'/v1/session/{api[-1]}/liveview'
    assert api[0].post(ordinary + '/start', json={'source': 'CCAPI_JPEG_POLLING'}).status_code == 200
    assert api[0].post(ordinary + '/stop').status_code == 200
    before = len(api[3].requests)
    assert start(api, token).status_code == 409
    assert len(api[3].requests) == before and not api[2].live_view_active


def test_ordinary_stop_waits_for_dispatched_owned_start(api):
    token = prepare(api)
    entered, resume, stop_dispatched = (threading.Event() for _ in range(3))
    original_start, original_stop = api[2].maintenance_start_owned_live_view, api[2].stop_live_view
    def delayed(*args):
        entered.set()
        assert resume.wait(3)
        return original_start(*args)
    def ordinary_stop():
        stop_dispatched.set()
        return original_stop()
    with (patch.object(api[2], 'maintenance_start_owned_live_view', side_effect=delayed),
          patch.object(api[2], 'stop_live_view', side_effect=ordinary_stop),
          ThreadPoolExecutor(max_workers=2) as pool):
        pending = pool.submit(start, api, token)
        assert entered.wait(2)
        cleanup = pool.submit(api[0].post, f'/v1/session/{api[-1]}/liveview/stop')
        try:
            assert not stop_dispatched.wait(0.1)
        finally:
            resume.set()
        assert pending.result(3).status_code == 200
        assert cleanup.result(3).json()['active'] is False
    assert not api[2].live_view_active
    assert stop(api, token).json() == {'restoreToken': token, 'restoreOutcome': 'stopped'}


def test_execution_deadline_never_replays_start_or_discards_cleanup_owner(api):
    token = prepare(api)
    camera = api[1]._bulb_requests[api[-1]]
    before = len(api[3].requests)
    with camera._execution, patch('open_eos_bridge.bulb.BULB_EXECUTION_WAIT_SECONDS', 0.01):
        assert start(api, token).status_code == 409
    assert len(api[3].requests) == before
    assert start(api, token).status_code == 409
    assert stop(api, token).json() == {'restoreToken': token, 'restoreOutcome': 'cancelled-before-start'}


def test_execution_deadline_leaves_dispatched_stop_retryable(api):
    token = prepare(api)
    assert start(api, token).status_code == 200
    camera = api[1]._bulb_requests[api[-1]]
    before = len(api[3].requests)
    with camera._execution, patch('open_eos_bridge.bulb.BULB_EXECUTION_WAIT_SECONDS', 0.01):
        assert stop(api, token).status_code == 409
    assert len(api[3].requests) == before and api[2].live_view_active
    assert stop(api, token).json()['active'] is False


@pytest.mark.parametrize('field', ['restoreToken', 'restore_token'])
@pytest.mark.parametrize('operation', ['start', 'stop', 'prepare'])
def test_explicit_null_token_never_falls_back_to_legacy_or_global_io(api, field, operation):
    if operation == 'start':
        start_original(api)
        assert api[0].post(path(api, 'stop'), json={}).status_code == 200
    elif operation == 'stop':
        token = prepare(api)
        assert start(api, token).status_code == 200
    else:
        start_original(api)
    before = len(api[3].requests)
    payload = {field: None}
    if operation == 'prepare':
        payload['prepareRestore'] = True
    result = api[0].post(path(api, 'start' if operation == 'start' else 'stop'), json=payload)
    assert result.status_code == 422
    assert len(api[3].requests) == before
    if operation == 'start':
        assert api[0].post(path(api, 'start'), json={}).status_code == 200
    elif operation == 'stop':
        assert api[2].live_view_active
        assert stop(api, token).json()['active'] is False
