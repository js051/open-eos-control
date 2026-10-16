# iOS first connection and recovery

This change implements the iOS connection-guidance group on the existing CCAPI and Desktop Bridge transports. It does not add a transport, endpoint, permission, camera capability, or automatic reconnect policy.

## Product behavior

- The connection form provides English and Traditional Chinese setup help. Direct-camera help explains that CCAPI must be enabled on a supported camera and that Canon Camera Connect pairing alone does not enable CCAPI. The supplied URL must match the camera's CCAPI address and port.
- Desktop Bridge help explains the USB-to-computer route, the computer's network URL and bearer token, camera scanning/selection, and the existing lack of direct iOS USB/PTP control.
- Failed connection or scan attempts present localized guidance alongside the fields. The edit action focuses the relevant field. Retry uses the existing explicit Connect or Scan action; changing a field, opening help, or displaying an error does not initiate a connection, scan, or shooting command. Existing lifecycle cleanup remains separate.
- Camera username/password fields automatically open only for a proven direct-camera HTTP 401/403 failure. Bridge HTTP 401/403 points to the Bridge bearer token. A Bridge 502 containing camera-authentication text alone remains a generic service error and does not open camera credential fields or claim the Bridge token was rejected.
- During Bridge scan/session creation, an actual HTTP 502 with exact `error.code = CCAPI_DISCOVERY_FAILED`, `error.engine = ccapi`, and a recognized `error.connectionReason` can provide camera-via-Bridge guidance. Its eight closed values describe authentication, TLS, host lookup, timeout, reachability, HTTP, discovery, or missing endpoints. That card directs the user to camera CCAPI and the Bridge's camera connection settings on the computer; it does not focus the mobile Bridge token or direct-camera fields. Unknown, absent, malformed, oversized, or mismatched evidence retains the existing HTTP classification. See [source validation and limitations](validation/ios-bridge-camera-guidance-2026-10-11.zh-TW.md).
- Bridge discovery distinguishes not scanned, scanning, failed, and completed. Only a completed scan with an empty result says no cameras were found. A cancelled rescan restores its preceding result/guidance. URL, token, mode, and disconnect changes retire the existing scan owner, so a late old scan cannot change a newer result, error, or busy state.
- Connection results and recovery also belong to the exact configuration revision and session generation. A late result after an edit or disconnect cannot become guidance for the new configuration. A retired successful connection closes its original session through the existing shutter-release cleanup.
- Scan and connect admission is mutually exclusive in the state API as well as the form. A held rescan cannot admit a new connection, and a held connection cannot admit a scan; neither rejected call changes recovery or starts additional transport work.
- Connection field edits do not clear unrelated operation errors or shutter-release warnings. Existing shutter interlocks and exact-session release handling remain in force.

## Evidence and compatibility

`CameraConnectionFailureReason` contains only enum values: invalid address, authentication rejected, endpoint missing, HTTP error, TLS error, host not found, timeout, unreachable, discovery failure, and unknown. It reads typed HTTP status and `URLError` codes; it never parses a server message, URL, password, token, or diagnostic string for a cause. Cancellation is excluded from recovery.

CCAPI discovery keeps a bounded set of reasons across the existing root, developer-list, and identity fallbacks. Successful fallback still wins. On terminal failure, an earlier HTTP 401/403 cannot be replaced by a later 404. The required identity read during `connectSnapshot` preserves the same evidence through its existing version fallback. Ordinary `info()` and other command/read error paths retain their previous handling.

The legacy `CCAPIError.discoveryFailed([String])` case remains constructible and its diagnostic strings map only to a generic discovery failure. New terminal failures use `discoveryFailedWithReasons`; connect-time identity failures use `connectionReadFailed`. Their localized diagnostic formats retain the existing messages. Code matching an exact terminal enum case must recognize these additive cases. No protocol request, authentication-header construction, URL normalization, capability inference, or TLS trust policy changes.

Presentation state contains only the connection mode, operation, reason, scan phase, and an opaque in-memory configuration revision. It is not persisted or added to diagnostic logs. Existing URL/username preferences are unchanged; passwords and tokens remain in memory. The optional Desktop Bridge `connectionReason` extension is not consumed in this group: only actual Bridge HTTP status is classified, so unknown response fields remain compatible.

## Automated verification scope

