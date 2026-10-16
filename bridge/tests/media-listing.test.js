"use strict";

// Real incremental Response bodies with controlled chunks. These are wire-parser
// fixtures, not browser or physical-camera validation.
const assert = require("node:assert/strict");
const test = require("node:test");
const listing = require("../open_eos_bridge/static/media-listing.js");
const encoder = new TextEncoder();
const item = (id = "synthetic-照片-é") => ({ id, name: `${id}.JPG`, kind: "jpeg" });
function records(items = [item()], limit = null) {
  const bind = { requestId: "synthetic-request", sessionId: "synthetic-session", scope: limit === null ? "full" : "recent" };
  return [
    { ...bind, type: "start", seq: 0, limit },
    { ...bind, type: "snapshot", seq: 1, revision: 1, offset: 0, total: items.length, items, last: true },
    { ...bind, type: "complete", seq: 2, revision: 1, count: items.length, scopeComplete: true },
  ];
}
const wire = rows => rows.map(row => JSON.stringify(row) + "\n").join("");
function body() {
  let controller, cancelled = false;
  const stream = new ReadableStream({ start(value) { controller = value; }, cancel() { cancelled = true; } });
  return { response: new Response(stream, { headers: { "Content-Type": listing.MEDIA_TYPE } }),
    send: value => controller.enqueue(typeof value === "string" ? encoder.encode(value) : value),
    close: () => controller.close(), cancelled: () => cancelled };
}
const read = (response, options = {}) => listing.readResponse(response, { sessionId: "synthetic-session", ...options });
const settle = async () => { for (let i = 0; i < 3; i++) await new Promise(setImmediate); };

test("publishes a complete revision before terminal, waits for terminal EOF, and preserves fragmented UTF-8 IDs", async () => {
  const b = body(), seen = [], rows = records();
  let finished = false;
  const promise = read(b.response, { onSnapshot: items => seen.push(items) }).then(value => { finished = true; return value; });
  const bytes = encoder.encode(wire(rows.slice(0, 2)));
  for (const byte of bytes) b.send(new Uint8Array([byte]));
  await settle();
  assert.deepEqual(seen, [[item()]]);
  assert.equal(finished, false);
  b.send(wire(rows.slice(2)));
  await settle();
  assert.equal(finished, false, "Terminal alone cannot hide trailing records");
  b.close();
  assert.deepEqual(await promise, { items: [item()] });
});

test("multi-frame snapshot remains unpublished until last chunk, with 501+ exact IDs and no full-card truncation", async () => {
  const items = Array.from({ length: 1101 }, (_, index) => item(`synthetic-${index}`));
  const base = records(items), chunks = [];
  for (let offset = 0; offset < items.length; offset += 128) chunks.push({ ...base[1], seq: chunks.length + 1,
    offset, items: items.slice(offset, offset + 128), last: offset + 128 >= items.length });
  const b = body(), seen = [];
  const promise = read(b.response, { onSnapshot: items => seen.push(items) });
  b.send(wire([base[0], ...chunks.slice(0, -1)]));
  await settle();
  assert.equal(seen.length, 0);
  b.send(wire([chunks.at(-1), { ...base[2], seq: chunks.length + 1 }]));
  b.close();
  assert.equal((await promise).items.length, 1101);
  assert.equal(seen.length, 1);
});

test("empty card requires an explicit empty snapshot and valid terminal", async () => {
  assert.deepEqual(await read(new Response(wire(records([])), { headers: { "Content-Type": listing.MEDIA_TYPE } })), { items: [] });
});

test("recent 61 sentinel uses exact scope and start limit", async () => {
  const items = Array.from({ length: 61 }, (_, index) => item(`synthetic-${index}`));
  assert.equal((await read(new Response(wire(records(items, 61)), { headers: { "Content-Type": listing.MEDIA_TYPE } }), { limit: 61 })).items.length, 61);
});

test("legacy JSON is parsed from the same one response", async () => {
  const seen = [];
  assert.deepEqual(await read(Response.json({ items: [item()] }), { onSnapshot: items => seen.push(items) }), { items: [item()] });
  assert.equal(seen.length, 0);
});

