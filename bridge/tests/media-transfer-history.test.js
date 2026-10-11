"use strict";

// Deterministic policy/transaction-adapter tests. This fake models the IDB
// request lifetime, transaction serialization and rollback; browser coverage must
// separately exercise the platform implementation, persistence and multiple tabs.
const assert = require("node:assert/strict");
const test = require("node:test");
const { createHistoryStore, sanitizeFilename, sanitizePlannedFilename, MAX_ENTRIES, MAX_SNAPSHOT_LENGTH,
  SCHEMA_VERSION } = require("../open_eos_bridge/static/media-transfer-history.js");

function fakeIndexedDB() {
  const databases = new Map();
  const fault = { put: false, hold: false, blocked: false, openThrows: false, transactionThrows: false,
    holdOpen: false, holdWriteSuccess: false, holdComplete: false };
  const events = [];
  let heldOpen = null;
  let heldRequest = null;
  let heldComplete = null;
  const clone = (value) => value === undefined ? undefined : structuredClone(value);
  const later = (callback) => queueMicrotask(callback);

  function transaction(record, mode) {
    const tx = { pending: 0, finished: false, active: false, objects: null, error: null };
    let completionQueued = false;
    const requests = [];
    const end = (aborted) => {
      if (tx.finished) return;
      tx.finished = true;
      if (!aborted && mode === "readwrite") record.objects = tx.objects;
      events.push(aborted ? "abort" : "commit");
      const dispatch = () => {
        if (aborted) tx.onabort?.();
        else tx.oncomplete?.();
        if (record.active === tx) record.active = null;
        record.queue = record.queue.filter((entry) => entry !== tx);
        record.queue[0]?.start();
      };
      if (!aborted && fault.holdComplete) {
        fault.holdComplete = false;
        heldComplete = dispatch;
      } else later(dispatch);
    };
    const completeWhenIdle = () => {
      if (completionQueued) return;
      completionQueued = true;
      later(() => {
        completionQueued = false;
        if (tx.active && !tx.finished && tx.pending === 0) end(false);
      });
    };
    const request = (operation, isWrite = false) => {
      if (tx.finished) throw new Error("TransactionInactiveError");
      const req = {};
      tx.pending += 1;
      const run = () => {
        if (tx.finished) return;
        const respond = () => {
          if (tx.finished) return;
          if (isWrite && fault.put) {
            fault.put = false;
            req.error = Object.assign(new Error("Do not persist this raw URL: https://private.invalid/?token=secret"), { name: "QuotaExceededError" });
            tx.error = req.error;
            req.onerror?.();
            tx.onerror?.();
            end(true);
            return;
          }
          req.result = operation();
          const deliver = () => {
            if (tx.finished) return;
            tx.pending -= 1;
            req.onsuccess?.();
            completeWhenIdle();
          };
          if (isWrite && fault.holdWriteSuccess) {
            fault.holdWriteSuccess = false;
            events.push("write-staged");
            heldRequest = deliver;
          } else deliver();
        };
        if (fault.hold) {
          fault.hold = false;
          heldRequest = respond;
        } else later(respond);
      };
      if (tx.active) run();
      else requests.push(run);
      return req;
    };
    tx.objectStore = () => ({
      keyPath: null, autoIncrement: false,
      count: () => request(() => tx.objects.size),
      get: (key) => request(() => clone(tx.objects.get(key))),
      put: (value, key) => {
        assert.equal(mode, "readwrite");
        const copy = clone(value);
        return request(() => { tx.objects.set(key, copy); return key; }, true);
      },
    });
    tx.abort = () => {
      if (tx.finished) throw new Error("InvalidStateError");
      end(true);
    };
    tx.start = () => {
      if (tx.finished || record.active) return;
      tx.active = true;
      record.active = tx;
      tx.objects = new Map(Array.from(record.objects, ([key, value]) => [key, clone(value)]));
      for (const run of requests) run();
      completeWhenIdle();
    };
    record.queue.push(tx);
    later(() => record.queue[0]?.start());
    return tx;
  }

  const backend = {
    fault, events,
    open(name, version) {
      if (fault.openThrows) throw new Error("SecurityError");
      const request = {};
      const proceed = () => later(() => {
        let record = databases.get(name);
        if (record && record.version > version) {
          request.error = { name: "VersionError" };
          request.onerror?.();
          return;
        }
        const created = !record;
        if (created) record = { version, names: [], objects: new Map(), queue: [], active: null };
        let isClosed = false;
        const connection = {
          objectStoreNames: { get length() { return record.names.length; }, contains: (key) => record.names.includes(key) },
          createObjectStore(storeName) {
            record.names.push(storeName);
            return { put: (value, key) => record.objects.set(key, clone(value)) };
          },
          transaction() {
            if (isClosed || fault.transactionThrows) throw new Error("InvalidStateError");
            return transaction(record, arguments[1]);
          },
          close() { isClosed = true; },
        };
        request.result = connection;
        if (created) {
          let aborted = false;
          request.transaction = { abort() { aborted = true; } };
          request.onupgradeneeded?.({ oldVersion: 0 });
          if (aborted) {
            request.error = { name: "AbortError" };
            request.onerror?.();
            return;
          }
          databases.set(name, record);
        }
        request.onsuccess?.();
      });
      if (fault.holdOpen) heldOpen = proceed;
      else if (fault.blocked) {
        heldOpen = proceed;
        later(() => request.onblocked?.());
      } else proceed();
      return request;
    },
    persisted(name = "test-history") { return clone(databases.get(name)?.objects.get("state")); },
    replace(snapshot, name = "test-history") { databases.get(name).objects.set("state", clone(snapshot)); },
    extraRecord() { databases.get("test-history").objects.set("unexpected", {}); },
    unsupportedVersion() { databases.get("test-history").version = 2; },
    releaseOpen() { heldOpen?.(); },
    releaseRequest() { heldRequest?.(); },
    releaseComplete() { heldComplete?.(); },
    hasDatabase(name = "test-history") { return databases.has(name); },
  };
  return backend;
}

