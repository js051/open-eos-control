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

No browser or camera execution is implied by the Node tests. A separate loopback fixture is prepared for baseline/fixed Firefox inspection. It uses production HTML/assets and synthetic HTTP-delivery controls, preserves native confirmation, and does not inject application state. Browser execution is recorded below.

## Release assessment

Impact: patch, correcting an existing media deletion display race. No version or release metadata changed. Independent source review and loopback Firefox verification passed; this is a dev integration checkpoint, not main acceptance or a release. Physical-camera status: not tested for this race.

## Firefox evidence, 22:43–22:52 UTC

[Fixture, receipts, screenshot manifest](evidence/pc-delete-listing-firefox-20261017/manifest.json). Existing cloud Firefox, native confirmation and mouse input; all camera fetches intercepted by the synthetic fixture, server bound only to 127.0.0.1:18792. No camera or USB commands.

- Baseline 3358c028: actual Refresh while DELETE pending, then DELETE 204, then buffered older listing. Final DOM receipt records both deleted and kept cards with Deletion confirmed. The saved baseline screenshot precedes the final late paint; the final screenshot attempt missed the 180-second owned-process deadline. The regression claim therefore uses the final DOM receipt, not that screenshot.
- Fixed source 2432dd5d: same native UI sequence leaves only SYNTHETIC_KEEP.JPG after the older listing; screenshots 01 and 02 and DOM receipt agree.
- Negative control: permit the same ID on a later fresh listing, then actual Refresh. Both IDs return normally (screenshot 03 and DOM receipt), confirming the fence is limited to the old listing owner.
- Fixed fixture observer text is nowrap to avoid evidence controls shifting when events grow; response ordering and product assets remain unchanged. Both runners terminated their owned process groups; fixed PID 52689 ended 22:52:45 UTC, remaining group empty.

These are synthetic browser transport results. Real camera deletion, Android and iOS native execution remain unverified.
