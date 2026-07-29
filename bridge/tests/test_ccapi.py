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
    MAX_MEDIA_PREVIEW_BYTES,
    MAX_MEDIA_THUMBNAIL_BYTES,
    CcapiEngine,
    CcapiResponse,
    CcapiStreamResponse,
    UrllibCcapiTransport,
    normalize_base_url,
)
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraFeature, LiveViewStartRequest
from open_eos_bridge.rtp import RtpError, RtpSessionDescription

from .fakes import FakeRunner

JPEG = b"\xff\xd8open-eos-ccapi\xff\xd9"
MEDIA = b"camera-media"
RTP_JPEG = b"\xff\xd8ccapi-rtp\xff\xd9"
RTP_SDP = """v=0
m=video 12000 RTP/AVP 96
a=rtpmap:96 H264/90000
m=audio 12002 RTP/AVP 97
a=rtpmap:97 MP4A-LATM/48000/2
"""
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
        {"path": "/shooting/settings/stillimagequality", "put": True},
        {"path": "/shooting/settings/wbshift", "put": True},
        {"path": "/shooting/control/shutterbutton", "post": True},
        {"path": "/shooting/control/shutterbutton/manual", "put": True},
        {"path": "/shooting/control/af", "post": True},
        {"path": "/shooting/control/recbutton", "post": True},
        {"path": "/shooting/liveview/afframeposition", "put": True},
        {"path": "/shooting/liveview/clickwb", "post": True},
        {"path": "/shooting/control/drivefocus", "post": True},
        {"path": "/shooting/liveview", "get": True, "post": True, "delete": True},
        {"path": "/shooting/liveview/flip", "get": True},
        {"path": "/shooting/liveview/flipdetail", "get": True},
        {"path": "/contents", "get": True, "delete": True},
    ]
}
RTP_DISCOVERY = {
    "ver100": [
        *DISCOVERY["ver100"],
        {"path": "/shooting/liveview/rtpsessiondesc", "get": True},
        {"path": "/shooting/liveview/rtp", "post": True},
    ]
}
EVENT_DISCOVERY = {
    "ver110": [{"path": "/event/polling", "get": True, "delete": True}],
    "ver100": [*DISCOVERY["ver100"]],
}


@dataclass(frozen=True)
class RecordedRequest:
    method: str
    path: str
    body: dict[str, object] | None


