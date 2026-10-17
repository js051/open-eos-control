# Android Bridge maintenance cleanup deadlines

## Scope

This follows the Android owned-Bulb work at `f9eb13d21a994ab502f02c70a0157129ec56bcfd`.
The narrow repair covers these existing saved-session maintenance cleanup routes:

- `POST /v1/session/{id}/maintenance/control/liveview/stop`
- `DELETE /v1/session/{id}/maintenance/control/events`
- `DELETE /v1/session/{id}/maintenance/control/session`

It does not change maintenance dispatch, readback or preview restoration; the
maintenance workflow, direct CCAPI, USB and physical-control contracts remain
separate. The change is local implementation and host validation, not a full
Android build, device acceptance or release.

## Required behavior

The cleanup caller uses `Call.enqueue` and the existing callback awaiter with its
unmodified 15-second whole-call deadline, even when an external response-body
producer ignores transport cancellation. The underlying call also receives a
maximum 15-second timeout, using the existing single-attempt strict client.
Cancellation and timeout do not prove that Live View, an event request or the
remote session stopped. Existing strict response checks and saved-owner checks
remain mandatory. The callback may produce data but cannot publish camera state
after its caller has timed out or retired.

Maintenance close retains its existing order: physical release, optional strict
Live View Stop, strict remote session retirement, then local retirement. Events
Stop remains the existing separate maintenance-access phase. Neither a failed
strict route nor an older Bridge authorizes fallback to generic cleanup routes.
The original error cause, suppressed physical-release errors and independent
Stop/retirement warning flags must remain intact.

## Explicit limits

The deadline applies independently to each cleanup HTTP leg, rather than a
15-second budget for every phase combined. Multiple required phases can consume
multiple budgets. It does not establish that a transport callback thread or real
HTTP socket has exited; a noncooperative producer can still finish later.

ViewModel disconnect still joins unrelated operation jobs before repository
cleanup. General maintenance dispatch/read/restore and held-autofocus physical
release are unchanged. A wait in one of those paths can prevent reaching these
cleanup routes. Therefore this repair must not be described as proving that every
Android maintenance disconnect returns within a fixed deadline.

### Follow-on caller-boundary repair

The following counterexample describes the cleanup-only baseline. It was later
confirmed by exact production runtime tests and repaired separately in
[Android Bridge maintenance dispatch and Disconnect](android-maintenance-dispatch-deadline.md).
That repair leaves the cleanup order and job join intact; general readback and
preview restoration remain separate boundaries.

A concrete existing entry is `CameraViewModel.submitMaintenance` with
`MaintenanceAction.SENSOR_CLEANING`, no active preview/events, and a valid frozen
owner. It registers `CameraOperation.MAINTENANCE` through `launchCameraOperation`,
then calls `MaintenanceAttempt.run`. That method's command phase wraps
`access.dispatch` in `withTimeout(15_000)`. The Bridge access dispatch sends
`POST /v1/session/{id}/maintenance/sensor-cleaning/outcome`, which intentionally
still enters the blocking branch of `maintenanceJson`.

The smallest follow-on host probe can hold that response body, make its producer
ignore cancellation, then call the exact ViewModel `disconnect` method. Disconnect
retires the attempt and cancels its registered job, but joins that non-Bulb job
before `repository.disconnect`. Assert that repository close has not been entered
while the body remains blocked, and release the latch only in test cleanup.
Repeat against the command's real default timeout if a deadline claim is needed.
This caller chain was source-confirmed but had not been executed in this cleanup
batch; the linked follow-on contains its runtime evidence. Readback and explicit restore use
the same unconverted transport, but do not need to be mixed into the first probe.

## Validation

`scripts/validation/run-android-maintenance-cleanup-host.py` uses only existing
receipt-verified Kotlin/JVM tools, with one active processor and a 256 MiB maximum
heap. Production method extracts and dependencies are hashed; adapters and
unexecuted Android boundaries are recorded beside the test receipt and in the
fixture README. No dependencies were downloaded and no Gradle/native tools ran.

The immutable baseline compiled and produced four expected runtime assertion
failures: caller cancellation could not finish strict Stop while `execute`
ignored cancellation, could not finish session DELETE under the same condition,
and could not finish while the production strict body reader remained blocked.
The fourth case did not cancel the caller: the original 15-second Stop timeout
fired and `Call.cancel` was recorded, but close still did not return while its
body producer remained blocked. The four-test baseline took 16.524 seconds.
Each producer stayed blocked until the test deliberately released its latch.

The final fixed maintenance suite compiled and passed 16/16 tests in 15.2 seconds.
Four independent cases exercised the real, unmodified 15-second default together:
no-callback strict Stop, no-callback events DELETE, no-callback session DELETE,
and session DELETE with a body that ignored cancellation. The Stop deadline still
allowed the existing session DELETE phase to run. Other cases cover exact route
selection, saved-session replacement, late-body suppression, strict empty 204,
framing/length/UTF-8/size rejection, original-caller gates and error priority.
Forty existing Android source/resource checks also passed.
The unchanged earlier owned-Bulb host suite also compiled and passed 59/59 tests
in 15.472 seconds against this production source.

These tests execute full production state/deadline/strict-reader sources and
byte-exact client methods. The HTTP objects and body producers are synthetic;
org.json decoding, the complete client constructor and Android class graph,
OkHttp configuration/socket behavior, Compose/lifecycle, APK packaging and real
cameras remain unverified. The tests prove caller return despite a blocked
synthetic producer, not that cancellation makes a physical socket stop.

## Release assessment

Impact: `patch`, because this repairs cleanup of an existing Bridge workflow.
No version change, publication, device validation or physical-camera claim is
part of this local batch. Exact-SHA CI and complete Android compilation remain
separate requirements for delivery through the repository's normal workflow.
