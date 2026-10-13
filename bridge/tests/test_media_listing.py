from __future__ import annotations

import asyncio
import json
import socket
import threading
import time
import unittest
from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import patch

import httpx
import uvicorn
from fastapi.testclient import TestClient
from starlette.requests import Request

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.media_listing import (
    MAX_LIST_FRAME_BYTES,
    MAX_LIST_QUEUED_FRAMES,
    MEDIA_LIST_CONTENT_TYPE,
    MediaListingStreams,
    accepts_media_stream,
)
from open_eos_bridge.models import MediaItem

from .test_ccapi_strict_media import PHOTO, ROOT, SECOND, session_for, unpaged

ACCEPT = {"Accept": MEDIA_LIST_CONTENT_TYPE}


def progressive(session, limit=None, on_progress=None, cancelled=None):
    return session.list_media_progressive(
        limit, on_progress=on_progress or (lambda items: None), cancelled=cancelled or (lambda: False)
    )


def wait_until(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return False


def app_for(session, token=None):
    app = create_app(token=token)
    app.state.session_manager._sessions["fixture"] = session
    return app


@contextmanager
def serving(app):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    sock.listen(16)
    server = uvicorn.Server(uvicorn.Config(app, log_level="error", lifespan="off"))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [sock]}, daemon=True)
    thread.start()
    try:
        if not wait_until(lambda: server.started):
            raise AssertionError("Local HTTP fixture did not start")
        yield f"http://127.0.0.1:{sock.getsockname()[1]}"
    finally:
        app.state.media_listings.close()
        server.should_exit = True
        thread.join(timeout=4)
        sock.close()
        if thread.is_alive():
            raise AssertionError("Local HTTP fixture did not stop")


