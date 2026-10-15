"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");
const policy = require("../open_eos_bridge/static/capture-configuration.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m")); assert.notEqual(start, -1, name);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /); assert.notEqual(end, -1, name);
  return source.slice(start, start + 1 + end);
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function setup() {
  const calls = [], gate = deferred(), owner = { id: "SYNTHETIC-A", engine: "ccapi" };
  const state = { session: owner, token: "", info: { model: "SYNTHETIC" }, refreshGeneration: 0, busy: false,
    fileNamingDrafts: {}, captureDirectoryDraft: "", captureConfigurationDraftRevisions: {}, captureConfigurationConsumedDrafts: {},
    captureConfigurationSequence: 0, captureConfigurationHistory: [], captureConfigurationOwner: null, capabilities: { settings: [] } };
  const context = vm.createContext({ state, window: { location: { origin: "https://bridge.example" } }, AbortController,
    setTimeout, clearTimeout, FEATURES: { DIRECTORY_CONTROL: "directory", FILE_NAMING_CONTROL: "naming" },
    featureSupported: () => true, fileNamingValueValid: () => true, cameraInteractionBusy: () => state.busy,
    beginCameraInteraction: () => { state.refreshGeneration++; state.busy = true; }, renderAvailability() {},
    renderAdvancedSettings() {}, renderSession() {}, setOperationState() {}, t: (value) => value,
    captureConfiguration: { ...policy, transfer: async (url, options) => { calls.push({ url, options }); return gate.promise; } } });
  for (const name of ["editCaptureConfigurationDraft", "captureConfigurationCurrent", "captureConfigurationDraftAvailable",
    "cancelCaptureConfiguration", "renderCaptureConfigurationButtonAvailability", "publishCaptureConfiguration", "runCaptureConfiguration", "recoverCaptureConfiguration",
    "createCaptureDirectory", "updateFileNaming"]) vm.runInContext(productionFunction(name), context);
  return { context, state, calls, gate, owner };
}
const receipt = { status: 502, body: { error: { code: "CAPTURE_CONFIG_READBACK_FAILED", engine: "ccapi", feature: "DIRECTORY_CONTROL",
  captureConfigReceipt: { version: 1, operation: "create-directory", stage: "acknowledged", requestedValue: "ABCDE", acknowledgedValue: "ABCDE" } } } };
