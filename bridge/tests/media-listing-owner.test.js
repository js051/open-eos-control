"use strict";

// Production request/parser/owner/render paths with controlled HTTP bodies and a
// minimal DOM. This proves client scheduling, not a browser or camera I/O stop.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaListing = require("../open_eos_bridge/static/media-listing.js");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const mediaBatch = require("../open_eos_bridge/static/media-batch.js");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, `Missing production function ${name}`);
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function element(tagName = "div") {
  const attrs = new Map(), listeners = {};
  return { tagName, children: [], dataset: {}, className: "", textContent: "", hidden: false, disabled: false, open: false, value: "",
    style: { removeProperty() {} }, classList: { toggle() {}, remove() {} },
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
    setAttribute(key, value) { attrs.set(key, value); }, removeAttribute(key) { attrs.delete(key); },
    showModal() { this.open = true; }, close() { this.open = false; },
    addEventListener(name, handler) { listeners[name] = handler; },
    click(event) { listeners.click?.(event); }, pause() {}, load() {},
    querySelectorAll(selector) {
      const all = this.children.flatMap(child => [child, ...child.querySelectorAll("*")]);
      if (selector === "*") return all;
      if (selector.startsWith(".")) return all.filter(child => child.className.split(" ").includes(selector.slice(1)));
      return [];
    },
  };
}
function item(id, overrides = {}) { return { id, name: `${id}.JPG`, kind: "jpeg", previewAvailable: true, ...overrides }; }
const OLD = item("synthetic-old");
const NEW = item("synthetic-new");
const settle = async () => { for (let turn = 0; turn < 6; turn++) await new Promise(setImmediate); };
function fixture() {
  const state = { session: { id: "synthetic-session" }, token: "", refreshGeneration: 0, busy: false,
    media: [OLD], mediaLoaded: true, mediaLoadStatus: "COMPLETE", mediaScope: "all", mediaSort: "camera", mediaFilter: "all", mediaPage: 0,
    mediaSelection: mediaBatch.createSelection(), mediaThumbnailUrls: new Map(), mediaThumbnailLoads: new Set(), mediaThumbnailFailures: new Set(), mediaGeneration: 0,
    mediaPreviewGeneration: 0, mediaDetailsGeneration: 0, latestMediaGeneration: 0,
  };
  state.mediaSelection.replaceLoaded(state.media);
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const requests = [], listings = [], feedback = [], timers = [], polls = [];
  const context = vm.createContext({ state, ui, requests, listings, feedback, mediaListing, mediaLibrary, mediaBatch, mediaTransfer,
    AbortController, Blob, Headers, Response, Map, Set, Intl, Date,
    MEDIA_PAGE_SIZE: 72, MAX_MEDIA_THUMBNAIL_CACHE_ITEMS: 144, MAX_MEDIA_THUMBNAIL_BYTES: 4 * 1024 * 1024,
    MAX_MEDIA_PREVIEW_BYTES: 32 * 1024 * 1024,
    ApiError: class ApiError extends Error { constructor(message, fields = {}) { super(message); Object.assign(this, fields); } },
    FEATURES: new Proxy({}, { get: (_, name) => name }),
    featureSupported: () => true, resolvedLanguage: () => "en",
    t: (key, values = {}) => `${key}:${JSON.stringify(values)}`,
    document: { createElement: element }, window: { showDirectoryPicker() {} },
    URL: { createObjectURL: () => "blob:synthetic" },
    renderAvailability() {}, renderLatestMedia() {}, renderMediaDetails() {},
    renderMediaPreviewNavigation() {}, formatMediaSize: () => "", formatMediaDimensions: () => "",
    releaseObjectUrl() {}, captureError: error => { feedback.push(error.message); return error; },
    showToast() {}, sleep: delay => { timers.push(delay); return new Promise(() => {}); },
    setOperationState() {}, renderSession() {}, syncLiveMagnificationFromCapabilities() {}, clampFps: value => value,
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (/\/media(?:\?|$)/.test(url) && !/\/media\//.test(url)) {
        let controller;
        const body = new ReadableStream({ start(value) { controller = value; } });
        const gate = { controller, signal: options.signal, seq: 0, revision: 0, cancelled: false,
          binding: { requestId: `synthetic-request-${listings.length}`, sessionId: state.session.id, scope: url.includes("limit") ? "recent" : "full" } };
        gate.send = record => controller.enqueue(new TextEncoder().encode(JSON.stringify({ ...gate.binding, seq: gate.seq++, ...record }) + "\n"));
        gate.start = () => gate.send({ type: "start", limit: url.includes("limit") ? 61 : null });
        gate.snapshot = items => {
          gate.revision++;
          for (let offset = 0; offset < Math.max(1, items.length); offset += 128) gate.send({ type: "snapshot", revision: gate.revision,
            total: items.length, offset, items: items.slice(offset, offset + 128), last: offset + 128 >= items.length });
          gate.count = items.length;
        };
        gate.complete = () => { gate.send({ type: "complete", revision: gate.revision, count: gate.count, scopeComplete: true }); controller.close(); };
        // Fetch deliberately ignores abort; parser reader cancellation must still
        // release ownership immediately, and old responses must remain harmless.
        listings.push(gate);
        return new Response(body, { headers: { "Content-Type": mediaListing.MEDIA_TYPE } });
      }
      if (url.endsWith("/events")) return new Promise((resolve, reject) => {
        polls.push({ resolve, reject });
        options.signal.addEventListener("abort", () => reject(mediaTransfer.cancellationError()), { once: true });
      });
      if (url.endsWith("/thumbnail") || url.endsWith("/preview")) return new Response(new Blob(["synthetic"], { type: "image/jpeg" }));
      if (/\/(info|status|capabilities)$/.test(url)) return Response.json(url.includes("/media/") ? OLD : {});
      throw new Error(`Unexpected request ${url}`);
    },
  });
  const names = ["api", "refreshMedia", "refreshMediaWhenCurrent", "cancelMediaListing", "renderMedia", "renderMediaSummary", "renderMediaSelection",
    "renderMediaDateFilter", "renderMediaRatingFilter", "renderMediaFolderFilter", "mediaFolderChoiceLabel", "mediaDisplayTimeZone",
    "displayedMedia", "mediaDateGroup", "formatMediaTime", "mediaIsVideo", "renderMediaThumbnail", "observeMediaThumbnail", "loadMediaThumbnail",
    "updateVisibleMediaThumbnail", "clearMediaThumbnails", "mediaTransferActive", "cameraInteractionBusy", "beginCameraInteraction",
    "openMediaDetails", "clearMediaDetails", "mediaMetadataSupported", "replaceMediaItem", "startEventLoop", "cancelEventLoop", "refreshSession", "adoptCameraStatus"];
  vm.runInContext("let mediaThumbnailObserver = null;\n" + names.map(extract).join("\n"), context);
  context.closeMediaPreview = () => { ui.mediaPreviewDialog.open = false; };
  state.eventGeneration = 0;
  const scopeStart = source.indexOf('    ui.mediaScopeControl.addEventListener("click"');
  const scopeEnd = source.indexOf('    ui.mediaFilterControl.addEventListener', scopeStart);
  vm.runInContext(source.slice(scopeStart, scopeEnd), context);
  return Object.assign(context, { timers, polls });
}
function mediaReads(c) { return c.requests.filter(({ url }) => /\/(thumbnail|preview|info)$/.test(url) || /\/media\/[^/]+$/.test(url)); }

