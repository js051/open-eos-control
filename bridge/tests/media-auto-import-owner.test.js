"use strict";

// Production owner callbacks, real policy/stream/destination modules, synthetic
// camera responses and filesystem handles. No camera or wall-clock timers.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaAutoImport = require("../open_eos_bridge/static/media-auto-import.js");
const mediaBatchDestination = require("../open_eos_bridge/static/media-batch-destination.js");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const start = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(start, name);
  return source.slice(start.index, source.indexOf("\n  }", start.index) + 4);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function element() {
  const listeners = new Map();
  return { hidden: false, disabled: false, textContent: "", dataset: {}, children: [], open: false,
    replaceChildren() { this.children = []; }, append(...children) { this.children.push(...children); },
    querySelectorAll() { return this.children; },
    setAttribute() {},
    addEventListener(type, fn) { listeners.set(type, fn); },
    dispatch(type) { return listeners.get(type)?.({ type }); },
  };
}
function fixture(options = {}) {
  const base = { id: "synthetic:card/OLD.JPG", name: "OLD.JPG", kind: "image", sizeBytes: 3 };
  const added = { id: "synthetic:card/NEW.JPG", name: "NEW.JPG", kind: "image", sizeBytes: 3 };
  const state = { session: { id: "synthetic-session", engine: "ccapi" }, status: {}, busy: false,
    mediaAutoImportOwner: null, mediaAutoImportReading: null, mediaAutoImport: null,
    mediaThumbnailLoads: new Set(), mediaThumbnailUrls: new Map(), mediaThumbnailFailures: new Set(), mediaGeneration: 0,
    media: [base], mediaLoadStatus: "COMPLETE", refreshGeneration: 0, operatorConfirmedFeatures: new Set() };
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const calls = { picker: 0, requests: [], directories: 0, raw: [], history: [], renders: 0, warnings: 0 };
  const entered = Object.fromEntries(["inventory", "metadata", "original", "write", "close", "abort", "history", "thumbnail"].map(name => [name, deferred()]));
  const timers = new Map();
  let timerId = 0, now = 0, inventories = 0, metadata = 0, originals = 0;
  const missing = () => Object.assign(new Error("Synthetic missing entry"), { name: "NotFoundError" });
  const root = { kind: "directory", name: options.directoryName || "Synthetic folder", async getDirectoryHandle(name, opts) {
    if (!opts.create) throw missing();
    calls.directories += 1;
    return { kind: "directory", name, async getFileHandle(filename, opts) {
      if (!opts.create) throw missing();
      return { kind: "file", name: filename, async createWritable() {
        const record = { writes: [], closes: 0, aborts: 0 };
        calls.raw.push(record);
        return {
          async write(chunk) {
            entered.write.resolve();
            record.writes.push(Array.from(chunk));
            if (options.writeGate) await options.writeGate.promise;
            if (options.writeError) throw options.writeError;
          },
          async close() {
            record.closes += 1;
            entered.close.resolve();
            if (options.closeGate) await options.closeGate.promise;
            if (options.closeError) throw options.closeError;
          },
          async abort() {
            record.aborts += 1;
            entered.abort.resolve();
            if (options.abortGate) await options.abortGate.promise;
            if (options.abortError) throw options.abortError;
          },
        };
      } };
    } };
  } };
  const window = { ...element(), showDirectoryPicker(opts) {
    calls.picker += 1;
    assert.equal(opts.mode, "readwrite");
    return options.pickerGate ? options.pickerGate.promise : Promise.resolve(root);
  }, setTimeout(fn, delay) { assert.ok(delay >= 1000); timers.set(++timerId, fn); return timerId; },
  clearTimeout(id) { timers.delete(id); } };
  const document = { ...element(), visibilityState: "visible", createElement: () => element(),
    querySelectorAll() { calls.warnings += 1; return []; } };
  const historyStore = {};
  for (const operation of ["createBatchTicket", "startAttempt", "finishAttempt"]) {
    historyStore[operation] = async (...args) => {
      calls.history.push({ operation, args });
      if (operation === options.historyGateOperation) { entered.history.resolve(); await options.historyGate.promise; }
      if (operation === options.historyFail) throw new Error("Synthetic history failure");
      return { ok: true, ticket: "synthetic-ticket", receipt: "synthetic-receipt" };
    };
  }
  const context = vm.createContext({ state, ui, window, document, mediaAutoImport, mediaBatchDestination, mediaTransfer, mediaLibrary,
    mediaHistoryStore: historyStore, AbortController, performance: { now: () => now },
    URL: { createObjectURL: () => "blob:synthetic-thumbnail" },
    MEDIA_PAGE_SIZE: 72, MAX_MEDIA_THUMBNAIL_BYTES: 1024, MAX_MEDIA_THUMBNAIL_CACHE_ITEMS: 72,
    crypto: { randomUUID: () => "00000000-0000-4000-8000-000000000001" },
    FEATURES: { MEDIA_BROWSER: "browse", MEDIA_DOWNLOAD: "download", MEDIA_THUMBNAIL: "thumbnail" },
    featureSupported: feature => !options.missingCapability || options.missingCapability !== feature,
    bulbControlLocked: () => Boolean(state.status.bulbExposureActive),
    t: (key, values = {}) => `${key}:${JSON.stringify(values)}`,
    renderAvailability: () => {
      calls.renders += 1;
      ui.shutterButton.disabled = context.cameraInteractionBusy();
      if (state.mediaAutoImportThumbnailsDeferred && !context.cameraInteractionBusy()) context.retryMediaAutoImportThumbnails();
    },
    renderMediaPreviewNavigation: () => { calls.renders += 1; ui.mediaPreviewDownload.disabled = context.cameraInteractionBusy(); },
    refreshMediaHistory: async () => { calls.renders += 1; },
    updateVisibleMediaThumbnail: () => {}, renderLatestMedia: () => {},
    api: async (url, request) => {
      calls.requests.push({ url, request });
      if (url.endsWith("/thumbnail")) {
        assert.equal(state.mediaAutoImportReading, null, "thumbnail I/O cannot overlap an import reservation");
        entered.thumbnail.resolve();
        if (options.thumbnailGate) await options.thumbnailGate.promise;
        return new Blob(["synthetic thumbnail"], { type: "image/jpeg" });
      }
      assert.equal(request.redirect, "error");
      if (url.includes("inventory=strict")) {
        entered.inventory.resolve();
        if (options.inventoryGate) await options.inventoryGate.promise;
        const items = options.inventories?.[inventories] || (inventories < 2 ? [base] : [base, added, ...(options.extraItems || [])]);
        inventories += 1;
        return Response.json({ items }, { headers: options.missingHeader === "inventory" ? {} : { "X-Open-EOS-Inventory": "complete-v1" } });
      }
      if (url.includes("fresh=true")) {
        entered.metadata.resolve();
        if (options.metadataGate) await options.metadataGate.promise;
        const sizeBytes = options.sizes?.[metadata] ?? 3;
        const metadataItem = options.metadataItems?.[metadata++] || added;
        return Response.json({ ...metadataItem, sizeBytes, ...(options.metadataFields || {}) }, { headers: options.missingHeader === "metadata" ? {} : { "X-Open-EOS-Fresh-Metadata": "v1" } });
      }
      assert.ok(url.endsWith("?attempt=single&validation=jpeg-v1"), "one strict original path, with no fallback");
      entered.original.resolve();
      const originalOptions = options.originals?.[originals++] || options;
      const headers = options.missingHeader === "original" ? {} : { "X-Open-EOS-Original-Attempt": "single-v1" };
      if (originalOptions.missingHeader !== "validation") headers["X-Open-EOS-Original-Validation"] = originalOptions.validation || "jpeg-v1";
      if (options.contentLength !== undefined) headers["Content-Length"] = String(options.contentLength);
      if (options.contentRange) headers["Content-Range"] = options.contentRange;
      const response = new Response(new Uint8Array(options.bytes || [1, 2, 3]), { status: originalOptions.originalStatus || 200, headers });
      if (options.redirected) Object.defineProperty(response, "redirected", { value: true });
      return response;
    },
  });
  for (const name of ["releaseHeldAutofocusForLifecycle", "cancelCameraFocusInfo", "stopLiveLoop", "stopLocalVideo", "stopRtpAudio", "cancelEventLoop", "cancelMediaDownload", "cancelMediaUpload",
    "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaPreview",
    "closeMediaDetails", "releaseObjectUrl", "clearBulbTimer", "renderHealth"]) context[name] = () => {};
  state.mediaDisplayZone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  const names = ["maintenanceHeld", "cancelMaintenance", "suspendMaintenanceForLifecycle", "refreshMediaDateZone", "recordingRecoveryActive", "mediaTransferActive", "cameraInteractionBusy", "mediaAutoImportEligible", "ownsMediaAutoImport",
    "mediaAutoImportManualBusy", "assertMediaAutoImportCurrent", "withMediaAutoImportRead", "requireMediaAutoImportResponse",
    "readMediaAutoImportInventory", "readMediaAutoImportMetadata", "mediaAutoImportHistoryCall", "transferMediaAutoImportItem",
    "finishMediaAutoImportOwner", "scheduleMediaAutoImport", "stepMediaAutoImport", "startMediaAutoImport", "stopMediaAutoImport", "renderMediaAutoImport", "cancelSetting", "resetSession",
    "loadMediaThumbnail", "observeMediaThumbnail", "retryMediaAutoImportThumbnails", "loadLatestMediaThumbnail"];
  const eventsStart = source.indexOf('    ui.mediaAutoImportStart?.addEventListener');
  const eventsEnd = source.indexOf('    ui.mediaScopeControl.addEventListener', eventsStart);
  assert.ok(eventsStart > 0 && eventsEnd > eventsStart);
  vm.runInContext("let mediaHistoryWarning = false; let mediaThumbnailObserver = null;\n" + names.map(extract).join("\n") + "\n" + source.slice(eventsStart, eventsEnd), context);
  async function step(at) {
    now = at;
    const owner = state.mediaAutoImportOwner;
    if (!owner) return;
    if (owner.timer !== null) window.clearTimeout(owner.timer);
    owner.timer = null;
    await context.stepMediaAutoImport(owner);
  }
  async function baseline() { await context.startMediaAutoImport(); await step(0); await step(1000); }
  async function stableCandidate() { await baseline(); await step(6000); }
  return { context, state, ui, window, document, calls, entered, timers, root, base, added, step, baseline, stableCandidate };
}
const originalCalls = c => c.calls.requests.filter(call => call.url.endsWith("?attempt=single&validation=jpeg-v1"));
const finishCalls = c => c.calls.history.filter(call => call.operation === "finishAttempt");

