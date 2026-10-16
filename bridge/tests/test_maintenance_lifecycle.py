from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.maintenance import clock_matches, response_received, valid_clock, valid_reference

from .fakes import FakeRunner
from .test_ccapi import DISCOVERY, FakeCcapiTransport

VECTORS = json.loads((Path(__file__).resolve().parents[2] /
                     'contracts/maintenance-lifecycle/v1/maintenance-vectors.json').read_text())


@pytest.mark.parametrize('case', VECTORS['clock'], ids=lambda c: c['name'])
def test_shared_clock_contract(case):
    assert (valid_clock(case['observation']) and valid_reference(case['reference'])) is case['valid']
    assert bool(clock_matches(case['reference'], case['observation'])) is case['matched']


class MaintenanceTransport(FakeCcapiTransport):
    failure = None
    writes = 0

    def request(self, method, url, **options):
        path = urlsplit(url).path
        if method == 'GET' and path.endswith('/functions/datetime') and self.writes:
            if self.failure == 'read':
                raise BridgeError('SYNTHETIC_READ_FAILURE', 'Synthetic clock read unavailable.')
            if self.failure == 'duplicate':
                return CcapiResponse(200, {}, b'{"datetime":"bad","datetime":"bad","dst":false}')
            if self.failure == 'mismatch':
                self.camera_clock['dst'] = not self.camera_clock['dst']
        result = super().request(method, url, **options)
        if method in {'PUT', 'POST'} and path.endswith(('/functions/datetime', '/functions/sensorcleaning',
                                                      '/functions/autopoweroff')):
            self.writes += 1
            if self.failure == 'lost':
                raise BridgeError('SYNTHETIC_LOST_RESPONSE', 'Synthetic response was lost.')
            if self.failure == 'partial':
                return CcapiResponse(206, {'content-range': 'bytes 0-1/2'}, b'{}')
        return result


def ccapi():
    transport = MaintenanceTransport(discovery={"ver100": [*DISCOVERY["ver100"],
        {"path": "/functions/sensorcleaning", "post": True},
        {"path": "/functions/autopoweroff", "get": True, "put": True}]})
    session = CcapiEngine(lambda _u, _p: transport).open_connection('http://192.0.2.55:8080')
    session.capabilities()
    transport.requests.clear()
    return session, transport


@pytest.mark.parametrize('failure,evidence', [(None, 'matched'), ('read', 'unavailable'),
                                             ('duplicate', 'unavailable'), ('mismatch', 'mismatch')])
def test_ccapi_clock_response_survives_followup_without_status_or_retry(failure, evidence):
    session, transport = ccapi()
    transport.failure = failure
    with patch.object(session, 'status', side_effect=AssertionError('No broad status')):
        result = session.perform_maintenance_outcome('clock-sync', False)
    assert result['responseEvidence'] == 'response-received'
    assert result['clockReadEvidence'] == evidence
    assert transport.writes == 1
    assert all(r.path.endswith('/functions/datetime') for r in transport.requests)


@pytest.mark.parametrize('failure', ['lost', 'partial'])
def test_unknown_clock_write_never_replays_and_later_get_has_no_write_proof(failure):
    session, transport = ccapi()
    transport.failure = failure
    with pytest.raises(BridgeError):
        session.perform_maintenance_outcome('clock-sync', False)
    transport.failure = None
    result = session.read_maintenance('clock-sync')
    assert result['observationEvidence'] == 'observed'
    assert 'responseEvidence' not in result
    assert transport.writes == 1


@pytest.mark.parametrize('operation,auto', [('sensor-cleaning', False), ('sensor-cleaning', True), ('sleep', False)])
def test_power_maintenance_one_action_and_zero_automatic_followup(operation, auto):
    session, transport = ccapi()
    result = session.perform_maintenance_outcome(operation, auto)
    assert result['responseEvidence'] == 'response-received'
    assert result['clockReadEvidence'] == 'not-applicable'
    assert transport.writes == 1
    action = next(i for i, r in enumerate(transport.requests) if r.method in {'PUT', 'POST'})
    assert action == len(transport.requests) - 1
    assert not any(r.method == 'DELETE' for r in transport.requests)


