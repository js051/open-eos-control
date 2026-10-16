(function (root, factory) {
  const history = factory();
  if (typeof module === "object" && module.exports) module.exports = history;
  root.OpenEOSMediaTransferHistory = history;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const DATABASE_NAME = "open-eos-media-transfer-history";
  // The object-store layout stays at IDB version 1. Snapshot v1 is read without
  // inventing a selected roster, and upgraded in the same atomic transaction as
  // the next mutation. Old clients reject newer snapshots instead of overwriting them.
  const DATABASE_VERSION = 1;
  const SCHEMA_VERSION = 4;
  const MAX_ENTRIES = 100;
  const MAX_FILENAME_LENGTH = 120;
  const MAX_SNAPSHOT_LENGTH = 100 * 1024;
  const STORE = "history";
  const KEY = "state";
  const DESTINATION = "browser-directory";
  const DIRECTORY_OUTCOMES = ["in-progress", "completed", "failed", "cancelled-or-unknown"];
  const OBSERVATION_DESTINATIONS = ["browser-download", "browser-file"];
  const OUTCOMES = [...DIRECTORY_OUTCOMES, "handoff-requested", "close-confirmed"];

  // These are observed browser checkpoints, never interchangeable save receipts.
  // Download handoff has no save callback. Single-file close is not a worklist row.
  function validDestinationOutcome(entry, version) {
    if (entry.destination === DESTINATION) return DIRECTORY_OUTCOMES.includes(entry.outcome);
    if (version !== 4 || !OBSERVATION_DESTINATIONS.includes(entry.destination)) return false;
    if (["in-progress", "failed", "cancelled-or-unknown"].includes(entry.outcome)) return true;
    if (entry.destination === "browser-file" && entry.outcome === "close-confirmed") {
      return !entry.mayRemain && !entry.outcomeUnknown;
    }
    return entry.destination === "browser-download" && entry.outcome === "handoff-requested"
      && !entry.mayRemain && entry.outcomeUnknown;
  }
  const LEGACY_SNAPSHOT_FIELDS = ["schemaVersion", "epoch", "nextSequence", "entries"];
  const SNAPSHOT_FIELDS = [...LEGACY_SNAPSHOT_FIELDS, "plans"];
  const PLAN_FIELDS = ["batchId", "createdAtMillis", "rows"];
  const ROW_FIELDS = ["rowId", "order", "filename"];
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

  // Plan labels are sanitized display text, never identity or source locators.
  // Keep the same conservative query/fragment removal as actual-attempt labels.
  function sanitizePlannedFilename(value) {
    return sanitizeFilename(value);
  }

  function encodedSize(value) {
    return new TextEncoder().encode(JSON.stringify(value)).length;
  }

  const plansOf = (snapshot) => snapshot.schemaVersion === 1 ? [] : snapshot.plans;
  const plannedRows = (snapshot) => new Map(plansOf(snapshot).flatMap((plan) =>
    plan.rows.map((row) => [row.rowId, { row, batchId: plan.batchId }])));
  function occupiedRows(snapshot) {
    const rows = plannedRows(snapshot);
    return rows.size + snapshot.entries.filter((entry) => !rows.has(entry.receiptId)).length;
  }

  function migrateSnapshot(snapshot) {
    if (snapshot.schemaVersion === 1) {
      snapshot.plans = [];
    }
    snapshot.schemaVersion = SCHEMA_VERSION;
  }

  // Reserve the encoded footprint of every planned receipt before admitting the
  // roster. Starting a queued row must not consume another row or depend on old
  // records being evicted. Account for the largest valid terminal metadata too.
  function fitsCapacity(snapshot) {
    if (occupiedRows(snapshot) > MAX_ENTRIES) return false;
    const entries = new Map(snapshot.entries.map((entry) => [entry.receiptId, entry]));
    for (const plan of plansOf(snapshot)) {
      for (const row of plan.rows) {
        if (!entries.has(row.rowId)) entries.set(row.rowId, {
          receiptId: row.rowId, batchId: plan.batchId, filename: row.filename, destination: DESTINATION,
        });
      }
    }
    const reserved = { ...snapshot, schemaVersion: SCHEMA_VERSION, plans: plansOf(snapshot),
      epoch: Number.MAX_SAFE_INTEGER, nextSequence: Number.MAX_SAFE_INTEGER,
      entries: [...entries.values()].map((entry) => ({ ...entry, sequence: Number.MAX_SAFE_INTEGER,
        startedAtMillis: 8640000000000000, finishedAtMillis: 8640000000000000,
        outcome: "cancelled-or-unknown", mayRemain: false, outcomeUnknown: false,
        bytesTransferred: Number.MAX_SAFE_INTEGER })),
    };
    return encodedSize(reserved) <= MAX_SNAPSHOT_LENGTH;
  }

  function validateSnapshot(snapshot) {
    if (snapshot && ![1, 2, 3, SCHEMA_VERSION].includes(snapshot.schemaVersion)) return "unsupported-schema";
    if (!exactFields(snapshot, snapshot?.schemaVersion === 1 ? LEGACY_SNAPSHOT_FIELDS : SNAPSHOT_FIELDS) || !integer(snapshot.epoch)
      || !integer(snapshot.nextSequence) || !Array.isArray(snapshot.entries)
      || snapshot.entries.length > MAX_ENTRIES) return "corrupt";
    const plans = plansOf(snapshot);
    if (!Array.isArray(plans) || plans.length > MAX_ENTRIES) return "corrupt";
    const batches = new Set();
    const rows = new Map();
    for (const plan of plans) {
      if (!exactFields(plan, PLAN_FIELDS) || !identifier(plan.batchId) || batches.has(plan.batchId)
        || !timestamp(plan.createdAtMillis) || !Array.isArray(plan.rows)
        || plan.rows.length === 0 || plan.rows.length > MAX_ENTRIES) return "corrupt";
      batches.add(plan.batchId);
      for (const [index, row] of plan.rows.entries()) {
        if (!(exactFields(row, ROW_FIELDS) || ([3, 4].includes(snapshot.schemaVersion)
          && exactFields(row, [...ROW_FIELDS, "origin"])
          && exactFields(row.origin, ["batchId", "rowId"])
          && identifier(row.origin.batchId) && identifier(row.origin.rowId))) || !identifier(row.rowId) || rows.has(row.rowId)
          || row.order !== index || typeof row.filename !== "string" || row.filename.length > MAX_FILENAME_LENGTH
          || sanitizePlannedFilename(row.filename) !== row.filename) return "corrupt";
        rows.set(row.rowId, { row, batchId: plan.batchId });
      }
    }
    if (rows.size > MAX_ENTRIES || [...rows.keys()].some((rowId) => batches.has(rowId))) return "corrupt";
    // Local links carry no source identity. Require older, existing rows and no cycles.
    const order = new Map(plans.map((plan, index) => [plan.batchId, index]));
    for (const { row, batchId } of rows.values()) {
      if (!row.origin) continue;
      const origin = rows.get(row.origin.rowId);
      if (!origin || origin.batchId !== row.origin.batchId
        || order.get(origin.batchId) <= order.get(batchId)) return "corrupt";
    }
    const receipts = new Set();
    let priorSequence = snapshot.nextSequence;
    for (const entry of snapshot.entries) {
      const planned = rows.get(entry?.receiptId);
      if (!exactFields(entry, ENTRY_FIELDS) || !identifier(entry.receiptId) || !identifier(entry.batchId)
        || receipts.has(entry.receiptId) || !integer(entry.sequence) || entry.sequence >= priorSequence
        || typeof entry.filename !== "string" || entry.filename.length > MAX_FILENAME_LENGTH
        || (planned ? planned.batchId !== entry.batchId || planned.row.filename !== entry.filename
          : batches.has(entry.batchId) || sanitizeFilename(entry.filename) !== entry.filename)
        || !validDestinationOutcome(entry, snapshot.schemaVersion)
        || (planned && entry.destination !== DESTINATION)
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
    if (occupiedRows(snapshot) > MAX_ENTRIES) return "corrupt";
    return encodedSize(snapshot) <= MAX_SNAPSHOT_LENGTH ? null : "corrupt";
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

  function displayPlan(plan, entries) {
    const receipts = new Map(entries.map((entry) => [entry.receiptId, entry]));
    return Object.freeze({ batchId: plan.batchId, createdAtMillis: plan.createdAtMillis,
      rows: Object.freeze(plan.rows.map((row) => {
        const entry = receipts.get(row.rowId);
        const receipt = entry ? displayEntry(entry) : null;
        return Object.freeze({ ...row, receipt, outcome: receipt?.outcome || "queued" });
      })),
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
   * Offline selected-batch worklist plus optional actual-attempt receipts. A
   * planned batch persists every selected row atomically before any original or
   * destination is opened. Dynamic foreground imports only record actual starts.
   * Neither is a resumable queue or evidence that a completed file still exists;
   * directory completion and browser-file close-confirmed only record writer.close,
   * not fsync or existence. Browser-download handoff-requested never confirms saving.
   * Single-file observations share capacity but have no worklist or replay capability.
   * Await admission, then recheck the transfer's cancellation/session ownership.
   * Call finish at confirmed close or failure, independent of current UI ownership.
   * Once a transfer actually runs, finishing history must never change its return
   * value or exception. A planned row's admission failure must block its start.
   *
   * A single bounded snapshot uses IndexedDB readwrite transactions for all state
   * changes. The read and write stay in the same transaction, so another tab cannot
   * lose an update. clear changes the epoch and empties plans/entries atomically; old
   * batch tickets cannot recreate entries. No handles, camera identifiers, URLs,
   * errors, reconnects, picker requests, retries or destination deletion live here.
   */
  function createHistoryStore(options = {}) {
    let db = null;
    let opening = null;
    let closed = false;
    const tickets = new WeakSet();
    const plannedTickets = new WeakSet();
    const receipts = new WeakSet();
    const activeReceiptIds = new Set();
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
          request = backend.open(options.databaseName || DATABASE_NAME, DATABASE_VERSION);
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
              store.put({ schemaVersion: SCHEMA_VERSION, epoch: 0, nextSequence: 0, entries: [], plans: [] }, KEY);
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
                    migrateSnapshot(snapshot);
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
      return transact("readonly", (snapshot) => ({ result: success({
        epoch: snapshot.epoch,
        entries: Object.freeze(snapshot.entries.map(displayEntry)),
        plans: Object.freeze(plansOf(snapshot).map((plan) => displayPlan(plan, snapshot.entries))),
      }) }));
    }

    async function createBatchTicket() {
      return transact("readonly", (snapshot) => {
        const batchId = randomId();
        if (!identifier(batchId) || plansOf(snapshot).some((plan) => plan.batchId === batchId)
          || snapshot.entries.some((entry) => entry.batchId === batchId || entry.receiptId === batchId)
          || plannedRows(snapshot).has(batchId)) return { result: failure("invalid-input") };
        const ticket = Object.freeze({ batchId, epoch: snapshot.epoch });
        tickets.add(ticket);
        return { result: success({ ticket }) };
      });
    }

    async function beginContinuationBatch(input = {}) {
      if (!exactFields(input, ["epoch", "rows", "acknowledgeUnknown"]) || !integer(input.epoch)
        || typeof input.acknowledgeUnknown !== "boolean" || !Array.isArray(input.rows)
        || input.rows.length === 0 || input.rows.length > MAX_ENTRIES
        || input.rows.some((row) => !exactFields(row, ["batchId", "rowId", "filename"])
          || !identifier(row.batchId) || !identifier(row.rowId) || typeof row.filename !== "string")) {
        return failure("invalid-input");
      }
      // Copy before awaiting IDB; callers cannot change bindings during admission.
      // Even a queued row can still belong to a running batch in another tab.
      if (!input.acknowledgeUnknown) return failure("unknown-origin");
      const continuation = { epoch: input.epoch, acknowledgeUnknown: input.acknowledgeUnknown,
        rows: input.rows.map((row) => ({ ...row })) };
      return admitPlannedBatch(continuation.rows.map((row) => row.filename), continuation);
    }

    async function beginPlannedBatch(filenames) {
      return admitPlannedBatch(filenames);
    }

    async function admitPlannedBatch(filenames, continuation = null) {
      let admitted = null;
      let persistedPlan = null;
      const result = await transact("readwrite", (snapshot) => {
        if (!Array.isArray(filenames) || filenames.length === 0) {
          return { result: failure("invalid-input") };
        }
        if (filenames.length > MAX_ENTRIES || occupiedRows(snapshot) + filenames.length > MAX_ENTRIES) {
          return { result: failure("capacity-exceeded") };
        }
        for (const name of filenames) {
          if (typeof name !== "string" || name.length > 4096) return { result: failure("invalid-input") };
        }
        if (continuation) {
          if (snapshot.epoch !== continuation.epoch) return { result: failure("stale-ticket") };
          const origins = plannedRows(snapshot);
          const selected = new Set();
          for (const row of continuation.rows) {
            const origin = origins.get(row.rowId);
            if (!origin || origin.batchId !== row.batchId || selected.has(row.rowId)) {
              return { result: failure("invalid-origin") };
            }
            let rootId = row.rowId;
            for (let depth = 0; depth < origins.size && origins.get(rootId)?.row.origin; depth += 1) {
              rootId = origins.get(rootId).row.origin.rowId;
            }
            if (selected.has(rootId)) return { result: failure("duplicate-lineage") };
            selected.add(rootId);
            const lineage = new Set([rootId]);
            // Descendants are bounded by the same 100-row snapshot. A user may
            // select an old ancestor, but that cannot hide a later saved/unknown copy.
            for (let pass = 0; pass < origins.size; pass += 1) {
              let changed = false;
              for (const candidate of origins.values()) {
                if (candidate.row.origin && lineage.has(candidate.row.origin.rowId)
                  && !lineage.has(candidate.row.rowId)) { lineage.add(candidate.row.rowId); changed = true; }
              }
              if (!changed) break;
            }
            const related = snapshot.entries.filter((entry) => lineage.has(entry.receiptId));
            if (related.some((entry) => entry.outcome === "completed")) return { result: failure("completed-origin") };
            if ([...lineage].some((id) => activeReceiptIds.has(id))) return { result: failure("active-origin") };
            if (related.some((entry) => entry.outcome === "in-progress" || entry.outcomeUnknown || entry.mayRemain)
              && !continuation.acknowledgeUnknown) return { result: failure("unknown-origin") };
          }
        }
        const createdAtMillis = now();
        const batchId = randomId();
        const used = new Set(snapshot.entries.flatMap((entry) => [entry.receiptId, entry.batchId]));
        for (const plan of plansOf(snapshot)) {
          used.add(plan.batchId);
          for (const row of plan.rows) used.add(row.rowId);
        }
        if (!timestamp(createdAtMillis) || !identifier(batchId) || used.has(batchId)) {
          return { result: failure("invalid-input") };
        }
        used.add(batchId);
        const rows = [];
        for (const [order, filename] of filenames.entries()) {
          const rowId = randomId();
          if (!identifier(rowId) || used.has(rowId)) return { result: failure("invalid-input") };
          used.add(rowId);
          rows.push({ rowId, order, filename: sanitizePlannedFilename(filename),
            ...(continuation ? { origin: { batchId: continuation.rows[order].batchId,
              rowId: continuation.rows[order].rowId } } : {}) });
        }
        const plan = { batchId, createdAtMillis, rows };
        migrateSnapshot(snapshot);
        snapshot.plans.unshift(plan);
        if (!fitsCapacity(snapshot)) return { result: failure("capacity-exceeded") };
        const ticket = Object.freeze({ batchId, epoch: snapshot.epoch,
          rows: Object.freeze(rows.map((row) => Object.freeze({ ...row }))) });
        tickets.add(ticket);
        plannedTickets.add(ticket);
        persistedPlan = JSON.stringify(plan);
        admitted = success({ ticket, plan: displayPlan(plan, []) });
        return { write: true, result: admitted };
      });
      if (result.ok) return result;
      if (result.code !== "transaction-unconfirmed") return Object.freeze({ ...result, planAbsent: true });
      // A refused abort may mean the write committed before its completion event.
      // Verify the entire unique roster before giving its capability to a caller.
      // Failed verification is never permission to run an unrecorded batch.
      if (!admitted) return Object.freeze({ ...result, planAbsent: false });
      const verified = await transact("readonly", (snapshot) => {
        if (snapshot.epoch !== admitted.ticket.epoch) return { result: Object.freeze({
          ...failure("stale-ticket"), planAbsent: true,
        }) };
        const plan = plansOf(snapshot).find((candidate) => candidate.batchId === admitted.ticket.batchId);
        if (!plan) return { result: Object.freeze({ ...result, planAbsent: true }) };
        if (JSON.stringify(plan) !== persistedPlan) return { result: failure("corrupt") };
        return { result: success({ ticket: admitted.ticket, plan: displayPlan(plan, snapshot.entries) }) };
      });
      return verified.ok || verified.planAbsent === true ? verified : Object.freeze({ ...result, planAbsent: false });
    }

    async function beginPlannedAttempt(ticket, rowId) {
      const result = await transact("readwrite", (snapshot) => {
        if (!plannedTickets.has(ticket)) return { result: failure("invalid-ticket") };
        if (ticket.epoch !== snapshot.epoch) return { result: failure("stale-ticket") };
        const plan = plansOf(snapshot).find((candidate) => candidate.batchId === ticket.batchId);
        const row = plan?.rows.find((candidate) => candidate.rowId === rowId);
        if (!row) return { result: failure("missing-plan-row") };
        if (snapshot.entries.some((entry) => entry.receiptId === rowId)) return { result: failure("already-started") };
        const startedAtMillis = now();
        if (!timestamp(startedAtMillis) || snapshot.nextSequence >= Number.MAX_SAFE_INTEGER) {
          return { result: failure("invalid-input") };
        }
        const receipt = Object.freeze({ receiptId: rowId, batchId: ticket.batchId, epoch: ticket.epoch });
        snapshot.entries.unshift({ receiptId: rowId, batchId: ticket.batchId, sequence: snapshot.nextSequence++,
          filename: row.filename, destination: DESTINATION, startedAtMillis, finishedAtMillis: null,
          outcome: "in-progress", mayRemain: false, outcomeUnknown: false, bytesTransferred: null });
        receipts.add(receipt);
        return { write: true, result: success({ receipt }) };
      });
      if (result.ok) activeReceiptIds.add(result.receipt.receiptId);
      return result;
    }

    async function startAttempt(ticket, input = {}) {
      return transact("readwrite", (snapshot) => {
        if (!tickets.has(ticket) || plannedTickets.has(ticket)) return { result: failure("invalid-ticket") };
        if (ticket.epoch !== snapshot.epoch) return { result: failure("stale-ticket") };
        if (![DESTINATION, ...OBSERVATION_DESTINATIONS].includes(input.destination) || typeof input.filename !== "string"
          || input.filename.length > 4096 || snapshot.nextSequence >= Number.MAX_SAFE_INTEGER) {
          return { result: failure("invalid-input") };
        }
        const receiptId = randomId();
        const startedAtMillis = now();
        if (!identifier(receiptId) || !timestamp(startedAtMillis)
          || snapshot.entries.some((entry) => entry.receiptId === receiptId)
          || plannedRows(snapshot).has(receiptId)
          || plansOf(snapshot).some((plan) => plan.batchId === receiptId)) return { result: failure("invalid-input") };
        const planned = plannedRows(snapshot);
        const receipt = Object.freeze({ receiptId, batchId: ticket.batchId, epoch: ticket.epoch });
        snapshot.entries.unshift({ receiptId, batchId: ticket.batchId, sequence: snapshot.nextSequence++,
          filename: sanitizeFilename(input.filename), destination: input.destination, startedAtMillis,
          finishedAtMillis: null, outcome: "in-progress", mayRemain: false, outcomeUnknown: false, bytesTransferred: null });
        while (!fitsCapacity(snapshot)) {
          const removable = snapshot.entries.findLastIndex((entry) => !planned.has(entry.receiptId) && entry.outcome !== "in-progress");
          if (removable < 0) return { result: failure("capacity-exceeded") };
          snapshot.entries.splice(removable, 1);
        }
        receipts.add(receipt);
        return { write: true, result: success({ receipt }) };
      });
    }

    async function finishAttempt(receipt, input = {}) {
      let outcome, mayRemain, outcomeUnknown, bytesTransferred;
      try {
        ({ outcome, mayRemain = false, outcomeUnknown = false, bytesTransferred = null } = input);
      } catch (_) { return failure("invalid-input"); }
      if (receipts.has(receipt) && DIRECTORY_OUTCOMES.slice(1).includes(outcome)
        && typeof mayRemain === "boolean" && typeof outcomeUnknown === "boolean"
        && (bytesTransferred === null || integer(bytesTransferred))
        && !(outcome === "completed" && (mayRemain || outcomeUnknown))) {
        // The producer has settled even if recording that fact now fails. Keep
        // durable uncertainty, but do not strand a false active lock until reload.
        activeReceiptIds.delete(receipt.receiptId);
      }
      return transact("readwrite", (snapshot) => {
        if (!receipts.has(receipt)) return { result: failure("invalid-receipt") };
        if (receipt.epoch !== snapshot.epoch) return { result: failure("stale-ticket") };
        const entry = snapshot.entries.find((candidate) => candidate.receiptId === receipt.receiptId
          && candidate.batchId === receipt.batchId);
        if (!entry) return { result: failure("missing-receipt") };
        if (entry.outcome !== "in-progress") return { result: success({ updated: false }) };
        const finishedAtMillis = now();
        if (!OUTCOMES.slice(1).includes(outcome) || typeof mayRemain !== "boolean" || typeof outcomeUnknown !== "boolean"
          || !(bytesTransferred === null || integer(bytesTransferred)) || !timestamp(finishedAtMillis)
          || (outcome === "completed" && (mayRemain || outcomeUnknown))
          || !validDestinationOutcome({ ...entry, outcome, mayRemain, outcomeUnknown }, SCHEMA_VERSION)) return { result: failure("invalid-input") };
        activeReceiptIds.delete(receipt.receiptId);
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
        snapshot.plans = [];
        snapshot.schemaVersion = SCHEMA_VERSION;
        return { write: true, result: success() };
      });
    }

    function close() {
      closed = true;
      for (const cancel of Array.from(transactions)) cancel();
      try { db?.close(); } catch (_) { /* Closing history cannot affect a transfer. */ }
      db = null;
    }

    return Object.freeze({ open, list, createBatchTicket, startAttempt, beginPlannedBatch, beginContinuationBatch, beginPlannedAttempt,
      finishAttempt, clear, close });
  }

  return { createHistoryStore, sanitizeFilename, sanitizePlannedFilename, DATABASE_NAME, SCHEMA_VERSION,
    MAX_ENTRIES, MAX_FILENAME_LENGTH, MAX_SNAPSHOT_LENGTH };
});
