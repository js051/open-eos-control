(function (root, factory) {
  const configuration = factory();
  if (typeof module === "object" && module.exports) module.exports = configuration;
  root.OpenEOSCaptureConfiguration = configuration;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const fields = Object.freeze({
    "still-filename-mode": "stillFilenameMode", "still-user-setting-1": "stillUserSetting1",
    "still-user-setting-2": "stillUserSetting2", "movie-index": "movieIndex",
    "movie-reel-number": "movieReelNumber", "movie-clip-number": "movieClipNumber",
    "movie-user-defined": "movieUserDefined",
  });
  const object = (value) => Boolean(value && typeof value === "object" && !Array.isArray(value));
  function whole(pattern, value) {
    return typeof value === "string" && value.match(pattern)?.[0] === value;
  }
  function validFieldValue(field, value) {
    if (typeof value !== "string") return false;
    if (field === "still-filename-mode") return ["preset_code", "usersetting1", "usersetting2"].includes(value);
    const patterns = { "still-user-setting-1": /^[A-Z0-9][A-Z0-9_]{3}$/,
      "still-user-setting-2": /^[A-Z0-9][A-Z0-9_]{2}$/, "movie-index": /^[A-Z0-9][A-Z0-9_]$/,
      "movie-user-defined": /^[A-Z0-9]{5}$/ };
    if (Object.hasOwn(patterns, field)) return whole(patterns[field], value);
    if (!["movie-reel-number", "movie-clip-number"].includes(field) || !whole(/^[1-9][0-9]*$/, value)) return false;
    return value.length <= (field === "movie-reel-number" ? 4 : 3) && Number(value) <= (field === "movie-reel-number" ? 9999 : 999);
  }
  function validReceipt(input) {
    const { status, code, engine, feature, operation, requestedValue, field, receipt: value } = input;
    if (status !== 502 || code !== "CAPTURE_CONFIG_READBACK_FAILED" || engine !== "ccapi" ||
        !object(value) || value.version !== 1 || value.stage !== "acknowledged" ||
        value.operation !== operation || value.requestedValue !== requestedValue ||
        typeof requestedValue !== "string" || requestedValue.length > 64) return false;
    if (operation === "create-directory") return feature === "DIRECTORY_CONTROL" &&
      !Object.hasOwn(value, "field") && whole(/^(?:[A-Z0-9_]{5})?$/, requestedValue) &&
      whole(/^[A-Z0-9_]{5}$/, value.acknowledgedValue);
    return operation === "set-file-naming" && feature === "FILE_NAMING_CONTROL" &&
      Object.hasOwn(fields, field) && validFieldValue(field, requestedValue) && value.field === field && value.acknowledgedValue === requestedValue;
  }
  function outcome({ operation, dispatched, validAcknowledgment, readStatus, readbackMatches }) {
    return { writeEvidence: dispatched ? validAcknowledgment ? "acknowledged" : "unknown" : "not-sent",
      readbackEvidence: readStatus === "success" ? operation === "create-directory" ? "refreshed" :
        readbackMatches ? "matched" : "mismatch" : readStatus };
  }
  function ownership({ sameOwner, sameAttempt, retired, sameDraftRevision, validAcknowledgment, readbackMatched, cancelled }) {
    const canPublishCurrent = Boolean(sameOwner && sameAttempt && !retired);
    return { canPublishCurrent, canConsumeDraft: Boolean(canPublishCurrent && sameDraftRevision &&
      validAcknowledgment && readbackMatched && !cancelled) };
  }
  function canRecover({ sameOwner, writeActive, readActive, hasRecord }) {
    return Boolean(sameOwner && !writeActive && !readActive && hasRecord);
  }
  function validNaming(value) {
    if (!object(value) || !Array.isArray(value.stillFilenameModeOptions) ||
        value.stillFilenameModeOptions.length < 1 || value.stillFilenameModeOptions.length > 16 ||
        new Set(value.stillFilenameModeOptions).size !== value.stillFilenameModeOptions.length ||
        value.stillFilenameModeOptions.some((mode) => !["preset_code", "usersetting1", "usersetting2"].includes(mode)) ||
        !value.stillFilenameModeOptions.includes(value.stillFilenameMode)) return false;
    for (const [key, pattern] of [["stillUserSetting1", /^[A-Z0-9][A-Z0-9_]{3}$/],
      ["stillUserSetting2", /^[A-Z0-9][A-Z0-9_]{2}$/], ["movieIndex", /^[A-Z0-9][A-Z0-9_]$/],
      ["movieUserDefined", /^[A-Z0-9]{5}$/]]) {
      if (!whole(pattern, value[key])) return false;
    }
    for (const [key, rangeKey, maximum] of [["movieReelNumber", "movieReelRange", 9999],
      ["movieClipNumber", "movieClipRange", 999]]) {
      const range = value[rangeKey], current = value[key];
      if (!object(range) || ![current, range.minimum, range.maximum, range.step].every(Number.isSafeInteger) ||
          range.minimum < 1 || range.maximum > maximum || range.minimum > range.maximum || range.step < 1 ||
          current < range.minimum || current > range.maximum || (current - range.minimum) % range.step !== 0) return false;
    }
    return true;
  }
  function valueFor(naming, field) {
    return Object.hasOwn(fields, field) && naming?.[fields[field]] !== undefined ? String(naming[fields[field]]) : null;
  }
  function validSelection(value) {
    return object(value) && Array.isArray(value.ability) && value.ability.length > 0 && value.ability.length <= 256 &&
      value.ability.every((item) => whole(/^[0-9]{3}[A-Z0-9_]{5}$/, item)) &&
      new Set(value.ability).size === value.ability.length && value.ability.includes(value.value);
  }
  function parseJson(text) {
    const value = JSON.parse(text);
    // JSON.parse silently takes the last duplicate key and accepts lone escaped
    // surrogates. Neither is safe for exact request or media identity binding.
    // Scan the already grammar-checked text without recursive parsing.
    const stack = [];
    for (let index = 0; index < text.length; index++) {
      const character = text[index];
      if (character === "{") stack.push({ keys: new Set(), key: true });
      else if (character === "[") stack.push({ keys: null });
      else if (character === "}" || character === "]") stack.pop();
      else if (character === "," && stack.at(-1)?.keys) stack.at(-1).key = true;
      else if (character === '"') {
        const start = index++;
        while (text[index] !== '"') {
          if (text[index] === "\\") index++;
          index++;
        }
        const decoded = JSON.parse(text.slice(start, index + 1));
        if (/[\uD800-\uDFFF]/u.test(decoded)) throw new TypeError("Ambiguous configuration JSON");
        const object = stack.at(-1);
        if (object?.keys && object.key) {
          if (object.keys.has(decoded)) throw new TypeError("Ambiguous configuration JSON");
          object.keys.add(decoded);
          object.key = false;
        }
      }
    }
    return value;
  }
  async function transfer(path, { method = "GET", payload, token = "", signal } = {}) {
    const headers = { Accept: "application/json", "Cache-Control": "no-cache, no-store" };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (payload !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(path, { method, headers, body: payload === undefined ? undefined : JSON.stringify(payload),
      signal, cache: "no-store", credentials: "omit", redirect: "error" });
    if (!response.body?.getReader) throw new TypeError("Configuration response stream unavailable");
    const reader = response.body.getReader(), decoder = new TextDecoder("utf-8", { fatal: true });
    let bytes = 0, text = "";
    try {
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        bytes += next.value.byteLength;
        if (bytes > 65536) throw new TypeError("Configuration response exceeds limit");
        text += decoder.decode(next.value, { stream: true });
      }
      text += decoder.decode();
    } catch (error) {
      try { await reader.cancel(); } catch (_) { /* Retain the original error. */ }
      throw error;
    } finally { reader.releaseLock(); }
    return { status: response.status, body: parseJson(text) };
  }
  return Object.freeze({ validReceipt, validFieldValue, whole, outcome, ownership, canRecover, validNaming, valueFor, validSelection, transfer });
});
