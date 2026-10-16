"""Actual native receiver mailboxes and producer loops; synthetic input, no sockets/codecs."""
from __future__ import annotations

import threading
import time
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from open_eos_bridge.ccapi import CcapiEngine, CcapiMultipartSession, CcapiStreamResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import LiveViewStartRequest
from open_eos_bridge.rtp import (
    H264AccessUnit,
    PyAvH264FrameDecoder,
    RtpMediaDescription,
    RtpSessionDescription,
    UdpH264RtpSession,
)

from .liveview_jpeg_fixture import JPEG
from .test_ccapi import FakeRtpSession
from .test_magnification_geometry import MagnificationTransport

SOURCES = ("CCAPI_RTP", "CCAPI_MULTIPART")
PACKET = bytes([0x80, 0xE0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0x65, 1])


def publish(receiver, source, value):
    with receiver._condition:
        receiver._latest_frame = value
        if source == "CCAPI_RTP":
            receiver._frame_generation += 1
            receiver._last_emitted_at = time.monotonic()
        else:
            receiver._produced_generation += 1
        receiver._condition.notify_all()


@pytest.fixture
def setup_mailbox():
    sessions = []

    def create(source):
        transport = MagnificationTransport()
        session = CcapiEngine(lambda *_: transport, rtp_session_factory=lambda *_: FakeRtpSession(),
                              route_resolver=lambda _: "192.0.2.2").open_connection("http://192.0.2.1:8080")
        sessions.append(session)
        session.start_live_view(LiveViewStartRequest(source=source))
        if source == "CCAPI_RTP":
            receiver = UdpH264RtpSession(RtpSessionDescription("synthetic",
                RtpMediaDescription("video", 12000, 96, "H264", 90_000)), "192.0.2.2", audio_decoder_factory=None)
            session._rtp_session.close()
            session._rtp_session = receiver
        else:
            with patch("open_eos_bridge.ccapi.threading.Thread.start"):
                receiver = CcapiMultipartSession(CcapiStreamResponse(200, {}, BytesIO()), "frame")
            session._multipart_session.close()
            session._multipart_session = receiver
        publish(receiver, source, JPEG)
        return session, transport, receiver

    yield create
    for session in sessions:
        session.close()


@pytest.mark.parametrize("source", SOURCES)
@pytest.mark.parametrize("outcome", ["success", "lost-ack", "cancelled", "mismatched-readback"])
def test_zoom_retires_undelivered_mailbox_before_dispatch_and_again_on_settle(setup_mailbox, source, outcome):
    session, transport, receiver = setup_mailbox(source)

    def during_zoom():
        assert receiver._latest_frame is None, "zoom dispatched while old local output remained deliverable"
        # Simulate output produced while the command is awaiting acknowledgement.
        publish(receiver, source, b"during-command")

    transport.before_zoom = during_zoom
    if outcome in {"lost-ack", "cancelled"}:
        transport.zoom_failure = (BridgeError("CCAPI_UNREACHABLE", "lost ACK", status_code=502)
                                  if outcome == "lost-ack" else KeyboardInterrupt("cancelled"))
    transport.mismatched_readback = outcome == "mismatched-readback"
    if outcome == "success":
        session.set_live_view_magnification(5)
    else:
        with pytest.raises(KeyboardInterrupt if outcome == "cancelled" else BridgeError):
            session.set_live_view_magnification(5)
    assert receiver._latest_frame is None, "command settlement retained output from its uncertain interval"
    read = receiver.read_frame if source == "CCAPI_RTP" else receiver.next_frame
    with pytest.raises(Exception, match="Timed out"):
        read(timeout=0)
    publish(receiver, source, JPEG)
    assert read(timeout=0) == JPEG


class EndLoop(BaseException):
    pass


@pytest.mark.parametrize("crossing", ["receive", "decode"])
def test_rtp_local_producer_crossing_zoom_cannot_publish_and_current_input_recovers(setup_mailbox, crossing):
    session, _transport, receiver = setup_mailbox("CCAPI_RTP")

    class Socket:
        calls = 0

        def recvfrom(self, _limit):
            self.calls += 1
            if self.calls > 1:
                raise EndLoop
            if crossing == "receive":
                session.set_live_view_magnification(5)
            return PACKET, ("192.0.2.1", 12000)

        def close(self):
            pass

    class Decoder:
        def reset_for_frame_epoch(self):
            pass

        def decode(self, _unit, *, emit_frame):
            if crossing == "decode":
                session.set_live_view_magnification(5)
            return JPEG

    receiver._socket, receiver._decoder = Socket(), Decoder()
    with pytest.raises(EndLoop):
        receiver._receive_loop()
    assert receiver._latest_frame is None, "retired local input/decode refilled the mailbox"
    assert receiver._frame_generation == 1
    crossing = "current"
    receiver._socket = Socket()
    with pytest.raises(EndLoop):
        receiver._receive_loop()
    assert receiver.read_frame(timeout=0) == JPEG
    assert receiver._frame_generation == 2


