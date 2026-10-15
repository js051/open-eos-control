# Android magnification → geometry parity candidate

Base: `a04defb0573ee0ff70665431bab686c261a068f0`.

## Scope and release assessment

Backward-compatible repair of the existing Canon CCAPI magnification, detailed Live View, Touch AF and Click WB paths. Release impact: **patch**. No new transport, endpoint, zoom value, capability claim or release/version change. Existing advertised 1×/5×/10× restrictions remain. This candidate is locally implemented, not PR ready or released; exact-head CI and physical-device validation remain pending.

## Counterexample

At the base SHA, a byte-exact extraction of the production magnification and coordinate methods reproduced a stale RTP coordinate after 1× → 5×: center returned `(3100, 2200)` from the old `(100, 200, 6000, 4000)` rectangle, rather than `(1600, 1900)` from the newly supplied `(1000, 1500, 1200, 800)` rectangle. Baseline compilation exited 0; JUnit exited 1. The extraction substitutes HTTP, JSON and unrelated DTOs; it is not a full Android or camera run.

## Changes

- Retire cached geometry before magnification dispatch, and again on every terminal outcome. An acknowledgement alone cannot restore geometry. Invalid/missing readback, failure and cancellation still require fresh detailed metadata.
- Scope geometry reads, cache publication, magnification readback and coordinate HTTP dispatch to the admitted revision. Session/source retirement revokes old work, including local maintenance retirement before its first possible suspension; old finally blocks cannot clear replacement state.
- Reject non-integral/coerced, negative, empty and overflowing reported rectangles. Require the complete detailed packet envelope to be consumed, rejecting valid image/info prefixes followed by truncated packets or trailing bytes.
- Retire pending multipart mailbox frames both before and after mutation. A frame read carries its mailbox epoch before its blocking read, so an old in-flight read cannot publish into the new epoch.
- Block coordinate interaction while waiting for post-command local presentation; discard old bitmap ownership, cancel old reads and rebind only the original live-view owner. Same-owner cancellation schedules recovery without reviving a replaced source/session.
- Pause native rendering during the change. Carry the existing rendering revision from local RTP receive admission, through partial-assembly retirement, the access-unit queue and codec metadata, into presentation. Old locally queued input cannot acquire a fresh rendering identity at output.
- Preserve existing terminal Live View failure/stop-recovery behavior and audio foreground intent through the existing pause/resume path.

## Verification boundaries

The scoped evidence runner compiles the complete pure geometry state and multipart reader/session source. Multipart transport objects are minimal Call/Response stubs; production reader threads and monitor logic execute against deterministic in-memory bytes. Other host fixtures compile byte-exact CCAPI command/mapping/parser and ViewModel magnification declarations with explicit HTTP, JSON, repository and UI substitutes. Existing pure RTP progress/surface-wait regressions are included.

The shared twelve rectangles are supplied independently by the fixture and are not derived from zoom ratios. The host runner translates the JSON into SHA-bound Kotlin constants; the native resource loader is not exercised. Fixture SHA-256: `e73afb6875e8d1819e2e57d2e0f509ece850f68fb69fc925e5b88cfeffcd7609`.

Additional real OkHttp tests are authored for the 1→5→10→1 coordinate journey, malformed/truncated detailed metadata, and multipart mailbox/in-flight retirement. They have not run in this offline host environment. Kotlin PSI checks syntax only, not Android type checking. Source-wiring assertions do not execute MediaCodec.

**Not run:** Gradle/full Android compilation, real OkHttp integration tests, Compose, MediaCodec/GLES/device/native runtime, physical camera, remote CI, push or publication. Native camera/network buffering and physical pixel timing remain unverified; the fix establishes only known local ownership boundaries.

The scoped evidence receipt records commands, real exit statuses, extraction hashes and source hashes. Earlier intermediate fixture failures are documented there separately from the final run. No fixed pending KVM snapshots were modified.
