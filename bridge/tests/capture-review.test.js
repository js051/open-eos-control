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
    session: { id: "synthetic-session-a", engine: "ccapi" }, captureMode: "photo", busy: false,
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
    state, requests, feedback, delays, saved, AbortController, Blob, Response, setTimeout, clearTimeout,
    crypto: require("node:crypto").webcrypto, renderCaptureMode() {},
    recordingLifecycle: require("../open_eos_bridge/static/recording-lifecycle.js"),
    renderRecordingHistory() {},
    ui: { focusReticle: { hidden: true } },
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
    captureError: (error) => ({ code: error.code, message: error.message, status: error.status, feature: error.feature, engine: error.engine }),
    window: {},
    mediaHistoryCall: async () => ({ ok: true, ticket: {}, receipt: {}, updated: true }),
    saveMediaBlob: (blob, name) => saved.push({ blob, name }),
    api: async (url, options = {}) => {
      requests.push({ url, method: options.method || "GET", json: options.json, signal: options.signal });
      if (url.endsWith("/capture/still")) return { mode: "Manual", recording: false };
      if (url.endsWith("/media?limit=8")) return { items: [...sandbox.listing] };
      throw new Error(`Unexpected synthetic request: ${url}`);
    },
    listing: [OLD_A, OLD_B],
  });
  // Recording fixtures must provide the new envelope themselves. In particular,
  // CameraStatus.recording from api() is never promoted to a fresh observation.
  sandbox.captureConfiguration = { transfer: async (url, options) => {
    requests.push({ url, method: options.method, signal: options.signal });
    if (url.endsWith("/recording/observation")) {
      return { status: 200, body: await sandbox.recordingObservation() };
    }
    const match = /\/recording\/(start|stop)\/outcome$/.exec(url);
    assert.ok(match, `Unexpected recording fixture route: ${url}`);
    return { status: 200, body: await sandbox.recordingOutcome(match[1]) };
  }};
  sandbox.recordingOutcome = async () => { throw new Error("Recording outcome fixture was not installed"); };
  sandbox.recordingObservation = async () => sandbox.recordingLifecycle.empty("ccapi");
  const functionNames = ["adoptCameraStatus", "cancelRecordingWait", "recordingTransfer", "publishRecordingObservation", "runRecordingLifecycle", "recoverRecording",
    "mediaTransferActive", "cameraInteractionBusy", "beginCameraInteraction",
    "shutterReleaseUnconfirmed", "bulbControlLocked", "readBulbRecoveryStatus", "bulbOwnerCurrent", "bulbStopAvailable", "bulbOwnerRequest", "startOwnedBulb", "stopOwnedBulb", "operateShutter", "autofocus", "halfPressShutter",
    "cancelLatestMediaRefresh", "latestMediaFrom", "refreshLatestMedia",
    "publishLatestMedia", "loadLatestMediaThumbnail", "chooseMediaWritable", "downloadMedia", "cancelMediaDownload", "cancelMediaBatch",
  ];
  // Load a future recovery entry point only when present. Assertions first prove the
  // observable missing NOT_READY result on the unchanged baseline, not an import error.
  if (/^  function visibleMediaIds\(/m.test(source)) functionNames.push("visibleMediaIds");
  if (/^  function retryLatestMedia\(/m.test(source)) functionNames.push("retryLatestMedia");
  if (/^  function recordingRecoveryActive\(/m.test(source)) functionNames.push("recordingRecoveryActive");
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

function installLatestMediaRenderer(subject) {
  const element = () => ({
    hidden: false, disabled: false, textContent: "", dataset: {},
    setAttribute() {}, replaceChildren() {}, append() {},
  });
  subject.ui = Object.fromEntries([
    "latestMediaButton", "latestMediaHeading", "latestMediaReview", "latestMediaReviewStatus",
    "latestMediaRetry", "latestMediaLabel", "latestMediaThumbnail",
  ].map((name) => [name, element()]));
  subject.document = { createElement: element };
  vm.runInContext(productionFunction("renderLatestMedia"), subject);
  subject.renderSession = () => subject.renderLatestMedia();
}

for (const readyDuringSearch of [true, false]) {
  test(`acknowledged readback recovery renders ${readyDuringSearch ? "disabled retry before automatic discovery" :
    "enabled retry only after bounded exhaustion"}`, async () => {
    const subject = context();
    installLatestMediaRenderer(subject);
    const originalApi = subject.api;
    subject.api = async (url, options = {}) => {
      if (url.endsWith("/capture/still")) {
        subject.requests.push({ url, method: "POST", json: options.json });
        throw { code: "CAPTURE_STATUS_READBACK_FAILED", status: 502, message: "Synthetic readback warning" };
      }
      return originalApi(url, options);
    };
    const gates = [];
    subject.sleep = (delay) => {
      subject.delays.push(delay);
      const gate = deferred();
      gates.push(gate);
      return gate.promise;
    };
    await subject.operateShutter();
    const automaticReview = subject.state.latestMediaRefreshPromise;
    const originalWrites = writes(subject).slice();
    const readsBeforeReady = readyDuringSearch ? 1 : 4;
    for (let index = 0; index < Math.min(readsBeforeReady, 3); index += 1) {
      await eventually(() => gates.length === index + 1, `automatic review delay ${index + 1}`);
      assert.equal(listingRequests(subject).length, index + 1);
      assert.equal(subject.state.latestMediaReviewStatus, "SEARCHING");
      assert.equal(subject.ui.latestMediaRetry.hidden, false, "Visibility alone does not permit a manual retry");
      assert.equal(subject.ui.latestMediaRetry.disabled, true);
      assert.equal(subject.ui.latestMediaLabel.textContent, OLD_A.name);
      assert.equal(subject.retryLatestMedia(), automaticReview, "An early retry must retain the automatic owner");
      if (readyDuringSearch) subject.listing = [NEW_C, OLD_A, OLD_B];
      gates[index].resolve();
    }
    await automaticReview;
    if (!readyDuringSearch) {
      assert.equal(listingRequests(subject).length, 4);
      assert.deepEqual(subject.delays, [250, 750, 1500]);
      assert.equal(subject.state.latestMediaReviewStatus, "NOT_READY");
      assert.equal(subject.state.latestMediaRefreshPromise, null);
      assert.equal(subject.ui.latestMediaRetry.hidden, false);
      assert.equal(subject.ui.latestMediaRetry.disabled, false, "Exhaustion makes the manual retry actionable");
      assert.equal(subject.ui.latestMediaLabel.textContent, OLD_A.name);
      subject.listing = [NEW_C, OLD_A, OLD_B];
      await subject.retryLatestMedia();
    }
    assert.equal(listingRequests(subject).length, readsBeforeReady + 1);
    assert.equal(subject.state.latestMediaReviewStatus, "READY");
    assert.equal(subject.ui.latestMediaRetry.hidden, true, "Discovery hides the retry, even without a manual click");
    assert.equal(subject.ui.latestMediaLabel.textContent, NEW_C.name);
    assert.equal(originalWrites.length, 1);
    assert.equal(originalWrites[0].json.af, false);
    assert.deepEqual(writes(subject), originalWrites, "Automatic and manual review never resend the shutter");
    assert.equal(subject.feedback.includes("Synthetic readback warning"), true);
    assert.equal(subject.feedback.includes("captureComplete"), false, "Media discovery cannot erase the readback warning");
  });
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
      subject.recordingOutcome = () => response.promise;
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
      if (outcome === "acknowledged") response.resolve(recordingEnvelope(action, freshRecording(action === "start")));
      else response.reject({ code: "SHUTTER_RELEASE_UNCONFIRMED", message: "Old recording response failed" });
      await pending;
      assert.deepEqual(subject.requests.map(({url, method}) => ({url, method})),
        [{ url: `/v1/session/synthetic-session-a/recording/${action}/outcome`, method: "POST" }]);
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
  let writerCalls = 0;
  let clearedRenders = 0;
  subject.clearScheduledMediaTransferRender = () => { clearedRenders += 1; };
  subject.window.showSaveFilePicker = async () => {
    const index = pickerCalls++;
    return { createWritable: async () => {
      writerCalls += 1;
      await [oldDestination, newDestination][index].promise;
      return writers[index];
    } };
  };
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
  const oldDownload = subject.downloadMedia({ ...OLD_A, sizeBytes: 0 });
  await eventually(() => writerCalls === 1, "the old destination creation");
  replaceDownloadSession(subject);
  const newDownload = subject.downloadMedia({ ...NEW_C, sizeBytes: 0 });
  await eventually(() => writerCalls === 2, "the replacement destination creation");
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

// Review fixtures below model an explicitly supported fresh event provider. The
// real Canon-shaped HTTP peer has no verified fresh recording reader; its browser
// journey separately requires response-only evidence and no automatic selection.
const OLD_VIDEO = { id: "old-video", name: "OLD.MP4", kind: "VIDEO", date: "2099-01-01" };
const NEW_VIDEO = { id: "new-video", name: "NEW.MP4", kind: "VIDEO", date: "2026-01-01" };
function freshRecording(recording) {
  return { version: 1, engine: "ccapi", observationEvidence: "observed", recording, source: "ccapi-event" };
}
function unavailableRecording(failed = false) {
  return { version: 1, engine: "ccapi", observationEvidence: failed ? "read-failed" : "unavailable", recording: null, source: null };
}
function recordingEnvelope(operation, observation = unavailableRecording(), responseEvidence = "response-received") {
  return { version: 1, engine: "ccapi", operation, responseEvidence, observation };
}
function recordingSubject({ observation = freshRecording(false), responseEvidence = "response-received" } = {}) {
  const s = context({ loaded: [OLD_A, OLD_VIDEO], latest: OLD_A });
  s.state.captureMode = "video";
  s.state.status.recording = true;
  s.recordingOutcome = async operation => recordingEnvelope(operation, observation, responseEvidence);
  return s;
}
function recordingOperations(s) {
  return writes(s).map(request => /\/recording\/(start|stop)\/outcome$/.exec(request.url)?.[1]);
}
function installRecordingRefresh(s) {
  s.clampFps = value => value;
  s.syncLiveMagnificationFromCapabilities = () => {};
  vm.runInContext(productionFunction("refreshSession"), s);
  const previous = s.api;
  s.cachedRecording = false;
  s.api = async (url, options = {}) => {
    if (/\/(status|info|capabilities)$/.test(url)) {
      s.requests.push({ url, method: "GET" });
      return url.endsWith("/status") ? { recording: s.cachedRecording } : {};
    }
    return previous(url, options);
  };
}
function installProductionApi(s) {
  s.Headers = Headers;
  const start = source.indexOf("  class ApiError extends Error {");
  const end = source.indexOf("  function resolvedLanguage", start);
  s.renderDiagnostics = () => {};
  vm.runInContext(source.slice(start, end) + productionFunction("api") + productionFunction("captureError"), s);
}
function installRecordingWire(s, responseFor) {
  s.TextDecoder = TextDecoder;
  s.fetch = async (url, options) => {
    s.requests.push({ url, method: options.method, options });
    assert.equal(options.redirect, "error");
    assert.equal(options.cache, "no-store");
    assert.equal(options.credentials, "omit");
    assert.ok(options.signal);
    return responseFor(url, options);
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/capture-configuration.js"), "utf8"), s);
  s.captureConfiguration = s.OpenEOSCaptureConfiguration;
}

test("fresh same-attempt false after Stop reviews only a newly visible video", async () => {
  const s = recordingSubject(); s.listing = [NEW_C, OLD_VIDEO, NEW_VIDEO];
  await captureAndReview(s);
  assert.equal(s.state.latestMediaItem.id, NEW_VIDEO.id);
  assert.equal(s.state.latestMediaReviewStatus, "READY");
  assert.deepEqual(recordingOperations(s), ["stop"]);
  assert.equal(s.state.recordingRecord.observation.source, "ccapi-event");
});
test("recording retry excludes known video and never replays REC", async () => {
  const s = recordingSubject(); s.listing = [NEW_C, OLD_VIDEO];
  await captureAndReview(s);
  assert.equal(s.state.latestMediaReviewStatus, "NOT_READY");
  assert.equal(listingRequests(s).length, 4);
  assert.deepEqual(s.delays, [250, 750, 1500]);
  s.listing = [NEW_C, OLD_VIDEO, NEW_VIDEO]; await s.retryLatestMedia();
  assert.equal(s.state.latestMediaItem.id, NEW_VIDEO.id);
  assert.deepEqual(recordingOperations(s), ["stop"]);
});
for (const observation of [unavailableRecording(), unavailableRecording(true), freshRecording(true)]) {
  test(`Stop without fresh false never starts review: ${observation.observationEvidence}/${observation.recording}`, async () => {
    const s = recordingSubject({ observation }); s.listing = [NEW_VIDEO]; await captureAndReview(s);
    assert.equal(listingRequests(s).length, 0);
    assert.equal(s.state.latestMediaItem.id, OLD_A.id);
    assert.equal(s.state.recordingRecord.stopRequired, observation.recording === true);
    assert.deepEqual(recordingOperations(s), ["stop"]);
  });
}
for (const responseEvidence of ["unconfirmed", "rejected", "not-sent"]) {
  test(`Stop ${responseEvidence} retains duty without fresh false`, async () => {
    const s = recordingSubject({ observation: unavailableRecording(), responseEvidence });
    await captureAndReview(s);
    assert.equal(s.state.recordingRecord.stopRequired, true);
    assert.equal(s.state.recordingRecord.responseEvidence, responseEvidence);
    assert.equal(listingRequests(s).length, 0);
    assert.deepEqual(recordingOperations(s), ["stop"]);
  });
}
test("complete Stop response clears command duty but proves no false and permits deliberate Start", async () => {
  const s = recordingSubject({ observation: unavailableRecording() });
  await captureAndReview(s);
  const stop = s.state.recordingRecord;
  assert.equal(stop.responseEvidence, "response-received");
  assert.equal(stop.stopRequired, false);
  assert.equal(s.state.recordingRecovery, null);
  assert.equal(s.state.status.recording, null);
  assert.equal(s.feedback.includes("recordingResponseOnly"), true);
  assert.equal(s.feedback.includes("recordingStopped"), false);
  assert.equal(listingRequests(s).length, 0);
  await s.operateShutter();
  assert.deepEqual(recordingOperations(s), ["stop", "start"]);
  assert.equal(s.state.recordingRecord.stopRequired, true);
});
test("Start with fresh true still owns Stop and never starts post-stop listing", async () => {
  const s = recordingSubject({ observation: freshRecording(true) }); s.state.status.recording = false;
  await captureAndReview(s);
  assert.equal(s.state.recordingRecord.stopRequired, true);
  assert.equal(s.state.status.recording, true);
  assert.deepEqual(recordingOperations(s), ["start"]);
  assert.equal(listingRequests(s).length, 0);
});
for (const observation of [freshRecording(false), unavailableRecording(), unavailableRecording(true)]) {
  test(`Start never claims recording without fresh true: ${observation.observationEvidence}/${observation.recording}`, async () => {
    const s = recordingSubject({ observation }); s.state.status.recording = false;
    await s.operateShutter();
    assert.equal(s.feedback.includes("recordingStarted"), false);
    assert.equal(s.state.recordingRecord.stopRequired, observation.observationEvidence !== "observed");
    assert.equal(listingRequests(s).length, 0);
  });
}
test("lost Stop with an ACK-like still-capture error cannot trigger capture recovery", async () => {
  const s = recordingSubject();
  s.recordingOutcome = async () => { throw Object.assign(new Error("Lost Stop"), { code: "CAPTURE_STATUS_READBACK_FAILED" }); };
  await captureAndReview(s);
  assert.equal(s.state.recordingRecord.responseEvidence, "unconfirmed");
  assert.equal(s.state.recordingRecord.stopRequired, true);
  assert.equal(listingRequests(s).length, 0);
  assert.deepEqual(recordingOperations(s), ["stop"]);
});
test("fresh stopped observation without media capability sends no listing", async () => {
  const s = recordingSubject(); s.featureSupported = feature => feature !== "MEDIA_BROWSER";
  await captureAndReview(s); assert.equal(listingRequests(s).length, 0); assert.equal(writes(s).length, 1);
});
test("video retry while media is busy sends neither listing nor REC", async () => {
  const s = recordingSubject(); s.listing = [OLD_VIDEO]; await captureAndReview(s);
  const reads = listingRequests(s).length; s.state.mediaDownloadPreparing = true;
  await s.retryLatestMedia(); assert.equal(listingRequests(s).length, reads); assert.equal(writes(s).length, 1);
});
test("video review excludes previously seen candidates outside the current gallery", async () => {
  const s = recordingSubject(); s.state.latestMediaCandidates = { sessionId: s.state.session.id, ids: new Set([NEW_VIDEO.id]) };
  s.listing = [NEW_VIDEO, NEW_C]; await captureAndReview(s);
  assert.equal(s.state.latestMediaReviewStatus, "NOT_READY"); assert.equal(s.state.latestMediaItem.id, OLD_A.id);
});
for (const newOperation of ["photo", "start"]) {
  test(`new ${newOperation} retires pending stopped-video review before awaiting its response`, async () => {
    const s = recordingSubject(); const held = deferred(); const previous = s.api; let first = true;
    s.api = async (url, options) => {
      if (url.endsWith("/media?limit=8") && first) { first = false; return held.promise; }
      return previous(url, options);
    };
    await s.operateShutter(); const oldReview = s.state.latestMediaRefreshPromise;
    if (newOperation === "photo") { s.state.captureMode = "photo"; s.listing = [NEW_VIDEO, NEW_C]; }
    else s.recordingOutcome = async operation => recordingEnvelope(operation, unavailableRecording());
    await captureAndReview(s);
    held.resolve({ items: [NEW_VIDEO] }); await oldReview;
    assert.equal(s.state.latestMediaItem.id, newOperation === "photo" ? NEW_C.id : OLD_A.id);
    assert.equal(writes(s).length, 2);
  });
}
test("new photo retires deferred Stop review before later fresh false recovery", async () => {
  const s = recordingSubject({ observation: unavailableRecording() });
  await captureAndReview(s);
  const record = s.state.recordingRecord;
  assert.equal(record.reviewStarted, false);
  assert.equal(record.responseEvidence, "response-received");
  s.state.captureMode = "photo"; s.listing = [NEW_C];
  await captureAndReview(s);
  assert.equal(s.state.latestMediaItem.id, NEW_C.id);
  const photoAttempt = s.state.latestMediaReviewAttempt;
  const reads = listingRequests(s).length;
  s.recordingObservation = async () => freshRecording(false); s.listing = [NEW_VIDEO];
  assert.equal(await s.recoverRecording(record), true);
  assert.equal(record.observation.recording, false);
  assert.equal(record.responseEvidence, "response-received");
  assert.equal(listingRequests(s).length, reads);
  assert.equal(s.state.latestMediaReviewAttempt, photoAttempt);
  assert.equal(s.state.latestMediaItem.id, NEW_C.id);
  assert.deepEqual(recordingOperations(s).filter(Boolean), ["stop"]);
  assert.equal(writes(s).length, 2);
  assert.equal(record.reviewAttempt, null);
  s.recordingObservation = async () => freshRecording(true);
  await s.recoverRecording(record);
  s.recordingOutcome = async operation => recordingEnvelope(operation, freshRecording(false));
  await captureAndReview(s);
  assert.ok(s.state.recordingRecord.reviewAttempt.knownIds.has(NEW_C.id));
  assert.deepEqual(recordingOperations(s).filter(Boolean), ["stop", "stop"]);
});
test("Stop retires pending still review before the fresh stopped response", async () => {
  const s = context(); const held = deferred(); const previous = s.api; let first = true;
  s.api = async (url, options) => {
    if (url.endsWith("/media?limit=8") && first) { first = false; return held.promise; }
    return previous(url, options);
  };
  await s.operateShutter(); const oldReview = s.state.latestMediaRefreshPromise;
  s.state.captureMode = "video"; s.state.status.recording = true; s.listing = [NEW_C, NEW_VIDEO];
  s.recordingOutcome = async operation => recordingEnvelope(operation, freshRecording(false));
  await captureAndReview(s); held.resolve({ items: [NEW_C] }); await oldReview;
  assert.equal(s.state.latestMediaItem.id, NEW_VIDEO.id); assert.equal(s.state.latestMediaReviewKind, "video");
});
test("video review retains video-specific retry and result text", async () => {
  const s = recordingSubject(); installLatestMediaRenderer(s); s.listing = [OLD_VIDEO]; await captureAndReview(s);
  assert.equal(s.ui.latestMediaReviewStatus.textContent, "latestVideoNotReady");
  s.listing = [NEW_VIDEO]; await s.retryLatestMedia();
  assert.equal(s.ui.latestMediaReviewStatus.textContent, "latestVideoFound");
});
for (const operation of ["start", "stop"]) {
  test(`separate same-owner observation resolves unknown ${operation} without upgrading original response`, async () => {
    const s = recordingSubject({ observation: unavailableRecording(), responseEvidence: "unconfirmed" });
    s.state.status.recording = operation === "stop";
    await s.operateShutter(); const record = s.state.recordingRecord;
    s.recordingObservation = async () => freshRecording(false); s.listing = [NEW_VIDEO];
    assert.equal(await s.recoverRecording(record), true);
    if (s.state.latestMediaRefreshPromise) await s.state.latestMediaRefreshPromise;
    assert.equal(record.responseEvidence, "unconfirmed");
    assert.equal(record.stopRequired, false);
    assert.equal(s.state.status.recording, false);
    assert.equal(s.state.recordingRecovery, null);
    assert.equal(s.state.latestMediaItem.id, operation === "stop" ? NEW_VIDEO.id : OLD_A.id);
    assert.deepEqual(recordingOperations(s), [operation]);
    assert.equal(s.requests.filter(r => r.url.endsWith("/recording/observation") && r.method === "GET").length, 1);
  });
}
for (const cached of [false, true, null, 0, 1, "false", [], {}]) {
  for (const responseEvidence of ["response-received", "unconfirmed"]) {
    test(`cached status ${JSON.stringify(cached)} cannot resolve ${responseEvidence} Start`, async () => {
      const s = recordingSubject({ observation: unavailableRecording(), responseEvidence });
      s.state.status.recording = false; installRecordingRefresh(s); s.cachedRecording = cached;
      await s.operateShutter(); const record = s.state.recordingRecord;
      await s.refreshSession();
      assert.equal(s.state.recordingRecord, record);
      assert.equal(s.state.recordingRecovery, record);
      assert.equal(record.stopRequired, true);
      assert.equal(record.responseEvidence, responseEvidence);
      assert.equal(record.observation.observationEvidence, "unavailable");
      assert.equal(s.state.status.recording, null);
      assert.equal(s.requests.some(r => r.url.endsWith("/recording/observation")), false);
      s.recordingOutcome = async operation => recordingEnvelope(operation);
      await s.operateShutter();
      assert.deepEqual(recordingOperations(s), ["start", "stop"]);
      assert.equal(s.state.recordingRecovery, null);
      assert.equal(s.state.status.recording, null);
      assert.equal(listingRequests(s).length, 0);
    });
  }
}
for (const observation of [unavailableRecording(), unavailableRecording(true), { ...freshRecording(false), source: "assignment-cache" }, { ...freshRecording(false), engine: "usb-ptp" }]) {
  test(`unavailable or invalid read-only evidence cannot resolve unknown Start: ${JSON.stringify(observation)}`, async () => {
    const s = recordingSubject({ responseEvidence: "unconfirmed", observation: unavailableRecording() }); s.state.status.recording = false;
    await s.operateShutter(); const record = s.state.recordingRecord;
    s.recordingObservation = async () => observation;
    await s.recoverRecording(record);
    assert.equal(s.state.recordingRecovery, record); assert.equal(record.stopRequired, true);
    assert.equal(record.responseEvidence, "unconfirmed"); assert.equal(writes(s).length, 1);
    assert.equal(listingRequests(s).length, 0);
  });
}
test("failed read-only recovery retains exact record and immutable command response", async () => {
  const s = recordingSubject({ responseEvidence: "unconfirmed", observation: unavailableRecording() });
  await s.operateShutter(); const record = s.state.recordingRecord;
  s.recordingObservation = async () => { throw new Error("Fresh provider unavailable"); };
  assert.equal(await s.recoverRecording(record), false);
  assert.equal(s.state.recordingRecovery, record); assert.equal(record.responseEvidence, "unconfirmed");
  assert.equal(writes(s).length, 1);
});
for (const replacement of ["session", "attempt", "cancelled", "retired"]) {
  test(`late fresh recovery cannot mutate a ${replacement} owner`, async () => {
    const s = recordingSubject({ responseEvidence: "unconfirmed", observation: unavailableRecording() });
    await s.operateShutter(); const record = s.state.recordingRecord;
    const held = deferred(); s.recordingObservation = () => held.promise;
    const pending = s.recoverRecording(record);
    if (replacement === "session") s.state.session = { ...s.state.session };
    if (replacement === "attempt") s.state.recordingRecord = { ...record };
    if (replacement === "cancelled") s.cancelRecordingWait();
    if (replacement === "retired") record.retired = true;
    const replacementStatus = { owner: replacement, recording: null }; s.state.status = replacementStatus;
    held.resolve(freshRecording(false)); assert.equal(await pending, false);
    assert.equal(s.state.status, replacementStatus); assert.equal(record.stopRequired, true);
    assert.equal(record.responseEvidence, "unconfirmed"); assert.equal(listingRequests(s).length, 0);
    assert.equal(writes(s).length, 1);
  });
}
for (const operation of ["start", "stop"]) {
  for (const completion of ["ack", "lost"]) {
    test(`ordinary Refresh and another shutter cannot race admitted ${operation}/${completion}`, async () => {
      const s = recordingSubject(); installRecordingRefresh(s); s.state.status.recording = operation === "stop";
      const held = deferred(); s.recordingOutcome = () => held.promise;
      const pending = s.operateShutter();
      assert.equal(await s.refreshSession(), false); await s.operateShutter();
      assert.equal(s.requests.length, 1);
      if (completion === "ack") held.resolve(recordingEnvelope(operation)); else held.reject(new Error("Lost response"));
      await pending;
      assert.deepEqual(recordingOperations(s), [operation]);
      assert.equal(s.state.recordingRecord.responseEvidence, completion === "ack" ? "response-received" : "unconfirmed");
      assert.equal(s.state.recordingRecord.stopRequired, operation === "start" || completion === "lost");
      assert.equal(listingRequests(s).length, 0);
    });
  }
}
test("rejected and lost Stop keep the original seen-ID boundary across deliberate retries", async () => {
  const s = recordingSubject({ observation: unavailableRecording(), responseEvidence: "unconfirmed" });
  await s.operateShutter(); const attempt = s.state.recordingRecord.reviewAttempt;
  s.state.media = [NEW_VIDEO]; s.listing = [NEW_VIDEO];
  s.recordingOutcome = async operation => recordingEnvelope(operation, unavailableRecording(), "rejected");
  await s.operateShutter();
  assert.equal(s.state.recordingRecord.reviewAttempt, attempt); assert.equal(s.state.recordingRecord.stopRequired, true);
  s.recordingObservation = async () => freshRecording(false); await s.recoverRecording(s.state.recordingRecord);
  await s.state.latestMediaRefreshPromise;
  assert.equal(s.state.latestMediaItem.id, NEW_VIDEO.id);
  assert.deepEqual(recordingOperations(s), ["stop", "stop"]);
});
test("current unknown Start keeps deliberate Stop after capability disappears", async () => {
  const s = recordingSubject({ observation: unavailableRecording(), responseEvidence: "unconfirmed" }); s.state.status.recording = false;
  await s.operateShutter(); s.featureSupported = () => false;
  s.recordingOutcome = async operation => recordingEnvelope(operation);
  await s.operateShutter();
  assert.deepEqual(recordingOperations(s), ["start", "stop"]);
});
test("previous-session duty never changes a replacement Start direction", async () => {
  const s = recordingSubject({ observation: unavailableRecording(), responseEvidence: "unconfirmed" }); s.state.status.recording = false;
  await s.operateShutter(); const old = s.state.recordingRecord;
  s.state.session = { ...s.state.session }; s.state.status = { recording: false };
  s.recordingOutcome = async operation => recordingEnvelope(operation);
  await s.operateShutter();
  assert.deepEqual(recordingOperations(s), ["start", "start"]);
  assert.equal(old.stopRequired, true); assert.notEqual(s.state.recordingRecovery.session, old.session);
});
for (const reason of ["unknown", "in-flight", "confirmed-active", "idle", "retired-owner"]) {
  test(`connection retirement preserves only previous-camera warning: ${reason}`, () => {
    const s = recordingSubject(); s.state.status.recording = reason === "confirmed-active";
    if (reason === "unknown") s.state.recordingRecovery = { session: s.state.session, stopRequired: true };
    if (reason === "in-flight") s.state.recordingCommandOwner = { session: s.state.session };
    if (reason === "retired-owner") s.state.recordingRecovery = { session: { ...s.state.session }, stopRequired: true };
    s.state.operatorConfirmedFeatures = new Set();
    for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaDownload", "cancelMediaUpload",
      "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaPreview",
      "closeMediaDetails", "releaseObjectUrl", "clearBulbTimer", "renderAvailability", "renderHealth"]) s[name] = () => {};
    for (const name of ["connectionView", "controlView", "tokenInput", "ccapiPasswordInput"]) s.ui[name] = {};
    s.ui.mediaList = { replaceChildren(...children) { this.children = children; } };
    vm.runInContext(productionFunction("cancelSetting") + "\n" + productionFunction("resetSession"), s);
    s.resetSession();
    assert.equal(Boolean(s.state.shutterDisconnectWarning), ["unknown", "in-flight", "confirmed-active"].includes(reason));
    assert.equal(s.state.recordingRecovery, null); assert.equal(s.state.recordingCommandOwner, null);
    assert.equal(s.state.session, null); assert.equal(writes(s).length, 0);
    s.state.session = { id: "replacement" }; s.state.status = { recording: false };
    assert.equal(s.recordingRecoveryActive(), false);
  });
}
for (const operation of ["start", "stop"]) {
  for (const late of ["ack", "failure"]) {
    test(`cancelled admitted ${operation} retains duty despite late ${late}`, async () => {
      const s = recordingSubject(); s.state.status.recording = operation === "stop";
      const held = deferred(); s.recordingOutcome = () => held.promise;
      const pending = s.operateShutter(); const record = s.state.recordingRecord;
      s.cancelRecordingWait();
      if (late === "ack") held.resolve(recordingEnvelope(operation, freshRecording(false)));
      else held.reject(new Error("Cancelled transfer finished late"));
      await pending;
      assert.equal(record.stopRequired, true); assert.equal(record.cancelled, true);
      assert.equal(s.state.recordingRecovery, record);
      assert.equal(record.observation.observationEvidence, "unavailable");
      assert.equal(listingRequests(s).length, 0); assert.equal(writes(s).length, 1);
    });
  }
}

// Run malformed/ambiguous wire representations through the production bounded
// transfer, not api() or a value-level adapter. None may become a Stop receipt.
const malformedRecordingBodies = [[], true, false, 42, "invalid", null, {},
  { recording: null }, { recording: false }, { recording: true },
  ...[0, 1, 2, "false", "true", [], {}].map(recording => ({ recording })),
  ...[
    {}, {code:"CCAPI_HTTP_ERROR"}, {code:"CCAPI_HTTP_ERROR", message:7},
    {code:"CCAPI_HTTP_ERROR", message:"  "}, {code:7, message:"Rejected"},
    {code:"  ", message:"Rejected"},
    {code:"CCAPI_UNREACHABLE", status:502, feature:"VIDEO_RECORDING", engine:"ccapi"},
    {code:"CCAPI_RESPONSE_TOO_LARGE", status:502, feature:"VIDEO_RECORDING", engine:"ccapi"},
    {code:"RECORDING_COMMAND_UNCONFIRMED", status:400, feature:"VIDEO_RECORDING", engine:"ccapi"},
    {code:"RECORDING_COMMAND_UNCONFIRMED", status:502, feature:"STILL_CAPTURE", engine:"ccapi"},
    {code:"RECORDING_COMMAND_UNCONFIRMED", status:502, feature:"VIDEO_RECORDING", engine:"simulator"},
    {code:"RECORDING_COMMAND_UNCONFIRMED", status:502},
    {code:" RECORDING_COMMAND_UNCONFIRMED ", status:502, feature:"VIDEO_RECORDING", engine:"ccapi"},
    {code:"RECORDING_COMMAND_UNCONFIRMED", status:502, feature:" VIDEO_RECORDING ", engine:"ccapi"},
    {code:"RECORDING_COMMAND_UNCONFIRMED", status:502, feature:"VIDEO_RECORDING", engine:" ccapi "},
    {code:" RECORDING_STATUS_READBACK_FAILED ", status:502},
    {code:"RECORDING_STATUS_READBACK_FAILED", message:"Legacy ACK-like body"},
  ].map(error => ({error})) ,
  { ...recordingEnvelope("start"), version: true },
  { ...recordingEnvelope("start"), engine: "libgphoto2" },
  { ...recordingEnvelope("start"), responseEvidence: " response-received " },
  { ...recordingEnvelope("start"), observation: { ...freshRecording(false), source: "assignment-cache" } }];
for (const priorRecording of [false, true]) {
  for (const body of malformedRecordingBodies) {
    test(`bounded lifecycle rejects malformed body, prior=${priorRecording}: ${JSON.stringify(body)}`, async () => {
      const s = recordingSubject(); installRecordingRefresh(s); s.state.status.recording = priorRecording;
      let commands = 0, peerRecording = priorRecording;
      installRecordingWire(s, (url) => {
        const operation = /\/recording\/(start|stop)\/outcome$/.exec(url)[1];
        peerRecording = operation === "start";
        return Response.json(commands++ ? recordingEnvelope(operation) : body);
      });
      await s.operateShutter(); const record = s.state.recordingRecord;
      assert.equal(peerRecording, !priorRecording);
      assert.equal(record.responseEvidence, "unconfirmed"); assert.equal(record.stopRequired, true);
      await s.refreshSession(); assert.equal(s.state.recordingRecovery, record);
      await s.operateShutter();
      assert.deepEqual(recordingOperations(s), [priorRecording ? "stop" : "start", "stop"]);
      assert.equal(peerRecording, false); assert.equal(s.state.recordingRecovery, null);
      assert.equal(s.state.status.recording, null); assert.equal(listingRequests(s).length, 0);
    });
  }
}
const badWireCases = {
  "truncated-json": () => new Response('{"version":1'),
  "duplicate-key": () => new Response(JSON.stringify(recordingEnvelope("start")).replace('"version":1', '"version":1,"version":1')),
  "duplicate-nested-key": () => new Response(JSON.stringify(recordingEnvelope("start")).replace('"recording":null', '"recording":false,"recording":null')),
  "fractional-version": () => new Response(JSON.stringify(recordingEnvelope("start")).replace('"version":1', '"version":1.0')),
  "exponent-version": () => new Response(JSON.stringify(recordingEnvelope("start")).replace('"version":1', '"version":1e0')),
  "oversized-body": () => new Response(" ".repeat(65537) + JSON.stringify(recordingEnvelope("start"))),
  "wrong-content-length": () => new Response(JSON.stringify(recordingEnvelope("start")), { headers: { "content-length": "1" } }),
  "partial-representation": () => new Response(JSON.stringify(recordingEnvelope("start")), { headers: { "content-range": "bytes 0-99/999" } }),
  "encoded-representation": () => new Response(JSON.stringify(recordingEnvelope("start")), { headers: { "content-encoding": "gzip" } }),
  "interrupted-stream": () => new Response(new ReadableStream({ start(controller) { controller.error(new TypeError("Interrupted")); } })),
  "missing-stream": () => new Response(null, { status: 204 }),
  "redirect-network-loss": () => { throw new TypeError("Redirect rejected / connection lost"); },
};
for (const [name, makeResponse] of Object.entries(badWireCases)) {
  test(`bounded recording transfer retains unknown duty: ${name}`, async () => {
    const s = recordingSubject(); s.state.status.recording = false;
    installRecordingWire(s, makeResponse);
    await s.operateShutter();
    assert.equal(s.state.recordingRecord.responseEvidence, "unconfirmed");
    assert.equal(s.state.recordingRecord.stopRequired, true);
    assert.deepEqual(recordingOperations(s), ["start"]); assert.equal(listingRequests(s).length, 0);
  });
}
for (const status of [201, 202, 206, 301, 302, 408, 500, 502, 503]) {
  test(`non-200 ${status} with ACK-like body never becomes lifecycle receipt`, async () => {
    const s = recordingSubject(); s.state.status.recording = false;
    installRecordingWire(s, () => Response.json(recordingEnvelope("start"), { status }));
    await s.operateShutter();
    assert.equal(s.state.recordingRecord.responseEvidence, "unconfirmed");
    assert.equal(s.state.recordingRecord.stopRequired, true);
    assert.equal(writes(s).length, 1); assert.equal(listingRequests(s).length, 0);
  });
}
for (const status of [400, 401, 403, 404, 405, 409, 410, 422, 429]) {
  for (const priorRecording of [false, true]) {
    test(`explicit Bridge preflight ${status} is rejected, preserving prior Stop duty=${priorRecording}`, async () => {
      const s = recordingSubject(); s.state.status.recording = priorRecording;
      installRecordingWire(s, () => Response.json({ error: { code: "PREFLIGHT_REJECTED", message: "Synthetic rejection" } }, { status }));
      await s.operateShutter();
      assert.equal(s.state.recordingRecord.responseEvidence, "rejected");
      assert.equal(s.state.recordingRecord.stopRequired, priorRecording);
      assert.equal(writes(s).length, 1); assert.equal(listingRequests(s).length, 0);
    });
  }
}
for (const status of [400, 401, 403, 404, 405, 409, 410, 422, 429]) {
  test(`header rejection ${status} needs no readable body and never falls back`, async () => {
    const s = recordingSubject(); s.state.status.recording = false;
    let bodyRead = false;
    installRecordingWire(s, () => ({status, headers: new Headers(), body: {
      cancel: async () => {}, getReader() { bodyRead = true; throw new Error("Rejected body must not be consumed"); },
    }}));
    await s.operateShutter();
    assert.equal(bodyRead, false); assert.equal(s.state.recordingRecord.responseEvidence, "rejected");
    assert.equal(s.state.recordingRecord.stopRequired, false); assert.equal(s.state.recordingRecovery, null);
    assert.deepEqual(recordingOperations(s), ["start"]); assert.equal(listingRequests(s).length, 0);
  });
}
for (const evidence of ["not-sent", "rejected"]) {
  test(`explicit ${evidence} Start does not invent an admitted command duty`, async () => {
    const s = recordingSubject({ observation: unavailableRecording(), responseEvidence: evidence });
    s.state.status.recording = false; await s.operateShutter();
    assert.equal(s.state.recordingRecord.responseEvidence, evidence);
    assert.equal(s.state.recordingRecord.stopRequired, false); assert.equal(s.state.recordingRecovery, null);
    assert.equal(s.feedback.includes("recordingStarted"), false); assert.equal(listingRequests(s).length, 0);
    assert.deepEqual(recordingOperations(s), ["start"]);
  });
}
test("recording deadline bounds a non-cooperative transfer without automatic replay", async () => {
  const s = recordingSubject(); s.state.status.recording = false;
  let deadline, delay;
  s.setTimeout = (callback, milliseconds) => {deadline=callback; delay=milliseconds; return 1;};
  s.clearTimeout = () => {};
  installRecordingWire(s, () => new Promise(() => {}));
  const pending = s.operateShutter();
  assert.equal(delay,15000); assert.equal(writes(s).length,1);
  deadline(); await pending;
  assert.equal(s.requests[0].options.signal.aborted,true);
  assert.equal(s.state.recordingRecord.responseEvidence,"unconfirmed");
  assert.equal(s.state.recordingRecord.stopRequired,true);
  assert.deepEqual(recordingOperations(s),["start"]);
});
for (const newerRecording of [false,true,null]) {
  test(`latest concurrent cached Refresh owns status fields but cannot resolve duty: ${newerRecording}`, async () => {
    const s = recordingSubject({observation:unavailableRecording(), responseEvidence:"unconfirmed"});
    s.state.status.recording=false; installRecordingRefresh(s); await s.operateShutter();
    const record=s.state.recordingRecord, held=deferred(); let reads=0;
    s.api=async url => url.endsWith("/status")
      ? (++reads===1 ? held.promise : {recording:newerRecording, owner:"new"}) : {};
    const old=s.refreshSession(); await s.refreshSession();
    held.resolve({recording:!newerRecording, owner:"old"}); await old;
    assert.equal(s.state.status.owner,"new"); assert.equal(s.state.status.recording,null);
    assert.equal(s.state.recordingRecovery,record); assert.equal(record.stopRequired,true);
    assert.equal(record.responseEvidence,"unconfirmed"); assert.equal(writes(s).length,1);
  });
}
for (const operation of ["start", "stop"]) {
  for (const outcome of ["ack", "lost"]) {
    test(`late ${operation}/${outcome} cannot publish into a reused-ID connection`, async () => {
      const s = recordingSubject(); s.state.status.recording = operation === "stop";
      const held = deferred(); s.recordingOutcome = () => held.promise;
      const pending = s.operateShutter(); const old = s.state.recordingRecord;
      const replacement = { ...s.state.session }; s.state.session = replacement;
      const current = {session: replacement, stopRequired: true};
      s.state.recordingRecord = current; s.state.recordingRecovery = current;
      const status = {owner: "replacement", recording: null}; s.state.status = status; s.state.busy = true;
      const feedback = s.feedback.slice();
      if (outcome === "ack") held.resolve(recordingEnvelope(operation, freshRecording(false)));
      else held.reject(new Error("Old reply lost"));
      await pending;
      assert.equal(s.state.recordingRecord, current); assert.equal(s.state.recordingRecovery, current);
      assert.equal(s.state.status, status); assert.equal(s.state.busy, true);
      assert.equal(old.stopRequired, true); assert.deepEqual(s.feedback, feedback);
      assert.equal(listingRequests(s).length, 0); assert.deepEqual(recordingOperations(s), [operation]);
    });
  }
}

test("ACK-like REC error during active Bulb stop cannot acquire recording ownership", async () => {
  const s = recordingSubject(); installProductionApi(s); s.bulbControlLocked = () => true;
  s.fetch = async (url, options) => {
    s.requests.push({ url, method: options.method });
    if (url.endsWith("/bulb/stop")) return Response.json({ error: { code: "RECORDING_STATUS_READBACK_FAILED", message: "Unrelated error" } }, { status: 502 });
    return Response.json({ shutterReleaseUnconfirmed: true, bulbExposureActive: true });
  };
  await s.operateShutter();
  assert.equal(s.state.recordingRecovery, undefined); assert.equal(s.state.recordingRecord, undefined);
  assert.equal(s.state.shutterReleaseUnconfirmed, true); assert.equal(writes(s).length, 1);
  assert.match(writes(s)[0].url, /bulb\/stop$/);
});


test("accepted shutter status admitted before Live View Stop revokes its legacy proof", async () => {
  const s = context();
  s.state.liveActive = true;
  s.state.liveViewStopRevision = 1;
  s.state.liveViewStopOwner = { session: s.state.session, confirmedStart: true };
  s.featureSupported = feature => feature !== "MEDIA_BROWSER" && feature !== "MEDIA_THUMBNAIL";
  s.stopLiveLoop = () => { s.state.liveActive = false; };
  s.renderLiveState = () => {};
  vm.runInContext(["liveViewControlRequest", "stopLiveView", "liveViewStopRequired"].map(productionFunction).join("\n"), s);
  const captureReply = deferred(), stopReply = deferred();
  s.api = async (url, options) => {
    s.requests.push({ url, method: options?.method || "GET" });
    if (url.endsWith("/capture/still")) return captureReply.promise;
    if (url.endsWith("/liveview/stop")) return stopReply.promise;
    assert.fail(`Unexpected request ${url}`);
  };
  const capture = s.operateShutter();
  const stop = s.stopLiveView();
  captureReply.resolve({ mode: "Manual", recording: false, liveViewStopUnconfirmed: true });
  await capture;
  assert.equal(s.state.liveViewStopOwner.stopUnconfirmed, true,
    "The real shutter response remains accepted across Stop's interaction generation");
  stopReply.resolve({ active: false });
  assert.equal(await stop, false);
  assert.equal(s.liveViewStopRequired(), true);
  assert.deepEqual(s.requests.map(request => request.url.split("/").slice(-2).join("/")),
    ["capture/still", "liveview/stop"]);
});

// Cross-platform vectors compare evidence, never force native enum spellings.
const deliveryContractBytes = fs.readFileSync(path.join(__dirname, "../../contracts/media-delivery/v1/outcome-vectors.json"));
const deliveryContract = JSON.parse(deliveryContractBytes);
const deliveryVector = (id) => {
  const vector = deliveryContract.vectors.find((entry) => entry.id === id);
  assert.ok(vector, `Missing delivery vector ${id}`);
  return vector.expected;
};

test("shared delivery vectors remain bounded, synthetic and explicit about evidence", () => {
  assert.equal(deliveryContract.version, 1);
  assert.equal(deliveryContract.limits.maximumVectors, 32);
  assert.equal(deliveryContract.limits.maximumEventsPerVector, 12);
  assert.equal(deliveryContract.limits.maximumRows, 100);
  assert.equal(deliveryContract.limits.maximumJSONBytes, 32768);
  assert.ok(deliveryContractBytes.length <= deliveryContract.limits.maximumJSONBytes);
  assert.ok(deliveryContract.vectors.length <= deliveryContract.limits.maximumVectors);
  assert.equal(new Set(deliveryContract.vectors.map((entry) => entry.id)).size, deliveryContract.vectors.length);
  for (const vector of deliveryContract.vectors) {
    assert.match(vector.id, /^[a-z0-9_]{1,64}$/);
    assert.ok(vector.events.length > 0 && vector.events.length <= deliveryContract.limits.maximumEventsPerVector);
    assert.equal(typeof vector.expected.observation, "string");
    assert.equal(typeof vector.expected.checkpoint, "string");
    assert.equal(typeof vector.expected.continuation, "string");
  }
  assert.equal(deliveryContract.invariants.cloudSyncConfirmed, false);
  assert.equal(deliveryContract.invariants.automaticResumeOnRead, false);
  assert.equal(deliveryContract.invariants.persistDestinationLocator, false);
  assert.equal(deliveryVector("ios_picker_full").perFileMappingConfirmed, false);
  assert.equal(deliveryVector("ios_picker_full").saveConfirmed, false);
});

for (const route of ["blob", "writer"]) {
  test(`delivery contract: ${route} reports only its observed handoff or close checkpoint`, async () => {
    const expected = deliveryVector(route === "blob" ? "pc_blob_handoff" : "pc_writer_close");
    const subject = context();
    const writer = syntheticWriter();
    if (route === "writer") subject.window.showSaveFilePicker = async () => ({ createWritable: async () => writer });
    subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
    await subject.downloadMedia({ ...NEW_C, sizeBytes: route === "writer" ? null : 18 });
    assert.equal(subject.requests.length, 1);
    assert.equal(subject.feedback.includes("downloaded"), expected.saveConfirmed);
    assert.equal(subject.feedback.includes("downloadHandedOff"), expected.checkpoint === "browser_handoff");
    assert.equal(writer.output.closed, expected.checkpoint === "stream_close");
    assert.equal(writer.output.aborted, false);
    assert.equal(subject.saved.length, route === "blob" ? 1 : 0);
  });
}

for (const silent of [false, true]) {
  test(`delivery contract: confirmed close survives ${silent ? "silent retirement" : "user cancellation"}`, async () => {
    const expected = deliveryVector("close_after_cancel");
    const subject = context();
    const writer = syntheticWriter();
    const close = deferred();
    let closing = false;
    writer.close = async () => { closing = true; await close.promise; writer.output.closed = true; };
    subject.window.showSaveFilePicker = async () => ({ createWritable: async () => writer });
    subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
    const download = subject.downloadMedia({ ...NEW_C, sizeBytes: null });
    await eventually(() => closing, "single-file close pending");
    subject.cancelMediaDownload({ silent });
    const feedback = subject.feedback.slice();
    close.resolve();
    await download;
    assert.equal(writer.output.closed, expected.saveConfirmed);
    assert.equal(writer.output.aborted, expected.deleteCompletedOutput);
    assert.equal(subject.requests.length, 1, "Cancellation must not create another attempt");
    assert.equal(subject.feedback.includes("downloadCancelled"), false);
    if (silent) assert.deepEqual(subject.feedback, feedback, "Late commit cannot overwrite another operation's feedback");
    else assert.equal(subject.feedback.includes("downloaded"), true);
  });
}

test("delivery contract: rejected close stays unconfirmed even when abort succeeds", async () => {
  const expected = deliveryVector("pc_close_rejected_abort_succeeded");
  const subject = context();
  const writer = syntheticWriter();
  writer.close = async () => { throw new Error("Synthetic close response lost"); };
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => writer });
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
  await subject.downloadMedia({ ...NEW_C, sizeBytes: null });
  assert.equal(subject.feedback.includes("downloadUnconfirmed"), expected.inspectDestination);
  assert.equal(subject.feedback.includes("downloaded"), expected.saveConfirmed);
  assert.equal(subject.feedback.includes("downloadHandedOff"), false);
  assert.equal(writer.output.aborted, true);
  assert.equal(subject.requests.length, 1);
});

test("delivery contract: failed cleanup keeps a visible unconfirmed result and never retries", async () => {
  const expected = deliveryVector("cleanup_failed");
  const subject = context();
  const writer = syntheticWriter();
  let aborts = 0;
  writer.write = async () => { throw new Error("Synthetic writer stopped"); };
  writer.abort = async () => { aborts++; throw new Error("Synthetic abort refused"); };
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => writer });
  subject.api = async (url) => { subject.requests.push({ url, method: "GET" }); return originalResponse(); };
  await subject.downloadMedia({ ...NEW_C, sizeBytes: null });
  assert.equal(subject.feedback.includes("downloadUnconfirmed"), expected.inspectDestination);
  assert.equal(subject.feedback.includes("downloaded"), expected.saveConfirmed);
  assert.equal(writer.output.closed, false);
  assert.equal(aborts, 1);
  assert.equal(subject.requests.length, 1);
});

