"""Independent maintenance response and clock-observation evidence."""
from __future__ import annotations

from .errors import BridgeError

OPERATIONS = frozenset({"clock-sync", "sensor-cleaning", "sleep"})
MIN_EPOCH = -62135596800
MAX_EPOCH = 253402300799


def exact_integer(value: object, minimum: int, maximum: int) -> bool:
    return type(value) is int and minimum <= value <= maximum


def valid_zone(value: dict) -> bool:
    offset, daylight = value.get("utcOffsetMinutes"), value.get("daylightSaving")
    return ((offset is None and daylight is None and "utcOffsetMinutes" in value and "daylightSaving" in value)
            or (exact_integer(offset, -1439, 1439) and type(daylight) is bool))


def valid_clock(value: object) -> bool:
    return (isinstance(value, dict) and exact_integer(value.get("epochSeconds"), MIN_EPOCH, MAX_EPOCH)
            and valid_zone(value))


def valid_reference(value: object) -> bool:
    return (isinstance(value, dict) and valid_zone(value)
            and exact_integer(value.get("earliestEpochSeconds"), MIN_EPOCH, MAX_EPOCH)
            and exact_integer(value.get("latestEpochSeconds"), MIN_EPOCH, MAX_EPOCH)
            and 0 <= value["latestEpochSeconds"] - value["earliestEpochSeconds"] <= 120)


def clock_matches(reference: object, observed: object) -> bool:
    return (valid_reference(reference) and valid_clock(observed)
            and reference["earliestEpochSeconds"] <= observed["epochSeconds"] <= reference["latestEpochSeconds"]
            and (reference["utcOffsetMinutes"] is None
                 or (reference["utcOffsetMinutes"] == observed["utcOffsetMinutes"]
                     and reference["daylightSaving"] is observed["daylightSaving"])))


def require_intent(operation: str, auto_power_off: bool) -> None:
    if (operation not in OPERATIONS or type(auto_power_off) is not bool
            or (operation != "sensor-cleaning" and auto_power_off)):
        raise BridgeError("INVALID_MAINTENANCE_INTENT", "Invalid maintenance action or power-off intent.",
                          status_code=422)


def response_received(engine: str, operation: str, auto_power_off: bool, reference=None, read=None) -> dict:
    require_intent(operation, auto_power_off)
    result = {"version": 1, "engine": engine, "operation": operation, "autoPowerOff": auto_power_off,
              "responseEvidence": "response-received", "clockReference": None, "clockObservation": None,
              "clockReadEvidence": "unavailable" if operation == "clock-sync" else "not-applicable"}
    if operation != "clock-sync":
        return result
    if not valid_reference(reference):
        return result
    result["clockReference"] = reference
    try:
        observed = read()
        if not valid_clock(observed):
            return result
        result["clockObservation"] = observed
        result["clockReadEvidence"] = "matched" if clock_matches(reference, observed) else "mismatch"
    except Exception:
        # The later read cannot erase the complete original command response.
        pass
    return result


def observation(engine: str, operation: str, clock=None, *, reachable: bool = False) -> dict:
    if operation not in OPERATIONS:
        raise BridgeError("INVALID_MAINTENANCE_INTENT", "Unknown maintenance operation.", status_code=422)
    valid = operation == "clock-sync" and valid_clock(clock)
    return {"version": 1, "engine": engine, "operation": operation,
            "observationEvidence": "observed" if valid else "reachable" if reachable and operation != "clock-sync"
            else "unavailable", "clockObservation": clock if valid else None}
