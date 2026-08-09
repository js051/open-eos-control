from __future__ import annotations

import json
import re
from dataclasses import dataclass
from io import BytesIO
from unittest.mock import patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import (
    MAX_LIVE_VIEW_FRAME_BYTES,
    MAX_MEDIA_PREVIEW_BYTES,
    MAX_MEDIA_THUMBNAIL_BYTES,
    CcapiEngine,
    CcapiMultipartReader,
    CcapiResponse,
    CcapiStreamResponse,
    UrllibCcapiTransport,
    normalize_base_url,
    parse_multipart_boundary,
)
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraFeature, CameraTemperatureStatus, FileNamingField, LiveViewStartRequest
from open_eos_bridge.rtp import RtpAudioChunk, RtpError, RtpSessionDescription

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
        {"path": "/functions/datetime", "get": True, "put": True},
        {"path": "/shooting/control/shutterbutton", "post": True},
        {"path": "/shooting/control/shutterbutton/manual", "put": True},
        {"path": "/shooting/control/af", "post": True},
        {"path": "/shooting/control/recbutton", "post": True},
        {"path": "/shooting/liveview/afframeposition", "put": True},
        {"path": "/shooting/liveview/clickwb", "post": True},
        {"path": "/shooting/control/drivefocus", "post": True},
        {"path": "/shooting/control/zoom", "get": True, "post": True},
        {"path": "/shooting/liveview", "get": True, "post": True, "delete": True},
        {"path": "/shooting/liveview/flip", "get": True},
        {"path": "/shooting/liveview/flipdetail", "get": True},
        {"path": "/contents", "get": True, "put": True, "delete": True},
    ]
}
LIVE_VIEW_MAGNIFICATION_DISCOVERY = {
    "ver100": [
        *DISCOVERY["ver100"],
        {"path": "/shooting/settings/lvzoom", "get": True, "put": True},
    ]
}
RTP_DISCOVERY = {
    "ver100": [
        *DISCOVERY["ver100"],
        {"path": "/shooting/liveview/rtpsessiondesc", "get": True},
        {"path": "/shooting/liveview/rtp", "post": True},
    ]
}
MULTIPART_DISCOVERY = {
    "ver100": [
        *DISCOVERY["ver100"],
        {"path": "/shooting/liveview/multipart", "get": True, "delete": True},
    ]
}
EVENT_DISCOVERY = {
    "ver110": [{"path": "/event/polling", "get": True, "delete": True}],
    "ver100": [*DISCOVERY["ver100"]],
}
DEVICE_STATUS_DISCOVERY = {
    "ver100": [
        *DISCOVERY["ver100"],
        {"path": "/shooting/information/recordable", "get": True},
        {"path": "/devicestatus/lens", "get": True},
        {"path": "/devicestatus/temperature", "get": True},
    ]
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
        developer_discovery: dict[str, object] | None = None,
        external_media: bool = False,
        reject_autofocus_start: bool = False,
        reject_bulb_press: bool = False,
        reject_event_stop: bool = False,
        reject_rtp_start: bool = False,
        camera_sleep_status: int = 202,
        sensor_cleaning_status: int = 200,
        thumbnail_body: bytes = JPEG,
        thumbnail_content_type: str = "image/jpeg",
        preview_body: bytes = JPEG,
        preview_content_type: str = "image/jpeg",
        zoom_response: object | None = None,
        sound_recording_level_response: object | None = None,
        sound_recording_responses: dict[str, object] | None = None,
        focus_bracketing_responses: dict[str, object] | None = None,
        movie_setting_responses: dict[str, object] | None = None,
        movie_mode_response: object | None = None,
        card_selection_responses: dict[str, object] | None = None,
        directory_selection_response: object | None = None,
        file_naming_responses: dict[str, object] | None = None,
        device_function_responses: dict[str, object] | None = None,
        recordable_response: object | None = None,
        lens_response: object | None = None,
        temperature_response: object | None = None,
        live_view_magnification_response: object | None = None,
    ) -> None:
        self.discovery = discovery or DISCOVERY
        self.developer_discovery = developer_discovery
        self.external_media = external_media
        self.reject_autofocus_start = reject_autofocus_start
        self.reject_bulb_press = reject_bulb_press
        self.reject_event_stop = reject_event_stop
        self.reject_rtp_start = reject_rtp_start
        self.camera_sleep_status = camera_sleep_status
        self.sensor_cleaning_status = sensor_cleaning_status
        self.thumbnail_body = thumbnail_body
        self.thumbnail_content_type = thumbnail_content_type
        self.preview_body = preview_body
        self.preview_content_type = preview_content_type
        self.zoom_response = zoom_response
        self.sound_recording_level_response = sound_recording_level_response
        self.sound_recording_responses = sound_recording_responses or {}
        self.focus_bracketing_responses = focus_bracketing_responses or {}
        self.movie_setting_responses = movie_setting_responses or {}
        self.movie_mode_response = movie_mode_response
        self.card_selection_responses = card_selection_responses or {}
        self.directory_selection_response = directory_selection_response
        self.file_naming_responses = file_naming_responses or {}
        self.device_function_responses = device_function_responses or {}
        self.recordable_response = (
            recordable_response
            if recordable_response is not None
            else {"recordableshots": 2418, "remainingtime": None}
        )
        self.lens_response = (
            lens_response if lens_response is not None else {"mount": True, "name": "RF24-105mm F4 L IS USM"}
        )
        self.temperature_response = temperature_response if temperature_response is not None else {"status": "normal"}
        self.live_view_magnification_response = live_view_magnification_response
        self.live_view_magnification = "1"
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
        self.zoom = 50
        self.sound_recording_level = 32
        self.sound_recording = {
            "soundrecording": "manual",
            "windfilter": "auto",
            "attenuator": "disable",
        }
        self.focus_bracketing: dict[str, str | int] = {
            "focusbracketing": "disable",
            "focusbracketingnumberofshots": 100,
            "focusbracketingfocusincrement": 4,
            "focusbracketingexposuresmoothing": "disable",
        }
        self.movie_settings = {
            "moviequality": "3840x2160_5994_ipb_standard",
            "highframerate": "disable",
            "moviecropping": "disable",
            "movieformat": "mp4",
        }
        self.movie_mode = "off"
        self.card_selection = {"stillimage": "card1", "movie": "card2"}
        self.directories = ["100EOSXX", "101EOSXX"]
        self.directory_selection = "100EOSXX"
        self.file_naming: dict[str, str | int] = {
            "still-filename-mode": "preset_code",
            "still-user-setting-1": "IMG_",
            "still-user-setting-2": "IMG",
            "movie-index": "A_",
            "movie-reel-number": 1,
            "movie-clip-number": 1,
            "movie-user-defined": "CANON",
        }
        self.device_functions = {"beep": "enable", "displayoff": "60", "autopoweroff": "180"}
        self.camera_sleep_count = 0
        self.sensor_cleaning_count = 0
        self.sensor_cleaning_auto_power_off: bool | None = None
        self.reject_live_view_size = True
        self.camera_clock = {"datetime": "Tue, 01 Jan 2019 01:23:45 +0000", "dst": False}
        self.media_metadata: dict[str, object] = {
            "filesize": len(MEDIA),
            "protect": "disable",
            "archive": "disable",
            "rotate": "0",
            "rating": "off",
            "lastmodifieddate": "2026-08-05T10:00:00+08:00",
        }
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
        if method == "GET" and path == "/ccapi/ver100/topurlfordev" and self.developer_discovery is not None:
            return _json_response(self.developer_discovery)
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
        if method == "GET" and path == "/ccapi/ver100/shooting/information/recordable":
            return _json_response(self.recordable_response)
        if method == "GET" and path == "/ccapi/ver100/devicestatus/lens":
            return _json_response(self.lens_response)
        if method == "GET" and path == "/ccapi/ver100/devicestatus/temperature":
            return _json_response(self.temperature_response)
        if method == "GET" and path == "/ccapi/ver100/shooting/settings":
            return _json_response(self.settings)
        if method == "GET" and path == "/ccapi/ver100/shooting/settings/lvzoom":
            return _json_response(
                self.live_view_magnification_response
                if self.live_view_magnification_response is not None
                else {"value": self.live_view_magnification, "ability": ["1", "5", "10"]}
            )
        if method == "PUT" and path == "/ccapi/ver100/shooting/settings/lvzoom":
            assert payload == {"value": str(payload.get("value"))}
            self.live_view_magnification = str(payload["value"])
            return _json_response({}, status=200)
        if method == "GET" and path == "/ccapi/ver100/shooting/control/zoom":
            if self.zoom_response is not None:
                return _json_response(self.zoom_response)
            return _json_response({"value": self.zoom, "ability": {"min": 0, "max": 100, "step": 25}})
        if method == "POST" and path == "/ccapi/ver100/shooting/control/zoom":
            assert payload is not None and isinstance(payload.get("value"), int)
            self.zoom = payload["value"]
            return _json_response({"value": self.zoom})
        if method == "GET" and path == "/ccapi/ver100/shooting/settings/soundrecording/level":
            if self.sound_recording_level_response is not None:
                return _json_response(self.sound_recording_level_response)
            return _json_response(
                {"value": self.sound_recording_level, "ability": {"min": 0, "max": 63, "step": 1}}
            )
        if method == "PUT" and path == "/ccapi/ver100/shooting/settings/soundrecording/level":
            assert payload is not None
            value = payload.get("value")
            assert isinstance(value, int) and not isinstance(value, bool)
            self.sound_recording_level = value
            return _json_response({"value": value})
        sound_match = re.fullmatch(
            r"/ccapi/ver100/shooting/settings/soundrecording(?:/(windfilter|attenuator))?",
            path,
        )
        if sound_match:
            key = sound_match.group(1) or "soundrecording"
            abilities = {
                "soundrecording": ["auto", "manual", "disable"],
                "windfilter": ["auto", "enable", "disable"],
                "attenuator": ["enable", "disable", "auto", "manual"],
            }
            if method == "GET":
                response = self.sound_recording_responses.get(key)
                return _json_response(
                    response
                    if response is not None
                    else {"value": self.sound_recording[key], "ability": abilities[key]}
                )
            if method == "PUT":
                assert payload is not None and payload.get("value") in abilities[key]
                self.sound_recording[key] = str(payload["value"])
                return _json_response({"value": self.sound_recording[key]})
        focus_paths = {
            "/ccapi/ver100/shooting/settings/focusbracketing": "focusbracketing",
            "/ccapi/ver100/shooting/settings/focusbracketing/numberofshots": "focusbracketingnumberofshots",
            "/ccapi/ver100/shooting/settings/focusbracketing/focusincrement": "focusbracketingfocusincrement",
            "/ccapi/ver100/shooting/settings/focusbracketing/exposuresmoothing": "focusbracketingexposuresmoothing",
        }
        if path in focus_paths:
            key = focus_paths[path]
            if method == "GET":
                response = self.focus_bracketing_responses.get(key)
                if response is not None:
                    return _json_response(response)
                abilities: dict[str, object] = {
                    "focusbracketing": ["enable", "disable"],
                    "focusbracketingnumberofshots": {"min": 2, "max": 999, "step": 1},
                    "focusbracketingfocusincrement": {"min": 1, "max": 10, "step": 1},
                    "focusbracketingexposuresmoothing": ["enable", "disable"],
                }
                return _json_response({"value": self.focus_bracketing[key], "ability": abilities[key]})
            if method == "PUT":
                assert payload is not None
                value = payload.get("value")
                if key in {"focusbracketingnumberofshots", "focusbracketingfocusincrement"}:
                    assert isinstance(value, int) and not isinstance(value, bool)
                else:
                    assert value in {"enable", "disable"}
                self.focus_bracketing[key] = value
                return _json_response({"value": value})
        movie_paths = {
            "/ccapi/ver100/shooting/settings/moviequality": "moviequality",
            "/ccapi/ver110/shooting/settings/highframerate": "highframerate",
            "/ccapi/ver110/shooting/settings/moviecropping": "moviecropping",
            "/ccapi/ver110/shooting/settings/movieformat": "movieformat",
        }
        if path in movie_paths:
            key = movie_paths[path]
            abilities = {
                "moviequality": [
                    "3840x2160_5994_ipb_standard",
                    "1920x1080_2997_ipb_standard",
                ],
                "highframerate": ["enable", "disable"],
                "moviecropping": ["enable", "disable"],
                "movieformat": ["raw", "mp4"],
            }
            if method == "GET":
                response = self.movie_setting_responses.get(key)
                return _json_response(
                    response
                    if response is not None
                    else {"value": self.movie_settings[key], "ability": abilities[key]}
                )
            if method == "PUT":
                assert payload is not None and payload.get("value") in abilities[key]
                self.movie_settings[key] = str(payload["value"])
                return _json_response({"value": self.movie_settings[key]})
        if method == "GET" and path == "/ccapi/ver100/shooting/control/moviemode":
            if self.movie_mode_response is not None:
                return _json_response(self.movie_mode_response)
            return _json_response({"status": self.movie_mode})
        if method == "POST" and path == "/ccapi/ver100/shooting/control/moviemode":
            assert payload is not None and payload.get("action") in {"off", "on"}
            self.movie_mode = str(payload["action"])
            return CcapiResponse(204, {}, b"")
        card_match = re.fullmatch(r"/ccapi/ver100/functions/cardselection/(stillimage|movie)", path)
        if method == "GET" and card_match:
            kind = card_match.group(1)
            response = self.card_selection_responses.get(kind)
            if response is not None:
                return _json_response(response)
            return _json_response(
                {"value": self.card_selection[kind], "ability": ["none", "card1", "card2"]}
            )
        if method == "PUT" and card_match:
            kind = card_match.group(1)
            assert payload is not None and payload.get("value") in {"none", "card1", "card2"}
            self.card_selection[kind] = str(payload["value"])
            return _json_response({"value": self.card_selection[kind]})
        directory_selection_path = "/ccapi/ver100/functions/directory/directoryselection"
        if method == "GET" and path == directory_selection_path:
            response = self.directory_selection_response
            return _json_response(
                response
                if response is not None
                else {"value": self.directory_selection, "ability": self.directories}
            )
        if method == "PUT" and path == directory_selection_path:
            assert payload is not None and payload.get("value") in self.directories
            self.directory_selection = str(payload["value"])
            return _json_response({"value": self.directory_selection})
        if method == "POST" and path == "/ccapi/ver100/functions/directory/createdirectory":
            assert payload is not None and set(payload) == {"directoryname"}
            name = payload["directoryname"] or "EOSXX"
            assert isinstance(name, str) and re.fullmatch(r"[A-Z0-9_]{5}", name)
            full_name = f"{100 + len(self.directories):03d}{name}"
            self.directories.append(full_name)
            self.directory_selection = full_name
            return _json_response({"directoryname": name})
        file_naming_paths = {
            "stills/filename": ("still-filename-mode", "value"),
            "stills/usersetting1": ("still-user-setting-1", "usersetting1"),
            "stills/usersetting2": ("still-user-setting-2", "usersetting2"),
            "movies/index": ("movie-index", "index"),
            "movies/reelnum": ("movie-reel-number", "value"),
            "movies/clipnum": ("movie-clip-number", "value"),
            "movies/userdefined": ("movie-user-defined", "userdefined"),
        }
        file_naming_match = re.fullmatch(r"/ccapi/ver100/functions/filename/(stills|movies)/([^/]+)", path)
        if file_naming_match:
            suffix = f"{file_naming_match.group(1)}/{file_naming_match.group(2)}"
            field, response_key = file_naming_paths[suffix]
            if method == "GET":
                if field in self.file_naming_responses:
                    return _json_response(self.file_naming_responses[field])
                if field == "still-filename-mode":
                    return _json_response(
                        {
                            "value": self.file_naming[field],
                            "ability": ["preset_code", "usersetting1", "usersetting2"],
                        }
                    )
                if field == "movie-reel-number":
                    return _json_response(
                        {"value": self.file_naming[field], "ability": {"min": 1, "max": 9999, "step": 1}}
                    )
                if field == "movie-clip-number":
                    return _json_response(
                        {"value": self.file_naming[field], "ability": {"min": 1, "max": 999, "step": 1}}
                    )
                return _json_response({response_key: self.file_naming[field]})
            if method == "PUT":
                assert payload is not None and set(payload) == {response_key}
                self.file_naming[field] = payload[response_key]  # type: ignore[assignment]
                return _json_response({response_key: payload[response_key]})
        device_function_match = re.fullmatch(r"/ccapi/ver100/functions/(beep|displayoff|autopoweroff)", path)
        if device_function_match:
            key = device_function_match.group(1)
            abilities = {
                "beep": ["enable", "disable", "disabletouch"],
                "displayoff": ["10", "20", "30", "60", "120", "180"],
                "autopoweroff": ["30", "60", "120", "180", "300", "600", "disable", "immediately"],
            }
            if method == "GET":
                response = self.device_function_responses.get(key)
                return _json_response(
                    response
                    if response is not None
                    else {"value": self.device_functions[key], "ability": abilities[key]}
                )
            if method == "PUT":
                assert payload is not None and payload.get("value") in abilities[key]
                if key == "autopoweroff" and payload["value"] == "immediately":
                    self.camera_sleep_count += 1
                    return _json_response({}, status=self.camera_sleep_status)
                self.device_functions[key] = str(payload["value"])
                return _json_response({"value": self.device_functions[key]})
        if method == "PUT" and path == "/ccapi/ver100/functions/datetime":
            assert payload is not None
            self.camera_clock = payload
            return _json_response(self.camera_clock)
        if method == "GET" and path == "/ccapi/ver100/functions/datetime":
            return _json_response(self.camera_clock)
        if method == "POST" and path == "/ccapi/ver100/functions/sensorcleaning":
            assert payload is not None and isinstance(payload.get("autopoweroff"), bool)
            self.sensor_cleaning_count += 1
            self.sensor_cleaning_auto_power_off = payload["autopoweroff"]
            return _json_response({}, status=self.sensor_cleaning_status)
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
        if method == "DELETE" and path == "/ccapi/ver100/shooting/liveview/multipart":
            return _json_response({}, status=200)
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
        if method == "GET" and path.endswith("IMG_0001.JPG?kind=info"):
            return _json_response(self.media_metadata)
        if method == "PUT" and path.startswith("/ccapi/ver100/contents/"):
            assert payload is not None
            action = payload.get("action")
            value = payload.get("value")
            assert action in {"protect", "rating", "rotate", "archive"}
            assert isinstance(value, str)
            self.media_metadata[action] = value
            return _json_response({})
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
        if method == "GET" and path == "/ccapi/ver100/shooting/liveview/multipart":
            multipart = (
                b"--canon\nContent-Type: image/jpeg\nContent-Length: "
                + str(len(JPEG)).encode()
                + b"\n\n"
                + JPEG
                + b"\n--canon--\n"
            )
            return CcapiStreamResponse(
                200,
                {"content-type": "multipart/x-mixed-replace;boundary=canon"},
                BytesIO(multipart),
            )
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
        self.audio_status: dict[str, object] = {
            "advertised": True,
            "available": True,
            "active": True,
            "codec": "MP4A-LATM",
            "sampleRate": 48_000,
            "channels": 2,
            "generation": 1,
        }
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

    def read_audio(self, after_generation: int = 0, timeout: float = 1.0) -> RtpAudioChunk | None:
        assert after_generation == 0
        assert timeout == 1.0
        return RtpAudioChunk(b"\x00\x00\x01\x00", 1, 48_000, 2, 1)

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
    assert capabilities.profile.family == "EOS_R"
    assert capabilities.profile.priority == "PRIMARY"
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
        CameraFeature.MEDIA_PROTECT,
        CameraFeature.MEDIA_RATING,
        CameraFeature.MEDIA_ROTATE,
        CameraFeature.MEDIA_ARCHIVE,
        CameraFeature.MEDIA_DELETE,
        CameraFeature.CAMERA_CLOCK_SYNC,
        CameraFeature.ZOOM_CONTROL,
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
    assert next(item for item in capabilities.settings if item.key == "zoom").values == [
        "0",
        "25",
        "50",
        "75",
        "100",
    ]
    assert capabilities.evidence.source == "GET /ccapi"
    assert capabilities.evidence.protocol_versions == ["ver100"]
    assert "POST /ccapi/ver100/shooting/control/shutterbutton" in capabilities.evidence.advertised_commands
    assert "iso" in capabilities.evidence.writable_settings
    assert "zoom" in capabilities.evidence.writable_settings
    assert capabilities.evidence.truncated is False

    assert session.set_setting("iso", "1600").exposure.iso == "1600"
    session.set_setting("zoom", "75")
    zoom_write = next(
        request
        for request in transport.requests
        if request.method == "POST" and request.path.endswith("/shooting/control/zoom")
    )
    assert zoom_write.body == {"value": 75}
    assert transport.zoom == 75
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
    session.sync_camera_clock()
    clock_write = next(
        request
        for request in transport.requests
        if request.method == "PUT" and request.path.endswith("/functions/datetime")
    )
    assert clock_write.body is not None
    assert set(clock_write.body) == {"datetime", "dst"}
    assert re.fullmatch(
        r"[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} [+-]\d{4}",
        str(clock_write.body["datetime"]),
    )
    assert isinstance(clock_write.body["dst"], bool)
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
    info = session.media_info(media[0].id)
    assert info.protected is False
    assert info.rating == 0
    assert info.rotation_degrees == 0
    assert info.archived is False
    assert session.set_media_protection(media[0].id, True).protected is True
    assert session.set_media_rating(media[0].id, 5).rating == 5
    assert session.set_media_rotation(media[0].id, 270).rotation_degrees == 270
    assert session.set_media_archive(media[0].id, True).archived is True
    assert session.set_media_archive(media[0].id, False).archived is False
    session.delete_media(media[0].id)
    session.stop_live_view()
    observed = set(session.capabilities().evidence.observed_features)
    session.close()
    assert transport.closed is True
    assert {
        CameraFeature.DESKTOP_BRIDGE,
        CameraFeature.CAMERA_IDENTITY,
        CameraFeature.EXPOSURE_CONTROL,
        CameraFeature.CAMERA_CLOCK_SYNC,
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
        CameraFeature.MEDIA_PROTECT,
        CameraFeature.MEDIA_RATING,
        CameraFeature.MEDIA_ROTATE,
        CameraFeature.MEDIA_ARCHIVE,
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
            "PUT",
            "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG",
            {"action": "protect", "value": "enable"},
        )
        in transport.requests
    )
    assert (
        RecordedRequest(
            "PUT",
            "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG",
            {"action": "rating", "value": "5"},
        )
        in transport.requests
    )
    assert (
        RecordedRequest(
            "PUT",
            "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG",
            {"action": "rotate", "value": "270"},
        )
        in transport.requests
    )
    assert (
        RecordedRequest(
            "PUT",
            "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG",
            {"action": "archive", "value": "enable"},
        )
        in transport.requests
    )
    assert (
        RecordedRequest(
            "PUT",
            "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG",
            {"action": "archive", "value": "disable"},
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


def test_ccapi_device_status_requires_advertised_strict_canon_payloads() -> None:
    transport = FakeCcapiTransport(
        discovery=DEVICE_STATUS_DISCOVERY,
        temperature_response={"status": "frameratedown_and_restrictionmovierecording"},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    status = session.status()
    capabilities = session.capabilities()

    assert status.lens is not None
    assert status.lens.mounted is True
    assert status.lens.name == "RF24-105mm F4 L IS USM"
    assert status.temperature is CameraTemperatureStatus.FRAME_RATE_DOWN_AND_RESTRICTION_MOVIE_RECORDING
    assert status.temperature.movie_recording_allowed is False
    assert status.recordable_shots == 2418
    assert status.remaining_recording_seconds is None
    assert CameraFeature.RECORDABLE_STATUS in capabilities.supported
    assert CameraFeature.LENS_STATUS in capabilities.supported
    assert CameraFeature.TEMPERATURE_STATUS in capabilities.supported


def test_ccapi_malformed_device_status_remains_planned() -> None:
    transport = FakeCcapiTransport(
        discovery=DEVICE_STATUS_DISCOVERY,
        recordable_response={"recordableshots": True, "remainingtime": -1},
        lens_response={"mount": "true", "name": "RF24-105mm"},
        temperature_response={"status": "hot"},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    status = session.status()
    capabilities = session.capabilities()

    assert status.lens is None
    assert status.temperature is None
    assert status.recordable_shots is None
    assert status.remaining_recording_seconds is None
    assert CameraFeature.RECORDABLE_STATUS not in capabilities.supported
    assert CameraFeature.RECORDABLE_STATUS in capabilities.planned
    assert CameraFeature.LENS_STATUS not in capabilities.supported
    assert CameraFeature.TEMPERATURE_STATUS not in capabilities.supported
    assert CameraFeature.LENS_STATUS in capabilities.planned
    assert CameraFeature.TEMPERATURE_STATUS in capabilities.planned


def test_ccapi_oversized_lens_name_is_ignored_without_failing_status() -> None:
    transport = FakeCcapiTransport(
        discovery=DEVICE_STATUS_DISCOVERY,
        lens_response={"mount": True, "name": "R" * 513},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    status = session.status()
    capabilities = session.capabilities()

    assert status.lens is None
    assert CameraFeature.LENS_STATUS not in capabilities.supported
    assert CameraFeature.LENS_STATUS in capabilities.planned


@pytest.mark.parametrize(
    ("temperature", "operation", "forbidden_path"),
    [
        ("disablerelease", "capture", "/shooting/control/shutterbutton"),
        ("restrictionmovierecording", "record", "/shooting/control/recbutton"),
        ("disableliveview", "liveview", "/shooting/liveview"),
    ],
)
def test_ccapi_refreshes_temperature_before_restricted_start_commands(
    temperature: str,
    operation: str,
    forbidden_path: str,
) -> None:
    transport = FakeCcapiTransport(
        discovery=DEVICE_STATUS_DISCOVERY,
        temperature_response={"status": temperature},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    with pytest.raises(BridgeError) as raised:
        if operation == "capture":
            session.capture_still()
        elif operation == "record":
            session.start_recording()
        else:
            session.start_live_view(LiveViewStartRequest(fps=15, source="CCAPI_JPEG_POLLING"))

    assert raised.value.code == "CAMERA_TEMPERATURE_RESTRICTION"
    assert not any(
        request.method in {"POST", "PUT"} and request.path.endswith(forbidden_path)
        for request in transport.requests
    )


def test_ccapi_temperature_restriction_does_not_block_recording_stop() -> None:
    transport = FakeCcapiTransport(discovery=DEVICE_STATUS_DISCOVERY)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    assert session.start_recording().recording is True
    transport.temperature_response = {"status": "restrictionmovierecording"}
    assert session.stop_recording().recording is False

    assert RecordedRequest(
        "POST",
        "/ccapi/ver100/shooting/control/recbutton",
        {"action": "stop"},
    ) in transport.requests


def test_ccapi_malformed_temperature_refresh_does_not_clear_last_restriction() -> None:
    transport = FakeCcapiTransport(
        discovery=DEVICE_STATUS_DISCOVERY,
        temperature_response={"status": "disablerelease"},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    with pytest.raises(BridgeError, match="temperature restriction"):
        session.capture_still()
    transport.temperature_response = {"status": "hot"}
    with pytest.raises(BridgeError, match="temperature restriction"):
        session.capture_still()

    assert not any(
        request.method == "POST" and request.path.endswith("/shooting/control/shutterbutton")
        for request in transport.requests
    )


@pytest.mark.parametrize(
    "zoom_response",
    [
        {"value": False, "ability": {"min": 0, "max": 100, "step": 1}},
        {"value": 50, "ability": {"min": 0, "max": 1_000, "step": 1}},
        {"value": 50, "ability": {"min": 0, "max": 100, "step": 0}},
        {"value": 55, "ability": {"min": 0, "max": 100, "step": 10}},
    ],
)
def test_ccapi_zoom_hides_malformed_or_unbounded_ranges(zoom_response: object) -> None:
    transport = FakeCcapiTransport(zoom_response=zoom_response)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.ZOOM_CONTROL not in capabilities.supported
    assert CameraFeature.ZOOM_CONTROL in capabilities.planned
    assert not any(setting.key == "zoom" for setting in capabilities.settings)
    with pytest.raises(BridgeError, match="ZOOM_CONTROL"):
        session.set_setting("zoom", "50")


def test_ccapi_sound_recording_level_requires_matching_get_put_and_writes_integer() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/shooting/settings/soundrecording/level", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    sound_level = next(setting for setting in capabilities.settings if setting.key == "soundrecordinglevel")

    assert sound_level.value == "32"
    assert sound_level.values == [str(value) for value in range(64)]
    assert CameraFeature.SOUND_RECORDING_LEVEL_CONTROL in capabilities.supported
    assert "soundrecordinglevel" in capabilities.evidence.writable_settings
    put_count = sum(request.method == "PUT" for request in transport.requests)
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("soundrecordinglevel", "64")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count

    session.set_setting("soundrecordinglevel", "48")

    assert transport.sound_recording_level == 48
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver100/shooting/settings/soundrecording/level",
        {"value": 48},
    ) in transport.requests

    put_count = sum(request.method == "PUT" for request in transport.requests)
    transport.sound_recording_level_response = {
        "value": 32,
        "ability": {"min": 0, "max": 40, "step": 1},
    }
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("soundrecordinglevel", "48")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count


@pytest.mark.parametrize(
    "response",
    [
        {"value": False, "ability": {"min": 0, "max": 63, "step": 1}},
        {"value": 32.0, "ability": {"min": 0, "max": 63, "step": 1}},
        {"value": 32, "ability": {"min": 0, "max": 1_000, "step": 1}},
        {"value": 32, "ability": {"min": 0, "max": 63, "step": 0}},
        {"value": 33, "ability": {"min": 0, "max": 63, "step": 2}},
        {"value": 32, "ability": {"min": 32, "max": 32, "step": 1}},
    ],
)
def test_ccapi_sound_recording_level_hides_malformed_contract(response: object) -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/shooting/settings/soundrecording/level", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery, sound_recording_level_response=response)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.SOUND_RECORDING_LEVEL_CONTROL not in capabilities.supported
    assert CameraFeature.SOUND_RECORDING_LEVEL_CONTROL in capabilities.planned
    assert not any(setting.key == "soundrecordinglevel" for setting in capabilities.settings)


def test_ccapi_sound_recording_level_does_not_combine_get_put_across_versions() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/shooting/settings/soundrecording/level", "get": True},
            ],
            "ver110": [{"path": "/shooting/settings/soundrecording/level", "put": True}],
        }
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.SOUND_RECORDING_LEVEL_CONTROL not in capabilities.supported
    assert not any(setting.key == "soundrecordinglevel" for setting in capabilities.settings)
    assert not any("soundrecording/level" in request.path for request in transport.requests)


def test_ccapi_sound_recording_controls_require_matching_pairs_and_refresh_before_write() -> None:
    paths = [
        "/shooting/settings/soundrecording",
        "/shooting/settings/soundrecording/windfilter",
        "/shooting/settings/soundrecording/attenuator",
    ]
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            *({"path": path, "get": True, "put": True} for path in paths),
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    controls = {setting.key: setting for setting in capabilities.settings}

    assert controls["soundrecording"].values == ["auto", "manual", "disable"]
    assert controls["windfilter"].value == "auto"
    assert controls["attenuator"].values == ["enable", "disable", "auto", "manual"]
    assert CameraFeature.SOUND_RECORDING_CONTROL in capabilities.supported
    assert {"soundrecording", "windfilter", "attenuator"}.issubset(
        capabilities.evidence.writable_settings
    )

    session.set_setting("windfilter", "enable")
    assert transport.sound_recording["windfilter"] == "enable"
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver100/shooting/settings/soundrecording/windfilter",
        {"value": "enable"},
    ) in transport.requests

    put_count = sum(request.method == "PUT" for request in transport.requests)
    transport.sound_recording_responses["windfilter"] = {
        "value": "auto",
        "ability": ["auto", "disable"],
    }
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("windfilter", "enable")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count


@pytest.mark.parametrize(
    "response",
    [
        {"value": "on", "ability": ["auto", "enable", "disable"]},
        {"value": "auto", "ability": ["auto", "auto"]},
        {"value": "auto", "ability": ["auto"]},
        {"value": "auto", "ability": ["auto", 1]},
        {"value": 1, "ability": ["auto", "disable"]},
    ],
)
def test_ccapi_sound_recording_controls_hide_malformed_contract(response: object) -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {
                "path": "/shooting/settings/soundrecording/windfilter",
                "get": True,
                "put": True,
            },
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery, sound_recording_responses={"windfilter": response})
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.SOUND_RECORDING_CONTROL not in capabilities.supported
    assert CameraFeature.SOUND_RECORDING_CONTROL in capabilities.planned
    assert not any(setting.key == "windfilter" for setting in capabilities.settings)


def test_ccapi_sound_recording_controls_do_not_combine_versions() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/shooting/settings/soundrecording/attenuator", "get": True},
            ],
            "ver110": [
                {"path": "/shooting/settings/soundrecording/attenuator", "put": True},
            ],
        }
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.SOUND_RECORDING_CONTROL not in capabilities.supported
    assert not any(setting.key == "attenuator" for setting in capabilities.settings)
    assert not any("soundrecording/attenuator" in request.path for request in transport.requests)


