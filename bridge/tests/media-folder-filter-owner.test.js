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
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function fixture() {
  const media = [
    { id: "raw", name: "PAIR.CR3", kind: "raw", folderId: "synthetic:A", folderLabel: "card1/DCIM/100CANON", rating: 5 },
    { id: "jpeg", name: "PAIR.JPG", kind: "image", folderId: "synthetic:A", folderLabel: "card1/DCIM/100CANON", rating: 3 },
    { id: "other", name: "PAIR.JPG", kind: "image", folderId: "synthetic:B", folderLabel: "card1/DCIM/100CANON", rating: 5 },
    { id: "unknown", name: "UNKNOWN.JPG", kind: "image", rating: 0 },
  ].map(item => ({ ...item, captureTime: "2026-10-01", previewAvailable: true }));
  const state = { session: { id: "same-id" }, media, mediaFolderFilter: { kind: "all" },
    mediaFolderDialogSession: null, mediaFolderDraftChoices: [], mediaDateRange: null,
    mediaRatingFilter: "all", mediaFilter: "all", mediaSort: "camera", mediaPage: 3,
    mediaLoaded: true, mediaPreviewItem: media[0], latestMediaItem: null, mediaDownload: { id: "download" },
    operatorConfirmedFeatures: new Set(), refreshGeneration: 0 };
  const ui = new Proxy({}, { get(target, key) {
    if (!target[key]) target[key] = { value: "", textContent: "", hidden: false, children: [], dataset: {},
      setAttribute() {}, replaceChildren() { this.children = []; }, append(child) {
        this.children.push(child); if (child.selected) this.value = child.value;
      } };
    return target[key];
  }});
  ui.mediaFolderDialog.open = false;
  ui.mediaFolderDialog.showModal = function () { this.open = true; };
  ui.mediaFolderDialog.close = function () {
    this.open = false; state.mediaFolderDialogSession = null; state.mediaFolderDraftChoices = [];
  };
  const calls = [];
  const c = vm.createContext({ state, ui, calls, mediaLibrary,
    document: { createElement: () => ({}) },
    FEATURES: { MEDIA_BROWSER: "browser", MEDIA_PREVIEW: "preview", MEDIA_DOWNLOAD: "download" },
    featureSupported: () => true, resolvedLanguage: () => "en-US", mediaIsVideo: mediaLibrary.isVideo,
    t: (key, args = {}) => `${key}:${JSON.stringify(args)}`,
    api: () => { throw Error("Folder filtering must not request camera data"); },
    renderMedia: () => calls.push("render"), renderMediaPreviewNavigation: () => calls.push("navigation"),
  });
  for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaDownload", "cancelMediaUpload",
    "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaDetails",
    "releaseObjectUrl", "clearBulbTimer", "renderAvailability", "renderHealth"]) c[name] = () => {};
  c.closeMediaPreview = () => { state.mediaPreviewItem = null; };
  vm.runInContext(["displayedMedia", "renderMediaFolderFilter", "mediaFolderChoiceLabel", "openMediaFolderFilter", "applyMediaFolderFilter",
    "clearMediaFolderFilter", "previewableMedia", "cancelSetting", "resetSession"].map(extract).join("\n"), c);
  return c;
}
function apply(c, kind, id) {
  c.openMediaFolderFilter();
  const index = c.state.mediaFolderDraftChoices.findIndex(item => item.kind === kind && (kind !== "folder" || item.id === id));
  assert.ok(index >= 0); c.ui.mediaFolderSelect.value = String(index); c.applyMediaFolderFilter();
}
const ids = c => Array.from(c.displayedMedia(), item => item.id);

