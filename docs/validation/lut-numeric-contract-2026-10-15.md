# Shared Live View LUT numeric verification — 2026-10-15

## Implemented scope

The shared `contracts/live-view-lut/v1/numeric-vectors.json` contract contains
12 fixed float vectors, including six exact decoded-byte input/output pairs,
and two final quantization vectors. Its 8,171 bytes have SHA-256
`64a316b0809a3bb116064eaf07d9cf5c45431b51ba8f20ca3df97154bb743a64`.
The byte-identical fixture and README are present in the PC, Android, and iOS
candidate worktrees. The contract covers identity/invert, nonlinear channel
mixing, nonunit domains, input boundaries, negative/above-one table nodes,
post-interpolation output clamp, and asymmetric red-fastest 3x3x3 interpolation.

PC tests invoke the actual `bridge/open_eos_bridge/static/lut.js` parser and
sampler. Android tests invoke actual `CubeLut` and `NativeVideoLut` implementations.
`applyCubeLut(Bitmap, ...)` was moved verbatim into `CubeLutBitmap.kt`, with the
same package, API, cancellation, and cleanup. This lets the real pure numeric
production code compile without Android API substitutes.

An additional Android instrumentation source,
`NativeRtpLutNumericContractTest`, sends the same six fixed input bytes through a
real Canvas-backed renderer input Surface, real external texture and GLES LUT,
real visible SurfaceView, presentation callback, and PixelCopy. It checks five
uniform-color positions against fixed expected bytes, with tolerance 3 per
channel. Each vector closes its renderer on failure. Captures are recycled only after
any submitted PixelCopy completes; a lost callback retains at most one bounded
64x48 bitmap instead of recycling memory still owned by an in-flight copy.
It uses no CPU-generated expected outputs, decoder mocks, or screenshot goldens.
The separate existing H.264 test remains the decoder/orientation evidence gate.

## Local evidence

- PASS: PC `node bridge/tests/lut.test.js`, all 12 numeric, six decoded-byte,
  and two quantization vectors.
- PASS: PC `npm run test:modules`, 35 module entrypoints, exit 0: 936 Node-reported tests plus 60 custom-reported
  cases (996 explicitly counted); additional assertion-only scripts and the LUT
  vectors also pass and are not folded into that count.
- PASS: existing pinned Kotlin 2.2.21 compiler and real JUnit 4.13.2,
  15/15 tests: five new shared numeric test methods and ten existing native LUT
  tests. CPU absolute tolerance stays 0.000002; `sampleArgb` expected bytes and
  alpha are exact.
- PASS: Android existing validation/source suite, 32/32 tests.
- PASS: Bitmap adapter compared byte-for-byte with its original function;
  the remaining `CubeLut.kt` is the original file minus that function/import.
- PASS: all three shared fixture checksums and `git diff --check`.

The offline JVM command is:

```
python scripts/validation/run_lut_numeric_jvm.py \
  --jvm-tools <existing-pinned-jars-directory> \
  --output <local-evidence-directory>
```

The offline helper reads the committed JSON with Python and emits a data-only
Kotlin provider. It compiles the unchanged checked-in numeric JUnit body and
production LUTs. It does not calculate expected values. This is necessary when
only the already-installed tools are available and org.json is unavailable;
there is no new JSON parser, downloaded dependency, or Android API stub.

## Explicitly NOT_RUN here

- Formal org.json fixture loader and Gradle test-resource/asset mapping
- Full Android SDK/Gradle compilation and Bitmap adapter execution
- New or existing Android native GPU/decoder/PixelCopy instrumentation
- PC actual WebGL framebuffer execution
- iOS native/Xcode execution (tracked separately by its candidate)
- Physical camera testing

A passing CPU/source check does not turn any of those gates into PASS. Native
shader/API behavior and display color-space handling still need the real
platform runs. This work does not merge, publish, or change required checks.
