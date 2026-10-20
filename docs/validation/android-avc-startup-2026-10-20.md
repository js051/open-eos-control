# Android AVC startup configuration

## Scope and evidence limits

This follow-up addresses a startup-portability gap, not a proven native color fix.
The third device run at `a78e7a08840cf063a60a13d3fdfc8fe2ef99349e`
remains the latest device evidence. Its five shared failures and the additional
59 API 36 System UI ANR-blocked cases are not converted to passes by this work.
The separately retained ten-file local correction patch removes the forced
reference-decoder regression and repairs the identified recovery/UI issues.

The original native LUT fixture, picture bytes, numeric tolerances, single-input
readiness test, and deadlines remain acceptance requirements. No dummy picture,
EOS injection, software-decoder substitution, hardcoded color hint, or timeout
padding is part of this change.

## Platform contract

Android's [MediaCodec documentation](https://developer.android.com/reference/android/media/MediaCodec)
specifies AVC SPS in `csd-0` and PPS in `csd-1`, each with a start-code prefix.
Configured CSD is submitted automatically before media. Sending it a second
time as an explicit configuration input is unnecessary. A decoder configuration
change requires retirement/reconfiguration unless the supported adaptive
playback contract is actually in use; the initial headless decoder does not
establish that adaptive Surface contract.

Previously the session configured default 1920×1080 without CSD, then prefixed
keyframes with separately mutable latest SPS/PPS values. Those values could
describe newer input than a queued picture. Optional depacketizer fields also
missed parameter sets reconstructed through FU-A.

## Configuration resolution

`H264DecoderConfigurationStore` scans completed Annex-B access units, including
FU-A reconstruction, and resolves slice PPS IDs to SPS IDs in NAL order.
Admitted pictures own immutable parameter and media snapshots. SPS/PPS are
removed from media input; supported picture and ancillary NAL bytes (AUD, SEI,
and filler) retain their order. CSD uses the required four-byte prefix and the original escaped
parameter payload, without rewriting VUI.

Standalone AUD/SEI/filler are retained in original byte order for the next
resolved picture, within the same combined 8 MiB/1024-NAL budget. They are
consumed once, or discarded on rejected picture/rendering retirement. Sequence
terminators and unsupported ancillary/partition syntax are conservatively
rejected, not moved onto a later picture. Valid parameter sets and an IDR must
re-establish input after rejected syntax; this is not a claim to support every
AVC profile or extension.

The implementation reuses the already-present Media3 1.8.1
`ParsableNalUnitBitArray` for escaped bit reading. Its narrow syntax walker adds
explicit EOF, Exp-Golomb, loop, parameter-count, and byte bounds. Both coded and
cropped dimensions must fit existing preview resource limits. Raw absent,
unspecified, or mixed VUI fields are not replaced with guessed color metadata.
The native decoder remains responsible for codec-level support and full decode
validity; PPS and slice parsing deliberately validates only the bounded
reference/header prefix needed for configuration selection, not their complete
syntax. Encoded configuration alone never proves readiness.

SDP text is bounded before Base64 decoding. RTP appends are rejected before
exceeding the existing 8 MiB access-unit budget, rather than continuing to grow
an already-invalid buffer until its marker.

## Decoder ownership and continuity

The receiver resolves configuration under the same retirement lock that guards
rendering-generation changes. Configuration-only traffic does not count as a
picture; rejected picture-bearing input still breaks picture continuity.
Decoder startup waits for a resolved real IDR before creating native resources.
Content-identical and unreferenced parameter announcements do not trigger a
restart. A newly referenced configuration waits for its own IDR, then uses the
existing decoder/Surface writer-drain barrier.

An unsubmitted seed keeps its original input identity, timestamp, configuration,
and picture through cancelled replacements and pre-input GPU fallback. Successful
native submission consumes it atomically with retirement. An already-submitted
picture is never replayed to make fallback appear ready. Seed waiting handles
channel closure without an uncaught receive exception.

If rendering ownership changes after an input slot has been dequeued, a
still-current codec is retired through that same drain path: there is no input
slot release API, and neither leaking the slot nor queuing an empty/dummy picture
is acceptable. Output format/size notifications and renderer callbacks also
check current decoder/pass ownership before publishing.

At this startup checkpoint, a pre-existing transport-loss gap was separately identified: a discarded
malformed/lost-fragment RTP access unit does not yet export a discontinuity
marker to downstream picture continuity. This batch does not claim that gap is
fixed; the subsequent isolated repair and its separate validation status are
recorded in [Android RTP loss and prediction recovery](android-rtp-loss-2026-10-20.md).

## Validation status

Implemented and independently source-reviewed; the final offline gate passed
on 2026-10-20 at 10:57 UTC:

- All 74 current production data-layer Kotlin files compiled against the
  existing SDK and dependency cache.
- 123 JUnit tests passed: 51 configuration resolver, 9 RTP/SDP bounds, 10
  seed/ownership, and 53 existing RTP/session/geometry/LUT/continuity cases.
- 21 structural source guards passed. These are not native execution evidence.
- Source hashes were unchanged throughout the successful batch.

Two unsuccessful attempts remain recorded, not hidden: the initial harness
compile lacked cached Robolectric classes used by an existing test; the next
compile and 53 existing tests passed but 35 of 50 resolver tests failed. That
failure exposed the adapter incorrectly calling Media3 `readBits(0)` for
zero-valued Exp-Golomb suffixes. The wrapper now returns zero without consuming
bits, with a new targeted regression; all original assertions remain.

The final successful batch took about 56 seconds with a 768 MiB JVM/two CPU
limit and offline dependency resolution. Including failed attempts, aggregate
execution remained below three minutes. No new dependency installation or
network access was needed.

No new device, emulator, KVM, CI, publication, or full instrumentation compilation
has occurred for this follow-up. The offline compile does not cover full Compose
or instrumentation sources. Native color conversion and platform runtime
behavior remain unverified until their unchanged acceptance gates succeed.
