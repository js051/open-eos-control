# Live View first-frame and recovery lifecycle

## User journey

An admitted source retains its exact connection and Stop obligation while the application waits for the first frame. Start ACK, a listening socket, HTTP headers, a complete compressed frame, decoded pixels and presentation callbacks are different evidence. A stale or cancelled attempt cannot publish into a replacement connection. Source changes wait for original-source Stop confirmation; uncertain cleanup prevents AUTO fallback. A later deliberate Start is never a replay of an uncertain command.

A first-frame failure or terminal runtime failure clears stale presentation, tears down the local reader, and retains any unconfirmed camera Stop duty. Retrying Stop is a Stop-only recovery. Capturing, autofocus, maintenance and background handling keep their existing ownership and safety interlocks. Desktop background monitoring intentionally differs from mobile foreground requirements.

## Implementation evidence boundaries

- Desktop: bridge JPEG/multipart candidates require an actual Pillow decode in an isolated process (5-second wait, 12 MiB input, 64 million pixels), inside original cleanup ordering. Known multipart termination wins over a cached frame, including a post-decode usability recheck. RTP keeps its existing decoded-first-frame check. The browser waits for a private Image.decode result, bounds the complete HTTP/body/decode wait to 15 seconds and encoded input to 12 MiB, validates decoded dimensions, and publishes only for the current source generation. LUT and monitoring sample that same decoded image. A failed AF release does not suppress source Stop or erase the AF warning. Ordinary Start/Stop transport waits are bounded and retain frozen owner/token.
- iOS: JPEG and multipart startup retain the first validated frame; app wiring supplies actual image validation. RTP uses a bounded decoder-output probe before Core input readiness is published. Candidate errors are owned by source selection until a source is selected; terminal state is retained across callback installation. Mobile runtime failure/background retirement clears stale presentation and preserves cleanup responsibility.
- Android: JPEG/multipart first-frame validation runs within the original Stop transaction; app wiring supplies BitmapFactory validation. Core RTP readiness remains validated encoded input because the surface decoder is created later by the UI. Actual MediaCodec/GL presentation callbacks, not buffer-release intention, drive presentation progress. First-output and input inactivity watchdogs are separate from Start ACK.

## Verification and remaining limits

The shared 99 vectors specify necessary publication and fallback guards. They do not validate JPEG bytes, decode video or prove visible pixels. Local synthetic production-function tests, source checks, pure Kotlin/JVM execution and Kotlin syntax parsing are separate from native compilation and execution.

This local cohort does not establish Swift/Xcode, Android SDK/Gradle, actual browser, GPU, decoder, network, device or physical-camera acceptance. Native tests are authored for later authorized platform execution. Existing immutable pending CI snapshots are not replaced by this cohort.

On the iOS default sample-buffer display path, a nonnil displayed pixel buffer plus continued compressed input cannot establish ongoing pixel freshness. First decoder output, first renderer output, missing input and explicit decoder/display errors are distinct checks; a frozen old display while valid access units keep arriving remains an explicit follow-up gap. Android RTP Core encoded readiness must not be called decoded-first-frame parity. Platform presentation callbacks still do not prove that a user physically saw the display.

No new camera transport, real camera operation, automatic replay of an uncertain Start, main merge or release is part of this cohort.
