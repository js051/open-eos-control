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
  let reject;
  const promise = new Promise((complete, fail) => { resolve = complete; reject = fail; });
  return { promise, resolve, reject };
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
    mediaDownloadPreparing: false, mediaDownloadOwner: null, mediaDownload: null, mediaUpload: null,
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
      AUTOFOCUS: "AUTOFOCUS", SHUTTER_HALF_PRESS: "SHUTTER_HALF_PRESS",
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
    pauseLivePolling: () => feedback.push("pause"),
    resumeLivePolling: () => feedback.push("resume"),
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
    "shutterReleaseUnconfirmed", "bulbControlLocked", "operateShutter", "autofocus", "halfPressShutter",
    "cancelLatestMediaRefresh", "latestMediaFrom", "refreshLatestMedia",
    "publishLatestMedia", "loadLatestMediaThumbnail", "chooseMediaWritable", "downloadMedia", "cancelMediaDownload",
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

test("only the acknowledged capture readback code permits read-only media recovery", async () => {
  for (const code of ["CAPTURE_STATUS_READBACK_FAILED", "CCAPI_UNREACHABLE", "HTTP_ERROR"]) {
    const subject = context();
    const api = subject.api;
    subject.listing = [NEW_C, OLD_A, OLD_B];
    subject.api = async (url, options = {}) => {
      if (url.endsWith("/capture/still")) {
        subject.requests.push({ url, method: "POST", json: options.json });
        throw { code, status: 502, message: "Synthetic capture response" };
      }
      return api(url, options);
    };
    await captureAndReview(subject);
    assert.equal(writes(subject).length, 1, "Recovery must never resend the shutter");
    assert.equal(writes(subject)[0].json.af, false);
    assert.equal(listingRequests(subject).length, code === "CAPTURE_STATUS_READBACK_FAILED" ? 1 : 0);
    assert.equal(subject.state.latestMediaItem.id, code === "CAPTURE_STATUS_READBACK_FAILED" ? NEW_C.id : OLD_A.id);
    assert.equal(subject.feedback.includes("captureComplete"), false, "Readback failure must remain visible");
  }
});

test("short-control failure exposes a stop-only button and never repeats the original command", async () => {
  for (const [method, route] of [["operateShutter", "/capture/still"], ["autofocus", "/focus/auto"],
    ["halfPressShutter", "/shutter/half-press"]]) {
    const subject = context();
    subject.api = async (url, options = {}) => {
      subject.requests.push({ url, method: options.method || "GET" });
      if (url.endsWith(route)) throw { code: "SHUTTER_RELEASE_UNCONFIRMED", message: "Stop not confirmed" };
      if (url.endsWith("/bulb/stop")) return { bulbExposureActive: false, shutterReleaseUnconfirmed: false };
      throw new Error(`Unexpected request: ${url}`);
    };
    await subject[method]();
    assert.equal(subject.state.shutterReleaseUnconfirmed, true);
    assert.equal(subject.feedback.includes("pause"), true);
    await subject.operateShutter();
    assert.equal(subject.state.shutterReleaseUnconfirmed, false);
    assert.deepEqual(subject.requests.map(({ url }) => url.split("/synthetic-session-a")[1]), [route, "/bulb/stop"]);
  }
});

test("late short-control failures cannot transfer stop responsibility to a replacement session", async () => {
  for (const method of ["autofocus", "halfPressShutter"]) {
    const subject = context();
    const response = deferred();
    subject.api = () => response.promise;
    const pending = subject[method]();
    subject.state.session = { id: "replacement-session" };
    subject.state.busy = true;
    response.reject({ code: "SHUTTER_RELEASE_UNCONFIRMED", message: "Old-session stop failed" });
    await pending;
    assert.notEqual(subject.state.shutterReleaseUnconfirmed, true);
    assert.equal(subject.state.busy, true, "An old finally block cannot release the new session's owner");
  }
});

test("late short-control acknowledgements cannot replace a newer session's status or owner", async () => {
  for (const method of ["autofocus", "halfPressShutter"]) {
    const subject = context();
    const response = deferred();
    subject.api = () => response.promise;
    const pending = subject[method]();
    const status = { mode: "Manual", recording: true };
    subject.state.session = { id: "replacement-session" };
    subject.state.status = status;
    subject.state.busy = true;
    const feedback = [...subject.feedback];
    response.resolve({ mode: "Old camera", recording: false });
    await pending;
    assert.equal(subject.state.status, status);
    assert.equal(subject.state.busy, true);
    assert.deepEqual(subject.feedback, feedback);
  }
});

