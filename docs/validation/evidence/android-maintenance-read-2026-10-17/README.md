# Maintenance read host evidence, 2026-10-17

## Captured runtime results

- `baseline`: immutable `a42aad06d0c2521c3196ce6773dda62f9a0856ac`, compiled before
  product edits, exactly five expected runtime failures. Initial read,
  explicit recovery read, restore's fresh read, and strict restore Start were
  all exercised through exact production ViewModel and repository methods.
  The real unmodified read deadline fired after 15,017 ms. Every failure
  asserted an incomplete registered job, zero repository disconnect entries,
  teardown pending, and the response producer still held. Bodies were released
  only during fixture teardown.
- `current`: 64 passing tests in 16.230 seconds: 15 caller-chain tests, 44 existing
  production MaintenanceWorkflowTest cases, and five strict response/route tests.
  The real unmodified read deadline fired after 15,013 ms while the producer
  remained held. The strict rejection matrix executes 30 read/Start cases.
- `extra`: eight passing strict response/route/fence tests in 0.167 seconds.
  This reruns the five strict controls with successful valid observations for
  all three exact action GET roots and adds three cancellation, replacement
  owner, and duplicate callback controls. Combined with `current`, this is
  67 distinct passing tests; the five rerun tests are not counted twice.

Regression runs against the same frozen production sources:

- `prior-dispatch`: 67 passed in 30.727 seconds. Real unchanged clock and
  maintenance dispatch deadlines were 15,004 ms and 15,003 ms respectively.
- `prior-cleanup`: 16 passed in 15.211 seconds. The prior route selector's
  negative GET `sleep` vector intentionally becomes GET `sleep/outcome`, since
  the exact `sleep` root is now an explicitly supported bounded read. The exact
  method, owner, child-path and strict Start negative gates are preserved.

- `prior-bulb`: all 59 existing owned-Bulb controls passed in 15.486 seconds.

Only the four read counterexamples are repaired. Strict restore Start retains
its original blocking transport; the fifth baseline failure remains a known
limitation. The current compatibility control proves that Start still keeps
Disconnect waiting until the producer returns, retains strict Stop ownership,
and then sends strict Stop before strict session DELETE. It does not claim a
bounded return or remote server Start/Stop serialization.

All runs use receipt-verified existing tools, a 256 MiB JVM heap and one active
processor. Compiler and test JVMs run sequentially. No Android/Gradle build,
OkHttp/Compose/device execution, installation, network, or camera validation is
claimed. The fixture README describes the exact extraction and adapter seams.

Public receipts retain original source hash values and present source paths as
records. Public JUnit logs normalize trailing blank lines to one LF. Raw logs,
receipts, extracted source and compiled test outputs are retained separately.
