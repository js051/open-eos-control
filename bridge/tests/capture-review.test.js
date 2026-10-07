"use strict";

// Synthetic client contracts for the actual app.js functions. The companion browser
// journey exercises DOM events, Bridge HTTP and Canon-shaped HTTP separately.
// This harness does not add exports, timing switches or other production test seams.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");

function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, `Missing production function ${name}`);
  const tail = source.slice(start + 1);
  const end = tail.search(/\n  (?:async )?function /);
  assert.notEqual(end, -1, `Cannot locate end of production function ${name}`);
  return source.slice(start, start + 1 + end);
}

function deferred() {
  let resolve;
  const promise = new Promise((complete) => { resolve = complete; });
  return { promise, resolve };
}

async function eventually(predicate, description) {
  for (let count = 0; count < 100; count += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail(`Did not reach ${description}`);
}

function item(id, captureTime = "2026-10-07T10:00:00Z") {
  return { id, name: id, kind: "jpeg", contentType: "image/jpeg", captureTime, previewAvailable: true };
}

const OLD_A = item("SYNTHETIC_OLD_A.JPG");
const OLD_B = item("SYNTHETIC_OLD_B.JPG", "2026-10-07T09:00:00Z");
const NEW_C = item("SYNTHETIC_NEW_C.JPG", "2020-01-01T00:00:00Z");

function context({ loaded = [OLD_A, OLD_B], latest = OLD_A } = {}) {
  const state = {
    session: { id: "synthetic-session-a" }, captureMode: "photo", busy: false,
    status: { mode: "Manual", recording: false }, shutterAutofocus: false,
    media: [...loaded], latestMediaItem: latest, latestMediaGeneration: 0,
    latestMediaThumbnailLoading: false, latestMediaThumbnailUrl: null,
    latestMediaController: null, latestMediaRefreshPromise: null,
    mediaDownloadPreparing: false, mediaDownload: null, mediaUpload: null,
    refreshGeneration: 0,
  };
  const requests = [];
  const feedback = [];
  const delays = [];
  const saved = [];
  const sandbox = vm.createContext({
    state, requests, feedback, delays, saved, AbortController, Blob, Response,
    URL, Set, Map, mediaLibrary, mediaTransfer,
    mediaIsVideo: mediaLibrary.isVideo,
    FEATURES: {
      BULB_EXPOSURE: "BULB_EXPOSURE", VIDEO_RECORDING: "VIDEO_RECORDING",
      STILL_CAPTURE: "STILL_CAPTURE", MEDIA_BROWSER: "MEDIA_BROWSER",
      MEDIA_THUMBNAIL: "MEDIA_THUMBNAIL", MEDIA_DOWNLOAD: "MEDIA_DOWNLOAD",
    },
    LATEST_MEDIA_LIMIT: 8,
    LATEST_MEDIA_RETRY_DELAYS_MILLIS: [250, 750, 1500],
    MAX_MEDIA_THUMBNAIL_BYTES: 4 * 1024 * 1024,
    featureSupported: (feature) => feature !== "MEDIA_THUMBNAIL",
    shutterAFSupported: () => true,
    isBulbMode: () => false,
    t: (key) => key,
    resolvedLanguage: () => "en",
    sleep: async (delay) => { delays.push(delay); },
    renderAvailability() {}, renderSession() {}, renderLatestMedia() {},
    renderMedia() {}, renderMediaTransfer() {}, renderMediaPreviewNavigation() {},
    scheduleMediaTransferRender() {}, clearScheduledMediaTransferRender() {},
    releaseObjectUrl() {}, flashCapture() {},
    setOperationState: (message) => feedback.push(message),
    showToast: (message) => feedback.push(message),
    captureError: (error) => ({ code: error.code, message: error.message }),
    window: {},
    saveMediaBlob: (blob, name) => saved.push({ blob, name }),
    api: async (url, options = {}) => {
      requests.push({ url, method: options.method || "GET", json: options.json, signal: options.signal });
      if (url.endsWith("/capture/still")) return { mode: "Manual", recording: false };
      if (url.endsWith("/media?limit=8")) return { items: [...sandbox.listing] };
      throw new Error(`Unexpected synthetic request: ${url}`);
    },
    listing: [OLD_A, OLD_B],
  });
  const functionNames = [
    "mediaTransferActive", "cameraInteractionBusy", "beginCameraInteraction",
    "shutterReleaseUnconfirmed", "bulbControlLocked", "operateShutter",
    "cancelLatestMediaRefresh", "latestMediaFrom", "refreshLatestMedia",
    "publishLatestMedia", "loadLatestMediaThumbnail", "chooseMediaWritable", "downloadMedia",
  ];
  // Load a future recovery entry point only when present. Assertions first prove the
  // observable missing NOT_READY result on the unchanged baseline, not an import error.
  if (/^  function visibleMediaIds\(/m.test(source)) functionNames.push("visibleMediaIds");
  if (/^  function retryLatestMedia\(/m.test(source)) functionNames.push("retryLatestMedia");
  vm.runInContext(functionNames.map(productionFunction).join("\n"), sandbox);
  return sandbox;
}

async function captureAndReview(subject) {
  await subject.operateShutter();
  const review = subject.state.latestMediaRefreshPromise;
  if (review) await review;
}

function listingRequests(subject) {
  return subject.requests.filter((request) => request.url.endsWith("/media?limit=8"));
}

function writes(subject) {
  return subject.requests.filter((request) => request.method !== "GET");
}

test("known loaded A/B changing order must not turn old B into newly visible media", async () => {
  const subject = context();
  subject.listing = [OLD_B, OLD_A];
  await captureAndReview(subject);
  assert.equal(subject.state.latestMediaItem?.id, OLD_A.id, "Keep the previous-media entry after only known IDs return");
  assert.equal(listingRequests(subject).length, 4, "Search is bounded to the initial read plus three retries");
  assert.deepEqual(subject.delays, [250, 750, 1500]);
  assert.equal(writes(subject).length, 1);
  assert.equal(subject.feedback.includes("captureComplete"), true);
});

test("connection candidates are known even before the album has been opened", async () => {
  const subject = context({ loaded: [], latest: null });
  await subject.refreshLatestMedia();
  assert.equal(subject.state.latestMediaItem?.id, OLD_A.id);
  subject.listing = [OLD_B, OLD_A];
  await captureAndReview(subject);
  assert.equal(subject.state.latestMediaItem?.id, OLD_A.id, "The bounded connection listing already observed B");
  assert.equal(writes(subject).length, 1);
});

test("a new second candidate is visible even when its camera timestamp is older", async () => {
  const subject = context();
  subject.listing = [OLD_A, NEW_C, OLD_B];
  await captureAndReview(subject);
  assert.equal(subject.state.latestMediaItem?.id, NEW_C.id, "Exclude known IDs before ordering the remaining candidates");
  assert.equal(listingRequests(subject).length, 1);
  assert.equal(writes(subject).length, 1);
  assert.equal(subject.state.shutterAutofocus, false);
  assert.equal(writes(subject)[0].json.af, false);
});

test("excluding known media retains camera order and existing non-JPEG representation flags", async () => {
  const subject = context();
  const raw = { ...item("SYNTHETIC_NEW.CR3"), kind: "raw", contentType: "application/octet-stream", previewAvailable: true };
  subject.listing = [OLD_A, raw, NEW_C, OLD_B];
  await captureAndReview(subject);
  assert.equal(subject.state.latestMediaItem?.id, raw.id, "The camera's first unseen static candidate remains first for RAW+JPEG");
  assert.equal(subject.state.latestMediaItem?.previewAvailable, true);
  assert.equal(listingRequests(subject).length, 1);
});

test("a still review skips a new visible video before the second static candidate", async () => {
  const subject = context();
  const video = { ...item("SYNTHETIC_CLIP.MP4"), kind: "video", contentType: "video/mp4" };
  subject.listing = [video, NEW_C, OLD_A];
  await captureAndReview(subject);
  assert.equal(subject.state.latestMediaItem?.id, NEW_C.id);
  assert.equal(listingRequests(subject).length, 1);
  assert.equal(writes(subject).length, 1);
});

test("ordinary connection latest media continues to include video", async () => {
  const subject = context({ loaded: [], latest: null });
  const video = { ...item("SYNTHETIC_CLIP.MP4"), kind: "video", contentType: "video/mp4" };
  subject.listing = [video, OLD_A];
  await subject.refreshLatestMedia();
  assert.equal(subject.state.latestMediaItem?.id, video.id);
  assert.equal(writes(subject).length, 0);
});

test("bounded listing errors keep the shutter acknowledgement and identify the read failure", async () => {
  const subject = context();
  const originalApi = subject.api;
  subject.api = async (url, options) => {
    if (url.endsWith("/media?limit=8")) {
      subject.requests.push({ url, method: "GET" });
      throw new Error("Synthetic listing unavailable");
    }
    return originalApi(url, options);
  };
  await captureAndReview(subject);
  assert.equal(subject.feedback.includes("captureComplete"), true);
  assert.equal(subject.feedback.includes("Synthetic listing unavailable"), false);
  assert.equal(subject.state.latestMediaReviewStatus, "NOT_READY");
  assert.equal(subject.state.latestMediaReviewReadFailed, true);
  assert.equal(subject.state.latestMediaItem?.id, OLD_A.id);
  assert.equal(listingRequests(subject).length, 4);
  assert.equal(writes(subject).length, 1);
});

test("four old listings expose NOT_READY and repeated read-only recovery has one owner", async () => {
  const subject = context();
  await captureAndReview(subject);
  assert.equal(listingRequests(subject).length, 4);
  assert.equal(subject.state.latestMediaReviewStatus, "NOT_READY", "A successful shutter cannot make missing new media look ready");
  assert.equal(subject.state.latestMediaReviewReadFailed, false, "Successful old listings must not claim a read error");
  assert.equal(subject.state.latestMediaItem?.id, OLD_A.id);
  assert.equal(subject.state.latestMediaThumbnailLoading, false);
  assert.equal(typeof subject.retryLatestMedia, "function", "Recovery must be distinct from another shutter command");
  const originalWrites = writes(subject).slice();
  const gate = deferred();
  const originalApi = subject.api;
  let active = 0;
  let maximumActive = 0;
  subject.api = async (url, options) => {
    if (!url.endsWith("/media?limit=8")) return originalApi(url, options);
    active += 1;
    maximumActive = Math.max(maximumActive, active);
    try { await gate.promise; return await originalApi(url, options); }
    finally { active -= 1; }
  };
  const first = subject.retryLatestMedia();
  const owner = subject.state.latestMediaRefreshPromise;
  const second = subject.retryLatestMedia();
  assert.equal(subject.state.latestMediaRefreshPromise, owner, "Repeated clicks retain the same listing owner");
  gate.resolve();
  await Promise.all([first, second, owner]);
  assert.equal(maximumActive, 1);
  assert.equal(listingRequests(subject).length, 8);
  assert.equal(subject.state.latestMediaReviewStatus, "NOT_READY");
  subject.listing = [OLD_A, NEW_C, OLD_B];
  await subject.retryLatestMedia();
  await subject.state.latestMediaRefreshPromise;
  assert.equal(subject.state.latestMediaItem?.id, NEW_C.id);
  assert.equal(listingRequests(subject).length, 9);
  assert.deepEqual(writes(subject), originalWrites, "Neither a retry nor its failure may write any camera command");
  assert.equal(subject.state.shutterAutofocus, false);
});

test("an initially empty card still receives all four bounded post-shutter reads", async () => {
  const subject = context({ loaded: [], latest: null });
  subject.listing = [];
  await captureAndReview(subject);
  assert.equal(listingRequests(subject).length, 4, "No previous ID is not proof that a new item exists");
  assert.equal(subject.state.latestMediaReviewStatus, "NOT_READY");
  assert.equal(subject.state.latestMediaItem, null);
  assert.equal(writes(subject).length, 1);
});

for (const replacement of ["attempt", "session"]) {
  test(`a late fourth old listing cannot clear the replacement ${replacement}'s loading state`, async () => {
    const subject = context();
    const oldGate = deferred();
    const newGate = deferred();
    const originalApi = subject.api;
    let reads = 0;
    subject.api = async (url, options) => {
      if (!url.endsWith("/media?limit=8")) return originalApi(url, options);
      reads += 1;
      if (reads === 4) { await oldGate.promise; return { items: [OLD_A, OLD_B] }; }
      if (reads === 5) { await newGate.promise; return { items: [NEW_C] }; }
      return originalApi(url, options);
    };
    await subject.operateShutter();
    const oldOwner = subject.state.latestMediaRefreshPromise;
    await eventually(() => reads === 4, "the last old listing to wait for a response");
    if (replacement === "session") {
      subject.cancelLatestMediaRefresh();
      subject.state.session = { id: "synthetic-session-b" };
      subject.state.latestMediaItem = null;
      void subject.refreshLatestMedia();
    } else {
      await subject.operateShutter();
    }
    const newOwner = subject.state.latestMediaRefreshPromise;
    await eventually(() => reads === 5, "the replacement listing to wait for a response");
    oldGate.resolve();
    await oldOwner;
    const stayedLoading = subject.state.latestMediaThumbnailLoading;
    const retainedOwner = subject.state.latestMediaRefreshPromise;
    newGate.resolve();
    await newOwner;
    assert.equal(stayedLoading, true, "An obsolete exhausted read cannot mark a newer listing finished");
    assert.equal(retainedOwner, newOwner);
    assert.equal(subject.state.latestMediaItem?.id, NEW_C.id);
  });
}

for (const destination of ["blob", "direct writer"]) {
  test(`${destination} saves original bytes, rejects a short body, and retries without another shutter`, async () => {
    const subject = context();
    // Deliberately different synthetic byte representations. Their labels do not
    // claim image decoding; the browser journey validates real JPEG representations.
    const original = Buffer.from("synthetic JPEG ORIGINAL payload");
    const preview = Buffer.from("synthetic JPEG preview");
    const thumbnail = Buffer.from("synthetic JPEG thumbnail");
    const captured = { ...NEW_C, sizeBytes: destination === "direct writer" ? null : original.length };
    subject.listing = [captured];
    await captureAndReview(subject);
    const originalApi = subject.api;
    let shorten = true;
    const outputFiles = [];
    if (destination === "direct writer") {
      subject.window.showSaveFilePicker = async () => ({ createWritable: async () => {
        const output = { chunks: [], closed: false, aborted: false };
        outputFiles.push(output);
        return {
          write: async (chunk) => output.chunks.push(Buffer.from(chunk)),
          close: async () => { output.closed = true; },
          abort: async () => { output.aborted = true; },
        };
      } });
    }
    subject.api = async (url, options = {}) => {
      if (!url.includes(`/media/${encodeURIComponent(captured.id)}`)) return originalApi(url, options);
      subject.requests.push({ url, method: options.method || "GET" });
      const bytes = url.endsWith("/preview") ? preview : url.endsWith("/thumbnail") ? thumbnail : original;
      return new Response(shorten ? bytes.subarray(0, 5) : bytes, {
        headers: { "content-type": "image/jpeg", "content-length": String(bytes.length) },
      });
    };
    await subject.downloadMedia(captured);
    assert.equal(subject.saved.length, 0, "Partial original must never be published as a saved Blob");
    assert.equal(subject.feedback.some((message) => /length mismatch/.test(message)), true);
    if (destination === "direct writer") {
      assert.equal(outputFiles[0].closed, false);
      assert.equal(outputFiles[0].aborted, true);
    }
    shorten = false;
    await subject.downloadMedia(captured);
    const savedBytes = destination === "direct writer"
      ? Buffer.concat(outputFiles[1].chunks)
      : Buffer.from(await subject.saved[0].blob.arrayBuffer());
    assert.deepEqual(savedBytes, original);
    assert.notDeepEqual(savedBytes, preview);
    assert.notDeepEqual(savedBytes, thumbnail);
    if (destination === "direct writer") assert.equal(outputFiles[1].closed, true);
    assert.equal(writes(subject).length, 1, "Retrying a transfer cannot repeat capture");
    assert.equal(subject.state.shutterAutofocus, false);
  });
}
