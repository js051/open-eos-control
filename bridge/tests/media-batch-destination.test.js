"use strict";

const assert = require("node:assert/strict");
const { createDirectoryBatchDestination } = require("../open_eos_bridge/static/media-batch-destination.js");
const { safeDownloadName } = require("../open_eos_bridge/static/media-transfer.js");

const NONCE = "01234567-89ab-4def-8123-456789abcdef";
const BATCH_NAME = `Open-EOS-${NONCE}`;
const ITEM = Object.freeze({ id: "synthetic/media-1", name: "IMG_0001.CR3" });
const tests = [];
const test = (name, run) => tests.push({ name, run });
const namedError = (name) => Object.assign(new Error(name), { name });
const aborted = (error) => error.name === "AbortError";
const collision = (error) => error.code === "MEDIA_BATCH_COLLISION";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

// All handles and bytes below are in-memory mocks. No picker, filesystem, device,
// network, permission, or user data is used by these tests.
function fixture(options = {}) {
  const events = [];
  const hooks = {};
  const controller = new AbortController();
  let current = true;
  async function event(type, details = {}) {
    events.push({ type, ...details });
    await hooks[type]?.(details);
  }
  class FakeWriter {
    constructor(name) { this.name = name; this.chunks = []; }
    async write(chunk) {
      await event("write", { name: this.name });
      this.chunks.push([...chunk]);
    }
    async close() { await event("close", { name: this.name }); }
    async abort(reason) { await event("abort", { name: this.name, reason }); }
  }
  class FakeFile {
    constructor(name) {
      this.kind = "file";
      this.name = name;
      this.contents = "synthetic existing data";
      this.writer = new FakeWriter(name);
    }
    async createWritable(settings) {
      await event("writer:create", { name: this.name, settings, handle: this });
      return this.writer;
    }
  }
  class FakeDirectory {
    constructor(name) { this.kind = "directory"; this.name = name; this.entries = new Map(); }
    async getDirectoryHandle(name, { create = false } = {}) {
      let handle = this.entries.get(name);
      if (create && !handle) {
        handle = new FakeDirectory(name);
        this.entries.set(name, handle);
      }
      await event(`directory:${create ? "create" : "probe"}`, { name, handle, parent: this });
      if (!handle) throw namedError("NotFoundError");
      if (handle.kind !== "directory") throw namedError("TypeMismatchError");
      return handle;
    }
    async getFileHandle(name, { create = false } = {}) {
      let handle = this.entries.get(name);
      if (create && !handle) {
        handle = new FakeFile(name);
        this.entries.set(name, handle);
      }
      await event(`file:${create ? "create" : "probe"}`, { name, handle, parent: this });
      if (!handle) throw namedError("NotFoundError");
      if (handle.kind !== "file") throw namedError("TypeMismatchError");
      return handle;
    }
    removeEntry() { throw new Error("Deletion must never be attempted"); }
    requestPermission() { throw new Error("Permission must never be requested implicitly"); }
  }
  const root = new FakeDirectory("synthetic-selected-folder");
  const destination = createDirectoryBatchDestination({
    directoryHandle: root,
    nonce: NONCE,
    safeDownloadName,
    signal: controller.signal,
    isCurrent: () => current,
    abortTimeoutMs: 25,
    ...options,
  });
  return {
    destination, root, events, hooks, controller, FakeFile, FakeDirectory,
    retire: () => { current = false; },
    count: (type) => events.filter((entry) => entry.type === type).length,
  };
}

test("constructor is lazy and validates the selected handle, nonce, and timeout", () => {
  const f = fixture();
  assert.equal(f.events.length, 0);
  assert.deepEqual(f.destination.snapshot(), {
    batchName: BATCH_NAME, directoryState: "not-created", directoryMayRemain: false,
    files: [], leftoverCount: 0, unknownCount: 0,
  });
  for (const options of [
    { directoryHandle: null }, { directoryHandle: { kind: "file" } },
    { nonce: "../other-folder" }, { nonce: "ordinary-name" }, { nonce: "" },
    { safeDownloadName: null }, { isCurrent: false }, { abortTimeoutMs: 0 },
    { abortTimeoutMs: 5001 }, { abortTimeoutMs: 1.5 },
  ]) assert.throws(() => fixture(options), TypeError);
  fixture({ nonce: "0123456789abcdef0123456789abcdef" });
});

