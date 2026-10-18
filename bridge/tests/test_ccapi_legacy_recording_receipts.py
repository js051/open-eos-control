"""Authenticated compatibility REC API/session/urllib; only discovery and wire bytes are synthetic."""

import json
from copy import deepcopy
from email.message import Message
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse, CcapiSession, CcapiStreamResponse, UrllibCcapiTransport
from tests.test_ccapi import DISCOVERY, FakeCcapiTransport, RecordedRequest

PARTIAL = [
    ("partial_206", CcapiResponse(206, {}, b"{}"), False),
    ("range_200", CcapiResponse(200, {"Content-Range": "bytes 0-1/4"}, b"{}"), False),
    ("empty_range_200", CcapiResponse(200, {"cOnTeNt-RaNgE": ""}, b"{}"), False),
]
VARIANTS = PARTIAL + [
    ("complete_json_200", CcapiResponse(200, {}, b"{}"), True),
    ("complete_empty_204", CcapiResponse(204, {}, b""), True),
    ("complete_empty_200", CcapiResponse(200, {}, b""), True),
    ("complete_plain_200", CcapiResponse(200, {"Content-Type": "text/plain"}, b"Accepted"), True),
    ("complete_plain_202", CcapiResponse(202, {}, b"Accepted"), True),
    ("rejected_503", CcapiResponse(503, {}, b"busy"), False),
    ("rejected_range_503", CcapiResponse(503, {"Content-Range": ""}, b"busy"), False),
]


class Peer(FakeCcapiTransport):
    def __init__(self, method):
        discovery = deepcopy(DISCOVERY)
        for entry in discovery["ver100"]:
            if entry["path"] == "/shooting/control/recbutton":
                entry.pop("post")
                entry[method.lower()] = True
        super().__init__(discovery=discovery)
        self.response, self.apply = CcapiResponse(200, {}, b"{}"), True
        self.synthetic_recording = False
        self.commands = []
        self.http = UrllibCcapiTransport()

    def request(self, method, url, *, body=None, **kwargs):
        path = urlsplit(url).path
        if method in {"POST", "PUT"} and path.endswith("/shooting/control/recbutton"):
            payload = json.loads(body)
            self.requests.append(RecordedRequest(method, path, payload))
            self.commands.append([method, path, payload])
            if self.apply:
                self.synthetic_recording = payload["action"] == "start"
            raw = BytesIO(self.response.body)
            raw.status, raw.headers = self.response.status, Message()
            raw.headers["Content-Length"] = str(len(self.response.body))
            for name, value in self.response.headers.items():
                raw.headers[name] = value
            opener = Mock()
            opener.open.return_value = raw
            with (
                patch("open_eos_bridge.ccapi.urlopen", return_value=raw),
                patch("open_eos_bridge.ccapi.build_opener", return_value=opener),
            ):
                result = self.http.request(method, url, body=body, **kwargs)
            assert raw.closed
            return result
        return super().request(method, url, body=body, **kwargs)


def run(method, operation, variant, *, seed_cache=True, apply_partial=False):
    name, response, accepted = variant
    peer = Peer(method)
    desired = operation == "start"
    initial = not desired
    peer.synthetic_recording = initial
    # An obvious in-process fixture, never a real credential.
    token = "SYNTHETIC-LEGACY-REC-TEST"
    app = create_app(ccapi_engine=CcapiEngine(lambda *_: peer), token=token)
    with TestClient(app) as client:
        auth = {"Authorization": "Bearer " + token}
        created = client.post("/v1/session", headers=auth,
                              json={"engine": "ccapi", "ccapiUrl": "http://synthetic.invalid:8080"})
        assert created.status_code == 201, created.text
        base = "/v1/session/" + created.json()["id"]
        if seed_cache:
            setup = client.post(base + "/recording/" + ("start" if initial else "stop"), headers=auth)
            assert setup.status_code == 200 and setup.json()["recording"] is initial, setup.text
        before = len(peer.commands)
        assert before == int(seed_cache)
        denied = client.post(base + "/recording/" + operation)
        assert denied.status_code == 401 and len(peer.commands) == before
        peer.response, peer.apply = response, accepted or apply_partial
        result = client.post(base + "/recording/" + operation, headers=auth)
        status = client.get(base + "/status", headers=auth)
        observation = client.get(base + "/recording/observation", headers=auth)
        caps = client.get(base + "/capabilities", headers=auth)
        assert status.status_code == observation.status_code == caps.status_code == 200
        assert observation.json()["recording"] is None
        assert observation.json()["observationEvidence"] == "unavailable"
        assert len(peer.commands) == before + 1, "Read-only follow-up must not replay REC"
        assert peer.commands[-1] == [method, "/ccapi/ver100/shooting/control/recbutton", {"action": operation}]
        return {
            "method": method, "operation": operation, "variant": name,
            "wire_status": response.status, "wire_headers": response.headers,
            "initial_cache": initial if seed_cache else None,
            "initial_peer_recording": initial, "peer_recording": peer.synthetic_recording,
            "applied": peer.apply, "api_status": result.status_code, "api_result": result.json(),
            "status_recording": status.json()["recording"], "observation": observation.json(),
            "feature_observed": "VIDEO_RECORDING" in caps.json()["evidence"]["observedFeatures"],
            "unauthenticated_status": denied.status_code, "mutation_count": len(peer.commands) - before,
            "command": peer.commands[-1],
        }


