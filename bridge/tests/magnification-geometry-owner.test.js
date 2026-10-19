"use strict";
// Production handlers with controlled replies/frames. Not browser or physical-camera evidence.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function production(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.ok(start >= 0, name);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  return source.slice(start, start + 1 + end);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function setup() {
  const state = { session: { id: "SYNTHETIC-A" }, liveActive: true, liveGeneration: 1,
    refreshGeneration: 0, liveMagnification: 1, captureMode: "photo", busy: false,
    frameTimes: [], requestedFps: 30, liveObjectUrl: "old-frame", status: { owner: "A" } };
  state.liveViewStopOwner = { session: state.session, confirmedStart: true };
  const calls = [], pending = [], messages = [], resumed = [], removed = [], writes = [];
  const ui = { liveImage: { hidden: false, attributes: [], replaceWith: image => writes.push(image), removeAttribute() {} },
    viewfinderPlaceholder: { hidden: true }, focusReticle: { style: {}, hidden: true } };
  const context = vm.createContext({ state, ui, AbortController, setTimeout, clearTimeout, Date,
    performance: { now: () => 100 }, window: { setTimeout: () => 1, clearTimeout() {} },
    FEATURES: { LIVE_VIEW_MAGNIFICATION: "LIVE_VIEW_MAGNIFICATION" }, ApiError: Error,
    localPreviewSelected: () => false, cameraInteractionBusy: () => state.busy,
    bulbControlLocked: () => false, featureSupported: () => true, effectiveTapAction: () => "focus",
    liveMagnifications: () => [1, 5, 10], t: key => key,
    cancelCameraFocusInfo: () => { state.focusInfoSnapshot = null; },
    clearMonitoringLayers() {}, renderFrameIndicator() {}, maintenancePaused: () => false,
    renderAvailability() {}, renderLiveMagnification() {}, renderSession() {},
    setOperationState: (...args) => messages.push(args), showToast: (...args) => messages.push(args),
    captureError: error => error, pollLiveView: generation => resumed.push(generation),
    api: (url, options) => { calls.push({ url, options }); const d = deferred(); pending.push(d); return d.promise; },
    releaseObjectUrl: url => removed.push(url), renderMonitoringFrame() {}, pollCameraFocusInfo() {},
    liveViewLifecycle: require("../open_eos_bridge/static/liveview-lifecycle.js"), sleep: async () => {},
  });
  vm.runInContext(["setLiveViewMagnification", "nextLiveMagnification", "beginCameraInteraction", "pauseLivePolling",
    "resumeLivePolling", "tapFocus", "adoptCameraStatus"].map(production).join("\n"), context);
  return Object.assign(context, { calls, pending, messages, resumed, removed, writes });
}
const point = { x: .5, y: .5, displayX: 10, displayY: 10 };

test("magnification dispatch retires frame generation and keeps coordinates blocked after ACK", async () => {
  const h = setup(); const oldGeneration = h.state.liveGeneration;
  const abort = new AbortController(); h.state.liveFrameAbortController = abort;
  h.state.liveFrameSample = { old: true }; h.state.focusInfoSnapshot = { old: true };
  const run = h.setLiveViewMagnification();
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0].options.json.value, 5);
  assert.equal(abort.signal.aborted, true); assert.ok(h.state.liveGeneration > oldGeneration);
  assert.equal(h.state.liveFrameSample, null); assert.equal(h.state.focusInfoSnapshot, null);
  await h.tapFocus(point); assert.equal(h.calls.length, 1);
  h.pending[0].resolve({ accepted: true, value: 5 }); await run;
  assert.equal(h.state.liveMagnification, 5); assert.equal(h.state.busy, false);
  assert.equal(h.state.liveMagnificationFramePending, true); assert.equal(h.resumed.length, 1);
  await h.tapFocus(point); assert.equal(h.calls.length, 1, "ACK alone must not admit a coordinate mutation");
});

