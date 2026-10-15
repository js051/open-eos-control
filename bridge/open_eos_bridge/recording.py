"""Recording command receipts are independent of physical state observations."""
from __future__ import annotations

from .errors import BridgeError

SOURCES = frozenset({'ccapi-event', 'ccapi-simulator', 'usb-evf-record-status', 'gphoto-config'})
ENGINE_SOURCES = {'ccapi': {'ccapi-event', 'ccapi-simulator'}, 'libgphoto2': {'gphoto-config'},
                  'usb-ptp': {'usb-evf-record-status'}}
RESPONSES = frozenset({'response-received', 'not-sent', 'unconfirmed', 'rejected'})


def require_operation(operation: str) -> None:
    if operation not in {'start', 'stop'}:
        raise BridgeError('INVALID_RECORDING_OPERATION', 'Unknown recording command.', status_code=422)


def observation(engine: str, recording=None, source=None, *, failed=False) -> dict:
    observed = type(recording) is bool and isinstance(source, str) and source in ENGINE_SOURCES.get(engine, set())
    return {'version': 1, 'engine': engine,
            'observationEvidence': 'observed' if observed else 'read-failed' if failed else 'unavailable',
            'recording': recording if observed else None, 'source': source if observed else None}


def valid_observation(value: object, engine: str) -> bool:
    if (not isinstance(value, dict) or set(value) != {'version', 'engine', 'observationEvidence', 'recording', 'source'}
            or type(value.get('version')) is not int or value['version'] != 1
            or value.get('engine') != engine):
        return False
    kind = value.get('observationEvidence')
    if not isinstance(kind, str):
        return False
    if kind == 'observed':
        return (type(value.get('recording')) is bool and isinstance(value.get('source'), str)
                and value.get('source') in ENGINE_SOURCES.get(engine, set()))
    return (kind in {'unavailable', 'read-failed'} and 'recording' in value and 'source' in value
            and value['recording'] is None and value['source'] is None)


def outcome(engine: str, operation: str, response: str, observed: dict | None = None) -> dict:
    require_operation(operation)
    if response not in RESPONSES:
        raise ValueError('Invalid recording response evidence')
    return {'version': 1, 'engine': engine, 'operation': operation, 'responseEvidence': response,
            'observation': observed if valid_observation(observed, engine) else observation(engine)}


def ownership(value: dict) -> dict:
    publish = (value.get('sameOwner') is True and value.get('sameAttempt') is True
               and value.get('cancelled') is not True and value.get('retired') is not True)
    duty = value.get('priorStopRequired') is True
    fresh = value.get('observationEvidence') == 'observed' and type(value.get('recording')) is bool
    if publish:
        if fresh:
            duty = value['recording']
        elif value.get('responseEvidence') == 'response-received':
            duty = value.get('operation') == 'start'
        elif value.get('operation') == 'start' and value.get('responseEvidence') == 'unconfirmed':
            duty = True
    elif (value.get('sameOwner') is True and value.get('sameAttempt') is True
          and value.get('operation') == 'start'
          and value.get('responseEvidence') in {'response-received', 'unconfirmed'}):
        duty = True
    return {'canPublish': publish, 'stopRequired': duty,
            'canReview': publish and value.get('operation') == 'stop' and fresh and value['recording'] is False,
            'retryStart': False}
