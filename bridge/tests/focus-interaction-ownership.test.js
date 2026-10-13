"use strict";

// Execute the unchanged production handler with controlled replies and timers.
// No camera, DOM renderer, network request or elapsed-time sleep is involved.
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
function subject(action = "focus") {
  const reply = deferred(), timers = [], feedback = [], requests = [];
  const state = { session: { id: "synthetic-A" }, busy: false, refreshGeneration: 0, liveActive: true, status: { owner: "A" } };
  const ui = { focusReticle: { style: {}, className: "", hidden: true } };
  const sandbox = vm.createContext({ state, ui, reply, timers, feedback, requests,
    effectiveTapAction: () => action, cameraInteractionBusy: () => state.busy, FEATURES: { AUTOFOCUS: "AUTOFOCUS", SHUTTER_HALF_PRESS: "SHUTTER_HALF_PRESS" }, featureSupported: () => true, pauseLivePolling() {},
    localPreviewSelected: () => false,
    beginCameraInteraction: () => { state.busy = true; state.refreshGeneration += 1; },
    api: (url, options) => { requests.push({ url, options }); return reply.promise; },
    setOperationState: (...args) => feedback.push(args), showToast: (...args) => feedback.push(args),
    renderAvailability() {}, renderSession() {}, t: key => key,
    captureError: error => ({ message: error.message, code: error.code }),
    window: { setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout() {} },
  });
  vm.runInContext(["tapFocus", "autofocus", "halfPressShutter", "beginCameraInteraction"].map(productionFunction).join("\n"), sandbox);
  return sandbox;
}
const point = { x: 0.5, y: 0.5, displayX: 10, displayY: 10 };
function replace(s) {
  s.state.session = { id: "synthetic-B" };
  s.state.status = { owner: "B" };
  s.state.busy = true;
  s.ui.focusReticle.className = "new-owner-marker";
  s.ui.focusReticle.hidden = false;
  s.feedback.length = 0;
}
test("current tap reply completes exactly one command", async () => {
  const s = subject(); const run = s.tapFocus(point); s.reply.resolve({}); await run;
  assert.equal(s.requests.length, 1); assert.equal(s.state.busy, false);
  assert.equal(s.timers.length, 1);
});
test("old tap success does not unlock or redraw a replacement session", async () => {
  const s = subject(); const run = s.tapFocus(point); replace(s); s.reply.resolve({}); await run;
  assert.equal(s.state.busy, true); assert.equal(s.ui.focusReticle.className, "new-owner-marker");
  assert.equal(s.feedback.length, 0); assert.equal(s.timers.length, 0);
});
test("old tap failure does not unlock or report into a replacement session", async () => {
  const s = subject(); const run = s.tapFocus(point); replace(s); s.reply.reject(new Error("old failure")); await run;
  assert.equal(s.state.busy, true); assert.equal(s.feedback.length, 0);
  assert.equal(s.ui.focusReticle.className, "new-owner-marker");
});
test("old click-white-balance response cannot replace new status", async () => {
  const s = subject("whiteBalance"); const run = s.tapFocus(point); replace(s);
  s.reply.resolve({ owner: "A-late" }); await run;
  assert.equal(s.state.status.owner, "B"); assert.equal(s.state.busy, true);
});
test("old marker expiry cannot hide a replacement session marker", async () => {
  const s = subject(); const run = s.tapFocus(point); s.reply.resolve({}); await run;
  const expire = s.timers[0]; replace(s); expire();
  assert.equal(s.ui.focusReticle.hidden, false);
});

for (const command of ["autofocus", "halfPressShutter"]) {
  test(`${command}: replacement object with reused session ID rejects late status`, async () => {
    const s = subject(); const run = s[command](); replace(s);
    s.state.session.id = "synthetic-A";
    s.reply.resolve({ owner: "A-late" }); await run;
    assert.equal(s.state.status.owner, "B"); assert.equal(s.state.busy, true);
    assert.equal(s.feedback.length, 0);
  });
  test(`${command}: current release failure keeps the safety interlock`, async () => {
    const s = subject(); const run = s[command]();
    const error = new Error("stop uncertain"); error.code = "SHUTTER_RELEASE_UNCONFIRMED";
    s.reply.reject(error); await run;
    assert.equal(s.state.shutterReleaseUnconfirmed, true);
    assert.equal(s.state.busy, false); assert.equal(s.requests.length, 1);
  });
}
test("same-session later interaction owns busy and retires earlier tap response", async () => {
  const s = subject(); const run = s.tapFocus(point); s.beginCameraInteraction();
  s.ui.focusReticle.className = "new-owner-marker"; s.feedback.length = 0;
  s.reply.resolve({}); await run;
  assert.equal(s.state.busy, true); assert.equal(s.feedback.length, 0);
  assert.equal(s.ui.focusReticle.className, "new-owner-marker");
});
test("same-session later interaction retires an earlier marker timer", async () => {
  const s = subject(); const run = s.tapFocus(point); s.reply.resolve({}); await run;
  s.beginCameraInteraction();
  assert.equal(s.ui.focusReticle.hidden, true); s.timers[0]();
  assert.equal(s.ui.focusReticle.hidden, true);
});
test("disconnect before white-balance reply cannot recreate status", async () => {
  const s = subject("whiteBalance"); const run = s.tapFocus(point);
  s.state.session = null; s.state.status = null; s.state.busy = false;
  s.reply.resolve({ owner: "A-late" }); await run;
  assert.equal(s.state.status, null); assert.equal(s.state.busy, false);
});