def test_multipart_local_read_crossing_zoom_cannot_publish_and_current_input_recovers(setup_mailbox):
    session, _transport, receiver = setup_mailbox("CCAPI_MULTIPART")
    crossing = True

    class Reader:
        def __init__(self, *_args):
            self.calls = 0

        def next_frame(self):
            self.calls += 1
            if self.calls > 1:
                raise EndLoop
            if crossing:
                session.set_live_view_magnification(5)
            return JPEG

    with patch("open_eos_bridge.ccapi.CcapiMultipartReader", Reader), pytest.raises(EndLoop):
        receiver._drain()
    assert receiver._latest_frame is None, "retired multipart read refilled the mailbox"
    assert receiver._produced_generation == 1
    crossing = False
    with patch("open_eos_bridge.ccapi.CcapiMultipartReader", Reader), pytest.raises(EndLoop):
        receiver._drain()
    assert receiver.next_frame(timeout=0) == JPEG
    assert receiver._produced_generation == 2


@pytest.mark.parametrize("source", SOURCES)
def test_rejected_zoom_before_dispatch_does_not_retire_valid_mailbox(setup_mailbox, source):
    session, transport, receiver = setup_mailbox(source)
    with pytest.raises(BridgeError, match="one of"):
        session.set_live_view_magnification(2)
    assert transport.zoom_sent is False
    read = receiver.read_frame if source == "CCAPI_RTP" else receiver.next_frame
    assert read(timeout=0) == JPEG



def test_codec_buffer_reset_is_worker_owned_after_active_decode_and_before_next_input(setup_mailbox):
    session, _transport, receiver = setup_mailbox("CCAPI_RTP")
    entered, release = threading.Event(), threading.Event()
    command_thread = threading.get_ident()
    reset_threads = []

    class Socket:
        calls = 0

        def recvfrom(self, _limit):
            self.calls += 1
            if self.calls > 2:
                raise EndLoop
            return PACKET, ("192.0.2.1", 12000)

        def close(self):
            pass

    class Decoder:
        active = False
        first = True
        delayed = None

        def reset_for_frame_epoch(self):
            assert not self.active, "command thread must not reset an active codec"
            reset_threads.append(threading.get_ident())
            self.delayed = None

        def decode(self, _unit, *, emit_frame):
            self.active = True
            try:
                if self.first:
                    self.first = False
                    self.delayed = b"old-codec-buffer"
                    entered.set()
                    assert release.wait(5)
                    return None
                return self.delayed or JPEG
            finally:
                self.active = False

    receiver._socket, receiver._decoder = Socket(), Decoder()
    with ThreadPoolExecutor(max_workers=1) as executor:
        run = executor.submit(receiver._receive_loop)
        assert entered.wait(5)
        try:
            session.set_live_view_magnification(5)
            assert reset_threads == [], "camera command must only advance the mailbox epoch"
        finally:
            release.set()
        with pytest.raises(EndLoop):
            run.result(timeout=5)
    assert len(reset_threads) == 1
    assert reset_threads[0] != command_thread
    assert receiver.read_frame(timeout=0) == JPEG


def test_pyav_epoch_reset_replaces_context_and_requires_keyframe_without_native_codec():
    # Exercise the actual PyAV adapter logic with controlled codec objects.
    contexts, decoded = [], []

    class Image:
        def save(self, output, **_kwargs):
            output.write(JPEG)

    class Codec:
        def decode(self, packet):
            decoded.append(packet)
            return [SimpleNamespace(to_image=Image)]

    def create(codec, mode):
        assert (codec, mode) == ("h264", "r")
        contexts.append(Codec())
        return contexts[-1]

    decoder = PyAvH264FrameDecoder.__new__(PyAvH264FrameDecoder)
    old_context = object()
    decoder._codec = old_context
    decoder._av = SimpleNamespace(CodecContext=SimpleNamespace(create=create), Packet=lambda value: value)
    decoder._sps, decoder._pps = b"\x67synthetic-sps", b"\x68synthetic-pps"
    decoder._jpeg_quality, decoder._waiting_for_key_frame = 85, False
    decoder.reset_for_frame_epoch()
    assert decoder._codec is contexts[0] and decoder._codec is not old_context
    assert decoder._waiting_for_key_frame is True
    assert decoder.decode(H264AccessUnit((b"\x61delta",), 1, False), emit_frame=True) is None
    assert decoded == []
    assert decoder.decode(H264AccessUnit((b"\x65key",), 2, True), emit_frame=True) == JPEG
    assert decoder._waiting_for_key_frame is False
    assert b"synthetic-sps" in decoded[0] and b"synthetic-pps" in decoded[0]
