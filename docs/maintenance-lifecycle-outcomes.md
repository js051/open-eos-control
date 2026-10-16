# Clock, cleaning and sleep: reliable lifecycle

Each action retains its exact session and confirmed user intent. The interface
separates preparation/stop, command response, clock verification, later read-only
observation and any explicit monitoring restoration. No failed action is replayed
automatically. Closing/cancelling cannot undo a dispatched camera command.

Clock verification preserves actual transport capability: CCAPI includes UTC
offset and daylight-saving evidence; epoch-only transports never fabricate it.
A later clock read is a new observation, not a rewrite of the original result.

Cleaning and sleep retain the existing confirmation and recording/Bulb gates.
Losing contact proves neither sleep nor cleaning completion. Monitoring stays
paused after uncertainty and after sleep/power-off. Keep-power cleaning offers
explicit guarded restoration; read-only recovery performs no hidden writes.

The additive Bridge maintenance endpoints are required by this new journey.
Unsupported providers fail before dispatch. Old endpoints remain compatible,
but clients never fall back to them after uncertainty. Exact platform builds,
real browser flows and physical camera behavior remain separate acceptance.

Release assessment: backward-compatible reliability fix (patch); no product
version change or release. Native/physical acceptance is pending.
