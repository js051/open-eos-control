# Mobile Bridge legacy JSON listing: fail-closed validation

## Scope and status

This local change group aligns legacy JSON row integrity, exact-ID uniqueness and item-count bounds with the PC browsing parser. It does not change the NDJSON wire protocol, provider implementation, strict inventory support, HTTP retry policy or timeouts. iOS and Android are separate candidates; do not assume one candidate contains the other's implementation.

Source baselines inspected on 2026-10-16:

| Reference | Commit | Source SHA-256 |
| --- | --- | --- |
| PC `bridge/open_eos_bridge/static/media-listing.js` | `5c94a4a` | `7db465923c9015013153028197495301eef6312852e8bda0486ec8c40ceae988` |
| iOS `ios/OpenEOSCore/Sources/OpenEOSCore/DesktopBridgeClient.swift` | `7c0115900d92ff92cd0d8d0138ddd9051ffad7e6` | `7d6c15c24adb8c7927d8ee07a00dc1eca7ca376f86ca061f1705cea3c155e773` |
| Android `android/app/src/main/java/dev/openeos/control/data/DesktopBridgeClient.kt` | `6b1df91a97307c7ddae53c52f9921e5c48bedce7` | `6476be0868b239aabb80e07ebaa9453679214ae637978447079d0163a30201ec` |

Host execution: the actual PC parser passed 9 focused reference tests and 74 existing parser/owner tests. These are PC JavaScript results, not Swift/Kotlin execution. New mobile core and caller tests are authored but **NOT_RUN**; Swift/Kotlin toolchains are unavailable in this preparation environment. No native build, Gradle task, device test, CI run or physical-camera validation was performed.

## Exact counterexamples and previous behavior

All examples use one HTTP 200 `application/json` response to the original media GET. Let A be `{"id":"image","name":"IMG.JPG","kind":"image"}` and B be `{"id":"other","name":"OTHER.JPG","kind":"image"}`.

| Response / request | Previous PC result | Previous iOS result | Previous Android result | Required new mobile result |
| --- | --- | --- | --- | --- |
| `{"items":[A,null]}` | Reject | Reject | Silently drop null; return A | Reject without progress publication |
| `{"items":[A,{"id":"broken","kind":"image"}]}` | Reject missing name | Reject missing name | Silently drop invalid row; return A | Reject without progress publication |
| `{"items":[A,A]}` | Reject duplicate ID | Return both rows | Return both rows | Reject duplicate ID |
| A plus `{"id":"\u0069mage","name":"OTHER.JPG","kind":"image"}` | Reject decoded duplicate ID | Return both rows | Return both rows | Reject decoded duplicate ID |
| `{"items":[A,B]}` with `limit=1` | Reject excess response | Return A via prefix | Return A via take | Reject, never truncate into success |
| Full scope, 100,000 distinct valid rows | Accept all | Accept all | Accept all | Accept all |
| Full scope, 100,001 distinct valid rows, body below 64 MiB | Reject item bound | No item-count guard; return all | No item-count guard; return all | Reject before per-item allocation/publication |

The old mobile outcomes above are source-derived, not native test observations. The PC outcomes were executed against the recorded source hash.

At the original baselines, iOS legacy parsing was at `DesktopBridgeClient.swift:1840-1854`; Android was at `DesktopBridgeClient.kt:1332-1344`, with filtering helper at `2359-2360`. PC checks are at `media-listing.js:54-61,102-103`. iOS now checks count before mapping and exact UTF-8 ID uniqueness while mapping. Android replaces both filtering stages and `take` with a bounded, total per-index decode and duplicate rejection. Both validate the entire response before publishing.

Schema compatibility remains intentionally narrower than complete PC field equivalence: existing mobile legacy replies without `kind` still decode using the existing `other` default. Optional metadata behavior is unchanged. No identity normalization or trimming is introduced. In particular `caf\u00e9` and `cafe\u0301` remain distinct opaque identities; an identical decoded ID written with JSON escapes remains a duplicate.