def test_ccapi_sound_recording_controls_do_not_treat_aggregate_as_endpoint_get() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/shooting/settings/soundrecording/windfilter", "put": True},
            ],
        }
    )
    transport.settings["windfilter"] = {
        "value": "auto",
        "ability": ["auto", "enable", "disable"],
    }
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.SOUND_RECORDING_CONTROL not in capabilities.supported
    assert not any(setting.key == "windfilter" for setting in capabilities.settings)
    assert len(transport.requests) == 3


def test_ccapi_focus_bracketing_requires_exact_pairs_and_refreshes_integer_write() -> None:
    paths = [
        "/shooting/settings/focusbracketing",
        "/shooting/settings/focusbracketing/numberofshots",
        "/shooting/settings/focusbracketing/focusincrement",
        "/shooting/settings/focusbracketing/exposuresmoothing",
    ]
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            *({"path": path, "get": True, "put": True} for path in paths),
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    controls = {setting.key: setting for setting in capabilities.settings}

    assert CameraFeature.FOCUS_BRACKETING_CONTROL in capabilities.supported
    assert controls["focusbracketing"].values == ["enable", "disable"]
    assert controls["focusbracketingnumberofshots"].values == [str(value) for value in range(2, 1000)]
    assert controls["focusbracketingfocusincrement"].values == [str(value) for value in range(1, 11)]
    assert controls["focusbracketingexposuresmoothing"].value == "disable"
    assert set(controls).intersection(
        {
            "focusbracketing",
            "focusbracketingnumberofshots",
            "focusbracketingfocusincrement",
            "focusbracketingexposuresmoothing",
        }
    ).issubset(
        capabilities.evidence.writable_settings
    )

    session.set_setting("focusbracketingnumberofshots", "250")

    assert transport.focus_bracketing["focusbracketingnumberofshots"] == 250
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver100/shooting/settings/focusbracketing/numberofshots",
        {"value": 250},
    ) in transport.requests
    put_count = sum(request.method == "PUT" for request in transport.requests)
    transport.focus_bracketing_responses["focusbracketingnumberofshots"] = {
        "value": 100,
        "ability": {"min": 2, "max": 200, "step": 1},
    }
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("focusbracketingnumberofshots", "250")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count


