from __future__ import annotations

import base64
import io
import mimetypes
import os
import shutil
import sys
import tempfile
from collections.abc import Iterator, Mapping
from datetime import UTC, datetime
from pathlib import Path

from PIL import Image, ImageOps

from .errors import BridgeError
from .models import CameraFeature, MediaItem

ENGINE_NAME = "libgphoto2"
HOST_MEDIA_PREFIX = "gphoto2-host:"
MAX_CAPTURE_FILES_PER_SHOT = 8
MAX_CAPTURE_FILE_BYTES = 4 * 1024**3
MAX_LOCAL_MEDIA_ITEMS = 500
MAX_LOCAL_THUMBNAIL_BYTES = 8 * 1024 * 1024
MAX_LOCAL_PREVIEW_BYTES = 32 * 1024 * 1024
LOCAL_THUMBNAIL_SIZE = (960, 960)


def default_capture_directory(
    *,
    environment: Mapping[str, str] | None = None,
    platform_name: str | None = None,
    home: Path | None = None,
) -> Path:
    configured_environment = environment if environment is not None else os.environ
    configured = configured_environment.get("OPEN_EOS_CAPTURE_DIR")
    if configured:
        path = Path(configured).expanduser()
        if not path.is_absolute():
            raise BridgeError(
                "INVALID_CAPTURE_DIRECTORY",
                "OPEN_EOS_CAPTURE_DIR must be an absolute path.",
                status_code=500,
                engine=ENGINE_NAME,
            )
        return path

    user_home = home or Path.home()
    current_platform = platform_name or sys.platform
    if current_platform == "win32":
        base = Path(configured_environment.get("LOCALAPPDATA") or user_home / "AppData" / "Local")
        return base / "OpenEOSControl" / "Captures"
    if current_platform == "darwin":
        return user_home / "Library" / "Application Support" / "OpenEOSControl" / "Captures"
    xdg_data_home = configured_environment.get("XDG_DATA_HOME")
    base = Path(xdg_data_home).expanduser() if xdg_data_home else user_home / ".local" / "share"
    return base / "open-eos-control" / "captures"


