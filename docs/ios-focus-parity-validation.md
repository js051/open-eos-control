# iOS held autofocus and focus telemetry validation

## Scope

This candidate starts from `0023ad0994ad30c3717b235fbff0bda38bd26e5c` and adds held AF to direct CCAPI and explicitly capable Bridge sessions. Existing short autofocus and half-press commands retain their bounded semantics.

Each hold has an owner. Touch release, leaving the control, cancellation, backgrounding, disconnect and the finite watchdog retire that owner's press. Missing Start acknowledgement and failed Stop remain release obligations; no automatic re-press is introduced. The 30-second watchdog requests release; it is not proof that a physical camera stopped within 30 seconds.

Camera-reported focus metadata is separate from the existing accepted-command marker. Parsing is bounded to 256 KiB and 512 rectangles, selected 1× frames only, finite geometry and explicit unknown states. The overlay requires encoded-image aspect agreement and uses the same displayed-image rectangle, including desqueeze. Freshness and connection/Live View ownership prevent stale responses from drawing on a newer view.

New Bridge flags are optional and default to unsupported. The client does not discover support by attempting physical commands, and does not infer these capabilities for gphoto2 or direct USB. Old Bridge installations preserve existing operations.

## Evidence and limits

The candidate adds 30 test methods: 22 Core, 7 App and 1 bilingual UI journey. At preparation, diff whitespace checks, localized-key uniqueness and lightweight delimiter checks pass. These are source checks, not Swift compilation.

Core and App controlled test sources exercise the owner lifecycle, uncertain responses, stale status, cancellation, metadata framing/encoding/geometry, watchdog retirement and cleanup. The existing canonical simulator supports AF start/stop counters, which are used by new UI test sources to exercise the real control in English and Traditional Chinese without inventing optical-focus evidence.

Swift compilation, XCTest, the new touch/drag UI journeys, VoiceOver behavior and visual overlay rendering are **NOT_RUN locally** because this environment has no verified Swift/Xcode toolchain. The base's prior 390 Core / 467 App / 38 UI pass is historical evidence only and does not validate this candidate.

New exact-head normal CI is required. Real-camera AF/lens behavior, radio loss, camera-side release and sustained metadata remain concentrated device-acceptance items. A successful simulator counter or a frame drawn by a fixture does not establish physical focus.