def test_ccapi_focus_bracketing_malformed_root_hides_group_without_child_reads() -> None:
    paths = [
        "/shooting/settings/focusbracketing",
        "/shooting/settings/focusbracketing/numberofshots",
        "/shooting/settings/focusbracketing/focusincrement",
        "/shooting/settings/focusbracketing/exposuresmoothing",
    ]
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            *({"path": path, "get": True, "put": True} for path in paths),
        ]
    }
    transport = FakeCcapiTransport(
        discovery=discovery,
        focus_bracketing_responses={
            "focusbracketing": {"value": "disable", "ability": ["disable", "disable"]},
        },
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.FOCUS_BRACKETING_CONTROL not in capabilities.supported
    assert CameraFeature.FOCUS_BRACKETING_CONTROL in capabilities.planned
    assert not any(setting.key.startswith("focusbracketing") for setting in capabilities.settings)
    focus_reads = [
        request.path
        for request in transport.requests
        if request.method == "GET" and "/focusbracketing" in request.path
    ]
    assert focus_reads == ["/ccapi/ver100/shooting/settings/focusbracketing"]


def test_ccapi_focus_bracketing_does_not_combine_versions_or_use_aggregate_get() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/shooting/settings/focusbracketing", "get": True},
            ],
            "ver110": [{"path": "/shooting/settings/focusbracketing", "put": True}],
        }
    )
    transport.settings["focusbracketing"] = {
        "value": "disable",
        "ability": ["enable", "disable"],
    }
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.FOCUS_BRACKETING_CONTROL not in capabilities.supported
    assert not any(setting.key.startswith("focusbracketing") for setting in capabilities.settings)
    assert not any("focusbracketing" in request.path for request in transport.requests)


