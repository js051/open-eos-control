"use strict";

// Execute production connection, Diagnostics navigation and capability publication.
// Synthetic API promises control response order; unrelated rendering/teardown is stubbed.
// No production test exports, elapsed-time sleeps or camera commands are required.

const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const test = require("node:test");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, `Missing ${name}`);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  assert.notEqual(end, -1, `Missing end of ${name}`);
  return source.slice(start, start + 1 + end);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function element() {
  return { value: "", hidden: false, disabled: false, textContent: "", dataset: {},
    classList: { toggle() {} }, append(child) { child.parentElement = this; },
    setAttribute() {}, removeAttribute() {} };
}
function context({ reuseSessionId = false } = {}) {
  const parent = element();
  const nodes = {};
  const ui = new Proxy(nodes, { get(target, key) {
    if (!target[key]) target[key] = { ...element(), parentElement: parent };
    return target[key];
  }});
  ui.cameraSelect.value = "synthetic-camera";
  ui.connectionView.hidden = false;
  ui.controlView.hidden = true;
  const tabs = ["live", "media", "diagnostics"].map(view => ({ ...element(), dataset: { view } }));
  const panels = ["live", "media", "diagnostics"].map(view => ({ ...element(), id: `${view}-panel` }));
  const docElements = new Map();
  const state = { connectionMode: "usb", cameras: [{ id: "synthetic-camera", engine: "simulator" }],
    session: null, info: null, status: null, capabilities: null, busy: false,
    operatorConfirmedFeatures: new Set(), refreshGeneration: 0, captureMode: "photo",
    localVideoSupport: { available: false }, localVideoBusy: false, liveActive: false,
    previewInput: "CAMERA", mediaDownloadPreparing: false, mediaDownload: null, mediaUpload: null };
  const FEATURES = new Proxy({}, { get: (_, key) => key });
  const capabilities = {
    A: { profile: "synthetic-profile-A", supported: ["STILL_CAPTURE", "AUTOFOCUS"], liveView: {} },
    B: { profile: "synthetic-profile-B", supported: ["MEDIA_BROWSER"], liveView: {} },
  };
  const lateA = deferred();
  const requests = [];
  const renderedDiagnostics = [];
  const feedback = [];
  let connections = 0, aReads = 0;
  const sandbox = vm.createContext({ state, ui, FEATURES, requests, capabilities, lateA, renderedDiagnostics, feedback,
    document: {
      querySelectorAll(selector) { return selector === ".tab" ? tabs : selector === ".view-panel" ? panels : []; },
      querySelector(selector) {
        if (!docElements.has(selector)) docElements.set(selector, element());
        return docElements.get(selector);
      },
    },
    api: async (url, options = {}) => {
      requests.push({ url, method: options.method || "GET" });
      if (url === "/v1/session" && options.method === "POST") {
        connections += 1;
        return { id: connections === 1 || reuseSessionId ? "synthetic-A" : "synthetic-B" };
      }
      if (url === "/v1/session/synthetic-A/capabilities") {
        if (connections > 1) return capabilities.B;
        return ++aReads === 1 ? capabilities.A : lateA.promise;
      }
      if (url === "/v1/session/synthetic-B/capabilities") return capabilities.B;
      if (url.endsWith("/info")) return { model: "synthetic-model" };
      if (url.endsWith("/status")) return { recording: false };
      if (options.method === "DELETE") return {};
      throw new Error(`Unexpected fake API ${url}`);
    },
    t: key => key, clampFps: x => x, captureModeFromCamera: () => "photo",
    captureError: error => { feedback.push(error); return error; }, bulbControlLocked: () => false,
    isBulbMode: () => false, temperatureAllows: () => true, shutterAFSupported: () => false,
    canChangeShutterAF: () => false, localPreviewSelected: () => false,
    shutterReleaseUnconfirmed: () => false, effectiveTapAction: () => null,
  });
  for (const name of ["clearConnectionError", "syncLiveMagnificationFromCapabilities",
    "startEventLoop", "refreshLatestMedia", "renderHealth",
    "cancelMediaDownload", "stopLiveLoop", "stopLocalVideo", "stopEventLoop", "cancelEventLoop",
    "cancelMediaUpload", "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh",
    "closeMediaPreview", "closeMediaDetails", "releaseObjectUrl", "clearBulbTimer", "renderLiveMagnification",
    "renderMediaTransfer", "renderLatestMedia", "renderMediaDetails"]) sandbox[name] = () => {};
  for (const name of ["setOperationState", "showToast", "showConnectionError"]) {
    sandbox[name] = (...args) => feedback.push([name, ...args]);
  }
  sandbox.renderSession = () => sandbox.renderAvailability();
  sandbox.renderDiagnostics = () => renderedDiagnostics.push({
    session: state.session?.id || null, capabilities: sandbox.diagnosticCapabilities(),
  });
  const names = ["connectCamera", "refreshCapabilityEvidence", "disconnectCamera", "resetSession",
    "featureSupported", "mediaTransferActive", "cameraInteractionBusy", "beginCameraInteraction",
    "renderAvailability", "selectView", "diagnosticCapabilities"];
  vm.runInContext(names.map(productionFunction).join("\n"), sandbox);
  return sandbox;
}
async function settle() { await new Promise(resolve => setImmediate(resolve)); }
async function pendingDiagnostics(options) {
  const s = context(options);
  await s.connectCamera();
  assert.equal(s.state.session.id, "synthetic-A");
  assert.equal(s.ui.controlView.hidden, false);
  s.selectView("diagnostics");
  assert.equal(s.state.busy, false, "diagnostics refresh does not hold interaction lock");
  assert.equal(s.ui.disconnectButton.disabled, false, "production UI allows disconnect");
  assert.equal(s.requests.at(-1).url, "/v1/session/synthetic-A/capabilities");
  return s;
}
function writes(subject) {
  return subject.requests.filter(({ method }) => method !== "GET");
}

