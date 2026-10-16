# iOS ordinary shooting-setting outcomes

## Implemented flow

Every existing advertised choice, exposure rail, range, text and capture-mode control first selects/edits a local draft and requires an explicit **Apply**. Selection and slider movement do not dispatch a write. Each Apply uses a frozen setting key, exact requested value, current connection/configuration owner, editor revision and one logical write attempt. A successful transport response and a fresh observed value are separate facts:

- `not-sent`: no command dispatch was admitted.
- `unknown`: dispatch began, but a complete valid successful response was not established.
- `response-received`: the existing transport's successful response was retained. This is not an exact camera ACK or proof that this write caused the observed value.
- Readback is independently `not-read`, `unavailable`, `matched` or `mismatch`. A matching recovery read never upgrades an unknown write.

The settings sheets show requested/observed values and both evidence levels. The main photo/video control links to the same result. A new write requires the explicit **Prepare new change** action followed by a fresh selection/Apply; refresh never writes. Choice/text/range/mode drafts retain exact selected values and new edit revisions, including same-value edits. Passive capability refresh, reordered range choices, dismissal and later response completion cannot consume a newer draft.

## Transport and ownership

Direct CCAPI performs one bounded fresh read of the selected advertised setting before dispatch. Structured image-quality/WB-shift writes use the whole fresh selected object, preserving sibling fields and the existing all-formats-disabled guard. The exact write/read route is frozen before suspension. A failed write never tries another alias route. The app no longer waits on general status, storage or capability fan-out to decide the setting result.

A direct observation uses the exact advertised GET or its matching-version settings aggregate. Simulator primary exposure uses capabilities plus status within one 15-second read group; other simulator settings use capabilities. All response bodies are limited to 64 KiB. The existing HTTP stack has a separate setting-scoped no-redirect/no-authentication-replay task for GET/POST/PUT/PATCH. Custom test transports retain a single invocation and no fallback. This design is not an exactly-once network guarantee.

Bridge uses additive POST `/v1/session/{id}/settings/{key}/outcome` and GET `/v1/session/{id}/settings/{key}`. The response binds HTTP 200, exact integer version, actual connected engine, key and requested value before retaining response evidence. Complete UTF-8 JSON rejects duplicate keys, malformed/truncated/ambiguous framing and oversized bodies. A valid base with a missing, malformed or contradictory observation retains `response-received/unavailable`. Unknown endpoints never trigger a legacy POST fallback. See the shared shooting-setting v1 contract.

The app retains at most 20 records. Read-only recovery is explicit, single-flight and restricted to retained records from the same connection. Recovering older history never selects that record as the newest intent. Cancellation does not undo a dispatched write. Late proof stays with its original retained record; cancelled or retired work cannot publish to the new session, clear its busy state or consume its draft. Event refresh already waits for operation ownership; manual unscoped refresh is now gated while a setting write/read is active. Observation/revision checks also suppress stale publication without erasing response evidence.

Existing shutter, held-autofocus, Bulb, REC and foreground-transfer guards remain in force. No stop responsibility, security setting, physical-camera operation or new camera transport is introduced. Sealed capture-configuration/delete model, scoped transport and UI files remain byte-identical; only shared app/session/client/HTTP integration points are extended.

## Validation and boundary

- Python source/fixture audits: run locally with the existing approved interpreter; exact result is recorded in the candidate evidence manifest.
- Shared contract: 24 wire + 64 ownership + 24 outcome vectors. Swift consumers are authored, including actual Bridge-client fixture invocations.
- Authored native Core tests cover response/readback separation, strict envelope/JSON rejection, fresh ability validation, structured sibling preservation, no alias or legacy fallback and read-only recovery.
- Authored App tests cover double Apply, cancellation/late completion, manual-refresh isolation, unknown writes with matching recovery, bounded history and session retirement. Existing control-owner tests cover replacement-session late responses.
- Authored URLSession loopback tests cover GET/POST/PUT/PATCH, 307/308, authentication, lost/truncated/oversized/malformed responses, incomplete-body cancellation and no redirect/body replay.
- Swift compilation, native unit tests, URLSession loopback execution, native UI/rendering, physical-camera behavior and performance: **NOT_RUN**. Swift/Xcode is unavailable in this environment; no toolchain was installed.

## Release assessment

Impact: `patch`, a backward-compatible correction to existing advertised setting behavior. Additive Bridge support is required for the new outcome flow; an older Bridge leaves an uncertain result without replaying a mutation. No merge, version bump, CI run or release is part of this candidate. Physical-device compatibility and exact native behavior remain unverified.
