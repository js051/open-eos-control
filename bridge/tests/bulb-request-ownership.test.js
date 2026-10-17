"use strict";
// Production handlers, controlled transport, and virtual time. No exports/test seams.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, `Missing production function ${name}`);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  assert.notEqual(end, -1);
  return source.slice(start, start + 1 + end);
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
function subject({ realApi = false } = {}) {
  let sequence = 0;
  const timers = new Map(), requests = [], feedback = [];
  const node = () => ({ hidden: false, disabled: false, value: "", dataset: {}, textContent: "", classList: {
    toggle(name, enabled) { this[name] = enabled; } },
    setAttribute(name, value) { this[name] = value; }, removeAttribute() {}, append() {} });
  const parent = node();
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= { ...node(), parentElement: parent }; } });
  const state = { session: { id: "synthetic-A" }, token: "synthetic-token-A", captureMode: "photo", busy: false,
    status: { mode: "Bulb", bulbExposureActive: false, shutterReleaseUnconfirmed: false },
    shutterReleaseUnconfirmed: false, refreshGeneration: 0, bulbOwner: null, bulbCommandOwner: null,
    capabilities: {}, cameras: [], localVideoSupport: {}, shutterAutofocus: true };
  const sandbox = vm.createContext({ state, ui, requests, timers, feedback, AbortController, Headers, Response,
    crypto: { randomUUID: () => `synthetic-${++sequence}` }, Uint32Array,
    FEATURES: new Proxy({}, { get: (_, key) => key }), featureSupported: () => true,
    isBulbMode: () => true, captureModeFromCamera: () => null, shutterAFSupported: () => true,
    t: key => key, pauseLivePolling: () => feedback.push("pause"), resumeLivePolling: () => feedback.push("resume"),
    flashCapture: () => feedback.push("flash"), setOperationState: message => feedback.push(message), showToast: message => feedback.push(message),
    captureError: error => { state.lastError = error; return error; }, replaceButtonIcon() {},
    renderAvailability() {}, renderSession() {}, cancelCameraFocusInfo() {},
    setTimeout: (fn, ms) => { const id = ++sequence; timers.set(id, { fn, ms }); return id; }, clearTimeout: id => timers.delete(id),
    window: {}, document: { visibilityState: "visible", querySelector: () => node(), querySelectorAll: () => [] },
    validCcapiUrl: () => true, canChangeShutterAF: () => false, temperatureAllows: () => true,
    renderHeldAutofocus() {}, cameraFocusInfoEligible: () => false, liveViewStopRequired: () => false,
    localPreviewSelected: () => false, maintenanceStopRetryAvailable: () => false,
    renderLiveMagnification() {}, effectiveTapAction: () => null, renderMediaTransfer() {}, renderLatestMedia() {}, renderMediaDetails() {},
    cancelMediaDownload() {}, cancelMediaUpload() {}, cancelMediaMutation() {}, stopMediaAutoImport() {},
    stopLiveLoop() {}, stopLocalVideo() {}, cancelEventLoop() {}, stopEventLoop: async () => {},
    resetSession: () => { state.session = null; state.bulbOwner = null; state.bulbCommandOwner = null; state.busy = false; state.shutterReleaseUnconfirmed = false; },
    api: (url, options = {}) => { const result = deferred(); requests.push({ url, options, ...result }); return result.promise; },
    mediaTransfer: { isAbortError: error => error?.name === "AbortError" },
  });
  const names = ["mediaTransferActive", "cameraInteractionBusy", "beginCameraInteraction", "adoptCameraStatus",
    "shutterReleaseUnconfirmed", "bulbControlLocked", "recordingRecoveryActive", "readBulbRecoveryStatus", "operateShutter", "renderCaptureMode", "disconnectCamera"];
  // On the unchanged baseline, first fail on observable admission/Stop behavior.
  for (const name of ["bulbOwnerCurrent", "bulbStopAvailable", "bulbOwnerRequest", "startOwnedBulb", "stopOwnedBulb", "disconnectBulbCamera", "releaseBulbForLifecycle"]) {
    if (new RegExp(`^  (?:async )?function ${name}\\(`, "m").test(source)) names.push(name);
  }
  vm.runInContext(names.map(productionFunction).join("\n"), sandbox);
  if (realApi) {
    const start = source.indexOf("  class ApiError extends Error {");
    const end = source.indexOf("\n  function ", start);
    vm.runInContext(source.slice(start, end) + "\n" + productionFunction("api"), sandbox);
  }
  return sandbox;
}
function receipt(s, index, outcome = "started", status = outcome === "started"
  ? { bulbExposureActive: true, shutterReleaseUnconfirmed: false }
  : { bulbExposureActive: false, shutterReleaseUnconfirmed: false }) {
  const request = s.requests[index];
  return { ownerId: request.options.json?.ownerId, outcome, ...(status === null ? {} : { status }) };
}
function replace(s) {
  const replacement = { id: "synthetic-A" };
  s.state.session = replacement; s.state.token = "synthetic-token-B";
  s.state.bulbOwner = null; s.state.bulbCommandOwner = null; s.state.busy = true;
  s.state.shutterReleaseUnconfirmed = false; s.state.status = { mode: "Manual", bulbExposureActive: false };
  return replacement;
}
function installAvailability(s) { vm.runInContext(productionFunction("renderAvailability"), s); }
function expire(s) {
  const timer = [...s.timers.values()].find(value => value.ms === 15000);
  assert.ok(timer, "Whole request/body deadline must exist"); timer.fn();
}