class FakeCcapiTransport:
    def __init__(
        self,
        *,
        discovery: dict[str, object] | None = None,
        external_media: bool = False,
        reject_autofocus_start: bool = False,
        reject_bulb_press: bool = False,
        reject_event_stop: bool = False,
        reject_rtp_start: bool = False,
        thumbnail_body: bytes = JPEG,
        thumbnail_content_type: str = "image/jpeg",
        preview_body: bytes = JPEG,
        preview_content_type: str = "image/jpeg",
    ) -> None:
        self.discovery = discovery or DISCOVERY
        self.external_media = external_media
        self.reject_autofocus_start = reject_autofocus_start
        self.reject_bulb_press = reject_bulb_press
        self.reject_event_stop = reject_event_stop
        self.reject_rtp_start = reject_rtp_start
        self.thumbnail_body = thumbnail_body
        self.thumbnail_content_type = thumbnail_content_type
        self.preview_body = preview_body
        self.preview_content_type = preview_content_type
        self.requests: list[RecordedRequest] = []
        self.settings = {
            "iso": {"value": "800", "ability": ["100", "800", "1600"]},
            "tv": {"value": "1/50", "ability": ["1/50", "1/100"]},
            "av": {"value": "2.8", "ability": ["2.8", "4.0"]},
            "wb": {"value": "auto", "ability": ["auto", "daylight"]},
            "meteringmode": {"value": "evaluative", "ability": ["evaluative", "spot"]},
            "stillimagequality": {
                "value": {"raw": "none", "jpeg": "large_fine"},
                "ability": {
                    "raw": ["none", "raw", "craw"],
                    "jpeg": ["none", "large_fine", "large_normal"],
                },
            },
            "wbshift": {
                "value": {"ba": 0, "mg": 0},
                "ability": {
                    "ba": {"min": -9, "max": 9, "step": 1},
                    "mg": {"min": -9, "max": 9, "step": 1},
                },
            },
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
        if method == "GET" and path == "/ccapi/ver110/event/polling?timeout=long":
            return _json_response({"shootingsettings": {"iso": {"value": "1600"}}})
        if method == "DELETE" and path == "/ccapi/ver110/event/polling":
            if self.reject_event_stop:
                return _json_response({"message": "event polling is still busy"}, status=503)
            return CcapiResponse(204, {}, b"")
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
        if method == "GET" and path == "/ccapi/ver100/shooting/liveview/rtpsessiondesc":
            return CcapiResponse(200, {"content-type": "application/sdp"}, RTP_SDP.encode())
        if method == "POST" and path == "/ccapi/ver100/shooting/liveview/rtp":
            if self.reject_rtp_start and payload == {"action": "start", "ipaddress": "192.168.1.20"}:
                return _json_response({"message": "RTP unavailable"}, status=503)
            return CcapiResponse(204, {}, b"")
        if method == "GET" and path.startswith("/ccapi/ver100/shooting/liveview/flip?"):
            multipart = b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + JPEG + b"\r\n--frame\r\n"
            return CcapiResponse(200, {"content-type": "multipart/x-mixed-replace; boundary=frame"}, multipart)
        if method == "GET" and path.startswith("/ccapi/ver100/shooting/liveview/flipdetail?kind=both"):
            info = {
                "liveview": {
                    "image": {
                        "positionx": 100,
                        "positiony": 200,
                        "positionwidth": 6000,
                        "positionheight": 4000,
                    }
                }
            }
            return CcapiResponse(
                200,
                {"content-type": "application/octet-stream"},
                _detailed_live_view(JPEG, info),
            )
        if method == "GET" and path == "/ccapi/ver100/contents?kind=number":
            return _json_response({"pagenumber": 1})
        if method == "GET" and path == "/ccapi/ver100/contents?page=1&order=desc":
            media_path = (
                "http://attacker.invalid/ccapi/ver100/contents/IMG_0001.JPG"
                if self.external_media
                else "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
            )
            return _json_response({"path": [media_path]})
        if method == "GET" and path.endswith("IMG_0001.JPG?kind=thumbnail"):
            return CcapiResponse(200, {"content-type": self.thumbnail_content_type}, self.thumbnail_body)
        if method == "GET" and path.endswith("IMG_0001.JPG?kind=display"):
            return CcapiResponse(200, {"content-type": self.preview_content_type}, self.preview_body)
        if method == "DELETE" and path.startswith("/ccapi/ver100/contents/"):
            return CcapiResponse(204, {}, b"")
        if (
            path == "/ccapi/ver100/shooting/control/af"
            and payload == {"action": "start"}
            and self.reject_autofocus_start
        ):
            return _json_response({"message": "focus failed"}, status=503)
        if (
            path == "/ccapi/ver100/shooting/control/shutterbutton/manual"
            and payload == {"af": False, "action": "full_press"}
            and self.reject_bulb_press
        ):
            return _json_response({"message": "press response lost"}, status=503)
        if path in {
            "/ccapi/ver100/shooting/control/shutterbutton",
            "/ccapi/ver100/shooting/control/shutterbutton/manual",
            "/ccapi/ver100/shooting/control/af",
            "/ccapi/ver100/shooting/control/recbutton",
            "/ccapi/ver100/shooting/liveview/afframeposition",
            "/ccapi/ver100/shooting/liveview/clickwb",
            "/ccapi/ver100/shooting/control/drivefocus",
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


class FakeRtpSession:
    def __init__(
        self,
        *,
        start_error: Exception | None = None,
        ready_error: Exception | None = None,
    ) -> None:
        self.source_url = "rtp://192.168.1.20:12000"
        self.last_error: str | None = None
        self.start_error = start_error
        self.ready_error = ready_error
        self.target_fps = 0
        self.started = False
        self.closed = False

    def start(self) -> None:
        if self.start_error is not None:
            raise self.start_error
        self.started = True

    def set_target_fps(self, fps: int) -> None:
        self.target_fps = fps

    def read_frame(self, timeout: float = 5.0) -> bytes:
        assert timeout == 5.0
        return RTP_JPEG

    def wait_until_ready(self, timeout: float = 5.0) -> None:
        assert timeout == 5.0
        if self.ready_error is not None:
            raise self.ready_error

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
        CameraFeature.BULB_EXPOSURE,
        CameraFeature.AUTOFOCUS,
        CameraFeature.TAP_FOCUS,
        CameraFeature.CLICK_WHITE_BALANCE,
        CameraFeature.FOCUS_DRIVE,
        CameraFeature.MEDIA_THUMBNAIL,
        CameraFeature.MEDIA_PREVIEW,
        CameraFeature.MEDIA_DOWNLOAD,
        CameraFeature.MEDIA_DELETE,
    } <= set(capabilities.supported)
    assert CameraFeature.MEDIA_THUMBNAIL not in capabilities.planned
    assert CameraFeature.MEDIA_PREVIEW not in capabilities.planned
    assert next(item for item in capabilities.settings if item.key == "shutter").values == ["1/50", "1/100"]
    assert next(item for item in capabilities.settings if item.key == "stillimagequality.raw").values == [
        "none",
        "raw",
        "craw",
    ]
    assert next(item for item in capabilities.settings if item.key == "stillimagequality.jpeg").value == "large_fine"
    assert next(item for item in capabilities.settings if item.key == "wbshift.ba").values == [
        str(value) for value in range(-9, 10)
    ]
    assert capabilities.evidence.source == "GET /ccapi"
    assert capabilities.evidence.protocol_versions == ["ver100"]
    assert "POST /ccapi/ver100/shooting/control/shutterbutton" in capabilities.evidence.advertised_commands
    assert "iso" in capabilities.evidence.writable_settings
    assert capabilities.evidence.truncated is False

    assert session.set_setting("iso", "1600").exposure.iso == "1600"
    session.set_setting("stillimagequality.raw", "raw")
    quality_write = next(
        request
        for request in transport.requests
        if request.method == "PUT" and request.path.endswith("/shooting/settings/stillimagequality")
    )
    assert quality_write.body == {"value": {"raw": "raw", "jpeg": "large_fine"}}
    session.set_setting("stillimagequality.raw", "none")
    with pytest.raises(BridgeError, match="At least one still image format"):
        session.set_setting("stillimagequality.jpeg", "none")
    session.set_setting("wbshift.ba", "9")
    wb_shift_write = next(
        request
        for request in transport.requests
        if request.method == "PUT" and request.path.endswith("/shooting/settings/wbshift")
    )
    assert wb_shift_write.body == {"value": {"ba": 9, "mg": 0}}
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("wbshift.ba", "10")
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("iso", "51200")
    session.capture_still()
    started_bulb = session.start_bulb_exposure()
    stopped_bulb = session.stop_bulb_exposure()
    assert started_bulb.bulb_exposure_active is True
    assert stopped_bulb.bulb_exposure_active is False
    bulb_commands = [
        request.body
        for request in transport.requests
        if request.path.endswith("/shooting/control/shutterbutton/manual")
        and request.body is not None
        and request.body.get("af") is False
    ]
    assert {"af": False, "action": "full_press"} in bulb_commands
    assert {"af": False, "action": "release"} in bulb_commands
    session.autofocus()
    session.half_press_shutter()
    assert session.start_recording().recording is True
    assert session.stop_recording().recording is False
    session.start_live_view(LiveViewStartRequest(fps=15, size="LARGE", source="CCAPI_JPEG_POLLING"))
    assert session.requested_fps == 15
    focus_drive = session.drive_focus("far", "large")
    assert (focus_drive.accepted, focus_drive.direction, focus_drive.step) == (True, "FAR", "LARGE")
    assert session.live_view_frame() == JPEG
    focus = session.tap_focus(0.25, 0.75)
    assert (focus.accepted, focus.x, focus.y) == (True, 0.25, 0.75)
    focus_request = next(
        request for request in transport.requests if request.path.endswith("/shooting/liveview/afframeposition")
    )
    assert focus_request.method == "PUT"
    assert focus_request.body == {"positionx": 1600, "positiony": 3200}
    click_status = session.click_white_balance(0.4, 0.6)
    assert click_status.connected is True
    click_request = next(
        request for request in transport.requests if request.path.endswith("/shooting/liveview/clickwb")
    )
    assert click_request.method == "POST"
    assert click_request.body == {"positionx": 2500, "positiony": 2600}
    media = session.list_media()
    assert media[0].name == "IMG_0001.JPG"
    assert "/" not in media[0].id
    assert media[0].preview_available is True
    thumbnail, thumbnail_type = session.media_thumbnail(media[0].id)
    assert thumbnail == JPEG
    assert thumbnail_type == "image/jpeg"
    preview, preview_type = session.media_preview(media[0].id)
    assert preview == JPEG
    assert preview_type == "image/jpeg"
    item, chunks = session.download_media(media[0].id)
    assert item.size_bytes == len(MEDIA)
    assert b"".join(chunks) == MEDIA
    session.delete_media(media[0].id)
    session.stop_live_view()
    observed = set(session.capabilities().evidence.observed_features)
    session.close()
    assert transport.closed is True
    assert {
        CameraFeature.DESKTOP_BRIDGE,
        CameraFeature.CAMERA_IDENTITY,
        CameraFeature.EXPOSURE_CONTROL,
        CameraFeature.STILL_CAPTURE,
        CameraFeature.BULB_EXPOSURE,
        CameraFeature.AUTOFOCUS,
        CameraFeature.SHUTTER_HALF_PRESS,
        CameraFeature.VIDEO_RECORDING,
        CameraFeature.TAP_FOCUS,
        CameraFeature.CLICK_WHITE_BALANCE,
        CameraFeature.FOCUS_DRIVE,
        CameraFeature.LIVE_VIEW,
        CameraFeature.LIVE_VIEW_JPEG_POLLING,
        CameraFeature.MEDIA_BROWSER,
        CameraFeature.MEDIA_THUMBNAIL,
        CameraFeature.MEDIA_PREVIEW,
        CameraFeature.MEDIA_DOWNLOAD,
        CameraFeature.MEDIA_DELETE,
    } <= observed
    command_paths = [request.path for request in transport.requests]
    assert command_paths.count("/ccapi/ver100/shooting/control/shutterbutton/manual") == 4
    assert [request.body for request in transport.requests if request.path == "/ccapi/ver100/shooting/control/af"] == [
        {"action": "start"},
        {"action": "stop"},
    ]
    assert (
        RecordedRequest(
            "POST",
            "/ccapi/ver100/shooting/control/drivefocus",
            {"value": "far3"},
        )
        in transport.requests
    )
    assert (
        RecordedRequest(
            "DELETE",
            "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG",
            None,
        )
        in transport.requests
    )
    live_starts = [
        request.body
        for request in transport.requests
        if request.path == "/ccapi/ver100/shooting/liveview" and request.method == "POST"
    ]
    assert live_starts == [
        {"cameradisplay": "on", "liveviewsize": "large"},
        {"cameradisplay": "on"},
    ]


def test_ccapi_close_releases_an_active_bulb_exposure() -> None:
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _username, _password: transport, sleeper=lambda _: None).open_connection(
        "http://192.168.1.2:8080/"
    )

    assert session.start_bulb_exposure().bulb_exposure_active is True
    session.close()

    manual_commands = [
        request.body
        for request in transport.requests
        if request.path.endswith("/shooting/control/shutterbutton/manual")
    ]
    assert manual_commands[-1] == {"af": False, "action": "release"}
    assert transport.closed is True


