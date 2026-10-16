"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");

function item(id, folderId, folderLabel, extra = {}) {
  return Object.freeze({ id, name: `${id}.JPG`, folderId, folderLabel, ...extra });
}

const ids = (items) => items.map(({ id }) => id);
const display = (items, folderFilter, type = "all", range = null, ratingFilter = "all", sort = "camera") =>
  mediaLibrary.itemsForDisplay(items, type, sort, "en-US", range, ratingFilter, folderFilter);

test("folder provenance requires a valid pair and preserves exact opaque values", () => {
  const folderId = " synthetic:card%2Fone+folder ";
  const folderLabel = " card-one/DCIM/100 SYNTHETIC ";
  assert.deepEqual(mediaLibrary.folderForItem(item("raw", folderId, folderLabel)),
    { id: folderId, label: folderLabel });
  const invalid = [undefined, null, "", "  ", 0, 1, true, [], {}, new String("folder"), Symbol("folder")];
  for (const value of invalid) {
    assert.equal(mediaLibrary.folderForItem(item("bad-id", value, "DCIM/100SYNTH")), null);
    assert.equal(mediaLibrary.folderForItem(item("bad-label", "synthetic-id", value)), null);
  }
  assert.equal(mediaLibrary.folderForItem(null), null);
  assert.equal(mediaLibrary.folderForItem(undefined), null);
});

test("folder length limits use UTF-16 units and every C0/C1 control is rejected", () => {
  for (const [id, label] of [["i".repeat(4096), "l".repeat(1024)], ["📁".repeat(2048), "📷".repeat(512)]]) {
    assert.deepEqual(mediaLibrary.folderForItem(item("boundary", id, label)), { id, label });
    assert.equal(mediaLibrary.folderForItem(item("long-id", `${id}i`, label)), null);
    assert.equal(mediaLibrary.folderForItem(item("long-label", id, `${label}l`)), null);
  }
  const controlCodes = [...Array.from({ length: 32 }, (_, code) => code),
    ...Array.from({ length: 33 }, (_, code) => code + 127)];
  for (const code of controlCodes) {
    const control = String.fromCharCode(code);
    assert.equal(mediaLibrary.folderForItem(item("control-id", `folder${control}id`, "label")), null, `ID U+${code.toString(16)}`);
    assert.equal(mediaLibrary.folderForItem(item("control-label", "id", `folder${control}label`)), null, `label U+${code.toString(16)}`);
  }
});

test("unknown provenance is never inferred from filenames, item IDs or host paths", () => {
  const unavailable = () => { throw new Error("Folder evidence must not read unrelated item metadata"); };
  const opaque = Object.freeze({
    get id() { return unavailable(); }, get name() { return unavailable(); },
    get path() { return unavailable(); }, get hostPath() { return unavailable(); },
    get folder() { return unavailable(); },
  });
  assert.equal(mediaLibrary.folderForItem(opaque), null);
  assert.equal(mediaLibrary.folderMatchesFilter(opaque, { kind: "unknown" }), true);
  const items = [opaque, item("card/DCIM/file.JPG", undefined, undefined, { path: "/host/capture/DCIM" }),
    item("partial-id", "synthetic-id", undefined), item("partial-label", undefined, "DCIM/100SYNTH")];
  assert.equal(mediaLibrary.countUnknownFolders(items), 4);
  assert.deepEqual(mediaLibrary.folderOptions(items), []);
});

test("folder options group only exact IDs and remain deterministic despite conflicting labels", () => {
  const items = Object.freeze([
    item("first", "synthetic-card-a", "Z/DCIM"),
    item("second", "synthetic-card-b", "A/DCIM"),
    item("third", "synthetic-card-a", "A/DCIM"),
    item("fourth", "synthetic-card-a", "Z/DCIM"),
    item("case", "Synthetic-card-a", "A/DCIM"),
    item("unknown", "missing-label", null),
  ]);
  const expected = [
    { id: "Synthetic-card-a", label: "A/DCIM", count: 1, labelConflict: false },
    { id: "synthetic-card-a", label: "A/DCIM", count: 3, labelConflict: true },
    { id: "synthetic-card-b", label: "A/DCIM", count: 1, labelConflict: false },
  ];
  assert.deepEqual(mediaLibrary.folderOptions(items), expected);
  assert.deepEqual(mediaLibrary.folderOptions([...items].reverse()), expected);
  assert.deepEqual(mediaLibrary.folderOptions([items[3], items[1], items[5], items[2], items[4], items[0]]), expected);
  assert.equal(mediaLibrary.countUnknownFolders(items), 1);
  assert.equal(mediaLibrary.countUnknownFolders([]), 0);
  assert.deepEqual(mediaLibrary.folderOptions([]), []);
});

test("tagged folder filters keep reserved words and special property names as distinct opaque IDs", () => {
  const folderIds = ["all", "unknown", "folder", "__proto__", "constructor", "synthetic%2Ffolder", "synthetic/folder", " synthetic/folder "];
  const items = Object.freeze([...folderIds.map((folderId, index) => item(`file-${index}`, folderId, "same label")),
    item("unknown-file", null, null)]);
  for (const [index, folderId] of folderIds.entries()) {
    assert.deepEqual(ids(display(items, { kind: "folder", id: folderId })), [`file-${index}`]);
  }
  assert.deepEqual(ids(display(items, { kind: "all" })), ids(items));
  assert.deepEqual(ids(display(items, { kind: "unknown" })), ["unknown-file"]);
  assert.deepEqual(display(items, { kind: "folder", id: "SYNTHETIC/FOLDER" }), []);
  assert.equal(mediaLibrary.folderOptions(items).length, folderIds.length);
});