test("sequential files share one unique subdirectory and become complete only after close", async () => {
  const f = fixture();
  const first = await f.destination.open(ITEM, 0);
  assert.equal(first.name, ITEM.name);
  assert.equal(f.destination.snapshot().files[0].completed, false);
  await first.writable.write(new Uint8Array([1, 2, 3]));
  assert.equal(f.destination.snapshot().files[0].completed, false);
  await first.writable.close();
  const second = await f.destination.open({ name: "IMG_0002.JPG" }, 1);
  await second.writable.write(new Uint8Array([4]));
  await second.writable.close();
  assert.equal(f.count("directory:create"), 1);
  assert.equal(f.count("file:create"), 2);
  assert.equal(f.count("abort"), 0);
  assert.deepEqual(f.events.filter((e) => e.type === "writer:create").map((e) => e.settings), [
    { keepExistingData: false, mode: "exclusive" }, { keepExistingData: false, mode: "exclusive" },
  ]);
  const state = f.destination.snapshot();
  assert.equal(state.directoryState, "created");
  assert.equal(state.leftoverCount, 0);
  assert.equal(state.unknownCount, 0);
  assert.ok(state.files.every((item) => item.state === "closed" && item.closeSucceeded && item.completed));
  // Returned snapshots are copies, not mutable ownership/receipt records.
  state.files[0].completed = false;
  assert.equal(f.destination.snapshot().files[0].completed, true);
});

for (const kind of ["directory", "file"]) {
  test(`preexisting ${kind} at the batch path is refused without any write`, async () => {
    const f = fixture();
    const existing = kind === "directory" ? new f.FakeDirectory(BATCH_NAME) : new f.FakeFile(BATCH_NAME);
    f.root.entries.set(BATCH_NAME, existing);
    await assert.rejects(f.destination.open(ITEM, 0), collision);
    assert.equal(f.count("directory:create"), 0);
    assert.equal(f.count("file:probe"), 0);
    assert.equal(f.count("writer:create"), 0);
    assert.equal(f.root.entries.get(BATCH_NAME), existing);
    assert.equal(f.destination.snapshot().directoryMayRemain, false);
  });
  test(`preexisting ${kind} at a file path is refused without opening a writer`, async () => {
    const f = fixture();
    let existing;
    f.hooks["directory:create"] = ({ handle }) => {
      existing = kind === "directory" ? new f.FakeDirectory(ITEM.name) : new f.FakeFile(ITEM.name);
      handle.entries.set(ITEM.name, existing);
    };
    await assert.rejects(f.destination.open(ITEM, 0), collision);
    assert.equal(f.count("file:create"), 0);
    assert.equal(f.count("writer:create"), 0);
    assert.equal(f.root.entries.get(BATCH_NAME).entries.get(ITEM.name), existing);
    assert.equal(f.destination.snapshot().leftoverCount, 0);
  });
}

test("only sanitized leaf names reach the file APIs, with public name taking precedence", async () => {
  const f = fixture();
  const opened = await f.destination.open({ name: "../../DCIM/CON", filename: "wrong.JPG" }, 0);
  assert.equal(opened.name, "_CON");
  assert.ok(f.events.filter((e) => e.type.startsWith("file:")).every((e) => e.name === "_CON"));
  await opened.writable.abort();
  const fallback = await f.destination.open({ filename: "bad:name?.JPG" }, 1);
  assert.equal(fallback.name, "bad_name_.JPG");
  await fallback.writable.abort();
});