function fixture(overrides = {}) {
  const indexedDB = fakeIndexedDB();
  let id = 0;
  let time = 1000;
  const options = { indexedDB, databaseName: "test-history", randomId: () => (++id).toString(16).padStart(32, "0"), now: () => time, ...overrides };
  return { indexedDB, store: createHistoryStore(options), second: () => createHistoryStore(options),
    time: (value) => { time = value; } };
}
const start = async (store, ticket, filename = "IMG_0001.JPG") => store.startAttempt(ticket, { filename, destination: "browser-directory" });
const nextTurn = () => new Promise((resolve) => setImmediate(resolve));

for (const [input, expected] of [
  ["C:\\private\\DCIM\\IMG_0001.JPG?token=secret#data", "IMG_0001.JPG"],
  ["/private/DCIM/IMG_0002.JPG", "IMG_0002.JPG"],
  ["https://secret.invalid/camera.JPG?access=secret", "download"],
  ["content://private/camera.JPG", "download"],
  ["blob:private", "download"],
  ["\\\\private\\folder\\photo.JPG", "photo.JPG"],
  ["IMG\u202e_0003\u0000.JPG", "IMG_0003.JPG"],
  ["image.JPG#fragment", "image.JPG"],
  ["secret%3Ftoken%3D123", "download"],
  ["..", "download"], ["   ", "download"], [42, "download"],
  ["x".repeat(4097), "download"], ["a".repeat(121), "a".repeat(120)],
]) test(`display filename strips private locators: ${JSON.stringify(input).slice(0, 65)}`, () => {
  assert.equal(sanitizeFilename(input), expected);
});

test("only actual starts are receipts; durable completion is whitelisted and reloadable", async () => {
  const { store, indexedDB, second, time } = fixture();
  assert.deepEqual(await store.open(), { ok: true });
  const { ticket } = await store.createBatchTicket();
  assert.equal((await store.list()).entries.length, 0, "batch admission does not persist a queue manifest");
  const result = await store.startAttempt(ticket, { filename: "/private/photo.JPG?secret=token", destination: "browser-directory",
    id: "wire-id", url: "https://private.invalid", directoryHandle: { name: "private" }, error: "secret" });
  assert.equal(result.ok, true);
  assert.ok(Object.isFrozen(result.receipt));
  time(1100);
  assert.deepEqual(await store.finishAttempt(result.receipt, { outcome: "completed", bytesTransferred: 3, url: "secret" }), { ok: true, updated: true });
  const { entries } = await second().list();
  assert.deepEqual(entries, [{ receiptId: result.receipt.receiptId, batchId: ticket.batchId, filename: "photo.JPG",
    destination: "browser-directory", startedAtMillis: 1000, finishedAtMillis: 1100, outcome: "completed",
    mayRemain: false, outcomeUnknown: false, bytesTransferred: 3, unconfirmed: false }]);
  const persisted = JSON.stringify(indexedDB.persisted());
  for (const secret of ["private", "secret", "wire-id", "directoryHandle", "url", "error"]) assert.ok(!persisted.includes(secret));
  assert.ok(Object.isFrozen(entries));
  assert.ok(Object.isFrozen(entries[0]));
});

test("cold read projects unfinished as unconfirmed without mutating another tab's live transfer", async () => {
  const { store, indexedDB, second } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  const before = indexedDB.persisted();
  const reader = second();
  const entry = (await reader.list()).entries[0];
  assert.equal(entry.outcome, "cancelled-or-unknown");
  assert.equal(entry.unconfirmed, true);
  assert.equal(entry.outcomeUnknown, true);
  assert.equal(entry.finishedAtMillis, null);
  assert.deepEqual(indexedDB.persisted(), before);
  assert.equal((await store.finishAttempt(receipt, { outcome: "completed", bytesTransferred: 0 })).ok, true);
  assert.equal((await reader.list()).entries[0].outcome, "completed");
});

test("multi-tab concurrent admissions and completions never lose committed receipts", async () => {
  const { store, second } = fixture();
  const other = second();
  const [firstTicket, otherTicket] = await Promise.all([store.createBatchTicket(), other.createBatchTicket()]);
  const [first, secondReceipt] = await Promise.all([start(store, firstTicket.ticket, "first.JPG"), start(other, otherTicket.ticket, "second.JPG")]);
  assert.ok(first.ok && secondReceipt.ok);
  const finished = await Promise.all([
    store.finishAttempt(first.receipt, { outcome: "completed", bytesTransferred: 20 }),
    other.finishAttempt(secondReceipt.receipt, { outcome: "failed", mayRemain: true }),
  ]);
  assert.ok(finished.every((entry) => entry.ok));
  assert.deepEqual((await store.list()).entries.map((entry) => [entry.filename, entry.outcome]), [["second.JPG", "failed"], ["first.JPG", "completed"]]);
});

test("cross-tab clear is atomic and old batch starts and finishes cannot restore entries", async () => {
  const { store, second, indexedDB } = fixture();
  const other = second();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  await other.open();
  const before = indexedDB.persisted().epoch;
  const [cleared, lateStart, lateFinish] = await Promise.all([
    other.clear(), start(store, ticket, "late.JPG"), store.finishAttempt(receipt, { outcome: "completed" }),
  ]);
  assert.deepEqual(cleared, { ok: true });
  assert.deepEqual(lateStart, { ok: false, code: "stale-ticket" });
  assert.deepEqual(lateFinish, { ok: false, code: "stale-ticket" });
  assert.equal(indexedDB.persisted().epoch, before + 1);
  assert.deepEqual((await store.list()).entries, []);
  const fresh = await other.createBatchTicket();
  assert.equal((await start(other, fresh.ticket, "fresh.JPG")).ok, true);
  assert.deepEqual((await store.list()).entries.map((entry) => entry.filename), ["fresh.JPG"]);
});

