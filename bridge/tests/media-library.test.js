"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const mediaLibrary = require(path.join(
  __dirname,
  "..",
  "open_eos_bridge",
  "static",
  "media-library.js",
));

const item = (id, name, captureTime = null, kind = "image", contentType = null) => ({
  id, name, captureTime, kind, contentType,
});

function runDateContract() {
  const parse = mediaLibrary.parseMediaDate;
  const dateOnly = parse("2024-02-29");
  assert.equal(dateOnly.dateKey, "2024-02-29");
  assert.equal(dateOnly.hasTime, false);
  assert.equal(dateOnly.timestamp, Date.UTC(2024, 1, 29));
  assert.equal(dateOnly.date.toISOString(), "2024-02-29T00:00:00.000Z");
  assert.equal(parse("0001-01-01").date.toISOString(), "0001-01-01T00:00:00.000Z");
  assert.equal(parse("0099-12-31").date.toISOString(), "0099-12-31T00:00:00.000Z");
  assert.equal(parse("2000-02-29").dateKey, "2000-02-29");
  assert.equal(parse("2011-12-30").date.toISOString(), "2011-12-30T00:00:00.000Z", "date-only survives Apia's skipped local day");

  for (const raw of [
    "2024-02-29T23:59:58Z", "2024-02-29T23:59:58+00:00", "2024-02-29T23:59:58+0000",
    "2024-03-01T07:59:58+08:00", "2024-02-29T15:59:58-0800",
  ]) {
    const parsed = parse(raw);
    assert.equal(parsed.timestamp, Date.UTC(2024, 1, 29, 23, 59, 58), raw);
    assert.equal(parsed.hasTime, true, raw);
    assert.equal(mediaLibrary.mediaTime(item("date", "date.JPG", raw)), parsed.timestamp, raw);
  }
  assert.equal(parse("2024-02-29T23:59:58.123456789Z").timestamp, Date.UTC(2024, 1, 29, 23, 59, 58, 123));
  assert.equal(parse("2024-02-29T23:59:58.1Z").timestamp, Date.UTC(2024, 1, 29, 23, 59, 58, 100));
  assert.equal(parse("2024-02-29T23:59Z").timestamp, Date.UTC(2024, 1, 29, 23, 59));
  for (const raw of ["20240229T120345", "2024-02-29T12:03:45", "2024-02-29 12:03:45"]) {
    const parsed = parse(raw);
    assert.equal(parsed.timestamp, new Date(2024, 1, 29, 12, 3, 45).getTime(), raw);
    assert.equal(parsed.dateKey, "2024-02-29", raw);
    assert.equal(parsed.hasTime, true, raw);
  }
  assert.equal(parse("2024-02-29 12:03:45.25").timestamp, new Date(2024, 1, 29, 12, 3, 45, 250).getTime());
  assert.equal(parse(" 2024-02-29T12:03:45Z ").timestamp, Date.UTC(2024, 1, 29, 12, 3, 45));

  const invalid = [
    null, undefined, "", " ", 20240229, {}, "unknown", "2024-2-29", "2024-02", "02/29/2024",
    "0000-01-01", "2023-02-29", "1900-02-29", "2024-02-30", "2024-04-31", "2024-00-10",
    "2024-13-01", "2024-01-00", "2024-01-32", "2024-02-29junk", "2024-02-29T",
    "2023-02-29T12:00:00Z", "2024-04-31T12:00:00+08:00", "20240230T120000",
    "20240229T240000", "2024-02-29T24:00:00Z", "2024-02-29T23:60:00Z", "2024-02-29T23:59:60Z",
    "2024-02-29T12:00:00+24:00", "2024-02-29T12:00:00-24:00", "2024-02-29T12:00:00+08:60",
    "2024-02-29T12:00:00+8:00", "2024-02-29T12:00:00+08", "2024-02-29T12:00:00.Z",
    "2024-02-29T12:00:00.1234567890Z", "2024-02-29T12:00:00Zjunk",
    "2024-02-29T12:00:00+08:00[Asia/Taipei]",
  ];
  for (const raw of invalid) {
    assert.equal(parse(raw), null, String(raw));
    assert.equal(mediaLibrary.mediaTime(item("invalid", "invalid.JPG", raw)), null, String(raw));
  }
  assert.equal(mediaLibrary.mediaDateForItem({ captureTime: "bad", createdAt: "2024-02-29" }), null);
  assert.equal(mediaLibrary.mediaDateForItem(item("literal", "literal.JPG", "2024-02-29")).dateKey, "2024-02-29");

  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const expectedDays = {
    UTC: ["2026-03-01", "2026-03-02", "2026-03-08", "2026-03-08", "2026-11-01", "2026-11-01"],
    "Asia/Taipei": ["2026-03-02", "2026-03-02", "2026-03-08", "2026-03-08", "2026-11-01", "2026-11-01"],
    "America/Los_Angeles": ["2026-03-01", "2026-03-01", "2026-03-07", "2026-03-08", "2026-11-01", "2026-11-01"],
  }[tz];
  const offsetDates = [
    "2026-03-02T00:30:00+08:00", "2026-03-01T23:30:00-02:00",
    "2026-03-08T07:30:00Z", "2026-03-08T10:30:00Z",
    "2026-11-01T08:30:00Z", "2026-11-01T09:30:00Z",
  ];
  if (expectedDays) {
    offsetDates.forEach((raw, index) => assert.equal(parse(raw).dateKey, expectedDays[index], `${tz}: ${raw}`));
  }
  if (tz === "America/Los_Angeles") {
    const mixedDays = [
      item("literal", "LITERAL.JPG", "2026-10-07"),
      item("previous", "PREVIOUS.JPG", "2026-10-07T01:00:00Z"),
      item("later", "LATER.JPG", "2026-10-07T23:00:00-07:00"),
    ];
    assert.deepEqual(mediaLibrary.itemsForDisplay(mixedDays, "all", "newest", "en").map((entry) => entry.id),
      ["later", "literal", "previous"], "Newest keeps each displayed day contiguous with date-only values");
    assert.deepEqual(mediaLibrary.itemsForDisplay(mixedDays, "all", "oldest", "en").map((entry) => entry.id),
      ["previous", "literal", "later"], "Oldest orders displayed days before within-day timestamps");
    assert.equal(parse("2026-03-08T09:59:59Z").date.getHours(), 1);
    assert.equal(parse("2026-03-08T10:00:00Z").date.getHours(), 3);
    assert.equal(parse("2026-11-01T08:30:00Z").date.getHours(), 1);
    assert.equal(parse("2026-11-01T09:30:00Z").date.getHours(), 1);
    assert.equal(parse("2026-03-08T02:30:00").timestamp, new Date(2026, 2, 8, 2, 30).getTime());
  }

  const oneDay = { start: "2024-02-29", end: "2024-02-29" };
  assert.deepEqual(mediaLibrary.validateDateRange(oneDay), { valid: true, range: oneDay, error: null });
  assert.deepEqual(mediaLibrary.validateDateRange({ start: "0001-01-01", end: "9999-12-31" }), {
    valid: true, range: { start: "0001-01-01", end: "9999-12-31" }, error: null,
  });
  assert.deepEqual(mediaLibrary.validateDateRange({ start: "2024-02-28", end: "2024-03-01" }), {
    valid: true, range: { start: "2024-02-28", end: "2024-03-01" }, error: null,
  });
  for (const input of [undefined, null, {}, { start: "", end: "" }, { start: "2024-02-29" }, { end: "2024-02-29" }]) {
    assert.deepEqual(mediaLibrary.validateDateRange(input), { valid: false, range: null, error: "required" });
  }
  for (const input of [
    { start: "2023-02-29", end: "2024-02-29" }, { start: "2024-02-29", end: "2024-04-31" },
    { start: "2024-2-29", end: "2024-03-01" }, { start: " 2024-02-29", end: "2024-03-01" },
    { start: "2024-02-29\n", end: "2024-03-01" }, { start: "2024-02-29", end: "2024-03-01\n" },
    { start: "2024-02-29T00:00:00Z", end: "2024-03-01" },
  ]) {
    assert.deepEqual(mediaLibrary.validateDateRange(input), { valid: false, range: null, error: "invalid" });
  }
  assert.deepEqual(mediaLibrary.validateDateRange({ start: "2024-03-01", end: "2024-02-29" }), {
    valid: false, range: null, error: "reversed",
  });

  const rangeItems = [
    item("before", "IMG_1.JPG", "2024-02-28"),
    item("raw", "IMG_2.CR3", "2024-02-29T12:00:00"),
    item("jpeg", "IMG_2.JPG", "2024-02-29T12:00:00"),
    item("video", "CLIP.MP4", "2024-02-29T23:59:59.999"),
    item("date-only", "IMG_3.JPG", "2024-02-29"),
    item("after", "IMG_4.JPG", "2024-03-01"),
    item("unknown", "IMG_5.JPG"),
    item("invalid", "IMG_6.JPG", "2024-04-31"),
    item("unknown-video", "CLIP_2.MOV"),
  ];
  const snapshot = JSON.stringify(rangeItems);
  const filtered = mediaLibrary.itemsForDisplay(rangeItems, "all", "camera", "en-US", oneDay);
  assert.deepEqual(filtered.map(({ id }) => id), ["raw", "jpeg", "video", "date-only"]);
  assert.equal(filtered[0], rangeItems[1], "RAW object identity is preserved");
  assert.equal(filtered[1], rangeItems[2], "JPEG object identity is preserved");
  assert.deepEqual(mediaLibrary.itemsForDisplay(rangeItems, "photo", "camera", "en-US", oneDay).map(({ id }) => id), ["raw", "jpeg", "date-only"]);
  assert.deepEqual(mediaLibrary.itemsForDisplay(rangeItems, "video", "camera", "en-US", oneDay).map(({ id }) => id), ["video"]);
  assert.equal(mediaLibrary.countUnknownDates(rangeItems), 3, "unknown count covers the whole loaded set");
  assert.equal(mediaLibrary.countUnknownDates([]), 0);
  assert.equal(mediaLibrary.itemsForDisplay(rangeItems, "all", "camera", "en-US", null).length, rangeItems.length);
  assert.equal(mediaLibrary.itemsForDisplay(rangeItems, "all", "camera", "en-US").length, rangeItems.length);
  assert.throws(() => mediaLibrary.itemsForDisplay(rangeItems, "all", "camera", "en-US", {}), RangeError);
  assert.equal(JSON.stringify(rangeItems), snapshot, "filtering never mutates loaded media");
  assert.deepEqual(mediaLibrary.page(filtered, 8, 2).items.map(({ id }) => id), ["video", "date-only"]);
  const sorted = mediaLibrary.itemsForDisplay(rangeItems, "photo", "newest", "en-US", oneDay);
  assert.deepEqual(sorted.filter(({ id }) => id === "raw" || id === "jpeg").map(({ id }) => id), ["raw", "jpeg"]);
  const broader = mediaLibrary.itemsForDisplay(rangeItems, "all", "camera", "en-US", { start: "2024-02-28", end: "2024-03-01" });
  assert.deepEqual(broader.map(({ id }) => id), ["before", "raw", "jpeg", "video", "date-only", "after"]);
  const offsetItems = offsetDates.map((captureTime, index) => item(String(index), `OFFSET_${index}.JPG`, captureTime));
  for (const dateKey of new Set(offsetItems.map((entry) => mediaLibrary.mediaDateForItem(entry).dateKey))) {
    const onDay = mediaLibrary.itemsForDisplay(offsetItems, "all", "camera", "en-US", { start: dateKey, end: dateKey });
    assert.deepEqual(onDay, offsetItems.filter((entry) => mediaLibrary.mediaDateForItem(entry).dateKey === dateKey));
  }
}