test("malicious sanitizer output and absent names fail before directory creation", async () => {
  for (const name of ["../escape", "a/b", "a\\b", "..", ".", "", "bad\u0000name", "NUL.txt", "trailing.", "bad:name", "bad\u007fname"]) {
    const f = fixture({ safeDownloadName: () => name });
    await assert.rejects(f.destination.open(ITEM, 0), (e) => e.code === "MEDIA_BATCH_INVALID_NAME");
    assert.equal(f.events.length, 0);
  }
  const f = fixture();
  for (const item of [{}, { name: "" }, { name: 42 }]) {
    await assert.rejects(f.destination.open(item, 0), TypeError);
  }
  for (const index of [-1, 0.5, undefined]) await assert.rejects(f.destination.open(ITEM, index), TypeError);
  assert.equal(f.events.length, 0);
});

test("sanitized, case-folded, and canonically equivalent duplicate names cannot overwrite", async () => {
  for (const [firstName, nextName] of [["a:b.JPG", "a?b.JPG"], ["img.JPG", "IMG.jpg"], ["cafe\u0301.JPG", "caf\u00e9.JPG"]]) {
    const f = fixture();
    const first = await f.destination.open({ name: firstName }, 0);
    await first.writable.close();
    const before = f.events.length;
    await assert.rejects(f.destination.open({ name: nextName }, 1), collision);
    assert.equal(f.events.length, before);
    assert.equal(f.count("writer:create"), 1);
  }
});

for (const type of ["directory:probe", "directory:create", "file:probe", "file:create", "writer:create"]) {
  test(`permission failure at ${type} propagates without retry or permission request`, async () => {
    const f = fixture();
    const failure = namedError("NotAllowedError");
    f.hooks[type] = () => { throw failure; };
    await assert.rejects(f.destination.open(ITEM, 0), (e) => e === failure);
    assert.equal(f.count(type), 1);
    assert.equal(f.count("write"), 0);
    assert.equal(f.count("close"), 0);
    if (type === "directory:probe") assert.equal(f.count("directory:create"), 0);
    if (type === "file:probe") assert.equal(f.count("file:create"), 0);
    if (type === "writer:create") {
      assert.equal(f.destination.snapshot().leftoverCount, 1);
      assert.equal(f.destination.snapshot().unknownCount, 1);
    }
  });
}

test("wrong returned directory or file handle stops before any writer", async () => {
  for (const type of ["directory:create", "file:create"]) {
    const f = fixture();
    f.hooks[type] = ({ handle }) => { handle.name = "wrong-entry"; };
    await assert.rejects(f.destination.open(ITEM, 0), (e) => e.code === "MEDIA_BATCH_INVALID_HANDLE");
    assert.equal(f.count("writer:create"), 0);
    assert.equal(f.destination.snapshot().directoryMayRemain, true);
  }
});

for (const action of ["cancel", "retire"]) {
  test(`${action} before open does not read or create a directory`, async () => {
    const f = fixture();
    if (action === "cancel") f.controller.abort(); else f.retire();
    await assert.rejects(f.destination.open(ITEM, 0), aborted);
    assert.equal(f.events.length, 0);
  });
  for (const type of ["directory:probe", "directory:create", "file:probe", "file:create", "writer:create"]) {
    test(`${action} after ${type} prevents subsequent creation and disposes a late writer`, async () => {
      const f = fixture();
      f.hooks[type] = () => { if (action === "cancel") f.controller.abort(); else f.retire(); };
      await assert.rejects(f.destination.open(ITEM, 0), aborted);
      if (type === "directory:probe") assert.equal(f.count("directory:create"), 0);
      if (type === "directory:create") assert.equal(f.count("file:probe"), 0);
      if (type === "file:probe") assert.equal(f.count("file:create"), 0);
      if (type === "file:create") assert.equal(f.count("writer:create"), 0);
      assert.equal(f.count("abort"), type === "writer:create" ? 1 : 0);
      assert.equal(f.count("write"), 0);
      assert.equal(f.count("close"), 0);
      assert.equal(f.destination.snapshot().files[0].completed, false);
    });
  }
}

