# iOS capture-review observation and read evidence

Date: 2026-10-15 UTC. Sealed date-cohort base:
`e381340ed3e69866f46962b8379f8604e59193d3`.

Status: **implemented, source-verified; native NOT_RUN**. Not PR ready,
main accepted, released, or physical-camera validated. This receipt supplements,
and does not replace, the preceding media-date receipt.

## Behavior

The existing native state machine now carries a separate terminal observation
(`found`, `no-candidate-observed`, `unread`) and read evidence
(`successfulReads`, `failedReads`, `lastCompletedRead`). A mixed search ending
with failure retains successful no-candidate checks and an explicit last-read
warning in English and Traditional Chinese. Ending with success removes that
warning without discarding earlier failed-read counts. No text claims that new
media is absent from the card or that shutter/record-stop failed.

Each invocation starts fresh local counters. Cancellation, late completion,
owner/session replacement, and media-operation preemption cannot publish partial
or stale evidence. A still-current retired invocation restores the preceding
terminal outcome and releases transient searching so retry remains usable. If a
fresh capture has no previous terminal result, iOS keeps a retryable native
`notReady` state with `unread/0/0/null` and a distinct “no list check completed”
message. This is native busy-state handling, not a fabricated failed read.

Unchanged: four outer listing reads maximum; 250/750/1500 ms backoff; immutable
previous-ID snapshot; requested photo/video kind; camera response order; previous
media value; and zero shutter/REC/AF replay during review. The optional internal
delay seam exists for deterministic tests; normal callers retain the exact
production delays.

## Malformed-response boundary

- Simulator items must be an array of objects with nonblank string IDs and names.
- Legacy Bridge JSON validates every item before applying the requested limit;
  malformed entries cannot be silently dropped into an empty success.
- Canon paged/unpaged listings require arrays of nonblank string paths.
- Successfully returned page metadata must have a valid nonnegative integer count,
  or a valid unpaged path array when the camera ignores the count query.
- Unsupported optional count-query fallback and valid empty arrays remain valid.
- Existing stream decoder, origin/traversal guards, ordering and limits remain.

## Fixed contract and native test sources

The individual XcodeGen unit-test resource is
`contracts/capture-review-evidence/v1/review-evidence-vectors.json`:
25,745 bytes, SHA-256
`aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1`.

There are 106 fixed terminal vectors: all 16 success/failure patterns across three
no-candidate list shapes and both intents, eight found-at-round vectors, and two
malformed-response vectors. Native tests route all 106 through `CameraAppState`
and the real Bridge adapter with a synthetic transport, plus both fresh-retry
vectors in both intents. Expectations come from the pinned JSON, not production
code. Twenty-two added XCTest methods also cover zero-read and one-read
preemption, in-flight/backoff cancellation, stale success/failure, replacement
sessions, exact previous-media values, localization, and strict simulator/Canon/
Bridge adapter cases. **All of these native methods are NOT_RUN here.**

## Executed local evidence

- `python -m unittest discover -s scripts/validation/tests -v`: **43 PASS**.
  Includes seven new review structural/fixture checks and eight existing date
  checks; these do not compile or execute Swift.
- `python -m unittest scripts.ci.tests.test_ios_files_export_history scripts.ci.tests.test_ios_recording_mode_diagnostics -v`:
  **9 PASS**.
- `git diff --check`: **PASS**.
- Seven protected foreground/Files/delivery/RTP/LUT files match the preceding
  receipt byte-for-byte. The date fixture remains SHA-256
  `8eb57e04446478fe523c1e8cd08c0cc57b524d9fcb7f63845fc28d6d02f57e9a`.

A bounded source review of all five direct `listMedia` caller files and App
capture/readback/ownership/batch fixture routes found no existing successful
fixture needing correction. Deliberate malformed/error vectors remain unchanged.

Exact changed-file hashes, commands and log hashes are in the adjacent JSON.
The initial 52 unique passing Python checks are source/fixture evidence only.

The coordinator’s combined CI/validation/contracts Python run passes **95 tests**
(including the 52 above; do not add them) with one existing
`PytestReturnNotNoneWarning` in `test_export_ios_ui_evidence.py::test_tree`. The
actual Bridge Ruff configuration initially found seven E501 line-length errors in
the new structural test. Formatting-only corrections preserve its exact Python
AST and every assertion. The exact Ruff command now passes, and the combined
95-test run was repeated successfully. Initial failure and final passing logs are
both retained. Native production/test sources did not change in this correction.

## Remaining gates and release assessment

`swift`, `xcodebuild`, and `xcodegen` are absent. Swift syntax/typechecking, Core
unit tests, App XCTest/resource loading, Simulator UI/VoiceOver and physical
camera/provider tests are **NOT_RUN**. No installation, real camera request,
push, CI trigger, commit, merge, signing change or release was performed by this
implementation task. The coordinator owns final hooks, commit and delivery gates.

This cohort is a **patch** correction. The cumulative development branch remains
**minor** relative to the previously verified v0.12.0 release baseline because of
its other features. No version change or release is authorized by this receipt.
