"use strict";

// Pure deterministic client evidence. Deferred transfers represent reads and
// destination closes; these tests do not claim browser or physical-camera QA.
const assert = require("node:assert/strict");
const test = require("node:test");
const { createSelection, runSequentialBatch } = require("../open_eos_bridge/static/media-batch.js");

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

const item = (id) => ({ id, name: `${id}.JPG`, sizeBytes: 3 });
const receipt = (name = "synthetic.JPG") => ({ completed: true, bytesTransferred: 3, name });
const statuses = (state) => state.results.map((result) => result.status);

test("selection keeps exact opaque IDs, first duplicate and only loaded candidates", () => {
  const selection = createSelection();
  const ids = ["e\u0301", "\u00e9", " ID ", "ID", "id", "__proto__", "constructor"];
  selection.replaceLoaded([...ids.map(item), { ...item("ID"), name: "duplicate.JPG" }]);
  selection.setMany([...ids, "ID", "not-loaded", 7], true);
  assert.deepEqual(selection.snapshot().map((entry) => entry.id), ids);
  assert.equal(selection.snapshot()[3].item.name, "ID.JPG");
  assert.equal(selection.setSelected("not-loaded", true), false);
  assert.equal(selection.has("not-loaded"), false);
  assert.equal(selection.has("Id"), false);
  selection.setSelected("ID", false);
  assert.equal(selection.has(" ID "), true);
  assert.equal(selection.has("id"), true);
  assert.equal(selection.has("ID"), false);
  selection.setSelected("ID", true);
  assert.equal(selection.snapshot().at(-1).id, "ID", "reselection follows selection order");
});

test("filtering and pagination hide selections without changing ordered snapshots", () => {
  const selection = createSelection();
  const loaded = [item("first"), item("second"), item("third")];
  selection.replaceLoaded(loaded);
  selection.setMany(["third", "first"], true);
  assert.deepEqual(selection.snapshot(["first"]).map(({ id, hidden, loaded }) => ({ id, hidden, loaded })), [
    { id: "third", hidden: true, loaded: true }, { id: "first", hidden: false, loaded: true },
  ]);
  assert.ok(selection.snapshot([]).every((entry) => entry.hidden));
  assert.ok(selection.snapshot().every((entry) => !entry.hidden));
  assert.deepEqual(selection.snapshot(["second"]).map((entry) => entry.id), ["third", "first"]);
});

test("partial refresh preserves missing snapshots and explicitly reports their scope", () => {
  const selection = createSelection();
  const original = { ...item("old"), metadata: { label: "original" } };
  selection.replaceLoaded([original, item("present")]);
  selection.setMany(["old", "present"], true);
  const initial = selection.snapshot();
  original.name = "changed-outside.JPG";
  original.metadata.label = "changed-outside";
  selection.replaceLoaded([{ ...item("present"), name: "refreshed.JPG" }, item("new")]);
  const refreshed = selection.snapshot(["present", "new"]);
  assert.deepEqual(refreshed.map(({ id, loaded, hidden }) => ({ id, loaded, hidden })), [
    { id: "old", loaded: false, hidden: true }, { id: "present", loaded: true, hidden: false },
  ]);
  assert.equal(refreshed[0].item.name, "old.JPG");
  assert.equal(refreshed[0].item.metadata.label, "original");
  assert.equal(refreshed[1].item.name, "refreshed.JPG");
  assert.equal(initial[1].item.name, "present.JPG", "older snapshots never change");
  assert.equal(selection.setSelected("unknown", true), false);
  assert.ok(Object.isFrozen(refreshed));
  assert.ok(Object.isFrozen(refreshed[0]));
  assert.ok(Object.isFrozen(refreshed[0].item.metadata));
  assert.throws(() => { refreshed[0].item.metadata.label = "mutation"; }, TypeError);
  selection.replaceLoaded([{ ...item("old"), name: "back.JPG" }]);
  assert.equal(selection.snapshot()[0].loaded, true);
  assert.equal(selection.snapshot()[0].item.name, "back.JPG");
  selection.remove("old");
  assert.equal(selection.has("old"), false);
  assert.equal(selection.setSelected("old", true), false, "explicit deletion removes the loaded candidate");
  selection.clear();
  assert.deepEqual(selection.snapshot(), []);
});