class ProgressiveTraversalTests(unittest.TestCase):
    def test_progress_before_next_page_and_cancellation_stops_it(self):
        session, transport = session_for({
            f"{ROOT}?kind=number": {"pagenumber": 2},
            f"{ROOT}?page=1&order=desc": {"path": [PHOTO]},
            f"{ROOT}?page=2&order=desc": {"path": [SECOND]},
        })
        cancelled = threading.Event()
        snapshots = []

        def on_progress(items):
            snapshots.append(items)
            self.assertNotIn(f"{ROOT}?page=2&order=desc", transport.requests)
            cancelled.set()

        with self.assertRaisesRegex(BridgeError, "cancelled"):
            progressive(session, on_progress=on_progress, cancelled=cancelled.is_set)
        self.assertEqual(len(snapshots), 1)
        self.assertEqual(len(transport.requests), 2)
        self.assertEqual(session._media_cache, {})

    def test_lock_wait_cancellation_exits_without_camera_request(self):
        session, transport = session_for(unpaged([PHOTO]))
        cancelled, done = threading.Event(), threading.Event()
        failures = []

        def run():
            try:
                progressive(session, cancelled=cancelled.is_set)
            except BridgeError as error:
                failures.append(error.code)
            finally:
                done.set()

        with session._lock:
            thread = threading.Thread(target=run)
            thread.start()
            cancelled.set()
            self.assertTrue(done.wait(1))
        thread.join(1)
        self.assertEqual(failures, ["MEDIA_LIST_CANCELLED"])
        self.assertEqual(transport.requests, [])

    def test_cancel_after_rejected_query_stops_fallback(self):
        for count_query in (True, False):
            with self.subTest(count_query=count_query):
                session, transport = session_for({f"{ROOT}?kind=number": {"pagenumber": 1}})
                cancelled = threading.Event()
                original = transport.request
                calls = []

                def request(method, url, **kwargs):
                    calls.append(url)
                    if ("kind=number" in url) == count_query:
                        cancelled.set()
                        return CcapiResponse(400, {}, b"unsupported")
                    return original(method, url, **kwargs)

                transport.request = request
                with self.assertRaisesRegex(BridgeError, "cancelled"):
                    progressive(session, cancelled=cancelled.is_set)
                self.assertEqual(len(calls), 1 if count_query else 2)
                self.assertFalse(any("type=all" in url for url in calls))
                self.assertFalse(any(url.endswith("?page=1") for url in calls))

    def test_legacy_and_progressive_share_sibling_order_and_requests(self):
        first, second = f"{ROOT}/card1", f"{ROOT}/card2"
        routes = unpaged([first, second, first, ROOT])
        routes.update({
            f"{first}?kind=number": {"pagenumber": 2},
            f"{first}?page=1&order=desc": CcapiResponse(400, {}, b"unsupported"),
            f"{first}?page=1": {"path": [f"{first}/1.JPG", f"{first}/2.JPG"]},
            f"{first}?page=2": {"path": [f"{first}/3.JPG", f"{first}/4.JPG"]},
            f"{second}?kind=number": {"pagenumber": 1},
            f"{second}?page=1": {"path": [f"{second}/1.MP4", f"{second}/2.MP4"]},
        })
        legacy, old_transport = session_for(routes)
        session, transport = session_for(routes)
        snapshots = []
        result = progressive(session, on_progress=snapshots.append)
        self.assertEqual(result, legacy.list_media())
        self.assertEqual(transport.requests, old_transport.requests)
        self.assertEqual([item.name for item in result], ["4.JPG", "2.MP4", "3.JPG", "1.MP4", "2.JPG", "1.JPG"])
        self.assertTrue(any(0 < len(items) < len(result) for items in snapshots))
        self.assertEqual(transport.requests.count(f"{first}?page=1"), 1)
        self.assertNotIn(f"{second}?page=1&order=desc", transport.requests)

    def test_full_card_exceeds_1000_items_and_100_pages_without_fanout(self):
        routes = {f"{ROOT}?kind=number": {"pagenumber": 101}}
        for page in range(1, 102):
            routes[f"{ROOT}?page={page}&order=desc"] = {
                "path": [f"{ROOT}/IMG_{page:04d}_{index}.JPG" for index in range(11)]
            }
        session, transport = session_for(routes)
        snapshots = []
        result = progressive(session, on_progress=lambda items: snapshots.append(len(items)))
        self.assertEqual(len(result), 1111)
        self.assertEqual(snapshots, [11 * page for page in range(1, 102)])
        self.assertEqual(len(transport.requests), 102)
        self.assertTrue(all("kind=number" in path or "page=" in path for path in transport.requests))

    def test_recent_61_samples_siblings_without_more_pages(self):
        first, second = f"{ROOT}/card1", f"{ROOT}/card2"
        routes = unpaged([first, second])
        for container, extension in ((first, "JPG"), (second, "MP4")):
            routes.update({
                f"{container}?kind=number": {"pagenumber": 2},
                f"{container}?page=1&order=desc": {
                    "path": [f"{container}/{index:03d}.{extension}" for index in range(61)]
                },
            })
        session, transport = session_for(routes)
        result = progressive(session, 61)
        self.assertEqual(len(result), 61)
        self.assertEqual([item.kind for item in result[:2]], ["image", "video"])
        self.assertFalse(any("page=2" in path for path in transport.requests))
        self.assertEqual(len(transport.requests), 6)

    def test_empty_card_has_real_empty_snapshot(self):
        session, transport = session_for(unpaged([]))
        snapshots = []
        self.assertEqual(progressive(session, on_progress=snapshots.append), [])
        self.assertEqual(snapshots, [[]])
        self.assertEqual(len(transport.requests), 2)

    def test_malformed_pages_and_counts_fail_without_committing_cache(self):
        for value in ({}, {"path": None}, {"path": [None]}, {"path": [""]}, {"path": [42]}, None):
            with self.subTest(value=value):
                session, transport = session_for({
                    f"{ROOT}?kind=number": {"pagenumber": 2},
                    f"{ROOT}?page=1&order=desc": {"path": [PHOTO]},
                    f"{ROOT}?page=2&order=desc": value,
                })
                partial = []
                with self.assertRaises(BridgeError):
                    progressive(session, on_progress=partial.append)
                self.assertEqual(len(partial), 1)
                self.assertEqual(session._media_cache, {})
                self.assertEqual(len(transport.requests), 3)
        for value in (None, {}, [], {"pagenumber": True}, {"pagenumber": "2"}, {"pagenumber": -1}):
            session, transport = session_for({f"{ROOT}?kind=number": value, ROOT: {"path": []}})
            with self.assertRaises(BridgeError):
                progressive(session)
            self.assertEqual(len(transport.requests), 1)

    def test_auth_and_busy_do_not_fall_back(self):
        for status in (401, 403, 503):
            session, transport = session_for({f"{ROOT}?kind=number": CcapiResponse(status, {}, b"failure")})
            with self.assertRaises(BridgeError):
                progressive(session)
            self.assertEqual(len(transport.requests), 1)

    def test_resource_limits_fail_instead_of_truncated_success(self):
        for constant, bound, routes in (
            ("MAX_MEDIA_LIST_REQUESTS", 1, unpaged([PHOTO])),
            ("MAX_MEDIA_LIST_CONTAINERS", 1, unpaged([f"{ROOT}/card1"])),
            ("MAX_LIST_SNAPSHOT_ITEMS", 1, unpaged([PHOTO, SECOND])),
        ):
            with self.subTest(constant=constant):
                session, _ = session_for(routes)
                with patch(f"open_eos_bridge.ccapi.{constant}", bound), self.assertRaises(BridgeError):
                    progressive(session)
                self.assertEqual(session._media_cache, {})


