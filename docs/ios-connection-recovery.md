# iOS first connection and recovery

This change implements the iOS connection-guidance group on the existing CCAPI and Desktop Bridge transports. It does not add a transport, endpoint, permission, camera capability, or automatic reconnect policy.

## Product behavior

- The connection form provides English and Traditional Chinese setup help. Direct-camera help explains that CCAPI must be enabled on a supported camera and that Canon Camera Connect pairing alone does not enable CCAPI. The supplied URL must match the camera's CCAPI address and port.
- Desktop Bridge help explains the USB-to-computer route, the computer's network URL and bearer token, camera scanning/selection, and the existing lack of direct iOS USB/PTP control.
- Failed connection or scan attempts present localized guidance alongside the fields. The edit action focuses the relevant field. Retry uses the existing explicit Connect or Scan action; changing a field, opening help, or displaying an error does not initiate a connection, scan, or shooting command. Existing lifecycle cleanup remains separate.
- Camera username/password fields automatically open only for a proven direct-camera HTTP 401/403 failure. Bridge HTTP 401/403 points to the Bridge bearer token. A Bridge 502 containing camera-authentication text remains a generic service error and does not open camera credential fields or claim the Bridge token was rejected.
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

## Verification state for this local batch

Prepared against draft base `df3f24329f5937cf19f7fa3ec302e1d173b8e647` on 2026-10-11. Source review, whitespace checks, and localization key/format checks are local validation only.

- Swift Core runtime tests: **NOT_RUN**
- iOS App unit tests: **NOT_RUN**
- iOS Simulator UI journey/build: **NOT_RUN**
- New-head CI: **NOT_RUN**
- Physical iPhone/iPad and EOS validation: **NOT_RUN**

The local environment has no Swift/Xcode compiler. No toolchain install, build, CI trigger, push, merge, or release is part of this batch. Prior Core 301 / App 332 / UI 31 results apply only to the old exact base, not these edits. Development implementation is ready for the consolidated automated App acceptance stage; physical-camera compatibility and reliability are not claimed.

Source review also replaced the existing large-text date-filter journey’s initial Offline Preview existence-only wait with the existing bounded connection-scroll helper. The same eight-second budget and all media assertions remain. This is a reachability precaution for variable-height setup guidance, not evidence that a prior runtime failure was caused or fixed by this change.
