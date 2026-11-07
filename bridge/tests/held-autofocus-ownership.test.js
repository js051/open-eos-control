"use strict";
// Run production handlers with controlled HTTP completions and virtual clocks.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
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
const flush = async () => { for (let n = 0; n < 12; n++) await Promise.resolve(); };
function subject() {
  const requests = [], timers = new Map(), feedback = [];
  let sequence = 0;
  const node = () => ({ hidden: false, disabled: false, textContent: "", classList: { toggle() {} } });
  const state = { session: { id: "synthetic-A" }, token: "synthetic-token-A", activeView: "live", busy: false,
    refreshGeneration: 0, capabilities: { heldAutofocusSupported: true }, status: {}, heldAutofocusOwner: null };
  const ui = { heldAutofocusSection: node(), heldAutofocusStart: node(), heldAutofocusStop: node(), heldAutofocusStatus: node() };
  const sandbox = vm.createContext({ state, ui, requests, timers, feedback, AbortController,
    document: { visibilityState: "visible" }, crypto: { randomUUID: () => `synthetic-${++sequence}` },
    FEATURES: { AUTOFOCUS: "AUTOFOCUS" }, featureSupported: () => true,
    cameraInteractionBusy: () => state.busy || Boolean(state.heldAutofocusOwner),
    bulbControlLocked: () => Boolean(state.shutterReleaseUnconfirmed),
    beginCameraInteraction: () => { state.refreshGeneration++; state.busy = true; },
    renderAvailability: () => sandbox.renderHeldAutofocus(), renderSession: () => sandbox.renderHeldAutofocus(),
    setOperationState: (...args) => feedback.push(args), t: key => key,
    window: { setTimeout: (fn, ms) => { const id = ++sequence; timers.set(id, { fn, ms }); return id; },
      clearTimeout: id => timers.delete(id) },
    api: (url, options) => { const reply = deferred(); requests.push({ url, options, ...reply }); return reply.promise; },
  });
  vm.runInContext(["heldAutofocusSupported", "renderHeldAutofocus", "heldAutofocusCommand", "startHeldAutofocus",
    "stopHeldAutofocus", "releaseHeldAutofocusForLifecycle"].map(productionFunction).join("\n"), sandbox);
  return sandbox;
}
function respond(s, index, active, patch = {}) {
  const r = s.requests[index]; r.resolve({ ownerId: r.options.json.ownerId, active, stopUnconfirmed: false, ...patch });
}
function replace(s) {
  s.state.session = { id: "synthetic-A" }; // Reused backend ID is intentionally not the same owner.
  s.state.token = "synthetic-token-B";
  s.state.heldAutofocusOwner = null;
  s.state.busy = true;
}
test("Start has one owner; double start is suppressed; accessible stop ignores busy", async () => {
  const s = subject(); const first = s.startHeldAutofocus(); await s.startHeldAutofocus();
  assert.equal(s.requests.length, 1); assert.equal(s.ui.heldAutofocusStop.disabled, false);
  respond(s, 0, true); await first;
  assert.equal(s.state.busy, false); assert.ok(s.state.heldAutofocusOwner);
  assert.equal(s.ui.heldAutofocusStatus.textContent, "heldAutofocusActive");
  assert.equal(s.ui.heldAutofocusStart.disabled, true);
});
test("Stop before Start settles queues once and uses identical owner and session", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); const stop = s.stopHeldAutofocus();
  const duplicate = s.stopHeldAutofocus(); await flush(); assert.equal(s.requests.length, 1);
  respond(s, 0, true); await start; await flush(); assert.equal(s.requests.length, 2);
  assert.equal(s.requests[1].options.json.ownerId, s.requests[0].options.json.ownerId);
  respond(s, 1, false); assert.equal(await stop, true); assert.equal(await duplicate, true);
  assert.equal(s.state.heldAutofocusOwner, null); assert.equal(s.requests.length, 2);
});
for (const response of [null, {}, { active: true, stopUnconfirmed: false, ownerId: "wrong" },
  { active: null, stopUnconfirmed: true }, { active: true }]) {
  test(`unknown Start keeps Stop responsibility: ${JSON.stringify(response)}`, async () => {
    const s = subject(); const start = s.startHeldAutofocus(); s.requests[0].resolve(response); await flush();
    assert.equal(s.requests.length, 2); assert.match(s.requests[1].url, /\/stop$/);
    s.requests[1].reject(new Error("cleanup also uncertain")); await start;
    assert.equal(s.state.heldAutofocusOwner.stopUnconfirmed, true);
    assert.equal(s.ui.heldAutofocusStop.disabled, false); assert.equal(s.ui.heldAutofocusStart.disabled, true);
    assert.equal(s.requests.length, 2);
  });
}
test("lost Start ACK after camera activation immediately releases once", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); s.requests[0].reject(new Error("lost")); await flush();
  assert.equal(s.requests.length, 2); assert.match(s.requests[1].url, /\/stop$/);
  assert.equal(s.requests[1].options.json.ownerId, s.requests[0].options.json.ownerId);
  respond(s, 1, false); await start;
  assert.equal(s.state.heldAutofocusOwner, null); assert.equal(s.timers.size, 0);
  s.releaseHeldAutofocusForLifecycle(); await flush(); assert.equal(s.requests.length, 2);
});
test("unknown Stop preserves Stop; explicit retry is allowed, lifecycle never loops", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); respond(s, 0, true); await start;
  s.releaseHeldAutofocusForLifecycle(); await flush(); s.requests[1].reject(new Error("lost")); await flush();
  s.releaseHeldAutofocusForLifecycle(); s.releaseHeldAutofocusForLifecycle(); await flush();
  assert.equal(s.requests.length, 2); assert.equal(s.ui.heldAutofocusStop.disabled, false);
  assert.equal(s.ui.heldAutofocusStatus.textContent, "heldAutofocusUnconfirmed");
  const retry = s.stopHeldAutofocus(); await flush(); respond(s, 2, false); assert.equal(await retry, true);
});
for (const patch of [{ active: null }, { stopUnconfirmed: true }, { ownerId: "different" }, { stopUnconfirmed: null }]) {
  test(`malformed Stop cannot release saved responsibility: ${JSON.stringify(patch)}`, async () => {
    const s = subject(); const start = s.startHeldAutofocus(); respond(s, 0, true); await start;
    const stop = s.stopHeldAutofocus(); await flush(); respond(s, 1, false, patch);
    assert.equal(await stop, false); assert.ok(s.state.heldAutofocusOwner);
    assert.equal(s.ui.heldAutofocusStop.disabled, false);
  });
}
test("bounded timeout releases the queue even if the transport ignores abort", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); const stop = s.stopHeldAutofocus();
  [...s.timers.values()].find(timer => timer.ms === 8000).fn(); await start; await flush();
  assert.equal(s.requests[0].options.signal.aborted, true); assert.equal(s.requests.length, 2);
  respond(s, 1, false); assert.equal(await stop, true);
  respond(s, 0, true); await flush(); assert.equal(s.state.heldAutofocusOwner, null);
});
test("thirty-second watchdog attempts exactly one release and never presses again", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); respond(s, 0, true); await start;
  [...s.timers.values()].find(timer => timer.ms === 30000).fn(); await flush();
  assert.match(s.requests[1].url, /\/stop$/); s.requests[1].reject(new Error("lost")); await flush();
  assert.equal(s.timers.size, 0); assert.equal(s.requests.length, 2); assert.ok(s.state.heldAutofocusOwner);
});
test("retired owner Stop uses original token and cannot unlock replacement", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); const old = s.state.heldAutofocusOwner;
  const stop = s.stopHeldAutofocus({ owner: old, automatic: true, keepalive: true }); replace(s);
  respond(s, 0, true); await start; await flush();
  assert.equal(s.requests[1].options.authToken, "synthetic-token-A"); assert.equal(s.requests[1].options.keepalive, true);
  respond(s, 1, false); await stop; assert.equal(s.state.busy, true); assert.equal(s.feedback.length, 0);
});
test("retired owner release error leaves only previous-camera warning", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); respond(s, 0, true); await start;
  const stop = s.stopHeldAutofocus(); await flush(); replace(s); s.requests[1].reject(new Error("old stop")); await stop;
  assert.equal(s.state.shutterDisconnectWarning, true); assert.equal(s.state.busy, true);
  assert.equal(s.state.shutterReleaseUnconfirmed, undefined); assert.equal(s.feedback.length, 0);
});
test("Stop is reachable after capability loss and outside the live tab", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); respond(s, 0, true); await start;
  s.state.capabilities = {}; s.state.activeView = "media"; s.state.busy = true; s.state.shutterReleaseUnconfirmed = true;
  s.renderHeldAutofocus(); assert.equal(s.ui.heldAutofocusSection.hidden, false); assert.equal(s.ui.heldAutofocusStop.disabled, false);
  const stop = s.stopHeldAutofocus(); await flush(); respond(s, 1, false); await stop;
  assert.equal(s.state.busy, true);
});
test("older Bridge, background and media tab never admit a held press", async () => {
  const s = subject(); s.state.capabilities = {}; await s.startHeldAutofocus();
  s.state.capabilities.heldAutofocusSupported = true; s.document.visibilityState = "hidden"; await s.startHeldAutofocus();
  s.document.visibilityState = "visible"; s.state.activeView = "media"; await s.startHeldAutofocus();
  assert.equal(s.requests.length, 0);
});
test("completed owner cannot resend Stop or mutate a new held owner", async () => {
  const s = subject(); const start = s.startHeldAutofocus(); const old = s.state.heldAutofocusOwner;
  respond(s, 0, true); await start; const stop = s.stopHeldAutofocus(); await flush(); respond(s, 1, false); await stop;
  const next = s.startHeldAutofocus(); respond(s, 2, true); await next;
  await s.stopHeldAutofocus({ owner: old }); assert.equal(s.requests.length, 3);
  assert.notEqual(s.state.heldAutofocusOwner.ownerId, old.ownerId);
});
test("HTML keeps held Stop outside all hideable view panels and uses real buttons", () => {
  const html = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/index.html"), "utf8");
  assert.ok(html.indexOf('id="held-autofocus-stop"') < html.indexOf('id="live-panel"'));
  assert.match(html, /<button id="held-autofocus-stop"[^>]*type="button"[^>]*aria-describedby=/);
  assert.match(html, /id="held-autofocus-status"[^>]*role="status"[^>]*aria-live="polite"/);
  assert.match(source, /visibilitychange[\s\S]*releaseHeldAutofocusForLifecycle\(\)/);
});

