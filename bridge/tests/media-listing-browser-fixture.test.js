"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { datedListingResponse } = require("./media-listing-browser-fixture.js");
const listing = require("../open_eos_bridge/static/media-listing.js");

const item = (id) => ({ id, name: `${id}.JPG`, kind: "image", captureTime: null, sizeBytes: 123 });
const binding = { requestId: "synthetic-request", sessionId: "synthetic-session", scope: "full" };
const rows = [
  { ...binding, type: "start", seq: 0, limit: null },
  { ...binding, type: "snapshot", seq: 1, revision: 1, offset: 0, total: 2, last: false, items: [item("old-照片")] },
  { ...binding, type: "snapshot", seq: 2, revision: 1, offset: 1, total: 2, last: true, items: [item("new")] },
  { ...binding, type: "complete", seq: 3, revision: 1, count: 2, scopeComplete: true },
];
const wire = (records) => records.map(row => JSON.stringify(row) + "\n").join("");
const date = name => name.startsWith("old") ? "2026-10-07" : "2026-10-09";
const headers = { "content-type": listing.MEDIA_TYPE, "cache-control": "private, no-store" };
const decorate = (body, fields = headers) => datedListingResponse(body, fields, date);
const read = (value) => listing.readResponse(new Response(value.body, { headers: value.headers }), { sessionId: binding.sessionId });

test("dates every real stream snapshot chunk while preserving protocol and UTF-8 identities", async () => {
  const original = wire(rows);
  const output = decorate(original);
  const expected = rows.map(row => row.type === "snapshot" ? {
    ...row, items: row.items.map(value => ({ ...value, captureTime: date(value.name) })),
  } : row);
  assert.equal(output.body, wire(expected));
  assert.equal(output.headers["content-type"], listing.MEDIA_TYPE);
  assert.equal(output.headers["cache-control"], "private, no-store");
  assert.equal(output.headers["x-open-eos-inventory"], undefined);
  assert.equal(output.headers["content-length"], String(Buffer.byteLength(output.body)));
  assert.deepEqual((await read(output)).items, expected.flatMap(row => row.items || []));
  assert.equal(wire(rows), original, "No source fixture mutation");
});

test("legacy strict inventory keeps its header and metadata without another request", async () => {
  const source = { items: [item("old-照片"), item("new")], syntheticMetadata: "unchanged" };
  const output = decorate(JSON.stringify(source), {
    "Content-Type": "application/json; charset=utf-8", "X-Open-Eos-Inventory": "complete-v1",
    "Content-Length": "1", "Content-Encoding": "gzip", "Transfer-Encoding": "chunked",
  });
  const payload = JSON.parse(output.body);
  assert.equal(payload.syntheticMetadata, "unchanged");
  assert.equal(output.headers["x-open-eos-inventory"], "complete-v1");
  assert.equal(output.headers["content-encoding"], undefined);
  assert.equal(output.headers["transfer-encoding"], undefined);
  assert.equal(output.headers["content-length"], String(Buffer.byteLength(output.body)));
  assert.deepEqual((await read(output)).items, source.items.map(value => ({ ...value, captureTime: date(value.name) })));
  assert.equal(source.items[0].captureTime, null);
});

test("empty snapshots and CRLF delimiters remain exact", async () => {
  const empty = [rows[0], { ...rows[1], total: 0, last: true, items: [] }, { ...rows[3], seq: 2, count: 0 }];
  const original = wire(empty).replaceAll("\n", "\r\n");
  assert.equal(decorate(original).body, original);
  assert.deepEqual(await read(decorate(original)), { items: [] });
});

test("error terminal is not replaced with successful completion", async () => {
  const error = { ...binding, type: "error", seq: 3, code: "MEDIA_LIST_FAILED", message: "Synthetic failure" };
  const output = decorate(wire([...rows.slice(0, -1), error]));
  assert.ok(output.body.endsWith(JSON.stringify(error) + "\n"));
  await assert.rejects(read(output), value => value.code === "MEDIA_LIST_FAILED");
});

for (const [name, source] of [
  ["missing terminal", wire(rows.slice(0, -1))],
  ["missing final delimiter", wire(rows).trimEnd()],
  ["wrong terminal count", wire([...rows.slice(0, -1), { ...rows[3], count: 1 }])],
]) {
  test(`date fixture cannot repair ${name}`, async () => {
    await assert.rejects(read(decorate(source)), value => value.code === "INVALID_MEDIA_LISTING");
  });
}

test("unknown formats and malformed JSON fail instead of fabricating a listing", () => {
  assert.throws(() => decorate("{}", { "content-type": "text/plain" }));
  assert.throws(() => decorate("{", { "content-type": "application/json" }));
  assert.throws(() => decorate('{"type":"snapshot","items":null}\n'));
});
