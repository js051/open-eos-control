# iOS upload outcome and picker ownership validation

## Release Assessment

- Latest verified release baseline: **v0.12.0 Development Preview**.
- Current source version: **0.13.0, unpublished**. No version or release metadata changes.
- Proposed impact: **patch**, correcting the existing upload journey, evidence claims and selected-file ownership. No new transport.
- Delivery state: implementation and focused tests authored locally. Native compilation, Swift tests, simulator UI tests, exact-head CI and physical-camera checks are **NOT_RUN** here. This is not PR-ready, main-accepted or preview-released evidence.
- Release blockers: native compilation and new/retained native gates must pass for the candidate; normal CI/provenance/release acceptance remain required. Camera/firmware/USB upload compatibility and cancellation reliability are not claimed.

## Product contract

1. Opening the real document picker captures a unique presentation and camera-session owner. Its immutable UIKit coordinator delivers at most once. Admission consumes only that opening; stale, repeated, unsupported or busy callbacks cannot read/send the selected file to a replacement camera.
2. Security-scoped access is acquired after admission. An unacquired URL is not released; an acquired URL is released once when the admitted task settles, including pre-entry cancellation, failed staging and retirement.
3. The Bridge client stages a bounded private snapshot before HTTP entry. Cancellation/local failure before the dispatch marker is `not-sent`. After possible dispatch, no receipt means `uncertain`. Body progress is not completion evidence.
4. Only HTTP **201** with this invocation's validated receipt confirms completion. ID/name/kind must be nonempty strings, ID remains byte-exact, filename matching allows ASCII case variants only, and the original JSON size token must be a positive integer equal to staged size within the existing 4 GiB minus one limit. Duplicate members, booleans, fractional/exponent/string sizes, normalized Unicode, other attempts and 200/202/204 cannot confirm completion.
5. A complete valid response survives a racing cancellation; incomplete/failed bodies do not. The upload-specific URLSession delegate refuses redirects, including 307/308. No automatic retry or same-name deletion.
6. Receipt publication precedes optional gallery refresh. Cancelled/uncertain attempts start no automatic listing. An ordinary uncancelled confirmed attempt may refresh only bounded Recent on the same task. Refresh failure preserves its receipt. All progress/result/error/refresh/finally publication is fenced by session and attempt; refresh also checks gallery generation/scope.
7. English and Traditional Chinese distinguish confirmed/not-sent/uncertain. Uncertain results offer explicit **Review camera files** and ask the user to inspect the original camera/folder before choosing again. A listing never upgrades uncertainty. Previous-connection results are labeled and cannot offer review against the new camera.
8. Direct Canon CCAPI and the CCAPI simulator remain upload-unsupported. No new checksum promise, automatic whole-card scan or physical camera operation.

## Authored native gates (NOT_RUN)

### Shared XCTest resource

`upload-outcome-vectors.json` is required by the official `OpenEOSControlTests` target, with `createIntermediateGroups: false`. SHA-256:

`dcb90087f58db32166fa86291486190a1aca1ea368ff7e74cf3342a601c8b1fd`

`MediaUploadContractTests` consumes all 48 outcome, 12 admission, 20 publication, 48 receipt and 14 journey cases through production decisions, the item-producing receipt verifier and attempt state. Retired null owners are optional, not force-cast. A native staging test checks immutable snapshot content and temporary-directory cleanup.

### App, security scope and delegate ownership

`MediaResponseOwnershipTests` retains existing upload/progress/listing/scope/reconnection assertions with explicit opening tickets and adds:

- Production `UIDocumentPickerDelegate` coordinator single-use delivery and old/new opening isolation.
- Supported A to supported B picker retirement before acquiring file access.
- Denied grant, pre-entry cancellation, retirement and unreadable staging: exact acquire/release and zero POST/new-list.
- Existing same-name/same-size, ASCII case variant, another folder, unknown listed size and absent media after cancellation/lost ACK: no success, automatic read or retry.
- Verified reply after cancellation: one POST and no new listing.
- Exact receipt identity retained despite an earlier same-name gallery item and failed refresh.
- Late old-owner progress/receipt/error/finally and old gallery-generation responses cannot overwrite newer operations.

`DesktopBridgeClientTests` adds actual-client whitespace-only nonempty and opaque Unicode ID checks, so the generic list parser cannot contradict the upload contract.

### Real URLSession loopback HTTP

`URLSessionMediaUploadTests`, in the existing OpenEOSCore Swift package test target, uses `NWListener` and production URLSession/Bridge client:

- 201 receipt, exact staged bytes and receipt retained after cancellation.
- Lost ACK, invalid receipt, 202, truncated body and cancel after complete POST body.
- 307/308: one original POST, zero redirect-target POSTs, uncertain result.
- Direct/simulator CCAPI rejection without source read or HTTP dispatch.

These are authored protocol tests, not an executed HTTP pass here.

### Actual OS picker and UI

`OpenEOSControlUITests` adds English/Traditional Chinese journeys using an opt-in synthetic Bridge peer while retaining the real OS picker, app, staging and client:

- Open/cancel picker, disconnect/reconnect, open/cancel a fresh picker.
- Select a synthetic file in the real picker; success, cancel during upload or lost ACK; manually review uncertainty; reconnect and retain the labeled previous-connection result.

The DEBUG-only fixture seeds a uniquely named synthetic file in the app's Documents directory and supplies the picker's initial directory. The native provider must expose it: failure to find/select it is a failed native gate, never replaced by delegate injection or counted as passed. OS file-provider reachability, actual security-scoped grants and OS-delivered late callbacks require the native run. Controlled delegate/App tests do not substitute for those UI gates.

## Locally executed evidence

- Shared-corpus integrity plus iOS source/project guards: **20 passed**.
- Existing `scripts/validation/tests` and `scripts/contracts/tests`: **112 passed**.
- Ruff on changed iOS Python guards: **passed**.
- `git diff --check`: **passed**.

These inspect source/contract consistency; they do not compile or execute Swift. `swift`, `swiftc` and `xcodebuild` are absent; none were installed. No CI, commit, push, merge, release, physical-camera write or security-setting change was performed.
