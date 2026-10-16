"""Controlled physical worker ownership; no native decoder or network sockets."""
from __future__ import annotations

import threading
from unittest.mock import patch

import pytest

from open_eos_bridge.rtp import RtpError, RtpMediaDescription, RtpSessionDescription, UdpH264RtpSession

PACKET = bytes([0x80, 0xE0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0x65, 1])
JPEG = b"\xff\xd8synthetic-decoded\xff\xd9"


class Receiver:
    def __init__(self, *_args):
        self.closed = threading.Event()
        self.sent = False

    def settimeout(self, _timeout):
        pass

    def bind(self, _endpoint):
        pass

    def recvfrom(self, _limit):
        if not self.sent:
            self.sent = True
            return PACKET, ("192.0.2.1", 12000)
        self.closed.wait(0.01)
        if self.closed.is_set():
            raise OSError("synthetic socket closed")
        raise TimeoutError

    def close(self):
        self.closed.set()


class Decoder:
    def __init__(self, *, blocked=False, during_decode=None):
        self.entered = threading.Event()
        self.release = threading.Event()
        self.disposed = threading.Event()
        self.active = False
        self.close_count = 0
        self.during_decode = during_decode
        if not blocked:
            self.release.set()

    def decode(self, _unit, *, emit_frame=True):
        self.active = True
        self.entered.set()
        try:
            if self.during_decode:
                self.during_decode()
            assert self.release.wait(5), "test must release controlled decoder"
            return JPEG if emit_frame else None
        finally:
            self.active = False

    def close(self):
        assert not self.active, "must never close a physically running decoder"
        self.close_count += 1
        self.disposed.set()


def session(port, decoder, *, owner=None, audio_decoder=None):
    description = RtpSessionDescription("synthetic", RtpMediaDescription("video", port, 96, "H264", 90_000),
        RtpMediaDescription("audio", port + 1, 97, "MP4A-LATM", 48_000, 2) if audio_decoder else None)
    value = UdpH264RtpSession(description, "192.0.2.2", decoder_factory=lambda: decoder,
        audio_decoder_factory=(lambda: audio_decoder) if audio_decoder else None)
    if owner:
        value.set_decoder_owner(owner)
    return value


@pytest.fixture(autouse=True)
def controlled_sockets():
    with patch("open_eos_bridge.rtp.socket.socket", side_effect=Receiver):
        yield


def close_without_waiting(value):
    join = threading.Thread.join
    with patch.object(threading.Thread, "join", lambda worker, timeout=None: join(worker, timeout=0.01)):
        value.close()


def drain(value, decoder):
    worker = value._thread
    decoder.release.set()
    assert decoder.disposed.wait(2)
    if worker is not None:
        worker.join(2)
        assert not worker.is_alive()
    assert decoder.close_count == 1


def test_logical_close_retains_busy_endpoint_until_physical_decoder_exit():
    decoder = Decoder(blocked=True)
    value = session(21000, decoder)
    value.start()
    assert decoder.entered.wait(2)
    close_without_waiting(value)
    assert decoder.close_count == 0
    assert value._receiver_workers == 1
    with pytest.raises(RtpError, match="draining"):
        session(21000, Decoder()).start()
    # Independent endpoints retain legitimate multi-camera concurrency.
    independent_decoder = Decoder()
    independent = session(21002, independent_decoder)
    independent.start()
    independent.close()
    assert independent_decoder.disposed.wait(2)
    drain(value, decoder)
    assert value._frame_generation == 0
    replacement_decoder = Decoder()
    replacement = session(21000, replacement_decoder)
    replacement.start()
    try:
        # Old cleanup cannot release the newer reservation (ABA).
        with value._condition:
            value._release_receiver_reservation()
        with pytest.raises(RtpError, match="draining"):
            session(21000, Decoder()).start()
    finally:
        replacement.close()
    assert replacement_decoder.disposed.wait(2)


