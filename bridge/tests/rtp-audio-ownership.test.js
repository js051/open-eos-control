"use strict";

// Execute production state, API, RTP functions and normal lifecycle controls verbatim.
// Only external I/O, DOM and unrelated render/video helpers are mocked: no camera,
// network, browser or audio hardware is used. Deferred responses cross event-loop turns.

const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const test = require("node:test");
const app = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(app);
  assert.ok(match, name);
  const ending = app.indexOf("\n  }", match.index);
  assert.ok(ending > match.index, name);
  return app.slice(match.index, ending + 4);
}
const realFunctions = [
  "api", "connectCamera", "disconnectCamera", "resetSession", "refreshSession",
  "featureSupported", "cancelEventLoop", "stopEventLoop", "mediaTransferActive",
  "cameraInteractionBusy", "beginCameraInteraction", "settingByKey", "isBulbMode",
  "captureModeFromCamera", "shutterReleaseUnconfirmed", "bulbControlLocked",
  "localPreviewSelected", "previewActive", "changePreviewInput", "clampFps", "toggleLiveView", "startLiveView", "stopLiveView",
  "stopLiveLoop", "changeLiveSource", "currentRtpAudioStatus", "refreshRtpAudioStatus",
  "renderRtpAudio", "toggleRtpAudio", "pollRtpAudio", "stopRtpAudioSources", "stopRtpAudio",
  "renderAvailability", "setOperationState",
];
const stateStart = app.indexOf("  const state = {");
const stateEnd = app.indexOf("\n  };", stateStart);
const stateSource = app.slice(stateStart, stateEnd + 5).replace("const state", "globalThis.state");
const noop = () => {};
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};
function element() {
  const item = {
    hidden: false, disabled: false, value: "", textContent: "", title: "", dataset: {},
    classList: {toggle: noop, add: noop, remove: noop},
    setAttribute(name, value) { this[name] = value; }, removeAttribute: noop, append: noop,
    querySelector: () => element(),
  };
  item.parentElement = item;
  return item;
}
function status(owner, recording = false) {
  return {owner, recording, raw: {rtpAudio: {advertised: true, available: true}}};
}
const caps = {supported: ["LIVE_VIEW", "STILL_CAPTURE", "VIDEO_RECORDING"],
  liveView: {maxFps: 15, minFps: 1, defaultSize: "MEDIUM", sources: ["CCAPI_RTP", "CCAPI_MULTIPART"]}};