def test_ccapi_device_function_settings_require_pairs_and_refresh_before_write() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/beep", "get": True, "put": True},
            {"path": "/functions/displayoff", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    controls = {setting.key: setting for setting in capabilities.settings}

    assert CameraFeature.ADVANCED_SETTINGS in capabilities.supported
    assert controls["beep"].values == ["enable", "disable", "disabletouch"]
    assert controls["displayoff"].values == ["10", "20", "30", "60", "120", "180"]
    assert {"beep", "displayoff"}.issubset(capabilities.evidence.writable_settings)

    session.set_setting("beep", "disabletouch")

    assert transport.device_functions["beep"] == "disabletouch"
    assert RecordedRequest("PUT", "/ccapi/ver100/functions/beep", {"value": "disabletouch"}) in transport.requests

    put_count = sum(request.method == "PUT" for request in transport.requests)
    transport.device_function_responses["beep"] = {
        "value": "enable",
        "ability": ["enable", "disable"],
    }
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("beep", "disabletouch")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count


def test_ccapi_auto_power_off_exposes_safe_values_and_separate_sleep_action() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/autopoweroff", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    control = next(setting for setting in capabilities.settings if setting.key == "autopoweroff")

    assert control.value == "180"
    assert control.values == ["30", "60", "120", "180", "300", "600", "disable"]
    assert "immediately" not in control.values
    assert CameraFeature.CAMERA_SLEEP in capabilities.supported
    assert "autopoweroff" in capabilities.evidence.writable_settings

    session.set_setting("autopoweroff", "300")
    session.sleep_camera()

    assert transport.device_functions["autopoweroff"] == "300"
    assert transport.camera_sleep_count == 1
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver100/functions/autopoweroff",
        {"value": "immediately"},
    ) in transport.requests
    assert CameraFeature.CAMERA_SLEEP in session.capabilities().evidence.observed_features