def test_ccapi_event_polling_uses_advertised_lifecycle_without_control_lock() -> None:
    transport = FakeCcapiTransport(discovery=EVENT_DISCOVERY)
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080/"
    )

    capabilities = session.capabilities()
    event = session.poll_event()
    session.stop_event_polling()

    assert CameraFeature.EVENT_POLLING in capabilities.supported
    assert CameraFeature.EVENT_POLLING not in capabilities.planned
    assert event.changed_keys == ["shootingsettings"]
    assert RecordedRequest(
        "GET",
        "/ccapi/ver110/event/polling?timeout=long",
        None,
    ) in transport.requests
    assert RecordedRequest(
        "DELETE",
        "/ccapi/ver110/event/polling",
        None,
    ) in transport.requests


def test_bridge_api_exposes_ccapi_events_and_stop() -> None:
    transport = FakeCcapiTransport(discovery=EVENT_DISCOVERY)
    application = create_app(
        engine=GPhoto2Engine(runner=FakeRunner()),
        ccapi_engine=CcapiEngine(lambda _username, _password: transport),
    )
    with TestClient(application) as api:
        created = api.post(
            "/v1/session",
            json={"engine": "ccapi", "ccapiUrl": "http://192.168.1.2:8080"},
        )
        session_id = created.json()["id"]

        event = api.get(f"/v1/session/{session_id}/events")
        stopped = api.delete(f"/v1/session/{session_id}/events")

    assert event.status_code == 200
    assert event.json() == {"changedKeys": ["shootingsettings"]}
    assert stopped.status_code == 204


