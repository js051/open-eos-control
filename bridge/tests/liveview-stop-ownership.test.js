"use strict";

// Actual UI functions with controlled replies; not browser or camera evidence.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function body(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, name);
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function fixture() {
  const state = { session: { id: "synthetic-A", engine: "ccapi" }, liveActive: true, liveGeneration: 1,
    liveSource: "CCAPI_RTP", activeLiveSource: "CCAPI_RTP", requestedFps: 15,
    previewInput: "CAMERA", localVideoSupport: { available: true }, busy: false, refreshGeneration: 0,
    status: {}, frameTimes: [], maintenanceSequence: 0, maintenanceHistory: [] };
  const requests = [], messages = [], pending = [];
  const context = vm.createContext({ state, AbortController, setTimeout, clearTimeout,
    maintenanceLifecycle: require("../open_eos_bridge/static/maintenance-lifecycle.js"), mediaTransferActive: () => false,
    renderAdvancedSettings() {},
    ui: { fpsSelect: { value: "30" }, liveSourceSelect: { value: "CCAPI_JPEG_POLLING" },
      previewInputSelect: { value: "LOCAL_VIDEO" } },
    FEATURES: { LIVE_VIEW: "LIVE_VIEW", CAMERA_SLEEP: "CAMERA_SLEEP", SENSOR_CLEANING: "SENSOR_CLEANING" },
    localPreviewSelected: () => state.previewInput === "LOCAL_VIDEO",
    previewActive: () => state.liveActive || state.localVideoActive,
    cameraInteractionBusy: () => state.busy, bulbControlLocked: () => false, recordingRecoveryActive: () => false,
    featureSupported: () => true, clampFps: Number,
    liveCapabilities: () => ({ defaultSize: "MEDIUM" }),
    beginCameraInteraction: () => { state.busy = true; },
    stopLiveLoop: () => { state.liveActive = false; state.activeLiveSource = null; state.liveGeneration++; },
    stopLocalVideo() {}, startLocalVideo: async () => { state.localVideoActive = true; },
    renderAvailability() {}, renderLiveState() {}, renderFps() {}, renderFrameIndicator() {}, renderPreviewInput() {},
    syncLiveMagnificationFromCapabilities() {}, pollLiveView() {}, refreshRtpAudioStatus() {},
    stopEventLoop: async () => {}, startEventLoop() {}, refreshCapabilityEvidence: async () => {}, renderSession() {},
    resetSession: () => { state.session = null; state.liveViewStopOwner = null; },
    captureError: error => error, t: key => key, showToast: message => messages.push(message),
    setOperationState: message => messages.push(message),
    api: (url, options) => { const gate = deferred(); requests.push({ url, options }); pending.push(gate); return gate.promise; },
  });
  const names = ["maintenanceHeld", "maintenancePaused", "maintenanceCurrent", "maintenancePublishable",
    "maintenanceSafetyClear", "maintenanceOtherBusy", "cancelMaintenance", "suspendMaintenanceForLifecycle", "maintenanceControlRequest", "maintenanceOwnedControl", "maintenanceStopRetryAvailable", "disconnectMaintenanceCamera", "runMaintenance", "maintenanceRead",
    "recoverMaintenance", "restoreMaintenance", "adoptCameraStatus", "beginCameraInteraction", "refreshSession", "stopLiveView", "startLiveView", "toggleLiveView", "changeFps", "changeLiveSource", "changePreviewInput",
    "sleepCamera", "cleanSensor"];
  if (/function liveViewStopRequired\(/.test(source)) names.push("liveViewStopRequired");
  context.captureConfiguration = { transfer: async (url, options) => ({ status: 200, body: await context.api(url, options) }) };
  vm.runInContext(names.map(body).join("\n"), context);
  return Object.assign(context, { requests, pending, messages });
}
async function turns() { for (let i = 0; i < 8; i++) await new Promise(setImmediate); }
for (const method of ["changeFps", "changeLiveSource", "changePreviewInput"]) {
  test(`${method} does not start another source after failed stop`, async () => {
    const f = fixture();
    const operation = f[method]();
    f.pending[0].reject(new Error("synthetic stop failed"));
    await turns();
    // Drain a forbidden start as well, so baseline failure does not leak a task.
    for (const gate of f.pending.slice(1)) gate.resolve({ active: false, liveViewStopUnconfirmed: false });
    await operation;
    assert.equal(f.requests.length, 1);
    assert.equal(f.state.previewInput, "CAMERA");
    assert.equal(f.state.localVideoActive, undefined);
  });
}
test("failed stop remains retryable using the same session and only Stop", async () => {
  const f = fixture();
  const first = f.stopLiveView();
  f.pending[0].reject(new Error("synthetic stop failed"));
  await first;
  const retry = f.toggleLiveView();
  assert.match(f.requests[1].url, /synthetic-A\/liveview\/stop$/);
  f.pending[1].resolve({ active: false, liveViewStopUnconfirmed: false });
  await retry;
  assert.equal(f.state.liveActive, false);
});
test("failed start retains a stop-only recovery path", async () => {
  const f = fixture(); f.state.liveActive = false;
  const start = f.startLiveView();
  f.pending[0].reject(new Error("synthetic lost start response"));
  await start;
  const retry = f.toggleLiveView();
  assert.match(f.requests[1].url, /liveview\/stop$/);
  f.pending[1].resolve({ active: false, liveViewStopUnconfirmed: false }); await retry;
});
for (const outcome of ["success", "failure"]) {
  test(`old stop ${outcome} cannot clear replacement state or feedback`, async () => {
    const f = fixture(); const stop = f.stopLiveView();
    const replacement = { id: "synthetic-A" }; // Reused wire ID is not ownership.
    f.state.session = replacement; f.state.liveActive = true; f.state.busy = true;
    f.state.liveViewStopOwner = { session: replacement }; f.messages.length = 0;
    if (outcome === "success") f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false });
    else f.pending[0].reject(new Error("old error"));
    await stop;
    assert.equal(f.state.busy, true);
    assert.equal(f.state.liveViewStopOwner.session, replacement);
    assert.deepEqual(f.messages, []);
  });
}
for (const method of ["changeFps", "changeLiveSource"]) {
  test(`${method} still restarts after confirmed stop`, async () => {
    const f = fixture(); const operation = f[method]();
    f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await turns();
    assert.equal(f.requests.length, 2);
    assert.match(f.requests[1].url, /liveview\/start$/);
    f.pending[1].resolve({ active: true, source: "CCAPI_JPEG_POLLING", requestedFps: 30 });
    await operation; assert.equal(f.state.liveActive, true);
  });
}
test("confirmed camera stop permits explicitly selected local preview", async () => {
  const f = fixture(); const operation = f.changePreviewInput();
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await operation;
  assert.equal(f.state.previewInput, "LOCAL_VIDEO");
  assert.equal(f.state.localVideoActive, true);
  assert.equal(f.requests.length, 1);
});
test("status-only stop responsibility routes toggle to Stop", async () => {
  const f = fixture(); f.state.liveActive = false;
  f.adoptCameraStatus({ liveViewStopUnconfirmed: true }, f.state.session, f.state.liveViewStopRevision || 0);
  const operation = f.toggleLiveView();
  assert.match(f.requests[0].url, /liveview\/stop$/);
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await operation;
  assert.equal(f.state.status.liveViewStopUnconfirmed, false);
});
for (const outcome of ["success", "failure"]) {
  test(`old start ${outcome} cannot change a replacement with reused ID`, async () => {
    const f = fixture(); f.state.liveActive = false; const operation = f.startLiveView();
    f.state.session = { id: "synthetic-A" }; f.state.liveActive = true; f.state.busy = true;
    f.state.liveViewStopOwner = { session: f.state.session }; f.messages.length = 0;
    if (outcome === "success") f.pending[0].resolve({ active: true, source: "CCAPI_RTP" });
    else f.pending[0].reject(new Error("old start"));
    await operation;
    assert.equal(f.state.liveActive, true); assert.equal(f.state.busy, true);
    assert.deepEqual(f.messages, []);
  });
  test(`older concurrent stop ${outcome} cannot settle a newer stop`, async () => {
    const f = fixture(); const first = f.stopLiveView(); const second = f.stopLiveView();
    f.messages.length = 0;
    if (outcome === "success") f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false });
    else f.pending[0].reject(new Error("old stop"));
    await first; assert.equal(f.state.busy, true); assert.deepEqual(f.messages, []);
    f.pending[1].reject(new Error("current stop failed")); await second;
    assert.equal(f.liveViewStopRequired(), true);
    assert.equal(f.state.busy, false);
  });
}
test("new start cannot dispatch while previous stop is unresolved", async () => {
  const f = fixture(); const stop = f.stopLiveView();
  f.pending[0].reject(new Error("stop failed")); await stop;
  await f.startLiveView(); assert.equal(f.requests.length, 1);
});
test("local receiver cleanup does not acknowledge camera stop", async () => {
  const f = fixture(); f.state.liveViewStopOwner = { session: f.state.session };
  assert.equal(await f.stopLiveView({ remote: false }), false);
  assert.equal(f.liveViewStopRequired(), true); assert.equal(f.requests.length, 0);
});
test("Stop retires a late Start response within the same session", async () => {
  const f = fixture(); f.state.liveActive = false;
  const start = f.startLiveView(); const stop = f.stopLiveView();
  f.messages.length = 0;
  f.pending[0].resolve({ active: true, source: "CCAPI_RTP" }); await start;
  assert.equal(f.state.liveActive, false); assert.equal(f.state.busy, true);
  assert.deepEqual(f.messages, []);
  f.pending[1].reject(new Error("stop unconfirmed")); await stop;
  assert.equal(f.liveViewStopRequired(), true);
});
test("failed frame cleanup preserves the primary error and a retry path", async () => {
  const f = fixture(); f.messages.push("original frame error");
  const stop = f.stopLiveView({ announce: false, preserveError: true });
  f.pending[0].reject(new Error("cleanup failed")); await stop;
  assert.deepEqual(f.messages, ["original frame error"]);
  assert.equal(f.liveViewStopRequired(), true);
});
test("known running legacy session can confirm its first stop", async () => {
  const f = fixture(); const stop = f.stopLiveView();
  f.pending[0].resolve({ active: false });
  assert.equal(await stop, true); assert.equal(f.liveViewStopRequired(), false);
});
for (const reply of [{ active: false }, { active: false, liveViewStopUnconfirmed: null },
  { active: false, liveViewStopUnconfirmed: true }, { active: true, liveViewStopUnconfirmed: false }, {}]) {
  test(`ambiguous start requires explicit stop proof: ${JSON.stringify(reply)}`, async () => {
    const f = fixture(); f.state.liveActive = false;
    const start = f.startLiveView(); f.pending[0].reject(new Error("lost ACK")); await start;
    const stop = f.stopLiveView(); f.pending[1].resolve(reply);
    assert.equal(await stop, false); assert.equal(f.liveViewStopRequired(), true);
    assert.equal(f.requests.length, 2);
  });
}
test("failed first legacy stop cannot accept a subsequent bare false", async () => {
  const f = fixture(); const first = f.stopLiveView();
  f.pending[0].reject(new Error("lost stop ACK")); await first;
  const second = f.stopLiveView(); f.pending[1].resolve({ active: false });
  assert.equal(await second, false); assert.equal(f.liveViewStopRequired(), true);
});
for (const method of ["sleepCamera", "cleanSensor"]) {
  test(`${method} cannot proceed when Live View stop is unconfirmed`, async () => {
    const f = fixture(); const operation = f[method]({}, false);
    assert.match(f.requests[0].url, /liveview\/stop$/);
    f.pending[0].reject(new Error("stop failed")); await operation;
    assert.equal(f.requests.length, 1); assert.equal(f.liveViewStopRequired(), true);
  });
}
test("non-poweroff cleaning holds preview until an explicit fresh-read restore", async () => {
  const f = fixture(); const operation = f.cleanSensor({}, false);
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await turns();
  assert.match(f.requests[1].url, /maintenance\/sensor-cleaning\/outcome$/);
  f.pending[1].resolve({ version: 1, engine: "ccapi", operation: "sensor-cleaning", autoPowerOff: false,
    responseEvidence: "response-received", clockReference: null, clockObservation: null, clockReadEvidence: "not-applicable" });
  await operation;
  assert.equal(f.requests.length, 2); assert.equal(f.state.liveActive, false);
  f.state.requestedFps = 25; f.state.liveSource = "AUTO";
  const restore = f.restoreMaintenance(f.state.maintenanceHistory[0]); await turns();
  assert.match(f.requests[2].url, /maintenance\/sensor-cleaning$/);
  f.pending[2].resolve({ version: 1, engine: "ccapi", operation: "sensor-cleaning", observationEvidence: "reachable", clockObservation: null }); await turns();
  assert.match(f.requests[3].url, /maintenance\/control\/liveview\/start$/);
  assert.equal(f.requests[3].options.payload.fps, 15);
  assert.equal(f.requests[3].options.payload.source, "CCAPI_RTP");
  f.pending[3].resolve({ active: true, liveViewStopUnconfirmed: false }); assert.equal(await restore, true);
  assert.equal(f.state.liveActive, true);
});
for (const reply of [null, {}, [], { active: false }, { active: "true" }]) {
  test(`malformed start cannot authorize legacy stop: ${JSON.stringify(reply)}`, async () => {
    const f = fixture(); f.state.liveActive = false;
    const start = f.startLiveView(); f.pending[0].resolve(reply); await start;
    assert.equal(f.state.liveActive, false); assert.equal(f.liveViewStopRequired(), true);
    const stop = f.stopLiveView(); f.pending[1].resolve({ active: false });
    assert.equal(await stop, false); assert.equal(f.liveViewStopRequired(), true);
  });
}

