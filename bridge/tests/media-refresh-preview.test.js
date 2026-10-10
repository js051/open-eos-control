"use strict";

// Exercise the production event, listing and preview functions with deferred reads.
// This is a deterministic client regression, not browser or physical-camera evidence.
// Abort assertions cover the browser-to-Bridge request, not synchronous camera I/O.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");

function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, `Missing production function ${name}`);
  const end = source.indexOf("\n  }", match.index);
  assert.notEqual(end, -1, `Missing end of production function ${name}`);
  return source.slice(match.index, end + 4);
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

async function eventually(predicate, description) {
  for (let turn = 0; turn < 40; turn += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail(`Did not reach ${description}`);
}

const item = (id, captureTime) => ({ id, name: id, captureTime, kind: "jpeg", previewAvailable: true });
const OLD = item("SYNTHETIC_OLD.JPG", "2026-10-01");
const LATEST = item("SYNTHETIC_LATEST.JPG", "2026-10-02");
const REPLACEMENT = item("SYNTHETIC_RECONNECTED.JPG", "2026-10-03");
const VIDEO = { ...item("SYNTHETIC_VIDEO.MP4", "2026-10-02"), kind: "video" };
const imageBlob = () => new Blob(["synthetic preview"], { type: "image/jpeg" });

function fixture(t, { dated = false } = {}) {
  const state = {
    session: { id: "synthetic-session-a" }, busy: false, refreshGeneration: 0,
    eventGeneration: 0, eventController: null, mediaRefreshPromise: null,
    media: [OLD], mediaLoaded: true, mediaLoadStatus: "COMPLETE", mediaHasMore: false,
    mediaScope: "recent", mediaFilter: "all", mediaSort: "camera", mediaPage: 0,
    mediaDateRange: dated ? { start: "2026-10-01", end: "2026-10-01" } : null,
    mediaDateDialogSession: null, mediaPreviewGeneration: 0, mediaPreviewItem: null,
    mediaPreviewUrl: null, mediaPreviewTicketUrl: null, mediaPreviewController: null,
    mediaDownloadPreparing: false, mediaDownload: null, mediaUpload: null,
    latestMediaItem: LATEST, latestMediaGeneration: 0, latestMediaRefreshPromise: null,
    latestMediaController: null, latestMediaThumbnailLoading: false,
    latestMediaReviewAttempt: null, latestMediaReviewStatus: "READY",
    latestMediaReviewReadFailed: false, operatorConfirmedFeatures: new Set(),
  };
  const ui = new Proxy({}, { get(target, key) {
    if (!target[key]) target[key] = {
      hidden: false, disabled: false, open: false, textContent: "", value: "", dataset: {},
      style: { removeProperty() {} }, classList: { remove() {} },
      showModal() { this.open = true; }, close() { this.open = false; },
      setAttribute(name, value) { this[name] = value; },
      removeAttribute(name) { delete this[name]; }, pause() {}, load() {},
    };
    return target[key];
  } });
  const requests = [];
  const listings = [];
  const previews = [];
  const playbacks = [];
  const originals = [];
  const ticketDeletes = [];
  const savedBlobs = [];
  const events = [];
  const feedback = [];
  const renders = [];
  const releasedUrls = [];
  const delays = [];
  const decode = deferred();
  let decodeCalls = 0;
  let objectUrls = 0;
  ui.mediaPreviewImage.decode = () => { decodeCalls += 1; return decode.promise; };
  const s = vm.createContext({
    state, ui, requests, listings, previews, playbacks, originals, ticketDeletes, savedBlobs,
    events, feedback, renders, releasedUrls, delays,
    AbortController, Blob, Set, Map, Intl, Date, mediaLibrary, mediaTransfer,
    URL: { createObjectURL: () => `blob:synthetic-preview-${++objectUrls}` },
    FEATURES: Object.fromEntries(["EVENT_POLLING", "MEDIA_BROWSER", "MEDIA_PREVIEW", "MEDIA_DOWNLOAD",
      "MEDIA_THUMBNAIL"].map((name) => [name, name])),
    featureSupported: (feature) => feature !== "MEDIA_THUMBNAIL",
    MAX_MEDIA_PREVIEW_BYTES: 32 * 1024 * 1024,
    LATEST_MEDIA_LIMIT: 8, LATEST_MEDIA_RETRY_DELAYS_MILLIS: [250, 750, 1500],
    mediaIsVideo: mediaLibrary.isVideo, resolvedLanguage: () => "en",
    t: (key, values = {}) => `${key}:${JSON.stringify(values)}`,
    formatMediaDimensions: () => "", formatMediaSize: () => "",
    mediaManagementSupported: () => true,
    releaseObjectUrl: (url) => { if (url) releasedUrls.push(url); },
    captureError: (error) => { feedback.push(error.message); return error; },
    showToast: (message) => feedback.push(message),
    sleep: async (delay) => { delays.push(delay); },
    clampFps: (value) => value,
    reviewResponse: () => ({ items: [LATEST, OLD] }),
    chooseMediaWritable: async () => ({ writable: null, cancelled: false }),
    saveMediaBlob: (blob, name) => savedBlobs.push({ blob, name }),
    fetch: async (url, options) => {
      assert.equal(options.method, "DELETE", "Only playback-ticket cleanup may use fetch");
      ticketDeletes.push({ url, method: options.method, cache: options.cache, keepalive: options.keepalive });
      return { ok: true };
    },
    api: (url, options = {}) => {
      requests.push({ url, method: options.method || "GET" });
      if (url.endsWith("/playback") && options.method === "POST") {
        const gate = deferred(); playbacks.push({ ...gate, url, signal: options.signal }); return gate.promise;
      }
      if (options.method && options.method !== "GET") throw new Error(`Unexpected write: ${url}`);
      if (url.endsWith("/events")) {
        const gate = deferred();
        events.push(gate);
        options.signal.addEventListener("abort", () => gate.reject(mediaTransfer.cancellationError()), { once: true });
        return gate.promise;
      }
      if (url.endsWith("/media?limit=61") || url.endsWith("/media")) {
        const gate = deferred(); listings.push({ ...gate, url }); return gate.promise;
      }
      if (url.endsWith("/preview")) {
        // Deliberately ignore abort until the test releases the read. This proves
        // timely cancellation and guards stale completion even when transport races.
        const gate = deferred(); previews.push({ ...gate, url, signal: options.signal }); return gate.promise;
      }
      if (url.endsWith("/media?limit=8")) return Promise.resolve().then(() => s.reviewResponse());
      if (/\/(?:info|status|capabilities)$/.test(url)) return Promise.resolve({});
      if (/\/media\/[^/]+$/.test(url)) {
        const gate = deferred(); originals.push({ ...gate, url, signal: options.signal }); return gate.promise;
      }
      throw new Error(`Unexpected request: ${url}`);
    },
  });
  for (const name of ["renderAvailability", "renderLatestMedia", "renderSession", "setOperationState",
    "syncLiveMagnificationFromCapabilities", "clearMediaThumbnails", "stopLiveLoop", "stopLocalVideo",
    "renderMediaTransfer", "scheduleMediaTransferRender", "cancelMediaUpload",
    "clearScheduledMediaTransferRender", "closeMediaDetails",
    "clearBulbTimer", "renderHealth"]) s[name] = () => {};
  const names = ["startEventLoop", "cancelEventLoop", "refreshSession", "refreshMedia", "refreshMediaWhenCurrent",
    "mediaTransferActive", "cameraInteractionBusy", "openMediaPreview", "closeMediaPreview", "clearMediaPreview",
    "resetMediaPreviewTransform", "renderMediaPreviewNavigation", "previewableMedia", "displayedMedia",
    "renderMediaSummary", "renderMediaDateFilter", "mediaDisplayTimeZone", "resetSession", "cancelLatestMediaRefresh",
    "retryLatestMedia", "refreshLatestMedia", "latestMediaFrom", "publishLatestMedia",
    "downloadMedia", "cancelMediaDownload"];
  vm.runInContext(names.map(extract).join("\n"), s, { timeout: 1000 });
  s.renderMedia = () => {
    renders.push(Array.from(state.media, (entry) => entry.id));
    s.renderMediaSummary();
  };
  s.decode = decode;
  s.decodeCalls = () => decodeCalls;
  t.after(() => s.cancelEventLoop());
  return s;
}

async function openReview(s, phase) {
  const pending = s.openMediaPreview(LATEST);
  assert.equal(s.previews.length, 1);
  if (phase !== "request pending") {
    s.previews[0].resolve(imageBlob());
    await eventually(() => s.decodeCalls() === 1, "image decode");
    if (phase === "ready") { s.decode.resolve(); await pending; }
  }
  return { pending, generation: s.state.mediaPreviewGeneration, url: s.state.mediaPreviewUrl };
}

async function contentEvent(s) {
  s.startEventLoop();
  assert.equal(s.events.length, 1);
  s.events[0].resolve({ changedKeys: ["contents"] });
  await eventually(() => s.listings.length === 1, "background library listing");
  const pending = s.state.mediaRefreshPromise;
  assert.ok(pending);
  s.cancelEventLoop();
  return { pending };
}

function assertReadOnly(s) {
  assert.equal(s.requests.every(({ method }) => method === "GET"), true);
  assert.equal(s.requests.some(({ url }) => /\/(?:capture|shutter|recording)(?:\/|$)/.test(url)), false);
}

function previewSnapshot(s) {
  return {
    open: s.ui.mediaPreviewDialog.open, item: s.state.mediaPreviewItem,
    generation: s.state.mediaPreviewGeneration, url: s.state.mediaPreviewUrl,
    ticketUrl: s.state.mediaPreviewTicketUrl, imageSource: s.ui.mediaPreviewImage.src,
    imageHidden: s.ui.mediaPreviewImage.hidden, videoSource: s.ui.mediaPreviewVideo.src,
    videoHidden: s.ui.mediaPreviewVideo.hidden, loading: s.ui.mediaPreviewLoading.hidden,
    unavailable: s.ui.mediaPreviewUnavailable.hidden, title: s.ui.mediaPreviewTitle.textContent,
    feedback: Array.from(s.feedback), releasedUrls: Array.from(s.releasedUrls),
  };
}

function retirePreview(s, action, replacement = OLD) {
  if (action === "close") { s.closeMediaPreview(); return {}; }
  if (action === "manual refresh") return { refresh: s.refreshMedia() };
  if (action.includes("reconnect")) {
    const sessionId = s.state.session.id;
    s.resetSession();
    s.state.session = { id: action.startsWith("reused") ? sessionId : "synthetic-session-b" };
  }
  const next = action.startsWith("reused") ? { ...LATEST } : replacement;
  return { replacement: s.openMediaPreview(next) };
}

for (const dated of [false, true]) {
  for (const phase of ["request pending", "decode pending", "ready"]) {
    test(`content event preserves ${phase} explicit review${dated ? " outside the date range" : ""}`,
      { timeout: 2000 }, async (t) => {
        const s = fixture(t, { dated });
        const review = await openReview(s, phase);
        const refresh = await contentEvent(s);
        const during = { open: s.ui.mediaPreviewDialog.open, item: s.state.mediaPreviewItem,
          generation: s.state.mediaPreviewGeneration, url: s.state.mediaPreviewUrl };
        s.listings[0].resolve({ items: [LATEST, OLD] });
        assert.equal(await refresh.pending, true);
        s.previews[0].resolve(imageBlob()); s.decode.resolve(); await review.pending;
        assert.equal(during.open, true, "Background listing must leave the requested preview open");
        assert.equal(during.item, LATEST);
        assert.equal(during.generation, review.generation, "Background listing must not cancel image ownership");
        assert.equal(during.url, review.url);
        assert.equal(s.ui.mediaPreviewDialog.open, true);
        assert.equal(s.state.mediaPreviewItem, LATEST);
        assert.equal(s.state.mediaPreviewGeneration, review.generation);
        assert.equal(s.ui.mediaPreviewImage.hidden, false);
        assert.equal(s.ui.mediaPreviewLoading.hidden, true);
        assert.equal(s.ui.mediaPreviewImage.src, s.state.mediaPreviewUrl);
        assert.deepEqual(s.releasedUrls, []);
        assert.equal(s.state.mediaLoadStatus, "COMPLETE");
        assert.deepEqual(Array.from(s.state.media, (entry) => entry.id), [LATEST.id, OLD.id]);
        assert.equal(s.previews.length, 1, "Refreshing the library cannot issue another preview read");
        assert.notEqual(s.previews[0].signal?.aborted, true,
          "A background listing must not abort the independently opened image read");
        if (dated) {
          assert.deepEqual(Array.from(s.displayedMedia(), (entry) => entry.id), [OLD.id]);
          assert.deepEqual(Array.from(s.previewableMedia(), (entry) => entry.id), [LATEST.id]);
          assert.match(s.ui.mediaPreviewMeta.textContent, /"position":1,"total":1/);
          assert.equal(s.ui.mediaPreviewPrevious.disabled, true);
          assert.equal(s.ui.mediaPreviewNext.disabled, true);
        }
        assertReadOnly(s);
      });
  }
}

for (const phase of ["request pending", "ready"]) {
  test(`explicit library refresh still closes ${phase} review`, { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const review = await openReview(s, phase);
    const refresh = s.refreshMedia();
    assert.equal(s.ui.mediaPreviewDialog.open, false);
    assert.equal(s.state.mediaPreviewItem, null);
    assert.ok(s.state.mediaPreviewGeneration > review.generation);
    s.listings[0].resolve({ items: [LATEST, OLD] });
    s.previews[0].resolve(imageBlob()); s.decode.resolve();
    assert.equal(await refresh, true); await review.pending;
    assert.equal(s.ui.mediaPreviewDialog.open, false, "Late image completion cannot reopen the closed preview");
    assert.equal(s.state.mediaPreviewUrl, null);
    assert.equal(s.ui.mediaPreviewImage.hidden, true);
    assert.equal(s.listings.length, 1);
    assertReadOnly(s);
  });
}

test("manual refresh coalesced with an in-flight background listing still closes the review",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const review = await openReview(s, "ready");
    const background = await contentEvent(s);
    const openBeforeManual = s.ui.mediaPreviewDialog.open;
    const manual = s.refreshMedia();
    const closedByManual = !s.ui.mediaPreviewDialog.open && s.state.mediaPreviewItem === null;
    s.listings[0].resolve({ items: [LATEST, OLD] });
    await Promise.all([background.pending, manual, review.pending]);
    assert.equal(openBeforeManual, true, "Background refresh preserves preview until the explicit request");
    assert.equal(closedByManual, true, "A coalesced explicit request must retain its close action");
    assert.equal(s.listings.length, 1, "Coalescing must not duplicate the library request");
    assert.deepEqual(s.releasedUrls, [review.url]);
    assertReadOnly(s);
  });