test("explicit user picker establishes two complete baselines without output, then saves one stable JPEG", async () => {
  const c = fixture();
  c.context.renderMediaAutoImport();
  assert.equal(c.calls.picker, 0);
  assert.equal(c.calls.requests.length, 0);
  await c.ui.mediaAutoImportStart.dispatch("click");
  assert.equal(c.calls.picker, 1);
  assert.equal(c.calls.requests.length, 0);
  assert.equal(c.context.cameraInteractionBusy(), false, "an idle watcher does not lock camera controls");
  assert.equal(c.timers.size, 1);
  await c.step(0);
  await c.step(1000);
  assert.equal(c.ui.mediaAutoImportPanel.dataset.phase, "watching");
  assert.equal(c.calls.directories, 0);
  assert.equal(c.calls.history.length, 0);
  await c.step(6000);
  assert.equal(originalCalls(c).length, 0, "one fresh positive size is insufficient");
  await c.step(6500);
  assert.equal(originalCalls(c).length, 0);
  await c.step(7000);
  assert.equal(originalCalls(c).length, 1);
  assert.equal(c.calls.directories, 1);
  assert.equal(c.calls.raw[0].closes, 1);
  assert.equal(c.calls.raw[0].aborts, 0);
  assert.equal(c.state.mediaAutoImport.snapshot.completed, 1);
  assert.equal(c.ui.mediaAutoImportList.children[0].dataset.id, c.added.id);
  assert.equal(c.ui.mediaAutoImportList.children[0].dataset.status, "completed");
  assert.equal(finishCalls(c)[0].args[1].bytesTransferred, 3);
  assert.equal(c.context.cameraInteractionBusy(), false);
  assert.equal(c.timers.size, 1);
});