test("status refresh cannot start during held ownership or relatch after exact Stop", async () => {
  const s = subject();
  vm.runInContext(productionFunction("refreshSession"), s);
  const oldRefresh = s.refreshSession({ quiet: true }); assert.equal(s.requests.length, 3);
  const start = s.startHeldAutofocus(); respond(s, 3, true); await start;
  assert.equal(await s.refreshSession({ quiet: true }), false); assert.equal(s.requests.length, 4);
  const stop = s.stopHeldAutofocus(); await flush(); respond(s, 4, false); await stop;
  s.requests[0].resolve({}); s.requests[1].resolve({ shutterReleaseUnconfirmed: true }); s.requests[2].resolve({});
  assert.equal(await oldRefresh, false);
  assert.equal(s.state.status.shutterReleaseUnconfirmed, false);
  assert.equal(s.state.heldAutofocusOwner, null);
});

function withDisconnect(s) {
  Object.assign(s, { stopMediaAutoImport() {}, cancelMediaDownload() {}, stopLiveLoop() {}, stopLocalVideo() {},
    stopEventLoop: async () => {}, captureError: error => error, showToast() {},
    resetSession: () => { s.state.session = null; s.state.heldAutofocusOwner = null; s.state.busy = false; } });
  s.ui.connectionError = {};
  vm.runInContext(productionFunction("disconnectCamera"), s);
  return s;
}
test("disconnect waits for bounded exact-owner release before deleting the original session", async () => {
  const s = withDisconnect(subject()); const start = s.startHeldAutofocus();
  const disconnect = s.disconnectCamera(); await flush(); assert.equal(s.requests.length, 1);
  respond(s, 0, true); await start; await flush(); assert.match(s.requests[1].url, /\/stop$/);
  respond(s, 1, false); await flush(); assert.equal(s.requests[2].options.method, "DELETE");
  assert.equal(s.requests[2].options.authToken, "synthetic-token-A");
  s.requests[2].resolve(null); await disconnect;
  assert.equal(s.state.session, null); assert.notEqual(s.state.shutterDisconnectWarning, true);
});
test("disconnect success cannot erase an unresolved held release warning", async () => {
  const s = withDisconnect(subject()); const start = s.startHeldAutofocus(); respond(s, 0, true); await start;
  const disconnect = s.disconnectCamera(); await flush(); s.requests[1].reject(new Error("uncertain stop")); await flush();
  s.requests[2].resolve(null); await disconnect; assert.equal(s.state.shutterDisconnectWarning, true);
});
test("disconnect cannot reset a replacement session with the same wire ID", async () => {
  const s = withDisconnect(subject()); const start = s.startHeldAutofocus(); respond(s, 0, true); await start;
  const disconnect = s.disconnectCamera(); await flush(); respond(s, 1, false); await flush();
  const replacement = { id: "synthetic-A" }; s.state.session = replacement; s.state.busy = true;
  s.requests[2].resolve(null); await disconnect;
  assert.equal(s.state.session, replacement); assert.equal(s.state.busy, true);
});
test("every new camera interaction retires existing focus telemetry before commands", () => {
  const s = subject(); let cancellations = 0;
  s.state.focusInfoSnapshot = { info: {} }; s.cancelCameraFocusInfo = () => { cancellations++; };
  vm.runInContext(productionFunction("beginCameraInteraction"), s); s.beginCameraInteraction();
  assert.equal(cancellations, 1); assert.equal(s.state.busy, true);
});
