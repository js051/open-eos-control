# Android maintenance dispatch / Disconnect ownership host

This offline runner compiles byte-exact production Kotlin with existing tools
whose JAR hashes must match the supplied installation receipt. It never downloads,
installs, uses Gradle/native builds, opens a browser, or accesses a camera/network.
Compiler and JUnit processes run sequentially with 256 MiB heap and one processor.

```sh
python scripts/validation/run-android-maintenance-dispatch-host.py \
  --phase baseline --jars /existing/verified/jvm-tools --out /separate/baseline
python scripts/validation/run-android-maintenance-dispatch-host.py \
  --phase current --jars /existing/verified/jvm-tools --out /separate/current
```

Baseline is immutable `1ca763aa62a6a5fed229b03b3cb3dacf500b32f6`. It must compile,
execute, and report exactly the two named runtime assertion failures. A compile
failure, source-text check, unexpected assertion, or missing test is not evidence.

The chain executes exact `CameraViewModel.submitMaintenance`, its entire real
`launchCameraOperation` registration/lifetime, whole `MaintenanceAttempt` and
`MaintenanceEvidence`, actual `DesktopBridgeClient.maintenanceAccess.dispatch`,
and exact `CameraViewModel.disconnect`, including the registered operation job
join. One probe explicitly cancels the maintenance attempt; the other waits for
the unmodified production default 15-second deadline without caller cancellation.
The response body deliberately ignores Call.cancel and remains held. If the
Disconnect join cannot finish, the fixture checks that repository disconnect has
not been reached, teardown remains pending, and the registered job is unfinished.
The producer is released in finally. The fixed version must finish that same join
and actual maintenance close/session DELETE while the producer remains held.

Current mode also executes the complete existing `MaintenanceWorkflowTest` source,
three direct production atomic-unavailability fence controls, and two strict
route/response controls. The dispatch rejection matrix covers 15 invalid framing,
length, UTF-8, body-size and JSON cases. The atomic-fence tests are current-only
because that API does not exist at the immutable baseline. `--test-class` may run
a named compiled class independently when collecting incremental evidence without
repeating the real 15-second deadline tests.
Additional controls cover late valid/malformed/mismatched responses, HTTP failure,
duplicate dispatch and callback delivery, original retired/cancelled history,
evicted history, same-text replacement session identity, preserved retirement and
physical-release warnings, unrelated jobs that Disconnect must still join, and
late clock proof versus newer explicit recovery evidence. No automatic read,
restore, replay, or new-session state publication is permitted from old callbacks.

## Explicit adapter boundary

Entire production sources: MaintenanceWorkflow, MaintenanceEvidence,
BridgeShutterReleaseSession, ShutterReleaseSession, BridgeBulbRequestSession,
BridgeBulbDeadline, ShootingSettingJson, StrictMediaJson. Byte-exact extracted
DesktopBridgeClient methods include maintenance access/dispatch/cleanup/read,
local retirement/close, Live View Start/Stop, postJson/requestJson, stopEventPolling,
and session identity checks. Byte-exact UI methods include submitMaintenance,
cancelMaintenance, publishMaintenanceStopWarning, launchCameraOperation, disconnect.
Every source/extracted-block/fixture/tool hash is recorded in the result receipt.

Synthetic adapters provide HTTP Call/Response/InputStream, typed-registry JSON
objects, unrelated camera/media/focus model seams, UI state, coroutine lifecycle
scope/queue, and a repository forwarding adapter. That adapter counts entry to
repository disconnect and calls exact production closeMaintenanceSession; it does
not simulate the registered job join. Production strict JSON grammar/UTF-8/bounded
body validation still execute. The baseline and main fixed probes use sensor
cleaning with auto-power-off, no preview/events, and no prior safety duty. Separate
controls inject cleanup failure through existing production close behavior.

This is host protocol/lifecycle evidence, not a full Android/OkHttp/Compose build,
APK verification, physical USB/CCAPI testing, or physical-camera validation.