function installSingleFileHistory(subject, overrides = {}) {
  const calls = [];
  const warnings = [{ hidden: true, textContent: "" }];
  const ticket = { batchId: "synthetic-batch", epoch: 0 };
  const receipt = { receiptId: "synthetic-observation", batchId: ticket.batchId, epoch: 0 };
  subject.document = { querySelectorAll: () => warnings };
  subject.mediaHistoryWarning = false;
  subject.mediaHistoryStore = Object.fromEntries(Object.entries({
    createBatchTicket: async () => ({ ok: true, ticket }),
    startAttempt: async () => ({ ok: true, receipt }),
    finishAttempt: async () => ({ ok: true, updated: true }),
    ...overrides,
  }).map(([name, operation]) => [name, async (...args) => {
    calls.push({ name, args });
    return operation(...args);
  }]));
  vm.runInContext(productionFunction("mediaHistoryCall"), subject);
  return { calls, warnings, ticket, receipt };
}

for (const route of ["blob", "writer"]) {
  test(`single-file ${route} observation is admitted before I/O and records only its real checkpoint`, async () => {
    const subject = context();
    const order = [];
    const admission = deferred();
    const writer = syntheticWriter();
    const history = installSingleFileHistory(subject, {
      createBatchTicket: async () => { order.push("ticket"); return { ok: true, ticket: {} }; },
      startAttempt: async () => { order.push("admission"); await admission.promise; return { ok: true, receipt: {} }; },
    });
    if (route === "writer") subject.window.showSaveFilePicker = () => {
      order.push("picker");
      return Promise.resolve({ createWritable: async () => { order.push("writer"); return writer; } });
    };
    subject.api = async (url) => { order.push("GET"); subject.requests.push({ url }); return originalResponse(); };
    const download = subject.downloadMedia({ ...NEW_C, sizeBytes: route === "writer" ? null : 18 });
    assert.deepEqual(order, route === "writer" ? ["picker"] : [], "The user-gesture picker is invoked synchronously before history");
    await eventually(() => order.includes("admission"), "single-file admission");
    assert.deepEqual(order, route === "writer" ? ["picker", "ticket", "admission"] : ["ticket", "admission"]);
    admission.resolve();
    await download;
    assert.deepEqual(order.slice(-2), route === "writer" ? ["writer", "GET"] : ["admission", "GET"]);
    assert.deepEqual(history.calls.map(call => call.name), ["createBatchTicket", "startAttempt", "finishAttempt"]);
    const start = history.calls[1].args[1];
    assert.equal(start.destination, route === "writer" ? "browser-file" : "browser-download");
    assert.deepEqual(Object.keys(start).sort(), ["destination", "filename"], "No handles, camera IDs or URLs cross the history boundary");
    const finish = history.calls[2].args[1];
    assert.equal(finish.outcome, route === "writer" ? "close-confirmed" : "handoff-requested");
    assert.equal(finish.outcomeUnknown, route === "blob");
    assert.equal(finish.bytesTransferred, 18);
    assert.equal(subject.requests.length, 1);
  });
}

