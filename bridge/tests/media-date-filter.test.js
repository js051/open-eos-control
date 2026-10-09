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
const item = (id, captureTime, kind = "image") => ({ id, name: id, captureTime, kind, previewAvailable: true });
function fixture() {
  const state = { session: { id: "synthetic-A" }, mediaDateRange: null, mediaDateDialogSession: null,
    media: [item("A.JPG", "2026-10-01"), item("A.CR3", "2026-10-01"),
      item("B.MP4", "2026-10-02", "video"), item("UNKNOWN.JPG", null)],
    mediaFilter: "all", mediaSort: "camera", mediaPage: 2, mediaLoaded: true,
    mediaScope: "recent", mediaHasMore: true, mediaLoadStatus: "COMPLETE",
    refreshGeneration: 0, operatorConfirmedFeatures: new Set(), latestMediaItem: null,
    mediaPreviewItem: null };
  const ui = new Proxy({}, { get(target, key) {
    if (!target[key]) target[key] = { value: "", textContent: "", hidden: false, disabled: false, dataset: {},
      attributes: {}, setAttribute(k, v) { this.attributes[k] = v; },
      removeAttribute(k) { delete this.attributes[k]; }, focus() { this.focused = true; } };
    return target[key];
  }});
  ui.mediaDateDialog.open = false;
  ui.mediaDateDialog.showModal = function () { this.open = true; };
  ui.mediaDateDialog.close = function () { this.open = false; state.mediaDateDialogSession = null; };
  const calls = [];
  const s = vm.createContext({ state, ui, mediaLibrary, calls, Intl, Date,
    FEATURES: { MEDIA_BROWSER: "browser", MEDIA_DOWNLOAD: "download", MEDIA_PREVIEW: "preview" },
    featureSupported: () => true, resolvedLanguage: () => "en-US", mediaIsVideo: mediaLibrary.isVideo,
    t: (key, args = {}) => `${key}:${JSON.stringify(args)}`,
    formatMediaDimensions: () => "", formatBytes: () => "", cameraInteractionBusy: () => false,
    mediaManagementSupported: () => true,
    api: () => { throw new Error("Date filtering must not request data"); },
    openMediaPreview: x => calls.push(["open-preview", x.id]),
  });
  for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaDownload", "cancelMediaUpload",
    "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaDetails",
    "releaseObjectUrl", "clearBulbTimer", "renderAvailability", "renderHealth"]) s[name] = () => {};
  s.closeMediaPreview = () => { calls.push(["close-preview"]); state.mediaPreviewItem = null; };
  const names = ["displayedMedia", "renderMediaSummary", "mediaDisplayTimeZone", "renderMediaDateFilter",
    "openMediaDateFilter", "applyMediaDateFilter", "clearMediaDateFilter", "previewableMedia", "mediaDateGroup",
    "formatMediaTime", "formatDate", "renderMediaPreviewNavigation", "openAdjacentMedia", "resetSession"];
  vm.runInContext(names.map(extract).join("\n"), s);
  s.renderMedia = () => { calls.push(["render-media"]); s.renderMediaSummary(); };
  return s;
}
function apply(s, start = "2026-10-01", end = start) {
  s.openMediaDateFilter();
  s.ui.mediaDateFrom.value = start;
  s.ui.mediaDateTo.value = end;
  s.applyMediaDateFilter();
}
function ids(s) { return Array.from(s.displayedMedia(), x => x.id); }

