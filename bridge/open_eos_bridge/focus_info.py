from __future__ import annotations

import json
import math

from .models import CameraFocusFrame, CameraFocusInfo

MAX_FOCUS_INFO_BYTES = 256 * 1024
MAX_AF_FRAMES = 512


def parse_ccapi_focus_info_packet(payload: bytes) -> CameraFocusInfo | None:
    """Decode Canon's single incidental-information packet, never image bytes."""
    if not 9 <= len(payload) <= MAX_FOCUS_INFO_BYTES:
        raise ValueError("Invalid focus information packet size.")
    if payload[:3] != b"\xff\x00\x01" or payload[-2:] != b"\xff\xff":
        raise ValueError("Invalid focus information packet framing.")
    length = int.from_bytes(payload[3:7], "big")
    if length <= 0 or length != len(payload) - 9:
        raise ValueError("Invalid focus information packet length.")
    return parse_ccapi_focus_info(json.loads(payload[7:-2].decode("utf-8")))


def _exact_int(value: object) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if not -2147483648 <= value <= 2147483647 or not math.isfinite(value) or value % 1:
        return None
    return int(value)


def parse_ccapi_focus_info(root: object) -> CameraFocusInfo | None:
    data = root.get("liveviewdata") if isinstance(root, dict) else None
    if not isinstance(data, dict):
        return None
    image, zoom, frames = data.get("image"), data.get("zoom"), data.get("afframe")
    if not isinstance(image, dict) or not isinstance(zoom, dict) or not isinstance(frames, list):
        return None
    origin_x, origin_y, width, height = (
        _exact_int(image.get(key)) for key in ("positionx", "positiony", "positionwidth", "positionheight")
    )
    magnification = _exact_int(zoom.get("magnification"))
    if (
        origin_x is None or origin_y is None or width is None or height is None
        or origin_x < 0 or origin_y < 0 or width <= 0 or height <= 0
        or magnification is None or magnification <= 0
    ):
        return None
    if len(frames) > MAX_AF_FRAMES:
        raise ValueError("Focus frame count exceeds the safety limit.")
    result: list[CameraFocusFrame] = []
    # Canon's selected AF rectangles describe 1x image geometry only.
    if magnification == 1:
        for frame in frames:
            if not isinstance(frame, dict) or _exact_int(frame.get("select")) != 1:
                continue
            raw, x, y, w, h = (_exact_int(frame.get(key)) for key in ("status", "x", "y", "width", "height"))
            if any(value is None for value in (raw, x, y, w, h)):
                continue
            if not 0 <= raw <= 255 or w <= 0 or h <= 0:
                continue
            left = min(1.0, max(0.0, (x - origin_x) / width))
            top = min(1.0, max(0.0, (y - origin_y) / height))
            right = min(1.0, max(0.0, (x + w - origin_x) / width))
            bottom = min(1.0, max(0.0, (y + h - origin_y) / height))
            if right <= left or bottom <= top:
                continue
            kind = {0x00: "GENERIC", 0x10: "NORMAL", 0x20: "FACE", 0x30: "TRACKING"}.get(raw & 0xF0, "UNKNOWN")
            status = "UNKNOWN" if kind == "UNKNOWN" else {
                0: "STANDBY", 1: "FOCUSED", 2: "UNFOCUSED", 3: "SERVO_OFF", 4: "FOCUSING",
            }.get(raw & 0x0F, "UNKNOWN")
            result.append(CameraFocusFrame(left=left, top=top, right=right, bottom=bottom, status=status, kind=kind))
    return CameraFocusInfo(frames=result, magnification=magnification, image_aspect_ratio=width / height)
