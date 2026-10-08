# Local Android measure/layout writer experiment

Status: **v2 JVM checks passed; the buildSrc runtime-classpath correction awaits real Gradle validation. No Android build or causal fix.**
Base: `d8a514c23c3532846fce0f629da13c56089e3cf7`.
Keep this branch separate from product PR #219 and Android fix PR #220. **Never merge this
recorder into main, a release branch, or a release candidate. No push/PR/remote run is authorized
by this document.** Release HOLD remains until the original crash's product risk is resolved.

## Scope and invariants

`-PeosMeasureWriterProbe=true -PeosMeasureWriterInvocation=<fresh-nonce>` adds two otherwise excluded source directories and registers the
AGP 8.6.1 ASM visitor for **debug variants only**, `InstrumentationScope.ALL`. Default/false builds
retain the original runner and exclude both diagnostic source directories. Release variants never
register the transform or include the recorder. Because Development Preview uses a signed DEBUG
APK, configuration also rejects `probe=true && developmentSigningEnabled` before a candidate can
be built; it neither reads nor logs signing values beyond the existing build configuration. Normal/off
signing is unchanged. A fresh external invocation nonce is required at configuration and runtime;
reuse for a second launch is forbidden. The diagnostic branch's existing CI wrapper now prepares
that nonce and passes the two explicit properties; its source-only implementation still awaits
owner-reviewed validation and authorization for any remote execution. No app ID/label, dependency version, permission,
workflow, required gate, pin, clock, dispatching policy, or test assertion is changed.

The current 356-test matrix and every existing Android test source are byte-for-byte unchanged.
The extra control case remains in its existing position after the original failing case; its
already-hidden IME condition remains inconclusive. Do not filter down to the failing test, shard,
or use orchestrator: retain the original same-process prefix and operations/assertions. The
original e3f84fde/run37703284681 XML contains 100 cases, ending with
`CameraMediaDateFilterJourneyTest.filteringAnActiveDownloadDoesNotChangeItsOwnerOrOriginalBytes`.
The evidence verifier requires exactly that first-100 class/method sequence. XML order alone does
not establish process continuity: also retain the unfiltered command and instrumentation PID/log.

## Hooks and evidence semantics

The pinned Compose 1.9.3 class file contains:

- 3 public measurement entries: `measureAndLayout(Function0)`, `measureOnly()`, and the mangled
  single-node `measureAndLayout-0kLqBqw(LayoutNode,long)`.
- 17 direct `duringMeasureLayout` PUTFIELD instructions, including the generated setter and all
  normal/handled/rethrow false resets. Four belong to the unused private inline template. The
  original candidate DEX retained 13 writes; this class-file/DEX distinction is intentional.
- 4 copies of the failing re-entry guard, including the private inline template.

The visitor fails the build if any of these counts or its class-initializer stamp differ. It does
not merely wrap the unused generated setter. Every original PUTFIELD remains in place. The
observer reads `root` and the real flag within the owning class, with no reflective layout access.
All audited receivers are `this` or an alias of `this`.

Each direct write has PRE and POST records. PRE is an extra read before the original write, plus
its intended value. POST means that original write returned and includes a **later** read that may
already differ due to another writer. Neither record is an atomic old/new transition or evidence
of a cross-thread total order. A guard record is emitted on the original failing branch before its
unchanged throw helper; its extra flag read can likewise differ from the value the guard tested.

Each thread owns a 128-event ring; at most 32 thread buffers are registered. Ordinary ENTRY,
PRE, POST and explicit EXIT records contain only compact observations/references: thread ID/main
status, per-thread sequence, System.nanoTime, phase, flag values, delegate/root and a constant
method/write-site string. **No ordinary hook captures a stack, formats a report, logs, writes a
file, starts a thread, waits, or dispatches work.** No raw user/request state is read.

At most three rare caller stacks (up to 12 frames, formatted only during export) are pinned:

