# Android upload outcome consistency

Status: implemented locally on base `727ea8445c868473297781b66e2151cb1045b063`; final validation results are recorded below. This work does not publish a build or establish PR readiness.

## Behavior and evidence boundaries

- An upload has a single in-memory attempt with local preparation, possible dispatch and a transport-verified receipt. Cancelling local source staging leaves **not sent**. Once dispatch may have begun, an absent, lost or invalid completion receipt leaves **unknown**. A verified receipt for that attempt remains **confirmed**, including cancellation at the IO-dispatcher return boundary or failure of the subsequent gallery refresh.
- A listing observation cannot confirm an upload. Existing or newly appearing same-name/same-size rows, case variants, unknown provider size, an empty list, a failed refresh and unrelated camera activity never promote an unknown result. Cancellation does not perform an automatic listing reconciliation, replay, deletion or overwrite.
- Desktop Bridge and simulator notify the attempt immediately before executing the actual HTTP call and immediately after validating its completion response. Both existing server upload endpoints return HTTP 201. Success requires that status, the exact actual byte count, a nonempty string identity and kind, the submitted filename under exact/ASCII-only case equivalence, and an equal positive integer size. Opaque returned IDs are retained exactly. Non-ASCII characters are not case-folded or normalized; receipt values are not trimmed.
- Responses are bounded before decoding: the existing Bridge 32 KiB ceiling is retained; the simulator uses 64 KiB. Strict UTF-8 and JSON grammar validation precede platform JSON decoding. Duplicate decoded keys at every nesting level are rejected. The raw top-level size token must consist of positive decimal integer digits, so a platform decoder cannot turn a floating, exponent or rounded numeric token into upload proof. The Bridge session is checked again after reading and validating the response.
- USB PTP marks possible dispatch immediately before `SendObjectInfo`, after existing preflight checks. The original operation-specific returned object handle and exact `ObjectInfo` storage, parent, format, filename and size verification remain necessary. A local pre-dispatch cancellation does not abort an otherwise usable USB session. A dispatched uncertain USB upload aborts/disconnects and requires explicit reconnect and inspection. Receipt publication precedes optional storage freshness; existing noncancellable USB post-commit read cleanup is retained.
- Every progress, terminal result, list-refresh publication and upload cleanup is fenced by the original connection reference, session generation and attempt identity. Disconnect, CONNECT retirement and ViewModel clearing synchronously close the same dispatch gate before generation invalidation and job cancellation. A controlled race where the old owner check already passed still cannot dispatch after that gate closes. New sessions clear prior upload UI state.
- Existing single-use picker ownership remains unchanged. Picker results are consumed and the current session/capability/busy state checked before source access. Process replacement and duplicate callbacks cannot replay a source.
- English and Traditional Chinese distinguish local preparation, sending, not sent, unknown and confirmed-with-refresh-unavailable. Unknown results offer an explicit read-only refresh/inspection action. The user must decide whether to choose a file again. Manual refresh does not establish upload success or erase a valid operation receipt.

## Shared contract

`contracts/media-upload/v1/upload-outcome-vectors.json`, SHA-256 `dcb90087f58db32166fa86291486190a1aca1ea368ff7e74cf3342a601c8b1fd`, contains 48 outcome, 12 picker admission, 20 owner-publication, 48 receipt and 14 journey vectors (142 total).

The formal Gradle loader consumes that exact resource and org.json values. Offline tests substitute a checksum-locked typed fixture loader only. They still execute the real production strict grammar/token guard, outcome/gate/owner logic and existing picker helper, but this adapter is not org.json, Android-runtime or HTTP evidence.

## Validation

Final local results (2026-10-15 08:43 UTC): **32 Kotlin/JUnit tests PASS**, including all 142 shared vectors and three actual `PtpSession` synthetic-container tests; **124 source/integrity tests PASS** with one pre-existing PytestReturnNotNoneWarning; **24 current Kotlin/KTS files PASS Kotlin PSI syntax**; Ruff and diff checks PASS. Source and artifact checksum manifests bind these results to the exact local files. The existing checksum-verified Kotlin 2.2.21 compiler and JUnit 4.13.2 are used with separate compiler/runtime classpaths, CPU 8, one active processor and a 256 MiB heap. The real model, upload helper, strict JSON parser, picker helper and PTP protocol/property implementation are compiled. Their backend model dependencies are extracted verbatim from the checksummed backend source; the full backend is not compiled by this offline command.

Authored coverage includes:

- Pure production outcome/receipt tests, all 142 shared vectors, the existing picker tests, prompt-cancellation receipt retention and latch-controlled retirement races.
- Actual `PtpSession` protocol fixtures: no `SendObjectInfo` before admission, one real protocol dispatch boundary and preserved responder handle, and unknown after a lost final response with no replay. These use synthetic containers, not a physical USB device.
- Formal Gradle Bridge HTTP tests for pre-dispatch cancellation, confirmed receipt before cancelled IO return, lost acknowledgement without replay, and shared receipt negatives through the real client. Existing upload streaming, bounded-response, simulator and USB backend tests remain and are extended with dispatch/receipt evidence.
- Actual-Android HTTP/JSONTokener fixtures for numeric-token coercion, lenient syntax, nested/decoded duplicate keys, invalid UTF-8 and exact opaque identity.
- Five ViewModel/provider/HTTP journey fixtures covering staging cancellation with known/unknown size and old filename collisions, unknown after dispatch and fresh matching inventory, receipt plus refresh failure/late cancel, and reconnect during staging or POST.
- Compose fixtures distinguish local preparation from sending and require the manual inspection action without automatic retry. Existing picker restoration/reuse fixtures remain; their synthetic successful simulator response is corrected to the endpoint's existing 201 status.

Android SDK/Gradle build, formal JVM org.json/HTTP suite, Android Lint, emulator/Compose/instrumentation and physical-camera acceptance are **NOT_RUN** in this environment. Kotlin PSI is syntax-only and is not Android type checking. No dependency was downloaded and no denied dependency route was retried. An execution-server interruption is recorded separately from compilation/test outcomes; the prior 29-test result is not represented as the later protocol-expanded run.

## Non-goals and device status

No physical camera write, checksum/content-integrity promise, all-card search, new transport, capability expansion, upload retry, same-name deletion, version change, commit, push, CI dispatch, merge or release is included. Real Canon CCAPI upload remains unsupported; its upload fixture is simulator-only. Physical post-cancel commit timing, USB reliability and compatibility require named camera/firmware/transport/build evidence and remain pending.

## Release Assessment

- Latest verified release baseline: **v0.12.0 Development Preview**, checked by the delivery coordinator on 2026-10-15. Source/changelog version 0.13.0 is unpublished source and is not treated as a released baseline.
- Proposed impact: **patch**, a backward-compatible correction to existing upload reporting, cancellation and ownership.
- Tester value: avoids false success from an unrelated same-name item and preserves genuine completion evidence while making uncertain results actionable.
- Unresolved distribution gates: full Android/native/HTTP/Compose verification, exact-head CI, normal main acceptance and immutable release-candidate checks. This local result is not `PR ready`, `main accepted` or `preview released`.
- Physical-device status: pending; synthetic fixtures establish no camera-compatibility or transport-reliability claim. No version is changed here.
