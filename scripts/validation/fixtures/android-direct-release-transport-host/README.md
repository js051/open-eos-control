# Direct release transport host boundary

The runner copies exact production AF/Bulb entry methods, commandOk/requestOk,
complete HeldAutofocusSession/ShutterReleaseSession and bounded readers into a
small offline JVM fixture. The public entries, not fixture-selected transport
flags, drive the 12 gap tests. Two compatibility tests cover unchanged Simulator
endpoints and typed plain-text errors, plus strict JSON precedence if still and
release flags are combined.

Calls, responses, streams, operation discovery, status and Simulator postJson
are synthetic. A held body blocks until fixture cleanup or Call.cancel; all
waits have watchdogs. Cancellation tests check producer retirement before the
original release and require the release itself to remain joined. This fixture
does not implement or prove OkHttp's whole-call timer. Separate source guards
check client/factory wiring; the authored MockWebServer tests remain NOT_RUN
without the existing missing dependencies.

See docs/validation/android-direct-release-transport-bound-2026-10-17.zh-TW.md
for the exact baseline, limits, command, evidence and native-test status.
