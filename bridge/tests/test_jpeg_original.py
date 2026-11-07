from __future__ import annotations

import base64
import hashlib
import json
import multiprocessing
from email.message import Message
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

import pytest
from av.error import FFmpegError
from fastapi.testclient import TestClient

from open_eos_bridge import jpeg_original
from open_eos_bridge.app import _stage_media, create_app
from open_eos_bridge.ccapi import CcapiResponse, CcapiStreamResponse, _media_id
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.jpeg_original import (
    InvalidJpegOriginal,
    _decode_native,
    decode_jpeg_file,
    parse_jpeg,
    validate_http_mime,
    validate_http_types,
    validate_jpeg_original,
)
from open_eos_bridge.models import MediaItem

from .test_ccapi_strict_media import PHOTO, session_for

CONTRACT = json.loads((Path(__file__).parents[2] / "contracts/jpeg-original/v1/jpeg-original-vectors.json").read_text())
VECTORS = CONTRACT["byteVectors"]
BODY = base64.b64decode(next(row for row in VECTORS if row["id"] == "rgb-baseline")["base64"])


@pytest.mark.parametrize("vector", CONTRACT["mimeVectors"], ids=lambda row: row["id"])
def test_frozen_http_mime(vector):
    if vector["valid"]:
        validate_http_mime(vector["raw"])
    else:
        with pytest.raises(InvalidJpegOriginal):
            validate_http_mime(vector["raw"])


@pytest.mark.parametrize("vector", CONTRACT["headerVectors"], ids=lambda row: row["id"])
def test_frozen_header_multiplicity(vector):
    if vector["valid"]:
        validate_http_types(vector["values"])
    else:
        with pytest.raises(InvalidJpegOriginal):
            validate_http_types(vector["values"])


@pytest.mark.parametrize("raw", ['image/jpeg; test="\x85"', "image/jpeg;", "image/jpeg; a", "image/jpeg; a=",
                                  'image/jpeg; a="unterminated', "image/jpeg\t", "image/jpeg; a=x,y"])
def test_malformed_parameters_and_control_characters_fail(raw):
    with pytest.raises(InvalidJpegOriginal):
        validate_http_mime(raw)


@pytest.mark.parametrize("vector", VECTORS, ids=lambda row: row["id"])
def test_frozen_structure(vector):
    data = base64.b64decode(vector["base64"])
    if vector["structuralValid"]:
        shape = parse_jpeg(data)
        assert (shape.width, shape.height) == (vector["width"], vector["height"])
    else:
        with pytest.raises(InvalidJpegOriginal):
            parse_jpeg(data)


@pytest.mark.parametrize("vector", [row for row in VECTORS if row["structuralValid"]], ids=lambda row: row["id"])
def test_native_decoder_rejects_premature_entropy_not_just_marker_damage(vector):
    data = base64.b64decode(vector["base64"])
    shape = parse_jpeg(data)
    if vector["decodedValid"]:
        _decode_native(data, shape)
    else:
        with pytest.raises((FFmpegError, InvalidJpegOriginal)):
            _decode_native(data, shape)


def test_runtime_envelope_matches_frozen_contract():
    policy = CONTRACT["policy"]
    assert policy["maxPixels"] == jpeg_original.MAX_PIXELS
    assert policy["maxDimension"] == jpeg_original.MAX_SIDE
    assert policy["maxMarkers"] == jpeg_original.MAX_MARKERS
    assert policy["maxScans"] == jpeg_original.MAX_SCANS
    assert policy["maxEncodedBytes"] == jpeg_original.MAX_ENCODED_BYTES


def test_encoded_size_and_scan_limits_precede_native_decode(monkeypatch):
    monkeypatch.setattr(jpeg_original, "MAX_ENCODED_BYTES", len(BODY) - 1)
    with pytest.raises(InvalidJpegOriginal):
        parse_jpeg(BODY)
    monkeypatch.setattr(jpeg_original, "MAX_ENCODED_BYTES", CONTRACT["policy"]["maxEncodedBytes"])
    monkeypatch.setattr(jpeg_original, "MAX_SCANS", 1)
    progressive = next(row for row in VECTORS if row["id"] == "rgb-progressive")
    with pytest.raises(InvalidJpegOriginal):
        parse_jpeg(base64.b64decode(progressive["base64"]))


