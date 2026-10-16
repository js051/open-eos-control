"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const mutations = require("../open_eos_bridge/static/media-mutations.js");
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "../../contracts/media-mutation/v1/media-mutation-vectors.json"), "utf8"));
const fields = { protection: "protected", rating: "rating", rotation: "rotationDegrees", archive: "archived" };
const roster = (count = 3) => Array.from({ length: count }, (_, index) => ({ id: `item-${index}`, loaded: true, hidden: false,
  item: { id: `item-${index}`, name: `SYNTHETIC_${index}.JPG`, archived: false } }));

for (const vector of fixture.metadataVectors.filter((row) => row.profile === "normalized")) {
  test(`normalized raw evidence: ${vector.id}`, () => {
    const field = fields[vector.field];
    const parsed = mutations.parseReadback(`{"id":"synthetic","${field}":${vector.rawValueJSON}}`);
    const value = mutations.valueFor(field, parsed[field]);
    assert.equal(value !== null, vector.known);
    if (vector.known) assert.equal(value, vector.value);
  });
}
for (const vector of fixture.readbackVectors.filter((row) => row.profile === "normalized")) {
  test(`exact readback: ${vector.id}`, () => {
    const parts = [];
    if (vector.returnedIdPresent) parts.push(`"id":${JSON.stringify(vector.returnedId)}`);
    if (vector.rawValueJSON !== null) parts.push(`"${fields[vector.operation]}":${vector.rawValueJSON}`);
    const response = mutations.parseReadback(`{${parts.join(",")}}`);
    assert.equal(mutations.confirms(response, vector.requestedId, mutations.operation(vector.operation, vector.target)),
      vector.outcome === "confirmed");
  });
}
for (const vector of fixture.batchVectors) {
  test(`shared batch: ${vector.id}`, async () => {
    const controller = new AbortController();
    let owner = true;
    const sent = [];
    const result = await mutations.run(roster(vector.events.length), mutations.operation("rating", 3), {
      signal: controller.signal, isCurrent: () => owner,
      preflight: (row) => {
        const event = vector.events[Number(row.id.split("-")[1])];
        if (event === "failed-not-sent") throw new Error("Synthetic local preparation failure");
        if (event === "cancel-before") controller.abort();
        return event === "not-started" ? "unavailable" : null;
      },
      dispatch: async (row) => {
        const index = Number(row.id.split("-")[1]);
        sent.push(index);
        const event = vector.events[index];
        if (event === "cancel-during-confirmed" || event === "cancel-during-error") controller.abort();
        if (event === "cancel-during-error") throw new Error("Synthetic abort");
        if (event === "owner-lost-during") owner = false;
        return { id: row.id, rating: event === "uncertain" ? 2 : 3 };
      },
    });
    assert.deepEqual(result.results.map((row) => row.status), vector.outcomes);
    assert.deepEqual(sent, vector.dispatchedIndices);
  });
}
for (const vector of fixture.rosterVectors) {
  test(`frozen roster: ${vector.id}`, () => {
    const rows = mutations.freezeRoster(vector.selectedIds.map((id) => ({ id, item: { id },
      loaded: vector.loadedIds.includes(id), hidden: !vector.visibleIds.includes(id) })));
    assert.deepEqual(rows.map((row) => row.id), vector.rosterIds);
    assert.deepEqual(rows.filter((row) => !mutations.qualify(row, mutations.operation("rating", 3), true)).map((row) => row.id), vector.eligibleIds);
    assert.equal(rows.filter((row) => row.loaded && row.hidden).length, vector.hiddenCount);
    assert.equal(rows.filter((row) => !row.loaded).length, vector.unloadedCount);
    assert.ok(Object.isFrozen(rows) && rows.every((row) => Object.isFrozen(row) && Object.isFrozen(row.item)));
  });
}

test("1,000 exact IDs accepted; 1,001 rejected as a whole before any write", async () => {
  assert.equal(mutations.freezeRoster(roster(1000)).length, 1000);
  let writes = 0;
  await assert.rejects(mutations.run(roster(1001), mutations.operation("rating", 3), {
    dispatch() { writes += 1; },
  }), RangeError);
  assert.equal(writes, 0);
});

test("HTTP rejection, unreadable ACK and mismatched field are always uncertain after dispatch", async () => {
  for (const dispatch of [async () => { throw Object.assign(new Error("HTTP error"), { status: 403 }); },
    async () => ({ ok: true }), async (row) => ({ id: row.id, protected: true })]) {
    const result = await mutations.run(roster(), mutations.operation("rating", 3), { dispatch });
    assert.deepEqual(result.results.map((row) => row.status), ["uncertain", "not-started", "not-started"]);
  }
});

