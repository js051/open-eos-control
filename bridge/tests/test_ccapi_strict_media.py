from __future__ import annotations

import json
import threading
import time
import unittest
from contextlib import suppress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import (
    CcapiOperation,
    CcapiResponse,
    CcapiSession,
    CcapiStreamResponse,
    UrllibCcapiTransport,
    _media_id,
)
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import MediaItem

ROOT = "/ccapi/ver100/contents"
PHOTO = f"{ROOT}/card1/100CANON/IMG_0001.JPG"
SECOND = f"{ROOT}/card2/100CANON/IMG_0002.JPG"
JPEG = b"\xff\xd8strict-original\xff\xd9"


class InventoryTransport:
    """Independent camera responses; missing routes fail instead of inventing empty pages."""

    def __init__(self, routes: dict[str, object]) -> None:
        self.routes = routes
        self.requests: list[str] = []
        self.timeouts: list[float] = []
        self.streams: list[BytesIO] = []

    def request(self, method: str, url: str, **kwargs: object) -> CcapiResponse:
        assert method == "GET"
        parsed = urlsplit(url)
        path = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        self.requests.append(path)
        self.timeouts.append(float(kwargs.get("timeout", 15)))
        value = self.routes.get(path, CcapiResponse(404, {}, b"missing fixture route"))
        if isinstance(value, Exception):
            raise value
        if isinstance(value, CcapiResponse):
            return value
        return CcapiResponse(200, {"content-type": "application/json"}, json.dumps(value).encode())

    def open_stream(self, method: str, url: str, **kwargs: object) -> CcapiStreamResponse:
        response = self.request(method, url, **kwargs)
        body = BytesIO(response.body)
        self.streams.append(body)
        return CcapiStreamResponse(response.status, response.headers, body)

    def close(self) -> None:
        pass


def session_for(routes: dict[str, object]) -> tuple[CcapiSession, InventoryTransport]:
    transport = InventoryTransport(routes)
    session = CcapiSession("http://192.0.2.1:8080", transport)
    session._initialized = True
    session._operations = {CcapiOperation("GET", ROOT)}
    return session, transport


def unpaged(paths: list[object]) -> dict[str, object]:
    return {f"{ROOT}?kind=number": {"pagenumber": 0}, ROOT: {"path": paths}}


