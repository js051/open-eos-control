# Android owned maintenance restoration host

Offline, single-JVM runner using existing checksum-verified Kotlin/JUnit tools.
Every compiler/test JVM has `-Xmx256m -XX:ActiveProcessorCount=1`. No Gradle,
installation, download, network, Android emulator, native build or camera is used.

```
python scripts/validation/run-android-maintenance-restore-token-host.py \
  --phase baseline --jars /existing/jvm-tools --out /separate/baseline
python scripts/validation/run-android-maintenance-restore-token-host.py \
  --phase current --jars /existing/jvm-tools --out /separate/current
```

Baseline reads immutable `06e02db764a0ae6d5982b1e135502ced95a514b4`, compiles
production code and requires three runtime failures: missing prepare, absent
Start/Stop token echo, and actual legacy Start despite missing permit. Compilation
failure is never a successful baseline. Current mode runs token contract controls,
actual registered-operation/Disconnect deadline cases, warning publication and
recovery controls, plus all existing MaintenanceWorkflowTest cases.

## Exact production boundary

Whole production maintenance evidence/workflow, ShutterReleaseSession,
BridgeShutterReleaseSession, BridgeBulbRequestSession, BridgeBulbDeadline,
ShootingSettingJson and StrictMediaJson. Byte-exact client state declarations,
maintenance access, dispatch/read/cleanup, strict Start/Stop, status observation,
postJson/requestJson and exact-session/retirement helpers. Byte-exact complete
repository maintenanceAccess and maintenanceStopRecoveryAccess. Byte-exact VM
submission, continuation, cancellation, publication helpers, refresh,
readCameraStateSnapshot, maintenance Retry Stop, launchCameraOperation and
Disconnect. Source/extraction/tool/fixture hashes are retained in each receipt.

HTTP Call/Response/InputStream, registry-backed JSON, status-read input and
unrelated Android/camera/media/capability/diagnostic/shutter-adoption seams are
explicit adapters. The UI state adapter allows STATUS alongside LIVE_VIEW, as
production does for these cases. Its recovery flag represents production's
computed warning predicate. The status publication tests execute the actual
Bridge revision observer before the actual VM refresh publisher and Retry Stop.
The additional UI-only injection case is a conservative identity-fence control;
it does not by itself prove an app publisher can bypass that observer.

Ignored cancellation is tested before response delivery and during body reading.
The unchanged 15,000 ms phase/deadline is real; tests do not fake or shorten it.
The registered maintenance job must finish while the external producer remains
held, then strict original-token Stop and session DELETE must execute. Releasing
the old producer later cannot publish state, replay Start, restore monitoring or
modify a same-text replacement session. DELETE 204 is local retirement evidence,
not proof of a physical Stop.

The server's request-ordering guarantee is established separately by the server
ownership suite. These Android fixtures do not reproduce a physical camera or
validate real OkHttp, the full Android/Compose build, APK, native lifecycle or
USB/RTP. Ordinary direct CCAPI/USB/AF behavior is not rewritten.