test("pending Start saves owner before I/O and exposes Stop/Disconnect while other controls stay locked", async () => {
  const s = subject(); installAvailability(s); const pending = s.operateShutter();
  assert.match(s.requests[0].url, /\/bulb\/start\/owned$/);
  const owner = s.state.bulbOwner;
  assert.ok(owner); assert.equal(owner.session, s.state.session); assert.equal(owner.token, "synthetic-token-A");
  assert.equal(s.requests[0].options.json.ownerId, owner.ownerId);
  s.renderAvailability(); s.renderCaptureMode();
  assert.equal(s.ui.shutterButton.disabled, false); assert.equal(s.ui.disconnectButton.disabled, false);
  assert.equal(s.ui.autofocusButton.disabled, true); assert.equal(s.ui.photoModeButton.disabled, true);
  assert.equal(s.ui.mediaUploadButton.disabled, true); assert.equal(s.ui.refreshButton.disabled, true);
  s.requests[0].resolve(receipt(s, 0)); await pending;
  assert.equal(s.state.busy, false); assert.equal(s.cameraInteractionBusy(), true);
});

test("Stop before pending Start settles dispatches immediately, joins repeats, and ignores late Start", async () => {
  const s = subject(); const start = s.operateShutter(); const owner = s.state.bulbOwner;
  const stop = s.operateShutter(); const repeat = s.operateShutter(); await flush();
  assert.equal(s.requests.length, 2); assert.match(s.requests[1].url, /\/bulb\/stop\/owned$/);
  assert.equal(s.requests[1].options.json.ownerId, s.requests[0].options.json.ownerId);
  s.requests[1].resolve(receipt(s, 1, "cancelled-before-start", null)); await stop; await repeat;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.state.busy, false);
  assert.equal(s.feedback.includes("bulbStopped"), false, "A tombstone is not a physical Stop claim");
  s.requests[0].resolve(receipt(s, 0)); await start;
  assert.notEqual(s.state.status.bulbExposureActive, true); assert.equal(s.state.bulbOwner, null);
  assert.equal(s.feedback.includes("bulbStarted"), false); assert.equal(owner.released, true);
});

test("late Start cannot clear newer Stop busy or a new owner's busy", async () => {
  const s = subject(); const start = s.operateShutter(); const stop = s.operateShutter();
  s.requests[0].resolve(receipt(s, 0)); await start;
  assert.equal(s.state.busy, true, "Stop owns busy after admission");
  s.requests[1].resolve(receipt(s, 1, "released")); await stop;
  const next = s.operateShutter(); const owner = s.state.bulbOwner;
  assert.equal(s.requests.length, 3); assert.equal(s.state.busy, true);
  s.requests[2].resolve(receipt(s, 2)); await next; assert.equal(s.state.bulbOwner, owner);
});

