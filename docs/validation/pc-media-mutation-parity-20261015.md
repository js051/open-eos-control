# PC media mutation batch and strict evidence, 2026-10-15

## Scope and delivery state

Local implementation on frozen baseline `e88970b9daca59b9904c2ce848a76a188ecea892`.
The new controls apply protection/unprotection, ratings 0–5, rotations 0/90/180/270,
and archive/unarchive to the selected roster. English and Traditional Chinese
labels are supplied. Single-item details use the same confirmation policy.
This document is not PR-ready, merge, release, or physical-camera acceptance.

## User-visible rules

- The selection is frozen in its exact ID order, keeping the first duplicate ID.
  Hidden loaded selections remain eligible. Unloaded selections remain visible
  as not started. No unseen full-card files are added.
- A batch above 1,000 unique IDs is rejected as a whole before any write; the
  roster is never silently truncated.
- Existing feature capability is required. An explicit per-item
  protectionWritable/ratingWritable/rotationWritable/archiveWritable=false vetoes
  its action. Archive also requires an already-known boolean archived state.
  Unknown or true writable flags do not grant capability. No preflight GET fanout.
- Only one mutation is in flight, using one existing Bridge PUT per eligible item.
  Canon verification keeps its existing maximum of three same-item GETs. There
  are no automatic write retries, alternate mutation paths, or resumed batches.
- Confirmation requires the same exact ID and valid requested field value. An
  ACK, missing field, malformed value, wrong ID, or different value is uncertain.
  All errors after dispatch are uncertain, including HTTP errors. The first
  uncertain result stops later writes while retaining prior confirmations.
- Results distinguish confirmed, uncertain, failed before sending, and not
  started. Cancel remaining stops later writes. An exact late readback for the
  same owner may still confirm the in-flight write; cancellation is not rollback.
- A changed owner or session, including reuse of the same session ID, prevents
  later writes and publication to the replacement connection. The ledger is
  session-local, not a persistent/process-resumable mutation history.
- Download saving/history, foreground JPEG import, delete, transport capability,
  device connection and production versions are not expanded by this work.

## Evidence decoding

The fixed shared corpus is `contracts/media-mutation/v1/media-mutation-vectors.json`
(SHA-256 `2d30173f8abb0538f1aa320cfce24e2fb0e01f86ef6fc54db93a052ed2fdd401`).
The separate 25 qualification and two current-loaded snapshot vectors are in
`media-mutation-eligibility-vectors.json` (SHA-256
`c7c6e27e7455ff12989cea4fab48f97fd103344ff442987a37cc4a32e3f99a1d`).
They exercise production qualification and selection refresh, not only fixture integrity.

Normalized Bridge metadata accepts only JSON integer tokens or canonical ASCII
integer strings for rating/rotation, and JSON booleans for protection/archive.
Canon metadata accepts only the exact documented string lexemes. These are
separate profiles. Generic camera-setting helpers are unchanged.

The browser's actual metadata response path inspects raw JSON number tokens
before JSON.parse can erase 3.0, 3e0, or a precision-losing fraction. Duplicate
critical fields cannot confirm. Only the verified requested field is applied to
the current cache. Python model validation separately rejects invalid normalized
values, and Canon media-info decoding rejects duplicate keys and conflicting IDs.
Both ordinary Canon media-info and bounded fresh inventory/media-info reject
NaN, Infinity and -Infinity JSON constants anywhere, including unrelated nested
fields. A malformed body stops on the first readback after one PUT; it cannot
confirm and never replays the write. Fresh reads still invalidate stale size/MIME
evidence and close their streams on rejection. Quoted strings with those names
remain legal JSON. Generic camera-setting decoders are untouched.
An absent Canon ID is bound to the exact canonical endpoint; an absent Bridge ID
cannot confirm.

## Verification and limitations

- New JavaScript production-function tests consume normalized metadata/readback,
  every shared batch-state vector, and every roster vector. They exercise the
  actual app callbacks and HTTP-response parser with synthetic responses.
- Python tests consume all shared metadata vectors and all Canon readback vectors,
  and test real in-memory Bridge HTTP validation plus bounded Canon requests.
- Existing download, transfer history, foreground JPEG import, owner, listing,
  and other module/Bridge tests are rerun with the same installed Python/Node tools.
- Authored `bridge/tests/media-mutation.browser.test.js` and wired it into the
  required `test:browser` chain. It drives the real production DOM served by the
  real Bridge with controlled HTTP response fixtures: all 14 choices in both
  languages, hidden/unloaded frozen roster, per-row confirmation/uncertainty,
  cancel remaining, immediate disconnect cancellation, and narrow 200% layout.
  No private app state or function is replaced. These cases are authored, not
  executed evidence.
- Chromium UI execution: NOT_RUN. The installed Playwright package has no usable
  Chromium executable; no browser installation or alternative download was used.
- Physical Canon camera, real Wi-Fi/USB transport, and visual/accessibility browser
  acceptance: NOT_RUN. Synthetic tests establish protocol/owner behavior only.
- Precise final commands, results, file hashes and earlier diagnostic failures are
  retained in the local evidence receipt. No test counts from separate runs are
  added together as one full-suite result.

## Release Assessment

- Latest verified published baseline: Development Preview v0.12.0, previously
  checked at 2026-10-15 03:54:34 UTC. The checkout declares 0.13.0; this is distinct
  from the published baseline. No new live release lookup was performed.
- Proposed impact: minor for the coherent new selected-file metadata capability;
  patch for strict metadata-evidence and owner-safety repairs considered alone.
- No product version change in this batch.
- Blockers: coordinator review and exact-source CI/PR acceptance remain pending;
  Chromium/native visual acceptance and physical-camera evidence remain absent.
- Pending physical validation prohibits unsupported compatibility/reliability
  claims. It does not itself promote or reject a future Development Preview.

## 繁體中文摘要

PC 現在可將保護／取消保護、0–5 星、0/90/180/270 度及封存／取消封存
套用至固定的多選清單。隱藏項目仍納入；未載入、不支援或明確不可寫的
項目保留為未開始。一次只送出一個項目，回讀相同 ID 與指定值後才確認。
結果不確定會停止後續項目，不自動重試；取消會保留已確認結果。
目前證據限本機合成協定與程式測試，尚未進行瀏覽器視覺或實體相機驗收，
也未推送、合併、發布或變更版本。
