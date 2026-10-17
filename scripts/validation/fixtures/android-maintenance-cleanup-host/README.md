# Android maintenance-owned cleanup host validation

This separate runner leaves the existing Android Bridge Bulb host suite unchanged.
It uses only a supplied directory of already-installed Kotlin/JVM tools, verifies
all JARs against `installation-receipt.json`, and never downloads dependencies,
invokes Gradle, builds native code, or accesses cameras/network services. Compiler
and JUnit run sequentially, each with a 256 MiB heap and one active processor.
Compilation has a 180-second process limit; JUnit has a 45-second process limit.

```sh
python scripts/validation/run-android-maintenance-cleanup-host.py \
  --phase baseline --jars /existing/verified/jvm-tools \
  --out /separate/evidence/maintenance-baseline
python scripts/validation/run-android-maintenance-cleanup-host.py \
  --phase current --jars /existing/verified/jvm-tools \
  --out /separate/evidence/maintenance-current
```

Baseline uses immutable commit `f9eb13d2` (resolved to a full SHA in the receipt).
The exact production close pipeline executes four runtime counterexamples:
strict Live View Stop blocked inside `Call.execute`, session DELETE blocked inside
`Call.execute`, and strict Stop blocked inside the production body-reading loop.
After dispatch, caller cancellation must finish cleanup without waiting for the
synthetic transport that deliberately ignores `cancel()`. Each test checks that
the producer latch remains blocked, then releases it in `finally`. Baseline JUnit
must compile successfully and report the four named assertion failures; only
that exact expected result makes the baseline runner exit successfully. A compile
failure never counts as a runtime counterexample. The fourth case never cancels
its caller or injects a shorter timeout: it waits for the original production
15-second strict Stop deadline to trigger `Call.cancel()`, confirms the body
producer remains blocked, and then shows that close still has not returned. Only
after that assertion does the test release the body. Thus the baseline includes
both cancellation counterexamples and a real-default deadline counterexample.

Current mode uses the worktree unless `--ref` selects an immutable commit. It
requires every JUnit test to pass. It exercises exact strict cleanup routes,
ordering and saved-owner identity, full response validation, late callbacks/body
completion, caller cancellation, and the original maintenance-owner gate before
accepting parsed data. It preserves the existing physical-release/retirement
error priority and verifies both success and failure of session DELETE.

The production default deadline is not shortened or replaced. Four independent
synthetic transports run concurrently in the same JVM: strict Stop with no
callback, session DELETE with no callback, maintenance events DELETE with no
callback, and DELETE with a body that ignores cancellation. The calls must finish
at approximately 15 seconds while the body producer remains blocked. The Stop
case must then issue session DELETE. These are application caller-deadline tests;
they do not claim that physical sockets obey cancellation.

## Production versus host boundaries

The following complete, unchanged production sources compile and execute:
`MaintenanceEvidence`, `BridgeShutterReleaseSession`, `ShutterReleaseSession`,
`BridgeBulbRequestSession`, `BridgeBulbDeadline`, `ShootingSettingJson`, and
`StrictMediaJson`. The runner inserts byte-exact production methods from
`DesktopBridgeClient` for maintenance close/local retirement, `maintenanceAccess`,
maintenance transport and cleanup helpers, Live View Start/Stop, `postJson`, the
entire `requestJson` method, legacy `stopEventPolling`, and exact session identity
checks. The receipt records full source hashes, every extracted block hash, host
fixture hashes, verified tool hashes, process exit codes, and limits.

HTTP URL/Call/Response objects, callback scheduling, InputStreams, CameraStatus,
focus/Bulb physical-release seams, and unrelated requestJson dependencies are
explicit synthetic adapters. The JSON object adapter is a typed registry whose
wire keys are valid JSON; production strict UTF-8, finite-size body reading and
strict JSON grammar validation still run, but org.json decoding is not tested.
The full DesktopBridgeClient constructor, OkHttp socket/configuration behavior,
Android class graph, Compose/lifecycle, JNI/native builds, APK packaging and
physical-camera behavior remain outside this host boundary.

Close order remains physical release, optional strict Live View Stop, strict
session DELETE, and local retirement. Close does not add an events DELETE.
Maintenance events DELETE is separately exercised through the unchanged
`maintenanceAccess.stopEvents` closure. Non-cleanup action/read/restore paths
remain on the existing blocking transport and retain their prior semantics; the
negative-route controls verify this explicitly. The legacy `stopEventPolling`
method is extracted, but its unrelated legacy transport is not exercised here.
