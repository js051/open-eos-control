# Controlled libgphoto2 progressive listing

## Scope and delivery state

Implemented locally on the PC listing candidate; no push, CI run, merge, version
change, release, dependency installation, or physical-camera operation was used.
The change adds the existing optional NDJSON provider to libgphoto2. It leaves
ordinary JSON responses and strict inventory's CCAPI-only gate intact.

Proposed release impact: patch, a backward-compatible repair of interrupted and
slow USB browsing. No release readiness or latest-release baseline is asserted
by this local verification document.

## Design evidence

- The same `GPhoto2Session` backend consumes one real `SubprocessGPhotoRunner`
  command. The fixture is a separate Python process pretending to be a CLI; it
  never opens USB, camera, credentials, or a network connection.
- A nonblocking stdout/stderr pump avoids buffered full-read waits and blocking
  reader-thread cleanup. All complete lines have a byte bound, stdout has a
  cumulative bound, stderr retains only a bounded tail, and item identities have
  the shared 100,000-item bound. Snapshot serialization retains existing limits.
- The session has an exclusive cancel-aware lock wait, checks cancellation on
  parsed rows and callbacks, and publishes full replacement snapshots. A
  provider-scoped cooperative deadline check also interrupts a saturated progress
  queue without changing other engines. The CLI must actually emit its data; its
  own buffering is not changed.
- Native POSIX owns a new process group and reaps the direct child. A fixture
  whose leader exits while a SIGTERM-ignoring descendant retains both pipes
  proves timeout does not hang on inherited pipe readers. The child is killed;
  the fixture permits a briefly reparented zombie but never an executing child.
- Windows uses PeekNamedPipe before reads. Abnormal Windows-native and WSL
  cleanup fences the entire runner before process cleanup, including existing
  sessions, new sessions, legacy JSON, other command/stream entry points, and
  health diagnostics. The fence requires checking the camera and restarting the
  Bridge. Linux WSL-mode tests establish fence logic only, not Windows API or
  remote Linux process termination.
- Success requires zero process exit and drained pipes plus complete declared
  folder counts. A complete scope is not an atomic card snapshot or strict
  foreground-import inventory. On cancellation/failure no partial cache commits,
  no complete terminal appears, and no automatic fallback/retry occurs.

## Focused automated verification

2026-10-16 native Linux result: **163 passed**, including 44 dedicated gphoto
listing cases and the existing gphoto2/NDJSON/API regressions. Ruff and
`git diff --check` passed. One existing Starlette TestClient deprecation warning
was emitted; no dependency was installed or changed.

Run from the repository root with the already available development environment:

```sh
PYTHONPATH=bridge python -m pytest \
  bridge/tests/test_gphoto_listing.py \
  bridge/tests/test_gphoto2.py \
  bridge/tests/test_media_listing.py \
  bridge/tests/test_api.py
python -m ruff check bridge/open_eos_bridge/gphoto2.py \
  bridge/open_eos_bridge/gphoto_listing.py bridge/open_eos_bridge/media_listing.py \
  bridge/tests/fakes.py \
  bridge/tests/test_gphoto_listing.py bridge/tests/fixtures/gphoto_listing_peer.py
git diff --check
```

Coverage includes real progress before CLI exit; fragmented UTF-8; recent-61 and
full 1,101-item results; legacy stable ordering and host captures; empty cards;
count mismatch and malformed/duplicate rows; complete stdout EOF before a still
running process; timeout/output/line/item limits; cancellation while waiting on
the lock and after a final callback; graceful termination and kill escalation;
stderr flood; callback failure; saturated progress-queue deadline; direct close;
actual HTTP partial/error/complete, disconnect and exact-session retirement; busy reservation until cleanup returns;
WSL cleanup fencing; legacy JSON and strict-inventory rejection.

Native Linux focused fixtures do not substitute for Windows-native PeekNamedPipe,
WSL lifecycle, real gphoto2 version/output buffering, card-scale performance,
mobile network idle timeouts, or physical-camera verification. Those remain
explicit acceptance follow-ups rather than being implied by local fixture PASS.