- FIRST_OFF_MAIN_ENTRY proves a non-main call occurred. It does not prove that the call wrote.
- FIRST_OFF_MAIN_WRITE begins at the first non-main PRE-true, before its original write. A separate
  POST-true reference proves that write completed; PRE alone only proves an attempt.
- FIRST_GUARD_FAILURE pins the original failing branch and a bounded memory-only snapshot of
  existing rings. No file/log I/O runs before the original guard throws.

The first call/writer records retain their actual POST-true, POST-false and explicit-exit anchors
separately from the rotating rings. A completion-tail reference snapshot is made only **after** the
original false PUTFIELD or an observed-false explicit exit. Thus snapshot/report I/O cannot prolong
that writer's active true interval. A call that returns without writing remains call-only evidence.
Exit hooks are added before existing RETURN/IRETURN/ATHROW instructions; implicit throws from
called methods are not falsely reported as explicit exits. Every existing instruction and exception
table is retained. Original finally false writes remain observable even if an implicit throw follows.

A per-thread latest-entry sequence prevents a later invocation from filling an earlier call's
completion record. This is deliberately conservative: a nested entry can prevent pairing an outer
reset/exit, which must then remain incomplete. It is not a reconstructed atomic call stack. The
unpaired raw writes remain in the ring. Setter attempts advance this token independently.

Exact reference-based dN identities are stable within one exported report (including all pinned
histories/tails); hN identity hashes are stable across reports but are not globally unique. Retained
ranges and GAP records disclose loss/concurrent snapshots. There is no global snapshot barrier,
layout lock or cross-thread total order. Recorder errors/capacity exhaustion invalidate proof.

## Retained output and crash path

The runner uses the already declared AndroidX test runner/monitor APIs, with no new dependency:
`PlatformTestStorageRegistry.getInstance().openOutputFile(...)`. Cached runner 1.6.2 registers
FileTestStorage during onCreate when useTestStorageService is false. Cached monitor 1.7.2 routes
FileTestStorage through TestDirCalculator, whose calculateOutputDir uses the
`additionalTestOutputDir` instrumentation argument directly. The probe requires that argument;
it marks proof invalid rather than trusting a default directory the collector may not retain.

Cached AGP 8.6.1 connects the same on-device output path to both the instrumentation argument and
its additional-output collection plugin. Both existing API workflows upload
`connected_android_test_additional_output` with `if: always()`. The verified d8a514c API34/API36
ZIPs each already contain nine screenshots there. This establishes the existing collection route;
it does not claim that the new probe files have yet been collected on Android.

No lifecycle logcat timing assumption is needed. Before tests, the runner hashes only its own
`targetContext.applicationInfo.sourceDir` and instrumentation `context.applicationInfo.sourceDir`.
It rejects inaccessible/missing inputs, identical paths and unexpected split APK arrays. This is
one startup read of the two project APKs, not a package scan or hot-path hashing.

The runner writes invocation/session/PID-specific names and commits these report kinds:

- `eos-measure-writer-<invocation>-<session>-<pid>-start.txt`: EXPECTED, collector route, APK hashes
- `...-finish.txt`: full retained evidence and proof at normal runner finish
- `...-fatal.txt`: full retained evidence at the existing onException callback

Publication is fail-closed: exclusive-create a same-directory `.pending` name, write/flush/close
through the verified FileTestStorage instance, finish fallback logging, and atomically move to the
accepted `.txt` name as the last operation. A close-after-complete-bytes or logging failure cannot
publish a final. Unsupported URI/provider, name collision or atomic-move failure leaves unaccepted
pending evidence; no retries or cleanup overwrite another invocation's valid/pending artifacts.
Session-specific names and a fresh external invocation avoid competing owners of the same final
name. This is not a general atomic no-clobber guarantee: Java ATOMIC_MOVE does not portably
provide no-replace semantics, and an unrelated actor creating the exact same target between the
existence check and move is outside the probe-owned unique-name assumption. Pre-existing
collisions are rejected and ordinary probe writers use distinct invocation/session/PID names.
No broad locking or cleanup is introduced. No fallible logging, flush, close or cleanup follows
a successful promotion inside save.

