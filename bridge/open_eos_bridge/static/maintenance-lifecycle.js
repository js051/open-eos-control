(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.OpenEOSMaintenanceLifecycle = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const operations = new Set(["clock-sync", "sensor-cleaning", "sleep"]);
  const integer = (value, low, high) => Number.isSafeInteger(value) && value >= low && value <= high;
  const object = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
  const zone = (value) => (value.utcOffsetMinutes === null && value.daylightSaving === null) ||
    (integer(value.utcOffsetMinutes, -1439, 1439) && typeof value.daylightSaving === "boolean");
  const validClock = (value) => object(value) && integer(value.epochSeconds, -62135596800, 253402300799) && zone(value);
  const validReference = (value) => object(value) && zone(value) &&
    integer(value.earliestEpochSeconds, -62135596800, 253402300799) &&
    integer(value.latestEpochSeconds, -62135596800, 253402300799) &&
    value.latestEpochSeconds >= value.earliestEpochSeconds && value.latestEpochSeconds - value.earliestEpochSeconds <= 120;
  const clockMatches = (reference, observed) => validReference(reference) && validClock(observed) &&
    observed.epochSeconds >= reference.earliestEpochSeconds && observed.epochSeconds <= reference.latestEpochSeconds &&
    (reference.utcOffsetMinutes === null || (reference.utcOffsetMinutes === observed.utcOffsetMinutes &&
      reference.daylightSaving === observed.daylightSaving));
  function parseOutcome({ status, engine, operation, autoPowerOff, response }) {
    const result = { responseEvidence: "unknown", clockReference: null, clockObservation: null,
      clockReadEvidence: operation === "clock-sync" ? "unavailable" : "not-applicable" };
    if (status !== 200 || !operations.has(operation) || typeof autoPowerOff !== "boolean" ||
        (operation !== "sensor-cleaning" && autoPowerOff) || !object(response) || response.version !== 1 ||
        response.engine !== engine || response.operation !== operation || response.autoPowerOff !== autoPowerOff ||
        response.responseEvidence !== "response-received") return result;
    result.responseEvidence = "response-received";
    if (operation !== "clock-sync") return result;
    if (validReference(response.clockReference)) result.clockReference = { ...response.clockReference };
    if (!result.clockReference || !validClock(response.clockObservation)) return result;
    const evidence = clockMatches(result.clockReference, response.clockObservation) ? "matched" : "mismatch";
    if (response.clockReadEvidence !== evidence) return result;
    result.clockReadEvidence = evidence;
    result.clockObservation = { ...response.clockObservation };
    return result;
  }
  function parseObservation({ status, engine, operation, response }) {
    const empty = { observationEvidence: "unavailable", clockObservation: null };
    if (status !== 200 || !operations.has(operation) || !object(response) || response.version !== 1 ||
        response.engine !== engine || response.operation !== operation) return empty;
    if (operation === "clock-sync") return response.observationEvidence === "observed" && validClock(response.clockObservation)
      ? { observationEvidence: "observed", clockObservation: { ...response.clockObservation } } : empty;
    return response.observationEvidence === "reachable" && response.clockObservation === null
      ? { observationEvidence: "reachable", clockObservation: null } : empty;
  }
  function ownership(input) {
    const canPublish = input.sameOwner === true && input.sameAttempt === true && input.cancelled !== true && input.retired !== true;
    return { canPublish, canRestore: canPublish && input.operation === "sensor-cleaning" && input.autoPowerOff === false &&
      input.responseReceived === true && input.stopConfirmed === true &&
      input.freshReachable === true && input.safetyClear === true };
  }
  return Object.freeze({ validClock, validReference, clockMatches, parseOutcome, parseObservation, ownership });
});
