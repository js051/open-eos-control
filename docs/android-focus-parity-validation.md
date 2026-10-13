# Android Bridge focus parity validation

## Scope

This candidate extends the existing Android direct-CCAPI held-AF and camera-focus telemetry workflow to a Bridge that explicitly advertises the corresponding optional capabilities. Its base is `23352065ff06947429e9fe3ebb3f800506097d85`; results for that base do not validate these changes.

- One hold owns one saved Bridge session and owner ID. Failed or missing Start acknowledgement retains release responsibility; Stop retries never repeat Start.
- Disconnect attempts original-session cleanup and preserves an unresolved previous-camera warning. A late old result cannot acquire the new connection's controls.
- Telemetry is bounded to 256 KiB and 512 rectangles, strict UTF-8 and finite normalized geometry. Unknown frame kinds cannot be displayed as focused.
- A cancelled telemetry request keeps its reservation until it actually settles, so a restarted loop cannot overlap an uncooperative old read.
- Encoded-image aspect agreement is separate from the desqueezed display rectangle. Display transformation does not make camera coordinates apply to a different image.

The Bridge contract adds `heldAutofocusSupported` and `focusInfoSupported`. Missing, null or non-Boolean flags do not enable the new routes. Old Bridge installations retain their existing bounded autofocus behavior; no speculative held command is sent. This does not add held AF or camera focus metadata to unsupported USB or gphoto2 providers.

## Evidence

Controlled test sources cover the client routes, owner identity, uncertain Start/Stop, cancellation, close cleanup, metadata bounds and parsing, cross-loop read ownership, old-server capability controls, and overlay geometry. Existing direct-CCAPI and business assertions are retained.

The candidate adds 33 controlled JVM test methods:

- `BridgeHeldAutofocusSessionTest`: 7 owner-lifecycle fixtures.
- `DesktopBridgeFocusParityTest`: 18 independent Bridge HTTP-peer fixtures, including strict UTF-8, literal-NUL/UTF-16 and trailing-data rejection.
- `BridgeFocusMetadataTest`: 5 wire-to-model parsing fixtures.
- `CameraFocusReadGateTest`: 2 delayed-cancellation and retired-generation fixtures.
- `CameraShutterRecoveryStateTest`: 1 additional previous-camera focus-warning fixture.

The existing `CameraFocusOverlayTest` geometry case now covers portrait, landscape and tablet viewports at 1x, 1.33x and 2x desqueeze (3 × 3 combinations). These are test sources awaiting execution, not screenshot or device evidence.

At candidate preparation, `git diff --check` passes. Kotlin compilation, JVM tests and API 34/36 instrumentation for this candidate are **NOT_RUN locally**. There is no suitable verified local EOS Kotlin/Gradle dependency graph in this environment. Source review and the prior base's green CI are not substitutes.

The normal exact-head CI must compile the new tests and execute the existing required matrix before this candidate can be described as tested. Emulator results do not establish physical AF release, lens focus, real camera metadata cadence or Bridge-host-loss behavior. Those remain explicit concentrated device-acceptance items.