for (const operation of ["start", "stop"]) for (const phase of ["fetch", "body"]) {
  test(`${operation} ${phase} ignores abort but whole request settles at 15s without clearing duty`, async () => {
    const s = subject({ realApi: true }); const held = deferred();
    s.fetch = async (url, options) => {
      s.requests.push({ url, options: { ...options, json: JSON.parse(options.body) } });
      if (url.includes("/start/") && operation === "stop") return Response.json(receipt(s, 0));
      return phase === "fetch" ? held.promise : { ok: true, status: 200, json: () => held.promise };
    };
    let pending = s.operateShutter(); if (operation === "stop") { await pending; pending = s.operateShutter(); }
    await flush(); expire(s); await pending;
    assert.equal(s.state.busy, false); assert.ok(s.state.bulbOwner); assert.equal(s.bulbControlLocked(), true);
    assert.equal(s.requests.at(-1).options.signal.aborted, true); assert.equal(s.timers.size, 0);
    assert.equal(s.requests.some(r => r.url.endsWith("/status")), false, "Status cannot settle owned duty");
    const late = receipt(s, s.requests.length - 1, operation === "stop" ? "released" : "started");
    held.resolve(phase === "fetch" ? Response.json(late) : late); await flush();
    assert.ok(s.state.bulbOwner, "Late result cannot silently retire duty");
  });
}

for (const status of [404, 405, 400]) {
  test(`older/rejecting Bridge ${status} never falls back to legacy Start or clears owner`, async () => {
    const s = subject({ realApi: true });
    s.fetch = async (url, options) => { s.requests.push({ url, options: { ...options, json: JSON.parse(options.body) } });
      return Response.json({ error: { code: "HTTP_ERROR", message: "Synthetic rejection" } }, { status }); };
    await s.operateShutter(); assert.ok(s.state.bulbOwner); await s.operateShutter();
    assert.deepEqual(s.requests.map(r => r.url.split("/bulb/")[1]), ["start/owned", "stop/owned"]);
    assert.ok(s.state.bulbOwner); assert.equal(s.state.busy, false);
  });
}

for (const response of [null, {}, { ownerId: "wrong", outcome: "released" }, { outcome: "started" },
  { outcome: "released", status: { bulbExposureActive: true, shutterReleaseUnconfirmed: false } },
  { outcome: "released", status: { bulbExposureActive: false } }]) {
  test(`nonterminal/malformed/wrong owner Stop receipt retains duty: ${JSON.stringify(response)}`, async () => {
    const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
    const stop = s.operateShutter(); s.requests[1].resolve(response && { ownerId: s.requests[0].options.json.ownerId, ...response }); await stop;
    assert.ok(s.state.bulbOwner); assert.equal(s.state.busy, false);
    s.adoptCameraStatus({ bulbExposureActive: false, shutterReleaseUnconfirmed: false }, s.state.session, 0);
    assert.equal(s.bulbControlLocked(), true, "Ordinary idle snapshot is not an owner receipt");
  });
}

test("repeated terminal released receipt without status clears only exact current owner", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const startStatus = s.state.status;
  const stop = s.operateShutter(); s.requests[1].resolve(receipt(s, 1, "released", null)); await stop;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.bulbControlLocked(), false);
  assert.equal(startStatus.bulbExposureActive, true, "The original observation is not rewritten");
  assert.equal(Object.hasOwn(s.state.status, "bulbExposureActive"), false,
    "A receipt retires its own stale Start flag without inventing a fresh idle observation");
  assert.equal(s.feedback.includes("resume"), true);
  assert.equal(s.feedback.includes("flash"), true); assert.equal(s.feedback.includes("bulbReleaseConfirmed"), true);
  s.renderCaptureMode(); assert.equal(s.ui.shutterLabel.textContent, "startBulb");
  const nextStart = s.operateShutter();
  assert.match(s.requests[2].url, /\/bulb\/start\/owned$/);
  assert.notEqual(s.requests[2].options.json.ownerId, s.requests[0].options.json.ownerId);
  s.requests[2].resolve(receipt(s, 2)); await nextStart;
});

