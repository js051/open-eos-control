# Bulb ownership across camera controls

## Scope and invariant

An unresolved Bridge owned-Bulb request excludes new camera-control dispatches on the same session, even when the provider's physical release completed and a later status read failed. Provider idle status cannot retire the request duty. Only the owned terminal receipt does that.

The original failure crossed otherwise-correct boundaries:

1. Owned Bulb A starts, then its Stop releases the camera but its status tail raises an error. The Bridge retains A's duty.
2. A new short half-press/AF B previously bypassed that duty through `manager.get(...).autofocus()` or `half_press_shutter()`.
3. B's cleanup fails and retains its own immutable release operation.
4. Retrying A's Stop calls the provider's general recovery routine, which releases B before returning A's terminal receipt.

The repair serializes new control admission and dispatch with the same exact-session execution lock used by owned Bulb. It checks ownership both before waiting and again under that lock, then holds it through provider return or exception. This is not a check-then-call predicate: a control queued before A cannot dispatch after A has been admitted. A control already dispatched finishes before a queued Start, and Stop can still cancel that queued Start without waiting.

## Boundary

The gate covers new half-press, one-shot/held AF, still capture, focus drive/tap, Click WB, recording Start, Live View Start/magnification, settings, capture configuration and maintenance writes. A conflicting request returns `BULB_OWNER_BUSY` before provider calls. Waiting on an unrelated in-flight control is bounded by the existing 15-second admission deadline and returns `BULB_OWNER_PENDING` without later dispatch.

Read-only routes, explicit Stop/recovery routes and session retirement stay available. Retirement fences queued controls and waits for an admitted provider operation before final cleanup. The legacy Bulb wire and all provider method signatures are unchanged. Media/file transfer, media metadata and playback lifecycle are outside this camera-control admission change. The guard does not claim knowledge of independent physical-camera actions or give unowned legacy Stop an owner identity.

Once A's terminal receipt is stored, B may start. Repeating A's Stop returns only `{ownerId, outcome}` with no cached status and no provider I/O, so it cannot release B. The PC separately preserves newer camera observations and warnings when it consumes that receipt; see `desktop-bulb-request-ownership.md`.

## Evidence

`bridge/tests/test_bulb_cross_control_ownership.py` exercises production FastAPI handlers, SessionManager and BulbRequests with synthetic providers, plus production GPhoto2Session/ShortRunner and CcapiSession/FakeCcapiTransport. No physical camera or native libgphoto2 is involved.

The pre-fix run failed 30 of its first 31 cases. The fixed suite has 32 passing cases covering the complete A/B failure sequence for both GPhoto short controls, actual CCAPI held AF admission, 21 control request shapes, queued check/call races, Start cancellation during an admitted control, timeout, retirement, exception cleanup and accessible read/Stop/close controls. The CCAPI protocol trace is exactly Bulb full-press/release followed by AF start/stop; old A's retry adds no command.

A focused 599-case Python regression batch also passed across Bulb, short controls, camera API, focus, shutter AF choice, shooting settings, capture configuration, maintenance, recording and shutdown. Integrated browser and exact-commit acceptance remain separate. This is a patch-level safety repair, not a release or physical-device validation claim.
