# Android native RTP sampled monitoring

## Scope

The existing H.264 decoder and native Surface remain the video presentation path. A separate bounded sample feeds the existing histogram, luma waveform, zebra, false-color and focus-peaking algorithms. This does not provide native video LUT presentation, full-rate scopes, calibrated scene luminance, or a physical-camera compatibility claim. A loaded LUT stays in memory but is not applied on this path.

## API and pixel contract

The app's minimum Android API is 26. [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy) supports copying a Surface to a scaled destination bitmap from API 24; the optional source-rectangle overload is API 26. This implementation copies only the decoder Surface, excluding app HUD and overlays. The destination is explicitly opaque sRGB ARGB_8888, bounded by the existing 120×80 analysis dimensions. Bitmap.getPixels supplies RGB channel values to the existing display-preview luma formula; no limited-range Y plane is substituted.

[MediaCodec Surface rendering](https://developer.android.com/reference/android/media/MediaCodec#transformations-when-rendering-onto-surface) applies crop, rotation and scaling. The size reported to the UI uses inclusive decoder crop edges and quarter-turn rotation. Surface layout, taps and overlays share the displayed aspect ratio. Sampling uses that Surface's dimensions, and overlays stretch back to that same rectangle; no second desqueeze or CPU rotation is applied. Device conformance, color conversion and alignment still require runtime fixtures.

## Ownership, timing and resource bounds

- [OnFrameRenderedListener](https://developer.android.com/reference/android/media/MediaCodec.OnFrameRenderedListener) provides a rendered-frame activity signal and a System.nanoTime timestamp. Callbacks may be delayed/batched, and older Android versions can omit callbacks. PixelCopy returns the latest queued buffer, which may be newer than that signal; there is no claim that its pixels have the callback's exact timestamp or PTS.
- Each decoder keeps at most 16 pending render metadata records. Decoder, rendering and format generations retire old notifications. A StateFlow retains only the latest render observation.
- Copies start only in the foreground, during active auto-refresh with thermal permission, with an attached valid Surface and at least one of the five pixel assists enabled. No capture timer runs.
- A process-wide single physical slot spans PixelCopy and CPU analysis, including screen replacement. Owner/settings/rotation/Surface changes invalidate publication but never release an unfinished native operation. The callback owns bitmap recycling and slot release. No image queue is retained.
- Requests start at most once every 250 ms. The existing 120×80 sampling bound applies to the destination bitmap itself. At most one copied bitmap and one analysis are being produced, plus the current UI result.
- A successful result must still own the current session, Surface, decoder/format and settings, and remain younger than one second by both render activity and acquisition time. Newer frames do not invalidate a still-fresh completed result. A one-shot expiry removes stale scopes if new frames stop; it cannot schedule a copy.
- Settings, layout, source/format changes, pause, background and detach clear results immediately. Capture errors, no pixels, waiting and stale state have distinct visible labels. Failures do not stop native video or change audio state.

## Validation and limits

Focused Kotlin tests cover physical-slot ownership through invalidation, delayed old frames, cadence, stale/future timestamps, completion starvation, duplicate completions, crop/rotation, channel order and bounded geometry. Controlled Python source checks cover the Surface-only API, bounded destination, lifecycle wiring, native presentation priority, no LUT application, no capture timer and translated status resources. These source checks are not execution of Kotlin or PixelCopy.

Kotlin/Gradle compilation, Android unit tests, emulator/instrumented tests, physical camera tests, color-bar/crop/rotation/desqueeze fixture rendering, sustained memory/latency/FPS and audio coexistence measurements are NOT_RUN in this local development group. No new dependency, installation, CI run or device access is required or performed by these source checks.

Runtime acceptance must cover source A→B (including equal dimensions), close while copying, pause/resume, foreground/background, Surface recreation, crop/format changes, portrait/landscape, desqueeze 1/1.33/1.5/1.8/2, no-data/error/late callbacks and continuous input. Verify all five visible assists through actual native RTP decode→Surface→PixelCopy, while keeping audio muted/enabled checks separate. Use synthetic black/white/color bars, near-threshold zebra and sharp edges; never equate a JPEG fixture or source check with native rendering or a physical camera.