test("clear failure rolls back its epoch and leaves existing batch usable", async () => {
  const { store, indexedDB } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  const before = indexedDB.persisted();
  indexedDB.fault.put = true;
  assert.deepEqual(await store.clear(), { ok: false, code: "transaction-failed" });
  assert.deepEqual(indexedDB.persisted(), before);
  assert.equal((await store.finishAttempt(receipt, { outcome: "completed" })).ok, true);
  assert.equal((await start(store, ticket, "later.JPG")).ok, true);
});

test("latest 100 actual starts safely trim terminal unplanned records; evicted completion cannot recreate", async () => {
  const { store, time } = fixture();
  const { ticket } = await store.createBatchTicket();
  const first = await start(store, ticket, "evicted.JPG");
  await store.finishAttempt(first.receipt, { outcome: "completed" });
  for (let i = 0; i < MAX_ENTRIES; i += 1) {
    time(999 - i);
    const next = await start(store, ticket, `IMG_${i}.JPG`);
    assert.equal(next.ok, true);
    await store.finishAttempt(next.receipt, { outcome: "completed" });
  }
  const entries = (await store.list()).entries;
  assert.equal(entries.length, MAX_ENTRIES);
  assert.equal(entries[0].filename, "IMG_99.JPG");
  assert.equal(entries.at(-1).filename, "IMG_0.JPG");
  assert.deepEqual(await store.finishAttempt(first.receipt, { outcome: "completed" }), { ok: false, code: "missing-receipt" });
  assert.equal((await store.list()).entries.length, MAX_ENTRIES);
});

test("first terminal result wins even if a later cancellation or duplicate completion arrives", async () => {
  const { store, time } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  time(500);
  assert.deepEqual(await store.finishAttempt(receipt, { outcome: "completed", bytesTransferred: 123 }), { ok: true, updated: true });
  assert.deepEqual(await store.finishAttempt(receipt, { outcome: "cancelled-or-unknown", mayRemain: true, outcomeUnknown: true }), { ok: true, updated: false });
  const entry = (await store.list()).entries[0];
  assert.equal(entry.outcome, "completed");
  assert.equal(entry.finishedAtMillis, 1000, "clock rollback cannot put finish before admission");
  assert.equal(entry.bytesTransferred, 123);
});

test("unknown bytes stay unknown, and uncertainty plus possible leftovers survive cold read", async () => {
  const { store, second } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  assert.equal((await store.finishAttempt(receipt, { outcome: "cancelled-or-unknown", mayRemain: true, outcomeUnknown: true })).ok, true);
  const entry = (await second().list()).entries[0];
  assert.equal(entry.bytesTransferred, null);
  assert.equal(entry.mayRemain, true);
  assert.equal(entry.outcomeUnknown, true);
  assert.equal(entry.unconfirmed, false, "terminal unknown is distinct from a cold unfinished receipt");
});

test("foreign, forged or stale receipt objects cannot finish or start work", async () => {
  const { store, second } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  assert.deepEqual(await start(store, { ...ticket }), { ok: false, code: "invalid-ticket" });
  assert.deepEqual(await start(second(), ticket), { ok: false, code: "invalid-ticket" });
  assert.deepEqual(await store.finishAttempt({ ...receipt }, { outcome: "completed" }), { ok: false, code: "invalid-receipt" });
  assert.deepEqual(await second().finishAttempt(receipt, { outcome: "completed" }), { ok: false, code: "invalid-receipt" });
});

test("invalid terminal inputs do not overwrite a pending record or leak exception text", async () => {
  const { store, indexedDB } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  const before = indexedDB.persisted();
  for (const input of [
    { outcome: "pending" }, { outcome: "completed", outcomeUnknown: true }, { outcome: "completed", mayRemain: true },
    { outcome: "failed", mayRemain: "true" }, ...[-1, Infinity, NaN, "3", Number.MAX_SAFE_INTEGER + 1].map((bytesTransferred) => ({ outcome: "completed", bytesTransferred })),
    { get outcome() { throw new Error("secret raw error"); } }, null,
  ]) {
    assert.deepEqual(await store.finishAttempt(receipt, input), { ok: false, code: "invalid-input" });
    assert.deepEqual(indexedDB.persisted(), before);
  }
});

test("corrupt, excessive and unknown-version data is preserved, never repaired or silently reset", async () => {
  const { store, indexedDB } = fixture();
  const { ticket } = await store.createBatchTicket();
  await start(store, ticket);
  const valid = indexedDB.persisted();
  const corruptions = [
    { ...valid, schemaVersion: SCHEMA_VERSION + 1 }, { ...valid, extra: "secret" }, { ...valid, epoch: -1 },
    { ...valid, entries: Array(101).fill(valid.entries[0]) },
    ...[
      { filename: "https://private.invalid" }, { url: "https://private.invalid" },
      { receiptId: "wire-id" }, { bytesTransferred: "123" }, { finishedAtMillis: 1000 },
      { filename: "x".repeat(121) }, { sequence: Number.MAX_SAFE_INTEGER },
      { startedAtMillis: 8640000000000001 },
      { outcome: "completed", finishedAtMillis: 8640000000000001 },
      { outcome: "completed", finishedAtMillis: 500 }, { destination: "unknown" },
    ].map((changed) => ({ ...valid, entries: [{ ...valid.entries[0], ...changed }] })),
  ];
  for (const snapshot of corruptions) {
    indexedDB.replace(snapshot);
    const code = snapshot.schemaVersion === SCHEMA_VERSION + 1 ? "unsupported-schema" : "corrupt";
    assert.deepEqual(await store.list(), { ok: false, code });
    assert.deepEqual(await store.clear(), { ok: false, code });
    assert.deepEqual(await start(store, ticket), { ok: false, code });
    assert.deepEqual(indexedDB.persisted(), snapshot);
  }
  indexedDB.replace(valid);
  indexedDB.extraRecord();
  assert.deepEqual(await store.clear(), { ok: false, code: "corrupt" });
  assert.deepEqual(indexedDB.persisted(), valid);
});