test("unsupported browsers, module, USB, recording, Bulb or missing capabilities cannot enable import", async () => {
  for (const mode of ["picker", "uuid", "module", "usb", "recording", "bulb", "capability", "hidden"]) {
    const c = fixture(mode === "capability" ? { missingCapability: "download" } : {});
    if (mode === "picker") delete c.window.showDirectoryPicker;
    if (mode === "uuid") c.context.crypto = {};
    if (mode === "module") c.context.mediaAutoImport = null;
    if (mode === "usb") c.state.session.engine = "libgphoto2";
    if (mode === "recording") c.state.status.recording = true;
    if (mode === "bulb") c.state.status.bulbExposureActive = true;
    if (mode === "hidden") c.document.visibilityState = "hidden";
    await c.context.startMediaAutoImport();
    assert.equal(c.calls.picker, 0, mode);
    assert.equal(c.calls.requests.length, 0, mode);
    assert.equal(c.state.mediaAutoImportOwner, null, mode);
  }
});

test("late picker after stop or session replacement creates no destination and cannot touch a new owner", async () => {
  for (const replace of [false, true]) {
    const gate = deferred(), c = fixture({ pickerGate: gate });
    const pending = c.context.startMediaAutoImport();
    assert.equal(c.calls.picker, 1, "picker starts synchronously in the initiating click");
    c.context.stopMediaAutoImport();
    const replacement = { session: { id: "synthetic-session", engine: "ccapi" } };
    const replacementView = { newRun: true };
    if (replace) { c.state.session = replacement.session; c.state.mediaAutoImportOwner = replacement; c.state.mediaAutoImport = replacementView; }
    const renders = c.calls.renders;
    gate.resolve(c.root);
    await pending;
    assert.equal(c.calls.requests.length, 0);
    assert.equal(c.calls.directories, 0);
    assert.equal(c.timers.size, 0);
    if (replace) {
      assert.equal(c.state.mediaAutoImportOwner, replacement);
      assert.equal(c.state.mediaAutoImport, replacementView);
      assert.equal(c.calls.renders, renders);
    } else assert.equal(c.state.mediaAutoImportOwner, null);
  }
});

test("legacy responses missing strict inventory or fresh metadata evidence fail closed", async () => {
  for (const missingHeader of ["inventory", "metadata"]) {
    const c = fixture({ missingHeader });
    await c.stableCandidate();
    await c.step(7000);
    assert.equal(c.state.mediaAutoImportOwner, null);
    assert.equal(originalCalls(c).length, 0);
    assert.equal(c.calls.directories, 0);
    assert.equal(c.timers.size, 0);
  }
});

test("strict originals require positive header, status 200 and no range or redirect", async () => {
  for (const options of [{ missingHeader: "original" }, { originalStatus: 206 }, { contentRange: "bytes 0-2/3" }, { redirected: true }]) {
    const c = fixture(options);
    await c.stableCandidate();
    await c.step(7000);
    assert.equal(originalCalls(c).length, 1);
    assert.equal(c.calls.raw[0].closes, 0);
    assert.equal(c.calls.raw[0].aborts, 1);
    assert.equal(c.state.mediaAutoImport.snapshot.completed, 0);
    assert.equal(c.state.mediaAutoImportOwner, null);
  }
});

test("stable expected size overrides HTTP length, caps every chunk and verifies final bytes before close", async () => {
  for (const options of [{ contentLength: 2, bytes: [1, 2] }, { contentLength: 4, bytes: [1, 2, 3, 4] },
    { bytes: [1, 2, 3, 4] }, { bytes: [1, 2] }, { contentLength: "invalid" }]) {
    const c = fixture(options);
    await c.stableCandidate();
    await c.step(7000);
    assert.equal(originalCalls(c).length, 1);
    assert.equal(c.calls.raw[0].closes, 0);
    assert.equal(c.calls.raw[0].aborts, 1);
    if (options.bytes?.length === 4 || options.contentLength !== undefined) assert.equal(c.calls.raw[0].writes.length, 0);
    assert.equal(c.state.mediaAutoImport.snapshot.completed, 0);
  }
});

test("manual work yields between steps without camera reads; inventory/metadata/transfer reserve only their operation", async () => {
  const c = fixture();
  await c.baseline();
  for (const field of ["busy", "mediaRefreshPromise", "latestMediaRefreshPromise", "mediaPreviewItem", "mediaDetailsBusy", "mediaDownload", "mediaUpload", "mediaBatchOwner"]) {
    c.state[field] = true;
    const count = c.calls.requests.length;
    await c.step(6000);
    assert.equal(c.calls.requests.length, count, field);
    c.state[field] = null;
  }
  c.state.mediaThumbnailLoads.add("synthetic-other");
  await c.step(6000);
  assert.equal(c.calls.requests.length, 2);
  c.state.mediaThumbnailLoads.clear();
  await c.step(6000);
  const writeGate = deferred(), d = fixture({ writeGate });
  await d.stableCandidate();
  const pending = d.step(7000);
  await d.entered.write.promise;
  assert.equal(d.context.cameraInteractionBusy(), true);
  d.context.stopMediaAutoImport();
  assert.equal(d.context.cameraInteractionBusy(), true, "stop retains the unsettled write reservation");
  writeGate.resolve();
  await pending;
  assert.equal(d.context.cameraInteractionBusy(), false);
  assert.equal(d.calls.raw[0].closes, 0);
  assert.equal(d.calls.raw[0].aborts, 1);
  assert.equal(d.timers.size, 0);
});

test("stop during inventory holds its owner until settlement and starts no later metadata", async () => {
  const gate = deferred(), c = fixture({ inventoryGate: gate });
  await c.context.startMediaAutoImport();
  const pending = c.step(0);
  await c.entered.inventory.promise;
  const owner = c.state.mediaAutoImportOwner;
  assert.equal(c.context.cameraInteractionBusy(), true);
  c.context.stopMediaAutoImport();
  assert.equal(c.state.mediaAutoImportOwner, owner);
  assert.equal(c.context.cameraInteractionBusy(), true);
  gate.resolve();
  await pending;
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.context.cameraInteractionBusy(), false);
});

