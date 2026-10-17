"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const test = require("node:test");
const app = fs.readFileSync(require("node:path").join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(app);
  assert.ok(match, name);
  return app.slice(match.index, app.indexOf("\n  }", match.index) + 4);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
}
function harness() {
  const enumerations = [], messages = [], renders = [];
  const listeners = {};
  const track = {muted: false, addEventListener(name, callback) {listeners[name] = callback;}};
  const stream = {stopped: false};
  const state = {session: {id: "synthetic"}, localVideoSupport: {available: true}, localVideoGeneration: 0};
  const noop = () => {};
  const context = {state, navigator: {mediaDevices: {}}, Date,
    ui: {localVideo: {play: async () => {}, pause() {}, srcObject: null}},
    localVideo: {start: async () => ({stream, track, settings: {}}), stop: value => {if (value) value.stopped = true;},
      enumerateInputs: () => {const pending = deferred(); enumerations.push(pending); return pending.promise;}, errorCode: () => "synthetic"},
    localPreviewSelected: () => true, shutterReleaseUnconfirmed: () => false,
    t: key => key, setOperationState: value => messages.push(value), showToast: value => messages.push(value),
    renderLocalVideoDevices: () => renders.push(state.localVideoInputs), renderDiagnostics: noop,
    renderAvailability: noop, renderLiveState: noop, scheduleLocalVideoFrame: noop,
    cancelLocalVideoFrame: noop, clearMonitoringLayers: noop, captureLocalVideoError: error => error};
  vm.createContext(context);
  vm.runInContext(["refreshLocalVideoInputs", "startLocalVideo", "stopLocalVideo", "handleLocalVideoEnded"].map(extract).join("\n"), context);
  return {...context, context, enumerations, messages, renders, stream, listeners};
}
const tick = () => new Promise(resolve => setImmediate(resolve));
for (const action of ["stop", "disconnect", "ended", "replacement"]) {
  test(`late device enumeration cannot announce local preview started after ${action}`, async () => {
    const h = harness();
    const start = h.startLocalVideo();
    await tick();
    assert.equal(h.state.localVideoActive, true);
    assert.equal(h.state.localVideoBusy, false, "optional device enumeration must not lock Stop");
    assert.equal(h.enumerations.length, 1);
    if (action === "ended") h.listeners.ended();
    else h.stopLocalVideo();
    if (action === "disconnect") h.state.session = null;
    if (action === "replacement") {h.state.session = {id: "replacement"}; h.setOperationState("new-operation");}
    const expected = h.messages.slice();
    h.enumerations[0].resolve([]);
    await start;
    assert.deepEqual(h.messages, expected);
    assert.equal(h.stream.stopped, true);
    assert.equal(h.state.localVideoActive, false);
  });
}
test("older device enumeration cannot replace a newer device list", async () => {
  const h = harness();
  const old = h.refreshLocalVideoInputs();
  const current = h.refreshLocalVideoInputs();
  h.enumerations[1].resolve(["new-device"]);
  await current;
  h.enumerations[0].resolve(["removed-device"]);
  await old;
  assert.deepEqual(h.state.localVideoInputs, ["new-device"]);
  assert.equal(h.renders.length, 1);
});
test("older device enumeration failure cannot overwrite newer success", async () => {
  const h = harness();
  const old = h.refreshLocalVideoInputs({quiet: false});
  const current = h.refreshLocalVideoInputs();
  h.enumerations[1].resolve(["new-device"]);
  await current;
  h.enumerations[0].reject(new Error("old failure"));
  await old;
  assert.equal(h.state.localVideoError, undefined);
  assert.deepEqual(h.messages, []);
});
test("current successful preview still announces completion", async () => {
  const h = harness();
  const start = h.startLocalVideo();
  await tick();
  assert.deepEqual(h.messages, ["busy", "localVideoStarted", "localVideoStarted"],
    "Start feedback must not wait for optional enumeration");
  h.enumerations[0].resolve([]);
  await start;
  assert.deepEqual(h.messages, ["busy", "localVideoStarted", "localVideoStarted"]);
  assert.equal(h.state.localVideoBusy, false);
});
test("old enumeration completion cannot unlock a replacement start", async () => {
  const h = harness();
  const old = h.startLocalVideo();
  await tick();
  h.stopLocalVideo();
  const pendingStart = deferred();
  h.localVideo.start = () => pendingStart.promise;
  const replacement = h.startLocalVideo();
  assert.equal(h.state.localVideoBusy, true);
  h.enumerations[0].resolve([]);
  await old;
  assert.equal(h.state.localVideoBusy, true);
  assert.equal(h.messages.at(-1), "busy");
  const replacementStream = {stopped: false};
  pendingStart.resolve({stream: replacementStream, track: {muted: false, addEventListener() {}}, settings: {}});
  await tick();
  assert.equal(h.state.localVideoStream, replacementStream);
  assert.equal(replacementStream.stopped, false);
  assert.equal(h.state.localVideoBusy, false);
  h.enumerations[1].resolve([]);
  await replacement;
  assert.equal(replacementStream.stopped, false);
  assert.equal(h.messages.at(-1), "localVideoStarted");
});

