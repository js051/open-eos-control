(function (root, factory) {
  const history = factory();
  if (typeof module === "object" && module.exports) module.exports = history;
  root.OpenEOSMediaTransferHistory = history;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const DATABASE_NAME = "open-eos-media-transfer-history";
  const SCHEMA_VERSION = 1;
  const MAX_ENTRIES = 100;
  const MAX_FILENAME_LENGTH = 120;
  const MAX_SNAPSHOT_LENGTH = 100 * 1024;
  const STORE = "history";
  const KEY = "state";
  const DESTINATION = "browser-directory";
  const OUTCOMES = ["in-progress", "completed", "failed", "cancelled-or-unknown"];
  const SNAPSHOT_FIELDS = ["schemaVersion", "epoch", "nextSequence", "entries"];
  const ENTRY_FIELDS = ["receiptId", "batchId", "sequence", "filename", "destination", "startedAtMillis",
    "finishedAtMillis", "outcome", "mayRemain", "outcomeUnknown", "bytesTransferred"];
  const ID_PATTERN = /^(?:[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12})$/i;

  const success = (value = {}) => Object.freeze({ ok: true, ...value });
  const failure = (code) => Object.freeze({ ok: false, code });
  const integer = (value) => Number.isSafeInteger(value) && value >= 0;
  const timestamp = (value) => integer(value) && value <= 8640000000000000;
  const identifier = (value) => typeof value === "string" && (value.length === 32 || value.length === 36) && ID_PATTERN.test(value);
  const exactFields = (value, fields) => value !== null && typeof value === "object" && !Array.isArray(value)
    && Object.keys(value).length === fields.length && fields.every((key) => Object.hasOwn(value, key));

  // Display-only leaf name. Never retain a URI authority, query, fragment or path.
  // Reject schemes instead of extracting a potentially secret token as a filename.
  function sanitizeFilename(value) {
    if (typeof value !== "string" || value.length > 4096) return "download";
    const cleaned = value.replace(/[\p{Cc}\p{Cf}\p{Cs}]/gu, "").trim();
    const windowsPath = /^[a-z]:[/\\]/i.test(cleaned);
    if (!windowsPath && /^[a-z][a-z0-9+.-]*:/i.test(cleaned)) return "download";
    const leaf = cleaned.split(/[?#]/, 1)[0].replace(/\\/g, "/").split("/").pop().trim();
    // Percent escapes can conceal a path, URI or query. A display placeholder is
    // safer than decoding repeatedly or storing the encoded address verbatim.
    if (!leaf || leaf === "." || leaf === ".." || /[:%]/.test(leaf)) return "download";
    return leaf.slice(0, MAX_FILENAME_LENGTH).replace(/[\uD800-\uDBFF]$/, "").trim() || "download";
  }

  function validateSnapshot(snapshot) {
    if (snapshot && snapshot.schemaVersion !== SCHEMA_VERSION) return "unsupported-schema";
    if (!exactFields(snapshot, SNAPSHOT_FIELDS) || !integer(snapshot.epoch)
      || !integer(snapshot.nextSequence) || !Array.isArray(snapshot.entries)
      || snapshot.entries.length > MAX_ENTRIES) return "corrupt";
    const receipts = new Set();
    let priorSequence = snapshot.nextSequence;
    for (const entry of snapshot.entries) {
      if (!exactFields(entry, ENTRY_FIELDS) || !identifier(entry.receiptId) || !identifier(entry.batchId)
        || receipts.has(entry.receiptId) || !integer(entry.sequence) || entry.sequence >= priorSequence
        || typeof entry.filename !== "string" || entry.filename.length > MAX_FILENAME_LENGTH
        || sanitizeFilename(entry.filename) !== entry.filename || entry.destination !== DESTINATION
        || !timestamp(entry.startedAtMillis) || !OUTCOMES.includes(entry.outcome)
        || typeof entry.mayRemain !== "boolean" || typeof entry.outcomeUnknown !== "boolean"
        || !(entry.bytesTransferred === null || integer(entry.bytesTransferred))) return "corrupt";
      if (entry.outcome === "in-progress") {
        if (entry.finishedAtMillis !== null || entry.mayRemain || entry.outcomeUnknown || entry.bytesTransferred !== null) return "corrupt";
      } else if (!timestamp(entry.finishedAtMillis) || entry.finishedAtMillis < entry.startedAtMillis) return "corrupt";
      if (entry.outcome === "completed" && (entry.mayRemain || entry.outcomeUnknown)) return "corrupt";
      receipts.add(entry.receiptId);
      priorSequence = entry.sequence;
    }
    return JSON.stringify(snapshot).length <= MAX_SNAPSHOT_LENGTH ? null : "corrupt";
  }

  function displayEntry(entry) {
    const { sequence, ...display } = entry;
    // This is a read projection, never a persistent transition. A different tab
    // may still own and finish this in-progress transfer after a cold read.
    const unconfirmed = entry.outcome === "in-progress";
    return Object.freeze({ ...display,
      outcome: unconfirmed ? "cancelled-or-unknown" : entry.outcome,
      outcomeUnknown: unconfirmed || entry.outcomeUnknown,
      unconfirmed,
    });
  }

  function secureId() {
    const crypto = globalThis.crypto;
    if (typeof crypto?.randomUUID === "function") return crypto.randomUUID();
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  }

  /**
   * Optional receipt journal, not a resumable queue or evidence that a file still
   * exists. Only actual attempted originals are admitted, immediately before
   * destination.open. Capture one ticket after the picker, before the first item.
   * Await admission, then recheck the transfer's cancellation/session ownership.
   * Call finish at confirmed close or failure, independent of current UI ownership.
   * History failures must never change the transfer's return value or exception.
   *
   * A single bounded snapshot uses IndexedDB readwrite transactions for all state
   * changes. The read and write stay in the same transaction, so another tab cannot
   * lose an update. clear changes the epoch and empties entries atomically; old
   * batch tickets cannot recreate entries. No handles, camera identifiers, URLs,
   * errors, reconnects, picker requests, retries or destination deletion live here.
   */
  function createHistoryStore(options = {}) {
    let db = null;
    let opening = null;
    let closed = false;
    const tickets = new WeakSet();
    const receipts = new WeakSet();
    const transactions = new Set();
    const timeoutMs = Number.isInteger(options.timeoutMs) && options.timeoutMs >= 1 && options.timeoutMs <= 10000
      ? options.timeoutMs : 2000;
    const now = options.now || Date.now;
    const randomId = options.randomId || secureId;

    async function connect() {
      if (closed) return failure("closed");
      if (db) return success();
      if (opening) return opening;
      opening = new Promise((resolve) => {
        let settled = false;
        let request = null;
        let upgrade = null;
        const finish = (result) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          resolve(result);
        };
        const timer = setTimeout(() => {
          try { upgrade?.abort(); } catch (_) { /* An already finished upgrade cannot be aborted. */ }
          finish(failure("timeout"));
        }, timeoutMs);
        try {
          const backend = Object.hasOwn(options, "indexedDB") ? options.indexedDB : globalThis.indexedDB;
          if (!backend || typeof backend.open !== "function") return finish(failure("unavailable"));
          request = backend.open(options.databaseName || DATABASE_NAME, SCHEMA_VERSION);
          request.onblocked = () => finish(failure("blocked"));
          request.onerror = () => finish(failure(request.error?.name === "VersionError" ? "unsupported-schema" : "unavailable"));
          request.onupgradeneeded = (event) => {
            upgrade = request.transaction;
            try {
              if (settled || closed || event.oldVersion !== 0) {
                upgrade.abort();
                return finish(failure(closed ? "closed" : "unsupported-schema"));
              }
              const store = request.result.createObjectStore(STORE);
              store.put({ schemaVersion: SCHEMA_VERSION, epoch: 0, nextSequence: 0, entries: [] }, KEY);
            } catch (_) {
              try { upgrade.abort(); } catch (_) { /* Handled by the open result. */ }
              finish(failure("unavailable"));
            }
          };
          request.onsuccess = () => {
            const connection = request.result;
            if (settled || closed) {
              connection.close();
              return finish(failure("closed"));
            }
            if (connection.objectStoreNames.length !== 1 || !connection.objectStoreNames.contains(STORE)) {
              connection.close();
              return finish(failure("unsupported-schema"));
            }
            db = connection;
            connection.onversionchange = () => close();
            connection.onclose = () => { if (db === connection) { db = null; closed = true; } };
            finish(success());
          };
        } catch (_) { finish(failure("unavailable")); }
      });
      return opening;
    }

    async function transact(mode, operation) {
      try {
        const connected = await connect();
        if (!connected.ok) return connected;
        if (closed || !db) return failure("closed");
        return await new Promise((resolve) => {
          let settled = false;
          let transaction = null;
          let result = null;
          let problem = null;
          const finish = (value) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            transactions.delete(cancel);
            resolve(value);
          };
          const cancel = (code = "closed") => {
            if (settled) return;
            problem = code;
            try {
              transaction?.abort();
              finish(failure(code));
            } catch (_) {
              // Commit may already have happened, before its completion event.
              // Never claim rollback when the browser refused our abort.
              finish(failure("transaction-unconfirmed"));
            }
          };
          const timer = setTimeout(() => cancel("timeout"), timeoutMs);
          try {
            transaction = db.transaction(STORE, mode);
            transactions.add(cancel);
            transaction.onabort = () => finish(failure(problem || "transaction-failed"));
            transaction.onerror = () => { problem ||= "transaction-failed"; };
            transaction.oncomplete = () => finish(result || failure("transaction-unconfirmed"));
            const store = transaction.objectStore(STORE);
            if (store.keyPath !== null || store.autoIncrement) return cancel("unsupported-schema");
            const countRequest = store.count();
            countRequest.onsuccess = () => {
              if (settled) return;
              if (countRequest.result !== 1) return cancel("corrupt");
              const readRequest = store.get(KEY);
              readRequest.onsuccess = () => {
                if (settled) return;
                try {
                  const snapshot = readRequest.result;
                  const invalid = validateSnapshot(snapshot);
                  if (invalid) return cancel(invalid);
                  const change = operation(snapshot);
                  result = change.result;
                  if (change.write) {
                    const invalidWrite = validateSnapshot(snapshot);
                    if (invalidWrite) return cancel(invalidWrite);
                    store.put(snapshot, KEY);
                  }
                } catch (_) { cancel("invalid-input"); }
              };
            };
          } catch (_) { cancel("transaction-failed"); }
        });
      } catch (_) { return failure("unavailable"); }
    }

    async function open() {
      return transact("readonly", () => ({ result: success() }));
    }

    async function list() {
      return transact("readonly", (snapshot) => ({ result: success({ entries: Object.freeze(snapshot.entries.map(displayEntry)) }) }));
    }

    async function createBatchTicket() {
      return transact("readonly", (snapshot) => {
        const batchId = randomId();
        if (!identifier(batchId)) return { result: failure("invalid-input") };
        const ticket = Object.freeze({ batchId, epoch: snapshot.epoch });
        tickets.add(ticket);
        return { result: success({ ticket }) };
      });
    }

    async function startAttempt(ticket, input = {}) {
      return transact("readwrite", (snapshot) => {
        if (!tickets.has(ticket)) return { result: failure("invalid-ticket") };
        if (ticket.epoch !== snapshot.epoch) return { result: failure("stale-ticket") };
        if (input.destination !== DESTINATION || typeof input.filename !== "string"
          || input.filename.length > 4096 || snapshot.nextSequence >= Number.MAX_SAFE_INTEGER) {
          return { result: failure("invalid-input") };
        }
        const receiptId = randomId();
        const startedAtMillis = now();
        if (!identifier(receiptId) || !timestamp(startedAtMillis)
          || snapshot.entries.some((entry) => entry.receiptId === receiptId)) return { result: failure("invalid-input") };
        const receipt = Object.freeze({ receiptId, batchId: ticket.batchId, epoch: ticket.epoch });
        snapshot.entries.unshift({ receiptId, batchId: ticket.batchId, sequence: snapshot.nextSequence++,
          filename: sanitizeFilename(input.filename), destination: DESTINATION, startedAtMillis,
          finishedAtMillis: null, outcome: "in-progress", mayRemain: false, outcomeUnknown: false, bytesTransferred: null });
        snapshot.entries.length = Math.min(snapshot.entries.length, MAX_ENTRIES);
        receipts.add(receipt);
        return { write: true, result: success({ receipt }) };
      });
    }

    async function finishAttempt(receipt, input = {}) {
      return transact("readwrite", (snapshot) => {
        if (!receipts.has(receipt)) return { result: failure("invalid-receipt") };
        if (receipt.epoch !== snapshot.epoch) return { result: failure("stale-ticket") };
        const entry = snapshot.entries.find((candidate) => candidate.receiptId === receipt.receiptId
          && candidate.batchId === receipt.batchId);
        if (!entry) return { result: failure("missing-receipt") };
        if (entry.outcome !== "in-progress") return { result: success({ updated: false }) };
        const { outcome, mayRemain = false, outcomeUnknown = false, bytesTransferred = null } = input;
        const finishedAtMillis = now();
        if (!OUTCOMES.slice(1).includes(outcome) || typeof mayRemain !== "boolean" || typeof outcomeUnknown !== "boolean"
          || !(bytesTransferred === null || integer(bytesTransferred)) || !timestamp(finishedAtMillis)
          || (outcome === "completed" && (mayRemain || outcomeUnknown))) return { result: failure("invalid-input") };
        Object.assign(entry, { outcome, mayRemain, outcomeUnknown, bytesTransferred,
          finishedAtMillis: Math.max(entry.startedAtMillis, finishedAtMillis) });
        return { write: true, result: success({ updated: true }) };
      });
    }

    async function clear() {
      return transact("readwrite", (snapshot) => {
        if (snapshot.epoch >= Number.MAX_SAFE_INTEGER) return { result: failure("epoch-exhausted") };
        snapshot.epoch += 1;
        snapshot.entries = [];
        return { write: true, result: success() };
      });
    }

    function close() {
      closed = true;
      for (const cancel of Array.from(transactions)) cancel();
      try { db?.close(); } catch (_) { /* Closing history cannot affect a transfer. */ }
      db = null;
    }

    return Object.freeze({ open, list, createBatchTicket, startAttempt, finishAttempt, clear, close });
  }

  return { createHistoryStore, sanitizeFilename, DATABASE_NAME, SCHEMA_VERSION, MAX_ENTRIES, MAX_FILENAME_LENGTH };
});
