"""Controlled receiver/decoder evidence, without a socket or native decoder."""
from __future__ import annotations

from unittest.mock import patch

import pytest

from open_eos_bridge.rtp import RtpError, RtpMediaDescription, RtpSessionDescription, UdpH264RtpSession

JPEG = b"\xff\xd8synthetic-decoded\xff\xd9"
PACKET = bytes([0x80, 0xE0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0x65, 1])


def session() -> UdpH264RtpSession:
    return UdpH264RtpSession(RtpSessionDescription(
        "synthetic", RtpMediaDescription("video", 12000, 96, "H264", 90_000)
    ), "192.0.2.1", audio_decoder_factory=None)


def buffered(value: UdpH264RtpSession, at: float = 10.0) -> None:
    value._latest_frame = JPEG
    value._frame_generation = 1
    value._last_emitted_at = at


@pytest.mark.parametrize("operation", ["wait_until_ready", "read_frame"])
def test_closed_receiver_never_returns_buffered_success(operation: str) -> None:
    value = session()
    buffered(value)
    value.close()
    with pytest.raises(RtpError, match="closed"):
        getattr(value, operation)(timeout=0)


@pytest.mark.parametrize("operation", ["wait_until_ready", "read_frame"])
def test_old_undelivered_output_is_not_freshness(operation: str) -> None:
    value = session()
    buffered(value)
    with patch("open_eos_bridge.rtp.time.monotonic", return_value=15.0), pytest.raises(RtpError, match="Timed out"):
        getattr(value, operation)(timeout=0)
    assert value._delivered_generation == 0


class Receiver:
    def __init__(self, value: UdpH264RtpSession) -> None:
        self.value = value
        self.calls = 0

    def recvfrom(self, _limit: int) -> tuple[bytes, tuple[str, int]]:
        self.calls += 1
        if self.calls > 1:
            raise EndLoop
        return PACKET, ("192.0.2.1", 12000)

    def close(self) -> None:
        pass


class EndLoop(BaseException):
    pass


def test_decoder_completion_after_close_cannot_publish_or_clear_failure() -> None:
    value = session()

    class Decoder:
        def decode(self, _unit, *, emit_frame):
            value.close()
            value._last_error = "terminal-owner-retired"
            return JPEG

        def close(self):
            pass

    value._socket, value._decoder = Receiver(value), Decoder()
    value._receive_loop()
    assert value._frame_generation == 0
    assert value._latest_frame is None
    assert value.last_error == "terminal-owner-retired"


def test_only_completed_output_advances_freshness_at_completion_time() -> None:
    value = session()
    buffered(value, at=1.0)
    value._delivered_generation = 1
    now = [10.0]

    class Decoder:
        def decode(self, _unit, *, emit_frame):
            assert value._frame_generation == 1
            assert value._last_emitted_at == 1.0
            with pytest.raises(RtpError, match="Timed out"):
                value.read_frame(timeout=0)
            now[0] = 12.0
            return JPEG

    value._socket, value._decoder = Receiver(value), Decoder()
    with patch("open_eos_bridge.rtp.time.monotonic", side_effect=lambda: now[0]):
        with pytest.raises(EndLoop):
            value._receive_loop()
        assert value._frame_generation == 2
        assert value._last_emitted_at == 12.0
        assert value.read_frame(timeout=0) == JPEG


@pytest.mark.parametrize("result", [None, b""])
def test_input_and_no_decoder_output_do_not_advance_progress(result) -> None:
    value = session()

    class Decoder:
        def decode(self, _unit, *, emit_frame):
            return result

    value._socket, value._decoder = Receiver(value), Decoder()
    with pytest.raises(EndLoop):
        value._receive_loop()
    assert value._frame_generation == 0
    assert value._latest_frame is None
