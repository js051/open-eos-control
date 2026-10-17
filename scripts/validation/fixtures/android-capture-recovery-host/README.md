# Android capture-configuration recovery host boundary

This offline JVM fixture executes byte-exact production `DesktopBridgeClient.readCaptureConfiguration`, `requestJson`, the fixed read-only callback helper, `CameraRepository.captureConfigurationAccess`, `CaptureConfigurationAttempt`, and `CameraViewModel.recoverCaptureConfiguration`/cancel/registered-operation lifetime/Disconnect joins. Capture evidence, strict JSON framing, file-naming models and parsers are production sources. The runner verifies existing Kotlin/JUnit/coroutines artifact hashes and records source/extraction/fixture hashes. No downloads or Gradle run are involved. Compiler and test JVMs run sequentially, each limited to one processor and 256 MiB heap.

The synthetic HTTP adapter deliberately ignores `Call.cancel` and has no transport timeout enforcement. Held response and held body tests therefore require the app's cancellation/deadline boundary, not a helpful socket. The fixture checks the original registered DIRECTORY job completes before repository Disconnect cleanup while the response/body remains held. The real 15-second application deadline is exercised. Same-text replacement session callbacks must close their own body without adopting old capture settings or publishing status. Exact route, verb, null/stale owner, status-publication and mutation-evidence negative controls keep the special path read-only. Ordinary requests retain synchronous execution.

The capture attempt is seeded as a finished historical attempt without issuing a mutation. This is the explicit recovery entry condition. Repository write closures are extracted, but synthetic mutation adapters fail immediately if invoked. Maintenance teardown helpers are included to exercise the actual registered job join and the strict session-close ordering; unrelated focus/media/Android/UI models remain synthetic.

JSON is a typed registry adapter; production strict framing and size readers still execute over real streams. This does not execute Android, OkHttp itself, Compose, USB, a Bridge server or a physical camera. It establishes the production coroutine/owner/adoption boundary under adversarial transport behavior, not physical-camera compatibility or socket disposal timing.

Run with the previously verified local jar directory:

    python scripts/validation/run-android-capture-recovery-host.py --jars <verified-local-jars> --out <evidence>/baseline --phase baseline
    python scripts/validation/run-android-capture-recovery-host.py --jars <verified-local-jars> --out <evidence>/current --phase current

Baseline is pinned to `34669de2`; success means exactly the three expected runtime failures, never compilation failure. Fixed success requires all focused tests to pass. Raw generated extraction, logs and receipt remain in the explicitly supplied evidence directory.
