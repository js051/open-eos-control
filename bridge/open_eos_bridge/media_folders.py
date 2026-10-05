from __future__ import annotations

import hashlib

MAX_FOLDER_ID_LENGTH = 4096
MAX_FOLDER_LABEL_LENGTH = 1024


def optional_folder_value(value: object, maximum_length: int) -> str | None:
    """Discard unusable optional provenance without rejecting the media item."""
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > maximum_length
        or sum(2 if ord(character) > 0xFFFF else 1 for character in value) > maximum_length
        or any(ord(character) < 32 or 127 <= ord(character) <= 159 for character in value)
    ):
        return None
    return value


def camera_folder_fields(source: str, folder: str, label: str) -> dict[str, str]:
    """Build display-only provenance from a provider-observed camera folder."""
    if optional_folder_value(folder, MAX_FOLDER_ID_LENGTH) is None:
        return {}
    if optional_folder_value(label, MAX_FOLDER_LABEL_LENGTH) is None:
        return {}
    try:
        digest = hashlib.sha256(folder.encode("utf-8")).hexdigest()
    except UnicodeEncodeError:
        return {}
    return {"folder_id": f"{source}:{digest}", "folder_label": label}