test("write quota failure returns a warning with no partial snapshot and does not prevent a later receipt", async () => {
  const { store, indexedDB } = fixture();
  const { ticket } = await store.createBatchTicket();
  indexedDB.fault.put = true;
  assert.deepEqual(await start(store, ticket), { ok: false, code: "transaction-failed" });
  assert.deepEqual((await store.list()).entries, []);
  const { receipt } = await start(store, ticket, "next.JPG");
  indexedDB.fault.put = true;
  assert.deepEqual(await store.finishAttempt(receipt, { outcome: "completed" }), { ok: false, code: "transaction-failed" });
  assert.equal((await store.list()).entries[0].unconfirmed, true);
});

test("timeout aborts its own pending transaction and a late request cannot write or publish success", async () => {
  const { store, indexedDB } = fixture({ timeoutMs: 5 });
  const { ticket } = await store.createBatchTicket();
  const before = indexedDB.persisted();
  indexedDB.fault.hold = true;
  const result = await start(store, ticket);
  assert.deepEqual(result, { ok: false, code: "timeout" });
  indexedDB.releaseRequest();
  await nextTurn();
  assert.deepEqual(indexedDB.persisted(), before);
  assert.equal((await start(store, ticket, "next.JPG")).ok, true);
});

test("closing aborts admitted history transactions without deleting persisted receipts", async () => {
  const { store, indexedDB, second } = fixture();
  const { ticket } = await store.createBatchTicket();
  await start(store, ticket);
  indexedDB.fault.hold = true;
  const attempt = start(store, ticket, "late.JPG");
  await nextTurn();
  store.close();
  assert.deepEqual(await attempt, { ok: false, code: "closed" });
  indexedDB.releaseRequest();
  await nextTurn();
  assert.deepEqual(await store.list(), { ok: false, code: "closed" });
  assert.equal((await second().list()).entries.length, 1);
});

test("timeout rolls back a write already staged in its transaction", async () => {
  const { store, indexedDB } = fixture({ timeoutMs: 5 });
  const { ticket } = await store.createBatchTicket();
  const before = indexedDB.persisted();
  indexedDB.fault.holdWriteSuccess = true;
  const result = await start(store, ticket);
  assert.ok(indexedDB.events.includes("write-staged"));
  assert.deepEqual(result, { ok: false, code: "timeout" });
  assert.deepEqual(indexedDB.persisted(), before);
  indexedDB.releaseRequest();
  await nextTurn();
  assert.deepEqual(indexedDB.persisted(), before);
});

test("timeout racing an already committed transaction reports uncertainty and resolves only once", async () => {
  const { store, indexedDB } = fixture({ timeoutMs: 5 });
  const { ticket } = await store.createBatchTicket();
  indexedDB.fault.holdComplete = true;
  const result = await start(store, ticket);
  assert.deepEqual(result, { ok: false, code: "transaction-unconfirmed" });
  assert.equal(indexedDB.persisted().entries.length, 1, "abort was refused after commit, so rollback cannot be claimed");
  indexedDB.releaseComplete();
  await nextTurn();
  assert.deepEqual(result, { ok: false, code: "transaction-unconfirmed" });
  assert.equal((await store.list()).entries[0].unconfirmed, true);
});

test("open timeout cannot create a database when the request resumes later", async () => {
  const { store, indexedDB } = fixture({ timeoutMs: 5 });
  indexedDB.fault.holdOpen = true;
  assert.deepEqual(await store.open(), { ok: false, code: "timeout" });
  indexedDB.releaseOpen();
  await nextTurn();
  assert.equal(indexedDB.hasDatabase(), false);
});

test("unavailable, denied, blocked and future-version databases return bounded explicit errors", async () => {
  assert.deepEqual(await createHistoryStore({ indexedDB: null }).open(), { ok: false, code: "unavailable" });
  const denied = fixture();
  denied.indexedDB.fault.openThrows = true;
  assert.deepEqual(await denied.store.list(), { ok: false, code: "unavailable" });
  const blocked = fixture();
  blocked.indexedDB.fault.blocked = true;
  assert.deepEqual(await blocked.store.open(), { ok: false, code: "blocked" });
  blocked.indexedDB.releaseOpen();
  await nextTurn();
  assert.equal(blocked.indexedDB.hasDatabase(), false, "a blocked request that resumes later must abort its upgrade");
  const future = fixture();
  await future.store.open();
  future.indexedDB.unsupportedVersion();
  assert.deepEqual(await future.second().open(), { ok: false, code: "unsupported-schema" });
  future.indexedDB.fault.transactionThrows = true;
  assert.deepEqual(await future.store.list(), { ok: false, code: "transaction-failed" });
});

test("invalid random IDs, exhausted counters and hostile clock produce explicit errors without writes", async () => {
  const invalidId = fixture({ randomId: () => "camera-wire-id" });
  assert.deepEqual(await invalidId.store.createBatchTicket(), { ok: false, code: "invalid-input" });
  const { store, indexedDB, time } = fixture();
  const { ticket } = await store.createBatchTicket();
  for (const value of [-1, NaN, Infinity, "1000", 8640000000000001, Number.MAX_SAFE_INTEGER]) {
    time(value);
    assert.deepEqual(await start(store, ticket), { ok: false, code: "invalid-input" });
  }
  time(1000);
  const snapshot = indexedDB.persisted();
  indexedDB.replace({ ...snapshot, nextSequence: Number.MAX_SAFE_INTEGER });
  assert.deepEqual(await start(store, ticket), { ok: false, code: "invalid-input" });
  indexedDB.replace({ ...snapshot, epoch: Number.MAX_SAFE_INTEGER });
  assert.deepEqual(await store.clear(), { ok: false, code: "epoch-exhausted" });
});

test("receipt timestamps remain renderable throughout the valid JavaScript Date domain", async () => {
  const { store, indexedDB, time } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket);
  const before = indexedDB.persisted();
  for (const invalidTime of [-1, 8640000000000001, Number.MAX_SAFE_INTEGER]) {
    time(invalidTime);
    assert.deepEqual(await store.finishAttempt(receipt, { outcome: "completed" }), { ok: false, code: "invalid-input" });
    assert.deepEqual(indexedDB.persisted(), before);
  }
  time(8640000000000000);
  assert.equal((await store.finishAttempt(receipt, { outcome: "completed" })).ok, true);
  assert.equal((await start(store, ticket, "maximum-date.JPG")).ok, true);
  const { entries } = await store.list();
  for (const entry of entries) {
    assert.doesNotThrow(() => new Date(entry.startedAtMillis).toISOString());
    if (entry.finishedAtMillis !== null) assert.doesNotThrow(() => new Date(entry.finishedAtMillis).toISOString());
  }
});

