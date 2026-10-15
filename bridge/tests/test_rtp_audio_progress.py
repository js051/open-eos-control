"""Controlled audio decoder/output evidence: no socket, camera or audio device."""
from __future__ import annotations

import threading
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from open_eos_bridge.ccapi import CcapiSession
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.rtp import (
    MAX_AUDIO_CHUNK_FRAMES,
    PcmAudio,
    RtpAudioChunk,
    RtpError,
    RtpMediaDescription,
    RtpSessionDescription,
    UdpH264RtpSession,
)

PACKET = bytes([0x80, 0xE1, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1])
PCM = PcmAudio(b"\0\0\0\0", 48000, 2, 1)


class EndLoop(BaseException):
    pass


class Receiver:
    def __init__(self):
        self.reads = 0

    def recvfrom(self, _limit):
        self.reads += 1
        if self.reads > 1:
            raise EndLoop
        return PACKET, ("192.0.2.1", 12001)

    def close(self):
        pass


def session():
    value = UdpH264RtpSession(RtpSessionDescription("synthetic",
        RtpMediaDescription("video", 12000, 96, "H264", 90000),
        RtpMediaDescription("audio", 12001, 97, "MP4A-LATM", 48000, 2)), "192.0.2.1")
    value._audio_thread = SimpleNamespace(join=lambda **_kwargs: None)
    value._audio_socket = Receiver()
    return value


def buffered(value, generation=1, at=10.0):
    chunk = RtpAudioChunk(PCM.content, generation, 48000, 2, 1, decoded_at_monotonic=at)
    value._audio_chunks.append(chunk)
    value._audio_generation = generation
    value._audio_last_decoded_at = at
    return chunk


@pytest.mark.parametrize("after", [0, 1])
def test_cached_pcm_expires_even_if_receiver_thread_remains_active(after):
    value = session()
    buffered(value, generation=2)
    with patch("open_eos_bridge.rtp.time.monotonic", return_value=11.0):
        assert value.read_audio(after_generation=after, timeout=0) is None
        assert not value._audio_chunks
        status = value.audio_status
    assert status["decodingAvailable"] is True
    assert status["available"] is False
    assert status["decodedFresh"] is False
    assert status["outputProgressSupported"] is False
    assert status["physicalAudibilityConfirmed"] is False


def test_live_thread_without_output_never_advertises_decoded_readiness():
    value = session()
    assert value.audio_status["active"] is True
    assert value.audio_status["decodingAvailable"] is True
    assert value.audio_status["available"] is False
    assert value.audio_status["generation"] == 0
    assert value.read_audio(timeout=0) is None


def test_fresh_read_skips_old_chunks_marks_discontinuity_and_can_recover():
    value = session()
    buffered(value, generation=2, at=9.0)
    buffered(value, generation=3, at=10.5)
    with patch("open_eos_bridge.rtp.time.monotonic", return_value=11.0):
        result = value.read_audio(after_generation=1, timeout=0)
        assert result.generation == 3
        assert result.discontinuity is True
        assert value.audio_status["decodedFresh"] is True
        assert value.read_audio(after_generation=0, timeout=0).generation == 3
    with patch("open_eos_bridge.rtp.time.monotonic", return_value=11.5):
        assert value.read_audio(after_generation=0, timeout=0) is None
        buffered(value, generation=4, at=11.5)
        assert value.read_audio(after_generation=3, timeout=0).generation == 4


def test_close_clears_pcm_and_no_cached_success_can_escape():
    value = session()
    buffered(value)
    value.close()
    assert not value._audio_chunks
    assert value.audio_status["decodingAvailable"] is False
    with pytest.raises(RtpError, match="closed"):
        value.read_audio(timeout=0)


@pytest.mark.parametrize("output", [None, PcmAudio(b"", 48000, 2, 0),
    replace(PCM, content=b"\0"), replace(PCM, sample_rate=44100), replace(PCM, channels=1),
    PcmAudio(bytes((MAX_AUDIO_CHUNK_FRAMES + 1) * 4), 48000, 2, MAX_AUDIO_CHUNK_FRAMES + 1)])
