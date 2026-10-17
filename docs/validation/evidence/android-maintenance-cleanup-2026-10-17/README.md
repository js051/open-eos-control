# Android maintenance cleanup runtime evidence

- Baseline: `f9eb13d21a994ab502f02c70a0157129ec56bcfd`; compiler exit 0,
  JUnit exit 1, exactly 4 named runtime assertion failures, 16.524 seconds. The runner confirms
  these expected red counterexamples and exits 0. The first pre-fix run was
  preserved locally; this final rerun uses the same immutable production source
  with the finalized fixture and unique-boundary runner. Three cases prove caller
  cancellation cannot return while execute/body ignores cancel. The fourth never
  cancels the caller: after the unchanged production 15-second strict Stop scope
  invokes `Call.cancel()`, close still fails to return until the body latch is
  released. All four failures were checked against their exact intended assertion
  messages, not merely a nonzero JUnit exit.
- Fixed worktree: compiler/JUnit exit 0; **16 tests pass**, 15.2 seconds.
  Exact production client source SHA-256:
  `954980dc5aefa0c855a1b190ba73e1b560eeaac87c989c001a609c9a8173162b`.
- Unchanged previous owned-Bulb host suite: compiler/JUnit exit 0;
  **59 tests pass**, 15.472 seconds, against the same client source hash.

Every compiler/test JVM used one active processor and a 256 MiB heap. Runs were
sequential, used existing checksum-verified Kotlin/JVM tools, and performed no
network access, downloads, installation, Gradle or native builds. Receipts record
source/extract/fixture/tool hashes and process results; JUnit output is included
as `junit.txt` with trailing blank lines normalized. Published source digests use `source_file` / `sha256`
records instead of a filename-keyed map, preserving every value while avoiding a
secret-scanner false positive on the `BridgeBulbAccess.kt` digest. Raw runner
receipts remain in local evidence; no scan rule or allowlist was changed.
Generated source snapshots, compiler logs and binary test JARs remain separate
local build evidence and are not checked in.

The 16 fixed tests include four parallel real-default 15-second cleanup calls
and unchanged strict production body/UTF-8/JSON validation, plus caller cancellation,
exact saved-owner isolation, late response/body fences, negative-route controls,
original maintenance owner checks, and physical-release/retirement error ordering.

See the [fixture boundary and commands](../../../../scripts/validation/fixtures/android-maintenance-cleanup-host/README.md).
This is an extracted production-host runtime result. It is not whole-client/full
Android compilation, OkHttp socket validation, APK packaging, a CI result,
physical-camera acceptance, or a release.
