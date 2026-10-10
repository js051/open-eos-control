"use strict";

// Exercise extracted production callbacks with real selection, queue, streaming
// and destination modules. Browser handles and camera responses are synthetic;
// deferred promises control every ownership/commit boundary without timers.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaBatch = require("../open_eos_bridge/static/media-batch.js");
const mediaBatchDestination = require("../open_eos_bridge/static/media-batch-destination.js");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, name);
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function missing() { return Object.assign(new Error("Missing synthetic entry"), { name: "NotFoundError" }); }
function element() {
  return { value: "", textContent: "", hidden: false, disabled: false, open: false, dataset: {}, children: [],
    classList: { toggle() {}, remove() {} }, style: { removeProperty() {} },
    replaceChildren() { this.children = []; }, append(...children) { this.children.push(...children); },
    querySelectorAll() { return []; }, setAttribute() {}, removeAttribute() {},
    close() { this.open = false; } };
}

function fixture(options = {}) {
  const items = [
    { id: "synthetic:card1/PAIR.CR3", name: "PAIR.CR3", kind: "raw", sizeBytes: 3 },
    { id: "synthetic:card1/PAIR.JPG", name: "PAIR.JPG", kind: "image", sizeBytes: 3 },
    { id: "synthetic:card2/THIRD.JPG", name: "THIRD.JPG", kind: "image", sizeBytes: 3 },
  ];
  const selection = mediaBatch.createSelection();
  selection.replaceLoaded(items);
  selection.setMany(items.slice(0, options.count || 2).map(item => item.id), true);
  const state = {
    session: { id: "synthetic-session" }, media: items, mediaSelection: selection,
    mediaBatchOwner: null, mediaBatch: null, mediaDownloadOwner: null, mediaDownload: null,
    mediaUpload: null, mediaDownloadPreparing: false, busy: false, refreshGeneration: 0,
    mediaFilter: "all", mediaSort: "camera", mediaDateRange: null, mediaRatingFilter: "all",
    mediaFolderFilter: { kind: "all" }, operatorConfirmedFeatures: new Set(),
  };
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const calls = { pickers: [], requests: [], raw: [], feedback: [], renders: [], anchor: 0, directories: 0 };
  const entered = { writer: deferred(), write: deferred(), close: deferred(), abort: deferred(), api: deferred() };
  const root = { kind: "directory", name: "Synthetic chosen folder", async getDirectoryHandle(name, opts) {
    if (!opts.create) throw missing();
    calls.directories += 1;
    const files = new Map();
    return { kind: "directory", name, async getFileHandle(fileName, fileOpts) {
      if (!fileOpts.create) { if (files.has(fileName)) return files.get(fileName); throw missing(); }
      const handle = { kind: "file", name: fileName, async createWritable() {
        const index = calls.raw.length;
        const record = { name: fileName, writes: [], closes: 0, aborts: 0 };
        calls.raw.push(record);
        const raw = {
          async write(chunk) {
            record.writes.push(Array.from(chunk));
            entered.write.resolve();
            if (index === (options.holdIndex || 0) && options.writeGate) await options.writeGate.promise;
            if (index === (options.failIndex || 0) && options.writeError) throw options.writeError;
          },
          async close() {
            record.closes += 1;
            entered.close.resolve();
            if (index === (options.holdIndex || 0) && options.closeGate) await options.closeGate.promise;
            if (index === (options.failIndex || 0) && options.closeError) throw options.closeError;
          },
          async abort() {
            record.aborts += 1;
            entered.abort.resolve();
            if (options.abortGate) await options.abortGate.promise;
            if (options.abortError) throw options.abortError;
          },
        };
        entered.writer.resolve();
        if (index === 0 && options.writerGate) await options.writerGate.promise;
        return raw;
      } };
      files.set(fileName, handle);
      return handle;
    } };
  } };
  const context = vm.createContext({
    state, ui, calls, mediaBatch, mediaBatchDestination, mediaTransfer, mediaLibrary,
    AbortController, crypto: { randomUUID: () => "00000000-0000-4000-8000-000000000001" },
    window: { showDirectoryPicker(opts) {
      calls.pickers.push(opts);
      if (options.pickerError) return Promise.reject(options.pickerError);
      return options.pickerGate ? options.pickerGate.promise : Promise.resolve(root);
    } },
    document: { createElement: () => element() },
    FEATURES: { MEDIA_DOWNLOAD: "download" }, featureSupported: () => true,
    resolvedLanguage: () => "en", formatBytes: value => `${value} B`,
    t: (key, values = {}) => `${key}:${JSON.stringify(values)}`,
    api: async (url, request) => {
      calls.requests.push({ url, request });
      entered.api.resolve();
      if (options.apiGate) await options.apiGate.promise;
      if (options.apiError) throw options.apiError;
      return new Response(new Uint8Array([1, 2, 3]), { headers: { "content-length": "3" } });
    },
    saveMediaBlob: () => { calls.anchor += 1; },
    captureError: error => ({ message: error.message }),
    scheduleMediaTransferRender: () => calls.renders.push("scheduled"),
    clearScheduledMediaTransferRender: () => calls.renders.push("clear-timer"),
    renderMedia: () => { state.mediaSelection.replaceLoaded(state.media); calls.renders.push("media"); },
    renderAvailability: () => calls.renders.push("availability"),
    renderMediaPreviewNavigation: () => calls.renders.push("preview"),
    setOperationState: (...args) => calls.feedback.push(["operation", ...args]),
    showToast: (...args) => calls.feedback.push(["toast", ...args]),
  });
  for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaUpload",
    "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaPreview", "closeMediaDetails",
    "releaseObjectUrl", "clearBulbTimer", "renderHealth"]) context[name] = () => {};
  vm.runInContext(["mediaTransferActive", "cameraInteractionBusy", "displayedMedia", "renderMediaSelection",
    "renderMediaBatch", "ownsMediaBatch", "assertMediaBatchCurrent", "transferMediaBatchItem",
    "downloadSelectedMedia", "cancelMediaBatch", "cancelMediaDownload", "resetSession"].map(extract).join("\n"), context);
  context.renderMediaTransfer = () => context.renderMediaBatch();
  return { ...context, context, state, ui, calls, entered, root, items };
}
const statuses = subject => Array.from(subject.state.mediaBatch.queue.results, entry => entry.status);

