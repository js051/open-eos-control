from __future__ import annotations

from unittest.mock import patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse, UrllibCcapiTransport
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import FileNamingField

from .test_ccapi import DISCOVERY, FakeCcapiTransport

NAMING_PATHS = [
    '/functions/filename/stills/filename', '/functions/filename/stills/usersetting1',
    '/functions/filename/stills/usersetting2', '/functions/filename/movies/index',
    '/functions/filename/movies/reelnum', '/functions/filename/movies/clipnum',
    '/functions/filename/movies/userdefined',
]
DISCOVERY_CONFIG = {'ver100': [*DISCOVERY['ver100'],
    {'path': '/functions/directory/createdirectory', 'post': True},
    {'path': '/functions/directory/directoryselection', 'get': True, 'put': True},
    *({'path': path, 'get': True, 'put': True} for path in NAMING_PATHS)]}


class ConfigurationTransport(FakeCcapiTransport):
    def __init__(self):
        super().__init__(discovery=DISCOVERY_CONFIG)
        self.failure = None
        self.write_seen = False
        self.calls = []

    def request(self, method, url, **kwargs):
        path = urlsplit(url).path
        self.calls.append((method, path, kwargs))
        is_write = method in {'POST', 'PUT'} and ('/functions/filename/' in path or path.endswith('/createdirectory'))
        if self.write_seen and method == 'GET' and self.failure == 'read':
            raise OSError('synthetic read unavailable after acknowledgment')
        if is_write:
            self.write_seen = True
            if self.failure == 'lost':
                raise OSError('synthetic write response lost')
            if self.failure == 'duplicate':
                return CcapiResponse(200, {}, b'{"directoryname":"ABCDE","directoryname":"OTHER"}')
            if self.failure == 'framing':
                return CcapiResponse(200, {'content-length': 'invalid'}, b'{"directoryname":"ABCDE"}')
            if self.failure == 'bool':
                return CcapiResponse(200, {}, b'{"value":true}')
        response = super().request(method, url, **kwargs)
        if is_write and self.failure == 'mismatch':
            self.file_naming['movie-index'] = 'Z9'
        return response


def connected():
    transport = ConfigurationTransport()
    session = CcapiEngine(lambda _u, _p: transport).open_connection('http://192.0.2.1:8080')
    session.capabilities()
    transport.calls.clear()
    return session, transport


@pytest.mark.parametrize('operation', ['create-directory', 'set-file-naming'])
def test_exact_ack_retained_when_following_read_fails_without_another_write(operation):
    session, transport = connected()
    transport.failure = 'read'
    with pytest.raises(BridgeError) as failure:
        if operation == 'create-directory':
            session.create_directory('ABCDE')
        else:
            session.set_file_naming(FileNamingField.MOVIE_INDEX, 'B2')
    assert failure.value.code == 'CAPTURE_CONFIG_READBACK_FAILED'
    receipt = failure.value.capture_config_receipt
    assert receipt['operation'] == operation
    assert receipt['stage'] == 'acknowledged'
    expected = 'ABCDE' if operation == 'create-directory' else 'B2'
    assert receipt['requestedValue'] == receipt['acknowledgedValue'] == expected
    assert sum(method in {'POST', 'PUT'} for method, _, _ in transport.calls) == 1


@pytest.mark.parametrize('failure_mode', ['lost', 'duplicate', 'framing'])
def test_no_ack_receipt_from_lost_ambiguous_or_malformed_write_response(failure_mode):
    session, transport = connected()
    transport.failure = failure_mode
    with pytest.raises((BridgeError, OSError)) as failure:
        session.create_directory('ABCDE')
    assert getattr(failure.value, 'capture_config_receipt', None) is None
    assert len(transport.calls) == 1


def test_naming_boolean_is_not_an_integer_acknowledgment():
    session, transport = connected()
    transport.failure = 'bool'
    with pytest.raises(BridgeError) as failure:
        session.set_file_naming(FileNamingField.MOVIE_REEL_NUMBER, '1')
    assert failure.value.code == 'INVALID_CCAPI_RESPONSE'
    assert failure.value.capture_config_receipt is None
    assert sum(method == 'PUT' for method, _, _ in transport.calls) == 1