for (const action of ["start", "stop"]) {
  for (const outcome of ["acknowledged", "failed"]) {
    test(`late recording ${action} ${outcome} cannot overwrite a replacement session`, async () => {
      const subject = context();
      subject.state.captureMode = "video";
      subject.state.status.recording = action === "stop";
      const response = deferred();
      subject.api = (url, options) => {
        subject.requests.push({ url, method: options.method });
        return response.promise;
      };
      const pending = subject.operateShutter();
      const replacement = { id: "replacement-recording-session" };
      const status = { mode: "Manual", recording: action === "stop" };
      const error = { message: "Replacement operation warning" };
      subject.state.session = replacement;
      subject.state.status = status;
      subject.state.lastError = error;
      subject.state.busy = true;
      subject.state.shutterReleaseUnconfirmed = false;
      const feedback = [...subject.feedback];
      if (outcome === "acknowledged") response.resolve({ mode: "Old camera", recording: action === "start" });
      else response.reject({ code: "SHUTTER_RELEASE_UNCONFIRMED", message: "Old recording response failed" });
      await pending;
      assert.deepEqual(subject.requests, [{ url: `/v1/session/synthetic-session-a/recording/${action}`, method: "POST" }]);
      assert.equal(subject.state.session, replacement);
      assert.equal(subject.state.status, status, "A recording response belongs only to the captured session");
      assert.equal(subject.state.lastError, error);
      assert.equal(subject.state.busy, true, "Old finally must not release the replacement operation");
      assert.equal(subject.state.shutterReleaseUnconfirmed, false);
      assert.deepEqual(subject.feedback, feedback, "Late recording feedback must remain silent");
    });
  }
}

for (const outcome of ["acknowledged", "unconfirmed", "lost-response"]) {
  test(`late ${outcome} disconnect cleans only its original session and preserves newer ownership`, async () => {
    const subject = context();
    const eventStop = deferred();
    const close = deferred();
    subject.state.shutterReleaseUnconfirmed = true;
    subject.ui = { connectionError: {} };
    subject.stopLiveLoop = () => {};
    subject.stopLocalVideo = () => {};
    subject.stopEventLoop = (id) => {
      assert.equal(id, "synthetic-session-a");
      return eventStop.promise;
    };
    let resets = 0;
    subject.resetSession = () => { resets += 1; subject.state.session = null; subject.state.busy = false; };
    subject.api = (url, options) => {
      subject.requests.push({ url, method: options.method });
      return close.promise;
    };
    vm.runInContext(productionFunction("disconnectCamera"), subject);
    const pending = subject.disconnectCamera();
    const replacement = { id: "replacement-session" };
    const status = { mode: "Manual", recording: true };
    subject.state.session = replacement;
    subject.state.status = status;
    subject.state.busy = true;
    subject.state.shutterReleaseUnconfirmed = false;
    const feedback = [...subject.feedback];
    eventStop.resolve();
    await eventually(() => subject.requests.length === 1, "original close after event stop");
    if (outcome === "acknowledged") close.resolve(null);
    else close.reject({ code: outcome === "unconfirmed" ? "SHUTTER_RELEASE_UNCONFIRMED" : "NETWORK_ERROR",
      message: "Old camera stop could not be confirmed" });
    await pending;
    assert.deepEqual(subject.requests, [{ url: "/v1/session/synthetic-session-a", method: "DELETE" }]);
    assert.equal(resets, 0, "Original close must not reset the replacement session");
    assert.equal(subject.state.session, replacement);
    assert.equal(subject.state.status, status);
    assert.equal(subject.state.busy, true);
    assert.equal(subject.state.shutterReleaseUnconfirmed, false);
    assert.equal(Boolean(subject.state.shutterDisconnectWarning), outcome !== "acknowledged",
      "A failed old close retains a separate previous-camera warning");
    assert.deepEqual(subject.feedback, feedback);
  });
}

test("late event-stop failure does not publish diagnostics into a replacement session", async () => {
  const subject = context();
  const response = deferred();
  subject.cancelEventLoop = () => {};
  subject.api = () => response.promise;
  const errors = [];
  subject.captureError = (error) => errors.push(error);
  vm.runInContext(productionFunction("stopEventLoop"), subject);
  const pending = subject.stopEventLoop("synthetic-session-a");
  subject.state.session = { id: "replacement-session" };
  response.reject({ code: "NETWORK_ERROR", message: "Old event stop failed" });
  await pending;
  assert.deepEqual(errors, []);
});

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

function replaceDownloadSession(subject) {
  // The companion production browser journey establishes that closing the preview,
  // disconnecting and reconnecting is reachable while createWritable is pending.
  // These contracts isolate the resulting reset transition and late continuations.
  subject.cancelMediaDownload({ silent: true });
  subject.state.session = { id: "synthetic-session-b" };
  subject.state.mediaDownloadPreparing = false;
  subject.state.mediaDownload = null;
  subject.state.mediaDownloadOwner = null;
}