class StrictInventoryTests(unittest.TestCase):
    def test_exhausts_pages_and_sibling_containers_with_cycles(self) -> None:
        first, second = f"{ROOT}/card1", f"{ROOT}/card2"
        routes = unpaged([first, second, first, ROOT])
        routes.update({
            f"{first}?kind=number": {"pagenumber": 2},
            f"{first}?page=1&order=desc": {"path": [PHOTO, ROOT]},
            f"{first}?page=2&order=desc": {"path": [PHOTO]},
            f"{second}?kind=number": {"pagenumber": 0},
            second: {"path": [SECOND, first]},
        })
        session, transport = session_for(routes)
        self.assertEqual({item.id for item in session.list_media_strict(2)}, {_media_id(PHOTO), _media_id(SECOND)})
        self.assertEqual(len(transport.requests), 7)
        self.assertEqual(transport.requests.count(f"{ROOT}?kind=number"), 1)

    def test_malformed_second_page_cannot_publish_partial_inventory_or_cache(self) -> None:
        session, _ = session_for({
            f"{ROOT}?kind=number": {"pagenumber": 2},
            f"{ROOT}?page=1&order=desc": {"path": [PHOTO]},
            f"{ROOT}?page=2&order=desc": {"path": [None]},
        })
        with self.assertRaises(BridgeError):
            session.list_media_strict(1000)
        self.assertEqual(session._media_cache, {})

    def test_inventory_keeps_manual_metadata_but_does_not_return_cached_size_evidence(self) -> None:
        session, transport = session_for(unpaged([PHOTO]))
        stale = MediaItem(id=_media_id(SECOND), name="IMG_0002.JPG", kind="image", size_bytes=128)
        session._media_cache[stale.id] = stale
        manual = MediaItem(id=_media_id(PHOTO), name="IMG_0001.JPG", kind="image", size_bytes=256,
                           rating=3, protected=True, capture_time="2026-10-10T10:00:00Z")
        session._media_cache[manual.id] = manual
        transport.routes[ROOT] = {"path": [PHOTO, None]}
        with self.assertRaises(BridgeError):
            session.list_media_strict(1000)
        self.assertEqual(session._media_cache, {stale.id: stale, manual.id: manual})
        transport.routes[ROOT] = {"path": [PHOTO]}
        items = session.list_media_strict(1000)
        self.assertEqual(session._media_cache, {stale.id: stale, manual.id: manual})
        self.assertEqual(items[0].size_bytes, 0)

    def test_strict_identities_reject_query_fragment_and_encoded_aliases(self) -> None:
        for path in (f"{PHOTO}?kind=display", f"{PHOTO}?", f"{PHOTO}#fragment", f"{PHOTO}#",
                     f"{ROOT}/card1/../IMG.JPG", f"{ROOT}/card1/%2e%2e/IMG.JPG",
                     f"{ROOT}/card1/%252e%252e/IMG.JPG", f"{ROOT}/card1%2fIMG.JPG",
                     f"{ROOT}/%49MG.JPG", f"{ROOT}/IMG%ZZ.JPG", f"{ROOT}/IMG%0a.JPG",
                     f"{ROOT}/IMG%5c.JPG"):
            with self.subTest(path=path):
                session, transport = session_for(unpaged([path]))
                with self.assertRaises(BridgeError):
                    session.list_media_strict(1000)
                self.assertEqual(len(transport.requests), 2)
                self.assertEqual(session._media_cache, {})

    def test_same_origin_absolute_identity_and_relative_identity_deduplicate(self) -> None:
        session, _ = session_for(unpaged([f"http://192.0.2.1:8080{PHOTO}", PHOTO]))
        items = session.list_media_strict(1)
        self.assertEqual([item.id for item in items], [_media_id(PHOTO)])

    def test_missing_mixed_and_invalid_paths_are_errors(self) -> None:
        invalid = [None, [], {}, {"path": None}, {"path": "not-list"}, {"path": [PHOTO, None]},
                   {"path": [""]}, {"path": [" "]}, {"path": [42]},
                   {"path": ["/ccapi/ver100/shooting/control/shutterbutton"]},
                   {"path": ["http://198.51.100.1/ccapi/ver100/contents/a.JPG"]},
                   {"path": [f"{ROOT}/%2e%2e/a.JPG"]}, {"path": [f"{ROOT}/bad\npath.JPG"]}]
        for value in invalid:
            with self.subTest(value=value):
                session, _ = session_for({f"{ROOT}?kind=number": {"pagenumber": 0}, ROOT: value})
                with self.assertRaises(BridgeError):
                    session.list_media_strict(1000)

    def test_invalid_counts_do_not_become_unpaged_success(self) -> None:
        for count in (-1, 1.5, True, False, "2", None, {}, [], 2**31):
            with self.subTest(count=count):
                session, transport = session_for({f"{ROOT}?kind=number": {"pagenumber": count}, ROOT: {"path": []}})
                with self.assertRaises(BridgeError):
                    session.list_media_strict(1000)
                self.assertEqual(transport.requests, [f"{ROOT}?kind=number"])

    def test_missing_count_requires_valid_identity_listing(self) -> None:
        for value in ({}, {"path": [None]}, [], None):
            with self.subTest(value=value):
                session, _ = session_for({f"{ROOT}?kind=number": value, ROOT: {"path": []}})
                with self.assertRaises(BridgeError):
                    session.list_media_strict(1000)
        session, _ = session_for({f"{ROOT}?kind=number": {"path": [PHOTO]}, ROOT: {"path": [PHOTO]}})
        self.assertEqual(len(session.list_media_strict(1000)), 1)

    def test_auth_busy_and_transport_failures_are_not_swallowed(self) -> None:
        for failure in (CcapiResponse(401, {}, b"auth"), CcapiResponse(403, {}, b"denied"),
                        CcapiResponse(503, {}, b"busy"), BridgeError("CCAPI_UNREACHABLE", "offline"),
                        CcapiResponse(200, {}, b"not JSON")):
            with self.subTest(failure=failure):
                session, transport = session_for({f"{ROOT}?kind=number": failure, ROOT: {"path": []}})
                with self.assertRaises(BridgeError):
                    session.list_media_strict(1000)
                self.assertEqual(transport.requests, [f"{ROOT}?kind=number"])

    def test_only_explicitly_unsupported_number_queries_fall_back(self) -> None:
        session, transport = session_for({
            f"{ROOT}?kind=number": CcapiResponse(400, {}, b"unsupported"),
            f"{ROOT}?type=all,kind=number": CcapiResponse(405, {}, b"unsupported"),
            ROOT: {"path": []},
        })
        self.assertEqual(session.list_media_strict(1000), [])
        self.assertEqual(len(transport.requests), 3)

    def test_order_fallback_only_on_400(self) -> None:
        for status in (400, 401, 503):
            with self.subTest(status=status):
                session, transport = session_for({
                    f"{ROOT}?kind=number": {"pagenumber": 1},
                    f"{ROOT}?page=1&order=desc": CcapiResponse(status, {}, b"error"),
                    f"{ROOT}?page=1": {"path": [PHOTO]},
                })
                if status == 400:
                    self.assertEqual(len(session.list_media_strict(1000)), 1)
                    self.assertEqual(len(transport.requests), 3)
                else:
                    with self.assertRaises(BridgeError):
                        session.list_media_strict(1000)
                    self.assertEqual(len(transport.requests), 2)

    def test_exact_limit_needs_exhaustion_and_limit_plus_one_fails(self) -> None:
        session, _ = session_for(unpaged([PHOTO]))
        self.assertEqual(len(session.list_media_strict(1)), 1)
        session, _ = session_for(unpaged([PHOTO, SECOND]))
        with self.assertRaisesRegex(BridgeError, "item budget"):
            session.list_media_strict(1)
        session, transport = session_for({
            f"{ROOT}?kind=number": {"pagenumber": 2},
            f"{ROOT}?page=1&order=desc": {"path": [PHOTO]},
            f"{ROOT}?page=2&order=desc": {"path": [None]},
        })
        with self.assertRaises(BridgeError):
            session.list_media_strict(1)
        self.assertEqual(len(transport.requests), 3)

    def test_request_and_container_budgets_fail_without_partial_results(self) -> None:
        session, transport = session_for(unpaged([PHOTO]))
        with (
            patch("open_eos_bridge.ccapi.MAX_MEDIA_INVENTORY_REQUESTS", 1),
            self.assertRaisesRegex(BridgeError, "request budget"),
        ):
            session.list_media_strict(1000)
        self.assertEqual(len(transport.requests), 1)
        session, _ = session_for(unpaged([f"{ROOT}/card1", f"{ROOT}/card2"]))
        with (
            patch("open_eos_bridge.ccapi.MAX_MEDIA_INVENTORY_CONTAINERS", 2),
            self.assertRaisesRegex(BridgeError, "container budget"),
        ):
            session.list_media_strict(1000)
        self.assertEqual(session._media_cache, {})

    def test_query_variants_and_order_fallback_consume_real_request_budget(self) -> None:
        session, transport = session_for({
            f"{ROOT}?kind=number": CcapiResponse(400, {}, b"unsupported"),
            f"{ROOT}?type=all,kind=number": {"pagenumber": 1},
            f"{ROOT}?page=1&order=desc": CcapiResponse(400, {}, b"unsupported order"),
            f"{ROOT}?page=1": {"path": []},
        })
        with (
            patch("open_eos_bridge.ccapi.MAX_MEDIA_INVENTORY_REQUESTS", 3),
            self.assertRaisesRegex(BridgeError, "request budget"),
        ):
            session.list_media_strict(1000)
        self.assertEqual(len(transport.requests), 3)

    def test_invalid_json_lengths_and_oversized_body_are_not_inventory_evidence(self) -> None:
        for response in (CcapiResponse(200, {"content-length": "1000"}, b'{"path":[]}'),
                         CcapiResponse(200, {"content-length": "bad"}, b'{"path":[]}'),
                         CcapiResponse(200, {}, b" " * 65)):
            with self.subTest(response=response):
                session, transport = session_for({f"{ROOT}?kind=number": response})
                with patch("open_eos_bridge.ccapi.MAX_JSON_BYTES", 64), self.assertRaises(BridgeError):
                    session.list_media_strict(1000)
                self.assertTrue(all(stream.closed for stream in transport.streams))

    def test_partial_and_ambiguous_json_responses_cannot_prove_exhaustion(self) -> None:
        for response in (
            CcapiResponse(206, {}, b'{"path":[]}'),
            CcapiResponse(200, {"content-range": "bytes 0-10/100"}, b'{"path":[]}'),
            CcapiResponse(200, {}, b'{"pagenumber":2,"pagenumber":0}'),
            CcapiResponse(200, {}, b'{"path":[null],"path":[]}'),
        ):
            with self.subTest(response=response):
                session, transport = session_for({f"{ROOT}?kind=number": response, ROOT: {"path": []}})
                with self.assertRaises(BridgeError):
                    session.list_media_strict(1000)
                self.assertEqual(len(transport.requests), 1)

    def test_count_null_after_unsupported_variant_is_malformed(self) -> None:
        session, transport = session_for({f"{ROOT}?kind=number": CcapiResponse(400, {}, b"unsupported"),
                                          f"{ROOT}?type=all,kind=number": None, ROOT: {"path": []}})
        with self.assertRaises(BridgeError):
            session.list_media_strict(1000)
        self.assertEqual(len(transport.requests), 2)

    def test_expired_observation_never_claims_complete(self) -> None:
        session, transport = session_for(unpaged([PHOTO]))
        with (
            patch("open_eos_bridge.ccapi.time.monotonic", side_effect=[0.0, 0.0, 100.0, 100.0]),
            self.assertRaisesRegex(BridgeError, "deadline"),
        ):
            session.list_media_strict(1000)
        self.assertLessEqual(len(transport.requests), 1)
        self.assertEqual(session._media_cache, {})

    def test_legacy_listing_keeps_permissive_path_and_count_behavior(self) -> None:
        session, _ = session_for({f"{ROOT}?kind=number": {"pagenumber": "1"},
                                  f"{ROOT}?page=1&order=desc": {"path": [None, PHOTO]}})
        self.assertEqual([item.id for item in session.list_media(1)], [_media_id(PHOTO)])


