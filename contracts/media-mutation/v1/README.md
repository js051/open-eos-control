# Media mutation evidence and batch contract v1

This contract covers the existing rating, protection, rotation and archive operations on
already-selected media through supported transports. It adds no delete operation, camera
capability, automatic mutation retry, full-card lookup, or background work. A transport
acknowledgment is not evidence that the requested metadata value was observed.

The primary fixed corpus contains 200 metadata, 66 readback, 10 batch-state and 3 roster vectors.
The companion eligibility corpus contains 25 qualification and 2 stale-selection snapshot cases.
`rawValueJSON` is an exact JSON value literal; null at the fixture level means the field
was absent. A literal `"null"` means the response field was present with JSON null.
A parser must not turn missing or malformed metadata into zero stars, zero rotation,
false protection, or a claimed successful write.

## Coding profiles

- `normalized`: Bridge and normalized simulator listing metadata. Ratings accept integer
  JSON tokens 0–5 and exact ASCII strings `"0"`–`"5"`. Rotation accepts integer tokens
  0, 90, 180, 270 and the corresponding exact strings. String compatibility is retained
  from the existing Android rating client. Protection and archive accept JSON booleans only.
- `canon`: Canon CCAPI kind=info, including the existing simulator's canonical info response.
  Rating accepts exactly `"off"` or `"1"`–`"5"`; rotation exactly `"0"`, `"90"`, `"180"`,
  or `"270"`; protection/archive exactly `"enable"` or `"disable"`.
- Reject other values, including booleans used as numbers, floats/exponents, noncanonical
  strings, signs, padding, whitespace, Unicode digits, objects, arrays and overflow.
  Keep each profile separate; do not change generic camera-setting JSON helpers globally.
- Preserve raw token evidence where a platform decoder otherwise rounds or erases numeric
  representation. A pure parser fed an explicit typed token does not establish that the
  actual transport preserves that token. Required platform tests must cover the real wire
  parser, including 3.0, 3e0 and 3.0000000000000000001. Do not weaken existing strict tests.
- The browser may receive already-normalized model values from its own Bridge; provider
  normalization and actual HTTP decoding still need their own tests. A common fixture
  passing in another language does not prove the browser or native transport behavior.

## Readback and identity

Only the requested field is required to match the immutable target, but it must be known
and exactly equal. Missing, malformed, different or unverified results remain uncertain.
Bridge responses must include the exact requested ID. Identity is opaque UTF-8 byte
identity, with no trimming, case folding, URL decoding or Unicode normalization.

Canon kind=info can omit an ID: the exact requested camera endpoint supplies the identity
binding. An explicitly returned ID must still be an exact string match. This exception
is specific to canonical endpoint-bound responses, not a fallback for Bridge responses.

A write is dispatched at most once per selected item. Existing bounded readback may run;
the batch must not replay writes, create a new baseline, or issue preflight metadata fanout.
A returned value can confirm what was observed; it does not establish physical-camera
compatibility beyond the tested camera/transport/build evidence.

## Frozen batch and outcome rules

- Snapshot selected IDs and immutable operation/target once; preserve selection order and
  the first occurrence of each exact ID. Retain hidden and unloaded rows in the roster.
- Resolve eligibility using already-loaded current-owner items and existing capability and
  per-item constraints. The current loaded exact-ID item supplies qualification metadata;
  a stale selected copy supplies display information only when no current item is loaded. Unknown transport capability is not support. Explicitly unwritable
  items remain unwritable. The optional fields `protectionWritable`, `ratingWritable`,
  `rotationWritable` and `archiveWritable` veto their corresponding action when exactly false;
  true or absence never grants a missing capability. Archive additionally needs a known
  boolean prior archived state, matching the existing single-item safety gate. No extra GET
  is needed to make an unavailable row eligible.
- At most 1,000 unique selected IDs. Empty/over-limit batches are rejected before any write;
  do not silently truncate. At most one write is in flight.
- A safe preflight rejection is `failed-not-sent` or an unavailable `not-started` row with a
  reason. The remainder may continue. These states must not suggest the camera was changed.
- After dispatch, an error, lost response, cancellation without confirmation, malformed
  readback, wrong ID, or mismatched target is `uncertain`. Stop at the first uncertain row;
  preserve earlier `confirmed` rows and mark later rows `not-started`.
- Cancel prevents subsequent writes. A late exact confirmation within the same owner may
  remain `confirmed`; cancellation does not undo a completed camera operation. Do not report
  an interrupted in-flight operation as safely cancelled or failed-before-send.
- Session/owner replacement prevents later sends and stale mutations of the replacement
  session's UI/cache. A retained old-owner report is historical, never current-camera state.
- A later explicit user action creates a new batch. There is no automatic continuation or
  mutation replay from this ledger. Download/delete batch policies remain separate.

The batch vectors use synthetic executor events. `cancel-during-confirmed` means a same-owner
late confirmation after cancellation; `owner-lost-during` retires that owner without new-session
publication. UI-specific state names can differ if their exact meaning maps to these outcomes.

## Evidence levels

The fixture checksum and source guards establish corpus integrity, not product behavior.
Production-function JVM/JavaScript/Python checks, full HTTP clients, native UI and physical
camera tests are separate evidence. Missing Xcode/Android/Chromium execution stays NOT_RUN.
New batch controls are a user-visible capability (minor impact); strict evidence repairs are
patch fixes. This document does not change a version, publish a release, or make a PR ready.
