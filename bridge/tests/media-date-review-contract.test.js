"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const library = require("../open_eos_bridge/static/media-library.js");
const raw = fs.readFileSync(path.join(__dirname, "../../contracts/media-date-review/v1/date-review-vectors.json"));
assert.ok(raw.length > 0 && raw.length <= 64 * 1024);
const fixture = JSON.parse(raw);
assert.equal(fixture.schemaVersion, 1);
assert.equal(fixture.instantResolution, "milliseconds-truncated");
assert.equal(fixture.calendar, "proleptic-gregorian-ad-1-9999");
assert.deepEqual(fixture.dateSortPrecisionOrder, ["instant", "floating", "date-only"]);
assert.equal(fixture.unknownAndTies, "loaded-order");
assert.equal(fixture.vectors.length, 48);
assert.equal(new Set(fixture.vectors.map(x => x.id)).size, 48);
for (const vector of fixture.vectors) {
  for (const zone of fixture.displayZones) {
    const actual = library.parseMediaDate(vector.raw, zone);
    const tag = `${vector.id}/${zone}`;
    assert.equal(actual?.kind ?? "unknown", vector.kind, tag);
    assert.equal(actual?.timestamp ?? null, vector.instantMillis, tag);
    assert.equal(actual?.dateKey ?? null, vector.display[zone].dateKey, tag);
    assert.equal(actual?.timeKey ?? null, vector.display[zone].timeKey, tag);
    assert.equal(actual?.hasTime ?? false, ["instant", "floating"].includes(vector.kind), tag);
    if (actual?.kind !== "instant") assert.equal(library.mediaTime({ captureTime: vector.raw }), null, tag);
    const item = { id: vector.id, name: "Synthetic.JPG", captureTime: vector.raw };
    const day = vector.display[zone].dateKey;
    if (day) {
      const filtered = library.itemsForDisplay([item], "all", "camera", "en", { start: day, end: day }, "all", { kind: "all" }, zone);
      assert.equal(filtered[0], item, `${tag}: filter and display share exact day and identity`);
    }
  }
}
const sorted = (items, mode, zone) => library.itemsForDisplay(items, "all", mode, "en", null, "all", { kind: "all" }, zone);
for (const test of fixture.sortCases) {
  assert.deepEqual(sorted(fixture.sortItems, test.sort, test.zone).map(x => x.id), test.expectedIds, `${test.zone}/${test.sort}`);
  if (test.sort === "camera") continue;
  const rank = new Map(test.expectedIds.map((id, index) => [id, index]));
  // Equal instants and unknown metadata are ties, ordered by their actual input positions.
  rank.set("JPEG", rank.get("RAW"));
  rank.set("U_LAST", rank.get("U_FIRST"));
  for (const a of fixture.sortItems) for (const b of fixture.sortItems) for (const c of fixture.sortItems) {
    if (a === b || a === c || b === c) continue;
    const input = [a, b, c];
    const expected = input.map((item, index) => ({ item, index })).sort((l, r) => rank.get(l.item.id) - rank.get(r.item.id) || l.index - r.index);
    assert.deepEqual(sorted(input, test.sort, test.zone).map(x => x.id), expected.map(x => x.item.id), "total-order triple permutation");
  }
}
for (const test of fixture.reviewCases) {
  const result = library.selectReviewItem(fixture.reviewItems, new Set(test.previousIds), test.kind);
  assert.equal(result?.id ?? null, test.expectedId, test.kind);
  if (result) assert.equal(result, fixture.reviewItems.find(item => item.id === result.id));
}
assert.equal(library.parseMediaDate("2026-08-14T12:34:56Z[" + "A".repeat(129) + "]", "UTC"), null);
assert.equal(library.selectReviewItem(fixture.reviewItems, new Set(), "invalid"), null);
assert.equal(library.selectReviewItem([fixture.reviewItems[1], fixture.reviewItems[0]], new Set(), "recent"), fixture.reviewItems[1]);
assert.equal(library.selectReviewItem([fixture.reviewItems[1], fixture.reviewItems[0]], new Set(), "photo"), fixture.reviewItems[0]);
console.log("Media date/review contract: 48 parse vectors x 3 zones, 9 sort cases, 2016 triple orders, 4 review cases passed.");
