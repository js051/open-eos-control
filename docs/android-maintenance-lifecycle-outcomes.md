# Android maintenance lifecycle outcomes

## Implemented scope

Clock sync, supported sensor cleaning, and supported camera sleep use a frozen connection generation, backend/session handle, action, and product intent. The clock button itself is an explicit intent; cleaning and sleep retain their existing confirmation dialogs. Changing connection while a dialog is open retires its intent. Backgrounding or closing the maintenance sheet cancels its in-flight work. For a maintenance-owned connection, including after successful restoration, ViewModel destruction retires only local stream/owner resources and does not implicitly delete the Bridge session or write camera Stop/mode commands. An explicit user disconnect remains separate. A consumed confirmation token cannot dispatch again.

The receipt retains separate event Stop, Live View Stop, command response, original clock reference/read/sample, latest read-only observation, and explicit restoration evidence. History is bounded to 20 entries. A late response can update its retained original history entry, but cannot update the replacement connection, revive an evicted entry, issue another command, or resume monitoring.

Clock bounds preserve the existing 10-second comparison. CCAPI proves epoch plus the frozen UTC offset and DST flag. USB proves only the advertised integer epoch property through a fresh same-property event; it cannot invent timezone or DST evidence. A later observation never rewrites the original bounds or original verification. Bridge rejects numeric strings, decimals/exponents, booleans, overflow, malformed framing, and duplicate JSON keys as proof. Invalid clock child fields retain a valid command-response envelope.

## Paused maintenance and explicit recovery

Cleaning/sleep first suspend local monitoring and require separate successful event and Live View Stop where needed. An event Stop failure blocks the main command even if no preview was running. A required Stop needs its own complete response, not reachability.

Sleep and auto-power-off cleaning perform no automatic follow-up read, restore, disconnect, or Bridge session deletion. Unreachable does not prove sleep, power-off, or completed cleaning. Reconnecting/disconnecting is a new explicit user action.

Keep-power cleaning may make one bounded required read after its response. It remains paused. The separate read-only recovery control makes only a fresh clock/battery/Bridge maintenance GET or already-prepared USB GET_EVENT. It never prepares USB remote mode, writes, reconnects, or starts Live View.

The separate Restore controls/monitoring confirmation requires the original owner and attempt, successful required Stops, a command response, its own fresh required read, and current REC/Bulb/shutter/AF/Stop safety. A new unresolved Stop warning blocks restoration and cannot be cleared by an old receipt or a no-op restore. Only previously running preview/events are restored. With no previous preview, restoration releases local control hold without starting Live View. Restoration does not prove physical cleaning completion.

## Transport boundaries

Maintenance command, event Stop, Live View Stop/Start, and recovery use dedicated no-redirect/no-auth-replay/no-cache/no-connection-retry branches. Each phase configures a 15-second budget; I/O cancellation watchers cover both phase deadline and the original caller. A configured timeout alone does not prove bounded caller return when a body producer ignores cancellation. The Bridge command-dispatch and three strict cleanup routes have separate callback-based caller-return evidence in [dispatch validation](android-maintenance-dispatch-deadline.md) and [cleanup validation](android-maintenance-cleanup-deadline.md). General readback, preview restoration and other transports are not covered by that claim. In-flight completed responses may remain in history, while every subsequent dependency rechecks the original owner/cancellation gate.

Direct CCAPI freezes the original Stop closure and actual active preview source/size. Restoration does not retry parameters, negotiate another source, or automatically issue cleanup after an uncertain Start. Multipart opening remains separately bounded and is transferred to normal streaming only after successful acceptance. A strict-owned Stop obligation retains its transport policy and frozen endpoint after a failed or cancelled restoration. A later explicit Retry Stop uses a fresh caller scope with the same strict profile, never the cancelled attempt’s context. During a maintenance hold, this control can stop only; it cannot restart preview or release the hold. Same-session newer warning revisions supersede delayed strict Stop and Start responses.

Explicit maintenance-owned disconnect first performs the original owned held-autofocus and Bulb/shutter release steps, preserving their original evidence and independent warning on failure. A failed focus release does not skip an outstanding shutter release. Any unconfirmed physical release blocks Live View Stop and preserves its outstanding warning. Otherwise it sends at most one required strict Stop and at most one Bridge maintenance/control/session DELETE. That route retires only server-local resources; it cannot trigger camera cleanup. Missing/failed retirement is surfaced without legacy fallback or automatic retry. Stop uncertainty remains a warning even when retirement returns empty HTTP 204. Background/onCleared retirement stays entirely local. Normal non-maintenance lifecycle behavior is unchanged.

Bridge uses the additive maintenance/control/events and maintenance/control/liveview/{stop,start} routes. The server freezes its actual active preview source/size/FPS at Stop, including when the client's original request was AUTO. Event Stop requires exactly empty HTTP 204; Live View control requires HTTP 200 and typed active/Stop evidence. Missing new routes fail closed without legacy fallback.

## Validation boundary

The local harness compiles actual pure production maintenance evidence/workflow/strict-JSON code with the existing Kotlin/JUnit tools, runs focused tests and all 130 shared vectors, checks Kotlin PSI syntax and EN/zh-TW resource parity, and records source hashes. The fixture adapter preserves the shared vector JSON types; the real org.json resource loader is not substituted into production.

Android SDK/Gradle type checking, Compose instrumentation, MockWebServer transport tests, native USB/RTP execution, and real-camera validation are NOT_RUN in this environment. Source-only tests cover those paths for the native acceptance lane. These results establish an implemented candidate, not PR-ready, physical-camera, main-accepted, or released status.
