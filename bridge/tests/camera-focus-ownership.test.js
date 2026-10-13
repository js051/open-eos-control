"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const cameraFocus = require("../open_eos_bridge/static/camera-focus.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  assert.notEqual(end, -1);
  return source.slice(start, start + 1 + end);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const info = () => ({ frames: [{ left: 0.25, top: 0.25, right: 0.75, bottom: 0.75, status: "FOCUSED", kind: "FACE" }],
  magnification: 1, imageAspectRatio: 1.5 });
function subject() {
  const requests = [], timers = new Map();
  let clock = 0, sequence = 0;
  const node = () => ({ hidden: true, style: {}, children: [], replaceChildren(...nodes) { this.children = nodes; } });
  const state = { session: { id: "synthetic-A" }, capabilities: { focusInfoSupported: true }, liveActive: true,
    livePollingSuspended: false, previewInput: "CAMERA", liveMagnification: 1, activeView: "live", liveObjectUrl: "synthetic-frame",
    liveGeneration: 1, focusInfoGeneration: 0, focusInfoRequest: null, focusInfoSnapshot: null, focusInfoNextAt: 0 };
  const ui = { liveImage: { hidden: false, naturalWidth: 600, naturalHeight: 400 }, cameraFocusOverlay: node(), cameraFocusLegend: node() };
  const document = { visibilityState: "visible", createElement: node };
  const sandbox = vm.createContext({ state, ui, requests, timers, document, cameraFocus, AbortController,
    performance: { now: () => clock }, advance: ms => { clock += ms; },
    localPreviewSelected: () => state.previewInput === "LOCAL_VIDEO",
    liveContentDisplayRect: () => ({ left: 20, top: 100, width: 600, height: 400 }),
    positionMonitorLayer: (element, rect) => Object.assign(element.style, rect),
    window: { setTimeout: (fn, ms) => { const id = ++sequence; timers.set(id, { fn, ms }); return id; },
      clearTimeout: id => timers.delete(id) },
    api: (url, options) => { const reply = deferred(); requests.push({ url, options, ...reply }); return reply.promise; },
  });
  vm.runInContext(["cameraFocusInfoEligible", "clearCameraFocusFrames", "cancelCameraFocusInfo", "pollCameraFocusInfo", "renderCameraFocusInfo"].map(productionFunction).join("\n"), sandbox);
  return sandbox;
}
async function accept(s, data = info()) {
  const run = s.pollCameraFocusInfo(); s.requests.at(-1).resolve(data); await run;
}
test("camera metadata uses the actual letterboxed image rect and independent overlay", async () => {
  const s = subject(); await accept(s);
  assert.deepEqual(s.ui.cameraFocusOverlay.style, { left: 20, top: 100, width: 600, height: 400 });
  const frame = s.ui.cameraFocusOverlay.children[0];
  assert.equal(frame.style.left, "25%"); assert.equal(frame.style.width, "50%");
  assert.equal(frame.className, "camera-focus-frame status-focused kind-face");
  assert.equal(s.ui.cameraFocusLegend.hidden, false);
  assert.equal(s.requests[0].options.responseType, "focusInfo");
});
test("max one in-flight telemetry request and modest frame-driven throttle", async () => {
  const s = subject(); const first = s.pollCameraFocusInfo(); await s.pollCameraFocusInfo(); assert.equal(s.requests.length, 1);
  s.requests[0].resolve(info()); await first; await s.pollCameraFocusInfo(); assert.equal(s.requests.length, 1);
  s.advance(250); const next = s.pollCameraFocusInfo(); assert.equal(s.requests.length, 2); s.requests[1].resolve(null); await next;
});
test("null telemetry clears previous frames and backs off for two seconds", async () => {
  const s = subject(); await accept(s); s.advance(250); await accept(s, null);
  assert.equal(s.ui.cameraFocusOverlay.hidden, true); assert.equal(s.state.focusInfoSnapshot, null);
  s.advance(1999); await s.pollCameraFocusInfo(); assert.equal(s.requests.length, 2);
  s.advance(1); const next = s.pollCameraFocusInfo(); assert.equal(s.requests.length, 3); s.requests[2].resolve(null); await next;
});
test("telemetry failure clears frames and backs off one second without camera commands", async () => {
  const s = subject(); await accept(s); s.advance(250); const next = s.pollCameraFocusInfo();
  s.requests[1].reject(new Error("lost")); await next; assert.equal(s.ui.cameraFocusOverlay.hidden, true);
  s.advance(999); await s.pollCameraFocusInfo(); assert.equal(s.requests.length, 2);
  assert.ok(s.requests.every(request => !request.options.method));
});
test("focus frames expire by request age within one second, even without another image", async () => {
  const s = subject(); const request = s.pollCameraFocusInfo(); s.advance(400); s.requests[0].resolve(info()); await request;
  const expiry = [...s.timers.values()][0]; assert.equal(expiry.ms, 600);
  s.advance(600); expiry.fn(); assert.equal(s.ui.cameraFocusOverlay.hidden, true);
  assert.equal(s.state.focusInfoSnapshot, null);
});
test("late response at one second is never displayed", async () => {
  const s = subject(); const request = s.pollCameraFocusInfo(); s.advance(1000); s.requests[0].resolve(info()); await request;
  assert.equal(s.ui.cameraFocusOverlay.hidden, true); assert.equal(s.state.focusInfoSnapshot, null);
});
for (const change of [s => { s.state.session = { id: "synthetic-A" }; }, s => { s.state.liveGeneration++; },
  s => { s.state.liveActive = false; }, s => { s.state.busy = true; }, s => { s.state.livePollingSuspended = true; },
  s => { s.state.previewInput = "LOCAL_VIDEO"; }, s => { s.document.visibilityState = "hidden"; },
  s => { s.state.capabilities = {}; }, s => { s.state.liveMagnification = 5; },
  s => { s.state.activeView = "media"; }, s => { s.ui.liveImage.naturalWidth = 640; }]) {
  test(`retired telemetry cannot draw after ${change.toString()}`, async () => {
    const s = subject(); const request = s.pollCameraFocusInfo(); change(s); s.requests[0].resolve(info()); await request;
    assert.equal(s.ui.cameraFocusOverlay.hidden, true); assert.equal(s.state.focusInfoSnapshot, null);
  });
}
test("cancel clears frames, aborts old read, and retains reservation until transport settles", async () => {
  const s = subject(); await accept(s); s.advance(250); const old = s.pollCameraFocusInfo();
  s.cancelCameraFocusInfo(); assert.equal(s.ui.cameraFocusOverlay.hidden, true);
  assert.equal(s.requests[1].options.signal.aborted, true);
  await s.pollCameraFocusInfo(); assert.equal(s.requests.length, 2);
  s.requests[1].resolve(info()); await old; assert.equal(s.state.focusInfoSnapshot, null);
  const next = s.pollCameraFocusInfo(); assert.equal(s.requests.length, 3); s.requests[2].resolve(info()); await next;
  assert.equal(s.ui.cameraFocusOverlay.hidden, false);
});
test("old expiry does not clear a newer snapshot", async () => {
  const s = subject(); await accept(s); const oldExpiry = [...s.timers.values()][0].fn;
  s.advance(250); await accept(s); oldExpiry(); assert.equal(s.ui.cameraFocusOverlay.hidden, false);
});
test("zoomed, wrong aspect or unknown payload cannot paint a success frame", async () => {
  for (const data of [{ ...info(), magnification: 5 }, { ...info(), imageAspectRatio: 2 }, {}, null]) {
    const s = subject(); await accept(s, data); assert.equal(s.ui.cameraFocusOverlay.hidden, true);
  }
});
test("old Bridge, UVC, hidden or paused preview makes no telemetry request", async () => {
  const s = subject(); s.state.capabilities = {}; await s.pollCameraFocusInfo();
  s.state.capabilities.focusInfoSupported = true; s.state.previewInput = "LOCAL_VIDEO"; await s.pollCameraFocusInfo();
  s.state.previewInput = "CAMERA"; s.document.visibilityState = "hidden"; await s.pollCameraFocusInfo();
  s.document.visibilityState = "visible"; s.state.livePollingSuspended = true; await s.pollCameraFocusInfo();
  assert.equal(s.requests.length, 0);
});
test("layout change clears stale dimensions before rendering", async () => {
  const s = subject(); await accept(s); s.ui.liveImage.naturalWidth = 640;
  s.renderCameraFocusInfo(s.liveContentDisplayRect()); assert.equal(s.ui.cameraFocusOverlay.hidden, true);
});
test("timeout cancels read and clears focus without overlapping another request", async () => {
  const s = subject(); const request = s.pollCameraFocusInfo(); [...s.timers.values()][0].fn();
  assert.equal(s.requests[0].options.signal.aborted, true);
  s.advance(1000); await s.pollCameraFocusInfo(); assert.equal(s.requests.length, 1);
  s.requests[0].resolve(info()); await request; assert.equal(s.ui.cameraFocusOverlay.hidden, true);
});