- Core fixtures cover invalid URLs, all-missing discovery, 401/403 followed by missing fallbacks, developer-list authentication, TLS/DNS/timeout/network errors, forged error text, successful discovery/identity fallback, explicit corrected retry, required identity-read evidence, and cancellation without further fallback.
- App tests cover inline safe reasons, direct-camera versus Bridge credential targets, no automatic retry on edits, successful explicit retry, stale connection success/failure across configuration/session changes, cancellation, and both localized resources.
- Controlled overlap tests cover both state API admission directions with a prior scanned camera, late success/cancellation, preserved failure guidance, exact discovery/session counts, and a permitted explicit retry after the owning operation settles.
- Existing controlled async tests now include scan phase and recovery in ownership assertions, distinguish completed-empty results, preserve unrelated warnings, and cover old authentication failures arriving after newer network guidance and cancelled retry restoration.
- The existing shutter-recovery test also checks that mode/credential edits retain an unresolved release warning. The existing Bridge form UI journey is extended with invalid-address recovery, unchanged authentication disclosure, correction without connection, and the initial unscanned state. Existing wait deadlines and hit-frame checks are retained.

## Initial source-only preparation

Prepared against draft base `df3f24329f5937cf19f7fa3ec302e1d173b8e647` on 2026-10-11. Source review, whitespace checks, and localization key/format checks are local validation only.

- Swift Core runtime tests: **NOT_RUN**
- iOS App unit tests: **NOT_RUN**
- iOS Simulator UI journey/build: **NOT_RUN**
- New-head CI: **NOT_RUN**
- Physical iPhone/iPad and EOS validation: **NOT_RUN**

The initial local environment had no Swift/Xcode compiler. That preparation did not install a toolchain, build, trigger CI, push, merge, or release. Prior Core 301 / App 332 / UI 31 results applied only to the old exact base, not these edits. Physical-camera compatibility and reliability are not claimed.

Source review also replaced the existing large-text date-filter journey’s initial Offline Preview existence-only wait with the existing bounded connection-scroll helper. The same eight-second budget and all media assertions remain. This is a reachability precaution for variable-height setup guidance, not evidence that a prior runtime failure was caused or fixed by this change.

## Observed recovery accessibility failure and candidate correction