test("single-file cancelled picker creates no history, destination writer or original request", async () => {
  const subject = context();
  const history = installSingleFileHistory(subject);
  subject.window.showSaveFilePicker = async () => { throw mediaTransfer.cancellationError(); };
  await subject.downloadMedia({ ...NEW_C, sizeBytes: null });
  assert.deepEqual(history.calls, []);
  assert.deepEqual(subject.requests, []);
});

test("single-file retired picker discards a late handle without opening or admitting it", async () => {
  const subject = context();
  const history = installSingleFileHistory(subject);
  const picker = deferred();
  let opened = 0;
  subject.window.showSaveFilePicker = () => picker.promise;
  const download = subject.downloadMedia({ ...NEW_C, sizeBytes: null });
  replaceDownloadSession(subject);
  picker.resolve({ createWritable: async () => { opened++; return syntheticWriter(); } });
  await download;
  assert.equal(opened, 0);
  assert.deepEqual(history.calls, []);
  assert.deepEqual(subject.requests, []);
});

test("single-file cancellation while admission waits records cancellation without writer or GET", async () => {
  const subject = context();
  const gate = deferred();
  let opened = 0;
  const history = installSingleFileHistory(subject, { startAttempt: async () => {
    await gate.promise; return { ok: true, receipt: {} };
  } });
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => { opened++; return syntheticWriter(); } });
  const download = subject.downloadMedia({ ...NEW_C, sizeBytes: null });
  await eventually(() => history.calls.length === 2, "admission to wait");
  subject.cancelMediaDownload({ silent: true });
  gate.resolve();
  await download;
  assert.equal(opened, 0);
  assert.deepEqual(subject.requests, []);
  assert.equal(history.calls[2].args[1].outcome, "cancelled-or-unknown");
  assert.equal(history.calls[2].args[1].outcomeUnknown, false);
});

