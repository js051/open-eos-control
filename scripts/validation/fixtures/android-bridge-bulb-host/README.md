# Android Bridge Bulb host validation

The runner uses a supplied directory of already-installed Kotlin/JVM tools. Every
tool JAR must match its recorded SHA-256 before any compiler executes. It never
downloads dependencies, runs Gradle, builds native code, or touches a camera.
The compiler and test JVM run sequentially with one active processor and a
256 MiB maximum heap. Compilation is bounded to 180 seconds and JUnit to 30 seconds.
Ordinary concurrency cases have a 5-second test bound; the real-default deadline
case has a 25-second bound while three 15-second attempts execute concurrently.

## Baseline counterexamples

Run from the repository root, replacing both paths with existing local locations:

```sh
python scripts/validation/run-android-bridge-bulb-host.py \
  --jars /existing/verified/jvm-tools \
  --out /separate/evidence/android-bulb-baseline
```

The default immutable source baseline is commit
`4faea09a2697b192bb84133f0531704dab302146`. A successful *runner* result means all
four named JUnit runtime counterexamples were observed. JUnit itself must report
four tests and four assertion failures. Compilation failure never counts as red
evidence. The JSON receipt records both exit codes and all production/tool hashes.

The cases show:

- A pending Start response prevents Stop dispatch under the shared mutex.
- A subsequent GET status incorrectly substitutes for an owner-specific terminal
  release receipt after a lost Stop response.
- A late Start response can rearm a retired session's confirmed-start flag.
- DesktopBridgeClient sends the legacy Start route and lacks a request owner.

## Current production state/deadline tests

```sh
python scripts/validation/run-android-bridge-bulb-host.py --phase current \
  --jars /existing/verified/jvm-tools \
  --out /separate/evidence/android-bulb-current
```

Current mode reads the worktree unless `--ref` names a commit. It executes the
checked-in `BridgeBulbRequestSessionTest` and `BridgeBulbCallbackTest` against the
entire production owned-state and deadline-helper sources. Unlike baseline mode,
success requires all JUnit tests to pass. It covers pending Start/Stop ordering,
reservation before dispatch, exact owner and terminal receipts, saved-owner
isolation, coalesced Stop, late retired responses, later status warnings, duplicate
callbacks, callback failure, cancellation and an ignored-cancel body producer.

Current mode also executes byte-exact client preparation, owned command dispatch,
callback response/body consumption, status observation, request admission, Stop,
close, and retirement-ACK code. It tests owned 404 without legacy fallback, matching
owner payloads, session replacement even when the textual session ID is reused,
fresh versus cached release snapshots, response-size limits, and failure ordering.

The production caller's unmodified 15-second default is exercised concurrently by
three synthetic transports: a Stop with no callback, a Start whose response body
ignores cancellation, and a DELETE with no callback. No outer short timeout makes
these pass. The producers remain blocked until after the caller deadlines fire;
tests then release their latches. This contributes approximately 15 seconds to the
JUnit run and stays inside its 30-second process budget.

The UI fixture executes byte-exact ViewModel toggle, owned Start/Stop, retirement,
disconnect, and CameraUiState admission code using a deterministic dispatcher.
It covers immediate Stop while Capture is pending, late-result protection for
replacement state and busy jobs, 404/timeout feedback, newer status/error fences,
and disconnect reaching repository cleanup without waiting for an ignored-cancel
Start or a blocked post-Stop Live View resume. A cached receipt is also passed
through the actual extracted client, actual Access, and actual extracted UI.

The exact repository disconnect body and previous-connection warning classifier
execute against a backend adapter. Owned cleanup must bypass blocked legacy
autofocus/Live View operations; unowned cleanup retains their existing order;
shutter uncertainty remains a previous-connection warning when close fails.
Maintenance-owned close stays an explicitly separate transport boundary.

A deterministic metadata race test holds the historical core monitor while an
ordinary observation passes its outer revision check. A later owner transition
must not let that already-stale observation overwrite the newer warning. This
executes the complete composed session/core classes and validates their shared
monitor, rather than a source-pattern assertion.

## Coverage boundary

The entire unchanged production `BridgeShutterReleaseSession` and
`ShutterReleaseSession` compile and execute. The client Bulb entry points and
Bulb-status parser are byte-exact extracts from production. The runner checks
unique declaration boundaries and records the extracted byte hash.

CameraStatus is reduced to the fields needed by this slice. JSON is a synthetic
typed registry, not a JSON parser. Endpoints, Call/Response objects, HTTP failures,
repository/lifecycle dependencies, and unrelated UI interlocks are explicit
adapters. The ordinary status read has a synthetic read boundary. These tests do not
establish the correctness of org.json parsing, OkHttp configuration, body I/O,
the full Android class graph, Compose callbacks, Android lifecycle behavior,
JNI/native code, APK packaging, or physical-camera behavior. In particular,
callback cancellation tests can prove a production callback awaiter's deadline
without claiming that a real HTTP socket/body stopped on cancellation. The actual
production body-reading loop runs against synthetic InputStreams; real OkHttp
callbacks and sockets are not executed.

The migrated MockWebServer tests in `DesktopBridgeClientTest`,
`DesktopBridgeShutterRecoveryTest`, `DesktopBridgeControlDeadlineTest`, and
`DesktopBridgeLiveViewRecoveryTest` remain separate. Their owned fixtures now echo
the exact owner in terminal receipts. Legacy GET-proof cases deliberately acquire
unowned short-control uncertainty instead of an owned Bulb request. The host runner
does not claim to execute these complete Android/MockWebServer suites.

No dependency substitution can be represented as full Android compilation.