test("a 50-row selected batch survives interruption as five completed, one unknown and 44 queued", async () => {
  const { store, second, indexedDB } = fixture();
  const names = Array.from({ length: 50 }, (_, index) => `IMG_${index}.JPG`);
  const batch = await store.beginPlannedBatch(names);
  assert.equal(batch.ok, true);
  assert.equal(batch.ticket.rows.length, 50);
  assert.equal((await store.list()).entries.length, 0, "persisting the selection is not a claim that any original started");
  assert.deepEqual(indexedDB.persisted().plans[0].rows.map((row) => row.filename), names);
  for (const row of batch.ticket.rows.slice(0, 5)) {
    const begun = await store.beginPlannedAttempt(batch.ticket, row.rowId);
    assert.equal(begun.receipt.receiptId, row.rowId);
    assert.equal((await store.finishAttempt(begun.receipt, { outcome: "completed", bytesTransferred: 7 })).ok, true);
  }
  await store.beginPlannedAttempt(batch.ticket, batch.ticket.rows[5].rowId);
  const persistedBeforeRead = indexedDB.persisted();
  const reader = second();
  const cold = await reader.list();
  const rows = cold.plans[0].rows;
  assert.deepEqual(rows.map((row) => row.filename), names);
  assert.deepEqual(rows.map((row) => row.order), Array.from({ length: 50 }, (_, index) => index));
  assert.deepEqual(rows.map((row) => row.outcome), [
    ...Array(5).fill("completed"), "cancelled-or-unknown", ...Array(44).fill("queued"),
  ]);
  assert.equal(cold.entries.length, 6, "compatibility receipts contain only actual starts");
  assert.equal(rows[5].receipt.unconfirmed, true);
  assert.equal(rows[5].receipt.outcomeUnknown, true);
  assert.equal(rows[5].receipt.finishedAtMillis, null);
  assert.ok(rows.slice(6).every((row) => row.receipt === null));
  assert.deepEqual(indexedDB.persisted(), persistedBeforeRead, "cold projection never rewrites the active tab's records");
  assert.ok(Object.isFrozen(cold.plans) && Object.isFrozen(cold.plans[0]) && Object.isFrozen(rows) && Object.isFrozen(rows[0]));
  assert.ok(Object.isFrozen(batch.ticket) && Object.isFrozen(batch.ticket.rows) && Object.isFrozen(batch.ticket.rows[0]));
  assert.deepEqual(await reader.beginPlannedAttempt(batch.ticket, rows[6].rowId), { ok: false, code: "invalid-ticket" });
  assert.deepEqual(await store.beginPlannedAttempt({ ...batch.ticket }, rows[6].rowId), { ok: false, code: "invalid-ticket" });
  assert.deepEqual(await start(store, batch.ticket), { ok: false, code: "invalid-ticket" });
});

test("full plan commit is an all-or-nothing gate before the caller can open an original or destination", async () => {
  const { store, indexedDB } = fixture();
  await store.open();
  const before = indexedDB.persisted();
  let originalRequests = 0;
  let destinationOpens = 0;
  indexedDB.fault.put = true;
  const result = await store.beginPlannedBatch(["one.JPG", "two.JPG", "three.JPG"]);
  if (result.ok) {
    originalRequests += 1;
    destinationOpens += 1;
  }
  assert.deepEqual(result, { ok: false, code: "transaction-failed", planAbsent: true });
  assert.equal(originalRequests, 0);
  assert.equal(destinationOpens, 0);
  assert.deepEqual(indexedDB.persisted(), before);
  const successful = await store.beginPlannedBatch(["one.JPG", "two.JPG", "three.JPG"]);
  assert.equal(successful.ok, true);
  assert.equal(indexedDB.persisted().plans[0].rows.length, 3);
  assert.equal(indexedDB.persisted().entries.length, 0);
});

test("planned attempt failure leaves the row queued, and terminal write failure leaves it unknown", async () => {
  const { store, indexedDB, second } = fixture();
  const { ticket } = await store.beginPlannedBatch(["same.JPG", "same.JPG"]);
  const rowId = ticket.rows[0].rowId;
  const before = indexedDB.persisted();
  indexedDB.fault.put = true;
  assert.deepEqual(await store.beginPlannedAttempt(ticket, rowId), { ok: false, code: "transaction-failed" });
  assert.deepEqual(indexedDB.persisted(), before);
  assert.equal((await second().list()).plans[0].rows[0].outcome, "queued");
  const { receipt } = await store.beginPlannedAttempt(ticket, rowId);
  assert.equal(receipt.receiptId, rowId);
  indexedDB.fault.put = true;
  assert.deepEqual(await store.finishAttempt(receipt, { outcome: "completed" }), { ok: false, code: "transaction-failed" });
  assert.equal((await second().list()).plans[0].rows[0].outcome, "cancelled-or-unknown");
  assert.deepEqual(await store.beginPlannedAttempt(ticket, rowId), { ok: false, code: "already-started" });
  assert.equal((await store.finishAttempt(receipt, { outcome: "completed", bytesTransferred: 4 })).ok, true);
  const rows = (await second().list()).plans[0].rows;
  assert.equal(rows[0].outcome, "completed");
  assert.equal(rows[1].outcome, "queued", "equal filenames are separate rows, never implicit matches");
  assert.notEqual(rows[0].rowId, rows[1].rowId);
});