The cached MonitoringInstrumentation uncaught handler calls virtual onException(object, throwable)
after the real Java throw, then invokes the prior uncaught handler with that same Throwable. The
probe overrides only that existing callback, exports once, and forwards unchanged arguments to
super, preserving its return/throw behavior. It never installs/replaces a handler. Existing
DateFilterFailureDiagnostics also forwards to that chain in its finally block.

The normal result stream always receives proof, including successful runs. For failure-wrapper
fallback, exports use the already retained `EOSDateFilterDiag` tag with an `EOSMeasureWriter`
prefix. Export is only at lifecycle/fatal boundaries; no layout hook reaches the sink. Storage or
export failure marks proof invalid and appends the standard instrumentation shortMsg failure
without dropping prior failure text or replacing the original Throwable.

The evidence verifier requires an independently prepared pre-run host manifest containing the fresh
invocation, CI and checkout identities, exact command and scoped source snapshot hash. A separate
post-run receipt adds the app/test APK hashes and seals the unchanged pre-run bytes. It rehashes the supplied external artifacts,
then requires the current invocation's committed start plus fatal/finish files, full terminators,
filename/header session/PID agreement and matching runtime APK hashes. An old complete invocation
cannot substitute for a failed new one. Pending output for the current invocation invalidates the
experiment. The scoped source snapshot is an explicit host build/provenance assertion, not proof that a
hash by itself establishes which source produced an APK or the full checkout. Keep exact build logs/commands alongside it.
Per-test logcat or a nonce discovered only from the report cannot validate freshness/build identity.
A JVM/process kill that bypasses onException, storage failure, collector failure or truncated output
remains **missing evidence**, never a green result. Crash-path collection and full new-file retention
still need the explicitly approved Android validation run.

## Observer effects and limits

Ordinary hooks still allocate compact events and perform extra reads/calls/volatile publications;
startup APK hashing adds I/O/CPU before tests, and bounded retained roots can change GC lifetime. The three rare stack captures and first-guard
reference snapshot can perturb timing. Completion snapshots occur after a false write but before
later original instructions, and can still perturb subsequent work. Export/format/file/log I/O is
moved to run finish or **after the original fatal throwable**; fatal export delays forwarding to the
existing handler. These effects are explicit and cannot be treated as zero overhead.

Snapshots are per-thread observations; extra flag reads can race with writers, and a later flag read
need not equal the preceding write's intended value. No hook synchronizes Compose layout state.
A pending writer at fatal export is labelled incomplete; no wait is added to make it look complete.
An instrumented green run cannot clear release HOLD or prove a causal fix. VM resource failures and
unretained history remain limits. Static completeness does not prove all reset paths ran on Android.

## Small-check plan and current status

The revised v2 checks passed on patch `466f178c3c1d7320ec4959bf1914d1fca3cbecf6f75818790c2d0a13da8ca6c4`
at 2026-10-08 11:41:49–11:41:54 UTC (exit 0). The exact receipt is under
`.codex/validation/measure-writer-probe/v2-atomic-checks/validation-receipt.json`.
The later buildSrc scope change does not alter those Java/fixture sources. Real Gradle configuration,
opt-in APK packaging and actual transformed-class checks have since passed as recorded below.
V1 results remain separately archived.
To repeat the bounded source/JVM checks only in an approved window, run:

```bash
source /workspace/scratch/2e9db061c9e9/open-eos-control/.codex/toolchain/android-20261008/env.sh
timeout 90s bash scripts/diagnostics/measure-writer-probe/run-local-checks.sh \
  /tmp/eos-layout-readonly/ui-android-1.9.3-classes.jar
```

