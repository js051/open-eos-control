"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, name);
  const end = source.indexOf("\n  }", match.index);
  return source.slice(match.index, end + 4);
}
function fixture() {
  const media = [0, 5, 3, null, "0", -1].map((rating, index) => ({
    id: `synthetic-${index}`, name: `IMG_${index}.JPG`, kind: "image", rating,
    captureTime: "2026-10-01", previewAvailable: true,
  }));
  const state = { session: { id: "same-id" }, media, mediaRatingFilter: "all", mediaRatingDialogSession: null,
    mediaDateRange: { start: "2026-10-01", end: "2026-10-01" }, mediaFilter: "photo", mediaSort: "camera",
    mediaPage: 2, mediaLoaded: true, mediaLoadStatus: "COMPLETE", mediaScope: "recent", mediaHasMore: true,
    mediaPreviewItem: media[0], latestMediaItem: null, mediaDownload: { id: "active-download" },
    operatorConfirmedFeatures: new Set(), refreshGeneration: 0 };
  const ui = new Proxy({}, { get(target, key) {
    if (!target[key]) target[key] = { value: "", textContent: "", hidden: false, disabled: false, dataset: {},
      attributes: {}, setAttribute(k, v) { this.attributes[k] = v; } };
    return target[key];
  }});
  ui.mediaRatingDialog.open = false;
  ui.mediaRatingDialog.showModal = function () { this.open = true; };
  ui.mediaRatingDialog.close = function () { this.open = false; state.mediaRatingDialogSession = null; };
  const calls = [];
  const context = vm.createContext({ state, ui, mediaLibrary, calls,
    FEATURES: { MEDIA_BROWSER: "browser", MEDIA_DOWNLOAD: "download", MEDIA_PREVIEW: "preview" },
    featureSupported: () => true, resolvedLanguage: () => "en-US", mediaIsVideo: mediaLibrary.isVideo,
    t: (key, args = {}) => `${key}:${JSON.stringify(args)}`,
    api: () => { throw new Error("Filtering must not send camera requests"); },
    renderMedia: () => calls.push("render"), renderMediaPreviewNavigation: () => calls.push("navigation"),
  });
  for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaDownload", "cancelMediaUpload",
    "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaDetails",
    "releaseObjectUrl", "clearBulbTimer", "renderAvailability", "renderHealth"]) context[name] = () => {};
  context.closeMediaPreview = () => { calls.push("close-preview"); state.mediaPreviewItem = null; };
  vm.runInContext(["displayedMedia", "renderMediaRatingFilter", "openMediaRatingFilter", "applyMediaRatingFilter",
    "clearMediaRatingFilter", "previewableMedia", "mediaDateGroup", "cancelSetting", "resetSession"].map(extract).join("\n"), context);
  return context;
}
function apply(context, filter) {
  context.openMediaRatingFilter();
  context.ui.mediaRatingSelect.value = filter;
  context.applyMediaRatingFilter();
}
const ids = context => Array.from(context.displayedMedia(), item => item.id);

test("apply and clear preserve date/type/source, preview identity and active download", () => {
  const c = fixture(), before = { media: c.state.media, date: c.state.mediaDateRange,
    preview: c.state.mediaPreviewItem, download: c.state.mediaDownload };
  apply(c, "3-plus");
  assert.deepEqual(ids(c), ["synthetic-1", "synthetic-2"]);
  assert.equal(c.state.mediaPage, 0);
  assert.equal(c.state.media, before.media);
  assert.equal(c.state.mediaDateRange, before.date);
  assert.equal(c.state.mediaFilter, "photo");
  assert.equal(c.state.mediaPreviewItem, before.preview);
  assert.equal(c.state.mediaDownload, before.download);
  assert.deepEqual(Array.from(c.previewableMedia(), item => item.id), [before.preview.id]);
  c.clearMediaRatingFilter();
  assert.equal(c.state.mediaRatingFilter, "all");
  assert.equal(c.state.mediaPreviewItem, before.preview);
  assert.equal(c.state.mediaDownload, before.download);
  assert.equal(ids(c).length, 6);
  assert.equal(c.calls.includes("close-preview"), false);
});