test("batch uses one user picker and sequential exact-ID GETs with close receipts", async () => {
  const c = fixture();
  await c.downloadSelectedMedia();
  assert.equal(c.calls.pickers.length, 1);
  assert.equal(c.calls.pickers[0].mode, "readwrite");
  assert.equal(c.calls.directories, 1);
  assert.deepEqual(c.calls.requests.map(call => decodeURIComponent(call.url.split("/media/")[1])), c.items.slice(0, 2).map(item => item.id));
  assert.deepEqual(statuses(c), ["completed", "completed"]);
  assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
  assert.equal(c.calls.anchor, 0);
  assert.equal(c.state.mediaBatchOwner, null);
  assert.match(c.state.mediaBatch.destinationName, /Synthetic chosen folder \/ Open-EOS-/);
});

test("unsupported and denied folder access request zero originals and have no fallback", async () => {
  for (const unsupported of [true, false]) {
    const c = fixture({ pickerError: Object.assign(new Error("Denied"), { name: "NotAllowedError" }) });
    if (unsupported) delete c.window.showDirectoryPicker;
    await c.downloadSelectedMedia();
    assert.equal(c.calls.requests.length, 0);
    assert.equal(c.calls.directories, 0);
    assert.equal(c.calls.anchor, 0);
    assert.equal(c.state.mediaBatchOwner, null);
    if (!unsupported) assert.deepEqual(statuses(c), ["not-started", "not-started"]);
  }
});

test("dismissed first picker and missing UUID generation fail before any destination or GET", async () => {
  for (const missingCrypto of [false, true]) {
    const c = fixture(missingCrypto ? {} : { pickerError: Object.assign(new Error("Dismissed"), { name: "AbortError" }) });
    if (missingCrypto) c.context.crypto = {};
    await c.downloadSelectedMedia();
    assert.equal(c.calls.requests.length, 0);
    assert.equal(c.calls.directories, 0);
    assert.equal(c.calls.anchor, 0);
    assert.deepEqual(statuses(c), ["not-started", "not-started"]);
    assert.equal(c.state.mediaBatchOwner, null);
  }
});

test("cancel during picker stays busy until settlement and never creates a destination", async () => {
  const pickerGate = deferred(), c = fixture({ pickerGate });
  const pending = c.downloadSelectedMedia();
  assert.equal(c.cameraInteractionBusy(), true);
  c.cancelMediaDownload();
  assert.equal(c.cameraInteractionBusy(), true);
  assert.deepEqual(statuses(c), ["not-started", "not-started"]);
  pickerGate.resolve(c.root);
  await pending;
  assert.equal(c.cameraInteractionBusy(), false);
  assert.equal(c.calls.requests.length, 0);
  assert.equal(c.calls.directories, 0);
});

test("late picker with reused session ID cannot mutate or clear a new owner", async () => {
  const pickerGate = deferred(), c = fixture({ pickerGate });
  const pending = c.downloadSelectedMedia();
  c.state.session = { id: "synthetic-session" };
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  pickerGate.resolve(c.root);
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 0);
  assert.equal(c.calls.directories, 0);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
});