No Gradle/download/emulator is invoked by that script. JVMs use two active CPUs. The updated checks
cover the actual-class 3-entry/17-write/4-guard transform plus explicit exits; exact retained original
instructions/branches/handlers; synthetic reset/Throwable behavior; zero ordinary hook logging;
three rare stack captures even after 1,000 later calls; pinned completion retention after ring
rotation; and bounded-buffer overflow invalidation. Runner spies check normal/crash stored reports,
unchanged super arguments/return/Throwable/handler, normal stream proof, rejected split/missing APKs,
and storage-failure rejection. Commit tests cover interrupted writes, flush/close after complete
bytes, logging and promotion failures, atomic success and preservation of older artifacts.
Python tests cover pending/truncated/stale/mismatched-session or APK artifacts, external source/APK
hash mismatch and original first-100 order.

Source/bytecode route audit: `.codex/validation/measure-writer-probe/output-route-bytecode.txt`.
Existing artifact placement: `.codex/validation/measure-writer-probe/existing-output-route.json`.
No ART/device execution has run; no local KVM is available. Actual AGP/D8 packaging was verified:

- Opt-in and ordinary/off configuration both exited 0 (30s and 17s) after resolving the declared
  API runtime dependency. Earlier missing-nonce, missing-class and offline-cache failures remain
  archived; no versions were changed or dependency exclusions added.
- Opt-in debug and test APK assembly exited 0 in 4m19s with two CPUs, one worker and in-process
  Kotlin. Build source patch: `14d98bdbb22221eeb78b6cd9e4a923a7743f6218832e3529691cedd4bdf0f2fb`.
- App SHA-256: `cc15edec85a9d24ec33639c8002c5286c3cc9145fc2cd1b64b67f34120f33ae3`.
  Test APK SHA-256: `4f5abda3ed246de2a9575dee3914c41519e894678756fb45709df6d1aa774edf`.
- The actual AGP-produced class (SHA-256 `35618a45ac0c1020d2f5ca56a9d07ae534a8b7acff3e875f1711aadb0a679f53`)
  passed BasicVerifier. Removing only the known observer instructions yields the exact original
  instructions, branch targets and exception tables: 3 entries, 17 PRE/POST pairs, 4 guards, 10 exits.
- APK DEX retains 17 writes, including four in the uncalled private inline template. Its 30 static
  writer-call instructions account for four shared POST tails; every original write path retains
  PRE/write/POST. No external direct field writer or setter/private-template caller was found.
- There is one app recorder definition and one test runner definition. The test APK merely refers
  to the recorder; it does not define a second recorder or Compose delegate. Its manifest selects
  the diagnostic runner. These facts are structural, not evidence of Android execution.

Receipts, raw DEX inspection and preserved APKs are under
`.codex/validation/measure-writer-probe/apk-on-v2/`. The later wrapper/host-packaging changes do not
alter the Android runtime/transform sources; their separate Python checks are recorded below.

## BuildSrc runtime-classpath correction

The owner's bounded AGP 8.6.1 configuration reached successful buildSrc compilation, then failed
loading `com/android/build/api/instrumentation/AsmClassVisitorFactory` with the probe enabled and
a valid invocation. `compileOnly` made the API available to javac but not buildSrc's exported
runtime classpath. The minimal fix is changing that one dependency scope to:

```kotlin
implementation("com.android.tools.build:gradle-api:8.6.1")
```

