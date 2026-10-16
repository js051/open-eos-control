"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");

function item(id, rating, captureTime = "2026-10-05", name = `${id}.JPG`) {
  return Object.freeze({ id, name, rating, captureTime });
}

const ids = (items) => items.map(({ id }) => id);
const display = (items, ratingFilter, sort = "camera", type = "all", range = null) =>
  mediaLibrary.itemsForDisplay(items, type, sort, "en-US", range, ratingFilter);

test("only numeric integers 0 through 5 are known ratings", () => {
  for (let rating = 0; rating <= 5; rating += 1) {
    assert.equal(mediaLibrary.normalizeRating(rating), rating);
  }
  const unknown = [
    undefined, null, "", " ", "0", "5", true, false, -1, 6, 0.5, 4.999,
    NaN, Infinity, -Infinity, Number.MAX_SAFE_INTEGER, [], [0], {}, { rating: 0 },
    new Number(0), 0n, Symbol("unknown"),
  ];
  for (const rating of unknown) {
    assert.equal(mediaLibrary.normalizeRating(rating), null, String(rating));
    assert.equal(mediaLibrary.ratingMatchesFilter(rating, "unrated"), false, String(rating));
    assert.equal(mediaLibrary.ratingMatchesFilter(rating, "unknown"), true, String(rating));
  }
});

test("each rating filter respects its exact boundary and keeps unknown separate from zero", () => {
  const items = Object.freeze([
    ...Array.from({ length: 6 }, (_, rating) => item(String(rating), rating)),
    item("unknown", null), item("invalid", 6),
  ]);
  const expected = {
    all: ["0", "1", "2", "3", "4", "5", "unknown", "invalid"],
    unrated: ["0"],
    "1-plus": ["1", "2", "3", "4", "5"],
    "2-plus": ["2", "3", "4", "5"],
    "3-plus": ["3", "4", "5"],
    "4-plus": ["4", "5"],
    "5": ["5"],
    unknown: ["unknown", "invalid"],
  };
  for (const [filter, expectedIds] of Object.entries(expected)) {
    assert.deepEqual(ids(display(items, filter)), expectedIds, filter);
  }
});

test("unrecognized filters fail closed instead of broadening the visible set", () => {
  const items = [item("known", 5), item("zero", 0), item("unknown", null)];
  for (const filter of [null, "", "ALL", "zero", "5-plus", "6-plus", 0, {}, []]) {
    assert.deepEqual(display(items, filter), [], String(filter));
    for (const { rating } of items) {
      assert.equal(mediaLibrary.ratingMatchesFilter(rating, filter), false, String(filter));
    }
  }
});

test("unknown counts cover the loaded set, including missing and malformed values", () => {
  const items = [
    item("zero", 0), item("five", 5), item("null", null), item("fraction", 1.5),
    item("string", "0"), Object.freeze({ id: "missing", name: "missing.JPG" }),
  ];
  assert.equal(mediaLibrary.countUnknownRatings(items), 4);
  assert.equal(mediaLibrary.countUnknownRatings([]), 0);
  assert.deepEqual(ids(display(items, "unrated")), ["zero"]);
  assert.equal(mediaLibrary.countUnknownRatings(items), 4, "visible filtering does not change loaded evidence");
});

test("both rating sort directions put unknown last and preserve source order for ties", () => {
  const items = Object.freeze([
    item("unknown-z", undefined, "2026-10-09", "Z.JPG"),
    item("three-z", 3, "2026-10-01", "Z.JPG"),
    item("zero", 0),
    item("unknown-a", null, "2026-10-01", "A.JPG"),
    item("five", 5),
    item("three-a", 3, "2026-10-09", "A.JPG"),
    item("invalid", "5"),
    item("one", 1),
  ]);
  assert.deepEqual(ids(display(items, "all", "rating-highest")),
    ["five", "three-z", "three-a", "one", "zero", "unknown-z", "unknown-a", "invalid"]);
  assert.deepEqual(ids(display(items, "all", "rating-lowest")),
    ["zero", "one", "three-z", "three-a", "five", "unknown-z", "unknown-a", "invalid"]);
  assert.deepEqual(ids(items),
    ["unknown-z", "three-z", "zero", "unknown-a", "five", "three-a", "invalid", "one"]);
  assert.deepEqual(ids(display(items, "3-plus", "rating-highest")), ["five", "three-z", "three-a"]);
  assert.deepEqual(ids(display(items, "unknown", "rating-lowest")), ["unknown-z", "unknown-a", "invalid"]);
});