def test_bridge_api_reports_ccapi_event_stop_failure() -> None:
    transport = FakeCcapiTransport(discovery=EVENT_DISCOVERY, reject_event_stop=True)
    application = create_app(
        engine=GPhoto2Engine(runner=FakeRunner()),
        ccapi_engine=CcapiEngine(lambda _username, _password: transport),
    )
    with TestClient(application) as api:
        created = api.post(
            "/v1/session",
            json={"engine": "ccapi", "ccapiUrl": "http://192.168.1.2:8080"},
        )
        session_id = created.json()["id"]

        stopped = api.delete(f"/v1/session/{session_id}/events")

    assert stopped.status_code == 502
    assert stopped.json()["error"]["code"] == "CCAPI_HTTP_ERROR"


def test_failed_ccapi_bulb_press_still_attempts_release() -> None:
    transport = FakeCcapiTransport(reject_bulb_press=True)
    session = CcapiEngine(lambda _username, _password: transport, sleeper=lambda _: None).open_connection(
        "http://192.168.1.2:8080/"
    )

    with pytest.raises(BridgeError):
        session.start_bulb_exposure()

    manual_commands = [
        request.body
        for request in transport.requests
        if request.path.endswith("/shooting/control/shutterbutton/manual")
    ]
    assert manual_commands == [
        {"af": False, "action": "full_press"},
        {"af": False, "action": "release"},
    ]
    assert session.status().bulb_exposure_active is False