def test_mismatching_complete_naming_readback_keeps_ack_but_does_not_return_success():
    session, transport = connected()
    transport.failure = 'mismatch'
    with pytest.raises(BridgeError) as failure:
        session.set_file_naming(FileNamingField.MOVIE_INDEX, 'B2')
    assert failure.value.code == 'CAPTURE_CONFIG_READBACK_FAILED'
    assert failure.value.capture_config_receipt['acknowledgedValue'] == 'B2'
    assert sum(method == 'PUT' for method, _, _ in transport.calls) == 1


@pytest.mark.parametrize(('operation', 'expected_gets'), [('create-directory', 1), ('set-file-naming', 7)])
def test_explicit_recovery_bypasses_cache_and_performs_only_bounded_exact_reads(operation, expected_gets):
    session, transport = connected()
    transport.file_naming['movie-index'] = 'Z9'
    transport.directories = ['100EOSXX', '101ABCDE']
    result = session.read_capture_configuration(operation)
    assert result['operation'] == operation
    assert len(transport.calls) == expected_gets
    assert all(method == 'GET' for method, _, _ in transport.calls)
    assert all(0 < options['timeout'] <= 15 and options['max_bytes'] == 65536 for _, _, options in transport.calls)
    if operation == 'set-file-naming':
        assert result['fileNaming']['movieIndex'] == 'Z9'
    else:
        assert result['directorySelection']['ability'] == ['100EOSXX', '101ABCDE']


def test_total_recovery_deadline_stops_before_next_read():
    session, transport = connected()
    with patch('open_eos_bridge.ccapi.time.monotonic', side_effect=[0, 1, 16]), pytest.raises(BridgeError) as failure:
        session.read_capture_configuration('set-file-naming')
    assert failure.value.code == 'CAPTURE_CONFIG_READBACK_TIMEOUT'
    assert len(transport.calls) == 1
    assert transport.calls[0][0] == 'GET'


@pytest.mark.parametrize(('method', 'path'), [
    ('POST', '/ccapi/ver100/functions/directory/createdirectory'),
    ('PUT', '/ccapi/ver100/functions/filename/movies/index'),
    ('GET', '/ccapi/ver100/functions/directory/directoryselection'),
])
def test_configuration_transport_disables_redirects_without_changing_other_commands(method, path):
    transport = UrllibCcapiTransport()
    with patch.object(transport, '_open', side_effect=OSError('synthetic')) as opened:
        with pytest.raises(OSError):
            transport.request(method, f'http://192.0.2.1{path}')
        assert opened.call_args.kwargs['follow_redirects'] is False
    with patch.object(transport, '_open', side_effect=OSError('synthetic')) as opened:
        with pytest.raises(OSError):
            transport.request('GET', 'http://192.0.2.1/ccapi/ver100/devicestatus/battery')
        assert opened.call_args.kwargs['follow_redirects'] is True


def test_bridge_route_serializes_only_valid_same_operation_receipt_and_fresh_gets():
    transport = ConfigurationTransport()
    app = create_app(ccapi_engine=CcapiEngine(lambda _u, _p: transport))
    with TestClient(app) as client:
        created = client.post('/v1/session', json={'engine': 'ccapi', 'ccapiUrl': 'http://192.0.2.1:8080'})
        assert created.status_code == 201, created.text
        session_id = created.json()['id']
        client.get(f'/v1/session/{session_id}/capabilities')
        transport.calls.clear()
        transport.failure = 'read'
        failed = client.post(f'/v1/session/{session_id}/directories', json={'name': 'ABCDE'})
        assert failed.status_code == 502
        assert failed.json()['error']['captureConfigReceipt'] == {
            'version': 1, 'operation': 'create-directory', 'stage': 'acknowledged',
            'requestedValue': 'ABCDE', 'acknowledgedValue': 'ABCDE',
        }
        transport.failure = None
        transport.calls.clear()
        read = client.get(f'/v1/session/{session_id}/capture-configuration/create-directory')
        assert read.status_code == 200
        assert read.json()['directorySelection']['value'] in read.json()['directorySelection']['ability']
        assert len(transport.calls) == 1 and transport.calls[0][0] == 'GET'
        assert client.get(f'/v1/session/{session_id}/capture-configuration/unknown').status_code == 409


