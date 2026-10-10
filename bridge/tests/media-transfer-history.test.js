"use strict";

// Deterministic policy/transaction-adapter tests. This fake models the IDB
// request lifetime, transaction serialization and rollback; browser coverage must
// separately exercise the platform implementation, persistence and multiple tabs.
const assert = require("node:assert/strict");
const test = require("node:test");
const { createHistoryStore, sanitizeFilename, MAX_ENTRIES } = require("../open_eos_bridge/static/media-transfer-history.js");

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

test("latest 100 actual starts follow admission order across wall-clock reversal; evicted completion cannot recreate", async () => {
  const { store, time } = fixture();
  const { ticket } = await store.createBatchTicket();
  const first = await start(store, ticket, "evicted.JPG");
  for (let i = 0; i < MAX_ENTRIES; i += 1) {
    time(999 - i);
    assert.equal((await start(store, ticket, `IMG_${i}.JPG`)).ok, true);
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
    { ...valid, schemaVersion: 2 }, { ...valid, extra: "secret" }, { ...valid, epoch: -1 },
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
    const code = snapshot.schemaVersion === 2 ? "unsupported-schema" : "corrupt";
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