def test_spawned_native_validation_preserves_original_bytes(tmp_path):
    path = tmp_path / "original.bin"
    path.write_bytes(BODY)
    assert validate_jpeg_original(path) == parse_jpeg(BODY)
    assert path.read_bytes() == BODY


def test_spawned_decoder_deadline_kills_child_releases_slot_and_never_passes(tmp_path, monkeypatch):
    path = tmp_path / "original.bin"
    path.write_bytes(BODY)
    before = {child.pid for child in multiprocessing.active_children()}
    monkeypatch.setattr(jpeg_original, "DECODE_TIMEOUT_SECONDS", 0.00001)
    with pytest.raises(InvalidJpegOriginal, match="deadline"):
        decode_jpeg_file(path, parse_jpeg(BODY), hashlib.sha256(BODY).hexdigest())
    assert {child.pid for child in multiprocessing.active_children()} == before
    assert jpeg_original._DECODE_SLOT.acquire(blocking=False)
    jpeg_original._DECODE_SLOT.release()
    assert path.read_bytes() == BODY


def test_native_decoder_concurrency_is_bounded(tmp_path):
    jpeg_original._DECODE_SLOT.acquire()
    try:
        with pytest.raises(InvalidJpegOriginal, match="busy"):
            decode_jpeg_file(tmp_path / "not-read", parse_jpeg(BODY), hashlib.sha256(BODY).hexdigest())
    finally:
        jpeg_original._DECODE_SLOT.release()


@pytest.fixture
def route(tmp_path):
    session, transport = session_for({PHOTO: CcapiResponse(200, {"content-type": "image/jpeg"}, BODY)})
    app = create_app()
    app.state.session_manager._sessions["fixture"] = session
    with TestClient(app) as client, patch("open_eos_bridge.app.tempfile.tempdir", str(tmp_path)):
        yield client, session, transport, f"/v1/session/fixture/media/{_media_id(PHOTO)}", tmp_path


def test_proof_follows_closed_spool_actual_decode_one_get_and_cleanup(route):
    client, _, transport, path, tmp = route
    response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 200, response.text
    assert response.content == BODY
    assert response.headers["x-open-eos-original-validation"] == "jpeg-v1"
    assert response.headers["x-open-eos-original-attempt"] == "single-v1"
    assert response.headers["content-type"] == "image/jpeg"
    assert response.headers["content-length"] == str(len(BODY))
    assert transport.requests == [PHOTO]
    assert all(stream.closed for stream in transport.streams)
    assert list(tmp.iterdir()) == []


@pytest.mark.parametrize("vector", [row for row in VECTORS if not row["decodedValid"]], ids=lambda row: row["id"])
def test_bad_original_never_gets_proof_or_body_or_leftover_and_never_retries(route, vector):
    client, _, transport, path, tmp = route
    body = base64.b64decode(vector["base64"])
    transport.routes[PHOTO] = CcapiResponse(200, {"content-type": "image/jpeg", "content-length": str(len(body))}, body)
    response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "MEDIA_ORIGINAL_JPEG_INVALID"
    assert "x-open-eos-original-validation" not in response.headers
    assert "x-open-eos-original-attempt" not in response.headers
    assert transport.requests == [PHOTO]
    assert all(stream.closed for stream in transport.streams)
    assert list(tmp.iterdir()) == []


@pytest.mark.parametrize("mime", [None, "application/octet-stream", " image/jpeg ; charset=binary "])
def test_actual_jpeg_allows_absent_or_generic_http_mime_without_filename_guess_evidence(route, mime):
    client, _, transport, path, _ = route
    transport.routes[PHOTO] = CcapiResponse(200, {} if mime is None else {"content-type": mime}, BODY)
    response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 200, response.text
    assert response.headers["x-open-eos-original-validation"] == "jpeg-v1"
    assert response.content == BODY
    assert transport.requests == [PHOTO]


@pytest.mark.parametrize("mime", ["", " ", "image/png", "text/plain", "image/jpeg, image/jpeg",
                                  "image/jpg", "image/jpeg\x00"])
def test_invalid_raw_http_mime_rejected_before_mimetype_fallback_or_body_read(route, mime):
    client, _, transport, path, tmp = route
    transport.routes[PHOTO] = CcapiResponse(200, {"content-type": mime}, BODY)
    response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 502
    assert "x-open-eos-original-validation" not in response.headers
    assert transport.requests == [PHOTO]
    assert all(stream.closed for stream in transport.streams)
    assert list(tmp.iterdir()) == []


