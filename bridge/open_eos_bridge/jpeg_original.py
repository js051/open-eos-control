"""Bounded opt-in validation of an automatic import's already downloaded original.

The parser is a format/envelope gate, not an entropy decoder. A separate native
FFmpeg decode is required before a caller may publish a JPEG validation receipt.
This is not a claim that every possible damaged JPEG can be detected.
"""
from __future__ import annotations

import hashlib
import multiprocessing
import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from .errors import BridgeError

MAX_ENCODED_BYTES = 64 * 1024 * 1024
MAX_PIXELS = 64_000_000
MAX_SIDE = 16384
MAX_MARKERS = 4096
MAX_SCANS = 256
MAX_MIME_BYTES = 4096
DECODE_TIMEOUT_SECONDS = 15.0
# Native decode is isolated from the bridge, serial, and bounded in wall time.
# lowres=3 limits output to ceil(side / 8), at most 2048 on either side.
_DECODE_SLOT = threading.BoundedSemaphore(1)
_TOKEN = r"[!#$%&'*+.^_`|~0-9A-Za-z-]+"
_QUOTED = r'"(?:[\x20-\x21\x23-\x5b\x5d-\x7e]|\\[\x20-\x7e])*"'
_MIME = re.compile(rf'({_TOKEN})/({_TOKEN}) *(?:; *{_TOKEN} *= *(?:{_TOKEN}|{_QUOTED}) *)*')


class InvalidJpegOriginal(ValueError):
    pass


def validate_http_mime(raw: str | None) -> None:
    """Validate an observed field, never a guessed filename or metadata MIME."""
    if raw is None:
        return
    if not isinstance(raw, str) or not raw or len(raw) > MAX_MIME_BYTES or not raw.strip(" \t"):
        raise InvalidJpegOriginal("empty original Content-Type")
    if any(not 32 <= ord(char) <= 126 for char in raw):
        raise InvalidJpegOriginal("control character in original Content-Type")
    match = _MIME.fullmatch(raw.strip(" "))
    if not match or f"{match[1]}/{match[2]}".lower() not in {"image/jpeg", "application/octet-stream"}:
        raise InvalidJpegOriginal("unsupported original Content-Type")


def validate_http_types(values: list[str | None]) -> None:
    if len(values) > 1:
        raise InvalidJpegOriginal("duplicate original Content-Type")
    validate_http_mime(values[0] if values else None)


@dataclass(frozen=True)
class JpegShape:
    width: int
    height: int
    components: int
    scans: int