test("late writable is aborted before the first GET after reset", async () => {
  const writerGate = deferred(), c = fixture({ writerGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.writer.promise;
  c.resetSession();
  writerGate.resolve();
  await pending;
  assert.equal(c.calls.requests.length, 0);
  assert.equal(c.calls.raw[0].aborts, 1);
  assert.equal(c.state.mediaBatch, null);
  assert.equal(c.state.mediaBatchOwner, null);
  assert.equal(c.state.mediaSelection.snapshot().length, 0);
  assert.equal(c.state.mediaSelection.setSelected(c.items[0].id, true), false);
});

test("cancel during write waits for write and cleanup, then stops every later GET", async () => {
  const writeGate = deferred(), abortGate = deferred(), c = fixture({ writeGate, abortGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.write.promise;
  c.cancelMediaBatch();
  assert.equal(c.cameraInteractionBusy(), true);
  assert.equal(c.calls.requests.length, 1);
  writeGate.resolve();
  await c.entered.abort.promise;
  assert.equal(c.cameraInteractionBusy(), true);
  assert.equal(c.calls.requests.length, 1);
  abortGate.resolve();
  await pending;
  assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  assert.equal(c.calls.raw[0].closes, 0);
  assert.equal(c.cameraInteractionBusy(), false);
});

test("cancel during close preserves successful completed file and does not start next", async () => {
  const closeGate = deferred(), c = fixture({ closeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  assert.deepEqual(statuses(c), ["running", "pending"]);
  c.cancelMediaBatch();
  assert.equal(c.cameraInteractionBusy(), true);
  closeGate.resolve();
  await pending;
  assert.deepEqual(statuses(c), ["completed", "not-started"]);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].aborts, 0);
  assert.equal(c.state.mediaBatch.queue.results[0].receipt.bytesTransferred, 3);
});

test("second-file write failure preserves completed receipt and stops third", async () => {
  const c = fixture({ count: 3, failIndex: 1, writeError: new Error("Synthetic disk full") });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["completed", "failed", "not-started"]);
  assert.equal(c.calls.requests.length, 2);
  assert.equal(c.calls.raw[1].closes, 0);
  assert.equal(c.calls.raw[1].aborts, 1);
  assert.equal(c.state.mediaBatch.destination.leftoverCount, 1);
});

test("close rejection remains uncertain even when abort succeeds", async () => {
  const c = fixture({ closeError: new Error("Synthetic close result unknown") });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.state.mediaBatch.destination.unknownCount, 1);
});

test("HTTP failure plus failed cleanup reports uncertainty and retains the primary failure", async () => {
  const c = fixture({ apiError: Object.freeze(new Error("Synthetic HTTP failure")), abortError: new Error("Synthetic abort failure") });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.state.mediaBatch.queue.results[0].error.message, "Synthetic HTTP failure");
  assert.equal(c.state.mediaBatch.destination.unknownCount, 1);
});

test("old close/progress/finally cannot overwrite a replacement session or owner", async () => {
  const closeGate = deferred(), c = fixture({ closeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  const retiredBatch = c.state.mediaBatch;
  c.state.session = { id: "synthetic-session" };
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  closeGate.resolve();
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
  assert.equal(retiredBatch.queue.results[0].status, "running");
});

test("replaced batch owner in the same session rejects late API bytes and leaves the new UI alone", async () => {
  const apiGate = deferred(), c = fixture({ apiGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.api.promise;
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  apiGate.resolve();
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].writes.length, 0);
  assert.equal(c.calls.raw[0].closes, 0);
  assert.equal(c.calls.raw[0].aborts, 1);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
});

test("reused session ID during a pending write cannot publish late progress", async () => {
  const writeGate = deferred(), c = fixture({ writeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.write.promise;
  c.state.session = { id: "synthetic-session" };
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  writeGate.resolve();
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].closes, 0);
  assert.equal(c.calls.raw[0].aborts, 1);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
});

test("selection summary distinguishes hidden loaded from omitted partial-list entries", () => {
  const c = fixture();
  c.state.media = [c.items[0]];
  c.state.mediaSelection.replaceLoaded(c.state.media);
  c.renderMediaSelection([]);
  assert.match(c.ui.mediaSelectionSummary.textContent, /"selected":2,"hidden":1,"notLoaded":1/);
  assert.equal(c.ui.mediaBatchStart.disabled, false);
  delete c.window.showDirectoryPicker;
  c.renderMediaSelection([]);
  assert.equal(c.ui.mediaBatchStart.disabled, true);
  assert.equal(c.ui.mediaBatchUnavailable.hidden, false);
});

test("batch result DOM uses text nodes for untrusted names/errors and never claims running saved", () => {
  const c = fixture();
  c.state.mediaBatch = { queue: { results: [
    { id: "unsafe", item: { name: "<img src=x>" }, status: "running", error: { message: "<script>unsafe</script>" } },
  ] }, destinationName: "<b>folder</b>", destination: null };
  c.renderMediaBatch();
  assert.equal(c.ui.mediaBatchList.children[0].children[0].textContent, "<img src=x>");
  assert.equal(c.ui.mediaBatchList.children[0].children[2].textContent, "<script>unsafe</script>");
  assert.match(c.ui.mediaBatchSummary.textContent, /"completed":0/);
  assert.ok(c.ui.mediaBatchList.children[0].children.every(child => child.innerHTML === undefined));
});

test("destination names remain literal with production translation interpolation", () => {
  const c = fixture();
  c.context.messages = { en: { mediaBatchDestination: "Destination: {name}" } };
  vm.runInContext(extract("t"), c.context);
  const name = "camera $& $` $' {index}";
  c.state.mediaBatch = { queue: { results: [] }, destinationName: name, destination: null };
  c.renderMediaBatch();
  assert.equal(c.ui.mediaBatchDestination.textContent, `Destination: ${name}`);
});
