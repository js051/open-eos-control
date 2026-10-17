"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");
const guidance = require("../open_eos_bridge/static/connection-guidance.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
const html = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/index.html"), "utf8");

function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, name);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  assert.notEqual(end, -1, name);
  return source.slice(start, start + 1 + end);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function element() {
  const child = { textContent: "" };
  return { value: "", hidden: false, disabled: false, textContent: "", attributes: {}, children: [],
    classList: { toggle() {} }, querySelector() { return child; },
    append(node) { this.children.push(node); node.parentElement = this; },
    replaceChildren() { this.children = []; this.value = ""; },
    setAttribute(name, value) { this.attributes[name] = value; },
    removeAttribute(name) { delete this.attributes[name]; }, focus() { this.focused = true; } };
}
function context() {
  const parent = element(), nodes = {};
  const ui = new Proxy(nodes, { get(target, key) {
    if (!target[key]) target[key] = { ...element(), parentElement: parent };
    return target[key];
  }});
  ui.ccapiUrlInput.value = "http://camera.invalid:8080";
  const state = { language: "en", connectionMode: "usb", token: "", health: null, healthAttempt: null,
    healthFailed: false, scanState: "idle", scanAttempt: null, connectionFailure: null, cameras: [],
    session: null, busy: false, operatorConfirmedFeatures: new Set(), localVideoSupport: { available: false },
    shutterDisconnectWarning: true, captureMode: "photo", shutterAutofocus: true, previewInput: "CAMERA" };
  const requests = [], errors = [], timers = new Map();
  let nextTimer = 0;
  const sandbox = vm.createContext({ state, ui, requests, errors, timers, URL, AbortController, Headers, Error,
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    window: { location: { href: "http://bridge.invalid/" } }, connectionGuidance: guidance, document: { createElement: element, querySelector: element, querySelectorAll: () => [] },
    mediaTransfer: { isAbortError: (error) => error?.name === "AbortError" },
    FEATURES: new Proxy({}, { get: (_, name) => name }),
    t: key => key, api: async (url, options) => { requests.push({ url, options }); return { cameras: [] }; },
    captureError: error => { errors.push(error); return error; },
    bulbControlLocked: () => false, cameraInteractionBusy: () => state.busy,
    featureSupported: () => false, isBulbMode: () => false, temperatureAllows: () => true,
    shutterAFSupported: () => false, canChangeShutterAF: () => false, localPreviewSelected: () => false,
    shutterReleaseUnconfirmed: () => false, effectiveTapAction: () => null,
    renderHeldAutofocus() {}, cameraFocusInfoEligible: () => false, cancelCameraFocusInfo() {},
    renderLiveMagnification() {}, renderMediaTransfer() {}, renderLatestMedia() {}, renderMediaDetails() {},
    clampFps: value => value, captureModeFromCamera: () => "photo", syncLiveMagnificationFromCapabilities() {},
    setOperationState() {}, writeCameraPreference() {}, renderSession() {}, startEventLoop() {},
    refreshLatestMedia() {}, showToast() {}, CCAPI_URL_KEY: "test-url", CCAPI_USERNAME_KEY: "test-user",
  });
  vm.runInContext(source.slice(source.indexOf("  class ApiError extends Error {"),
    source.indexOf("  function resolvedLanguage()")), sandbox);
  vm.runInContext(["adoptCameraStatus", "showConnectionError", "clearConnectionError", "renderConnectionGuidance", "correctConnection",
    "renderHealth", "validCcapiUrl", "renderConnectionMode", "selectConnectionMode", "renderCameras",
    "recordingRecoveryActive", "refreshHealth", "cancelCameraScan", "scanCameras", "connectionRetirementBlocked", "renderConnectionCleanup", "recordConnectionCleanupWarning", "cancelCameraConnection", "retireConnectionAttempt", "connectCamera", "liveViewStopRequired", "maintenanceStopRetryAvailable", "renderAvailability"]
    .map(productionFunction).join("\n"), sandbox);
  return sandbox;
}

const cameraFailure = reason => ({ status: 502, code: "CCAPI_DISCOVERY_FAILED", engine: "ccapi", connectionReason: reason });
test("camera origins require explicit scheme and no path, inline credentials, or URL repair", () => {
  for (const valid of ["http://192.0.2.1:8080", "https://camera.invalid/", "http://[::1]:8080", " HTTP://camera.invalid "]) {
    assert.equal(guidance.validCameraOrigin(valid), true, valid);
  }
  for (const invalid of ["", "camera.invalid", "http:camera.invalid", "https:///camera.invalid", "ftp://camera.invalid",
    "http://user:pass@camera.invalid", "http://@camera.invalid", "http://camera.invalid/ccapi", "http://camera.invalid?",
    "http://camera.invalid?q=1", "http://camera.invalid#", "http://camera.invalid/#top", "http://camera.invalid:65536",
    "http://camera.invalid/.", "http://camera.invalid/ccapi/..", "http://camera.invalid/%2e",
    "http://camera.invalid\\ccapi", "http://cam\nera.invalid", null, {}]) {
    assert.equal(guidance.validCameraOrigin(invalid), false, String(invalid));
  }
});

test("actual Bridge auth status outranks forged codes, reasons and message text", () => {
  assert.deepEqual(guidance.failureGuidance({ ...cameraFailure("TLS_ERROR"), status: 401, message: "404" }),
    ["connectionBridgeAuth", "token"]);
  assert.deepEqual(guidance.failureGuidance({ ...cameraFailure("AUTHENTICATION_REJECTED"), status: 403 }),
    ["connectionBridgeAccess", "bridge"]);
  assert.deepEqual(guidance.failureGuidance({ ...cameraFailure("AUTHENTICATION_REJECTED"), status: 404 }),
    ["connectionBridgeEndpoint", "bridge"]);
  for (const error of [
    { ...cameraFailure("AUTHENTICATION_REJECTED"), status: 503 },
    { ...cameraFailure("AUTHENTICATION_REJECTED"), engine: "libgphoto2" },
    { status: 503, code: "UNAUTHORIZED", message: "401 bad password" },
    { status: 502, code: "CCAPI_HTTP_ERROR", message: "401 bad password" },
  ]) assert.deepEqual(guidance.failureGuidance(error), ["connectionUnknownFailure", null]);
});