def test_decoder_input_empty_or_malformed_output_never_advances_pcm_progress(output):
    value = session()
    value._audio_decoder = SimpleNamespace(decode=lambda _unit: output)
    with pytest.raises(EndLoop):
        value._receive_audio_loop()
    assert value._audio_generation == 0
    assert value.audio_status["decoderInputAccessUnits"] == 1
    assert value.audio_status["decodedSampleFrames"] == 0
    assert value._audio_last_decoded_at is None
    assert value.audio_status["decodedFresh"] is False
    assert value.last_error is None, "Audio errors remain separate from video errors"


def test_silent_pcm_counts_only_after_decode_completes_at_monotonic_completion():
    value = session()
    now = [10.0]

    def decode(_unit):
        assert value.audio_status["decodedFresh"] is False
        assert value._audio_generation == 0
        now[0] = 10.1
        return PCM

    value._audio_decoder = SimpleNamespace(decode=decode)
    with patch("open_eos_bridge.rtp.time.monotonic", side_effect=lambda: now[0]):
        with pytest.raises(EndLoop):
            value._receive_audio_loop()
        chunk = value.read_audio(timeout=0)
        assert chunk.content == bytes(4), "Digital silence is valid decoded output"
        assert chunk.decoded_at_monotonic == 10.1
        assert value.audio_status["available"] is True
    assert value._audio_generation == 1


def test_decode_delayed_beyond_pcm_deadline_does_not_publish_old_input_as_new_output():
    value = session()
    now = [10.0]

    def decode(_unit):
        now[0] = 11.0
        return PCM

    value._audio_decoder = SimpleNamespace(decode=decode)
    with patch("open_eos_bridge.rtp.time.monotonic", side_effect=lambda: now[0]), pytest.raises(EndLoop):
        value._receive_audio_loop()
    assert value._audio_generation == 0
    assert "deadline" in value._audio_last_error
    assert value.last_error is None


def test_late_decode_after_close_cannot_publish_or_clear_terminal_audio_error():
    value = session()

    def decode(_unit):
        value.close()
        value._audio_last_error = "synthetic retired owner"
        return PCM

    value._audio_decoder = SimpleNamespace(decode=decode)
    value._receive_audio_loop()
    assert value._audio_generation == 0
    assert value._audio_last_error == "synthetic retired owner"
    assert not value._audio_chunks


def test_wall_clock_change_never_keeps_old_pcm_fresh():
    value = session()
    buffered(value)
    with patch("open_eos_bridge.rtp.time.time", return_value=0), \
            patch("open_eos_bridge.rtp.time.monotonic", return_value=11.0):
        assert value.read_audio(timeout=0) is None
        assert value.audio_status["decodedFresh"] is False


def ccapi_session(reader):
    value = CcapiSession.__new__(CcapiSession)
    value._lock = threading.RLock()
    value._closed = False
    value._live_view_active = True
    value._active_live_view_source = "CCAPI_RTP"
    value._last_error = "synthetic prior video diagnostic"
    value._rtp_session = SimpleNamespace(
        audio_status={"available": False, "decodingAvailable": True}, read_audio=reader
    )
    return value


def test_ccapi_can_wait_for_first_pcm_without_claiming_decoded_readiness():
    calls = []

    def reader(**kwargs):
        calls.append(kwargs)
        return None

    value = ccapi_session(reader)
    assert value.live_view_audio(timeout=0) is None
    assert calls == [{"after_generation": 0, "timeout": 0}]


def test_ccapi_drops_audio_returned_after_receiver_owner_was_replaced():
    def reader(**_kwargs):
        value._rtp_session = object()
        return RtpAudioChunk(PCM.content, 1, 48000, 2, 1)

    value = ccapi_session(reader)
    assert value.live_view_audio(timeout=0) is None
    assert value._live_view_active is True


def test_ccapi_audio_error_never_replaces_video_diagnostic_or_stops_video():
    def reader(**_kwargs):
        raise RtpError("synthetic audio error")

    value = ccapi_session(reader)
    with pytest.raises(BridgeError) as failure:
        value.live_view_audio(timeout=0)
    assert failure.value.code == "CCAPI_RTP_AUDIO_FAILED"
    assert value._last_error == "synthetic prior video diagnostic"
    assert value._live_view_active is True