test("same-connection Diagnostics success publishes capabilities without a camera write", async () => {
  const subject = await pendingDiagnostics();
  const originalWrites = writes(subject).slice();
  const updated = { ...subject.capabilities.A, profile: "synthetic-profile-A-updated" };
  subject.lateA.resolve(updated);
  await settle();
  assert.equal(subject.state.capabilities, updated);
  assert.equal(subject.renderedDiagnostics.at(-1).capabilities.profile, updated.profile);
  assert.deepEqual(writes(subject), originalWrites);
});

test("an unrelated same-connection interaction does not discard valid capability evidence", async () => {
  const subject = await pendingDiagnostics();
  const session = subject.state.session;
  const generation = subject.state.refreshGeneration;
  subject.beginCameraInteraction();
  assert.equal(subject.state.session, session);
  assert.equal(subject.state.refreshGeneration, generation + 1);
  const updated = { ...subject.capabilities.A, profile: "synthetic-profile-A-updated" };
  subject.lateA.resolve(updated);
  await settle();
  assert.equal(subject.state.capabilities, updated);
  assert.equal(subject.state.busy, true, "Evidence refresh must not release another interaction's lock");
});

test("Diagnostics success after disconnect cannot restore old capabilities", async () => {
  const subject = await pendingDiagnostics();
  await subject.disconnectCamera();
  assert.equal(subject.state.session, null);
  assert.equal(subject.state.capabilities, null);
  assert.equal(subject.ui.connectionView.hidden, false);
  const originalWrites = writes(subject).slice();
  subject.lateA.resolve(subject.capabilities.A);
  await settle();
  assert.equal(subject.state.capabilities, null);
  assert.equal(subject.renderedDiagnostics.at(-1).session, null);
  assert.equal(subject.renderedDiagnostics.at(-1).capabilities, null);
  subject.renderAvailability();
  assert.equal(subject.ui.shutterButton.disabled, true);
  assert.deepEqual(writes(subject), originalWrites);
});

for (const reuseSessionId of [false, true]) {
  test(`Diagnostics success cannot replace reconnected capabilities (${reuseSessionId ? "reused" : "different"} wire ID)`, async () => {
    const subject = await pendingDiagnostics({ reuseSessionId });
    const originalSession = subject.state.session;
    await subject.disconnectCamera();
    assert.equal(subject.ui.connectButton.disabled, false, "Production UI permits reconnect");
    await subject.connectCamera();
    const replacementSession = subject.state.session;
    assert.notEqual(replacementSession, originalSession);
    assert.equal(replacementSession.id, reuseSessionId ? originalSession.id : "synthetic-B");
    assert.equal(subject.state.capabilities, subject.capabilities.B);
    assert.equal(subject.ui.shutterButton.disabled, true, "B does not support capture");
    const originalWrites = writes(subject).slice();
    subject.lateA.resolve(subject.capabilities.A);
    await settle();
    assert.equal(subject.state.session, replacementSession);
    assert.equal(subject.state.capabilities, subject.capabilities.B);
    assert.equal(subject.renderedDiagnostics.at(-1).capabilities.profile, subject.capabilities.B.profile);
    subject.renderAvailability();
    assert.equal(subject.ui.shutterButton.disabled, true, "A-only capture stays unavailable for B");
    assert.deepEqual(writes(subject), originalWrites);
  });
}

for (const reconnect of [false, true]) {
  test(`delayed Diagnostics rejection stays silent for the ${reconnect ? "replacement" : "same"} connection`, async () => {
    const subject = await pendingDiagnostics();
    if (reconnect) {
      await subject.disconnectCamera();
      await subject.connectCamera();
    }
    const expectedCapabilities = subject.state.capabilities;
    const expectedSession = subject.state.session;
    const originalRequests = subject.requests.slice();
    const originalFeedback = subject.feedback.slice();
    subject.lateA.reject(new Error("Synthetic delayed failure"));
    await settle();
    assert.equal(subject.state.session, expectedSession);
    assert.equal(subject.state.capabilities, expectedCapabilities);
    assert.equal(subject.renderedDiagnostics.at(-1).capabilities.profile, expectedCapabilities.profile);
    assert.deepEqual(subject.requests, originalRequests, "Evidence failure does not retry");
    assert.deepEqual(subject.feedback, originalFeedback, "Evidence failure does not alter successful operation feedback");
  });
}

test("no connection makes no capability request and leaves evidence empty", async () => {
  const subject = context();
  await subject.refreshCapabilityEvidence();
  assert.deepEqual(subject.requests, []);
  assert.deepEqual(subject.feedback, []);
  assert.equal(subject.state.capabilities, null);
});