test("only a whitelisted terminal camera discovery reason selects camera recovery", () => {
  const expectations = { AUTHENTICATION_REJECTED: "connectionCameraAuth", ENDPOINT_NOT_FOUND: "connectionCameraEndpoint",
    HTTP_ERROR: "connectionCameraHttp", TLS_ERROR: "connectionCameraTls", HOST_NOT_FOUND: "connectionCameraHost",
    TIMEOUT: "connectionCameraTimeout", UNREACHABLE: "connectionCameraNetwork", DISCOVERY_FAILED: "connectionCameraDiscovery" };
  for (const [reason, key] of Object.entries(expectations)) assert.equal(guidance.failureGuidance(cameraFailure(reason))[0], key);
  for (const malformed of [null, undefined, ["AUTHENTICATION_REJECTED"], {}, "__proto__", "constructor", "401", "TIMEOUT "]) {
    assert.deepEqual(guidance.failureGuidance(cameraFailure(malformed)), ["connectionCameraDiscovery", "address"]);
  }
  assert.deepEqual(guidance.failureGuidance({ status: 0, code: "NETWORK_ERROR", message: "SSL 401" }),
    ["connectionBridgeNetwork", "bridge"]);
});

test("USB readiness recognizes existing EDSDK and unavailable engines have actionable guidance", () => {
  const s = context();
  s.state.health = { engines: { libgphoto2: { available: false }, edsdk: { available: true, version: "SYNTHETIC EDSDK" } } };
  assert.equal(guidance.availableUsbEngine(s.state.health), "edsdk");
  s.renderHealth();
  assert.equal(s.ui.engineState.textContent, "bridgeReady");
  s.state.health.engines.edsdk.available = false;
  s.renderConnectionGuidance();
  assert.equal(s.ui.connectionStatus.textContent, "connectionUsbEngine");
  s.showConnectionError({ status: 503, code: "ENGINE_UNAVAILABLE" });
  assert.equal(s.ui.connectionError.textContent, "connectionUsbEngine");
  assert.equal(s.ui.connectionFixButton.textContent, "connectionCheckBridge");
  assert.equal(s.state.shutterDisconnectWarning, true);
});

test("initial, empty, failed and explicit retry scans render distinct states without camera writes", async () => {
  const s = context();
  s.renderConnectionGuidance();
  assert.equal(s.ui.connectionStatus.textContent, "connectionScanIdle");
  await s.scanCameras();
  assert.equal(s.ui.connectionStatus.textContent, "connectionScanEmpty");
  assert.equal(s.ui.cameraSelect.children[0].textContent, "noCameras");
  assert.equal(s.ui.connectButton.disabled, true);
  const failure = { status: 401, code: "SYNTHETIC", message: "Not camera credentials" };
  s.api = async (url, options) => { s.requests.push({ url, options }); throw failure; };
  await s.scanCameras();
  assert.equal(s.ui.connectionStatus.textContent, "connectionScanFailed");
  assert.equal(s.ui.connectionError.textContent, "connectionBridgeAuth");
  s.correctConnection();
  assert.equal(s.ui.tokenInput.focused, true);
  s.api = async (url, options) => {
    s.requests.push({ url, options });
    return { cameras: [{ id: "TEST", model: "SYNTHETIC", port: "USB", engine: "edsdk" }] };
  };
  await s.scanCameras();
  assert.equal(s.ui.connectionStatus.textContent, "connectionScanReady");
  assert.equal(s.ui.connectionError.hidden, true);
  assert.equal(s.ui.connectButton.disabled, false);
  assert.equal(s.requests.length, 3);
  assert.equal(s.requests.every(request => request.url === "/v1/cameras" && !request.options.method), true);
  assert.equal(s.state.shutterDisconnectWarning, true);
  assert.equal(s.ui.shutterDisconnectWarning.hidden, false);
});

test("cancelled or mode-retired scan cannot overwrite a new scan or restore its error", async () => {
  for (const rejected of [false, true]) {
    const s = context(), old = deferred(), fresh = deferred();
    let calls = 0;
    s.api = async (url, options) => {
      s.requests.push({ url, options });
      return ++calls === 1 ? old.promise : fresh.promise;
    };
    const pendingOld = s.scanCameras();
    assert.equal(s.ui.connectButton.disabled, true);
    assert.equal(s.ui.scanButton.disabled, true);
    assert.equal(s.ui.scanCancelButton.hidden, false);
    await s.scanCameras();
    assert.equal(calls, 1, "repeated clicks do not duplicate a pending scan");
    s.selectConnectionMode("ccapi");
    assert.equal(s.requests[0].options.signal.aborted, true);
    s.selectConnectionMode("usb");
    assert.equal(calls, 1, "returning to USB requires an explicit retry");
    const pendingFresh = s.scanCameras();
    if (rejected) old.reject({ status: 401, message: "late token failure" });
    else old.resolve({ cameras: [{ id: "OLD" }] });
    await pendingOld;
    assert.equal(s.state.scanState, "scanning");
    assert.equal(s.ui.scanButton.disabled, true);
    assert.equal(s.errors.length, 0);
    fresh.resolve({ cameras: [{ id: "NEW", engine: "edsdk" }] });
    await pendingFresh;
    assert.equal(s.state.cameras[0].id, "NEW");
    assert.equal(s.ui.connectButton.disabled, false);
    assert.equal(s.state.shutterDisconnectWarning, true);
  }
});

test("explicit cancellation and AbortError are quiet and do not trigger retry", async () => {
  const s = context(), pending = deferred();
  s.api = async (url, options) => { s.requests.push({ url, options }); return pending.promise; };
  const scan = s.scanCameras();
  s.cancelCameraScan();
  assert.equal(s.state.scanState, "cancelled");
  assert.equal(s.ui.scanCancelButton.hidden, true);
  const abort = new Error("synthetic cancelled"); abort.name = "AbortError";
  pending.reject(abort);
  await scan;
  s.showConnectionError(abort);
  assert.equal(s.errors.length, 0);
  assert.equal(s.requests.length, 1);
  assert.equal(s.ui.connectionStatus.textContent, "connectionScanCancelled");
});