def test_ccapi_rtp_capability_and_exact_lifecycle_are_end_to_end() -> None:
    transport = FakeCcapiTransport(discovery=RTP_DISCOVERY)
    rtp_sessions: list[FakeRtpSession] = []
    descriptions: list[tuple[RtpSessionDescription, str]] = []

    def rtp_factory(description: RtpSessionDescription, destination: str) -> FakeRtpSession:
        descriptions.append((description, destination))
        result = FakeRtpSession()
        rtp_sessions.append(result)
        return result

    session = CcapiEngine(
        lambda _username, _password: transport,
        rtp_session_factory=rtp_factory,
        route_resolver=lambda _url: "192.168.1.20",
    ).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    assert CameraFeature.LIVE_VIEW_RTP in capabilities.supported
    assert capabilities.live_view.sources == ["CCAPI_RTP", "CCAPI_JPEG_POLLING"]
    assert capabilities.live_view.default_source == "CCAPI_RTP"

    session.start_live_view(LiveViewStartRequest(fps=24, source="CCAPI_RTP"))
    assert descriptions[0][0].video.codec == "H264"
    assert descriptions[0][1] == "192.168.1.20"
    assert rtp_sessions[0].started is True
    assert rtp_sessions[0].target_fps == 24
    assert session.live_view_source == "CCAPI_RTP"
    assert session.status().raw["rtpSource"] == "rtp://192.168.1.20:12000"
    assert session.live_view_frame() == RTP_JPEG

    focus = session.tap_focus(0.25, 0.75)
    assert focus.accepted is True
    session.stop_live_view()
    assert rtp_sessions[0].closed is True

    rtp_requests = [request for request in transport.requests if request.path.endswith("/shooting/liveview/rtp")]
    assert [(request.method, request.body) for request in rtp_requests] == [
        ("POST", {"action": "start", "ipaddress": "192.168.1.20"}),
        ("POST", {"action": "stop", "ipaddress": ""}),
    ]
    assert any(request.path.endswith("/shooting/liveview/rtpsessiondesc") for request in transport.requests)
    assert not any(
        request.path == "/ccapi/ver100/shooting/liveview" and request.method == "POST" for request in transport.requests
    )


def test_ccapi_auto_falls_back_to_jpeg_when_local_rtp_start_fails() -> None:
    transport = FakeCcapiTransport(discovery=RTP_DISCOVERY)
    failed_session = FakeRtpSession(start_error=RtpError("UDP bind failed"))
    session = CcapiEngine(
        lambda _username, _password: transport,
        rtp_session_factory=lambda _description, _destination: failed_session,
        route_resolver=lambda _url: "192.168.1.20",
        sleeper=lambda _: None,
    ).open_connection("http://192.168.1.2:8080")

    session.start_live_view(LiveViewStartRequest(fps=15, source="AUTO"))

    assert failed_session.closed is True
    assert session.live_view_source == "CCAPI_JPEG_POLLING"
    assert session.live_view_frame() == JPEG
    assert any(
        request.path == "/ccapi/ver100/shooting/liveview" and request.method == "POST" for request in transport.requests
    )
    assert not any(request.path.endswith("/shooting/liveview/rtp") for request in transport.requests)
    session.stop_live_view()