@pytest.mark.parametrize("values", [["image/jpeg", "image/jpeg"], ["image/jpeg", "text/plain", "image/jpeg"]])
def test_wire_header_duplicates_cannot_hide_in_compatibility_dictionary(route, values):
    client, _, transport, path, tmp = route
    body = BytesIO(BODY)
    body.headers = Message()
    for value in values:
        body.headers["Content-Type"] = value
    original = CcapiStreamResponse(200, {"content-type": values[-1]}, body)
    with patch.object(transport, "open_stream", return_value=original) as get:
        response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 502
    assert body.closed
    assert get.call_count == 1
    assert list(tmp.iterdir()) == []


@pytest.mark.parametrize("suffix,method,headers", [
    ("?validation=jpeg-v1", "get", {}), ("?attempt=single&validation=jpeg-v1", "head", {}),
    ("?attempt=single&validation=jpeg-v1", "get", {"Range": "bytes=0-10"}),
    ("?attempt=single&validation=future", "get", {}),
])
def test_invalid_opt_in_does_no_camera_io(route, suffix, method, headers):
    client, _, transport, path, _ = route
    response = getattr(client, method)(path + suffix, headers=headers)
    assert response.status_code == 422
    assert transport.requests == []
    assert "x-open-eos-original-validation" not in response.headers


def test_manual_jpeg_raw_and_old_single_route_remain_unvalidated(route):
    client, _, transport, path, _ = route
    transport.routes[PHOTO] = CcapiResponse(200, {"content-type": "application/octet-stream"}, b"legacy RAW payload")
    for suffix in ["", "?attempt=single"]:
        response = client.get(path + suffix)
        assert response.status_code == 200
        assert response.content == b"legacy RAW payload"
        assert "x-open-eos-original-validation" not in response.headers
    assert transport.requests == [PHOTO, PHOTO]


@pytest.mark.parametrize("declared", [False, True])
def test_encoded_transfer_limit_stops_one_get_closes_response_cleans_spool(route, monkeypatch, declared):
    client, _, transport, path, tmp = route
    headers = {"content-type": "image/jpeg"}
    if declared:
        headers["content-length"] = str(len(BODY))
    transport.routes[PHOTO] = CcapiResponse(200, headers, BODY)
    monkeypatch.setattr("open_eos_bridge.app.MAX_ENCODED_BYTES", len(BODY) - 1)
    monkeypatch.setattr("open_eos_bridge.ccapi.MAX_ENCODED_BYTES", len(BODY) - 1)
    response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 502
    assert transport.requests == [PHOTO]
    assert all(stream.closed for stream in transport.streams)
    assert list(tmp.iterdir()) == []


def test_truncated_transfer_fails_before_decode_with_owned_cleanup(route):
    client, _, transport, path, tmp = route
    headers = {"content-type": "image/jpeg", "content-length": str(len(BODY) + 1)}
    transport.routes[PHOTO] = CcapiResponse(200, headers, BODY)
    with patch("open_eos_bridge.app.validate_jpeg_original") as decoder:
        response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 502
    decoder.assert_not_called()
    assert "x-open-eos-original-validation" not in response.headers
    assert all(stream.closed for stream in transport.streams)
    assert list(tmp.iterdir()) == []


def test_failed_decode_cleanup_does_not_remove_previous_delivery(route):
    client, _, transport, path, tmp = route
    delivered = tmp / "already-delivered.JPG"
    delivered.write_bytes(BODY)
    transport.routes[PHOTO] = CcapiResponse(200, {"content-type": "image/jpeg"}, b"not jpeg")
    response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 502
    assert delivered.read_bytes() == BODY
    assert list(tmp.iterdir()) == [delivered]


def test_staging_cap_closes_iterators_and_preserves_other_files(tmp_path):
    closed = []
    def chunks():
        try:
            yield b"1234"
        finally:
            closed.append(True)
    with patch("open_eos_bridge.app.tempfile.tempdir", str(tmp_path)), pytest.raises(BridgeError):
        _stage_media(MediaItem(id="synthetic", name="x.JPG", kind="image"), chunks(), max_bytes=3)
    assert closed == [True]
    assert list(tmp_path.iterdir()) == []


def test_frozen_entrypoint_dispatches_multiprocessing_before_importing_bridge():
    entry = (Path(__file__).parents[1] / "scripts/standalone_entry.py").read_text()
    assert entry.index("multiprocessing.freeze_support()") < entry.index("from open_eos_bridge.standalone import main")