test("background refresh coalesced with a manual listing cannot reopen its closed preview",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    await openReview(s, "ready");
    const manual = s.refreshMedia();
    const background = s.refreshMedia({ preservePreview: true });
    assert.equal(s.ui.mediaPreviewDialog.open, false);
    s.listings[0].resolve({ items: [LATEST, OLD] });
    await Promise.all([manual, background]);
    assert.equal(s.listings.length, 1);
    assert.equal(s.state.mediaPreviewItem, null);
    assert.equal(s.ui.mediaPreviewDialog.open, false);
    assertReadOnly(s);
  });

test("preserved listing refresh updates open-preview count and arrows without replacing the image",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const review = await openReview(s, "ready");
    assert.match(s.ui.mediaPreviewMeta.textContent, /"position":1,"total":2/);
    assert.equal(s.ui.mediaPreviewNext.disabled, false);
    const imageSource = s.ui.mediaPreviewImage.src;
    const background = await contentEvent(s);
    s.listings[0].resolve({ items: [LATEST] });
    assert.equal(await background.pending, true);
    assert.equal(s.ui.mediaPreviewDialog.open, true);
    assert.equal(s.state.mediaPreviewItem, LATEST);
    assert.equal(s.state.mediaPreviewGeneration, review.generation);
    assert.equal(s.state.mediaPreviewUrl, review.url);
    assert.equal(s.ui.mediaPreviewImage.src, imageSource);
    assert.equal(s.ui.mediaPreviewImage.hidden, false);
    assert.equal(s.previews.length, 1);
    assert.deepEqual(s.releasedUrls, []);
    assert.match(s.ui.mediaPreviewMeta.textContent, /"position":1,"total":1/);
    assert.equal(s.ui.mediaPreviewPrevious.disabled, true);
    assert.equal(s.ui.mediaPreviewNext.disabled, true);
    assertReadOnly(s);
  });