def test_ccapi_rtp_http_start_failure_closes_receiver_and_sends_stop() -> None:
    transport = FakeCcapiTransport(discovery=RTP_DISCOVERY, reject_rtp_start=True)
    rtp_session = FakeRtpSession()
    session = CcapiEngine(
        lambda _username, _password: transport,
        rtp_session_factory=lambda _description, _destination: rtp_session,
        route_resolver=lambda _url: "192.168.1.20",
    ).open_connection("http://192.168.1.2:8080")

    with pytest.raises(BridgeError) as failure:
        session.start_live_view(LiveViewStartRequest(source="CCAPI_RTP"))

    assert failure.value.code == "CCAPI_HTTP_ERROR"
    assert rtp_session.closed is True
    rtp_requests = [request for request in transport.requests if request.path.endswith("/shooting/liveview/rtp")]
    assert [request.body for request in rtp_requests] == [
        {"action": "start", "ipaddress": "192.168.1.20"},
        {"action": "stop", "ipaddress": ""},
    ]


def test_ccapi_auto_stops_and_falls_back_when_rtp_never_decodes_a_frame() -> None:
    transport = FakeCcapiTransport(discovery=RTP_DISCOVERY)
    rtp_session = FakeRtpSession(ready_error=RtpError("No decoded keyframe"))
    session = CcapiEngine(
        lambda _username, _password: transport,
        rtp_session_factory=lambda _description, _destination: rtp_session,
        route_resolver=lambda _url: "192.168.1.20",
        sleeper=lambda _: None,
    ).open_connection("http://192.168.1.2:8080")

    session.start_live_view(LiveViewStartRequest(source="AUTO"))

    assert rtp_session.closed is True
    assert session.live_view_source == "CCAPI_JPEG_POLLING"
    rtp_requests = [request for request in transport.requests if request.path.endswith("/shooting/liveview/rtp")]
    assert [request.body for request in rtp_requests] == [
        {"action": "start", "ipaddress": "192.168.1.20"},
        {"action": "stop", "ipaddress": ""},
    ]


def test_ccapi_rtp_is_not_advertised_without_a_real_decoder_or_route() -> None:
    transport = FakeCcapiTransport(discovery=RTP_DISCOVERY)
    session = CcapiEngine(
        lambda _username, _password: transport,
        rtp_session_factory=None,
        route_resolver=lambda _url: "192.168.1.20",
    ).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.LIVE_VIEW_RTP not in capabilities.supported
    assert CameraFeature.LIVE_VIEW_RTP in capabilities.planned
    assert capabilities.live_view.sources == ["CCAPI_JPEG_POLLING"]
    assert "decoder" in capabilities.reasons[CameraFeature.LIVE_VIEW_RTP.value].casefold()