test("malformed IDs fail atomically without coercion or replacing loaded candidates", async () => {
  const selection = createSelection();
  selection.replaceLoaded([item("kept")]);
  for (const id of [null, undefined, "", 42]) {
    assert.throws(() => selection.replaceLoaded([item("new"), item(id)]), TypeError);
    assert.equal(selection.setSelected("kept", true), true);
    assert.equal(selection.setSelected("new", true), false);
    await assert.rejects(runSequentialBatch([item(id)], { transfer: async () => receipt() }), TypeError);
  }
});

test("queue captures immutable first-deduplicated ordered items and runs one at a time", async () => {
  const items = [item("second"), item("first"), { ...item("second"), name: "duplicate.JPG" }];
  items[0].metadata = { value: "captured" };
  const gates = [deferred(), deferred()];
  const calls = [], updates = [];
  const pending = runSequentialBatch(items, {
    transfer: (value, context) => {
      calls.push({ value, context });
      assert.ok(Object.isFrozen(value));
      return gates[context.index].promise;
    },
    onUpdate: (state) => updates.push(state),
  });
  assert.deepEqual(calls.map(({ value }) => value.id), ["second"]);
  assert.deepEqual(statuses(updates[0]), ["pending", "pending"]);
  assert.deepEqual(statuses(updates.at(-1)), ["running", "pending"]);
  items[0].name = "mutated.JPG";
  items[0].metadata.value = "mutated";
  items.reverse();
  items.push(item("late"));
  const firstReceipt = receipt("first-saved.JPG");
  gates[0].resolve(firstReceipt);
  await Promise.resolve();
  assert.deepEqual(calls.map(({ value }) => value.id), ["second", "first"]);
  assert.equal(calls[0].value.name, "second.JPG");
  assert.equal(calls[0].value.metadata.value, "captured");
  firstReceipt.name = "mutated-receipt.JPG";
  gates[1].resolve(receipt("second-saved.JPG"));
  const final = await pending;
  assert.equal(final.status, "completed");
  assert.equal(final.currentIndex, null);
  assert.deepEqual(statuses(final), ["completed", "completed"]);
  assert.equal(final.results[0].receipt.name, "first-saved.JPG");
  assert.ok(Object.isFrozen(final.results));
  assert.ok(Object.isFrozen(final.results[0]));
  assert.ok(Object.isFrozen(final.results[0].receipt));
  assert.deepEqual(statuses(updates[0]), ["pending", "pending"], "published histories never mutate");
});

test("selected snapshots omitted by a partial refresh are still sent once", async () => {
  const selection = createSelection();
  selection.replaceLoaded([item("previous"), item("current")]);
  selection.setMany(["previous", "current"], true);
  selection.replaceLoaded([item("current")]);
  const calls = [];
  const final = await runSequentialBatch(selection.snapshot().map((entry) => entry.item), {
    transfer: async (value) => { calls.push(value.id); return receipt(); },
  });
  assert.deepEqual(calls, ["previous", "current"]);
  assert.deepEqual(statuses(final), ["completed", "completed"]);
});

test("queue does not merge Unicode-equivalent, case or whitespace-distinct IDs", async () => {
  const ids = ["e\u0301", "\u00e9", "A", "a", " a "];
  const calls = [];
  const result = await runSequentialBatch([...ids, ids[0]].map(item), {
    transfer: async (value) => { calls.push(value.id); return receipt(); },
  });
  assert.deepEqual(calls, ids);
  assert.deepEqual(result.results.map((entry) => entry.id), ids);
});

