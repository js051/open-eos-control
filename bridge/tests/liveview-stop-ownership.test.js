"use strict";

// Actual UI functions with controlled replies; not browser or camera evidence.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function body(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, name);
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function fixture() {
  const state = { session: { id: "synthetic-A" }, liveActive: true, liveGeneration: 1,
    liveSource: "CCAPI_RTP", activeLiveSource: "CCAPI_RTP", requestedFps: 15,
    previewInput: "CAMERA", localVideoSupport: { available: true }, busy: false,
    status: {}, frameTimes: [] };
  const requests = [], messages = [], pending = [];
  const context = vm.createContext({ state,
    ui: { fpsSelect: { value: "30" }, liveSourceSelect: { value: "CCAPI_JPEG_POLLING" },
      previewInputSelect: { value: "LOCAL_VIDEO" } },
    FEATURES: { LIVE_VIEW: "LIVE_VIEW", CAMERA_SLEEP: "CAMERA_SLEEP", SENSOR_CLEANING: "SENSOR_CLEANING" },
    localPreviewSelected: () => state.previewInput === "LOCAL_VIDEO",
    previewActive: () => state.liveActive || state.localVideoActive,
    cameraInteractionBusy: () => state.busy, bulbControlLocked: () => false, recordingRecoveryActive: () => false,
    featureSupported: () => true, clampFps: Number,
    liveCapabilities: () => ({ defaultSize: "MEDIUM" }),
    beginCameraInteraction: () => { state.busy = true; },
    stopLiveLoop: () => { state.liveActive = false; state.activeLiveSource = null; state.liveGeneration++; },
    stopLocalVideo() {}, startLocalVideo: async () => { state.localVideoActive = true; },
    renderAvailability() {}, renderLiveState() {}, renderFps() {}, renderFrameIndicator() {}, renderPreviewInput() {},
    syncLiveMagnificationFromCapabilities() {}, pollLiveView() {}, refreshRtpAudioStatus() {},
    stopEventLoop: async () => {}, startEventLoop() {}, refreshCapabilityEvidence: async () => {}, renderSession() {},
    resetSession: () => { state.session = null; state.liveViewStopOwner = null; },
    captureError: error => error, t: key => key, showToast: message => messages.push(message),
    setOperationState: message => messages.push(message),
    api: (url, options) => { const gate = deferred(); requests.push({ url, options }); pending.push(gate); return gate.promise; },
  });
  const names = ["stopLiveView", "startLiveView", "toggleLiveView", "changeFps", "changeLiveSource", "changePreviewInput",
    "sleepCamera", "cleanSensor"];
  if (/function liveViewStopRequired\(/.test(source)) names.push("liveViewStopRequired");
  vm.runInContext(names.map(body).join("\n"), context);
  return Object.assign(context, { requests, pending, messages });
}
async function turns() { for (let i = 0; i < 8; i++) await new Promise(setImmediate); }
for (const method of ["changeFps", "changeLiveSource", "changePreviewInput"]) {
  test(`${method} does not start another source after failed stop`, async () => {
    const f = fixture();
    const operation = f[method]();
    f.pending[0].reject(new Error("synthetic stop failed"));
    await turns();
    // Drain a forbidden start as well, so baseline failure does not leak a task.
    for (const gate of f.pending.slice(1)) gate.resolve({ active: false, liveViewStopUnconfirmed: false });
    await operation;
    assert.equal(f.requests.length, 1);
    assert.equal(f.state.previewInput, "CAMERA");
    assert.equal(f.state.localVideoActive, undefined);
  });
}
test("failed stop remains retryable using the same session and only Stop", async () => {
  const f = fixture();
  const first = f.stopLiveView();
  f.pending[0].reject(new Error("synthetic stop failed"));
  await first;
  const retry = f.toggleLiveView();
  assert.match(f.requests[1].url, /synthetic-A\/liveview\/stop$/);
  f.pending[1].resolve({ active: false, liveViewStopUnconfirmed: false });
  await retry;
  assert.equal(f.state.liveActive, false);
});
test("failed start retains a stop-only recovery path", async () => {
  const f = fixture(); f.state.liveActive = false;
  const start = f.startLiveView();
  f.pending[0].reject(new Error("synthetic lost start response"));
  await start;
  const retry = f.toggleLiveView();
  assert.match(f.requests[1].url, /liveview\/stop$/);
  f.pending[1].resolve({ active: false, liveViewStopUnconfirmed: false }); await retry;
});
for (const outcome of ["success", "failure"]) {
  test(`old stop ${outcome} cannot clear replacement state or feedback`, async () => {
    const f = fixture(); const stop = f.stopLiveView();
    const replacement = { id: "synthetic-A" }; // Reused wire ID is not ownership.
    f.state.session = replacement; f.state.liveActive = true; f.state.busy = true;
    f.state.liveViewStopOwner = { session: replacement }; f.messages.length = 0;
    if (outcome === "success") f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false });
    else f.pending[0].reject(new Error("old error"));
    await stop;
    assert.equal(f.state.busy, true);
    assert.equal(f.state.liveViewStopOwner.session, replacement);
    assert.deepEqual(f.messages, []);
  });
}
for (const method of ["changeFps", "changeLiveSource"]) {
  test(`${method} still restarts after confirmed stop`, async () => {
    const f = fixture(); const operation = f[method]();
    f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await turns();
    assert.equal(f.requests.length, 2);
    assert.match(f.requests[1].url, /liveview\/start$/);
    f.pending[1].resolve({ active: true, source: "CCAPI_JPEG_POLLING", requestedFps: 30 });
    await operation; assert.equal(f.state.liveActive, true);
  });
}
test("confirmed camera stop permits explicitly selected local preview", async () => {
  const f = fixture(); const operation = f.changePreviewInput();
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await operation;
  assert.equal(f.state.previewInput, "LOCAL_VIDEO");
  assert.equal(f.state.localVideoActive, true);
  assert.equal(f.requests.length, 1);
});
test("status-only stop responsibility routes toggle to Stop", async () => {
  const f = fixture(); f.state.liveActive = false;
  f.state.status = { liveViewStopUnconfirmed: true };
  const operation = f.toggleLiveView();
  assert.match(f.requests[0].url, /liveview\/stop$/);
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await operation;
  assert.equal(f.state.status.liveViewStopUnconfirmed, false);
});
for (const outcome of ["success", "failure"]) {
  test(`old start ${outcome} cannot change a replacement with reused ID`, async () => {
    const f = fixture(); f.state.liveActive = false; const operation = f.startLiveView();
    f.state.session = { id: "synthetic-A" }; f.state.liveActive = true; f.state.busy = true;
    f.state.liveViewStopOwner = { session: f.state.session }; f.messages.length = 0;
    if (outcome === "success") f.pending[0].resolve({ active: true, source: "CCAPI_RTP" });
    else f.pending[0].reject(new Error("old start"));
    await operation;
    assert.equal(f.state.liveActive, true); assert.equal(f.state.busy, true);
    assert.deepEqual(f.messages, []);
  });
  test(`older concurrent stop ${outcome} cannot settle a newer stop`, async () => {
    const f = fixture(); const first = f.stopLiveView(); const second = f.stopLiveView();
    f.messages.length = 0;
    if (outcome === "success") f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false });
    else f.pending[0].reject(new Error("old stop"));
    await first; assert.equal(f.state.busy, true); assert.deepEqual(f.messages, []);
    f.pending[1].reject(new Error("current stop failed")); await second;
    assert.equal(f.liveViewStopRequired(), true);
    assert.equal(f.state.busy, false);
  });
}
test("new start cannot dispatch while previous stop is unresolved", async () => {
  const f = fixture(); const stop = f.stopLiveView();
  f.pending[0].reject(new Error("stop failed")); await stop;
  await f.startLiveView(); assert.equal(f.requests.length, 1);
});
test("local receiver cleanup does not acknowledge camera stop", async () => {
  const f = fixture(); f.state.liveViewStopOwner = { session: f.state.session };
  assert.equal(await f.stopLiveView({ remote: false }), false);
  assert.equal(f.liveViewStopRequired(), true); assert.equal(f.requests.length, 0);
});
test("Stop retires a late Start response within the same session", async () => {
  const f = fixture(); f.state.liveActive = false;
  const start = f.startLiveView(); const stop = f.stopLiveView();
  f.messages.length = 0;
  f.pending[0].resolve({ active: true, source: "CCAPI_RTP" }); await start;
  assert.equal(f.state.liveActive, false); assert.equal(f.state.busy, true);
  assert.deepEqual(f.messages, []);
  f.pending[1].reject(new Error("stop unconfirmed")); await stop;
  assert.equal(f.liveViewStopRequired(), true);
});
test("failed frame cleanup preserves the primary error and a retry path", async () => {
  const f = fixture(); f.messages.push("original frame error");
  const stop = f.stopLiveView({ announce: false, preserveError: true });
  f.pending[0].reject(new Error("cleanup failed")); await stop;
  assert.deepEqual(f.messages, ["original frame error"]);
  assert.equal(f.liveViewStopRequired(), true);
});
test("known running legacy session can confirm its first stop", async () => {
  const f = fixture(); const stop = f.stopLiveView();
  f.pending[0].resolve({ active: false });
  assert.equal(await stop, true); assert.equal(f.liveViewStopRequired(), false);
});
for (const reply of [{ active: false }, { active: false, liveViewStopUnconfirmed: null },
  { active: false, liveViewStopUnconfirmed: true }, { active: true, liveViewStopUnconfirmed: false }, {}]) {
  test(`ambiguous start requires explicit stop proof: ${JSON.stringify(reply)}`, async () => {
    const f = fixture(); f.state.liveActive = false;
    const start = f.startLiveView(); f.pending[0].reject(new Error("lost ACK")); await start;
    const stop = f.stopLiveView(); f.pending[1].resolve(reply);
    assert.equal(await stop, false); assert.equal(f.liveViewStopRequired(), true);
    assert.equal(f.requests.length, 2);
  });
}
test("failed first legacy stop cannot accept a subsequent bare false", async () => {
  const f = fixture(); const first = f.stopLiveView();
  f.pending[0].reject(new Error("lost stop ACK")); await first;
  const second = f.stopLiveView(); f.pending[1].resolve({ active: false });
  assert.equal(await second, false); assert.equal(f.liveViewStopRequired(), true);
});
for (const method of ["sleepCamera", "cleanSensor"]) {
  test(`${method} cannot proceed when Live View stop is unconfirmed`, async () => {
    const f = fixture(); const operation = f[method]({}, false);
    assert.match(f.requests[0].url, /liveview\/stop$/);
    f.pending[0].reject(new Error("stop failed")); await operation;
    assert.equal(f.requests.length, 1); assert.equal(f.liveViewStopRequired(), true);
  });
}
test("non-poweroff cleaning resumes preview only after explicit stop and maintenance", async () => {
  const f = fixture(); const operation = f.cleanSensor({}, false);
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await turns();
  assert.match(f.requests[1].url, /maintenance\/sensor-cleaning$/);
  f.pending[1].resolve({}); await turns();
  assert.match(f.requests[2].url, /\/status$/);
  f.pending[2].resolve({}); await turns();
  assert.match(f.requests[3].url, /liveview\/start$/);
  f.pending[3].resolve({ active: true }); await operation;
  assert.equal(f.state.liveActive, true);
});
for (const reply of [null, {}, [], { active: false }, { active: "true" }]) {
  test(`malformed start cannot authorize legacy stop: ${JSON.stringify(reply)}`, async () => {
    const f = fixture(); f.state.liveActive = false;
    const start = f.startLiveView(); f.pending[0].resolve(reply); await start;
    assert.equal(f.state.liveActive, false); assert.equal(f.liveViewStopRequired(), true);
    const stop = f.stopLiveView(); f.pending[1].resolve({ active: false });
    assert.equal(await stop, false); assert.equal(f.liveViewStopRequired(), true);
  });
}