async function acceptedStatus(f, status) {
  const offset = f.pending.length;
  const refresh = f.refreshSession({ quiet: true });
  assert.equal(f.requests.length, offset + 3, "Use the actual status refresh handler");
  f.pending[offset].resolve({ model: "synthetic" });
  f.pending[offset + 1].resolve(status);
  f.pending[offset + 2].resolve({ liveView: { maxFps: 30 } });
  assert.equal(await refresh, true);
}

for (const legacyReply of [{ active: false }, { active: false, liveViewStopUnconfirmed: null }]) {
  test(`newer true status survives late Start ACK and cannot regain legacy proof: ${JSON.stringify(legacyReply)}`, async () => {
    const f = fixture(); f.state.liveActive = false;
    const start = f.startLiveView();
    await acceptedStatus(f, { liveViewStopUnconfirmed: true });
    const owner = f.state.liveViewStopOwner;
    await acceptedStatus(f, { liveViewStopUnconfirmed: false });
    assert.equal(f.state.liveViewStopOwner, owner);
    f.pending[0].resolve({ active: true, source: "CCAPI_RTP" });
    await start;
    assert.equal(owner.stopUnconfirmed, true);
    assert.equal(owner.confirmedStart, false, "A late ACK cannot restore revoked legacy permission");
    const offset = f.pending.length;
    const stop = f.stopLiveView(); f.pending[offset].resolve(legacyReply);
    assert.equal(await stop, false);
    assert.equal(f.liveViewStopRequired(), true);
    const retryOffset = f.pending.length;
    const retry = f.toggleLiveView();
    assert.match(f.requests[retryOffset].url, /liveview\/stop$/);
    f.pending[retryOffset].resolve({ active: false, liveViewStopUnconfirmed: false });
    await retry;
    assert.equal(f.liveViewStopRequired(), false);
    assert.equal(f.requests.filter(request => request.url.endsWith("/liveview/start")).length, 1);
  });
}