test("empty queue completes without starting a transfer", async () => {
  const result = await runSequentialBatch([], { transfer: () => assert.fail("unexpected transfer") });
  assert.equal(result.status, "completed");
  assert.deepEqual(result.results, []);
});

test("already cancelled or retired queue starts nothing", async () => {
  const controller = new AbortController();
  controller.abort();
  for (const options of [{ signal: controller.signal }, { isCurrent: () => false }]) {
    const updates = [];
    const result = await runSequentialBatch([item("first"), item("second")], {
      ...options, transfer: () => assert.fail("unexpected transfer"), onUpdate: (state) => updates.push(state),
    });
    assert.equal(result.status, "stopped");
    assert.deepEqual(statuses(result), ["not-started", "not-started"]);
    if (options.isCurrent) assert.equal(updates.length, 0, "retired owner must not publish even initial state");
  }
});

test("cancellation between running notification and transfer never starts that item", async () => {
  const controller = new AbortController();
  const result = await runSequentialBatch([item("first"), item("second")], {
    signal: controller.signal,
    transfer: () => assert.fail("unexpected transfer"),
    onUpdate: (state) => { if (state.status === "running") controller.abort(); },
  });
  assert.deepEqual(statuses(result), ["not-started", "not-started"]);
});

test("failure preserves earlier completion and stops later items without retry", async () => {
  const calls = [];
  const failure = new Error("synthetic original is no longer available");
  const final = await runSequentialBatch([item("one"), item("missing"), item("later")], {
    transfer: async (value) => {
      calls.push(value.id);
      if (value.id === "missing") throw failure;
      return receipt();
    },
  });
  assert.deepEqual(calls, ["one", "missing"]);
  assert.deepEqual(statuses(final), ["completed", "failed", "not-started"]);
  assert.equal(final.stopReason, "failed");
  assert.equal(final.results[1].error.message, failure.message);
});

for (const outcome of ["reject", "unconfirmed", "closed"]) {
  test(`cancel during noncooperative transfer preserves completion and waits for ${outcome}`, async () => {
    const controller = new AbortController();
    const gate = deferred(), beganSecond = deferred();
    const updates = [], calls = [];
    let settled = false;
    const pending = runSequentialBatch([item("one"), item("two"), item("three")], {
      signal: controller.signal,
      transfer: async (value, context) => {
        calls.push(value.id);
        assert.equal(context.signal, controller.signal);
        if (value.id === "one") return receipt();
        beganSecond.resolve();
        return gate.promise;
      },
      onUpdate: (state) => updates.push(state),
    });
    pending.then(() => { settled = true; });
    await beganSecond.promise;
    controller.abort();
    assert.equal(updates.at(-1).status, "stopping");
    assert.deepEqual(statuses(updates.at(-1)), ["completed", "cancelled-or-unknown", "not-started"]);
    await Promise.resolve();
    assert.equal(settled, false, "busy ownership is retained until the active transfer settles");
    if (outcome === "reject") gate.reject(new Error("synthetic cancelled write"));
    else gate.resolve(outcome === "closed" ? receipt() : undefined);
    const final = await pending;
    assert.equal(final.status, "stopped");
    assert.equal(final.stopReason, "cancelled");
    assert.deepEqual(calls, ["one", "two"]);
    assert.deepEqual(statuses(final), ["completed", outcome === "closed" ? "completed" : "cancelled-or-unknown", "not-started"]);
    assert.equal(updates.find((state) => state.status === "stopping").results[1].status, "cancelled-or-unknown");
  });
}

test("cancel during destination close still records a successful close receipt", async () => {
  const controller = new AbortController(), closing = deferred(), close = deferred();
  const pending = runSequentialBatch([item("one"), item("later")], {
    signal: controller.signal,
    transfer: async () => { closing.resolve(); await close.promise; return receipt("committed.JPG"); },
  });
  await closing.promise;
  controller.abort();
  close.resolve();
  const final = await pending;
  assert.deepEqual(statuses(final), ["completed", "not-started"]);
  assert.equal(final.results[0].receipt.name, "committed.JPG");
});

