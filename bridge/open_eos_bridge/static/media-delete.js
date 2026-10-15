(function (root, factory) {
  const policy = factory();
  if (typeof module === "object" && module.exports) module.exports = policy;
  root.OpenEOSMediaDelete = policy;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const MAX_ROWS = 1000;
  const REFUSALS = new Set([400, 401, 403, 404, 405, 409, 412, 413, 415, 422, 429]);
  const SOURCES = new Set(["camera", "desktop-host", "android-host"]);

  function eligibility({ loaded, capability, source, protected: protectedValue, writable } = {}) {
    const reason = !loaded ? "not-loaded" : capability !== true ? "no-capability" :
      !SOURCES.has(source) ? "unknown-source" : protectedValue === true ? "protected" :
      writable === false ? "not-writable" : "eligible";
    return Object.freeze({ allowed: reason === "eligible", reason,
      qualificationUnknown: reason === "eligible" && (protectedValue == null || writable == null) });
  }
  function admission({ openingOwner, currentOwner, confirmed, consumed, busy } = {}) {
    return Object.freeze({ allowed: openingOwner != null && openingOwner === currentOwner &&
      confirmed === true && consumed === false && busy === false });
  }
  function outcome({ started, dispatched, confirmedReceipt, rejectedReceipt, skippedReason } = {}) {
    return dispatched ? confirmedReceipt ? "confirmed" : rejectedReceipt ? "rejected" : "uncertain" :
      skippedReason ? "skipped" : started ? "not-sent" : "not-started";
  }
  function httpReceipt({ status, responseComplete, framingValid, bodyBytes } = {}) {
    if (responseComplete !== true || framingValid !== true) return "uncertain";
    if (status === 204 && bodyBytes === 0) return "confirmed";
    return REFUSALS.has(status) ? "rejected" : "uncertain";
  }
  function roster(selectedIds) {
    if (!Array.isArray(selectedIds) || selectedIds.some((id) => typeof id !== "string" || !id.length)) {
      return Object.freeze({ allowed: false, reason: "invalid-id", ids: Object.freeze([]) });
    }
    const ids = [...new Set(selectedIds)];
    const reason = !ids.length ? "empty" : ids.length > MAX_ROWS ? "too-many" : "valid";
    return Object.freeze({ allowed: reason === "valid", reason,
      ids: Object.freeze(reason === "valid" ? ids : []) });
  }
  function consumeSelection({ operationOwner, currentOwner, operationSelectionToken,
    currentSelectionToken, selectedIds, confirmedIds } = {}) {
    if (operationOwner == null || operationOwner !== currentOwner || operationSelectionToken == null ||
        operationSelectionToken !== currentSelectionToken) return [...selectedIds];
    const confirmed = new Set(confirmedIds);
    return selectedIds.filter((id) => !confirmed.has(id));
  }
  function sourceFor(item, engine) {
    if (typeof item?.id !== "string") return "unknown";
    if (engine === "libgphoto2" && item.id.startsWith("gphoto2-host:")) return "desktop-host";
    if (engine === "libgphoto2" && item.id.startsWith("gphoto2:")) return "camera";
    if (engine === "ccapi" && item.id.startsWith("ccapi:")) return "camera";
    return "unknown";
  }
  function createAttempt() {
    let started = false, dispatched = false, cancelled = false;
    let confirmedReceipt = false, rejectedReceipt = false, skippedReason = null;
    return Object.freeze({
      start() { if (cancelled || started) return false; started = true; return true; },
      skip(reason) { if (!dispatched && !confirmedReceipt) skippedReason = reason || "ineligible"; },
      beforeDispatch() { if (cancelled || dispatched || skippedReason || !started) return false; dispatched = true; return true; },
      confirm() { if (dispatched) confirmedReceipt = true; },
      reject() { if (dispatched && !confirmedReceipt) rejectedReceipt = true; },
      cancel() { cancelled = true; },
      snapshot() { return Object.freeze({ started, dispatched, cancelled, confirmedReceipt,
        rejectedReceipt, skippedReason, status: outcome({ started, dispatched, confirmedReceipt, rejectedReceipt, skippedReason }) }); },
    });
  }

  // The adapter owns the real transport gate and must record a complete receipt
  // before returning through a cancellable continuation. No generic exception is
  // an authoritative refusal. The queue never retries, refreshes or compensates.
  async function runBatch(rows, { transfer, signal = null, isCurrent = () => true, onUpdate = () => {} } = {}) {
    if (typeof transfer !== "function" || !roster(rows.map((row) => row.id)).allowed) {
      throw new TypeError("A nonempty, bounded exact deletion roster is required");
    }
    const unique = new Map();
    for (const row of rows) if (!unique.has(row.id)) unique.set(row.id, row);
    const results = [...unique.values()].map((row) => ({ row: Object.freeze({ ...row }),
      attempt: createAttempt(), status: "not-started", error: null }));
    let stopped = null, active = null, finished = false;
    const snapshot = () => Object.freeze({ stopped, finished,
      results: Object.freeze(results.map(({ row, attempt, error }) => Object.freeze({ ...row,
        status: attempt.snapshot().status, dispatched: attempt.snapshot().dispatched,
        reason: attempt.snapshot().skippedReason || row.reason, error }))) });
    const stop = (reason) => { if (!stopped) stopped = reason; active?.cancel(); };
    const publish = () => { try { onUpdate(snapshot()); } catch (_) { stop("observer-error"); } };
    const check = () => {
      if (!isCurrent()) stop("retired");
      if (signal?.aborted) stop("cancelled");
      return !stopped;
    };
    const abort = () => { stop("cancelled"); publish(); };
    signal?.addEventListener("abort", abort, { once: true });
    try {
      publish();
      for (const result of results) {
        if (!check()) break;
        active = result.attempt;
        if (!result.row.eligible) { active.skip(result.row.reason); publish(); active = null; continue; }
        if (!active.start()) { stop("not-sent"); break; }
        publish();
        if (!check()) break;
        try {
          await transfer(result.row, { attempt: active, signal, isCurrent,
            beforeDispatch: () => {
              if (!check() || !active.beforeDispatch()) return false;
              publish();
              // Dispatch was admitted synchronously. Observer cancellation must
              // still prevent the transport call, but remains conservative.
              return check();
            } });
        } catch (error) {
          result.error = typeof error?.message === "string" ? error.message : "Deletion result unavailable";
        }
        const terminal = active.snapshot().status;
        if (terminal === "uncertain" || terminal === "not-sent") stop(terminal);
        active = null;
        publish();
      }
    } finally {
      active = null;
      finished = true;
      signal?.removeEventListener("abort", abort);
      publish();
    }
    return snapshot();
  }
  async function transferHttp(url, { attempt, beforeDispatch, signal, headers = {}, fetchImpl = fetch,
    timeoutMs = 30000 } = {}) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    signal?.addEventListener("abort", abort, { once: true });
    if (signal?.aborted) abort();
    const timer = setTimeout(abort, timeoutMs);
    try {
      if (controller.signal.aborted || !beforeDispatch()) return;
      const response = await fetchImpl(url, { method: "DELETE", headers, signal: controller.signal,
        redirect: "error", cache: "no-store", credentials: "omit" });
      let bytes = 0;
      const reader = response.body?.getReader();
      if (reader) {
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            bytes += value.byteLength;
            if (bytes > 65536) { await reader.cancel(); return; }
          }
        } finally { reader.releaseLock(); }
      }
      const length = response.headers.get("content-length");
      const encoding = response.headers.get("transfer-encoding");
      const framingValid = (length === null || (/^[0-9]+$/.test(length) && Number(length) === bytes)) &&
        !(length !== null && encoding !== null) && (response.status !== 204 || encoding === null);
      // Fetch cannot expose discarded illegal wire bytes for 204. This confirms
      // only the complete, strictly framed response observable through Fetch.
      const status = httpReceipt({ status: response.status, responseComplete: true, framingValid, bodyBytes: bytes });
      if (status === "confirmed") attempt.confirm();
      else if (status === "rejected") attempt.reject();
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
    }
  }
  function boundedHistory(batches) {
    const result = [...batches].slice(-20);
    let total = result.reduce((sum, batch) => sum + batch.results.length, 0);
    while (total > 2000 && result.length) total -= result.shift().results.length;
    return result;
  }
  return Object.freeze({ MAX_ROWS, eligibility, admission, outcome, httpReceipt, roster,
    consumeSelection, sourceFor, createAttempt, runBatch, transferHttp, boundedHistory });
});
