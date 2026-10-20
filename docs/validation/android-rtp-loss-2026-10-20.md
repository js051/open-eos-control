# Android RTP loss and prediction recovery

## Isolated follow-up

This follows the locally verified
[AVC startup configuration repair](android-avc-startup-2026-10-20.md).
That prior 21-file candidate and its successful 123 JUnit/21 structural-guard
evidence are retained separately. This change does not claim to repair or prove
native emulator color conversion.

The existing H264 depacketizer retained its expected packet sequence across
assembly resets, but only compared it within the same RTP timestamp branch.
Missing packets between access units could therefore be invisible to downstream
prediction continuity. Incomplete/malformed access units were also discarded
without an explicit loss signal. Counting only completed pictures cannot
represent pictures lost before completion.

## Recovery contract

- A transport loss remains visible across configuration-only traffic and
  dependent pictures. Only a complete, resolved real IDR can restore decoder
  admission; encoded SPS/PPS or an unresolved IDR do not prove recovery.
- An access unit containing a detected sequence gap is not treated as complete
  merely because one remaining NAL looks like an IDR.
- An explicit receiver-generation reset must not reinterpret the remaining
  packets of an interrupted picture as a new complete picture. Recovery
  quarantines input through a boundary before admitting a new access unit.
- Sequence wrap is normal. Duplicate/late packets must not rewind expected
  sequence state or replay an old picture. Repeated or out-of-order media
  timestamps remain supported through the existing independent input identity
  and codec timestamp machinery.
- Ancillary prefixes accumulated before loss are discarded. During pending
  recovery, standalone ancillary prefixes are conservatively discarded rather
  than associated with a later picture without evidence. Ancillary bytes
  co-located with a complete admitted IDR remain unchanged, as does normal
  contiguous-stream ancillary retention.
- Decoder ownership, exactly-once seeds, Surface writer drain, native output
  readiness, pixel tolerances, and watchdog deadlines remain unchanged.

The behavior deliberately does not add a packet reordering buffer, retransmit
requests, dummy pictures, synthetic EOS, timeout padding, or a codec override.

## Validation status

Implemented and independently reviewed. The final unchanged-source offline
batch passed on 2026-10-20 at 11:08 UTC:

- All 74 current production data-layer Kotlin files compiled.
- 153 JUnit cases passed: 55 resolver, 28 protocol/bounds, 17 seed/integration,
  and 53 existing RTP/session/geometry/LUT/continuity tests. This retains every
  case in the earlier 123-test startup gate and adds 30 transport cases.
- 24 production-binding/structural source guards passed.
- Execution took 58.56 seconds, using the existing offline tool/dependency
  cache, a 768 MiB JVM limit and two CPUs. Source identity was checked before
  and after execution.

The tests cover contiguous traffic and wrap, gaps across marker/timestamp
boundaries, partial and malformed aggregates/fragments, duplicate/late packets,
stale markers after reset, configuration-only traffic, unresolved IDRs, and
actual depacketizer-to-resolver-to-production-input-gate recovery. Original
fixture bytes, pixel thresholds, existing tests, and deadlines remain intact.

This is local implementation evidence, not full Compose/instrumentation
compilation or native decoder/display evidence. No publication, CI,
device/emulator operation, KVM/security change, or native acceptance run occurred.
The original native color mismatch and platform UI/runtime acceptance remain
unverified.