test("valid ACK survives failed follow-on read and duplicate create is blocked", async () => {
  const h = setup(); const task = h.context.createCaptureDirectory({}, "ABCDE");
  assert.equal(h.calls.length, 1); h.gate.resolve(receipt); await task;
  const record = h.state.captureConfigurationHistory[0]; assert.equal(record.validAcknowledgment, true); assert.equal(record.readStatus, "unavailable");
  assert.equal(h.state.lastCreatedDirectoryName, "ABCDE"); assert.equal(h.state.busy, false);
  await h.context.createCaptureDirectory({}, "ABCDE"); assert.equal(h.calls.length, 1);
});
test("old-owner late valid receipt only settles original history", async () => {
  const h = setup(); const task = h.context.createCaptureDirectory({}, "ABCDE");
  h.context.cancelCaptureConfiguration({ retire: true });
  h.state.session = { id: "SYNTHETIC-A" }; h.state.refreshGeneration++; h.state.captureConfigurationOwner = { replacement: true };
  h.state.captureDirectoryDraft = "OTHER"; h.state.lastCreatedDirectoryName = "NEWER";
  h.gate.resolve(receipt); await task;
  assert.equal(h.state.captureConfigurationHistory[0].validAcknowledgment, true);
  assert.equal(h.state.lastCreatedDirectoryName, "NEWER"); assert.equal(h.state.captureDirectoryDraft, "OTHER");
  assert.equal(h.state.captureConfigurationOwner.replacement, true); assert.equal(h.state.busy, true);
});
test("cancel retains uncertainty and never causes automatic replay", async () => {
  const h = setup(); const task = h.context.createCaptureDirectory({}, "ABCDE");
  h.context.cancelCaptureConfiguration(); h.gate.reject(new Error("synthetic lost acknowledgment")); await task;
  assert.equal(h.calls.length, 1); const record = h.state.captureConfigurationHistory[0];
  assert.equal(policy.outcome(record).writeEvidence, "unknown"); assert.equal(record.cancelled, true);
  await h.context.createCaptureDirectory({}, "ABCDE"); assert.equal(h.calls.length, 1);
});
test("read-only matching observation does not promote unknown write", async () => {
  const h = setup(); const task = h.context.createCaptureDirectory({}, "ABCDE"); h.gate.reject(new Error("lost")); await task;
  const record = h.state.captureConfigurationHistory[0];
  h.context.captureConfiguration.transfer = async (url, options) => { h.calls.push({ url, options });
    return { status: 200, body: { operation: "create-directory", directorySelection: { value: "101ABCDE", ability: ["101ABCDE"] } } }; };
  await h.context.recoverCaptureConfiguration(record);
  assert.equal(h.calls.length, 2); assert.equal(h.calls[1].options.method, undefined);
  assert.equal(policy.outcome(record).writeEvidence, "unknown"); assert.equal(policy.outcome(record).readbackEvidence, "refreshed");
  assert.equal(record.observedValue, "101ABCDE");
});
test("body cannot override trusted HTTP status to fabricate ACK", async () => {
  const h = setup(); const task = h.context.createCaptureDirectory({}, "ABCDE");
  h.gate.resolve({ status: 503, body: { error: { ...receipt.body.error, status: 502 } } }); await task;
  assert.equal(h.state.captureConfigurationHistory[0].validAcknowledgment, false);
});
test("same owner edits while response pending stay intact", async () => {
  const h = setup(); h.context.editCaptureConfigurationDraft("movie-index", "A1");
  const task = h.context.updateFileNaming("movie-index", "A1", {});
  h.context.editCaptureConfigurationDraft("movie-index", "B2");
  const naming = { stillFilenameMode: "preset_code", stillFilenameModeOptions: ["preset_code"], stillUserSetting1: "IMG_",
    stillUserSetting2: "IMG", movieIndex: "A1", movieReelNumber: 1, movieReelRange: { minimum: 1, maximum: 9999, step: 1 },
    movieClipNumber: 1, movieClipRange: { minimum: 1, maximum: 999, step: 1 }, movieUserDefined: "ABCDE" };
  h.gate.resolve({ status: 200, body: naming }); await task;
  assert.equal(h.state.fileNamingDrafts["movie-index"], "B2"); assert.equal(h.state.capabilities.fileNaming.movieIndex, "A1");
});
test("history bound and removed records are not resurrected by readback", async () => {
  const h = setup(); h.context.captureConfiguration.transfer = async () => ({ status: 200, body: { name: "ABCDE" } });
  for (let i = 0; i < 25; i++) { h.state.captureConfigurationDraftRevisions.directory = i; await h.context.runCaptureConfiguration("create-directory", "ABCDE"); }
  assert.equal(h.state.captureConfigurationHistory.length, 20);
  const removed = h.state.captureConfigurationHistory.shift(); const before = h.calls.length;
  await h.context.recoverCaptureConfiguration(removed); assert.equal(h.calls.length, before); assert.equal(h.state.captureConfigurationHistory.includes(removed), false);
});
test('same-value real edit is a new draft revision; passive render synchronization is not', async () => {
  const h = setup(); h.context.editCaptureConfigurationDraft('movie-index', 'A1');
  const task = h.context.updateFileNaming('movie-index', 'A1', {});
  const started = h.state.captureConfigurationDraftRevisions['movie-index'];
  h.context.editCaptureConfigurationDraft('movie-index', 'A1', { edited: false });
  assert.equal(h.state.captureConfigurationDraftRevisions['movie-index'], started);
  h.context.editCaptureConfigurationDraft('movie-index', 'A1');
  assert.equal(h.state.captureConfigurationDraftRevisions['movie-index'], started + 1);
  const naming = { stillFilenameMode: 'preset_code', stillFilenameModeOptions: ['preset_code'], stillUserSetting1: 'IMG_',
    stillUserSetting2: 'IMG', movieIndex: 'A1', movieReelNumber: 1, movieReelRange: { minimum: 1, maximum: 9999, step: 1 },
    movieClipNumber: 1, movieClipRange: { minimum: 1, maximum: 999, step: 1 }, movieUserDefined: 'ABCDE' };
  h.gate.resolve({ status: 200, body: naming }); await task;
  assert.equal(h.state.fileNamingDrafts['movie-index'], 'A1');
  assert.equal(h.context.captureConfigurationDraftAvailable('movie-index'), true);
});
test('mismatched actual session engine cannot accept CCAPI error receipt', async () => {
  const h = setup(); h.owner.engine = 'libgphoto2';
  const task = h.context.createCaptureDirectory({}, 'ABCDE'); h.gate.resolve(receipt); await task;
  assert.equal(h.state.captureConfigurationHistory[0].validAcknowledgment, false);
});
test('render never recreates a consumed naming draft from a focused DOM input', () => {
  const render = productionFunction('renderAdvancedSettings');
  assert.equal(render.includes('editCaptureConfigurationDraft'), true, 'real directory input handler remains inside render');
  const focused = render.slice(0, render.indexOf('ui.advancedSettings.replaceChildren();'));
  assert.equal(focused.includes('fileNamingDrafts'), false);
  assert.equal(focused.includes('editCaptureConfigurationDraft'), false);
  const h = setup();
  h.context.editCaptureConfigurationDraft('movie-index', 'A1', { edited: false });
  assert.equal(h.state.captureConfigurationDraftRevisions['movie-index'], undefined);
});
test('late valid ACK after cancel settles history only; explicit fresh recovery can publish', async () => {
  const h = setup(); h.state.lastCreatedDirectoryName = 'OLDER';
  h.state.capabilities.settings = [{ key: 'directoryselection', value: '100EOSXX', values: ['100EOSXX'] }];
  const task = h.context.createCaptureDirectory({}, 'ABCDE');
  h.context.cancelCaptureConfiguration(); h.gate.resolve({ status: 200, body: { name: 'ABCDE' } }); await task;
  const record = h.state.captureConfigurationHistory[0];
  assert.equal(record.validAcknowledgment, true); assert.equal(record.cancelled, true);
  assert.equal(h.state.lastCreatedDirectoryName, 'OLDER'); assert.equal(h.state.busy, false);
  h.context.captureConfiguration.transfer = async () => ({ status: 200, body: { operation: 'create-directory',
    directorySelection: { value: '101ABCDE', ability: ['100EOSXX', '101ABCDE'] } } });
  await h.context.recoverCaptureConfiguration(record);
  assert.equal(h.state.capabilities.settings[0].value, '101ABCDE');
  assert.equal(record.cancelled, true); assert.equal(h.state.lastCreatedDirectoryName, 'ABCDE');
});
test('older retained recovery publishes current selection without replacing newer write summary', async () => {
  const h = setup();
  h.context.captureConfiguration.transfer = async (url, options) => ({ status: 200, body: { name: options.payload.name } });
  await h.context.createCaptureDirectory({}, 'ABCDE'); await h.context.createCaptureDirectory({}, 'OTHER');
  const first = h.state.captureConfigurationHistory[0];
  assert.equal(h.state.lastCreatedDirectoryName, 'OTHER');
  h.context.captureConfiguration.transfer = async () => ({ status: 200, body: { operation: 'create-directory',
    directorySelection: { value: '101ABCDE', ability: ['101ABCDE'] } } });
  await h.context.recoverCaptureConfiguration(first);
  assert.equal(h.state.lastCreatedDirectoryName, 'OTHER');
  assert.equal(h.state.captureDirectoryDraft, 'OTHER');
});
test('configuration availability is independent of clock feature and preserves consumed/retired gates', () => {
  const h = setup(); h.context.featureSupported = feature => feature === 'directory' || feature === 'naming';
  const directory = { dataset: { cameraCommand: 'directory' }, parentElement: { querySelector: () => ({ value: 'ABCDE' }) } };
  assert.equal(h.context.renderCaptureConfigurationButtonAvailability(directory, false, false), true);
  assert.equal(directory.disabled, false);
  h.state.captureConfigurationConsumedDrafts.directory = 0;
  h.context.renderCaptureConfigurationButtonAvailability(directory, false, false); assert.equal(directory.disabled, true);
  const input = { value: 'A1', dataset: { fileNamingField: 'movie-index' } };
  const naming = { dataset: { cameraCommand: 'file-naming' }, parentElement: { querySelector: () => input } };
  h.context.fileNamingValueValid = policy.validFieldValue;
  h.context.renderCaptureConfigurationButtonAvailability(naming, false, false); assert.equal(naming.disabled, false);
  input.value = 'A'; h.context.renderCaptureConfigurationButtonAvailability(naming, false, false); assert.equal(naming.disabled, true);
  const record = { id: 1, owner: h.owner, retired: false, controller: new AbortController() };
  h.state.captureConfigurationHistory = [record]; h.state.captureConfigurationOwner = record;
  const button = action => ({ dataset: { captureConfigurationAction: action, captureConfigurationId: '1' } });
  const cancel = button('cancel'), recover = button('recover'), prepare = button('prepare');
  h.context.renderCaptureConfigurationButtonAvailability(cancel, true, false); assert.equal(cancel.disabled, false);
  h.context.renderCaptureConfigurationButtonAvailability(recover, true, false); assert.equal(recover.disabled, true);
  h.state.captureConfigurationOwner = null;
  h.context.renderCaptureConfigurationButtonAvailability(recover, false, false); assert.equal(recover.disabled, false);
  record.retired = true;
  for (const control of [cancel, recover, prepare]) { h.context.renderCaptureConfigurationButtonAvailability(control, false, false); assert.equal(control.disabled, true); }
  const source = productionFunction('renderAvailability');
  assert.ok(source.indexOf('renderCaptureConfigurationButtonAvailability(button, interactionBusy, bulbActive)') < source.indexOf('const supported = command === "sleep"'));
});
test('valid fresh directory recovery restores a missing picker without changing unrelated settings', async () => {
  const h = setup();
  h.state.capabilities.settings = [{ key: 'iso', value: '100', values: ['100', '200'] }];
  const task = h.context.createCaptureDirectory({}, 'ABCDE'); h.gate.resolve(receipt); await task;
  const record = h.state.captureConfigurationHistory[0];
  h.context.captureConfiguration.transfer = async () => ({ status: 200, body: { operation: 'create-directory',
    directorySelection: { value: '101ABCDE', ability: ['100EOSXX', '101ABCDE'] } } });
  await h.context.recoverCaptureConfiguration(record);
  assert.equal(h.state.capabilities.settings.length, 2);
  assert.equal(h.state.capabilities.settings[0].key, 'iso'); assert.equal(h.state.capabilities.settings[0].value, '100');
  assert.equal(h.state.capabilities.settings[1].key, 'directoryselection');
  assert.equal(h.state.capabilities.settings[1].value, '101ABCDE');
  const h2 = setup(), old = h2.context.createCaptureDirectory({}, 'ABCDE');
  h2.context.cancelCaptureConfiguration({ retire: true }); h2.state.session = { id: 'NEW', engine: 'ccapi' };
  h2.gate.resolve(receipt); await old;
  assert.equal(h2.state.capabilities.settings.length, 0);
});
test('successful directory create sends one write then one fresh scoped read and updates picker', async () => {
  const h = setup();
  h.context.captureConfiguration.transfer = async (url, options) => {
    h.calls.push({ url, options });
    return options.method === 'POST' ? { status: 200, body: { name: 'ABCDE' } } :
      { status: 200, body: { operation: 'create-directory', directorySelection: { value: '101ABCDE', ability: ['100EOSXX', '101ABCDE'] } } };
  };
  await h.context.createCaptureDirectory({}, 'ABCDE');
  assert.equal(h.calls.length, 2); assert.equal(h.calls[0].options.method, 'POST'); assert.equal(h.calls[1].options.method, undefined);
  assert.equal(h.state.capabilities.settings[0].value, '101ABCDE');
  assert.equal(h.state.captureConfigurationHistory[0].readStatus, 'success');
  assert.equal(h.state.captureConfigurationHistory[0].validAcknowledgment, true);
});
test('failed automatic read after successful ACK retains proof and never repeats write', async () => {
  const h = setup();
  h.context.captureConfiguration.transfer = async (url, options) => {
    h.calls.push({ url, options });
    if (options.method === 'POST') return { status: 200, body: { name: 'ABCDE' } };
    throw new Error('synthetic read unavailable');
  };
  await h.context.createCaptureDirectory({}, 'ABCDE');
  const record = h.state.captureConfigurationHistory[0];
  assert.equal(record.validAcknowledgment, true); assert.equal(record.readStatus, 'unavailable');
  assert.equal(h.state.lastCreatedDirectoryName, 'ABCDE');
  assert.equal(h.calls.filter(call => call.options.method === 'POST').length, 1);
  await h.context.createCaptureDirectory({}, 'ABCDE'); assert.equal(h.calls.length, 2);
});
