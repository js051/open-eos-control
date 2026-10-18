# Rejected EDSDK open cleanup ownership

## Current scope and contract correction

- Current correction baseline: `6789e33c`.
- Scope: `edsdk.py`, its rejected-cleanup tests, the provider behavior documentation, and this evidence note. No SessionManager/registry behavior change in this batch.
- Synthetic providers and the existing in-memory gPhoto runner only. No Canon SDK, physical device, installation, external network request, permission change, version bump, push, merge, or release.

The earlier implementation retried a rejected owner's failed `close()` during reconnect or shutdown and treated a later normal return as release confirmation. That inference was unsupported. `edsdk_contract.py` returns `CameraEngineSession`, whose contract declares only `close() -> None`; it defines no retry-after-failure receipt. A session may mark itself closed, fail cleanup, and make every later close an idempotent no-op. Earlier green retry-oriented tests modeled an assumption rather than proving the contract.

The retry behavior is removed. Once the first rejected-session cleanup fails, is missing, or is interrupted, its exact owner stays quarantined for the lifetime of this Bridge engine/process. Reconnect and shutdown cannot re-invoke close or clear that quarantine. This correction does not introduce a new provider protocol.

## Red evidence

At `6789e33c`, a production FastAPI regression used a wrong-camera provider session whose first close raised and whose second close would return `None` without confirming release. The initial POST returned HTTP 502 `INVALID_PROVIDER_RESPONSE`. A subsequent libgphoto2 POST returned HTTP 201 instead of the required HTTP 409 because the adapter retried the old close and cleared its reservation.

The same regression now receives HTTP 409 `CAMERA_BUSY`. The retained object's close count stays exactly one through reconnect and shutdown; the second no-op is never called.

## Implemented boundary

- Retain the exact rejected object before its first cleanup attempt, including malformed/None responses with no callable close.
- Keep short ownership metadata separate from the potentially blocking first native close. Health and local admission report the pending owner without starting another cleanup.
- If the first close returns successfully, clear the reservation as before and retain the initial validation error. This preserves the existing single-cleanup flow; it is not independent physical-device evidence.
- If the first close raises an ordinary exception or lacks a callable close, preserve the original bounded validation error and retain quarantine. Private native exception text is not exposed.
- If first cleanup is interrupted by KeyboardInterrupt, SystemExit, or cancellation, propagate that interruption while preserving quarantine.
- Keep the private `retry` parameter for existing callers, but it performs no retry. Every pending-owner preflight simply returns the existing `CAMERA_BUSY` failure.
- Differentiate in-progress first cleanup from terminal quarantine in the error text. A terminal quarantine tells the user reconnect cannot clear it and to check the camera/provider state before restarting Bridge. Restart is not asserted to repair or verify native cleanup.
- Local EDSDK, libgphoto2, AUTO and discovery remain blocked through existing registry/manager preflights. Health does not query the quarantined EDSDK provider. Polling, reconnect and shutdown never replay close.
- CCAPI connection/control remains independent. Existing registered sessions can still issue Bulb/recording Stop and DELETE.

## Focused evidence

The existing validation Python ran this worktree's source via `PYTHONPATH=bridge`:

```text
python -m pytest -q -p no:cacheprovider bridge/tests/test_edsdk_failed_cleanup.py
```

Result: **23 passed**. Coverage includes the first-failure/second-no-op regression; reconnect and shutdown permanence; concurrent reconnects; missing/None cleanup; propagated first-close interruptions; blocked initial cleanup; successful first cleanup; unchanged private-error handling; original-session Stop/DELETE; and independent CCAPI create/info/DELETE; and a fixture regression proving that a test-body BridgeError is not consumed as an expected shutdown error. The fixture catches `CAMERA_BUSY` only around `TestClient.__exit__`, never around the test body.

Separate adjacent checks, without rerunning the above cases:

```text
python -m pytest -o addopts='' -q -p no:cacheprovider \
  bridge/tests/test_edsdk.py \
  bridge/tests/test_bulb_request_ownership.py \
  bridge/tests/test_session_shutdown.py \
  bridge/tests/test_local_close_reservation.py \
  bridge/tests/test_local_duplicate_admission.py -k 'not real_ccapi'
```

Result: **92 passed, 2 deselected**. The two synthetic HTTP-peer tests were excluded to avoid starting network listeners. These are **115 distinct passed** across the two final runs, not additive with historical verification counts. The pre-existing Starlette/httpx deprecation warning remains; no dependency was installed or replaced.

Targeted Ruff and `git diff --check` passed.

## Explicit limits and delivery state

This is implemented synthetic evidence, not physical-camera validation, PR readiness, main acceptance, or a release. It cannot independently certify native cleanup or impose a deadline on a blocked native call.

This batch does not implement normal registered-session close-failure recovery, general cross-session callback serialization, or cleanup of resources hidden by a provider that raises from open without returning an object. The provider remains responsible for partially opened resources it never returns. Normal in-flight retirement admission and active exact-camera duplicate admission remain separate existing changes.

Product impact is a backward-compatible safety correction appropriate for a patch release if subsequently included in an authorized release. No version or release action was performed.
