# RTP output, presentation and monitoring acceptance

## One user journey

Start an existing advertised RTP source, wait for actual decoder output, attach the existing renderer, enable/replace/disable a LUT, inspect scopes and spatial overlays, then exercise stream failure, background/foreground and an explicit recovery. Complete the original source's Stop before any replacement Start or AUTO candidate. Preserve recording, AF, shutter, Bulb and maintenance interlocks throughout. No new transport or physical camera operation is introduced by the synthetic tests.

## Observable evidence

- Input-ready: a complete validated encoded access unit exists. It is not decoder readiness.
- Decoded-output: an actual decoder returned pixels (or a nonempty decoded output buffer), owned by this attempt. Input queueing and packet activity cannot advance it.
- Surface-submitted: this exact processed output was accepted by the renderer's output surface. This is not a compositor or physical-display guarantee.
- Presentation-confirmed: a platform rendering callback or positively identified displayed output corresponds to an owned frame token. Repeated old tokens cannot advance the progress clock.
- Presentation-unverified: the platform does not expose a usable identity/callback. Do not convert it into success, and do not misclassify unsupported observation as camera failure.
- Decoded/submitted/confirmed analysis: scopes carry the sampled output identity and evidence level. Their asynchronous result may describe a bounded recent frame; spatial overlays additionally require identity with the current video frame.

A flat or repeated-looking scene is valid. Pixel value equality, image hashes, nonempty display buffers and new RTP packets are not progress evidence. No software callback here proves the user saw a physical display.

## Required synthetic scenarios

1. Start ACK and continuous valid AUs with no decoded output must not report decoded readiness.
2. A nonempty decoded output produces decoder evidence; later packets without new output cannot refresh it indefinitely.
3. Close/terminal state defeats cached readiness and a late decoder result.
4. Decoder completion timestamps are measured at completion, not before a blocked decoder call.
5. A renderer callback/readback with a repeated, out-of-order or retired token cannot refresh progress.
6. A platform without verifiable presentation stays explicitly unverified; a previously verified renderer which stalls retains distinct failure evidence.
7. A LUT revision change retires older render/analysis callbacks. A LUT error is not overwritten by successful ungraded analysis.
8. Scope pixels and LUT output derive from one immutable decoded/processed frame or the same GL draw. Later asynchronous surface copying is not assigned the earlier frame's identity.
9. A fresh sample may finish after newer video frames only with retained source identity, bounded age and current owner/renderer/LUT. Spatial overlays must not be drawn over an unrelated frame.
10. Background/retirement clears obsolete evidence immediately while physical decoder/GPU ownership is retained until work actually ends.
11. Late old-owner failures cannot retire a new source. Unknown remote Stop is retained and blocks replacement Start/AUTO.
12. Foreground recovery uses a fresh attempt only after the existing safe Stop and interlock requirements are satisfied.

## Platform execution boundary

Local pure contracts, Python/Node tests and Kotlin/JVM policy checks do not execute Apple VideoToolbox/AVSampleBufferVideoRenderer, Android MediaCodec/GLES/Surface, native UI, or the browser compositor. Authored native fixtures must be run on the exact candidate in the separately approved platform batch. Actual devices and cameras still need repeated LUT switching, identical-frame scenes, valid-input/decoder-stall, renderer-stall, weak network, background, cancellation, long-running performance and resource-pressure testing. Unsupported display-readback capabilities must remain visible rather than being inferred from simulator or source checks.

The already pending native CI snapshot is immutable and separate from this local cohort. It must not be replaced with these newer source commits without matching authorization.
