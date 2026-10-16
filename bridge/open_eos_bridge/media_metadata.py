"""Raw camera metadata evidence. No inference or MIME normalization here."""
from __future__ import annotations

import re

MIME_ALIASES = ("content_type", "contentType", "contenttype", "mimeType", "mimetype")


def declared_media_mime(value: dict[str, object]) -> str | None:
    declarations = [value[key] for key in MIME_ALIASES if key in value]
    if any(item is not None and not isinstance(item, str) for item in declarations):
        raise ValueError("Media MIME declaration must be a string or null")
    if declarations and any(item != declarations[0] for item in declarations[1:]):
        raise ValueError("Media MIME aliases disagree")
    return declarations[0] if declarations else None


def media_response_content_type(value: str | None) -> str:
    """Render an HTTP header safely; never write this fallback into metadata."""
    if value is None or re.search(r"[\x00-\x1f\x7f-\x9f]", value):
        return "application/octet-stream"
    mime = value.split(";", 1)[0].strip()
    if not re.fullmatch(r"[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+", mime):
        return "application/octet-stream"
    return mime
