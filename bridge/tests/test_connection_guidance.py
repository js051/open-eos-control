"""Public Bridge contract with an actual loopback HTTP camera, never a physical camera."""
from __future__ import annotations

import json
import socket
import ssl
import threading
from collections import deque
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import URLError

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiSession, UrllibCcapiTransport
from open_eos_bridge.errors import BridgeError, ConnectionFailureReason

DISCOVERY_PATHS = ["/ccapi", "/ccapi/", "/ccapi/ver110/deviceinformation", "/ccapi/ver100/deviceinformation"]


@contextmanager
def camera_peer(responses):
    replies = deque(responses)
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler contract.
            requests.append(self.path)
            status, value = replies.popleft() if replies else (500, {"message": "Unexpected request"})
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", requests
        assert not replies, "The existing discovery fallback sequence must consume every expected response."
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        assert not thread.is_alive()


def test_camera_auth_survives_fallback_404_with_legacy_bridge_contract():
    with camera_peer([(401, {}), (404, {}), (404, {}), (404, {})]) as (origin, requests):
        engine = CcapiEngine(rtp_session_factory=None, route_resolver=lambda _: None)
        with TestClient(create_app(ccapi_engine=engine)) as client:
            response = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": origin})
    assert response.status_code == 502
    assert "www-authenticate" not in response.headers
    detail = response.json()["error"]
    assert set(detail) == {"code", "message", "engine", "connectionReason"}
    assert detail["code"] == "CCAPI_DISCOVERY_FAILED"
    assert detail["engine"] == "ccapi"
    assert detail["connectionReason"] == "AUTHENTICATION_REJECTED"
    assert detail["message"].startswith(f"Failed to discover a Canon CCAPI camera at {origin}.\n")
    assert "returned HTTP 401" in detail["message"]
    assert requests == DISCOVERY_PATHS


@pytest.mark.parametrize("status, reason", [(401, "AUTHENTICATION_REJECTED"), (403, "AUTHENTICATION_REJECTED"),
                                          (404, "ENDPOINT_NOT_FOUND"), (500, "HTTP_ERROR")])
def test_discovery_uses_actual_http_status_not_forged_body(status, reason):
    body = {"message": "401 Unauthorized TLS password error", "connectionReason": "AUTHENTICATION_REJECTED"}
    with camera_peer([(status, body)] * 4) as (origin, requests):
        session = CcapiSession(origin, UrllibCcapiTransport())
        with pytest.raises(BridgeError) as caught:
            session.initialize()
        session.close()
    assert caught.value.status_code == 502
    assert caught.value.code == "CCAPI_DISCOVERY_FAILED"
    assert caught.value.connection_reason == reason
    assert requests == DISCOVERY_PATHS


def test_successful_identity_fallback_wins_after_auth_rejection():
    identity = {"productname": "SYNTHETIC CAMERA", "version": "TEST"}
    with camera_peer([(401, {}), (404, {}), (200, identity)]) as (origin, requests):
        engine = CcapiEngine(rtp_session_factory=None, route_resolver=lambda _: None)
        with TestClient(create_app(ccapi_engine=engine)) as client:
            response = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": origin})
            assert response.status_code == 201
            assert "connectionReason" not in response.text
            assert client.delete(f"/v1/session/{response.json()['id']}").status_code == 204
    assert requests == DISCOVERY_PATHS[:3]


def test_new_discovery_does_not_reuse_previous_failure_reason():
    with camera_peer([(401, {}), (404, {}), (404, {}), (404, {})] + [(404, {})] * 4) as (origin, requests):
        session = CcapiSession(origin, UrllibCcapiTransport())
        for reason in ["AUTHENTICATION_REJECTED", "ENDPOINT_NOT_FOUND"]:
            with pytest.raises(BridgeError) as caught:
                session.initialize()
            assert caught.value.connection_reason == reason
        session.close()
    assert requests == DISCOVERY_PATHS * 2


def test_empty_api_lists_are_discovery_failure_even_if_fallbacks_are_missing():
    replies = [(200, {"ver100": []}), (404, {})] * 2 + [(404, {}), (404, {})]
    with camera_peer(replies) as (origin, _):
        session = CcapiSession(origin, UrllibCcapiTransport())
        with pytest.raises(BridgeError) as caught:
            session.initialize()
        session.close()
    assert caught.value.connection_reason == "DISCOVERY_FAILED"


@pytest.mark.parametrize("failure, expected", [
    (URLError(ssl.SSLError("synthetic TLS")), "TLS_ERROR"),
    (URLError(socket.gaierror("synthetic host")), "HOST_NOT_FOUND"),
    (URLError(TimeoutError("synthetic timeout")), "TIMEOUT"),
    (URLError(ConnectionRefusedError("synthetic refusal")), "UNREACHABLE"),
    (URLError("401 TLS certificate timeout"), "DISCOVERY_FAILED"),
])
def test_transport_reason_uses_exception_type_without_message_parsing(monkeypatch, failure, expected):
    attempts = []

    def fail(request, **_kwargs):
        attempts.append(request.full_url)
        raise failure

    monkeypatch.setattr("open_eos_bridge.ccapi.urlopen", fail)
    session = CcapiSession("http://camera.invalid", UrllibCcapiTransport())
    with pytest.raises(BridgeError) as caught:
        session.initialize()
    assert caught.value.connection_reason == expected
    assert len(attempts) == 4
    session.close()


def test_actual_bridge_token_401_keeps_its_original_payload():
    with TestClient(create_app(token="SYNTHETIC-TOKEN")) as client:
        response = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid"})
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {"error": {"code": "AUTHENTICATION_REQUIRED",
                                         "message": "Provide the configured desktop bridge Bearer token."}}


def test_invalid_url_does_not_gain_camera_auth_reason():
    with TestClient(create_app()) as client:
        response = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://camera.invalid/ccapi"})
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INVALID_CCAPI_URL"
    assert "connectionReason" not in response.json()["error"]


def test_reason_rejects_non_enum_diagnostic_text():
    error = BridgeError("SYNTHETIC", "Synthetic", connection_reason="401 Unauthorized")
    assert error.connection_reason is None
    error = BridgeError("SYNTHETIC", "Synthetic", connection_reason=ConnectionFailureReason.TIMEOUT)
    assert error.connection_reason == ConnectionFailureReason.TIMEOUT