test("rating order depends only on loaded rating, even when date and name are unavailable", () => {
  const unavailable = () => { throw new Error("Rating sorting must not read date or name metadata"); };
  const items = Object.freeze([null, 4, 0, 4].map((rating, index) => Object.freeze({
    id: String(index), rating, get name() { return unavailable(); }, get captureTime() { return unavailable(); },
  })));
  assert.deepEqual(ids(display(items, "all", "rating-highest")), ["1", "3", "2", "0"]);
  assert.deepEqual(ids(display(items, "all", "rating-lowest")), ["2", "1", "3", "0"]);
});

test("rating, type and inclusive date range intersect without merging RAW and JPEG identities", () => {
  const raw = item("raw-id", 4, "2026-10-05", "SYNTHETIC_001.CR3");
  const jpeg = item("jpeg-id", 4, "2026-10-05", "SYNTHETIC_001.JPG");
  const items = Object.freeze([
    item("before", 5, "2026-10-04"), raw, jpeg,
    item("video", 5, "2026-10-06", "SYNTHETIC_002.MP4"),
    item("zero", 0, "2026-10-06"), item("unknown", null, "2026-10-06"),
    item("end", 5, "2026-10-06"), item("after", 5, "2026-10-07"), item("undated", 5, null),
  ]);
  const range = Object.freeze({ start: "2026-10-05", end: "2026-10-06" });
  const matched = display(items, "4-plus", "camera", "photo", range);
  assert.deepEqual(ids(matched), ["raw-id", "jpeg-id", "end"]);
  assert.equal(matched[0], raw);
  assert.equal(matched[1], jpeg);
  assert.deepEqual(ids(display(items, "4-plus", "rating-highest", "photo", range)),
    ["end", "raw-id", "jpeg-id"]);
  assert.deepEqual(ids(display(items, "4-plus", "rating-lowest", "photo", range)),
    ["raw-id", "jpeg-id", "end"]);
  assert.deepEqual(ids(display(items, "4-plus", "camera", "video", range)), ["video"]);
  assert.deepEqual(ids(display(items, "all", "camera", "photo", range)),
    ["raw-id", "jpeg-id", "zero", "unknown", "end"], "clearing rating keeps type/date");
  assert.deepEqual(ids(display(items, "4-plus", "camera", "photo")),
    ["before", "raw-id", "jpeg-id", "end", "after", "undated"], "clearing date keeps type/rating");
});

test("known read-only ratings remain usable without a media-rating write capability", () => {
  const items = Object.freeze([
    Object.freeze({ ...item("read-only", 5), supportedActions: Object.freeze([]) }),
    Object.freeze({ ...item("writable-unknown", null), supportedActions: Object.freeze(["rating"]) }),
  ]);
  assert.deepEqual(ids(display(items, "5")), ["read-only"]);
  assert.deepEqual(ids(display(items, "unknown")), ["writable-unknown"]);
  assert.equal(mediaLibrary.countUnknownRatings(items), 1);
});

test("omitting the sixth argument preserves the existing display API", () => {
  const items = Object.freeze([
    item("two", 0, "2026-10-05", "SYNTHETIC_2.JPG"),
    item("ten", null, "2026-10-06", "SYNTHETIC_10.JPG"),
    item("video", 5, "2026-10-06", "SYNTHETIC_3.MP4"),
  ]);
  const range = { start: "2026-10-05", end: "2026-10-06" };
  for (const sort of ["camera", "name", "newest", "oldest"]) {
    assert.deepEqual(mediaLibrary.itemsForDisplay(items, "all", sort, "en-US"), display(items, "all", sort));
    assert.deepEqual(mediaLibrary.itemsForDisplay(items, "photo", sort, "en-US", range),
      display(items, "all", sort, "photo", range));
  }
  assert.throws(() => display(items, "5", "camera", "all", {}), RangeError);
});

test("empty and unknown-only lists remain stable in either rating direction", () => {
  const items = [item("z", undefined), item("a", null), item("b", -1)];
  for (const sort of ["rating-highest", "rating-lowest"]) {
    assert.deepEqual(display([], "all", sort), []);
    assert.deepEqual(display(items, "all", sort), items);
    assert.deepEqual(display(items, "unrated", sort), []);
  }
});

test("rating filtering and sorting preserve exact item references through pagination", () => {
  const items = Object.freeze([item("zero", 0), item("five", 5), item("four", 4), item("unknown", null)]);
  const matched = display(items, "4-plus", "rating-highest");
  const secondPage = mediaLibrary.page(matched, 1, 1);
  assert.equal(secondPage.items[0], items[2]);
  assert.equal(secondPage.total, 2);
  assert.equal(secondPage.pageCount, 2);
  assert.equal(mediaLibrary.page(display(items, "unknown"), 1, 1).items[0], items[3]);
});