test("invalid address, unavailable engine and repeated connect cannot create a session", async () => {
  const s = context();
  s.selectConnectionMode("ccapi");
  s.ui.ccapiUrlInput.value = "http://camera.invalid/ccapi";
  s.renderConnectionGuidance(); s.renderAvailability();
  assert.equal(s.ui.ccapiUrlInput.attributes["aria-invalid"], "true");
  assert.equal(s.ui.ccapiUrlValidation.textContent, "connectionInvalidOrigin");
  assert.equal(s.ui.connectButton.disabled, true);
  await s.connectCamera();
  assert.equal(s.requests.length, 0);
  s.ui.ccapiUrlInput.value = "http://camera.invalid";
  s.state.health = { engines: { ccapi: { available: false } } };
  s.renderConnectionGuidance(); s.renderAvailability();
  assert.equal(s.ui.connectionStatus.textContent, "connectionCcapiEngine");
  assert.equal(s.ui.connectButton.disabled, true);
  await s.connectCamera();
  assert.equal(s.requests.length, 0);
  s.state.health.engines.ccapi.available = true;
  const pending = deferred();
  s.api = async (url, options) => { s.requests.push({ url, options }); return pending.promise; };
  const connect = s.connectCamera();
  await s.connectCamera();
  assert.equal(s.requests.length, 1);
  pending.reject(cameraFailure("AUTHENTICATION_REJECTED"));
  await connect;
  assert.equal(s.ui.connectionError.textContent, "connectionCameraAuth");
  s.correctConnection();
  assert.equal(s.ui.cameraCredentials.open, true);
  assert.equal(s.ui.ccapiUsernameInput.focused, true);
  assert.equal(s.state.shutterDisconnectWarning, true);
  assert.equal(s.requests.length, 1, "fix action focuses inputs without retrying a camera request");
});

test("health failure requires explicit retry and clears only its connection guidance", async () => {
  const s = context();
  s.api = async (url, options) => { s.requests.push({ url, options }); throw { status: 0, code: "NETWORK_ERROR" }; };
  await s.refreshHealth();
  assert.equal(s.state.healthFailed, true);
  assert.equal(s.ui.connectionError.textContent, "connectionBridgeNetwork");
  assert.equal(s.ui.connectButton.disabled, true);
  s.api = async (url, options) => {
    s.requests.push({ url, options });
    return { authRequired: false, engines: { edsdk: { available: true } } };
  };
  await s.refreshHealth();
  assert.equal(s.state.healthFailed, false);
  assert.equal(s.ui.connectionError.hidden, true);
  assert.equal(s.requests.length, 2);
  assert.equal(s.requests.every(request => request.url === "/health"), true);
  assert.equal(s.state.shutterDisconnectWarning, true);
});

function expireHealthDeadline(s) {
  assert.equal(s.timers.size, 1, "the admitted health read must have one bounded deadline");
  const [id, timer] = [...s.timers][0];
  assert.equal(timer.delay, 15000);
  s.timers.delete(id);
  timer.callback();
}

function useProductionApi(s, fetch) {
  s.fetch = (url, options) => { s.requests.push({ url, options }); return fetch(url, options); };
  vm.runInContext(productionFunction("api"), s);
}

const readyHealth = () => ({ authRequired: false, engines: { libgphoto2: { available: true } } });
const healthResponse = health => ({ ok: true, status: 200, json: async () => health });

test("never-settling health fetch times out and permits a fresh explicit retry", async () => {
  const s = context();
  useProductionApi(s, () => new Promise(() => {}));
  const first = s.refreshHealth();
  assert.equal(s.ui.healthRetryButton.disabled, true);
  assert.equal(s.ui.scanButton.disabled, true);
  assert.equal(s.ui.connectButton.disabled, true);
  await s.refreshHealth();
  assert.equal(s.requests.length, 1, "pending reads are not duplicated");
  expireHealthDeadline(s);
  await first;
  assert.equal(s.requests[0].options.signal.aborted, true);
  assert.equal(s.state.healthAttempt, null);
  assert.equal(s.state.healthFailed, true);
  assert.equal(s.ui.healthRetryButton.disabled, false);
  assert.equal(s.ui.connectionError.textContent, "connectionBridgeTimeout");
  assert.equal(s.errors[0].code, "BRIDGE_HEALTH_TIMEOUT");
  assert.equal(s.timers.size, 0);
  assert.equal(s.requests.length, 1, "timeout must not automatically retry");
  const health = readyHealth();
  useProductionApi(s, async () => healthResponse(health));
  await s.refreshHealth();
  assert.equal(s.requests.length, 2);
  assert.equal(s.state.health, health);
  assert.equal(s.state.healthFailed, false);
  assert.equal(s.ui.connectionError.hidden, true);
  assert.equal(s.timers.size, 0);
});

for (const outcome of ["success", "failure"]) {
  for (const phase of ["pending", "completed"]) {
  test(`late timed-out health ${outcome} cannot overwrite or unlock a ${phase} newer read`, async () => {
    const s = context(), old = deferred(), fresh = deferred();
    let count = 0;
    useProductionApi(s, () => ++count === 1 ? old.promise : fresh.promise);
    const first = s.refreshHealth();
    expireHealthDeadline(s);
    await first;
    const second = s.refreshHealth();
    const owner = s.state.healthAttempt;
    const errorsBefore = s.errors.slice();
    const health = readyHealth();
    if (phase === "completed") { fresh.resolve(healthResponse(health)); await second; }
    if (outcome === "success") old.resolve(healthResponse({ authRequired: true, engines: {} }));
    else old.reject(new Error("Synthetic old network failure"));
    await new Promise(setImmediate);
    assert.equal(s.state.healthAttempt, phase === "pending" ? owner : null);
    assert.equal(s.ui.healthRetryButton.disabled, phase === "pending");
    assert.deepEqual(s.errors, errorsBefore);
    assert.equal(s.state.health, phase === "pending" ? null : health);
    assert.equal(s.timers.size, phase === "pending" ? 1 : 0, "old completion must not change the fresh deadline");
    if (phase === "pending") { fresh.resolve(healthResponse(health)); await second; }
    assert.equal(s.state.health, health);
    assert.equal(s.state.healthFailed, false);
    assert.equal(s.state.healthAttempt, null);
    assert.equal(s.timers.size, 0);
  });
  }
}