class FreshMetadataAndOriginalTests(unittest.TestCase):
    def test_fresh_metadata_lock_wait_is_bounded_and_does_not_issue_camera_reads(self) -> None:
        session, transport = session_for({})
        session._lock = Mock()
        session._lock.acquire.return_value = False
        with (
            patch("open_eos_bridge.ccapi.MAX_MEDIA_INVENTORY_SECONDS", 0.04),
            self.assertRaisesRegex(BridgeError, "deadline"),
        ):
            session.media_info(_media_id(PHOTO), fresh=True)
        session._lock.acquire.assert_called_once_with(timeout=0.04)
        session._lock.release.assert_not_called()
        self.assertEqual(transport.requests, [])

    def test_fresh_and_single_original_reject_query_identity_before_camera_request(self) -> None:
        session, transport = session_for({})
        identity = _media_id(f"{PHOTO}?kind=display")
        with self.assertRaises(BridgeError):
            session.media_info(identity, fresh=True)
        with self.assertRaises(BridgeError):
            session.download_media(identity, single_attempt=True)
        self.assertEqual(transport.requests, [])

    def test_strict_identity_works_without_cache_and_does_not_list_after_cache_changes(self) -> None:
        session, transport = session_for({f"{PHOTO}?kind=info": {"filesize": len(JPEG)},
                                          PHOTO: CcapiResponse(200, {"content-type": "image/jpeg"}, JPEG)})
        media_id = _media_id(PHOTO)
        item = session.media_info(media_id, fresh=True)
        self.assertEqual(item.size_bytes, len(JPEG))
        session._media_cache.clear()  # Manual browsing replaced the cache after the observation.
        with patch.object(session, "list_media", side_effect=AssertionError("implicit legacy inventory")):
            _, chunks = session.download_media(media_id, single_attempt=True)
            self.assertEqual(b"".join(chunks), JPEG)
        self.assertEqual(transport.requests, [f"{PHOTO}?kind=info", PHOTO])

    def test_strict_reads_never_start_implicit_discovery_or_accept_other_resources(self) -> None:
        for initialize in (False, True):
            for path in (PHOTO, "/ccapi/ver100/devicestatus/batterylist"):
                if initialize and path == PHOTO:
                    continue
                with self.subTest(initialize=initialize, path=path):
                    session, transport = session_for({})
                    session._initialized = initialize
                    with self.assertRaises(BridgeError):
                        session.media_info(_media_id(path), fresh=True)
                    with self.assertRaises(BridgeError):
                        session.download_media(_media_id(path), single_attempt=True)
                    self.assertEqual(transport.requests, [])

    def test_fresh_missing_zero_or_invalid_size_clears_cached_positive_and_type(self) -> None:
        for size in (None, 0, -1, True, 1.5, "128 bytes", {}, []):
            with self.subTest(size=size):
                session, transport = session_for({f"{PHOTO}?kind=info": {"filesize": 128}})
                media_id = _media_id(PHOTO)
                self.assertEqual(session.media_info(media_id).size_bytes, 128)
                transport.routes[f"{PHOTO}?kind=info"] = {} if size is None else {"filesize": size}
                item = session.media_info(media_id, fresh=True)
                self.assertEqual(item.size_bytes, 0)
                self.assertEqual(item.content_type, "application/octet-stream")
                self.assertEqual(session._media_cache[media_id].size_bytes, 0)

    def test_failed_fresh_response_also_invalidates_stale_size(self) -> None:
        session, transport = session_for({f"{PHOTO}?kind=info": {"filesize": 128}})
        media_id = _media_id(PHOTO)
        session.media_info(media_id)
        transport.routes[f"{PHOTO}?kind=info"] = CcapiResponse(503, {}, b"busy")
        with self.assertRaises(BridgeError):
            session.media_info(media_id, fresh=True)
        self.assertEqual(session._media_cache[media_id].size_bytes, 0)

    def test_legacy_info_still_preserves_cached_size(self) -> None:
        session, transport = session_for({f"{PHOTO}?kind=info": {"filesize": 128}})
        media_id = _media_id(PHOTO)
        session.media_info(media_id)
        transport.routes[f"{PHOTO}?kind=info"] = {}
        self.assertEqual(session.media_info(media_id).size_bytes, 128)

    def test_single_original_errors_never_request_alternates(self) -> None:
        for response in (CcapiResponse(503, {}, b"busy"), CcapiResponse(401, {}, b"auth"),
                         CcapiResponse(206, {"content-type": "image/jpeg"}, JPEG),
                         CcapiResponse(200, {"content-range": "bytes 0-10/100"}, JPEG),
                         CcapiResponse(200, {"content-type": "text/html"}, b"error page")):
            with self.subTest(response=response):
                session, transport = session_for({PHOTO: response, f"{PHOTO}?kind=main": CcapiResponse(200, {}, JPEG)})
                with self.assertRaises(BridgeError):
                    session.download_media(_media_id(PHOTO), single_attempt=True)
                self.assertEqual(transport.requests, [PHOTO])
                self.assertTrue(all(stream.closed for stream in transport.streams))

    def test_legacy_original_retains_alternate_fallback(self) -> None:
        session, transport = session_for({
            PHOTO: CcapiResponse(404, {}, b"missing"),
            f"{PHOTO}?kind=main": CcapiResponse(200, {"content-type": "image/jpeg"}, JPEG),
        })
        _, chunks = session.download_media(_media_id(PHOTO))
        self.assertEqual(b"".join(chunks), JPEG)
        self.assertEqual(transport.requests, [PHOTO, f"{PHOTO}?kind=main"])


class StrictMediaRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.session, self.transport = session_for({
            **unpaged([PHOTO]), f"{PHOTO}?kind=info": {"filesize": len(JPEG)},
            PHOTO: CcapiResponse(200, {"content-type": "image/jpeg", "content-length": str(len(JPEG))}, JPEG),
        })
        app = create_app()
        self.manager = app.state.session_manager
        self.manager._sessions["fixture"] = self.session
        self.client = TestClient(app)
        self.path = "/v1/session/fixture/media"
        self.item_path = f"{self.path}/{_media_id(PHOTO)}"

    def tearDown(self) -> None:
        self.client.close()

    def test_opt_in_evidence_headers_and_default_compatibility(self) -> None:
        for path, header, value in (
            (f"{self.path}?inventory=strict&limit=1000", "x-open-eos-inventory", "complete-v1"),
            (f"{self.item_path}/info?fresh=true", "x-open-eos-fresh-metadata", "v1"),
            (f"{self.item_path}?attempt=single", "x-open-eos-original-attempt", "single-v1"),
        ):
            with self.subTest(path=path):
                response = self.client.get(path)
                self.assertEqual(response.status_code, 200, response.text)
                self.assertEqual(response.headers.get(header), value)
                legacy = self.client.get(path.split("?", 1)[0])
                self.assertEqual(legacy.status_code, 200, legacy.text)
                self.assertNotIn(header, legacy.headers)

    def test_errors_never_carry_positive_evidence(self) -> None:
        self.transport.routes[ROOT] = {"path": [None]}
        response = self.client.get(f"{self.path}?inventory=strict")
        self.assertEqual(response.status_code, 502, response.text)
        self.assertNotIn("x-open-eos-inventory", response.headers)
        self.transport.routes[PHOTO] = CcapiResponse(503, {}, b"busy")
        response = self.client.get(f"{self.item_path}?attempt=single")
        self.assertEqual(response.status_code, 502, response.text)
        self.assertNotIn("x-open-eos-original-attempt", response.headers)

    def test_unsupported_engine_fails_closed_before_legacy_methods(self) -> None:
        other = Mock(engine_name="libgphoto2")
        other.list_media.return_value = []
        other.media_info.return_value = MediaItem(id="fixture", name="fixture.JPG", kind="image")
        self.manager._sessions["fixture"] = other
        for path in (f"{self.path}?inventory=strict", f"{self.item_path}/info?fresh=true",
                     f"{self.item_path}?attempt=single"):
            with self.subTest(path=path):
                response = self.client.get(path)
                self.assertEqual(response.status_code, 409, response.text)
        other.list_media.assert_not_called()
        other.media_info.assert_not_called()
        other.download_media.assert_not_called()

    def test_single_attempt_head_does_not_claim_original_request(self) -> None:
        response = self.client.head(f"{self.item_path}?attempt=single")
        self.assertEqual(response.status_code, 422)
        self.assertNotIn("x-open-eos-original-attempt", response.headers)
        self.assertEqual(self.transport.requests, [])

    def test_strict_metadata_and_original_reject_encoded_id_aliases_before_io(self) -> None:
        for alias in ("!!!!", "===="):
            for suffix in ("/info?fresh=true", "?attempt=single"):
                with self.subTest(alias=alias, suffix=suffix):
                    response = self.client.get(self.item_path + alias + suffix)
                    self.assertEqual(response.status_code, 502, response.text)
                    self.assertNotIn("x-open-eos-fresh-metadata", response.headers)
                    self.assertNotIn("x-open-eos-original-attempt", response.headers)
                    self.assertEqual(self.transport.requests, [])
                    self.assertEqual(self.session._media_cache, {})

    def test_single_attempt_rejects_range_before_camera_io_but_legacy_keeps_ranges(self) -> None:
        response = self.client.get(self.item_path + "?attempt=single", headers={"Range": "bytes=0-1"})
        self.assertEqual(response.status_code, 422, response.text)
        self.assertNotIn("x-open-eos-original-attempt", response.headers)
        self.assertEqual(self.transport.requests, [])
        legacy = self.client.get(self.item_path, headers={"Range": "bytes=0-1"})
        self.assertEqual(legacy.status_code, 206, legacy.text)
        self.assertEqual(legacy.content, JPEG[:2])

    def test_single_original_rejects_malformed_http_length_before_positive_evidence(self) -> None:
        for declared in (f"{len(JPEG)}junk", f"{len(JPEG)}, {len(JPEG)+1}", "-1", "+19", "\u00a019\u00a0"):
            with self.subTest(declared=declared):
                self.transport.requests.clear()
                self.transport.routes[PHOTO] = CcapiResponse(200, {"content-length": declared}, JPEG)
                response = self.client.get(self.item_path + "?attempt=single")
                self.assertEqual(response.status_code, 502)
                self.assertNotIn("x-open-eos-original-attempt", response.headers)
                self.assertEqual(self.transport.requests, [PHOTO])

    def test_single_original_accepts_identical_combined_lengths_without_legacy_digit_concatenation(self) -> None:
        for separator in (",", ", "):
            with self.subTest(separator=separator):
                self.transport.requests.clear()
                self.transport.routes[PHOTO] = CcapiResponse(
                    200, {"content-length": f"{len(JPEG)}{separator}{len(JPEG)}"}, JPEG,
                )
                response = self.client.get(self.item_path + "?attempt=single")
                self.assertEqual(response.status_code, 200, response.text)
                self.assertEqual(response.content, JPEG)
                self.assertEqual(self.transport.requests, [PHOTO])

    def test_fresh_unknown_size_is_reported_as_unknown_even_with_observation_header(self) -> None:
        self.client.get(f"{self.item_path}/info")
        self.transport.routes[f"{PHOTO}?kind=info"] = {}
        response = self.client.get(f"{self.item_path}/info?fresh=true")
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.headers.get("x-open-eos-fresh-metadata"), "v1")
        self.assertEqual(response.json()["sizeBytes"], 0)

    def test_truncated_original_has_no_success_evidence_and_no_retry(self) -> None:
        self.transport.routes[PHOTO] = CcapiResponse(200, {"content-length": "100", "content-type": "image/jpeg"}, JPEG)
        response = self.client.get(f"{self.item_path}?attempt=single")
        self.assertEqual(response.status_code, 502, response.text)
        self.assertNotIn("x-open-eos-original-attempt", response.headers)
        self.assertEqual(self.transport.requests, [PHOTO])

    def test_inventory_limit_and_unknown_modes_are_rejected(self) -> None:
        for suffix in ("?inventory=strict&limit=1001", "?inventory=strict&limit=0", "?inventory=approximate"):
            self.assertEqual(self.client.get(self.path + suffix).status_code, 422)
        self.assertEqual(self.client.get(self.item_path + "?attempt=retry").status_code, 422)


