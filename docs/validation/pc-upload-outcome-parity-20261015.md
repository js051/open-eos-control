# PC upload outcome journey: validation and release assessment

Date: 2026-10-15 UTC. Source baseline: `f0c48ce8c743bd4bc74a9361a5090b1e8257b017`.

## Implemented behavior

The existing supported-camera upload journey now binds the browser picker to its opening connection object and a single-use input/owner. Reconnect, a replacement picker, cancellation, capability loss, busy state, and lifecycle retirement prevent that old picker from dispatching a file to a different camera. A new picker uses a distinct input element and callback; an old change event cannot consume the new owner's admission.

One selected file can enter at most one POST. Production uses Fetch with `redirect: "error"`, `credentials: "omit"`, `cache: "no-store"` and an operation-owned AbortController. This prevents 307/308 redirects from automatically replaying the body. Only the in-memory Bearer token is sent. Network upload progress is unavailable with this transport, so the bilingual UI explicitly says so and shows indeterminate progress. Results are evidence-based:

- **Confirmed:** HTTP 201 from that exact attempt contains a valid `MediaItem` receipt: nonempty opaque ID and kind, filename equality with ASCII-only case folding, and exact positive raw integer byte length. Duplicate keys, including nested and escaped-key duplicates, malformed/oversized/deep JSON, numeric strings, booleans, fractional/exponent sizes and other HTTP success codes cannot confirm an upload.
- **Not sent:** dispatch was never entered. A local setup failure or rejected picker can be retried only by a new explicit selection.
- **Uncertain:** dispatch may have started but no valid receipt was retained. Cancellation, timeout, send exceptions, transport failure, invalid responses, empty lists and same-name/same-size collisions cannot establish success or rollback.

The genuine Bridge/gphoto2 success path remains intact. A verified response is retained before the optional existing-scope listing refresh. A failed or empty refresh does not erase confirmation. Only a complete bounded response stream with strict UTF-8 decoding can yield a receipt; headers or partial JSON alone cannot confirm. A late genuine receipt may remain evidence on its original retired attempt, but cannot replace a current UI result or mutate the replacement connection.

English and Traditional Chinese show the latest upload result and an explicit read-only **Review current camera files** action. The result is retained in memory across reconnect and labelled as a previous-connection result. It is a latest-result snapshot, not a durable upload history; page reload or a later completed selection can replace it. Review keeps the selected listing scope and never retries an upload, deletes a matching item, or upgrades listing observations into a receipt.

Dispatch, progress, response, refresh and retirement are fenced by the exact session, attempt owner and interaction generation. Disconnect retires the upload/picker before any awaited cleanup. Page exit retires them too; ordinary browser focus loss is not treated as picker cancellation.

## Scope and non-goals

This is a backward-compatible correction to an existing upload capability. No new transport or CCAPI upload is introduced. No checksum of camera bytes is promised. No new preflight/full-card identity scan, automatic resend, resume, cleanup deletion, version change, credential handling, installation, push, CI dispatch, merge or release is included. The existing backend's target-folder verification remains unchanged.

## Executed checks

- Production upload helper and actual app callback tests: **205 passed**. This includes all **142 shared vectors** (48 outcome, 12 admission, 20 publication, 48 receipt, 14 journey) plus **63 app/edge tests**. The app tests include actual listing-refresh owner code, independent native-input closures, reused textual session IDs, source-metadata access guards, cancellation collisions, malformed acknowledgments, older responses after a newer operation, and preserved genuine receipts. Real Node Fetch against a synthetic loopback HTTP server also verified HTTP 307 and 308: one original POST, zero redirect-target requests, uncertain outcome and no listing/retry. The response reader rejects oversized bytes and invalid UTF-8.
- Complete `npm run test:modules`: **40 scripts passed**, exit 0. The 30 `node:test` scripts reported **1,590 tests passed**; remaining legacy direct-assert scripts also exited successfully. These counts include the 205 new upload tests and must not be added to them again.
- Complete Bridge pytest on the final Fetch source: **1,572 passed**, zero failures/errors/skips, exit 0. It includes six new API/backend tests and the existing upload, collision, cancellation and late-commit safety tests. This result was obtained by the bounded recovery run from 08:48:31 to 08:49:45 UTC, after acquiring the shared validation lock and rechecking all 15 source-file hashes. The earlier interrupted run remains recorded with an unknown terminal state; its partial output was not counted as a pass.
- Contract/validation Python suites: **49 passed**, exit 0. Shared corpus-integrity checks alone are not product execution evidence.
- Ruff using the repository's Bridge CI working directory: **passed**. JavaScript syntax checks and `git diff --check`: **passed**.

An intermediate Fetch-test conversion also had a test-file syntax error; it was preserved and corrected before the final complete Node run. The first new Python fixture run failed three tests because its session-creation JSON mistakenly reused an image Content-Type. That test-fixture bug was corrected and both focused and complete suites passed. Initial Ruff invocations from a different working directory produced configuration/import-discovery findings; the unchanged repository CI invocation from `bridge` passed. Original failure logs were retained; no existing safety test or lint rule was removed.

## Browser execution: NOT_RUN

Twelve real-browser journeys were authored in `bridge/tests/media-upload.browser.test.js` and registered in `test:browser`. They use the production page, native FileChooser/input events, Fetch, rendered bilingual UI, cancellation/reconnect boundaries, read-only review, and 200% font-size/mobile-width checks. They do not inject application state or replace application functions.

The Playwright-managed Chromium was absent. An existing system Chromium was then verified: **154.0.8037.57**, launcher `/usr/bin/chromium`, binary `/usr/lib/chromium/chromium`. The runner's existing `OPEN_EOS_TEST_CHROMIUM_EXECUTABLE` setting was used. Launch failed before any journey ran:

`FATAL chrome/browser/process_singleton_posix.cc:297: socket() failed: Operation not permitted (1)`

The authorized local-socket execution retry failed with the same environment restriction. No new browser flags, installation, network/proxy/socket configuration or OS security changes were made. The route was stopped. Thus all twelve browser journeys and their screenshots/layout assertions remain **NOT_RUN**, not passed. The ordinary browser suite was not claimed to have run.

Reproduction on a permitted existing browser environment:

```sh
cd bridge
PYTHON=/path/to/existing/python \
OPEN_EOS_TEST_CHROMIUM_EXECUTABLE=/path/to/existing/chromium \
node tests/media-upload.browser.test.js
```

## Release Assessment

- Latest verified distribution baseline: **v0.12.0 Development Preview**.
- Source version: **0.13.0, unpublished**. This change does not modify version files or imply that 0.13.0 was released.
- Proposed impact: **patch**. It corrects false upload confirmation, stale picker admission and cross-operation completion ownership in existing behavior.
- Delivery state: **implemented with local automated evidence**. It is not PR ready, main accepted or preview released; no commit, push or CI run occurred for this work.
- Open validation blocker: real-browser execution is blocked by the executor's socket restriction. Complete browser evidence is still required before claiming the PC UI journey fully verified.
- Physical-device status: **pending/not run**. No physical camera, card, USB/Bridge connection or named device/build was used. Synthetic API/backend tests do not establish real-device reliability. Confirm writable storage, actual saved bytes, late-cancel/commit timing and subsequent open/download on a named camera/build before making those claims.
- Release recommendation: retain this as an unpublished candidate until the remaining browser check and normal exact-SHA delivery gates are satisfied. Pending physical-camera evidence limits compatibility/reliability claims; it does not by itself invent a stable-release or preview-publication status.