function initializeHarness({health = {authRequired: false, engines: {libgphoto2: {available: true}}}, healthGate = null} = {}) {
  const h = harness(), calls = [];
  h.state.session = null;
  h.state.health = null;
  Object.assign(h.ui, {ccapiUrlInput: {}, ccapiUsernameInput: {}});
  Object.assign(h.context, {
    window: {}, CCAPI_URL_KEY: "synthetic-url", CCAPI_USERNAME_KEY: "synthetic-user",
    readCameraPreference: (_, fallback = "") => fallback,
    bindEvents: () => calls.push("bind"), renderMonitoringControls() {}, applyLanguage() {},
    connectionGuidance: require("../open_eos_bridge/static/connection-guidance.js"),
    refreshHealth: async () => {
      calls.push("health");
      if (healthGate) await healthGate.promise;
      h.state.health = health;
      h.state.healthFailed = !health;
    },
    scanCameras: async () => { calls.push("scan"); },
  });
  vm.runInContext(extract("initialize"), h.context);
  return {...h, initialize: h.context.initialize, calls};
}

test("initialize reaches health and initial scan while optional enumeration never settles", async () => {
  const h = initializeHarness();
  let finished = false;
  const pending = h.initialize().then(() => { finished = true; });
  await tick();
  assert.deepEqual(h.calls, ["bind", "health", "scan"]);
  assert.equal(finished, true, "optional input discovery cannot block mandatory startup");
  assert.equal(h.enumerations.length, 1);
  assert.equal(h.state.localVideoInputs, undefined);
  await pending;
});

test("initialize waits for health before scanning even when optional enumeration succeeds first", async () => {
  const healthGate = deferred(), h = initializeHarness({healthGate});
  const pending = h.initialize();
  await tick();
  assert.deepEqual(h.calls, ["bind", "health"]);
  h.enumerations[0].resolve(["synthetic-input"]);
  await tick();
  assert.deepEqual(h.state.localVideoInputs, ["synthetic-input"]);
  assert.deepEqual(h.calls, ["bind", "health"], "device discovery must not trigger camera scanning");
  healthGate.resolve();
  await pending;
  assert.deepEqual(h.calls, ["bind", "health", "scan"]);
});

test("initialize optional enumeration rejection remains handled after mandatory startup completes", async () => {
  const h = initializeHarness();
  const pending = h.initialize();
  await tick();
  assert.deepEqual(h.calls, ["bind", "health", "scan"]);
  h.enumerations[0].reject(new Error("Synthetic device enumeration failure"));
  await tick();
  await pending;
  assert.equal(h.state.localVideoError.code, "synthetic");
  assert.equal(h.state.healthFailed, false);
  assert.deepEqual(h.messages, [], "quiet optional failure must not become a startup failure toast");
  assert.deepEqual(h.calls, ["bind", "health", "scan"]);
});

for (const outcome of ["success", "failure"]) {
  test(`initialize late retired optional ${outcome} cannot replace a fresh device result`, async () => {
    const h = initializeHarness();
    const pending = h.initialize();
    await tick();
    assert.deepEqual(h.calls, ["bind", "health", "scan"]);
    h.stopLocalVideo({announce: false});
    const current = h.refreshLocalVideoInputs();
    h.enumerations[1].resolve(["synthetic-fresh-input"]);
    await current;
    if (outcome === "success") h.enumerations[0].resolve(["synthetic-retired-input"]);
    else h.enumerations[0].reject(new Error("Synthetic retired failure"));
    await tick();
    await pending;
    assert.deepEqual(h.state.localVideoInputs, ["synthetic-fresh-input"]);
    assert.equal(h.state.localVideoError, undefined);
    assert.equal(h.renders.length, 1);
    assert.deepEqual(h.calls, ["bind", "health", "scan"]);
  });
}

test("initialize handles failed health without scanning or waiting for optional enumeration", async () => {
  const h = initializeHarness({health: null});
  let finished = false;
  const pending = h.initialize().then(() => { finished = true; });
  await tick();
  assert.deepEqual(h.calls, ["bind", "health"]);
  assert.equal(finished, true);
  assert.equal(h.state.healthFailed, true);
  h.enumerations[0].resolve([]);
  await pending;
  assert.deepEqual(h.calls, ["bind", "health"]);
});

test("initialize preserves token-required health gate for the initial scan", async () => {
  const h = initializeHarness({health: {authRequired: true, engines: {libgphoto2: {available: true}}}});
  let finished = false;
  const pending = h.initialize().then(() => { finished = true; });
  await tick();
  assert.deepEqual(h.calls, ["bind", "health"]);
  assert.equal(finished, true);
  h.enumerations[0].resolve([]);
  await pending;
  assert.deepEqual(h.calls, ["bind", "health"]);
});

test("initialize still reaches health and scan when local video is unavailable", async () => {
  const h = initializeHarness();
  h.state.localVideoSupport.available = false;
  await h.initialize();
  assert.deepEqual(h.calls, ["bind", "health", "scan"]);
  assert.equal(h.enumerations.length, 0);
});
