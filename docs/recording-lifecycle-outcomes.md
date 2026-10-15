# Recording response, state and recovery

This development candidate aligns the existing supported recording journey, without new camera commands or transports. A received Start/Stop response is recorded separately from a fresh recording observation. Stop response success can release the command duty and allow a deliberate new Start, but is never converted into an observed stopped camera or automatic clip selection. Lost Start/Stop replies preserve explicit Stop recovery; neither timeout, cancellation, ordinary status refresh nor reconnect replays Start. Later fresh observation cannot rewrite the original response receipt.

## Evidence by transport

- Direct canonical CCAPI: complete native 200 empty/unique empty-object or empty204 response is response evidence. General status contains assignment cache and is not a fresh recording reader. No unadvertised REC GET is invented. Canonical queued recbutton events remain change hints; neither arrival order nor a local sequence establishes a fresh current state. They cannot resolve unknown command duty or trigger clip selection.
- CCAPI simulator: its existing dedicated status response is synthetic state observation only. Simulator success is not physical-camera evidence.
- Desktop Bridge: additive strict outcome and observation envelopes require exact engine, operation, version and source. Old status-only bodies do not upgrade to fresh observation and there is no legacy mutation fallback.
- libgphoto2: exact fresh movierecordtarget Card/None config can support state observation; unknown values and read failure remain unavailable. The reader never invokes a helper that silently stops movie transport. An admitted Stop retains its original path/value if capabilities or later reads disappear.
- Android USB: a matching old property cache is not a fresh no-op observation. Fresh observation reuses an already advertised REQUEST_DEVICE_PROP_VALUE read for EVF_RECORD_STATUS and a request-scoped boundary; queued event delivery alone is not proof. Accepted write without that authoritative read retains response evidence and separate unavailable state; cancellation/lost response retains duty.

## User journey

Current-owner attempts retain response, observation and Stop responsibility separately. The history is bounded to20 in-memory records. Retired connection results are previous-camera notices, cannot target a replacement camera and do not transfer commands. Read-only recovery cannot send recording, Live View or other camera writes. A new recording attempt invalidates the previous stopped-video selection owner; only current-owned fresh false may enter the existing bounded clip-selection flow, with its original known-ID boundary.

Existing thermal, Bulb, held-focus and capability gates still apply to Start. Explicit Stop must remain available under a retained same-owner responsibility. Ordinary settings/battery/capability refresh and other safety warnings must continue after a settled recording result.

## Validation boundary

Local pure logic, synthetic protocol and source tests are separate from native compilation, UI runtime, GPU/RTP decoding and physical-camera testing. This group does not itself claim those unavailable platform/device layers passed. It does not change the fixed pending CI snapshot, draft PR state, main branch or releases.