for (const newerStatus of [
  { mode: "Manual", bulbExposureActive: false, shutterReleaseUnconfirmed: true, marker: "short-control-B" },
  { mode: "Bulb", bulbExposureActive: true, shutterReleaseUnconfirmed: false, marker: "exposure-B" },
  { mode: "Manual", marker: "unknown-state-B" },
]) test(`cached A receipt preserves status observed after lost Stop: ${newerStatus.marker}`, async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const owner = s.state.bulbOwner;
  const lostStop = s.operateShutter(); s.requests[1].reject(new Error("Synthetic lost release reply")); await lostStop;
  assert.equal(owner.stopUnconfirmed, true);
  s.adoptCameraStatus(newerStatus, s.state.session, 0);
  s.feedback.length = 0;
  const retry = s.operateShutter(); s.requests[2].resolve(receipt(s, 2, "released", null)); await retry;
  assert.equal(s.state.bulbOwner, null); assert.equal(owner.released, true);
  assert.equal(owner.stopUnconfirmed, false);
  assert.equal(s.state.status, newerStatus, "A's cached receipt is not a newer camera observation");
  if (newerStatus.marker !== "unknown-state-B") {
    assert.equal(s.bulbControlLocked(), true); assert.equal(s.feedback.includes("resume"), false);
    installAvailability(s); s.renderCaptureMode(); s.renderAvailability();
    assert.equal(s.ui.autofocusButton.disabled, true); assert.equal(s.ui.photoModeButton.disabled, true);
    assert.equal(s.ui.shutterButton.disabled, false, "Unowned explicit recovery remains usable");
    assert.equal(s.ui.shutterLabel.textContent, newerStatus.shutterReleaseUnconfirmed ? "retryBulbStop" : "stopBulb");
  }
});

test("cached released receipt preserves independent global warning without a held AF owner", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  s.state.shutterReleaseUnconfirmed = true;
  const independentError = { message: "Synthetic unrelated cleanup failure" };
  s.state.lastError = independentError;
  vm.runInContext(productionFunction("setOperationState"), s);
  assert.equal(s.state.heldAutofocusOwner, undefined);
  s.feedback.length = 0;
  const stop = s.operateShutter(); s.requests[1].resolve(receipt(s, 1, "released", null)); await stop;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.state.shutterReleaseUnconfirmed, true);
  assert.equal(s.bulbControlLocked(), true); assert.equal(s.feedback.includes("resume"), false);
  assert.equal(s.state.lastError, independentError);
  assert.equal(s.ui.operationState.textContent, "bulbReleaseUnconfirmed");
  assert.equal(s.ui.operationState.classList["error-text"], true);
  assert.equal(s.feedback.includes("bulbReleaseConfirmed"), false); assert.equal(s.feedback.includes("flash"), false);
});

for (const newerStatus of [
  { bulbExposureActive: false, shutterReleaseUnconfirmed: true, marker: "short-control-warning" },
  { bulbExposureActive: true, shutterReleaseUnconfirmed: false, marker: "another-active-exposure" },
]) test(`cached receipt preserves B error and warning footer without global success effects: ${newerStatus.marker}`, async () => {
  const s = subject(); vm.runInContext(productionFunction("setOperationState"), s);
  const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const lostStop = s.operateShutter(); s.requests[1].reject(new Error("Synthetic lost Stop reply")); await lostStop;
  s.adoptCameraStatus(newerStatus, s.state.session, 0);
  const errorB = { message: "Synthetic later camera-control error" };
  s.state.lastError = errorB; s.setOperationState("Synthetic B warning", true);
  s.feedback.length = 0;
  const retry = s.operateShutter(); s.requests[2].resolve(receipt(s, 2, "released", null)); await retry;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.bulbControlLocked(), true);
  assert.equal(s.state.lastError, errorB, "A's receipt cannot dismiss B's error");
  assert.equal(s.ui.operationState.textContent, "bulbReleaseUnconfirmed");
  assert.equal(s.ui.operationState.classList["error-text"], true);
  assert.equal(s.feedback.includes("bulbReleaseConfirmed"), false);
  assert.equal(s.feedback.includes("flash"), false); assert.equal(s.feedback.includes("resume"), false);
});

