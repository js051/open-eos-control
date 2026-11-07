const assert = require("node:assert/strict");
const { createController } = require("../open_eos_bridge/static/media-auto-import.js");
const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const item = (id, extra = {}) => ({ id, name: `${id}.JPG`, kind: "image", sizeBytes: 900, ...extra });
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function fixture(options = {}) {
  const env = { time: 0, current: true, foreground: true, busy: false, items: [item("old")], calls: [], updates: [],
    info: 10, ...options };
  const controller = createController({
    inventory: async () => { env.calls.push("inventory"); return { complete: true, items: env.items }; },
    metadata: async (entry) => { env.calls.push(`metadata:${entry.id}`); return { fresh: true, item: { ...entry, sizeBytes: env.info } }; },
    transfer: async (entry, context) => { env.calls.push(`transfer:${entry.id}`); assert.equal(context.expectedBytes, entry.sizeBytes); return { completed: true, bytesTransferred: entry.sizeBytes }; },
    isCurrent: () => env.current, isForeground: () => env.foreground, isBusy: () => env.busy,
    now: () => env.time, onUpdate: (value) => env.updates.push(value), ...options.callbacks,
  });
  return { env, controller, async tick(ms = 1000) { env.time += ms; return controller.step(); },
    async start() { await controller.step(); env.time += 1000; return controller.step(); } };
}
test("two complete baselines never download existing originals", async () => {
  const f = fixture(); await f.start(); assert.equal(f.controller.snapshot().phase, "watching");
  assert.deepEqual(f.env.calls, ["inventory", "inventory"]); await f.tick(5000);
  assert.equal(f.controller.snapshot().completed, 0);
});
test("short or absent complete evidence never forms baseline", async () => {
  for (const complete of [undefined, false, "true"]) {
    const f = fixture({ callbacks: { inventory: async () => ({ complete, items: [] }) } });
    await f.controller.step(); assert.equal(f.controller.snapshot().reason, "inventory-unavailable");
  }
});
test("changed baseline stops rather than importing old files", async () => {
  const f = fixture(); await f.controller.step(); f.env.items.push(item("new")); await f.tick();
  assert.equal(f.controller.snapshot().reason, "baseline-changed"); assert.equal(f.env.calls.length, 2);
});
test("order may change between equal baselines", async () => {
  const f = fixture({ items: [item("a"), item("b")] }); await f.controller.step(); f.env.items.reverse(); await f.tick();
  assert.equal(f.controller.snapshot().phase, "watching");
});
test("new JPEG uses two fresh sizes and ignores listing size", async () => {
  const f = fixture(); await f.start(); f.env.items.push(item("new")); await f.tick(5000);
  assert.equal(f.env.calls.filter((x) => x.startsWith("transfer")).length, 0);
  await f.tick(999); assert.equal(f.env.calls.filter((x) => x.startsWith("metadata")).length, 1);
  await f.tick(1); assert.equal(f.controller.snapshot().completed, 1);
  assert.equal(f.controller.snapshot().results[0].bytesTransferred, 10);
});
test("RAW identities are remembered without metadata or transfer", async () => {
  const f = fixture(); await f.start(); f.env.items.push(item("raw", { name: "IMG.CR3" }), item("jpeg"));
  await f.tick(5000); await f.tick(); assert.equal(f.controller.snapshot().knownCount, 3);
  assert.equal(f.env.calls.includes("metadata:raw"), false); assert.equal(f.env.calls.includes("transfer:raw"), false);
});
test("missing/zero/bool/string sizes cannot inherit cached positive size", async () => {
  for (const info of [undefined, 0, true, "10", -1, 1.5]) {
    const f = fixture({ info }); await f.start(); f.env.items.push(item("new")); await f.tick(5000); await f.tick();
    assert.equal(f.controller.snapshot().completed, 0); await f.tick(31000);
    assert.equal(f.controller.snapshot().reason, "metadata-deadline");
  }
});
test("size change restarts stability interval", async () => {
  const f = fixture(); await f.start(); f.env.items.push(item("new")); await f.tick(5000);
  f.env.info = 20; await f.tick(); assert.equal(f.controller.snapshot().completed, 0);
  await f.tick(); assert.equal(f.controller.snapshot().results[0].bytesTransferred, 20);
});
test("fresh marker and exact metadata identity required", async () => {
  for (const value of [{ fresh: false, item: item("new") }, { fresh: true, item: item("other") }]) {
    const f = fixture({ callbacks: { metadata: async () => value } }); await f.start(); f.env.items.push(item("new"));
    await f.tick(5000); assert.equal(f.controller.snapshot().reason, "fresh-metadata-unavailable");
  }
});
test("queue overflow stops before metadata fanout", async () => {
  const f = fixture(); await f.start(); f.env.items.push(...Array.from({ length: 21 }, (_, n) => item(`new${n}`)));
  await f.tick(5000); assert.equal(f.controller.snapshot().reason, "queue-limit");
  assert.equal(f.env.calls.some((x) => x.startsWith("metadata")), false);
});
test("duplicate exact IDs invalidate complete evidence; Unicode spellings remain distinct", async () => {
  const f = fixture({ items: [item("é"), item("e\u0301")] }); await f.start();
  assert.equal(f.controller.snapshot().knownCount, 2);
  f.env.items.push(item("é")); await f.tick(5000); assert.equal(f.controller.snapshot().reason, "inventory-unavailable");
});
test("disappeared pending identity stops, completed identity never reimports", async () => {
  const f = fixture(); await f.start(); f.env.items.push(item("new")); await f.tick(5000);
  f.env.items.pop(); await f.tick(5000); assert.equal(f.controller.snapshot().reason, "pending-disappeared");
  const g = fixture(); await g.start(); g.env.items.push(item("new")); await g.tick(5000); await g.tick();
  g.env.items.pop(); await g.tick(5000); g.env.items.push(item("new")); await g.tick(5000);
  assert.equal(g.env.calls.filter((x) => x === "transfer:new").length, 1);
});
test("busy yields without any reads, hidden and retired owners stop", async () => {
  const f = fixture({ busy: true }); await f.controller.step(); assert.deepEqual(f.env.calls, []);
  f.env.busy = false; await f.start(); f.env.foreground = false; await f.tick();
  assert.equal(f.controller.snapshot().reason, "backgrounded"); f.env.foreground = true; await f.tick();
  assert.equal(f.env.calls.length, 2);
  const g = fixture(); g.env.current = false; await g.controller.step(); assert.equal(g.env.updates.length, 0);
  assert.equal(g.controller.snapshot().reason, "session-retired");
});
test("concurrent and observer-reentrant steps coalesce", async () => {
  const gate = deferred(); let calls = 0, inner; let controller;
  controller = createController({ inventory: () => { calls++; return gate.promise; }, metadata: async () => {}, transfer: async () => {},
    isCurrent: () => true, isForeground: () => true, onUpdate: () => { inner = controller.step(); } });
  const first = controller.step(), second = controller.step(); await Promise.resolve();
  assert.equal(first, second); assert.equal(first, inner); assert.equal(calls, 1);
  gate.resolve({ complete: true, items: [] }); await first;
});
test("stop during noncooperative inventory prevents metadata and late publication into replacement", async () => {
  const gate = deferred(); const f = fixture({ callbacks: { inventory: () => gate.promise } });
  const work = f.controller.step(); await Promise.resolve(); f.controller.stop(); f.env.current = false;
  const count = f.env.updates.length; gate.resolve({ complete: true, items: [item("new")] }); await work;
  assert.equal(f.env.updates.length, count); assert.equal(f.controller.snapshot().knownCount, 0);
});
test("stop during metadata prevents original request", async () => {
  const gate = deferred(); const f = fixture({ callbacks: { metadata: () => gate.promise } });
  await f.start(); f.env.items.push(item("new")); const work = f.tick(5000); await Promise.resolve(); await Promise.resolve();
  f.controller.stop(); gate.resolve({ fresh: true, item: item("new", { sizeBytes: 10 }) }); await work;
  assert.equal(f.env.calls.some((x) => x.startsWith("transfer")), false);
});
test("late successful close remains factual after stop; no following original", async () => {
  const gate = deferred(); const f = fixture({ callbacks: { transfer: () => gate.promise } });
  await f.start(); f.env.items.push(item("a"), item("b")); await f.tick(5000);
  const work = f.tick(); await Promise.resolve(); await Promise.resolve(); f.controller.stop();
  gate.resolve({ completed: true, bytesTransferred: 10 }); await work;
  assert.equal(f.controller.snapshot().completed, 1); assert.equal(f.controller.snapshot().phase, "stopped");
  assert.equal(f.controller.snapshot().pendingCount, 1);
});
test("wrong byte receipt and uncertain failure never retry or start next", async () => {
  for (const transfer of [async () => ({ completed: true, bytesTransferred: 11 }), async () => { throw Object.assign(new Error(), { outcomeUnknown: true }); }]) {
    const f = fixture({ callbacks: { transfer } }); await f.start(); f.env.items.push(item("a"), item("b"));
    await f.tick(5000); await f.tick(); const count = f.env.calls.length; await f.tick(10000);
    assert.equal(f.controller.snapshot().reason, "transfer-unconfirmed"); assert.equal(f.controller.snapshot().completed, 0);
    assert.equal(f.env.calls.length, count);
  }
});
test("observer-reentrant manual busy wins at each operation boundary", async () => {
  for (const target of ["inventory", "metadata", "transfer"]) {
    let armed = false; let f;
    f = fixture({ callbacks: { onUpdate: (snapshot) => { if (armed && snapshot.operation === target) f.env.busy = true; } } });
    if (target !== "inventory") { await f.start(); f.env.items.push(item("new")); }
    if (target === "transfer") await f.tick(5000);
    armed = true;
    const before = f.env.calls.filter((call) => call.startsWith(target)).length;
    await f.tick(target === "metadata" ? 5000 : 1000);
    assert.equal(f.env.busy, true);
    assert.equal(f.env.calls.filter((call) => call.startsWith(target)).length, before);
    armed = false; f.env.busy = false; await f.tick(1000);
    assert.equal(f.env.calls.filter((call) => call.startsWith(target)).length, before + 1);
  }
});
test("observer failure fails closed before original I/O", async () => {
  const f = fixture({ callbacks: { onUpdate: () => { throw new Error(); } } }); await f.controller.step();
  assert.equal(f.controller.snapshot().reason, "observer-failed"); assert.deepEqual(f.env.calls, []);
});
(async () => { for (const [name, fn] of tests) { await fn(); console.log(`PASS ${name}`); } console.log(`${tests.length} tests passed`); })().catch((error) => { console.error(error); process.exitCode = 1; });
