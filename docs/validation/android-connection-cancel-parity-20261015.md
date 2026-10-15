# Android connecting cancellation and retirement

Base: `5bbf2d565232e56e3e2e9e0038ce6ae5cbca5f03`. This candidate is local and uncommitted. No push, CI, Android native build, emulator, USB device or physical-camera acceptance was performed for this cohort.

## Behavior

- Existing Cancel and accepted connection-setting edits revoke the ViewModel attempt through its existing cancellation/generation/join path. Editing never starts a new attempt.
- A retry remains an explicit action and waits for the old repository owner to retire. The connection form says when that teardown is still pending; settings remain editable.
- Bridge creation retains a returned session ID before a coroutine dispatcher can discard it on cancellation. Cleanup uses the original client endpoint, original authentication and exact retained ID. IDs are validated as 1–128 URL-safe token characters.
- The protected Bridge creation call is bounded to 15 seconds, preserving an existing shorter transport timeout. It uses the existing non-retrying, non-redirecting mutation transport and a 64 KiB response limit. Abandoned setup cleanup inherits the existing five-second recovery budget, preserving a shorter timeout. Ordinary close keeps its previous cancellation/deadline policy.
- Only a complete empty HTTP 204 is a Bridge session-retirement acknowledgement. Other success statuses do not establish retirement.
- Unknown creation outcomes or failed retirement retain an independent warning and block repository connection admission, including an already queued retry. They cannot overwrite the current attempt's error or erase its busy state. Settings remain editable. Bridge discovery is blocked during pending or unknown retirement because local engine discovery can touch the same USB owner; rejected scans are never queued. The operator must close/check the previous camera or Bridge session and restart the app; dismissing an unrelated message does not grant connection authority.
- Existing shutter/autofocus safety exceptions retain their original types. Late creation and DELETE shutter/live-view/recording codes also retain dedicated previous-owner warnings and the connection-admission gate, without granting Stop authority over a new owner.
- USB transport acquisition retains the exact OS handle before cancellation can discard it. Abandoned setup uses strict PTP retirement: an uncertain or successful OPEN_SESSION owns one CLOSE_SESSION attempt; an explicit OPEN rejection never authorizes closing an existing session; only OK or SESSION_NOT_OPEN confirms retirement. Failure is reported even though the local handle is closed. Ordinary established-session shutdown retains its existing best-effort policy.

## Executed host evidence

All tools were existing pinned offline Kotlin/JUnit artifacts. No dependency installation or network tool download was used.

1. Bridge baseline counterexample: exact original initialize/close declarations compile; one ownership assertion fails because cancellation discards the created ID and sends no DELETE.
2. USB baseline counterexample: exact original USB initialize declaration produces two failures (the late opened handle is not closed and cleanup uncertainty is lost).
3. PTP baseline counterexample: complete original PtpProtocol/PtpProperties files compile; two of five assertions fail (no CLOSE_SESSION after cancelled OPEN_SESSION; failed CLOSE ACK swallowed). The baseline test maps the new strict-cleanup API call to the old shutdown API, with all assertion bodies preserved.
4. Final connection harness: 22 JUnit tests, including all 49 shared classification vectors, exercise exact Bridge initialize/close, USB initialize, repository discover/connect/disconnect declarations and the complete ConnectionRetirement policy file. The harness substitutes synthetic HTTP completions, metadata, handles, DTOs and unrelated control shapes. It does not execute the complete Android repository/client stack, JSON loader or ViewModel.
5. Final PTP harness: six JUnit tests execute complete production PtpProtocol/PtpProperties files and the checked-in test unchanged. Synthetic PTP containers replace the device. CameraConnection and unused deletion callbacks are shape-only adapters.
6. Kotlin PSI syntax checks, XML parsing, source-only assertions for existing ViewModel generation/cancel/retry/edit wiring, fixture packaging checks and diff whitespace checks are separate source evidence, not native execution.

The shared rejection fixture is byte-identical to the cohort contract:
SHA-256 `3856273dcab8e6620ad40dd912b4ba2380b3f0650ec6c8dbd3ae70ef739934bf`.
The host classification test uses a hash-bound Python JSON-to-Kotlin constant mapping; it does not validate Android resource packaging at runtime.

## Authored, not executed here

- DesktopBridgeConnectionCancellationTest: real production client/repository with MockWebServer late creation, lost responses, failed retirement, malformed IDs, rejection/safety typing and non-204 DELETE responses.
- UsbPtpCameraBackendTest: cancelled acquisition closes the actual backend's synthetic transport before any PTP command.
- CameraUiStateTest and CameraConnectionRecoveryUiTest: pending teardown presentation, editable settings, explicit retry and persistent uncertain-retirement admission gate.
- ConnectionRejectionContractTest: native JVM JSON resource loader for the shared fixture.

Status for full Android Gradle/JVM integration, Compose UI, emulator, Android OS USB, actual camera and device timing: **NOT_RUN**. Existing six pending KVM snapshots remain unchanged; this host evidence does not replace them.

## Limitations and release assessment

Android OS USB open is not guaranteed to be cooperative or to have a whole-operation deadline. Logical cancellation restores editing immediately, but a retry can remain waiting until that exact OS acquisition returns and owned cleanup finishes. PTP USB calls have transport-level behavior; no physical timing guarantee is inferred from host tests. Local handle closure alone is never represented as a camera ACK.

Release impact: patch for existing connection/cancellation/resource ownership fixes. This is implemented local code with focused host evidence, not PR-ready, main-accepted or released. Native and physical acceptance remain pending.
