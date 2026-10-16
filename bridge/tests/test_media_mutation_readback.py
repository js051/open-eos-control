"""Media-only request/evidence validation; fixtures never contact a camera."""
from __future__ import annotations

import json
from io import BytesIO
from pathlib import Path
from unittest.mock import Mock
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiOperation, CcapiResponse, CcapiSession, CcapiStreamResponse, _media_id
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import (
    CameraFeature,
    MediaArchiveUpdate,
    MediaItem,
    MediaProtectionUpdate,
    MediaRatingUpdate,
    MediaRotationUpdate,
)

ROOT = "/ccapi/ver100/contents"
PHOTO = f"{ROOT}/card1/100CANON/SYNTHETIC.JPG"
MEDIA_ID = _media_id(PHOTO)
OTHER_ID = _media_id(f"{ROOT}/card2/100CANON/SYNTHETIC.JPG")
ACTIONS = (
    ("protection", "protect", "protected", True, "enable", CameraFeature.MEDIA_PROTECT),
    ("rating", "rating", "rating", 1, "1", CameraFeature.MEDIA_RATING),
    ("rotation", "rotate", "rotation_degrees", 90, "90", CameraFeature.MEDIA_ROTATE),
    ("archive", "archive", "archived", True, "enable", CameraFeature.MEDIA_ARCHIVE),
)
MODELS = (
    (MediaProtectionUpdate, "enabled", "protected"),
    (MediaRatingUpdate, "value", "rating"),
    (MediaRotationUpdate, "degrees", "rotationDegrees"),
    (MediaArchiveUpdate, "enabled", "archived"),
)
CONTRACT = json.loads(
    (Path(__file__).parents[2] / "contracts/media-mutation/v1/media-mutation-vectors.json").read_text(),
)
NORMALIZED_FIELDS = {"rating": "rating", "rotation": "rotationDegrees",
                     "protection": "protected", "archive": "archived"}
CANON_FIELDS = {"rating": "rating", "rotation": "rotate", "protection": "protect", "archive": "archive"}


class MutationTransport:
    """Independent command acknowledgement and readbacks; never mirrors a write."""

    def __init__(self, responses: list[object], put_response: object | None = None) -> None:
        self.responses = list(responses)
        self.put_response = put_response
        self.requests: list[tuple[str, str, object]] = []
        self.reads = 0

    def request(self, method: str, url: str, **kwargs: object) -> CcapiResponse:
        parsed = urlsplit(url)
        path = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        body = kwargs.get("body")
        self.requests.append((method, path, json.loads(body) if body else None))
        if method == "PUT":
            assert path == PHOTO
            response = self.put_response
        else:
            assert method == "GET" and path == f"{PHOTO}?kind=info"
            response = self.responses[min(self.reads, len(self.responses) - 1)]
            self.reads += 1
        if isinstance(response, Exception):
            raise response
        if isinstance(response, CcapiResponse):
            return response
        return CcapiResponse(200, {"content-type": "application/json"}, json.dumps(response).encode())

    def close(self) -> None:
        pass


def session_for(responses: list[object], put_response: object | None = None):
    transport = MutationTransport(responses, put_response)
    session = CcapiSession("http://192.0.2.1:8080", transport, sleeper=Mock())
    session._initialized = True
    session._operations = {CcapiOperation("GET", ROOT), CcapiOperation("PUT", ROOT)}
    return session, transport


def assert_requests(transport: MutationTransport, action: str, wire_value: str, reads: int) -> None:
    assert transport.requests == [
        ("PUT", PHOTO, {"action": action, "value": wire_value}),
        *[("GET", f"{PHOTO}?kind=info", None)] * reads,
    ]


@pytest.mark.parametrize("row", CONTRACT["metadataVectors"], ids=lambda row: row["id"])
def test_shared_metadata_vectors_use_production_models_and_ccapi_observation(row):
    field = NORMALIZED_FIELDS[row["field"]]
    literal = row["rawValueJSON"]
    if row["profile"] == "normalized":
        fields = f',"{field}":{literal}' if literal is not None else ""
        try:
            item = MediaItem.model_validate_json(
                f'{{"id":"synthetic","name":"SYNTHETIC.JPG","kind":"image"{fields}}}',
            )
        except ValidationError:
            assert row["known"] is False
            return
    else:
        canon_field = CANON_FIELDS[row["field"]]
        response = {} if literal is None else json.loads(f'{{"{canon_field}":{literal}}}')
        session, transport = session_for([response])
        item = session.media_info(MEDIA_ID)
        assert transport.requests == [("GET", f"{PHOTO}?kind=info", None)]
    result = item.model_dump(by_alias=True)[field]
    assert (result is not None) is row["known"]
    assert result == row["value"]
    if row["known"]:
        assert type(result) is type(row["value"])