test("archive requires explicit per-item boolean; other capabilities preserve existing qualification", () => {
  const row = { id: "a", item: { id: "a" }, loaded: true };
  assert.equal(mutations.qualify(row, mutations.operation("archive", true), true), "unsupported-item");
  for (const [suffix, value] of [["protection", true], ["rating", 5], ["rotation", 90]]) {
    assert.equal(mutations.qualify(row, mutations.operation(suffix, value), true), null);
    assert.equal(mutations.qualify(row, mutations.operation(suffix, value), false), "unsupported");
  }
});

test("raw parser rejects duplicate critical fields, nested or rounded numeric lookalikes", () => {
  for (const raw of ['{"id":"a","id":"a","rating":3}', '{"id":"a","rating":2,"rating":3}',
    '{"id":"a","rating":3.00000000000000000000000001}', '{"id":"a","rating":3e0}',
    '{"id":"a","rating":{"nested":3}}']) {
    assert.equal(mutations.confirms(mutations.parseReadback(raw), "a", mutations.operation("rating", 3)), false, raw);
  }
  const parsed = mutations.parseReadback(' {"other":{"text":"},\\\"rating\\\":3","array":[0,{"rating":3}]}, "id":"a", "rating":3 } ');
  assert.equal(mutations.confirms(parsed, "a", mutations.operation("rating", 3)), true);
  assert.throws(() => mutations.parseReadback("x"), SyntaxError);
  assert.throws(() => mutations.parseReadback(" ".repeat(262145)), TypeError);
});

test("only one write is in flight, frozen selection order is unchanged, and no automatic replay", async () => {
  let active = 0, peak = 0;
  const writes = [];
  const rows = roster();
  const frozen = mutations.freezeRoster(rows);
  rows.reverse(); rows[0].item.id = "changed";
  const result = await mutations.run(frozen, mutations.operation("rotation", 270), {
    dispatch: async (row) => {
      active += 1; peak = Math.max(peak, active); writes.push(row.id);
      await Promise.resolve(); active -= 1;
      return { id: row.id, rotationDegrees: 270 };
    },
  });
  assert.equal(peak, 1);
  assert.deepEqual(writes, ["item-0", "item-1", "item-2"]);
  assert.ok(result.results.every((row) => row.status === "confirmed"));
});

test("observer failure is fail-closed; no stale callback after owner retirement", async () => {
  let owner = true, updates = 0, writes = 0;
  const result = await mutations.run(roster(), mutations.operation("rating", 3), {
    isCurrent: () => owner, onUpdate() { updates += 1; },
    dispatch: async (row) => { writes += 1; owner = false; return { id: row.id, rating: 3 }; },
  });
  assert.equal(updates, 2); assert.equal(writes, 1);
  assert.deepEqual(result.results.map((row) => row.status), ["uncertain", "not-started", "not-started"]);
  const other = await mutations.run(roster(), mutations.operation("rating", 3), {
    onUpdate() { throw new Error("Synthetic observer failure"); }, dispatch() { throw new Error("Must not write"); },
  });
  assert.ok(other.results.every((row) => row.status === "not-started"));
});

test("explicit per-item writable=false vetoes every operation without admitting unknown capability", () => {
  for (const [suffix, target] of [["protection", true], ["rating", 3], ["rotation", 90], ["archive", true]]) {
    const requested = mutations.operation(suffix, target);
    const entry = { id: "a", loaded: true, item: { id: "a", archived: false, [`${suffix}Writable`]: false } };
    assert.equal(mutations.qualify(entry, requested, true), "unsupported-item");
    entry.item[`${suffix}Writable`] = true;
    assert.equal(mutations.qualify(entry, requested, false), "unsupported");
    assert.equal(mutations.qualify(entry, requested, true), null);
  }
});

const eligibilityFixture = JSON.parse(fs.readFileSync(path.join(__dirname, "../../contracts/media-mutation/v1/media-mutation-eligibility-vectors.json"), "utf8"));
for (const vector of eligibilityFixture.vectors) {
  test(`shared eligibility uses production qualify: ${vector.id}`, () => {
    const target = { protection: true, rating: 3, rotation: 90, archive: true }[vector.operation];
    const reason = mutations.qualify({ id: vector.requestedId, item: vector.item, loaded: vector.loaded },
      mutations.operation(vector.operation, target), vector.supported);
    assert.equal(reason === null, vector.eligible);
  });
}