def test_ccapi_discovery_accepts_same_origin_url_entries_and_rejects_unsafe_operations() -> None:
    origin = "http://192.168.1.2:8080"
    discovery = {
        "ver100": [
            {"url": f"{origin}/ccapi/ver100/deviceinformation", "get": True},
            {"url": f"{origin}/ccapi/ver100/devicestatus/storage?token=secret", "get": True},
            {"url": f"{origin}/ccapi/ver100/shooting/settings", "get": True},
            {"url": f"{origin}/ccapi/ver100/shooting/settings/iso", "put": True},
            {"url": f"{origin}/ccapi/ver100/shooting/control/shutterbutton", "post": True},
            {
                "url": "http://attacker.invalid/ccapi/ver100/shooting/control/recbutton",
                "post": True,
            },
            {
                "url": f"{origin}/ccapi/ver100/ignored/../shooting/control/recbutton",
                "post": True,
            },
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection(origin)

    status = session.status()
    capabilities = session.capabilities()

    assert status.media.available is True
    assert CameraFeature.STORAGE_STATUS in capabilities.supported
    assert CameraFeature.EXPOSURE_CONTROL in capabilities.supported
    assert CameraFeature.STILL_CAPTURE in capabilities.supported
    assert CameraFeature.VIDEO_RECORDING not in capabilities.supported
    assert "GET /ccapi/ver100/devicestatus/storage" in capabilities.evidence.advertised_commands
    assert "POST /ccapi/ver100/shooting/control/shutterbutton" in capabilities.evidence.advertised_commands
    assert all(
        "secret" not in command and "attacker" not in command for command in capabilities.evidence.advertised_commands
    )


def test_ccapi_wb_shift_hides_malformed_or_unbounded_ranges() -> None:
    transport = FakeCcapiTransport()
    transport.settings["wbshift"] = {
        "value": {"ba": 0, "mg": 0},
        "ability": {
            "ba": {"min": -1000, "max": 1000, "step": 1},
            "mg": {"min": -9, "max": 9, "step": 0},
        },
    }
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert not any(setting.key.startswith("wbshift.") for setting in capabilities.settings)


@pytest.mark.parametrize("current", [{"ba": 0}, {"ba": 0.5, "mg": 0}])
def test_ccapi_wb_shift_requires_complete_integer_current_value(current: dict[str, object]) -> None:
    transport = FakeCcapiTransport()
    transport.settings["wbshift"] = {
        "value": current,
        "ability": {
            "ba": {"min": -9, "max": 9, "step": 1},
            "mg": {"min": -9, "max": 9, "step": 1},
        },
    }
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert not any(setting.key.startswith("wbshift.") for setting in capabilities.settings)


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
    with pytest.raises(BridgeError) as focus_failure:
        session.drive_focus("near", "small")

    assert CameraFeature.STILL_CAPTURE not in capabilities.supported
    assert CameraFeature.EXPOSURE_CONTROL not in capabilities.supported
    assert capabilities.settings == []
    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert delete_failure.value.code == "UNSUPPORTED_FEATURE"
    assert thumbnail_failure.value.code == "UNSUPPORTED_FEATURE"
    assert focus_failure.value.code == "UNSUPPORTED_FEATURE"
    observed = set(session.capabilities().evidence.observed_features)
    assert CameraFeature.STILL_CAPTURE not in observed
    assert CameraFeature.MEDIA_DELETE not in observed
    assert CameraFeature.FOCUS_DRIVE not in observed
    assert len(transport.requests) == before


def test_ccapi_incomplete_media_stream_is_not_recorded_as_observed() -> None:
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")
    media = session.list_media()
    _, chunks = session.download_media(media[0].id)

    stream = iter(chunks)
    assert next(stream) == MEDIA
    stream.close()

    observed = set(session.capabilities().evidence.observed_features)
    assert CameraFeature.MEDIA_BROWSER in observed
    assert CameraFeature.MEDIA_DOWNLOAD not in observed


def test_ccapi_focus_drive_rejects_invalid_values_without_a_camera_request() -> None:
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")
    before = len(transport.requests)

    with pytest.raises(BridgeError) as failure:
        session.drive_focus("sideways", "large")

    assert failure.value.code == "INVALID_FOCUS_DRIVE"
    assert len(transport.requests) == before


def test_failed_ccapi_autofocus_start_still_sends_stop() -> None:
    transport = FakeCcapiTransport(reject_autofocus_start=True)
    session = CcapiEngine(lambda _username, _password: transport, sleeper=lambda _: None).open_connection(
        "http://192.168.1.2:8080"
    )

    with pytest.raises(BridgeError):
        session.autofocus()

    actions = [request.body for request in transport.requests if request.path.endswith("/shooting/control/af")]
    assert actions == [{"action": "start"}, {"action": "stop"}]


def test_ccapi_capability_evidence_is_bounded_and_removes_queries() -> None:
    discovery = {
        "ver100": [{"path": f"/diagnostics/item{index}/{'x' * 600}?token=secret", "get": True} for index in range(300)]
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
            {"path": "/shooting/liveview/afframeposition", "put": True},
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
    assert CameraFeature.TAP_FOCUS not in capabilities.supported
    assert CameraFeature.TAP_FOCUS in capabilities.planned
    assert len(transport.requests) == before


def test_ccapi_tap_focus_without_detailed_frame_sends_no_command() -> None:
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")
    before = len(transport.requests)

    with pytest.raises(BridgeError) as failure:
        session.tap_focus(0.25, 0.75)

    assert failure.value.code == "LIVE_VIEW_COORDINATES_UNAVAILABLE"
    assert len(transport.requests) == before

    with pytest.raises(BridgeError) as click_failure:
        session.click_white_balance(0.25, 0.75)

    assert click_failure.value.code == "LIVE_VIEW_COORDINATES_UNAVAILABLE"
    assert len(transport.requests) == before


def test_ccapi_media_rejects_cross_origin_camera_paths() -> None:
    transport = FakeCcapiTransport(external_media=True)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    with pytest.raises(BridgeError) as failure:
        session.list_media()

    assert failure.value.code == "INVALID_CAMERA_RESOURCE"


@pytest.mark.parametrize(
    ("body", "content_type", "expected_code"),
    [
        (b'{"message":"not an image"}', "application/json", "INVALID_MEDIA_THUMBNAIL"),
        (b"x" * (MAX_MEDIA_THUMBNAIL_BYTES + 1), "image/jpeg", "CCAPI_RESPONSE_TOO_LARGE"),
    ],
    ids=["text", "oversized"],
)
def test_ccapi_thumbnail_rejects_text_and_oversized_payloads(
    body: bytes,
    content_type: str,
    expected_code: str,
) -> None:
    transport = FakeCcapiTransport(thumbnail_body=body, thumbnail_content_type=content_type)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")
    item = session.list_media()[0]

    with pytest.raises(BridgeError) as failure:
        session.media_thumbnail(item.id)

    assert failure.value.code == expected_code


@pytest.mark.parametrize(
    ("body", "content_type", "expected_code"),
    [
        (b'{"message":"not an image"}', "application/json", "INVALID_MEDIA_PREVIEW"),
        (b"x" * (MAX_MEDIA_PREVIEW_BYTES + 1), "image/jpeg", "CCAPI_RESPONSE_TOO_LARGE"),
    ],
    ids=["text", "oversized"],
)
def test_ccapi_preview_uses_display_query_and_rejects_invalid_payloads(
    body: bytes,
    content_type: str,
    expected_code: str,
) -> None:
    transport = FakeCcapiTransport(preview_body=body, preview_content_type=content_type)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")
    item = session.list_media()[0]

    with pytest.raises(BridgeError) as failure:
        session.media_preview(item.id)

    assert failure.value.code == expected_code
    assert any(request.path.endswith("IMG_0001.JPG?kind=display") for request in transport.requests)


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
        media = client.get(f"/v1/session/{session_id}/media", headers=headers)
        media_id = media.json()["items"][0]["id"]
        thumbnail = client.get(f"/v1/session/{session_id}/media/{media_id}/thumbnail", headers=headers)
        preview = client.get(f"/v1/session/{session_id}/media/{media_id}/preview", headers=headers)
        live_started = client.post(
            f"/v1/session/{session_id}/liveview/start",
            headers=headers,
            json={"fps": 15, "size": "MEDIUM", "source": "CCAPI_JPEG_POLLING"},
        )
        client.get(f"/v1/session/{session_id}/liveview/frame", headers=headers)
        focused = client.post(
            f"/v1/session/{session_id}/focus/tap",
            headers=headers,
            json={"x": 0.4, "y": 0.6},
        )
        white_balanced = client.post(
            f"/v1/session/{session_id}/whitebalance/click",
            headers=headers,
            json={"x": 0.4, "y": 0.6},
        )
        driven = client.post(
            f"/v1/session/{session_id}/focus/drive",
            headers=headers,
            json={"direction": "NEAR", "step": "MEDIUM"},
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
    assert "CLICK_WHITE_BALANCE" in capabilities.json()["supported"]
    assert "FOCUS_DRIVE" in capabilities.json()["supported"]
    assert "MEDIA_THUMBNAIL" in capabilities.json()["supported"]
    assert "MEDIA_PREVIEW" in capabilities.json()["supported"]
    assert thumbnail.content == JPEG
    assert thumbnail.headers["content-type"].startswith("image/jpeg")
    assert thumbnail.headers["cache-control"] == "private, no-store, max-age=0"
    assert preview.content == JPEG
    assert preview.headers["content-type"].startswith("image/jpeg")
    assert preview.headers["cache-control"] == "private, no-store, max-age=0"
    assert focused.json() == {"accepted": True, "x": 0.4, "y": 0.6}
    assert white_balanced.json()["connected"] is True
    assert driven.json() == {"accepted": True, "direction": "NEAR", "step": "MEDIUM"}
    assert live_started.json()["source"] == "CCAPI_JPEG_POLLING"
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


def _detailed_live_view(jpeg: bytes, info: object) -> bytes:
    return _detail_packet(0x00, jpeg) + _detail_packet(0x01, json.dumps(info).encode())


def _detail_packet(data_type: int, payload: bytes) -> bytes:
    return b"\xff\x00" + bytes([data_type]) + len(payload).to_bytes(4, "big") + payload + b"\xff\xff"