@pytest.mark.parametrize("row", [row for row in CONTRACT["readbackVectors"] if row["profile"] == "canon"],
                         ids=lambda row: row["id"])
def test_shared_canon_readback_vectors_use_actual_mutation_dispatch(row):
    action = CANON_FIELDS[row["operation"]]
    response = {} if row["rawValueJSON"] is None else json.loads(f'{{"{action}":{row["rawValueJSON"]}}}')
    if row["returnedIdPresent"]:
        response["id"] = MEDIA_ID if row["returnedId"] == row["requestedId"] else row["returnedId"]
    session, transport = session_for([response])
    operation = getattr(session, f'set_media_{row["operation"]}')
    if row["outcome"] == "confirmed":
        result = operation(MEDIA_ID, row["target"])
        assert result.id == MEDIA_ID
        assert result.model_dump(by_alias=True)[NORMALIZED_FIELDS[row["operation"]]] == row["target"]
    else:
        with pytest.raises(BridgeError):
            operation(MEDIA_ID, row["target"])
    assert sum(method == "PUT" for method, _, _ in transport.requests) == 1
    assert 1 <= transport.reads <= 3
    assert all(path in (PHOTO, f"{PHOTO}?kind=info") for _, path, _ in transport.requests)


@pytest.mark.parametrize("model,key,field", MODELS)
def test_normalized_media_missing_and_null_are_unknown_but_not_valid_requests(model, key, field) -> None:
    for fields in ({}, {field: None}):
        item = MediaItem.model_validate({"id": MEDIA_ID, "name": "SYNTHETIC.JPG", "kind": "image", **fields})
        assert item.model_dump(by_alias=True)[field] is None
    for fields in ({}, {key: None}):
        with pytest.raises(ValidationError):
            model.model_validate(fields)


@pytest.mark.parametrize("rating", list(range(6)) + [str(value) for value in range(6)])
def test_normalized_rating_accepts_only_canonical_values(rating) -> None:
    assert MediaRatingUpdate(value=rating).value == int(rating)
    item = MediaItem(id=MEDIA_ID, name="SYNTHETIC.JPG", kind="image", rating=rating)
    assert type(item.rating) is int and item.rating == int(rating)


@pytest.mark.parametrize("degrees", [0, 90, 180, 270, "0", "90", "180", "270"])
def test_normalized_rotation_accepts_only_canonical_values(degrees) -> None:
    assert MediaRotationUpdate(degrees=degrees).degrees == int(degrees)
    item = MediaItem(id=MEDIA_ID, name="SYNTHETIC.JPG", kind="image", rotationDegrees=degrees)
    assert type(item.rotation_degrees) is int and item.rotation_degrees == int(degrees)


@pytest.mark.parametrize("model,key,field", [MODELS[0], MODELS[3]])
@pytest.mark.parametrize("value", [True, False])
def test_normalized_boolean_requires_json_boolean(model, key, field, value) -> None:
    assert model.model_validate({key: value}).enabled is value
    item = MediaItem.model_validate({"id": MEDIA_ID, "name": "SYNTHETIC.JPG", "kind": "image", field: value})
    assert item.model_dump(by_alias=True)[field] is value


INVALID_NUMBERS = [
    "true", "false", "0.0", "1.0", "90.0", "1e0", "9e1", "0.5", "90.5", "-1", "6", "360",
    '"01"', '"00"', '"090"', '"+1"', '"+90"', '"-0"', '" 1"', '"90 "', '"1\\n"',
    '"1.0"', '"90.0"', '"1e0"', '"9e1"', '"１"', '"١"', '"９０"', '""', '"off"',
    "[]", "[1]", "{}", '{"value":1}', "9223372036854775808", '"9223372036854775808"',
]