test("cached receipt cannot retire a later adoption of the same status object", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const observedAgain = s.state.status;
  s.adoptCameraStatus(observedAgain, s.state.session, 0);
  const stop = s.operateShutter(); s.requests[1].resolve(receipt(s, 1, "released", null)); await stop;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.state.status, observedAgain);
  assert.equal(s.state.status.bulbExposureActive, true); assert.equal(s.bulbControlLocked(), true);
});

test("supplied dual-false Stop status cannot overwrite an observation adopted during the request", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const stop = s.operateShutter();
  const newer = { bulbExposureActive: false, shutterReleaseUnconfirmed: true, marker: "newer-short-control" };
  s.adoptCameraStatus(newer, s.state.session, 0);
  s.state.shutterReleaseUnconfirmed = true;
  s.requests[1].resolve(receipt(s, 1, "released")); await stop;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.state.status, newer);
  assert.equal(s.state.shutterReleaseUnconfirmed, true); assert.equal(s.bulbControlLocked(), true);
});

test("supplied dual-false Stop proof clears existing warning when no newer observation intervenes", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  s.state.shutterReleaseUnconfirmed = true;
  s.state.lastError = { message: "Synthetic prior Stop uncertainty" };
  const stop = s.operateShutter();
  const proof = { bulbExposureActive: false, shutterReleaseUnconfirmed: false, marker: "release-proof" };
  s.requests[1].resolve(receipt(s, 1, "released", proof)); await stop;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.state.shutterReleaseUnconfirmed, false);
  assert.equal(s.state.status.marker, "release-proof"); assert.equal(s.bulbControlLocked(), false);
  assert.equal(s.state.lastError, null); assert.equal(s.feedback.includes("bulbReleaseConfirmed"), true);
});

test("supplied release status does not clear a global warning first raised during Stop", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const stop = s.operateShutter(); s.state.shutterReleaseUnconfirmed = true;
  s.requests[1].resolve(receipt(s, 1, "released")); await stop;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.state.shutterReleaseUnconfirmed, true);
  assert.equal(s.bulbControlLocked(), true);
});

test("owner completion never clears another interaction's busy reservation", async () => {
  const s = subject(); const start = s.operateShutter(); s.beginCameraInteraction();
  s.requests[0].resolve(receipt(s, 0)); await start; assert.equal(s.state.busy, true);
  installAvailability(s); s.renderAvailability(); assert.equal(s.ui.disconnectButton.disabled, true);
  assert.equal(s.ui.shutterButton.disabled, true);
});

for (const late of ["start", "stop"]) test(`replacement with same wire ID ignores old ${late} completion`, async () => {
  const s = subject(); const start = s.operateShutter();
  let pending = start; if (late === "stop") { s.requests[0].resolve(receipt(s, 0)); await start; pending = s.operateShutter(); }
  const replacement = replace(s); s.requests.at(-1).resolve(receipt(s, s.requests.length - 1, late === "stop" ? "released" : "started")); await pending;
  assert.equal(s.state.session, replacement); assert.equal(s.state.busy, true);
  assert.equal(s.state.status.bulbExposureActive, false); assert.equal(s.state.bulbOwner, null);
  assert.equal(s.state.shutterReleaseUnconfirmed, false);
});

test("Disconnect during Start stops saved owner immediately then closes saved session/token", async () => {
  const s = subject(); const start = s.operateShutter(); const disconnect = s.disconnectCamera(); await flush();
  assert.equal(s.requests.length, 2); assert.match(s.requests[1].url, /stop\/owned$/);
  s.requests[1].resolve(receipt(s, 1, "cancelled-before-start", null)); await flush();
  assert.equal(s.requests.length, 3); assert.equal(s.requests[2].options.method, "DELETE");
  assert.equal(s.requests[2].url, "/v1/session/synthetic-A"); assert.equal(s.requests[2].options.authToken, "synthetic-token-A");
  s.requests[2].resolve(null); await disconnect; assert.equal(s.state.session, null);
  s.requests[0].resolve(receipt(s, 0)); await start; assert.equal(s.state.session, null);
});

test("Disconnect Stop and close each remain bounded despite ignored abort and retain previous warning", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const disconnect = s.disconnectCamera(); await flush(); expire(s); await flush();
  assert.equal(s.requests.length, 3); expire(s); await disconnect;
  assert.equal(s.state.session, null); assert.equal(s.state.shutterDisconnectWarning, true); assert.equal(s.state.busy, false);
  s.requests[1].resolve(receipt(s, 1, "released")); s.requests[2].resolve(null); await flush();
  assert.equal(s.state.shutterDisconnectWarning, true);
});