test("tap reply rejects a replacement object even when the backend ID is reused", async () => {
  const s = subject(); const run = s.tapFocus(point); replace(s); s.state.session.id = "synthetic-A";
  s.reply.resolve({}); await run;
  assert.equal(s.state.busy, true); assert.equal(s.feedback.length, 0);
});
test("current click white balance still applies returned status", async () => {
  const s = subject("whiteBalance"); const run = s.tapFocus(point);
  s.reply.resolve({ owner: "current-WB" }); await run;
  assert.equal(s.state.status.owner, "current-WB"); assert.equal(s.state.busy, false);
  assert.equal(s.ui.focusReticle.className, "focus-reticle success");
});
test("current tap acknowledgement is neutral and its own timer hides it", async () => {
  const s = subject(); const run = s.tapFocus(point); s.reply.resolve({}); await run;
  assert.equal(s.ui.focusReticle.className, "focus-reticle accepted");
  assert.equal(s.ui.focusReticle.hidden, false); s.timers[0]();
  assert.equal(s.ui.focusReticle.hidden, true);
});
test("current tap failure reports once and cleans only its own marker", async () => {
  const s = subject(); const run = s.tapFocus(point); s.reply.reject(new Error("current failure")); await run;
  assert.equal(s.state.busy, false); assert.equal(s.ui.focusReticle.className, "focus-reticle failure");
  assert.equal(s.feedback.filter(row => row[0] === "current failure").length, 2);
  assert.equal(s.requests.length, 1); s.timers[0](); assert.equal(s.ui.focusReticle.hidden, true);
});
for (const command of ["autofocus", "halfPressShutter"]) {
  test(`${command}: same-session newer operation retains busy after late reply`, async () => {
    const s = subject(); const run = s[command](); s.beginCameraInteraction(); s.feedback.length = 0;
    s.reply.resolve({ owner: "late" }); await run;
    assert.equal(s.state.status.owner, "A"); assert.equal(s.state.busy, true);
    assert.equal(s.feedback.length, 0);
  });
  test(`${command}: current success applies status and does not repeat`, async () => {
    const s = subject(); const run = s[command](); s.reply.resolve({ owner: "current" }); await run;
    assert.equal(s.state.status.owner, "current"); assert.equal(s.state.busy, false);
    assert.equal(s.requests.length, 1);
  });
}
test("busy and disconnected controls do not send a tap request", async () => {
  const s = subject(); s.state.busy = true; await s.tapFocus(point);
  s.state.busy = false; s.state.session = null; await s.tapFocus(point);
  assert.equal(s.requests.length, 0); assert.equal(s.timers.length, 0);
});

test("new tap marker survives an earlier tap expiry in the same session", async () => {
  const s = subject(); const first = s.tapFocus(point); s.reply.resolve({}); await first;
  const expireOld = s.timers[0]; await s.tapFocus(point);
  assert.equal(s.ui.focusReticle.hidden, false); expireOld();
  assert.equal(s.ui.focusReticle.hidden, false);
  s.timers[1](); assert.equal(s.ui.focusReticle.hidden, true);
});

for (const command of ["autofocus", "halfPressShutter"]) {
  test(`${command}: a retired session release error does not poison the replacement`, async () => {
    const s = subject(); const run = s[command](); replace(s); s.state.session.id = "synthetic-A";
    const error = new Error("old stop uncertain"); error.code = "SHUTTER_RELEASE_UNCONFIRMED";
    s.reply.reject(error); await run;
    assert.equal(s.state.shutterReleaseUnconfirmed, undefined);
    assert.equal(s.state.status.owner, "B"); assert.equal(s.state.busy, true);
    assert.equal(s.feedback.length, 0);
  });
}