test("unknown is distinct from known zero in actual display callback", () => {
  const c = fixture(); apply(c, "unrated"); assert.deepEqual(ids(c), ["synthetic-0"]);
  apply(c, "unknown"); assert.deepEqual(ids(c), ["synthetic-3", "synthetic-4", "synthetic-5"]);
});

test("cancel preserves active filter and pagination", () => {
  const c = fixture(); apply(c, "5"); c.state.mediaPage = 7;
  c.openMediaRatingFilter(); c.ui.mediaRatingSelect.value = "unrated"; c.ui.mediaRatingDialog.close();
  assert.equal(c.state.mediaRatingFilter, "5"); assert.equal(c.state.mediaPage, 7);
});

test("old dialog cannot apply or clear a new session even with reused ID", () => {
  for (const action of ["applyMediaRatingFilter", "clearMediaRatingFilter"]) {
    const c = fixture(); c.openMediaRatingFilter();
    c.state.session = { id: "same-id" }; c.state.mediaRatingFilter = "5"; c.state.mediaPage = 4;
    c.ui.mediaRatingSelect.value = "unrated"; c[action]();
    assert.equal(c.state.mediaRatingFilter, "5"); assert.equal(c.state.mediaPage, 4);
    assert.deepEqual(c.calls, []);
  }
});

test("disconnected or invalid draft cannot mutate filtering", () => {
  for (const disconnected of [false, true]) {
    const c = fixture(); c.openMediaRatingFilter(); c.ui.mediaRatingSelect.value = "invented";
    if (disconnected) c.state.session = null;
    c.applyMediaRatingFilter();
    assert.equal(c.state.mediaRatingFilter, "all"); assert.equal(c.state.mediaPage, 2);
    assert.deepEqual(c.calls, []);
  }
});

test("reset retires dialog and restores all ratings", () => {
  const c = fixture(); apply(c, "unknown"); c.openMediaRatingFilter(); c.resetSession();
  assert.equal(c.state.mediaRatingFilter, "all"); assert.equal(c.state.mediaRatingDialogSession, null);
  assert.equal(c.ui.mediaRatingDialog.open, false);
});

test("rating sort has no invented date headers and summary counts loaded unknown", () => {
  for (const sort of ["rating-highest", "rating-lowest"]) {
    const c = fixture(); c.state.mediaSort = sort; c.renderMediaRatingFilter();
    assert.equal(c.mediaDateGroup(c.state.media[0]), null);
    assert.equal(c.ui.mediaRatingSummary.hidden, false);
    assert.match(c.ui.mediaRatingSummary.textContent, /"unknown":3/);
    assert.match(c.ui.mediaRatingSummary.textContent, /"total":6/);
    assert.equal(c.ui.mediaRatingClear.hidden, true);
  }
});

test("a readonly browser may filter known ratings without rating-write capability", () => {
  const c = fixture(); c.featureSupported = feature => feature === "browser";
  apply(c, "5"); assert.deepEqual(ids(c), ["synthetic-1"]);
});


test("rating navigation never prepends an out-of-list latest capture", () => {
  for (const sort of ["rating-highest", "rating-lowest"]) {
    for (const rating of [null, 0]) {
      const c = fixture();
      c.state.mediaDateRange = null; c.state.mediaSort = sort;
      const latest = { id: "latest-outside-list", name: "LATEST.JPG", kind: "image",
        rating, previewAvailable: true };
      c.state.latestMediaItem = latest;
      const expected = ids(c);
      assert.deepEqual(Array.from(c.previewableMedia(), item => item.id), expected);
      c.state.mediaPreviewItem = latest;
      assert.deepEqual(Array.from(c.previewableMedia(), item => item.id), [latest.id]);
      assert.equal(c.state.media.length, 6);
    }
  }
});