class StrictUrllibBoundaryTests(unittest.TestCase):
    """Exercise the production transport against a synthetic local HTTP camera."""

    def setUp(self) -> None:
        self.requests: list[str] = []
        self.mode = "redirect"
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args: object) -> None:
                pass

            def do_GET(self) -> None:
                owner.requests.append(self.path)
                if owner.mode == "duplicate-length":
                    body = json.dumps({"path": []}).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Content-Length", str(len(body) + 1))
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                elif owner.mode == "drip":
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", "1000")
                    self.end_headers()
                    with suppress(BrokenPipeError, ConnectionResetError):
                        for _ in range(50):
                            self.wfile.write(b" ")
                            self.wfile.flush()
                            time.sleep(0.01)
                elif self.path == "/redirect-target":
                    self.send_response(200)
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(len(JPEG)))
                    self.end_headers()
                    self.wfile.write(JPEG)
                else:
                    self.send_response(302)
                    self.send_header("Location", "/redirect-target")
                    self.send_header("Content-Length", "0")
                    self.end_headers()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.session = CcapiSession(f"http://127.0.0.1:{self.server.server_port}", UrllibCcapiTransport())
        self.session._initialized = True
        self.session._operations = {CcapiOperation("GET", ROOT)}

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1)
        self.session.transport.close()

    def test_single_original_rejects_redirect_without_second_camera_request(self) -> None:
        with self.assertRaises(BridgeError):
            self.session.download_media(_media_id(PHOTO), single_attempt=True)
        self.assertEqual(self.requests, [PHOTO])

    def test_strict_inventory_rejects_redirect_without_fallback_or_follow(self) -> None:
        with self.assertRaises(BridgeError):
            self.session.list_media_strict(1000)
        self.assertEqual(self.requests, [f"{ROOT}?kind=number"])

    def test_legacy_original_keeps_redirect_behavior(self) -> None:
        _, chunks = self.session.download_media(_media_id(PHOTO))
        self.assertEqual(b"".join(chunks), JPEG)
        self.assertEqual(self.requests, [PHOTO, "/redirect-target"])

    def test_slow_body_is_stopped_by_observation_deadline(self) -> None:
        self.mode = "drip"
        started = time.monotonic()
        with (
            patch("open_eos_bridge.ccapi.MAX_MEDIA_INVENTORY_SECONDS", 0.04),
            self.assertRaisesRegex(BridgeError, "deadline"),
        ):
            self.session.list_media_strict(1000)
        self.assertLess(time.monotonic() - started, 0.3)
        self.assertEqual(self.requests, [f"{ROOT}?kind=number"])
        self.assertEqual(self.session._media_cache, {})

    def test_conflicting_duplicate_lengths_cannot_be_collapsed_into_complete_evidence(self) -> None:
        self.mode = "duplicate-length"
        with self.assertRaises(BridgeError):
            self.session.list_media_strict(1000)
        self.assertEqual(self.requests, [f"{ROOT}?kind=number"])


if __name__ == "__main__":
    unittest.main()