function syntheticWriter() {
  const output = { bytes: [], closed: false, aborted: false };
  return {
    output,
    write: async (chunk) => output.bytes.push(...chunk),
    close: async () => { output.closed = true; },
    abort: async () => { output.aborted = true; },
  };
}

function originalResponse(bytes = Buffer.from("synthetic original")) {
  return new Response(bytes, { headers: { "content-type": "image/jpeg", "content-length": String(bytes.length) } });
}

test("download owner aborts an old picker destination before any replacement-session GET", async () => {
  const subject = context();
  const destination = deferred();
  const writer = syntheticWriter();
  let entered = false;
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => {
    entered = true;
    await destination.promise;
    return writer;
  } });
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
  const download = subject.downloadMedia({ ...NEW_C, sizeBytes: 0 });
  await eventually(() => entered, "the original browser destination creation");
  replaceDownloadSession(subject);
  const feedback = subject.feedback.slice();
  destination.resolve();
  await download;
  assert.deepEqual(subject.requests, [], "A retired picker must not send an original-file request");
  assert.equal(writer.output.aborted, true);
  assert.equal(writer.output.closed, false);
  assert.deepEqual(subject.feedback, feedback, "An old result must not overwrite replacement-session feedback");
  assert.equal(subject.state.mediaDownloadPreparing, false);
});

test("download owner cancels pending creation before disconnect has cleared the same session", async () => {
  const subject = context();
  const destination = deferred();
  const writer = syntheticWriter();
  let entered = false;
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => {
    entered = true;
    await destination.promise;
    return writer;
  } });
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
  const download = subject.downloadMedia({ ...NEW_C, sizeBytes: 0 });
  await eventually(() => entered, "destination creation before disconnect");
  const session = subject.state.session;
  subject.beginCameraInteraction(); // Disconnect is waiting for event-loop / DELETE completion.
  subject.cancelMediaDownload({ silent: true });
  const feedback = subject.feedback.slice();
  destination.resolve();
  await download;
  assert.equal(subject.state.session, session, "The cancellation must already work before resetSession");
  assert.deepEqual(subject.requests, []);
  assert.equal(writer.output.aborted, true);
  assert.equal(writer.output.closed, false);
  assert.deepEqual(subject.feedback, feedback, "Silent disconnect cancellation must not rewrite operation feedback");
  assert.equal(subject.state.busy, true, "The separate disconnect operation still owns its busy state");
  assert.equal(subject.state.mediaDownloadPreparing, false, "The cancelled current owner must still clean up preparation");
  assert.equal(subject.state.mediaDownloadOwner, null);
});

test("download owner preserves a newer pending destination when the old picker completes", async () => {
  const subject = context();
  const oldDestination = deferred();
  const newDestination = deferred();
  const writers = [syntheticWriter(), syntheticWriter()];
  let pickerCalls = 0;
  let clearedRenders = 0;
  subject.clearScheduledMediaTransferRender = () => { clearedRenders += 1; };
  subject.window.showSaveFilePicker = async () => {
    const index = pickerCalls++;
    return { createWritable: async () => {
      await [oldDestination, newDestination][index].promise;
      return writers[index];
    } };
  };
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
  const oldDownload = subject.downloadMedia({ ...OLD_A, sizeBytes: 0 });
  await eventually(() => pickerCalls === 1, "the old picker");
  replaceDownloadSession(subject);
  const newDownload = subject.downloadMedia({ ...NEW_C, sizeBytes: 0 });
  await eventually(() => pickerCalls === 2, "the replacement picker");
  const newOwner = subject.state.mediaDownloadOwner;
  const feedback = subject.feedback.slice();
  oldDestination.resolve();
  await oldDownload;
  const retained = {
    preparing: subject.state.mediaDownloadPreparing,
    owner: subject.state.mediaDownloadOwner,
    clearedRenders,
    feedback: subject.feedback.slice(),
  };
  newDestination.resolve();
  await newDownload;
  assert.equal(retained.preparing, true, "An old finally cannot enable another download during the new picker");
  assert.equal(retained.owner, newOwner);
  assert.equal(retained.clearedRenders, 0);
  assert.deepEqual(retained.feedback, feedback);
  assert.deepEqual(subject.requests.map(({ url }) => url), [`/v1/session/synthetic-session-b/media/${NEW_C.id}`]);
  assert.equal(writers[0].output.aborted, true);
  assert.equal(writers[1].output.closed, true);
  assert.equal(writers[1].output.aborted, false);
});

