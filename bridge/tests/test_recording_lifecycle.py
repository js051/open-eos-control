from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

import pytest

from open_eos_bridge.recording import observation, ownership, valid_observation

from .test_maintenance_lifecycle import ccapi, gphoto

VECTORS = json.loads((Path(__file__).resolve().parents[2] /
                     'contracts/recording-lifecycle/v1/recording-vectors.json').read_text())


@pytest.mark.parametrize('case', VECTORS['observations'], ids=lambda c: c['name'])
def test_shared_observations(case):
    assert valid_observation(case['response'], 'ccapi') is case['valid']


@pytest.mark.parametrize('case', VECTORS['ownership'], ids=lambda c: c['name'])
def test_shared_ownership(case):
    assert ownership(case['input']) == case['expected']


def test_ccapi_complete_response_does_not_fabricate_fresh_state_or_status_read():
    session, transport = ccapi()
    with patch.object(session, 'status', side_effect=AssertionError('No broad status')):
        result = session.perform_recording_outcome('start')
        assert result['responseEvidence'] == 'response-received'
        assert result['observation'] == observation('ccapi')
        before = len(transport.requests)
        assert session.read_recording_observation() == observation('ccapi')
        assert len(transport.requests) == before
        assert session.perform_recording_outcome('start')['responseEvidence'] == 'not-sent'
        assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'
        assert session.perform_recording_outcome('start')['responseEvidence'] == 'response-received'


def test_ccapi_stop_keeps_original_advertised_route_after_capability_change():
    session, transport = ccapi()
    session.perform_recording_outcome('start')
    session._operations = [item for item in session._operations if not item.path.endswith('/recbutton')]
    assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'
    assert [entry.method for entry in transport.requests if entry.path.endswith('/recbutton')] == ['POST', 'POST']


def test_ccapi_stop_response_retirement_keeps_stop_only_route_after_capability_loss():
    session, transport = ccapi()
    session.perform_recording_outcome('start')
    # The server completed Stop, but the client may cancel before accepting its response.
    assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'
    assert session._recording_stop_operation is None
    session._operations = [item for item in session._operations if not item.path.endswith('/recbutton')]
    before = len(transport.requests)
    assert session.perform_recording_outcome('start')['responseEvidence'] == 'not-sent'
    assert not any(entry.path.endswith('/recbutton') for entry in transport.requests[before:])
    assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'
    assert [entry.body['action'] for entry in transport.requests
            if entry.path.endswith('/recbutton')] == ['start', 'stop', 'stop']


@pytest.mark.parametrize('release', ['ack', 'observation'])
def test_gphoto_stop_only_route_survives_retired_false_and_capability_loss(release):
    session, runner = gphoto()
    runner.values['/main/settings/movierecordtarget'] = 'None'
    session.perform_recording_outcome('start')
    if release == 'ack':
        assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'
    else:
        runner.values['/main/settings/movierecordtarget'] = 'None'
        assert session.read_recording_observation()['recording'] is False
    assert session._recording_stop_values is None
    before = len(runner.commands)
    with patch.object(session, '_recording_values', return_value=None), patch.object(
            session, '_maintenance_config', side_effect=OSError('synthetic optional read failure')):
        assert session.perform_recording_outcome('start')['responseEvidence'] == 'not-sent'
        assert len(runner.commands) == before
        assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'
    assert sum('--set-config-value' in args for args in runner.commands[before:]) == 1


def test_ccapi_lost_start_retains_stop_and_never_auto_replays():
    session, transport = ccapi()
    original = transport.request
    writes = []

    def lost(method, url, **options):
        if url.endswith('/recbutton'):
            writes.append(json.loads(options['body']))
            raise OSError('synthetic interrupted response')
        return original(method, url, **options)

    with patch.object(transport, 'request', side_effect=lost):
        assert session.perform_recording_outcome('start')['responseEvidence'] == 'unconfirmed'
        assert session.read_recording_observation()['recording'] is None
        assert session.perform_recording_outcome('start')['responseEvidence'] == 'not-sent'
    assert writes == [{'action': 'start'}]
    assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'


def test_gphoto_exact_fresh_config_noop_and_unknown_value():
    session, runner = gphoto()
    runner.values['/main/settings/movierecordtarget'] = 'None'
    value = session.read_recording_observation()
    assert value == observation('libgphoto2', False, 'gphoto-config')
    before = len(runner.commands)
    result = session.perform_recording_outcome('stop')
    assert result['responseEvidence'] == 'not-sent'
    assert result['observation']['recording'] is False
    assert all('--set-config-value' not in call for call in runner.commands[before:])
    runner.values['/main/settings/movierecordtarget'] = 'SDRAM'
    assert session.read_recording_observation()['observationEvidence'] == 'unavailable'