def test_spawned_native_failure_reaps_child_and_releases_capacity(tmp_path):
    path = tmp_path / "corrupt.bin"
    data = base64.b64decode(next(row for row in VECTORS if row["id"] == "premature-entropy-eoi-10")["base64"])
    path.write_bytes(data)
    before = {child.pid for child in multiprocessing.active_children()}
    with pytest.raises(BridgeError, match="bounded JPEG validation"):
        validate_jpeg_original(path)
    assert {child.pid for child in multiprocessing.active_children()} == before
    assert jpeg_original._DECODE_SLOT.acquire(blocking=False)
    jpeg_original._DECODE_SLOT.release()


def test_process_start_failure_closes_resources_releases_slot_and_fails_closed(tmp_path):
    from unittest.mock import Mock

    context = Mock()
    receiver, sender, process = Mock(), Mock(), Mock(pid=None)
    context.Pipe.return_value = receiver, sender
    context.Process.return_value = process
    process.start.side_effect = OSError("synthetic process start failure")
    path = tmp_path / "original.bin"
    path.write_bytes(BODY)
    with patch.object(multiprocessing, "get_context", return_value=context), pytest.raises(BridgeError):
        validate_jpeg_original(path)
    receiver.close.assert_called_once()
    sender.close.assert_called_once()
    process.close.assert_called_once()
    process.kill.assert_not_called()
    assert jpeg_original._DECODE_SLOT.acquire(blocking=False)
    jpeg_original._DECODE_SLOT.release()


def test_closed_copy_digest_mismatch_cannot_spawn_decoder_or_get_receipt(tmp_path):
    path = tmp_path / "changed.bin"
    path.write_bytes(BODY)
    with patch.object(jpeg_original, "decode_jpeg_file") as decode, pytest.raises(BridgeError):
        validate_jpeg_original(path, expected_sha256="0" * 64)
    decode.assert_not_called()


def test_native_readback_digest_mismatch_rejects_same_dimension_replacement(tmp_path):
    path = tmp_path / "changed.bin"
    path.write_bytes(BODY)
    with pytest.raises(InvalidJpegOriginal, match="native decode failed"):
        decode_jpeg_file(path, parse_jpeg(BODY), "0" * 64)


def test_wire_content_type_duplicate_is_preserved_by_real_urllib_transport():
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    from open_eos_bridge.ccapi import CcapiOperation, CcapiSession, UrllibCcapiTransport

    requests = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(BODY)))
            self.end_headers()
            self.wfile.write(BODY)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        session = CcapiSession(f"http://127.0.0.1:{server.server_port}", UrllibCcapiTransport())
        session._initialized = True
        session._operations = {CcapiOperation("GET", "/ccapi/ver100/contents")}
        with pytest.raises(BridgeError, match="MIME"):
            session.download_media(_media_id(PHOTO), single_attempt=True, jpeg_validation=True)
        assert requests == [PHOTO]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)



def test_native_decode_timeout_route_has_no_proof_and_cleans_closed_spool(route, monkeypatch):
    client, _, transport, path, tmp = route
    before = {child.pid for child in multiprocessing.active_children()}
    monkeypatch.setattr(jpeg_original, "DECODE_TIMEOUT_SECONDS", 0.00001)
    response = client.get(path + "?attempt=single&validation=jpeg-v1")
    assert response.status_code == 502
    assert "x-open-eos-original-validation" not in response.headers
    assert list(tmp.iterdir()) == []
    assert all(stream.closed for stream in transport.streams)
    assert transport.requests == [PHOTO]
    assert {child.pid for child in multiprocessing.active_children()} == before


def test_browser_peer_corrupt_original_has_valid_framing_and_truthful_length_but_fails_decode():
    # Same recipe as the independent browser peer. Real browser execution is a
    # separate gate; this control proves its corruption reaches the entropy gate.
    from io import BytesIO

    from PIL import Image

    output = BytesIO()
    Image.new("RGB", (640, 480), color=(30, 30, 200)).save(output, format="JPEG", quality=90)
    original = output.getvalue()
    scan = original.index(b"\xff\xda") + 2
    entropy = scan + int.from_bytes(original[scan:scan + 2], "big")
    bad = original[:entropy + 10] + b"\xff\xd9"
    shape = parse_jpeg(bad)
    assert (shape.width, shape.height) == (640, 480)
    with pytest.raises((FFmpegError, InvalidJpegOriginal)):
        _decode_native(bad, shape)