for (const reuseId of [false, true]) {
  for (const outcome of ["resolve", "reject"]) {
    test(`late background listing ${outcome} cannot publish into a ${reuseId ? "reused-ID" : "different-ID"} reconnect`,
      { timeout: 2000 }, async (t) => {
        const s = fixture(t);
        const background = await contentEvent(s);
        s.resetSession();
        s.state.session = { id: reuseId ? "synthetic-session-a" : "synthetic-session-b" };
        s.state.media = [REPLACEMENT]; s.state.mediaLoaded = true; s.state.mediaLoadStatus = "COMPLETE";
        const renders = s.renders.length;
        const feedback = s.feedback.length;
        if (outcome === "resolve") s.listings[0].resolve({ items: [OLD] });
        else s.listings[0].reject(new Error("Synthetic retired listing failed"));
        let settled = false;
        background.pending.then(() => { settled = true; });
        await eventually(() => settled || s.listings.length === 2, "retired listing settlement or current retry");
        assert.deepEqual(Array.from(s.state.media, (entry) => entry.id), [REPLACEMENT.id]);
        assert.equal(s.renders.length, renders, "Old response cannot render stale media");
        assert.equal(s.feedback.length, feedback, "Old failure cannot overwrite feedback");
        if (s.listings.length === 2) {
          assert.match(s.listings[1].url, new RegExp(`/session/${s.state.session.id}/media`));
          s.listings[1].resolve({ items: [REPLACEMENT] });
        }
        await background.pending;
        assert.deepEqual(Array.from(s.state.media, (entry) => entry.id), [REPLACEMENT.id]);
        assertReadOnly(s);
      });
  }
}

