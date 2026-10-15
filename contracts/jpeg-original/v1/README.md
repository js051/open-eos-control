# Automatic original JPEG validation v1

This contract applies only to automatic capture-to-delivery of a newly observed,
strictly eligible JPEG. It does not change manual JPEG/RAW downloads. The existing
single original camera GET remains the sole body transfer. Validation reads the
closed, owned local temporary/pending copy; it never retries capture or downloads.

The contract has three independent surfaces:
- `mimeVectors`: MIME field value as exposed by the transport. `null` means absent.
  A present empty or whitespace-only field is rejected. HTTP libraries may strip
  optional whitespace, so this fail-closed rule avoids calling normalized empty
  data proof of a genuinely empty wire field. `image/jpeg` and
  `application/octet-stream`, case-insensitively and with parameters, are allowed (at most 4,096 ASCII bytes).
  Non-ASCII characters, C0/C1 controls, malformed token/quoted parameter values, other media types, and aliases are rejected.
- `headerVectors`: exposed Content-Type field list. More than one is rejected;
  coalesced comma-joined values are not valid MIME values. This is evidence about
  the HTTP library's representation, not a new raw-wire HTTP parser.
- `byteVectors`: immutable Base64 bytes, byte count and SHA-256. `structuralValid`
  is the bounded parser expectation. `decodedValid` additionally requires actual
  strict decoder acceptance. A file with valid segment framing but premature
  entropy EOI must not pass merely because its markers and dimensions look valid.

## Bounded supported envelope

At most 64 MiB encoded, 64,000,000 pixels, 16,384 pixels on either source axis,
4,096 markers, and 256 scans. Only one 8-bit SOF0/SOF1/SOF2 frame with 1, 3, or 4
unique components is supported. Quantization and Huffman tables referenced by
SOF/SOS must exist. Sampling factors are nonzero and at most four. For interleaved scans only, the
sum of selected horizontal-times-vertical sampling factors is at most ten. Scan
component IDs must exist, be unique, and retain their SOF order; baseline/extended sequential scans require the
sequential spectral/approximation parameters. Segment lengths, stuffing and
restart placement must be valid. SOI is at byte zero; EOI terminates the exact
file. APP/COM payloads are opaque, including embedded marker-like bytes.
Unsupported inputs fail before delivery, without broadening manual import.

Decode validation requests a bounded raster (target longest edge 1,024; hard
maximum 2,048 per decoded axis for codecs with power-of-two reduction). Raw SOF
dimensions are distinct from orientation-aware raster dimensions. Decoders must
force actual pixels, not only inspect metadata. Decoder implementation and
cancellation capabilities differ; platform execution evidence remains separate.
A sample decode and corpus do not prove rejection of every possible corruption.

Validation must not re-encode or replace original bytes. Any stream digest and
local readback digest describe byte continuity, not a camera-provided cryptographic
identity or proof that a camera returned the intended photograph. Exact path/ID,
fresh metadata, response status/length, one GET, closed-copy checks and decoder
acceptance are complementary evidence.

Invalid originals cannot be published, marked complete or added to delivered-ID
registries. Clean up only owned pending outputs. Retain previously committed
files and factual failed-delivery history. Recovery requires explicit re-enable
with fresh baselines; no automatic camera GET or shutter resend is introduced.

## Fixed fixture provenance

All images are synthetic patterns created locally with already installed
Pillow 12.3.0. No user photo, camera ID, credential, or third-party image is used.
The fixture bytes are committed and SHA-256 fixed; tests do not regenerate them.
The corpus includes RGB, gray, progressive, optimized, CMYK, 8-bit SOF1, EXIF
orientation, restart markers, opaque APP/COM bytes, one-pixel, malformed framing,
resource-limit rejection, conservative coding-declaration boundaries, and entropy
truncation counterexamples. Linux strict
PyAV 18.1.0 rejects all four premature-entropy samples while Pillow 12.3.0 `.load()`
accepts them even with truncated-image loading disabled. ImageIO and ImageDecoder
acceptance must be established on their actual platforms.

Primary technical references:
- JPEG T.81: https://www.w3.org/Graphics/JPEG/itu-t81.pdf
- FFmpeg decoder options: https://ffmpeg.org/ffmpeg-codecs.html
- Android partial-image handling: https://developer.android.com/reference/android/graphics/ImageDecoder.OnPartialImageListener
- Apple ImageIO source APIs: https://developer.apple.com/documentation/imageio/cgimagesource

### Conservative coding declarations

The automatic envelope additionally requires 8-bit quantization tables for all
supported frames, unique Huffman symbols (DC category at most 11; AC size at most
10), and only EOB/ZRL zero-size AC symbols in sequential scans. SOF0 selectors are
0 or 1. Progressive coefficient-band declarations track prior approximation,
require DC before AC and no repeated initial band, and use zero in an unused table
selector. This bounded declaration policy is intentionally conservative and is
not an implementation of full JPEG entropy decoding. Full decoder acceptance is
still required. These limits do not change manual import.