test("same-label folders remain distinct and RAW/JPEG identities stay together", () => {
  const c = fixture(); c.openMediaFolderFilter();
  const options = c.ui.mediaFolderSelect.children.slice(2);
  assert.equal(options.length, 2); assert.notEqual(options[0].textContent, options[1].textContent);
  c.ui.mediaFolderDialog.close(); apply(c, "folder", "synthetic:A");
  assert.deepEqual(ids(c), ["raw", "jpeg"]); assert.equal(c.state.mediaPage, 0);
  apply(c, "folder", "synthetic:B"); assert.deepEqual(ids(c), ["other"]);
});
test("folder intersects date/rating/type and preserves preview and download", () => {
  const c = fixture(), media = c.state.media, preview = c.state.mediaPreviewItem, download = c.state.mediaDownload;
  c.state.mediaDateRange = { start: "2026-10-01", end: "2026-10-01" }; c.state.mediaRatingFilter = "5";
  apply(c, "folder", "synthetic:B"); assert.deepEqual(ids(c), ["other"]);
  assert.equal(c.state.media, media); assert.equal(c.state.mediaPreviewItem, preview); assert.equal(c.state.mediaDownload, download);
  assert.deepEqual(Array.from(c.previewableMedia(), item => item.id), [preview.id]);
  c.clearMediaFolderFilter(); assert.deepEqual(ids(c), ["raw", "other"]);
  assert.equal(c.state.mediaRatingFilter, "5"); assert.equal(c.state.mediaDateRange.start, "2026-10-01");
});
test("unknown is explicit and opaque reserved-looking IDs are not special", () => {
  const c = fixture(); c.state.media[2].folderId = "unknown";
  apply(c, "unknown"); assert.deepEqual(ids(c), ["unknown"]);
  apply(c, "folder", "unknown"); assert.deepEqual(ids(c), ["other"]);
});
test("old dialog rejects apply and clear after reused-ID reconnect", () => {
  for (const action of ["applyMediaFolderFilter", "clearMediaFolderFilter"]) {
    const c = fixture(); c.openMediaFolderFilter(); c.ui.mediaFolderSelect.value = "2";
    c.state.session = { id: "same-id" }; c.state.mediaFolderFilter = { kind: "unknown" };
    c[action](); assert.equal(c.state.mediaFolderFilter.kind, "unknown"); assert.equal(c.state.mediaPage, 3);
    assert.deepEqual(c.calls, []);
  }
});
test("cancel and invalid option keep active filter and pagination", () => {
  for (const value of ["-1", "1.5", "01", "999999", "not-an-option"]) {
    const c = fixture(); c.openMediaFolderFilter(); c.ui.mediaFolderSelect.value = value;
    c.applyMediaFolderFilter(); assert.equal(c.state.mediaFolderFilter.kind, "all"); assert.equal(c.state.mediaPage, 3);
    c.ui.mediaFolderDialog.close(); assert.equal(c.state.mediaFolderFilter.kind, "all");
  }
});
test("selected folder absent from refreshed loaded list remains exact zero-match", () => {
  const c = fixture(); apply(c, "folder", "synthetic:A"); c.state.media = c.state.media.slice(2);
  assert.deepEqual(ids(c), []); c.openMediaFolderFilter();
  const option = c.state.mediaFolderDraftChoices.find(item => item.id === "synthetic:A");
  assert.ok(option); assert.match(option.displayLabel, /mediaFolderMissing/);
  c.applyMediaFolderFilter(); assert.deepEqual(ids(c), []);
  c.renderMediaFolderFilter(); assert.match(c.ui.mediaFolderSummary.textContent, /"matched":0,"total":2/);
  c.clearMediaFolderFilter(); assert.deepEqual(ids(c), ["other", "unknown"]);
});
test("disconnect retires folder draft and filter", () => {
  const c = fixture(); apply(c, "unknown"); c.openMediaFolderFilter(); c.resetSession();
  assert.equal(c.state.mediaFolderFilter.kind, "all"); assert.equal(c.state.mediaFolderDialogSession, null);
  assert.equal(c.ui.mediaFolderDialog.open, false); assert.equal(c.state.mediaFolderDraftChoices.length, 0);
});
test("untrusted folder labels remain text and malformed pairs stay unknown", () => {
  const c = fixture(); c.state.media[0].folderLabel = "<img src=x onerror=alert(1)>";
  c.state.media[1].folderLabel = null; c.openMediaFolderFilter();
  assert.ok(c.ui.mediaFolderSelect.children.some(item => item.textContent.includes("<img")));
  assert.ok(c.ui.mediaFolderSelect.children.every(item => item.innerHTML === undefined));
  c.ui.mediaFolderDialog.close(); apply(c, "unknown"); assert.deepEqual(ids(c), ["jpeg", "unknown"]);
});


test("active folder summary distinguishes duplicate labels and follows refreshed evidence", () => {
  const c = fixture(); c.state.mediaRatingFilter = "5";
  apply(c, "folder", "synthetic:A"); c.renderMediaFolderFilter();
  const first = c.ui.mediaFolderSummary.textContent;
  apply(c, "folder", "synthetic:B"); c.renderMediaFolderFilter();
  assert.notEqual(c.ui.mediaFolderSummary.textContent, first);
  c.state.media[2].folderLabel = "updated-camera-label";
  c.renderMediaFolderFilter();
  assert.match(c.ui.mediaFolderSummary.textContent, /^updated-camera-label:/);
  c.state.media = c.state.media.slice(0, 2);
  c.renderMediaFolderFilter();
  assert.match(c.ui.mediaFolderSummary.textContent, /mediaFolderMissing/);
  assert.equal(c.state.mediaFolderFilter.id, "synthetic:B");
  assert.deepEqual(ids(c), []);
});


test("folder labels remain literal with production translation interpolation", () => {
  const c = fixture(), label = "camera $& $` $' {index}";
  c.resolvedLanguage = () => "en";
  c.messages = { en: { mediaFolderDuplicate: "distinct folder {index}" } };
  vm.runInContext(extract("t"), c);
  for (const item of c.state.media.slice(0, 3)) item.folderLabel = label;
  c.openMediaFolderFilter();
  assert.equal(c.ui.mediaFolderSelect.children[2].textContent, label + " · distinct folder 1");
  assert.equal(c.ui.mediaFolderSelect.children[3].textContent, label + " · distinct folder 2");
  c.ui.mediaFolderSelect.value = "2"; c.applyMediaFolderFilter(); c.renderMediaFolderFilter();
  assert.ok(c.ui.mediaFolderSummary.textContent.startsWith(label + " · distinct folder 1:"));
});
