"use strict";
// Real production reset/render/editor/input/write functions, synthetic DOM and transport.
// Saved listener delivery models queued callbacks; it makes no browser focus claim.
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm"), test = require("node:test");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
const shootingSettings = require("../open_eos_bridge/static/shooting-settings.js");
const configuration = require("../open_eos_bridge/static/capture-configuration.js");
function production(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, name);
  return source.slice(start, source.indexOf("\n  }", start) + 4);
}
function element(tagName = "div") {
  const listeners = {};
  return { tagName, children: [], dataset: {}, className: "", value: "", open: false, hidden: false,
    classList: { toggle() {}, remove() {} }, style: { removeProperty() {} },
    append(...nodes) { this.children.push(...nodes); }, replaceChildren(...nodes) { this.children = nodes; },
    setAttribute() {}, removeAttribute() {}, showModal() { this.open = true; }, close() { this.open = false; },
    contains(node) { return this === node || this.children.some(child => child.contains(node)); },
    matches(selector) { return selector === 'input[type="text"]' && this.tagName === "input" && this.type === "text"; },
    addEventListener(type, callback) { (listeners[type] ||= []).push(callback); },
    async dispatch(type) { for (const callback of listeners[type] || []) await callback({ target: this }); },
    querySelectorAll(selector) {
      const all = this.children.flatMap(child => [child, ...child.querySelectorAll("*")]);
      if (selector === "*") return all;
      if (selector === "button[data-setting-value]") return all.filter(n => n.tagName === "button" && n.dataset.settingValue !== undefined);
      return [];
    }, querySelector() { return null; }, scrollIntoView() {},
  };
}
const textSetting = () => ({ key: "artist", label: "Artist", inputKind: "text", value: "CURRENT", values: [], maxLength: 64 });
const choiceSetting = () => ({ key: "metering", label: "Metering", inputKind: "choice", value: "one", values: ["one", "two"] });
const rangeSetting = () => ({ key: "zoom", label: "Zoom", inputKind: "choice", value: "1", values: ["1", "2"] });
function fixture() {
  const state = { session: { id: "camera-a", engine: "ccapi" }, token: "", busy: false, refreshGeneration: 0,
    settingEditor: 0, settingSequence: 0, settingHistory: [], settingDrafts: {}, settingDraftRevisions: {}, settingConsumedDrafts: {},
    captureConfigurationHistory: [], captureConfigurationDraftRevisions: {}, captureConfigurationConsumedDrafts: {}, captureConfigurationSequence: 0,
    captureDirectoryDraft: "", fileNamingDrafts: {}, captureMode: "photo", operatorConfirmedFeatures: new Set(), status: {} };
  const ui = new Proxy({}, { get(target, name) { return target[name] ||= element(); } });
  const requests = [];
  const c = vm.createContext({ state, ui, AbortController, TextEncoder, setTimeout, clearTimeout,
    document: { activeElement: null, createElement: element }, window: { location: { origin: "http://synthetic.invalid" } },
    requestAnimationFrame() {}, CORE_SETTINGS: [], FEATURES: new Proxy({}, { get: (_, key) => key }),
    shootingSettings, captureConfiguration: { ...configuration, async transfer(url, options) { requests.push({ url, options }); return { status: 503, body: {} }; } },
    t: key => key, settingLabel: setting => setting.label || setting, settingValueLabel: (_, value) => value,
    currentSettingValue: setting => setting.value, settingByKey: key => state.capabilities?.settings.find(s => s.key === key),
    cameraInteractionBusy: () => state.busy, bulbControlLocked: () => false,
    beginCameraInteraction() { state.busy = true; state.refreshGeneration++; },
  });
  for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaDownload", "cancelMediaUpload",
    "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaPreview", "closeMediaDetails",
    "releaseObjectUrl", "clearBulbTimer", "renderAvailability", "renderHealth", "setOperationState", "renderSession"]) c[name] = () => {};
  for (const name of ["resetSession", "featureSupported", "renderAdvancedSettings", "renderFileNamingSettings", "fileNamingRow", "appendFileNamingInput",
    "fileNamingValueValid", "fileNamingModeLabel", "textSettingValueValid", "settingMatchesCaptureMode", "closeSettingDialog", "handleSettingDialogClosed", "openSettingDialog", "editSettingDraft",
    "settingDraftAvailable", "settingCurrent", "cancelSetting", "publishSetting", "renderSettingHistory", "renderMaintenanceHistory", "updateSetting",
    "editCaptureConfigurationDraft", "captureConfigurationCurrent", "captureConfigurationDraftAvailable", "cancelCaptureConfiguration",
    "renderCaptureConfigurationHistory", "runCaptureConfiguration", "publishCaptureConfiguration", "updateFileNaming"]) vm.runInContext(production(name), c);
  function install(id = "camera-a") {
    state.session = { id, engine: "ccapi" }; state.status = {}; state.captureMode = "photo";
    state.capabilities = { settings: [textSetting(), choiceSetting(), rangeSetting()], supported: ["FILE_NAMING_CONTROL"],
      fileNaming: { stillFilenameMode: "preset_code", stillFilenameModeOptions: ["preset_code", "usersetting1"], stillUserSetting1: "ABCD", stillUserSetting2: "ABC" } };
  }
  install(); c.renderAdvancedSettings();
  return { c, state, ui, requests, install, all: () => ui.advancedSettings.querySelectorAll("*") };
}
function controls(h, key) {
  return h.all().filter(n => n.dataset.settingKey === key);
}
test("setting dialog fills the associated title before each native open", () => {
  const h = fixture(), observed = [];
  // Exercise the production title resolver and open caller; showModal is a DOM adapter,
  // not an accessibility-tree or browser focus simulation.
  h.c.messages = { en: { metering: "Metering mode" }, zh: { metering: "測光模式" } };
  let language = "en";
  h.c.resolvedLanguage = () => language;
  vm.runInContext(production("settingLabel"), h.c);
  h.ui.settingDialog.showModal = () => { observed.push(h.ui.settingDialogTitle.textContent); };
  h.c.openSettingDialog(choiceSetting());
  language = "zh";
  h.c.openSettingDialog(choiceSetting());
  assert.deepEqual(observed, ["Metering mode", "測光模式"]);
});
for (const sameId of [false, true]) {
  const suffix = sameId ? "same session ID, new owner" : "different session ID";
  test(`reset closes an open setting dialog (${suffix})`, () => {
    const h = fixture(); h.c.openSettingDialog(choiceSetting()); assert.equal(h.ui.settingDialog.open, true);
    h.c.resetSession(); h.install(sameId ? "camera-a" : "camera-b");
    assert.equal(h.ui.settingDialog.open, false, "retired camera dialog must be closed by production reset");
  });
  test(`reset clears retired advanced controls before reconnect render (${suffix})`, () => {
    const h = fixture(); assert.ok(h.all().length); h.c.resetSession();
    assert.equal(h.ui.advancedSettings.children.length, 0, "old settings DOM must not survive reset");
  });
  for (const key of ["artist", "metering", "zoom"]) {
    test(`queued ${key} input cannot edit replacement owner draft (${suffix})`, async () => {
      const h = fixture(), input = controls(h, key).find(n => n.tagName !== "button");
      h.c.resetSession(); h.install(sameId ? "camera-a" : "camera-b"); h.c.renderAdvancedSettings();
      h.c.editSettingDraft(key, "NEW-OWNER-DRAFT");
      input.value = key === "artist" ? "OLD" : key === "zoom" ? "1" : "two";
      await input.dispatch(key === "metering" ? "change" : "input");
      assert.equal(h.state.settingDrafts[key], "NEW-OWNER-DRAFT", "retired input must not overwrite new draft");
    });
    test(`queued ${key} Apply cannot write replacement camera (${suffix})`, async () => {
      const h = fixture(), nodes = controls(h, key), input = nodes.find(n => n.tagName !== "button"), apply = nodes.find(n => n.tagName === "button");
      input.value = key === "artist" ? "OLD" : key === "zoom" ? "1" : "two";
      h.c.resetSession(); h.install(sameId ? "camera-a" : "camera-b"); h.c.renderAdvancedSettings();
      h.c.editSettingDraft(key, key === "artist" ? "NEW" : key === "zoom" ? "2" : "two");
      await apply.dispatch("click");
      assert.equal(h.requests.length, 0, "retired Apply must not dispatch to current session");
    });
  }
  test(`queued filename input cannot overwrite replacement draft (${suffix})`, async () => {
    const h = fixture(), input = h.all().find(n => n.dataset.fileNamingField === "still-user-setting-1");
    h.c.resetSession(); h.install(sameId ? "camera-a" : "camera-b"); h.c.renderAdvancedSettings();
    h.c.editCaptureConfigurationDraft("still-user-setting-1", "NEW1"); input.value = "OLD1"; await input.dispatch("input");
    assert.equal(h.state.fileNamingDrafts["still-user-setting-1"], "NEW1");
  });
  test(`queued filename mode change cannot write replacement camera (${suffix})`, async () => {
    const h = fixture(), input = h.all().find(n => n.tagName === "select" && !n.dataset.settingKey);
    h.c.resetSession(); h.install(sameId ? "camera-a" : "camera-b"); h.c.renderAdvancedSettings();
    input.value = "usersetting1"; await input.dispatch("change");
    assert.equal(h.requests.length, 0, "retired filename mode must not dispatch to current session");
  });
}
test("replaced modal editor callbacks are inert without resetting session", async () => {
  const h = fixture(); h.c.openSettingDialog(choiceSetting());
  const buttons = [...h.ui.settingOptions.children]; h.c.openSettingDialog(choiceSetting());
  await buttons[1].dispatch("click"); await buttons.at(-1).dispatch("click");
  assert.equal(h.state.settingDrafts.metering, undefined); assert.equal(h.requests.length, 0);
});
test("current advanced controls accept typing and retain legitimate same-owner draft across render", async () => {
  const h = fixture(), input = controls(h, "artist").find(n => n.tagName === "input");
  input.value = "My legitimate draft"; await input.dispatch("input");
  assert.equal(h.state.settingDrafts.artist, "My legitimate draft");
  h.c.renderAdvancedSettings();
  assert.equal(controls(h, "artist").find(n => n.tagName === "input").value, "My legitimate draft");
  assert.equal(h.requests.length, 0);
});
for (const key of ["artist", "metering", "zoom"]) {
  test(`same-session rerender retires queued ${key} draft and Apply callbacks`, async () => {
    const h = fixture(), nodes = controls(h, key), input = nodes.find(n => n.tagName !== "button"), apply = nodes.find(n => n.tagName === "button");
    const currentDraft = key === "artist" ? "LATEST" : key === "zoom" ? "2" : "two";
    h.c.editSettingDraft(key, currentDraft); h.c.renderAdvancedSettings();
    input.value = key === "artist" ? "STALE" : key === "zoom" ? "0" : "one";
    await input.dispatch(key === "metering" ? "change" : "input");
    const afterInput = h.state.settingDrafts[key];
    await apply.dispatch("click");
    assert.deepEqual({ draft: afterInput, requests: h.requests.length }, { draft: currentDraft, requests: 0 });
  });
}
for (const sameId of [false, true]) {
  test(`retired filename Apply is write-free (${sameId ? "same" : "different"} session ID)`, async () => {
    const h = fixture(), row = h.ui.advancedSettings.children.find(n => n.querySelectorAll("*").some(child => child.dataset.fileNamingField === "still-user-setting-1"));
    const input = row.querySelectorAll("*").find(n => n.tagName === "input"), apply = row.querySelectorAll("*").find(n => n.tagName === "button");
    input.value = "OLD1"; h.c.resetSession(); h.install(sameId ? "camera-a" : "camera-b"); h.c.renderAdvancedSettings();
    h.c.editCaptureConfigurationDraft("still-user-setting-1", "NEW1"); await apply.dispatch("click");
    assert.equal(h.requests.length, 0);
    assert.equal(h.state.fileNamingDrafts["still-user-setting-1"], "NEW1");
  });
}
test("current select/range edits and filename typing remain available", async () => {
  const h = fixture(), select = controls(h, "metering").find(n => n.tagName === "select"), range = controls(h, "zoom").find(n => n.tagName === "input");
  select.value = "two"; await select.dispatch("change"); range.value = "1"; await range.dispatch("input");
  const input = h.all().find(n => n.dataset.fileNamingField === "still-user-setting-1");
  input.value = "new1"; await input.dispatch("input");
  assert.equal(h.state.settingDrafts.metering, "two"); assert.equal(h.state.settingDrafts.zoom, "2");
  assert.equal(h.state.fileNamingDrafts["still-user-setting-1"], "NEW1"); assert.equal(h.requests.length, 0);
  h.c.renderAdvancedSettings();
  assert.equal(controls(h, "metering").find(n => n.tagName === "select").value, "two");
  assert.equal(controls(h, "zoom").find(n => n.tagName === "input").value, "1");
  assert.equal(h.all().find(n => n.dataset.fileNamingField === "still-user-setting-1").value, "NEW1");
});
for (const key of ["artist", "metering", "zoom"]) {
  test(`current ${key} Apply admits exactly one production setting write`, async () => {
    const h = fixture(), nodes = controls(h, key), input = nodes.find(n => n.tagName !== "button"), apply = nodes.find(n => n.tagName === "button");
    const value = key === "artist" ? "VALID AUTHOR" : key === "zoom" ? "2" : "two";
    input.value = key === "zoom" ? "1" : value;
    await input.dispatch(key === "metering" ? "change" : "input");
    await apply.dispatch("click");
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].url, `/v1/session/camera-a/settings/${key}/outcome`);
    assert.equal(h.requests[0].options.method, "POST");
    assert.equal(h.requests[0].options.payload.value, value);
  });
}
test("current filename mode change admits its production PUT", async () => {
  const h = fixture(), input = h.all().find(n => n.tagName === "select" && !n.dataset.settingKey);
  input.value = "usersetting1"; await input.dispatch("change");
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].url, "/v1/session/camera-a/file-naming/still-filename-mode");
  assert.equal(h.requests[0].options.method, "PUT"); assert.equal(h.requests[0].options.payload.value, "usersetting1");
});
test("current filename input Apply admits its production PUT", async () => {
  const h = fixture(), row = h.ui.advancedSettings.children.find(n => n.querySelectorAll("*").some(child => child.dataset.fileNamingField === "still-user-setting-1"));
  const input = row.querySelectorAll("*").find(n => n.tagName === "input"), apply = row.querySelectorAll("*").find(n => n.tagName === "button");
  input.value = "new1"; await input.dispatch("input"); await apply.dispatch("click");
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].url, "/v1/session/camera-a/file-naming/still-user-setting-1");
  assert.equal(h.requests[0].options.method, "PUT"); assert.equal(h.requests[0].options.payload.value, "NEW1");
});
test("simulated activeElement preserves unsubmitted same-owner text during render", () => {
  // This directly supplies activeElement to test render's preservation branch.
  // It is not a browser focus/blur, selection, or hidden-container lifecycle test.
  const h = fixture(), input = controls(h, "artist").find(n => n.tagName === "input");
  input.value = "Typing before input dispatch"; h.c.document.activeElement = input;
  h.c.renderAdvancedSettings();
  assert.equal(h.state.settingDrafts.artist, "Typing before input dispatch");
  assert.equal(controls(h, "artist").find(n => n.tagName === "input").value, "Typing before input dispatch");
  assert.equal(h.requests.length, 0);
});
test("all saved advanced input and Apply callbacks are inert while session is absent", async () => {
  const h = fixture(), oldControls = h.all().filter(n => ["input", "select", "button"].includes(n.tagName));
  h.c.resetSession();
  for (const input of oldControls) {
    input.value = input.type === "range" ? "1" : input.dataset.settingKey === "metering" ? "two" : "OLD1";
    await input.dispatch("input"); await input.dispatch("change"); await input.dispatch("click");
  }
  assert.equal(Object.keys(h.state.settingDrafts).length, 0);
  assert.equal(Object.keys(h.state.fileNamingDrafts).length, 0);
  assert.equal(h.requests.length, 0);
});
function bindProductionDialogClose(h) {
  // Execute the unchanged production listener registration, not a duplicate callback.
  const registration = '    ui.settingDialog.addEventListener("close", handleSettingDialogClosed);';
  assert.ok(source.includes(registration));
  vm.runInContext(registration, h.c);
}
test("production dialog close event invalidates old option and Apply callbacks", async () => {
  const h = fixture(); bindProductionDialogClose(h); h.c.openSettingDialog(choiceSetting());
  const editor = h.state.settingEditor, buttons = [...h.ui.settingOptions.children];
  h.ui.settingDialog.close(); await h.ui.settingDialog.dispatch("close");
  assert.equal(h.state.settingEditor, editor + 1);
  await buttons[1].dispatch("click"); await buttons.at(-1).dispatch("click");
  assert.equal(h.state.settingDrafts.metering, undefined); assert.equal(h.requests.length, 0);
});
test("reset plus production close event retires modal generation before reconnect", async () => {
  const h = fixture(); bindProductionDialogClose(h); h.c.openSettingDialog(choiceSetting());
  const editor = h.state.settingEditor, buttons = [...h.ui.settingOptions.children];
  h.c.resetSession(); assert.ok(h.state.settingEditor > editor); assert.equal(h.ui.settingDialog.open, false);
  // Browser close is queued; explicitly deliver it here rather than claiming a real event loop.
  await h.ui.settingDialog.dispatch("close"); h.install("camera-a"); h.c.openSettingDialog(choiceSetting());
  const currentEditor = h.state.settingEditor;
  await buttons[1].dispatch("click"); await buttons.at(-1).dispatch("click");
  assert.equal(h.state.settingEditor, currentEditor); assert.equal(h.ui.settingDialog.open, true);
  assert.equal(h.state.settingDrafts.metering, undefined); assert.equal(h.requests.length, 0);
  await h.ui.settingOptions.children[1].dispatch("click");
  await h.ui.settingOptions.children.at(-1).dispatch("click");
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].options.payload.value, "two");
});
function commandFixture() {
  const h = fixture(), commands = [], confirmations = [];
  h.state.capabilities.supported.push("CAMERA_CLOCK_SYNC", "DIRECTORY_CONTROL", "SENSOR_CLEANING", "CAMERA_SLEEP");
  h.c.recordingRecoveryActive = () => false;
  h.c.window.confirm = text => { confirmations.push(text); return true; };
  // Leaf camera commands are spies; all button creation/listeners are production render.
  h.c.syncCameraClock = () => commands.push("clock");
  h.c.createCaptureDirectory = (_, value) => commands.push(`directory:${value}`);
  h.c.cleanSensor = (_, powerOff) => commands.push(powerOff ? "clean-power-off" : "clean");
  h.c.sleepCamera = () => commands.push("sleep");
  h.c.renderAdvancedSettings();
  const buttons = h.all().filter(n => ["clock", "directory", "sensor-cleaning", "sleep"].includes(n.dataset.cameraCommand));
  const directoryRow = h.ui.advancedSettings.children.find(row => row.querySelectorAll("*").some(n => n.dataset.cameraCommand === "directory"));
  const directoryInput = directoryRow.querySelectorAll("*").find(n => n.tagName === "input");
  return { ...h, commands, confirmations, buttons, directoryInput };
}
for (const replacement of ["absent", "same-id", "different-id", "rerender"]) {
  test(`retired maintenance/directory callbacks make no confirmation or command (${replacement})`, async () => {
    const h = commandFixture();
    if (replacement === "rerender") h.c.renderAdvancedSettings();
    else {
      h.c.resetSession();
      if (replacement !== "absent") { h.install(replacement === "same-id" ? "camera-a" : "camera-b"); h.c.renderAdvancedSettings(); }
    }
    h.state.captureDirectoryDraft = "NEW01";
    h.directoryInput.value = "old01"; await h.directoryInput.dispatch("input");
    for (const button of h.buttons) await button.dispatch("click");
    assert.equal(h.state.captureDirectoryDraft, "NEW01");
    assert.deepEqual(h.commands, []); assert.deepEqual(h.confirmations, []); assert.equal(h.requests.length, 0);
  });
}
test("current maintenance/directory callbacks retain command and confirmation behavior", async () => {
  const h = commandFixture(); h.directoryInput.value = "new01"; await h.directoryInput.dispatch("input");
  assert.equal(h.state.captureDirectoryDraft, "NEW01");
  for (const button of h.buttons) await button.dispatch("click");
  assert.deepEqual(h.commands, ["clock", "directory:NEW01", "clean", "clean-power-off", "sleep"]);
  assert.deepEqual(h.confirmations, ["sensorCleaningConfirm", "sensorCleaningPowerOffConfirm", "cameraSleepConfirm"]);
});
for (const replacement of ["same-id", "different-id", "rerender"]) {
  test(`retired production exposure button cannot open editor or write (${replacement})`, async () => {
    const h = fixture(); h.c.CORE_SETTINGS.push("iso");
    const iso = () => ({ key: "iso", label: "ISO", inputKind: "choice", value: "400", values: ["400", "800"] });
    h.state.capabilities.settings.push(iso()); vm.runInContext(production("renderExposure"), h.c); h.c.renderExposure();
    const oldButton = h.ui.exposureStrip.children[0];
    if (replacement !== "rerender") { h.c.resetSession(); h.install(replacement === "same-id" ? "camera-a" : "camera-b"); h.state.capabilities.settings.push(iso()); }
    h.c.renderExposure(); const editor = h.state.settingEditor;
    await oldButton.dispatch("click");
    assert.equal(h.ui.settingDialog.open, false, "retired exposure callback must not open a new owner editor");
    assert.equal(h.state.settingEditor, editor); assert.equal(h.requests.length, 0);
    await h.ui.exposureStrip.children[0].dispatch("click");
    assert.equal(h.ui.settingDialog.open, true);
    await h.ui.settingOptions.children[1].dispatch("click"); await h.ui.settingOptions.children.at(-1).dispatch("click");
    assert.equal(h.requests.length, 1); assert.equal(h.requests[0].options.payload.value, "800");
  });
}
test("delayed old modal close event cannot retire replacement owner's active editor", async () => {
  const h = fixture(); bindProductionDialogClose(h); h.c.openSettingDialog(choiceSetting());
  h.c.resetSession(); h.install("camera-a"); h.c.openSettingDialog(choiceSetting());
  const currentEditor = h.state.settingEditor;
  // Deliver the old close only after the replacement owner has opened its modal.
  await h.ui.settingDialog.dispatch("close");
  assert.equal(h.state.settingEditor, currentEditor, "old close event must retain its original editor identity");
  await h.ui.settingOptions.children[1].dispatch("click"); await h.ui.settingOptions.children.at(-1).dispatch("click");
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].options.payload.value, "two");
});
