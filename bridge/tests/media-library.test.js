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
}

run();
