"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const library = require("../open_eos_bridge/static/media-library.js");
const { createController } = require("../open_eos_bridge/static/media-auto-import.js");
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "../../contracts/media-type/v1/media-type-vectors.json")));
for (const row of fixture.cases) test(`media type fixed vector: ${row.id}`, () => {
  assert.equal(library.classifyMedia(row.item), row.expected.classification);
  assert.equal(library.isPhoto(row.item), row.expected.isPhoto);
  assert.equal(library.isVideo(row.item), row.expected.isVideo);
  assert.equal(library.automaticJpegEligible(row.item), row.expected.automaticJpegEligible);
  assert.equal(library.canRequestThumbnail(row.item), row.expected.isPhoto || row.expected.isVideo);
  assert.deepEqual(library.itemsForDisplay([row.item], "photo", "camera", "en"), row.expected.isPhoto ? [row.item] : []);
  assert.deepEqual(library.itemsForDisplay([row.item], "video", "camera", "en"), row.expected.isVideo ? [row.item] : []);
  assert.deepEqual(library.itemsForDisplay([row.item], "all", "camera", "en"), [row.item]);
});
for (const row of fixture.freshMetadataCases) test(`fresh metadata fixed vector: ${row.id}`, () => {
  assert.equal(library.freshAutomaticJpegMetadata(row.original, row.metadata, row.fresh), row.expectedAdmitted);
});
for (const row of fixture.selectionCases) test(`review selection fixed vector: ${row.id}`, () => {
  const byId = Object.fromEntries(fixture.cases.map((entry) => [entry.id, entry.item]));
  const selected = library.selectReviewItem(row.listingCases.map((id) => byId[id]), new Set(row.knownCaseIds.map((id) => byId[id].id)), row.intent);
  assert.equal(selected, row.expectedCaseId ? byId[row.expectedCaseId] : null);
});
for (const row of fixture.freshMetadataCases) test(`real foreground policy fresh gate: ${row.id}`, async () => {
  let time = 0, added = false;
  const calls = [], transferred = [];
  const controller = createController({ inventory: async () => ({ complete: true, items: added ? [row.original] : [] }),
    metadata: async () => { calls.push("info"); return { fresh: row.fresh, item: { ...row.metadata, sizeBytes: 10 } }; },
    transfer: async (item) => { calls.push("original"); transferred.push(item); return { completed: true, bytesTransferred: 10 }; },
    isCurrent: () => true, isForeground: () => true, now: () => time });
  await controller.step(); time = 1000; await controller.step(); added = true; time = 6000; await controller.step();
  time = 7000; await controller.step();
  assert.equal(transferred.length, row.expectedAdmitted ? 1 : 0);
  if (row.expectedAdmitted) {
    assert.deepEqual(transferred[0], { ...row.metadata, sizeBytes: 10 });
    assert.deepEqual(calls, ["info", "info", "original"]);
  } else assert.equal(controller.snapshot().reason, "fresh-metadata-unavailable");
});
test("real foreground gate keeps zero-size invalidation pending until stable positive fresh metadata", async () => {
  let time = 0, added = false, size = 0, originals = 0;
  const original = fixture.freshMetadataCases[0].original;
  const controller = createController({ inventory: async () => ({ complete: true, items: added ? [original] : [] }),
    metadata: async () => ({ fresh: true, item: { ...original, sizeBytes: size, contentType: size ? null : "application/octet-stream" } }),
    transfer: async () => { originals++; return { completed: true, bytesTransferred: 10 }; },
    isCurrent: () => true, isForeground: () => true, now: () => time });
  await controller.step(); time = 1000; await controller.step(); added = true; time = 6000; await controller.step();
  assert.equal(controller.snapshot().reason, null); assert.equal(originals, 0);
  size = 10; time = 7000; await controller.step(); assert.equal(originals, 0);
  time = 8000; await controller.step(); assert.equal(originals, 1);
});
test("raw malformed optional MIME and blank identities never gain automatic eligibility", () => {
  for (const contentType of [false, 42, {}, [], "image/jpeg; x=\u007f", "image/jpeg; x=\u009f"]) {
    const item = { ...fixture.freshMetadataCases[0].original, contentType };
    assert.equal(library.classifyMedia(item), "unknown"); assert.equal(library.automaticJpegEligible(item), false);
  }
  for (const id of [null, "", " ", 42]) assert.equal(library.automaticJpegEligible({ ...fixture.freshMetadataCases[0].original, id }), false);
});
