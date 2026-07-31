from __future__ import annotations

import socket
import time
from fractions import Fraction
from io import BytesIO
from unittest.mock import patch

import av
import pytest

from open_eos_bridge.rtp import (
    H264AccessUnit,
    H264RtpDepacketizer,
    LatmAccessUnit,
    LatmRtpDepacketizer,
    PcmAudio,
    PyAvH264FrameDecoder,
    PyAvLatmAudioDecoder,
    RtpError,
    RtpMediaDescription,
    RtpSessionDescription,
    UdpH264RtpSession,
    latm_audio_support,
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
CANON_SDP = """v=0
o=- 0 0 IN IP4 192.168.11.4
s=RTP Session
c=IN IP4 0.0.0.0
t=0 0
a=control *
m=video 12000 RTP/AVP 103
a=rtpmap:103 H264/90000
m=audio 12010 RTP/AVP 106
a=rtpmap:106 MP4A-LATM/48000
"""
JPEG = b"\xff\xd8decoded-rtp\xff\xd9"


def test_parse_canon_sdp_selects_h264_video_and_audio() -> None:
    description = parse_sdp(SDP)

    assert description.video == RtpMediaDescription("video", 12000, 96, "H264", 90_000)
    assert description.audio == RtpMediaDescription("audio", 12002, 97, "MP4A-LATM", 48_000, 2)


def test_parse_canon_reference_sdp_accepts_in_band_latm_without_channels_or_fmtp() -> None:
    description = parse_sdp(CANON_SDP)

    assert description.video == RtpMediaDescription("video", 12000, 103, "H264", 90_000)
    assert description.audio == RtpMediaDescription("audio", 12010, 106, "MP4A-LATM", 48_000)
    assert latm_audio_support(description.audio) == (
        True,
        "Canon RTP MP4A-LATM audio uses in-band StreamMuxConfig.",
    )


def test_sdp_fmtp_is_scoped_to_payload_and_rejects_out_of_band_latm_configuration() -> None:
    description = parse_sdp(
        SDP + "a=fmtp:96 packetization-mode=1\na=fmtp:97 profile-level-id=1; cpresent=0; config=400026203fc0\n"
    )

    assert description.video.parameter("packetization-mode") == "1"
    assert description.audio is not None
    assert description.audio.parameter("config") == "400026203fc0"
    supported, reason = latm_audio_support(description.audio)
    assert supported is False
    assert "cpresent=0" in reason


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


def test_latm_depacketizer_reassembles_fragments_and_drops_packet_loss() -> None:
    depacketizer = LatmRtpDepacketizer(97)
    assert depacketizer.accept(_rtp_packet(10, 900, b"audio-", payload_type=97)) is None
    assert depacketizer.accept(_rtp_packet(11, 900, b"mux", marker=True, payload_type=97)) == LatmAccessUnit(
        b"audio-mux",
        900,
    )

    assert depacketizer.accept(_rtp_packet(20, 901, b"lost-", payload_type=97)) is None
    assert depacketizer.accept(_rtp_packet(22, 901, b"packet", marker=True, payload_type=97)) is None
    assert depacketizer.accept(_rtp_packet(23, 902, b"next", marker=True, payload_type=97)) == LatmAccessUnit(
        b"next",
        902,
        discontinuity=True,
    )


def test_pyav_latm_decoder_turns_real_in_band_streammuxconfig_into_pcm() -> None:
    audio_mux_element = _encoded_latm_audio_mux_element()
    decoder = PyAvLatmAudioDecoder()

    pcm = decoder.decode(LatmAccessUnit(audio_mux_element, 0))
    decoder.close()

    assert pcm is not None
    assert pcm.sample_rate == 48_000
    assert pcm.channels == 2
    assert pcm.sample_frames > 0
    assert len(pcm.content) == pcm.sample_frames * pcm.channels * 2


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


def test_udp_rtp_session_receives_video_and_audio_without_coupling_readiness() -> None:
    video_port = _available_udp_port()
    audio_port = _available_udp_port(excluding={video_port})
    video_decoder = _FakeDecoder()
    audio_decoder = _FakeAudioDecoder()
    description = RtpSessionDescription(
        raw_sdp="test",
        video=RtpMediaDescription("video", video_port, 96, "H264", 90_000),
        audio=RtpMediaDescription("audio", audio_port, 97, "MP4A-LATM", 48_000),
    )
    session = UdpH264RtpSession(
        description,
        "127.0.0.1",
        decoder_factory=lambda: video_decoder,
        audio_decoder_factory=lambda: audio_decoder,
    )
    sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    session.start()
    try:
        sender.sendto(_rtp_packet(1, 500, b"\x65frame", marker=True), ("127.0.0.1", video_port))
        sender.sendto(_rtp_packet(2, 600, b"latm", marker=True, payload_type=97), ("127.0.0.1", audio_port))
        assert session.read_frame(timeout=2.0) == JPEG
        audio = session.read_audio(timeout=2.0)
        assert audio is not None
        assert audio.content == b"\x00\x00\x01\x00"
        assert audio.generation == 1
        assert audio.sample_rate == 48_000
        assert audio.channels == 2
        assert audio.sample_frames == 1
        assert audio.discontinuity is False
        assert session.audio_status["available"] is True
        assert session.audio_status["generation"] == 1
        assert audio_decoder.units == [LatmAccessUnit(b"latm", 600)]
    finally:
        sender.close()
        session.close()
    assert video_decoder.closed is True
    assert audio_decoder.closed is True


def test_udp_rtp_audio_failure_does_not_prevent_video_start() -> None:
    video_port = _available_udp_port()
    occupied_audio = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    occupied_audio.bind(("127.0.0.1", 0))
    audio_port = int(occupied_audio.getsockname()[1])
    description = RtpSessionDescription(
        raw_sdp="test",
        video=RtpMediaDescription("video", video_port, 96, "H264", 90_000),
        audio=RtpMediaDescription("audio", audio_port, 97, "MP4A-LATM", 48_000),
    )
    session = UdpH264RtpSession(
        description,
        "127.0.0.1",
        decoder_factory=_FakeDecoder,
        audio_decoder_factory=_FakeAudioDecoder,
    )
    sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        session.start()
        sender.sendto(_rtp_packet(1, 500, b"\x65frame", marker=True), ("127.0.0.1", video_port))
        assert session.read_frame(timeout=2.0) == JPEG
        assert session.audio_status["available"] is False
        assert "prepare" in str(session.audio_status["reason"]).casefold()
        with pytest.raises(RtpError, match="prepare"):
            session.read_audio(timeout=0)
    finally:
        sender.close()
        occupied_audio.close()
        session.close()


def test_first_rtp_audio_read_starts_at_latest_buffered_chunk() -> None:
    video_port = _available_udp_port()
    audio_port = _available_udp_port(excluding={video_port})
    description = RtpSessionDescription(
        raw_sdp="test",
        video=RtpMediaDescription("video", video_port, 96, "H264", 90_000),
        audio=RtpMediaDescription("audio", audio_port, 97, "MP4A-LATM", 48_000),
    )
    session = UdpH264RtpSession(
        description,
        "127.0.0.1",
        decoder_factory=_FakeDecoder,
        audio_decoder_factory=_FakeAudioDecoder,
    )
    sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    session.start()
    try:
        for generation in range(1, 4):
            sender.sendto(
                _rtp_packet(generation, 700 + generation, bytes([generation]), marker=True, payload_type=97),
                ("127.0.0.1", audio_port),
            )
        deadline = time.monotonic() + 2
        while session.audio_status["generation"] < 3 and time.monotonic() < deadline:
            time.sleep(0.01)
        chunk = session.read_audio(after_generation=0, timeout=1.0)
        assert chunk is not None
        assert chunk.generation == 3
        assert chunk.discontinuity is False
    finally:
        sender.close()
        session.close()


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


class _FakeAudioDecoder:
    def __init__(self) -> None:
        self.units: list[LatmAccessUnit] = []
        self.closed = False

    def decode(self, access_unit: LatmAccessUnit) -> PcmAudio:
        self.units.append(access_unit)
        return PcmAudio(b"\x00\x00\x01\x00", 48_000, 2, 1)

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


def _rtp_packet(
    sequence: int,
    timestamp: int,
    payload: bytes,
    *,
    marker: bool = False,
    payload_type: int = 96,
) -> bytes:
    return (
        bytes(
            [
                0x80,
                (0x80 if marker else 0) | payload_type,
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


def _available_udp_port(*, excluding: set[int] | None = None) -> int:
    while True:
        receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            receiver.bind(("127.0.0.1", 0))
            port = int(receiver.getsockname()[1])
        finally:
            receiver.close()
        if port not in (excluding or set()):
            return port


def _encoded_latm_audio_mux_element() -> bytes:
    output = BytesIO()
    container = av.open(output, mode="w", format="latm")
    stream = container.add_stream("aac", rate=48_000)
    stream.layout = "stereo"
    frame = av.AudioFrame(format="fltp", layout="stereo", samples=1024)
    frame.sample_rate = 48_000
    frame.time_base = Fraction(1, 48_000)
    frame.pts = 0
    for plane in frame.planes:
        plane.update(bytes(plane.buffer_size))
    for packet in stream.encode(frame):
        container.mux(packet)
    for packet in stream.encode(None):
        container.mux(packet)
    container.close()
    loas = output.getvalue()
    assert loas[:1] == b"\x56"
    size = ((loas[1] & 0x1F) << 8) | loas[2]
    return loas[3 : 3 + size]


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