test("hidden/pagehide stop timers without automatic restart when foreground returns", async () => {
  for (const pagehide of [false, true]) {
    const c = fixture();
    await c.baseline();
    if (pagehide) c.window.dispatch("pagehide");
    else { c.document.visibilityState = "hidden"; c.document.dispatch("visibilitychange"); }
    assert.equal(c.timers.size, 0);
    assert.equal(c.state.mediaAutoImportOwner, null);
    c.document.visibilityState = "visible";
    c.document.dispatch("visibilitychange");
    assert.equal(c.timers.size, 0);
    assert.equal(c.calls.picker, 1);
    assert.equal(c.calls.requests.length, 2);
  }
});

test("visible return and BFCache pageshow enable a new explicit start without resuming the stopped run", async () => {
  for (const pagehide of [false, true]) {
    const c = fixture();
    await c.baseline();
    c.document.visibilityState = "hidden";
    if (pagehide) c.window.dispatch("pagehide");
    else c.document.dispatch("visibilitychange");
    assert.equal(c.state.mediaAutoImportOwner, null);
    assert.equal(c.ui.mediaAutoImportStart.disabled, true);
    assert.equal(c.timers.size, 0);
    const requests = c.calls.requests.length;
    c.document.visibilityState = "visible";
    if (pagehide) c.window.dispatch("pageshow");
    else c.document.dispatch("visibilitychange");
    assert.equal(c.ui.mediaAutoImportStart.disabled, false);
    assert.equal(c.state.mediaAutoImportOwner, null);
    assert.equal(c.ui.mediaAutoImportPanel.dataset.phase, "stopped");
    assert.equal(c.timers.size, 0);
    assert.equal(c.calls.picker, 1);
    assert.equal(c.calls.requests.length, requests);
    assert.equal(c.calls.directories, 0);
    await c.ui.mediaAutoImportStart.dispatch("click");
    assert.equal(c.calls.picker, 2);
    assert.ok(c.state.mediaAutoImportOwner);
    assert.equal(c.timers.size, 1);
    assert.equal(c.calls.requests.length, requests);
    assert.equal(c.calls.directories, 0);
  }
});

test("stop between stable metadata observations requests no original and no output folder", async () => {
  const c = fixture();
  await c.stableCandidate();
  c.context.stopMediaAutoImport();
  await c.step(7000);
  assert.equal(originalCalls(c).length, 0);
  assert.equal(c.calls.directories, 0);
  assert.equal(c.state.mediaAutoImport.snapshot.pendingCount, 1);
});

test("a late successful close keeps its receipt after stop, and an old owner cannot alter replacement UI", async () => {
  for (const replace of [false, true]) {
    const gate = deferred(), c = fixture({ closeGate: gate });
    await c.stableCandidate();
    const owner = c.state.mediaAutoImportOwner;
    const pending = c.step(7000);
    await c.entered.close.promise;
    c.context.stopMediaAutoImport();
    const replacement = { session: { id: "synthetic-session", engine: "ccapi" } };
    const view = { replacement: true };
    if (replace) { c.state.session = replacement.session; c.state.mediaAutoImportOwner = replacement; c.state.mediaAutoImport = view; }
    const renders = c.calls.renders;
    const status = c.ui.mediaAutoImportStatus.textContent;
    gate.resolve();
    await pending;
    assert.equal(originalCalls(c).length, 1);
    assert.equal(c.calls.raw[0].closes, 1);
    assert.equal(c.calls.raw[0].aborts, 0);
    assert.equal(owner.policy.snapshot().completed, 1);
    assert.equal(finishCalls(c)[0].args[1].outcome, "completed");
    if (replace) {
      assert.equal(c.state.mediaAutoImportOwner, replacement);
      assert.equal(c.state.mediaAutoImport, view);
      assert.equal(c.calls.renders, renders + 2, "only current availability and navigation refresh after releasing the old reservation");
      assert.equal(c.ui.shutterButton.disabled, false);
      assert.equal(c.ui.mediaPreviewDownload.disabled, false);
      assert.equal(c.ui.mediaAutoImportStatus.textContent, status);
    } else assert.equal(c.state.mediaAutoImport.snapshot.completed, 1);
  }
});

test("history ticket/admission/finish failure never changes the successful close or GET count", async () => {
  for (const historyFail of ["createBatchTicket", "startAttempt", "finishAttempt"]) {
    const c = fixture({ historyFail });
    await c.stableCandidate();
    await c.step(7000);
    assert.equal(originalCalls(c).length, 1);
    assert.equal(c.calls.raw[0].closes, 1);
    assert.equal(c.calls.raw[0].aborts, 0);
    assert.equal(c.state.mediaAutoImport.snapshot.completed, 1);
  }
});

test("delayed history admission rechecks cancellation before destination or original I/O", async () => {
  const gate = deferred(), c = fixture({ historyGateOperation: "startAttempt", historyGate: gate });
  await c.stableCandidate();
  const pending = c.step(7000);
  await c.entered.history.promise;
  c.context.stopMediaAutoImport();
  gate.resolve();
  await pending;
  assert.equal(originalCalls(c).length, 0);
  assert.equal(c.calls.directories, 0);
  assert.equal(finishCalls(c)[0].args[1].outcome, "cancelled-or-unknown");
});

test("close rejection and abort failure remain uncertain and never become saved", async () => {
  const c = fixture({ closeError: Object.freeze(new Error("Synthetic unconfirmed close")), abortError: new Error("Synthetic failed abort") });
  await c.stableCandidate();
  await c.step(7000);
  assert.equal(c.state.mediaAutoImport.snapshot.completed, 0);
  assert.equal(c.state.mediaAutoImport.snapshot.results[0].status, "cancelled-or-unknown");
  assert.equal(finishCalls(c)[0].args[1].outcomeUnknown, true);
  assert.equal(originalCalls(c).length, 1);
});

test("rendering uses literal destination and filenames, with localized unknown stop reasons", async () => {
  const c = fixture({ directoryName: "<img src=x> $& {name}" });
  c.added.name = "<img src=x>.JPG";
  await c.stableCandidate();
  await c.step(7000);
  assert.match(c.ui.mediaAutoImportDestination.textContent, /<img src=x>/);
  assert.equal(c.ui.mediaAutoImportList.children[0].children[0].textContent, c.added.name);
  assert.equal(c.ui.mediaAutoImportList.children[0].children[0].innerHTML, undefined);
  c.context.stopMediaAutoImport("synthetic-unknown");
  assert.match(c.ui.mediaAutoImportReason.textContent, /mediaAutoImportUnknown/);
});