@pytest.mark.parametrize("model,key,field", [MODELS[1], MODELS[2]])
@pytest.mark.parametrize("literal", INVALID_NUMBERS)
def test_normalized_numeric_media_rejects_coercion(model, key, field, literal) -> None:
    with pytest.raises(ValidationError):
        model.model_validate_json(f'{{"{key}":{literal}}}')
    with pytest.raises(ValidationError):
        MediaItem.model_validate_json(
            f'{{"id":"synthetic","name":"SYNTHETIC.JPG","kind":"image","{field}":{literal}}}',
        )


@pytest.mark.parametrize("model,key,field", [MODELS[0], MODELS[3]])
@pytest.mark.parametrize("literal", [
    "0", "1", "0.0", "1.0", '"true"', '"false"', '"1"', '"0"', '"enable"', '"disable"',
    '"yes"', '"no"', '" true "', '""', "[]", "{}", '{"enabled":true}',
])
def test_normalized_boolean_media_rejects_coercion(model, key, field, literal) -> None:
    with pytest.raises(ValidationError):
        model.model_validate_json(f'{{"{key}":{literal}}}')
    with pytest.raises(ValidationError):
        MediaItem.model_validate_json(
            f'{{"id":"synthetic","name":"SYNTHETIC.JPG","kind":"image","{field}":{literal}}}',
        )


@pytest.mark.parametrize("operation,payload", [
    ("rating", '{"value":true}'), ("rating", '{"value":1.0}'), ("rating", '{"value":1e0}'),
    ("rating", '{"value":"01"}'), ("rating", '{"value":" 1"}'),
    ("rotation", '{"degrees":false}'), ("rotation", '{"degrees":90.0}'),
    ("rotation", '{"degrees":9e1}'), ("rotation", '{"degrees":"090"}'),
    ("protection", '{"enabled":1}'), ("protection", '{"enabled":"true"}'),
    ("archive", '{"enabled":0}'), ("archive", '{"enabled":"disable"}'),
])
def test_http_request_validation_rejects_malformed_values_before_camera_io(operation, payload):
    session, transport = session_for([{}])
    app = create_app()
    app.state.session_manager._sessions["synthetic"] = session
    with TestClient(app) as client:
        response = client.put(
            f"/v1/session/synthetic/media/{MEDIA_ID}/{operation}",
            content=payload, headers={"Content-Type": "application/json"},
        )
    assert response.status_code == 422 and transport.requests == []


@pytest.mark.parametrize("operation,payload,action,wire_value", [
    ("rating", {"value": "0"}, "rating", "off"),
    ("rating", {"value": "5"}, "rating", "5"),
    ("rotation", {"degrees": "270"}, "rotate", "270"),
    ("protection", {"enabled": False}, "protect", "disable"),
    ("archive", {"enabled": True}, "archive", "enable"),
])
def test_http_canonical_request_uses_one_write_and_matching_readback(operation, payload, action, wire_value):
    session, transport = session_for([{action: wire_value}])
    app = create_app()
    app.state.session_manager._sessions["synthetic"] = session
    with TestClient(app) as client:
        response = client.put(f"/v1/session/synthetic/media/{MEDIA_ID}/{operation}", json=payload)
    assert response.status_code == 200 and response.json()["id"] == MEDIA_ID
    assert_requests(transport, action, wire_value, 1)


@pytest.mark.parametrize("method,action,field,requested,wire_value,feature", ACTIONS)
@pytest.mark.parametrize("explicit_id", [False, True])
def test_exact_ccapi_readback_confirms_one_write(method, action, field, requested, wire_value, feature, explicit_id):
    response = {action: wire_value, **({"id": MEDIA_ID} if explicit_id else {})}
    session, transport = session_for([response])
    result = getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
    assert result.id == MEDIA_ID and getattr(result, field) == requested
    assert feature in session._observed
    assert_requests(transport, action, wire_value, 1)


@pytest.mark.parametrize("method,action,requested,wire_value", [
    ("rating", "rating", 0, "off"), *[("rating", "rating", value, str(value)) for value in range(1, 6)],
    *[("rotation", "rotate", value, str(value)) for value in (0, 90, 180, 270)],
    ("protection", "protect", False, "disable"), ("archive", "archive", False, "disable"),
])
def test_every_valid_ccapi_lexeme(method, action, requested, wire_value):
    session, transport = session_for([{action: wire_value}])
    getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
    assert_requests(transport, action, wire_value, 1)