test("health response body stalls are bounded by the same deadline", async () => {
  const s = context(), body = deferred();
  useProductionApi(s, async () => ({ ok: true, status: 200, json: () => body.promise }));
  const pending = s.refreshHealth();
  await new Promise(setImmediate);
  expireHealthDeadline(s);
  await pending;
  assert.equal(s.state.healthFailed, true);
  assert.equal(s.ui.connectionError.textContent, "connectionBridgeTimeout");
  body.resolve(readyHealth());
  await new Promise(setImmediate);
  assert.equal(s.state.health, null);
  assert.equal(s.state.healthFailed, true);
});

test("timeout feedback wins over synchronous AbortError from the aborted request", async () => {
  const s = context();
  useProductionApi(s, (_, { signal }) => new Promise((_, reject) => {
    signal.addEventListener("abort", () => reject(Object.assign(new Error("Synthetic abort"), { name: "AbortError" })));
  }));
  const pending = s.refreshHealth();
  expireHealthDeadline(s);
  await pending;
  assert.equal(s.errors.length, 1);
  assert.equal(s.errors[0].code, "BRIDGE_HEALTH_TIMEOUT");
  assert.equal(s.state.healthFailed, true);
  assert.equal(s.ui.healthRetryButton.disabled, false);
});

test("guidance has unique English and Traditional Chinese copy and accessible inline controls", () => {
  const languageStart = source.indexOf('    "zh-TW": {');
  const languageSources = [source.slice(source.indexOf("    en: {"), languageStart),
    source.slice(languageStart, source.indexOf("\n  };", languageStart))];
  const keys = new Set([...languageSources[0].matchAll(/\b(connection[A-Z][A-Za-z]+): "/g)].map(match => match[1]));
  for (const key of keys) for (const language of languageSources) {
    assert.equal([...language.matchAll(new RegExp(`^\\s+${key}: "`, "gm"))].length, 1, key);
  }
  for (const id of ["ccapi-url-validation", "connection-status", "connection-fix-button", "scan-cancel-button"]) {
    assert.equal([...html.matchAll(new RegExp(`id="${id}"`, "g"))].length, 1, id);
  }
  assert.match(html, /aria-describedby="ccapi-url-help ccapi-url-validation"/);
  assert.match(html, /src="\/app\/connection-guidance\.js"/);
});

test("production API retains actual status and optional reason, and propagates body-read cancellation", async () => {
  const s = context();
  vm.runInContext(productionFunction("api"), s);
  for (const status of [502, 401, 503]) {
    s.fetch = async () => ({ ok: false, status, statusText: "Synthetic",
      json: async () => ({ error: { code: "CCAPI_DISCOVERY_FAILED", message: "forged 401",
        engine: "ccapi", connectionReason: "AUTHENTICATION_REJECTED" } }) });
    await assert.rejects(s.api("/v1/session", { method: "POST", json: {} }), error => {
      assert.equal(error.status, status);
      assert.equal(error.connectionReason, "AUTHENTICATION_REJECTED");
      assert.equal(guidance.failureGuidance(error)[0], status === 502 ? "connectionCameraAuth"
        : status === 401 ? "connectionBridgeAuth" : "connectionUnknownFailure");
      return true;
    });
  }
  const abort = new Error("synthetic body cancellation"); abort.name = "AbortError";
  s.fetch = async () => ({ ok: false, status: 502, json: async () => { throw abort; } });
  await assert.rejects(s.api("/v1/cameras"), error => error === abort);
});

test("release safety evidence outranks connection recovery and survives editing and scans", async () => {
  const s = context();
  s.state.shutterDisconnectWarning = false;
  s.showConnectionError({ status: 401, code: "SHUTTER_RELEASE_UNCONFIRMED" });
  assert.equal(s.ui.connectionError.textContent, "previousShutterWarning");
  assert.equal(s.state.shutterDisconnectWarning, true);
  s.selectConnectionMode("ccapi");
  assert.equal(s.ui.shutterDisconnectWarning.hidden, false);
  s.selectConnectionMode("usb");
  await s.scanCameras();
  assert.equal(s.ui.shutterDisconnectWarning.hidden, false);
  assert.equal(s.state.shutterDisconnectWarning, true);
});

test("a late scan cannot publish into a replacement session or render that session", async () => {
  const s = context(), pending = deferred();
  s.api = async () => pending.promise;
  const scan = s.scanCameras();
  const replacement = { id: "SYNTHETIC-REPLACEMENT" };
  s.state.session = replacement;
  s.state.capabilities = { supported: ["SYNTHETIC"] };
  s.ui.connectionStatus.textContent = "replacement owns this UI";
  pending.resolve({ cameras: [{ id: "OLD" }] });
  await scan;
  assert.equal(s.state.session, replacement);
  assert.equal(s.state.cameras.length, 0);
  assert.equal(s.ui.connectionStatus.textContent, "replacement owns this UI");
  assert.equal(s.state.capabilities.supported[0], "SYNTHETIC");
});

// Execute production connection owners with controlled transport completions.
// No camera, browser, process restart, elapsed-time sleeps or production test hook.
const flushConnection = () => new Promise(resolve => setImmediate(resolve));
function connectionContext() {
  const s = context();
  s.selectConnectionMode("ccapi");
  s.ui.connectionView.hidden = false;
  s.ui.controlView.hidden = true;
  s.ui.tokenInput.value = "SYNTHETIC-OLD-TOKEN";
  s.ui.ccapiUsernameInput.value = "synthetic-old-user";
  s.ui.ccapiPasswordInput.value = "synthetic-old-password";
  s.feedback = [];
  s.startEventLoop = () => s.feedback.push("events");
  s.refreshLatestMedia = () => s.feedback.push("latest-media");
  s.showToast = message => s.feedback.push(message);
  return s;
}
function connectionResponse(url) {
  if (url.endsWith("/info")) return { model: "SYNTHETIC" };
  if (url.endsWith("/status")) return { recording: false };
  if (url.endsWith("/capabilities")) return { supported: [], liveView: {} };
  throw new Error(`Unexpected synthetic request: ${url}`);
}

test("cancel before session reply unlocks settings, waits for exact cleanup, then requires explicit retry", async () => {
  const s = connectionContext(), old = deferred(), cleanup = deferred();
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    if (options.method === "POST") return old.promise;
    if (options.method === "DELETE") return cleanup.promise;
    return connectionResponse(url);
  };
  const connecting = s.connectCamera();
  const attempt = s.state.connectAttempt;
  assert.equal(s.ui.connectCancelButton.hidden, false);
  assert.equal(s.ui.cameraSelect.disabled, true);
  assert.equal(s.ui.tokenInput.disabled, true);
  await s.connectCamera();
  assert.equal(s.requests.length, 1);
  s.cancelCameraConnection(); s.cancelCameraConnection();
  assert.equal(s.state.connectAttempt, null);
  assert.equal(s.state.busy, false);
  assert.equal(s.state.session, null);
  assert.equal(s.ui.connectCancelButton.hidden, true);
  for (const field of ["tokenInput", "ccapiUrlInput", "ccapiUsernameInput", "ccapiPasswordInput", "usbModeButton", "ccapiModeButton"]) {
    assert.equal(s.ui[field].disabled, false, field);
  }
  assert.equal(s.ui.connectButton.disabled, true, "shared backend cannot overlap pending creation");
  assert.equal(s.ui.connectionCleanupNotice.textContent, "connectionCleanupPending");
  s.ui.tokenInput.value = "SYNTHETIC-NEW-TOKEN";
  s.ui.ccapiUrlInput.value = "http://new-camera.invalid:8081";
  s.ui.ccapiUsernameInput.value = "synthetic-new-user";
  s.ui.ccapiPasswordInput.value = "synthetic-new-password";
  s.window.location.href = "http://new-bridge.invalid/";
  s.state.token = "SYNTHETIC-MUTATED-TOKEN";
  s.selectConnectionMode("usb");
  await s.scanCameras();
  s.selectConnectionMode("ccapi");
  await s.connectCamera();
  assert.equal(s.requests.length, 1, "editing or retry during teardown never queues a connection");
  assert.equal(attempt.owner.engine, "ccapi");
  old.resolve({ id: "synthetic-old" });
  await flushConnection();
  assert.equal(s.requests.length, 2);
  assert.equal(s.requests[1].url, "http://bridge.invalid/v1/session/synthetic-old");
  assert.equal(s.requests[1].options.method, "DELETE");
  assert.equal(s.requests[1].options.authToken, "SYNTHETIC-OLD-TOKEN");
  assert.equal(s.requests[1].options.redirect, "error");
  assert.equal(s.requests[1].options.keepalive, true);
  assert.equal(s.requests[0].options.signal, undefined, "POST stays observed to recover its exact session ID");
  assert.equal(s.requests[0].options.json.ccapiUrl, "http://camera.invalid:8080");
  assert.equal(s.ui.connectButton.disabled, true, "DELETE acknowledgment is still pending");
  cleanup.resolve({ status: 204 });
  await connecting;
  assert.equal(s.state.connectionCleanupStatus, "acknowledged");
  assert.equal(s.ui.connectionCleanupNotice.textContent, "connectionCleanupAcknowledged");
  assert.equal(s.ui.controlView.hidden, true);
  assert.equal(s.ui.connectButton.disabled, false);
  assert.equal(s.ui.connectButton.querySelector().textContent, "connectionRetry");
  assert.deepEqual(s.feedback, []);
  assert.equal(s.requests.length, 2, "cleanup completion does not reconnect");
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    return options.method === "POST" ? { id: "synthetic-new" } : connectionResponse(url);
  };
  await s.connectCamera();
  assert.equal(s.state.session.id, "synthetic-new");
  assert.equal(s.requests[2].url, "http://new-bridge.invalid/v1/session");
  assert.equal(s.requests[2].options.authToken, "SYNTHETIC-NEW-TOKEN");
  assert.equal(s.requests[2].options.json.ccapiUrl, "http://new-camera.invalid:8081");
  assert.equal(s.requests[2].options.json.ccapiUsername, "synthetic-new-user");
  assert.equal(s.requests[2].options.json.ccapiPassword, "synthetic-new-password");
  assert.equal(s.ui.controlView.hidden, false);
  assert.equal(s.state.shutterDisconnectWarning, true, "connection cancellation never clears previous control safety evidence");
});

for (const lateError of [false, true]) test(`cancel during metadata drains all reads and discards late ${lateError ? "error" : "success"}`, async () => {
  const s = connectionContext(), info = deferred(), status = deferred(), capabilities = deferred();
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    if (options.method === "POST") return { id: "synthetic-old" };
    if (options.method === "DELETE") return { status: 204 };
    if (url.endsWith("/info")) return info.promise;
    if (url.endsWith("/status")) return status.promise;
    if (url.endsWith("/capabilities")) return capabilities.promise;
  };
  const connecting = s.connectCamera();
  await flushConnection();
  assert.equal(s.requests.length, 4);
  assert.equal(s.state.session, null, "uninitialized session is private to its attempt");
  s.cancelCameraConnection();
  s.ui.ccapiUrlInput.value = "http://edited-camera.invalid";
  if (lateError) info.reject(cameraFailure("AUTHENTICATION_REJECTED"));
  else info.resolve({ model: "SYNTHETIC-OLD" });
  status.resolve({ recording: false });
  await flushConnection();
  assert.equal(s.requests.length, 4, "first read error or completion cannot close beneath remaining reads");
  assert.equal(s.ui.connectButton.disabled, true);
  capabilities.resolve({ supported: [] });
  await connecting;
  assert.equal(s.requests.length, 5);
  assert.equal(s.requests[4].url, "http://bridge.invalid/v1/session/synthetic-old");
  assert.equal(s.state.session, null);
  assert.equal(s.state.info, undefined);
  assert.equal(s.state.capabilities, undefined);
  assert.equal(s.ui.ccapiUrlInput.value, "http://edited-camera.invalid");
  assert.equal(s.ui.controlView.hidden, true);
  assert.equal(s.errors.length, 0, "retired initialization cannot restore old failure");
  assert.deepEqual(s.feedback, []);
});

