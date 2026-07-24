from __future__ import annotations

import ipaddress
import socket
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from io import BytesIO
from typing import Protocol
from urllib.parse import urlsplit

MAX_ACCESS_UNIT_BYTES = 8 * 1024 * 1024
MAX_DATAGRAM_BYTES = 65_535
H264_CLOCK_RATE = 90_000
ANNEX_B_START_CODE = b"\x00\x00\x00\x01"


class RtpError(ValueError):
    pass


@dataclass(frozen=True)
class RtpMediaDescription:
    kind: str
    port: int
    payload_type: int
    codec: str
    clock_rate: int
    channels: int | None = None


@dataclass(frozen=True)
class RtpSessionDescription:
    raw_sdp: str
    video: RtpMediaDescription
    audio: RtpMediaDescription | None = None


@dataclass(frozen=True)
class H264AccessUnit:
    nal_units: tuple[bytes, ...]
    rtp_timestamp: int
    key_frame: bool
    sequence_parameter_set: bytes | None = None
    picture_parameter_set: bytes | None = None

    @property
    def encoded_byte_count(self) -> int:
        return sum(map(len, self.nal_units))


@dataclass(frozen=True)
class _RtpPacket:
    marker: bool
    payload_type: int
    sequence_number: int
    timestamp: int
    payload: bytes

    @classmethod
    def parse(cls, datagram: bytes) -> _RtpPacket | None:
        if len(datagram) < 12 or datagram[0] >> 6 != 2:
            return None
        csrc_count = datagram[0] & 0x0F
        has_extension = bool(datagram[0] & 0x10)
        has_padding = bool(datagram[0] & 0x20)
        payload_start = 12 + csrc_count * 4
        if payload_start > len(datagram):
            return None
        if has_extension:
            if payload_start + 4 > len(datagram):
                return None
            extension_words = int.from_bytes(datagram[payload_start + 2 : payload_start + 4], "big")
            payload_start += 4 + extension_words * 4
            if payload_start > len(datagram):
                return None
        padding_bytes = datagram[-1] if has_padding else 0
        if has_padding and (padding_bytes == 0 or padding_bytes > len(datagram) - payload_start):
            return None
        payload_end = len(datagram) - padding_bytes
        if payload_start >= payload_end:
            return None
        return cls(
            marker=bool(datagram[1] & 0x80),
            payload_type=datagram[1] & 0x7F,
            sequence_number=int.from_bytes(datagram[2:4], "big"),
            timestamp=int.from_bytes(datagram[4:8], "big"),
            payload=datagram[payload_start:payload_end],
        )


def parse_sdp(sdp: str) -> RtpSessionDescription:
    if not sdp.strip():
        raise RtpError("Canon RTP session description is empty.")
    lines = [line.strip() for line in sdp.splitlines() if line.strip()]
    mappings: dict[int, tuple[str, int, int | None]] = {}
    for line in lines:
        if not line.casefold().startswith("a=rtpmap:") or " " not in line:
            continue
        payload_text, mapping_text = line[9:].split(" ", 1)
        fields = mapping_text.split("/")
        try:
            payload_type = int(payload_text)
            clock_rate = int(fields[1])
        except (IndexError, ValueError):
            continue
        channels = None
        if len(fields) > 2:
            try:
                channels = int(fields[2])
            except ValueError:
                channels = None
        mappings[payload_type] = (fields[0], clock_rate, channels)

    media: list[RtpMediaDescription] = []
    for line in lines:
        if not line.casefold().startswith("m="):
            continue
        fields = line[2:].split()
        if len(fields) < 4 or fields[2].casefold() != "rtp/avp":
            continue
        try:
            port = int(fields[1].split("/", 1)[0])
            candidates = [int(value) for value in fields[3:]]
        except ValueError:
            continue
        kind = fields[0].casefold()
        payload_type = next(
            (
                candidate
                for candidate in candidates
                if candidate in mappings and (kind != "video" or mappings[candidate][0].casefold() == "h264")
            ),
            None,
        )
        if payload_type is None:
            continue
        codec, clock_rate, channels = mappings[payload_type]
        media.append(RtpMediaDescription(kind, port, payload_type, codec, clock_rate, channels))

    video = next(
        (item for item in media if item.kind == "video" and item.codec.casefold() == "h264"),
        None,
    )
    if video is None:
        raise RtpError("Canon RTP SDP does not advertise an H.264 video stream.")
    if not 1 <= video.port <= 65_535:
        raise RtpError(f"Canon RTP video port {video.port} is invalid.")
    if not 0 <= video.payload_type <= 127:
        raise RtpError(f"Canon RTP video payload type {video.payload_type} is invalid.")
    if video.clock_rate != H264_CLOCK_RATE:
        raise RtpError(f"Canon RTP H.264 clock rate {video.clock_rate} is unsupported; expected {H264_CLOCK_RATE}.")
    audio = next((item for item in media if item.kind == "audio"), None)
    if audio is not None and not 1 <= audio.port <= 65_535:
        raise RtpError(f"Canon RTP audio port {audio.port} is invalid.")
    return RtpSessionDescription(raw_sdp=sdp, video=video, audio=audio)