for (const route of ["blob", "writer"]) {
  for (const failedOperation of ["createBatchTicket", "startAttempt", "finishAttempt"]) {
    test(`single-file ${route} ${failedOperation} failure stops before I/O or preserves the real terminal result`, async () => {
      const subject = context();
      const writer = syntheticWriter();
      let opens = 0;
      const history = installSingleFileHistory(subject, { [failedOperation]: async () => { throw new Error("Synthetic unavailable history"); } });
      if (route === "writer") subject.window.showSaveFilePicker = async () => ({ createWritable: async () => { opens++; return writer; } });
      subject.api = async (url) => { subject.requests.push({ url }); return originalResponse(); };
      await subject.downloadMedia({ ...NEW_C, sizeBytes: route === "writer" ? null : 18 });
      const terminal = failedOperation === "finishAttempt";
      assert.equal(subject.requests.length, terminal ? 1 : 0);
      assert.equal(opens, terminal && route === "writer" ? 1 : 0);
      assert.equal(subject.saved.length, terminal && route === "blob" ? 1 : 0);
      assert.equal(subject.feedback.includes(route === "writer" ? "downloaded" : "downloadHandedOff"), terminal);
      assert.equal(subject.feedback.includes("mediaSingleAdmissionFailed"), !terminal);
      assert.equal(writer.output.aborted, false);
      assert.equal(history.warnings[0].hidden, false);
      assert.equal(history.calls.filter(call => call.name === failedOperation).length, 1);
      assert.equal(history.calls.filter(call => call.name === "finishAttempt").length, terminal ? 1 : 0);
    });
  }
}

