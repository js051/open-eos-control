(function (root, factory) {
  const mutations = factory();
  if (typeof module === "object" && module.exports) module.exports = mutations;
  root.OpenEOSMediaMutations = mutations;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MAX_ITEMS = 1000;
  const fields = Object.freeze({ protection: "protected", rating: "rating", rotation: "rotationDegrees", archive: "archived" });
  const features = Object.freeze({ protection: "MEDIA_PROTECT", rating: "MEDIA_RATING", rotation: "MEDIA_ROTATE", archive: "MEDIA_ARCHIVE" });

  function valueFor(field, value) {
    if (field === "protected" || field === "archived") return typeof value === "boolean" ? value : null;
    const allowed = field === "rating" ? [0, 1, 2, 3, 4, 5] : field === "rotationDegrees" ? [0, 90, 180, 270] : [];
    // Bridge's normalized profile permits canonical ASCII integer strings too.
    if (typeof value === "string" && allowed.some((number) => String(number) === value)) return Number(value);
    return typeof value === "number" && Number.isSafeInteger(value) && !Object.is(value, -0) && allowed.includes(value) ? value : null;
  }

  function operation(suffix, value) {
    const field = fields[suffix];
    const normalized = valueFor(field, value);
    if (!field || normalized === null) throw new TypeError("Invalid media metadata operation");
    const key = suffix === "rating" ? "value" : suffix === "rotation" ? "degrees" : "enabled";
    return Object.freeze({ suffix, field, value: normalized, feature: features[suffix], json: Object.freeze({ [key]: normalized }) });
  }

  // JSON.parse erases 3.0/3e0 and precision-losing fractions. Inspect top-level
  // number tokens before using normalized Bridge evidence. JSON.parse validates
  // syntax first; this small scanner only locates complete member value slices.
  function parseReadback(text) {
    if (typeof text !== "string" || text.length > 262144) throw new TypeError("Invalid media metadata response");
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
    let cursor = text.indexOf("{") + 1;
    const seen = new Set();
    const invalid = new Set();
    const whitespace = () => { while (/\s/.test(text[cursor] || "") && cursor < text.length) cursor += 1; };
    const stringEnd = () => {
      cursor += 1;
      while (cursor < text.length) {
        if (text[cursor] === "\\") cursor += 2;
        else if (text[cursor++] === '"') return;
      }
    };
    while (cursor < text.length) {
      whitespace();
      if (text[cursor] === "}") break;
      const keyStart = cursor;
      stringEnd();
      const key = JSON.parse(text.slice(keyStart, cursor));
      whitespace();
      cursor += 1; // colon, already validated by JSON.parse
      whitespace();
      const start = cursor;
      let depth = 0;
      while (cursor < text.length) {
        const character = text[cursor];
        if (character === '"') { stringEnd(); continue; }
        if (depth === 0 && (character === "," || character === "}")) break;
        if (character === "{" || character === "[") depth += 1;
        if (character === "}" || character === "]") depth -= 1;
        cursor += 1;
      }
      if (["id", ...Object.values(fields)].includes(key)) {
        if (seen.has(key)) invalid.add(key);
        seen.add(key);
        if (typeof parsed[key] === "number" && !/^(0|[1-9][0-9]*)$/.test(text.slice(start, cursor).trim())) invalid.add(key);
      }
      if (text[cursor] === ",") cursor += 1;
      else break;
    }
    for (const key of invalid) parsed[key] = null;
    for (const field of Object.values(fields)) {
      if (Object.hasOwn(parsed, field)) parsed[field] = valueFor(field, parsed[field]);
    }
    return parsed;
  }

  function confirms(item, id, requested) {
    return Boolean(item && typeof item === "object" && !Array.isArray(item) &&
      typeof item.id === "string" && item.id === id &&
      Object.hasOwn(item, requested.field) && valueFor(requested.field, item[requested.field]) === requested.value);
  }

  function freezeRoster(entries) {
    const roster = new Map();
    for (const entry of entries) {
      if (typeof entry?.id !== "string" || entry.id.length === 0) throw new TypeError("Invalid media identity");
      if (!roster.has(entry.id)) {
        roster.set(entry.id, Object.freeze({ id: entry.id, item: Object.freeze({ ...entry.item }),
          loaded: entry.loaded === true, hidden: entry.hidden === true }));
      }
      if (roster.size > MAX_ITEMS) throw new RangeError("Media metadata batch exceeds 1000 unique items");
    }
    return Object.freeze([...roster.values()]);
  }

  function qualify(entry, requested, supported) {
    if (!entry.loaded) return "unavailable";
    if (!supported) return "unsupported";
    if (entry.item?.[`${requested.suffix}Writable`] === false) return "unsupported-item";
    if (entry.item?.id !== entry.id) return "identity";
    if (requested.suffix === "archive" && typeof entry.item.archived !== "boolean") return "unsupported-item";
    return null;
  }

  async function run(roster, requested, { dispatch, preflight = () => null, isCurrent = () => true,
    signal = null, onUpdate = () => {} } = {}) {
    const frozen = freezeRoster(roster);
    const spec = operation(requested.suffix, requested.value);
    const results = frozen.map((entry) => ({ ...entry, status: "queued", reason: null, observed: null }));
    let stopReason = null;
    let inFlight = null;
    let observerFailed = false;
    const snapshot = () => Object.freeze({ operation: spec, stopReason,
      results: Object.freeze(results.map((row) => Object.freeze({ ...row }))) });
    const stop = (reason) => {
      stopReason ||= reason;
      for (const row of results) if (row.status === "queued") { row.status = "not-started"; row.reason = reason; }
      if (inFlight) { inFlight.status = "uncertain"; inFlight.reason = reason; }
    };
    const current = () => {
      if (!isCurrent()) stop("retired");
      else if (signal?.aborted) stop("cancelled");
      return !stopReason;
    };
    const publish = () => {
      if (!isCurrent() || observerFailed) return;
      try { onUpdate(snapshot()); }
      catch (_) { observerFailed = true; stop("observer-error"); }
    };
    const cancel = () => { stop("cancelled"); publish(); };
    signal?.addEventListener("abort", cancel, { once: true });
    try {
      current();
      publish();
      for (const row of results) {
        if (!current()) break;
        try {
          row.reason = preflight(row, spec);
          if (row.reason) { row.status = "not-started"; publish(); continue; }
        } catch (_) {
          row.status = "failed-not-sent";
          row.reason = "preflight";
          publish();
          continue;
        }
        row.status = "running";
        publish();
        if (!current()) { row.status = "not-started"; row.reason = stopReason; break; }
        // From this boundary any rejection is uncertain, including HTTP errors.
        // The adapter sends exactly one PUT; it must never retry a mutation.
        inFlight = row;
        try {
          const observed = await dispatch(row, spec, { signal });
          if (isCurrent() && confirms(observed, row.id, spec)) {
            row.status = "confirmed";
            row.reason = null;
            row.observed = Object.freeze({ id: row.id, [spec.field]: spec.value });
          } else {
            row.status = "uncertain";
            row.reason = isCurrent() ? "unconfirmed" : "retired";
            inFlight = null;
            stop(row.reason);
          }
        } catch (_) {
          row.status = "uncertain";
          row.reason = "unconfirmed";
          inFlight = null;
          stop("unconfirmed");
        } finally { inFlight = null; }
        current();
        publish();
      }
      current();
      publish();
      return snapshot();
    } finally { signal?.removeEventListener("abort", cancel); }
  }

  return Object.freeze({ MAX_ITEMS, operation, valueFor, parseReadback, confirms, freezeRoster, qualify, run });
});