@pytest.mark.parametrize('operation,auto', [('sleep', True), ('clock-sync', True), ('unknown', False)])
def test_invalid_intent_is_rejected_before_any_io(operation, auto):
    session, transport = ccapi()
    with pytest.raises(BridgeError):
        session.perform_maintenance_outcome(operation, auto)
    assert not transport.requests


@pytest.mark.parametrize('flag', ['_recording', '_bulb_exposure_active',
                                 '_live_view_active', '_live_view_stop_unconfirmed'])
def test_power_maintenance_keeps_stop_and_recording_gates(flag):
    session, transport = ccapi()
    setattr(session, flag, True)
    with pytest.raises(BridgeError):
        session.perform_maintenance_outcome('sensor-cleaning', False)
    assert not transport.requests


def test_required_identity_probe_does_not_use_optional_status_or_cache():
    session, transport = ccapi()
    with patch.object(session, 'status', side_effect=AssertionError('No status')):
        assert session.read_maintenance('sleep')['observationEvidence'] == 'reachable'
    assert len(transport.requests) == 1
    assert transport.requests[0].method == 'GET'
    assert transport.requests[0].path.endswith('/deviceinformation')
    with patch.object(transport, 'request', return_value=CcapiResponse(200, {}, b'{}')), pytest.raises(BridgeError):
        session.read_maintenance('sleep')


def gphoto():
    runner = FakeRunner()
    engine = GPhoto2Engine(runner)
    session = engine.open()
    session.capabilities()
    runner.commands.clear()
    return session, runner


def test_gphoto_scoped_clock_action_and_epoch_only_evidence():
    session, runner = gphoto()
    with patch.object(session, 'status', side_effect=AssertionError('No status')):
        result = session.perform_maintenance_outcome('clock-sync', False)
    assert result['responseEvidence'] == 'response-received'
    assert result['clockReadEvidence'] == 'matched'
    assert result['clockReference']['utcOffsetMinutes'] is None
    assert result['clockObservation']['daylightSaving'] is None
    assert sum('--set-config-value' in c for c in runner.commands) == 1
    assert all('--list-all-config' not in c for c in runner.commands)
    before = len(runner.commands)
    assert session.read_maintenance('clock-sync')['observationEvidence'] == 'observed'
    assert len(runner.commands) == before + 1
    assert '--get-config' in runner.commands[-1]


def test_gphoto_response_survives_failed_read_and_has_no_optimistic_cache():
    session, runner = gphoto()
    original = session._maintenance_clock_sample
    count = 0

    def reading(config, deadline):
        nonlocal count
        count += 1
        if count == 2:
            raise BridgeError('SYNTHETIC_READ_FAILURE', 'Synthetic post-action clock read failure.')
        return original(config, deadline)

    before = session._configs['/main/actions/syncdatetimeutc'].current
    with patch.object(session, '_maintenance_clock_sample', side_effect=reading):
        result = session.perform_maintenance_outcome('clock-sync', False)
    assert result['responseEvidence'] == 'response-received'
    assert result['clockReadEvidence'] == 'unavailable'
    assert session._configs['/main/actions/syncdatetimeutc'].current == before
    assert sum('--set-config-value' in c for c in runner.commands) == 1


def test_response_reference_survives_followup_error():
    reference = VECTORS['clock'][0]['reference']
    def failed():
        raise ValueError('synthetic')
    result = response_received('ccapi', 'clock-sync', False, reference, failed)
    assert result['clockReference'] == reference
    assert result['responseEvidence'] == 'response-received'
    assert result['clockReadEvidence'] == 'unavailable'