def test_ccapi_camera_sleep_requires_canon_accepted_status() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/autopoweroff", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery, camera_sleep_status=200)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    assert CameraFeature.CAMERA_SLEEP in session.capabilities().supported
    with pytest.raises(BridgeError, match="expected HTTP 202"):
        session.sleep_camera()

    assert CameraFeature.CAMERA_SLEEP not in session.capabilities().evidence.observed_features


def test_ccapi_sensor_cleaning_requires_advertised_post_and_exact_boolean_payload() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/sensorcleaning", "post": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )

    assert CameraFeature.SENSOR_CLEANING in session.capabilities().supported
    session.clean_sensor(auto_power_off=True)

    assert transport.sensor_cleaning_count == 1
    assert transport.sensor_cleaning_auto_power_off is True
    assert RecordedRequest(
        "POST",
        "/ccapi/ver100/functions/sensorcleaning",
        {"autopoweroff": True},
    ) in transport.requests
    assert CameraFeature.SENSOR_CLEANING in session.capabilities().evidence.observed_features


def test_ccapi_sensor_cleaning_rejects_noncanonical_success_status() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/sensorcleaning", "post": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery, sensor_cleaning_status=204)
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )

    with pytest.raises(BridgeError, match="expected HTTP 200"):
        session.clean_sensor(auto_power_off=False)

    assert CameraFeature.SENSOR_CLEANING not in session.capabilities().evidence.observed_features


def test_ccapi_sensor_cleaning_is_planned_and_never_sent_when_unadvertised() -> None:
    transport = FakeCcapiTransport()
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )
    capabilities = session.capabilities()
    request_count = len(transport.requests)

    assert CameraFeature.SENSOR_CLEANING not in capabilities.supported
    assert CameraFeature.SENSOR_CLEANING in capabilities.planned
    with pytest.raises(BridgeError, match="did not advertise"):
        session.clean_sensor(auto_power_off=False)
    assert len(transport.requests) == request_count


def test_ccapi_auto_power_off_requires_valid_current_and_explicit_immediate_ability() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/autopoweroff", "get": True, "put": True},
        ]
    }
    timed_only = FakeCcapiTransport(
        discovery=discovery,
        device_function_responses={
            "autopoweroff": {"value": "180", "ability": ["30", "60", "180", "disable"]},
        },
    )
    timed_session = CcapiEngine(lambda _username, _password: timed_only).open_connection(
        "http://192.168.1.2:8080"
    )

    timed_capabilities = timed_session.capabilities()

    assert any(setting.key == "autopoweroff" for setting in timed_capabilities.settings)
    assert CameraFeature.CAMERA_SLEEP not in timed_capabilities.supported
    assert CameraFeature.CAMERA_SLEEP in timed_capabilities.planned
    with pytest.raises(BridgeError, match="immediately"):
        timed_session.sleep_camera()
    assert timed_only.camera_sleep_count == 0

    malformed = FakeCcapiTransport(
        discovery=discovery,
        device_function_responses={
            "autopoweroff": {"value": "future", "ability": ["30", "180", "future", "immediately"]},
        },
    )
    malformed_capabilities = CcapiEngine(lambda _username, _password: malformed).open_connection(
        "http://192.168.1.2:8080"
    ).capabilities()

    assert not any(setting.key == "autopoweroff" for setting in malformed_capabilities.settings)
    assert CameraFeature.CAMERA_SLEEP not in malformed_capabilities.supported


@pytest.mark.parametrize(
    "response",
    [
        {"value": "enable", "ability": ["enable", "enable"]},
        {"value": "enable", "ability": ["enable", "future"]},
        {"value": 1, "ability": ["enable", "disable"]},
    ],
)
def test_ccapi_device_function_settings_hide_malformed_contract(response: object) -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/beep", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(
        discovery=discovery,
        device_function_responses={"beep": response},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert not any(setting.key == "beep" for setting in capabilities.settings)
    assert "beep" not in capabilities.evidence.writable_settings


def test_ccapi_device_function_settings_do_not_combine_versions_or_use_aggregate_get() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/functions/beep", "get": True},
            ],
            "ver110": [{"path": "/functions/beep", "put": True}],
        }
    )
    transport.settings["beep"] = {
        "value": "enable",
        "ability": ["enable", "disable", "disabletouch"],
    }
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert not any(setting.key == "beep" for setting in capabilities.settings)
    assert not any(request.path.endswith("/functions/beep") for request in transport.requests)


def test_ccapi_movie_settings_require_exact_pairs_and_refresh_before_write() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/shooting/settings/moviequality", "get": True, "put": True},
        ],
        "ver110": [
            {"path": "/shooting/settings/highframerate", "get": True, "put": True},
            {"path": "/shooting/settings/moviecropping", "get": True, "put": True},
            {"path": "/shooting/settings/movieformat", "get": True, "put": True},
        ],
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    controls = {setting.key: setting for setting in capabilities.settings}

    assert CameraFeature.MOVIE_SETTINGS_CONTROL in capabilities.supported
    assert controls["moviequality"].values == [
        "3840x2160_5994_ipb_standard",
        "1920x1080_2997_ipb_standard",
    ]
    assert controls["highframerate"].values == ["enable", "disable"]
    assert controls["moviecropping"].value == "disable"
    assert controls["movieformat"].values == ["raw", "mp4"]
    assert {"moviequality", "highframerate", "moviecropping", "movieformat"}.issubset(
        capabilities.evidence.writable_settings
    )

    session.set_setting("movieformat", "raw")

    assert transport.movie_settings["movieformat"] == "raw"
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver110/shooting/settings/movieformat",
        {"value": "raw"},
    ) in transport.requests

    put_count = sum(request.method == "PUT" for request in transport.requests)
    transport.movie_setting_responses["moviequality"] = {
        "value": "3840x2160_5994_ipb_standard",
        "ability": ["3840x2160_5994_ipb_standard", "1920x1080_2500_ipb_standard"],
    }
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("moviequality", "1920x1080_2997_ipb_standard")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count


@pytest.mark.parametrize(
    "response",
    [
        {"value": "enable", "ability": ["enable", "enable"]},
        {"value": "enable", "ability": ["enable", "unsupported"]},
        {"value": 1, "ability": ["enable", "disable"]},
    ],
)
def test_ccapi_movie_settings_hide_malformed_contract(response: object) -> None:
    discovery = {
        "ver100": [*DISCOVERY["ver100"]],
        "ver110": [{"path": "/shooting/settings/highframerate", "get": True, "put": True}],
    }
    transport = FakeCcapiTransport(
        discovery=discovery,
        movie_setting_responses={"highframerate": response},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.MOVIE_SETTINGS_CONTROL not in capabilities.supported
    assert CameraFeature.MOVIE_SETTINGS_CONTROL in capabilities.planned
    assert not any(setting.key == "highframerate" for setting in capabilities.settings)


def test_ccapi_movie_settings_do_not_combine_versions_or_use_aggregate_get() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/shooting/settings/moviequality", "get": True},
            ],
            "ver110": [{"path": "/shooting/settings/moviequality", "put": True}],
        }
    )
    transport.settings["moviequality"] = {
        "value": "3840x2160_5994_ipb_standard",
        "ability": ["3840x2160_5994_ipb_standard", "1920x1080_2997_ipb_standard"],
    }
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.MOVIE_SETTINGS_CONTROL not in capabilities.supported
    assert not any(setting.key == "moviequality" for setting in capabilities.settings)
    assert not any("moviequality" in request.path for request in transport.requests)