class H264RtpDepacketizer:
    def __init__(self, payload_type: int) -> None:
        self.payload_type = payload_type
        self._timestamp: int | None = None
        self._expected_sequence: int | None = None
        self._valid = True
        self._fragmented_nal: bytearray | None = None
        self._key_frame = False
        self._sps: bytes | None = None
        self._pps: bytes | None = None
        self._nal_units: list[bytes] = []
        self._access_unit_bytes = 0

    def accept(self, datagram: bytes) -> H264AccessUnit | None:
        packet = _RtpPacket.parse(datagram)
        if packet is None or packet.payload_type != self.payload_type:
            return None
        if self._timestamp != packet.timestamp:
            self._reset(packet.timestamp)
        elif self._expected_sequence is not None and packet.sequence_number != self._expected_sequence:
            self._valid = False
            self._fragmented_nal = None
        self._expected_sequence = (packet.sequence_number + 1) & 0xFFFF

        nal_type = packet.payload[0] & 0x1F
        if 1 <= nal_type <= 23:
            self._append_nal(packet.payload)
        elif nal_type == 24:
            self._append_stap_a(packet.payload)
        elif nal_type == 28:
            self._append_fu_a(packet.payload)
        else:
            self._valid = False

        if not packet.marker:
            return None
        completed = None
        if self._valid and self._fragmented_nal is None and self._nal_units and self._timestamp is not None:
            completed = H264AccessUnit(
                nal_units=tuple(self._nal_units),
                rtp_timestamp=self._timestamp,
                key_frame=self._key_frame,
                sequence_parameter_set=self._sps,
                picture_parameter_set=self._pps,
            )
        self._reset(None)
        return completed

    def _append_stap_a(self, payload: bytes) -> None:
        offset = 1
        while offset + 2 <= len(payload):
            size = int.from_bytes(payload[offset : offset + 2], "big")
            offset += 2
            if size <= 0 or offset + size > len(payload):
                self._valid = False
                return
            self._append_nal(payload[offset : offset + size])
            offset += size
        if offset != len(payload):
            self._valid = False

    def _append_fu_a(self, payload: bytes) -> None:
        if len(payload) < 3:
            self._valid = False
            return
        fu_indicator, fu_header = payload[:2]
        start = bool(fu_header & 0x80)
        end = bool(fu_header & 0x40)
        nal_type = fu_header & 0x1F
        if (start and end) or fu_header & 0x20:
            self._valid = False
            self._fragmented_nal = None
            return
        if start:
            if self._fragmented_nal is not None:
                self._valid = False
                return
            reconstructed_header = (fu_indicator & 0xE0) | nal_type
            self._fragmented_nal = bytearray([reconstructed_header, *payload[2:]])
            self._observe_nal_type(nal_type, None)
        else:
            if self._fragmented_nal is None:
                self._valid = False
                return
            self._fragmented_nal.extend(payload[2:])
        if self._fragmented_nal is not None and len(self._fragmented_nal) > MAX_ACCESS_UNIT_BYTES:
            self._valid = False
        if end and self._fragmented_nal is not None:
            nal = bytes(self._fragmented_nal)
            self._fragmented_nal = None
            self._append_nal(nal)

    def _append_nal(self, nal: bytes) -> None:
        if not nal:
            self._valid = False
            return
        self._nal_units.append(nal)
        self._access_unit_bytes += len(nal)
        self._observe_nal_type(nal[0] & 0x1F, nal)
        if self._access_unit_bytes > MAX_ACCESS_UNIT_BYTES:
            self._valid = False

    def _observe_nal_type(self, nal_type: int, nal: bytes | None) -> None:
        if nal_type == 5:
            self._key_frame = True
        elif nal_type == 7:
            self._sps = nal
        elif nal_type == 8:
            self._pps = nal

    def _reset(self, timestamp: int | None) -> None:
        self._timestamp = timestamp
        self._expected_sequence = None
        self._valid = True
        self._fragmented_nal = None
        self._key_frame = False
        self._sps = None
        self._pps = None
        self._nal_units = []
        self._access_unit_bytes = 0