def test_gphoto_read_does_not_stop_owned_movie_stream():
    session, runner = gphoto()
    marker = object()
    session._live_view_stream = marker
    before = len(runner.commands)
    assert session.read_recording_observation()['observationEvidence'] == 'unavailable'
    assert len(runner.commands) == before and session._live_view_stream is marker


@pytest.mark.parametrize('status,body,expected', [
    (200, b'', 'response-received'), (200, b'{}', 'response-received'),
    (204, b'', 'response-received'), (204, b'{}', 'unconfirmed'),
    (200, b'{"x":1,"x":2}', 'unconfirmed'), (200, b'{"error":"busy"}', 'unconfirmed'),
    (200, b'null', 'unconfirmed'), (200, b'[]', 'unconfirmed'), (200, b'truncated', 'unconfirmed'),
    (201, b'{}', 'unconfirmed'), (202, b'{}', 'unconfirmed'), (205, b'', 'unconfirmed'),
    (206, b'{}', 'unconfirmed'), (307, b'', 'rejected'), (401, b'', 'rejected'),
    (403, b'', 'rejected'), (408, b'', 'rejected'), (421, b'', 'rejected'), (503, b'', 'rejected'),
])
def test_ccapi_native_ack_shape(status, body, expected):
    from open_eos_bridge.ccapi import CcapiResponse
    session, transport = ccapi()
    original = transport.request
    writes = []

    def reply(method, url, **options):
        if url.endswith('/recbutton'):
            writes.append(options)
            return CcapiResponse(status, {}, body)
        return original(method, url, **options)

    with patch.object(transport, 'request', side_effect=reply):
        result = session.perform_recording_outcome('start')
    assert result['responseEvidence'] == expected
    assert len(writes) == 1 and writes[0]['strict_control'] is True
    assert writes[0]['max_bytes'] == 32768 and writes[0]['timeout'] <= 10
    assert result['observation']['recording'] is None


def test_ccapi_failed_preflight_is_not_sent_and_has_zero_rec_writes():
    session, transport = ccapi()
    with patch.object(session, '_refresh_temperature_status', side_effect=OSError('synthetic no network')):
        assert session.perform_recording_outcome('start')['responseEvidence'] == 'not-sent'
    assert not any(request.path.endswith('/recbutton') for request in transport.requests)
    assert session._recording_stop_operation is None


def test_gphoto_lost_start_then_fresh_false_allows_new_deliberate_start():
    session, runner = gphoto()
    runner.values['/main/settings/movierecordtarget'] = 'None'
    original = runner.run

    def lost(arguments, **options):
        if '--set-config-value' in arguments:
            raise OSError('synthetic lost completion')
        return original(arguments, **options)

    with patch.object(runner, 'run', side_effect=lost):
        assert session.perform_recording_outcome('start')['responseEvidence'] == 'unconfirmed'
    before = len(runner.commands)
    assert session.read_recording_observation()['recording'] is False
    assert all('--set-config-value' not in args for args in runner.commands[before:])
    assert session.perform_recording_outcome('start')['responseEvidence'] == 'response-received'


def test_gphoto_retained_stop_survives_failed_readback_preflight():
    session, runner = gphoto()
    runner.values['/main/settings/movierecordtarget'] = 'None'
    assert session.perform_recording_outcome('start')['responseEvidence'] == 'response-received'
    before = len(runner.commands)
    with patch.object(session, '_maintenance_config', side_effect=OSError('synthetic read lost')):
        result = session.perform_recording_outcome('stop')
    assert result['responseEvidence'] == 'response-received'
    assert result['observation']['observationEvidence'] == 'read-failed'
    writes = [args for args in runner.commands[before:] if '--set-config-value' in args]
    assert len(writes) == 1 and writes[0][-1].endswith('=None')


@pytest.fixture
def actual_boundary():
    from fastapi.testclient import TestClient

    from open_eos_bridge.app import create_app
    from open_eos_bridge.ccapi import CcapiEngine

    from .ccapi_bulb_peer import BulbPeer
    peer = BulbPeer()
    peer.mode = 'Manual'
    peer.drop_press = False
    peer.reject_release = False
    peer.temperature_enabled = True
    with TestClient(create_app(ccapi_engine=CcapiEngine(sleeper=lambda _: None))) as client:
        session = client.post('/v1/session', json={'engine': 'ccapi', 'ccapiUrl': peer.origin})
        route = f"/v1/session/{session.json()['id']}"
        yield client, peer, route
    peer.close()


