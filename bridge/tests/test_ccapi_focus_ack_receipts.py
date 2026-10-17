"""Production API/session/urllib focus receipts; only discovery and wire bytes are synthetic."""

import json
from email.message import Message
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse, UrllibCcapiTransport
from tests.test_ccapi import FakeCcapiTransport, RecordedRequest

ROUTES = [
    ("drive", "/focus/drive", "/shooting/control/drivefocus", {"direction": "NEAR", "step": "MEDIUM"}, "FOCUS_DRIVE"),
    ("tap", "/focus/tap", "/shooting/liveview/afframeposition", {"x": 0.4, "y": 0.6}, "TAP_FOCUS"),
    ("white_balance", "/whitebalance/click", "/shooting/liveview/clickwb", {"x": 0.4, "y": 0.6}, "CLICK_WHITE_BALANCE"),
]
VARIANTS = [
    ("partial_206", CcapiResponse(206, {"content-length": "2"}, b"{}"), False),
    ("ranged_200", CcapiResponse(200, {"content-length": "2", "content-range": "bytes 0-1/4"}, b"{}"), False),
    ("empty_range_200", CcapiResponse(200, {"content-length": "2", "content-range": ""}, b"{}"), False),
    ("valid_json_200", CcapiResponse(200, {"content-length": "2"}, b"{}"), True),
    ("valid_empty_204", CcapiResponse(204, {"content-length": "0"}, b""), True),
    ("valid_plain_200", CcapiResponse(200, {"content-length": "8", "content-type": "text/plain"}, b"Accepted"), True),
    ("rejected_503", CcapiResponse(503, {"content-length": "4"}, b"busy"), False),
]


class Peer(FakeCcapiTransport):
    def __init__(self, suffix, response, apply):
        super().__init__(reject_live_view_size=False)
        self.suffix, self.response, self.apply = suffix, response, apply
        self.commands, self.applied = [], []
        self.http = UrllibCcapiTransport()

    def request(self, method, url, *, body=None, **kwargs):
        path = urlsplit(url).path
        if path.endswith(self.suffix) and method in {"PUT", "POST"}:
            payload = json.loads(body)
            self.requests.append(RecordedRequest(method, path, payload))
            self.commands.append([method, path, payload])
            if self.apply:
                self.applied.append([method, path, payload])
            raw = BytesIO(self.response.body)
            raw.status = self.response.status
            raw.headers = Message()
            for name, value in self.response.headers.items():
                raw.headers[name] = value
            opener = Mock()
            opener.open.return_value = raw
            with (
                patch("open_eos_bridge.ccapi.urlopen", return_value=raw),
                patch("open_eos_bridge.ccapi.build_opener", return_value=opener),
            ):
                return self.http.request(method, url, body=body, **kwargs)
        return super().request(method, url, body=body, **kwargs)


def run(route, variant):
    label, route_path, suffix, payload, feature = route
    name, wire_response, apply = variant
    peer = Peer(suffix, wire_response, apply)
    app = create_app(ccapi_engine=CcapiEngine(lambda *_: peer, sleeper=lambda _: None))
    with TestClient(app) as client:
        created = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://synthetic.invalid:8080"})
        assert created.status_code == 201, created.text
        base = "/v1/session/" + created.json()["id"]
        start = client.post(
            base + "/liveview/start", json={"fps": 15, "size": "MEDIUM", "source": "CCAPI_JPEG_POLLING"}
        )
        assert start.status_code == 200, start.text
        frame = client.get(base + "/liveview/frame")
        assert frame.status_code == 200, frame.text
        response = client.post(base + route_path, json=payload)
        assert len(peer.commands) == 1, peer.commands
        caps = client.get(base + "/capabilities").json()
        status = client.get(base + "/status")
        assert status.status_code == 200
        assert len(peer.commands) == 1, "Read-only follow-up must not replay mutation"
        result = {
            "control": label,
            "variant": name,
            "wire_status": wire_response.status,
            "range_present": "content-range" in wire_response.headers,
            "api_status": response.status_code,
            "api_result": response.json(),
            "feature_observed": feature in caps["evidence"]["observedFeatures"],
            "synthetic_mutation_applied": bool(peer.applied),
            "mutation_calls_after_status_and_capabilities": len(peer.commands),
            "commands": peer.commands,
        }
        client.delete(base)
        return result