test("production partial render preserves selections/filters and performs zero thumbnail/info/original reads", async () => {
  const c = fixture();
  c.state.mediaSelection.setSelected(OLD.id, true);
  c.state.mediaDateRange = { start: "2026-10-01", end: "2026-10-31" };
  c.state.mediaRatingFilter = "unknown";
  c.state.mediaFolderFilter = { kind: "unknown" };
  c.state.mediaSort = "name";
  const promise = c.refreshMedia(), gate = c.listings[0];
  assert.equal(c.requests[0].options.headers.get("Accept"), mediaListing.MEDIA_TYPE);
  assert.equal(c.requests[0].options.redirect, "error", "Redirects cannot replay a listing GET");
  assert.equal(c.ui.mediaListingCancel.hidden, false);
  assert.match(c.ui.mediaSummary.textContent, /"count":0/);
  gate.start(); gate.snapshot([item("synthetic-partial", { captureTime: "2026-10-13" })]);
  await settle();
  assert.equal(c.state.mediaLoadStatus, "LOADING");
  assert.equal(c.state.media.length, 1);
  assert.equal(c.state.mediaSelection.snapshot()[0].loaded, false);
  assert.equal(c.state.mediaSelection.has(OLD.id), true);
  assert.equal(c.state.mediaSort, "name");
  assert.equal(c.state.mediaRatingFilter, "unknown");
  assert.equal(c.state.mediaFolderFilter.kind, "unknown");
  assert.equal(c.state.mediaDateRange.start, "2026-10-01");
  assert.equal(mediaReads(c).length, 0);
  await c.loadMediaThumbnail(c.state.media[0]);
  c.renderMedia();
  assert.equal(mediaReads(c).length, 0, "Direct observer demand is also deferred");
  gate.complete();
  assert.equal(await promise, true);
  await settle();
  assert.equal(c.state.mediaLoadStatus, "COMPLETE");
  assert.equal(c.ui.mediaListingCancel.hidden, true);
  assert.equal(mediaReads(c).length, 1);
});