@pytest.mark.parametrize('status', [403, 503])
@pytest.mark.parametrize('fault', ['truncated', 'oversized', 'invalid_chunk'])
def test_real_http_rejection_preserves_header_without_body_replay(actual_boundary, status, fault):
    client, peer, route = actual_boundary
    peer.reject_recording = True
    peer.recording_rejection_status = status
    peer.recording_response_fault = fault
    result = client.post(f'{route}/recording/start/outcome')
    assert result.status_code == 200
    assert result.json()['responseEvidence'] == 'rejected'
    assert peer.recording is False and len(peer.commands) == 1


def test_real_boundary_lost_start_stop_read_do_not_replay_or_fake_observation(actual_boundary):
    client, peer, route = actual_boundary
    peer.drop_recording_response = True
    result = client.post(f'{route}/recording/start/outcome')
    assert result.json()['responseEvidence'] == 'unconfirmed' and peer.recording is True
    before = list(peer.reads)
    assert client.get(f'{route}/recording/observation').json() == observation('ccapi')
    assert peer.reads == before and len(peer.commands) == 1
    peer.drop_recording_response = False
    stopped = client.post(f'{route}/recording/stop/outcome')
    assert stopped.json()['responseEvidence'] == 'response-received'
    assert stopped.json()['observation']['recording'] is None
    assert peer.recording is False and len(peer.commands) == 2


def test_gphoto_preflight_failure_is_not_sent_without_runner_write():
    session, runner = gphoto()
    before = len(runner.commands)
    with patch.object(session, '_maintenance_config', side_effect=OSError('synthetic read lost')):
        result = session.perform_recording_outcome('start')
    assert result['responseEvidence'] == 'not-sent'
    assert all('--set-config-value' not in args for args in runner.commands[before:])


def test_gphoto_new_true_observation_retains_last_stop_route_after_ack():
    session, runner = gphoto()
    runner.values['/main/settings/movierecordtarget'] = 'None'
    session.perform_recording_outcome('start')
    session.perform_recording_outcome('stop')
    runner.values['/main/settings/movierecordtarget'] = 'Card'
    assert session.read_recording_observation()['recording'] is True
    with patch.object(session, '_recording_values', return_value=None):
        assert session.perform_recording_outcome('stop')['responseEvidence'] == 'response-received'


@pytest.mark.parametrize('source', [[], {}, False, 1, 'untrusted'])
def test_observation_rejects_invalid_source_type_without_throwing(source):
    value = {'version': 1, 'engine': 'ccapi', 'observationEvidence': 'observed',
             'recording': False, 'source': source}
    assert valid_observation(value, 'ccapi') is False


def test_observation_rejects_extra_fields():
    assert not valid_observation({**observation('ccapi'), 'extra': True}, 'ccapi')


@pytest.mark.parametrize('kind', [[], {}, False, 1, None])
def test_observation_rejects_invalid_evidence_type_without_throwing(kind):
    assert not valid_observation({**observation('ccapi'), 'observationEvidence': kind}, 'ccapi')


def test_gphoto_explicit_start_and_retained_stop_preserve_movie_control_policy():
    from .fakes import FakeMovieStream
    session, runner = gphoto()
    runner.values['/main/settings/movierecordtarget'] = 'None'
    for operation, target in [('start', 'Card'), ('stop', 'None')]:
        stream = FakeMovieStream()
        session._live_view_stream = stream
        result = session.perform_recording_outcome(operation)
        assert stream.closed is True
        assert result['responseEvidence'] == 'response-received'
        assert runner.values['/main/settings/movierecordtarget'] == target
        assert session._live_view_stream is None


def test_gphoto_failed_movie_close_retains_stream_and_stop_without_rec_write():
    session, runner = gphoto()
    runner.values['/main/settings/movierecordtarget'] = 'None'
    session.perform_recording_outcome('start')

    class FailedStream:
        def close(self):
            raise OSError('synthetic movie close failed')

    stream = FailedStream()
    session._live_view_stream = stream
    before = len(runner.commands)
    result = session.perform_recording_outcome('stop')
    assert result['responseEvidence'] == 'not-sent'
    assert session._live_view_stream is stream and session._recording_stop_values is not None
    assert len(runner.commands) == before
