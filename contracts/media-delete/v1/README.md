# Media deletion journey contract v1

This fixture defines conservative client behavior for the existing supported deletion transports. It does not establish real-camera compatibility, grant permission to delete real files, or replace each operation's explicit permanent-deletion confirmation.

## Confirmed target and admission

Freeze the exact opaque object IDs, display names, source location, opening connection owner, and selection token before confirmation. Keep the first exact occurrence in order; do not case-fold, trim, normalize Unicode, replace IDs with filenames, or silently truncate. The maximum is 1,000 unique IDs. An empty, invalid, or over-limit roster cannot dispatch. The UI distinguishes camera storage, the connected desktop's host-capture store, Android's private host-capture store, and unknown source. A mixed-source confirmation identifies each location. No backup or recovery is implied.

Dispatch requires the same current owner, a confirmed unused token, and no overlapping deletion attempt. Known protected=true or writable=false vetoes dispatch. An unloaded row, unknown source, or missing advertised delete capability is skipped. Missing protection or writable metadata remains visibly unknown; it does not become a claim that the item is unprotected or writable. It does not widen the preexisting advertised transport capability. There is no implicit unprotect, new transport, whole-card scan, automatic retry, compensating delete, or resurrection of an old confirmation after reconnect.

## Evidence and immutable outcomes

Each row distinguishes `not-started`, `not-sent`, `confirmed`, `rejected`, `uncertain`, and `skipped`. Actual dispatch evidence takes priority over preparation/cancellation flags. After dispatch, a validated same-attempt success receipt is retained even if cancellation or refresh failure occurs later. A later error cannot downgrade it. No receipt after a possible dispatch means uncertain, not safe-to-retry. Only a typed authoritative refusal is rejected. An unsent local failure or cancellation cannot be called a completed delete.

Cancellation stops future rows; it cannot roll back a command already sent. An uncertain or not-sent interrupted row stops remaining dispatches. A fully observed explicit rejection may continue to the next frozen row under the existing batch policy. Every row has at most one dispatch. Disconnect remains usable, and old-owner records are read-only. Keep at most 20 batches and 2,000 rows, evicting whole oldest batches rather than partially rewriting a receipt.

A confirmed ID can be consumed from selection only while owner and original selection token match. Preserve later reselection and unrelated selections. A finer per-ID generation mechanism is permitted if it preserves this rule. Preserve exact IDs and source locations in per-item history, including rejected, uncertain, skipped, and never-started rows. Do not clear history merely because the user refreshes or reconnects.

## Transport evidence

The repository-owned Bridge and simulator DELETE endpoints synchronously return 204 after their backend operation. Existing CCAPI fixtures likewise use 204; this is not proof that every camera/firmware/version has only that legal success code. Other 2xx statuses are conservatively unconfirmed until an explicit supported completion contract is established.

For the HTTP policy fixture, confirmed requires a complete response with valid framing, status 204, and no body bytes. Complete, valid-framing statuses 400, 401, 403, 404, 405, 409, 412, 413, 415, 422, and 429 are explicit refusals, not success. Status 408, nonstandard 499, other 4xx, 3xx, other 2xx, 5xx, incomplete bodies, and invalid framing remain uncertain. Do not infer a previous success from 404 or an empty/partial listing. Do not silently follow redirects or replay a DELETE for connection/authentication recovery. Existing unrelated GET/authentication behavior is unchanged.

USB uses its own exact concrete object handle, matching transaction, and PTP result; it does not use HTTP status. Reject zero, reserved/all-object, malformed, and out-of-range handles before dispatch. A validated matching PTP success is the receipt; transport loss is not. Desktop CLI deletion retains successful command completion as its evidence, and host-capture deletion retains the exact local deletion result. These are not cryptographic camera-storage identity guarantees. Actual EDSDK provider semantics remain a separate unverified boundary.

Manual inspection reports current observations only. A missing item in a filtered, recent, or partial listing does not prove the prior attempt succeeded. Another delete is a new user action against the current owner and a new explicit target/location confirmation; do not resume the previous batch automatically.

## Fixture sections and evidence limits

Each case has `id`, `input`, and `expected`:
- eligibility: 216 cases
- admission: 24 cases
- outcome: 32 cases
- httpReceipt: 192 cases
- selection: 54 cases
- roster: 10 cases
- batch: 12 cases

There are 540 shared cases; these overlap the consuming platform suite totals and must not be added to them as separate tests. Pure policy, source checks, and synthetic transport tests do not establish native UI, physical-camera, OS permission, or byte-storage acceptance. Real deletion testing requires separately approved disposable target media.