class StreamRouteTests(unittest.TestCase):
    def test_same_get_negotiates_json_stream_and_strict_without_extra_reads(self):
        session, transport = session_for(unpaged([PHOTO]))
        app = app_for(session)
        with TestClient(app) as client:
            legacy = client.get("/v1/session/fixture/media")
            self.assertEqual(len(transport.requests), 2)
            streamed = client.get("/v1/session/fixture/media", headers=ACCEPT)
            self.assertEqual(len(transport.requests), 4)
            strict = client.get("/v1/session/fixture/media?inventory=strict", headers=ACCEPT)
            self.assertEqual(len(transport.requests), 6)
        self.assertEqual(legacy.headers["content-type"], "application/json")
        self.assertEqual(streamed.headers["content-type"], MEDIA_LIST_CONTENT_TYPE)
        self.assertEqual(strict.headers["content-type"], "application/json")
        self.assertEqual(strict.headers["x-open-eos-inventory"], "complete-v1")
        self.assertNotIn("x-open-eos-inventory", streamed.headers)
        records = [json.loads(line) for line in streamed.content.splitlines()]
        self.assertEqual([record["type"] for record in records], ["start", "snapshot", "complete"])
        self.assertEqual([record["seq"] for record in records], [0, 1, 2])
        self.assertEqual(records[1]["items"], legacy.json()["items"])
        self.assertTrue(records[2]["scopeComplete"])
        self.assertEqual(records[2]["revision"], 1)
        for record in records:
            self.assertEqual(record["requestId"], records[0]["requestId"])
            self.assertEqual(record["sessionId"], "fixture")
            self.assertEqual(record["scope"], "full")

    def test_unsupported_engine_keeps_one_json_call(self):
        calls = []
        session = SimpleNamespace(engine_name="libgphoto2", list_media=lambda limit: calls.append(limit) or [])
        with TestClient(app_for(session)) as client:
            response = client.get("/v1/session/fixture/media?limit=61", headers=ACCEPT)
            client.app.state.session_manager._sessions.clear()
        self.assertEqual(response.json(), {"items": []})
        self.assertEqual(calls, [61])

    def test_auth_and_loopback_failures_precede_traversal(self):
        session, transport = session_for(unpaged([PHOTO]))
        with TestClient(app_for(session, token="synthetic-token")) as client:
            response = client.get("/v1/session/fixture/media", headers=ACCEPT)
        self.assertEqual(response.status_code, 401)
        self.assertEqual(transport.requests, [])

        async def remote():
            adapter = httpx.ASGITransport(app=app_for(session), client=("198.51.100.1", 1234))
            async with httpx.AsyncClient(transport=adapter, base_url="http://fixture") as client:
                return await client.get("/v1/session/fixture/media", headers=ACCEPT)

        self.assertEqual(asyncio.run(remote()).status_code, 403)
        self.assertEqual(transport.requests, [])

    def test_partial_then_error_has_no_complete_or_private_prose(self):
        session, _ = session_for({
            f"{ROOT}?kind=number": {"pagenumber": 2},
            f"{ROOT}?page=1&order=desc": {"path": [PHOTO]},
            f"{ROOT}?page=2&order=desc": BridgeError("CCAPI_UNREACHABLE", "private-camera-address"),
        })
        with TestClient(app_for(session)) as client:
            response = client.get("/v1/session/fixture/media?limit=61", headers=ACCEPT)
        records = [json.loads(line) for line in response.content.splitlines()]
        self.assertEqual([record["type"] for record in records], ["start", "snapshot", "error"])
        self.assertEqual(records[0]["limit"], 61)
        self.assertEqual(records[0]["scope"], "recent")
        self.assertEqual(records[-1]["code"], "CCAPI_UNREACHABLE")
        self.assertNotIn("private-camera-address", response.text)
        self.assertNotIn("x-open-eos-inventory", response.headers)

    def test_utf8_frame_bytes_chunks_and_assembly_are_bounded(self):
        items = [MediaItem(id=f"opaque-{index}", name="相機" * 100, kind="image") for index in range(300)]
        session = SimpleNamespace(list_media_progressive=lambda limit, **kwargs: items)
        app = app_for(session)
        with TestClient(app) as client:
            response = client.get("/v1/session/fixture/media", headers=ACCEPT)
            app.state.session_manager._sessions.clear()
        lines = response.content.splitlines(keepends=True)
        self.assertTrue(all(len(line) <= MAX_LIST_FRAME_BYTES for line in lines))
        records = [json.loads(line) for line in lines]
        snapshots = [record for record in records if record["type"] == "snapshot"]
        self.assertGreater(len(snapshots), 3)
        self.assertTrue(all(len(record["items"]) <= 128 for record in snapshots))
        self.assertEqual([record["seq"] for record in records], list(range(len(records))))
        offset = 0
        for record in snapshots:
            self.assertEqual(record["offset"], offset)
            self.assertEqual(record["total"], 300)
            offset += len(record["items"])
        self.assertEqual(offset, 300)
        self.assertTrue(snapshots[-1]["last"])
        self.assertEqual(records[-1]["type"], "complete")

    def test_snapshot_overflow_emits_error_without_completion(self):
        items = [MediaItem(id=f"opaque-{index}", name="photo.JPG", kind="image") for index in range(300)]
        session = SimpleNamespace(list_media_progressive=lambda limit, **kwargs: items)
        app = app_for(session)
        with TestClient(app) as client, patch("open_eos_bridge.media_listing.MAX_LIST_SNAPSHOT_BYTES", 50_000):
            response = client.get("/v1/session/fixture/media", headers=ACCEPT)
            app.state.session_manager._sessions.clear()
        records = [json.loads(line) for line in response.content.splitlines()]
        self.assertEqual(records[-1]["type"], "error")
        self.assertEqual(records[-1]["code"], "MEDIA_LIST_RESOURCE_LIMIT")
        self.assertNotIn("complete", [record["type"] for record in records])

    def test_accept_requires_explicit_nonzero_supported_type(self):
        self.assertTrue(accepts_media_stream(f"application/json, {MEDIA_LIST_CONTENT_TYPE};q=0.5"))
        for value in ("*/*", "application/json", f"{MEDIA_LIST_CONTENT_TYPE};q=0", f"{MEDIA_LIST_CONTENT_TYPE};q=bad"):
            self.assertFalse(accepts_media_stream(value))