test("full-plan admission never truncates the new roster or evicts existing records", async () => {
  const { store, indexedDB } = fixture();
  const { ticket } = await store.createBatchTicket();
  const { receipt } = await start(store, ticket, "existing.JPG");
  await store.finishAttempt(receipt, { outcome: "completed" });
  const before = indexedDB.persisted();
  assert.deepEqual(await store.beginPlannedBatch(Array(100).fill("photo.JPG")), {
    ok: false, code: "capacity-exceeded", planAbsent: true,
  });
  assert.deepEqual(indexedDB.persisted(), before);
  assert.deepEqual(await store.beginPlannedBatch(Array(101).fill("photo.JPG")), {
    ok: false, code: "capacity-exceeded", planAbsent: true,
  });
  assert.deepEqual(indexedDB.persisted(), before);
  assert.equal((await store.beginPlannedBatch(Array(99).fill("photo.JPG"))).ok, true);
  assert.equal((await store.list()).entries[0].receiptId, receipt.receiptId);
});

test("shared capacity protects every plan and live dynamic receipt while trimming only terminal dynamic history", async () => {
  const { store, indexedDB } = fixture();
  const planned = await store.beginPlannedBatch(Array(98).fill("planned.JPG"));
  const { ticket } = await store.createBatchTicket();
  const first = await start(store, ticket, "live-one.JPG");
  const second = await start(store, ticket, "live-two.JPG");
  const before = indexedDB.persisted();
  assert.deepEqual(await start(store, ticket, "cannot-fit.JPG"), { ok: false, code: "capacity-exceeded" });
  assert.deepEqual(indexedDB.persisted(), before);
  const plannedReceipt = await store.beginPlannedAttempt(planned.ticket, planned.ticket.rows[0].rowId);
  assert.equal(plannedReceipt.ok, true, "an admitted planned row has already reserved its capacity");
  await store.finishAttempt(plannedReceipt.receipt, { outcome: "completed" });
  await store.finishAttempt(first.receipt, { outcome: "failed", mayRemain: true });
  for (let index = 0; index < 105; index += 1) {
    const dynamic = await start(store, ticket, `new-${index}.JPG`);
    assert.equal(dynamic.ok, true);
    await store.finishAttempt(dynamic.receipt, { outcome: "completed" });
  }
  const latest = await store.list();
  assert.equal(latest.plans[0].rows.length, 98);
  assert.equal(latest.plans[0].rows[0].outcome, "completed", "dynamic rolling can never turn a completed planned row back into queued");
  assert.equal(latest.plans[0].rows.filter((row) => row.outcome === "queued").length, 97);
  assert.equal(latest.entries.length, 3);
  assert.ok(latest.entries.some((entry) => entry.receiptId === second.receipt.receiptId));
  assert.equal((await store.finishAttempt(second.receipt, { outcome: "completed" })).ok, true);
});

test("100 planned rows admit all of their receipts without another slot, and reject any dynamic addition", async () => {
  const { store, indexedDB } = fixture();
  const { ticket } = await store.beginPlannedBatch(Array.from({ length: 100 }, (_, index) => `${index}.JPG`));
  for (const row of ticket.rows) {
    const { receipt } = await store.beginPlannedAttempt(ticket, row.rowId);
    assert.ok(receipt);
    await store.finishAttempt(receipt, { outcome: "completed" });
  }
  const before = indexedDB.persisted();
  const dynamic = await store.createBatchTicket();
  assert.deepEqual(await start(store, dynamic.ticket), { ok: false, code: "capacity-exceeded" });
  assert.deepEqual(indexedDB.persisted(), before);
  assert.ok((await store.list()).plans[0].rows.every((row) => row.outcome === "completed"));
});

test("concurrent plan admissions use one atomic shared capacity calculation", async () => {
  const { store, second, indexedDB } = fixture();
  const other = second();
  const results = await Promise.all([
    store.beginPlannedBatch(Array(60).fill("one.JPG")),
    other.beginPlannedBatch(Array(50).fill("two.JPG")),
  ]);
  assert.equal(results.filter((result) => result.ok).length, 1);
  assert.deepEqual(results.find((result) => !result.ok), { ok: false, code: "capacity-exceeded", planAbsent: true });
  assert.equal(indexedDB.persisted().plans.length, 1);
  assert.equal(indexedDB.persisted().plans[0].rows.length, results.find((result) => result.ok).ticket.rows.length);
});

test("cross-tab clear removes plans and receipts in one epoch and old capabilities cannot resurrect either", async () => {
  const { store, second, indexedDB } = fixture();
  const other = second();
  const { ticket } = await store.beginPlannedBatch(["finished.JPG", "active.JPG", "queued.JPG"]);
  const completed = await store.beginPlannedAttempt(ticket, ticket.rows[0].rowId);
  await store.finishAttempt(completed.receipt, { outcome: "completed" });
  const active = await store.beginPlannedAttempt(ticket, ticket.rows[1].rowId);
  const before = indexedDB.persisted();
  await other.open();
  const [cleared, lateStart, lateFinish] = await Promise.all([
    other.clear(), store.beginPlannedAttempt(ticket, ticket.rows[2].rowId),
    store.finishAttempt(active.receipt, { outcome: "completed" }),
  ]);
  assert.deepEqual(cleared, { ok: true });
  assert.deepEqual(lateStart, { ok: false, code: "stale-ticket" });
  assert.deepEqual(lateFinish, { ok: false, code: "stale-ticket" });
  assert.equal(indexedDB.persisted().epoch, before.epoch + 1);
  assert.deepEqual(await store.list(), { ok: true, entries: [], plans: [] });
  assert.equal((await other.beginPlannedBatch(["new.JPG"])).ok, true);
  assert.deepEqual(await store.beginPlannedAttempt(ticket, ticket.rows[2].rowId), { ok: false, code: "stale-ticket" });
  assert.equal((await store.list()).plans.length, 1);
});

test("failed clear leaves the exact plan, receipts and epoch intact and the plan remains usable", async () => {
  const { store, second, indexedDB } = fixture();
  const { ticket } = await store.beginPlannedBatch(["active.JPG", "queued.JPG"]);
  const { receipt } = await store.beginPlannedAttempt(ticket, ticket.rows[0].rowId);
  const before = indexedDB.persisted();
  indexedDB.fault.put = true;
  assert.deepEqual(await second().clear(), { ok: false, code: "transaction-failed" });
  assert.deepEqual(indexedDB.persisted(), before);
  assert.equal((await store.finishAttempt(receipt, { outcome: "completed" })).ok, true);
  assert.equal((await store.beginPlannedAttempt(ticket, ticket.rows[1].rowId)).ok, true);
});

