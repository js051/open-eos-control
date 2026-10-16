"""Exact ordinary-setting observation, independent from command-response evidence."""
from __future__ import annotations

import re

from .errors import BridgeError
from .models import CameraSetting


def valid_string(value: object, *, maximum: int = 512) -> bool:
    return (isinstance(value, str) and len(value) <= maximum
            and not any(ord(char) < 32 or 0x7F <= ord(char) <= 0x9F or 0xD800 <= ord(char) <= 0xDFFF
                        for char in value))


def valid_key(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[a-z][a-z0-9._-]{0,63}", value) is not None


def exact_setting(setting: CameraSetting, key: str) -> CameraSetting:
    if (setting.key != key or not valid_key(key) or not valid_string(setting.label)
            or not valid_string(setting.value)):
        raise ValueError("Invalid exact setting identity or value")
    if setting.input_kind == "text":
        if (setting.values or type(setting.max_length) is not int or not 1 <= setting.max_length <= 255
                or len(setting.value) > setting.max_length
                or any(not 32 <= ord(char) <= 126 for char in setting.value)):
            raise ValueError("Invalid exact text setting")
    elif (setting.input_kind != "choice" or setting.max_length is not None
          or not 1 <= len(setting.values) <= 1024 or len(set(setting.values)) != len(setting.values)
          or any(not valid_string(value) or not value for value in setting.values)
          or setting.value not in setting.values):
        raise ValueError("Invalid exact choice setting")
    return setting


def require_requested_value(setting: CameraSetting, value: str) -> None:
    valid = valid_string(value)
    if setting.input_kind == "text":
        valid = valid and len(value) <= setting.max_length and all(32 <= ord(char) <= 126 for char in value)
    else:
        valid = valid and value in setting.values
    if not valid:
        raise BridgeError("INVALID_SETTING_VALUE", "Value is not a current advertised setting choice.", status_code=422)


def observation(engine: str, setting: CameraSetting) -> dict[str, object]:
    exact_setting(setting, setting.key)
    return {"version": 1, "engine": engine, "setting": setting.model_dump(by_alias=True)}


def responded(engine: str, key: str, value: str, read) -> dict[str, object]:
    """Call only after a complete successful command transport response."""
    result: dict[str, object] = {
        "version": 1, "engine": engine, "key": key, "requestedValue": value,
        "writeEvidence": "response-received", "readbackEvidence": "unavailable", "setting": None,
    }
    try:
        setting = exact_setting(read(), key)
    except Exception:
        # A later read/parse/closed-session failure cannot erase a completed response.
        return result
    result["readbackEvidence"] = "matched" if setting.value == value else "mismatch"
    result["setting"] = setting.model_dump(by_alias=True)
    return result
