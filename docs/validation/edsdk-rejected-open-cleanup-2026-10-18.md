# Rejected EDSDK open cleanup ownership

## Scope and baseline

- Baseline: `7d5f994a9ece7bdb959f307c3eb66b0e4b42262f`.
- Scope: the SDK-neutral Bridge adapter and production HTTP admission paths for a returned provider session that fails validation and cannot confirm cleanup.
- Synthetic providers and the existing in-memory gPhoto runner only. No Canon SDK, device, installation, external network request, permission change, version bump, push, merge, or release.

## Red evidence

The new API regression requested camera A, received session B, and made B's `close()` raise a synthetic private exception. The initial response correctly returned HTTP 502 `INVALID_PROVIDER_RESPONSE`, but both a later EDSDK connection and a later gPhoto connection returned HTTP 201 rather than HTTP 409. Both parameterized cases failed on the unmodified baseline.

The manager never registered the invalid session, so its registered-session compatibility check could not reserve the uncertain owner. The adapter's best-effort cleanup discarded the only reference needed for a safe retry.

## Implemented boundary

- Retain the exact rejected object before its first cleanup attempt, including malformed responses with no callable `close`.
- Keep short ownership metadata separate from native cleanup. Known quarantine can be reported while the native close remains blocked; a second connection cannot concurrently close the same object.
- Fail local discovery and connection admission with existing `CAMERA_BUSY`, before automatic selection invokes local provider health/discovery callbacks.
- Preserve the original bounded validation error and never expose private cleanup exception text.
- Retry only the retained object's `close`, on an explicit local connection attempt or shutdown. A successful return clears that exact reservation. Failed or missing cleanup retains it.
- A reconnect only attempts cleanup; it cannot repair a missing `close`. Provider repair and a Bridge process restart may be required, without implying physical-camera cleanup was independently verified.
- Health polling does not call the quarantined provider or retry cleanup. CCAPI connection/control does not depend on or retry local cleanup.
- Existing registered session commands, Bulb/recording Stop, and DELETE remain unchanged.

## Focused evidence

Command, run with the existing validation Python and this worktree's `bridge` on `PYTHONPATH`:

```text
python -m pytest -o addopts='' -q -p no:cacheprovider \
  bridge/tests/test_edsdk_failed_cleanup.py \
  bridge/tests/test_edsdk.py \
  bridge/tests/test_bulb_request_ownership.py -k 'not real_ccapi'
```

Result: **80 passed, 2 deselected**. The two synthetic HTTP-peer tests were excluded to keep this check free of network listeners. One pre-existing Starlette/httpx deprecation warning remained.

Coverage includes local engine and AUTO routing, unchanged successful invalid-session cleanup, exact rejected-owner recovery, shutdown retry, malformed/None responses, no polling retries, fast health/admission during a blocked initial close, non-concurrent blocked retry, retained original-session Stop/DELETE, and independent CCAPI create/info/DELETE through the in-process test client.

Targeted Ruff and `git diff --check` passed.

## Explicit limits

This is implemented synthetic evidence, not physical-camera validation, PR readiness, main acceptance, or a release. A provider's successful `close()` is the existing provider-contract acknowledgment; the Bridge cannot independently certify native cleanup or impose a deadline on a blocked native call.

This change does not implement general cross-session command serialization, quarantine/retry after normal registered-session close failures, or recover resources hidden by a provider that raises from `open` without returning an object. The provider remains responsible for cleaning partially opened resources before raising. In-flight normal-session retirement reservations are a separate change.

## Combined integration, 2026-10-18 04:00 UTC

The rejected-owner guard was merged with the normal in-flight retirement reservation.
A metadata-only retirement preflight runs before saved-owner retry, then is repeated
under local admission before selection/open. A new combined API test publishes a
normal DELETE retirement while rejected cleanup is pending: EDSDK, libgphoto2,
AUTO and discovery all return CAMERA_BUSY without retrying the pending owner or
calling health/discovery/open. Existing release obligations may still overlap a
subsequently published retirement; this is not global provider callback serialization.
Normal retirement does not suppress the independent /health route.

The integrated source passed 92 tests across test_edsdk_failed_cleanup.py,
test_local_close_reservation.py, test_edsdk.py and test_bulb_request_ownership.py,
plus 11 test_session_shutdown.py cases: **103 distinct passed**. Targeted Ruff and
diff whitespace checks passed. The original overlapping worker counts above are
not additive. An initial command named nonexistent test_shutdown.py and collected
no tests; the corrected existing test_session_shutdown.py was executed successfully.
