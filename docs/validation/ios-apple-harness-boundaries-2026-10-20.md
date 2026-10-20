# iOS harness boundaries after the Apple diagnostic run

Status: source candidate and bounded offline checks completed; native Apple
compilation and App/UI verification for this candidate have not run.
This is synthetic Simulator/test-harness evidence, not physical-camera validation.

## Baseline

The [Apple dev run for `ff44d1c`](https://github.com/js051/open-eos-control/actions/runs/39430641926)
passed its secret/helper gate, Core tests and ordinary App build. App tests reported
838 tests with two assertion failures in one case; UI tests reported 43 tests with
two failing cases. These were test failures, not the workflow's timeout result.
The [Android host run](https://github.com/js051/open-eos-control/actions/runs/39430641788)
passed independently.

The bounded Apple evidence archive was 4,885,421 bytes, SHA-256
`1e40e721a1fca0849af2b9aff7f7af8bca651c54b0c47e4567204fc54c75f240`.
The following findings use its fixed-control geometry/journal and the job log;
they do not require another full xcresult download.

## Findings and scoped repairs

### A disappearing Disconnect is not a failed Disconnect

`testPreviousConnectionWarningStaysSeparateFromNewConnectionStop` failed in its
optional after-tap geometry helper. It checked the Disconnect element's existence,
then separately requested its frame after that element had disappeared. The
failure snapshot already showed the connection screen and previous-camera warning.
Production deliberately clears the current connection and sheet synchronously
before old-session cleanup completes.

Only this transitional observation now uses one public, throwing
[`snapshot()`](https://developer.apple.com/documentation/xcuiautomation/xcuielementsnapshotproviding/snapshot()).
Fixed-ID counts and booleans are read from that snapshot; no live disappearing
element is queried again for its geometry. Hittability is not a snapshot attribute,
so it is omitted. Snapshot retrieval failure logs only a fixed unavailable marker.
The real pre-tap hit-target checks and subsequent connection/warning/Stop assertions
are not caught, skipped or relaxed. No hierarchy, arbitrary labels or error text
are exported. The exporter schema is unchanged.

### Correct a clipped target without accepting clipping

`testRecoverySheetStopAndDisconnectHaveIndependentHitTargets` failed before its
reconnect tap, in the Traditional Chinese/accessibility XXXL/landscape case. The
single Disconnect had retired the connection. The dedicated connection helper
then issued full swipes up, down and up. Its final Connect frame was
`(157, -2.6667, 560, 77.3333)` in a `(0, 0, 874, 285)` scroll viewport.
The full-target assertion correctly rejected the clipped top despite AX hittability.

The helper now uses existing geometry-derived stopped correction for a known
near-edge target, rather than another momentum swipe. Distant seeking, the known
container, eight-second admission deadline and maximum gesture count remain.
There is no additional camera-command tap or fresh wait budget. Synchronous
XCTest operations can outlast an admission deadline; this is not a hard
per-operation wall-clock guarantee.

New pure geometry coverage uses that exact clipped frame, bottom clipping,
fully visible and invalid/oversized targets, and viewport-contained drag endpoints.
The full-target and minimum-44pt predicates remain unchanged. Source and geometry
support a navigation repair; native execution must still establish that the
revised gesture reaches the target. No production layout change is made.

### Separate logical freshness from test scheduling

`testNewerFrameDoesNotStarveSampledScopesButClearsSpatialOverlay` held a background
worker, awaited startup, and polled for 0.8 seconds while a real one-second
freshness/expiry deadline was running. The case took 2.534 seconds. Its logs cannot
distinguish late-completion rejection, expiry after publication, or completion
after the polling budget. An ownership regression is not established.

The monitor now accepts a paired monotonic clock/expiry-wait dependency. Its
default remains `ProcessInfo.processInfo.systemUptime` plus `Task.sleep`.
The one-second age, 0.25-second cadence, original source deadline, physical-work
slot, owner/epoch/LUT checks, cancellation and generation guards are unchanged.
Expiry still captures the monitor weakly. A clock-only seam would be insufficient:
a real expiry sleeper could still clear a result during unrelated test scheduling.

The ownership test now holds logical time while awaiting an explicit sampled
publication. Its original four analysis/token/overlay assertions are unchanged.
It then verifies availability just before the original deadline and rejection
and storage expiry at exactly that deadline, without another frame.
Additional tests cover exact-age/future-source rejection and manual-clock waiter
cancellation before/after registration. Advancing time and cancellation remove
each continuation before resuming it. The existing real-clock expiry test and
lease tests remain.

## Picker scope remains unresolved

The original six real-picker scenarios passed in this run, with admitted uploads
and expected uncertainty/verified outcomes in both languages. The earlier
DocumentManager deallocated-host-proxy crash did not reproduce. This is not a
picker lifecycle repair: the picker, ownership/retirement fast path, result and
receipt rules are unchanged. Diagnostic logging can affect timing. Public
Presenter/Coordinator deinitialization does not prove private proxy/XPC drainage.

## Verification gate

Completed offline checks:

- 52 focused Python source/exporter tests passed.
- All three changed Swift sources parsed in Swift 5 language mode.
- Six extracted pure Swift tests passed: three original lease tests, the new
  connection geometry test and two manual-clock cancellation/deadline tests.
  The exact declarations/test bodies were used with Foundation/XCTest; Linux
  required only a two-field `CGVector` math compatibility value. This is not a
  CoreGraphics/UIKit SDK compatibility claim.
- All 43 original UI test method bodies, including the six-picker-scenario
  method, compared byte-identical against `ff44d1c`. The four original retained
  sample assertions, full-hit predicate, overlay gate and lease were preserved.
- Exact five-file review and `git diff --check` passed. The exporter and workflow
  sources are unchanged.

The first portable compilation could not write its default read-only module
cache. Using an explicit project-scoped cache resolved that host tooling issue
without changing source, permissions or installed software. The complete bounded
gate, including this correction, took under 30 seconds.

These checks do not provide UIKit SDK compilation or native App/UI runtime
proof. In particular, public `XCUIElementSnapshot` integration, the actual
Connect gesture and the controlled-clock App ownership/expiry case still need
native Apple execution. The pure clock tests do not substitute for that App test.

Any later native verification must retain the existing full Apple workflow,
ordinary build and all App/UI assertions. This candidate changes only iOS,
an exporter test and this document; it does not change workflow triggers,
security checks, Android/device validation, or release settings.
The observed Apple App job lasted 47m09s. Its 55-minute job and 45-minute test-step
limits remain unchanged. Supplemental dev checks do not establish PR
`ci-complete`, main acceptance, preview release or physical-camera validation.

Release assessment: `none` for this isolated harness/testability change. It makes
no new user-facing product or distribution claim and does not authorize a release.