test("disconnect discards the pending background listing without restarting a camera read",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const background = await contentEvent(s);
    s.resetSession();
    s.listings[0].resolve({ items: [OLD] });
    assert.equal(await background.pending, false);
    assert.equal(s.state.session, null);
    assert.equal(s.state.media.length, 0);
    assert.equal(s.state.mediaLoadStatus, "NOT_LOADED");
    assert.equal(s.listings.length, 1);
    assertReadOnly(s);
  });

test("background listing failure preserves review; explicit retry closes it and remains read-only",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t, { dated: true });
    const review = await openReview(s, "ready");
    const background = await contentEvent(s);
    s.listings[0].reject(new Error("Synthetic listing unavailable"));
    assert.equal(await background.pending, false);
    const afterFailure = { open: s.ui.mediaPreviewDialog.open, generation: s.state.mediaPreviewGeneration,
      item: s.state.mediaPreviewItem, status: s.state.mediaLoadStatus };
    await eventually(() => s.state.mediaRefreshPromise === null, "refresh owner cleanup");
    const retry = s.refreshMedia();
    const closedOnRetry = !s.ui.mediaPreviewDialog.open;
    s.listings[1].resolve({ items: [LATEST, OLD] });
    assert.equal(await retry, true);
    assert.equal(afterFailure.open, true);
    assert.equal(afterFailure.generation, review.generation);
    assert.equal(afterFailure.item, LATEST);
    assert.equal(afterFailure.status, "FAILED");
    assert.equal(closedOnRetry, true);
    assert.equal(s.previews.length, 1);
    assertReadOnly(s);
  });