test("full progress keeps 1101 entries while completion resumes only the current 72-card thumbnail page", async () => {
  const c = fixture(), promise = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot(Array.from({ length: 1101 }, (_, n) => item(`synthetic-${n}`)));
  await settle();
  assert.equal(c.state.media.length, 1101);
  assert.equal(c.ui.mediaList.children.filter(node => node.className === "media-card").length, 72);
  assert.equal(mediaReads(c).length, 0);
  c.state.mediaPage = 1; c.renderMedia();
  assert.equal(mediaReads(c).length, 0);
  gate.complete(); await promise; await settle();
  assert.equal(mediaReads(c).length, 72);
  assert.ok(mediaReads(c).every(row => /synthetic-(?:7[2-9]|[89][0-9]|1[0-3][0-9]|14[0-3])\/thumbnail$/.test(row.url)));
});

test("cancel twice resolves promptly, keeps partial, and never schedules thumbnails or another traversal", async () => {
  const c = fixture(), promise = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot([NEW]); await settle();
  c.cancelMediaListing(); c.cancelMediaListing();
  assert.equal(await promise, false);
  assert.equal(promise.mediaOutcome, "cancelled");
  assert.equal(gate.signal.aborted, true);
  assert.equal(c.state.mediaLoadStatus, "CANCELLED");
  assert.equal(c.state.media[0].id, NEW.id);
  assert.equal(c.ui.mediaListingCancel.hidden, true);
  c.renderMedia(); await settle();
  assert.equal(c.listings.length, 1);
  assert.equal(mediaReads(c).length, 0);
});

test("scope click aborts the old owner and only the new scope may complete", async () => {
  const c = fixture(), old = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot([OLD]); await settle();
  c.ui.mediaScopeControl.click({ target: { closest: () => ({ dataset: { mediaScope: "recent" } }) } });
  const current = c.state.mediaRefreshPromise, next = c.listings[1];
  assert.equal(await old, false);
  assert.equal(gate.signal.aborted, true);
  next.start(); next.snapshot(Array.from({ length: 61 }, (_, n) => item(`synthetic-new-${n}`)));
  next.complete();
  assert.equal(await current, true);
  assert.equal(c.state.media.length, 60);
  assert.equal(c.state.mediaHasMore, true);
  assert.equal(c.state.mediaScope, "recent");
});

for (const reuseId of [true, false]) test(`replacement ${reuseId ? "same" : "different"} session ID cannot receive old partial or finalizer`, async () => {
  const c = fixture(), old = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot([OLD]); await settle();
  c.state.session = { id: reuseId ? "synthetic-session" : "synthetic-replacement" };
  const current = c.refreshMedia(), next = c.listings[1];
  next.start(); next.snapshot([NEW]); await settle();
  assert.equal(await old, false);
  assert.equal(c.state.mediaListingOwner.promise, current);
  assert.equal(c.state.media[0].id, NEW.id);
  next.complete(); assert.equal(await current, true);
});

