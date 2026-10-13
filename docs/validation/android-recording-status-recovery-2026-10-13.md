# Android REC acknowledgement and status recovery

Base: PR #240, `705205c2fc9b3160a8e46c9a244069148da0f497`.

## Contract

- Desktop Bridge maps only the exact `RECORDING_STATUS_READBACK_FAILED` code to a typed acknowledgement with the requested Start/Stop direction. Generic transport, rejection, missing-ACK and similar-looking codes remain ordinary failures. No command is automatically replayed.
- Direct CCAPI wraps only failures from the separate status read after a successful command. Cancellation is rethrown. Required simulator status failures exercise this path; native optional status reads retain their existing error handling and cached recording semantics. Native cached state is not new physical-camera evidence.
- An accepted command with a failed reconciliation read or nullable recording status creates a local pending owner. Its direction, connection identity, session generation and prior visible media IDs are separate from the camera status. A read failure retains the previous snapshot; no recovery branch invents `recording=true` or `false`.
- Pending state presents a persistent direction-specific warning and an explicit read-only “Refresh recording status” action. The confirmed REC indicator is hidden. The capture control offers deliberate Stop, including the existing capability, temperature and foreground-import Stop exemptions; still capture and photo-mode switching stay blocked.
- Refresh reads only status for the same pending owner. A fresh boolean resolves the pending state. Start followed by `false` shows a not-recording warning and permits a deliberate new Start; `null` stays pending. Failed Refresh stays pending. Refresh never sends REC.
- Each command gets a distinct owner. A late Refresh cannot clear or replace a newer command's state. Rejected safety Stop retains the preceding acknowledged owner rather than gaining a false Stop acknowledgement. Disconnect/session replacement cancels work and clears UI recovery; late results cannot revive it.
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