function harness() {
  const ui = new Proxy({}, {get(target, key) {return target[key] ||= element();}});
  ui.cameraSelect.value = "synthetic-camera";
  const requests = [], toasts = [], contexts = [], deferredStatus = [], audioRenders = [];
  let nextSession = {id: "synthetic-A"};
  let currentStatus = status("initial-A");
  let liveSource = "CCAPI_RTP";
  let delayNextStatus = false;
  class FakeAudioContext {
    constructor() {this.resumeGate = deferred(); this.closeCount = 0; contexts.push(this);}
    resume() {return this.resumeGate.promise;}
    close() {this.closeCount += 1; return Promise.resolve();}
  }
  const sandbox = {
    console, Headers, AbortController, Date, Set, Map, Number, Error, encodeURIComponent,
    ui, window: {AudioContext: FakeAudioContext}, navigator: {mediaDevices: {}},
    localVideo: {supportState: () => ({available: true})},
    document: {querySelector: () => element(), querySelectorAll: () => []},
    readLanguagePreference: () => "en",
    FEATURES: new Proxy({}, {get: (_, name) => name}),
    t: key => key,
    ApiError: class extends Error {constructor(message, props) {super(message); Object.assign(this, props);}},
    mediaTransfer: {isAbortError: error => error?.name === "AbortError"},
    showToast: (message, error) => toasts.push({message, error: Boolean(error)}),
    captureError: error => error,
    temperatureAllows: () => true,
    shutterAFSupported: () => true, canChangeShutterAF: () => true,
    effectiveTapAction: () => null, validCcapiUrl: () => true,
    liveCapabilities: () => sandbox.state.capabilities?.liveView || {},
    rtpAudio: {readPcmResponse: () => {throw new Error("Audio fixture must stay pending");}},
    renderLiveState: () => sandbox.renderRtpAudio(),
    fetch: async (url, options) => {
      requests.push({url, method: options.method, signal: options.signal});
      let body;
      if (url === "/v1/session" && options.method === "POST") body = nextSession;
      else if (url.endsWith("/status")) {
        if (delayNextStatus) {
          delayNextStatus = false;
          const pending = deferred();
          deferredStatus.push(pending);
          return pending.promise;
        }
        body = currentStatus;
      } else if (url.endsWith("/info")) body = {model: "synthetic-camera"};
      else if (url.endsWith("/capabilities")) body = caps;
      else if (url.endsWith("/liveview/start")) body = {source: liveSource, requestedFps: 15};
      else if (url.includes("/liveview/audio?")) {
        return new Promise((_, reject) => {
          const abort = () => {const error = new Error("synthetic abort"); error.name = "AbortError"; reject(error);};
          if (options.signal?.aborted) abort();
          else options.signal?.addEventListener("abort", abort, {once: true});
        });
      } else if (options.method === "DELETE" || url.endsWith("/liveview/stop")) body = null;
      else throw new Error(`Unexpected mocked request: ${url}`);
      return response(body);
    },
  };
  for (const name of ["clearConnectionError", "renderSession", "startEventLoop", "refreshLatestMedia",
    "writeCameraPreference", "showConnectionError", "renderHealth", "cancelMediaDownload",
    "cancelMediaUpload", "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh",
    "closeMediaPreview", "closeMediaDetails", "releaseObjectUrl", "clearBulbTimer", "stopLocalVideo",
    "syncLiveMagnificationFromCapabilities", "pollLiveView", "renderFps", "clearMonitoringLayers",
    "renderPreviewInput", "replaceButtonIcon", "renderLiveMagnification", "renderMediaTransfer", "renderLatestMedia", "renderMediaDetails"])
    sandbox[name] = noop;
  vm.createContext(sandbox);
  vm.runInContext(`${stateSource}\n${realFunctions.map(extract).join("\n")}`, sandbox, {timeout: 1000});
  const renderRtpAudio = sandbox.renderRtpAudio;
  sandbox.renderRtpAudio = () => { audioRenders.push(true); renderRtpAudio(); };
  sandbox.startLocalVideo = async () => { sandbox.state.localVideoActive = true; };
  Object.assign(sandbox.state, {session: nextSession, capabilities: caps, status: currentStatus,
    cameras: [{id: "synthetic-camera", engine: "fixture"}], liveSource: "CCAPI_RTP"});
  sandbox.renderAvailability();
  return Object.assign(sandbox, {
    requests, toasts, contexts, deferredStatus, audioRenders,
    delayStatus: () => {delayNextStatus = true;},
    useStatus: value => {currentStatus = value;},
    useSession: value => {nextSession = value;},
    useSource: value => {liveSource = value;},
  });
}
const response = body => ({ok: true, status: 200, json: async () => body});
const flush = async () => {for (let turn = 0; turn < 8; turn++) await Promise.resolve();};
const nextTask = () => new Promise(resolve => setImmediate(resolve));
const audioRequests = h => h.requests.filter(item => item.url.includes("/liveview/audio?"));
function assertNormalLiveControls(h) {
  h.renderAvailability();
  assert.equal(h.cameraInteractionBusy(), false);
  assert.equal(h.ui.liveToggleButton.disabled, false, "normal Stop/Start remains available");
  assert.equal(h.ui.liveSourceSelect.disabled, false, "normal source selector remains available");
  assert.equal(h.ui.disconnectButton.disabled, false, "normal Disconnect remains available");
}
async function reconnect(h, sessionId) {
  await nextTask();
  assert.equal(h.ui.disconnectButton.disabled, false);
  await h.disconnectCamera();
  assert.equal(h.state.session, null);
  assert.equal(h.ui.connectionView.hidden, false);
  assert.equal(h.ui.connectButton.disabled, false);
  h.useSession({id: sessionId});
  h.useStatus(status("replacement-B"));
  await nextTask();
  await h.connectCamera();
  assert.equal(h.state.session.id, sessionId);
  assert.equal(h.state.status.owner, "replacement-B");
}

async function liveHarness(t) {
  const h = harness();
  t.after(() => h.stopRtpAudio());
  await h.startLiveView();
  await flush();
  assertNormalLiveControls(h);
  return h;
}

async function changeSource(h, source) {
  h.useSource(source);
  h.ui.liveSourceSelect.value = source;
  await h.changeLiveSource();
  await flush();
}

const transitions = {
  "normal Stop": async h => { await h.toggleLiveView(); },
  "normal Stop then Start": async h => {
    await h.toggleLiveView();
    assertNormalLiveControls(h);
    await h.toggleLiveView();
  },
  "RTP to multipart": h => changeSource(h, "CCAPI_MULTIPART"),
  "RTP to multipart and back": async h => {
    await changeSource(h, "CCAPI_MULTIPART");
    await changeSource(h, "CCAPI_RTP");
  },
  "Disconnect": h => h.disconnectCamera(),
  "replacement session": async h => {
    await reconnect(h, "synthetic-B");
    await h.toggleLiveView();
  },
  "replacement session reusing ID": async h => {
    await reconnect(h, "synthetic-A");
    await h.toggleLiveView();
  },
  "camera to local preview": async h => {
    assert.equal(h.ui.previewInputSelect.disabled, false);
    h.ui.previewInputSelect.value = "LOCAL_VIDEO";
    await h.changePreviewInput();
  },
};