test("terminal error after partial keeps partial and never falls back or starts thumbnails", async () => {
  const c = fixture(), promise = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot([NEW]);
  gate.send({ type: "error", code: "SYNTHETIC_FAILURE", message: "Synthetic traversal failed." });
  gate.controller.close();
  assert.equal(await promise, false);
  assert.equal(promise.mediaOutcome, "failed");
  assert.equal(c.state.media[0].id, NEW.id);
  assert.equal(c.state.mediaLoadStatus, "FAILED");
  assert.equal(c.ui.mediaListingCancel.hidden, true);
  assert.equal(c.listings.length, 1);
  assert.equal(mediaReads(c).length, 0);
});

test("explicit details read retires listing before reading metadata", async () => {
  const c = fixture(), promise = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot([OLD]); await settle();
  await c.openMediaDetails(OLD);
  assert.equal(await promise, false);
  assert.equal(gate.signal.aborted, true);
  assert.equal(c.state.mediaLoadStatus, "CANCELLED");
  assert.equal(mediaReads(c).filter(row => row.url.endsWith("/info")).length, 1);
  assert.equal(mediaReads(c).filter(row => row.url.endsWith("/thumbnail")).length, 0);
});

test("content-event cancellation consumes only its old hint and permits a genuinely new event", async () => {
  const c = fixture();
  c.startEventLoop();
  c.polls[0].resolve(Response.json({ changedKeys: ["contents"] })); await settle();
  const promise = c.state.mediaRefreshPromise, gate = c.listings[0];
  gate.start(); gate.snapshot([NEW]); await settle();
  c.cancelMediaListing(); await promise; await settle();
  assert.equal(c.state.mediaLoadStatus, "CANCELLED");
  assert.equal(c.listings.length, 1);
  assert.deepEqual(c.timers, []);
  assert.equal(c.polls.length, 2);
  c.polls[1].resolve(Response.json({ changedKeys: [] })); await settle();
  assert.equal(c.listings.length, 1);
  c.polls[2].resolve(Response.json({ changedKeys: ["contents"] })); await settle();
  assert.equal(c.listings.length, 2);
  c.cancelMediaListing(); c.cancelEventLoop(); await settle();
});

test("quiet state refresh cannot fan out into camera info while listing owns the read", async () => {
  const c = fixture(), promise = c.refreshMedia();
  assert.equal(await c.refreshSession({ quiet: true }), false);
  assert.equal(mediaReads(c).length, 0);
  c.cancelMediaListing(); await promise;
});

test("legacy production API uses one request and the same Accept with no probe", async () => {
  const c = fixture(); let calls = 0;
  c.featureSupported = feature => feature === "MEDIA_BROWSER";
  c.fetch = async (url, options) => { calls++; assert.equal(options.headers.get("Accept"), mediaListing.MEDIA_TYPE); return Response.json({ items: [NEW] }); };
  assert.equal(await c.refreshMedia(), true);
  assert.equal(calls, 1);
  assert.equal(c.state.media[0].id, NEW.id);
  assert.equal(c.state.mediaLoadStatus, "COMPLETE");
});

test("an event waiting behind a manual listing cannot replay its old contents hint after Cancel", async () => {
  const c = fixture(), promise = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot([NEW]); await settle();
  c.startEventLoop();
  c.polls[0].resolve(Response.json({ changedKeys: ["contents"] })); await settle();
  assert.equal(mediaReads(c).length, 0, "Event state/info requests wait behind the listing");
  c.cancelMediaListing(); await promise; await settle();
  assert.equal(c.listings.length, 1);
  assert.equal(c.polls.length, 2);
  assert.equal(c.state.mediaLoadStatus, "CANCELLED");
  assert.deepEqual(c.timers, []);
  c.cancelEventLoop(); await settle();
});