@pytest.mark.parametrize("method,action,field,requested,wire_value,feature", ACTIONS)
@pytest.mark.parametrize("literal", [
    "null", "true", "false", "0", "1", "90", "0.0", "1.0", "90.0", "1e0", "9e1", "90.5",
    '" 1"', '"1 "', '"01"', '"+1"', '"1.0"', '"1e0"', '"１"', '"١"',
    '" 90"', '"90 "', '"090"', '"+90"', '"90.0"', '"9e1"', '"９０"',
    '" enable"', '"enable "', '"ENABLE"', '"disable "', '"off "', '"Off"',
    '"true"', '"false"', '""', "[]", "[1]", "{}", '{"value":"1"}', "9223372036854775808",
])
def test_malformed_ccapi_evidence_never_confirms_or_replays(
    method, action, field, requested, wire_value, feature, literal,
):
    response = json.loads(f'{{"{action}":{literal}}}')
    session, transport = session_for([response])
    # Matching stale metadata must not stand in for this observation.
    session._media_cache[MEDIA_ID] = MediaItem(
        id=MEDIA_ID, name="SYNTHETIC.JPG", kind="image", **{field: requested},
    )
    with pytest.raises(BridgeError) as raised:
        getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
    assert raised.value.code == "CCAPI_MEDIA_METADATA_NOT_APPLIED"
    assert feature not in session._observed
    assert getattr(session._media_cache[MEDIA_ID], field) is None
    assert_requests(transport, action, wire_value, 3)
    assert session._sleep.call_count == 2


@pytest.mark.parametrize("method,action,field,requested,wire_value,feature", ACTIONS)
def test_omitted_ccapi_field_cannot_use_other_fields_or_cached_value(
    method, action, field, requested, wire_value, feature,
):
    session, transport = session_for([{"id": MEDIA_ID, "unrelated": wire_value}])
    session._media_cache[MEDIA_ID] = MediaItem(
        id=MEDIA_ID, name="SYNTHETIC.JPG", kind="image", **{field: requested},
    )
    with pytest.raises(BridgeError, match="did not report the requested value"):
        getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
    assert_requests(transport, action, wire_value, 3)


@pytest.mark.parametrize("method,action,field,requested,wire_value,feature", ACTIONS)
@pytest.mark.parametrize("identity", [OTHER_ID, "", f" {MEDIA_ID}", None, True, 1, [], {}])
def test_explicit_conflicting_or_malformed_identity_stops_without_replay(
    method, action, field, requested, wire_value, feature, identity,
):
    session, transport = session_for([{"id": identity, action: wire_value, "name": "SYNTHETIC.JPG"}])
    with pytest.raises(BridgeError) as raised:
        getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
    assert raised.value.code == "INVALID_CCAPI_MEDIA_INFO"
    assert feature not in session._observed and MEDIA_ID not in session._media_cache
    assert_requests(transport, action, wire_value, 1)


@pytest.mark.parametrize("method,action,field,requested,wire_value,feature", ACTIONS)
def test_valid_third_read_confirms_but_fourth_is_never_read(method, action, field, requested, wire_value, feature):
    mismatch = "disable" if wire_value == "enable" else "off" if action == "rating" else "0"
    for matching_index in (2, 3):
        responses = [{action: mismatch}] * matching_index + [{action: wire_value}]
        session, transport = session_for(responses)
        if matching_index == 2:
            result = getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
            assert getattr(result, field) == requested and feature in session._observed
        else:
            with pytest.raises(BridgeError):
                getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
            assert feature not in session._observed
        assert_requests(transport, action, wire_value, 3)


@pytest.mark.parametrize("failure", [
    BridgeError("CCAPI_UNREACHABLE", "Synthetic offline response"),
    CcapiResponse(503, {}, b"synthetic busy"),
    CcapiResponse(200, {}, b"not-json"), [], None,
])
def test_readback_failure_never_replays_write(failure):
    session, transport = session_for([failure])
    with pytest.raises(BridgeError):
        session.set_media_rating(MEDIA_ID, 1)
    assert_requests(transport, "rating", "1", 1)