for (const lateError of [false, true]) test(`old cleanup ${lateError ? "failure" : "success"} cannot delete or unlock a replacement owner`, async () => {
  const s = connectionContext(), old = deferred(), cleanup = deferred();
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    return options.method === "POST" ? old.promise : cleanup.promise;
  };
  const connecting = s.connectCamera();
  s.cancelCameraConnection();
  old.resolve({ id: "synthetic-old" }); await flushConnection();
  // A separately installed replacement is adversarial; normal admission waits.
  const replacement = { id: "synthetic-new" }, nextAttempt = {};
  s.state.session = replacement;
  s.state.connectAttempt = nextAttempt;
  s.state.connectionRetirement = nextAttempt;
  s.state.connectionCleanupStatus = "replacement-owned";
  s.state.connectionFailure = ["replacement-failure", "address"];
  s.state.busy = true;
  s.state.token = "SYNTHETIC-NEW-TOKEN";
  s.ui.engineState.textContent = "replacement busy";
  s.ui.connectionStatus.textContent = "replacement status";
  if (lateError) cleanup.reject(new Error("synthetic old DELETE failure"));
  else cleanup.resolve({ status: 204 });
  await connecting;
  assert.equal(s.state.session, replacement);
  assert.equal(s.state.connectAttempt, nextAttempt);
  assert.equal(s.state.connectionRetirement, nextAttempt);
  assert.equal(s.state.connectionCleanupStatus, "replacement-owned");
  assert.equal(s.state.connectionFailure[0], "replacement-failure");
  assert.equal(s.state.busy, true);
  assert.equal(s.ui.engineState.textContent, "replacement busy");
  assert.equal(s.ui.connectionStatus.textContent, "replacement status");
  assert.equal(s.requests.length, 2);
  assert.equal(s.requests[1].url, "http://bridge.invalid/v1/session/synthetic-old");
  assert.equal(s.requests[1].options.authToken, "SYNTHETIC-OLD-TOKEN");
});