test("retired close uses saved identity and cannot reset replacement with same ID", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  const disconnect = s.disconnectCamera(); const replacement = replace(s);
  s.requests[1].reject(new Error("Synthetic uncertain Stop")); await flush();
  assert.equal(s.requests[2].options.authToken, "synthetic-token-A");
  s.requests[2].resolve(null); await disconnect;
  assert.equal(s.state.session, replacement); assert.equal(s.state.busy, true); assert.equal(s.state.shutterDisconnectWarning, true);
});

test("visibility stops once without closing the existing connection", async () => {
  const s = subject(); const start = s.operateShutter(); const owner = s.state.bulbOwner;
  assert.ok(owner, "Start must have a saved owner before lifecycle retirement");
  const hidden = s.releaseBulbForLifecycle(); s.releaseBulbForLifecycle(); await flush();
  assert.equal(s.requests.length, 2); assert.equal(s.requests[1].options.keepalive, true);
  s.requests[1].resolve(receipt(s, 1, "cancelled-before-start", null)); await hidden;
  assert.equal(s.requests.length, 2); assert.equal(s.state.session, owner.session);
  s.requests[0].resolve(receipt(s, 0)); await start;
  assert.equal(s.state.session, owner.session); assert.equal(s.state.bulbOwner, null);
  assert.match(source, /visibilitychange[\s\S]*releaseBulbForLifecycle\(\)/);
});

test("pagehide joins a pending visibility Stop then closes saved session exactly once", async () => {
  const s = subject(); const start = s.operateShutter(); const owner = s.state.bulbOwner;
  s.releaseBulbForLifecycle(); s.releaseBulbForLifecycle({ close: true }); s.releaseBulbForLifecycle({ close: true }); await flush();
  assert.equal(s.requests.length, 2); assert.equal(s.requests[1].options.keepalive, true);
  s.requests[1].resolve(receipt(s, 1, "released")); await flush();
  assert.equal(s.requests[2].options.keepalive, true); s.requests[2].resolve(null); await owner.closePromise;
  s.requests[0].resolve(receipt(s, 0)); await start; assert.equal(s.state.session, null);
  assert.equal(s.requests.length, 3);
  assert.match(source, /pagehide[\s\S]*releaseBulbForLifecycle\(\{ close: true \}\)/);
});

test("cancelled-before-start clears only this owner and preserves unrelated shutter/AF warning", async () => {
  const s = subject(); const start = s.operateShutter();
  s.state.shutterReleaseUnconfirmed = true;
  s.state.heldAutofocusOwner = { session: s.state.session };
  const errorB = { message: "Synthetic unrelated AF cleanup failure" };
  s.state.lastError = errorB;
  vm.runInContext(productionFunction("setOperationState"), s);
  const stop = s.operateShutter(); s.requests[1].resolve(receipt(s, 1, "cancelled-before-start", null)); await stop;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.state.shutterReleaseUnconfirmed, true);
  assert.ok(s.state.heldAutofocusOwner); assert.equal(s.feedback.includes("bulbReleaseConfirmed"), false);
  assert.equal(s.state.lastError, errorB);
  assert.equal(s.ui.operationState.textContent, "bulbCancelledBeforeStart");
  assert.equal(s.feedback.includes("flash"), false);
  s.requests[0].resolve(receipt(s, 0)); await start;
});

test("late Start cannot publish into a new Bulb owner after old Stop terminal receipt", async () => {
  const s = subject(); const oldStart = s.operateShutter(); const oldStop = s.operateShutter();
  s.requests[1].resolve(receipt(s, 1, "cancelled-before-start", null)); await oldStop;
  const nextStart = s.operateShutter(); const nextOwner = s.state.bulbOwner;
  s.requests[0].resolve(receipt(s, 0)); await oldStart;
  assert.equal(s.state.bulbOwner, nextOwner); assert.equal(s.state.busy, true);
  assert.equal(s.state.status.bulbExposureActive, false);
  assert.equal(s.feedback.filter(message => message === "bulbStarted").length, 0);
  s.requests[2].resolve(receipt(s, 2)); await nextStart;
});