test("the production API forwards strict redirect refusal while retaining the manual default", async () => {
  const c = fixture();
  const requests = [];
  c.context.Headers = Headers;
  c.context.fetch = async (url, options) => { requests.push(options); return new Response("synthetic"); };
  vm.runInContext(extract("api"), c.context);
  await c.context.api("/synthetic", { responseType: "response", redirect: "error" });
  await c.context.api("/synthetic", { responseType: "response" });
  assert.equal(requests[0].redirect, "error");
  assert.equal(requests[1].redirect, "follow");
});

test("the foreground scheduler coalesces duplicate steps while inventory is pending", async () => {
  const gate = deferred(), c = fixture({ inventoryGate: gate });
  await c.context.startMediaAutoImport();
  const owner = c.state.mediaAutoImportOwner;
  const pending = c.step(0);
  await c.entered.inventory.promise;
  await c.context.stepMediaAutoImport(owner);
  c.context.scheduleMediaAutoImport(owner);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.timers.size, 0);
  gate.resolve();
  await pending;
  c.context.scheduleMediaAutoImport(owner);
  assert.equal(c.timers.size, 1);
});

test("lost capability or recording while idle stops without another camera read", async () => {
  for (const mode of ["capability", "recording", "bulb"]) {
    const c = fixture();
    await c.baseline();
    if (mode === "capability") c.context.featureSupported = () => false;
    if (mode === "recording") c.state.status.recording = true;
    if (mode === "bulb") c.state.status.bulbExposureActive = true;
    await c.step(6000);
    assert.equal(c.calls.requests.length, 2);
    assert.equal(c.state.mediaAutoImportOwner, null);
    assert.equal(c.state.mediaAutoImport.snapshot.reason, "camera-unavailable");
    assert.equal(c.timers.size, 0);
  }
});

test("a failing late history receipt cannot warn or render into a replacement session", async () => {
  const gate = deferred(), c = fixture({ historyGateOperation: "finishAttempt", historyGate: gate, historyFail: "finishAttempt" });
  await c.stableCandidate();
  const pending = c.step(7000);
  await c.entered.history.promise;
  const oldOwner = c.state.mediaAutoImportOwner;
  c.context.stopMediaAutoImport();
  c.state.session = { id: "synthetic-session", engine: "ccapi" };
  c.state.mediaAutoImportOwner = { session: c.state.session };
  const replacementView = { newRun: true };
  c.state.mediaAutoImport = replacementView;
  c.ui.mediaHistoryDialog.open = true;
  const renders = c.calls.renders, warnings = c.calls.warnings;
  gate.resolve();
  await pending;
  assert.equal(oldOwner.policy.snapshot().completed, 1);
  assert.equal(c.state.mediaAutoImport, replacementView);
  assert.equal(c.calls.renders, renders + 2, "current controls refresh when the old reservation settles");
  assert.equal(c.ui.shutterButton.disabled, false);
  assert.equal(c.calls.warnings, warnings);
});

test("reset and reconnect retain the old active reservation until settlement then refresh current controls", async () => {
  const gate = deferred(), c = fixture({ closeGate: gate });
  await c.stableCandidate();
  const pending = c.step(7000);
  await c.entered.close.promise;
  const oldOwner = c.state.mediaAutoImportOwner;
  c.context.resetSession();
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.state.mediaAutoImportReading, oldOwner);
  c.state.session = { id: "synthetic-session", engine: "ccapi" };
  const replacementOwner = { session: c.state.session };
  const replacementView = { newRun: true };
  c.state.mediaAutoImportOwner = replacementOwner;
  c.state.mediaAutoImport = replacementView;
  c.ui.mediaHistoryDialog.open = true;
  c.context.renderAvailability();
  c.context.renderMediaPreviewNavigation();
  assert.equal(c.ui.shutterButton.disabled, true);
  assert.equal(c.ui.mediaPreviewDownload.disabled, true);
  const status = c.ui.mediaAutoImportStatus.textContent;
  const renders = c.calls.renders, warnings = c.calls.warnings;
  gate.resolve();
  await pending;
  assert.equal(c.state.mediaAutoImportReading, null);
  assert.equal(c.context.cameraInteractionBusy(), false);
  assert.equal(c.ui.shutterButton.disabled, false);
  assert.equal(c.ui.mediaPreviewDownload.disabled, false);
  assert.equal(c.calls.renders, renders + 2);
  assert.equal(c.calls.warnings, warnings);
  assert.equal(c.state.mediaAutoImportOwner, replacementOwner);
  assert.equal(c.state.mediaAutoImport, replacementView);
  assert.equal(c.ui.mediaAutoImportStatus.textContent, status);
  assert.equal(oldOwner.policy.snapshot().completed, 1);
  assert.equal(finishCalls(c)[0].args[1].outcome, "completed");
});

test("an old settlement cannot clear or refresh a newer operation reservation", async () => {
  const gate = deferred(), c = fixture({ closeGate: gate });
  await c.stableCandidate();
  const pending = c.step(7000);
  await c.entered.close.promise;
  c.context.resetSession();
  c.state.session = { id: "synthetic-session", engine: "ccapi" };
  const replacement = { session: c.state.session };
  c.state.mediaAutoImportOwner = replacement;
  c.state.mediaAutoImportReading = replacement;
  const view = { newRun: true };
  c.state.mediaAutoImport = view;
  const renders = c.calls.renders;
  gate.resolve();
  await pending;
  assert.equal(c.state.mediaAutoImportReading, replacement);
  assert.equal(c.state.mediaAutoImportOwner, replacement);
  assert.equal(c.state.mediaAutoImport, view);
  assert.equal(c.context.cameraInteractionBusy(), true);
  assert.equal(c.calls.renders, renders);
});

