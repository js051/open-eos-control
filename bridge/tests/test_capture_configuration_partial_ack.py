"""Production API/session/urllib receipts; synthetic peers never contact a camera."""
from __future__ import annotations

import json
from email.message import Message
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, UrllibCcapiTransport

from .test_capture_configuration import ConfigurationTransport


class ReceiptPeer(ConfigurationTransport):
    def __init__(self, status, headers, *, apply=False):
        super().__init__()
        self.status, self.headers, self.apply = status, headers, apply
        self.mutations = []
        self.responses = []
        self.http = UrllibCcapiTransport()

    def request(self, method, url, **kwargs):
        path = urlsplit(url).path
        if method in {'POST', 'PUT'} and path.endswith(('/createdirectory', '/filename/movies/index')):
            self.mutations.append((method, path, json.loads(kwargs['body'])))
            if self.apply:
                body = super().request(method, url, **kwargs).body
            else:
                body = json.dumps({'directoryname': 'ABCDE'} if method == 'POST' else {'index': 'B2'}).encode()
            raw = BytesIO(body)
            raw.status = self.status
            raw.headers = Message()
            raw.headers['Content-Length'] = str(len(body))
            for name, value in self.headers:
                raw.headers[name] = value
            self.responses.append(raw)
            opener = Mock()
            opener.open.return_value = raw
            with patch('open_eos_bridge.ccapi.urlopen', return_value=raw), patch(
                'open_eos_bridge.ccapi.build_opener', return_value=opener,
            ):
                return self.http.request(method, url, **kwargs)
        return super().request(method, url, **kwargs)


def connect(client):
    response = client.post('/v1/session', json={'engine': 'ccapi', 'ccapiUrl': 'http://synthetic.invalid:8080'})
    assert response.status_code == 201
    base = '/v1/session/' + response.json()['id']
    assert client.get(base + '/capabilities').status_code == 200
    return base


def write(client, base, operation):
    if operation == 'create-directory':
        return client.post(base + '/directories', json={'name': 'ABCDE'})
    return client.put(base + '/file-naming/movie-index', json={'value': 'B2'})


def assert_one_original_write(peer, operation):
    expected = ('POST', '/ccapi/ver100/functions/directory/createdirectory', {'directoryname': 'ABCDE'}) \
        if operation == 'create-directory' else \
        ('PUT', '/ccapi/ver100/functions/filename/movies/index', {'index': 'B2'})
    assert peer.mutations == [expected]
    assert all(response.closed for response in peer.responses)


@pytest.mark.parametrize('operation', ['create-directory', 'set-file-naming'])
@pytest.mark.parametrize(('status', 'headers'), [
    (206, []), (200, [('Content-Range', 'bytes 0-24/80')]), (200, [('cOnTeNt-RaNgE', '')]),
])
def test_partial_capture_configuration_reply_never_publishes_ack_or_replays_write(operation, status, headers):
    peer = ReceiptPeer(status, headers)
    app = create_app(ccapi_engine=CcapiEngine(lambda *_: peer))
    with TestClient(app) as client:
        base = connect(client)
        peer.calls.clear()
        result = write(client, base, operation)
        assert result.status_code == 502, result.text
        assert result.json()['error']['code'] == 'INVALID_CCAPI_RESPONSE'
        assert result.json()['error'].get('captureConfigReceipt') is None
        # Failure is still unknown, not an ACK followed by a readback error.
        assert not peer.calls
        read = client.get(base + '/capture-configuration/' + operation)
        assert read.status_code == 200, read.text
        assert all(method == 'GET' for method, _, _ in peer.calls)
        if operation == 'create-directory':
            assert read.json()['directorySelection'] == {'value': '100EOSXX', 'ability': ['100EOSXX', '101EOSXX']}
        else:
            assert read.json()['fileNaming']['movieIndex'] == 'A_'
        assert_one_original_write(peer, operation)


@pytest.mark.parametrize('operation', ['create-directory', 'set-file-naming'])
@pytest.mark.parametrize('status', [200, 201, 202])
def test_complete_capture_configuration_receipts_preserve_existing_status_and_readback(operation, status):
    peer = ReceiptPeer(status, [], apply=True)
    app = create_app(ccapi_engine=CcapiEngine(lambda *_: peer))
    with TestClient(app) as client:
        base = connect(client)
        result = write(client, base, operation)
        assert result.status_code == 200, result.text
        if operation == 'create-directory':
            assert result.json() == {'name': 'ABCDE'}
            assert peer.directories == ['100EOSXX', '101EOSXX', '102ABCDE']
            assert peer.directory_selection == '102ABCDE'
        else:
            assert result.json()['movieIndex'] == 'B2'
        assert_one_original_write(peer, operation)


def test_complete_directory_ack_does_not_add_a_selection_or_full_name_equality_requirement():
    peer = ReceiptPeer(200, [])
    app = create_app(ccapi_engine=CcapiEngine(lambda *_: peer))
    with TestClient(app) as client:
        base = connect(client)
        result = write(client, base, 'create-directory')
        assert result.status_code == 200 and result.json() == {'name': 'ABCDE'}
        assert peer.directories == ['100EOSXX', '101EOSXX']
        assert_one_original_write(peer, 'create-directory')


@pytest.mark.parametrize('operation', ['create-directory', 'set-file-naming'])
def test_non_success_with_range_keeps_original_http_error_classification(operation):
    peer = ReceiptPeer(503, [('Content-Range', '')])
    app = create_app(ccapi_engine=CcapiEngine(lambda *_: peer))
    with TestClient(app) as client:
        base = connect(client)
        result = write(client, base, operation)
        assert result.status_code == 502
        assert result.json()['error']['code'] == 'CCAPI_HTTP_ERROR'
        assert result.json()['error'].get('captureConfigReceipt') is None
        assert_one_original_write(peer, operation)
