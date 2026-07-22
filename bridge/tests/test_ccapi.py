from __future__ import annotations

import json
from dataclasses import dataclass
from io import BytesIO
from unittest.mock import patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import (
    CcapiEngine,
    CcapiResponse,
    CcapiStreamResponse,
    UrllibCcapiTransport,
    normalize_base_url,
)
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraFeature, LiveViewStartRequest

from .fakes import FakeRunner

JPEG = b"\xff\xd8open-eos-ccapi\xff\xd9"
MEDIA = b"camera-media"
DISCOVERY = {
    "ver100": [
        {"path": "/deviceinformation", "get": True},
        {"path": "/devicestatus/batterylist", "get": True},
        {"path": "/devicestatus/storage", "get": True},
        {"path": "/shooting/settings", "get": True},
        {"path": "/shooting/settings/iso", "put": True},
        {"path": "/shooting/settings/tv", "put": True},
        {"path": "/shooting/settings/av", "put": True},
        {"path": "/shooting/settings/wb", "put": True},
        {"path": "/shooting/control/shutterbutton", "post": True},
        {"path": "/shooting/control/shutterbutton/manual", "put": True},
        {"path": "/shooting/control/recbutton", "post": True},
        {"path": "/shooting/control/afpoint", "put": True},
        {"path": "/shooting/liveview", "get": True, "post": True, "delete": True},
        {"path": "/shooting/liveview/flip", "get": True},
        {"path": "/contents", "get": True, "delete": True},
    ]
}


@dataclass(frozen=True)
class RecordedRequest:
    method: str
    path: str
    body: dict[str, object] | None


class FakeCcapiTransport:
    def __init__(self, *, discovery: dict[str, object] | None = None, external_media: bool = False) -> None:
        self.discovery = discovery or DISCOVERY
        self.external_media = external_media
        self.requests: list[RecordedRequest] = []
        self.settings = {
            "iso": {"value": "800", "ability": ["100", "800", "1600"]},
            "tv": {"value": "1/50", "ability": ["1/50", "1/100"]},
            "av": {"value": "2.8", "ability": ["2.8", "4.0"]},
            "wb": {"value": "auto", "ability": ["auto", "daylight"]},
            "meteringmode": {"value": "evaluative", "ability": ["evaluative", "spot"]},
        }
        self.reject_live_view_size = True
        self.closed = False

    def request(
        self,
        method: str,
        url: str,
        *,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        timeout: float = 15.0,
        max_bytes: int = 2 * 1024 * 1024,
    ) -> CcapiResponse:
        del headers, timeout, max_bytes
        path = _request_path(url)
        payload = json.loads(body) if body else None
        self.requests.append(RecordedRequest(method, path, payload))
        if method == "GET" and path == "/ccapi":
            return _json_response(self.discovery)
        if method == "GET" and path == "/ccapi/ver100/deviceinformation":
            return _json_response(
                {
                    "productname": "Canon EOS R6 Mark III",
                    "serialnumber": "TEST-SERIAL-0001",
                    "version": "1.4.0",
                }
            )
        if method == "GET" and path == "/ccapi/ver100/devicestatus/batterylist":
            return _json_response({"batterylist": [{"level": 89, "quality": "good"}]})
        if method == "GET" and path == "/ccapi/ver100/devicestatus/storage":
            return _json_response({"storagelist": [{"name": "card1", "maxsize": 64_000, "spacesize": 32_000}]})
        if method == "GET" and path == "/ccapi/ver100/shooting/settings":
            return _json_response(self.settings)
        if method == "PUT" and path.startswith("/ccapi/ver100/shooting/settings/"):
            key = path.rsplit("/", 1)[-1]
            assert payload is not None
            self.settings[key]["value"] = payload["value"]
            return CcapiResponse(204, {}, b"")
        if method == "POST" and path == "/ccapi/ver100/shooting/liveview":
            if self.reject_live_view_size and payload and "liveviewsize" in payload:
                self.reject_live_view_size = False
                return _json_response({"message": "Invalid parameter"}, status=400)
            return CcapiResponse(204, {}, b"")
        if method == "GET" and path.startswith("/ccapi/ver100/shooting/liveview/flip?"):
            multipart = b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + JPEG + b"\r\n--frame\r\n"
            return CcapiResponse(200, {"content-type": "multipart/x-mixed-replace; boundary=frame"}, multipart)
        if method == "GET" and path == "/ccapi/ver100/contents?kind=number":
            return _json_response({"pagenumber": 1})
        if method == "GET" and path == "/ccapi/ver100/contents?page=1&order=desc":
            media_path = (
                "http://attacker.invalid/ccapi/ver100/contents/IMG_0001.JPG"
                if self.external_media
                else "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
            )
            return _json_response({"path": [media_path]})
        if method == "DELETE" and path.startswith("/ccapi/ver100/contents/"):
            return CcapiResponse(204, {}, b"")
        if path in {
            "/ccapi/ver100/shooting/control/shutterbutton",
            "/ccapi/ver100/shooting/control/shutterbutton/manual",
            "/ccapi/ver100/shooting/control/recbutton",
            "/ccapi/ver100/shooting/control/afpoint",
            "/ccapi/ver100/shooting/liveview",
        }:
            return CcapiResponse(204, {}, b"")
        return _json_response({"message": "not found"}, status=404)

    def open_stream(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        timeout: float = 60.0,
    ) -> CcapiStreamResponse:
        del headers, timeout
        path = _request_path(url)
        self.requests.append(RecordedRequest(method, path, None))
        if path.endswith("IMG_0001.JPG"):
            return CcapiStreamResponse(
                200,
                {"content-type": "application/json"},
                BytesIO(b'{"kind":"metadata"}'),
            )
        if path.endswith("IMG_0001.JPG?kind=main"):
            return CcapiStreamResponse(
                200,
                {"content-type": "image/jpeg", "content-length": str(len(MEDIA))},
                BytesIO(MEDIA),
            )
        return CcapiStreamResponse(404, {}, BytesIO(b"not found"))

    def close(self) -> None:
        self.closed = True