test("an intersection during held inventory is re-observed after settlement and reads its thumbnail once", async () => {
  const gate = deferred(), c = fixture({ inventoryGate: gate });
  const thumbnail = element();
  thumbnail.dataset.mediaId = c.base.id;
  c.ui.mediaList.children = [thumbnail];
  let observer, observations = 0, unobserved = 0;
  const Observer = class {
    constructor(callback) { this.callback = callback; observer = this; }
    observe(target) {
      observations += 1;
      if (observations > 1) queueMicrotask(() => this.callback([{ target, isIntersecting: true }], this));
    }
    unobserve() { unobserved += 1; }
  };
  c.context.IntersectionObserver = Observer;
  c.window.IntersectionObserver = Observer;
  await c.context.startMediaAutoImport();
  const pending = c.step(0);
  await c.entered.inventory.promise;
  c.context.observeMediaThumbnail(thumbnail);
  observer.callback([{ target: thumbnail, isIntersecting: true }], observer);
  assert.equal(c.state.mediaAutoImportThumbnailsDeferred, true);
  assert.equal(c.calls.requests.filter(call => call.url.endsWith("/thumbnail")).length, 0);
  assert.equal(unobserved, 1);
  gate.resolve();
  await pending;
  await c.entered.thumbnail.promise;
  assert.equal(observations, 2);
  assert.equal(unobserved, 2);
  assert.equal(c.calls.requests.filter(call => call.url.endsWith("/thumbnail")).length, 1);
  assert.equal(c.state.mediaAutoImportThumbnailsDeferred, false);
  assert.match(extract("renderAvailability"), /retryMediaAutoImportThumbnails\(\)/);
});

test("deferred thumbnail retry is bounded by the current rendered page and preserves the no-observer fallback", async () => {
  const gate = deferred(), c = fixture({ inventoryGate: gate });
  const thumbnail = element();
  thumbnail.dataset.mediaId = c.base.id;
  c.ui.mediaList.children = [thumbnail];
  await c.context.startMediaAutoImport();
  const pending = c.step(0);
  await c.entered.inventory.promise;
  c.context.observeMediaThumbnail(thumbnail);
  assert.equal(c.calls.requests.filter(call => call.url.endsWith("/thumbnail")).length, 0);
  gate.resolve();
  await pending;
  await c.entered.thumbnail.promise;
  assert.equal(c.calls.requests.filter(call => call.url.endsWith("/thumbnail")).length, 1);
  let observed = 0;
  c.context.observeMediaThumbnail = () => { observed += 1; };
  c.ui.mediaList.children = Array.from({ length: 1000 }, () => element());
  c.state.mediaAutoImportThumbnailsDeferred = true;
  c.context.retryMediaAutoImportThumbnails();
  assert.equal(observed, 72);
});

test("latest thumbnail waits for an import read and rejects late demand for a reused-ID session", async () => {
  for (const replace of [false, true]) {
    const gate = deferred(), c = fixture({ inventoryGate: gate });
    await c.context.startMediaAutoImport();
    const pending = c.step(0);
    await c.entered.inventory.promise;
    c.state.latestMediaGeneration = 1;
    c.state.latestMediaItem = c.base;
    c.state.latestMediaThumbnailLoading = true;
    const thumbnail = c.context.loadLatestMediaThumbnail(c.base, 1, c.state.session.id, new AbortController().signal);
    assert.equal(c.calls.requests.filter(call => call.url.endsWith("/thumbnail")).length, 0);
    if (replace) {
      c.context.stopMediaAutoImport();
      c.state.session = { id: "synthetic-session", engine: "ccapi" };
      c.state.mediaAutoImportOwner = null;
    }
    gate.resolve();
    await pending;
    await thumbnail;
    assert.equal(c.calls.requests.filter(call => call.url.endsWith("/thumbnail")).length, replace ? 0 : 1);
    if (!replace) assert.equal(c.state.latestMediaThumbnailLoading, false);
  }
});

test("visible return during latest-thumbnail work restores Start only after the thumbnail and refresh promise settle", async () => {
  const gate = deferred(), c = fixture({ thumbnailGate: gate });
  await c.baseline();
  c.context.mediaIsVideo = mediaLibrary.isVideo;
  c.context.LATEST_MEDIA_LIMIT = 5;
  c.context.LATEST_MEDIA_RETRY_DELAYS_MILLIS = [];
  c.context.latestMediaFrom = items => items[0];
  c.state.latestMediaGeneration = 1;
  const thumbnailApi = c.context.api;
  c.context.api = (url, options) => url.includes("?limit=") ? Promise.resolve({ items: [c.base] }) : thumbnailApi(url, options);
  vm.runInContext(["renderLatestMedia", "refreshLatestMedia", "publishLatestMedia"].map(extract).join("\n"), c.context);
  const pending = c.context.refreshLatestMedia();
  await c.entered.thumbnail.promise;
  c.document.visibilityState = "hidden";
  c.document.dispatch("visibilitychange");
  c.document.visibilityState = "visible";
  c.document.dispatch("visibilitychange");
  assert.equal(c.ui.mediaAutoImportStart.disabled, true);
  assert.ok(c.state.latestMediaRefreshPromise);
  assert.equal(c.state.latestMediaThumbnailLoading, true);
  const requests = c.calls.requests.length;
  gate.resolve();
  await pending;
  assert.equal(c.state.latestMediaRefreshPromise, null);
  assert.equal(c.state.latestMediaThumbnailLoading, false);
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.timers.size, 0);
  assert.equal(c.calls.picker, 1);
  assert.equal(c.calls.requests.length, requests);
  assert.equal(originalCalls(c).length, 0);
});

test("initial gallery thumbnail blocks Start until settlement, then one explicit gesture establishes the baseline", async () => {
  const gate = deferred(), c = fixture({ thumbnailGate: gate });
  vm.runInContext(extract("updateVisibleMediaThumbnail"), c.context);
  c.context.renderMediaThumbnail = () => {};
  const thumbnail = element();
  thumbnail.dataset.mediaId = c.base.id;
  c.ui.mediaList.children = [thumbnail];
  c.context.renderMediaAutoImport();
  assert.equal(c.ui.mediaAutoImportPanel.dataset.phase, "off");
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
  const pending = c.context.loadMediaThumbnail(c.base);
  await c.entered.thumbnail.promise;
  assert.equal(c.state.mediaThumbnailLoads.size, 1);
  assert.equal(c.ui.mediaAutoImportStart.disabled, true);
  await c.context.startMediaAutoImport();
  assert.equal(c.calls.picker, 0, "The admission guard also refuses a call during gallery I/O");
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.timers.size, 0);
  assert.equal(originalCalls(c).length, 0);
  gate.resolve();
  await pending;
  assert.equal(c.state.mediaThumbnailLoads.size, 0);
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
  assert.equal(c.ui.mediaAutoImportPanel.dataset.phase, "off");
  assert.equal(c.calls.picker, 0, "Thumbnail settlement cannot open the picker or enable import itself");
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.timers.size, 0);
  const started = c.ui.mediaAutoImportStart.dispatch("click");
  assert.equal(c.calls.picker, 1, "The one explicit gesture opens its picker synchronously");
  await started;
  await c.step(0);
  await c.step(1000);
  assert.equal(c.ui.mediaAutoImportPanel.dataset.phase, "watching");
  assert.equal(c.calls.requests.filter(call => call.url.includes("inventory=strict")).length, 2);
  assert.equal(c.calls.directories, 0);
  assert.equal(originalCalls(c).length, 0);
});