class H264FrameDecoder(Protocol):
    def decode(self, access_unit: H264AccessUnit, *, emit_frame: bool) -> bytes | None: ...

    def close(self) -> None: ...


class PyAvH264FrameDecoder:
    def __init__(self, *, jpeg_quality: int = 85) -> None:
        import av

        self._av = av
        self._codec = av.CodecContext.create("h264", "r")
        self._jpeg_quality = min(max(jpeg_quality, 1), 95)
        self._sps: bytes | None = None
        self._pps: bytes | None = None
        self._waiting_for_key_frame = True

    def decode(self, access_unit: H264AccessUnit, *, emit_frame: bool) -> bytes | None:
        if access_unit.sequence_parameter_set is not None:
            self._sps = access_unit.sequence_parameter_set
        if access_unit.picture_parameter_set is not None:
            self._pps = access_unit.picture_parameter_set
        if self._waiting_for_key_frame and (not access_unit.key_frame or self._sps is None or self._pps is None):
            return None

        nal_units = list(access_unit.nal_units)
        if access_unit.key_frame:
            if not any(nal and nal[0] & 0x1F == 7 for nal in nal_units):
                nal_units.insert(0, self._sps or b"")
            if not any(nal and nal[0] & 0x1F == 8 for nal in nal_units):
                insertion = 1 if nal_units and nal_units[0] == self._sps else 0
                nal_units.insert(insertion, self._pps or b"")
        packet_bytes = b"".join(ANNEX_B_START_CODE + nal for nal in nal_units if nal)
        frames = self._codec.decode(self._av.Packet(packet_bytes))
        if access_unit.key_frame:
            self._waiting_for_key_frame = False
        if not emit_frame or not frames:
            return None
        output = BytesIO()
        frames[-1].to_image().save(output, format="JPEG", quality=self._jpeg_quality)
        return output.getvalue()

    def close(self) -> None:
        self._codec = None


def pyav_decoder_available() -> bool:
    try:
        from PIL import Image  # noqa: F401

        decoder = PyAvH264FrameDecoder()
        decoder.close()
    except Exception:
        return False
    return True


class RtpLiveViewSession(Protocol):
    @property
    def source_url(self) -> str: ...

    @property
    def last_error(self) -> str | None: ...

    def start(self) -> None: ...

    def set_target_fps(self, fps: int) -> None: ...

    def wait_until_ready(self, timeout: float = 5.0) -> None: ...

    def read_frame(self, timeout: float = 5.0) -> bytes: ...

    def close(self) -> None: ...


RtpSessionFactory = Callable[[RtpSessionDescription, str], RtpLiveViewSession]