def parse_jpeg(data: bytes) -> JpegShape:
    """Parse the complete codestream, including every scan and its true EOI."""
    if not 4 <= len(data) <= MAX_ENCODED_BYTES or data[:2] != b"\xff\xd8":
        raise InvalidJpegOriginal("invalid JPEG start or encoded size")
    offset, markers, scans = 2, 1, 0
    shape: tuple[int, int, int] | None = None
    components: set[int] = set()
    sof = None
    quantization: set[int] = set()
    huffman: dict[tuple[int, int], bytes] = {}
    component_tables: dict[int, int] = {}
    sampling_factors: dict[int, int] = {}
    coefficients: dict[int, list[int]] = {}
    scanned: set[int] = set()
    restart_interval = 0
    while offset < len(data):
        if data[offset] != 0xFF:
            raise InvalidJpegOriginal("expected JPEG marker")
        while offset < len(data) and data[offset] == 0xFF:
            offset += 1
        if offset >= len(data):
            raise InvalidJpegOriginal("truncated JPEG marker")
        marker = data[offset]
        offset += 1
        markers += 1
        if markers > MAX_MARKERS:
            raise InvalidJpegOriginal("JPEG marker limit")
        if marker == 0xD9:
            if shape is None or not scans or scanned != components or offset != len(data):
                raise InvalidJpegOriginal("incomplete JPEG or trailing data")
            if sof == 0xC2 and any(values[0] < 0 for values in coefficients.values()):
                raise InvalidJpegOriginal("missing progressive JPEG DC scan")
            return JpegShape(*shape, scans)
        if marker in {0, 1, 0xD8} or 0xD0 <= marker <= 0xD7:
            raise InvalidJpegOriginal("unexpected standalone JPEG marker")
        if offset + 2 > len(data):
            raise InvalidJpegOriginal("truncated JPEG segment length")
        length = int.from_bytes(data[offset:offset + 2], "big")
        end = offset + length
        if length < 2 or end > len(data):
            raise InvalidJpegOriginal("invalid JPEG segment length")
        payload = data[offset + 2:end]
        offset = end
        if marker in {0xC0, 0xC1, 0xC2}:
            if shape is not None or len(payload) < 6:
                raise InvalidJpegOriginal("duplicate or incomplete JPEG frame")
            precision, count = payload[0], payload[5]
            height, width = int.from_bytes(payload[1:3], "big"), int.from_bytes(payload[3:5], "big")
            if precision != 8 or count not in {1, 3, 4} or len(payload) != 6 + 3 * count:
                raise InvalidJpegOriginal("unsupported JPEG frame")
            if not 1 <= width <= MAX_SIDE or not 1 <= height <= MAX_SIDE or width * height > MAX_PIXELS:
                raise InvalidJpegOriginal("JPEG dimension limit")
            for index in range(count):
                identity, sampling, table = payload[6 + 3 * index:9 + 3 * index]
                if identity in components or not 1 <= sampling >> 4 <= 4 or not 1 <= sampling & 15 <= 4 or table > 3:
                    raise InvalidJpegOriginal("invalid JPEG frame component")
                components.add(identity)
                component_tables[identity] = table
                sampling_factors[identity] = (sampling >> 4) * (sampling & 15)
                coefficients[identity] = [-1] * 64
            shape, sof = (width, height, count), marker
        elif marker == 0xDA:
            scans += 1
            if shape is None or scans > MAX_SCANS or not payload:
                raise InvalidJpegOriginal("missing JPEG frame or scan limit")
            count = payload[0]
            if not 1 <= count <= len(components) or len(payload) != 4 + 2 * count:
                raise InvalidJpegOriginal("invalid JPEG scan header")
            first, last, approx = payload[-3:]
            high, low = approx >> 4, approx & 15
            if sof in {0xC0, 0xC1}:
                if (first, last, approx) != (0, 63, 0):
                    raise InvalidJpegOriginal("invalid sequential JPEG scan")
            elif (first > last or last > 63 or (first == 0 and last != 0) or (first > 0 and count != 1)
                  or high > 13 or low > 13 or (high != 0 and high != low + 1)):
                raise InvalidJpegOriginal("invalid progressive JPEG scan")
            selected: set[int] = set()
            order = list(component_tables)
            prior_index = -1
            for index in range(count):
                identity, tables = payload[1 + index * 2:3 + index * 2]
                dc, ac = tables >> 4, tables & 15
                if identity not in components or identity in selected or dc > 3 or ac > 3:
                    raise InvalidJpegOriginal("invalid JPEG scan component or table selector")
                if order.index(identity) <= prior_index:
                    raise InvalidJpegOriginal("JPEG scan component order differs from frame")
                prior_index = order.index(identity)
                if sof == 0xC0 and (dc > 1 or ac > 1):
                    raise InvalidJpegOriginal("unsupported baseline JPEG table selector")
                if component_tables[identity] not in quantization:
                    raise InvalidJpegOriginal("missing JPEG quantization table")
                if first == 0 and high == 0 and (0, dc) not in huffman:
                    raise InvalidJpegOriginal("missing JPEG DC Huffman table")
                if last > 0 and (1, ac) not in huffman:
                    raise InvalidJpegOriginal("missing JPEG AC Huffman table")
                if sof != 0xC2:
                    if identity in scanned:
                        raise InvalidJpegOriginal("repeated sequential JPEG component")
                    if any(symbol & 15 == 0 and symbol not in {0, 0xF0} for symbol in huffman[(1, ac)]):
                        raise InvalidJpegOriginal("invalid sequential JPEG AC symbol")
                else:
                    if (first == 0 and ac != 0) or (first > 0 and dc != 0):
                        raise InvalidJpegOriginal("nonzero unused progressive JPEG selector")
                    previous = coefficients[identity]
                    if first > 0 and previous[0] < 0:
                        raise InvalidJpegOriginal("JPEG AC scan precedes DC scan")
                    for coefficient in range(first, last + 1):
                        if previous[coefficient] != (-1 if high == 0 else high):
                            raise InvalidJpegOriginal("invalid progressive JPEG coefficient sequence")
                        previous[coefficient] = low
                selected.add(identity)
            if count > 1 and sum(sampling_factors[identity] for identity in selected) > 10:
                raise InvalidJpegOriginal("interleaved JPEG sampling exceeds ten blocks")
            scanned.update(selected)
            entropy, next_restart = 0, 0
            while offset < len(data):
                if data[offset] != 0xFF:
                    entropy += 1
                    offset += 1
                    continue
                start = offset
                offset += 1
                while offset < len(data) and data[offset] == 0xFF:
                    offset += 1
                if offset >= len(data):
                    raise InvalidJpegOriginal("truncated JPEG entropy marker")
                code = data[offset]
                if code == 0:
                    if offset != start + 1:
                        raise InvalidJpegOriginal("invalid JPEG byte stuffing")
                    entropy += 1
                    offset += 1
                elif 0xD0 <= code <= 0xD7:
                    if not entropy or not restart_interval or code != 0xD0 + next_restart:
                        raise InvalidJpegOriginal("invalid JPEG restart sequence")
                    entropy, next_restart = 0, (next_restart + 1) % 8
                    markers += 1
                    if markers > MAX_MARKERS:
                        raise InvalidJpegOriginal("JPEG marker limit")
                    offset += 1
                else:
                    offset = start
                    break
            if not entropy:
                raise InvalidJpegOriginal("empty JPEG entropy")
        elif marker == 0xDB:
            index = 0
            if not payload:
                raise InvalidJpegOriginal("empty JPEG quantization segment")
            while index < len(payload):
                descriptor = payload[index]
                precision, table = descriptor >> 4, descriptor & 15
                if precision != 0 or table > 3:
                    raise InvalidJpegOriginal("invalid JPEG quantization table")
                begin = index + 1
                index += 1 + 64 * (precision + 1)
                if index > len(payload):
                    raise InvalidJpegOriginal("truncated JPEG quantization table")
                values = [int.from_bytes(payload[pos:pos + precision + 1], "big")
                          for pos in range(begin, index, precision + 1)]
                if 0 in values:
                    raise InvalidJpegOriginal("zero JPEG quantization value")
                quantization.add(table)
        elif marker == 0xC4:
            index = 0
            if not payload:
                raise InvalidJpegOriginal("empty JPEG Huffman segment")
            while index < len(payload):
                if index + 17 > len(payload):
                    raise InvalidJpegOriginal("truncated JPEG Huffman header")
                descriptor = payload[index]
                category, table = descriptor >> 4, descriptor & 15
                counts = payload[index + 1:index + 17]
                count = sum(counts)
                available = 1
                for length_count in counts:
                    available = available * 2 - length_count
                    if available < 0:
                        raise InvalidJpegOriginal("oversubscribed JPEG Huffman table")
                if available == 0:
                    raise InvalidJpegOriginal("full JPEG Huffman table")
                if category > 1 or table > 3 or not 1 <= count <= 256:
                    raise InvalidJpegOriginal("invalid JPEG Huffman table")
                index += 17 + count
                if index > len(payload):
                    raise InvalidJpegOriginal("truncated JPEG Huffman symbols")
                symbols = payload[index - count:index]
                if len(set(symbols)) != count or any(
                    symbol > 11 if category == 0 else symbol & 15 > 10 for symbol in symbols
                ):
                    raise InvalidJpegOriginal("invalid or duplicate JPEG Huffman symbols")
                huffman[(category, table)] = symbols
        elif marker == 0xDD:
            if len(payload) != 2:
                raise InvalidJpegOriginal("invalid JPEG restart interval")
            restart_interval = int.from_bytes(payload, "big")
        elif marker not in {0xC4, 0xDB, 0xFE} and not 0xE0 <= marker <= 0xEF:
            raise InvalidJpegOriginal("unsupported JPEG marker")
    raise InvalidJpegOriginal("missing JPEG end")