test("grid thumbnail settlement refreshes stopped import Start without restarting it", async () => {
  const gate = deferred(), c = fixture({ thumbnailGate: gate });
  await c.baseline();
  vm.runInContext(extract("updateVisibleMediaThumbnail"), c.context);
  c.context.renderMediaThumbnail = () => {};
  const thumbnail = element();
  thumbnail.dataset.mediaId = c.base.id;
  c.ui.mediaList.children = [thumbnail];
  const pending = c.context.loadMediaThumbnail(c.base);
  await c.entered.thumbnail.promise;
  c.document.visibilityState = "hidden";
  c.document.dispatch("visibilitychange");
  c.document.visibilityState = "visible";
  c.document.dispatch("visibilitychange");
  assert.equal(c.ui.mediaAutoImportStart.disabled, true);
  gate.resolve();
  await pending;
  assert.equal(c.state.mediaThumbnailLoads.size, 0);
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.timers.size, 0);
  assert.equal(c.calls.picker, 1);
  assert.equal(originalCalls(c).length, 0);
});

test("details completion, details close and preview close refresh stopped import Start without restarting", async () => {
  const gate = deferred(), c = fixture();
  await c.baseline();
  c.context.stopMediaAutoImport();
  c.context.mediaMutations = require("../open_eos_bridge/static/media-mutations.js");
  vm.runInContext(["renderMediaDetails", "clearMediaDetails", "updateMediaMetadata", "runMediaMutation", "ownsMediaMutation", "renderMediaMutation"].map(extract).join("\n"), c.context);
  c.state.mediaDetailsGeneration = 1;
  c.state.mediaDetailsItem = c.base;
  c.context.api = () => gate.promise;
  c.context.replaceMediaItem = () => {};
  c.context.renderMedia = () => {};
  c.context.renderAvailability = () => c.context.renderMediaAutoImport();
  c.context.showToast = () => {};
  const pending = c.context.updateMediaMetadata("MEDIA_RATING", "rating", { value: 1 });
  assert.ok(c.state.mediaMutationOwner);
  assert.equal(c.ui.mediaAutoImportStart.disabled, true);
  gate.resolve({ ...c.base, rating: 1 });
  await pending;
  assert.equal(c.state.mediaMutationOwner, null);
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
  c.state.mediaDetailsBusy = true;
  c.context.renderMediaDetails();
  assert.equal(c.ui.mediaAutoImportStart.disabled, true);
  c.context.clearMediaDetails();
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
  c.state.mediaPreviewItem = c.base;
  c.context.renderMediaAutoImport();
  assert.equal(c.ui.mediaAutoImportStart.disabled, true);
  c.state.mediaPreviewItem = null;
  c.context.previewableMedia = () => [];
  vm.runInContext(extract("renderMediaPreviewNavigation"), c.context);
  c.context.renderMediaPreviewNavigation();
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.timers.size, 0);
  assert.equal(c.calls.picker, 1);
  assert.equal(originalCalls(c).length, 0);
});


test("fresh type contradictions stop the real owner before creating destination or requesting an original", async () => {
  for (const metadataFields of [{ kind: "video" }, { kind: "raw" }, { kind: "IMAGE" },
    { name: "OTHER.JPG" }, { contentType: "video/mp4" }, { contentType: "image/jpg" },
    { contentType: "application/octet-stream" }, { contentType: " " }, { contentType: "image/jpeg; x=\n" }]) {
    const c = fixture({ metadataFields }); await c.stableCandidate(); await c.step(7000);
    assert.equal(originalCalls(c).length, 0); assert.equal(c.calls.directories, 0);
    assert.equal(c.calls.raw.length, 0); assert.equal(c.calls.history.length, 0);
  }
});

test("unknown or contradictory media cannot enter gallery/latest image decode paths", async () => {
  for (const item of [{ id: "unknown", name: "FILE.bin", kind: "unknown" },
    { id: "conflict", name: "FRAME.JPG", kind: "video" },
    { id: "audio", name: "SOUND.wav", kind: "audio" }]) {
    const c = fixture(); await c.context.loadMediaThumbnail(item);
    c.state.latestMediaItem = item; c.state.latestMediaGeneration = 1;
    await c.context.loadLatestMediaThumbnail(item, 1, c.state.session.id, new AbortController().signal);
    assert.equal(c.calls.requests.length, 0);
  }
});

test("cached thumbnail URLs cannot render an image after same-ID classification becomes unknown or conflicting", () => {
  const c = fixture(), created = [];
  c.context.mediaIsVideo = mediaLibrary.isVideo;
  c.document.createElement = (tag) => { created.push(tag); return element(); };
  vm.runInContext(extract("renderMediaThumbnail") + "\n" + extract("renderLatestMedia"), c.context);
  const container = element(); container.classList = { toggle() {} };
  for (const item of [{ ...c.base, kind: "video" }, { ...c.base, name: "FILE.bin", kind: "unknown" }]) {
    c.context.renderMediaThumbnail(container, item, "blob:previously-valid-image");
    c.state.latestMediaItem = item; c.state.latestMediaThumbnailUrl = "blob:previously-valid-image";
    c.context.renderLatestMedia();
  }
  assert.equal(created.includes("img"), false);
  c.context.renderMediaThumbnail(container, c.base, "blob:photo");
  c.context.renderMediaThumbnail(container, { ...c.base, name: "CLIP.MP4", kind: "video" }, "blob:video-poster");
  assert.equal(created.filter((tag) => tag === "img").length, 2, "valid photo and video-poster paths remain supported");
});