for (const route of ["blob", "writer"]) {
  for (const [operation, code] of [["createBatchTicket", "unavailable"], ["startAttempt", "capacity-exceeded"],
    ["startAttempt", "transaction-failed"], ["startAttempt", "transaction-unconfirmed"], ["startAttempt", "stale-ticket"]]) {
    test(`single-file ${route} ${code} admission opens no writer and requests no original`, async () => {
      const subject = context();
      let opens = 0, pickers = 0;
      const history = installSingleFileHistory(subject, { [operation]: async () => ({ ok: false, code }) });
      if (route === "writer") subject.window.showSaveFilePicker = async () => {
        pickers++;
        return { createWritable: async () => { opens++; return syntheticWriter(); } };
      };
      await subject.downloadMedia({ ...NEW_C, sizeBytes: route === "writer" ? null : 18 });
      assert.equal(pickers, route === "writer" ? 1 : 0);
      assert.equal(opens, 0);
      assert.deepEqual(subject.requests, []);
      assert.deepEqual(subject.saved, []);
      assert.equal(subject.feedback.includes("mediaSingleAdmissionFailed"), true);
      assert.equal(history.calls.filter(call => call.name === operation).length, 1);
      assert.equal(history.calls.some(call => call.name === "finishAttempt"), false);
      for (const call of history.calls.filter(call => call.name === "startAttempt")) {
        assert.deepEqual(Object.keys(call.args[1]).sort(), ["destination", "filename"]);
      }
    });
  }
}

