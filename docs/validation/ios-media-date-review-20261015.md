# iOS media date precision and review alignment

Base: `9ed86312c93eedadc390102bebcd4da188806f90`.
Status: implemented source and focused tests; native compile, XCTest, UI,
simulator, camera and physical-device validation **NOT_RUN**.
The source implementation worker did not commit or publish. Parent local commit
and outgoing scan receipts are separate; no push, CI, security setting or release
action is included in this cohort.

## Changes

- `MediaCaptureDate.swift` implements strict four-state parsing (`instant`,
  `floating`, `date-only`, `unknown`) using integer proleptic Gregorian arithmetic.
  Trimmed metadata is bounded to 128 UTF-8 bytes. Offsets are bounded to ±18:00;
  a known IANA (plus UTC/GMT), bounded ASCII named zone must agree with the explicit offset.
  Fractions truncate to milliseconds. Offset/display AD-year overflow is unknown.
  Floating clocks retain literal fields through DST gaps/overlaps and receive the
  English/Traditional Chinese unknown-timezone marker. Date-only never displays
  a time or enters instant comparison.
- `MediaLibrary.swift` uses displayed day, fixed precision bucket, then the actual
  instant/floating clock for date sorting. Equal/unknown values retain loaded order.
  Date sorting parses once per admitted loaded index; other sort modes with no
  active range skip date parsing. Group appends do not hold a copied previous group.
  Name/rating/camera modes retain their own purposes. Filter, summaries and grouping
  use the same supplied zone snapshot; raw metadata/IDs are never rewritten.
- `MediaDateRange` retains its original Date/timezone boundary API and existing
  23/25-hour DST tests. New literal day keys drive gallery filtering; explicit UTC
  DatePicker carriers preserve the user's endpoints even across skipped civil days.
  A carrier is never a capture instant. The active range label, filter, card, group,
  detail and worklist label use the shared `CameraAppState.mediaDateDisplayZone`.
  System timezone notifications and app resume update that snapshot without changing
  selected media, range endpoints, session IDs or transfer ownership.
- Both recent and after-capture review preserve camera response order. After-capture
  first excludes all previous IDs and enforces photo/video kind. Dates and filenames
  cannot make an old ID new or establish that this app caused capture. Existing
  owner/session/generation, cancellation, bounded read-only retry and zero-resend
  logic is unchanged. Foreground inventory/JPEG/fresh-metadata/stable-size code,
  RTP/LUT, Files delivery and prepared-media storage are byte-identical to base.

## Evidence

- PASS: 45 Python source/contract/regression checks (8 new date/review source guards).
- PASS: CI-equivalent lint from `bridge`: `ruff check --no-cache . ../scripts/validation`.
- PASS: `git diff --check` and protected-source byte comparison with the base.
- Fixed fixture remains 26,467 bytes, SHA-256
  `8eb57e04446478fe523c1e8cd08c0cc57b524d9fcb7f63845fc28d6d02f57e9a`.
  XcodeGen maps only its JSON, preserving existing resources without README collisions.
- Eleven new XCTest methods are written, but **NOT_RUN**: eight shared-contract methods,
  one timezone/range/selection ownership method and two review integration methods.
  They exercise the real Swift parser/filter/sort/group/card/detail/review functions
  against 144 fixed parse projections, 9 sort cases, 4 review cases and 2,016 distinct
  tuple-order triples. Additional fixed cases cover AD 1/9999, 1582, negative epoch,
  fractional truncation, skipped days, overflow and unknown-date first review.
  A 20,000-row unknown-date group regression is also written; synthetic GMT+0800
  bracket names must be rejected. Card/detail presentation is consistently minute
  precision while parser and sorting retain milliseconds.
- Existing DST and safety assertions remain. Intentional old expectations changed:
  recent review now keeps camera order; unknown date sort ties keep loaded order;
  timezone-free metadata is floating rather than an assumed-zone Date.

Full commands, source hashes and native pending counts are in
`ios-media-date-review-20261015.json`. Python guards are structural evidence, not
production Swift execution. Foundation/ICU named-zone behavior, Xcode type checking,
resource bundle loading, SwiftUI labels/DatePicker behavior and owner integration
remain native validation risks until the unchanged required SDK gates run.

## Release assessment

Published baseline: [v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0),
verified by the delivery owner through official GitHub metadata at 2026-10-15
03:54:34 UTC. Current development version remains 0.13.0.

Expected impact: patch for this date/review correctness cohort; the cumulative
feature delta since v0.12.0 remains minor. No version change is made. Pending gates
include exact-head CI, Swift/SDK/native UI and the previous native RTP/recording
failures. Physical-camera evidence remains pending. No PR-ready, main-accepted
or release claim is made.

The final parent check additionally ran all scripts/validation/tests,
scripts/ci/tests and scripts/contracts/tests: 88 passed with one existing
PytestReturnNotNoneWarning in the export-evidence helper. This is still only
Python/source/fixture evidence; production Swift is NOT_RUN.