for (const status of [{ liveViewStopUnconfirmed: false }, {}, { liveViewStopUnconfirmed: null }]) {
  test(`accepted ordinary snapshot cannot erase a prior stop warning: ${JSON.stringify(status)}`, async () => {
    const f = fixture(); f.state.liveActive = false;
    await acceptedStatus(f, { liveViewStopUnconfirmed: true });
    const owner = f.state.liveViewStopOwner;
    await acceptedStatus(f, status);
    assert.equal(f.state.liveViewStopOwner, owner);
    assert.equal(f.liveViewStopRequired(), true);
    const beforeStart = f.requests.length;
    await f.startLiveView();
    assert.equal(f.requests.length, beforeStart);
    const retry = f.toggleLiveView();
    assert.match(f.requests.at(-1).url, /liveview\/stop$/);
    f.pending.at(-1).resolve({ active: false, liveViewStopUnconfirmed: false });
    await retry;
    assert.equal(f.liveViewStopRequired(), false);
  });
}

for (const [reply, accepted] of [
  [{ active: false }, false],
  [{ active: false, liveViewStopUnconfirmed: null }, false],
  [{ active: false, liveViewStopUnconfirmed: false }, true],
]) {
  test(`status during in-flight Stop changes settlement proof: ${JSON.stringify(reply)}`, async () => {
    const f = fixture();
    const stop = f.stopLiveView();
    await acceptedStatus(f, { liveViewStopUnconfirmed: true });
    await acceptedStatus(f, { liveViewStopUnconfirmed: false });
    f.pending[0].resolve(reply);
    assert.equal(await stop, accepted);
    assert.equal(f.liveViewStopRequired(), !accepted);
  });
}