function run() {
  const items = [
    item("ten", "IMG_10.JPG", "2026-08-14T10:00:00Z"),
    item("two", "img_2.jpg", "2026-08-15T10:00:00Z"),
    item("one", "IMG_1.JPG"),
  ];
  assert.deepEqual(
    mediaLibrary.itemsForDisplay(items, "all", "name", "en-US").map(({ id }) => id),
    ["one", "two", "ten"],
  );
  assert.deepEqual(
    mediaLibrary.itemsForDisplay(items, "all", "camera", "en-US").map(({ id }) => id),
    ["ten", "two", "one"],
  );

  const dated = [
    item("unknown", "IMG_99.JPG"),
    item("old", "IMG_1.JPG", "20260813T120000"),
    item("new", "IMG_2.JPG", "2026-08-14 12:00:00"),
  ];
  assert.deepEqual(
    mediaLibrary.itemsForDisplay(dated, "all", "newest", "en-US").map(({ id }) => id),
    ["new", "old", "unknown"],
  );
  assert.deepEqual(
    mediaLibrary.itemsForDisplay(dated, "all", "oldest", "en-US").map(({ id }) => id),
    ["old", "new", "unknown"],
  );

  const cameraOrdered = [
    item("unknown-10", "IMG_10.JPG"),
    item("unknown-9", "IMG_9.JPG"),
    item("raw", "IMG_2.CR3", "2026-08-14T10:00:00Z"),
    item("jpeg", "IMG_2.JPG", "2026-08-14T10:00:00Z"),
  ];
  assert.deepEqual(
    mediaLibrary.itemsForDisplay(cameraOrdered, "all", "newest", "en-US").map(({ id }) => id),
    ["raw", "jpeg", "unknown-10", "unknown-9"],
  );
  assert.deepEqual(
    mediaLibrary.itemsForDisplay(cameraOrdered, "all", "oldest", "en-US").map(({ id }) => id),
    ["raw", "jpeg", "unknown-9", "unknown-10"],
  );

  const videos = [
    item("photo", "IMG_1.JPG"),
    item("kind", "CLIP.bin", null, "video"),
    item("mime", "CLIP.data", null, "other", "video/mp4"),
    item("extension", "CLIP.MP4"),
  ];
  assert.deepEqual(
    mediaLibrary.itemsForDisplay(videos, "video", "name", "en-US").map(({ id }) => id),
    ["kind", "mime", "extension"],
  );

  const thousands = Array.from({ length: 5_003 }, (_, index) => item(String(index), `IMG_${index}.JPG`));
  const lastPage = mediaLibrary.page(thousands, 69, 72);
  assert.equal(lastPage.items.length, 35);
  assert.deepEqual(
    { pageIndex: lastPage.pageIndex, pageCount: lastPage.pageCount, start: lastPage.start, end: lastPage.end },
    { pageIndex: 69, pageCount: 70, start: 4969, end: 5003 },
  );
  assert.equal(mediaLibrary.page([], 12, 72).pageIndex, 0);

  const cache = new Map();
  mediaLibrary.setBounded(cache, "one", 1, 3);
  mediaLibrary.setBounded(cache, "two", 2, 3);
  mediaLibrary.setBounded(cache, "three", 3, 3);
  assert.deepEqual(mediaLibrary.setBounded(cache, "four", 4, 3), [["one", 1]]);
  assert.deepEqual([...cache.keys()], ["two", "three", "four"]);
  assert.deepEqual(mediaLibrary.setBounded(cache, "two", 9, 3), [["two", 2]]);
  assert.deepEqual([...cache.entries()], [["three", 3], ["four", 4], ["two", 9]]);
  assert.equal(mediaLibrary.touch(cache, "three"), true);
  assert.deepEqual([...cache.keys()], ["four", "two", "three"]);
  assert.equal(mediaLibrary.touch(cache, "missing"), false);

  assert.deepEqual(
    mediaLibrary.imagePanBounds(2, { width: 400, height: 300 }, { width: 800, height: 400 }),
    { x: 200, y: 50 },
  );
  assert.deepEqual(
    mediaLibrary.clampImagePan(
      { x: 500, y: -500 },
      2,
      { width: 400, height: 300 },
      { width: 800, height: 400 },
    ),
    { x: 200, y: -50 },
  );
  assert.deepEqual(
    mediaLibrary.clampImagePan(
      { x: 20, y: 20 },
      1,
      { width: 400, height: 300 },
      { width: 800, height: 400 },
    ),
    { x: 0, y: 0 },
  );

  assert.equal(mediaLibrary.videoPlaybackFailure(3), "codec");
  assert.equal(mediaLibrary.videoPlaybackFailure(4), "codec");
  assert.equal(mediaLibrary.videoPlaybackFailure(2), "transport");
  assert.equal(mediaLibrary.videoPlaybackFailure(null), "transport");
  assert.equal(mediaLibrary.videoContainerLabel("MVI_0001.MP4"), "MP4");
  assert.equal(mediaLibrary.videoContainerLabel("clip.mov"), "QuickTime MOV");
  assert.equal(mediaLibrary.videoContainerLabel("clip.m4v"), "M4V");
  assert.equal(mediaLibrary.videoContainerLabel("clip.avi"), "AVI");
  assert.equal(mediaLibrary.videoContainerLabel("clip.mkv"), "Matroska MKV");
  assert.equal(mediaLibrary.videoContainerLabel("clip.bin"), "BIN");
  assert.equal(mediaLibrary.videoContainerLabel("clip"), "VIDEO");
}

run();
runDateContract();