test("latest-review read failure and retry after a content event never resend a capture command",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const background = await contentEvent(s);
    s.listings[0].resolve({ items: [OLD] }); await background.pending;
    s.state.latestMediaReviewAttempt = { sessionId: s.state.session.id, knownIds: new Set([OLD.id, LATEST.id]) };
    s.state.latestMediaReviewStatus = "NOT_READY";
    s.reviewResponse = () => { throw new Error("Synthetic latest listing unavailable"); };
    assert.equal(await s.retryLatestMedia(), false);
    assert.equal(s.state.latestMediaReviewStatus, "NOT_READY");
    assert.equal(s.state.latestMediaReviewReadFailed, true);
    assert.deepEqual(s.delays, [250, 750, 1500]);
    assert.equal(s.requests.filter(({ url }) => url.endsWith("/media?limit=8")).length, 4);
    s.reviewResponse = () => ({ items: [REPLACEMENT, LATEST, OLD] });
    assert.equal(await s.retryLatestMedia(), true);
    assert.equal(s.state.latestMediaReviewStatus, "READY");
    assert.equal(s.state.latestMediaItem, REPLACEMENT);
    assert.equal(s.requests.filter(({ url }) => url.endsWith("/media?limit=8")).length, 5);
    assertReadOnly(s);
  });

