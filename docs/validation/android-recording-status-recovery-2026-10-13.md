# Android REC acknowledgement and status recovery

Base: PR #240, `705205c2fc9b3160a8e46c9a244069148da0f497`.

## Contract

- Desktop Bridge maps only the exact `RECORDING_STATUS_READBACK_FAILED` code to typed acknowledgement/readback recovery. Separately, the exact HTTP 502 / `RECORDING_COMMAND_UNCONFIRMED` / `VIDEO_RECORDING` / `ccapi` tuple means the command result is unknown. Bridge-hop I/O failure, invalid successful response, or missing/malformed HTTP 502 error envelope at the actual REC request also means unknown. Known HTTP rejection and similar-looking codes remain ordinary failures. No command is automatically replayed.
- Direct CCAPI keeps the separate acknowledged-status-read failure type. I/O failure or invalid simulator JSON at the actual REC request instead produces command-result-unknown responsibility. Discovery, temperature preflight and known HTTP rejection cannot manufacture either ACK or unknown evidence. Cancellation is rethrown. Native optional status reads retain their established cached recording semantics; that cache is not independent REC evidence.
- An accepted command with a failed reconciliation read or nullable recording status creates a local pending owner. Its direction, connection identity, session generation and prior visible media IDs are separate from the camera status. A read failure retains the previous snapshot; no recovery branch invents `recording=true` or `false`.
- Pending state presents a persistent direction-specific warning and an explicit read-only “Refresh recording status” action. The confirmed REC indicator is hidden. The capture control offers deliberate Stop, including the existing capability, temperature and foreground-import Stop exemptions; still capture and photo-mode switching stay blocked.
- Refresh reads only status for the same pending owner. A boolean resolves acknowledged-readback pending according to the existing contract; Start followed by `false` shows a not-recording warning. Result-unknown responsibility is stricter: cached booleans, including delayed native REC events, never clear it. Only a later deliberate REC command with reliable acknowledgement can replace that owner. Failed Refresh and `null` retain pending state; Refresh never sends REC.
- Each command gets a distinct owner. A late Refresh cannot clear or replace a newer command's state. Rejected safety Stop retains the preceding acknowledged owner rather than gaining a false Stop acknowledgement. Disconnect/session replacement cancels work and clears current-camera recovery while preserving a truthful warning to check the previous camera. It never sends an automatic Stop or transfers old Stop responsibility to a new session; late results cannot revive it.
- A pending Stop retains the pre-Stop seen-ID set across repeated deliberate Stops and read-only retries. Only confirmed `false` starts the existing bounded video review. A Start resolving `false` does not start video review. The existing known-ID/media-causality limitations remain unchanged.

## Verification evidence

Source includes production-path MockWebServer cases for exact Bridge code mapping in both directions, required simulator readback failure, rejected and lost-ACK commands, native optional-read behavior, cancellation, pending Start/Stop, read-only repeated Refresh, unknown readback, rejected Stop ownership, late Refresh versus newer Stop, reconciliation read failure, and disconnect while readback is in flight. Existing recording-review tests remain, with the formerly untyped simulator failure expectation updated for the new typed contract. UI state coverage includes photo interlocks and foreground-import Stop admission.

- `git diff --check`: PASS.
- Focused JVM tests: NOT_RUN. The existing Gradle wrapper could not start because its distribution cache/lock directory was unavailable. No toolchain download or installation was attempted.
- Kotlin/Compose compile, Android Lint, aggregate checks, APK, instrumentation and UI/device execution: NOT_RUN.
- Physical camera/phone evidence: NOT_RUN.

This candidate is local source/test implementation only, not PR ready, main accepted or preview released. Tests listed above are coverage authored, not runtime passes. A separate source review and the required executable checks are still needed before publication/readiness claims.

## Release assessment

Impact: patch, a backward-compatible recovery fix for existing REC control. No version change, main merge, release, CI or device run is part of this local candidate. Latest released baseline must be rechecked by the owning delivery task before publication; no new release baseline is asserted here.

## Complete UI recovery journey

The existing real Compose → ViewModel → synthetic HTTP → Media3/MediaStore journey now also covers Start ACK/status failure → visible Stop-only controls → failed read-only Refresh with no replay → explicit Stop ACK/status failure → successful read-only Refresh → newly visible video preview and exact saved original. This additional instrumentation test is NOT_RUN locally; original assertions and timeouts remain. The native canonical recording boolean remains the established ACK-derived cache, not independent physical REC observation.

## First exact-head CI result and lost-ACK fixture isolation

At head `e13e381d432cc0f5c50475bbb0bb759916170501`, run `38360643375` executed 1,124 JVM tests with one failure. The new lost-acknowledgement fixture expected dispatched paths `[start, stop]` but observed `[start]`. Its two commands shared the transport deliberately broken by the first response loss. Both returned untyped failures, but the fixture did not retain either exception or the incoming request queue. This evidence does not establish which connection boundary failed or prove a product Stop defect. The original failure remains unresolved transport-sequence evidence.

The revised fixture independently injects `DISCONNECT_AFTER_REQUEST` for each direction with a fresh peer and client. Each case requires one complete queued POST with the exact path and no request-read failure, exactly one dispatched action and total request, no status GET, and a non-null failure that is neither acknowledged-readback recovery nor cancellation. Aggregate direction coverage remains `[start, stop]`. The injected client permits ordinary connection retries so the production mutation client and one-shot request enforce no replay. No sleeps, manual retries, pool eviction, or production changes are introduced.

