# Android acknowledged capture and bounded review recovery

## Scope and evidence

Baseline: `0a73519aeda1de55d6a31539d4cec8da7af03e74`.

The causal fixture runs the production `CameraViewModel → CameraRepository →
CcapiCameraBackend → CcapiClient` against an independent MockWebServer, using a
controlled Main scheduler and real HTTP I/O. It initializes and caches the server
address on `Dispatchers.IO`; no StrictMode policy is relaxed.

Before the fix, the four initial cases produced exactly one failure:

- An acknowledged simulator shutter POST followed by an independent status GET
  returning 503 caused **zero review reads**. The VM classified the entire
  operation as a capture failure.
- A successful shutter POST/status GET did discover `NEW.JPG`.
- A rejected shutter POST did not start review or claim acknowledgement.
- Native CCAPI with a battery-status 503 still discovered `NEW.JPG`: native
  status fields use optional reads, so this fixture **does not reproduce the
  simulator failure in the native status path**. This optional-read behavior is
  unchanged. A simulated failure is not evidence about physical Canon behavior.

The raw causal run is retained locally in `.codex/evidence/capture-review-red.log`
and `capture-review-red.xml`. Earlier fixture setup failures were corrected
before that evidence was recorded and are not counted as product failures.

## Recovery contract

`CaptureStatusReadbackException` represents only the separate CCAPI status read
following a returned command acknowledgement and, for manual shutter operation,
a successfully completed release. It is created outside the command/release
block. Cancellation remains cancellation. An unconfirmed command or failed
release retains its prior error/safety behavior. Bridge and USB command
implementations and public backend interfaces are unchanged.

The VM begins bounded review after acknowledgement even if that status read
failed. If another operation crosses a successful command response, the VM's
additional status-reconciliation read can fail independently too; that failure
also becomes a status-readback warning rather than a failed shutter command.
Warning publication checks cancellation plus session/connection ownership.

The warning does not prove exposure failure or success. It survives discovery of
media and is cleared by a successful later status snapshot published by manual
Refresh or an event, a successfully reconciled capture status, a new capture
attempt, or session teardown.

An acknowledged capture retains its original previous-item ID and session owner
for bounded review. The ordinary delays remain 250, 750 and 1,500 ms, with an
initial attempt before those retries. Exhaustion leaves the old item available
but marks the new search `NOT_READY`; the shutter is never replayed. Read-only
retry reuses the same previous-item boundary and cannot start while CAPTURE or
MEDIA is busy. A gated production `loadMediaInfo` operation verifies no competing
listing starts during MEDIA, and retry remains available afterward. This guard
only restricts the new manual retry entry; existing capture/MEDIA interlocks are
unchanged. Duplicate retry taps, old-session
responses and older attempts cannot clear or replace a newer attempt. An event
containing only the previous item cannot convert `NOT_READY` into ready.

The review entry shows a pending indicator instead of presenting an old thumbnail
as the new result. Its status dialog distinguishes the acknowledged command,
status-read failure and newly visible media. Retry, open-previous and dismiss
controls share a scroll area for small windows/large text. Finding a different
visible ID is not proof that it belongs to this shutter command; the dialog says
so. Offline preview keeps its existing direct open action.

## Bounded listing

The existing `CAPTURE_REVIEW_REQUEST_ITEMS = 8` and listing algorithm are unchanged.
Eight is a candidate request budget, **not eight HTTP requests** and not a global
card-size-independent request cap. The native fixture advertises 1,000 pages,
returns eight candidates in the first page and verifies nonempty page requests
all stop at page one. It also verifies the actual old/new IDs and exact shutter
counts across retries. The simulator's fetch-all-then-take implementation is not
used as evidence for native pagination bounds. Multiple media kinds, sibling
containers and ordering fallback can legitimately require additional reads.

## Verification

Local checks passed: 58 focused JVM tests (13 new capture-review cases and 45
existing regressions), plus `:app:compileDebugAndroidTestKotlin`. The fixture
teardown joins all cancelled ViewModel children before resetting Main, preventing
an event-poll continuation from leaking into a later test class.

Focused JVM suites:

- `CameraCaptureReviewRecoveryTest`: causal controls, bounded exhaustion/retry,
  repeated taps, newer-capture ownership, reconnect isolation, cancellation
  after acknowledgement, old/new media events, revision-crossed readback
  failure, and warning clearing only after successful status evidence.
- `CcapiControlRecoveryTest`: existing shutter/release safety regressions.
- `CcapiShutterAutofocusTest`: existing AF and manual-release behavior.
- `MediaLibraryTest`: existing media selection and cancellation behavior.

`CameraCaptureReviewUiTest` adds three Compose callback/scroll cases, including
retry disabled while MEDIA is busy and enabled afterward. Two are configuration-driven: the parent composition requests 320×480 English and 480×320 Traditional
Chinese configurations at 2× text, with read-only retry/open callbacks,
no-previous-item access and offline-preview behavior. A Compose Dialog owns a
separate window; those parent `ForcedSize` settings do not establish the actual
dialog viewport. These cases must not be cited as measured small-window layout
or screenshot evidence. Compilation and device execution are separate gates.
This cloud environment has no emulator, so instrumentation execution and actual
dialog-window size/accessibility inspection remain for CI/device validation. No physical camera,
USB device, iOS runtime or Swift build was used for this change.

## Release assessment

- Latest repository release baseline: `v0.10.0`.
- Proposed impact: `patch`, repairing the existing Android capture/review flow.
- No new camera command, transport capability, protocol claim, or automatic
  shutter retry is introduced. No capture budget/AF or download/save behavior is
  changed.
- Delivery state is local implementation only. Integration aggregate checks,
  exact-SHA CI (including instrumentation), review and any authorized publication
  remain separate gates. Remote delivery and exact-head results are recorded in
  the submitting PR; this document does not establish a merge or release.
- Physical-device validation remains pending and is not inferred from fixtures.