test("v1 receipts remain readable without an invented roster and migrate atomically without losing history", async () => {
  const { store, indexedDB, second } = fixture();
  const dynamic = await store.createBatchTicket();
  const { receipt } = await start(store, dynamic.ticket, "legacy.JPG");
  await store.finishAttempt(receipt, { outcome: "completed", bytesTransferred: 42 });
  const { plans: _plans, ...persisted } = indexedDB.persisted();
  const legacy = { ...persisted, schemaVersion: 1 };
  indexedDB.replace(legacy);
  const reader = second();
  assert.equal((await reader.open()).ok, true);
  const old = await reader.list();
  assert.equal(old.entries.length, 1);
  assert.deepEqual(old.plans, []);
  assert.deepEqual(indexedDB.persisted(), legacy, "readonly access does not claim a new schema or selected roster");
  indexedDB.fault.put = true;
  assert.deepEqual(await reader.beginPlannedBatch(["new.JPG"]), { ok: false, code: "transaction-failed", planAbsent: true });
  assert.deepEqual(indexedDB.persisted(), legacy, "failed migration and admission roll back together");
  assert.equal((await reader.beginPlannedBatch(["new.JPG"])).ok, true);
  const migrated = indexedDB.persisted();
  assert.equal(migrated.schemaVersion, SCHEMA_VERSION);
  assert.deepEqual(migrated.entries, legacy.entries);
  assert.equal(migrated.epoch, legacy.epoch);
  assert.equal(migrated.plans.length, 1);
  assert.equal((await store.list()).entries[0].bytesTransferred, 42);
});

test("new plan labels exclude locators and query secrets, while duplicate labels keep separate local identities", async () => {
  const { store, indexedDB } = fixture();
  const input = ["https://private.invalid/secret.JPG?token=abc#hidden", "foo.JPG?token=abc#hidden",
    "/private/foo.JPG", "C:\\private\\foo.JPG", "content://private/secret", "secret%3Ftoken%3Dabc", "foo.JPG"];
  const { ticket } = await store.beginPlannedBatch(input);
  assert.deepEqual(ticket.rows.map((row) => row.filename), ["download", "foo.JPG", "foo.JPG", "foo.JPG", "download", "download", "foo.JPG"]);
  assert.equal(new Set(ticket.rows.map((row) => row.rowId)).size, input.length);
  assert.equal(sanitizePlannedFilename("foo.JPG?token=secret"), "foo.JPG");
  const serialized = JSON.stringify(indexedDB.persisted());
  for (const secret of ["private", "token", "abc", "hidden", "secret", "https", "content", "directoryHandle", "serial", "session"]) {
    assert.ok(!serialized.includes(secret), secret);
  }
  for (const row of ticket.rows) {
    const { receipt } = await store.beginPlannedAttempt(ticket, row.rowId);
    assert.equal(receipt.receiptId, row.rowId);
  }
});

test("malformed or hostile full plans are preserved and never projected as trustworthy queued rows", async () => {
  const { store, indexedDB } = fixture();
  const { ticket } = await store.beginPlannedBatch(["first.JPG", "second.JPG"]);
  await store.beginPlannedAttempt(ticket, ticket.rows[0].rowId);
  const valid = indexedDB.persisted();
  const plan = valid.plans[0];
  const corruptions = [
    { ...valid, plans: null }, { ...valid, plans: [{ ...plan, rows: [] }] },
    { ...valid, plans: [{ ...plan, session: "secret" }] },
    { ...valid, plans: [{ ...plan, createdAtMillis: -1 }] },
    { ...valid, plans: [{ ...plan, rows: [plan.rows[1], plan.rows[0]] }] },
    { ...valid, plans: [{ ...plan, rows: [plan.rows[0], { ...plan.rows[1], rowId: plan.rows[0].rowId }] }] },
    { ...valid, plans: [{ ...plan, rows: [plan.rows[0], { ...plan.rows[1], rowId: plan.batchId }] }] },
    { ...valid, plans: [{ ...plan, rows: [plan.rows[0], { ...plan.rows[1], url: "https://secret.invalid" }] }] },
    { ...valid, plans: [{ ...plan, rows: [plan.rows[0], { ...plan.rows[1], filename: "image.JPG?token=secret" }] }] },
    { ...valid, plans: [plan, plan] },
    { ...valid, entries: [{ ...valid.entries[0], filename: "mismatch.JPG" }] },
    { ...valid, entries: [{ ...valid.entries[0], receiptId: "f".repeat(32) }] },
    { ...valid, entries: [{ ...valid.entries[0], batchId: "f".repeat(32) }] },
    { ...valid, schemaVersion: 1 },
  ];
  for (const snapshot of corruptions) {
    indexedDB.replace(snapshot);
    assert.deepEqual(await store.list(), { ok: false, code: "corrupt" });
    assert.deepEqual(await store.clear(), { ok: false, code: "corrupt" });
    assert.deepEqual(await store.beginPlannedAttempt(ticket, ticket.rows[1].rowId), { ok: false, code: "corrupt" });
    assert.deepEqual(await store.beginPlannedBatch(["new.JPG"]), { ok: false, code: "corrupt", planAbsent: true });
    assert.deepEqual(indexedDB.persisted(), snapshot);
  }
});