def test_valid_aggregate_selection_does_not_replace_failed_canonical_readback():
    session, transport = connected()
    original = transport.request

    def request(method, url, **kwargs):
        if transport.write_seen and url.endswith('/directoryselection'):
            raise OSError('synthetic canonical failure')
        if url.endswith('/shooting/settings'):
            return CcapiResponse(200, {}, b'{"directoryselection":{"value":"100EOSXX","ability":["100EOSXX"]}}')
        return original(method, url, **kwargs)

    transport.request = request
    with pytest.raises(BridgeError) as failure:
        session.create_directory('ABCDE')
    assert failure.value.code == 'CAPTURE_CONFIG_READBACK_FAILED'
    assert failure.value.capture_config_receipt['acknowledgedValue'] == 'ABCDE'
    assert sum(method == 'POST' for method, _, _ in transport.calls) == 1


def test_recovery_lock_wait_is_bounded_and_does_not_send_any_request():
    from unittest.mock import Mock
    session, transport = connected()
    lock = Mock()
    lock.acquire.return_value = False
    session._lock = lock
    with pytest.raises(BridgeError) as failure:
        session.read_capture_configuration('create-directory')
    assert failure.value.code == 'CAPTURE_CONFIG_READBACK_TIMEOUT'
    lock.acquire.assert_called_once_with(timeout=15.0)
    lock.release.assert_not_called()
    assert not transport.calls


def test_configuration_transport_rejects_late_single_body_chunk_and_closes():
    from io import BytesIO

    from open_eos_bridge.ccapi import CcapiStreamResponse
    body = BytesIO(b'{"directoryname":"ABCDE"}')
    response = CcapiStreamResponse(200, {}, body)
    transport = UrllibCcapiTransport()
    with patch.object(transport, '_open', return_value=response), patch(
        'open_eos_bridge.ccapi.time.monotonic', side_effect=[0, 1, 16],
    ), pytest.raises(BridgeError) as failure:
        transport.request('POST', 'http://192.0.2.1/ccapi/ver100/functions/directory/createdirectory')
    assert failure.value.code == 'CCAPI_UNREACHABLE'
    assert body.closed


def test_duplicate_post_ack_naming_read_fails_closed_but_retains_ack():
    session, transport = connected()
    original = transport.request

    def request(method, url, **kwargs):
        if transport.write_seen and method == 'GET' and url.endswith('/movies/index'):
            return CcapiResponse(200, {}, b'{"value":"A1","value":"B2"}')
        return original(method, url, **kwargs)

    transport.request = request
    with pytest.raises(BridgeError) as failure:
        session.set_file_naming(FileNamingField.MOVIE_INDEX, 'B2')
    assert failure.value.code == 'CAPTURE_CONFIG_READBACK_FAILED'
    assert failure.value.capture_config_receipt['acknowledgedValue'] == 'B2'
    assert sum(method == 'PUT' for method, _, _ in transport.calls) == 1


@pytest.mark.parametrize('body', [b'{"directoryname":"ABCDE","ignored":"\\ud800"}',
                                  b'{"directoryname":"ABCDE","ignored":NaN}'])
def test_invalid_json_scalar_anywhere_cannot_be_accepted_as_ack(body):
    session, transport = connected()
    transport.request = lambda *args, **kwargs: CcapiResponse(200, {}, body)
    with pytest.raises(BridgeError) as failure:
        session.create_directory('ABCDE')
    assert failure.value.code == 'INVALID_CCAPI_RESPONSE'
    assert failure.value.capture_config_receipt is None