for (const action of ["close", "manual refresh", "replacement", "reused-item replacement",
  "reset/reconnect", "reused-session-and-item reconnect"]) {
  for (const outcome of ["resolve", "reject"]) {
    test(`${action} aborts the image request before its late ${outcome} can settle`,
      { timeout: 2000 }, async (t) => {
        const s = fixture(t);
        const pending = s.openMediaPreview(LATEST);
        const retired = s.previews[0];
        assert.ok(retired.signal, "Image preview GET must receive its own AbortSignal");
        assert.equal(retired.signal.aborted, false);
        const next = retirePreview(s, action);
        // Check before releasing either deferred request, never after completion.
        assert.equal(retired.signal.aborted, true, "Retirement must immediately abort the pending image read");
        const current = s.previews[1];
        if (next.replacement) {
          assert.ok(current.signal);
          assert.notEqual(current.signal, retired.signal, "Replacement owns a separate request signal");
          assert.equal(current.signal.aborted, false);
        }
        const afterRetirement = previewSnapshot(s);
        if (outcome === "resolve") retired.resolve(imageBlob());
        else retired.reject(new Error("Synthetic retired preview transport failure"));
        await pending;
        assert.deepEqual(previewSnapshot(s), afterRetirement,
          "A retired image must not publish a URL, failure, or loading-state change");
        assert.equal(s.decodeCalls(), 0, "A retired image must not start decoding");
        if (next.replacement) {
          assert.equal(current.signal.aborted, false, "The old finalizer must not cancel the new request");
          s.closeMediaPreview();
          assert.equal(current.signal.aborted, true,
            "The old finalizer must leave the replacement controller available to Close");
          current.resolve(imageBlob());
          await next.replacement;
        }
        if (next.refresh) {
          s.listings[0].resolve({ items: [LATEST, OLD] });
          assert.equal(await next.refresh, true);
        }
        assertReadOnly(s);
      });
  }
}

for (const action of ["close", "reused-item replacement", "reused-session-and-item reconnect"]) {
  test(`retired non-AbortError after ${action} cannot overwrite preview feedback`,
    { timeout: 2000 }, async (t) => {
      const s = fixture(t);
      const pending = s.openMediaPreview(LATEST);
      const next = retirePreview(s, action);
      const current = previewSnapshot(s);
      s.previews[0].reject(new Error("Synthetic stale non-AbortError"));
      await pending;
      assert.deepEqual(previewSnapshot(s), current);
      assert.deepEqual(s.feedback, []);
      if (next.replacement) {
        s.previews[1].resolve(imageBlob()); s.decode.resolve();
        await next.replacement;
        assert.equal(s.ui.mediaPreviewImage.hidden, false);
        assert.equal(s.ui.mediaPreviewUnavailable.hidden, true);
      }
      assertReadOnly(s);
    });
}

for (const action of ["close", "manual refresh", "replacement", "reset/reconnect"]) {
  test(`delayed video ticket after ${action} remains readable for cleanup without stale playback`,
    { timeout: 2000 }, async (t) => {
      const s = fixture(t);
      const pending = s.openMediaPreview(VIDEO);
      assert.equal(s.playbacks.length, 1);
      const allocation = s.playbacks[0];
      assert.equal(allocation.signal, undefined,
        "Aborting ticket allocation can lose the cleanup token; await and revoke stale tickets instead");
      const next = retirePreview(s, action);
      assert.equal(s.ticketDeletes.length, 0, "No cleanup token exists until allocation finishes");
      const current = previewSnapshot(s);
      const ticketUrl = "/v1/media-playback/synthetic-retired-ticket";
      allocation.resolve({ url: ticketUrl });
      await pending;
      assert.deepEqual(s.ticketDeletes, [{ url: ticketUrl, method: "DELETE", cache: "no-store", keepalive: true }]);
      assert.deepEqual(previewSnapshot(s), current, "A stale ticket cannot replace the active image or reopen playback");
      if (next.replacement) {
        const image = s.previews[0];
        assert.ok(image.signal);
        assert.equal(image.signal.aborted, false);
        s.closeMediaPreview();
        assert.equal(image.signal.aborted, true,
          "The old video finalizer must leave the replacement image controller available to Close");
        image.resolve(imageBlob());
        await next.replacement;
        assert.equal(s.ui.mediaPreviewImage.hidden, true);
        assert.equal(s.ui.mediaPreviewVideo.hidden, true);
      }
      if (next.refresh) {
        s.listings[0].resolve({ items: [LATEST, OLD] });
        assert.equal(await next.refresh, true);
      }
      assert.equal(s.requests.filter(({ method }) => method === "POST").length, 1);
      assert.equal(s.requests.some(({ url }) => /\/(?:capture|shutter|recording)(?:\/|$)/.test(url)), false);
    });
}

