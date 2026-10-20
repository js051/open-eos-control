# iOS real-picker lifecycle diagnostic candidate

Status: diagnostic, not a lifecycle repair. The bounded offline gate below has
passed. Native UIKit compilation and execution remain pending at this checkpoint.

## Why the previous failure is not a receipt failure

Apple dev run `39416734400` at published development commit
`1a589b91a14f5ce1f93007f09db821fc5c527728` passed 833 App tests and failed one of
43 UI tests. The final Traditional Chinese **success** scenario of
`testRealPickerSelectionCancelOrLostAckKeepsUncertaintyAcrossReconnect` failed
to observe its result after a real file tap. The preceding lost-ack scenario
completed its reconnect checks.

The original crash has capture time `2026-10-20 12:14:34.863 UTC`, about 0.69
seconds after the file tap. It is SIGABRT/EXC_CRASH on an NSXPC worker, through
DocumentManager's `DOCWeakProxy.forwardingTargetForSelector:`. The matching
unified-log record identifies `_updateRemoteBarButtonFrames:forUUID:` and
“DocumentManager service tried to send a message to a deallocated host proxy”.
No app frame identifies which public owner released that private proxy.
The test's later `app-state=1` is not the basis for this conclusion.

The exact full xcresult artifact and raw crash/trace remain retained separately;
they are not copied into this repository because they contain machine paths and
identifiers. Archive SHA-256:
`6a949f0f7c05df1f3c1a6eac157f87302deded3be2ff017228c6b340dcbe6dae`.

Current source retains the picker and Coordinator through its retirement
completion. However, `presentingViewController == nil` enters a synchronous
completion path, and the existing journal shows all-zero modal flags. That is
not a documented receipt that DocumentManager has drained remote UI messages.
Neither a guessed delay nor merely retaining the weak delegate establishes the
missing private ownership boundary.

## Observation-only diagnostic added

- Synthetic opening ticket, relative monotonic milliseconds, exact fixture
  language/scenario, fixed lifecycle stages and Boolean flags only.
- Real picker presentation request/completion, without subclassing or replacing
  `UIDocumentPickerViewController`.
- Presenter disappearance/deinit entry; Coordinator deinit entry.
- Original nil-modal / explicit-dismiss / observed-transition route, transition
  registration and cancellation, UIKit dismissal completion.
- Delegate-returning boundary, original completion begin/end and dismantle.
- SwiftUI sheet clear, content disappearance and onDismiss.
- The existing `startMediaUpload` Boolean return and the existing published
  outcome: not-sent, uncertain or verified.

The diagnostic never changes admission, completion order, retirement decisions,
scope acquisition/release, transport, receipt verification or recovery behavior.
It adds no sleep, retry, private API, swizzle, owner-retention grace period, test skip or
fallback success. Observer closures retain only synthetic values; presentation
observation captures picker/Presenter weakly. Selection equality explicitly
remains id+owner, excluding the diagnostic ticket. Original production presentation
and dismissal calls remain direct when diagnostics are disabled.

`delegate-returning` is recorded inside a defer, before the delegate actually
returns. `*-deinit` means deinit entry, before stored properties finish releasing.
`outcome-verified` means model publication, not a visible UI assertion.
The picker/private-proxy deallocation point is deliberately unobserved: an app
owner's deinit entry does not prove when UIKit releases an internal object.
The sheet onDismiss ticket is for the serial synthetic journey, not an assertion
about overlapping independent presentations. No stage claims XPC drainage.

For `flag`, presentation-completed means the Presenter still names that picker;
transition-registered means registration succeeded; transition-completed means
cancelled; upload-returned means startMediaUpload returned true. Other stages
leave it false. The three modal booleans are sampled only at controller trace
points; their false defaults at other stages are not modal observations.

## Privacy and bounds

Both `DEBUG` and `OEC_REQUIRE_SIMULATOR_E2E`, an allowed fixture scenario, and an
allowed explicit test language are required to create a ticket. Ordinary builds
produce disabled tickets and do not read/write the journal.

Tickets are synthetic increasing ordinals 1–9999, not owner UUIDs, process IDs,
device IDs or filenames. Relative time is clamped to 0–999999 ms. The writer and
exporter allow only a closed schema; no arbitrary string, URL or selected-file
path can enter a record. Legacy fixed-schema records remain readable.

The same single cache file is used. Writes are serialized and atomic, with
regular-file/symlink/schema checks. The rolling journal retains only its newest
records, within **both** 128 lines and 16 KiB; older scenarios may roll out.
The exporter retains its existing no-follow/nonblocking open, byte/file caps,
exclusive destination creation and independent-warning behavior. Failed
diagnostics cannot turn a failed test into a pass.

Logging and extra completion observations can perturb timing. This is not a
claim of zero scheduling effect. A green diagnostic run
therefore means **the crash was not reproduced in that run**, not a proven fix.

## Unchanged acceptance and workflow