@pytest.mark.parametrize("body", [
    '{"rating":"off","rating":"1"}',
    '{"rating":true,"rating":"1"}',
    '{"rating":"1","rating":"1"}',
    f'{{"id":"other","id":"{MEDIA_ID}","rating":"1"}}',
    f'{{"id":null,"id":"{MEDIA_ID}","rating":"1"}}',
])
def test_duplicate_metadata_keys_never_hide_contradictory_evidence(body):
    session, transport = session_for([CcapiResponse(200, {}, body.encode())])
    with pytest.raises(BridgeError) as raised:
        session.set_media_rating(MEDIA_ID, 1)
    assert raised.value.code == "INVALID_CCAPI_MEDIA_INFO"
    assert CameraFeature.MEDIA_RATING not in session._observed
    assert_requests(transport, "rating", "1", 1)


@pytest.mark.parametrize("failure", [
    BridgeError("CCAPI_UNREACHABLE", "Synthetic offline response"),
    CcapiResponse(503, {}, b"synthetic busy"),
    CcapiResponse(202, {}, b"{}"),
])
def test_command_failure_never_replays_or_reads(failure):
    session, transport = session_for([{"rating": "1"}], put_response=failure)
    with pytest.raises(BridgeError):
        session.set_media_rating(MEDIA_ID, 1)
    assert_requests(transport, "rating", "1", 0)


@pytest.mark.parametrize("method,action,field,requested,wire_value,feature", ACTIONS)
@pytest.mark.parametrize("value", [None, "1", "90", "true", "false", "enable", "", 1.0, 90.5, {}, []])
def test_direct_setters_reject_invalid_requests_before_io(method, action, field, requested, wire_value, feature, value):
    session, transport = session_for([{action: wire_value}])
    with pytest.raises(BridgeError) as raised:
        getattr(session, f"set_media_{method}")(MEDIA_ID, value)
    assert raised.value.status_code == 422 and transport.requests == []


@pytest.mark.parametrize("method,value", [("rating", True), ("rating", False), ("rotation", False),
                                          ("protection", 1), ("protection", 0), ("archive", 1), ("archive", 0)])
def test_direct_setters_do_not_confuse_boolean_and_integer(method, value):
    session, transport = session_for([{}])
    with pytest.raises(BridgeError):
        getattr(session, f"set_media_{method}")(MEDIA_ID, value)
    assert transport.requests == []


@pytest.mark.parametrize("media_id", [
    MEDIA_ID + "=", _media_id(PHOTO + "?kind=info"), _media_id(PHOTO + "#fragment"),
    _media_id(ROOT), _media_id("/ccapi/ver100/shooting/control/shutterbutton"),
    _media_id(PHOTO.replace("SYNTHETIC", "%53YNTHETIC")),
])
def test_noncanonical_or_nonmedia_target_is_rejected_before_write(media_id):
    session, transport = session_for([{"rating": "1"}])
    with pytest.raises(BridgeError):
        session.set_media_rating(media_id, 1)
    assert transport.requests == []


def test_same_name_different_returned_item_is_not_confirmation(monkeypatch):
    session, transport = session_for([{}])
    other = MediaItem(id=OTHER_ID, name="SYNTHETIC.JPG", kind="image", rating=1)
    readback = Mock(return_value=other)
    monkeypatch.setattr(session, "_media_info", readback)
    with pytest.raises(BridgeError):
        session.set_media_rating(MEDIA_ID, 1)
    assert readback.call_count == 3
    assert_requests(transport, "rating", "1", 0)


@pytest.mark.parametrize("field,value,method,requested", [
    ("rating", True, "rating", 1), ("rating", 1.0, "rating", 1),
    ("rotation_degrees", False, "rotation", 0), ("rotation_degrees", 90.0, "rotation", 90),
])
def test_bypassed_model_validation_still_cannot_confirm_coerced_number(monkeypatch, field, value, method, requested):
    session, transport = session_for([{}])
    item = MediaItem(id=MEDIA_ID, name="SYNTHETIC.JPG", kind="image").model_copy(update={field: value})
    readback = Mock(return_value=item)
    monkeypatch.setattr(session, "_media_info", readback)
    with pytest.raises(BridgeError):
        getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
    assert readback.call_count == 3 and len(transport.requests) == 1


