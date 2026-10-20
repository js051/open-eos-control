# Android device environment health guard

## Evidence and purpose

The third supplemental device run, [39399454712](https://github.com/js051/open-eos-control/actions/runs/39399454712),
reported 460 tests on each API. API 34 had 455 passes and five failures.
API 36 had 396 passes and 64 failures: the same five functional failures and
59 cases blocked by a persistent System UI ANR. Retained focus diagnostics
identify `Application Not Responding: com.android.systemui`; six SAF cases
also captured the System UI error dialog. The first retained screenshot was
at approximately 09:28 UTC. This evidence does not identify what initiated
the ANR and does not make those 59 cases passes.

The supplemental workflow now wraps the original, complete
`:app:connectedDebugAndroidTest` command with a bounded environment observer.
It does not change instrumentation cases, selectors, fixtures, pixel
tolerances, simulator requirements, emulator settings, or the shared
instrumentation compilation prerequisite before either KVM operation.
The existing consumed one-shot SHA and first-attempt guard remain unchanged;
this edit does not authorize another device attempt.

## Failure and evidence contract

- Inspect the current focused window before starting the test command and
  periodically while it runs. Only the exact System UI ANR window signal
  qualifies; historical log text and unrelated app ANRs do not.
- On detection, preserve the focus snapshot, observation time in UTC,
  a best-effort screenshot, and bounded last-ANR diagnostics. Diagnostic
  capture failures remain visible and cannot convert the failure to success.
  Each capture child has a five-second deadline and an inherited per-file
  resource limit of 16 MiB plus one overflow-detection byte (or a lower
  existing hard limit). Retained stderr is separately capped at 64 KiB.
  This only constrains the owned capture process; it changes no host
  permissions, persistent settings, or emulator settings.
- Fail nonzero and terminate the owned test command with bounded escalation.
  Never dismiss the dialog, restart System UI, retry tests, or change device
  permissions or settings.
- Keep ordinary test stdout/stderr and existing test-result artifacts.
  Never rewrite JUnit XML, hide previous test failures, manufacture skipped
  cases, or count the remaining unexecuted tests as passes.
- Inability to observe the device is an explicit nonzero infrastructure
  outcome, not evidence that the environment was healthy.

An interrupted Gradle invocation may not have finalized every test XML file.
Existing result files and logs remain uploadable, but a partial run cannot
establish the exact remaining test count without independent runner evidence.
The guard's outcome is separate from test results and must be reported as an
unhealthy or unobservable environment, with incomplete native validation.

## Validation limits

Local fake-process/fake-adb tests exercise the observer and cleanup contracts
without executing adb, Gradle, an emulator, or KVM. Workflow source guards
retain the full test command, artifacts, exact one-shot gate and shared
pre-KVM compilation dependency. These are local automation checks, not proof
that the native color, surface, Compose, SAF, or recovery behavior now passes.
The original native color mismatch and all device-only gates remain pending
a separately approved native run.

The 2026-10-20 offline gate passed 56 checks: 28 health-observer/process tests,
four original workflow/pre-KVM guards, and 24 retained RTP source guards.
Execution took 0.166 seconds under a 768 MiB address-space limit and two CPU
cores. Code hashes were unchanged during the gate. Independent source review
preceded execution; this result is not full instrumentation compilation or
native acceptance.