This follows [Android's internal build-logic guidance](https://developer.android.com/build/extend-agp#add-the-dependency).
The cached API JAR contains AsmClassVisitorFactory and **no** `META-INF/gradle-plugins` descriptors.
The existing full AGP JAR contains the plugin descriptors, but not a duplicate copy of this API
class. The fix imports only the already pinned 8.6.1 API and its normal transitives (including
ASM 9.6), not the full Android plugin implementation. Application dependencies, plugin versions,
normal/off signing, test bodies and workflows are unchanged.

This avoids introducing a second plugin implementation/descriptor; it does not itself prove the
real build's classloader compatibility. BuildSrc runtime dependency exposure changes the build
classpath, including its existing transitive API dependencies. Actual bounded on/off configuration and transform execution have now passed. Neither exhibited
missing API classes, duplicate-class/interface ClassCastException or plugin-version conflicts.
The initial offline attempt lacked declared kotlin-stdlib 1.9.20; ordinary official-repository
resolution supplied that existing dependency graph. The next checks concern off/release payload
isolation and actual Android execution, not another standalone classloader claim.
No version force, transitive exclusion, app dependency or extra full AGP dependency is added to
hide such a failure. The source/JVM harness alone cannot validate Gradle's classloader topology.

## Required next validation, owner-coordinated window only

Do **not** start these builds until the shared build window is approved. Do not retry failures
blindly. Capture the first failure and inspect it. Use the existing helper, two CPUs, one worker and one
fork where any JVM tests are later approved, explicit outer timeout, and no Gradle daemon/cache
reuse assumptions. No dependency upgrades/installations are part of this experiment.

Representative repeat of the completed opt-in package stage, from `android/` (no emulator/test execution):

```bash
timeout 720s /workspace/scratch/2e9db061c9e9/open-eos-control/.codex/toolchain/android-20261008/run-gradle.sh \
  --offline --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx2048m -XX:ActiveProcessorCount=2' \
  -Pkotlin.compiler.execution.strategy=in-process \
  -PeosMeasureWriterProbe=true -PeosMeasureWriterInvocation=OWNER_SUPPLIED_FRESH_NONCE \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Before any remote run, retain exact source diff/commit, build command, AGP-transformed class,
app/test APK SHA-256s and DEX audit. Verify the app has recorder/manifest and test APK has runner:

```bash
python3 scripts/diagnostics/measure-writer-probe/check-apk-marker.py on-app android/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/diagnostics/measure-writer-probe/check-apk-marker.py on-test android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

These marker checks do not establish DEX completeness. Inspect the resulting DEX: every retained
real flag write must still have its PRE/original write/POST sequence, all three entry hooks and
all retained guard branches and explicit-exit hooks must exist, original handlers/throws must remain, and no other class
may write the flag. Expect 13 writes if D8 drops the unused private template, otherwise 17 with
that template explicitly accounted for. Any other count or missing hook invalidates the build.

Then, in separately approved bounded stages, build debug with the property absent (especially
**after** an on build to detect stale instrumentation), and release both without and with the
flag (without publication signing). Also verify probe=true is rejected with a complete synthetic
publication-signing configuration before any key access; use dummy values, never real credentials.
Use the same timeout/CPU settings and no product publication. For each normal debug,
normal androidTest, and release APK run `check-apk-marker.py off PATH`; verify the default runner
in the off test manifest. Compare off/release APK payloads with a clean exact-base build under
identical build/signing conditions, accounting explicitly for ZIP/signing metadata, rather than
asserting equality from source inspection. The completed isolation results are recorded below.

Remote API34 and API36 execution is later owner-controlled. Keep the complete normal 356-case
matrix and gates, no retries/sleeps/autoAdvance changes or narrowed test lists. Capture
the committed probe output files plus original crash/handler output, connected XML, process continuity, APK hashes and
transform proof. Run the evidence verifier on each collected ZIP and its independently recorded artifacts:

```bash
python3 scripts/diagnostics/measure-writer-probe/verify-run.py \
  --original-zip /workspace/scratch/2e9db061c9e9/eos219-api36.zip \
  --result-zip RESULT.zip --run-manifest RUN-MANIFEST.json \
  --pre-run-manifest PRE-RUN-MANIFEST.json \
  --expected-pre-run-sha256 CURRENT_JOB_PRE_LAUNCH_SHA256 \
  --app-apk CURRENT-APP.apk --test-apk CURRENT-TEST.apk --source-snapshot SOURCE-SNAPSHOT.json
```

PRE-RUN-MANIFEST.json must be independently recorded before launch. RUN-MANIFEST.json is a
post-run receipt, never a claim that APK hashes existed before the command built them. Use a fresh
nonce for every launch, even within one CI run/attempt. Never derive the expected invocation from
discovered runtime proof. Compare the expected pre-run manifest hash/invocation with the current
CI step's pre-launch line, and retain its exact CI run/attempt/job and API/device identity.
The expected SHA-256 must come independently from that intended job's pre-launch log line, not
from the result ZIP. Verify the downloaded artifact's run/attempt/job metadata against the same
intended job. An internally consistent older package cannot replace this external freshness anchor.
No CI activation or source provenance is inferred just from successful report parsing.

The original ZIP is SHA-256 pinned. The verifier retains the original first-100 ordering and
reports only `xml_356_cases_zero_failures_and_skips`, not full-matrix acceptance: it has not compared
an authoritative complete 356-identity manifest. Full selection/matrix/API evidence is still required. A clean instrumented run without a causal contrast leaves
the investigation open. Remove this entire experiment from any eventual product fix.

## Diagnostic CI wrapper and host package (local synthetic checks passed; CI not yet run)

Both unchanged API34/API36 workflows already invoke `scripts/ci/run-android-ui-tests.sh` and
always upload `android/app/build/outputs/connected_android_test_additional_output`. Only this
diagnostic branch's wrapper opts in. Direct/default Gradle builds remain off, and the existing
signed-debug publication rejection remains in place. There is no normal wrapper fallback: a
missing CI identity or failed setup stops before Gradle rather than silently running without proof.

`package-ci-evidence.py prepare` uses a fresh random nonce and records the real checked-out
`HEAD` commit and tree plus GitHub run, attempt, job, SHA, repository, ref and workflow. This
includes a PR merge checkout when that is what Actions actually checked out; it does not substitute
the PR head. The command recorded before launch is exactly:

```text
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true -PeosMeasureWriterProbe=true -PeosMeasureWriterInvocation=<fresh-nonce>
```

The pre-run manifest and deterministic `source-snapshot.json` are staged outside AGP's collector
directory so collector recreation cannot erase them. The wrapper caches and prints the pre-run
manifest SHA-256 before launching Gradle, then supplies that frozen hash to packaging. No APK hash
is invented at setup time. The snapshot contains full UTF-8 bytes and hashes for the declared
probe/buildSrc/build-config/wrapper/helper/verifier/test/guide scope. It excludes ignored generated
files and itself. It is a scoped source snapshot, **not** a base-to-head diff or a complete build
input archive. It requires no parent/base Git objects, fetched history, credentials or network.

After the single full instrumentation command, packaging rejects a changed pre-run manifest,
source snapshot or checkout identity. It copies the exact current app/test APKs, source snapshot,
pre-run manifest and captured Gradle log into
`connected_android_test_additional_output/eos-measure-writer-host-<invocation>/`. It checks the
current invocation's committed runtime reports against those APK hashes. Missing APKs, reports,
normal finish after green Gradle, pending output, stale identity, invalid recorder proof or failed
build-log capture invalidate the package. The post-run `run-manifest.json` is promoted last, after
all checked files are closed. A missing receipt is an incomplete package, never a valid probe.
The receipt is exclusively written to a pending name, flushed and closed before the final rename;
the verifier rejects any pending host marker even if it contains complete JSON bytes.
The offline verifier also requires the ZIP's retained host files to match the supplied external
pre-run receipt, post-run receipt, source and APK bytes, including the build-log hash.

Gradle's exact exit code always wins if nonzero, including when log capture, packaging or the
existing bounded `EOSDateFilterDiag` fallback also fail. A green Gradle exit becomes nonzero if
packaging fails. Crash evidence may be useful while Gradle still fails; it never makes the test
gate green. No retries, filtering, sharding, test-body edits, timing changes, broad logcat, workflow
edits or matrix changes are introduced. The original first-100 and raw 356-count checks remain
explicit; if the actual checkout later includes added tests, record that change rather than
silently converting this contract to 358 or treating a count as full identity/process proof.

Focused Python tests are prepared for both CI jobs, fresh nonces, setup abort, retained host bytes,
source/pre-run mutation, missing APKs, stale/pending/missing/mismatched runtime reports, log-capture
failure, original Gradle failure preservation and bounded fallback. They have **not been run for
this source change**; wait for the owner's CPU window. No new Android/ART execution is claimed.

### Activation check receipt

On 2026-10-08 at 12:28 UTC, seven wrapper tests plus sixteen host-receipt/verifier tests passed
(exit 0, about five seconds). All Gradle/adb behavior in those tests is synthetic; no emulator or
remote run was started. They cover both API job identities, the exact full command, fresh nonces,
setup failure, immutable pre-run identity, missing/stale/invalid proof, tee and packaging failures,
original Gradle failure precedence, atomic receipt failure-after-complete-bytes and promotion failure.
The seven-file source aggregate before this documentation-only reconciliation was
`cc00f68c5cfbd636159a5c8141658c7238fe07fb8e205cbd51ce447ffcd461ad`; test log SHA-256
`1eb5cb5068d748fdd1f235fa6f5995be386ba5eb9b246ad80df729fbbc0c4f5a`.
Normal/off/release APK exclusion has since been verified below. Actual Android runtime/crash collection remains pending.

The complete existing CI-helper suite also passed afterward: 44/44 tests, exit 0 in 4.787s.
Its fixture warnings are synthetic export-test output, not a newly run iOS test result.

## Completed off/release isolation and preserved ordering mismatch

The single serial 900-second window ran at 2026-10-08 13:18:25–13:28:51 UTC. Normal/off debug,
AndroidTest and unsigned release assembly passed, as did unsigned release with the probe flag
explicitly present and the clean exact-base `d8a514c` builds under the same toolchain/signing
conditions. All ordinary/off and release artifacts have no recorder/runner/transform markers;
the off test manifest selects `androidx.test.runner.AndroidJUnitRunner`. Complete synthetic
publication-signing configuration succeeds with probe off and is rejected with probe on by the
intended guard before key access. No real signing key or publication was used.

The first strict filename-based payload comparison deliberately ended with exit 2, preserved in
`.codex/validation/measure-writer-probe/isolation-v2/`. It found differing debug DEX filenames;
it did not fail a build/test or establish a changed program. Both unsigned release payloads
(with probe absent/present) already matched the exact-base release payload byte for byte.

A separate read-only audit then compared the debug DEX bytes directly, without disassembly
normalization or ignored instructions/metadata. Every DEX has exactly one byte-identical baseline
match under a possibly different numbered filename:

- App baseline→off: 1→1, 2→2, 3→3, 4→5, 5→6, 6→7, 7→8, 8→4.
- Test baseline→off: 1→1, 2→2, 3→4, 4→5, 5→6, 6→7, 7→3.
- App class inventory: 23,308; test: 4,511. Neither has added, removed or duplicate definitions.
- All 2,344 app and 80 test non-DEX entries also match.

Thus the debug difference is whole-DEX enumeration order, with identical executable bytes,
annotations, constants and debug metadata. The APK/container ordering is not byte-identical;
on-device behavioral equivalence was not executed or claimed. No ordinary check was disabled,
no build output was cleaned to hide the difference, and the initial exit 2 is not rewritten.
The direct comparison receipt is `isolation-v2/dex-inspect/debug-dex-order-audit.json`.
This closes local content-contamination checks, not the original crash investigation.

The next step is one explicit diagnostic CI run on the existing draft PR, retaining the full
matrix and independently checking the actual merge checkout and original first-100 prefix.
The observer experiment must never be merged or distributed as a product fix. A green probe run
alone cannot close the release HOLD; interpret actual writer/entry/guard evidence and its limits.