@pytest.mark.parametrize("field", ["protectionWritable", "ratingWritable", "rotationWritable", "archiveWritable"])
def test_optional_writable_veto_survives_normalized_model_and_ccapi_info(field):
    item = MediaItem.model_validate({"id": MEDIA_ID, "name": "SYNTHETIC.JPG", "kind": "image", field: False})
    assert item.model_dump(by_alias=True)[field] is False
    session, transport = session_for([{field: False}])
    observed = session.media_info(MEDIA_ID)
    assert observed.model_dump(by_alias=True)[field] is False
    assert transport.requests == [("GET", f"{PHOTO}?kind=info", None)]
    for invalid in [0, "false", "disable"]:
        with pytest.raises(ValidationError):
            MediaItem.model_validate({"id": MEDIA_ID, "name": "SYNTHETIC.JPG", "kind": "image", field: invalid})


def nonstandard_constant_body(action, wire_value, constant, location):
    known = f'"{action}":{json.dumps(wire_value)}'
    if location == "requested-field":
        return f'{{"{action}":{constant}}}'.encode()
    if location == "unrelated-field":
        return f'{{{known},"unrelated":{constant}}}'.encode()
    if location == "nested-array":
        return f'{{{known},"unrelated":[{{"nested":[{constant}]}}]}}'.encode()
    return f'{{{known},"unrelated":{{"nested":{constant}}}}}'.encode()


@pytest.mark.parametrize("method,action,field,requested,wire_value,feature", ACTIONS)
@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
@pytest.mark.parametrize("location", ["requested-field", "unrelated-field", "nested-array", "nested-object"])
def test_nonstandard_json_anywhere_cannot_confirm_or_replay_mutation(
    method, action, field, requested, wire_value, feature, constant, location,
):
    body = nonstandard_constant_body(action, wire_value, constant, location)
    session, transport = session_for([CcapiResponse(200, {"content-type": "application/json"}, body)])
    with pytest.raises(BridgeError) as raised:
        getattr(session, f"set_media_{method}")(MEDIA_ID, requested)
    assert raised.value.code == "INVALID_CCAPI_MEDIA_INFO"
    assert feature not in session._observed
    assert_requests(transport, action, wire_value, 1)


class FreshMutationTransport(MutationTransport):
    def __init__(self, responses):
        super().__init__(responses)
        self.streams = []

    def open_stream(self, method, url, **kwargs):
        assert kwargs.get("follow_redirects") is False
        response = self.request(method, url, **kwargs)
        stream = BytesIO(response.body)
        self.streams.append(stream)
        return CcapiStreamResponse(response.status, response.headers, stream)


@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
@pytest.mark.parametrize("location", ["requested-field", "unrelated-field", "nested-array", "nested-object"])
def test_fresh_media_read_rejects_nonstandard_constants_and_invalidates_cached_evidence(constant, location):
    body = nonstandard_constant_body("rating", "3", constant, location)
    transport = FreshMutationTransport([CcapiResponse(200, {"content-type": "application/json"}, body)])
    session = CcapiSession("http://192.0.2.1:8080", transport)
    session._initialized = True
    session._operations = {CcapiOperation("GET", ROOT)}
    session._media_cache[MEDIA_ID] = MediaItem(
        id=MEDIA_ID, name="SYNTHETIC.JPG", kind="image", size_bytes=3, content_type="image/jpeg",
    )
    with pytest.raises(BridgeError) as raised:
        session.media_info(MEDIA_ID, fresh=True)
    assert raised.value.code == "CCAPI_INVENTORY_INCOMPLETE"
    assert transport.requests == [("GET", f"{PHOTO}?kind=info", None)]
    assert len(transport.streams) == 1 and transport.streams[0].closed
    assert session._media_cache[MEDIA_ID].size_bytes == 0
    assert session._media_cache[MEDIA_ID].content_type == "application/octet-stream"


@pytest.mark.parametrize("quoted", ["NaN", "Infinity", "-Infinity"])
def test_valid_json_strings_resembling_constants_do_not_reject_matching_readback(quoted):
    body = json.dumps({"rating": "3", "unrelated": {"nested": [quoted]}}).encode()
    session, transport = session_for([CcapiResponse(200, {}, body)])
    assert session.set_media_rating(MEDIA_ID, 3).rating == 3
    assert_requests(transport, "rating", "3", 1)
