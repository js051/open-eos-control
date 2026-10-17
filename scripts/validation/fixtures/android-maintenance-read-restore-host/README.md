# Android maintenance read / restore ownership host

This separate offline runner extracts byte-exact production Kotlin from immutable
baseline `a42aad06d0c2521c3196ce6773dda62f9a0856ac`, or a supplied current ref. It
preserves the earlier dispatch and cleanup runners and their evidence. Existing
JARs must match their installation receipt. No Gradle, download, installation,
network, browser, native build, Android emulator, or camera is used. Compiler and
JUnit run sequentially with `-Xmx256m -XX:ActiveProcessorCount=1`.

```sh
python scripts/validation/run-android-maintenance-read-restore-host.py \
  --phase baseline --jars /existing/verified/jvm-tools --out /separate/baseline
python scripts/validation/run-android-maintenance-read-restore-host.py \
  --phase current --jars /existing/verified/jvm-tools --out /separate/current
```

Baseline must compile and execute all five named runtime-red probes. Compilation
failure, source guards, missing tests, or different failed assertions do not count.
The tests execute keep-power sensor cleaning with initial strict Live View Stop,
command dispatch and required GET; explicit `recoverMaintenance` GET; explicit
`restoreMaintenance` fresh GET; and strict restore Start. A body producer ignores
`Call.cancel` and remains held until `finally`. The actual unmodified 15,000 ms
read deadline is exercised. Restore mutation intentionally retains the production
NonCancellable phase with a configured 15-second budget; its cancellation probe
waits for that real phase deadline rather than requiring immediate mutation
abandonment.

At a failed join, the tests require `repository.disconnect == 0`, teardown still
pending, and the real registered maintenance job incomplete. They do not bypass
the join. Fixed read cleanup must run strict session DELETE. A separate
compatibility control leaves admitted strict Start blocking and verifies that its saved strict
Stop executes before DELETE only after that Start producer returns. Core caller
probe traffic stays inside the maintenance namespace, and Start must have saved its Stop duty before I/O.

## Exact production boundary

Entire sources: MaintenanceWorkflow, MaintenanceEvidence,
BridgeShutterReleaseSession, ShutterReleaseSession, BridgeBulbRequestSession,
BridgeBulbDeadline, ShootingSettingJson, and StrictMediaJson.

Byte-exact extracted methods include:

- CameraViewModel submit/cancel/recover/restore/continueMaintenance, its publication
  helper, full launchCameraOperation registration/lifetime, and full Disconnect.
- CameraRepository maintenanceAccess, including the captured initial preview and
  repository liveViewRunning write after restore returns.
- DesktopBridgeClient maintenance access/dispatch/read/cleanup, strict Start/Stop,
  postJson/requestJson, close/local retirement, and exact-owner helpers.

Synthetic adapters are restricted to HTTP Call/Response/InputStream, typed-registry
JSON objects, unrelated camera/focus/media/UI models and hooks, coroutine scope/
queue, and repository disconnection entry counting/forwarding. Production bounded
UTF-8/framing/JSON validation still runs. Each receipt records source, extracted
block, fixture and tool checksums.

Current controls exercise original-attempt/history preservation, same-text
replacement session identity, no late read publication or auto restore,
strict response validation, exact routes/no-cache read headers, and unchanged
non-maintenance generic transports. Strict Start remains on its original blocking
transport and is an explicit known limitation, not a repaired path. Current mode includes all production
MaintenanceWorkflowTest cases. `--test-class` can run a compiled class subset.

This is host protocol/lifecycle evidence only. It does not prove full Android,
OkHttp, Compose, APK, physical USB/CCAPI, camera or server-worker safety. In
particular a bounded Android waiter alone does not establish that a remotely
admitted restore Start cannot race server Stop/session retirement.
