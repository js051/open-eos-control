# Maintenance Live View restore ownership

This is a backward-compatible safety correction to the existing maintenance
restore journey. It does not add a new camera feature or a physical-camera
validation claim.

## Additive Bridge contract

All controls below remain under `/v1/session/{id}/maintenance/control/liveview`.
The token is a session-local, one-shot permit issued by Bridge, not a camera
credential. Requests use a URL-safe string matching `[A-Za-z0-9_-]{24,64}`;
Bridge currently generates 32 characters. Never move a token to another session.

1. Initial `POST stop` sends `{"prepareRestore":true}` only when the caller plans
   to restore the actual preview after non-power-off sensor cleaning. Bridge
   freezes the actual source, size and requested FPS, performs the existing strict
   Stop, and issues `restoreToken` only after affirmative Stop and an available
   original source. An idle Stop succeeds without a token. No token is issued for
   a failed or unconfirmed initial Stop.
2. `POST start` adds `restoreToken` to the existing Start body. Source/size/FPS in
   that body cannot replace the frozen source. Success echoes the token and
   `restoreOutcome:"started"`, with `active:true` and
   `liveViewStopUnconfirmed:false`. A reused, cancelled, retired or other-session
   token fails; Start is never replayed automatically.
3. Owned cleanup `POST stop` sends `{"restoreToken":"<saved token>"}`. The first
   performed Stop echoes `restoreOutcome:"stopped"`, `active:false` and
   `liveViewStopUnconfirmed:false`. Failure retains the obligation for explicit
   Stop retry. Cleanup bypasses the owned-Bulb new-mutation gate, but shares its
   execution lane so it cannot overtake an already dispatched Start.
4. If Stop cancels a prepared/queued Start, it returns only the token and
   `restoreOutcome:"cancelled-before-start"`. A repeated terminal Stop or a token
   displaced by an explicit ordinary Stop returns only its terminal receipt.
   These responses omit `active` and `liveViewStopUnconfirmed`; they contain no
   fresh current-camera-state evidence. They do not stop a newer unrelated
   preview or clear a newer Live View/AF warning.
5. `POST stop` with `{}` or no body remains explicit global maintenance cleanup.
   It revokes owned permits before waiting for execution, including a prepare
   whose response/token was lost. It creates no owned permit. The ordinary
   `/liveview/stop` route also revokes prepared/queued permits and runs its original
   provider Stop, preserving its autofocus cleanup and error behavior.

`prepareRestore:true` and `restoreToken` cannot be combined. Unknown fields,
non-boolean prepare flags and malformed or explicitly null tokens are rejected
before camera I/O. Only an omitted token selects legacy/global behavior.
Responses are `Cache-Control: no-store`.

## Caller obligations

Capture the token only from the original successful, still-current initial Stop.
Freeze that same token into the Start/cleanup owner before Start can reach Bridge.
Require an exact token echo and the expected outcome. Retain owner identity and
warning revision across retries. A terminal receipt can discharge only that
exact Start obligation when no newer warning superseded it; it cannot be treated
as a fresh all-clear. An explicit later global Stop can obtain fresh Stop evidence.

When an actual prior preview needs restoration, missing/invalid token means fail
closed before Start. Restoring a journey that had no preview remains local and
does not require a token. A timed-out/cancelled client request is not evidence that
a dispatched camera operation stopped; retain explicit Stop recovery.

A new prepare cannot replace a prepared, queued or unconfirmed dispatched permit.
After a confirmed restore, a later maintenance journey may perform one initial
Stop on that active preview: only a successful physical Stop retires the old token
and issues the next permit. Failed Stop keeps the old owner, with no replacement.
The session table stores at most 256 permits without evicting replay tombstones;
at capacity, explicit global Stop remains available, then reconnect.

## Compatibility and retirement

Existing no-token Stop → Start callers retain their wire contract and frozen-source
behavior. Their admitted Start and cleanup Stop now share serialization. They do
not have the request-identifiable guarantee for a Start that has not reached
Bridge across separate legacy journeys. Token-aware PC/Android migration must be
validated separately; this server correction does not claim iOS migration or
native iOS acceptance.

Session deletion/retirement fences queued permits and exact session objects.
Failed local-only retirement preserves explicit Stop recovery; cancellation
history is not rolled back. `DELETE maintenance/control/session` remains local
retirement, including its existing pending-Live-View warning behavior. Its 204
response never proves physical Stop and never sends a hidden remote cleanup.

## Verification and release assessment

`bridge/tests/test_maintenance_restore_ownership.py` uses production FastAPI routes,
SessionManager, admission locks and CCAPI lifecycle code with a synthetic camera
transport. It covers the original admitted-Start/Stop ordering counterexample,
Stop-before-arrival/queued dispatch, immutable frozen source, replay, lost replies,
terminal receipts versus newer preview/warnings, ordinary Stop retirement, bounded
capacity, repeated journeys, owned Bulb cleanup admission and local retirement.
The legacy ordering test fails against the pre-fix production implementation at
`ab607b0a24da2a6c92487ec252e43b386acb37e4` because Stop overtakes admitted Start.

Run focused verification with:

```sh
PYTHONPATH=bridge python -m pytest bridge/tests/test_maintenance_restore_ownership.py \
  bridge/tests/test_maintenance_lifecycle.py bridge/tests/test_bulb_request_ownership.py \
  bridge/tests/test_bulb_cross_control_ownership.py bridge/tests/test_ccapi_live_view_recovery.py \
  bridge/tests/test_focus_contract.py bridge/tests/test_session_shutdown.py
```

Release impact: patch, fixing an existing restore/cleanup ordering hazard with
additive protocol fields. No version change, main merge or release is included.
Exact-SHA integrated CI and caller-specific validation remain separate gates.
Physical camera validation remains pending.
