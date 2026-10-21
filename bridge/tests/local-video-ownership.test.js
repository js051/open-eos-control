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

test("local preview restart retires the previous fallback frame counter", async () => {
  const h = harness();
  h.state.localVideoLastFrameCount = 99;
  await h.startLocalVideo();
  assert.equal(h.state.localVideoLastFrameCount, null);
  h.state.localVideoLastFrameCount = 12;
  h.stopLocalVideo({announce: false});
  assert.equal(h.state.localVideoLastFrameCount, null);
});

// Startup terminal-state witnesses use real app functions and the real local
// video helper. Only the browser's stream, track and play promise are synthetic.
function startupTrack(initialState = "live") {
  const listeners = new Map();
  const track = {
    readyState: initialState, muted: false, stopCalls: 0, endedEvents: 0,
    getSettings: () => ({width: 160, height: 90, frameRate: 30}),
    addEventListener(name, callback, options = {}) {
      const entries = listeners.get(name) || [];
      entries.push({callback, once: options.once === true});
      listeners.set(name, entries);
      // In particular, registering after ended does not deliver an old event.
    },
    stop() {this.stopCalls += 1; this.readyState = "ended";},
    end() {
      this.readyState = "ended";
      this.endedEvents += 1;
      const entries = [...(listeners.get("ended") || [])];
      for (const entry of entries) {
        if (entry.once) listeners.set("ended", listeners.get("ended").filter(value => value !== entry));
        entry.callback({type: "ended", target: this});
      }
    },
  };
  return track;
}

function startupOpening({readyState = "live", playGate = null} = {}) {
  const track = startupTrack(readyState);
  const stream = {getVideoTracks: () => [track], getTracks: () => [track]};
  return {track, stream, playGate};
}

function startupHarness(openings) {
  const h = harness(), acquisitions = [], playCalls = [], frames = new Map();
  const realLocalVideo = require("../open_eos_bridge/static/local-video.js");
  let frameID = 0;
  h.context.localVideo = realLocalVideo;
  h.localVideo = realLocalVideo;
  h.navigator.mediaDevices = {
    async getUserMedia(constraints) {
      acquisitions.push(constraints);
      const opening = openings[acquisitions.length - 1];
      assert.ok(opening, "every synthetic acquisition must have an explicit opening");
      return opening.stream;
    },
    async enumerateDevices() {return [];},
  };
  Object.assign(h.ui.localVideo, {
    play() {
      const stream = this.srcObject;
      playCalls.push(stream);
      const opening = openings.find(value => value.stream === stream);
      assert.ok(opening, "play must use the exact acquired stream");
      return opening.playGate?.promise || Promise.resolve();
    },
    requestVideoFrameCallback(callback) {
      const id = ++frameID;
      frames.set(id, callback);
      return id;
    },
    cancelVideoFrameCallback(id) {frames.delete(id);},
  });
  Object.assign(h.context, {
    ApiError: class extends Error {
      constructor(message, details) {super(message); Object.assign(this, details);}
    },
    captureError: error => error,
  });
  vm.runInContext([
    "localVideoErrorMessage", "captureLocalVideoError", "scheduleLocalVideoFrame", "cancelLocalVideoFrame",
  ].map(extract).join("\n"), h.context);
  return {...h, acquisitions, playCalls, frames};
}

function assertStartupRetired(h, opening) {
  assert.equal(h.state.localVideoActive, false);
  assert.equal(h.state.localVideoBusy, false);
  assert.equal(h.state.localVideoStream, null);
  assert.equal(h.state.localVideoTrack, null);
  assert.equal(h.ui.localVideo.srcObject, null);
  assert.ok(opening.track.stopCalls >= 1, "retirement must stop the acquired stream");
  assert.equal(h.frames.size, 0, "retired input must not schedule video frames");
  assert.equal(h.state.localVideoError?.code, "LOCAL_VIDEO_ENDED");
  assert.equal(h.messages.includes("localVideoStarted"), false);
  assert.deepEqual(h.messages.slice(-2), ["localVideoEnded", "localVideoEnded"]);
}

test("startup ended track already acquired cannot publish started", async () => {
  const opening = startupOpening({readyState: "ended"}), h = startupHarness([opening]);
  await h.startLocalVideo();
  assert.equal(h.state.localVideoActive, false, "UVC_STARTUP_ALREADY_ENDED");
  assert.equal(opening.track.endedEvents, 0, "readyState is evidence without an invented ended event");
  assert.equal(h.playCalls.length, 0, "an already-ended track must not begin playback");
  assert.equal(h.acquisitions.length, 1, "terminal input must never auto-restart acquisition");
  assertStartupRetired(h, opening);
});

