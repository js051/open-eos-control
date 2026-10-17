# Android Bridge maintenance read deadlines

## Scope and implementation

This follows `a42aad06d0c2521c3196ce6773dda62f9a0856ac`. Only these existing,
saved-session read-only routes join the bounded callback reader:

- `GET /v1/session/{id}/maintenance/clock-sync`
- `GET /v1/session/{id}/maintenance/sensor-cleaning`
- `GET /v1/session/{id}/maintenance/sleep`

The exact method, captured session and complete path must match. The existing
three strict cleanup routes retain the same helper, renamed
`maintenanceReadOrCleanupJson`. Other methods, owners and paths keep their
previous transport. Dispatch keeps its separate original-command receipt policy.
No generic transport, ViewModel join, repository cleanup order, error priority,
direct CCAPI, USB or held-autofocus contract changes are included.

The read caller waits through the existing unmodified 15-second callback deadline.
An external body producer can ignore cancellation and finish later, but cannot
hold the caller's registered operation job open. The callback produces text only.
Original-caller, cancellation and captured-session checks precede acceptance;
the workflow additionally rechecks its invocation/owner before adopting readback.
Unlike an original command response, a late cancelled read cannot update history
or current camera evidence. A read never proves command execution or physical
cleaning, sleep, preview Stop or session retirement.

This covers the required read after keep-power cleaning, explicit read-only
recovery, and the fresh read before an explicit restoration. Restoration still
requires that fresh observation and current safety checks. Cancelling the fresh
read cannot cause a subsequent Live View Start or resume monitoring.

## Runtime baseline and deferred restore Start

The immutable baseline compiled and produced five expected runtime assertion
failures through exact existing ViewModel, repository, workflow and client
methods. A body producer ignored cancellation while the actual registered job
kept Disconnect in its join; repository disconnect had not been reached.

Four cases belong to this repair: initial read cancellation, explicit recovery
cancellation, restoration's fresh-read cancellation, and the real default read
timeout. The latter fired after 15,017 ms while the body remained held. The fifth
case is strict restoration Start cancellation followed by its real phase deadline;
that distinct blocking path is intentionally **not fixed in this batch**.

A separate host ordering probe found that server maintenance Stop can overtake an
already admitted restoration Start before it enters the provider. Start can then
run after Stop, followed by local-only retirement. Bounded restoration Start must
wait for a safe remote request-ordering/ownership contract; merely detaching its
caller could expose that race. This batch therefore keeps strict Start on its
existing transport and preserves its Stop obligation and Disconnect join. A
passing preservation test does not turn that known blocking behavior into a
bounded-return guarantee. Maintenance DELETE HTTP 204 proves local retirement,
not physical Stop.

## Fixed runtime evidence

The main fixed run compiled and passed 64 tests in 16.23 seconds: 15 caller-chain
controls, all 44 existing production workflow cases, and five strict-response/
route controls. The unmodified read deadline fired at 15,013 ms with the body
producer still held; Disconnect completed its real registered-job join and
required cleanup. The three explicit read-cancellation cases also completed while
their external body producers remained blocked.

A later strict-class run passed eight tests in 0.167 seconds, rechecking the five
strict controls and adding three acceptance-fence cases. There are 67 distinct
tests in the read batch across those two executions. All three exact action GETs
return valid typed observations in their positive controls. Thirty malformed
response cases exercise read and unchanged strict-Start parsing separately.
Additional controls reject late acceptance after cancellation, same-text session
replacement and duplicate callbacks; preserve original history/errors and newer
warnings/attempts; prevent evicted-history resurrection; and retain unrelated
operation joining. Normal explicit restore still needs its fresh read and retains
the exact strict Stop duty after successful Start.

Regression runs against the same frozen product source also passed: dispatch
67/67 in 30.727 seconds, cleanup 16/16 in 15.211 seconds and owned Bulb 59/59 in
15.486 seconds. Across the main, additional and three regression runs this is
214 passing test executions, including the explicitly identified repeated strict
cases. Forty Android source/resource checks passed separately; they are not
counted as runtime evidence.

The earlier cleanup route-selection test changes one negative vector from the
newly supported action GET root to its unsupported `/outcome` child. Exact-method,
wrong-owner, extra-path and strict-Start exclusions remain enforced; the new three
GET positive controls verify the intended expansion instead of deleting the gate.

Receipts, compiler output and JUnit text are under
`docs/validation/evidence/android-maintenance-read-2026-10-17/`. The five baseline
failures include the explicitly deferred Start path. They must not be presented
as five repaired bugs. Main and subsequent runs retain their actual fixture
hashes and test-class selections rather than being combined into an invented
single JUnit result.

## Verification boundary

The host runner includes complete production maintenance evidence/workflow and
strict body readers, byte-exact ViewModel submission/continuation/registration/
Disconnect, the complete repository `maintenanceAccess`, and exact client
read/dispatch/cleanup/Start/Stop methods. HTTP and Android/model/UI seams are
explicit adapters. Tests execute production caller functions rather than source
guards, with immutable-source and extraction hashes in each receipt.

This does not compile the whole Android app or validate actual OkHttp sockets,
Compose/native lifecycle, APK packaging, native USB/RTP, or physical cameras.
Compiler and test JVMs use existing checksum-verified tools, one processor and
a 256 MiB heap, without Gradle, downloads or installation. Physical-control
cleanup and unrelated registered jobs remain independent limits.

## Release assessment

Impact: `patch`, repairing cancellation/deadline behavior in existing maintenance
reads. No version change or release is included. Full Android/CI and device
acceptance remain separate requirements. Restoration Start and its server ordering
contract remain explicitly open rather than being included in the read repair's
completion claim.