test("a stale status read cannot revive responsibility after explicit Stop acknowledgement", async () => {
  const f = fixture();
  const stop = f.stopLiveView();
  const refresh = f.refreshSession({ quiet: true });
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false });
  assert.equal(await stop, true);
  f.pending[1].resolve({ model: "synthetic" });
  f.pending[2].resolve({ liveViewStopUnconfirmed: true });
  f.pending[3].resolve({ liveView: { maxFps: 30 } });
  await refresh;
  assert.equal(f.liveViewStopRequired(), false);
  assert.equal(f.state.liveViewStopOwner, null);
});

test("replacement session with a reused wire ID cannot inherit old status warning", async () => {
  const f = fixture(); f.state.liveActive = false;
  const oldSession = f.state.session;
  const refresh = f.refreshSession({ quiet: true });
  f.state.session = { id: oldSession.id };
  f.state.status = { owner: "replacement" };
  f.state.liveViewStopOwner = null;
  f.pending[0].resolve({ model: "old" });
  f.pending[1].resolve({ liveViewStopUnconfirmed: true });
  f.pending[2].resolve({ liveView: { maxFps: 30 } });
  assert.equal(await refresh, false);
  assert.equal(f.state.status.owner, "replacement");
  assert.equal(f.liveViewStopRequired(), false);
  assert.equal(f.adoptCameraStatus({ liveViewStopUnconfirmed: true }, oldSession, f.state.liveViewStopRevision || 0), false);
  assert.equal(f.state.status.owner, "replacement");
});

