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
  vm.runInContext(["adoptCameraStatus", "tapFocus", "autofocus", "halfPressShutter", "beginCameraInteraction"].map(productionFunction).join("\n"), sandbox);
  return sandbox;
}
// These regressions add the unchanged production API parser to the handler
// harness. Only fetch is synthetic; do not replace its 502 with a plain Error.
function wireSubject(action = "focus") {
  const s = subject(action);
  s.Error = Error;
  s.Headers = Headers;
  s.mediaTransfer = { isAbortError: error => error?.name === "AbortError" };
  s.errors = [];
  s.captureError = error => { s.errors.push(error); return error; };
  s.fetch = (url, options) => { s.requests.push({ url, options }); return s.reply.promise; };
  const start = source.indexOf("  class ApiError extends Error {");
  assert.notEqual(start, -1);
  const apiError = source.slice(start, source.indexOf("\n  }", start) + 4);
  vm.runInContext(apiError + "\n" + productionFunction("api"), s);
  return s;
}
const interrupted = { status: 502, body: { error: { code: "CCAPI_UNREACHABLE",
  message: "The camera CCAPI response was interrupted; command completion is unknown." } } };
function wireReply(s, body, status = 200) {
  s.reply.resolve(new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } }));
}
async function turns() { for (let i = 0; i < 5; i++) await new Promise(setImmediate); }
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

for (const action of ["focus", "whiteBalance"]) {
  const endpoint = action === "whiteBalance" ? "whitebalance/click" : "focus/tap";
  const accepted = action === "whiteBalance" ? "clickWhiteBalanceAccepted" : "focusAccepted";

  test(`${action}: interrupted CCAPI body retains unknown completion without acceptance or automatic retry`, async () => {
    const s = wireSubject(action); const run = s.tapFocus(point);
    await s.tapFocus(point);
    assert.equal(s.requests.length, 1, "a pending tap must not send a second mutation");
    wireReply(s, interrupted.body, interrupted.status); await run; await turns();
    assert.equal(s.requests.length, 1);
    assert.equal(s.requests[0].url, `/v1/session/synthetic-A/${endpoint}`);
    assert.equal(s.requests[0].options.method, "POST");
    assert.equal(s.requests[0].options.body, JSON.stringify({ x: point.x, y: point.y }));
    assert.equal(s.state.busy, false); assert.equal(s.state.status.owner, "A");
    assert.equal(s.errors.length, 1);
    assert.equal(s.errors[0].name, "ApiError"); assert.equal(s.errors[0].status, 502);
    assert.equal(s.errors[0].code, interrupted.body.error.code);
    assert.equal(s.errors[0].message, interrupted.body.error.message);
    assert.deepEqual(s.feedback, [["busy"], [interrupted.body.error.message, true],
      [interrupted.body.error.message, true]]);
    assert.equal(s.feedback.some(row => row[0] === "focusAccepted" || row[0] === "clickWhiteBalanceAccepted"), false);
    // The existing failure marker is transient generic feedback, not a typed or
    // durable uncertainty state, and cannot prove that the mutation was rejected.
    assert.equal(s.ui.focusReticle.className, "focus-reticle failure");
    assert.equal(s.ui.focusReticle.hidden, false); assert.equal(s.timers.length, 1);
    s.timers[0](); await turns();
    assert.equal(s.ui.focusReticle.hidden, true);
    assert.equal(s.requests.length, 1); assert.equal(s.errors.length, 1);
    assert.equal(s.feedback.length, 3);
  });

  test(`${action}: a later explicit tap remains possible after unknown completion`, async () => {
    const s = wireSubject(action); const first = s.tapFocus(point);
    wireReply(s, interrupted.body, interrupted.status); await first; await turns();
    assert.equal(s.requests.length, 1);
    // No automatic retry and no permanent retry fence: this is a new user action.
    s.reply = deferred();
    const next = s.tapFocus(point);
    assert.equal(s.requests.length, 2);
    assert.equal(s.requests[1].url, `/v1/session/synthetic-A/${endpoint}`);
    assert.equal(s.requests[1].options.method, "POST");
    assert.equal(s.requests[1].options.body, JSON.stringify({ x: point.x, y: point.y }));
    wireReply(s, action === "whiteBalance" ? { owner: "current-WB" } : {}); await next; await turns();
    assert.equal(s.state.busy, false); assert.equal(s.requests.length, 2);
    assert.equal(s.errors.length, 1);
    assert.equal(s.ui.focusReticle.className, action === "whiteBalance" ? "focus-reticle success" : "focus-reticle accepted");
    assert.equal(s.feedback.filter(row => row[0] === accepted).length, 1);
    assert.equal(s.state.status.owner, action === "whiteBalance" ? "current-WB" : "A");
    assert.equal(s.timers.length, 2);
    s.timers[0]();
    assert.equal(s.ui.focusReticle.hidden, false, "the old failure timer must not hide the newer acknowledgement");
    s.timers[1](); await turns();
    assert.equal(s.ui.focusReticle.hidden, true); assert.equal(s.requests.length, 2);
  });

  for (const retirement of ["replacement-session", "reused-session-id", "newer-interaction", "disconnect"]) {
    test(`${action}: ${retirement} suppresses a late unknown-completion envelope`, async () => {
      const s = wireSubject(action); const run = s.tapFocus(point);
      if (retirement === "replacement-session" || retirement === "reused-session-id") {
        replace(s);
        if (retirement === "reused-session-id") s.state.session.id = "synthetic-A";
      } else if (retirement === "newer-interaction") {
        s.beginCameraInteraction(); s.state.status = { owner: "new-interaction" };
        s.ui.focusReticle.className = "new-owner-marker"; s.ui.focusReticle.hidden = false;
        s.feedback.length = 0;
      } else {
        s.state.session = null; s.state.status = null; s.state.busy = false;
        s.ui.focusReticle.className = "disconnected-marker"; s.ui.focusReticle.hidden = true;
        s.feedback.length = 0;
      }
      const current = { session: s.state.session, status: s.state.status, busy: s.state.busy,
        marker: s.ui.focusReticle.className, hidden: s.ui.focusReticle.hidden };
      wireReply(s, interrupted.body, interrupted.status); await run; await turns();
      assert.equal(s.requests.length, 1);
      assert.equal(s.requests[0].url, `/v1/session/synthetic-A/${endpoint}`);
      assert.equal(s.state.session, current.session); assert.equal(s.state.status, current.status);
      assert.equal(s.state.busy, current.busy);
      assert.equal(s.ui.focusReticle.className, current.marker); assert.equal(s.ui.focusReticle.hidden, current.hidden);
      assert.equal(s.feedback.length, 0); assert.equal(s.errors.length, 0); assert.equal(s.timers.length, 0);
      await turns(); assert.equal(s.requests.length, 1);
    });
  }
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