def _decode_native(data: bytes, shape: JpegShape) -> None:
    import av

    codec = av.CodecContext.create("mjpeg", "r")
    codec.options = {
        "err_detect": "explode+bitstream+buffer+careful+compliant+aggressive",
        "threads": "1", "lowres": "3",
    }
    frames = codec.decode(av.Packet(data)) + codec.decode(None)
    expected = ((shape.width + 7) // 8, (shape.height + 7) // 8)
    if len(frames) != 1 or (frames[0].width, frames[0].height) != expected:
        raise InvalidJpegOriginal("unexpected native JPEG decode dimensions")
    if frames[0].is_corrupt:
        raise InvalidJpegOriginal("native JPEG decoder reported corruption")


def _decode_worker(path: str, shape: JpegShape, expected_sha256: str, connection) -> None:
    try:
        # Read is bounded even if the private spool changed unexpectedly.
        with Path(path).open("rb") as source:
            data = source.read(MAX_ENCODED_BYTES + 1)
        if hashlib.sha256(data).hexdigest() != expected_sha256 or parse_jpeg(data) != shape:
            raise InvalidJpegOriginal("JPEG changed before decode")
        _decode_native(data, shape)
        connection.send(True)
    except Exception:
        # Do not return native diagnostics, private paths, or camera bytes.
        connection.send(False)
    finally:
        connection.close()


def decode_jpeg_file(path: Path, shape: JpegShape, expected_sha256: str) -> None:
    if not _DECODE_SLOT.acquire(blocking=False):
        raise InvalidJpegOriginal("JPEG validation is busy; explicitly enable import again later")
    receiver = sender = process = None
    release_slot = True
    try:
        context = multiprocessing.get_context("spawn")
        receiver, sender = context.Pipe(duplex=False)
        process = context.Process(target=_decode_worker, args=(str(path), shape, expected_sha256, sender), daemon=True)
        deadline = time.monotonic() + DECODE_TIMEOUT_SECONDS
        process.start()
        sender.close()
        process.join(max(0, deadline - time.monotonic()))
        if process.is_alive():
            raise InvalidJpegOriginal("JPEG validation deadline exceeded")
        if process.exitcode != 0 or not receiver.poll() or receiver.recv() is not True:
            raise InvalidJpegOriginal("original JPEG native decode failed")
    finally:
        if process is not None:
            if process.pid is not None and process.is_alive():
                process.kill()
                process.join(2.0)
            if process.pid is not None and process.is_alive():
                # Even OS reaping must not hold a request indefinitely. Keep the
                # single slot reserved until the killed child actually exits.
                release_slot = False
                threading.Thread(target=_reap_decoder, args=(process,), daemon=True).start()
            else:
                process.close()
        if receiver is not None:
            receiver.close()
        if sender is not None:
            sender.close()
        if release_slot:
            _DECODE_SLOT.release()


def _reap_decoder(process) -> None:
    try:
        process.join()
        process.close()
    finally:
        _DECODE_SLOT.release()


def validate_jpeg_original(path: Path, *, expected_sha256: str | None = None) -> JpegShape:
    try:
        with path.open("rb") as source:
            data = source.read(MAX_ENCODED_BYTES + 1)
        digest = hashlib.sha256(data).hexdigest()
        if expected_sha256 is not None and digest != expected_sha256:
            raise InvalidJpegOriginal("closed original differs from downloaded bytes")
        shape = parse_jpeg(data)
        del data
        decode_jpeg_file(path, shape, digest)
        return shape
    except (InvalidJpegOriginal, OSError, EOFError, RuntimeError) as error:
        raise BridgeError(
            "MEDIA_ORIGINAL_JPEG_INVALID",
            "The downloaded original could not pass bounded JPEG validation. Automatic import has stopped.",
            status_code=502, feature="MEDIA_DOWNLOAD",
        ) from error