for (const rejection of [{ status: 401 }, { status: 422 }, cameraFailure("AUTHENTICATION_REJECTED")]) {
  test(`cancelled creation with explicit ${rejection.status}/${rejection.code || "HTTP"} rejection permits manual correction`, async () => {
    const s = connectionContext(), old = deferred();
    s.api = async (url, options) => { s.requests.push({ url, options }); return old.promise; };
    const connecting = s.connectCamera(); s.cancelCameraConnection(); old.reject(rejection); await connecting;
    assert.equal(s.state.connectionCleanupStatus, null);
    assert.equal(s.state.connectionRetirement, null);
    assert.equal(s.ui.connectButton.disabled, false);
    assert.equal(s.errors.length, 0);
    assert.equal(s.requests.length, 1);
    assert.equal(s.state.session, null);
  });
}

for (const outcome of ["lost-create", "malformed-create", "lost-delete", "unexpected-delete-success", "missing-delete"]) {
  test(`cancelled ${outcome} remains unconfirmed and cannot silently free the shared backend`, async () => {
    const s = connectionContext(), old = deferred();
    s.api = async (url, options = {}) => {
      s.requests.push({ url, options });
      if (options.method === "POST") return old.promise;
      if (outcome === "lost-delete") throw new Error("synthetic lost reply");
      if (outcome === "missing-delete") throw { status: 404, code: "SESSION_NOT_FOUND" };
      return { status: 200 };
    };
    const connecting = s.connectCamera(); s.cancelCameraConnection();
    if (outcome === "lost-create") old.reject({ status: 0, code: "NETWORK_ERROR" });
    else old.resolve(outcome === "malformed-create" ? { id: "" } : { id: "synthetic-old" });
    await connecting;
    assert.equal(s.state.connectionCleanupStatus, "unconfirmed");
    assert.equal(s.state.connectionRetirement, null);
    assert.equal(s.ui.connectionCleanupNotice.textContent, "connectionCleanupUnconfirmed");
    assert.equal(s.ui.tokenInput.disabled, false);
    assert.equal(s.ui.connectButton.disabled, true);
    const count = s.requests.length;
    s.selectConnectionMode("usb"); await s.scanCameras();
    s.selectConnectionMode("ccapi"); await s.connectCamera();
    assert.equal(s.requests.length, count);
    assert.equal(s.errors.length, 0);
  });
}

test("current metadata failure stays actionable while exact-session cleanup is pending", async () => {
  const s = connectionContext(), cleanup = deferred();
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    if (options.method === "POST") return { id: "synthetic-current" };
    if (options.method === "DELETE") return cleanup.promise;
    if (url.endsWith("/status")) throw { status: 401, code: "UNAUTHORIZED" };
    return connectionResponse(url);
  };
  const connecting = s.connectCamera(); await flushConnection();
  assert.equal(s.ui.connectionError.textContent, "connectionBridgeAuth");
  assert.equal(s.state.busy, false);
  assert.equal(s.ui.tokenInput.disabled, false);
  assert.equal(s.ui.connectButton.disabled, true);
  s.correctConnection(); assert.equal(s.ui.tokenInput.focused, true);
  s.ui.tokenInput.value = "SYNTHETIC-CORRECTED-TOKEN";
  cleanup.resolve({ status: 204 }); await connecting;
  assert.equal(s.ui.connectionError.textContent, "connectionBridgeAuth");
  assert.equal(s.ui.connectButton.disabled, false);
  assert.equal(s.ui.tokenInput.value, "SYNTHETIC-CORRECTED-TOKEN");
  assert.equal(s.requests.filter(row => row.options.method === "POST").length, 1);
});

test("session-creation rejection evidence never treats unknown transport or malformed response as a rejection", () => {
  for (const error of [{ status: 0, code: "NETWORK_ERROR" }, { status: 502, code: "RESPONSE_BODY_UNAVAILABLE" },
    { status: 500 }, { status: 503 }, { status: 201 }, { status: 302 }, { name: "AbortError" },
    { status: 502, code: "CCAPI_DISCOVERY_FAILED", engine: "libgphoto2" }]) {
    assert.equal(guidance.sessionCreationRejected(error), false);
  }
});