test("production interaction generation rejects a refresh admitted before Stop", async () => {
  const f = fixture();
  const refresh = f.refreshSession({ quiet: true });
  const stop = f.stopLiveView();
  f.pending[0].resolve({ model: "synthetic" });
  f.pending[1].resolve({ liveViewStopUnconfirmed: true });
  f.pending[2].resolve({ liveView: { maxFps: 30 } });
  assert.equal(await refresh, false);
  assert.equal(f.state.liveViewStopOwner.stopUnconfirmed, false);
  f.pending[3].resolve({ active: false });
  assert.equal(await stop, true);
  assert.equal(f.liveViewStopRequired(), false);
});

test("active Start reply with explicit stop warning never publishes preview or starts polling", async () => {
  const f = fixture(); f.state.liveActive = false;
  let polls = 0;
  f.pollLiveView = () => { polls++; };
  const start = f.startLiveView();
  f.pending[0].resolve({ active: true, liveViewStopUnconfirmed: true });
  await start;
  assert.equal(f.state.liveActive, false);
  assert.equal(polls, 0);
  assert.equal(f.messages.includes("liveViewStarted"), false);
  assert.equal(f.liveViewStopRequired(), true);
  const stop = f.stopLiveView();
  f.pending[1].resolve({ active: false });
  assert.equal(await stop, false);
  assert.equal(f.liveViewStopRequired(), true);
  const retry = f.stopLiveView();
  f.pending[2].resolve({ active: false, liveViewStopUnconfirmed: false });
  assert.equal(await retry, true);
});

test("cancel after restore Start dispatch retains stop responsibility and cannot publish late preview", async () => {
  const f=fixture(),action=f.cleanSensor({},false);
  f.pending[0].resolve({active:false,liveViewStopUnconfirmed:false});await turns();
  f.pending[1].resolve({version:1,engine:'ccapi',operation:'sensor-cleaning',autoPowerOff:false,responseEvidence:'response-received',clockReference:null,clockObservation:null,clockReadEvidence:'not-applicable'});await action;
  const record=f.state.maintenanceHistory[0],restore=f.restoreMaintenance(record);
  f.pending[2].resolve({version:1,engine:'ccapi',operation:'sensor-cleaning',observationEvidence:'reachable',clockObservation:null});await turns();
  assert.match(f.requests[3].url,/liveview\/start$/);f.cancelMaintenance();
  f.pending[3].resolve({active:true});assert.equal(await restore,false);
  assert.equal(f.state.liveActive,false);assert.equal(f.liveViewStopRequired(),true);
  assert.equal(f.state.liveViewStopOwner.stopUnconfirmed,true);assert.equal(record.restoreEvidence,'unknown');
  assert.equal(f.state.maintenanceHold,record);assert.equal(f.requests.length,4);
});

