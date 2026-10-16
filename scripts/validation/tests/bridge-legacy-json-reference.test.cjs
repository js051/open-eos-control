"use strict";

// Execute the real PC parser as a contract reference. This is not execution of
// either mobile implementation; their native tests remain independently required.
const assert = require("node:assert/strict");
const { createHash } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const modulePath = process.env.OPEN_EOS_PC_LISTING_MODULE;
assert.ok(modulePath, "Set OPEN_EOS_PC_LISTING_MODULE to the actual PC media-listing.js");
const resolved = path.resolve(modulePath);
const listing = require(resolved);
const sourceHash = createHash("sha256").update(fs.readFileSync(resolved)).digest("hex");
const item = id => ({ id, name: "IMG.JPG", kind: "image" });
const read = (body, options = {}) => listing.readResponse(
  new Response(body, { headers: { "Content-Type": "application/json" } }),
  { sessionId: "synthetic-session", ...options },
);

test("records the actual PC reference source", t => {
  assert.equal(listing.MAX_ITEMS, 100_000);
  t.diagnostic(`PC media-listing.js SHA256 ${sourceHash}`);
});

const valid = JSON.stringify(item("image"));
for (const [name, body, limit] of [
  ["non-object row", `{"items":[${valid},null]}`, null],
  ["invalid row", `{"items":[${valid},{"id":"broken","kind":"image"}]}`, null],
  ["duplicate identity", `{"items":[${valid},${valid}]}`, null],
  ["escaped duplicate identity", `{"items":[${valid},{"id":"\\u0069mage","name":"OTHER.JPG","kind":"image"}]}`, null],
  ["requested limit", JSON.stringify({ items: [item("image"), item("other")] }), 1],
]) {
  test(`legacy rejects ${name} without publishing a replacement`, async () => {
    const snapshots = [];
    await assert.rejects(read(body, { limit, onSnapshot: rows => snapshots.push(rows) }));
    assert.deepEqual(snapshots, []);
  });
}

test("legacy accepts empty and exact requested limit with exact Unicode identities", async () => {
  const ids = [" 影像%2F+ ", "caf\u00e9", "cafe\u0301"];
  for (const expected of [[], ids]) {
    const result = await read(JSON.stringify({ items: expected.map(item) }), { limit: 3 });
    assert.deepEqual(result.items.map(row => row.id), expected);
  }
});

for (const count of [100_000, 100_001]) {
  test(`legacy full scope ${count} item boundary`, async () => {
    const body = JSON.stringify({ items: Array.from({ length: count }, (_, index) => item(`image-${index}`)) });
    const snapshots = [];
    const pending = read(body, { onSnapshot: rows => snapshots.push(rows) });
    if (count === 100_000) {
      const result = await pending;
      assert.equal(result.items.length, count);
      assert.equal(result.items.at(-1).id, "image-99999");
    } else await assert.rejects(pending);
    // The PC legacy path returns once; its UI publishes only after success.
    assert.deepEqual(snapshots, []);
  });
}