for (const outcome of [undefined, {}, { completed: false }, "uncertain-close", "abort-error"]) {
  test(`unconfirmed transfer outcome ${JSON.stringify(outcome)} never claims completed or rolled back`, async () => {
    const calls = [];
    const result = await runSequentialBatch([item("one"), item("later")], {
      transfer: async (value) => {
        calls.push(value.id);
        if (outcome === "uncertain-close") throw Object.assign(new Error("close state unknown"), { outcomeUnknown: true });
        if (outcome === "abort-error") throw Object.assign(new Error("cancelled"), { name: "AbortError" });
        return outcome;
      },
    });
    assert.deepEqual(statuses(result), ["cancelled-or-unknown", "not-started"]);
    assert.deepEqual(calls, ["one"]);
    assert.equal(result.results[0].receipt, null);
  });
}

for (const outcome of ["closed", "reject"]) {
  test(`retired owner cannot publish late ${outcome} or start another transfer`, async () => {
    const oldOwner = { id: "reused-session" };
    let currentOwner = oldOwner;
    const gate = deferred(), calls = [], updates = [];
    const pending = runSequentialBatch([item("one"), item("later")], {
      isCurrent: () => currentOwner === oldOwner,
      transfer: (value) => { calls.push(value.id); return gate.promise; },
      onUpdate: (state) => updates.push(state),
    });
    currentOwner = { id: "reused-session" };
    const beforeReplacement = updates.slice();
    if (outcome === "closed") gate.resolve(receipt());
    else gate.reject(new Error("synthetic old-owner failure"));
    const final = await pending;
    assert.deepEqual(updates, beforeReplacement);
    assert.deepEqual(calls, ["one"]);
    assert.equal(final.stopReason, "retired");
    assert.deepEqual(statuses(final), [outcome === "closed" ? "completed" : "cancelled-or-unknown", "not-started"]);
  });
}

test("retirement during notification starts nothing and publishes no later state", async () => {
  let current = true;
  const updates = [];
  const result = await runSequentialBatch([item("one")], {
    isCurrent: () => current,
    transfer: () => assert.fail("unexpected transfer"),
    onUpdate: (state) => { updates.push(state); if (state.status === "running") current = false; },
  });
  assert.deepEqual(statuses(result), ["not-started"]);
  assert.equal(result.stopReason, "retired");
  assert.equal(updates.length, 2);
});

test("observer exception after a close preserves the receipt and stops further work", async () => {
  const calls = [];
  const result = await runSequentialBatch([item("one"), item("later")], {
    transfer: async (value) => { calls.push(value.id); return receipt(); },
    onUpdate: (state) => { if (state.results[0].status === "completed") throw new Error("synthetic renderer error"); },
  });
  assert.deepEqual(calls, ["one"]);
  assert.deepEqual(statuses(result), ["completed", "not-started"]);
  assert.equal(result.results[0].receipt.completed, true);
  assert.equal(result.stopReason, "observer-error");
  assert.equal(result.observerError.message, "synthetic renderer error");
});

test("observer exception before any transfer starts nothing", async () => {
  const result = await runSequentialBatch([item("one")], {
    transfer: () => assert.fail("unexpected transfer"),
    onUpdate: () => { throw new Error("synthetic renderer error"); },
  });
  assert.deepEqual(statuses(result), ["not-started"]);
  assert.equal(result.stopReason, "observer-error");
});

test("completed queue removes its cancellation observer", async () => {
  const controller = new AbortController();
  const updates = [];
  const result = await runSequentialBatch([item("one")], {
    signal: controller.signal, transfer: async () => receipt(), onUpdate: (state) => updates.push(state),
  });
  const count = updates.length;
  controller.abort();
  assert.equal(updates.length, count);
  assert.equal(result.status, "completed");
  assert.deepEqual(statuses(result), ["completed"]);
});
