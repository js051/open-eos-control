"""Session-local admission for bounded, non-replayable desktop Bulb requests.

The provider remains responsible for its saved camera release operation. This
guard orders requests at the Bridge boundary; it never infers physical state.
"""
from __future__ import annotations

import threading
from collections.abc import Callable
from contextlib import contextmanager

from .engine import CameraEngineSession
from .errors import BridgeError
from .models import BulbOwnerResult, CameraFeature, CameraStatus

MAX_BULB_OWNERS = 256
BULB_EXECUTION_WAIT_SECONDS = 15.0


class BulbRequests:
    def __init__(self, session: CameraEngineSession) -> None:
        self.session = session
        self._metadata = threading.Lock()
        self._execution = threading.Lock()
        self.retirement_lock = threading.Lock()
        self._retired = False
        self._retirement_revision = 0
        self._owners: dict[str, str] = {}
        self._active_owner: str | None = None
        self._legacy_duty = False

    def _error(self, code: str, message: str, status_code: int = 409) -> BridgeError:
        return BridgeError(code, message, status_code=status_code,
                           feature=CameraFeature.BULB_EXPOSURE.value, engine=self.session.engine_name)

    def _require_open(self, revision: int | None = None) -> None:
        if self._retired or (revision is not None and revision != self._retirement_revision):
            raise self._error("BULB_SESSION_RETIRED", "This Bulb request belongs to a retired camera session.")

    def _acquire_execution(self) -> None:
        if not self._execution.acquire(timeout=BULB_EXECUTION_WAIT_SECONDS):
            raise self._error("BULB_OWNER_PENDING", "The earlier camera operation is still pending. Retry Stop.")

    @contextmanager
    def _executing(self):
        self._acquire_execution()
        try:
            yield
        finally:
            self._execution.release()

    @staticmethod
    def _released(status: CameraStatus) -> bool:
        return (getattr(status, "bulb_exposure_active", None) is False
                and getattr(status, "shutter_release_unconfirmed", None) is False)

    def start(self, owner_id: str | None = None) -> CameraStatus | BulbOwnerResult:
        if owner_id is None:
            with self._metadata:
                self._require_open()
                revision = self._retirement_revision
            with self._executing():
                with self._metadata:
                    self._require_open(revision)
                    if self._active_owner is not None:
                        raise self._error("BULB_OWNER_BUSY", "Resolve the current owner's Bulb operation with Stop.")
                    self._legacy_duty = True
                return self.session.start_bulb_exposure()

        with self._metadata:
            self._require_open()
            revision = self._retirement_revision
            if owner_id in self._owners:
                raise self._error("BULB_OWNER_REPLAY", "This Bulb owner was already used. Start cannot be replayed.")
            if len(self._owners) >= MAX_BULB_OWNERS:
                raise self._error("BULB_OWNER_LIMIT", "Bulb owner capacity reached. Resolve Stop, then reconnect.")
            if self._active_owner is not None or self._legacy_duty:
                # Even a refused owner must not become an unexpected later Start.
                self._owners[owner_id] = "cancelled-before-start"
                raise self._error("BULB_OWNER_BUSY", "Resolve the current Bulb operation with Stop.")
            self._owners[owner_id] = "queued"
            self._active_owner = owner_id

        with self._executing():
            with self._metadata:
                self._require_open(revision)
                if self._owners[owner_id] != "queued":
                    raise self._error("BULB_OWNER_REPLAY", "Stop cancelled this owner before Start was dispatched.")
                # Stop can now only resolve this owner after this serialized call.
                self._owners[owner_id] = "dispatched"
            status = self.session.start_bulb_exposure()
            if getattr(status, "bulb_exposure_active", None) is not True:
                raise self._error("BULB_START_UNCONFIRMED", "The Bulb Start result is unconfirmed. Retry Stop.", 502)
            with self._metadata:
                self._owners[owner_id] = "started"
            return BulbOwnerResult(owner_id=owner_id, outcome="started", status=status)

    def stop(self, owner_id: str | None = None) -> CameraStatus | BulbOwnerResult:
        with self._metadata:
            self._require_open()
            revision = self._retirement_revision
            if owner_id is not None:
                previous = self._owners.get(owner_id)
                if previous in {"released", "cancelled-before-start"}:
                    return BulbOwnerResult(owner_id=owner_id, outcome=previous)
                if previous is None or previous == "queued":
                    # This metadata change does not wait for another provider call.
                    # A full table rejects every unseen Start forever, so an unknown
                    # Stop needs no allocation once the fail-closed ceiling is reached.
                    if previous is not None or len(self._owners) < MAX_BULB_OWNERS:
                        self._owners[owner_id] = "cancelled-before-start"
                    if self._active_owner == owner_id:
                        self._active_owner = None
                    return BulbOwnerResult(owner_id=owner_id, outcome="cancelled-before-start")
            elif self._active_owner is not None and self._owners[self._active_owner] == "queued":
                self._owners[self._active_owner] = "cancelled-before-start"
                self._active_owner = None

        with self._executing():
            with self._metadata:
                self._require_open(revision)
                if owner_id is not None:
                    previous = self._owners[owner_id]
                    if previous in {"released", "cancelled-before-start"}:
                        return BulbOwnerResult(owner_id=owner_id, outcome=previous)
                current_owner = self._active_owner
            status = self.session.stop_bulb_exposure()
            released = self._released(status)
            with self._metadata:
                if released:
                    self._legacy_duty = False
                    if current_owner is not None:
                        self._owners[current_owner] = "released"
                        if self._active_owner == current_owner:
                            self._active_owner = None
            if owner_id is None:
                # Preserve the exact legacy status contract, including non-reporting providers.
                return status
            if not released:
                raise self._error("SHUTTER_RELEASE_UNCONFIRMED", "Camera release is unconfirmed. Retry Stop.", 502)
            return BulbOwnerResult(owner_id=owner_id, outcome="released", status=status)

    def retire(self) -> None:
        with self._metadata:
            self._retired = True
            self._retirement_revision += 1
            if self._active_owner is not None and self._owners[self._active_owner] == "queued":
                self._owners[self._active_owner] = "cancelled-before-start"
                self._active_owner = None

    def resume_after_failed_retirement(self) -> None:
        # A refused local-only maintenance retirement must leave explicit Stop
        # usable. The revision and queued-owner tombstones are never rolled back.
        with self._metadata:
            self._retired = False

    def finish_retirement(self, operation: Callable[[], None]) -> None:
        # Retirement has already fenced queued requests. Keep serialization through
        # the provider's final cleanup, even if its caller stopped waiting for HTTP.
        with self._execution:
            operation()
