# Android safe media deletion candidate — 2026-10-15

## State and Release Assessment

- State: **implemented**, with focused pure-JVM/synthetic and source validation. Not PR ready, main accepted, or released.
- Release baseline: **v0.12.0 Development Preview**, as recorded by the coordinator at 2026-10-15 03:54 UTC. The existing source version 0.13.0 is unpublished; this candidate does not change versions.
- Proposed impact for the complete three-platform group: **minor**, because PC and iOS add batch deletion. Android repairs its existing single/batch deletion, acknowledgement, selection, and packaging behavior, individually a patch-level change.
- Blockers: exact-candidate Android Gradle/native unit tests, Compose/instrumentation, asset merging, and required CI have not run. Real-camera, real-USB and real host-original deletion validation is **NOT_RUN**. No destructive physical verification is claimed.

## Scope and safety boundaries

Every single or batch confirmation freezes the exact first-occurrence IDs, name, owner reference/generation, source location and per-ID selection epoch. The complete roster is retained in confirmation order, including hidden or unloaded rows. More than 1,000 unique rows or an empty exact ID rejects the complete operation; it is never silently truncated. Byte-distinct/case-distinct IDs remain distinct.

The confirmation explicitly identifies camera card, Android app-private USB host captures, desktop Bridge host captures, or unknown source. Bridge source classification requires the matching actual session-created operation/camera engine fields, a sanitized actual endpoint origin, and its known ID namespace; a requested engine hint or an api display name is not sufficient. Confirmation/history show the connected Bridge host origin and engine; credentials, paths, query and fragment are never displayed. Unknown protection/writability is displayed as unknown. Known protected and non-writable items are vetoed. Missing capability, unknown source and unloaded exact items are skipped. Existing advertised capability remains required; there is no new transport or capability escalation.

Admission is owner-bound and single-use. A second transport-boundary fence checks the current owner and eligibility. Cancellation closes admission to new rows and cancels the in-flight call, while preserving any already observed terminal receipt. A command that crossed the gate but lacks a trustworthy acknowledgement is uncertain, even if an exception sounds like a refusal. An uncertain or not-sent row stops the remainder. A typed explicit rejection can continue to the next row.

Per-item outcomes are confirmed, rejected, uncertain, not-sent, not-started and skipped. History is retained in process across refresh, disconnect and reconnect, bounded to 20 batches/2,000 complete rows. It is readable in the disconnected connection screen as well as media history; retired owners cannot resume or dispatch. History is not durable across app-process termination. Manual inspection refreshes only the existing user-selected media scope. List absence never changes a receipt into success.

Only confirmed IDs consume their original UUID selection epoch, which cannot restart at 1 when the media composition is disposed and recreated. Re-selected IDs, newly added selections and a new owner remain untouched. This repairs the reachable partial-batch case where a successful ID remained selected and hid the remaining rejected item's delete entry. Existing cancel/join and keyed dialog disposal are retained. This change does not claim that cross-reconnection wrong deletion was demonstrated.

No unprotect, automatic retry, compensating delete, whole-card scan, or restoration claim is added. A new delete requires a new explicit permanent-delete confirmation.

## Transport receipt rules

- HTTP: dedicated one-shot DELETE disables redirects, connection retries, authentication retries and cache use. A network-boundary single-dispatch guard cannot be reused. A complete, empty, validly framed 204 confirms. Complete 400/401/403/404/405/409/412/413/415/422/429 responses reject. Other 2xx, 3xx, 4xx (including 408/499), 5xx, truncated/read-failed or malformed responses remain uncertain. This conservative contract matches the Bridge route and repository CCAPI fixtures; it does not assert that every Canon firmware uses only 204.
- When connecting to Bridge, preservation of upstream CCAPI refusal status requires the matching PC Bridge candidate; older Bridge error mapping may conservatively remain uncertain.
- USB: only an exact `ptp:` concrete object handle in 1…FFFFFFFE is admitted. Zero, wildcard/reserved, overflow, wrong prefix and format-wide delete are rejected locally. Matching-transaction, well-framed PTP OK confirms; all non-OK responses remain uncertain because this repository establishes no deletion-specific refusal allowlist. General-error, partial-deletion, undefined and vendor codes cannot continue the remainder. Mismatch, unexpected container or lost receipt also remains uncertain. Receipt observation occurs before Android IO prompt-cancellation return. Tests use synthetic containers only.
- Android private host capture: existing exact-file path validation remains; the gate runs immediately before the existing file deletion, and a successful local result is captured before IO return. No user original files were used in validation.

## Shared contract and focused evidence

The coordinator-owned `contracts/media-delete/v1/delete-outcome-vectors.json` has SHA-256 `f42c67bc2b80185ac2198d00a59a9188b4424a4e6fa21a0f9df69f26fef15a68`: eligibility 216, admission 24, outcome 32, HTTP receipt 192, selection 54, roster 10 and batch 12, totaling **540**.

The focused JVM runner executes the real pure deletion coordinator and PtpSession with synthetic transports, plus the unchanged extracted gallery policy and DTOs. It uses a generated typed adapter for those exact 540 vectors. The production org.json fixture loader is written but not executed by this JVM lane. The unused Android-dependent PtpTransportFactory declaration alone is omitted from the extracted PtpProtocol source; protocol logic is unchanged.

Evidence includes actual compiler/JUnit exit codes, source hashes, outer `flock` argv, lock-acquisition output and outer exit. Java is limited to 8 active processors and 256 MiB heap. Every compiler/full validation uses the absolute shared heavy-validation lock. Kotlin PSI checks syntax only and cannot establish Android/Compose semantic compilation. MockWebServer receipt/redirect/truncation tests and Compose selection/location and actual composition-disposal/recreation tests are written but **NOT_RUN** locally.

## Separate previous-CI repairs

The previous approved exact SHA `732b06c` and run `38710613377` failed independently of this candidate:

1. API 34/36 instrumentation jobs stopped at `mergeDebugAndroidTestAssets`: duplicate README.md from contract roots. They did not execute UI tests. This candidate replaces directory-wide fixture inputs with explicit standard Gradle Sync manifests (10 unit JSON and 2 Android asset JSON), flat names unchanged, duplicate/missing guards, and preBuild dependencies. Local manifest/hash/loader checks pass; actual AGP merging remains **NOT_RUN**.
2. The previous unit job reported 1,348 tests and five failures. Full XML/artifacts were unavailable; causes below are source/fixture analysis, not invented runtime values:
   - negative page-count rejection had a generic diagnostic; exact integer/range validation now preserves the negative diagnostic without relaxing malformed-input rejection;
   - two native capture-preview fixtures explicitly returned short simulator IDs instead of full native resource IDs; only native fixture identity is corrected, with the original full-ID assertions retained;
   - the malformed-listing shape test advertised 1,000 pages while serving an empty response for each; that shape-only fixture now advertises one page and adds a page-one-only assertion, retaining the original success/evidence assertions and separate large-page tests;
   - gallery MP4 positive input inherited image-kind from a CR3 fixture; positive input now correctly declares video, preserving the video/mp4 assertion, and an explicit image/video conflict negative assertion is added.

The old failed logs remain historical evidence. No new CI was dispatched, and these fixes are not represented as a passing replacement of that native run.