test("single-file late close records close-confirmed after silent session replacement", async () => {
  const subject = context();
  const close = deferred();
  let closing = false;
  const writer = syntheticWriter();
  writer.close = async () => { closing = true; await close.promise; writer.output.closed = true; };
  const history = installSingleFileHistory(subject);
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => writer });
  subject.api = async (url) => { subject.requests.push({ url }); return originalResponse(); };
  const download = subject.downloadMedia({ ...NEW_C, sizeBytes: null });
  await eventually(() => closing, "close to wait");
  replaceDownloadSession(subject);
  const feedback = subject.feedback.slice();
  close.resolve();
  await download;
  assert.equal(history.calls.at(-1).args[1].outcome, "close-confirmed");
  assert.equal(history.calls.at(-1).args[1].outcomeUnknown, false);
  assert.equal(writer.output.aborted, false);
  assert.deepEqual(subject.feedback, feedback);
});

test("single-file cleared observation is not recreated by a late successful close", async () => {
  const subject = context();
  const history = installSingleFileHistory(subject, { finishAttempt: async () => ({ ok: false, code: "stale-ticket" }) });
  subject.window.showSaveFilePicker = async () => ({ createWritable: async () => syntheticWriter() });
  subject.api = async () => originalResponse();
  await subject.downloadMedia({ ...NEW_C, sizeBytes: null });
  assert.deepEqual(history.calls.map(call => call.name), ["createBatchTicket", "startAttempt", "finishAttempt"]);
  assert.equal(history.calls.at(-1).args[1].outcome, "close-confirmed");
  assert.equal(subject.feedback.includes("downloaded"), true);
  assert.equal(history.warnings[0].hidden, true);
});