@pytest.mark.parametrize("route", ROUTES, ids=lambda item: item[0])
@pytest.mark.parametrize("variant", VARIANTS, ids=lambda item: item[0])
def test_original_focus_receipt_controls_success_and_observed_evidence(route, variant):
    result = run(route, variant)
    accepted = variant[2]
    assert result["api_status"] == (200 if accepted else 502), result
    assert result["feature_observed"] is accepted, result
    assert result["mutation_calls_after_status_and_capabilities"] == 1
    assert result["synthetic_mutation_applied"] is accepted
    method, path, payload = result["commands"][0]
    assert method == ("PUT" if route[0] == "tap" else "POST")
    assert path.endswith(route[2])
    if route[0] == "drive":
        assert payload == {"value": "near2"}
    else:
        assert set(payload) == {"positionx", "positiony"}
    if accepted and route[0] != "white_balance":
        assert result["api_result"]["accepted"] is True
    elif not accepted:
        expected = "CCAPI_HTTP_ERROR" if variant[1].status == 503 else "INVALID_CCAPI_RESPONSE"
        assert result["api_result"]["error"]["code"] == expected


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/shooting/control/drivefocus"),
        ("PUT", "/shooting/control/drivefocus"),
        ("POST", "/shooting/liveview/afframeposition"),
        ("PUT", "/shooting/liveview/clickwb"),
        ("POST", "/shooting/liveview/clickwb/other"),
        ("GET", "/contents/card1/IMAGE.JPG"),
        ("POST", "/focus/drive"),
        ("POST", "/whitebalance/click"),
    ],
)
def test_unrelated_methods_paths_and_simulator_routes_keep_range_policy(method, path):
    from open_eos_bridge.ccapi import CcapiSession

    peer = Mock()
    peer.request.return_value = CcapiResponse(206, {"Content-Range": "bytes 0-1/4"}, b"{}")
    CcapiSession("http://synthetic.invalid", peer)._request_ok(method, "/ccapi/ver120" + path)
    assert peer.request.call_count == 1


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/shooting/control/drivefocus"),
        ("PUT", "/shooting/liveview/afframeposition"),
        ("POST", "/shooting/liveview/clickwb"),
    ],
)
@pytest.mark.parametrize(("status", "body"), [(200, b""), (202, b"Accepted")])
def test_other_legacy_full_success_forms_remain_valid(method, path, status, body):
    from open_eos_bridge.ccapi import CcapiSession

    peer = Mock()
    peer.request.return_value = CcapiResponse(status, {}, body)
    CcapiSession("http://synthetic.invalid", peer)._request_ok(method, "/ccapi/ver120" + path)
    assert peer.request.call_count == 1


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/shooting/control/drivefocus"),
        ("PUT", "/shooting/liveview/afframeposition"),
        ("POST", "/shooting/liveview/clickwb"),
    ],
)
def test_focus_receipt_check_does_not_add_a_transport_body_deadline(method, path):
    from open_eos_bridge.ccapi import CcapiStreamResponse

    raw = BytesIO(b"Accepted")
    peer = UrllibCcapiTransport()
    with (
        patch.object(peer, "_open", return_value=CcapiStreamResponse(200, {}, raw)) as opened,
        patch(
            "open_eos_bridge.ccapi.time.monotonic",
        ) as clock,
    ):
        assert peer.request(method, "http://synthetic.invalid/ccapi/ver120" + path).body == b"Accepted"
    clock.assert_not_called()
    assert opened.call_args.kwargs["follow_redirects"] is True
    assert raw.closed
