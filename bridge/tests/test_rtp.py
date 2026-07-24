from __future__ import annotations

import socket
import time
from fractions import Fraction
from unittest.mock import patch

import av
import pytest

from open_eos_bridge.rtp import (
    H264AccessUnit,
    H264RtpDepacketizer,
    PyAvH264FrameDecoder,
    RtpError,
    RtpMediaDescription,
    RtpSessionDescription,
    UdpH264RtpSession,
    parse_sdp,
    resolve_local_ipv4,
)

SDP = """v=0
o=- 0 0 IN IP4 127.0.0.1
s=Canon CCAPI
c=IN IP4 0.0.0.0
t=0 0
m=video 12000 RTP/AVP 96
a=rtpmap:96 H264/90000
m=audio 12002 RTP/AVP 97
a=rtpmap:97 MP4A-LATM/48000/2
"""
JPEG = b"\xff\xd8decoded-rtp\xff\xd9"


def test_parse_canon_sdp_selects_h264_video_and_audio() -> None:
    description = parse_sdp(SDP)

    assert description.video == RtpMediaDescription("video", 12000, 96, "H264", 90_000)
    assert description.audio == RtpMediaDescription("audio", 12002, 97, "MP4A-LATM", 48_000, 2)


@pytest.mark.parametrize(
    ("sdp", "message"),
    [
        ("", "empty"),
        ("m=video 12000 RTP/AVP 96\na=rtpmap:96 VP8/90000", "H.264"),
        ("m=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000", "port"),
        ("m=video 12000 RTP/AVP 96\na=rtpmap:96 H264/8000", "90000"),
    ],
)
def test_parse_canon_sdp_rejects_invalid_video_contracts(sdp: str, message: str) -> None:
    with pytest.raises(RtpError, match=message):
        parse_sdp(sdp)


def test_h264_depacketizer_handles_single_nal_stap_a_and_fu_a() -> None:
    single = H264RtpDepacketizer(96).accept(_rtp_packet(1, 100, b"\x65idr", marker=True))
    assert single == H264AccessUnit((b"\x65idr",), 100, True)

    stap_payload = b"\x78" + len(b"\x67sps").to_bytes(2, "big") + b"\x67sps"
    stap_payload += len(b"\x68pps").to_bytes(2, "big") + b"\x68pps"
    stap = H264RtpDepacketizer(96).accept(_rtp_packet(2, 200, stap_payload, marker=True))
    assert stap == H264AccessUnit(
        (b"\x67sps", b"\x68pps"),
        200,
        False,
        sequence_parameter_set=b"\x67sps",
        picture_parameter_set=b"\x68pps",
    )

    depacketizer = H264RtpDepacketizer(96)
    assert depacketizer.accept(_rtp_packet(10, 300, b"\x7c\x85first")) is None
    fragmented = depacketizer.accept(_rtp_packet(11, 300, b"\x7c\x45last", marker=True))
    assert fragmented == H264AccessUnit((b"\x65firstlast",), 300, True)


def test_h264_depacketizer_drops_access_unit_after_packet_loss() -> None:
    depacketizer = H264RtpDepacketizer(96)

    assert depacketizer.accept(_rtp_packet(10, 400, b"\x7c\x85first")) is None
    assert depacketizer.accept(_rtp_packet(12, 400, b"\x7c\x45last", marker=True)) is None


def test_h264_depacketizer_accepts_rtp_extension_and_padding() -> None:
    packet = bytearray(_rtp_packet(20, 450, b"\x61frame", marker=True))
    packet[0] |= 0x30
    packet[12:12] = b"\x10\x00\x00\x01ext!"
    packet.extend(b"\x00\x00\x00\x04")

    access_unit = H264RtpDepacketizer(96).accept(bytes(packet))

    assert access_unit == H264AccessUnit((b"\x61frame",), 450, False)


def test_pyav_decoder_turns_a_real_h264_access_unit_into_jpeg() -> None:
    access_unit = _encoded_h264_access_unit()
    decoder = PyAvH264FrameDecoder()

    jpeg = decoder.decode(access_unit, emit_frame=True)
    decoder.close()

    assert jpeg is not None
    assert jpeg.startswith(b"\xff\xd8")
    assert jpeg.endswith(b"\xff\xd9")


def test_udp_rtp_session_receives_depacketizes_and_exposes_latest_jpeg() -> None:
    port = _available_udp_port()
    decoder = _FakeDecoder()
    description = RtpSessionDescription(
        raw_sdp="test",
        video=RtpMediaDescription("video", port, 96, "H264", 90_000),
    )
    session = UdpH264RtpSession(description, "127.0.0.1", decoder_factory=lambda: decoder)
    sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    session.set_target_fps(15)
    session.start()
    try:
        sender.sendto(_rtp_packet(1, 500, b"\x65frame", marker=True), ("127.0.0.1", port))
        session.wait_until_ready(timeout=2.0)
        assert session.read_frame(timeout=2.0) == JPEG
        assert decoder.units == [H264AccessUnit((b"\x65frame",), 500, True)]
        assert decoder.emit_flags == [True]
    finally:
        sender.close()
        session.close()
    assert decoder.closed is True