class LocalCaptureStore:
    def __init__(self, root: Path) -> None:
        self.root = root.expanduser().resolve(strict=False)

    def begin_capture(self) -> Path:
        staging_root = self.root / ".staging"
        try:
            staging_root.mkdir(parents=True, exist_ok=True)
            return Path(tempfile.mkdtemp(prefix="capture-", dir=staging_root))
        except OSError as error:
            raise _store_error("create capture staging", error) from error

    def discard_capture(self, staging: Path) -> None:
        self._require_staging_path(staging)
        shutil.rmtree(staging, ignore_errors=True)

    def promote_capture(self, staging: Path) -> list[MediaItem]:
        self._require_staging_path(staging)
        try:
            candidates = sorted(
                path
                for path in staging.iterdir()
                if path.is_file() and not path.is_symlink()
            )
        except OSError as error:
            raise _store_error("inspect captured media", error) from error
        if not candidates:
            raise BridgeError(
                "CAPTURE_TRANSFER_MISSING",
                "gphoto2 completed capture-and-download without producing a local media file.",
                status_code=502,
                feature=CameraFeature.STILL_CAPTURE.value,
                engine=ENGINE_NAME,
            )
        if len(candidates) > MAX_CAPTURE_FILES_PER_SHOT:
            raise BridgeError(
                "CAPTURE_FILE_LIMIT",
                f"A single capture produced more than {MAX_CAPTURE_FILES_PER_SHOT} files.",
                status_code=502,
                feature=CameraFeature.STILL_CAPTURE.value,
                engine=ENGINE_NAME,
            )

        for candidate in candidates:
            try:
                size = candidate.stat().st_size
            except OSError as error:
                raise _store_error("inspect captured media", error) from error
            if size <= 0 or size > MAX_CAPTURE_FILE_BYTES:
                raise BridgeError(
                    "CAPTURE_FILE_INVALID",
                    f"Captured file '{candidate.name}' has an invalid size.",
                    status_code=502,
                    feature=CameraFeature.STILL_CAPTURE.value,
                    engine=ENGINE_NAME,
                )

        try:
            self.root.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise _store_error("create the local media library", error) from error
        promoted: list[MediaItem] = []
        for candidate in candidates:
            target = self._unique_target(candidate.name)
            try:
                os.replace(candidate, target)
            except OSError as error:
                raise _store_error("store captured media", error) from error
            promoted.append(self._item(target))
        shutil.rmtree(staging, ignore_errors=True)
        return promoted

    def list_items(self) -> list[MediaItem]:
        try:
            if not self.root.is_dir():
                return []
            files = [
                path
                for path in self.root.iterdir()
                if path.is_file() and not path.is_symlink()
            ]
            files.sort(key=lambda path: path.stat().st_mtime, reverse=True)
            return [self._item(path) for path in files[:MAX_LOCAL_MEDIA_ITEMS]]
        except OSError as error:
            raise _store_error("read the local media library", error) from error

    def item(self, media_id: str) -> tuple[MediaItem, Path]:
        name = _decode_host_media_id(media_id)
        path = self.root / name
        try:
            available = path.is_file() and not path.is_symlink()
        except OSError as error:
            raise _store_error("read local captured media", error) from error
        if not available or path.parent.resolve(strict=False) != self.root:
            raise BridgeError("MEDIA_NOT_FOUND", "Local captured media was not found.", status_code=404)
        return self._item(path), path

    def stream(self, media_id: str) -> tuple[MediaItem, Iterator[bytes]]:
        item, path = self.item(media_id)

        def chunks() -> Iterator[bytes]:
            try:
                with path.open("rb") as source:
                    while chunk := source.read(64 * 1024):
                        yield chunk
            except FileNotFoundError as error:
                raise BridgeError("MEDIA_NOT_FOUND", "Local captured media was not found.", status_code=404) from error
            except OSError as error:
                raise _store_error("stream local captured media", error) from error

        return item, chunks()

    def thumbnail(self, media_id: str) -> tuple[bytes, str]:
        _, path = self.item(media_id)
        try:
            with Image.open(path) as source:
                image = ImageOps.exif_transpose(source)
                image.thumbnail(LOCAL_THUMBNAIL_SIZE, Image.Resampling.LANCZOS)
                if image.mode not in {"RGB", "L"}:
                    image = image.convert("RGB")
                output = io.BytesIO()
                image.save(output, format="JPEG", quality=85, optimize=True)
        except (OSError, Image.DecompressionBombError) as error:
            raise BridgeError(
                "MEDIA_THUMBNAIL_UNAVAILABLE",
                "This local media format does not have a supported preview decoder.",
                status_code=422,
                feature=CameraFeature.MEDIA_THUMBNAIL.value,
                engine=ENGINE_NAME,
            ) from error
        content = output.getvalue()
        if not content or len(content) > MAX_LOCAL_THUMBNAIL_BYTES:
            raise BridgeError(
                "MEDIA_THUMBNAIL_LIMIT",
                f"The generated thumbnail exceeds {MAX_LOCAL_THUMBNAIL_BYTES} bytes.",
                status_code=502,
                feature=CameraFeature.MEDIA_THUMBNAIL.value,
                engine=ENGINE_NAME,
            )
        return content, "image/jpeg"

    def preview(self, media_id: str) -> tuple[bytes, str]:
        item, path = self.item(media_id)
        if not item.preview_available:
            raise _preview_unavailable()
        try:
            with path.open("rb") as source:
                content = source.read(MAX_LOCAL_PREVIEW_BYTES + 1)
        except FileNotFoundError as error:
            raise BridgeError("MEDIA_NOT_FOUND", "Local captured media was not found.", status_code=404) from error
        except OSError as error:
            raise _store_error("read local media preview", error) from error
        content_type = preview_content_type(content)
        if not content_type:
            raise _preview_unavailable()
        return content, content_type

    def delete(self, media_id: str) -> None:
        _, path = self.item(media_id)
        try:
            path.unlink()
        except FileNotFoundError as error:
            raise BridgeError("MEDIA_NOT_FOUND", "Local captured media was not found.", status_code=404) from error
        except OSError as error:
            raise _store_error("delete local captured media", error) from error

    def _item(self, path: Path) -> MediaItem:
        try:
            stat = path.stat()
        except OSError as error:
            raise _store_error("read local captured media", error) from error
        content_type = _content_type(path.name)
        return MediaItem(
            id=_host_media_id(path.name),
            name=path.name,
            kind=_media_kind(path.name, content_type),
            size_bytes=stat.st_size,
            capture_time=datetime.fromtimestamp(stat.st_mtime, UTC).isoformat().replace("+00:00", "Z"),
            content_type=content_type,
            preview_available=is_previewable_media(path.name, content_type, stat.st_size),
        )

    def _unique_target(self, name: str) -> Path:
        target = self.root / name
        if not target.exists():
            return target
        stem = Path(name).stem
        suffix = Path(name).suffix
        for index in range(2, 10_000):
            candidate = self.root / f"{stem}-{index}{suffix}"
            if not candidate.exists():
                return candidate
        raise BridgeError(
            "CAPTURE_NAME_EXHAUSTED",
            "Could not allocate a unique filename for captured media.",
            status_code=500,
            feature=CameraFeature.STILL_CAPTURE.value,
            engine=ENGINE_NAME,
        )

    def _require_staging_path(self, staging: Path) -> None:
        expected_parent = (self.root / ".staging").resolve(strict=False)
        if staging.resolve(strict=False).parent != expected_parent:
            raise BridgeError(
                "INVALID_CAPTURE_STAGING",
                "Capture staging path is outside the media store.",
                status_code=500,
            )