test("invalid batch inputs and colliding local IDs never persist a partial roster", async () => {
  const { store, indexedDB } = fixture();
  await store.open();
  const before = indexedDB.persisted();
  for (const names of [null, [], "photo.JPG", [null], ["x".repeat(4097)], new Array(2), [{ filename: "photo.JPG", url: "secret" }]]) {
    assert.deepEqual(await store.beginPlannedBatch(names), { ok: false, code: "invalid-input", planAbsent: true });
    assert.deepEqual(indexedDB.persisted(), before);
  }
  const hostileNames = ["photo.JPG"];
  Object.defineProperty(hostileNames, "0", { get() { throw new Error("secret private address"); } });
  assert.deepEqual(await store.beginPlannedBatch(hostileNames), { ok: false, code: "invalid-input", planAbsent: true });
  assert.deepEqual(indexedDB.persisted(), before);
  const collisions = fixture({ randomId: () => "a".repeat(32) });
  assert.deepEqual(await collisions.store.beginPlannedBatch(["photo.JPG"]), { ok: false, code: "invalid-input", planAbsent: true });
  assert.equal(collisions.indexedDB.persisted().plans.length, 0);
});

test("encoded capacity reserves future receipts and bounds multibyte plans before admission", async () => {
  const { store, indexedDB, time } = fixture();
  await store.open();
  const before = indexedDB.persisted();
  assert.deepEqual(await store.beginPlannedBatch(Array(100).fill("界".repeat(120))), {
    ok: false, code: "capacity-exceeded", planAbsent: true,
  });
  assert.deepEqual(indexedDB.persisted(), before);
  const admitted = await store.beginPlannedBatch(Array(50).fill("界".repeat(120)));
  assert.equal(admitted.ok, true);
  time(8640000000000000);
  for (const row of admitted.ticket.rows) {
    const { receipt } = await store.beginPlannedAttempt(admitted.ticket, row.rowId);
    assert.ok(receipt);
    assert.equal((await store.finishAttempt(receipt, {
      outcome: "cancelled-or-unknown", mayRemain: false, outcomeUnknown: false, bytesTransferred: Number.MAX_SAFE_INTEGER,
    })).ok, true);
  }
  assert.ok(Buffer.byteLength(JSON.stringify(indexedDB.persisted()), "utf8") <= MAX_SNAPSHOT_LENGTH);
});

test("a timeout that confirms rollback reports the plan absent and late writes cannot resurrect it", async () => {
  const { store, indexedDB } = fixture({ timeoutMs: 5 });
  await store.open();
  const before = indexedDB.persisted();
  indexedDB.fault.holdWriteSuccess = true;
  assert.deepEqual(await store.beginPlannedBatch(["one.JPG", "two.JPG"]), { ok: false, code: "timeout", planAbsent: true });
  assert.deepEqual(indexedDB.persisted(), before);
  indexedDB.releaseRequest();
  await nextTurn();
  assert.deepEqual(indexedDB.persisted(), before);
});

test("uncertain plan commit never reports absent when readonly verification also cannot finish", async () => {
  const { store, indexedDB } = fixture({ timeoutMs: 5 });
  await store.open();
  indexedDB.fault.holdComplete = true;
  const result = await store.beginPlannedBatch(["one.JPG", "two.JPG"]);
  assert.deepEqual(result, { ok: false, code: "transaction-unconfirmed", planAbsent: false });
  assert.equal(indexedDB.persisted().plans[0].rows.length, 2);
  indexedDB.releaseComplete();
  await nextTurn();
  assert.equal((await store.list()).plans[0].rows.length, 2);
});

test("uncertain commit can publish a ticket only after the whole persisted plan is verified", async () => {
  const { store, indexedDB } = fixture({ timeoutMs: 20 });
  await store.open();
  indexedDB.fault.holdComplete = true;
  const pending = store.beginPlannedBatch(["one.JPG", "two.JPG"]);
  const release = setTimeout(() => indexedDB.releaseComplete(), 25);
  const result = await pending;
  clearTimeout(release);
  assert.equal(result.ok, true);
  assert.deepEqual(result.ticket.rows.map((row) => row.filename), ["one.JPG", "two.JPG"]);
  assert.equal((await store.beginPlannedAttempt(result.ticket, result.ticket.rows[0].rowId)).ok, true);
});

test("storage errors are never classified as a clear epoch and absent backend is safe to label plan absent", async () => {
  assert.deepEqual(await createHistoryStore({ indexedDB: null }).beginPlannedBatch(["one.JPG"]), {
    ok: false, code: "unavailable", planAbsent: true,
  });
  const { store, indexedDB } = fixture();
  const { ticket } = await store.beginPlannedBatch(["one.JPG"]);
  indexedDB.fault.transactionThrows = true;
  assert.deepEqual(await store.beginPlannedAttempt(ticket, ticket.rows[0].rowId), { ok: false, code: "transaction-failed" });
  indexedDB.fault.transactionThrows = false;
  assert.equal((await store.beginPlannedAttempt(ticket, ticket.rows[0].rowId)).ok, true);
});

test("committed but unacknowledged planned attempt returns no capability and cold read stays unknown", async () => {
  const { store, indexedDB, second } = fixture({ timeoutMs: 5 });
  const { ticket } = await store.beginPlannedBatch(["first.JPG", "second.JPG"]);
  indexedDB.fault.holdComplete = true;
  const result = await store.beginPlannedAttempt(ticket, ticket.rows[0].rowId);
  assert.deepEqual(result, { ok: false, code: "transaction-unconfirmed" });
  assert.equal(Object.hasOwn(result, "receipt"), false);
  assert.equal(indexedDB.persisted().entries.length, 1);
  assert.equal(indexedDB.persisted().entries[0].outcome, "in-progress");
  indexedDB.releaseComplete();
  await nextTurn();
  const cold = second();
  const before = structuredClone(indexedDB.persisted());
  const listed = await cold.list();
  assert.equal(listed.ok, true);
  assert.deepEqual(listed.plans[0].rows.map(row => row.outcome), ["cancelled-or-unknown", "queued"]);
  assert.equal(listed.plans[0].rows[0].receipt.unconfirmed, true);
  assert.equal(listed.plans[0].rows[1].receipt, null);
  assert.deepEqual(indexedDB.persisted(), before, "Cold projection cannot retry or settle the unacknowledged attempt");
  assert.deepEqual(await store.beginPlannedAttempt(ticket, ticket.rows[0].rowId), { ok: false, code: "already-started" });
  cold.close(); store.close();
});
