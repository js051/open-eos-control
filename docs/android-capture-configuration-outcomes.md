# Android capture-configuration outcomes and recovery

Scope: capture-directory creation and the seven-field Canon file-naming group only. Existing advertised-capability, same-version endpoint-group, field, exact integer and range validators remain enforced. No generic setting workflow, card enumeration, camera/host deletion, transport, CI, release, or permission changes are included.

## User journey

1. Open Create directory or the file-naming editor. The editor captures the actual connection owner/epoch and an explicit draft token. User edits advance its revision; composition/re-render does not.
2. Apply once. The repository freezes the actual backend; clients retain the selected operation and exact session/resource route. One consumed draft revision admits one logical write, with a real dispatch boundary and a separately validated camera ACK.
3. Results show the frozen endpoint, camera, request, wire target, write evidence and readback evidence. The input draft is retained. A five-character directory suffix is never described as an exact numbered folder or card-slot identity.
4. An explicit “Read current settings (no writes)” action uses only a fresh directory-selection GET or a complete seven-resource naming GET. All retained same-owner records can be inspected separately; recovering an older record does not promote it or replace a newer draft/receipt. A retired owner performs zero recovery I/O.
5. Cancel preserves completed evidence. A dispatched command cannot be undone. A cancelled write or recovery invocation cannot publish current settings; a later explicit, live recovery may publish fresh settings while the original cancellation marker stays recorded. Reconnect retires the original owner and releases only its own locks after settlement; late results can update existing history rows but cannot resurrect evicted rows or modify a replacement session.

The latest 20 records are retained in memory. One global capture-configuration write/read lock prevents overlapping submissions and recoveries. Creating a new directory draft explicitly opens a new request; merely rendering its UI never enables a replay.

## Evidence and HTTP boundaries

- Write evidence is independently `not-sent`, `unknown`, or `acknowledged`. Generic exceptions are not treated as dispatch/ACK proof.
- Read evidence is independently `not-read`, `unavailable`, `mismatch`, `matched`, or directory `refreshed`. A later match cannot upgrade an unknown prior write.
- Valid ACKs are recorded inside response handling before coroutine return/session-retirement checks can discard the result. Direct-client failures after ACK carry typed receipts.
- A successful Bridge directory ACK is followed by one scoped fresh selection GET on the same session and target frozen before the write. Cancellation or retirement suppresses that read. A 502 ACK receipt requires explicit recovery, with no automatic follow-up. Read failure retains the ACK and marks readback unavailable.
- Directory proof is sourced only from the canonical validated directory-selection GET. A legal aggregate/cached shooting-settings value cannot hide a failed canonical GET. Valid current value and advertised choices are preserved and replace only the current owner’s directory picker.
- Capture-only HTTP clients disable redirects, authentication follow-ups, cache and connection retry. Mutations use one-shot bodies. The 408/421/503 follow-up path is rejected. This is a configured single-attempt policy, not a claim of wire-level exactly-once delivery.
- ACK/recovery JSON is bounded to 64 KiB, strictly decoded as UTF-8, checked for complete declared framing, and rejects duplicate keys, non-JSON syntax and trailing JSON.
- Explicit recovery has a 15-second total coroutine budget. No automatic command retry, cached-capability fallback, whole-card scan, or write is issued by recovery. Older Bridge servers returning 404 leave readback unavailable.
- Bridge 502 receipts must match the original request, actual captured CCAPI engine, exact error code/feature/version/operation/field and legal ACK value. The success response format is unchanged. Directory `field` must be absent, not null.

## Validation and limits

The focused pure-JVM harness compiles the real evidence/workflow/JSON-boundary production sources and uses unchanged extracted DTOs plus typed adapters for all 329 common vectors (241 evidence/ownership/recovery and 88 exact-value cases). It also executes synthetic cancellation, late ACK, reconnect, mismatch, retained-record recovery, draft replay, directory-choice replacement and byte/JSON boundary tests. Typed adapters do not validate the Android `org.json` loader or AGP resource packaging.

Additional authored tests cover real client calls using MockWebServer and two Compose UI journeys. Android SDK/Gradle/native typechecking, MockWebServer and Compose execution, and physical cameras are NOT_RUN in the restricted cloud validation environment. Kotlin PSI proves syntax only. Source-wiring and XML checks do not substitute for native execution.

Release assessment: proposed patch impact for a backward-compatible repair of existing operation-result handling. No version bump, commit, push, main merge, release or camera compatibility claim is authorized by this implementation artifact. Exact-SHA native CI and physical-device status remain separate delivery gates.
