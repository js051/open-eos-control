from __future__ import annotations

import secrets
import threading
import time
from collections.abc import Iterable, Iterator
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path

from .models import MediaItem


@dataclass(frozen=True)
class MediaByteRange:
    start: int
    end: int

    @property
    def length(self) -> int:
        return self.end - self.start + 1


class InvalidMediaRange(ValueError):
    pass


class MediaPlaybackCacheFull(ValueError):
    pass


def parse_media_range(value: str | None, size_bytes: int) -> MediaByteRange | None:
    if value is None:
        return None
    if size_bytes <= 0 or not value.startswith("bytes=") or "," in value:
        raise InvalidMediaRange("A single byte range requires a known positive media size.")
    bounds = value.removeprefix("bytes=").strip().split("-", 1)
    if len(bounds) != 2:
        raise InvalidMediaRange("The media byte range is malformed.")
    first, last = bounds
    if not first:
        suffix = int(last) if last.isdecimal() else 0
        if suffix <= 0:
            raise InvalidMediaRange("The media suffix range is malformed.")
        start = max(0, size_bytes - suffix)
        return MediaByteRange(start=start, end=size_bytes - 1)
    if not first.isdecimal() or (last and not last.isdecimal()):
        raise InvalidMediaRange("The media byte range is malformed.")
    start = int(first)
    if start >= size_bytes:
        raise InvalidMediaRange("The media byte range starts after the end of the file.")
    end = min(int(last), size_bytes - 1) if last else size_bytes - 1
    if end < start:
        raise InvalidMediaRange("The media byte range ends before it starts.")
    return MediaByteRange(start=start, end=end)


def ranged_chunks(chunks: Iterable[bytes], byte_range: MediaByteRange | None) -> Iterator[bytes]:
    if byte_range is None:
        yield from chunks
        return
    skip = byte_range.start
    remaining = byte_range.length
    iterator = iter(chunks)
    try:
        for chunk in iterator:
            if not chunk:
                continue
            if skip >= len(chunk):
                skip -= len(chunk)
                continue
            selected = chunk[skip : skip + remaining]
            skip = 0
            if selected:
                yield selected
                remaining -= len(selected)
            if remaining == 0:
                return
    finally:
        close = getattr(iterator, "close", None)
        if callable(close):
            close()


@dataclass(frozen=True)
class MediaPlaybackCacheEntry:
    session_id: str
    item: MediaItem
    path: Path
    expires_at: float


class MediaPlaybackCache:
    def __init__(self, *, max_bytes: int = 1024 * 1024 * 1024, max_entries: int = 4) -> None:
        self.max_bytes = max_bytes
        self.max_entries = max_entries
        self._values: dict[str, MediaPlaybackCacheEntry] = {}
        self._timers: dict[str, threading.Timer] = {}
        self._total_bytes = 0
        self._lock = threading.RLock()

    def get(self, token: str) -> MediaPlaybackCacheEntry | None:
        with self._lock:
            self._remove_expired(time.monotonic())
            return self._values.get(token)

    def put(self, token: str, session_id: str, item: MediaItem, path: Path, expires_at: float) -> None:
        if item.size_bytes <= 0 or item.size_bytes > self.max_bytes:
            raise MediaPlaybackCacheFull("The media is larger than the playback cache limit.")
        with self._lock:
            self._remove_expired(time.monotonic())
            self._remove(token)
            if len(self._values) >= self.max_entries or self._total_bytes + item.size_bytes > self.max_bytes:
                raise MediaPlaybackCacheFull("The playback cache is currently full.")
            entry = MediaPlaybackCacheEntry(session_id, item, path, expires_at)
            self._values[token] = entry
            self._total_bytes += item.size_bytes
            timer = threading.Timer(max(0.0, expires_at - time.monotonic()), self.remove, args=(token,))
            timer.daemon = True
            self._timers[token] = timer
            timer.start()

    def remove(self, token: str) -> None:
        with self._lock:
            self._remove(token)

    def remove_session(self, session_id: str) -> None:
        with self._lock:
            for token, entry in tuple(self._values.items()):
                if entry.session_id == session_id:
                    self._remove(token)

    def clear(self) -> None:
        with self._lock:
            for token in tuple(self._values):
                self._remove(token)

    def _remove_expired(self, now: float) -> None:
        for token, entry in tuple(self._values.items()):
            if entry.expires_at <= now:
                self._remove(token)

    def _remove(self, token: str) -> None:
        entry = self._values.pop(token, None)
        timer = self._timers.pop(token, None)
        if timer is not None:
            timer.cancel()
        if entry is None:
            return
        self._total_bytes -= entry.item.size_bytes
        with suppress(FileNotFoundError, OSError):
            entry.path.unlink()


@dataclass(frozen=True)
class MediaPlaybackGrant:
    session_id: str
    media_id: str
    expires_at: float


class MediaPlaybackTickets:
    def __init__(self, lifetime_seconds: int = 900) -> None:
        self.lifetime_seconds = lifetime_seconds
        self._values: dict[str, MediaPlaybackGrant] = {}
        self._lock = threading.Lock()

    def issue(self, session_id: str, media_id: str) -> str:
        token = secrets.token_urlsafe(32)
        now = time.monotonic()
        with self._lock:
            self._remove_expired(now)
            self._values[token] = MediaPlaybackGrant(
                session_id=session_id,
                media_id=media_id,
                expires_at=now + self.lifetime_seconds,
            )
        return token

    def resolve(self, token: str) -> MediaPlaybackGrant | None:
        now = time.monotonic()
        with self._lock:
            self._remove_expired(now)
            return self._values.get(token)

    def revoke(self, token: str) -> None:
        with self._lock:
            self._values.pop(token, None)

    def revoke_session(self, session_id: str) -> None:
        with self._lock:
            self._values = {
                token: grant for token, grant in self._values.items() if grant.session_id != session_id
            }

    def _remove_expired(self, now: float) -> None:
        self._values = {
            token: grant for token, grant in self._values.items() if grant.expires_at > now
        }