for (const stage of ["creation", "close", "cleanup"]) {
  test(`single-file ${stage} uncertainty is persisted without a false save or automatic retry`, async () => {
    const subject = context();
    const history = installSingleFileHistory(subject);
    const writer = syntheticWriter();
    if (stage === "close") writer.close = async () => { throw new Error("Synthetic close lost"); };
    if (stage === "cleanup") {
      writer.write = async () => { throw new Error("Synthetic write failed"); };
      writer.abort = async () => { throw new Error("Synthetic cleanup unknown"); };
    }
    subject.window.showSaveFilePicker = async () => ({ createWritable: async () => {
      if (stage === "creation") throw new Error("Synthetic creation result lost");
      return writer;
    } });
    subject.api = async (url) => { subject.requests.push({ url }); return originalResponse(); };
    await subject.downloadMedia({ ...NEW_C, sizeBytes: null });
    const terminal = history.calls.at(-1).args[1];
    assert.equal(terminal.outcome, "failed");
    assert.equal(terminal.outcomeUnknown, true);
    assert.equal(terminal.mayRemain, true);
    assert.equal(subject.feedback.includes("downloadUnconfirmed"), true);
    assert.equal(subject.requests.length, stage === "creation" ? 0 : 1);
  });
}

// Fixed two-axis contract shared byte-for-byte with iOS and Android. The adapter
// below supplies synthetic transport observations, never expected production results.
const reviewEvidenceVectors = JSON.parse(fs.readFileSync(path.join(__dirname,
  "../../contracts/capture-review-evidence/v1/review-evidence-vectors.json"), "utf8"));
const evidenceOld = [item("OLD_A"), item("OLD_B")];
const evidencePhoto = item("NEW_PHOTO", "2020-01-01T00:00:00Z");
const evidenceVideo = { ...item("NEW_VIDEO"), name: "NEW_VIDEO.MP4", kind: "video", contentType: "video/mp4" };
const evidenceFound = [evidenceOld[0], evidencePhoto, evidenceVideo,
  { ...evidencePhoto, id: "NEW_PHOTO_2" }, { ...evidenceVideo, id: "NEW_VIDEO_2" }];

function installEvidenceReads(subject, reads, intent = "photo") {
  let next = 0;
  const previousApi = subject.api;
  if (intent === "video") subject.recordingOutcome = async operation => recordingEnvelope(operation, freshRecording(false));
  subject.api = async (url, options = {}) => {
    if (!url.endsWith("/media?limit=8")) return previousApi(url, options);
    subject.requests.push({ url, method: options.method || "GET", signal: options.signal });
    assert.ok(next < reads.length, "Bounded review exceeded the fixture read budget");
    const read = reads[next++];
    if (read === "failure") throw new Error("Synthetic list read failure");
    if (read === "malformed") return { items: null };
    const items = read === "empty" ? [] : read === "known-only" ? evidenceOld :
      read === "wrong-kind" ? [intent === "photo" ? evidenceVideo : evidencePhoto] : evidenceFound;
    return { items };
  };
}

for (const vector of reviewEvidenceVectors.cases) {
  test(`shared review evidence: ${vector.id}`, async () => {
    const subject = context({ loaded: evidenceOld, latest: evidenceOld[0] });
    installLatestMediaRenderer(subject);
    if (vector.intent === "video") {
      subject.state.captureMode = "video";
      subject.state.status = { mode: "Movie", recording: true };
    }
    installEvidenceReads(subject, vector.reads, vector.intent);
    await captureAndReview(subject);
    assert.deepEqual(JSON.parse(JSON.stringify(subject.state.latestMediaReviewEvidence)), vector.expected);
    assert.equal(Object.isFrozen(subject.state.latestMediaReviewEvidence), true);
    assert.equal(subject.state.latestMediaItem?.id, vector.expected.candidateId || evidenceOld[0].id);
    assert.equal(subject.state.latestMediaReviewStatus, vector.expected.candidateId ? "READY" : "NOT_READY");
    assert.equal(listingRequests(subject).length, vector.reads.length);
    assert.deepEqual(subject.delays, reviewEvidenceVectors.retryDelaysMillis.slice(0, vector.reads.length - 1));
    assert.equal(writes(subject).length, 1, "Only the original acknowledged capture/stop may write");
    assert.equal(subject.state.latestMediaController, null);
    assert.equal(subject.state.latestMediaRefreshPromise, null);
    if (!vector.expected.candidateId) {
      const prefix = vector.intent === "video" ? "latestVideo" : "latestMedia";
      const suffix = vector.expected.successfulReads && vector.expected.lastCompletedRead === "failure" ?
        "MixedReadFailed" : vector.expected.successfulReads ? "NotReady" : "ReadFailed";
      assert.equal(subject.ui.latestMediaReviewStatus.textContent, prefix + suffix);
    }
  });
}

for (const vector of reviewEvidenceVectors.retryCases) {
  test(`shared review retry evidence: ${vector.id}`, async () => {
    const subject = context({ loaded: evidenceOld, latest: evidenceOld[0] });
    installEvidenceReads(subject, vector.first);
    await captureAndReview(subject);
    const previous = subject.state.latestMediaReviewEvidence;
    installEvidenceReads(subject, vector.second);
    await subject.retryLatestMedia();
    assert.deepEqual(JSON.parse(JSON.stringify(subject.state.latestMediaReviewEvidence)), vector.expected);
    assert.notEqual(subject.state.latestMediaReviewEvidence, previous);
    assert.equal(Object.isFrozen(previous), true);
    assert.equal(listingRequests(subject).length, 8);
    assert.equal(writes(subject).length, 1, "A retry must not resend shutter/REC/AF");
  });
}

for (const retirement of ["zero-completed", "in-delay", "same-id-session", "new-attempt"]) {
  test(`retired review preserves terminal evidence: ${retirement}`, async () => {
    const subject = context({ loaded: evidenceOld, latest: evidenceOld[0] });
    installEvidenceReads(subject, ["failure", "failure", "failure", "failure"]);
    await captureAndReview(subject);
    const previous = subject.state.latestMediaReviewEvidence;
    const previousAttempt = subject.state.latestMediaReviewAttempt;
    const gate = deferred();
    const originalApi = subject.api;
    subject.api = async (url, options = {}) => {
      if (!url.endsWith("/media?limit=8")) return originalApi(url, options);
      subject.requests.push({ url, method: "GET", signal: options.signal });
      if (retirement === "in-delay") return { items: [] };
      await gate.promise;
      if (retirement === "same-id-session") throw new Error("Retired owner failure");
      return { items: evidenceFound };
    };
    if (retirement === "in-delay") subject.sleep = () => gate.promise;
    const pending = subject.retryLatestMedia();
    await eventually(() => listingRequests(subject).length === 5, "new invocation read admission");
    if (retirement === "in-delay") await new Promise((resolve) => setImmediate(resolve));
    assert.equal(subject.state.latestMediaReviewEvidence, previous, "Partial progress is not terminal publication");
    subject.cancelLatestMediaRefresh();
    assert.equal(subject.state.latestMediaReviewEvidence, previous);
    assert.equal(subject.state.latestMediaReviewStatus, "NOT_READY");
    if (retirement === "same-id-session") subject.state.session = { id: subject.state.session.id };
    if (retirement === "new-attempt") subject.state.latestMediaReviewAttempt = { ...previousAttempt };
    gate.resolve();
    await pending;
    assert.equal(subject.state.latestMediaReviewEvidence, previous, "A late success/failure cannot overwrite prior evidence");
    assert.equal(subject.state.latestMediaItem, evidenceOld[0]);
    assert.equal(listingRequests(subject).length, 5);
    assert.equal(writes(subject).length, 1);
    assert.equal(subject.state.latestMediaController, null);
  });
}

test("new capture cancelled before a completed list check never fabricates failure evidence", async () => {
  const subject = context({ loaded: evidenceOld, latest: evidenceOld[0] });
  const gate = deferred();
  const originalApi = subject.api;
  subject.api = async (url, options = {}) => {
    if (!url.endsWith("/media?limit=8")) return originalApi(url, options);
    subject.requests.push({ url, method: "GET", signal: options.signal });
    await gate.promise;
    return { items: [] };
  };
  await subject.operateShutter();
  const pending = subject.state.latestMediaRefreshPromise;
  subject.cancelLatestMediaRefresh();
  gate.resolve();
  await pending;
  assert.equal(subject.state.latestMediaReviewEvidence, null);
  assert.equal(subject.state.latestMediaReviewReadFailed, false);
  assert.equal(subject.state.latestMediaReviewStatus, "IDLE");
  assert.equal(writes(subject).length, 1);
});

for (const response of [null, {}, { items: {} }, { items: [null] }, { items: [{ id: "" }] },
  { items: [{ id: "NEW" }] }, { items: [{ id: "NEW", name: "" }] }, { items: [{ id: "NEW", name: 42 }] }]) {
  test(`invalid list shape cannot masquerade as observed empty: ${JSON.stringify(response)}`, async () => {
    const subject = context();
    const originalApi = subject.api;
    subject.api = async (url, options = {}) => {
      if (!url.endsWith("/media?limit=8")) return originalApi(url, options);
      subject.requests.push({ url, method: "GET" });
      return response;
    };
    await captureAndReview(subject);
    assert.equal(subject.state.latestMediaReviewEvidence.observation, "unread");
    assert.equal(subject.state.latestMediaReviewEvidence.successfulReads, 0);
    assert.equal(subject.state.latestMediaReviewEvidence.failedReads, 4);
    assert.equal(subject.state.latestMediaItem, OLD_A);
    assert.equal(writes(subject).length, 1);
  });
}