## Caller outcome and tests

- Failed legacy validation publishes no replacement rows. The existing gallery rows remain visible, the owned operation fails, and no successful final array reaches the caller.
- iOS `CameraAppState.performMediaLibraryLoad` retains the error path and `.failed` state. New app test `testInvalidLegacyBridgeRefreshPreservesRowsAndFailsWithoutFallback` covers null and duplicate rows after a valid gallery load, retained rows, deferred thumbnails, visible error and exactly one additional listing request.
- Android `CameraViewModel.refreshMedia` retains the error path and `FAILED` state. New production VM/repository/loopback fixture `invalidLegacyBridgeRefreshPreservesRowsAndFailsWithoutFallback` checks the same outcomes.
- Both `BridgeMediaListingTests` / `BridgeMediaListingTest` add invalid rows, exact/escaped duplicate IDs, requested-limit excess, 100,000/100,001 boundaries, empty results and Unicode identities. Rejections assert zero progress publications and one media request.
- Existing valid legacy authentication/single-GET tests and NDJSON malformed/partial/error/cancellation/same-ID replacement tests remain required. Their source is preserved; native execution is pending.

## Reproducible host evidence

The checked-in reference harness loads the real PC module named by the environment variable and prints its SHA-256. It contains no mobile parser model or replacement implementation.

```sh
OPEN_EOS_PC_LISTING_MODULE=/path/to/pc-candidate/bridge/open_eos_bridge/static/media-listing.js \
  node --test scripts/validation/tests/bridge-legacy-json-reference.test.cjs
node --test /path/to/pc-candidate/bridge/tests/media-listing.test.js \
  /path/to/pc-candidate/bridge/tests/media-listing-owner.test.js
git diff --check
```

Recorded results: reference vectors **9/9 PASS**; PC parser and production-owner fixtures **74/74 PASS**; patch whitespace checks clean. The PC regression includes partial-then-error, missing terminal/EOF, cancel after terminal before EOF, cancel before headers, same-ID replacement, and one-response legacy handling. No source-string check is being counted as a native or behavior pass.

On a suitable approved environment, run `swift test` in `ios/OpenEOSCore`, the iOS `MediaResponseOwnershipTests` app test target, Android `BridgeMediaListingTest`, and `CameraMediaPreviewOwnershipTest` (including its Bridge parameter). Existing direct-CCAPI and strict foreground inventory suites remain required. The 100,000-row native cases are bounded synthetic inputs and should be observed for runtime/memory during their first native execution.

## NDJSON provider integration assessment

Source review found no consumer blocker to a gphoto progressive provider honoring the existing replacement-snapshot contract. All three consumers require complete revision assembly, a valid terminal and EOF; partial/error/cancel states cannot supply strict inventory evidence. The server routes strict inventory before progressive browsing and rejects non-CCAPI strict inventory. Mobile Bridge foreground import remains unsupported.

Existing limits still apply: iOS uses the 10-second request setting and 120-second resource timeout; Android inherits the configured HTTP client's timeouts; PC has no additional listing idle timer. A long gap can fail while retaining the last complete revision. The server's busy reservation must last until its actual provider worker exits. Cancel is not proof of instantaneous camera I/O interruption. Real-provider timeout/disconnect behavior requires separate provider and device evidence.

## Release assessment

- Repository-recorded published baseline: v0.12.0; remote release state was not re-queried for this local group.
- Impact: patch, because malformed/oversized legacy responses now fail instead of appearing as successful incomplete browsing results.
- No version change, merge, publication or release request is included.
- Local source review accepted the complete-array, owner, exact-ID, boundary and caller-failure changes before commit. This review is not native execution.
- Blockers: native compilation and focused mobile tests have not run; exact-candidate delivery gates remain outstanding.
- Physical-camera status: unverified; all new input vectors are synthetic.