test("startup ended during pending play cannot publish after resolve", async () => {
  const playGate = deferred(), opening = startupOpening({playGate}), h = startupHarness([opening]);
  const starting = h.startLocalVideo();
  await tick();
  assert.equal(h.ui.localVideo.srcObject, opening.stream);
  assert.equal(h.playCalls.length, 1);
  opening.track.end();
  const immediatelyDetached = h.ui.localVideo.srcObject === null && h.state.localVideoStream === null;
  playGate.resolve();
  await starting;
  assert.equal(h.state.localVideoActive, false, "UVC_STARTUP_ENDED_RESOLVE");
  assert.equal(immediatelyDetached, true, "ended cleanup cannot wait for play to settle");
  assert.equal(opening.track.endedEvents, 1);
  assert.equal(h.acquisitions.length, 1);
  assertStartupRetired(h, opening);
});

test("startup ended during pending play remains terminal after rejection", async () => {
  const playGate = deferred(), opening = startupOpening({playGate}), h = startupHarness([opening]);
  const starting = h.startLocalVideo();
  await tick();
  assert.equal(h.ui.localVideo.srcObject, opening.stream);
  opening.track.end();
  playGate.reject(new Error("Synthetic late playback rejection"));
  await starting;
  assert.equal(h.state.localVideoError?.code, "LOCAL_VIDEO_ENDED", "UVC_STARTUP_ENDED_REJECT");
  assert.equal(h.messages.includes("localVideoPlaybackBlocked"), false,
    "a retired play rejection cannot replace the already-observed input termination");
  assert.equal(h.acquisitions.length, 1);
  assertStartupRetired(h, opening);
});

test("startup live track starts normally and later ended retires it", async () => {
  const opening = startupOpening(), h = startupHarness([opening]);
  await h.startLocalVideo();
  assert.equal(h.state.localVideoActive, true);
  assert.equal(h.state.localVideoBusy, false);
  assert.equal(h.state.localVideoStream, opening.stream);
  assert.equal(h.ui.localVideo.srcObject, opening.stream);
  assert.equal(opening.track.readyState, "live");
  assert.equal(opening.track.stopCalls, 0);
  assert.equal(h.frames.size, 1);
  assert.deepEqual(h.messages, ["busy", "localVideoStarted", "localVideoStarted"]);
  opening.track.end();
  assert.equal(h.state.localVideoActive, false);
  assert.equal(h.ui.localVideo.srcObject, null);
  assert.equal(opening.track.stopCalls, 1);
  assert.equal(h.frames.size, 0);
  assert.equal(h.state.localVideoError.code, "LOCAL_VIDEO_ENDED");
  assert.equal(h.acquisitions.length, 1);
});

for (const settlement of ["resolve", "rejection"]) {
  const title = settlement === "resolve"
    ? "startup retired play resolve and old ended cannot stop replacement"
    : "startup retired play rejection and old ended cannot overwrite replacement";
  test(title, async () => {
    const oldPlay = deferred(), old = startupOpening({playGate: oldPlay}), replacement = startupOpening();
    const h = startupHarness([old, replacement]);
    const oldStart = h.startLocalVideo();
    await tick();
    const oldGeneration = h.state.localVideoGeneration;
    h.stopLocalVideo({announce: false});
    await h.startLocalVideo();
    const newGeneration = h.state.localVideoGeneration;
    assert.notEqual(newGeneration, oldGeneration);
    assert.equal(h.state.localVideoStream, replacement.stream);
    assert.equal(h.frames.size, 1);
    const messages = h.messages.slice(), frameHandle = h.state.localVideoFrameHandle;
    // Model a queued event from the retired producer, not an event replayed
    // when a listener is registered. It must never act on the replacement.
    old.track.end();
    if (settlement === "resolve") oldPlay.resolve();
    else oldPlay.reject(new Error("Synthetic obsolete playback rejection"));
    await oldStart;
    assert.equal(h.state.localVideoGeneration, newGeneration);
    assert.equal(h.state.localVideoActive, true);
    assert.equal(h.state.localVideoBusy, false);
    assert.equal(h.state.localVideoStream, replacement.stream);
    assert.equal(h.state.localVideoTrack, replacement.track);
    assert.equal(h.ui.localVideo.srcObject, replacement.stream);
    assert.equal(replacement.track.readyState, "live");
    assert.equal(replacement.track.stopCalls, 0);
    assert.ok(old.track.stopCalls >= 1);
    assert.equal(h.state.localVideoError, null);
    assert.equal(h.state.localVideoFrameHandle, frameHandle);
    assert.equal(h.frames.size, 1);
    assert.deepEqual(h.messages, messages);
    assert.equal(h.acquisitions.length, 2);
  });
}
