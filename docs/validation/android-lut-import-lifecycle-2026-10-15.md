# Android LUT import lifecycle, 2026-10-15

Status: implemented with focused host-JVM evidence. Not PR ready, full Android SDK verified, or physical-device validated.

## Behavior

- A production `CubeLutImporter`, retained by `CameraViewModel`, owns selection, provider read, strict decoding, parsing, and publication. Identity is claimed synchronously before I/O; a late read/parse cannot obtain a new identity.
- One physical pipeline and one replaceable latest pending selection prevent non-cooperative document providers from creating parallel reads. A replacement waits for the old pipeline to exit. The UI distinguishes loading, waiting for the prior import, and cancellation still awaiting physical retirement.
- Cancel revokes pending import and outstanding picker publication while preserving the applied LUT. Remove additionally clears the applied LUT. Neither claims that a blocked provider has already stopped.
- Opening a picker and receiving null do not supersede an already accepted selection. The app-root launcher keeps a saved immutable launch ticket, with one outstanding OS request. Ticket identity and selection-generation fences reject stale results, errors and duplicate completions. The slot is not reused until its original callback arrives, even after revocation.
- Done/closing the monitoring sheet, backgrounding for Files, and camera disconnect do not discard the app-run LUT or cancel its current local import. Clearing the ViewModel retires the owner. There is no LUT library or persistent LUT/URI permission.
- After process/owner loss, a restored outstanding picker ticket can only retire, never import. Its orphaned status is visible and Load stays disabled instead of silently doing nothing. Finish/cancel the earlier picker; if it no longer exists, remove the task from Recent apps and reopen. This recovery boundary is described in UI copy, not claimed as physical-device acceptance. Same-owner Compose restoration is separately covered by an unrun native test; it is not proof of Activity rotation or process death.
- Reads enforce 16 MiB on original bytes and inspect at most one sentinel byte beyond the cap. Invalid UTF-8 is rejected even inside ignored comments or TITLE. One leading UTF-8 BOM is removed; repeated leading BOMs and UTF-16 are rejected. The existing 3D-only cube parser and interpolation contract remain unchanged.
- Read/decode/parse failure preserves the last applied LUT. LUT loading/error fields are separate from camera errors and operation-busy flags. `CancellationException` is rethrown and never published as a late import error.

## Verification

Focused real Kotlin 2.2.21/JUnit 4.13.2 run: **24 tests passed**, including 16 shared byte vectors, original-byte boundaries, cancellation checkpoints, stream closure, latest pending selection, non-cooperative read retirement, read/parse publication races, cancellation before initial dispatch, current/retired picker outcomes, and restored-owner retirement. No coroutine-test dependency or Android stand-in replaces the production importer/reader/parser.

The host run uses a generated, SHA-bound mapping of the shared JSON fixture because the existing host tool directory has no `org.json` jar. Normal Gradle unit tests use `CubeLutUtf8JsonFixture.kt` and the same shared resource. The normal JSON resource-loader path is **NOT_RUN** here.

`CameraLutImportJourneyTest` adds **9 native integration tests** driving the production Compose picker and screen, ViewModel, ContentResolver, FileProvider, decoder and parser with synthetic files. The deferred registry replaces only the external system picker. Coverage includes Unicode, all shared byte vectors, strict-invalid bytes, preserving/retrying a prior LUT, cancellation/removal, null results, launcher failure, sheet closure/disconnect, same-owner saved-state restoration and visible orphaned-ticket handling. These tests are **NOT_RUN**: no Android SDK/emulator/device execution was authorized for this pass.

Also **NOT_RUN**: full Gradle compilation/lint/unit suite, physical phone/provider behavior, real Files UI, Activity rotation/process-death recovery, native GPU rendering, camera/USB/network operations, and CI. Host evidence is not a substitute for these gates.

## Release assessment

- Baseline: unreleased 0.13.0 source; latest published preview remains the repository-documented 0.12.0 baseline, not re-queried during this offline pass.
- Impact: patch; backward-compatible fixes to the existing LUT-import lifecycle and strict encoding/bounds contract.
- No version bump, main merge, publication, camera transport change, new format, LUT library, or renderer feature is part of this change.
- Blockers to stronger readiness claims: full Android SDK and native integration execution are pending. Physical-device/provider validation remains explicitly unverified.
