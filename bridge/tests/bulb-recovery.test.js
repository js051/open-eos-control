"use strict";

// Exercise production control-flow functions without a DOM package. Real-browser coverage
// lives in bulb-recovery.browser.test.js; these are intentionally narrower client contracts.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");

function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, `Missing production function ${name}`);
  const tail = source.slice(start + 1);
  const end = tail.search(/\n  (?:async )?function /);
  assert.notEqual(end, -1);
  return source.slice(start, start + 1 + end);
}

function context(status = {}) {
  const state = {
    session: { id: "synthetic-session" }, captureMode: "photo", busy: false,
    status: { mode: "Bulb", bulbExposureActive: false, shutterReleaseUnconfirmed: false, ...status },
    shutterReleaseUnconfirmed: false,
  };
  const requests = [];
  const feedback = [];
  const element = () => ({
    classList: { toggle() {} }, setAttribute(name, value) { this[name] = value; }, hidden: true,
  });
  const ui = {
    shutterButton: element(), shutterLabel: element(), photoModeButton: element(),
    videoModeButton: element(), recordIndicator: element(),
  };
  const sandbox = vm.createContext({
    state, ui, requests, feedback,
    FEATURES: { BULB_EXPOSURE: "BULB_EXPOSURE", VIDEO_RECORDING: "VIDEO_RECORDING" },
    featureSupported: () => true,
    isBulbMode: () => state.status.mode === "Bulb",
    captureModeFromCamera: () => null,
    cameraInteractionBusy: () => state.busy,
    beginCameraInteraction: () => { state.busy = true; },
    t: (key) => key,
    pauseLivePolling: () => feedback.push("pause"),
    resumeLivePolling: () => feedback.push("resume"),
    flashCapture: () => feedback.push("flash"),
    renderAvailability() {}, renderSession() {}, replaceButtonIcon() {},
    setOperationState: (message) => feedback.push(message),
    showToast: (message) => feedback.push(message),
    captureError: (error) => error,
    api: async (url, options) => {
      requests.push([url, options?.method || "GET"]);
      return { bulbExposureActive: false, shutterReleaseUnconfirmed: false, mode: "Manual" };
    },
  });
  vm.runInContext([
    "shutterReleaseUnconfirmed", "bulbControlLocked", "renderCaptureMode", "operateShutter",
  ].map(productionFunction).join("\n"), sandbox);
  return sandbox;
}

async function run() {
  {
    const test = context({ bulbExposureActive: true, mode: "Manual" });
    test.featureSupported = () => false;
    test.renderCaptureMode();
    assert.equal(test.ui.shutterButton["aria-label"], "stopBulb");
    await test.operateShutter();
    assert.deepEqual(test.requests, [["/v1/session/synthetic-session/bulb/stop", "POST"]]);
    assert.equal(test.state.status.bulbExposureActive, false);
  }
  {
    const test = context();
    test.api = async (url, options) => {
      test.requests.push([url, options?.method || "GET"]);
      throw { code: "SHUTTER_RELEASE_UNCONFIRMED", message: "Synthetic release failed" };
    };
    await test.operateShutter();
    assert.equal(test.state.shutterReleaseUnconfirmed, true);
    assert.equal(test.feedback.includes("resume"), false);
    test.state.status = { mode: "Manual", bulbExposureActive: false, shutterReleaseUnconfirmed: false };
    test.featureSupported = () => false;
    test.renderCaptureMode();
    assert.equal(test.ui.shutterButton["aria-label"], "retryBulbStop");
    await test.operateShutter();
    assert.deepEqual(test.requests.map(([url]) => url.split("/").slice(-2).join("/")), [
      "bulb/start", "synthetic-session/status", "bulb/stop", "synthetic-session/status",
    ]);
    assert.equal(test.state.shutterReleaseUnconfirmed, true);
  }
  {
    const test = context({ bulbExposureActive: null, shutterReleaseUnconfirmed: true });
    test.state.shutterReleaseUnconfirmed = true;
    test.api = async (url, options) => {
      test.requests.push([url, options?.method || "GET"]);
      if (url.endsWith("/bulb/stop")) throw { code: "CCAPI_UNREACHABLE", message: "Status tail failed" };
      return { mode: "Manual", bulbExposureActive: false, shutterReleaseUnconfirmed: false };
    };
    await test.operateShutter();
    assert.equal(test.state.shutterReleaseUnconfirmed, false);
    assert.equal(test.shutterReleaseUnconfirmed(), false);
    assert.equal(test.feedback.includes("bulbReleaseConfirmed"), true);
    assert.equal(test.feedback.includes("flash"), false);
    assert.equal(test.requests.length, 2);
  }
  {
    const test = context({ bulbExposureActive: null, shutterReleaseUnconfirmed: true });
    test.state.shutterReleaseUnconfirmed = true;
    test.api = async (url) => {
      if (url.endsWith("/bulb/stop")) throw { code: "NETWORK_ERROR", message: "Lost response" };
      return { mode: "Manual", bulbExposureActive: false }; // Old server omits new proof field.
    };
    await test.operateShutter();
    assert.equal(test.state.shutterReleaseUnconfirmed, true);
    assert.equal(test.feedback.includes("bulbReleaseConfirmed"), false);
  }
  {
    const test = context({ bulbExposureActive: true });
    const freshStatus = { mode: "Manual", bulbExposureActive: false };
    test.api = async () => {
      test.state.session = { id: "new-synthetic-session" };
      test.state.status = freshStatus;
      return { bulbExposureActive: null, shutterReleaseUnconfirmed: true };
    };
    await test.operateShutter();
    assert.equal(test.state.status, freshStatus);
    assert.equal(test.state.shutterReleaseUnconfirmed, false);
    assert.equal(test.feedback.includes("bulbStopped"), false);
  }
  {
    const test = context({ bulbExposureActive: true });
    const freshStatus = { mode: "Manual", bulbExposureActive: false };
    test.api = async (url) => {
      if (url.endsWith("/bulb/stop")) throw { code: "NETWORK_ERROR", message: "Lost stop response" };
      test.state.session = { id: "new-synthetic-session" };
      test.state.status = freshStatus;
      test.state.shutterReleaseUnconfirmed = false;
      throw { code: "NETWORK_ERROR", message: "Old refresh failed late" };
    };
    await test.operateShutter();
    assert.equal(test.state.status, freshStatus);
    assert.equal(test.state.shutterReleaseUnconfirmed, false);
    assert.equal(test.feedback.includes("resume"), false);
  }
  console.log("PASS: production PC Bulb state contracts (mode/capability change, sticky failure, fresh Stop readback, legacy omission, session isolation)");
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