for (const replacement of ["same-id-session", "attempt"]) {
  test(`identity fencing rejects late result without explicit cancellation: ${replacement}`, async () => {
    const subject = context({ loaded: evidenceOld, latest: evidenceOld[0] });
    installEvidenceReads(subject, ["empty", "failure", "failure", "failure"]);
    await captureAndReview(subject);
    const previous = subject.state.latestMediaReviewEvidence;
    const gate = deferred();
    subject.api = async (url, options = {}) => {
      assert.ok(url.endsWith("/media?limit=8"));
      subject.requests.push({ url, method: "GET", signal: options.signal });
      await gate.promise;
      return { items: evidenceFound };
    };
    const pending = subject.retryLatestMedia();
    await eventually(() => listingRequests(subject).length === 5, "held retry read");
    if (replacement === "same-id-session") subject.state.session = { id: subject.state.session.id };
    else subject.state.latestMediaReviewAttempt = { ...subject.state.latestMediaReviewAttempt };
    gate.resolve();
    await pending;
    assert.equal(subject.state.latestMediaReviewEvidence, previous);
    assert.equal(subject.state.latestMediaItem, evidenceOld[0]);
    assert.notEqual(subject.state.latestMediaReviewStatus, "READY");
    assert.equal(listingRequests(subject).length, 5);
    assert.equal(writes(subject).length, 1);
  });
}

for (const language of ["en", "zh-TW"]) {
  for (const intent of ["photo", "video"]) {
    test(`mixed last-read failure is explicit in ${language} ${intent} UI`, async () => {
      const subject = context({ loaded: evidenceOld, latest: evidenceOld[0] });
      installLatestMediaRenderer(subject);
      const dictionarySource = source.slice(source.indexOf("  const messages = {"),
        source.indexOf("  const commonSettingValueKeys = {"));
      const dictionaries = vm.runInNewContext(`${dictionarySource}\nmessages;`);
      subject.t = (key) => dictionaries[language][key];
      if (intent === "video") {
        subject.state.captureMode = "video";
        subject.state.status = { mode: "Movie", recording: true };
      }
      installEvidenceReads(subject, ["empty", "failure", "failure", "failure"], intent);
      await captureAndReview(subject);
      const label = subject.ui.latestMediaReviewStatus.textContent;
      assert.match(label, language === "en" ? /No new .* observed/ : /尚未觀察到/);
      assert.match(label, language === "en" ? /latest list read failed; current visibility is unconfirmed/ : /最後一次清單讀取失敗，目前可見情況未確認/);
      assert.match(label, language === "en" ? /without/ : /不會再次/);
      assert.equal(writes(subject).length, 1);
    });
  }
}

for (const completedBeforeAbort of [0, 1]) {
  test(`current-owner abort restores terminal evidence after ${completedBeforeAbort} reads`, async () => {
    const subject = context({ loaded: evidenceOld, latest: evidenceOld[0] });
    installEvidenceReads(subject, ["empty", "failure", "failure", "failure"]);
    await captureAndReview(subject);
    const previous = subject.state.latestMediaReviewEvidence;
    let read = 0;
    subject.api = async (url, options = {}) => {
      assert.ok(url.endsWith("/media?limit=8"));
      subject.requests.push({ url, method: "GET", signal: options.signal });
      if (read++ < completedBeforeAbort) return { items: [] };
      const error = new Error("Synthetic retired listing");
      error.name = "AbortError";
      throw error;
    };
    await subject.retryLatestMedia();
    assert.equal(subject.state.latestMediaReviewEvidence, previous);
    assert.equal(subject.state.latestMediaReviewStatus, "NOT_READY");
    assert.equal(subject.state.latestMediaReviewReadFailed, true);
    assert.equal(subject.state.latestMediaThumbnailLoading, false);
    assert.equal(subject.state.latestMediaController, null);
    assert.equal(subject.state.latestMediaRefreshPromise, null);
    assert.equal(listingRequests(subject).length, 5 + completedBeforeAbort);
    assert.equal(writes(subject).length, 1);
  });
}


for (const language of ["en", "zh-TW"]) {
  test(`recording browser journey waits for current exact observation text: ${language}`, () => {
    const dictionarySource = source.slice(source.indexOf("  const messages = {"),
      source.indexOf("  const commonSettingValueKeys = {"));
    const dictionaries = vm.runInNewContext(`${dictionarySource}\nmessages;`);
    const browserSource = fs.readFileSync(path.join(__dirname, "ccapi.browser.test.js"), "utf8");
    const start = browserSource.indexOf("async function verifyRecordingReviewJourneys(");
    const end = browserSource.indexOf("async function verifyConnectionRecoveryJourneys(", start);
    const journey = browserSource.slice(start, end);
    assert.ok(journey.includes(JSON.stringify(dictionaries[language].latestVideoNotReady)));
    assert.ok(journey.includes(JSON.stringify(dictionaries[language].recordingResponseOnly)));
    assert.ok(journey.includes('observationProvider === "real-ccapi"'));
    assert.ok(journey.includes('explicit-fresh-event-fixture'));
    assert.ok(journey.includes('const peer = await readSimulatorState(simulatorOrigin)'));
    assert.ok(journey.includes('"/recording/observation"'));
    assert.ok(journey.includes('textContent === text &&'));
    assert.ok(journey.includes('retry && !retry.hidden && !retry.disabled'));
    assert.ok(journey.includes('state.record_stop_count === 1'));
    assert.ok(journey.includes('state.record_start_count === 1'));
  });
}

for (const invalid of ['truncated', 'missing-envelope', 'missing-code', 'missing-message']) {
  test(`Bulb Start ${invalid} 502 retains Stop duty despite cached idle status`, async () => {
    const s = context(); installProductionApi(s); s.isBulbMode = () => true;
    s.fetch = async (url, options) => {
      s.requests.push({ url, method: options.method });
      if (url.endsWith('/bulb/start/owned')) {
        if (invalid === 'truncated') return new Response('{', { status: 502 });
        return Response.json(invalid === 'missing-envelope' ? {} : { error: {
          ...(invalid === 'missing-code' ? {} : { code: 'SYNTHETIC_REJECTION' }),
          ...(invalid === 'missing-message' ? {} : { message: 'Synthetic rejection' }),
        } }, { status: 502 });
      }
      return Response.json({ ownerId: JSON.parse(options.body).ownerId, outcome: "released",
        status: { bulbExposureActive: false, shutterReleaseUnconfirmed: false } });
    };
    await s.operateShutter();
    assert.equal(s.state.lastError.code, 'RESPONSE_BODY_UNAVAILABLE');
    assert.equal(s.shutterReleaseUnconfirmed(), true, 'An unreadable mutation result cannot prove rejection');
    assert.equal(s.feedback.includes('resume'), false);
    await s.operateShutter();
    assert.deepEqual(s.requests.filter(r => r.method === 'POST').map(r => r.url.split('/').slice(-3).join('/')),
      ['bulb/start/owned', 'bulb/stop/owned'], 'The next action must release, never replay Start');
    assert.equal(s.state.shutterReleaseUnconfirmed, false, 'Explicit successful Stop may clear duty');
  });
}

test('Explicit owned Bulb Start rejection stays fail-closed until its terminal Stop receipt', async () => {
  const s = context(); installProductionApi(s); s.isBulbMode = () => true;
  s.fetch = async (url, options) => {
    s.requests.push({ url, method: options.method });
    if (url.endsWith('/bulb/start/owned')) return Response.json({ error: {
      code: 'UNSUPPORTED_FEATURE', message: 'Synthetic unsupported operation',
    } }, { status: 400 });
    return Response.json({ bulbExposureActive: false, shutterReleaseUnconfirmed: false });
  };
  await s.operateShutter();
  assert.equal(s.shutterReleaseUnconfirmed(), true);
  assert.equal(s.feedback.includes('resume'), false);
  assert.ok(s.state.bulbOwner);
});

for (const phase of ['fetch', 'body']) for (const retired of [false, true]) {
  test(`Bulb recovery ${phase} stall is bounded with retired=${retired}`, async () => {
    const s = context(); installProductionApi(s); s.isBulbMode = () => true;
    s.state.status.bulbExposureActive = true;
    const held = deferred(), timers = [], cleared = [];
    s.setTimeout = (callback, milliseconds) => { timers.push({ callback, milliseconds }); return timers.length; };
    s.clearTimeout = id => cleared.push(id);
    s.fetch = async (url, options) => {
      s.requests.push({ url, method: options.method, signal: options.signal });
      if (url.endsWith('/bulb/stop')) throw Error('Synthetic lost unowned Stop response');
      if (phase === 'fetch') return held.promise;
      return { ok: true, status: 200, json: () => held.promise };
    };
    const pending = s.operateShutter();
    await eventually(() => s.requests.some(r => r.url.endsWith('/status')), 'recovery request');
    try {
      assert.equal(timers.length, 1, 'Recovery must not indefinitely hide Stop behind busy');
      assert.equal(timers[0].milliseconds, 15000);
      if (retired) {
        s.state.session = { id: 'synthetic-replacement' };
        s.state.busy = true; s.state.shutterReleaseUnconfirmed = false;
      }
      timers[0].callback();
      await pending;
      assert.equal(s.state.busy, retired);
      assert.equal(s.state.shutterReleaseUnconfirmed, !retired);
      assert.equal(s.requests.find(r => r.url.endsWith('/status')).signal.aborted, true);
      assert.equal(cleared.length, 1);
      assert.equal(s.feedback.includes('resume'), false);
    } finally {
      const idle = { bulbExposureActive: false, shutterReleaseUnconfirmed: false };
      held.resolve(phase === 'fetch' ? Response.json(idle) : idle);
      await pending;
    }
    assert.equal(s.state.shutterReleaseUnconfirmed, !retired, 'Late read completion cannot erase or transfer the warning');
  });
}