test("cancel controls are localized, page retirement is wired, and cleanup evidence is separate from shutter safety", () => {
  assert.match(html, /id="connect-cancel-button"[^>]*type="button"[^>]*hidden/);
  assert.match(html, /id="connection-cleanup-notice"[^>]*role="status"[^>]*aria-live="polite"/);
  assert.match(source, /ui\.connectCancelButton\.addEventListener\("click", cancelCameraConnection\)/);
  for (const event of ["pagehide", "beforeunload"]) {
    assert.match(source, new RegExp(`window\\.addEventListener\\("${event}", \\(\\) => \\{\\s+cancelCameraConnection\\(\\)`));
  }
  assert.doesNotMatch(productionFunction("cancelCameraConnection"), /\.abort\(|state\.shutterDisconnectWarning\s*=/);
  assert.doesNotMatch(productionFunction("retireConnectionAttempt"), /state\.token|state\.session\.id|ui\..*\.value/);
  assert.doesNotMatch(productionFunction("diagnosticReport"), /connectAttempt|connectionRetirement/);
});

test("every allowed terminal creation rejection is explicit and safety obligations outrank its status", () => {
  for (const status of [400, 401, 403, 404, 405, 409, 422, 429]) {
    assert.equal(guidance.sessionCreationRejected({ status }), true, String(status));
    for (const code of ["SHUTTER_RELEASE_UNCONFIRMED", "LIVE_VIEW_STOP_UNCONFIRMED", "RECORDING_COMMAND_UNCONFIRMED"]) {
      assert.equal(guidance.sessionCreationRejected({ status, code }), false, `${status}/${code}`);
    }
  }
  for (const status of [501, 503]) {
    assert.equal(guidance.sessionCreationRejected({ status, code: "ENGINE_UNAVAILABLE" }), true);
    assert.equal(guidance.sessionCreationRejected({ status, code: "OTHER" }), false);
  }
  assert.equal(guidance.sessionCreationRejected(cameraFailure("TIMEOUT")), true);
});

test("a cancelled creation with late release uncertainty retains its previous-camera safety warning", async () => {
  const s = connectionContext(), old = deferred(); s.state.shutterDisconnectWarning = false;
  s.api = async () => old.promise;
  const connecting = s.connectCamera(); s.cancelCameraConnection();
  old.reject({ status: 409, code: "SHUTTER_RELEASE_UNCONFIRMED" });
  await connecting;
  assert.equal(s.state.connectionCleanupStatus, "unconfirmed");
  assert.equal(s.state.shutterDisconnectWarning, true);
  assert.equal(s.ui.shutterDisconnectWarning.hidden, false);
  assert.equal(s.ui.connectButton.disabled, true);
  assert.equal(s.errors.length, 0);
});

const creationVectors = JSON.parse(fs.readFileSync(path.join(__dirname,
  "../../contracts/connection-lifecycle/v1/session-creation-rejection-vectors.json"), "utf8"));
for (const vector of creationVectors.vectors) test(`shared creation rejection: ${vector.id}`, () => {
  assert.equal(guidance.sessionCreationRejected(vector), vector.creationRejected);
});

test("USB cancellation captures its original backend and camera; a changed selection needs explicit retry", async () => {
  const s = connectionContext(), old = deferred();
  s.state.cameras = [{ id: "synthetic-usb-A", engine: "edsdk" }, { id: "synthetic-usb-B", engine: "libgphoto2" }];
  s.ui.cameraSelect.value = "synthetic-usb-A";
  s.selectConnectionMode("usb");
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    return options.method === "POST" ? old.promise : { status: 204 };
  };
  const connecting = s.connectCamera(); const attempt = s.state.connectAttempt;
  s.cancelCameraConnection();
  assert.equal(s.ui.cameraSelect.disabled, false);
  s.ui.cameraSelect.value = "synthetic-usb-B"; s.renderAvailability();
  await s.connectCamera(); assert.equal(s.requests.length, 1);
  old.resolve({ id: "synthetic-usb-session-A" }); await connecting;
  assert.equal(attempt.owner.engine, "edsdk");
  assert.equal(s.requests[0].options.json.engine, "edsdk");
  assert.equal(s.requests[0].options.json.cameraId, "synthetic-usb-A");
  assert.equal(s.requests[1].url, "http://bridge.invalid/v1/session/synthetic-usb-session-A");
  assert.equal(s.ui.cameraSelect.value, "synthetic-usb-B");
  assert.equal(s.ui.connectButton.disabled, false);
  assert.equal(s.requests.length, 2);
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    return options.method === "POST" ? { id: "synthetic-usb-session-B" } : connectionResponse(url);
  };
  await s.connectCamera();
  assert.equal(s.requests[2].options.json.engine, "libgphoto2");
  assert.equal(s.requests[2].options.json.cameraId, "synthetic-usb-B");
  assert.equal(s.state.session.id, "synthetic-usb-session-B");
});

for (const event of ["pagehide", "beforeunload"]) test(`${event} callback retires pending connection publication without an automatic restart`, async () => {
  const s = connectionContext(), old = deferred(), handlers = {};
  s.api = async (url, options = {}) => {
    s.requests.push({ url, options });
    return options.method === "POST" ? old.promise : { status: 204 };
  };
  const connecting = s.connectCamera();
  s.window.addEventListener = (name, handler) => { handlers[name] = handler; };
  for (const name of ["stopRtpAudio", "suspendMaintenanceForLifecycle", "cancelMediaUpload", "stopMediaAutoImport",
    "releaseHeldAutofocusForLifecycle", "releaseBulbForLifecycle", "cancelCameraFocusInfo", "cancelMediaDownload", "clearMediaThumbnails",
    "closeMediaPreview", "closeMediaDetails", "stopLocalVideo"]) s[name] = () => {};
  s.lutPreviewRenderer = null;
  const begin = source.indexOf(`    window.addEventListener("${event}"`);
  vm.runInContext(source.slice(begin, source.indexOf("\n    });", begin) + 8), s);
  handlers[event]();
  assert.equal(s.state.connectAttempt, null);
  assert.equal(s.state.connectionCancelled, true);
  assert.equal(s.state.busy, false);
  old.resolve({ id: "synthetic-old" }); await connecting;
  assert.equal(s.state.session, null);
  assert.equal(s.requests.length, 2);
  assert.equal(s.requests[1].options.method, "DELETE");
  assert.deepEqual(s.feedback, []);
});

