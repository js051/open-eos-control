# Registered local close failure: diagnostic and fail-closed boundary

Status: **implemented with focused synthetic verification; not release acceptance**.

Baseline: `6789e33c3831cf780852f23108c8d1bded25b392`, including the prior `47940ea6` in-flight/rejected-open ownership work and the exact-duplicate admission guard. The following requests use another local engine, so the duplicate-camera guard does not explain the result.

## Production-path evidence

Using the existing validation Python, with this worktree's `bridge` on `PYTHONPATH`:

```text
python -m pytest -o addopts='' -q -p no:cacheprovider \
  bridge/tests/test_registered_close_failure.py
```

Baseline result: **4 red tests** before production changes (the initial diagnostic filename was `test_registered_close_contract_audit.py`). No SDK, physical device, external network request/listener, installation, or permission change was used.

1. Registered libgphoto2 session's `close` raises ordinary `OSError`: DELETE returns 500; the old DELETE and Bulb Stop both return 404. Discovery then returns 200, and the EDSDK engine's actual `open` callback runs and returns 201.
2. The same registered EDSDK failure admits libgphoto2 in exactly the same way. In both cases, independent CCAPI create/status/DELETE still succeeds, with no second close of the failed local session.
3. A real `GPhoto2Session` using the synthetic runner starts owned Bulb. Rejected release leaves the session available for Stop before disconnect. DELETE then returns 502 `SHUTTER_RELEASE_UNCONFIRMED`, leaves the provider object's `_closed=True` and its saved Bulb release present, but removes the API handle. Calling that exact object's `close()` again returns `None` without issuing any runner command or clearing the release obligation. EDSDK nevertheless opens with 201 afterward.
4. `SessionManager.close_all`, the application's shutdown path, preserves the first ordinary error and drains the other local and CCAPI sessions. It then discards every reservation; a later EDSDK request calls the provider and returns 201.

The four original failing assertions now pass. Four additional cases cover other registered Stop/delete plus later CCAPI shutdown, unchanged initial `BaseException` propagation for DELETE and shutdown, and independence from failed CCAPI cleanup. The focused file passes **8 tests**; targeted Ruff also passes.

Final focused compatibility command:

```text
python -m pytest -o addopts='' -q -p no:cacheprovider \
  bridge/tests/test_registered_close_failure.py \
  bridge/tests/test_local_close_reservation.py \
  bridge/tests/test_bulb_request_ownership.py \
  bridge/tests/test_ccapi_bulb_recovery.py::BulbShutdownTests -k 'not real_ccapi'
```

Result: **71 passed, 2 deselected**, with one existing Starlette/httpx deprecation warning. The two synthetic HTTP-peer tests were excluded to avoid network listeners. Targeted Ruff and `git diff --check` passed. This does not claim a full Bridge suite or cross-worker integration run.

## Why retrying close is not a recovery receipt

`GPhoto2Session.close` returns immediately once `_closed` is true. Its first close can set `_closed=True` and then raise a saved release error. It can also preserve the same release error after `_stop_movie_stream` itself fails, attaching a note rather than proving native cleanup. Therefore neither `_closed`, the `SHUTTER_RELEASE_UNCONFIRMED` code alone, nor a subsequent no-op `close()` return distinguishes released USB resources from unknown native ownership.

The generic `CameraEngineSession.close() -> None` contract has no separate local-resource receipt or retryable cleanup-only state. Re-registering the retired object or resuming its retired guard is unsafe: the public handle has been removed, and built-in providers may already reject further commands as closed. This change does not use a subsequent rejected-open or registered-close retry as evidence of cleanup completion; the rejected-open policy is maintained separately.

## Implemented minimal fail-closed boundary

The reviewed implementation uses the existing manager metadata/admission locks. Native close runs outside those locks; a frozen camera key and success flag bind its final result to the original reservation.

1. Keep the existing in-flight reservation through the original close. On successful completion, release only that exact reservation as today. On any ordinary local close exception, retain an explicit failed-close record with the exact retired owner and reservation, independent of active registration.
2. Block new local sessions and discovery using the existing metadata-only preflight and `CAMERA_BUSY`. Distinguish failed/unconfirmed cleanup from still-running cleanup in the message. Do not retry the failed registered owner's `close()` during reconnect, polling, or repeated shutdown, and never clear it based on a later no-op return.
3. Keep the removed handle removed. Existing other registered sessions' Stop/disconnect paths remain untouched; do not blanket-gate `get`, resurrect the failed handle, replay its saved Stop through a replacement session, or weaken retired-guard checks.
4. Continue draining every other registered session after ordinary `Exception` failures during shutdown, retain failed local records, and report the first failure after the drain. `BaseException` preserves immediate propagation and can interrupt the remaining drain, as before. A previously failed owner is not retried. CCAPI failure/drain remains independent and does not quarantine local USB admission.
5. Preserve the original ordinary exception and `BaseException` propagation, including the first ordinary failure on initial shutdown. Repeated shutdown reports bounded `ENGINE_COMMAND_FAILED` without retaining/exposing private exception text or retrying the old close. The message explains that cleanup is unconfirmed and the previous session is no longer available.

## Recovery cost and alternatives

The implemented boundary is deliberately sticky for the lifetime of the manager/process. Even a transient exception blocks all new local USB connections and discovery. With the existing API there is no trustworthy operation that can clear this state: the user must inspect/stop the camera and, when appropriate, repair the provider and restart the Bridge process. Restart only discards process-local quarantine; it is not physical proof that an exposure or camera control stopped.

Before disconnect, explicit Stop can still be retried on the live original session. After failed DELETE, that recovery path is already gone in the present contract; the proposal does not pretend to restore it.

A recoverable alternative requires separate design: retain a cleanup-only exact session, distinguish USB/native-resource completion from physical-control completion, and provide an idempotent cleanup receipt that remains meaningful after normal commands close. Each backend would need to implement and test that contract. A generic second call to today's `close` is insufficient. This larger lifecycle/API/provider-contract change is not proposed for immediate implementation in this audit.

Rejected-provider-open quarantine, ordinary registered-close quarantine, and physical Stop obligations must remain distinct claims. None of this synthetic evidence certifies camera/SDK compatibility, main acceptance, or a release.

## Combined integration — 2026-10-18 04:15 UTC

The registered-close guard was merged with permanent rejected-EDSDK-close quarantine.
Seven affected modules passed 125 tests. Two additional combined cases (DELETE and
shutdown) then verified both retained owners together: original OSError identity,
CCAPI drain timing, both guards retained, and no repeated close. The registered
test client's teardown was also narrowed to catch only __exit__, with a body-error
identity regression. The two changed test modules passed 34 tests; this overlaps
the earlier set, giving **128 distinct executed passing cases**, not 159.
The final body-error regression was rerun after a Ruff-only context formatting fix
and passed. Targeted Ruff and git diff --check passed.

The final combination does not retry either kind of unconfirmed close. Reconnect,
discovery and shutdown cannot turn a later no-op return into a release receipt.
Physical release still requires camera/provider verification outside this synthetic
evidence. Existing active owner cleanup and CCAPI remain independent.