function maintenanceHeldFixture() {
  const f = fixture();
  const record = { owner: f.state.session, token: 'SYNTHETIC-FROZEN', retired: false, wasEvents: true,
    resume: { source: 'CCAPI_RTP', fps: 15, size: 'MEDIUM' } };
  f.state.token = 'SYNTHETIC-NEW'; f.state.maintenanceHold = record;
  f.state.liveActive = false;
  f.state.liveViewStopOwner = { session: f.state.session, maintenanceRecord: record, stopUnconfirmed: true };
  f.cancelEventLoop = () => {}; f.cancelMediaDownload = () => {};
  f.ui.connectionError = { textContent: '', hidden: true };
  f.captureConfiguration.transfer = async (url, options) => ({ status: options.method === 'DELETE' ? 204 : 200,
    body: await f.api(url, options) });
  return { f, record };
}
test('held maintenance Retry Stop remains reachable, bounded, single-dispatch and retains frozen token', async () => {
  const { f, record } = maintenanceHeldFixture();
  assert.equal(f.maintenanceStopRetryAvailable(), true);
  const marker = source.indexOf('    const liveStopRequired = liveViewStopRequired();');
  const end = source.indexOf('    const quickActionCount =', marker);
  assert.ok(marker > 0 && end > marker);
  f.ui.liveToggleButton = {}; f.ui.railLiveButton = {};
  Object.assign(f, { interactionBusy: true, bulbActive: false, cameraLiveSupported: true,
    localLiveSupported: false, selectedLiveSupported: true, temperatureAllows: () => true });
  vm.runInContext(source.slice(marker, end), f);
  assert.equal(f.ui.liveToggleButton.disabled, false);
  const stop = f.toggleLiveView();
  assert.equal(f.maintenanceStopRetryAvailable(), false);
  await f.toggleLiveView(); assert.equal(f.requests.length, 1);
  assert.match(f.requests[0].url, /maintenance\/control\/liveview\/stop$/);
  assert.equal(f.requests[0].options.token, record.token);
  assert.equal(f.requests[0].options.strictSettings, true);
  assert.equal(f.requests[0].options.strictIntegerTokens, true);
  assert.ok(f.requests[0].options.signal);
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await stop;
  assert.equal(f.liveViewStopRequired(), false);
  assert.equal(f.state.maintenanceHold, record);
  assert.equal(f.state.liveActive, false);
});
test('same-owner newer Stop warning cannot be cleared by late strict Stop proof', async () => {
  const { f } = maintenanceHeldFixture(); const stop = f.toggleLiveView();
  f.adoptCameraStatus({ liveViewStopUnconfirmed: true }, f.state.session, f.state.liveViewStopRevision || 0);
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await stop;
  assert.equal(f.liveViewStopRequired(), true); assert.equal(f.state.liveViewStopOwner.stopUnconfirmed, true);
});
test('same-owner newer Stop warning prevents late maintenance Restore Start publication', async () => {
  const { f, record } = maintenanceHeldFixture(); f.state.liveViewStopOwner = null;
  const invocation = { record, mode: 'restore', restoreAdmitted: true, cancelled: false, controller: new AbortController() };
  f.state.maintenanceOwner = invocation;
  const start = f.startLiveView({ maintenanceRestore: invocation });
  f.adoptCameraStatus({ liveViewStopUnconfirmed: true }, f.state.session, f.state.liveViewStopRevision);
  f.pending[0].resolve({ active: true, liveViewStopUnconfirmed: false }); await start;
  assert.equal(f.state.liveActive, false); assert.equal(f.state.liveViewStopOwner.stopUnconfirmed, true);
  assert.equal(f.state.liveViewStopOwner.maintenanceRecord, record);
});
test('background cancellation preserves strict Stop responsibility and explicit retry uses a fresh signal', async () => {
  const { f } = maintenanceHeldFixture(); const stop = f.toggleLiveView();
  const signal = f.requests[0].options.signal;
  f.suspendMaintenanceForLifecycle(); assert.equal(signal.aborted, true);
  f.pending[0].resolve({ active: false, liveViewStopUnconfirmed: false }); await stop;
  assert.equal(f.liveViewStopRequired(), true);
  const retry = f.toggleLiveView(); assert.notEqual(f.requests[1].options.signal, signal);
  assert.equal(f.requests[1].options.signal.aborted, false);
  f.pending[1].resolve({ active: false, liveViewStopUnconfirmed: false }); await retry;
  assert.equal(f.liveViewStopRequired(), false); assert.equal(f.requests.length, 2);
});
for (const retireFails of [false, true]) test(`explicit maintenance disconnect retains Stop uncertainty across local retire failure=${retireFails}`, async () => {
  const { f, record } = maintenanceHeldFixture();
  const close = f.disconnectMaintenanceCamera(f.state.session, record);
  f.pending[0].reject(new Error('Synthetic lost stop')); await turns();
  assert.match(f.requests[1].url, /maintenance\/control\/events$/);
  f.pending[1].resolve(null); await turns();
  assert.match(f.requests[2].url, /maintenance\/control\/session$/);
  if (retireFails) f.pending[2].reject(new Error('Synthetic local retirement failure'));
  else f.pending[2].resolve(null);
  await close;
  assert.equal(f.requests.length, 3);
  assert.equal(f.state.session, null);
  assert.match(f.ui.connectionError.textContent, /liveViewStopUnconfirmed/);
  assert.equal(f.ui.connectionError.textContent.includes('maintenanceRetireUnconfirmed'), retireFails);
  assert.equal(f.requests.every(r => r.options.token === record.token && r.options.strictSettings), true);
});
for (const releaseConfirmed of [true, false]) test(`restored maintenance disconnect preserves subsequent Bulb release confirmed=${releaseConfirmed}`, async () => {
  const { f, record } = maintenanceHeldFixture(); f.bulbControlLocked = () => true;
  const close = f.disconnectMaintenanceCamera(f.state.session, record);
  assert.match(f.requests[0].url, /bulb\/stop$/);
  f.pending[0].resolve({ bulbExposureActive: !releaseConfirmed, shutterReleaseUnconfirmed: !releaseConfirmed }); await turns();
  const offset = releaseConfirmed ? 1 : 0;
  if (releaseConfirmed) {
    assert.match(f.requests[1].url, /maintenance\/control\/liveview\/stop$/);
    f.pending[1].resolve({ active: false, liveViewStopUnconfirmed: false }); await turns();
  }
  if (releaseConfirmed) {
    assert.match(f.requests[1 + offset].url, /maintenance\/control\/events$/);
    f.pending[1 + offset].resolve(null); await turns();
  }
  const retirementIndex = releaseConfirmed ? 3 : 1;
  assert.match(f.requests[retirementIndex].url, /maintenance\/control\/session$/);
  f.pending[retirementIndex].resolve(null); await close;
  assert.equal(f.requests.length, retirementIndex + 1);
  assert.equal(Boolean(f.state.shutterDisconnectWarning), !releaseConfirmed);
  if (!releaseConfirmed) assert.match(f.ui.connectionError.textContent, /bulbDisconnectWarning/);
});
test('restored maintenance disconnect preserves subsequent held AF release failure', async () => {
  const { f, record } = maintenanceHeldFixture(); const held = {}; f.state.heldAutofocusOwner = held;
  let calls = 0; f.stopHeldAutofocus = async ({ owner }) => { calls++; assert.equal(owner || f.state.heldAutofocusOwner, held); return false; };
  const close = f.disconnectMaintenanceCamera(f.state.session, record); await turns();
  // Stop Live View cannot cross an unresolved held AF release, and retire cannot claim it did.
  assert.match(f.requests[0].url, /maintenance\/control\/session$/);
  f.pending[0].resolve(null); await close;
  assert.equal(f.requests.length, 1);
  assert.equal(calls, 1); assert.equal(f.state.shutterDisconnectWarning, true);
});
test('cancelling Close during required physical release cannot dispatch a later Stop or retirement',async()=>{
 const {f,record}=maintenanceHeldFixture(),gate=deferred();f.state.heldAutofocusOwner={};let calls=0;
 f.stopHeldAutofocus=async()=>{calls++;return gate.promise;};
 const close=f.disconnectMaintenanceCamera(f.state.session,record);f.suspendMaintenanceForLifecycle();gate.resolve(false);await close;
 assert.equal(calls,1);assert.equal(f.requests.length,0);assert.equal(f.state.session,null);
 assert.match(f.ui.connectionError.textContent,/maintenanceRetireUnconfirmed/);
});