test("same-owner RTP status succeeds and ordinary unmute/mute retains normal controls", async t => {
  const h = await liveHarness(t);
  const fresh = status("fresh-A");
  h.useStatus(fresh);
  await h.refreshRtpAudioStatus();
  assert.equal(h.state.status, fresh);
  const start = h.toggleRtpAudio();
  assert.equal(h.state.rtpAudioBusy, true);
  assert.equal(h.ui.rtpAudioButton.disabled, true);
  assertNormalLiveControls(h);
  await nextTask();
  h.contexts[0].resumeGate.resolve();
  await start;
  assert.equal(h.state.rtpAudioEnabled, true);
  assert.equal(h.state.rtpAudioBusy, false);
  assert.equal(h.state.rtpAudioContext, h.contexts[0]);
  assert.equal(h.ui.rtpAudioButton["aria-pressed"], "true");
  assert.equal(audioRequests(h).length, 1);
  assert.equal(audioRequests(h)[0].method, "GET");
  assert.match(audioRequests(h)[0].url, /^\/v1\/session\/synthetic-A\/liveview\/audio\?/);
  assert.equal(h.toasts.filter(item => item.message === "cameraAudioStarted").length, 1);
  await h.toggleRtpAudio();
  await flush();
  assert.equal(h.state.rtpAudioEnabled, false);
  assert.equal(h.state.rtpAudioContext, null);
  assert.equal(h.contexts[0].closeCount, 1);
  assert.equal(audioRequests(h)[0].signal.aborted, true);
  assert.equal(h.ui.rtpAudioButton["aria-pressed"], "false");
  assert.equal(h.toasts.at(-1).message, "cameraAudioStopped");
});

test("same-owner optional status failure keeps current status and live controls", async t => {
  const h = await liveHarness(t);
  const before = h.state.status;
  const toasts = h.toasts.slice();
  h.delayStatus();
  const pending = h.refreshRtpAudioStatus();
  await nextTask();
  h.deferredStatus[0].reject(new Error("Synthetic optional read failure"));
  await pending;
  assert.equal(h.state.status, before);
  assert.deepEqual(h.toasts, toasts);
  assert.equal(h.state.liveActive, true);
  assertNormalLiveControls(h);
});

for (const [name, transition] of Object.entries(transitions)) {
  for (const result of ["resolve", "reject"]) {
    test(`late RTP status ${result} after ${name} cannot change status or render`, async t => {
      const h = await liveHarness(t);
      h.delayStatus();
      const pending = h.refreshRtpAudioStatus();
      assert.equal(h.deferredStatus.length, 1);
      await nextTask();
      await transition(h);
      await flush();
      const before = h.state.status;
      const toasts = h.toasts.slice();
      const renders = h.audioRenders.length;
      h.renderAvailability();
      const photoDisabled = h.ui.photoModeButton.disabled;
      await nextTask();
      if (result === "resolve") h.deferredStatus[0].resolve(response(status("stale-A", true)));
      else h.deferredStatus[0].reject(new Error("Synthetic stale status failure"));
      await pending;
      assert.equal(h.state.status, before, "Old full status must not replace current recording/audio state");
      assert.equal(h.audioRenders.length, renders, "Old optional reads must not render replacement state");
      h.renderAvailability();
      assert.equal(h.ui.photoModeButton.disabled, photoDisabled);
      assert.deepEqual(h.toasts, toasts);
    });
  }
}

test("RTP status before a newer camera interaction cannot replace its status", async t => {
  const h = await liveHarness(t);
  h.delayStatus();
  const pending = h.refreshRtpAudioStatus();
  await nextTask();
  h.beginCameraInteraction();
  const current = status("new-interaction");
  h.state.status = current;
  const renders = h.audioRenders.length;
  h.deferredStatus[0].resolve(response(status("old-interaction", true)));
  await pending;
  assert.equal(h.state.status, current);
  assert.equal(h.audioRenders.length, renders);
  assert.equal(h.state.busy, true, "Optional audio status must not unlock an operation");
});

test("same-owner resume rejection closes its context and permits a fresh unmute", async t => {
  const h = await liveHarness(t);
  const pending = h.toggleRtpAudio();
  await nextTask();
  h.contexts[0].resumeGate.reject(new Error("Synthetic current resume failure"));
  await pending;
  assert.equal(h.state.rtpAudioEnabled, false);
  assert.equal(h.state.rtpAudioBusy, false);
  assert.equal(h.state.rtpAudioContext, null);
  assert.equal(h.state.rtpAudioError, "Synthetic current resume failure");
  assert.equal(h.contexts[0].closeCount, 1);
  assert.equal(audioRequests(h).length, 0);
  assert.deepEqual(h.toasts.at(-1), { message: "Synthetic current resume failure", error: true });
  const retry = h.toggleRtpAudio();
  h.contexts[1].resumeGate.resolve();
  await retry;
  assert.equal(h.state.rtpAudioError, null);
  assert.equal(h.state.rtpAudioEnabled, true);
  assert.equal(audioRequests(h).length, 1);
});