The complete original UI source is byte-identical to the baseline. Its SHA-256:
`17d74208dcaa6a7819ec21dc238ce3579630fe933357d653a79662573e705119`.
The six scenarios still use the real picker, unchanged result visibility,
uncertainty, same-name-list rejection and reconnect assertions. None is replaced
by journal assertions.

`.github/workflows/apple-dev.yml` is unchanged. It has no workflow_dispatch or
supported single-test input. Its SHA-256:
`0f0d1a2c43d8b94996ac233c6716ddfa1fb2b28cd09b4796224cf671e61ceb20`.
No new workflow, branch or settings permission is proposed.

The commissioned source changes and ordinary dev publication already have
existing authorization; this preparation step does not execute them. The exact
offline/CI scope and cost must be notified before execution and checked against
the current quota authorization. No additional workflow/branch permission is
needed for the following existing route, and no new KVM permission is implied.

The minimal existing route is **one non-force dev push** of the reviewed batch,
retaining the complete
Apple secret scan/helpers/contract checks, Core tests, normal App build, full App
and 43-test UI suites, isolated picker plist verification and evidence export.
The five new journal App tests join the full App suite. No repeated execution is
part of this plan.

The previous ios-app job took 39m03s, with 34m18s in App/UI testing; Core took
3m24s. Budget approximately **35–45 minutes plus queue**, with the existing
55-minute job and 45-minute test-step limits unchanged. Combining already
reviewed Android changes also triggers existing Android host validation.
These development workflows are supplemental: green is not PR `ci-complete`,
main acceptance, a release, or physical-camera validation.

This file set does not match device-dev, desktop-dev or windows-dev push paths.
The previous two KVM operations are consumed and authorize no more. A fresh
check immediately before publication must verify the exact dev SHA and absence
of an open PR with dev as head. An open PR could independently trigger the
required pull-request workflow and Android UI/KVM jobs. If that condition
changes, stop publication and reassess authorization; do not disable checks.
Do not create a PR as a side effect of this batch. With those conditions met,
the proposed Apple + Android host execution uses **zero device/KVM operations**.

## Pre-publication gate

One bounded offline review/test batch, estimated 2–5 minutes on the existing
cloud toolchain, without installations or native UI:

1. Review exact candidate diff, weak capture/retention behavior, journal
   allowlists and byte caps; verify original UI/workflow hash equality.
2. Run `scripts.ci.tests.test_picker_journal_evidence` and
   `scripts.validation.tests.test_ios_media_upload_source`, then the existing
   CI helper suite as required by integration. New exporter tests cover every
   stage, numeric bounds, private/unknown fields and allowlist parity.
3. Parse changed Swift source with and without the diagnostic compilation flag;
   type-check the Foundation-only journal with the existing Swift toolchain.
   These checks do not substitute for UIKit/Xcode compilation.
4. Run the existing mandated pre-push/secret checks on the final combined batch.

After the single notified native run, retain its immutable SHA/result bundle.
Compare the last ticket's appearance, nil-modal retirement, delegate/completion,
sheet, deinit and outcome order against any crash. If the same private proxy
exception recurs, use that ordering to propose the smallest public ownership
repair. If it does not recur, report non-reproduction and unresolved causality,
not success of a lifecycle fix and not a reason to rerun until green.

### Offline result, 2026-10-20

- 20 focused journal-export/privacy and upload-source checks passed.
- The existing 116-test CI helper suite passed. Counts overlap with the focused
  checks and must not be summed as unique coverage.
- Ordinary and diagnostic Swift source parsing passed with the existing Swift
  6.4.1 toolchain. The Foundation-only production journal type-checked in Swift 5
  compatibility mode with the diagnostic flags.
- All five authored journal XCTest methods passed on Linux against the unchanged
  production helper. Only the App module import was omitted for that standalone
  runner; no app/UIKit behavior was simulated or established.
- An isolated `@MainActor NSObject` deinit using the exact immutable Sendable
  ticket and journal call type-checked. This checks the language boundary, not
  UIKit SDK annotations or native owner lifetime.
- The initial whitespace check found one extra EOF blank line. It was removed;
  final focused checks and `git diff --check` passed. The initial result remains
  in the gate evidence, not discarded.

No network, installation, Android emulator/KVM or Apple simulator execution was
part of this offline batch. Full App/UI native acceptance is still a separate
result, and the previous crash remains unresolved.

## Public API references

- [UIKit dismiss completion](https://developer.apple.com/documentation/uikit/uiviewcontroller/dismiss(animated:completion:))
  follows the presented controller's viewDidDisappear; it does not promise
  remote service queue drainage.
- [Document picker delegate](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller/delegate)
  is weak. The existing Presenter already retains its Coordinator.
- [Adaptive presentation did-dismiss](https://developer.apple.com/documentation/uikit/uiadaptivepresentationcontrollerdelegate/presentationcontrollerdiddismiss(_:))
  is not delivered for programmatic dismissal, so it cannot replace the
  programmatic receipt.