def test_additive_bridge_endpoints_and_strict_boolean_intent():
    app = create_app(engine=GPhoto2Engine(FakeRunner()))
    client = TestClient(app)
    identifier = client.post('/v1/session', json={}).json()['id']
    path = f'/v1/session/{identifier}/maintenance/clock-sync'
    for value in ['false', 0, 1, None]:
        assert client.post(path + '/outcome', json={'autoPowerOff': value}).status_code == 422
    result = client.post(path + '/outcome', json={'autoPowerOff': False})
    assert result.status_code == 200
    assert result.json()['responseEvidence'] == 'response-received'
    assert result.headers['cache-control'] == 'no-store'
    result = client.get(path)
    assert result.status_code == 200
    assert result.json()['observationEvidence'] == 'observed'
    session = app.state.session_manager.get(identifier)
    with patch.object(session, 'perform_maintenance_outcome', None), patch.object(session, 'sync_camera_clock') as old:
        assert client.post(path + '/outcome', json={'autoPowerOff': False}).status_code == 409
        old.assert_not_called()


def test_gphoto_reference_ends_after_verified_clock_read():
    session, _runner = gphoto()
    clock = {'now': 1700000000, 'reads': 0}

    def reading(_config, _deadline):
        clock['reads'] += 1
        if clock['reads'] == 2:
            clock['now'] += 12
        return {'epochSeconds': clock['now'], 'utcOffsetMinutes': None, 'daylightSaving': None}

    with patch('open_eos_bridge.gphoto2.time.time', side_effect=lambda: clock['now']), \
            patch.object(session, '_maintenance_clock_sample', side_effect=reading):
        result = session.perform_maintenance_outcome('clock-sync', False)
    assert result['clockReadEvidence'] == 'matched'
    assert result['clockReference']['latestEpochSeconds'] == 1700000022


@pytest.mark.parametrize('headers,body,status', [
    ({'content-length': '3'}, b'{}', 200),
    ({'content-length': '2', 'transfer-encoding': 'chunked'}, b'{}', 200),
    ({'content-range': 'bytes 0-1/2'}, b'{}', 200),
    ({'content-encoding': 'gzip'}, b'{}', 200),
    ({}, b'x' * 65537, 200),
    ({}, b'{}', 206),
    ({}, b'{}', 202),
])
def test_cleaning_response_requires_complete_exact_original_contract(headers, body, status):
    session, transport = ccapi()
    with (patch.object(transport, 'request', return_value=CcapiResponse(status, headers, body)) as request,
          pytest.raises(BridgeError)):
        session.perform_maintenance_outcome('sensor-cleaning', False)
    assert request.call_count == 1


def test_clock_exact_offset_cannot_be_replaced_by_same_instant():
    session, transport = ccapi()
    original = session._maintenance_clock_sample

    def wrong_offset(*args):
        observed = original(*args)
        observed['utcOffsetMinutes'] = 60 if observed['utcOffsetMinutes'] == 0 else 0
        return observed

    with patch.object(session, '_maintenance_clock_sample', side_effect=wrong_offset):
        result = session.perform_maintenance_outcome('clock-sync', False)
    assert result['responseEvidence'] == 'response-received'
    assert result['clockReadEvidence'] == 'mismatch'
    assert transport.writes == 1


def test_expired_required_read_deadline_never_returns_reachable_or_dispatches():
    session, transport = ccapi()
    with (patch('open_eos_bridge.ccapi.time.monotonic', side_effect=[0, 16]),
          pytest.raises(BridgeError, match='timed out')):
        session.read_maintenance('sleep')
    assert not transport.requests


@pytest.mark.parametrize('isdst', [0, 1])
@pytest.mark.parametrize('stamp', ['2026-01-15T12:00:00-05:00', '2026-07-15T12:00:00-04:00',
                                 '2026-11-01T01:59:59-04:00', '2026-11-01T01:00:00-05:00'])
