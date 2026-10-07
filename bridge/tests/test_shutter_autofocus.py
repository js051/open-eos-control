from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine

from .fakes import FakeRunner
from .test_ccapi import DEVICE_STATUS_DISCOVERY, DISCOVERY, FakeCcapiTransport


def engine(transport):
    return CcapiEngine(lambda *_: transport, sleeper=lambda _: None)


def shutter_requests(transport):
    return [request for request in transport.requests if request.method != "GET" and "shutterbutton" in request.path]


@pytest.mark.parametrize("body, expected", [(None, True), ({}, True), ({"af": True}, True), ({"af": False}, False)])
def test_http_shutter_default_and_explicit_boolean_reach_camera_once(body, expected):
    transport = FakeCcapiTransport()
    with TestClient(create_app(ccapi_engine=engine(transport))) as client:
        session = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://192.0.2.1"}).json()["id"]
        response = client.post(f"/v1/session/{session}/capture/still", **({"json": body} if body is not None else {}))
    assert response.status_code == 200
    commands = shutter_requests(transport)
    assert [(request.method, request.body) for request in commands] == [("POST", {"af": expected})]
    assert type(commands[0].body["af"]) is bool


def test_capable_http_session_advertises_shutter_af_choice():
    transport = FakeCcapiTransport()
    with TestClient(create_app(ccapi_engine=engine(transport))) as client:
        session = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://192.0.2.1"}).json()["id"]
        assert client.get(f"/v1/session/{session}/capabilities").json().get("shutterAutofocusSupported") is True


@pytest.mark.parametrize("body", [{"autofocus": False}, {"af": False, "unknown": True}, [], "false", 0])
def test_http_rejects_malformed_shoot_body_without_defaulting_to_af_true(body):
    transport = FakeCcapiTransport()
    with TestClient(create_app(ccapi_engine=engine(transport))) as client:
        session = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://192.0.2.1"}).json()["id"]
        before = list(transport.requests)
        response = client.post(f"/v1/session/{session}/capture/still", json=body)
    assert response.status_code == 422
    assert transport.requests == before


@pytest.mark.parametrize("invalid", ["false", "true", 0, 1, None, [], {}])
def test_http_rejects_non_boolean_af_before_any_camera_request(invalid):
    transport = FakeCcapiTransport()
    with TestClient(create_app(ccapi_engine=engine(transport))) as client:
        session = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://192.0.2.1"}).json()["id"]
        before = list(transport.requests)
        response = client.post(f"/v1/session/{session}/capture/still", json={"af": invalid})
    assert response.status_code == 422
    assert transport.requests == before


@pytest.mark.parametrize("method", ["POST", "PUT"])
@pytest.mark.parametrize("af", [True, False])
def test_manual_press_uses_choice_and_exact_advertised_method_then_releases_without_af(method, af):
    discovery = {"ver100": [entry for entry in DISCOVERY["ver100"] if "shutterbutton" not in entry["path"]] + [
        {"path": "/shooting/control/shutterbutton/manual", method.lower(): True},
    ]}
    transport = FakeCcapiTransport(discovery=discovery)
    session = engine(transport).open_connection("http://192.0.2.1")
    assert session.capabilities().shutter_autofocus_supported
    session.capture_still(autofocus=af)
    assert [(request.method, request.body) for request in shutter_requests(transport)] == [
        (method, {"af": af, "action": "full_press"}), (method, {"af": False, "action": "release"}),
    ]


def test_false_is_rejected_for_gphoto_without_camera_calls_and_legacy_default_remains_usable():
    runner = FakeRunner()
    with TestClient(create_app(engine=GPhoto2Engine(runner))) as client:
        session = client.post("/v1/session", json={}).json()["id"]
        assert client.get(f"/v1/session/{session}/capabilities").json().get("shutterAutofocusSupported", False) is False
        before = list(runner.commands)
        response = client.post(f"/v1/session/{session}/capture/still", json={"af": False})
        assert response.status_code == 409
        assert response.json()["error"]["code"] == "UNSUPPORTED_FEATURE"
        assert response.json()["error"]["feature"] == "STILL_CAPTURE"
        assert response.json()["error"]["engine"] == "libgphoto2"
        assert runner.commands == before
        assert client.post(f"/v1/session/{session}/capture/still").status_code == 200


def test_get_only_shutter_does_not_advertise_choice_or_dispatch_false():
    transport = FakeCcapiTransport(discovery={"ver100": [
        {"path": "/deviceinformation", "get": True},
        {"path": "/shooting/control/shutterbutton", "get": True, "put": True},
    ]})
    session = engine(transport).open_connection("http://192.0.2.1")
    assert not session.capabilities().shutter_autofocus_supported
    before = list(transport.requests)
    with pytest.raises(BridgeError):
        session.capture_still(autofocus=False)
    assert transport.requests == before


def test_rejected_manual_false_still_releases_once_without_falling_back_to_true():
    discovery = {"ver100": [
        entry for entry in DISCOVERY["ver100"] if entry["path"] != "/shooting/control/shutterbutton"
    ]}
    transport = FakeCcapiTransport(discovery=discovery, reject_bulb_press=True)
    session = engine(transport).open_connection("http://192.0.2.1")
    with pytest.raises(BridgeError):
        session.capture_still(autofocus=False)
    assert [request.body for request in shutter_requests(transport)] == [
        {"af": False, "action": "full_press"}, {"af": False, "action": "release"},
    ]


def test_false_does_not_bypass_temperature_restriction():
    transport = FakeCcapiTransport(discovery=DEVICE_STATUS_DISCOVERY,
                                   temperature_response={"status": "disablerelease"})
    session = engine(transport).open_connection("http://192.0.2.1")
    assert session.capabilities().shutter_autofocus_supported
    with pytest.raises(BridgeError):
        session.capture_still(autofocus=False)
    assert not shutter_requests(transport)
