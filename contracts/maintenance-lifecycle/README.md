# Maintenance lifecycle evidence v1

Operations: `clock-sync`, `sensor-cleaning`, `sleep`. New POST
`/v1/session/{id}/maintenance/{operation}/outcome` requires strict boolean
`autoPowerOff`; it must be false except for sensor cleaning. No old mutation
endpoint fallback is permitted. One frozen owner/intent dispatches at most once.

A complete, unambiguous HTTP 200 response must match version (integer 1), actual
engine, operation, autoPowerOff and `responseEvidence: response-received`.
Response evidence means the existing transport contract succeeded, not physical
completion, exactly-once delivery or causal proof. Invalid/missing clock evidence
cannot erase valid response evidence. Invalid JSON/framing rejects the whole body.

Clock reference has earliestEpochSeconds, latestEpochSeconds, utcOffsetMinutes,
daylightSaving. Observation has epochSeconds, utcOffsetMinutes, daylightSaving.
Seconds must be exact integers in [-62135596800,253402300799]; reference window
is ordered and at most120s. Offset and DST are both null or an exact integer
[-1439,1439] and boolean. A match needs inclusive epoch bounds and equality of
non-null reference offset/DST. CCAPI retains requested time +-10s and validates
offset/DST. Existing gphoto2 action retains request-10..verified+10 epoch-only
proof. USB retains its existing10s event criterion; absent zone proof stays null.

Outcome clockReadEvidence is matched/mismatch/unavailable for clock; other
operations use not-applicable and null clock fields. Matching/mismatch needs
valid reference+observation and a consistent recomputed label. Reference may be
retained independently after read failure. Non-clock fields are not clock proof.

GET `/v1/session/{id}/maintenance/{operation}` is strictly read-only. Envelope:
version,engine,operation,observationEvidence,clockObservation. Clock uses
observed+valid sample; cleaning/sleep use reachable+null only after a required
strict actual read. Unavailable/malformed/failed observation is unavailable.
Do not synthesize reachability from optional status/cached model. No preparation
writes, Live View starts, reconnect, route fallback or action replay during GET.

Keep immutable initial command/clock evidence plus separate latest recovery. A
later clock naturally advances: never move the original bounds or erase earlier
verification. A later read cannot upgrade unknown command evidence.

Local phases: stop=not-required/pending/confirmed/unknown; command=not-sent/unknown/
response-received; restore=not-requested/pending/confirmed/unknown/not-applicable.
Keep20 history entries. Current publication requires same owner/attempt, no
cancel/retirement. Original response can settle history after cancellation.
Restoration is a separate explicit action only for keep-power cleaning, same
owner, confirmed stop, received response, new
required reachability read and current REC/uncertain Stop/Bulb safety. Unknown
stop/action leaves monitoring paused. Sleep/auto-off never auto DELETE/reset or
restart; the user explicitly reconnects. Explicit keep-power restoration resumes
controls and only previously active Live View; no prior Live View means no Start.
Local control restoration is not proof that cleaning finished. Read-only recovery never restores.

No native/browser/physical acceptance is implied by shared source fixtures.

Maintenance prerequisites use additive controls: POST
`/v1/session/{id}/maintenance/control/liveview/stop` and `/start` return exact
LiveViewState, while DELETE `/v1/session/{id}/maintenance/control/events` requires
204 and no body. Stop requires active=false and explicit liveViewStopUnconfirmed=false.
Providers without scoped controls fail before dispatch; no legacy fallback.
The Bridge freezes actual source/size/FPS before Stop and restores that frozen
request, not client AUTO negotiation. No source/size retry or automatic Stop
after an uncertain restore. A new stop obligation remains until explicit action.
Native/client cancellation stops observation and preserves uncertainty. Python
server camera I/O has cooperative per-request deadlines, not hard cancellation
of an already dispatched camera command when the HTTP client disconnects.

Explicit maintenance-owned Retry Stop keeps the original token and strict transport,
uses a fresh cancellable deadline, sends no Start and remains available while held.
A same-owner newer Stop warning while Stop/Start is pending prevents late success
from clearing it. Background/retirement cancel the invocation, never the obligation.

Explicit Disconnect may attempt each required strict Stop once, then DELETE
`/v1/session/{id}/maintenance/control/session` requires exact empty204. This route
only closes Bridge-local receivers/transport and removes its session registration;
it makes no camera request, does not acknowledge Stop, and does not invoke ordinary
provider.close. Unsupported providers fail before dispatch. Physical shutter
release obligations prevent local-only retirement. Keep Stop and local-retirement
uncertainty separate; do not fall back or automatically retry. Background/teardown
without explicit Disconnect performs client-local cleanup only.