const cleanupSafetyCodes = ["SHUTTER_RELEASE_UNCONFIRMED", "LIVE_VIEW_STOP_UNCONFIRMED", "RECORDING_COMMAND_UNCONFIRMED"];
for (const code of cleanupSafetyCodes) for (const stage of ["creation", "cleanup"]) for (const replacement of [false, true]) {
  test(`${stage} late ${code} retains explicit old-camera evidence${replacement ? " beside a replacement owner" : " after cancel"}`, async () => {
    const s = connectionContext(), creation = deferred(), cleanup = deferred();
    s.api = async (url, options = {}) => {
      s.requests.push({ url, options });
      return options.method === "POST" ? creation.promise : cleanup.promise;
    };
    const connecting = s.connectCamera(); s.cancelCameraConnection();
    if (stage === "cleanup") {
      creation.resolve({ id: "synthetic-old" }); await flushConnection();
      assert.equal(s.requests.length, 2);
    }
    const newSession = { id: "synthetic-replacement" }, nextAttempt = {}, newStatus = { recording: false };
    if (replacement) {
      s.state.session = newSession;
      s.state.status = newStatus;
      s.state.connectAttempt = nextAttempt;
      s.state.connectionRetirement = nextAttempt;
      s.state.connectionCleanupStatus = "replacement-owned";
      s.state.connectionFailure = ["replacement-error", "address"];
      s.state.busy = true;
    }
    const failure = { status: 409, code, message: "SYNTHETIC-PRIVATE-PROVIDER-MESSAGE" };
    (stage === "creation" ? creation : cleanup).reject(failure);
    await connecting;
    assert.equal(s.state.connectionCleanupWarnings.has(code), true);
    assert.equal(s.ui.connectionCleanupNotice.hidden, false);
    assert.match(s.ui.connectionCleanupNotice.textContent, new RegExp(guidance.cleanupWarningKey(code)));
    assert.doesNotMatch(s.ui.connectionCleanupNotice.textContent, /SYNTHETIC-PRIVATE|SYNTHETIC-OLD-TOKEN|synthetic-old/);
    assert.equal(s.state.shutterDisconnectWarning, true, "existing previous-control evidence is never cleared");
    assert.equal(s.errors.length, 0, "late safety evidence is separate from the current operation error");
    assert.equal(s.requests.length, stage === "creation" ? 1 : 2);
    assert.equal(s.requests.some(row => /synthetic-replacement/.test(row.url)), false, "no Stop or cleanup is transferred");
    if (replacement) {
      assert.equal(s.state.session, newSession);
      assert.equal(s.state.status, newStatus);
      assert.equal(s.state.connectAttempt, nextAttempt);
      assert.equal(s.state.connectionRetirement, nextAttempt);
      assert.equal(s.state.connectionCleanupStatus, "replacement-owned");
      assert.equal(s.state.connectionFailure[0], "replacement-error");
      assert.equal(s.state.busy, true);
      assert.equal(s.ui.connectionCleanupNotice.parentElement, s.ui.shutterButton.parentElement);
    } else {
      assert.equal(s.state.connectionCleanupStatus, "unconfirmed");
      assert.equal(s.ui.connectButton.disabled, true);
    }
  });
}

test("old-camera warnings are additive, deduplicated, whitelist-only and survive generic cleanup evidence changes", () => {
  const s = connectionContext(); s.state.shutterDisconnectWarning = false;
  for (const code of ["LIVE_VIEW_STOP_UNCONFIRMED", "RECORDING_COMMAND_UNCONFIRMED"]) {
    s.recordConnectionCleanupWarning(code); s.recordConnectionCleanupWarning(code);
  }
  for (const code of [null, {}, "__proto__", "constructor", "UNKNOWN", "SYNTHETIC-PRIVATE-PROVIDER-MESSAGE"]) {
    s.recordConnectionCleanupWarning(code);
    assert.equal(guidance.cleanupWarningKey(code), null);
  }
  assert.equal(s.state.connectionCleanupWarnings.size, 2);
  assert.equal(s.state.shutterDisconnectWarning, false, "recording/Live View does not invent a shutter control owner");
  s.state.connectionCleanupStatus = "acknowledged"; s.renderConnectionCleanup();
  assert.match(s.ui.connectionCleanupNotice.textContent, /connectionCleanupAcknowledged/);
  assert.match(s.ui.connectionCleanupNotice.textContent, /connectionPreviousLiveViewUnconfirmed/);
  assert.match(s.ui.connectionCleanupNotice.textContent, /connectionPreviousRecordingUnconfirmed/);
  s.clearConnectionError(); s.selectConnectionMode("usb");
  assert.equal(s.state.connectionCleanupWarnings.size, 2);
  assert.match(s.ui.connectionCleanupNotice.textContent, /connectionPreviousRecordingUnconfirmed/);
  assert.doesNotMatch(productionFunction("resetSession"), /connectionCleanupWarnings/);
});

test("recording and Live View old-camera warnings name the uncertainty in English and Traditional Chinese", () => {
  assert.match(source, /connectionPreviousRecordingUnconfirmed: "Previous camera: recording is unconfirmed and may still be active\./);
  assert.match(source, /connectionPreviousRecordingUnconfirmed: "前次相機：錄影狀態未確認，可能仍在錄影。/);
  assert.match(source, /connectionPreviousLiveViewUnconfirmed: "Previous camera: Live View stop is unconfirmed\./);
  assert.match(source, /connectionPreviousLiveViewUnconfirmed: "前次相機：尚未確認 Live View 已停止/);
  assert.doesNotMatch(productionFunction("recordConnectionCleanupWarning"), /\bapi\(|recordingRecovery|state\.status|state\.session\s*=/);
});
