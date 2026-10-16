# iOS capture configuration outcomes and read-only recovery

## Status and scope

This candidate implements the existing capture-directory and Canon file-naming journey in the iOS app and its existing Direct CCAPI / Desktop Bridge clients. It is a patch-level correction against source version 0.13.0; this work does not change the release version.

Validation is explicitly split:

- Python contract/source checks: recorded in the candidate evidence manifest and log.
- Swift core, iOS app, and native URLSession tests: authored, **NOT_RUN**. The authorized environment cannot compile the existing native targets, and no native tool installation or retry was performed.
- Physical cameras, USB/Wi-Fi integration, Apple-device UI and accessibility: **NOT_RUN**.
- No claim of PR readiness, native transport acceptance, physical-camera parity, or wire exactly-once delivery follows from the source checks or mock request counts.

The previous media-delete journey, receipt types, scoped DELETE adapter and acceptance evidence remain separate and unchanged. Generic settings, media listing, storage scanning and transport selection are not expanded.

## Product journey

1. Open capture-directory controls or the file-naming editor. The submitted owner, connection configuration, operation, field, target selection, requested value and draft revision are frozen before asynchronous work.
2. One logical write is admitted for that attempt. A known immediate ACK is retained independently of the following read. A thrown read failure does not erase a valid ACK.
3. The panel shows the requested value, acknowledged value when available, write evidence, independent readback evidence, and the freshly observed value. Directory ACKs display the actual returned five-character suffix, which may differ from the request.
4. Cancellation stops remaining work where possible, preserves an already completed ACK in its original record, and suppresses that invocation's late live-state publication. A retired owner cannot change the replacement owner's UI, drafts, errors or busy locks.
5. “Refresh current settings” is available on every retained same-owner record and performs fresh bounded reads only. Recovery is globally single-flight, preserves history order and the newest current request/draft, and never replaces a newer directory ACK suffix with an older one. It does not call either write method and cannot establish a missing write ACK. An older Bridge without the fresh endpoint reports unavailable instead of falling back to cached capabilities.
6. The app retains at most 20 original records. Evicted records are not recreated by late responses. Retired receipts stay inspectable even if the replacement camera does not advertise these controls; their recovery actions are disabled. A dispatched unknown/acknowledged attempt disables another Apply/Create until the user explicitly prepares a new request. No creation retry is automatic, including after readback failure.

Text drafts are retained on submission and readback; later edits are not overwritten by incoming capabilities. A historical cancellation flag does not prevent a later explicitly requested, uncancelled read-only recovery from updating current settings.

## Independent evidence

Write evidence is `not-sent`, `unknown`, or `acknowledged`. Readback evidence is `not-read`, `unavailable`, `mismatch`, `matched`, or `refreshed`.

A complete strict naming read can be `matched` even when the earlier write remains `unknown`; observation alone never upgrades that write. The UI can treat a naming write as fully confirmed only with an ACK plus a matching complete state. A mismatch retains both the valid ACK and the actual complete observed state.

A directory `refreshed` result means only that the canonical current selection and its legal options were read successfully. Neither a five-character ACK suffix nor a current eight-character selection proves the exact numbered folder or storage slot created by the earlier operation.

## Protocol and validation boundaries

- Direct CCAPI retains the existing advertised, same-version complete endpoint group, write-method, ability, range and seven-resource naming validation. Naming continues to read the strict complete group after its immediate field ACK. The app consumes that returned observation without an extra capabilities GET.
- A directory post-ACK read uses only the canonical advertised `directoryselection` GET. An aggregate `/shooting/settings` value or an old cache cannot substitute for a failed canonical read.
- Explicit direct naming recovery reads the original seven advertised resources with a monotonic 15-second group deadline checked before and after each GET and before validating the full group. A custom transport returning a late final response cannot publish it. A transport ignoring timeout may still hold its call until settlement; the budget rejects late observations rather than claiming forced cancellation of that transport. Directory recovery reads one canonical resource. Each response is bounded to 64 KiB and must be complete JSON without duplicate keys. The simulator uses its existing read-only capabilities resource with the same scoped response bound.
- Bridge recovery is `GET /v1/session/{id}/capture-configuration/{operation}`, with a 15-second timeout and 64 KiB response limit. The exact operation is checked; naming must be a complete strict normalized group; directory selection must contain 1–256 unique legal eight-character options including the current value.
- A Bridge failure receipt is accepted only for HTTP 502, `CAPTURE_CONFIG_READBACK_FAILED`, the exact expected feature, error engine `ccapi`, and an actual connected SessionCreated engine exactly equal to `ccapi`. That actual engine is frozen before sending, independently of a requested `auto` engine and before retirement/cancellation checks.
- Receipt version is numeric 1, never a boolean. Operation, optional field and requested bytes must match the frozen invocation. Directory receipts omit `field` and carry a legal five-ASCII-character suffix; naming receipts require the exact field and legal ACK value equal to the request. A valid receipt preserves ACK evidence but the original 502 still throws.
- ASCII/full-range validation rejects terminal newline, CRLF, Unicode and malformed values without broadening unrelated settings validation.

## Existing URLSession adapter

Only the two capture-configuration writes and their scoped reads select the new task implementation within the existing HTTP transport. The task supplies its request body once, declines redirects and authentication replay, disables cache/credential/cookie reuse, and bounds the streamed response. Only error-free complete EOF can yield ACK bytes. Cancellation or truncation before complete EOF cannot manufacture proof. There is no fallback or automatic retry after a failed scoped request.

Native loopback tests exercise actual URLSession POST/PUT bodies, read-only GET, 307/308 rejection, authentication rejection, lost ACK, truncated/oversized/malformed JSON and cancellation during a partial response. Those tests are authored but were not executed here. Physical delivery can still be unknown after a single admitted write; one logical invocation does not prove exactly-once execution by a camera.

## Review and acceptance coverage

`CaptureConfigurationEvidenceTests.swift` consumes all original 241 shared contract vectors and all 88 exact-value vectors. The lexical naming/directory fixtures go through the actual Direct CCAPI and Bridge readback parsers. It also covers ACK plus failed read, mismatch, unknown plus matching observation, canonical-directory-only proof, malformed ACK, old Bridge 404, actual-engine binding, pre-dispatch cancellation and late ACK preservation.

`ControlResponseOwnershipTests.swift` preserves the existing ownership assertions and adds frozen drafts, bounded history, explicit new requests, one-write recovery, no redundant app naming GET, old-owner settlement, cancellation suppression and fresh recovery after cancellation. Existing naming readback gates now represent the complete response phase rather than an obsolete extra app capabilities GET.

`ios/validation/test_capture_configuration_source.py` audits fixture consumption, exact lexical guards, ACK order, fresh-only routes, cancellation/owner boundaries, bounded scoped transport, localized UI, and unchanged sealed delete sources. These are source checks, not a Swift compiler or a substitute for the native tests.

Before release, run the core/app/native suites on the supported Apple toolchain, exercise both localizations and accessibility on an iOS target, and record real-camera acceptance for supported endpoint groups. No speculative Canon ACK schema or folder-identity guarantee is introduced.
