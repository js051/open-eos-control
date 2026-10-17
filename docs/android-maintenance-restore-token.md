# Android Bridge request-owned maintenance restoration

## Contract and scope

This follows dev `06e02db764a0ae6d5982b1e135502ced95a514b4` and targets the
server ownership contract in `4715590389bd13aad16c96f04f81fd8abc71db9d`.
Only existing Android Desktop Bridge maintenance restoration changes. Direct
CCAPI, USB, held AF, generic transport and the operation-job join stay unchanged.

An initial strict Stop that plans to restore a previously running preview sends
`prepareRestore: true`. Only a fresh affirmative Stop can save a valid
`restoreToken`. The permit belongs to the captured access/session, never a later
maintenance journey. Start sends that token and requires its exact echo,
`restoreOutcome: started`, `active: true` and `liveViewStopUnconfirmed: false`.
The original token and strict Stop endpoint are saved before Start I/O.

Stop sends the obligation's original token. A fresh `stopped` receipt needs exact
token identity and both false booleans. A receipt-only `stopped` or
`cancelled-before-start` may discharge only that obligation at the same warning
revision; it is not a fresh camera observation. Partial state, wrong identity,
wrong outcome and newer warning revisions retain responsibility. A new warning
before retry selects an explicit no-token global strict Stop, which must return
fresh double-false proof. No automatic extra Stop is sent. Preparing a second
cleaning journey after a confirmed restored preview sends a new prepare request,
not the first journey's token. Old closures cannot borrow the new permit.

An absent or malformed initial token prevents Start before network dispatch.
The maintenance receipt uses a typed `BRIDGE_UNAVAILABLE` outcome and English /
Traditional Chinese resources explaining that Bridge must be updated and the
connection deliberately reopened. Old Bridge 404/405 responses never trigger a
legacy Start fallback. Idle restoration with no previous preview remains local.
This is an explicit old-Bridge compatibility restriction, not a claim that every
version supports the new workflow.

## Bounded caller and retained evidence

Only the exact strict Start path with its captured restoration context joins the
existing whole-call callback deadline, after the server ordering suite passed.
The unmodified 15-second budget covers response delivery and full body reading.
Cancellation of the transport alone is never treated as Stop proof. The retained
mutation phase may wait until its real deadline, then releases the registered
job even if an external producer ignores cancellation. Disconnect still joins
that job, executes saved-token strict Stop, then strict session DELETE and local
retirement. Late Start callbacks cannot publish, replay, restore monitoring,
clear a new warning or retire a replacement session.

Original maintenance command/history/error evidence and fresh read requirements
remain separate. A command response, reachable GET, cancelled-before-start
receipt or DELETE 204 does not prove physical cleaning, sleep or camera Stop.
Physical AF/Bulb cleanup and unrelated operation joins retain their own limits.

## Warning publication and explicit recovery

The actual Bridge `status()` observer updates the Live View warning revision
before returning to the actual ViewModel `refresh()` publisher. Tests execute
that observer, publisher, maintenance Retry Stop and repository closure together.
A warning before retry requires fresh global Stop. A warning during owned Stop
rejects the old receipt; the next explicit retry obtains fresh global proof.

A newly found after-receipt edge was reproduced: once A's receipt cleared its
owner, status warning B created an ordinary owner, causing the maintenance
repository recovery gate to reject Retry. The repair preserves the existing
strict maintenance policy when creating B's owner. The next explicit retry
sends the exact original-session strict Stop with no token and clears current B
only after fresh proof. No new backend API or automatic command was added.

A small ViewModel identity fence also preserves a different UI status arriving
while Stop waits. Its injection-only test is a conservative fence control, not
evidence that a real publisher bypasses the Bridge observer. Production
before/during/after publication controls are the decisive recovery evidence.

## Runtime evidence

- Immutable dev baseline: three expected runtime failures, compile exit 0,
  showing missing prepare, missing token echoes and actual Start without permit.
- A contract-implemented, still-blocking source stage: two real caller failures
  in three tests. Cancellation followed by the phase deadline and the default
  timeout (15,025 ms) both left the actual registered job incomplete and
  `repository.disconnect == 0` while the producer remained held.
- UI identity pre-fix stage: one expected runtime failure, overwriting newer
  status warning true with false.
- Production status/retry pre-fix stage: two controls passed, after-publication
  failed because the new warning owner lost strict maintenance policy.
- Final new suite: 68/68 passed in 45.649 seconds, comprising 24 new controls and
  all 44 existing workflow cases. The real default restore Start deadline was
  15,022 ms. Additional real-budget cases hold response delivery or body reading
  despite cancellation, complete the actual join and cleanup, then verify late
  callbacks against a same-text replacement session.

The owned Start strict matrix separately rejects 15 malformed framing, status,
length, UTF-8 and JSON cases. Receipt controls cover no-state terminals, partial
state, bad echoes, newer warning before/during Stop, two distinct journeys and
replay prevention. Existing read tests now prepare tokens before strict Start,
assert callback transport and retain the registered join; no exact-route gate
was removed. Prior host metadata declarations are now byte-exact extractions
rather than copies of superseded private fields.

Final-source regressions passed: read 67/67 (16.257 s), dispatch 67/67
(30.762 s), cleanup 16/16 (15.219 s) and owned Bulb 59/59 (15.51 s).
Together with the new suite this is 277 passing executions; shared workflow
cases are deliberately repeated, so this is not a unique-test count. Forty-two
Android source/resource checks passed separately. The immutable three-red token
baseline was rerun with the final runner and compiled successfully (0.108 s).

Compact compiler/JUnit/receipt evidence is under
`docs/validation/evidence/android-maintenance-restore-token-2026-10-17/`.
Stage receipts retain their actual source/extraction/fixture hashes. The expected
revision rejection prints its existing production diagnostic stack trace during
a passing recovery test; this is not an uncaught JUnit failure.

## Verification and release boundary

The runner executes full production evidence/workflow/body-reader classes and
byte-exact client, repository and ViewModel methods. Explicit HTTP/model/UI
adapters remain. It does not compile the entire Android app or exercise real
OkHttp sockets, Compose, APK packaging, native lifecycle, USB/RTP, iOS or a
physical camera. Server request ordering is separately verified by its own
suite. Local JARs are checksum-verified; each JVM is single-processor with a
256 MiB heap. No Gradle, downloads, installations or network calls are used.

Release assessment: `minor` if included in a pre-1.0 distribution requiring the
new Bridge restoration protocol; legacy peers cannot perform this restoration
with the updated Android caller. This batch changes no version and performs no
release. The aggregate release decision and full native/device acceptance remain
separate.