test("a current video ticket publishes once and Close revokes its playback URL",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const pending = s.openMediaPreview(VIDEO);
    const ticketUrl = "/v1/media-playback/synthetic-current-ticket";
    s.playbacks[0].resolve({ url: ticketUrl }); await pending;
    assert.equal(s.ui.mediaPreviewVideo.src, ticketUrl);
    assert.equal(s.ui.mediaPreviewVideo.hidden, false);
    assert.equal(s.state.mediaPreviewTicketUrl, ticketUrl);
    assert.deepEqual(s.ticketDeletes, []);
    s.closeMediaPreview();
    assert.deepEqual(s.ticketDeletes, [{ url: ticketUrl, method: "DELETE", cache: "no-store", keepalive: true }]);
    assert.equal(s.ui.mediaPreviewVideo.src, undefined);
    assert.equal(s.state.mediaPreviewTicketUrl, null);
  });

test("closing an image preview leaves its independently requested original save running",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const preview = s.openMediaPreview(LATEST);
    const download = s.downloadMedia(LATEST);
    await eventually(() => s.originals.length === 1, "original download read");
    const original = s.originals[0];
    assert.ok(original.signal);
    assert.notEqual(original.signal, s.previews[0].signal);
    s.closeMediaPreview();
    assert.equal(original.signal.aborted, false, "Closing review must not cancel a user-requested original save");
    original.resolve(new Response("synthetic original", { headers: { "Content-Type": "image/jpeg" } }));
    await download;
    assert.equal(s.savedBlobs.length, 1);
    assert.equal(s.savedBlobs[0].name, LATEST.name);
    assert.equal(await s.savedBlobs[0].blob.text(), "synthetic original");
    const current = previewSnapshot(s);
    s.previews[0].reject(new Error("Synthetic retired preview failed after original save"));
    await preview;
    assert.deepEqual(previewSnapshot(s), current);
    assert.equal(s.feedback.some((message) => message.startsWith("downloaded:")), true);
    assertReadOnly(s);
  });

for (const outcome of ["resolve", "reject"]) {
  test(`Close releases a decoding image once and ignores its late decode ${outcome}`,
    { timeout: 2000 }, async (t) => {
      const s = fixture(t);
      const review = await openReview(s, "decode pending");
      assert.ok(review.url);
      s.closeMediaPreview();
      assert.equal(s.previews[0].signal.aborted, true);
      assert.deepEqual(s.releasedUrls, [review.url]);
      const current = previewSnapshot(s);
      if (outcome === "resolve") s.decode.resolve();
      else s.decode.reject(new Error("Synthetic retired image decode failure"));
      await review.pending;
      assert.deepEqual(previewSnapshot(s), current);
      assert.equal(s.state.mediaPreviewUrl, null);
      assert.deepEqual(s.feedback, []);
      assertReadOnly(s);
    });
}

test("a current image transport failure still publishes the preview failure",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const pending = s.openMediaPreview(LATEST);
    s.previews[0].reject(new Error("Synthetic current preview transport failure"));
    await pending;
    assert.equal(s.ui.mediaPreviewDialog.open, true);
    assert.equal(s.ui.mediaPreviewUnavailable.hidden, false);
    assert.equal(s.ui.mediaPreviewLoading.hidden, true);
    assert.equal(s.ui.mediaPreviewImage.hidden, true);
    assert.ok(s.feedback.includes("Synthetic current preview transport failure"));
    assertReadOnly(s);
  });

test("cancelling an original save leaves its independently opened image preview running",
  { timeout: 2000 }, async (t) => {
    const s = fixture(t);
    const preview = s.openMediaPreview(LATEST);
    const download = s.downloadMedia(LATEST);
    await eventually(() => s.originals.length === 1, "original download read");
    s.cancelMediaDownload();
    assert.equal(s.originals[0].signal.aborted, true);
    assert.equal(s.previews[0].signal.aborted, false);
    s.originals[0].reject(mediaTransfer.cancellationError());
    await download;
    assert.deepEqual(s.savedBlobs, []);
    s.previews[0].resolve(imageBlob()); s.decode.resolve();
    await preview;
    assert.equal(s.ui.mediaPreviewDialog.open, true);
    assert.equal(s.ui.mediaPreviewImage.hidden, false);
    assert.equal(s.ui.mediaPreviewUnavailable.hidden, true);
    assertReadOnly(s);
  });
