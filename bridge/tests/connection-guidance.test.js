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
  const requests = [], errors = [];
  const sandbox = vm.createContext({ state, ui, requests, errors, URL, AbortController, Headers, Error,
    connectionGuidance: guidance, document: { createElement: element, querySelector: element, querySelectorAll: () => [] },
    mediaTransfer: { isAbortError: (error) => error?.name === "AbortError" },
    FEATURES: new Proxy({}, { get: (_, name) => name }),
    t: key => key, api: async (url, options) => { requests.push({ url, options }); return { cameras: [] }; },
    captureError: error => { errors.push(error); return error; },
    bulbControlLocked: () => false, cameraInteractionBusy: () => state.busy,
    featureSupported: () => false, isBulbMode: () => false, temperatureAllows: () => true,
    shutterAFSupported: () => false, canChangeShutterAF: () => false, localPreviewSelected: () => false,
    shutterReleaseUnconfirmed: () => false, effectiveTapAction: () => null,
    renderLiveMagnification() {}, renderMediaTransfer() {}, renderLatestMedia() {}, renderMediaDetails() {},
    clampFps: value => value, captureModeFromCamera: () => "photo", syncLiveMagnificationFromCapabilities() {},
    setOperationState() {}, writeCameraPreference() {}, renderSession() {}, startEventLoop() {},
    refreshLatestMedia() {}, showToast() {}, CCAPI_URL_KEY: "test-url", CCAPI_USERNAME_KEY: "test-user",
  });
  vm.runInContext(["showConnectionError", "clearConnectionError", "renderConnectionGuidance", "correctConnection",
    "renderHealth", "validCcapiUrl", "renderConnectionMode", "selectConnectionMode", "renderCameras",
    "recordingRecoveryActive", "refreshHealth", "cancelCameraScan", "scanCameras", "connectCamera", "renderAvailability"]
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
  vm.runInContext(source.slice(source.indexOf("  class ApiError extends Error"),
    source.indexOf("  function resolvedLanguage")) + productionFunction("api"), s);
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