def test_camera_owner_retained_across_sdp_port_change_but_not_other_cameras():
    decoder = Decoder(blocked=True)
    value = session(21010, decoder, owner="SYNTHETIC-CAMERA-A")
    value.start()
    assert decoder.entered.wait(2)
    close_without_waiting(value)
    with pytest.raises(RtpError, match="draining"):
        session(21012, Decoder(), owner="SYNTHETIC-CAMERA-A").start()
    with pytest.raises(RtpError, match="draining"):
        session(21010, Decoder(), owner="SYNTHETIC-CAMERA-B").start()
    other_decoder = Decoder()
    other = session(21014, other_decoder, owner="SYNTHETIC-CAMERA-B")
    other.start()
    other.close()
    assert other_decoder.disposed.wait(2)
    drain(value, decoder)
    replacement = session(21012, Decoder(), owner="SYNTHETIC-CAMERA-A")
    replacement.start()
    replacement.close()


def test_close_from_receiver_thread_defers_disposal_until_decode_returns():
    decoder = Decoder()
    value = session(21020, decoder)
    decoder.during_decode = value.close
    value.start()
    assert decoder.disposed.wait(2)
    assert decoder.close_count == 1
    assert value._frame_generation == 0
    assert value._receiver_cleanup_failed is False


def test_close_during_factory_retains_reservation_and_never_starts_worker():
    entered, release = threading.Event(), threading.Event()
    decoder = Decoder()
    value = session(21030, decoder)

    def factory():
        entered.set()
        assert release.wait(2)
        return decoder

    value._decoder_factory = factory
    errors = []

    def prepare():
        try:
            value.start()
        except RtpError as error:
            errors.append(str(error))

    worker = threading.Thread(target=prepare)
    worker.start()
    assert entered.wait(2)
    value.close()
    with pytest.raises(RtpError, match="draining"):
        session(21030, Decoder()).start()
    release.set()
    worker.join(2)
    assert not worker.is_alive()
    assert errors and "closed during" in errors[0]
    assert not decoder.entered.is_set()
    assert decoder.close_count == 1
    replacement = session(21030, Decoder())
    replacement.start()
    replacement.close()


@pytest.mark.parametrize("failure", ["factory", "bind", "thread-start"])
def test_preparation_failure_releases_only_its_own_reservation(failure):
    decoder = Decoder()
    value = session(21040, decoder)
    if failure == "factory":
        value._decoder_factory = lambda: (_ for _ in ()).throw(RuntimeError("factory failed"))
    target = "open_eos_bridge.rtp.threading.Thread.start" if failure == "thread-start" else __name__ + ".Receiver.bind"
    with patch(target, side_effect=RuntimeError(failure + " failed")), pytest.raises(RuntimeError, match="failed"):
        value.start()
    assert decoder.close_count == (0 if failure == "factory" else 1)
    assert value._receiver_workers == 0
    replacement = session(21040, Decoder())
    replacement.start()
    replacement.close()


def test_half_started_audio_failure_disposes_each_decoder_exactly_once():
    video, audio = Decoder(), Decoder()
    value = session(21050, video, audio_decoder=audio)
    original_start = threading.Thread.start
    count = 0

    def start(worker):
        nonlocal count
        count += 1
        if count == 2:
            raise RuntimeError("audio start failed")
        original_start(worker)

    with patch.object(threading.Thread, "start", start), pytest.raises(RuntimeError, match="audio start failed"):
        value.start()
    assert video.disposed.wait(2)
    assert audio.close_count == video.close_count == 1
    assert value._receiver_workers == 0
    replacement = session(21050, Decoder())
    replacement.start()
    replacement.close()


@pytest.mark.parametrize("failure", ["socket", "decoder"])
def test_failed_cleanup_keeps_reservation_and_never_skips_other_disposal(failure):
    decoder = Decoder()
    value = session(21060 if failure == "socket" else 21070, decoder)
    value.start()
    assert decoder.entered.wait(2)
    if failure == "socket":
        original_close = Receiver.close

        def failing_close(receiver):
            original_close(receiver)
            raise OSError("synthetic socket disposal failure")

        with patch.object(Receiver, "close", failing_close):
            value.close()
    else:
        original_close = decoder.close

        def failing_close():
            original_close()
            raise RuntimeError("synthetic decoder disposal failure")

        decoder.close = failing_close
        value.close()
    assert decoder.disposed.wait(2)
    assert decoder.close_count == 1
    assert value._receiver_workers == 0
    assert value._starting is False
    assert value._receiver_cleanup_failed is True
    with pytest.raises(RtpError, match="draining"):
        session(value.description.video.port, Decoder()).start()