def test_ccapi_engine_runs_advertised_controls_live_view_and_media_end_to_end() -> None:
    transport = FakeCcapiTransport()
    credentials: list[tuple[str, str]] = []

    def factory(username: str, password: str) -> FakeCcapiTransport:
        credentials.append((username, password))
        return transport

    session = CcapiEngine(factory, sleeper=lambda _: None).open_connection(
        "http://192.168.1.2:8080/",
        "camera-user",
        "secret",
    )

    assert credentials == [("camera-user", "secret")]
    assert session.info().model == "Canon EOS R6 Mark III"
    status = session.status()
    capabilities = session.capabilities()
    assert status.battery.level == 89
    assert status.media.available is True
    assert status.exposure.shutter == "1/50"
    assert capabilities.profile.priority == "primary"
    assert capabilities.live_view.max_fps == 30
    assert {
        CameraFeature.STILL_CAPTURE,
        CameraFeature.TAP_FOCUS,
        CameraFeature.MEDIA_DOWNLOAD,
        CameraFeature.MEDIA_DELETE,
    } <= set(capabilities.supported)
    assert CameraFeature.MEDIA_THUMBNAIL not in capabilities.supported
    assert CameraFeature.MEDIA_THUMBNAIL in capabilities.planned
    assert next(item for item in capabilities.settings if item.key == "shutter").values == ["1/50", "1/100"]
    assert capabilities.evidence.source == "GET /ccapi"
    assert capabilities.evidence.protocol_versions == ["ver100"]
    assert "POST /ccapi/ver100/shooting/control/shutterbutton" in capabilities.evidence.advertised_commands
    assert "iso" in capabilities.evidence.writable_settings
    assert capabilities.evidence.truncated is False

    assert session.set_setting("iso", "1600").exposure.iso == "1600"
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("iso", "51200")
    session.capture_still()
    session.half_press_shutter()
    assert session.start_recording().recording is True
    assert session.stop_recording().recording is False
    focus = session.tap_focus(0.25, 0.75)
    assert (focus.accepted, focus.x, focus.y) == (True, 0.25, 0.75)

    session.start_live_view(LiveViewStartRequest(fps=15, size="LARGE", source="CCAPI_JPEG_POLLING"))
    assert session.requested_fps == 15
    assert session.live_view_frame() == JPEG
    media = session.list_media()
    assert media[0].name == "IMG_0001.JPG"
    assert "/" not in media[0].id
    item, chunks = session.download_media(media[0].id)
    assert item.size_bytes == len(MEDIA)
    assert b"".join(chunks) == MEDIA
    session.delete_media(media[0].id)
    session.stop_live_view()
    session.close()
    assert transport.closed is True

    command_paths = [request.path for request in transport.requests]
    assert command_paths.count("/ccapi/ver100/shooting/control/shutterbutton/manual") == 2
    assert RecordedRequest(
        "DELETE",
        "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG",
        None,
    ) in transport.requests
    live_starts = [
        request.body
        for request in transport.requests
        if request.path == "/ccapi/ver100/shooting/liveview" and request.method == "POST"
    ]
    assert live_starts == [
        {"cameradisplay": "on", "liveviewsize": "large"},
        {"cameradisplay": "on"},
    ]


def test_ccapi_capabilities_do_not_enable_unadvertised_commands() -> None:
    discovery = {"ver100": [{"path": "/deviceinformation", "get": True}]}
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    before = len(transport.requests)
    with pytest.raises(BridgeError) as failure:
        session.capture_still()
    with pytest.raises(BridgeError) as delete_failure:
        session.delete_media("ccapi:invalid")
    with pytest.raises(BridgeError) as thumbnail_failure:
        session.media_thumbnail("ccapi:invalid")

    assert CameraFeature.STILL_CAPTURE not in capabilities.supported
    assert CameraFeature.EXPOSURE_CONTROL not in capabilities.supported
    assert capabilities.settings == []
    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert delete_failure.value.code == "UNSUPPORTED_FEATURE"
    assert thumbnail_failure.value.code == "UNSUPPORTED_FEATURE"
    assert len(transport.requests) == before


