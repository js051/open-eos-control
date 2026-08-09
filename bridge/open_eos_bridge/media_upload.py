from __future__ import annotations

import re
from pathlib import PurePosixPath, PureWindowsPath

from .errors import BridgeError

MAX_UPLOAD_BYTES = 4 * 1024 * 1024 * 1024 - 1
MAX_UPLOAD_FILENAME_CHARS = 128
_CONTROL_CHARACTERS = re.compile(r"[\x00-\x1f\x7f]")
_SAFE_FILENAME = re.compile(r"^[^<>:\"|?*\\/]+$")
_RESERVED_WINDOWS_STEMS = frozenset(
    {"CON", "PRN", "AUX", "NUL", *(f"COM{index}" for index in range(1, 10)), *(f"LPT{index}" for index in range(1, 10))}
)
_IMAGE_EXTENSIONS = frozenset({".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif", ".cr2", ".cr3", ".dng"})
_VIDEO_EXTENSIONS = frozenset({".mp4", ".mov", ".m4v", ".avi"})
_CONTENT_TYPE_BY_EXTENSION = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".heic": "image/heic",
    ".heif": "image/heif",
    ".cr2": "image/x-canon-cr2",
    ".cr3": "image/x-canon-cr3",
    ".dng": "image/x-adobe-dng",
    ".mp4": "video/mp4",
    ".mov": "video/quicktime",
    ".m4v": "video/x-m4v",
    ".avi": "video/x-msvideo",
}


def validate_upload_request(
    filename: str, content_type: str | None, content_length: str | None
) -> tuple[str, str, int]:
    if not filename or len(filename) > MAX_UPLOAD_FILENAME_CHARS:
        raise BridgeError("INVALID_UPLOAD_FILENAME", "Upload filename is empty or too long.", status_code=422)
    if (
        PurePosixPath(filename).name != filename
        or PureWindowsPath(filename).name != filename
        or filename in {".", ".."}
        or not _SAFE_FILENAME.fullmatch(filename)
        or _CONTROL_CHARACTERS.search(filename)
        or filename != filename.strip()
        or filename.endswith((".", " "))
    ):
        raise BridgeError("INVALID_UPLOAD_FILENAME", "Upload filename must be a safe basename.", status_code=422)
    stem, dot, extension = filename.rpartition(".")
    extension = f".{extension.casefold()}" if dot else ""
    if not stem or extension not in _IMAGE_EXTENSIONS | _VIDEO_EXTENSIONS:
        raise BridgeError(
            "UNSUPPORTED_UPLOAD_FORMAT",
            "Upload filename must use an allowed photo or video extension.",
            status_code=415,
        )
    if stem.upper() in _RESERVED_WINDOWS_STEMS:
        raise BridgeError("INVALID_UPLOAD_FILENAME", "Upload filename uses a reserved device name.", status_code=422)
    try:
        filename.encode("utf-8")
    except UnicodeEncodeError as error:
        raise BridgeError("INVALID_UPLOAD_FILENAME", "Upload filename is not valid UTF-8.", status_code=422) from error

    media_type = (content_type or "").split(";", 1)[0].strip().casefold()
    if not media_type or media_type == "application/octet-stream":
        media_type = _CONTENT_TYPE_BY_EXTENSION[extension]
    elif extension in _IMAGE_EXTENSIONS and not media_type.startswith("image/"):
        raise BridgeError(
            "INVALID_UPLOAD_CONTENT_TYPE", "Photo uploads require an image Content-Type.", status_code=415
        )
    elif extension in _VIDEO_EXTENSIONS and not media_type.startswith("video/"):
        raise BridgeError(
            "INVALID_UPLOAD_CONTENT_TYPE", "Video uploads require a video Content-Type.", status_code=415
        )

    if content_length is None:
        raise BridgeError("CONTENT_LENGTH_REQUIRED", "Content-Length is required for media upload.", status_code=411)
    try:
        size_bytes = int(content_length, 10)
    except ValueError as error:
        raise BridgeError(
            "INVALID_CONTENT_LENGTH", "Content-Length must be a decimal integer.", status_code=400
        ) from error
    if size_bytes <= 0:
        raise BridgeError("INVALID_CONTENT_LENGTH", "Media upload must contain at least one byte.", status_code=400)
    if size_bytes > MAX_UPLOAD_BYTES:
        raise BridgeError(
            "UPLOAD_TOO_LARGE",
            f"Media upload exceeds the {MAX_UPLOAD_BYTES} byte limit.",
            status_code=413,
        )
    return filename, media_type, size_bytes
