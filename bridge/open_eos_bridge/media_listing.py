"""Request-scoped browsing streams; never complete-inventory evidence."""

from __future__ import annotations

import asyncio
import json
import queue
import re
import secrets
import threading
from collections.abc import Callable, Iterator

from fastapi import Request
from fastapi.responses import StreamingResponse
from starlette.types import Receive, Scope, Send

from .errors import BridgeError
from .models import MediaItem

MEDIA_LIST_CONTENT_TYPE = "application/vnd.open-eos.media-list.v1+ndjson"
MAX_LIST_FRAME_BYTES = 65_536
MAX_LIST_CHUNK_ITEMS = 128
MAX_LIST_SNAPSHOT_ITEMS = 100_000
MAX_LIST_SNAPSHOT_BYTES = 64 * 1024 * 1024
MAX_LIST_QUEUED_FRAMES = 4


def accepts_media_stream(accept: str) -> bool:
    for candidate in accept.split(","):
        parts = [part.strip().lower() for part in candidate.split(";")]
        if parts[0] != MEDIA_LIST_CONTENT_TYPE:
            continue
        quality = next((part[2:] for part in parts[1:] if part.startswith("q=")), "1")
        try:
            if 0 < float(quality) <= 1:
                return True
        except ValueError:
            pass
    return False


class _ListingCancelled(Exception):
    pass


class _ListingResponse(StreamingResponse):
    """One disconnect receiver even on ASGI versions that only check failed sends."""

    def __init__(self, listing: _Listing) -> None:
        self.listing = listing
        super().__init__(
            listing.body(), media_type=MEDIA_LIST_CONTENT_TYPE,
            headers={"Cache-Control": "private, no-store, max-age=0", "X-Content-Type-Options": "nosniff"},
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        stream = asyncio.create_task(self.stream_response(send))
        disconnect = asyncio.create_task(self.listen_for_disconnect(receive))
        try:
            done, _ = await asyncio.wait({stream, disconnect}, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
        finally:
            self.listing.cancel()
            stream.cancel()
            disconnect.cancel()
            await asyncio.gather(stream, disconnect, return_exceptions=True)


class MediaListingStreams:
    """Keep each reservation until its actual worker exits, including after disconnect."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active: dict[int, _Listing] = {}
        self._closed = False

    def response(
        self,
        request: Request,
        session_id: str,
        session: object,
        limit: int | None,
        provider: Callable,
        is_current: Callable[[], bool],
    ) -> StreamingResponse:
        listing = _Listing(self, session_id, session, limit, provider, is_current)
        with self._lock:
            if self._closed:
                raise BridgeError("SESSION_CLOSED", "The bridge is shutting down.", status_code=410)
            if id(session) in self._active:
                raise BridgeError(
                    "MEDIA_LIST_BUSY", "A media listing is still stopping or running. Retry manually.", status_code=409
                )
            self._active[id(session)] = listing
        return _ListingResponse(listing)

    def _release(self, listing: _Listing) -> None:
        with self._lock:
            if self._active.get(id(listing.session)) is listing:
                del self._active[id(listing.session)]

    def close(self) -> None:
        with self._lock:
            self._closed = True
            for listing in self._active.values():
                listing.stopped.set()


class _Listing:
    def __init__(self, owner, session_id, session, limit, provider, is_current) -> None:
        self.owner = owner
        self.session = session
        self.limit = limit
        self.provider = provider
        self.is_current = is_current
        self.stopped = threading.Event()
        self.finished = threading.Event()
        self.frames: queue.Queue[bytes] = queue.Queue(MAX_LIST_QUEUED_FRAMES)
        self.binding = {
            "requestId": secrets.token_urlsafe(18),
            "sessionId": session_id,
            "scope": "full" if limit is None else "recent",
        }
        self.sequence = 0
        self.revision = 0
        self.last_items: list[MediaItem] | None = None
        self.started = False

    def cancelled(self) -> bool:
        return self.stopped.is_set() or not self.is_current()

    def check(self) -> None:
        if self.cancelled():
            raise _ListingCancelled()

    def cancel(self) -> None:
        self.stopped.set()
        if not self.started:
            self.owner._release(self)

    def encode(self, fields: dict) -> bytes:
        frame = json.dumps(
            {**self.binding, "seq": self.sequence, **fields}, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8") + b"\n"
        if len(frame) > MAX_LIST_FRAME_BYTES:
            raise BridgeError("MEDIA_LIST_RESOURCE_LIMIT", "Media listing frame exceeds the resource bound.")
        return frame

    def put(self, frame: bytes) -> None:
        while True:
            self.check()
            try:
                self.frames.put(frame, timeout=0.05)
                self.sequence += 1
                return
            except queue.Full:
                continue

    def emit(self, **fields) -> None:
        self.put(self.encode(fields))

    def snapshot(self, items: list[MediaItem]) -> None:
        self.check()
        if self.last_items == items:
            return
        if len(items) > MAX_LIST_SNAPSHOT_ITEMS:
            raise BridgeError("MEDIA_LIST_RESOURCE_LIMIT", "Media listing exceeds the item resource bound.")
        self.revision += 1
        total_bytes = 0
        for frame in self.snapshot_frames(items):
            total_bytes += len(frame)
            if total_bytes > MAX_LIST_SNAPSHOT_BYTES:
                raise BridgeError("MEDIA_LIST_RESOURCE_LIMIT", "Media listing exceeds the snapshot resource bound.")
            self.put(frame)
        self.last_items = list(items)

    def snapshot_frames(self, items: list[MediaItem]) -> Iterator[bytes]:
        offset = 0
        while offset < len(items) or offset == 0:
            self.check()
            count = min(MAX_LIST_CHUNK_ITEMS, len(items) - offset)
            while True:
                fields = {
                    "type": "snapshot", "revision": self.revision, "offset": offset,
                    "total": len(items), "last": offset + count == len(items),
                    "items": [item.model_dump(mode="json", by_alias=True) for item in items[offset:offset + count]],
                }
                try:
                    frame = self.encode(fields)
                    break
                except BridgeError:
                    if count <= 1:
                        raise
                    count //= 2
            yield frame
            offset += count
            if offset == len(items):
                break

    def run(self) -> None:
        try:
            self.emit(type="start", limit=self.limit)
            items = self.provider(self.limit, on_progress=self.snapshot, cancelled=self.cancelled)
            self.snapshot(items)
            self.check()
            self.emit(type="complete", revision=self.revision, count=len(items), scopeComplete=True)
        except _ListingCancelled:
            pass
        except Exception as error:
            if not self.cancelled():
                code = error.code if isinstance(error, BridgeError) else "MEDIA_LIST_FAILED"
                if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,79}", code):
                    code = "MEDIA_LIST_FAILED"
                try:
                    # Camera response prose and URLs may contain private information.
                    self.emit(type="error", code=code, message="Media listing did not finish. Retry manually.")
                except _ListingCancelled:
                    pass
        finally:
            self.owner._release(self)
            self.finished.set()

    async def body(self):
        try:
            self.started = True
            try:
                threading.Thread(target=self.run, name="media-listing", daemon=True).start()
            except BaseException:
                self.owner._release(self)
                self.finished.set()
                raise
            while True:
                if self.cancelled():
                    return
                try:
                    frame = self.frames.get_nowait()
                except queue.Empty:
                    if self.finished.is_set() and self.frames.empty():
                        return
                    await asyncio.sleep(0.01)
                    continue
                yield frame
        finally:
            self.cancel()