test("malformed folder filters fail closed and never coerce strings into reserved filters", () => {
  const items = [item("known", "synthetic-folder", "DCIM"), item("unknown", null, null)];
  const invalid = [null, "all", "unknown", "synthetic-folder", "", 0, true, [], {},
    { kind: "ALL" }, { kind: "other" }, { kind: "folder" }, { kind: "folder", id: "" },
    { kind: "folder", id: " " }, { kind: "folder", id: 1 }, { kind: "folder", id: "i".repeat(4097) },
    { kind: "folder", id: "synthetic\nfolder" }];
  for (const filter of invalid) {
    assert.deepEqual(display(items, filter), [], JSON.stringify(filter));
    for (const entry of items) assert.equal(mediaLibrary.folderMatchesFilter(entry, filter), false);
  }
});

test("selected folder identity survives label changes or disappearing from the loaded set", () => {
  const items = Object.freeze([item("current", "synthetic-current", "new label"), item("unknown", null, null)]);
  const current = Object.freeze({ kind: "folder", id: "synthetic-current", label: "previous label" });
  assert.deepEqual(ids(display(items, current)), ["current"], "labels never participate in identity matching");
  const gone = Object.freeze({ kind: "folder", id: "synthetic-missing", label: "new label" });
  assert.deepEqual(display(items, gone), [], "same label does not substitute a different ID");
  assert.deepEqual(display([], current), [], "an empty loaded set must not broaden the selected filter");
  assert.deepEqual(current, { kind: "folder", id: "synthetic-current", label: "previous label" });
});

test("folder intersects type, inclusive date and rating while retaining RAW/JPEG identities", () => {
  const folderA = "synthetic-card-a";
  const make = (id, extra = {}, folderId = folderA) => item(id, folderId, "DCIM/100SYNTH", {
    captureTime: "2026-10-05", rating: 4, ...extra,
  });
  const raw = make("raw", { name: "SYNTHETIC_001.CR3" });
  const jpeg = make("jpeg", { name: "SYNTHETIC_001.JPG" });
  const items = Object.freeze([
    raw, make("before", { captureTime: "2026-10-04" }), jpeg,
    make("other-card", { name: "SYNTHETIC_001.JPG" }, "synthetic-card-b"),
    make("video", { name: "SYNTHETIC_001.MP4" }), make("zero", { rating: 0 }),
    make("unknown-rating", { rating: null }), make("unknown-date", { captureTime: null }),
    make("end", { captureTime: "2026-10-06", rating: 5 }), make("after", { captureTime: "2026-10-07" }),
    make("unknown-folder", {}, null),
  ]);
  const range = Object.freeze({ start: "2026-10-05", end: "2026-10-06" });
  const filter = Object.freeze({ kind: "folder", id: folderA });
  const filtered = display(items, filter, "photo", range, "4-plus");
  assert.deepEqual(ids(filtered), ["raw", "jpeg", "end"]);
  assert.equal(filtered[0], raw);
  assert.equal(filtered[1], jpeg);
  assert.deepEqual(ids(display(items, filter, "photo", range, "4-plus", "rating-highest")), ["end", "raw", "jpeg"]);
  assert.deepEqual(ids(display(items, filter, "video", range, "4-plus")), ["video"]);
  assert.deepEqual(ids(display(items, { kind: "unknown" }, "photo", range, "4-plus")), ["unknown-folder"]);
  assert.deepEqual(ids(display(items, { kind: "all" }, "photo", range, "4-plus")),
    ["raw", "jpeg", "other-card", "end", "unknown-folder"], "clearing folder keeps other filters");
  assert.equal(mediaLibrary.countUnknownFolders(items), 1, "counts remain based on loaded evidence");
  assert.equal(mediaLibrary.page(filtered, 1, 1).items[0], jpeg);
});

test("omitting folder filtering preserves the existing API and does not read optional evidence", () => {
  const unavailable = () => { throw new Error("All folders must not inspect optional provenance"); };
  const items = Object.freeze([
    Object.freeze({ id: "second", name: "SYNTHETIC_2.JPG", captureTime: "2026-10-05", rating: 4,
      get folderId() { return unavailable(); }, get folderLabel() { return unavailable(); } }),
    Object.freeze({ id: "first", name: "SYNTHETIC_1.JPG", captureTime: "2026-10-06", rating: 5 }),
  ]);
  const range = { start: "2026-10-05", end: "2026-10-06" };
  for (const sort of ["camera", "name", "newest", "oldest", "rating-highest", "rating-lowest"]) {
    assert.deepEqual(mediaLibrary.itemsForDisplay(items, "all", sort, "en-US", range, "4-plus"),
      display(items, { kind: "all" }, "all", range, "4-plus", sort));
  }
  assert.equal(mediaLibrary.folderMatchesFilter(items[0]), true);
  assert.throws(() => display(items, { kind: "all" }, "all", {}), RangeError);
});
