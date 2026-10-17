# iOS Bridge-owned Bulb requests

## Scope and evidence status

This is a patch-level safety repair to existing G04/G05 Bridge Bulb controls,
starting from integration source `e340e1ff`. It uses the existing additive Bridge
owned request contract. Direct CCAPI, unowned short-control release recovery,
and held autofocus retain their separate contracts. No protocol fallback,
version bump, remote push, merge, or release is included.

Swift/Xcode are unavailable in this workspace. Native fixtures below are authored
but **unexecuted**. Python source guards and diff checks are supplementary only;
they do not establish Swift compilation, URLSession behavior, iOS lifecycle
execution, app packaging, or physical-camera compatibility.

## Counterexamples addressed

- A cancellation-ignoring Start could block UI Stop, core Stop, and Disconnect
  because all joined Start before releasing. Stop now has its own immutable owner
  and does not join Start. Bridge handles Stop-before-Start ordering.
- A failed owned Stop followed by an ordinary dual-false GET could erase release
  responsibility. Only a matching owned terminal receipt retires that owner.
- Owner A's lost release response could be retried after another caller B produced
  a newer AF/Bulb warning. A cached receipt without status retires only A; it does
  not claim current global false, erase B, or authorize automatic preview resume.
- A successful Start followed by an ordinary active status observation must remain
  known active, rather than manufacturing an unknown label merely because a
  separate global observation was retained.
- A timeout that cancels a transport but then joins it is not a total deadline.
  Complete-response waiting now uses a one-shot continuation gate independent of
  cancellation cooperation.

## Admission, wire, and observations

The App records its command UUID, camera generation, exact client, and admission
Task before suspension. Admission synchronously reserves `DesktopBridgeBulbOwner`
on that client actor, freezing owner UUID, session generation, and both URLs.
Start can dispatch only once for this token; Stop awaits admission only.

The wire is `/bulb/start/owned` and `/bulb/stop/owned` with exactly the saved
`ownerId`. There is no legacy Start fallback or replay on 404/405, timeout, malformed
JSON, cancellation, or uncertain response. Core-owned duty interlocks ordinary
mutations, while exact Stop and retirement remain available.

Owned envelopes reuse the strict maintenance JSON/framing decoder. Start requires
matching owner, `started`, and literal active true / release-unconfirmed false.
Stop accepts matching `released` or `cancelled-before-start`. A supplied status is
allowed only for `released` and must contain both literal false fields. Explicit
null status, partial or ambiguous JSON, wrong owner/outcome, duplicate keys,
trailing bytes, and incomplete/oversize responses do not discharge responsibility.

Concurrent explicit Stops join one saved Stop task. Observation revisions prevent
late Start or Stop data from replacing newer global warnings. A receipt without
status removes only owned responsibility. With no independent observation the core
reports no duty/no warning and unknown activity (`nil`), never fabricated false; a
fresh GET can subsequently establish inactive state. The App keeps its existing status/error,
retains any independently observed recovery duty, and leaves preview paused.
Fresh-release evidence is a separate flag and is invalidated by new active or
unknown observations. The Bridge fallback UI path also distinguishes owned release
from ordinary unowned or held-AF recovery.

## Bounded transport and retirement

Owned requests reuse the existing no-redirect/no-auth-replay maintenance HTTP
transport with 64 KiB response bounds. `BridgeBoundedExchange` adds a 15-second
whole-exchange deadline over request and body, including custom transports that
ignore cancellation. Its worker returns data only. The winning continuation
cancels worker/timer without joining them; a late worker cannot update client state.
This does not claim that cancellation physically stops a camera command.

Background handling captures the original App owner and requests its Stop without
closing the connection. Disconnect fences the UI immediately. Core close captures
the original owned token, attempts bounded Stop, and then attempts bounded original
session DELETE even if Stop failed. Neither joins the old owned Start. A failed Stop
remains a previous-connection warning even if retirement succeeds; successful
registry deletion is never physical-release proof.

After a maintenance lifecycle, close retains strict maintenance retirement and
never falls back to ordinary DELETE. Saved owned Bulb Stop remains owned. Its
new owned/maintenance branches bound individual owned Stop, strict event/Live View
cleanup, and retirement HTTP exchanges. This is not an end-to-end deadline for
every close path: pre-retirement waits in legacy/Live View branches retain their
existing contracts. Unresolved cleanup remains previous-connection evidence.

## Authored native coverage

`DesktopBridgeShutterRecoveryTests`, `URLSessionPendingBulbStopTests`,
`DesktopBridgeClientTests`, `MaintenanceControlTests`, and App
`ShutterReleaseRecoveryTests` cover owned wire identity, pending ignored-cancel
Start, Stop coalescing, malformed receipts, late/recycled-session results,
GET-vs-owned proof, cached receipt warning retention, background, and retirement.
The native tests have not run here. Existing direct/unowned recovery controls remain
in their respective suites. `test_ios_owned_bulb_source.py` checks key source wiring
without substituting for those native tests.

## Release Assessment

- Impact: patch, repairing existing iOS Bridge Bulb safety/retirement behavior.
- Integration baseline: `34669de2`; product source baseline `e340e1ff`; repository
  version `0.13.0`. The latest remote release was not queried for this local task.
- Local supplementary result: 88 iOS source guards passed; diff checks passed.
- Native Swift/Xcode compilation and tests, iOS packaging, and device/camera
  acceptance remain unexecuted. Authored coverage includes cancelled maintenance
  close and the real 15-second retirement deadline after the owner is already
  retired; neither test was run here.
- No product version change, push, merge, prerelease, or physical compatibility
  claim is included. Exact-SHA CI and release decisions remain separate.