for (const [name, transition] of Object.entries(transitions)) {
  for (const result of ["resolve", "reject"]) {
    test(`pending resume ${result} after ${name} stays muted without an audio GET`, async t => {
      const h = await liveHarness(t);
      const pending = h.toggleRtpAudio();
      assertNormalLiveControls(h);
      await nextTask();
      await transition(h);
      await flush();
      const before = { toasts: h.toasts.slice(), renders: h.audioRenders.length, error: h.state.rtpAudioError };
      assert.equal(audioRequests(h).length, 0);
      await nextTask();
      if (result === "resolve") h.contexts[0].resumeGate.resolve();
      else h.contexts[0].resumeGate.reject(new Error("Synthetic stale resume failure"));
      await pending;
      assert.equal(audioRequests(h).length, 0, "A new run or connection needs its own unmute");
      assert.equal(h.state.rtpAudioEnabled, false);
      assert.equal(h.state.rtpAudioBusy, false);
      assert.equal(h.state.rtpAudioContext, null);
      assert.equal(h.contexts[0].closeCount, 1, "Stop must dispose the pending context exactly once");
      assert.equal(h.state.rtpAudioError, before.error);
      assert.deepEqual(h.toasts, before.toasts);
      assert.equal(h.audioRenders.length, before.renders);
    });
  }
}

for (const newState of ["pending", "playing", "failed"]) {
  for (const result of ["resolve", "reject"]) {
    test(`old resume ${result} cannot disturb a newer ${newState} unmute`, async t => {
      const h = await liveHarness(t);
      const oldStart = h.toggleRtpAudio();
      await nextTask();
      await transitions["normal Stop then Start"](h);
      await flush();
      const newStart = h.toggleRtpAudio();
      if (newState === "playing") h.contexts[1].resumeGate.resolve();
      if (newState === "failed") h.contexts[1].resumeGate.reject(new Error("Synthetic new resume failure"));
      if (newState !== "pending") await newStart;
      const before = {
        toasts: h.toasts.slice(), renders: h.audioRenders.length,
        context: h.state.rtpAudioContext, error: h.state.rtpAudioError,
        busy: h.state.rtpAudioBusy, enabled: h.state.rtpAudioEnabled,
        generation: h.state.rtpAudioLoopGeneration, controller: h.state.rtpAudioAbortController,
        requests: audioRequests(h).length,
      };
      await nextTask();
      if (result === "resolve") h.contexts[0].resumeGate.resolve();
      else h.contexts[0].resumeGate.reject(new Error("Synthetic old resume failure"));
      await oldStart;
      assert.equal(h.state.rtpAudioContext, before.context);
      assert.equal(h.state.rtpAudioError, before.error);
      assert.equal(h.state.rtpAudioBusy, before.busy);
      assert.equal(h.state.rtpAudioEnabled, before.enabled);
      assert.equal(h.state.rtpAudioLoopGeneration, before.generation);
      assert.equal(h.state.rtpAudioAbortController, before.controller);
      assert.equal(audioRequests(h).length, before.requests);
      assert.deepEqual(h.toasts, before.toasts);
      assert.equal(h.audioRenders.length, before.renders);
      assert.equal(h.contexts[0].closeCount, 1);
      assert.equal(h.contexts[1].closeCount, newState === "failed" ? 1 : 0);
      if (newState === "pending") {
        h.contexts[1].resumeGate.resolve();
        await newStart;
        assert.equal(h.state.rtpAudioContext, h.contexts[1]);
        assert.equal(h.state.rtpAudioEnabled, true);
        assert.equal(audioRequests(h).length, 1);
      }
    });
  }
}

test("Stop closes a still-pending context without waiting for resume to settle", async t => {
  const h = await liveHarness(t);
  const pending = h.toggleRtpAudio();
  await h.toggleLiveView();
  assert.equal(h.contexts[0].closeCount, 1);
  assert.equal(h.state.rtpAudioContext, null);
  h.contexts[0].resumeGate.reject(new Error("Synthetic close interrupted resume"));
  await pending;
});

test("repeated unmute while resume is pending opens only one context", async t => {
  const h = await liveHarness(t);
  const pending = h.toggleRtpAudio();
  const duplicate = h.toggleRtpAudio();
  assert.equal(h.contexts.length, 1);
  h.contexts[0].resumeGate.resolve();
  await Promise.all([pending, duplicate]);
  assert.equal(audioRequests(h).length, 1);
});
