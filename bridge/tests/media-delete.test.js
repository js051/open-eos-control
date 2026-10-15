"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const policy = require("../open_eos_bridge/static/media-delete.js");
const { createSelection } = require("../open_eos_bridge/static/media-batch.js");
const vectors = require("../../contracts/media-delete/v1/delete-outcome-vectors.json");
for (const kind of ["eligibility", "admission", "outcome", "httpReceipt", "selection", "roster"]) {
  for (const vector of vectors[kind]) test(`delete contract ${vector.id}`, () => {
    const actual = kind === "selection" ? { selectedIds: policy.consumeSelection(vector.input) }
      : kind === "roster" ? policy.roster(vector.input.selectedIds)
      : ["outcome", "httpReceipt"].includes(kind) ? { status: policy[kind](vector.input) }
      : policy[kind](vector.input);
    assert.deepEqual(actual, vector.expected);
  });
}
for (const vector of vectors.batch) test(`delete contract ${vector.id}`, async () => {
  const controller = new AbortController();
  const dispatches = vector.input.eligible.map(() => 0);
  if (vector.input.cancelBeforeIndex === 0) controller.abort();
  const result = await policy.runBatch(vector.input.eligible.map((eligible, index) => ({
    id: String(index), eligible, reason: eligible ? "eligible" : "protected",
  })), { signal: controller.signal, transfer: async (row, { attempt, beforeDispatch }) => {
    const index = Number(row.id), response = vector.input.responses[index];
    if (response !== "not-sent" && beforeDispatch()) {
      dispatches[index] += 1;
      if (response === "confirmed") attempt.confirm();
      else if (response === "rejected") attempt.reject();
    }
    if (vector.input.cancelBeforeIndex === index + 1) controller.abort();
  }});
  assert.deepEqual({ statuses: result.results.map(row => row.status), dispatches }, vector.expected);
});

test("selection revisions consume only unchanged confirmed IDs, with opaque IDs", () => {
  const selection = createSelection();
  selection.replaceLoaded(["A", "a", "é", "é"].map(id => ({ id })));
  selection.setMany(["A", "a", "é", "é"], true);
  const first = selection.token("A"), other = selection.token("a");
  selection.setSelected("A", false); selection.setSelected("A", true);
  assert.equal(selection.consumeIfToken("A", first), false);
  assert.equal(selection.consumeIfToken("a", other), true);
  selection.replaceLoaded([]);
  assert.deepEqual(selection.snapshot().map(row => row.id), ["é", "é", "A"]);
  selection.clear(); selection.replaceLoaded([{ id: "A" }]); selection.setSelected("A", true);
  assert.equal(selection.consumeIfToken("A", first), false);
});

test("source classification never guesses from filename or camera model", () => {
  for (const [id, engine, expected] of [["ccapi:A","ccapi","camera"],["gphoto2:A","libgphoto2","camera"],
    ["gphoto2-host:A","libgphoto2","desktop-host"],["gphoto2-host:A","ccapi","unknown"],
    ["IMG.JPG","ccapi","unknown"],["ccapi:A","unknown","unknown"]]) {
    assert.equal(policy.sourceFor({ id, name: "Canon" }, engine), expected);
  }
});

async function httpAttempt(response, { abortAfterReceipt = false, permit = true } = {}) {
  const controller = new AbortController(), attempt = policy.createAttempt();
  attempt.start(); const calls = [];
  await policy.transferHttp("/v1/session/A/media/opaque", { attempt, signal: controller.signal,
    beforeDispatch: () => permit && attempt.beforeDispatch(),
    fetchImpl: async (...args) => { calls.push(args); return response; },
  });
  if (abortAfterReceipt) { controller.abort(); attempt.cancel(); }
  return { result: attempt.snapshot(), calls };
}
for (const status of [200,201,202,204,206,301,307,308,400,401,403,404,408,409,429,499,500,503]) {
  test(`real Fetch response classification ${status}`, async () => {
    const response = new Response(status === 204 ? null : "body", { status });
    const { result, calls } = await httpAttempt(response, { abortAfterReceipt: true });
    assert.equal(result.status, policy.httpReceipt({ status, bodyBytes: status === 204 ? 0 : 4,
      responseComplete: true, framingValid: true }));
    assert.equal(calls.length, 1);
    assert.equal(calls[0][1].method, "DELETE");
    assert.equal(calls[0][1].redirect, "error");
    assert.equal(calls[0][1].credentials, "omit");
  });
}
test("transport admission and invalid framing cannot confirm deletion", async () => {
  assert.equal((await httpAttempt(new Response(null, { status: 204 }), { permit: false })).calls.length, 0);
  for (const headers of [{ "content-length": "1" }, { "content-length": "0", "transfer-encoding": "chunked" },
    { "content-length": "wat" }, { "transfer-encoding": "chunked" }]) {
    assert.equal((await httpAttempt(new Response(null, { status: 204, headers }))).result.status, "uncertain");
  }
});
test("lost response, oversized and truncated body remain uncertain, no retry", async () => {
  for (const mode of ["lost", "oversized", "truncated"]) {
    const attempt = policy.createAttempt(); attempt.start(); let calls = 0;
    try {
      await policy.transferHttp("/target", { attempt, beforeDispatch: () => attempt.beforeDispatch(), fetchImpl: async () => {
        calls += 1;
        if (mode === "lost") throw new Error("lost ACK");
        if (mode === "oversized") return new Response("x".repeat(65537), { status: 403 });
        return new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array([1])); c.error(new Error("truncated")); } }), { status: 403 });
      }});
    } catch (_) { /* Production queue stores this as uncertain. */ }
    assert.equal(calls, 1); assert.equal(attempt.snapshot().status, "uncertain");
  }
});
test("confirmation survives retirement, prevents further rows and no replay", async () => {
  let current = true, calls = 0;
  const result = await policy.runBatch([{ id: "A", eligible: true }, { id: "B", eligible: true }], {
    isCurrent: () => current,
    transfer: async (_, { beforeDispatch, attempt }) => { assert.equal(beforeDispatch(), true); calls++; current = false; attempt.confirm(); },
  });
  assert.deepEqual(result.results.map(x => x.status), ["confirmed", "not-started"]);
  assert.equal(calls, 1);
});
test("observer cannot erase dispatch evidence or trigger further deletion", async () => {
  let calls = 0;
  const result = await policy.runBatch([{ id: "A", eligible: true }, { id: "B", eligible: true }], {
    onUpdate(snapshot) { if (snapshot.results[0].status === "confirmed") throw new Error("observer failed"); },
    transfer: async (_, { attempt, beforeDispatch }) => { if (beforeDispatch()) { calls++; attempt.confirm(); } },
  });
  assert.equal(calls, 1); assert.deepEqual(result.results.map(x => x.status), ["confirmed", "not-started"]);
});
test("bounded history preserves complete newest batches", () => {
  assert.equal(policy.boundedHistory(Array.from({ length: 25 }, (_, id) => ({ id, results: [{}] }))).length, 20);
  assert.deepEqual(policy.boundedHistory([0,1,2].map(id => ({ id, results: Array(1000).fill({}) }))).map(x => x.id), [1,2]);
});