const mutations = {
  "missing terminal": rows => rows.slice(0, 2),
  "no snapshot": rows => [rows[0], { ...rows[2], seq: 1, count: 0, revision: 0 }],
  "duplicate terminal": rows => [...rows, { ...rows[2], seq: 3 }],
  "snapshot after terminal": rows => [...rows, { ...rows[1], seq: 3, revision: 2 }],
  "bad first record": rows => [{ ...rows[0], type: "snapshot" }, ...rows.slice(1)],
  "wrong request": rows => rows.map((row, i) => i === 1 ? { ...row, requestId: "another" } : row),
  "wrong session": rows => rows.map((row, i) => i === 1 ? { ...row, sessionId: "another" } : row),
  "wrong scope": rows => rows.map((row, i) => i === 1 ? { ...row, scope: "recent" } : row),
  "wrong limit": rows => [{ ...rows[0], limit: 61 }, ...rows.slice(1)],
  "empty request id": rows => rows.map(row => ({ ...row, requestId: "" })),
  "overlong request id": rows => rows.map(row => ({ ...row, requestId: "é".repeat(129) })),
  "sequence gap": rows => rows.map((row, i) => i === 1 ? { ...row, seq: 2 } : row),
  "unsafe sequence": rows => rows.map((row, i) => i === 1 ? { ...row, seq: Number.MAX_SAFE_INTEGER + 1 } : row),
  "negative count": rows => rows.map((row, i) => i === 2 ? { ...row, count: -1 } : row),
  "fractional count": rows => rows.map((row, i) => i === 2 ? { ...row, count: 1.5 } : row),
  "string revision": rows => rows.map((row, i) => i === 1 ? { ...row, revision: "1" } : row),
  "revision gap": rows => rows.map((row, i) => i === 1 ? { ...row, revision: 2 } : row),
  "wrong offset": rows => rows.map((row, i) => i === 1 ? { ...row, offset: 1 } : row),
  "wrong total": rows => rows.map((row, i) => i === 1 ? { ...row, total: 2 } : row),
  "unsafe total": rows => rows.map((row, i) => i === 1 ? { ...row, total: Number.MAX_SAFE_INTEGER + 1 } : row),
  "excess total": rows => rows.map((row, i) => i === 1 ? { ...row, total: 100001 } : row),
  "last is string": rows => rows.map((row, i) => i === 1 ? { ...row, last: "true" } : row),
  "non-final chunk fills total": rows => rows.map((row, i) => i === 1 ? { ...row, last: false } : row),
  "duplicate IDs": rows => rows.map((row, i) => i === 1 ? { ...row, total: 2, items: [item(), item()] } : row),
  "invalid ID": rows => rows.map((row, i) => i === 1 ? { ...row, items: [{ ...item(), id: 7 }] } : row),
  "too many items per frame": rows => rows.map((row, i) => i === 1 ? { ...row, total: 129, items: Array.from({ length: 129 }, (_, n) => item(`${n}`)) } : row),
  "wrong terminal revision": rows => rows.map((row, i) => i === 2 ? { ...row, revision: 2 } : row),
  "wrong terminal count": rows => rows.map((row, i) => i === 2 ? { ...row, count: 0 } : row),
  "scope incomplete": rows => rows.map((row, i) => i === 2 ? { ...row, scopeComplete: false } : row),
};
for (const [name, change] of Object.entries(mutations)) test(`rejects ${name} without claiming completion`, async () => {
  await assert.rejects(read(new Response(wire(change(records())), { headers: { "Content-Type": listing.MEDIA_TYPE } })));
});

test("partial survives a terminal error and parser does not treat it as completion", async () => {
  const rows = records(), seen = [];
  rows[2] = { ...rows[2], type: "error", code: "CAMERA_BUSY", message: "Synthetic camera busy." };
  await assert.rejects(read(new Response(wire(rows), { headers: { "Content-Type": listing.MEDIA_TYPE } }), {
    onSnapshot: items => seen.push(items),
  }), /Synthetic camera busy/);
  assert.deepEqual(seen, [[item()]]);
});

for (const malformed of ["{\n", "\n", wire(records()).trimEnd(), "x".repeat(65536) + "\n",
  new Uint8Array([0xff, 10])]) test("rejects malformed, unterminated, oversized or invalid UTF-8 frames", async () => {
  await assert.rejects(read(new Response(malformed, { headers: { "Content-Type": listing.MEDIA_TYPE } })));
});

test("abort cancels a stalled reader immediately and keeps the last partial", async () => {
  const b = body(), controller = new AbortController(), seen = [];
  const pending = read(b.response, { signal: controller.signal, onSnapshot: items => seen.push(items) });
  b.send(wire(records().slice(0, 2)));
  await settle();
  controller.abort();
  await assert.rejects(pending, { name: "AbortError" });
  assert.equal(b.cancelled(), true);
  assert.deepEqual(seen, [[item()]]);
});

