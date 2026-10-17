# Android Bridge-owned Bulb requests

## Scope

This repairs the existing Android Desktop Bridge Bulb Start/Stop flow. The integration
baseline is `4faea09a2697b192bb84133f0531704dab302146`. Direct CCAPI, USB, held AF,
recording, and unowned short-control recovery keep their existing contracts.
This is local implementation and host validation, not an Android build, device
acceptance, release, or physical-camera claim.

## Command ownership

`DesktopBridgeClient.prepareOwnedBulb` synchronously reserves a UUID before the UI
launches Start. Its access handle retains the exact client, session object, and
request owner. Start and Stop use the additive `/bulb/start/owned` and
`/bulb/stop/owned` routes with `{ownerId}`. An older Bridge returning 404/405 fails
closed. There is no fallback to legacy Start.

The composed legacy/owned states share one metadata monitor, so revision validation
and observation storage cannot be separated by a concurrent owner transition.
The metadata lock is never held over HTTP. Pending Start already owns release
responsibility, so Stop can dispatch independently. Repeated Stop callers join
one in-flight attempt. A matching `released` or `cancelled-before-start` receipt
clears only that owner. Supplied release status must contain literal false for
both `bulbExposureActive` and `shutterReleaseUnconfirmed`. Ordinary status reads,
HTTP errors, cancellation, and timeout cannot clear owned responsibility.

A cancelled-before-start receipt is a command-ordering acknowledgement, not a
claim that an independently active camera control has stopped. Cached receipts
omit status. The state machine retains newer observations and removes only its
own unchanged Start snapshot's active flag, making it unknown rather than false.
A later UI status identity also survives an older Start/Stop completion. The
separate fresh-release-observation bit permits automatic preview resume only
when the Stop actually carried a current dual-false observation.

The legacy `BridgeShutterReleaseSession.stop(release, read)` contract remains for
unowned short-control recovery. Owned failures do not enter that legacy latch,
and owned status display does not fabricate a global unowned warning. The UI
has separate owned responsibility, pending, and uncertainty fields.

## Bounded cleanup

The owned HTTP adapter uses `Call.enqueue`. A production coroutine callback
awaiter has a 15-second total deadline, independent of whether transport cancel
or an external response-body producer cooperates. The callback reads at most
64 KiB, closes the response, and returns only data. A late callback cannot publish
camera state. The underlying call also receives a maximum 15-second timeout;
requests retain the existing no-replay mutation client.

Stop remains available during pending Start. Other camera mutations remain
interlocked. Start/Stop completion and busy cleanup are fenced by saved UI owner,
command identity, and camera generation. New recovery text uses existing typed
app-message tokens and English/Traditional Chinese resources.

Backgrounding requests the saved owner's Stop without closing the connection.
Disconnect and ViewModel retirement synchronously fence the saved access. They
cancel, but do not pre-join, its Start or post-Stop preview-resume UI jobs. The
Bridge client joins any already-running owned Stop itself and then attempts
bounded DELETE, even after Stop failure. Repository cleanup does not first await
legacy preview I/O for that retirement. A release failure remains the primary
exception if DELETE also fails, preserving the previous-camera safety warning.
A replacement session cannot inherit the old command or its completion.

After a session has entered maintenance, it still uses the strict maintenance
Stop and retirement routes. The original owned-Bulb batch did not establish a
deadline for that branch's blocking `maintenanceJson` cleanup. The separate
[maintenance cleanup repair](android-maintenance-cleanup-deadline.md) addresses
the three exact strict cleanup routes without changing general maintenance
dispatch/read/restore or existing held-AF cleanup. The ordinary owned Stop +
DELETE evidence below does not by itself validate that separate branch.

## Host evidence

`scripts/validation/run-android-bridge-bulb-host.py` uses only an existing
receipt-verified Kotlin/JVM tool directory. It never downloads dependencies or
invokes Gradle/native tools. Compiler and test JVMs run sequentially with one
active processor and a 256 MiB heap limit. See its fixture README for commands
and exact adapter boundaries. Every run writes source/tool hashes, compiler
exit status, JUnit output, and a receipt.

The immutable baseline compiled and produced four expected runtime assertion
failures: Stop blocked behind pending Start, GET incorrectly substituting for
owned release proof, late Start rearming a closed owner, and legacy Start wire.
A separate saved UI production snapshot also compiled and reproduced timeout
leaving stale pending text rather than a retryable warning. A held-monitor race
case also compiled and failed before the shared-monitor fix: an old read passed
revision validation, waited on the second lock, then erased a newer warning.

The final frozen-source host run compiled successfully and passed 59/59 runtime
tests in 15.479 seconds. Forty existing Android source/resource checks also passed;
these are separate from runtime evidence.

Current tests execute the whole owned state machine and callback helper, plus
byte-exact production client, UI admission/retirement, and interlock methods.
They cover owner matching, no legacy fallback, pending Stop, coalescing, cached
receipts, newer observations, replacement identity, retired callbacks, and
ignored-cancel cleanup. One runtime case waits the real default 15 seconds with
simultaneous synthetic no-callback Stop, blocked-body Start, and blocked DELETE;
no outer short timeout substitutes for the production deadline.

The ordinary MockWebServer test fixtures were migrated to owned envelopes and
unowned-recovery controls, but cannot be run in this offline host toolset because
OkHttp/Okio/org.json/Android dependencies are absent. Host transport, JSON, and UI
models are explicit synthetic adapters; this does not establish real OkHttp
socket cancellation, Android lifecycle/Compose behavior, full source/resource
compilation, APK packaging, native behavior, or physical camera compatibility.
Those remain separate acceptance work. Existing source/resource checks are
supplementary and do not replace the production runtime cases.

## Delivery assessment

This is a patch-level safety repair to an existing feature, using the already
implemented additive Bridge ownership contract. No product version, release,
remote branch, or PR is changed here. Exact-SHA CI, Android build/device testing,
and physical-camera validation are still pending.