def test_ccapi_movie_mode_requires_matching_get_post_and_writes_action() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/shooting/control/moviemode", "get": True, "post": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    movie_mode = next(setting for setting in capabilities.settings if setting.key == "moviemode")
    assert movie_mode.value == "off"
    assert movie_mode.values == ["off", "on"]
    assert CameraFeature.MOVIE_MODE_CONTROL in capabilities.supported
    session.set_setting("moviemode", "on")
    assert transport.movie_mode == "on"
    assert RecordedRequest(
        "POST",
        "/ccapi/ver100/shooting/control/moviemode",
        {"action": "on"},
    ) in transport.requests


@pytest.mark.parametrize("movie_mode_response", [{"status": "recording"}, {"status": True}, {}])
def test_ccapi_movie_mode_hides_invalid_status(movie_mode_response: object) -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/shooting/control/moviemode", "get": True, "post": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery, movie_mode_response=movie_mode_response)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.MOVIE_MODE_CONTROL not in capabilities.supported
    assert CameraFeature.MOVIE_MODE_CONTROL in capabilities.planned
    assert not any(setting.key == "moviemode" for setting in capabilities.settings)


def test_ccapi_movie_mode_does_not_combine_get_post_across_versions() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/shooting/control/moviemode", "get": True},
            ],
            "ver110": [{"path": "/shooting/control/moviemode", "post": True}],
        }
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.MOVIE_MODE_CONTROL not in capabilities.supported
    assert not any(setting.key == "moviemode" for setting in capabilities.settings)


def test_ccapi_card_selection_requires_matching_get_put_and_writes_value() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/cardselection/stillimage", "get": True, "put": True},
            {"path": "/functions/cardselection/movie", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    settings = {setting.key: setting for setting in capabilities.settings}

    assert settings["cardselectionstillimage"].value == "card1"
    assert settings["cardselectionstillimage"].values == ["none", "card1", "card2"]
    assert settings["cardselectionmovie"].value == "card2"
    assert CameraFeature.CARD_SELECTION_CONTROL in capabilities.supported
    assert "cardselectionstillimage" in capabilities.evidence.writable_settings
    put_count = sum(request.method == "PUT" for request in transport.requests)
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("cardselectionstillimage", "card3")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count

    session.set_setting("cardselectionstillimage", "card2")

    assert transport.card_selection["stillimage"] == "card2"
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver100/functions/cardselection/stillimage",
        {"value": "card2"},
    ) in transport.requests

    put_count = sum(request.method == "PUT" for request in transport.requests)
    transport.card_selection_responses["stillimage"] = {
        "value": "card1",
        "ability": ["none", "card1"],
    }
    with pytest.raises(BridgeError, match="not advertised"):
        session.set_setting("cardselectionstillimage", "card2")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count


@pytest.mark.parametrize(
    "response",
    [
        {"value": "card1", "ability": ["card1", "card1"]},
        {"value": "card3", "ability": ["card1", "card2"]},
        {"value": "card1", "ability": ["card2", "none"]},
        {"value": "card1", "ability": ["card1", 2]},
        {"value": True, "ability": ["card1", "card2"]},
        {"value": "card1", "ability": "card1"},
    ],
)
def test_ccapi_card_selection_hides_malformed_contract(response: object) -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/cardselection/stillimage", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(
        discovery=discovery,
        card_selection_responses={"stillimage": response},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.CARD_SELECTION_CONTROL not in capabilities.supported
    assert CameraFeature.CARD_SELECTION_CONTROL in capabilities.planned
    assert not any(setting.key.startswith("cardselection") for setting in capabilities.settings)


def test_ccapi_card_selection_does_not_combine_get_put_across_versions() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/functions/cardselection/stillimage", "get": True},
            ],
            "ver110": [{"path": "/functions/cardselection/stillimage", "put": True}],
        }
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.CARD_SELECTION_CONTROL not in capabilities.supported
    assert not any(setting.key.startswith("cardselection") for setting in capabilities.settings)
    assert not any("cardselection" in request.path for request in transport.requests)


def test_ccapi_directory_control_requires_complete_group_and_validates_values() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/functions/directory/createdirectory", "post": True},
            {"path": "/functions/directory/directoryselection", "get": True, "put": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    transport.directories = ["100EOSXX"]
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    setting = next(item for item in capabilities.settings if item.key == "directoryselection")
    assert setting.value == "100EOSXX"
    assert setting.values == ["100EOSXX"]
    assert CameraFeature.DIRECTORY_CONTROL in capabilities.supported
    request_count = len(transport.requests)
    with pytest.raises(BridgeError, match="exactly five"):
        session.create_directory("bad")
    assert len(transport.requests) == request_count

    assert session.create_directory("ABCDE") == "ABCDE"
    assert "101ABCDE" in transport.directories
    session.set_setting("directoryselection", "101ABCDE")
    assert transport.directory_selection == "101ABCDE"


@pytest.mark.parametrize(
    "discovery,response",
    [
        (
            {
                "ver100": [
                    {"path": "/shooting/settings", "get": True},
                    {"path": "/functions/directory/directoryselection", "get": True, "put": True},
                ]
            },
            None,
        ),
        (
            {
                "ver100": [
                    {"path": "/shooting/settings", "get": True},
                    {"path": "/functions/directory/createdirectory", "post": True},
                    {"path": "/functions/directory/directoryselection", "get": True, "put": True},
                ]
            },
            {"value": "100EOSXX", "ability": ["100EOSXX", "100EOSXX"]},
        ),
    ],
)
def test_ccapi_directory_control_hides_incomplete_or_malformed_contract(
    discovery: dict[str, object],
    response: object | None,
) -> None:
    transport = FakeCcapiTransport(discovery=discovery, directory_selection_response=response)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.DIRECTORY_CONTROL not in capabilities.supported
    assert CameraFeature.DIRECTORY_CONTROL in capabilities.planned
    assert not any(setting.key == "directoryselection" for setting in capabilities.settings)


def test_ccapi_file_naming_requires_complete_group_and_verifies_updates() -> None:
    endpoint_paths = [
        "/functions/filename/stills/filename",
        "/functions/filename/stills/usersetting1",
        "/functions/filename/stills/usersetting2",
        "/functions/filename/movies/index",
        "/functions/filename/movies/reelnum",
        "/functions/filename/movies/clipnum",
        "/functions/filename/movies/userdefined",
    ]
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            *({"path": path, "get": True, "put": True} for path in endpoint_paths),
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    naming = capabilities.file_naming
    assert naming is not None
    assert naming.still_filename_mode == "preset_code"
    assert naming.still_user_setting_1 == "IMG_"
    assert naming.movie_reel_range.maximum == 9999
    assert naming.movie_clip_range.maximum == 999
    assert CameraFeature.FILE_NAMING_CONTROL in capabilities.supported
    assert {field.value for field in FileNamingField} <= set(capabilities.evidence.writable_settings)

    put_count = sum(request.method == "PUT" for request in transport.requests)
    with pytest.raises(BridgeError, match="not valid"):
        session.set_file_naming(FileNamingField.STILL_USER_SETTING_1, "_BAD")
    assert sum(request.method == "PUT" for request in transport.requests) == put_count

    updated = session.set_file_naming(FileNamingField.STILL_USER_SETTING_1, "R6M_")
    assert updated.still_user_setting_1 == "R6M_"
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver100/functions/filename/stills/usersetting1",
        {"usersetting1": "R6M_"},
    ) in transport.requests

    updated = session.set_file_naming(FileNamingField.MOVIE_REEL_NUMBER, "42")
    assert updated.movie_reel_number == 42
    assert RecordedRequest(
        "PUT",
        "/ccapi/ver100/functions/filename/movies/reelnum",
        {"value": 42},
    ) in transport.requests


