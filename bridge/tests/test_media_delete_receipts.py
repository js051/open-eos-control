from __future__ import annotations

from unittest.mock import patch
from urllib.parse import quote

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse, UrllibCcapiTransport
from open_eos_bridge.errors import BridgeError

from .test_ccapi import FakeCcapiTransport


@pytest.mark.parametrize(
    ("status", "headers", "body", "confirmed"),
    [
        (204, {}, b"", True),
        (204, {"content-length": "0"}, b"", True),
        (200, {}, b"", False),
        (201, {}, b"", False),
        (202, {}, b"", False),
        (204, {}, b"unexpected", False),
        (204, {"content-length": "1"}, b"", False),
        (204, {"content-length": "invalid"}, b"", False),
        (204, {"transfer-encoding": "chunked"}, b"", False),
        (204, {"content-length": "0", "transfer-encoding": "chunked"}, b"", False),
        (307, {"location": "/other"}, b"", False),
        (308, {"location": "/other"}, b"", False),
        (403, {}, b"protected", False),
        (408, {}, b"", False),
        (503, {}, b"", False),
    ],
)
def test_ccapi_delete_requires_exact_empty_receipt_without_readback_or_retry(status, headers, body, confirmed):
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _u, _p: transport).open_connection("http://192.168.1.2:8080")
    item = session.list_media()[0]
    with patch.object(transport, "request", return_value=CcapiResponse(status, headers, body)) as request:
        if confirmed:
            session.delete_media(item.id)
        else:
            with pytest.raises(BridgeError):
                session.delete_media(item.id)
        assert request.call_count == 1
        assert request.call_args.args[0] == "DELETE"
        assert request.call_args.kwargs["max_bytes"] == 64 * 1024
    assert (item.id not in session._media_cache) is confirmed


def test_delete_transport_uses_explicit_no_redirect_path():
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", side_effect=OSError("synthetic lost response")) as opened:
        with pytest.raises(OSError):
            transport.request("DELETE", "http://192.168.1.2:8080/ccapi/media/item")
        assert opened.call_count == 1
        assert opened.call_args.kwargs["follow_redirects"] is False


@pytest.mark.parametrize("status", [400, 401, 403, 404, 405, 409, 412, 413, 415, 422, 429])
def test_delete_preserves_complete_explicit_camera_refusal_without_generic_502(status):
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _u, _p: transport).open_connection("http://192.168.1.2:8080")
    item = session.list_media()[0]
    response = CcapiResponse(status, {"content-length": "3"}, b"no!")
    with patch.object(transport, "request", return_value=response) as request:
        with pytest.raises(BridgeError) as raised:
            session.delete_media(item.id)
        assert raised.value.status_code == status
        assert raised.value.code == "MEDIA_DELETE_REJECTED"
        assert request.call_count == 1
    assert item.id in session._media_cache


@pytest.mark.parametrize("headers", [{"content-length": "4"}, {"content-length": "invalid"},
                                     {"content-length": "3", "transfer-encoding": "chunked"}])
def test_delete_does_not_convert_invalid_refusal_framing_into_certain_rejection(headers):
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _u, _p: transport).open_connection("http://192.168.1.2:8080")
    item = session.list_media()[0]
    with patch.object(transport, "request", return_value=CcapiResponse(403, headers, b"no!")) as request:
        with pytest.raises(BridgeError) as raised:
            session.delete_media(item.id)
        assert raised.value.status_code == 502
        assert raised.value.code == "INVALID_CCAPI_RESPONSE"
        assert request.call_count == 1
    assert item.id in session._media_cache


@pytest.mark.parametrize(
    ("status", "headers", "body", "expected_status"),
    [(204, {}, b"", 204), (403, {"content-length": "3"}, b"no!", 403),
     (403, {"content-length": "4"}, b"no!", 502), (503, {}, b"error", 502),
     (204, {"content-length": "1"}, b"", 502)],
)
def test_real_bridge_route_preserves_only_valid_delete_receipts(status, headers, body, expected_status):
    transport = FakeCcapiTransport()
    application = create_app(ccapi_engine=CcapiEngine(lambda _u, _p: transport))
    with TestClient(application) as client:
        created = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://192.168.1.2:8080"})
        session_id = created.json()["id"]
        listing = client.get(f"/v1/session/{session_id}/media")
        target = listing.json()["items"][0]["id"]
        with patch.object(transport, "request", return_value=CcapiResponse(status, headers, body)) as request:
            response = client.delete(f"/v1/session/{session_id}/media/{quote(target, safe='')}")
            assert request.call_count == 1
            assert request.call_args.args[0] == "DELETE"
        assert response.status_code == expected_status
        if expected_status == 204:
            assert response.content == b""
        else:
            assert response.json()["error"]["code"] == (
                "MEDIA_DELETE_REJECTED" if expected_status == 403 else "INVALID_CCAPI_RESPONSE"
            )