test("late gallery thumbnail does not publish after same-ID name or classification changes", async () => {
  for (const changes of [{ kind: "video" }, { name: "OTHER.JPG" }, { kind: "unknown", name: "FILE.bin" }]) {
    const gate = deferred(), c = fixture({ thumbnailGate: gate });
    const container = element(); container.dataset.mediaId = c.base.id; c.ui.mediaList.children = [container];
    const pending = c.context.loadMediaThumbnail(c.base); await c.entered.thumbnail.promise;
    c.state.media = [{ ...c.base, ...changes }]; gate.resolve(); await pending;
    assert.equal(c.state.mediaThumbnailUrls.size, 0);
    assert.equal(c.state.mediaThumbnailLoads.size, 0);
  }
});

test("late latest-photo thumbnail and a queued request recheck current name/type before decode", async () => {
  for (const queued of [false, true]) {
    const gate = deferred(), c = fixture(queued ? {} : { thumbnailGate: gate });
    c.state.latestMediaItem = c.base; c.state.latestMediaGeneration = 1; c.state.latestMediaThumbnailUrl = null;
    if (queued) c.state.mediaAutoImportReading = { readSettled: gate.promise };
    const pending = c.context.loadLatestMediaThumbnail(c.base, 1, c.state.session.id, new AbortController().signal);
    if (!queued) await c.entered.thumbnail.promise;
    c.state.latestMediaItem = { ...c.base, kind: "video" };
    c.state.mediaAutoImportReading = null; gate.resolve(); await pending;
    assert.equal(c.state.latestMediaThumbnailUrl, null);
    assert.equal(c.calls.requests.length, queued ? 0 : 1);
  }
});


test("automatic originals require exact decode proof before writes or close, and never retry", async () => {
  for (const options of [{ missingHeader: "validation" }, { validation: "jpeg-v0" },
    { validation: "jpeg-v1, jpeg-v1" }, { originalStatus: 502 }]) {
    const c = fixture(options);
    await c.stableCandidate();
    await c.step(7000);
    assert.equal(originalCalls(c).length, 1);
    assert.equal(c.calls.raw[0].writes.length, 0);
    assert.equal(c.calls.raw[0].closes, 0);
    assert.equal(c.calls.raw[0].aborts, 1);
    assert.equal(c.state.mediaAutoImport.snapshot.completed, 0);
    assert.equal(c.state.mediaAutoImport.snapshot.results[0].status, "failed");
    assert.equal(finishCalls(c)[0].args[1].outcome, "failed");
    assert.equal(c.state.mediaAutoImportOwner, null);
    assert.equal(c.timers.size, 0);
    await c.step(60000);
    assert.equal(originalCalls(c).length, 1, "no automatic redownload after validation failure");
    assert.equal(c.ui.mediaAutoImportStart.disabled, false, "explicit re-enable remains available");
    assert.equal(c.ui.mediaAutoImportReason.textContent.startsWith("mediaAutoImportFailed:"), true);
  }
});

test("failure recovery retains delivered files/history and fresh baselines skip all existing IDs", async () => {
  const base = { id: "synthetic:card/OLD.JPG", name: "OLD.JPG", kind: "image", sizeBytes: 3 };
  const first = { id: "synthetic:card/NEW.JPG", name: "NEW.JPG", kind: "image", sizeBytes: 3 };
  const second = { id: "synthetic:card/SECOND.JPG", name: "SECOND.JPG", kind: "image", sizeBytes: 3 };
  const third = { id: "synthetic:card/THIRD.JPG", name: "THIRD.JPG", kind: "image", sizeBytes: 3 };
  const existing = [base, first, second];
  const c = fixture({ inventories: [[base], [base], existing, existing, existing, [...existing, third]],
    metadataItems: [first, first, second, second, third, third],
    originals: [{}, { originalStatus: 502 }, {}] });
  await c.baseline();
  for (const at of [6000, 7000, 8000, 9000]) await c.step(at);
  assert.equal(c.state.mediaAutoImport.snapshot.completed, 1);
  assert.deepEqual(Array.from(c.state.mediaAutoImport.snapshot.results, row => row.status), ["completed", "failed"]);
  assert.equal(c.calls.raw[0].closes, 1);
  assert.equal(c.calls.raw[0].aborts, 0);
  assert.equal(c.calls.raw[1].closes, 0);
  assert.equal(c.calls.raw[1].aborts, 1);
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.timers.size, 0);
  await c.context.startMediaAutoImport();
  assert.equal(c.calls.picker, 2, "recovery is an explicit new folder grant");
  await c.step(10000);
  await c.step(11000);
  assert.equal(originalCalls(c).length, 2, "new baselines cannot retry a failed or delivered old ID");
  assert.equal(c.state.mediaAutoImport.snapshot.knownCount, 3);
  await c.step(16000);
  await c.step(17000);
  assert.equal(originalCalls(c).length, 3);
  assert.equal(originalCalls(c)[2].url.includes(encodeURIComponent(third.id)), true);
  assert.equal(c.state.mediaAutoImport.snapshot.completed, 1);
  assert.equal(c.calls.raw[0].closes, 1, "previous delivered file was not touched");
  assert.equal(c.calls.raw[0].aborts, 0);
  assert.deepEqual(finishCalls(c).map(call => call.args[1].outcome), ["completed", "failed", "completed"]);
  assert.equal(c.calls.requests.every(call => !call.request.method || call.request.method === "GET"), true,
    "recovery never sends a shutter or other camera mutation");
});

test("automatic encoded-size envelope refuses original request and can be re-enabled", async () => {
  const c = fixture({ sizes: [64 * 1024 * 1024 + 1, 64 * 1024 * 1024 + 1] });
  await c.stableCandidate();
  await c.step(7000);
  assert.equal(originalCalls(c).length, 0);
  assert.equal(c.calls.raw.length, 0);
  assert.equal(c.state.mediaAutoImport.snapshot.results[0].status, "failed");
  assert.equal(c.state.mediaAutoImportOwner, null);
  assert.equal(c.ui.mediaAutoImportStart.disabled, false);
});