@pytest.mark.parametrize(
    "discovery,file_naming_responses",
    [
        (
            {
                "ver100": [
                    *DISCOVERY["ver100"],
                    {"path": "/functions/filename/stills/filename", "get": True, "put": True},
                ]
            },
            {},
        ),
        (
            {
                "ver100": [
                    *DISCOVERY["ver100"],
                    *(
                        {"path": path, "get": True, "put": True}
                        for path in [
                            "/functions/filename/stills/filename",
                            "/functions/filename/stills/usersetting1",
                            "/functions/filename/stills/usersetting2",
                            "/functions/filename/movies/index",
                            "/functions/filename/movies/reelnum",
                            "/functions/filename/movies/clipnum",
                            "/functions/filename/movies/userdefined",
                        ]
                    ),
                ]
            },
            {"movie-reel-number": {"value": 0, "ability": {"min": 1, "max": 9999, "step": 1}}},
        ),
    ],
)
def test_ccapi_file_naming_hides_incomplete_or_malformed_contract(
    discovery: dict[str, object],
    file_naming_responses: dict[str, object],
) -> None:
    transport = FakeCcapiTransport(
        discovery=discovery,
        file_naming_responses=file_naming_responses,
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert capabilities.file_naming is None
    assert CameraFeature.FILE_NAMING_CONTROL not in capabilities.supported
    assert CameraFeature.FILE_NAMING_CONTROL in capabilities.planned


def test_ccapi_clock_sync_does_not_combine_read_and_write_across_api_versions() -> None:
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                {"path": "/shooting/settings", "get": True},
                {"path": "/functions/datetime", "get": True},
            ],
            "ver110": [{"path": "/functions/datetime", "put": True}],
        }
    )
    session = CcapiEngine(lambda _username, _password: transport, sleeper=lambda _: None).open_connection(
        "http://192.168.1.2:8080/"
    )

    capabilities = session.capabilities()

    assert CameraFeature.CAMERA_CLOCK_SYNC not in capabilities.supported
    assert CameraFeature.CAMERA_CLOCK_SYNC in capabilities.planned
    request_count = len(transport.requests)
    with pytest.raises(BridgeError, match="same API version"):
        session.sync_camera_clock()
    assert len(transport.requests) == request_count


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


def test_ccapi_live_view_magnification_uses_string_put_and_get_readback() -> None:
    transport = FakeCcapiTransport(discovery=LIVE_VIEW_MAGNIFICATION_DISCOVERY)
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )

    capabilities = session.capabilities()
    assert CameraFeature.LIVE_VIEW_MAGNIFICATION in capabilities.supported
    assert capabilities.live_view.magnifications == [1, 5, 10]
    assert capabilities.live_view.current_magnification == 1

    with pytest.raises(BridgeError) as inactive:
        session.set_live_view_magnification(10)
    assert inactive.value.code == "LIVE_VIEW_REQUIRED"

    session.start_live_view(LiveViewStartRequest(source="CCAPI_JPEG_POLLING"))
    result = session.set_live_view_magnification(10)

    assert result.model_dump() == {"accepted": True, "value": 10}
    assert transport.live_view_magnification == "10"
    lvzoom_requests = [
        request
        for request in transport.requests
        if request.path.endswith("/shooting/settings/lvzoom")
    ]
    assert [(request.method, request.body) for request in lvzoom_requests] == [
        ("GET", None),
        ("GET", None),
        ("PUT", {"value": "10"}),
        ("GET", None),
    ]


def test_ccapi_live_view_magnification_rejects_movie_mode_before_put() -> None:
    discovery = {
        "ver100": [
            *LIVE_VIEW_MAGNIFICATION_DISCOVERY["ver100"],
            {"path": "/shooting/control/moviemode", "get": True, "post": True},
        ]
    }
    transport = FakeCcapiTransport(discovery=discovery, movie_mode_response={"status": "on"})
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )
    session.capabilities()
    session.start_live_view(LiveViewStartRequest(source="CCAPI_JPEG_POLLING"))

    with pytest.raises(BridgeError) as unavailable:
        session.set_live_view_magnification(10)

    assert unavailable.value.code == "LIVE_VIEW_MAGNIFICATION_UNAVAILABLE"
    assert not any(
        request.method == "PUT" and request.path.endswith("/shooting/settings/lvzoom")
        for request in transport.requests
    )


@pytest.mark.parametrize(
    "response",
    [
        {"value": 1, "ability": ["1", "5"]},
        {"value": "1", "ability": ["1"]},
        {"value": "2", "ability": ["1", "2", "5"]},
        {"value": "10", "ability": ["1", "5"]},
        {"value": "1", "ability": ["1", "5", "5"]},
        {"value": "1", "ability": [{"value": "1"}, "5"]},
    ],
)
def test_ccapi_live_view_magnification_rejects_invalid_ability(response: object) -> None:
    transport = FakeCcapiTransport(
        discovery=LIVE_VIEW_MAGNIFICATION_DISCOVERY,
        live_view_magnification_response=response,
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )

    capabilities = session.capabilities()

    assert CameraFeature.LIVE_VIEW_MAGNIFICATION not in capabilities.supported
    assert capabilities.live_view.magnifications == []
    assert all(
        not (request.method == "PUT" and request.path.endswith("/shooting/settings/lvzoom"))
        for request in transport.requests
    )


def test_ccapi_live_view_magnification_does_not_combine_versions() -> None:
    discovery = {
        "ver100": [
            *DISCOVERY["ver100"],
            {"path": "/shooting/settings/lvzoom", "get": True},
        ],
        "ver110": [{"path": "/shooting/settings/lvzoom", "put": True}],
    }
    transport = FakeCcapiTransport(discovery=discovery)
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )

    capabilities = session.capabilities()

    assert CameraFeature.LIVE_VIEW_MAGNIFICATION not in capabilities.supported
    assert CameraFeature.LIVE_VIEW_MAGNIFICATION in capabilities.planned
    assert not any(request.path.endswith("/shooting/settings/lvzoom") for request in transport.requests)


