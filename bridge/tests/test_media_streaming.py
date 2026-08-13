from __future__ import annotations

import threading
import time
from pathlib import Path

import pytest

from open_eos_bridge.media_streaming import (
    InvalidMediaRange,
    MediaByteRange,
    MediaPlaybackCache,
    MediaPlaybackCacheFull,
    MediaPlaybackTickets,
    has_playback_storage_capacity,
    parse_media_range,
    ranged_chunks,
)
from open_eos_bridge.models import MediaItem


@pytest.mark.parametrize(
    ("header", "expected"),
    [
        (None, None),
        ("bytes=2-5", MediaByteRange(2, 5)),
        ("bytes=7-", MediaByteRange(7, 9)),
        ("bytes=-3", MediaByteRange(7, 9)),
        ("bytes=0-99", MediaByteRange(0, 9)),
    ],
)
def test_media_range_parser_accepts_single_bounded_ranges(
    header: str | None,
    expected: MediaByteRange | None,
) -> None:
    assert parse_media_range(header, 10) == expected


@pytest.mark.parametrize(
    "header",
    ["items=0-1", "bytes=", "bytes=1-0", "bytes=10-", "bytes=0-1,3-4", "bytes=-0"],
)
def test_media_range_parser_rejects_unsupported_ranges(header: str) -> None:
    with pytest.raises(InvalidMediaRange):
        parse_media_range(header, 10)


def test_ranged_chunks_skips_and_stops_without_buffering_the_file() -> None:
    assert b"".join(ranged_chunks([b"abc", b"defg", b"hij"], MediaByteRange(2, 7))) == b"cdefgh"


def test_playback_capacity_allows_files_larger_than_one_gibibyte_when_space_exists() -> None:
    two_gibibytes = 2 * 1024 * 1024 * 1024

    assert has_playback_storage_capacity(two_gibibytes, two_gibibytes + 512 * 1024 * 1024)
    assert not has_playback_storage_capacity(two_gibibytes, two_gibibytes + 64 * 1024 * 1024)


def test_playback_tickets_are_bound_to_session_and_media_and_can_be_revoked() -> None:
    tickets = MediaPlaybackTickets(lifetime_seconds=60)
    token = tickets.issue("session-a", "media-a")

    assert tickets.resolve(token) is not None
    assert tickets.resolve(token).session_id == "session-a"  # type: ignore[union-attr]
    assert tickets.resolve(token).media_id == "media-a"  # type: ignore[union-attr]

    tickets.revoke_session("session-a")
    assert tickets.resolve(token) is None


def test_playback_ticket_and_cache_can_be_renewed_without_old_expiry_removing_file(tmp_path: Path) -> None:
    tickets = MediaPlaybackTickets(lifetime_seconds=60)
    token = tickets.issue("session-a", "media-a")
    original = tickets.resolve(token)
    renewed = tickets.resolve(token, renew=True)
    assert original is not None and renewed is not None
    assert renewed.expires_at >= original.expires_at

    cache = MediaPlaybackCache()
    item = MediaItem(id="media-a", name="A.MP4", kind="video", size_bytes=5, content_type="video/mp4")
    media = tmp_path / "renewed.bin"
    media.write_bytes(b"12345")
    cache.put(token, "session-a", item, media, original.expires_at)
    cache.renew(token, renewed.expires_at + 60)
    cache._expire(token, original.expires_at)  # noqa: SLF001 - verifies stale timer behavior.

    assert cache.get(token) is not None
    assert media.exists()
    cache.remove(token)


def test_playback_cache_expires_files_and_never_evicts_active_entries(tmp_path: Path) -> None:
    cache = MediaPlaybackCache(max_entries=1)
    item = MediaItem(id="media-a", name="A.MP4", kind="video", size_bytes=5, content_type="video/mp4")
    first = tmp_path / "first.bin"
    second = tmp_path / "second.bin"
    first.write_bytes(b"12345")
    second.write_bytes(b"67890")

    cache.put("ticket-a", "session-a", item, first, time.monotonic() + 60)
    with pytest.raises(MediaPlaybackCacheFull):
        cache.put(
            "ticket-b",
            "session-a",
            item.model_copy(update={"id": "media-b", "name": "B.MP4"}),
            second,
            time.monotonic() + 60,
        )

    assert cache.get("ticket-a") is not None
    assert first.exists()
    assert second.exists()
    cache.remove("ticket-a")
    assert not first.exists()

    expired = tmp_path / "expired.bin"
    expired.write_bytes(b"12345")
    cache.put("ticket-expired", "session-a", item, expired, time.monotonic() - 1)
    assert cache.get("ticket-expired") is None
    assert not expired.exists()


def test_playback_cache_serializes_preparation_for_the_same_ticket() -> None:
    cache = MediaPlaybackCache()
    first_entered = threading.Event()
    release_first = threading.Event()
    second_entered = threading.Event()

    def first() -> None:
        with cache.preparing("ticket"):
            first_entered.set()
            assert release_first.wait(timeout=2)

    def second() -> None:
        assert first_entered.wait(timeout=2)
        with cache.preparing("ticket"):
            second_entered.set()

    first_thread = threading.Thread(target=first)
    second_thread = threading.Thread(target=second)
    first_thread.start()
    second_thread.start()
    assert first_entered.wait(timeout=2)
    assert not second_entered.wait(timeout=0.05)
    release_first.set()
    first_thread.join(timeout=2)
    second_thread.join(timeout=2)

    assert second_entered.is_set()


def test_playback_cache_cleans_file_after_revoked_stream_releases_it(tmp_path: Path) -> None:
    cache = MediaPlaybackCache()
    item = MediaItem(id="media-a", name="A.MP4", kind="video", size_bytes=5, content_type="video/mp4")
    media = tmp_path / "active.bin"
    media.write_bytes(b"12345")
    cache.put("ticket", "session-a", item, media, time.monotonic() + 60)

    cache.remove("ticket")
    media.write_bytes(b"12345")  # Simulates Windows unlink failure while the stream handle was open.
    cache.cleanup_orphan("ticket", media)

    assert not media.exists()