[CI run 38119070005](https://github.com/js051/open-eos-control/actions/runs/38119070005), on head `d6e744d`, passed App 344/344 and UI 28/31. The connection-recovery journey failed at its unchanged five-second wait for the `connection-recovery-message` static text. Its Connect action had passed the enabled/hit-frame checks and synthesized a tap.

The failing test's actual xcresult accessibility hierarchy establishes the cause of that missing identifier: the invalid-address recovery text was present at `(32.0, 537.7)` with size `326.0 × 82.3`, and Connect had changed to “Retry connection.” However, the title, recovery message, retry explanation, and “Edit settings” button all exposed the parent identifier `connection-recovery-guidance`. The message's `connection-recovery-message` and the action's `connection-edit-settings-button` identifiers had been replaced. The invalid address was also present in the camera URL field. This evidence identifies identifier propagation through the recovery stack; it does not support a clipping, disabled-button, or missing-error explanation.

The narrow correction gives the recovery stack explicit `.accessibilityElement(children: .contain)` semantics before assigning its group identifier, matching other app groups that preserve independently addressable children. The existing journey now checks that the launch override reaches the actual URL field, retains the same five-second message deadline and exact message-content assertion, requires a unique message leaf and the separate edit-action child, and saves a textual accessibility hierarchy plus screenshot if the leaf remains missing. No URL default, generic scrolling helper, or unrelated journey is changed by this correction.

The correction has passed source review and `git diff --check` only. Its Swift compilation, App/UI runtime, and new-head CI are **NOT_RUN** at this handoff. The observed failure mechanism is established; successful post-correction behavior still requires the next exact-head acceptance run. The two unrelated generic-navigation UI failures in that run remain separate work.

## Same-run media navigation evidence and bounded candidate

The same `d6e744d` run also failed the existing batch-selection and date/folder UI journeys at their fixed eight-second generic navigation checks (original lines 237 and 729). The helper and media layout were unchanged from the earlier green `df3f243` tree. Original failure PNGs show ordinary large-text media content with the requested controls outside the viewport; final-frame equality alone does not prove that a gesture failed to move content. Full artifact `11695585246` was verified at 258,379,516 bytes and SHA-256 `7734eb44c8d5eae3fe83bea8db2b042f10fa9e98084b39247f1adca4a5e90fa5`.

The candidate resolves a visible scrolling container and reuses only an unambiguous sole container or a verified target ancestor while its frame remains valid, avoiding repeated descendant searches. Ambiguous fallbacks remain uncached so a newly materialized target can resolve to another container. A known target farther than one bounded stopped stroke uses a directional seek; nearby or covered targets retain stopped correction. If that target disappears after seeking, one bounded stopped reverse is permitted as an explicit heuristic, then unresolved geometry fails. It does not assume that a missing target proves overshoot. Initial missing-target discovery, clipping, camera-action gestures, all business assertions, existing sleeps, and call-site deadlines remain unchanged.

Pure policy controls cover both directions, the exact coarse/fine boundary, covered and invalid frames, missing-after-seek exhaustion/reappearance, and the unchanged camera-action behavior. Failure diagnostics retain pre/post target samples from existing reads rather than adding accessibility queries solely for logging. Source checks pass; Swift compilation and all new policy/runtime behavior remain NOT_RUN locally. The next normal exact-head CI must determine whether these candidates resolve the failures; no successful navigation or product root cause is presumed from source review.

## Exact-head runtime result and navigation time budget

[Run 38121823412](https://github.com/js051/open-eos-control/actions/runs/38121823412), head `502c35436de671120461ad21ffa88bec713def14`, passed App 344/344 and UI 33/34. The recovery accessibility journey and batch-selection journey passed. The remaining failure is the date/folder journey's return to `media-folder-filter` at line 838. This run is **failed**, not accepted. The new policy controls passing does not establish that every large-text navigation path succeeds.

The trace proves two successful content movements: folder y changed from -1672.333 to -811 to 40 points. The visible viewport starts at y180.667; the final folder is still above it and requires a 152.667-point stopped correction. The recorded hot path is:

- Helper entry: test time 176.50 seconds; nominal action-admission deadline: 184.50.
- Initial target/container resolution: approximately 0.42 seconds.
- First seek and XCTest idle wait: 176.92–180.00, approximately 3.08 seconds.
- Target/cache/container resolution before the second seek: 180.00–180.77, approximately 0.77 seconds.
- Second seek and idle wait: 180.77–184.09, approximately 3.32 seconds.
- Final target/cache/container resolution reaches 184.50; the next-gesture guard exits around 184.54.

The immediately preceding 151-point stopped correction took approximately 1.40 seconds before the next target read, plus approximately 0.1 seconds for enabled/hittable checks. That is a nearby observed cost, not a guaranteed lower or upper bound. Repeated container rediscovery accounts for approximately 0.65 seconds; retaining the required visibility check would reduce the possible saving to roughly 0.4–0.5 seconds. That saving could admit the final correction in the recorded schedule. It cannot establish completion within eight seconds; applying the adjacent correction cost gives approximately 9.05–9.15 seconds overall even with that saving.

The existing helper's eight-second value is a **new-action admission deadline**, not a hard wall-clock completion guarantee. Synchronous XCTest gestures, their idle waits, subsequent accessibility reads, and failure attachments can return after it. In this failure the helper reported elapsed 8.254 seconds before finishing diagnostics; the trace then has approximately 23 seconds between attachment reporting and the assertion, without enough detail to attribute that interval. There is no helper-local hard cleanup bound, and the existing iOS job does not declare a custom `timeout-minutes`. This candidate does not increase the deadline, change gesture velocity/hold/travel, suppress content, or remove business assertions. A strict wall-clock guarantee would require a separate test-harness contract decision; it is not supplied by this correction.

The source uses exact CGRect equality at both cache gates. Repeated rediscovery and sub-picopoint variation in derived viewport offsets support a rounding-error hypothesis, but the old trace did not record the cached/current surface pair or rejection reason. The minimal candidate therefore compares finite geometry with the existing millionth-of-a-point representation tolerance, preserves meaningful movement and ambiguous-owner rejection, and records reuse decisions from already-read values. Its new Swift compilation/runtime result is **NOT_RUN** until the next exact-head CI; the cause and successful correction are not presumed from source review.