for (const response of [null, {}, { outcome: "started" }, { outcome: "released" },
  { ownerId: "wrong", outcome: "started", status: { bulbExposureActive: true } },
  { outcome: "started", status: { bulbExposureActive: "true" } }]) {
  test(`malformed Start retains owner: ${JSON.stringify(response)}`, async () => {
    const s = subject(); const start = s.operateShutter();
    s.requests[0].resolve(response && { ownerId: s.requests[0].options.json.ownerId, ...response }); await start;
    assert.ok(s.state.bulbOwner); assert.equal(s.shutterReleaseUnconfirmed(), true);
    assert.equal(s.feedback.includes("bulbStarted"), false); assert.equal(s.state.busy, false);
  });
}

test("explicit same-owner retry after pending Stop uses saved token and only terminal receipt clears duty", async () => {
  const s = subject(); const start = s.operateShutter(); s.requests[0].resolve(receipt(s, 0)); await start;
  s.state.token = "synthetic-new-token";
  const stop = s.operateShutter(); s.requests[1].reject({ code: "BULB_OWNER_PENDING", status: 409, message: "Synthetic pending" }); await stop;
  const retry = s.operateShutter(); assert.equal(s.requests[2].options.authToken, "synthetic-token-A");
  assert.equal(s.requests[2].options.json.ownerId, s.requests[0].options.json.ownerId);
  s.requests[2].resolve(receipt(s, 2, "released", null)); await retry;
  assert.equal(s.state.bulbOwner, null); assert.equal(s.shutterReleaseUnconfirmed(), false);
});

test("pending command blocks status read, settled owner permits read without surrendering duty", async () => {
  const s = subject(); vm.runInContext(productionFunction("refreshSession"), s); installAvailability(s);
  const start = s.operateShutter(); assert.equal(await s.refreshSession(), false);
  assert.equal(s.requests.length, 1); s.requests[0].resolve(receipt(s, 0)); await start;
  s.renderAvailability(); assert.equal(s.ui.refreshButton.disabled, false);
  s.state.status = { bulbExposureActive: false, shutterReleaseUnconfirmed: false };
  assert.equal(s.bulbControlLocked(), true); assert.equal(s.ui.autofocusButton.disabled, true);
});

for (const phase of ["fetch", "body"]) test(`Disconnect close ${phase} stall obeys whole deadline with production API`, async () => {
  const s = subject({ realApi: true }); const held = deferred();
  s.fetch = async (url, options) => {
    s.requests.push({ url, options: { ...options, json: options.body ? JSON.parse(options.body) : undefined } });
    if (options.method === "DELETE") return phase === "fetch" ? held.promise : { ok: true, status: 200, json: () => held.promise };
    return Response.json(receipt(s, s.requests.length - 1, url.includes("/start/") ? "started" : "released"));
  };
  await s.operateShutter(); const closing = s.disconnectCamera(); await flush(); expire(s); await closing;
  assert.equal(s.state.session, null); assert.equal(s.state.shutterDisconnectWarning, true);
  assert.equal(s.requests.at(-1).options.signal.aborted, true);
  held.resolve(phase === "fetch" ? new Response(null, { status: 204 }) : null); await flush();
  assert.equal(s.state.shutterDisconnectWarning, true);
});

for (const operation of ["start", "stop"]) test(`late ${operation} cannot overwrite newer interaction status or feedback`, async () => {
  const s = subject(); let pending = s.operateShutter();
  if (operation === "stop") { s.requests[0].resolve(receipt(s, 0)); await pending; pending = s.operateShutter(); }
  s.beginCameraInteraction(); const newer = { mode: "Manual", marker: "newer-interaction", bulbExposureActive: false };
  s.state.status = newer; s.feedback.length = 0;
  s.requests.at(-1).resolve(receipt(s, s.requests.length - 1, operation === "start" ? "started" : "released")); await pending;
  assert.equal(s.state.status, newer); assert.equal(s.state.busy, true);
  assert.equal(s.feedback.length, 0, "An older command cannot publish into newer operation feedback");
});