test("same-day range retains separate RAW/JPEG identities and requires no API request", () => {
  const s = fixture(); apply(s);
  assert.deepEqual(ids(s), ["A.JPG", "A.CR3"]);
  assert.equal(s.state.mediaPage, 0);
  assert.equal(s.state.mediaScope, "recent");
  assert.equal(s.state.mediaSort, "camera");
  assert.equal(s.ui.mediaDateDialog.open, false);
  assert.match(s.ui.mediaSummary.textContent, /mediaRecentMoreCount/);
  assert.match(s.ui.mediaDateSummary.textContent, /"matched":2,"total":4,"unknown":1/);
});
for (const [start, end, key] of [["", "", "mediaDateRequired"], ["2026-02-29", "2026-03-02", "mediaDateInvalid"],
  ["2026-10-03", "2026-10-01", "mediaDateReversed"]]) {
  test(`invalid draft ${key} preserves applied range and page`, () => {
    const s = fixture(); apply(s); s.state.mediaPage = 3;
    apply(s, start, end);
    assert.deepEqual(ids(s), ["A.JPG", "A.CR3"]);
    assert.equal(s.state.mediaPage, 3);
    assert.equal(s.ui.mediaDateDialog.open, true);
    assert.match(s.ui.mediaDateError.textContent, new RegExp(key));
    assert.equal(s.ui.mediaDateFrom.attributes["aria-invalid"], "true");
  });
}
test("closing a draft does not change range/page and reopening restores applied values", () => {
  const s = fixture(); apply(s); s.state.mediaPage = 4; s.openMediaDateFilter();
  s.ui.mediaDateFrom.value = "2025-01-01"; s.ui.mediaDateDialog.close();
  assert.equal(s.state.mediaPage, 4); assert.deepEqual(ids(s), ["A.JPG", "A.CR3"]);
  s.openMediaDateFilter(); assert.equal(s.ui.mediaDateFrom.value, "2026-10-01");
});
test("clear restores unknown dates and keeps type/sort/scope", () => {
  const s = fixture(); apply(s); s.state.mediaSort = "name"; s.state.mediaFilter = "photo";
  s.clearMediaDateFilter();
  assert.deepEqual(ids(s), ["A.CR3", "A.JPG", "UNKNOWN.JPG"]);
  assert.equal(s.state.mediaPage, 0); assert.equal(s.state.mediaScope, "recent");
  assert.equal(s.ui.mediaDateSummary.hidden, true);
});
for (const reuse of [false, true]) {
  test(`stale dialog cannot apply or clear a replacement connection (${reuse ? "reused" : "different"} ID)`, () => {
    const s = fixture(); s.openMediaDateFilter();
    s.ui.mediaDateFrom.value = "2026-10-01"; s.ui.mediaDateTo.value = "2026-10-01";
    s.state.session = { id: reuse ? "synthetic-A" : "synthetic-B" };
    s.state.mediaDateRange = { start: "2026-10-02", end: "2026-10-02" };
    s.applyMediaDateFilter(); s.clearMediaDateFilter();
    assert.deepEqual(ids(s), ["B.MP4"]); assert.equal(s.state.mediaPage, 2);
  });
}
test("production session reset clears range and closes the draft", () => {
  const s = fixture(); apply(s); s.openMediaDateFilter(); s.resetSession();
  assert.equal(s.state.session, null); assert.equal(s.state.mediaDateRange, null);
  assert.equal(s.state.mediaDateDialogSession, null); assert.equal(s.ui.mediaDateDialog.open, false);
  s.applyMediaDateFilter(); assert.equal(s.state.mediaDateRange, null);
});
for (const status of ["LOADING", "FAILED", "NOT_LOADED"]) {
  test(`date summary coexists with ${status} scope warning`, () => {
    const s = fixture(); apply(s); s.state.mediaLoadStatus = status; s.renderMediaSummary();
    assert.equal(s.ui.mediaSummary.dataset.loadStatus, status);
    assert.equal(s.ui.mediaDateSummary.hidden, false);
    assert.match(s.ui.mediaDateSummary.textContent, /"total":4/);
  });
}
test("unknown-only loaded media yields honest zero matches and can clear", () => {
  const s = fixture(); s.state.media = [item("ONE.JPG", null), item("BAD.JPG", "2026-04-31")]; apply(s);
  assert.deepEqual(ids(s), []);
  assert.match(s.ui.mediaDateSummary.textContent, /"matched":0,"total":2,"unknown":2/);
  s.clearMediaDateFilter(); assert.equal(ids(s).length, 2);
});
test("normal preview navigation excludes latest item outside filtered results", () => {
  const s = fixture(); s.state.latestMediaItem = item("LATEST.JPG", "2026-10-02"); apply(s);
  s.state.mediaPreviewItem = s.state.media[0]; s.renderMediaPreviewNavigation();
  assert.deepEqual(Array.from(s.previewableMedia(), x => x.id), ["A.JPG", "A.CR3"]);
  assert.equal(s.ui.mediaPreviewPrevious.disabled, true); assert.equal(s.ui.mediaPreviewNext.disabled, false);
  s.openAdjacentMedia(1); assert.deepEqual(s.calls.at(-1), ["open-preview", "A.CR3"]);
});
test("explicit capture-review item outside range stays isolated 1/1 with exact target", () => {
  const s = fixture(); const latest = item("LATEST.JPG", "2026-10-02"); s.state.latestMediaItem = latest; apply(s);
  s.state.mediaPreviewItem = latest; s.renderMediaPreviewNavigation();
  assert.deepEqual(Array.from(s.previewableMedia(), x => x.id), ["LATEST.JPG"]);
  assert.match(s.ui.mediaPreviewMeta.textContent, /"position":1,"total":1/);
  assert.equal(s.ui.mediaPreviewPrevious.disabled, true); assert.equal(s.ui.mediaPreviewNext.disabled, true);
  const before = s.calls.length; s.openAdjacentMedia(-1); s.openAdjacentMedia(1);
  assert.equal(s.calls.length, before); assert.equal(s.state.mediaPreviewItem, latest);
});
test("filtering during download leaves transfer object and exact original owner unchanged", () => {
  const s = fixture(); const transfer = { id: "B.MP4", bytes: 128, sessionId: "synthetic-A", cancelling: false };
  s.state.mediaDownload = transfer; apply(s); s.clearMediaDateFilter();
  assert.equal(s.state.mediaDownload, transfer); assert.equal(transfer.bytes, 128); assert.equal(transfer.cancelling, false);
});
test("date-only card/group/details share literal day without a fabricated time", () => {
  const s = fixture(); assert.equal(s.formatMediaTime("2026-10-01"), "");
  assert.match(s.mediaDateGroup(item("ONLY.JPG", "2026-10-01")), /October 1, 2026/);
  assert.match(s.formatDate("2026-10-01"), /Oct 1, 2026/);
  assert.equal(s.formatMediaTime("2026-02-29T12:00:00Z"), "");
  assert.match(s.mediaDateGroup(item("BAD.JPG", "2026-02-29")), /mediaUnknownDate/);
  assert.equal(s.formatDate("2026-02-29"), "");
});