test("a late writer is awaited through abort before cancellation is returned", async () => {
  const f = fixture();
  const writerEntered = deferred();
  const writerRelease = deferred();
  const abortEntered = deferred();
  const abortRelease = deferred();
  f.hooks["writer:create"] = async () => { writerEntered.resolve(); await writerRelease.promise; };
  f.hooks.abort = async () => { abortEntered.resolve(); await abortRelease.promise; };
  let settled = false;
  const opening = f.destination.open(ITEM, 0).finally(() => { settled = true; });
  const check = assert.rejects(opening, aborted);
  await writerEntered.promise;
  f.controller.abort();
  writerRelease.resolve();
  await abortEntered.promise;
  assert.equal(settled, false);
  abortRelease.resolve();
  await check;
  assert.equal(f.destination.snapshot().files[0].abortState, "aborted");
  assert.equal(f.destination.snapshot().leftoverCount, 1);
  assert.equal(f.root.entries.get(BATCH_NAME).entries.size, 1);
});

test("late-writer abort failure preserves the cancellation and reports an unknown leftover", async () => {
  const f = fixture();
  const failure = new Error("synthetic abort failure");
  f.hooks["writer:create"] = () => f.controller.abort();
  f.hooks.abort = () => { throw failure; };
  await assert.rejects(f.destination.open(ITEM, 0), (e) => aborted(e) && e.abortError === failure && e.outcomeUnknown);
  assert.equal(f.destination.snapshot().unknownCount, 1);
  assert.equal(f.destination.snapshot().files[0].abortState, "failed");
  assert.equal(f.root.entries.get(BATCH_NAME).entries.size, 1);
});

test("nonsettling late-writer abort is bounded and remains unknown after its late settlement", async () => {
  const f = fixture({ abortTimeoutMs: 1 });
  const gate = deferred();
  f.hooks["writer:create"] = () => f.controller.abort();
  f.hooks.abort = () => gate.promise;
  await assert.rejects(f.destination.open(ITEM, 0), (e) => aborted(e) && e.abortError.code === "MEDIA_BATCH_ABORT_TIMEOUT");
  assert.equal(f.destination.snapshot().files[0].abortState, "timeout");
  gate.resolve();
  await Promise.resolve();
  assert.equal(f.destination.snapshot().unknownCount, 1);
  assert.equal(f.destination.snapshot().files[0].completed, false);
});

test("simultaneous opens and an unresolved writer cannot create a second destination", async () => {
  const f = fixture();
  const entered = deferred();
  const gate = deferred();
  f.hooks["directory:create"] = async () => { entered.resolve(); await gate.promise; };
  const first = f.destination.open(ITEM, 0);
  await entered.promise;
  await assert.rejects(f.destination.open({ name: "second.JPG" }, 1), (e) => e.code === "MEDIA_BATCH_BUSY");
  gate.resolve();
  const opened = await first;
  await assert.rejects(f.destination.open({ name: "second.JPG" }, 1), (e) => e.code === "MEDIA_BATCH_BUSY");
  await opened.writable.abort();
  assert.equal(f.count("writer:create"), 1);
});

test("write failure retains the primary error when abort fails", async () => {
  const f = fixture();
  const primary = new Error("synthetic write failure");
  const secondary = new Error("synthetic abort failure");
  f.hooks.write = () => { throw primary; };
  f.hooks.abort = () => { throw secondary; };
  const { writable } = await f.destination.open(ITEM, 0);
  await assert.rejects(writable.write(new Uint8Array([1])), (e) => e === primary && e.abortError === secondary);
  await assert.rejects(writable.abort(), (e) => e === secondary);
  assert.equal(f.count("abort"), 1);
  assert.equal(f.count("close"), 0);
  assert.equal(f.destination.snapshot().unknownCount, 1);
  await assert.rejects(f.destination.open({ name: "second.JPG" }, 1), (e) => e.code === "MEDIA_BATCH_BUSY");
});

test("retirement during a write prevents close and retains its placeholder", async () => {
  const f = fixture();
  f.hooks.write = () => f.retire();
  const { writable } = await f.destination.open(ITEM, 0);
  await assert.rejects(writable.write(new Uint8Array([1])), aborted);
  await assert.rejects(writable.close(), aborted);
  assert.equal(f.count("abort"), 1);
  assert.equal(f.count("close"), 0);
  assert.equal(f.destination.snapshot().leftoverCount, 1);
});