def test_ccapi_live_view_magnification_rejects_mismatched_readback() -> None:
    transport = FakeCcapiTransport(
        discovery=LIVE_VIEW_MAGNIFICATION_DISCOVERY,
        live_view_magnification_response={"value": "5", "ability": ["1", "5", "10"]},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection(
        "http://192.168.1.2:8080"
    )
    session.start_live_view(LiveViewStartRequest(source="CCAPI_JPEG_POLLING"))

    with pytest.raises(BridgeError, match="did not match"):
        session.set_live_view_magnification(10)


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
    assert session.status().raw["rtpAudio"]["codec"] == "MP4A-LATM"
    assert session.live_view_frame() == RTP_JPEG
    audio = session.live_view_audio()
    assert audio is not None
    assert audio.content == b"\x00\x00\x01\x00"

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


def test_ccapi_multipart_parser_accepts_lf_and_quoted_boundary() -> None:
    boundary = parse_multipart_boundary('multipart/x-mixed-replace; charset=binary; boundary="canon"')
    body = (
        b"--canon\nContent-Type: image/jpeg\nContent-Length: "
        + str(len(JPEG)).encode()
        + b"\n\n"
        + JPEG
        + b"\n--canon--\n"
    )
    reader = CcapiMultipartReader(BytesIO(body), boundary)

    assert reader.next_frame() == JPEG
    assert reader.next_frame() is None


@pytest.mark.parametrize(
    ("content_type", "message"),
    [
        ("image/jpeg", "multipart/x-mixed-replace"),
        ("multipart/x-mixed-replace", "boundary"),
        ("multipart/x-mixed-replace;boundary=bad boundary", "ASCII boundary"),
    ],
)
def test_ccapi_multipart_parser_rejects_invalid_content_type(content_type: str, message: str) -> None:
    with pytest.raises(BridgeError, match=message):
        parse_multipart_boundary(content_type)


def test_ccapi_multipart_parser_rejects_invalid_or_oversized_frames() -> None:
    invalid_length = BytesIO(b"--b\nContent-Type: image/jpeg\nContent-Length: -1\n\nx\n--b--\n")
    oversized = BytesIO(
        b"--b\nContent-Type: image/jpeg\nContent-Length: "
        + str(MAX_LIVE_VIEW_FRAME_BYTES + 1).encode()
        + b"\n\n"
    )

    with pytest.raises(BridgeError, match="Content-Length"):
        CcapiMultipartReader(invalid_length, "b").next_frame()
    with pytest.raises(BridgeError, match="safety limit"):
        CcapiMultipartReader(oversized, "b").next_frame()


def test_ccapi_multipart_capability_and_exact_lifecycle_are_end_to_end() -> None:
    transport = FakeCcapiTransport(discovery=MULTIPART_DISCOVERY)
    session = CcapiEngine(
        lambda _username, _password: transport,
        rtp_session_factory=None,
        route_resolver=lambda _url: "192.168.1.20",
        sleeper=lambda _: None,
    ).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    assert CameraFeature.LIVE_VIEW_MULTIPART in capabilities.supported
    assert capabilities.live_view.sources == ["CCAPI_MULTIPART", "CCAPI_JPEG_POLLING"]
    assert capabilities.live_view.default_source == "CCAPI_MULTIPART"

    session.start_live_view(LiveViewStartRequest(fps=15, source="AUTO"))
    assert session.live_view_source == "CCAPI_MULTIPART"
    assert CameraFeature.LIVE_VIEW_MULTIPART not in session.capabilities().evidence.observed_features
    assert session.live_view_frame() == JPEG
    assert CameraFeature.LIVE_VIEW_MULTIPART in session.capabilities().evidence.observed_features
    session.stop_live_view()

    lifecycle = [
        (request.method, request.path)
        for request in transport.requests
        if request.path in {
            "/ccapi/ver100/shooting/liveview",
            "/ccapi/ver100/shooting/liveview/multipart",
        }
    ]
    assert lifecycle == [
        ("POST", "/ccapi/ver100/shooting/liveview"),
        ("POST", "/ccapi/ver100/shooting/liveview"),
        ("GET", "/ccapi/ver100/shooting/liveview/multipart"),
        ("DELETE", "/ccapi/ver100/shooting/liveview/multipart"),
        ("DELETE", "/ccapi/ver100/shooting/liveview"),
    ]


def test_ccapi_multipart_requires_one_api_version_for_the_complete_lifecycle() -> None:
    split_discovery = {
        "ver100": [*DISCOVERY["ver100"]],
        "ver110": [{"path": "/shooting/liveview/multipart", "get": True, "delete": True}],
    }
    transport = FakeCcapiTransport(discovery=split_discovery)
    session = CcapiEngine(
        lambda _username, _password: transport,
        rtp_session_factory=None,
    ).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()
    assert CameraFeature.LIVE_VIEW_MULTIPART not in capabilities.supported
    assert CameraFeature.LIVE_VIEW_MULTIPART in capabilities.planned
    with pytest.raises(BridgeError) as failure:
        session.start_live_view(LiveViewStartRequest(source="CCAPI_MULTIPART"))
    assert failure.value.feature == CameraFeature.LIVE_VIEW_MULTIPART.value


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


def test_ccapi_discovery_loads_canon_developer_api_list_when_root_omits_operations() -> None:
    transport = FakeCcapiTransport(
        discovery={"value": "No list of APIs"},
        developer_discovery=DISCOVERY,
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.STILL_CAPTURE in capabilities.supported
    assert CameraFeature.VIDEO_RECORDING in capabilities.supported
    assert CameraFeature.MEDIA_BROWSER in capabilities.supported
    assert capabilities.evidence.source == "GET /ccapi/ver100/topurlfordev (Canon developer API fallback)"
    assert "POST /ccapi/ver100/shooting/control/shutterbutton" in capabilities.evidence.advertised_commands
    assert [attempt.outcome for attempt in capabilities.evidence.discovery_trace] == ["NO_API_LIST", "OPERATIONS"]
    assert [attempt.endpoint for attempt in capabilities.evidence.discovery_trace] == [
        "GET /ccapi",
        "GET /ccapi/ver100/topurlfordev",
    ]
    assert capabilities.evidence.discovery_trace[-1].advertised_operation_count > 0
    request_paths = [request.path for request in transport.requests]
    assert request_paths[:2] == [
        "/ccapi",
        "/ccapi/ver100/topurlfordev",
    ]
    assert "/ccapi/ver100/shooting/settings" in request_paths


def test_ccapi_discovery_loads_developer_api_when_firmware_returns_version_without_commands() -> None:
    transport = FakeCcapiTransport(
        discovery={"api": ["/ccapi/ver100"], "version": "ver100", "ver100": []},
        developer_discovery=DISCOVERY,
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.LIVE_VIEW in capabilities.supported
    assert CameraFeature.STILL_CAPTURE in capabilities.supported
    assert CameraFeature.VIDEO_RECORDING in capabilities.supported
    assert capabilities.evidence.advertised_commands
    assert capabilities.evidence.source == "GET /ccapi/ver100/topurlfordev (Canon developer API fallback)"
    assert [attempt.outcome for attempt in capabilities.evidence.discovery_trace] == [
        "ZERO_OPERATIONS",
        "OPERATIONS",
    ]
    assert capabilities.evidence.discovery_trace[0].protocol_versions == ["ver100"]
    assert capabilities.evidence.discovery_trace[0].advertised_operation_count == 0
    request_paths = [request.path for request in transport.requests]
    assert request_paths[:2] == [
        "/ccapi",
        "/ccapi/ver100/topurlfordev",
    ]
    assert "/ccapi/ver100/shooting/settings" in request_paths


def test_ccapi_discovery_rejects_empty_developer_api_list_without_inventing_capabilities() -> None:
    transport = FakeCcapiTransport(
        discovery={"ver100": []},
        developer_discovery={"ver100": []},
    )
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.STILL_CAPTURE not in capabilities.supported
    assert CameraFeature.STILL_CAPTURE in capabilities.planned
    assert capabilities.evidence.advertised_commands == []
    assert capabilities.evidence.source == "GET /ccapi/ver100/deviceinformation (identity fallback)"
    assert [attempt.outcome for attempt in capabilities.evidence.discovery_trace] == [
        "ZERO_OPERATIONS",
        "ZERO_OPERATIONS",
        "HTTP_ERROR",
        "HTTP_ERROR",
        "IDENTITY",
    ]
    assert capabilities.evidence.discovery_trace[-1].response_keys == ["productname", "serialnumber", "version"]
    request_paths = [request.path for request in transport.requests]
    assert request_paths[:3] == [
        "/ccapi",
        "/ccapi/ver100/topurlfordev",
        "/ccapi/",
    ]


def test_ccapi_discovery_developer_api_failure_does_not_invent_capabilities() -> None:
    transport = FakeCcapiTransport(discovery={"value": "No list of APIs"})
    session = CcapiEngine(lambda _username, _password: transport).open_connection("http://192.168.1.2:8080")

    capabilities = session.capabilities()

    assert CameraFeature.STILL_CAPTURE not in capabilities.supported
    assert CameraFeature.STILL_CAPTURE in capabilities.planned
    assert capabilities.evidence.source == "GET /ccapi/ver100/deviceinformation (identity fallback)"
    assert "/ccapi/ver100/topurlfordev" in [request.path for request in transport.requests]
    assert "HTTP_ERROR" in [attempt.outcome for attempt in capabilities.evidence.discovery_trace]
    assert all("camera busy" not in str(attempt) for attempt in capabilities.evidence.discovery_trace)


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
    with pytest.raises(BridgeError) as archive_failure:
        session.set_media_archive("ccapi:invalid", True)
    with pytest.raises(BridgeError) as focus_failure:
        session.drive_focus("near", "small")

    assert CameraFeature.STILL_CAPTURE not in capabilities.supported
    assert CameraFeature.EXPOSURE_CONTROL not in capabilities.supported
    assert capabilities.settings == []
    assert failure.value.code == "UNSUPPORTED_FEATURE"
    assert delete_failure.value.code == "UNSUPPORTED_FEATURE"
    assert thumbnail_failure.value.code == "UNSUPPORTED_FEATURE"
    assert archive_failure.value.code == "UNSUPPORTED_FEATURE"
    assert focus_failure.value.code == "UNSUPPORTED_FEATURE"
    observed = set(session.capabilities().evidence.observed_features)
    assert CameraFeature.STILL_CAPTURE not in observed
    assert CameraFeature.MEDIA_DELETE not in observed
    assert CameraFeature.MEDIA_ARCHIVE not in observed
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
    transport = FakeCcapiTransport(
        discovery={
            "ver100": [
                *DISCOVERY["ver100"],
                {"path": "/functions/directory/createdirectory", "post": True},
                {"path": "/functions/directory/directoryselection", "get": True, "put": True},
                {"path": "/functions/filename/stills/filename", "get": True, "put": True},
                {"path": "/functions/filename/stills/usersetting1", "get": True, "put": True},
                {"path": "/functions/filename/stills/usersetting2", "get": True, "put": True},
                {"path": "/functions/filename/movies/index", "get": True, "put": True},
                {"path": "/functions/filename/movies/reelnum", "get": True, "put": True},
                {"path": "/functions/filename/movies/clipnum", "get": True, "put": True},
                {"path": "/functions/filename/movies/userdefined", "get": True, "put": True},
            ]
        }
    )
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
        media_info = client.get(f"/v1/session/{session_id}/media/{media_id}/info", headers=headers)
        protected = client.put(
            f"/v1/session/{session_id}/media/{media_id}/protection",
            headers=headers,
            json={"enabled": True},
        )
        rated = client.put(
            f"/v1/session/{session_id}/media/{media_id}/rating",
            headers=headers,
            json={"value": 4},
        )
        rotated = client.put(
            f"/v1/session/{session_id}/media/{media_id}/rotation",
            headers=headers,
            json={"degrees": 180},
        )
        archived = client.put(
            f"/v1/session/{session_id}/media/{media_id}/archive",
            headers=headers,
            json={"enabled": True},
        )
        created_directory = client.post(
            f"/v1/session/{session_id}/directories",
            headers=headers,
            json={"name": "ABCDE"},
        )
        updated_file_naming = client.put(
            f"/v1/session/{session_id}/file-naming/movie-index",
            headers=headers,
            json={"value": "B_"},
        )
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
    assert "MEDIA_PROTECT" in capabilities.json()["supported"]
    assert "MEDIA_RATING" in capabilities.json()["supported"]
    assert "MEDIA_ROTATE" in capabilities.json()["supported"]
    assert "MEDIA_ARCHIVE" in capabilities.json()["supported"]
    assert "DIRECTORY_CONTROL" in capabilities.json()["supported"]
    assert "FILE_NAMING_CONTROL" in capabilities.json()["supported"]
    assert capabilities.json()["fileNaming"]["movieIndex"] == "A_"
    assert created_directory.status_code == 200
    assert created_directory.json() == {"name": "ABCDE"}
    assert updated_file_naming.status_code == 200
    assert updated_file_naming.json()["movieIndex"] == "B_"
    assert thumbnail.content == JPEG
    assert thumbnail.headers["content-type"].startswith("image/jpeg")
    assert thumbnail.headers["cache-control"] == "private, no-store, max-age=0"
    assert preview.content == JPEG
    assert preview.headers["content-type"].startswith("image/jpeg")
    assert preview.headers["cache-control"] == "private, no-store, max-age=0"
    assert media_info.json()["protected"] is False
    assert protected.json()["protected"] is True
    assert rated.json()["rating"] == 4
    assert rotated.json()["rotationDegrees"] == 180
    assert archived.json()["archived"] is True
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
