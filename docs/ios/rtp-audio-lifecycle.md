# iOS RTP audio lifecycle and evidence boundary

This change remains on the existing Canon RTP/LATM → AVAudioConverter → AVAudioEngine path. It adds no transport, recording feature, background playback, or precise A/V synchronization claim.

## User-visible journey

- Every RTP session starts muted. Audio preference is not persisted.
- Explicit enable creates a playback epoch tied to the current session owner. Foreground and monitoring availability are required.
- A decoder input, fresh nonempty PCM, output submission, and current player-render progress are separate observations.
- Muting, backgrounding, monitoring pause, session replacement/retirement, or terminal audio failure clears the request. Foreground return and packet return do not automatically re-enable audio.
- First actual PCM and subsequent actual-PCM gaps have independent five-second limits. Supported player-render observation also has a five-second limit. Only the relevant real event renews its deadline; packets, accepted AAC, empty PCM and submissions do not.
- Unsupported render observation stays `unknown` while fresh PCM continues. It does not alone stop otherwise usable audio.
- Audio failure/stall retires audio only. Video remains usable. Recovery requires explicit enable; an unavailable listener requires the existing session lifecycle to restore availability.

## Evidence terminology

`requested` is user intent; `enabled` is admission to the active pipeline. Neither proves output.

- `decoderInputs`: AAC submitted to the actual converter.
- `decodedSampleFrames`: fresh, nonempty converter output, including delayed drain output.
- `submittedSampleFrames`: PCM scheduled on the player.
- `renderedSampleFrames`: frames credited by a current `.dataRendered` callback, fenced by session owner, playback epoch, output incarnation, unique buffer identity and original freshness deadline.
- `outputProgress`: `inactive`, `waiting`, `unknown`, `rendering`, `stalled`, or `failed`. Historical render progress becomes `unknown` after one second without current render evidence.
- Physical audibility is always `unknown`. The obsolete schedule-as-`playedSampleFrames` counter was removed from the app and diagnostic text.

The common lifecycle vocabulary maps to these separate iOS fields: unavailable = listener unavailable; muted = not requested; starting = enabled without fresh PCM; active = enabled with fresh PCM; stalled/failed = their output states. Retiring (`retiringResources`) is immediate logical revocation followed by serial native-output stop/detach and decoder cleanup. It stays visible while either physical resource is pending, including a blocked native call; it is not proof of physical silence. No `active` label establishes hearing.

Apple defines [dataRendered](https://developer.apple.com/documentation/avfaudio/avaudioplayernodecompletioncallbacktype/datarendered) as player render completion. It excludes downstream signal-processing latency. The UI explicitly says player render progress, not device playback or hearing.

## Ownership, freshness and retirement

The queued AAC bound is global across playback epochs (8), so rapid disable/enable cannot multiply retained decoder work. The player has at most 8 pending buffers. Pending converter provenance has at most 64 entries, each carrying remaining PCM frames and the original monotonic input deadline.

AAC/PCM at age 1 second is stale. Checks run before decode, after decode, after output preparation and in delayed-drain/output callback paths. Scheduled buffers retain this same deadline. A native expiry timer and snapshot/admission checks invalidate the output incarnation before stopping/flushing the player, preventing old scheduled audio from surviving a stalled-output rebuild. Timers use OS scheduling; this is a local best-effort freshness policy, not a real-time or physical-output timing guarantee.

The independent watchdog polls every 250 ms. Its control snapshot never waits for the native-output queue. Converter input/PCM evidence is recorded before native submission, so a blocked prepare does not hide already-produced PCM. No new converter runs in parallel: decode, `noDataNow` drain and decoder reset share the same serial decoder queue. Delayed PCM inherits its contributing input's deadline; draining does not make it fresh again. A gap never sends a synthetic end-of-stream to the converter.

Mute/retirement immediately revokes token/incarnation and enqueues AVAudioPlayerNode.stop, AVAudioEngine.stop and detach on a separate serial native-output queue. No prepare, schedule or stop call holds the control lock. If a device call does not return, the watchdog can still clear the request within its observation bound and expose `retiringResources`; replacement native output waits behind the retained native call and its stop/detach barrier. Completion of stop/detach is the local physical-resource retirement boundary, not the prior logical revocation. Native completion callbacks first hop to an independent queue, so stop-triggered callbacks cannot deadlock by reacquiring a held control lock. They cannot credit a replacement or consume its pending-buffer slots.

The owned AVAudioSession is deactivated on stop with `try?`; deactivation errors and downstream device silence are not proven. An already-running converter returns before its queued reset. Decoder retirement also stays pending until that reset returns. Blocked prepare, schedule and stop each have a production-path injected-boundary XCTest; none of these native tests was run in this environment.

## Authored native coverage and local checks

`RTPAudioLifecycleTests.swift` is automatically included by the existing XcodeGen `Tests` source entry and scheme. Its 19 tests exercise the real controller and production pipeline with injected decoder/output boundaries, plus a real Apple AAC fixture through the production pipeline. They cover default mute, queue/in-flight cancellation, stale errors/status/completion, global bounds, background and monitoring pause, empty-output and render deadlines, capability-unknown output, exact-one-second expiry, discontinuity, partial prepare failure, session replacement, final-input delayed PCM drain, manual recovery without video failure, and independently observable cancellation during blocked native prepare/schedule/stop. Existing Apple-decoder fixture coverage also exercises `noDataNow` drain.

Validation for this Linux worktree:

- 12 focused Python standard-library source/wiring checks: PASS.
- 5 existing, selected source checks for affected Live View/background/retirement/maintenance interactions: PASS.
- Swift compilation, XcodeGen, XCTest, native AVAudioEngine execution: NOT_RUN (tools unavailable; no installation authorized for this task).
- Camera, iPhone, hardware sound/output, physical A/V sync: NOT_RUN.
- CI, commit, push, merge and release: not performed by this work item.

Source assertions are not substitutes for native execution. No native or device readiness claim follows from their passing.
