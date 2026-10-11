(function (root, factory) {
  const policy = factory();
  if (typeof module === "object" && module.exports) module.exports = policy;
  root.OpenEOSMediaAutoImport = policy;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // No timer, picker, persistence or camera command lives in this policy. The
  // foreground owner explicitly schedules step(); repeated calls coalesce.
  function createController({ inventory, metadata, transfer, isCurrent, isForeground,
    isBusy = () => false, now = () => performance.now(), onUpdate = () => {},
    inventoryLimit = 1000, knownLimit = 4096, queueLimit = 20,
    stableMs = 1000, pollMs = 5000, pendingMs = 30000, resultLimit = 100,
  } = {}) {
    for (const callback of [inventory, metadata, transfer, isCurrent, isForeground, isBusy, now, onUpdate]) {
      if (typeof callback !== "function") throw new TypeError("Import callbacks are required");
    }
    for (const value of [inventoryLimit, knownLimit, queueLimit, stableMs, pollMs, pendingMs, resultLimit]) {
      if (!Number.isSafeInteger(value) || value < 1) throw new TypeError("Import limits must be positive integers");
    }
    if (inventoryLimit > 1000 || knownLimit > 4096 || queueLimit > 20 || resultLimit > 100 ||
        stableMs < 1000 || pollMs < 5000 || pendingMs > 30000 || pendingMs < stableMs || knownLimit < inventoryLimit) {
      throw new RangeError("Import limits exceed the bounded foreground policy");
    }
    const controller = new AbortController();
    const known = new Set();
    const pending = [];
    const results = [];
    let phase = "baseline-first", reason = null, baseline = null;
    let operation = null, promise = null, lastInventory = -Infinity, nextMetadata = -Infinity;
    let lastTime = -Infinity, completed = 0;
    const snapshot = () => Object.freeze({ phase, reason, operation, completed,
      knownCount: known.size, pendingCount: pending.length,
      results: Object.freeze(results.map((entry) => Object.freeze({ ...entry }))),
    });
    function stop(code = "user-stopped") {
      if (!reason) reason = code;
      phase = operation ? "stopping" : "stopped";
      controller.abort();
      publish();
      return snapshot();
    }
    function publish() {
      if (!isCurrent()) return;
      try { onUpdate(snapshot()); }
      catch (_) {
        if (!reason) reason = "observer-failed";
        phase = operation ? "stopping" : "stopped";
        controller.abort();
      }
    }
    function current() {
      if (!isCurrent()) { if (!reason) reason = "session-retired"; }
      else if (!isForeground()) { if (!reason) reason = "backgrounded"; }
      if (reason) { phase = operation ? "stopping" : "stopped"; controller.abort(); return false; }
      return true;
    }
    function time() {
      const value = now();
      if (!Number.isFinite(value) || value < lastTime) throw new Error("Invalid monotonic clock");
      lastTime = value;
      return value;
    }
    function validatedItems(observation) {
      if (observation?.complete !== true || !Array.isArray(observation.items) || observation.items.length > inventoryLimit) {
        throw new Error("Incomplete inventory evidence");
      }
      const map = new Map();
      for (const item of observation.items) {
        if (typeof item?.id !== "string" || !item.id || item.id.length > 4096 || map.has(item.id) ||
            typeof item.name !== "string" || !item.name || item.name.length > 4096) {
          throw new Error("Invalid or duplicate inventory identity");
        }
        map.set(item.id, Object.freeze({ ...item }));
      }
      return map;
    }
    function append(result) {
      results.push(result);
      if (results.length > resultLimit) results.shift();
    }
    async function execute() {
      if (!current()) { publish(); return snapshot(); }
      if (isBusy()) return snapshot();
      try {
        const start = time();
        if (phase === "baseline-first" || phase === "baseline-confirm" || start - lastInventory >= pollMs) {
          if (phase === "baseline-confirm" && start - lastInventory < stableMs) return snapshot();
          operation = "inventory"; publish();
          if (!current() || isBusy()) return snapshot();
          const observation = await inventory({ signal: controller.signal, limit: inventoryLimit });
          if (!current()) return snapshot();
          const items = validatedItems(observation);
          lastInventory = time();
          if (phase === "baseline-first") {
            baseline = new Set(items.keys()); phase = "baseline-confirm";
            return snapshot();
          }
          if (phase === "baseline-confirm") {
            if (items.size !== baseline.size || [...items.keys()].some((id) => !baseline.has(id))) {
              stop("baseline-changed"); return snapshot();
            }
            for (const id of baseline) known.add(id);
            baseline = null; phase = "watching";
            return snapshot();
          }
          const added = [...items.values()].filter((item) => !known.has(item.id));
          const jpeg = added.filter((item) => /\.jpe?g$/i.test(item.name) && item.kind === "image");
          // All identities, including RAW and non-JPEG files, consume the budget.
          if (known.size + added.length > knownLimit) { stop("identity-limit"); return snapshot(); }
          if (pending.length + jpeg.length > queueLimit) { stop("queue-limit"); return snapshot(); }
          for (const item of added) known.add(item.id);
          for (const item of jpeg) pending.push({ item, observedAt: lastInventory, size: null, sizeAt: null });
          if (pending.some((entry) => !items.has(entry.item.id))) { stop("pending-disappeared"); return snapshot(); }
        }
        if (!current() || isBusy() || !pending.length || time() < nextMetadata) return snapshot();
        const entry = pending[0];
        if (time() - entry.observedAt > pendingMs) { stop("metadata-deadline"); return snapshot(); }
        operation = "metadata"; publish();
        if (!current() || isBusy()) return snapshot();
        const observation = await metadata(entry.item, { signal: controller.signal });
        if (!current()) return snapshot();
        const at = time();
        nextMetadata = at + stableMs;
        if (observation?.fresh !== true || observation.item?.id !== entry.item.id) {
          stop("fresh-metadata-unavailable"); return snapshot();
        }
        const size = observation.item.sizeBytes;
        if (!Number.isSafeInteger(size) || size <= 0) { entry.size = null; entry.sizeAt = null; return snapshot(); }
        if (entry.size !== size) { entry.size = size; entry.sizeAt = at; return snapshot(); }
        if (at - entry.sizeAt < stableMs || isBusy()) return snapshot();
        if (at - entry.observedAt > pendingMs) { stop("metadata-deadline"); return snapshot(); }
        operation = "transfer"; publish();
        if (!current() || isBusy()) return snapshot();
        const record = { id: entry.item.id, name: entry.item.name, status: "running", bytesTransferred: null };
        append(record);
        try {
          const receipt = await transfer(Object.freeze({ ...entry.item, sizeBytes: size }), {
            signal: controller.signal, expectedBytes: size,
          });
          // A completed close is factual even after stop/session retirement.
          if (receipt?.completed === true && receipt.bytesTransferred === size) {
            record.status = "completed"; record.bytesTransferred = size; completed += 1;
            pending.shift();
          } else { record.status = "cancelled-or-unknown"; stop("transfer-unconfirmed"); }
        } catch (error) {
          record.status = reason || error?.name === "AbortError" || error?.outcomeUnknown === true
            ? "cancelled-or-unknown" : "failed";
          stop(record.status === "failed" ? "transfer-failed" : "transfer-unconfirmed");
        }
        current();
      } catch (_) {
        stop(operation === "inventory" ? "inventory-unavailable" : "metadata-unavailable");
      } finally {
        operation = null;
        if (reason) phase = "stopped";
        publish();
      }
      return snapshot();
    }
    function step() {
      if (promise) return promise;
      promise = Promise.resolve().then(execute).then(() => snapshot()).finally(() => { promise = null; });
      return promise;
    }
    return Object.freeze({ step, stop, snapshot });
  }
  return { createController };
});
