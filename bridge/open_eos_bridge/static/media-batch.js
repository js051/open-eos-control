(function (root, factory) {
  const mediaBatch = factory();
  if (typeof module === "object" && module.exports) module.exports = mediaBatch;
  root.OpenEOSMediaBatch = mediaBatch;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function copyFrozen(value) {
    if (Array.isArray(value)) return Object.freeze(value.map(copyFrozen));
    if (value && typeof value === "object") {
      return Object.freeze(Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, copyFrozen(entry)])));
    }
    return value;
  }

  function itemMap(items) {
    const result = new Map();
    for (const item of items) {
      // IDs are opaque: do not coerce, trim, case-fold or normalize Unicode.
      if (typeof item?.id !== "string" || item.id.length === 0) {
        throw new TypeError("Media items require a nonempty string ID");
      }
      if (!result.has(item.id)) result.set(item.id, copyFrozen(item));
    }
    return result;
  }

  function createSelection() {
    let loaded = new Map();
    const selected = new Map();
    const selectionTokens = new Map();
    let selectionRevision = 0;
    const touch = (id) => selectionTokens.set(id, ++selectionRevision);
    return Object.freeze({
      replaceLoaded(items) {
        loaded = itemMap(items);
        // Listings may be partial. Omission is a scope warning, not deletion;
        // only remove() or the caller's session reset discards a selection.
        for (const id of selected.keys()) {
          if (loaded.has(id)) selected.set(id, loaded.get(id));
        }
      },
      setSelected(id, value) {
        if (!value) { selectionTokens.delete(id); return selected.delete(id); }
        if (!loaded.has(id)) return false;
        touch(id);
        selected.set(id, loaded.get(id));
        return true;
      },
      setMany(ids, value) {
        for (const id of ids) {
          if (!value) { selectionTokens.delete(id); selected.delete(id); }
          else if (loaded.has(id)) { touch(id); selected.set(id, loaded.get(id)); }
        }
      },
      has(id) { return selected.has(id); },
      token(id) { return selectionTokens.get(id) ?? null; },
      consumeIfToken(id, token) {
        if (token == null || selectionTokens.get(id) !== token) return false;
        selectionTokens.delete(id);
        return selected.delete(id);
      },
      remove(id) {
        selectionTokens.delete(id);
        loaded.delete(id);
        return selected.delete(id);
      },
      clear() { selectionTokens.clear(); selected.clear(); },
      snapshot(visibleIds = null) {
        const visible = visibleIds === null ? null : new Set(visibleIds);
        return Object.freeze(Array.from(selected, ([id, item]) => Object.freeze({
          id, item, loaded: loaded.has(id), hidden: visible !== null && !visible.has(id),
        })));
      },
    });
  }

  function errorDetails(error) {
    return Object.freeze({
      name: typeof error?.name === "string" ? error.name : "Error",
      message: typeof error?.message === "string" ? error.message : String(error),
    });
  }

  // transfer must resolve { completed: true, bytesTransferred, name } only after
  // its destination has closed successfully. Reject uncertain close outcomes
  // with error.outcomeUnknown = true. The trusted transfer adapter may reject
  // with transferNotStarted === true only before any destination/file/network
  // side effect. Never retry or start another item here.
  async function runSequentialBatch(items, {
    transfer, signal = null, isCurrent = () => true, onUpdate = () => {},
  } = {}) {
    if (typeof transfer !== "function" || typeof isCurrent !== "function" || typeof onUpdate !== "function") {
      throw new TypeError("transfer, isCurrent and onUpdate must be functions");
    }
    const results = Array.from(itemMap(items), ([id, item]) => ({
      id, item, status: "pending", receipt: null, error: null,
    }));
    let status = "pending";
    let currentIndex = null;
    let inFlight = false;
    let stopReason = null;
    let observerError = null;
    const snapshot = () => Object.freeze({
      status, currentIndex, stopReason, observerError,
      results: Object.freeze(results.map((result) => Object.freeze({ ...result }))),
    });
    const stop = (reason) => {
      if (!stopReason) stopReason = reason;
      for (const result of results) {
        if (result.status === "pending") result.status = "not-started";
      }
      if (currentIndex !== null && results[currentIndex].status === "running") {
        results[currentIndex].status = inFlight ? "cancelled-or-unknown" : "not-started";
      }
      status = inFlight ? "stopping" : "stopped";
    };
    const checkOwner = () => {
      if (!isCurrent()) stop("retired");
      if (signal?.aborted) stop("cancelled");
      return !stopReason;
    };
    const publish = () => {
      // The predicate must identify the exact owner, not a reusable session ID.
      if (!isCurrent() || observerError) return;
      try {
        onUpdate(snapshot());
      } catch (error) {
        observerError = errorDetails(error);
        stop("observer-error");
      }
    };
    const abort = () => {
      stop("cancelled");
      publish();
    };

    signal?.addEventListener("abort", abort, { once: true });
    try {
      checkOwner();
      publish();
      for (let index = 0; index < results.length && checkOwner(); index += 1) {
        currentIndex = index;
        const result = results[index];
        result.status = "running";
        status = "running";
        publish();
        if (!checkOwner()) break;
        inFlight = true;
        try {
          const receipt = await transfer(result.item, { signal, isCurrent, index });
          checkOwner();
          // A close receipt remains true even if cancellation or replacement
          // arrived during close. Only this queue's own immutable result changes.
          if (receipt?.completed === true) {
            result.receipt = copyFrozen(receipt);
            result.status = "completed";
          } else {
            result.status = "cancelled-or-unknown";
            stop("unconfirmed");
          }
        } catch (error) {
          checkOwner();
          const uncertain = signal?.aborted || !isCurrent() || error?.outcomeUnknown === true ||
            error?.name === "AbortError";
          result.error = errorDetails(error);
          const notStarted = error?.transferNotStarted === true && error?.outcomeUnknown !== true;
          result.status = notStarted ? "not-started" : uncertain ? "cancelled-or-unknown" : "failed";
          stop(notStarted ? "not-started" : uncertain ? "unknown" : "failed");
        } finally {
          inFlight = false;
        }
        checkOwner();
        currentIndex = null;
        if (stopReason) status = "stopped";
        publish();
      }
      currentIndex = null;
      checkOwner();
      status = stopReason ? "stopped" : "completed";
      publish();
      return snapshot();
    } finally {
      signal?.removeEventListener("abort", abort);
    }
  }

  return { createSelection, runSequentialBatch };
});
