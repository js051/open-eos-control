# Android Bridge maintenance dispatch and Disconnect

## Scope

This follows the strict maintenance cleanup repair at
`1ca763aa62a6a5fed229b03b3cb3dacf500b32f6`. It addresses the existing maintenance
command response that can keep its registered ViewModel operation job alive and
prevent explicit Disconnect from reaching required cleanup.

The affected existing actions are clock sync, sensor cleaning and camera sleep,
through their saved-session `POST /maintenance/{action}/outcome` routes. Their
confirmation, no-replay and frozen-intent contracts remain in place. Ordinary
CCAPI, USB, held autofocus, readback and preview-restoration transports are not
being rewritten. Disconnect must continue joining registered jobs and performing
its original required cleanup.

## Evidence and ownership requirements

The command caller needs a whole-call deadline independent of whether its
external response-body producer honors cancellation. A timeout or cancellation
still leaves command execution uncertain; it is not a camera acknowledgement.

A complete late response may update only its captured original attempt's
evidence. Current-session UI publication keeps the existing generation, attempt,
cancellation and retirement checks; history updates must not revive an evicted
entry. A late body cannot initiate another command, read or preview restoration,
clear a newer error, or change a replacement session's state.

Clock evidence keeps the original response's reference/read/sample separate from
later recovery observations. A valid original response arriving after timeout
must not be rejected solely because the timeout marked initial proof unavailable,
and must not overwrite a newer recovery sample or availability warning. The
timeout's existing "mark unavailable only if still unread" intent needs an atomic
check/update when a callback can complete concurrently.

## Implementation

Only `maintenanceAccess.dispatch` uses `maintenanceDispatchJson`. It enqueues the
saved request through the existing strict single-attempt client and waits using
the existing callback awaiter's unmodified 15-second deadline. Response framing,
status, length, UTF-8 and JSON checks stay in the original strict body reader.
An atomic callback claim accepts at most one response. The external callback can
finish later, but it cannot keep the registered coroutine job alive after its
waiter is cancelled or times out.

The callback only passes a validated outcome into its captured engine, request
and original evidence object. The new Bridge-only atomic receipt operation
retains the command acknowledgement separately from the original clock sample,
without replacing later recovery evidence. The first original receipt remains
immutable. Ordinary `initialRead` behavior is unchanged. The workflow's failure
path atomically marks availability unavailable only if it remains unread.

ViewModel operation registration, Disconnect joining and required cleanup are
unchanged. A timed-out attempt that remains selected on the same connection may
still display its complete original late receipt under the existing publication
rules. Cancellation, retirement or replacement confines that receipt to an
already retained original history entry; an evicted entry is never reinserted.

## Runtime baseline

The immutable `1ca763aa` baseline compiled successfully and produced exactly two
expected runtime assertion failures through the complete existing caller chain:
`submitMaintenance` → registered `launchCameraOperation` → `MaintenanceAttempt`
→ `maintenanceAccess.dispatch` → blocked response body → `disconnect` job join.
One case cancelled the attempt explicitly; the other waited for its real default
15-second timeout (15,003 ms observed). In both, the producer ignored cancellation,
the registered job remained incomplete and `repository.disconnect` had zero calls
while the body was held. The body was released only for fixture teardown.

## Fixed runtime evidence

The main fixed run compiled and passed 62 tests in 30.736 seconds: 18 new exact
caller-chain controls and all 44 existing production `MaintenanceWorkflowTest`
cases. Two independent real, unmodified deadlines each fired at 15,001 ms while
the body producer remained held. Disconnect finished the registered-job join and
required session DELETE without waiting for that producer. Explicit cancellation
has the same caller-return result; command execution remains uncertain until a
valid original response arrives.

An additional five tests passed in 0.134 seconds against the same frozen product
sources. Three execute the atomic unavailable/original-response fence, including
both ordered completions and concurrent contenders. Two route through the real
dispatch closure: a 15-case rejection matrix for status, framing, lengths, UTF-8,
size and malformed JSON, plus the existing sleep outcome route. Total new-batch
runtime coverage is 67 passing tests across those two runs. The previous cleanup
suite also passed 16/16 in 15.239 seconds, and the owned-Bulb suite passed 59/59 in
15.48 seconds against the same product source. All four runs passed, totaling 142
test executions. Forty Android source/resource checks passed as a separate,
non-runtime gate.

Controls retain late original clock proof after cancellation and timeout, preserve
newer successful and failed recovery evidence, reject malformed/mismatched late
responses, ignore duplicate callbacks, prevent draft replay/history resurrection,
preserve replacement-session state and retirement errors, and continue waiting
for an unrelated registered job. No old callback initiates a read, restoration,
retry or new command.

Compact receipts and JUnit output are published under
`docs/validation/evidence/android-maintenance-dispatch-2026-10-17/`. Receipts record
the immutable baseline, product/extract/fixture/tool hashes and exact test classes.
Baseline and current fixture hashes intentionally reflect their actual execution
revisions. The main and additional fixed runs are reported separately rather than
being presented as one JUnit execution.

## Verification boundary

The runtime harness chains byte-exact ViewModel submission, operation registration
and Disconnect with the complete production maintenance workflow/evidence and
the exact Bridge access/transport methods. HTTP/Android/model adapters remain
explicit. This does not constitute complete Android compilation, Compose or
native lifecycle testing, actual OkHttp socket behavior, APK packaging or camera
validation. Compiler and test JVMs use only existing checksum-verified tools,
one active processor and a 256 MiB heap, without Gradle or downloads.

The strict cleanup routes have their own previous evidence. General readback and
preview restoration can still block on their existing transport; unrelated
registered jobs and physical-control cleanup are still separate boundaries.
No global "all Disconnect paths are bounded" claim is made.

Follow-on scope: [maintenance read deadlines](android-maintenance-read-deadline.md)
adds only the three exact Bridge read routes. Strict restoration Start remains a
separate blocking and remote-ordering boundary; the dispatch evidence above is
unchanged.

## Release assessment

Impact: `patch`, repairing an existing Android Bridge maintenance workflow.
No version change or release is part of this local batch. Full Android/CI and
physical-device validation remain separately identified acceptance work.