@pytest.mark.parametrize("method", ["POST", "PUT"])
@pytest.mark.parametrize("operation", ["start", "stop"])
@pytest.mark.parametrize("variant", VARIANTS, ids=lambda item: item[0])
def test_legacy_recording_requires_complete_original_ack(method, operation, variant):
    result = run(method, operation, variant)
    accepted = variant[2]
    assert result["api_status"] == (200 if accepted else 502), result
    expected = operation == "start" if accepted else result["initial_cache"]
    assert result["status_recording"] is expected, result
    assert result["peer_recording"] is expected, result
    assert result["feature_observed"] is True, "The earlier complete ACK remains observed"
    if accepted:
        assert result["api_result"]["recording"] is expected
    else:
        code = "CCAPI_HTTP_ERROR" if variant[1].status == 503 else "RECORDING_COMMAND_UNCONFIRMED"
        assert result["api_result"]["error"]["code"] == code, result


@pytest.mark.parametrize("method", ["POST", "PUT"])
@pytest.mark.parametrize("operation", ["start", "stop"])
@pytest.mark.parametrize("variant", PARTIAL, ids=lambda item: item[0])
@pytest.mark.parametrize("seed_cache", [False, True])
def test_partial_applied_recording_remains_unknown_and_does_not_assign_cache(method, operation, variant, seed_cache):
    result = run(method, operation, variant, seed_cache=seed_cache, apply_partial=True)
    assert result["api_status"] == 502, result
    assert result["api_result"]["error"]["code"] == "RECORDING_COMMAND_UNCONFIRMED", result
    assert result["api_result"]["error"]["feature"] == "VIDEO_RECORDING"
    assert result["status_recording"] is result["initial_cache"], result
    assert result["peer_recording"] is (operation == "start"), result
    assert result["feature_observed"] is seed_cache, result


@pytest.mark.parametrize(("method", "suffix"), [
    ("GET", "/shooting/control/recbutton"), ("DELETE", "/shooting/control/recbutton"),
    ("POST", "/shooting/control/recbutton/other"), ("PUT", "/shooting/control/recbutton-other"),
])
def test_recording_receipt_gate_does_not_cover_other_methods_or_paths(method, suffix):
    transport = Mock()
    transport.request.return_value = CcapiResponse(206, {"Content-Range": ""}, b"{}")
    CcapiSession("http://synthetic.invalid", transport)._request_ok(method, "/ccapi/ver100" + suffix)
    assert transport.request.call_count == 1


@pytest.mark.parametrize("method", ["POST", "PUT"])
def test_legacy_recording_keeps_existing_transport_deadline_and_redirect_policy(method):
    raw = BytesIO(b"Accepted")
    transport = UrllibCcapiTransport()
    with (
        patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, raw)) as opened,
        patch("open_eos_bridge.ccapi.time.monotonic") as clock,
    ):
        result = transport.request(method, "http://synthetic.invalid/ccapi/ver100/shooting/control/recbutton")
    assert result.body == b"Accepted"
    clock.assert_not_called()
    assert opened.call_args.kwargs["follow_redirects"] is True
    assert raw.closed
