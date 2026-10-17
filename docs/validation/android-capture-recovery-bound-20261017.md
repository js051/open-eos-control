# Android capture-configuration recovery: bounded Disconnect

## Scope and observed defect

Baseline: integration `34669de2`.

The explicit capture-configuration recovery action registers a `CameraOperation.DIRECTORY` job in `CameraViewModel`. The actual chain is `CaptureConfigurationAttempt.recover` → the frozen `CameraRepository.captureConfigurationAccess.read` closure → `DesktopBridgeClient.readCaptureConfiguration` → `requestJson(captureRecovery = true, observeStatus = false)`.

The baseline executes both the response and body synchronously on `Dispatchers.IO`. The existing 15-second coroutine timeout can cancel the job, but cannot release it if the transport ignores cancellation. Disconnect correctly cancels and joins the registered operation before physical cleanup; the blocked read therefore prevents that cleanup indefinitely.

An offline production-source host extraction compiled successfully and reproduced three expected assertion failures: held response, held body, and the real 15-second read deadline. In both cancellation cases the registered DIRECTORY job remained incomplete and repository Disconnect was never called while transport remained held (`execute = 1`, `enqueue = 0`). Compilation failure is explicitly not accepted as red evidence.

## Change

Only requests explicitly marked as capture recovery may enter the new helper. It requires:

- GET on exactly either frozen-session capture-configuration read route
- `observeStatus = false`
- no mutation evidence, mutation response callback, or recording evidence
- the same live session object before dispatch and after full-body completion

The existing bounded callback continuation now owns the complete read-only response/body. The callback performs strict capture JSON framing/size validation and closes its own response. The coroutine retains JSON conversion, error classification and state adoption. Cancellation and the real 15-second deadline can finish the registered job even when transport cancellation is ignored; late or duplicate completions cannot resume a retired invocation.

The ordinary synchronous request path, mutation evidence, registered job joins and physical-release ordering are unchanged. A successful Create Directory's separately scoped post-ACK read uses the same marked read-only route; this change never detaches its write.

## Evidence and limits

The focused host suite covers both recovery routes, blocked response/body cancellation, explicit Cancel, real deadline, late callbacks after same-text owner replacement, duplicate callbacks, successful readback without status publication, strict/error body rejection, and route/method/owner/status/mutation negative controls. The ordinary unmarked request still executes synchronously.

See `scripts/validation/fixtures/android-capture-recovery-host/README.md` for the exact production/synthetic boundary and reproduction command. The runner hashes all source and extracted methods plus the verified, already-local Kotlin/JUnit/coroutines jars. Each compiler/test JVM is sequential, one processor, at most 256 MiB heap. No Gradle, network or downloads are used.

The four existing maintenance host runners include the production capture-operation enum because their exact `requestJson` extraction now also contains this helper. Their original maintenance tests remain separate regression checks.

Validation runs on 2026-10-17 UTC:

- Baseline: compiled successfully; 3/3 expected runtime assertion failures
- Fixed focused suite: initial 12/12 passed; final 13/13 passed, including explicit Cancel coverage
- Maintenance cleanup: 16/16 passed
- Maintenance dispatch: 67/67 passed
- Maintenance read/restore: 67/67 passed
- Maintenance restore-token/status: 68/68 passed; its superseded-Stop negative case emits the existing expected rejection diagnostic on stderr

The final battery totals 231 passing test executions. Including the initial overlapping 12-case focused run gives 243 passing executions, plus the three expected baseline failures. Counts are test executions, not unique tests: maintenance suites deliberately repeat shared workflow tests. The initial focused run and final focused run also overlap. No baseline rerun was performed after proving the three runtime failures. A first fixture packaging compile error (private helper in a separate file) was corrected before the successful baseline compile; it is not counted as red evidence.

This is host coroutine/owner evidence with adversarial synthetic HTTP, not Android/OkHttp integration, Bridge server, USB or physical-camera validation. JSON adapters preserve typed fixture values but are not Android `org.json`. Physical validation remains pending. Socket callbacks may still consume transport resources until the underlying stack returns; the change bounds the app job and its Disconnect join, not physical socket completion.

## Release assessment

- Baseline: integration `34669de2`, Android version `0.13.0`; latest remote release was not queried for this local-only change
- Impact: patch, fixes an existing recovery/Disconnect hang
- No version bump or release in this change
- Status: local implementation and focused host validation; no remote push, CI, main merge or preview release claimed
- Non-goals: generic transport conversion, direct CCAPI capture changes, mutation cancellation, bypassing joins, or changing physical release proof