def is_host_media_id(media_id: str) -> bool:
    return media_id.startswith(HOST_MEDIA_PREFIX)


def is_previewable_media(name: str, content_type: str, size_bytes: int) -> bool:
    extension = Path(name).suffix.casefold()
    supported_type = content_type.casefold() in {"image/jpeg", "image/png"}
    supported_extension = extension in {".jpg", ".jpeg", ".png"}
    return 0 <= size_bytes <= MAX_LOCAL_PREVIEW_BYTES and supported_type and supported_extension


def preview_content_type(content: bytes) -> str | None:
    if len(content) < 4 or len(content) > MAX_LOCAL_PREVIEW_BYTES:
        return None
    if content.startswith(b"\xff\xd8") and content.endswith(b"\xff\xd9"):
        return "image/jpeg"
    if content.startswith(b"\x89PNG\r\n\x1a\n") and content.endswith(b"\x00\x00\x00\x00IEND\xaeB`\x82"):
        return "image/png"
    return None


def _preview_unavailable() -> BridgeError:
    return BridgeError(
        "MEDIA_PREVIEW_UNAVAILABLE",
        "This media item is not a complete JPEG or PNG image within the 32 MiB preview limit.",
        status_code=422,
        feature=CameraFeature.MEDIA_PREVIEW.value,
        engine=ENGINE_NAME,
    )


def _host_media_id(name: str) -> str:
    encoded = base64.urlsafe_b64encode(name.encode()).decode().rstrip("=")
    return HOST_MEDIA_PREFIX + encoded


def _decode_host_media_id(media_id: str) -> str:
    if not is_host_media_id(media_id):
        raise BridgeError("INVALID_MEDIA_ID", "Media ID does not belong to the local capture store.", status_code=422)
    encoded = media_id.removeprefix(HOST_MEDIA_PREFIX)
    try:
        name = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode()
    except (UnicodeDecodeError, ValueError) as error:
        raise BridgeError("INVALID_MEDIA_ID", "Local media ID is malformed.", status_code=422) from error
    if not name or any(character in name for character in ("/", "\\", "\x00", "\r", "\n")):
        raise BridgeError("INVALID_MEDIA_ID", "Local media ID contains an invalid filename.", status_code=422)
    return name


def _content_type(name: str) -> str:
    extension = Path(name).suffix.casefold()
    canon_types = {
        ".cr2": "image/x-canon-cr2",
        ".cr3": "image/x-canon-cr3",
        ".heif": "image/heif",
        ".heic": "image/heic",
    }
    return canon_types.get(extension) or mimetypes.guess_type(name)[0] or "application/octet-stream"


def _media_kind(name: str, content_type: str) -> str:
    if content_type.startswith("image/"):
        return "image"
    if content_type.startswith("video/"):
        return "video"
    extension = Path(name).suffix.casefold()
    if extension in {".cr2", ".cr3", ".dng"}:
        return "image"
    return "other"


def _store_error(operation: str, error: OSError) -> BridgeError:
    return BridgeError(
        "LOCAL_MEDIA_STORE_FAILED",
        f"Could not {operation}: {error.strerror or error.__class__.__name__}.",
        status_code=500,
        engine=ENGINE_NAME,
    )