def test_ccapi_capability_evidence_is_bounded_and_removes_queries() -> None:
    discovery = {
        "ver100": [
            {"path": f"/diagnostics/item{index}/{'x' * 600}?token=secret", "get": True}
            for index in range(300)
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    evidence = session.capabilities().evidence

    assert len(evidence.advertised_commands) == 256
    assert evidence.truncated is True
    assert all("?" not in command and "secret" not in command for command in evidence.advertised_commands)
    assert all(len(command) <= 512 for command in evidence.advertised_commands)


def test_ccapi_does_not_enable_wrong_method_shutter_or_incomplete_live_view() -> None:
    discovery = {
        "ver100": [
            {"path": "/deviceinformation", "get": True},
            {"path": "/shooting/control/shutterbutton", "put": True},
            {"path": "/shooting/liveview", "post": True},
            {"path": "/shooting/liveview/flip", "get": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    before = len(transport.requests)
    with pytest.raises(BridgeError):
        session.capture_still()
    with pytest.raises(BridgeError):
        session.start_live_view(LiveViewStartRequest(fps=15))

    assert CameraFeature.STILL_CAPTURE not in capabilities.supported
    assert CameraFeature.LIVE_VIEW not in capabilities.supported
    assert len(transport.requests) == before


def test_ccapi_media_rejects_cross_origin_camera_paths() -> None:
    transport = FakeCcapiTransport(external_media=True)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    with pytest.raises(BridgeError) as failure:
        session.list_media()

    assert failure.value.code == "INVALID_CAMERA_RESOURCE"


def test_ccapi_url_validation_rejects_credentials_and_non_origin_paths() -> None:
    assert normalize_base_url(" HTTP://192.168.1.2:8080/ ") == "http://192.168.1.2:8080"
    with pytest.raises(BridgeError, match="credentials"):
        normalize_base_url("http://user:secret@192.168.1.2:8080")
    with pytest.raises(BridgeError, match="camera origin"):
        normalize_base_url("http://192.168.1.2:8080/ccapi")


def test_urllib_transport_sends_basic_auth_without_putting_it_in_the_url() -> None:
    response = _UrlResponse(b"{}")
    with patch("open_eos_bridge.ccapi.urlopen", return_value=response) as request_call:
        transport = UrllibCcapiTransport("camera-user", "secret")
        transport.request("GET", "http://192.168.1.2:8080/ccapi")

    request = request_call.call_args.args[0]
    assert request.full_url == "http://192.168.1.2:8080/ccapi"
    assert request.get_header("Authorization").startswith("Basic ")


def test_bridge_api_creates_ccapi_session_and_never_echoes_camera_password() -> None:
    transport = FakeCcapiTransport()
    credentials: list[tuple[str, str]] = []

    def factory(username: str, password: str) -> FakeCcapiTransport:
        credentials.append((username, password))
        return transport

    headers = {"Authorization": "Bearer bridge-token"}
    app = create_app(
        engine=GPhoto2Engine(FakeRunner()),
        ccapi_engine=CcapiEngine(factory, sleeper=lambda _: None),
        token="bridge-token",
    )
    with TestClient(app) as client:
        health = client.get("/health")
        created = client.post(
            "/v1/session",
            headers=headers,
            json={
                "engine": "ccapi",
                "ccapiUrl": "http://192.168.1.2:8080",
                "ccapiUsername": "camera-user",
                "ccapiPassword": "camera-secret",
            },
        )
        session_id = created.json()["id"]
        capabilities = client.get(f"/v1/session/{session_id}/capabilities", headers=headers)
        focused = client.post(
            f"/v1/session/{session_id}/focus/tap",
            headers=headers,
            json={"x": 0.4, "y": 0.6},
        )
        duplicate = client.post(
            "/v1/session",
            headers=headers,
            json={"engine": "ccapi", "ccapiUrl": "http://192.168.1.2:8080"},
        )
        deleted = client.delete(f"/v1/session/{session_id}", headers=headers)

    assert health.json()["engines"]["ccapi"]["available"] is True
    assert created.status_code == 201
    assert created.json()["engine"] == "ccapi"
    assert "camera-secret" not in created.text
    assert "TAP_FOCUS" in capabilities.json()["supported"]
    assert focused.json() == {"accepted": True, "x": 0.4, "y": 0.6}
    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "CAMERA_BUSY"
    assert credentials == [("camera-user", "camera-secret"), ("", "")]
    assert deleted.status_code == 204


class _UrlResponse(BytesIO):
    status = 200
    headers = {"content-type": "application/json"}


def _request_path(url: str) -> str:
    parsed = urlsplit(url)
    return parsed.path + (f"?{parsed.query}" if parsed.query else "")


def _json_response(value: object, *, status: int = 200) -> CcapiResponse:
    return CcapiResponse(status, {"content-type": "application/json"}, json.dumps(value).encode())