test("retired callback and completion cannot overwrite Cancel or a newer owner's partial", async () => {
  const c = fixture(), calls = [];
  c.api = (url, options) => new Promise(resolve => calls.push({ options, resolve }));
  const old = c.refreshMedia();
  calls[0].options.listing.onSnapshot([OLD]);
  c.cancelMediaListing();
  assert.equal(await old, false);
  calls[0].options.listing.onSnapshot([NEW]);
  assert.equal(c.state.media[0].id, OLD.id);
  assert.equal(c.state.mediaLoadStatus, "CANCELLED");
  const current = c.refreshMedia();
  calls[1].options.listing.onSnapshot([NEW]);
  calls[0].resolve({ items: [OLD] }); await settle();
  assert.equal(c.state.mediaListingOwner.promise, current);
  assert.equal(c.state.mediaLoadStatus, "LOADING");
  assert.equal(c.state.media[0].id, NEW.id);
  c.cancelMediaListing(); await current;
  calls[1].resolve({ items: [NEW] }); await settle();
});

test("cancel wins after a valid terminal arrives but before EOF", async () => {
  const c = fixture(), promise = c.refreshMedia(), gate = c.listings[0];
  gate.start(); gate.snapshot([NEW]);
  gate.send({ type: "complete", revision: gate.revision, count: gate.count, scopeComplete: true });
  await settle();
  assert.equal(c.state.mediaLoadStatus, "LOADING");
  c.cancelMediaListing(); assert.equal(await promise, false);
  assert.equal(c.state.mediaLoadStatus, "CANCELLED");
  assert.equal(mediaReads(c).length, 0);
});

test("cancel before response headers releases the owner and ignores the late legacy body", async () => {
  const c = fixture(); let reply, signal;
  c.fetch = (url, options) => { signal = options.signal; return new Promise(resolve => { reply = resolve; }); };
  const promise = c.refreshMedia();
  c.cancelMediaListing();
  assert.equal(await promise, false);
  assert.equal(signal.aborted, true);
  reply(Response.json({ items: [NEW] })); await settle();
  assert.equal(c.state.mediaLoadStatus, "CANCELLED");
  assert.equal(c.state.media[0].id, OLD.id);
  assert.equal(c.feedback.length, 0);
});

test("an HTTP rejection or malformed initial response fails once without inventing an empty complete library", async () => {
  for (const response of [Response.json({ error: { code: "BUSY", message: "Synthetic busy." } }, { status: 409 }),
    Response.json({ items: null }), new Response("<html>invalid</html>", { headers: { "Content-Type": "text/html" } })]) {
    const c = fixture(); let calls = 0;
    c.state.mediaLoaded = false;
    c.fetch = async () => { calls++; return response; };
    assert.equal(await c.refreshMedia(), false);
    assert.equal(calls, 1);
    assert.equal(c.state.mediaLoadStatus, "FAILED");
    assert.equal(c.state.media[0].id, OLD.id);
    assert.equal(c.state.mediaLoaded, true, "Re-entering the view must not implicitly replay a failed traversal");
  }
});

test("listing redirect refusal fails once and does not retry the original or redirected URL", async () => {
  const c = fixture(); let calls = 0;
  c.fetch = async (url, options) => {
    calls++; assert.equal(options.redirect, "error");
    throw new TypeError("Synthetic redirect refused.");
  };
  assert.equal(await c.refreshMedia(), false);
  assert.equal(calls, 1);
  assert.equal(c.state.mediaLoadStatus, "FAILED");
});

test("production API bounds an oversized HTTP error body before generic JSON parsing", async () => {
  const c = fixture(); let cancelled = false, calls = 0;
  c.fetch = async () => { calls++; return new Response(new ReadableStream({
    pull(controller) { controller.enqueue(new TextEncoder().encode("x".repeat(40000))); },
    cancel() { cancelled = true; },
  }), { status: 502, headers: { "Content-Type": "application/json" } }); };
  assert.equal(await c.refreshMedia(), false);
  assert.equal(calls, 1);
  assert.equal(cancelled, true);
  assert.equal(c.state.mediaLoadStatus, "FAILED");
});