for (const value of [{}, { items: null }, { items: [{ id: 5 }] }]) test("malformed legacy JSON fails closed", async () => {
  await assert.rejects(read(Response.json(value)));
});

test("an incomplete next revision followed by error keeps only the earlier completed snapshot", async () => {
  const rows = records();
  const partial = { ...rows[1], seq: 2, revision: 2, total: 2, items: [item("synthetic-next")], last: false };
  const error = { ...rows[2], seq: 3, type: "error", code: "LIMIT", message: "Synthetic limit reached." };
  const seen = [];
  await assert.rejects(read(new Response(wire([rows[0], rows[1], partial, error]), { headers: { "Content-Type": listing.MEDIA_TYPE } }), {
    onSnapshot: items => seen.push(items),
  }), /Synthetic limit/);
  assert.deepEqual(seen, [[item()]]);
});

test("a revision may replace earlier ordering while preserving exact composed and decomposed IDs", async () => {
  const items = [item("synthetic-é"), item("synthetic-e\u0301"), item(" synthetic-é")];
  const rows = records(items), seen = [];
  const next = { ...rows[1], revision: 2, seq: 2, items: [...items].reverse() };
  const terminal = { ...rows[2], revision: 2, seq: 3 };
  const result = await read(new Response(wire([rows[0], rows[1], next, terminal]), { headers: { "Content-Type": listing.MEDIA_TYPE } }), {
    onSnapshot: items => seen.push(items),
  });
  assert.deepEqual(seen, [items, [...items].reverse()]);
  assert.deepEqual(result.items, [...items].reverse());
});

test("per-revision byte budget fails with explicit error and preserves the previous partial", async () => {
  const rows = records(), seen = [];
  let index = 0;
  const response = new Response(new ReadableStream({ pull(controller) {
    if (index < 2) { controller.enqueue(encoder.encode(wire([rows[index++]]))); return; }
    const n = index++ - 2;
    controller.enqueue(encoder.encode(wire([{ ...rows[1], seq: n + 2, revision: 2, offset: n,
      total: 2000, items: [{ ...item(`synthetic-${n}`), padding: "x".repeat(65000) }], last: false }])));
  } }), { headers: { "Content-Type": listing.MEDIA_TYPE } });
  await assert.rejects(read(response, { onSnapshot: items => seen.push(items) }));
  assert.deepEqual(seen, [[item()]]);
  assert.ok(index > 1000, "Failure must exercise the aggregate budget, not one large frame");
  assert.ok(index < 2000);
});

for (const [name, transform] of [
  ["duplicate decoded sequence key", text => text.replace('"seq":1', '"seq":0,"\\u0073eq":1')],
  ["duplicate item identity", text => text.replace('"id":', '"id":"synthetic-other","id":')],
  ["unpaired high surrogate ID", text => text.replace('"id":"synthetic-照片-é"', '"id":"\\ud800"')],
  ["unpaired low surrogate request", text => text.replace('"requestId":"synthetic-request"', '"requestId":"\\udc00"')],
]) test(`strict stream rejects ${name}`, async () => {
  await assert.rejects(read(new Response(transform(wire(records())), { headers: { "Content-Type": listing.MEDIA_TYPE } })));
});

for (const text of ['{"items":[],"\\u0069tems":[]}', '{"items":[{"id":"\\ud800","name":"synthetic","kind":"jpeg"}]}']) {
  test("legacy listing rejects ambiguous decoded keys or unroundtrippable identity", async () => {
    await assert.rejects(read(new Response(text, { headers: { "Content-Type": "application/json" } })));
  });
}

test("valid supplementary-plane Unicode and punctuation inside JSON strings remain exact", async () => {
  const value = item('synthetic-😀-\\"{},[]');
  assert.deepEqual((await read(new Response(wire(records([value])), { headers: { "Content-Type": listing.MEDIA_TYPE } }))).items, [value]);
});

test("non-2xx error JSON has a 64 KiB read bound and cancels a larger body", async () => {
  let pulls = 0, cancelled = false;
  const response = new Response(new ReadableStream({
    pull(controller) { pulls++; controller.enqueue(encoder.encode("x".repeat(40000))); },
    cancel() { cancelled = true; },
  }), { status: 503, headers: { "Content-Type": "application/json" } });
  await assert.rejects(listing.readError(response));
  assert.ok(pulls <= 3);
  assert.equal(cancelled, true);
});

test("a valid bounded error envelope keeps its actionable message", async () => {
  const value = { error: { code: "BUSY", message: "Synthetic busy." } };
  assert.deepEqual(await listing.readError(Response.json(value, { status: 409 })), value);
});
