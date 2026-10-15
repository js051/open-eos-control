# Android RTP audio lifecycle verification — 2026-10-15

Status: **implemented; focused JVM verification passed; Android native validation NOT_RUN**.
Base commit: `f765e361f2b8c563f1f881d8a6e041835acf33e6`.
This is not PR-ready, main acceptance, a release, or physical-camera/audio evidence.

## Implemented behavior

- Each session starts muted. Each explicit enable owns a separate bounded AAC queue, playback epoch, player, and callbacks. Background/pause, mute, receiver failure, parser failure, or a stall revokes the request. Foreground return, fresh packets, parser recovery, and output recovery never restore sound automatically.
- The production pump polls decoder output while input is quiet. A coroutine `select` atomically chooses input or a drain tick; a timeout wrapper cannot silently consume and discard an input at its deadline.
- The first fresh PCM deadline starts at manual enable, including time waiting for a previous writer to retire. Independent owner-side monitoring continues across native calls and requires a distinct actual-PCM event within five seconds. Repeated telemetry, queued input, format changes, and discontinuities cannot refresh that evidence.
- Local freshness is one second from receipt of an AAC sample by the current owner. It is checked before codec queueing, before every non-blocking PCM write, and for submitted frame ranges that have not been observed past the playback head. An expired range retires/flushes the output; a late head advance cannot retrospectively certify freshness. This is a local queue-age bound, not a claim about camera capture time or physical audibility.
- PCM writes use `WRITE_NON_BLOCKING`, cancellation checkpoints, partial-write accounting, and bounded backpressure. Actual output progress has a separate five-second observation deadline; outstanding PCM normally expires earlier under the one-second freshness rule. These are local audio observations, not diagnoses that the camera failed.
- The process-wide retirement lock is held until the real player's cleanup returns. Cancelled waiters cannot break the dependency chain. Old epoch PCM, final stats, and queued UI status callbacks cannot overwrite a newer attempt. UI callbacks read the current session snapshot.
- A native call that has not returned may outlive logical cancellation. The request is revoked, `retirementPending` stays visible, and a replacement cannot enter the native writer. Confirmed release failures remain latched and require an app restart; mute, backgrounding, or another enable cannot hide that responsibility. Successful release, rather than successful pause/stop alone, establishes retirement.
- Audio socket setup, LATM initialization/reset, decoder, output, and lifecycle errors stay in audio status. They do not publish the video terminal-failure event. Receiver/parser recovery only permits a new manual request.

## Evidence fields

| Field | Meaning |
| --- | --- |
| `accessUnitsReceived` | LATM-extracted AAC units received by the session; no decode claim |
| `queuedAccessUnits` | AAC units accepted by the decoder |
| `decodedAccessUnits` | Compatibility field name; counts actual non-config PCM buffers, never input queue calls |
| `decodedSampleFrames` | Frames in actual valid decoder PCM output, including output that freshness policy may discard |
| `submittedSampleFrames` | Complete PCM frames accepted by the output API |
| `playedSampleFrames` | Compatibility field name; observed unsigned AudioTrack playback-head progress, clamped to submitted frames and rebased per real track |
| `lastPcmAtMillis` | Actual decoded-PCM timestamp, not input arrival or write success |
| `lastOutputAtMillis` | Observed head-progress timestamp |
| `pcmFresh` | The most recent usable PCM source remains inside the local one-second window |
| `playbackEpoch` / `state` | Current manual attempt and lifecycle state |
| `evidenceLevel` | `none`, `queued-input`, `decoded-pcm`, `submitted-pcm`, or `output-render-progress` |
| `retirementPending` | A cancelled playback job has not yet completed real resource cleanup |

`active` means the pipeline has decoded PCM. It does not mean a listener physically heard sound. Diagnostics and both English/Traditional Chinese UI labels explicitly distinguish output-clock progress from audibility.

## Focused verification

The existing pinned tools were used: Kotlin compiler 2.2.21, Kotlin coroutines 1.9.0, JUnit 4.13.2, JVM target 17. No SDK, Gradle, dependency, or other tool was installed. Compilation and tests were serialized under the designated EOS heavy-validation lock and pinned to CPU 8.

`RtpAudioPlaybackTest`: **26 passed**, final recorded duration 1.062 seconds.

Coverage includes:

1. Input acceptance without PCM, delayed output without additional input, and bounded close without EOS.
2. Decode/submission/head-progress separation, partial and zero writes, cancellation, and output-clock stall.
3. One-second queued-input, held-PCM, submitted-range expiry, and late-head rejection.
4. First-PCM and output-progress deadlines across discontinuity/format churn; one-second freshness expiry independent of the counters.
5. Unsigned head wrap, clamping, and unexpected reset detection.
6. Default mute, background/foreground, receiver recovery, explicit retry, and no automatic replay.
7. Cancelled replacement waiters, stale final stats, cross-session resource retirement, and immediate persistent visibility of release failure.
8. Startup time consumed by retirement, ongoing PCM renewal, repeated telemetry not extending the deadline, and logical timeout while a non-cancellable surrogate remains unretired.

### Exactly what was compiled/executed

- Entire production `RtpAudioPlayback.kt` and `RtpAudioPlaybackOwner.kt`; no source extraction or replacement of their logic.
- Entire production `AndroidRtpAudioPlayer.kt` was also type-checked against explicit **compile-only** `android.media` signature stubs. The stubs supply `AudioAttributes`, `AudioFormat`, `AudioTrack`, `MediaCodecList`, `MediaCodec`, and `MediaFormat`. They do not implement decoding/output and are not executed by this test suite.
- The tests provide fake `RtpAudioBackend`/decoder/output implementations, fake monotonic clocks where required, and non-cancellable coroutine surrogates for delayed native retirement. These are unit-test seams, not device evidence.
- AAC model declarations were moved to the production pure-Kotlin module. Media3 LATM parsing is not stubbed into this suite and was not exercised here.
- The evidence package contains `run-jvm.sh`, the explicit stub source, `jvm-test.log`, `source-manifest.json`, and its validation receipt. The source manifest binds the tested/inspected revision; it is not an exact-SHA CI certificate.

### Not run / remaining acceptance

- Android SDK/Gradle app compilation, lint, original Android session integration tests, and on-device instrumentation: **NOT_RUN**.
- Three session integration regressions were added for background/manual recovery, player failure isolation/retry, and LATM initialization failure isolation. They remain **NOT_RUN** in this environment.
- `AndroidRtpAudioPlayerInstrumentedTest` now checks actual PCM, submitted frames, and separate playback-head progress. It remains **NOT_RUN**.
- Real MediaCodec timestamp attribution (including repeated/split output timestamps), real AudioTrack head/wrap/reset behavior, route changes, actual release timing, and device background behavior require Android runtime validation. Unknown timestamp attribution is deliberately conservative.
- Physical camera, speaker/headphones, audio route/volume, audible quality, and audio/video synchronization: **NOT_RUN / unverified**. The code does not claim physical audibility.
- XML parse/unique resource-name checks and `git diff --check` passed. No CI, commit, push, merge, release, camera action, or change to existing frozen evidence was performed by this work item.

## Scope and release assessment

This repairs the existing Canon CCAPI RTP audio monitor. It adds no transport, recording, microphone access, precise A/V synchronization, device/OS/network configuration, or automatic audio recovery. Necessary security and acceptance checks remain unchanged.

Proposed impact: **patch**, because it corrects existing playback/lifecycle behavior. No version change is included. Native/device acceptance and the repository's exact-SHA delivery gates remain unresolved; this document does not authorize a merge or release.
