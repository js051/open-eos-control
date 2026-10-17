"""One-shot restore permits bound to an exact Bridge session and frozen source.

Receipts acknowledge only their token. Cached/cancelled receipts deliberately
carry no current camera state. Physical Stop remains the provider's obligation;
local retirement fences dispatch but does not claim camera cleanup.
"""
from __future__ import annotations

import secrets
import threading
from dataclasses import dataclass

from .bulb import BulbRequests
from .errors import BridgeError, unsupported
from .models import LiveViewStartRequest, LiveViewState, MaintenanceLiveViewState

MAX_MAINTENANCE_RESTORE_TOKENS = 256
TERMINAL = {"stopped", "cancelled-before-start"}


@dataclass(frozen=True)
class FrozenRestore:
    source: str
    size: str
    fps: int

    def request(self) -> LiveViewStartRequest:
        return LiveViewStartRequest(source=self.source, size=self.size, fps=self.fps)


@dataclass
class RestorePermit:
    frozen: FrozenRestore
    state: str = "prepared"


class MaintenanceLiveViewRequests:
    def __init__(self, camera: BulbRequests) -> None:
        self.camera = camera
        self.session = camera.session
        self._metadata = threading.Lock()
        self._retired = False
        self._revision = 0
        self._preparing = False
        self._current: str | None = None
        self._permits: dict[str, RestorePermit] = {}

    def _error(self, code: str, message: str) -> BridgeError:
        return BridgeError(code, message, status_code=409, engine=self.session.engine_name,
                           feature="LIVE_VIEW")

    def _require_open(self, revision: int | None = None) -> None:
        if self._retired or (revision is not None and revision != self._revision):
            raise self._error("MAINTENANCE_RESTORE_RETIRED", "This maintenance restore was cancelled or retired.")

    def _operation(self, name: str):
        operation = getattr(self.session, name, None)
        if not callable(operation):
            raise unsupported("MAINTENANCE_CONTROLS", self.session.engine_name)
        return operation

    def _receipt(self, token: str, permit: RestorePermit) -> MaintenanceLiveViewState:
        return MaintenanceLiveViewState(restore_token=token, restore_outcome=permit.state)

    def _stopped(self, token: str | None = None) -> MaintenanceLiveViewState:
        if getattr(self.session, "live_view_stop_unconfirmed", None) is not False:
            raise self._error("LIVE_VIEW_STOP_UNCONFIRMED", "Live View Stop is unconfirmed. Retry Stop.")
        return MaintenanceLiveViewState(active=False, live_view_stop_unconfirmed=False,
                                        restore_token=token, restore_outcome="stopped" if token else None)

    def _cancel_permits(self) -> None:
        for permit in self._permits.values():
            if permit.state in {"prepared", "queued"}:
                permit.state = "cancelled-before-start"
        if self._current is not None and self._permits[self._current].state in TERMINAL:
            self._current = None

    def stop(self, *, prepare_restore: bool = False, restore_token: str | None = None) -> MaintenanceLiveViewState:
        if restore_token is not None:
            return self._stop_owned(restore_token)
        with self._metadata:
            self._require_open()
            if prepare_restore:
                # A confirmed restored preview can begin another journey, but
                # the old permit is retired only after this physical Stop succeeds.
                unresolved = self._current is not None and self._permits[self._current].state != "started"
                if self._preparing or unresolved:
                    raise self._error("MAINTENANCE_RESTORE_BUSY", "Resolve the previous restore with Stop first.")
                if len(self._permits) >= MAX_MAINTENANCE_RESTORE_TOKENS:
                    raise self._error("MAINTENANCE_RESTORE_LIMIT", "Restore capacity reached. Stop, then reconnect.")
                self._preparing = True
            # A no-token Stop is global cleanup. Revoke permits before waiting for
            # a dispatched provider call, including a prepare whose reply is lost.
            self._revision += 1
            revision = self._revision
            self._cancel_permits()
        try:
            with self.camera.cleanup():
                with self._metadata:
                    self._require_open(revision)
                operation = self._operation("maintenance_stop_owned_live_view" if prepare_restore
                                            else "maintenance_stop_live_view")
                frozen = operation()
                result = self._stopped()
                with self._metadata:
                    self._require_open(revision)
                    if self._current is not None:
                        self._permits[self._current].state = "stopped"
                        self._current = None
                    if prepare_restore and isinstance(frozen, LiveViewStartRequest) and frozen.source != "AUTO":
                        token = secrets.token_urlsafe(24)
                        self._permits[token] = RestorePermit(FrozenRestore(frozen.source, frozen.size, frozen.fps))
                        self._current = token
                        result.restore_token = token
                    return result
        finally:
            if prepare_restore:
                with self._metadata:
                    self._preparing = False

    def stop_ordinary(self) -> LiveViewState:
        """Preserve ordinary provider Stop (including AF cleanup), fencing restores."""
        with self._metadata:
            self._require_open()
            self._revision += 1
            revision = self._revision
            self._cancel_permits()
        with self.camera.cleanup():
            with self._metadata:
                self._require_open(revision)
            self.session.stop_live_view()
            unconfirmed = getattr(self.session, "live_view_stop_unconfirmed", None)
            with self._metadata:
                if unconfirmed is False and self._current is not None:
                    self._permits[self._current].state = "stopped"
                    self._current = None
            return LiveViewState(active=False, live_view_stop_unconfirmed=unconfirmed)

    def start(self, request: LiveViewStartRequest, restore_token: str | None = None) -> MaintenanceLiveViewState:
        with self._metadata:
            self._require_open()
            revision = self._revision
            if restore_token is not None:
                permit = self._permits.get(restore_token)
                if permit is None:
                    raise self._error("MAINTENANCE_RESTORE_UNKNOWN", "Unknown restore token for this camera session.")
                if permit.state != "prepared":
                    raise self._error("MAINTENANCE_RESTORE_REPLAY",
                                      "This restore token cannot start again. Retry Stop.")
                permit.state = "queued"
        with self.camera.mutation():
            with self._metadata:
                self._require_open(revision)
                if restore_token is not None:
                    permit = self._permits[restore_token]
                    if permit.state != "queued":
                        raise self._error("MAINTENANCE_RESTORE_REPLAY", "Stop cancelled this restore before dispatch.")
                    permit.state = "dispatched"
                    request = permit.frozen.request()
            if restore_token is None:
                self._operation("maintenance_start_live_view")(request)
            else:
                self._operation("maintenance_start_owned_live_view")(request, restore_token)
            if restore_token is not None and (getattr(self.session, "live_view_active", None) is not True
                    or getattr(self.session, "live_view_stop_unconfirmed", None) is not False):
                raise self._error("LIVE_VIEW_STOP_UNCONFIRMED", "Restore Start is unconfirmed. Retry Stop.")
            with self._metadata:
                if restore_token is not None:
                    # A concurrently requested Stop still executes after this call.
                    self._permits[restore_token].state = "started"
            return MaintenanceLiveViewState(
                active=True, requested_fps=getattr(self.session, "requested_fps", min(request.fps, 5)),
                source=getattr(self.session, "live_view_source", request.source),
                size=getattr(self.session, "live_view_size", None),
                live_view_stop_unconfirmed=getattr(self.session, "live_view_stop_unconfirmed", None),
                restore_token=restore_token, restore_outcome="started" if restore_token else None)

    def _stop_owned(self, token: str) -> MaintenanceLiveViewState:
        with self._metadata:
            self._require_open()
            revision = self._revision
            permit = self._permits.get(token)
            if permit is None:
                raise self._error("MAINTENANCE_RESTORE_UNKNOWN", "Unknown restore token for this camera session.")
            if permit.state in {"prepared", "queued"}:
                permit.state = "cancelled-before-start"
                if self._current == token:
                    self._current = None
            if permit.state in TERMINAL:
                return self._receipt(token, permit)
        with self.camera.cleanup():
            with self._metadata:
                self._require_open(revision)
                if permit.state in TERMINAL:
                    return self._receipt(token, permit)
            # False means this provider owner was already stopped/displaced. Never
            # stop the new source or turn an old receipt into fresh status evidence.
            performed = self._operation("maintenance_stop_owned_live_view")(token)
            result = self._stopped(token) if performed is True else None
            with self._metadata:
                permit.state = "stopped"
                if self._current == token:
                    self._current = None
                return result or self._receipt(token, permit)

    def retire(self) -> None:
        with self._metadata:
            self._retired = True
            self._revision += 1
            self._cancel_permits()

    def resume_after_failed_retirement(self) -> None:
        with self._metadata:
            self._retired = False