def test_clock_reference_uses_host_dst_for_exact_requested_instant(stamp, isdst):
    from datetime import datetime
    from types import SimpleNamespace

    session, transport = ccapi()
    target = datetime.fromisoformat(stamp)
    with (patch('open_eos_bridge.ccapi.datetime') as clock,
          patch('open_eos_bridge.ccapi.time.localtime', return_value=SimpleNamespace(tm_isdst=isdst)) as local):
        clock.now.return_value.astimezone.return_value.replace.return_value = target
        result = session.perform_maintenance_outcome('clock-sync', False)
    local.assert_called_once_with(target.timestamp())
    assert result['clockReference']['daylightSaving'] is bool(isdst)
    assert result['clockReference']['utcOffsetMinutes'] == int(target.utcoffset().total_seconds() // 60)
    assert result['clockReadEvidence'] == 'matched'
    assert transport.writes == 1


def test_gphoto_read_only_recovery_never_stops_existing_movie_transport():
    session, runner = gphoto()
    original = object()
    session._live_view_stream = original
    session._live_view_active = True
    with (patch.object(session, '_stop_movie_stream', side_effect=AssertionError('No hidden stop')),
          pytest.raises(BridgeError, match='Pause active movie')):
        session.read_maintenance('clock-sync')
    assert session._live_view_stream is original
    assert session._live_view_active
    assert runner.commands == []
    session._live_view_stream = None
    with patch.object(session, '_stop_movie_stream', side_effect=AssertionError('No hidden stop')):
        assert session.read_maintenance('clock-sync')['observationEvidence'] == 'observed'
    assert len(runner.commands) == 1


def test_maintenance_restore_freezes_actual_source_and_does_not_retry_size_or_stop_after_uncertainty():
    from open_eos_bridge.models import LiveViewStartRequest

    session, transport = ccapi()
    session.start_live_view(LiveViewStartRequest(source='CCAPI_JPEG_POLLING', size='LARGE', fps=10))
    original_source = session.live_view_source
    session.maintenance_stop_live_view()
    transport.requests.clear()
    session.maintenance_start_live_view(LiveViewStartRequest(source='AUTO', size='SMALL', fps=1))
    assert session.live_view_source == original_source
    assert session.requested_fps == 10
    starts = [r for r in transport.requests if r.method == 'POST']
    assert len(starts) == 1
    session.maintenance_stop_live_view()
    transport.requests.clear()
    with (patch.object(transport, 'request', return_value=CcapiResponse(400, {}, b'{}')) as request,
          pytest.raises(BridgeError)):
        session.maintenance_start_live_view(LiveViewStartRequest(source='AUTO', fps=1))
    assert request.call_count <= 2  # Optional temperature GET plus exactly one Start, no cleanup Stop.
    assert session.live_view_stop_unconfirmed
    assert all(call.kwargs.get('strict_control') is True for call in request.call_args_list)


@pytest.mark.parametrize('status,body', [(200, b''), (201, b''), (202, b''), (204, b'{}')])
def test_maintenance_event_stop_requires_exact_empty_204(status, body):
    session, transport = ccapi()
    # The synthetic capability list explicitly advertises paired event routes.
    from open_eos_bridge.ccapi import CcapiOperation
    session._operations.update({CcapiOperation('GET', '/ccapi/ver100/event/polling'),
                                CcapiOperation('DELETE', '/ccapi/ver100/event/polling')})
    with (patch.object(transport, 'request', return_value=CcapiResponse(status, {}, body)) as request,
          pytest.raises(BridgeError)):
        session.maintenance_stop_events()
    assert request.call_count == 1
    assert request.call_args.kwargs['strict_control'] is True


def test_additive_controls_bridge_to_camera_and_unsupported_provider_never_falls_back():
    from open_eos_bridge.models import LiveViewStartRequest

    transport = MaintenanceTransport(discovery={"ver100": [*DISCOVERY["ver100"],
        {"path": "/functions/sensorcleaning", "post": True}]})
    app = create_app(ccapi_engine=CcapiEngine(lambda _url, _password: transport))
    with TestClient(app) as client:
        result = client.post('/v1/session', json={'engine': 'ccapi', 'ccapiUrl': 'http://192.0.2.55:8080'})
        assert result.status_code == 201
        base = '/v1/session/' + result.json()['id']
        request = LiveViewStartRequest(source='CCAPI_JPEG_POLLING').model_dump()
        assert client.post(base + '/liveview/start', json=request).status_code == 200
        stopped = client.post(base + '/maintenance/control/liveview/stop')
        assert stopped.status_code == 200
        assert stopped.json()['active'] is False
        assert stopped.json()['liveViewStopUnconfirmed'] is False
        assert client.delete(base + '/maintenance/control/events').status_code == 409
        cleaned = client.post(base + '/maintenance/sensor-cleaning/outcome', json={'autoPowerOff': False})
        assert cleaned.status_code == 200
        restored = client.post(base + '/maintenance/control/liveview/start',
                               json={'source': 'AUTO', 'size': 'SMALL', 'fps': 1})
        assert restored.status_code == 200
        assert restored.json()['active'] is True
        assert restored.json()['liveViewStopUnconfirmed'] is False
        assert restored.json()['source'] == 'CCAPI_JPEG_POLLING'
        assert restored.headers['cache-control'] == 'no-store'
        client.post(base + '/maintenance/control/liveview/stop')
    app = create_app(engine=GPhoto2Engine(FakeRunner()))
    with TestClient(app) as client:
        identifier = client.post('/v1/session', json={}).json()['id']
        session = app.state.session_manager.get(identifier)
        with patch.object(session, 'start_live_view') as legacy:
            result = client.post(f'/v1/session/{identifier}/maintenance/control/liveview/start', json={})
            assert result.status_code == 409
            legacy.assert_not_called()


def test_maintenance_urllib_control_uses_no_redirect_bounded_profile_without_marker_headers():
    from io import BytesIO

    from open_eos_bridge.ccapi import CcapiStreamResponse, UrllibCcapiTransport

    transport = UrllibCcapiTransport()
    with patch.object(transport, '_open', return_value=CcapiStreamResponse(204, {}, BytesIO(b''))) as opened:
        assert transport.request('POST', 'http://192.0.2.55/ccapi/ver100/shooting/liveview',
                                 body=b'{}', strict_control=True).status == 204
    assert opened.call_args.kwargs['follow_redirects'] is False
    assert opened.call_args.kwargs['strict_control'] is True
    assert opened.call_args.kwargs['headers'] is None


def test_explicit_retry_stop_cannot_downgrade_maintenance_unknown_obligation():
    from open_eos_bridge.models import LiveViewStartRequest

    session, transport = ccapi()
    session.start_live_view(LiveViewStartRequest(source='CCAPI_JPEG_POLLING'))
    session.maintenance_stop_live_view()
    with (patch.object(transport, 'request', return_value=CcapiResponse(400, {}, b'{}')),
          pytest.raises(BridgeError)):
        session.maintenance_start_live_view(LiveViewStartRequest())
    assert session._maintenance_stop_owned
    assert session.live_view_stop_unconfirmed
    with patch.object(transport, 'request', return_value=CcapiResponse(204, {}, b'')) as request:
        session.stop_live_view()  # Explicit existing Stop inherits the original strict obligation.
    assert request.call_count == 1
    assert request.call_args.kwargs['strict_control'] is True
    assert not session.live_view_stop_unconfirmed
    assert not session._maintenance_stop_owned


def test_strict_multipart_restore_503_has_one_get_no_delay_or_automatic_cleanup_stop():
    from open_eos_bridge.models import LiveViewStartRequest

    from .test_ccapi import MULTIPART_DISCOVERY

    transport = MaintenanceTransport(discovery=MULTIPART_DISCOVERY)
    session = CcapiEngine(lambda _url, _password: transport).open_connection('http://192.0.2.55:8080')
    session.capabilities()
    session.start_live_view(LiveViewStartRequest(source='CCAPI_MULTIPART'))
    session.maintenance_stop_live_view()
    transport.requests.clear()
    transport.multipart_start_statuses = [503]
    with (patch.object(session, '_sleep', side_effect=AssertionError('No retry delay')),
          pytest.raises(BridgeError) as caught):
        session.maintenance_start_live_view(LiveViewStartRequest())
    assert caught.value.code == 'CCAPI_HTTP_ERROR'
    assert sum(r.path.endswith('/liveview/multipart') and r.method == 'GET' for r in transport.requests) == 1
    assert sum(r.method == 'POST' for r in transport.requests) == 1
    assert not any(r.method == 'DELETE' for r in transport.requests)
    assert session.live_view_stop_unconfirmed
    session.close()

@pytest.mark.parametrize('pending', [False, True])
def test_maintenance_local_retire_removes_registry_and_never_sends_camera_cleanup(pending):
    from unittest.mock import Mock

    from open_eos_bridge.models import LiveViewStartRequest
    transport = MaintenanceTransport(discovery=DISCOVERY)
    app = create_app(ccapi_engine=CcapiEngine(lambda _url, _password: transport))
    with TestClient(app) as client:
        identifier = client.post('/v1/session', json={'engine': 'ccapi', 'ccapiUrl': 'http://192.0.2.55:8080'}).json()['id']
        session = app.state.session_manager.get(identifier)
        if pending:
            session.start_live_view(LiveViewStartRequest(source='CCAPI_JPEG_POLLING'))
        receiver = Mock()
        session._rtp_session = receiver
        before = len(transport.requests)
        with patch.object(session, 'close', wraps=session.close) as generic:
            retired = client.delete(f'/v1/session/{identifier}/maintenance/control/session')
            assert retired.status_code == 204 and retired.content == b''
            assert retired.headers['cache-control'] == 'no-store'
            assert len(transport.requests) == before
            assert session._closed and session._rtp_session is None
            assert session.live_view_stop_unconfirmed is pending
            receiver.close.assert_called_once()
            generic.assert_not_called()
            with pytest.raises(BridgeError, match='not found'):
                app.state.session_manager.get(identifier)
            app.state.session_manager.close_all()
            assert len(transport.requests) == before
            generic.assert_not_called()


def test_maintenance_retire_does_not_discard_shutter_release_or_fallback_provider():
    from open_eos_bridge.models import CameraFeature
    transport = MaintenanceTransport(discovery=DISCOVERY)
    app = create_app(ccapi_engine=CcapiEngine(lambda _url, _password: transport))
    with TestClient(app) as client:
        identifier = client.post('/v1/session', json={'engine': 'ccapi', 'ccapiUrl': 'http://192.0.2.55:8080'}).json()['id']
        session = app.state.session_manager.get(identifier)
        session._short_control_release = (None, None, CameraFeature.AUTOFOCUS)
        before = len(transport.requests)
        try:
            assert client.delete(f'/v1/session/{identifier}/maintenance/control/session').status_code == 409
            assert app.state.session_manager.get(identifier) is session
            assert not session._closed and len(transport.requests) == before
        finally:
            session._short_control_release = None
    app = create_app(engine=GPhoto2Engine(FakeRunner()))
    with TestClient(app) as client:
        identifier = client.post('/v1/session', json={}).json()['id']
        session = app.state.session_manager.get(identifier)
        with patch.object(session, 'close') as generic:
            assert client.delete(f'/v1/session/{identifier}/maintenance/control/session').status_code == 409
            generic.assert_not_called()
            assert app.state.session_manager.get(identifier) is session