class UdpH264RtpSession:
    def __init__(
        self,
        description: RtpSessionDescription,
        destination_address: str,
        *,
        decoder_factory: Callable[[], H264FrameDecoder] = PyAvH264FrameDecoder,
    ) -> None:
        self.description = description
        self.destination_address = destination_address
        self._decoder_factory = decoder_factory
        self._condition = threading.Condition()
        self._socket: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._decoder: H264FrameDecoder | None = None
        self._closed = False
        self._target_fps = 30
        self._latest_frame: bytes | None = None
        self._frame_generation = 0
        self._delivered_generation = 0
        self._last_emitted_at = 0.0
        self._last_error: str | None = None

    @property
    def source_url(self) -> str:
        return f"rtp://{self.destination_address}:{self.description.video.port}"

    @property
    def last_error(self) -> str | None:
        with self._condition:
            return self._last_error

    def start(self) -> None:
        with self._condition:
            if self._thread is not None:
                return
            if self._closed:
                raise RtpError("Canon RTP session is already closed.")
        decoder = self._decoder_factory()
        receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        receiver.settimeout(0.25)
        try:
            receiver.bind((self.destination_address, self.description.video.port))
        except Exception:
            receiver.close()
            decoder.close()
            raise
        thread = threading.Thread(target=self._receive_loop, name="open-eos-ccapi-rtp", daemon=True)
        with self._condition:
            self._decoder = decoder
            self._socket = receiver
            self._thread = thread
        thread.start()

    def set_target_fps(self, fps: int) -> None:
        with self._condition:
            self._target_fps = min(max(fps, 1), 30)

    def wait_until_ready(self, timeout: float = 5.0) -> None:
        deadline = time.monotonic() + max(timeout, 0.0)
        with self._condition:
            while not self._closed and self._frame_generation == 0:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    detail = f" Last decoder error: {self._last_error}" if self._last_error else ""
                    raise RtpError(f"Timed out waiting for the first decoded Canon RTP frame.{detail}")
                self._condition.wait(remaining)
            if self._frame_generation == 0:
                detail = f" Last decoder error: {self._last_error}" if self._last_error else ""
                raise RtpError(f"Canon RTP session closed before its first decoded frame.{detail}")

    def read_frame(self, timeout: float = 5.0) -> bytes:
        deadline = time.monotonic() + max(timeout, 0.0)
        with self._condition:
            while not self._closed and self._frame_generation <= self._delivered_generation:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    detail = f" Last decoder error: {self._last_error}" if self._last_error else ""
                    raise RtpError(f"Timed out waiting for a decoded Canon RTP frame.{detail}")
                self._condition.wait(remaining)
            if self._latest_frame is None or self._frame_generation <= self._delivered_generation:
                detail = f" Last decoder error: {self._last_error}" if self._last_error else ""
                raise RtpError(f"Canon RTP session closed before a frame was decoded.{detail}")
            self._delivered_generation = self._frame_generation
            return self._latest_frame

    def close(self) -> None:
        with self._condition:
            if self._closed:
                return
            self._closed = True
            receiver = self._socket
            thread = self._thread
            decoder = self._decoder
            self._socket = None
            self._thread = None
            self._decoder = None
            self._condition.notify_all()
        if receiver is not None:
            receiver.close()
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=2.0)
        if decoder is not None:
            decoder.close()

    def _receive_loop(self) -> None:
        depacketizer = H264RtpDepacketizer(self.description.video.payload_type)
        while True:
            with self._condition:
                if self._closed:
                    return
                receiver = self._socket
                decoder = self._decoder
                target_fps = self._target_fps
            if receiver is None or decoder is None:
                return
            try:
                datagram, _ = receiver.recvfrom(MAX_DATAGRAM_BYTES)
                access_unit = depacketizer.accept(datagram)
                if access_unit is None:
                    continue
                now = time.monotonic()
                emit = (
                    access_unit.key_frame or self._last_emitted_at == 0 or now - self._last_emitted_at >= 1 / target_fps
                )
                frame = decoder.decode(access_unit, emit_frame=emit)
                if frame is None:
                    continue
                with self._condition:
                    self._latest_frame = frame
                    self._frame_generation += 1
                    self._last_emitted_at = now
                    self._last_error = None
                    self._condition.notify_all()
            except TimeoutError:
                continue
            except Exception as error:
                with self._condition:
                    if self._closed:
                        return
                    self._last_error = str(error)


def create_udp_rtp_session(description: RtpSessionDescription, destination_address: str) -> RtpLiveViewSession:
    return UdpH264RtpSession(description, destination_address)


def resolve_local_ipv4(base_url: str) -> str | None:
    parsed = urlsplit(base_url)
    try:
        camera = ipaddress.ip_address(parsed.hostname or "")
    except ValueError:
        return None
    if not isinstance(camera, ipaddress.IPv4Address) or camera.is_unspecified or camera.is_multicast:
        return None
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.settimeout(1.0)
    try:
        probe.connect((str(camera), parsed.port or (443 if parsed.scheme == "https" else 80)))
        address = ipaddress.ip_address(probe.getsockname()[0])
        if not isinstance(address, ipaddress.IPv4Address) or address.is_unspecified or address.is_loopback:
            return None
        return str(address)
    except OSError:
        return None
    finally:
        probe.close()