def test_udp_rtp_session_decodes_all_frames_while_capping_jpeg_output() -> None:
    port = _available_udp_port()
    decoder = _FakeDecoder()
    description = RtpSessionDescription(
        raw_sdp="test",
        video=RtpMediaDescription("video", port, 96, "H264", 90_000),
    )
    session = UdpH264RtpSession(description, "127.0.0.1", decoder_factory=lambda: decoder)
    sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    session.set_target_fps(1)
    session.start()
    try:
        sender.sendto(_rtp_packet(1, 500, b"\x65key", marker=True), ("127.0.0.1", port))
        assert session.read_frame(timeout=2.0) == JPEG
        sender.sendto(_rtp_packet(2, 501, b"\x61predicted-a", marker=True), ("127.0.0.1", port))
        sender.sendto(_rtp_packet(3, 502, b"\x61predicted-b", marker=True), ("127.0.0.1", port))
        deadline = time.monotonic() + 2
        while len(decoder.units) < 3 and time.monotonic() < deadline:
            time.sleep(0.01)
        assert len(decoder.units) == 3
        assert decoder.emit_flags == [True, False, False]
    finally:
        sender.close()
        session.close()


def test_route_resolver_uses_the_ipv4_route_selected_for_the_camera() -> None:
    probe = _FakeRouteSocket()
    with patch("open_eos_bridge.rtp.socket.socket", return_value=probe):
        address = resolve_local_ipv4("http://192.168.1.2:8080")

    assert address == "192.168.1.20"
    assert probe.connected_to == ("192.168.1.2", 8080)
    assert probe.closed is True


class _FakeDecoder:
    def __init__(self) -> None:
        self.units: list[H264AccessUnit] = []
        self.emit_flags: list[bool] = []
        self.closed = False

    def decode(self, access_unit: H264AccessUnit, *, emit_frame: bool) -> bytes | None:
        self.units.append(access_unit)
        self.emit_flags.append(emit_frame)
        return JPEG if emit_frame else None

    def close(self) -> None:
        self.closed = True


class _FakeRouteSocket:
    def __init__(self) -> None:
        self.connected_to: tuple[str, int] | None = None
        self.closed = False

    def settimeout(self, timeout: float) -> None:
        assert timeout == 1.0

    def connect(self, destination: tuple[str, int]) -> None:
        self.connected_to = destination

    def getsockname(self) -> tuple[str, int]:
        return "192.168.1.20", 52_000

    def close(self) -> None:
        self.closed = True


def _rtp_packet(sequence: int, timestamp: int, payload: bytes, *, marker: bool = False) -> bytes:
    return (
        bytes(
            [
                0x80,
                (0x80 if marker else 0) | 96,
                sequence >> 8,
                sequence & 0xFF,
                timestamp >> 24,
                (timestamp >> 16) & 0xFF,
                (timestamp >> 8) & 0xFF,
                timestamp & 0xFF,
                0,
                0,
                0,
                1,
            ]
        )
        + payload
    )


def _available_udp_port() -> int:
    receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        receiver.bind(("127.0.0.1", 0))
        return int(receiver.getsockname()[1])
    finally:
        receiver.close()


def _encoded_h264_access_unit() -> H264AccessUnit:
    encoder = av.CodecContext.create("libx264", "w")
    encoder.width = 64
    encoder.height = 48
    encoder.pix_fmt = "yuv420p"
    encoder.time_base = Fraction(1, 30)
    encoder.options = {"preset": "ultrafast", "tune": "zerolatency"}
    encoder.open()
    frame = av.VideoFrame(64, 48, "yuv420p")
    for plane, value in zip(frame.planes, (90, 128, 128), strict=True):
        plane.update(bytes([value]) * plane.buffer_size)
    frame.pts = 0
    encoded = b"".join(bytes(packet) for packet in [*encoder.encode(frame), *encoder.encode(None)])
    nal_units = tuple(_split_annex_b(encoded))
    sps = next(nal for nal in nal_units if nal[0] & 0x1F == 7)
    pps = next(nal for nal in nal_units if nal[0] & 0x1F == 8)
    return H264AccessUnit(nal_units, 0, True, sps, pps)


def _split_annex_b(data: bytes) -> list[bytes]:
    starts: list[tuple[int, int]] = []
    index = 0
    while index < len(data) - 3:
        if data[index : index + 4] == b"\x00\x00\x00\x01":
            starts.append((index, 4))
            index += 4
        elif data[index : index + 3] == b"\x00\x00\x01":
            starts.append((index, 3))
            index += 3
        else:
            index += 1
    return [
        data[start + prefix : starts[position + 1][0] if position + 1 < len(starts) else len(data)]
        for position, (start, prefix) in enumerate(starts)
    ]
