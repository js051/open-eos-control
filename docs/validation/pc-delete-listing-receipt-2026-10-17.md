# PC confirmed-delete / buffered-listing receipt fence

## Scope and original failure

G08/G10, existing PC media library. While a DELETE request is pending, Refresh remains available. The provider can buffer a listing before DELETE commits, then deliver that older snapshot after the confirmed DELETE receipt. Previously the listing replaced the media array and restored the deleted row.

The fix records confirmed IDs on the exact active same-session listing owner. Only that owner's subsequent snapshots exclude those IDs. The existing session/deletion-owner/idempotence checks remain authoritative. A later explicit listing owner starts with no fence, so a legitimately reused ID can appear again. Raw recent-scope overflow evidence is retained before filtering and slicing.

Non-goals: no metadata changes, new features, global generation bump, disabling Refresh, protocol changes, native-platform changes, releases or physical-camera claims. Android and iOS source audit found this overlap already excluded by their existing operation/listing admission and retirement rules; that is source-review evidence, not native execution or device validation.

## Automated evidence

Base: `3358c0286e0a05e06e1128b53108dd03d9a68049`.

Production `app.js` SHA-256 after the fix:
`79d6b9ad96b7f7fc9d3c2761353b09ccb91e54994796ff3f049566141c32c524`.

- Before production edits, the new source-extracted tests failed on buffered streaming snapshots and delayed legacy JSON responses restoring the confirmed-deleted ID. Three regression cases were red; retired-owner/session negative control passed.
- Final `npm --prefix bridge run test:modules`: exit 0; 3,867 node:test cases passed across 55 reporters, plus the existing standalone assertion scripts. Node old-space bound: 500 MiB.
- Existing Python validation environment, `pytest bridge/tests/test_static_ui.py -q`: exit 0; 15 cases passed.
- New tests exercise the production API/parser/listing/delete paths with synthetic transport and DOM: buffered stream, delayed JSON before first snapshot, receipt-only filtering, unrelated selected rows and receipt history, fresh listing ID reuse, retired delete owner, same-ID replacement session, and raw 61-row overflow evidence.

No browser or camera execution is implied by the Node tests. A separate loopback fixture is prepared for baseline/fixed Firefox inspection. It uses production HTML/assets and synthetic HTTP-delivery controls, preserves native confirmation, and does not inject application state. Browser execution and assessment are separate pending evidence.

## Release assessment

Impact: patch, correcting an existing media deletion display race. No version or release metadata changed. This local implementation is not PR-ready, main-accepted, or preview-released; browser evidence and integration review remain separate. Physical-camera status: not tested for this race.