test("download owner suppresses a late preparation error after silent cancellation in the same session", async () => {
  const subject = context();
  const destination = deferred();
  let entered = false;
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => {
    entered = true;
    return destination.promise;
  } });
  const download = subject.downloadMedia({ ...NEW_C, sizeBytes: 0 });
  await eventually(() => entered, "the pending destination before silent cancellation");
  subject.cancelMediaDownload({ silent: true });
  const feedback = subject.feedback.slice();
  destination.reject(new Error("Synthetic destination failed after cancellation"));
  await download;
  assert.deepEqual(subject.requests, []);
  assert.deepEqual(subject.feedback, feedback);
  assert.equal(subject.state.mediaDownloadPreparing, false);
  assert.equal(subject.state.mediaDownloadOwner, null);
});

test("download owner suppresses an old picker error while a replacement transfer owns progress", async () => {
  const subject = context();
  const oldDestination = deferred();
  const newResponse = deferred();
  let entered = false;
  let clearedRenders = 0;
  subject.clearScheduledMediaTransferRender = () => { clearedRenders += 1; };
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => {
    entered = true;
    return oldDestination.promise;
  } });
  subject.api = async (url) => {
    subject.requests.push({ url, method: "GET" });
    return newResponse.promise;
  };
  const oldDownload = subject.downloadMedia({ ...OLD_A, sizeBytes: 0 });
  await eventually(() => entered, "the old file creation");
  replaceDownloadSession(subject);
  const bytes = Buffer.from("synthetic replacement original");
  const newDownload = subject.downloadMedia({ ...NEW_C, sizeBytes: bytes.length });
  await eventually(() => subject.requests.length === 1, "the replacement original transfer");
  const newTransfer = subject.state.mediaDownload;
  const newOwner = subject.state.mediaDownloadOwner;
  const feedback = subject.feedback.slice();
  oldDestination.reject(new Error("Synthetic old file creation failed"));
  await oldDownload;
  const retained = { transfer: subject.state.mediaDownload, owner: subject.state.mediaDownloadOwner,
    clearedRenders, feedback: subject.feedback.slice() };
  newResponse.resolve(originalResponse(bytes));
  await newDownload;
  assert.equal(retained.transfer, newTransfer);
  assert.equal(retained.owner, newOwner);
  assert.equal(retained.clearedRenders, 0);
  assert.deepEqual(retained.feedback, feedback);
  assert.equal(newTransfer.controller.signal.aborted, false);
  assert.equal(subject.saved.length, 1);
  assert.deepEqual(Buffer.from(await subject.saved[0].blob.arrayBuffer()), bytes);
});

test("download owner discards a late original response without publishing into the new session", async () => {
  const subject = context();
  const response = deferred();
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return response.promise; };
  const bytes = Buffer.from("synthetic original before reconnect");
  const download = subject.downloadMedia({ ...OLD_A, sizeBytes: bytes.length });
  await eventually(() => subject.requests.length === 1, "the original session's media GET");
  replaceDownloadSession(subject);
  const feedback = subject.feedback.slice();
  response.resolve(originalResponse(bytes));
  await download;
  assert.deepEqual(subject.requests.map(({ url }) => url), [`/v1/session/synthetic-session-a/media/${OLD_A.id}`]);
  assert.equal(subject.saved.length, 0);
  assert.deepEqual(subject.feedback, feedback);
  assert.equal(subject.state.mediaDownload, null);
});

test("download owner releases a cancelled current picker without fetching or claiming a saved file", async () => {
  const subject = context();
  subject.window.showSaveFilePicker = async () => { throw mediaTransfer.cancellationError(); };
  await subject.downloadMedia({ ...NEW_C, sizeBytes: 0 });
  assert.deepEqual(subject.requests, []);
  assert.equal(subject.saved.length, 0);
  assert.equal(subject.state.mediaDownloadPreparing, false);
  assert.equal(subject.state.mediaDownload, null);
  assert.equal(subject.state.mediaDownloadOwner, null);
  assert.equal(subject.feedback.includes("downloaded"), false);
});

test("download owner retains normal active-transfer cancellation and discards partial bytes", async () => {
  const subject = context();
  let streamCancelled = false;
  const response = new Response(new ReadableStream({
    start(controller) { controller.enqueue(Uint8Array.from([1, 2, 3])); },
    cancel() { streamCancelled = true; },
  }), { headers: { "content-length": "100", "content-type": "image/jpeg" } });
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return response; };
  const download = subject.downloadMedia({ ...NEW_C, sizeBytes: 100 });
  await eventually(() => subject.state.mediaDownload?.bytesTransferred === 3, "an active partial transfer");
  subject.cancelMediaDownload();
  await download;
  assert.equal(streamCancelled, true);
  assert.equal(subject.saved.length, 0);
  assert.equal(subject.feedback.includes("downloadCancelled"), true);
  assert.equal(subject.feedback.includes("downloaded"), false);
  assert.equal(subject.state.mediaDownloadPreparing, false);
  assert.equal(subject.state.mediaDownload, null);
  assert.equal(subject.state.mediaDownloadOwner, null);
});
