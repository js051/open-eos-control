from __future__ import annotations

import secrets
import threading
import time
from collections.abc import Iterable, Iterator
from dataclasses import dataclass


@dataclass(frozen=True)
class MediaByteRange:
    start: int
    end: int

    @property
    def length(self) -> int:
        return self.end - self.start + 1


class InvalidMediaRange(ValueError):
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