class StreamingLifecycleTests(unittest.TestCase):
    def test_asgi_old_and_new_use_one_disconnect_receiver_and_cancel_waiter(self):
        async def run(version):
            owner, session = MediaListingStreams(), object()
            disconnected, entered = asyncio.Event(), threading.Event()
            cancelled_seen = threading.Event()
            receive_active = False
            start_frame = []

            def provider(limit, on_progress, cancelled):
                entered.set()
                if not wait_until(cancelled):
                    raise AssertionError("ASGI disconnect did not cancel provider")
                cancelled_seen.set()
                return []

            async def receive():
                nonlocal receive_active
                self.assertFalse(receive_active, "Competing disconnect consumers")
                receive_active = True
                try:
                    await disconnected.wait()
                    return {"type": "http.disconnect"}
                finally:
                    receive_active = False

            async def send(message):
                if message["type"] == "http.response.body" and message.get("body"):
                    start_frame.append(json.loads(message["body"]))
                    disconnected.set()

            scope = {"type": "http", "method": "GET", "path": "/", "asgi": {"spec_version": version}}
            response = owner.response(Request(scope, receive), "fixture", session, None, provider, lambda: True)
            active = owner._active[id(session)]
            await asyncio.wait_for(response(scope, receive, send), timeout=2)
            self.assertTrue(entered.is_set())
            self.assertTrue(await asyncio.to_thread(active.finished.wait, 2))
            self.assertTrue(cancelled_seen.is_set())
            self.assertEqual([frame["type"] for frame in start_frame], ["start"])
            self.assertNotIn(id(session), owner._active)

        for version in ("2.0", "2.4"):
            with self.subTest(version=version):
                asyncio.run(run(version))

    def test_real_http_partial_before_page_two_finishes_disconnect_stops_page_three(self):
        session, transport = session_for({
            f"{ROOT}?kind=number": {"pagenumber": 3},
            f"{ROOT}?page=1&order=desc": {"path": [PHOTO]},
            f"{ROOT}?page=2&order=desc": {"path": [SECOND]},
        })
        page_two_entered, release_page_two = threading.Event(), threading.Event()
        original = transport.request

        def request(method, url, **kwargs):
            if "page=2" in url:
                page_two_entered.set()
                if not release_page_two.wait(4):
                    raise AssertionError("Fixture did not release page two")
            return original(method, url, **kwargs)

        transport.request = request
        app = app_for(session)
        try:
            with serving(app) as base, httpx.Client(timeout=3, trust_env=False) as client:
                with client.stream("GET", f"{base}/v1/session/fixture/media", headers=ACCEPT) as response:
                    records = response.iter_lines()
                    self.assertEqual(json.loads(next(records))["type"], "start")
                    partial = json.loads(next(records))
                    self.assertEqual(partial["type"], "snapshot")
                    self.assertEqual(len(partial["items"]), 1)
                    self.assertTrue(page_two_entered.wait(1))
                    self.assertFalse(release_page_two.is_set())
                    active = app.state.media_listings._active[id(session)]
                self.assertTrue(wait_until(active.stopped.is_set))
                busy = client.get(f"{base}/v1/session/fixture/media", headers=ACCEPT)
                self.assertEqual(busy.status_code, 409)
                self.assertEqual(busy.json()["error"]["code"], "MEDIA_LIST_BUSY")
                release_page_two.set()
                self.assertTrue(active.finished.wait(2))
                self.assertNotIn(id(session), app.state.media_listings._active)
                self.assertFalse(any("page=3" in path for path in transport.requests))
                self.assertEqual(session._media_cache, {})
                self.assertTrue(session._lock.acquire(timeout=0.2))
                session._lock.release()
        finally:
            release_page_two.set()

    def test_slow_reader_has_bounded_queue_and_cancel_releases_actual_worker(self):
        async def run():
            owner, session = MediaListingStreams(), object()
            items = [MediaItem(id=str(index), name="photo.JPG", kind="image") for index in range(1000)]
            after_snapshot = threading.Event()

            def provider(limit, on_progress, cancelled):
                on_progress(items)
                after_snapshot.set()
                return items

            async def receive():
                await asyncio.sleep(100)

            request = Request({"type": "http", "method": "GET", "path": "/"}, receive)
            response = owner.response(request, "fixture", session, None, provider, lambda: True)
            iterator = response.body_iterator
            self.assertEqual(json.loads(await anext(iterator))["type"], "start")
            active = owner._active[id(session)]
            deadline = time.monotonic() + 2
            while active.frames.qsize() < MAX_LIST_QUEUED_FRAMES and time.monotonic() < deadline:
                await asyncio.sleep(0.01)
            self.assertEqual(active.frames.qsize(), MAX_LIST_QUEUED_FRAMES)
            self.assertFalse(after_snapshot.is_set())
            self.assertFalse(active.finished.is_set())
            await iterator.aclose()
            self.assertTrue(await asyncio.to_thread(active.finished.wait, 2))
            self.assertNotIn(id(session), owner._active)
            self.assertFalse(after_snapshot.is_set())

        asyncio.run(run())

    def test_retirement_and_shutdown_cancel_waiting_worker(self):
        for shutdown in (False, True):
            with self.subTest(shutdown=shutdown):
                session, transport = session_for(unpaged([PHOTO]))
                app = app_for(session)
                with session._lock, serving(app) as base, httpx.Client(timeout=3, trust_env=False) as client:
                    with client.stream("GET", f"{base}/v1/session/fixture/media", headers=ACCEPT) as response:
                        self.assertEqual(json.loads(next(response.iter_lines()))["type"], "start")
                        active = app.state.media_listings._active[id(session)]
                        if shutdown:
                            app.state.media_listings.close()
                        else:
                            replacement, _ = session_for(unpaged([SECOND]))
                            app.state.session_manager._sessions["fixture"] = replacement
                        self.assertTrue(active.finished.wait(2))
                        self.assertEqual(transport.requests, [])
                        self.assertEqual(session._media_cache, {})

    def test_thread_start_failure_releases_reservation(self):
        async def run():
            owner, session = MediaListingStreams(), object()
            request = Request({"type": "http", "method": "GET", "path": "/"})
            response = owner.response(request, "fixture", session, None, lambda *args, **kwargs: [], lambda: True)
            active = owner._active[id(session)]
            with patch("open_eos_bridge.media_listing.threading.Thread.start", side_effect=RuntimeError("fixture")):
                with self.assertRaisesRegex(RuntimeError, "fixture"):
                    await anext(response.body_iterator)
            self.assertNotIn(id(session), owner._active)
            self.assertTrue(active.finished.is_set())

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