This is independent lost-ACK typing/no-replay coverage, not a same-client reconnection guarantee. The revised Kotlin fixture is source-reviewed only until its own exact-head CI executes; previous successful cases do not validate the modified source.


## Result-unknown responsibility extension (local source candidate)

Base for this batch: `4f4c53024168238214b5ed817eb0d4595a8d52cb`, including the independently isolated lost-ACK fixture above. A transport failure is distinct from both rejection and acknowledgement. `RecordingCommandUnconfirmedException` carries only the requested direction; `RecordingRequestEvidence` is per-coroutine evidence marked immediately before the actual REC HTTP execution. Preflight cancellation sends zero REC. Cancellation after an attempted non-rejected REC propagates as cancellation while the same ViewModel owner retains result-unknown responsibility. No cancellation is converted into successful ACK. Connection identity and generation guards prevent late cancellation/results from mutating replacement sessions.

The persistent unknown warning does not claim that the command was accepted, and keeps deliberate Stop available through capability loss, temperature restriction and stale native events. A successful current Stop follows the existing acknowledged-status/review path. A lost Stop retains the original pre-Stop media-ID baseline across gallery reads and repeated explicit Stop, so a later acknowledged Stop can still find the recording's MP4. No automatic REC, retry, reconnect, Stop, or extra polling loop is added.

Additional authored coverage includes the exact Bridge error tuple and negative near-matches, Bridge-hop response loss after independent peer application, malformed success versus explicit 503 rejection, temperature preflight with zero REC, native applied-before-loss Start with old cached false and delayed stop event, capability/temperature Stop admission, rejected safety Stop, lost Stop review-baseline retention, old Refresh versus current Stop ACK, cancellation before/after actual dispatch, and old-camera warning without new-session ownership. The established fresh-transport lost-ACK fixture still requires a complete queued POST and no replay/readback; its result now must be unknown-typed and remains explicitly not ACK/readback-typed.

Validation for this extension:

- `git diff --check`: PASS.
- English/Traditional Chinese resource XML parse: PASS.
- JVM tests, Kotlin/Compose compile, lint, instrumentation, exact-head CI and device execution: NOT_RUN. The delegated environment has no usable Kotlin/Android runtime path; the previously blocked Java/AGP resolution was not retried, and no installation/download was attempted.
- All new test cases above are authored source coverage, not runtime pass claims. No physical camera validation, push, merge or release was performed.

Release impact remains patch; readiness remains local source implementation only pending independent review and executable validation.


## Independent-review corrections: admission retirement and incomplete Bridge 502

The first uncertainty candidate (`9ffba1016389c50aad7a3fde018a847d2d4e892c`) had a source-review race: disconnect/reconnect could snapshot `attempted=false`, then an I/O worker that had already passed its cancellation check could admit REC before the generation changed/cancellation arrived. Request admission and owner retirement now share the same per-attempt monitor. Retirement either sees admitted responsibility and preserves the old-camera warning, or prevents any subsequent admission with cancellation. No canceled-job callback is needed to modify a replacement session, and no command is sent by retirement.

`RecordingRequestAdmissionTest` exercises the exact production boundary for simulator JSON, native command-OK and Bridge JSON. It holds the evidence monitor until the HTTP worker is blocked at `beginRequest` after its cancellation check, retires the owner while holding that monitor, then requires cancellation and zero added HTTP requests. A second case covers admission-before-retirement retaining responsibility. These are authored tests, NOT_RUN locally.

The second correction treats a missing, malformed, empty, non-object or non-string-code Bridge HTTP 502 error envelope on REC as unknown. Such a body may have lost the server's acknowledgement/readback or unconfirmed-command evidence. Complete explicit rejection and legacy preflight envelopes retain ordinary failure classification; HTTP 503 remains an explicit rejection control. A complete rejection is recorded before session/cancellation checks, so cancellation does not itself convert that evidence to unknown. Additional peer tests apply Start/Stop before returning malformed 502 bodies, and verify the distinct known-rejection/preflight controls without automatic replay.

Validation status is unchanged: source diff/XML and normal secret hook are checked; Kotlin/JVM/Compose/CI/device execution remains NOT_RUN. No new toolchain, push, CI trigger or release is part of this correction.


## Rejection headers with interrupted response bodies

A further source-review correction preserves explicit non-502 rejection headers before reading the Bridge response body. HTTP 401/403/503 followed by a body I/O failure therefore remains an ordinary rejected command, without producing ACK or UNKNOWN responsibility. The special HTTP 502 envelope path still retains UNKNOWN when its body is lost or malformed. Non-502 envelopes cannot claim an acknowledged-readback outcome merely by using the ACK error-code text.

Additional authored fixtures use fresh peers/connections for both directions at 401, 403, 503 and 502, inject `DISCONNECT_DURING_RESPONSE_BODY`, retain complete incoming POST evidence and independent peer recording state, and require exactly one REC with no replay. The 502 case is the positive UNKNOWN control; the other statuses preserve rejection evidence. These fixtures remain NOT_RUN locally; normal hook/source checks do not substitute for JVM execution.


## Complete error-envelope schema alignment

A complete Bridge HTTP 502 error envelope now requires both `code` and `message` to be nonblank strings, matching the Bridge `ErrorDetail` schema. Code-only, blank-message, null-message, wrong-type message and code-only ACK-looking bodies stay UNKNOWN rather than establishing rejection or acknowledgement. This is defensive contract alignment; the normal Bridge handler already emits both fields. Complete known-rejection/preflight controls and exact error-code/feature/engine negative fixtures retain their original purpose and now include the required message field. Added source coverage remains NOT_RUN locally.
