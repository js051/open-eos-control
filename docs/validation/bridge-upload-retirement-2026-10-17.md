# Bridge upload admission during session retirement

## Scope and reproduced failure

This is a local correction to the existing G10 / #78 Bridge upload contract, based on integration commit `4163aad87ca8`. No transport or upload capability is added.

The real ASGI upload route captures a session before asynchronously staging the request body. Previously, it invoked that captured provider after staging without entering the session's camera-mutation admission guard. A concurrent Disconnect could remove the session registration and retire its guard while waiting for an already-admitted control. Completing the old body in that interval still returned HTTP 201 and issued one synthetic gPhoto2 `--upload-file` command, with the session unregistered but provider cleanup still pending.

This is specifically dispatch after logical retirement while cleanup is draining. It is **not** evidence that a normally closed gPhoto2 provider writes to a replacement session: the existing provider lock and closed check rejected that control case, with zero writes. HTTP request cancellation also does not substitute for session retirement; the original upload HTTP connection can remain open while another request disconnects the session.

## Correction

- Keep local request-body staging outside camera-operation admission.
- In the existing synchronous upload worker, enter `SessionManager.camera_mutation` and hold its existing retirement guard through provider completion, including verification and cleanup.
- Check exact provider-object identity inside admission, including a reused textual session ID.
- Recheck HTTP cancellation after admission, before invoking the provider. A cancelled queued worker cannot dispatch a later camera write.
- Retain the existing cancellation event, worker drain, staged-file lifetime, and successful-process readback behavior. An already-admitted upload finishes before session close; this does not claim that cancellation rolls back a committed camera write.

## Validation

The new `bridge/tests/test_upload_session_retirement.py` uses the real ASGI endpoints, SessionManager and GPhoto2Session, with only the existing synthetic FakeRunner replacing camera I/O. It covers eight distinct cases:

1. Body finishes after registration is retired while an earlier control still holds admission.
2. Body finishes after normal close.
3. Body finishes after a replacement session is registered.
4. Replacement reuses the original textual session ID.
5. HTTP task cancellation while upload waits for admission: no provider upload call, no camera write, and payload retained until the worker drains.
6. Upload already obtained the original guard and queued before retirement: the guard rechecks retirement after acquisition and rejects dispatch.
7. Already-admitted, committed upload finishes readback and returns its exact receipt before close, with payload available through provider completion.
8. The same committed-upload boundary with HTTP task cancellation: worker drain and readback still finish before close, payload stays available, and only the original write occurs.

The first case fails on the unchanged baseline with HTTP 201 instead of retirement rejection. After the correction, all eight new cases pass. The focused regression passes **217 distinct tests**, including those eight, covering existing upload validation/outcomes, API, gPhoto2, Bulb admission/ownership and session shutdown tests. An independent final run of the eight new cases also passes; those repeated cases are not added to 217. One existing Starlette TestClient deprecation warning remains. Results and source hashes are retained in [the validation evidence](evidence/bridge-upload-retirement-20261017/upload-retirement-source-sha256.txt); this is synthetic software evidence, not physical-camera validation.

Ruff and `git diff --check` pass. The repository's normal staged secret-scan hook is required for the local commit; no hook, safety check or acceptance gate is bypassed.

## Release assessment

- Local source baseline: `4163aad87ca8`, declared product version `0.13.0`; latest remote release was not rechecked in this local-only task.
- Proposed impact: **patch**, a backward-compatible correction preventing a newly staged upload from dispatching after session retirement.
- No version change, push, CI dispatch, merge, tag, release or installation is included.
- Exact-SHA CI and normal integration/release acceptance remain required before the corresponding delivery states can be claimed.
- Camera/firmware compatibility and physical disconnect/cancellation reliability remain unvalidated here.

Integration review: exact app/test tree equals source commit `085be20307477b041b7fbbe9c04ada72a28bc267`; the dispatch guard, queued-retirement check, cancellation and committed-write drain were independently inspected before integration.
