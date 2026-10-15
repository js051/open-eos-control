# Live View LUT numeric contract v1

`numeric-vectors.json` is a bounded (at most 64 KiB), synthetic, shared oracle.
Expected values are committed numbers, never results produced by the renderer
under test. The same bytes are consumed by PC, Android, and iOS tests.

## Numeric semantics

- Inputs and outputs are **sRGB display-encoded component values**. This preview
  contract does not apply an sRGB-to-linear transfer before LUT lookup, and is
  not a camera calibration, scene-referred, HDR, or exposure claim.
- `values` is a flat array of RGB triples. Red changes fastest, then green,
  then blue: `((blue * size + green) * size + red) * 3 + channel`.
- For each input channel, normalize using `domainMin`/`domainMax`, clamp the
  coordinate to 0..1, then scale by `size - 1`.
- Interpolate all eight surrounding nodes trilinearly. Preserve negative and
  above-one table nodes during interpolation. Clamp only the final RGB output.
- CPU floating-point comparisons use an absolute tolerance of 0.000002, not a
  display-byte tolerance. Do not quantize samples or nodes to make CPU tests pass.
- `inputRgb8` is optional. Where present, `inputRgb` equals those bytes divided
  by 255; `expectedRgb8` is the fixed final, clamped, nearest-rounded output.
  Exact half values round upward. The half-rounding test uses binary-exact 0.5;
  additional fractional-byte cases use binary-exact 0.125, 0.375, and 0.875.
  Decimal 0.9 is deliberately not used as a tie: its Float32 representation is
  slightly smaller, and double versus float multiplication by 255 can straddle
  a half-byte boundary. Do not hide a transfer-function error with a wide tolerance.

## Schema and independent derivation

Top-level metadata declares schema version, color encoding, interpolation,
red-fastest order, CPU tolerance, and byte-rounding policy. `luts` contains six
explicit tables. `vectors` contains 12 fixed input/output triples, six also with
explicit input/output bytes. `quantizationVectors` contains two fixed triples
for final clamp/round behavior.

Tables were authored from these simple definitions evaluated at grid nodes,
with the full resulting table stored in JSON:

- `identity`: (r, g, b)
- `invert`: (1-r, 1-g, 1-b)
- `cross-mix`: (r*g, g*b, b*r)
- `nonunit-cross-mix`: the same node table, domain (-1, 0, 0.25) to (1, 2, 0.75)
- `extended-nodes`: (3*r-1, 3*g-1, 3*b-1), including -1 and 2
- `asymmetric-three`: a 3x3x3 grid of
  (0.1+0.2*r*r+0.3*g+0.1*b, 0.05+0.1*r+0.4*g*g+0.2*b,
  0.02+0.3*r+0.1*g+0.5*b*b)

For example, nonunit input (0.2, 0.4, 0.8) normalizes/clamps to (0.6, 0.2, 1),
so cross-mixing gives (0.12, 0.2, 0.6), byte output (31, 51, 153).
The 3x3x3 grid at (0.2, 0.4, 0.8) uses interpolated squared components
(0.1, 0.2, 0.7), yielding (0.32, 0.31, 0.47), byte output (82, 79, 120).
These are grid interpolations, not direct evaluation of a continuous quadratic.

## Verification boundaries

Platform filenames below refer to their respective development branches; shared
fixture is not combined-tree evidence.

- PC: `node bridge/tests/lut.test.js` runs the real production parser and CPU
  sampler and checks all vectors. `npm run test:modules` in `bridge` includes it.
  CPU final-byte checks are not WebGL framebuffer execution evidence.
- Android JVM: `LiveViewLutNumericContractTest` runs the real `CubeLut` parser,
  sampler, `sampleArgb`, and `NativeVideoLut`. The bitmap adapter is kept in
  `CubeLutBitmap.kt` with the original package/API. No Android API stubs are used.
- Android device: `NativeRtpLutNumericContractTest` supplies real opaque Canvas
  pixels to `NativeRtpLutRenderer.inputSurface`, waits for real presentation, and
  PixelCopies a real visible Surface. Six fixed byte vectors use tolerance 3.
  This isolates GPU numeric behavior; the separate H.264 test covers decoding
  and orientation. Source presence alone is not a passed device test.
- iOS consumes this identical contract in its platform tests. Core Image or
  native GPU execution must be reported separately from CPU/source checks.

On the Android development branch only, when the already-installed pinned
Kotlin tools are available, `python scripts/validation/run_lut_numeric_jvm.py --jvm-tools <existing-tools>
--output <local-evidence-dir>` runs the unchanged numeric JUnit body with a
Python-generated **data-only** provider copied from this JSON. It never computes
expected outputs or downloads dependencies. Its result explicitly excludes the
org.json resource loader, Gradle resource mapping, full Android SDK build,
Bitmap adapter, and GPU/device execution. Those gates remain NOT_RUN until run
in their real environments.