for (const result of [{ accepted: false, value: 5 }, { accepted: true, value: 10 },
  { accepted: "true", value: 5 }, { accepted: true, value: "5" }, null]) {
  test(`invalid ACK stays unconfirmed: ${JSON.stringify(result)}`, async () => {
    const h = setup(); const run = h.setLiveViewMagnification(); h.pending[0].resolve(result); await run;
    assert.equal(h.state.liveMagnification, 1); assert.equal(h.state.liveMagnificationFramePending, true);
    assert.equal(h.messages.some(row => row[0] === "liveViewMagnificationChanged"), false);
    assert.equal(h.resumed.length, 1); await h.tapFocus(point); assert.equal(h.calls.length, 1);
  });
}
for (const outcome of ["success", "error", "cancel"]) {
  for (const replacement of ["session", "source", "interaction", "stop"]) {
    test(`retired ${replacement} rejects late magnification ${outcome}`, async () => {
      const h = setup(); const run = h.setLiveViewMagnification();
      if (replacement === "session") h.state.session = { id: "SYNTHETIC-A" };
      if (replacement === "source") h.state.liveViewStopOwner = { session: h.state.session, confirmedStart: true };
      if (replacement === "interaction") h.state.refreshGeneration++;
      if (replacement === "stop") { h.state.liveGeneration++; h.state.liveActive = false; }
      h.state.busy = true; h.state.liveMagnification = 10; h.messages.length = 0;
      if (outcome === "success") h.pending[0].resolve({ accepted: true, value: 5 });
      else h.pending[0].reject(Object.assign(Error("old failure"), { name: outcome === "cancel" ? "AbortError" : "Error" }));
      await run;
      assert.equal(h.state.liveMagnification, 10); assert.equal(h.state.busy, true);
      assert.deepEqual(h.messages, []); assert.deepEqual(h.resumed, []);
    });
  }
}
for (const name of ["Error", "AbortError"]) {
  test(`current ${name} resumes fresh reads but never restores old frame admission`, async () => {
    const h = setup(); const run = h.setLiveViewMagnification();
    h.pending[0].reject(Object.assign(Error("uncertain"), { name })); await run;
    assert.equal(h.state.liveMagnification, 1); assert.equal(h.state.liveMagnificationFramePending, true);
    assert.equal(h.state.busy, false); assert.equal(h.resumed.length, 1);
    assert.equal(h.messages.some(row => row[0] === "liveViewMagnificationChanged"), false);
    await h.tapFocus(point); assert.equal(h.calls.length, 1);
  });
}

for (const action of ["focus", "whiteBalance"]) test(`${action}: current decoded frame reopens control; late pre-change frame cannot`, async () => {
  const h = setup(), frames = [];
  h.effectiveTapAction = () => action;
  h.readDecodedLiveFrame = () => { const d = deferred(); frames.push(d); return d.promise; };
  h.expireLiveFrame = () => {};
  h.renderFrameIndicator = () => { if (h.state.liveFrameSample) h.state.livePollingSuspended = true; };
  vm.runInContext(production("pollLiveView"), h);
  const old = h.pollLiveView(1);
  const operation = h.setLiveViewMagnification();
  // Keep explicit control of the post-command reader instead of auto-starting it.
  const polling = h.pollLiveView; h.pollLiveView = generation => h.resumed.push(generation);
  h.pending[0].resolve({ accepted: true, value: 5 }); await operation;
  const image = () => ({ attributes: [], setAttribute() {}, removeAttribute() { this.removed = true; } });
  const stale = image();
  frames[0].resolve({ image: stale, url: "old-late", blob: { size: 6, type: "image/jpeg" } }); await old;
  assert.equal(stale.removed, true); assert.equal(h.state.liveMagnificationFramePending, true);
  assert.equal(h.writes.length, 0); assert.deepEqual(h.removed, ["old-late"]);
  const fresh = polling(h.state.liveGeneration), current = image();
  frames[1].resolve({ image: current, url: "fresh", blob: { size: 6, type: "image/jpeg" } }); await fresh;
  assert.equal(h.state.liveMagnificationFramePending, false); assert.equal(h.writes[0], current);
  h.state.livePollingSuspended = false;
  const tap = h.tapFocus(point); assert.equal(h.calls.length, 2);
  assert.ok(h.calls[1].url.endsWith(action === "focus" ? "/focus/tap" : "/whitebalance/click"));
  h.pending[1].resolve({ accepted: true }); await tap;
});

test("decoded frame replacement does not replay serialized inline style under strict CSP", async () => {
  const h = setup(), copied = [];
  h.ui.liveImage.attributes = [
    { name: "id", value: "live-image" }, { name: "class", value: "live-image" },
    { name: "aria-label", value: "Synthetic live frame" },
    { name: "src", value: "old-frame" },
    { name: "style", value: "left: 12px; top: 24px; width: 100px; height: 80px;" },
  ];
  const image = { style: {}, setAttribute(name, value) { copied.push([name, value]); }, removeAttribute() {} };
  h.readDecodedLiveFrame = async () => ({ image, url: "fresh-frame", blob: { size: 6, type: "image/jpeg" } });
  h.expireLiveFrame = () => {};
  vm.runInContext(production("positionMonitorLayer"), h);
  h.renderMonitoringFrame = () => h.positionMonitorLayer(h.ui.liveImage, { left: 2, top: 4, width: 60, height: 40 });
  h.renderFrameIndicator = () => { h.state.livePollingSuspended = true; };
  vm.runInContext(production("pollLiveView"), h);
  await h.pollLiveView(1);
  assert.deepEqual(copied, [["id", "live-image"], ["class", "live-image"], ["aria-label", "Synthetic live frame"]]);
  assert.equal(h.writes[0], image);
  assert.equal(h.state.liveDecodedImage, image);
  assert.deepEqual(image.style, { left: "2px", top: "4px", width: "60px", height: "40px" });
});
