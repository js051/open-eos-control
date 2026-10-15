(function (root, factory) {
  const settings = factory();
  if (typeof module === "object" && module.exports) module.exports = settings;
  root.OpenEOSShootingSettings = settings;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const object = (value) => Boolean(value && typeof value === "object" && !Array.isArray(value));
  const string = (value) => typeof value === "string" && [...value].length <= 512 &&
    !/[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(value);
  const key = (value) => typeof value === "string" && /^[a-z][a-z0-9._-]{0,63}$/.exec(value)?.[0] === value;
  function validSetting(value, expectedKey) {
    if (!object(value) || value.key !== expectedKey || !key(value.key) || !string(value.label) ||
        !string(value.value) || !Array.isArray(value.values)) return false;
    if (value.inputKind === "text") return value.values.length === 0 && Number.isInteger(value.maxLength) &&
      value.maxLength >= 1 && value.maxLength <= 255 && value.value.length <= value.maxLength &&
      /^[\x20-\x7e]*$/.exec(value.value)?.[0] === value.value;
    return value.inputKind === "choice" && value.maxLength == null && value.values.length >= 1 &&
      value.values.length <= 1024 && new Set(value.values).size === value.values.length &&
      value.values.every((item) => string(item) && item.length > 0) && value.values.includes(value.value);
  }
  function accepts(setting, value) {
    return validSetting(setting, setting?.key) && string(value) && (setting.inputKind === "text"
      ? value.length <= setting.maxLength && /^[\x20-\x7e]*$/.exec(value)?.[0] === value
      : setting.values.includes(value));
  }
  function parseOutcome({ status, engine, key: requestedKey, requestedValue, response }) {
    const result = { writeEvidence: "unknown", readbackEvidence: "unavailable", setting: null };
    if (status !== 200 || !object(response) || response.version !== 1 || response.engine !== engine ||
        response.key !== requestedKey || response.requestedValue !== requestedValue ||
        response.writeEvidence !== "response-received") return result;
    result.writeEvidence = "response-received";
    if (!validSetting(response.setting, requestedKey)) return result;
    const read = response.setting.value === requestedValue ? "matched" : "mismatch";
    if (read !== response.readbackEvidence) return result;
    return { ...result, readbackEvidence: read, setting: response.setting };
  }
  function parseObservation({ status, engine, key: requestedKey, response }) {
    return status === 200 && object(response) && response.version === 1 && response.engine === engine &&
      validSetting(response.setting, requestedKey) ? response.setting : null;
  }
  function outcome({ dispatched, responseReceived, readStatus, readbackMatches }) {
    return { writeEvidence: dispatched ? responseReceived ? "response-received" : "unknown" : "not-sent",
      readbackEvidence: readStatus === "success" ? readbackMatches ? "matched" : "mismatch" : readStatus };
  }
  function ownership({ sameOwner, sameAttempt, sameDraftRevision, cancelled, responseReceived, readbackMatched }) {
    const canPublishCurrent = Boolean(sameOwner && sameAttempt && !cancelled);
    return { canPublishCurrent, canConsumeDraft: Boolean(canPublishCurrent && sameDraftRevision && responseReceived && readbackMatched) };
  }
  return Object.freeze({ string, key, validSetting, accepts, parseOutcome, parseObservation, outcome, ownership });
});