test("cancellation before close aborts without committing", async () => {
  const f = fixture();
  const { writable } = await f.destination.open(ITEM, 0);
  f.controller.abort();
  await assert.rejects(writable.close(), aborted);
  assert.equal(f.count("close"), 0);
  assert.equal(f.count("abort"), 1);
  assert.equal(f.destination.snapshot().files[0].completed, false);
});

test("a fulfilled close preserves completion when cancellation and retirement arrive during close", async () => {
  const f = fixture();
  f.hooks.close = () => { f.controller.abort(); f.retire(); };
  const { writable } = await f.destination.open(ITEM, 0);
  await writable.close();
  await writable.abort();
  assert.equal(f.count("abort"), 0);
  assert.equal(f.destination.snapshot().files[0].completed, true);
  assert.equal(f.destination.snapshot().files[0].closeSucceeded, true);
  assert.equal(f.destination.snapshot().leftoverCount, 0);
});

test("a rejected close stays ambiguous even when abort succeeds", async () => {
  const f = fixture();
  const failure = new Error("synthetic close failure");
  f.hooks.close = () => { throw failure; };
  const { writable } = await f.destination.open(ITEM, 0);
  await assert.rejects(writable.close(), (e) => e === failure && e.outcomeUnknown);
  const record = f.destination.snapshot().files[0];
  assert.equal(record.completed, false);
  assert.equal(record.closeSucceeded, false);
  assert.equal(record.state, "unknown");
  assert.equal(record.abortState, "aborted");
  assert.equal(f.count("close"), 1);
  assert.equal(f.count("abort"), 1);
});

test("a rejected close preserves its primary error when abort also fails", async () => {
  const f = fixture();
  const primary = new Error("synthetic close failure");
  const secondary = new Error("synthetic abort failure");
  f.hooks.close = () => { throw primary; };
  f.hooks.abort = () => { throw secondary; };
  const { writable } = await f.destination.open(ITEM, 0);
  await assert.rejects(writable.close(), (e) => e === primary && e.outcomeUnknown && e.abortError === secondary);
  assert.equal(f.destination.snapshot().files[0].completed, false);
});

test("frozen close errors retain their cause and expose uncertainty to callers", async () => {
  const f = fixture();
  const primary = Object.freeze(new Error("synthetic frozen close failure"));
  f.hooks.close = () => { throw primary; };
  f.hooks.abort = () => { throw new Error("synthetic abort failure"); };
  const { writable } = await f.destination.open(ITEM, 0);
  await assert.rejects(writable.close(), (e) => e.cause === primary && e.message === primary.message
    && e.name === primary.name && e.outcomeUnknown && e.abortError.message === "synthetic abort failure");
  assert.equal(f.destination.snapshot().unknownCount, 1);
});

test("frozen write errors keep their primary cause when abort fails", async () => {
  const f = fixture();
  const primary = Object.freeze(Object.assign(new Error("synthetic frozen write failure"), { code: "WRITE_FAILED" }));
  const secondary = new Error("synthetic abort failure");
  f.hooks.write = () => { throw primary; };
  f.hooks.abort = () => { throw secondary; };
  const { writable } = await f.destination.open(ITEM, 0);
  await assert.rejects(writable.write(new Uint8Array([1])), (e) => e.cause === primary
    && e.code === "WRITE_FAILED" && e.abortError === secondary && e.outcomeUnknown);
  assert.equal(f.destination.snapshot().unknownCount, 1);
});

test("primitive close failures also report uncertain outcomes without losing the primary cause", async () => {
  const f = fixture();
  f.hooks.close = () => { throw "synthetic primitive failure"; };
  const { writable } = await f.destination.open(ITEM, 0);
  await assert.rejects(writable.close(), (e) => e.cause === "synthetic primitive failure" && e.outcomeUnknown);
  assert.equal(f.destination.snapshot().unknownCount, 1);
});

async function run() {
  for (const { name, run: check } of tests) {
    try { await check(); } catch (error) { error.message = `${name}: ${error.message}`; throw error; }
  }
  console.log(`media-batch-destination: ${tests.length} tests passed`);
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
