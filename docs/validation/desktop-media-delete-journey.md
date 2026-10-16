# Desktop permanent deletion journey

Status: implemented source candidate; platform browser and physical-camera validation pending.

## User-visible contract

- Single-file and selected-file deletion share one immutable review. It lists every exact ID, filename and camera/desktop capture-file location, including hidden, unloaded, protected and unknown-source rows.
- Every attempt still requires an explicit permanent-deletion confirmation. A confirmation is single-use, bound to the exact connection object, and capped at 1,000 unique IDs. No unseen card-wide scan is introduced.
- Unknown source, unloaded, known protected or known unwritable rows are skipped. Missing protection/write metadata is displayed as unknown; server enforcement remains necessary. It is never turned into a claim of safe eligibility.
- Each final dispatch gate rechecks current owner, exact loaded ID, source, delete capability, protection and writeability. Refreshing or protecting a queued row cannot bypass this gate.
- An empty complete HTTP 204 is the positive HTTP receipt. A bounded complete refusal with strict framing can be rejected; other responses, truncation, lost acknowledgment and cancellation after dispatch remain uncertain. The owned Bridge preserves complete upstream CCAPI refusal status only for deletion.
- Uncertain or not-sent rows stop the remaining queue. Explicit refusals can continue. There is no automatic retry, compensating write, automatic unprotect, deletion readback or reconciliation scan.
- Cancellation stops the remainder and aborts the local request. It cannot roll back a dispatched deletion. A received positive receipt stays on its original attempt after owner retirement and cannot mutate replacement-session media or selection.
- Only confirmed IDs with unchanged per-ID selection tokens consume selection. Reselecting an ID during an operation is preserved. Partial listings do not silently discard unloaded selections.
- Results remain in this window, bounded to 20 whole batches / 2,000 rows. Reload closes this history. A read-only inspection button loads the current explicitly selected listing scope and does not retry deletion. Previously downloaded copies at other locations are untouched.

## Transport boundaries

The browser issues one logical Fetch per admitted row, rejects redirects, omits ambient credentials, and bounds response bodies to 64 KiB and local time to 30 seconds. CCAPI deletion uses a nonredirecting request and its existing 15-second transport timeout. Unknown real-camera acknowledgment contracts remain unverified; a legal response outside the strict receipt contract is conservatively uncertain.

Fetch may hide illegal raw bytes on a 204. Neither one application-level Fetch invocation nor source review establishes absence of transparent browser/network retries. Browser wire behavior and physical-camera deletion remain acceptance gates, not proven exactly-once behavior.

## Verification

- Shared 540-vector classifier/admission/roster/selection/batch contract is consumed by production JavaScript tests.
- Deterministic production-handler tests cover late receipt after disconnect/reconnect (including reused session ID), refresh, overlap prevention, current row requalification and filtered preview ownership.
- Python tests cover actual CCAPI adapter receipt qualification, complete refusal forwarding, cache retention on uncertainty and single-call no-redirect selection.
- Thirteen full-production-page browser journeys are authored, including bilingual confirmation, mixed locations, explicit refusal, uncertain statuses, redirects, cancellation, retirement, changed selection and changed queued eligibility. They have not been run in this workspace: the previously denied normal/formal Chromium launch paths remain stopped.
- No real camera or user file is deleted during local tests. Synthetic browser fixture cleanup only concerns its own disposable temporary directory.

This additive selected-delete and bounded-history capability is a minor-version candidate. No version number, main branch, PR readiness or release state is changed by this document.
