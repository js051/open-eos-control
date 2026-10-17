# Desktop Bulb request ownership

## Scope

This repairs the existing PC Bulb control when Start's request or response body never settles. It requires the Bridge's additive owned Bulb routes. It does not refactor REC, held autofocus, legacy short-control recovery or camera providers, or change release policy or physical-camera compatibility claims. The companion Bridge admission gate prevents new camera controls from entering while an owned Bulb duty is unresolved; see `bulb-cross-control-admission.md`.

## Safety contract

- Before Start dispatch, retain the exact session object, session ID, authentication token, and a cryptographically generated owner ID. The pending request already owns a Stop duty.
- Send only `POST /v1/session/{id}/bulb/start/owned` and `/bulb/stop/owned`, with `{ownerId}`. An older Bridge's 404/405 never triggers a legacy Start fallback.
- Bound each entire request, including response-body parsing, to 15 seconds. Abort is not proof of cancellation or release. A transport that ignores abort cannot hold the PC command reservation indefinitely.
- Stop may be sent before Start arrives or finishes. Concurrent Stop actions join the same request. Server serialization and a cancellation tombstone provide the ordering guarantee; the client does not manufacture it by waiting for Start.
- Only a matching owner's terminal Stop receipt clears that owner's duty. `released` confirms the ordered release and may omit status on repeat. When supplied, release status must contain literal false for both `bulbExposureActive` and `shutterReleaseUnconfirmed`. `cancelled-before-start` confirms only that this request cannot dispatch and never displays physical-stop confirmation or clears an unrelated shutter/AF warning.
- A cached receipt without status retires only its owner's duty, never a later camera observation or an independent sticky shutter warning. If the only remaining active flag is the exact owned Start snapshot, its identity and observation revision allow that stale flag to be removed without inventing a new `false` observation. A later adoption, even of the same object, is preserved.
- If another exposure or warning still locks the camera after owner retirement, preserve the existing error, suppress capture flash and polling resume, and show the camera-stop-unconfirmed footer instead of global stop confirmation. A cancellation keeps its owner-only wording and also preserves the unrelated error.
- Supplied dual-false release status may update the camera snapshot only if no newer observation was adopted during that Stop request. A global warning first raised during the request remains sticky. The Bridge must omit cached status on repeated terminal receipts: there is no wire observation revision, so an old dual-false payload replayed after a newer observation but before a retry cannot be distinguished from fresh proof. This contract does not claim compatibility with servers that replay stale status in terminal receipts.
- Ordinary idle status, missing fields, errors, and late Start results cannot clear the owner's duty. Session-object identity, command-owner identity, and interaction generation prevent an old completion from unlocking or changing a replacement session or a newer operation.
- The shutter control immediately becomes Stop and the footer becomes “Bulb start is pending. Stop is available.” Stop and Disconnect can bypass only their own pending command reservation. Other mutations remain locked. Read-only Refresh remains usable once no owned command is pending.
- Visibility loss requests one bounded saved-owner Stop while keeping the session. Disconnect and pagehide join any pending Stop, then make a separately bounded close using the saved session and token. Uncertain teardown leaves a previous-camera warning and never transfers the duty to a replacement.
- Unowned recovery from existing camera state or a short shutter/AF operation retains the existing `/bulb/stop` route and bounded readback behavior.

## Automated evidence

`bridge/tests/bulb-request-ownership.test.js` executes production functions with controlled in-flight responses and a virtual clock. It covers pending Start controls, Stop-before-Start, repeated Stop joining, both fetch and body stalls despite ignored abort, legacy-Bridge fail-closed behavior, malformed and mismatched receipts, same-ID replacement, newer busy owners, exact-token retry, visibility, pagehide, Disconnect, close deadlines, and warning isolation. Baseline red tests failed on legacy Start dispatch and the missing immediate Stop request before implementation.

Cached-receipt regressions additionally cover an owned release whose reply is lost, a later short-control cleanup warning or another exposure, and the original owner's status-free terminal retry. They check independent global warnings, absent status fields, exact-Start retirement, re-adoption of the same status object, a supplied old status arriving after a newer observation, and genuine supplied dual-false proof. Before the cached-receipt repair, eight assertions failed against production functions. Four additional or strengthened cases then exposed error/footer and success-effect leakage before that repair. The revised focused suite passes 49 cases, with 459 cases passing across owned Bulb, legacy Bulb recovery, and capture review. These remain synthetic client evidence, not camera or browser validation.

`bridge/tests/capture-review.test.js` retains the malformed-502 regression using owned receipts; unowned recovery timeout coverage stays separate. `bridge/tests/bulb-recovery.browser.test.js` now includes a held-before-dispatch Start, real Bridge cancellation tombstone, accessible pending Stop/Disconnect, and explicit same-owner Stop retry after a failed status tail. A previously enabled Stop or old footer text is not used as a completion oracle.

Local checks:

- `node --check bridge/open_eos_bridge/static/app.js`
- `node --check bridge/tests/bulb-recovery.browser.test.js`
- `node --test bridge/tests/bulb-request-ownership.test.js`
- `npm --prefix bridge run test:modules`
- `git diff --check`

Host evidence is synthetic. Integrated real-browser execution and exact-commit aggregate checks remain separate acceptance steps. No physical camera was exercised by this change. The distributable impact is a backward-compatible patch to the bundled PC/Bridge Bulb safety behavior; no version bump or publication is included here.
