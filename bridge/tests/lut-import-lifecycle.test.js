"use strict";
// Imported production controller/decoder plus app DOM adapters with controlled Blob/DOM boundaries.
// Queue ownership is never reconstructed from slices of the application controller.
// This is Node orchestration evidence, not browser, native picker, GPU or camera evidence.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const lut = require("../open_eos_bridge/static/lut.js");
const lutImport = require("../open_eos_bridge/static/lut-import-controller.js");
const contract = require("../../contracts/lut-import/v1/utf8-vectors.json");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
const cube = contract.vectors.find(v => v.id === "ascii").normalizedText;
const encoder = new TextEncoder();
function production(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.ok(start >= 0, name);
  return source.slice(start, source.indexOf("\n  }", start) + 4);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const flush = () => new Promise(setImmediate);
function element() {
  return { value: "", hidden: false, dataset: {}, files: [], listeners: {},
    cloneNode: element, replaceWith(next) { this.replacement = next; },
    setAttribute(name, value) { this[name] = value; },
    addEventListener(name, callback) { this.listeners[name] = callback; },
    fire(name, event = {}) { return this.listeners[name]?.(event); }, click() { this.clicked = true; },
  };
}
function fixture({ existing = true, rendererFailure = false } = {}) {
  const previous = lut.parseCubeLut(cube, "Existing.cube");
  const state = { monitorSettings: { cubeLut: existing ? previous : null, desqueeze: 1 },
    monitorLutRevision: 7, monitorFrameEvidence: { frameRevision: 2 },
    monitorAnalysisError: "previous frame error", monitorLutImportOwner: null,
    monitorLutReadOwner: null, monitorLutPicker: null, monitorLutImportError: null };
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const calls = { toasts: [], renders: 0, diagnostics: 0, rendererCreations: 0, parses: 0, reads: [] };
  const options = { rendererFailure, afterParse: null, afterRenderer: null };
  const document = { body: {}, activeElement: null, querySelector: () => null };
  document.activeElement = document.body;
  const context = vm.createContext({ state, ui, document, Error, RangeError, TypeError,
    lutImport, lutImportController: null,
    lut: { ...lut, parseCubeLutBytes(bytes, name) {
      calls.parses++; const parsed = lut.parseCubeLutBytes(bytes, name); options.afterParse?.(); return parsed;
    }, createWebGLRenderer() {
      calls.rendererCreations++;
      if (options.rendererFailure) throw Error(typeof options.rendererFailure === "string"
        ? options.rendererFailure : "Synthetic renderer unavailable");
      options.afterRenderer?.(); return { dispose() {} };
    } }, lutPreviewRenderer: null, t: (key, vars = {}) => `${key}:${JSON.stringify(vars)}`,
    renderMonitoringFrame() { calls.renders++; state.monitorAnalysisError = null; },
    renderDiagnostics() { calls.diagnostics++; }, showToast: (...args) => calls.toasts.push(args),
  });
  for (const name of ["renderMonitoringControls", "prepareCubeLutPicker", "openCubeLutPicker",
    "cubeLutImportController", "cancelCubeLutImport", "importCubeLutFile", "removeCubeLut"])
    vm.runInContext(production(name), context);
  function file(name = "Replacement", { text = cube, bytes = encoder.encode(text), reportedSize = bytes.byteLength,
    hold = false, failure = null } = {}) {
    const gate = deferred();
    const input = { name: `${name}.cube`, size: reportedSize, gate, bytes,
      slice(start, end) {
        assert.equal(start, 0); assert.equal(end, lut.MAX_CUBE_BYTES + 1);
        calls.reads.push(name);
        return { async arrayBuffer() {
          if (hold) await gate.promise;
          if (failure) throw Error(failure);
          return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
        } };
      }, text() { assert.fail("lossy unbounded File.text must not be used"); },
      arrayBuffer() { assert.fail("unbounded File.arrayBuffer must not be used"); },
    };
    return input;
  }
  function choose(inputFile) {
    context.openCubeLutPicker();
    const input = ui.monitorLutFile;
    input.files = inputFile ? [inputFile] : [];
    input.fire("change");
    return input;
  }
  return { context, state, ui, calls, options, previous, file, choose, document };
}

for (const vector of contract.vectors) test(`shared UTF-8 contract: ${vector.id}`, () => {
  const bytes = Buffer.from(vector.base64, "base64");
  if (vector.accepted) {
    assert.equal(lut.decodeCubeLutText(bytes), vector.normalizedText);
    assert.equal(lut.parseCubeLutBytes(bytes, "Synthetic.cube").size, 2);
  } else {
    assert.throws(() => lut.decodeCubeLutText(bytes), vector.reason === "duplicate-leading-bom" ? /repeated/ : /valid UTF-8/);
    assert.throws(() => lut.parseCubeLutBytes(bytes, "Synthetic.cube"));
  }
});

test("byte size contract accepts exactly 16 MiB and rejects oversized metadata before any read", async () => {
  assert.equal(lut.MAX_CUBE_BYTES, contract.maximumBytes);
  const h = fixture();
  const exact = h.file("Exact", { bytes: new Uint8Array(contract.sizeBoundaryCases[0].bytes) });
  assert.equal((await lut.readCubeLutBytes(exact)).byteLength, contract.maximumBytes);
  const over = h.file("Too large", { reportedSize: contract.sizeBoundaryCases[1].bytes });
  await assert.rejects(lut.readCubeLutBytes(over), /16 MiB/);
  assert.deepEqual(h.calls.reads, ["Exact"]);
});

test("bounded read catches a dishonest size, truncated content and missing size", async () => {
  const h = fixture();
  await assert.rejects(lut.readCubeLutBytes(h.file("Overflow", {
    reportedSize: 0, bytes: new Uint8Array(lut.MAX_CUBE_BYTES + 1) })), /16 MiB/);
  await assert.rejects(lut.readCubeLutBytes(h.file("Truncated", { reportedSize: 500 })), /completely/);
  for (const size of [NaN, -1, 0.5, Infinity, undefined]) {
    await assert.rejects(lut.readCubeLutBytes({ size }), /size is unavailable/);
  }
});

test("valid Unicode, BOM and decoded replacement scalar do not alter the existing cube grammar", () => {
  assert.equal(lut.parseCubeLutBytes(encoder.encode(`TITLE "測試 🎥 �"\n${cube}`)).name, "測試 🎥 �");
  assert.throws(() => lut.parseCubeLutBytes(encoder.encode("LUT_1D_SIZE 2\n0 0 0\n1 1 1")), /not supported/);
  assert.throws(() => lut.parseCubeLutBytes(encoder.encode("LUT_3D_SIZE 2\n0 0 0")), /requires 8/);
});

test("successful replacement keeps the old LUT until read and setup complete, then advances one revision", async () => {
  const h = fixture(), input = h.file("New", { hold: true });
  const pending = h.context.importCubeLutFile(input);
  assert.equal(h.state.monitorSettings.cubeLut, h.previous);
  assert.equal(h.state.monitorLutRevision, 7);
  assert.equal(h.ui.monitorLutCancel.hidden, false);
  assert.equal(h.ui.monitorLutStatus["aria-busy"], "true");
  assert.match(h.ui.monitorLutSummary.textContent, /Existing/);
  input.gate.resolve(); await pending;
  assert.equal(h.state.monitorSettings.cubeLut.name, "New");
  assert.equal(h.state.monitorLutRevision, 8);
  assert.equal(h.state.monitorFrameEvidence, null);
  assert.equal(h.state.monitorLutImportOwner, null);
  assert.equal(h.state.monitorLutReadOwner, null);
  assert.equal(h.ui.monitorLutCancel.hidden, true);
  assert.equal(h.ui.monitorLutStatus.hidden, true);
  assert.equal(h.calls.toasts.length, 1);
});

for (const reason of ["read", "encoding", "syntax", "size", "renderer"]) {
  test(`latest ${reason} failure preserves the existing LUT, revision and frame error`, async () => {
    const h = fixture({ rendererFailure: reason === "renderer" });
    const input = h.file("Bad", reason === "read" ? { failure: "Synthetic file unreadable" } :
      reason === "encoding" ? { bytes: Buffer.from([0x80]) } :
      reason === "syntax" ? { text: "invalid cube" } :
      reason === "size" ? { reportedSize: lut.MAX_CUBE_BYTES + 1 } : {});
    await h.context.importCubeLutFile(input);
    assert.equal(h.state.monitorSettings.cubeLut, h.previous);
    assert.equal(h.state.monitorLutRevision, 7);
    assert.equal(h.state.monitorAnalysisError, "previous frame error");
    assert.ok(h.state.monitorLutImportError);
    assert.equal(h.ui.monitorLutStatus.hidden, false);
    assert.equal(h.ui.monitorLutCancel.hidden, true);
    assert.equal(h.calls.toasts.length, 1);
    const error = h.state.monitorLutImportError;
    h.context.renderMonitoringFrame(); h.context.renderMonitoringControls();
    assert.equal(h.state.monitorLutImportError, error);
    assert.match(h.ui.monitorLutStatus.textContent, /lutLoadFailed/);
  });
}

test("latest replacement alone is queued while at most one read remains active", async () => {
  const h = fixture(), a = h.file("A", { hold: true }), b = h.file("B"), c = h.file("C", { hold: true });
  const first = h.context.importCubeLutFile(a);
  h.context.importCubeLutFile(b); h.context.importCubeLutFile(c);
  assert.deepEqual(h.calls.reads, ["A"]);
  assert.match(h.ui.monitorLutStatus.textContent, /lutWaiting/);
  assert.equal(h.state.monitorSettings.cubeLut, h.previous);
  a.gate.resolve(); await first;
  assert.deepEqual(h.calls.reads, ["A", "C"]);
  assert.equal(h.calls.parses, 0);
  assert.equal(h.state.monitorLutImportOwner.file, c);
  assert.equal(h.ui.monitorLutCancel.hidden, false);
  c.gate.resolve(); await flush();
  assert.equal(h.state.monitorSettings.cubeLut.name, "C");
  assert.equal(h.state.monitorLutRevision, 8);
  assert.equal(h.calls.parses, 1);
  assert.equal(h.calls.toasts.length, 1);
});

test("obsolete read failure cannot publish an error or clear the newer loading owner", async () => {
  const h = fixture(), a = h.file("A", { hold: true, failure: "Obsolete failure" }), b = h.file("B", { hold: true });
  const first = h.context.importCubeLutFile(a); h.context.importCubeLutFile(b);
  a.gate.resolve(); await first;
  assert.equal(h.state.monitorLutImportError, null);
  assert.equal(h.state.monitorLutImportOwner.file, b);
  assert.equal(h.ui.monitorLutStatus["aria-busy"], "true");
  assert.equal(h.calls.toasts.length, 0);
  b.gate.resolve(); await flush(); assert.equal(h.state.monitorSettings.cubeLut.name, "B");
});

for (const action of ["cancel", "remove"]) test(`${action} retires both pending reads and queued replacements`, async () => {
  const h = fixture(), a = h.file("A", { hold: true }), b = h.file("B");
  const pending = h.context.importCubeLutFile(a); h.context.importCubeLutFile(b);
  h.context[action === "cancel" ? "cancelCubeLutImport" : "removeCubeLut"]();
  assert.equal(h.state.monitorLutImportOwner, null);
  assert.ok(h.state.monitorLutReadOwner, "logical cancel does not falsely claim physical read abortion");
  assert.equal(h.ui.monitorLutCancel.hidden, true);
  assert.equal(h.ui.monitorLutStatus.hidden, false);
  assert.equal(h.ui.monitorLutStatus["aria-busy"], "true");
  assert.match(h.ui.monitorLutStatus.textContent, /lutStopping/);
  a.gate.resolve(); await pending;
  assert.deepEqual(h.calls.reads, ["A"]);
  assert.equal(h.calls.parses, 0);
  assert.equal(h.calls.rendererCreations, 0);
  assert.equal(h.state.monitorSettings.cubeLut, action === "cancel" ? h.previous : null);
  assert.equal(h.state.monitorLutRevision, action === "cancel" ? 7 : 8);
  assert.equal(h.ui.monitorLutStatus.hidden, true);
});

test("cancel then reselect waits for the old physical read and publishes only the fresh choice", async () => {
  const h = fixture(), a = h.file("A", { hold: true }), b = h.file("B");
  const pending = h.context.importCubeLutFile(a); h.context.cancelCubeLutImport(); h.context.importCubeLutFile(b);
  assert.deepEqual(h.calls.reads, ["A"]);
  a.gate.resolve(); await pending; await flush();
  assert.deepEqual(h.calls.reads, ["A", "B"]);
  assert.equal(h.state.monitorSettings.cubeLut.name, "B");
  assert.equal(h.calls.toasts.length, 1);
});

test("remove is idempotent and a failed first import never invents an applied LUT", async () => {
  const h = fixture({ existing: false });
  await h.context.importCubeLutFile(h.file("Bad", { text: "bad" }));
  assert.equal(h.state.monitorSettings.cubeLut, null); assert.equal(h.state.monitorLutRevision, 7);
  h.context.removeCubeLut(); h.context.removeCubeLut();
  assert.equal(h.state.monitorLutRevision, 7);
  assert.equal(h.state.monitorLutImportError, null);
  assert.equal(h.calls.toasts.length, 1);
});

test("null selection and native picker cancellation preserve the active valid import", async () => {
  const h = fixture(), a = h.file("A", { hold: true });
  const pending = h.context.importCubeLutFile(a), owner = h.state.monitorLutImportOwner;
  h.choose(null); assert.equal(h.state.monitorLutImportOwner, owner);
  h.context.openCubeLutPicker(); h.ui.monitorLutFile.fire("cancel");
  assert.equal(h.state.monitorLutImportOwner, owner);
  assert.equal(h.calls.toasts.length, 0);
  a.gate.resolve(); await pending; assert.equal(h.state.monitorSettings.cubeLut.name, "A");
});

test("stale and duplicate native callbacks cannot consume a newer picker or import", async () => {
  const h = fixture(); h.context.openCubeLutPicker(); const old = h.ui.monitorLutFile;
  h.context.openCubeLutPicker(); const latest = h.ui.monitorLutFile, ticket = h.state.monitorLutPicker;
  old.files = [h.file("Old")]; old.fire("change"); old.fire("cancel");
  assert.equal(h.state.monitorLutPicker, ticket); assert.deepEqual(h.calls.reads, []);
  latest.files = [h.file("Latest")]; latest.fire("change"); latest.fire("change");
  await flush(); assert.equal(h.state.monitorSettings.cubeLut.name, "Latest");
  assert.deepEqual(h.calls.reads, ["Latest"]); assert.equal(latest.value, "");
});

for (const action of ["cancelCubeLutImport", "removeCubeLut"]) test(`${action} fences an outstanding native picker`, async () => {
  const h = fixture(); h.context.openCubeLutPicker(); const old = h.ui.monitorLutFile;
  h.context[action](); old.files = [h.file("Late")]; old.fire("change"); await flush();
  assert.deepEqual(h.calls.reads, []);
  assert.equal(h.state.monitorSettings.cubeLut, action === "removeCubeLut" ? null : h.previous);
});

for (const boundary of ["afterParse", "afterRenderer"]) test(`publication rechecks ownership ${boundary}`, async () => {
  const h = fixture(); h.options[boundary] = () => h.context.cancelCubeLutImport();
  await h.context.importCubeLutFile(h.file("Superseded"));
  assert.equal(h.state.monitorSettings.cubeLut, h.previous);
  assert.equal(h.state.monitorLutRevision, 7);
  assert.equal(h.calls.toasts.length, 0);
});

function lifecycle(h, event) {
  const handlers = {};
  const noop = () => {};
  Object.assign(h.context, { window: { addEventListener: (key, callback) => { handlers[key] = callback; } },
    document: { visibilityState: "hidden", addEventListener: (key, callback) => { handlers[key] = callback; } },
    suspendMaintenanceForLifecycle: noop, cancelMediaUpload: noop, stopMediaAutoImport: noop,
    releaseHeldAutofocusForLifecycle: noop, releaseBulbForLifecycle: noop, cancelCameraFocusInfo: noop, cancelCameraScan: noop, cancelCameraConnection: noop,
    cancelMediaDownload: noop, clearMediaThumbnails: noop, closeMediaPreview: noop, closeMediaDetails: noop,
    stopLocalVideo: noop, stopRtpAudio: noop, refreshMediaDateZone: noop, renderAvailability: noop,
    renderMediaAutoImport: noop,
  });
  const marker = event === "visibilitychange" ? '    document.addEventListener?.("visibilitychange"' :
    `    window.addEventListener("${event}"`;
  const start = source.indexOf(marker); assert.ok(start >= 0);
  vm.runInContext(source.slice(start, source.indexOf("\n    });", start) + 8), h.context);
  return handlers[event];
}
for (const event of ["pagehide", "beforeunload"]) test(`${event} retires picker, active and queued work without late recreation`, async () => {
  const h = fixture(), a = h.file("A", { hold: true }), b = h.file("B");
  const pending = h.context.importCubeLutFile(a); h.context.importCubeLutFile(b);
  h.context.openCubeLutPicker(); const input = h.ui.monitorLutFile;
  lifecycle(h, event)(); input.files = [h.file("Late picker")]; input.fire("change");
  a.gate.resolve(); await pending; await flush();
  assert.deepEqual(h.calls.reads, ["A"]); assert.equal(h.calls.rendererCreations, 0);
  assert.equal(h.state.monitorSettings.cubeLut, h.previous);
  assert.equal(h.state.monitorLutImportOwner, null); assert.equal(h.state.monitorLutPicker, null);
  assert.equal(h.state.monitorLutRevision, 7); assert.equal(h.calls.toasts.length, 0);
});

test("hidden visibility, dialog Done and camera-session replacement preserve page-owned imports", async () => {
  const h = fixture(), a = h.file("A", { hold: true });
  const pending = h.context.importCubeLutFile(a);
  lifecycle(h, "visibilitychange")();
  h.state.session = { id: "synthetic replacement" };
  h.ui.monitoringDialog.close = () => { h.ui.monitoringDialog.closed = true; };
  const line = source.split("\n").find(v => v.includes('ui.monitoringDialogClose.addEventListener("click"'));
  vm.runInContext(line, h.context); h.ui.monitoringDialogClose.fire("click");
  assert.equal(h.ui.monitoringDialog.closed, true);
  assert.ok(h.state.monitorLutImportOwner);
  a.gate.resolve(); await pending; assert.equal(h.state.monitorSettings.cubeLut.name, "A");
  assert.doesNotMatch(production("disconnectCamera"), /cancelCubeLutImport|cubeLut|monitorLutImport/);
});

test("fresh explicit selection works after pagehide; pageshow never restarts a canceled read", async () => {
  const h = fixture(), a = h.file("A", { hold: true });
  const pending = h.context.importCubeLutFile(a); lifecycle(h, "pagehide")(); lifecycle(h, "pageshow")();
  a.gate.resolve(); await pending;
  assert.deepEqual(h.calls.reads, ["A"]);
  h.choose(h.file("Fresh")); await flush(); assert.equal(h.state.monitorSettings.cubeLut.name, "Fresh");
});

for (const active of [false, true]) test(`real picker opening error is owned without cancelling an active import (${active})`, async () => {
  const h = fixture(), a = h.file("A", { hold: true });
  const pending = active ? h.context.importCubeLutFile(a) : null;
  const owner = h.state.monitorLutImportOwner;
  h.ui.monitorLutFile.cloneNode = () => ({ ...element(), click() { throw Error("Synthetic picker unavailable"); } });
  h.context.openCubeLutPicker();
  assert.equal(h.state.monitorLutPicker, null);
  assert.equal(h.state.monitorLutImportOwner, owner);
  assert.equal(h.state.monitorSettings.cubeLut, h.previous);
  assert.equal(h.calls.toasts.length, 1);
  if (active) {
    assert.equal(h.state.monitorLutImportError, null);
    a.gate.resolve(); await pending; assert.equal(h.state.monitorSettings.cubeLut.name, "A");
  } else assert.match(h.state.monitorLutImportError, /picker unavailable/);
});

test("late picker opening error after accepted selection cannot damage that import", async () => {
  const h = fixture(), a = h.file("A", { hold: true });
  h.ui.monitorLutFile.cloneNode = () => ({ ...element(), click() {
    this.files = [a]; this.fire("change"); throw Error("Obsolete picker error");
  } });
  h.context.openCubeLutPicker();
  assert.equal(h.state.monitorLutImportOwner.file, a);
  assert.equal(h.state.monitorLutImportError, null); assert.equal(h.calls.toasts.length, 0);
  a.gate.resolve(); await flush(); assert.equal(h.state.monitorSettings.cubeLut.name, "A");
});

test("monitoring option edits during a read survive successful LUT replacement", async () => {
  const h = fixture(), a = h.file("A", { hold: true });
  const pending = h.context.importCubeLutFile(a);
  h.state.monitorSettings = { ...h.state.monitorSettings, desqueeze: 2, histogramVisible: true };
  a.gate.resolve(); await pending;
  assert.equal(h.state.monitorSettings.desqueeze, 2); assert.equal(h.state.monitorSettings.histogramVisible, true);
});

for (const stage of ["provider", "renderer"]) test(`production diagnostics omit private LUT ${stage} errors while the local UI retains details`, async () => {
  const privateName = "SYNTHETIC_PRIVATE_LOOK.cube", privateTitle = "SYNTHETIC_PRIVATE_TITLE";
  const privatePath = "/synthetic-private-root/looks/SYNTHETIC_PRIVATE_LOOK.cube";
  const privateContents = "SYNTHETIC_PRIVATE_CONTENTS";
  const failure = `${stage}: ${privateName}, ${privateTitle}, ${privatePath}, ${privateContents}`;
  const h = fixture({ rendererFailure: stage === "renderer" ? failure : false });
  h.state.monitorSettings.cubeLut = { ...h.previous, name: privateTitle };
  await h.context.importCubeLutFile(h.file(privateName, stage === "provider" ? { failure } : {}));
  assert.equal(h.state.monitorLutImportError, failure);
  assert.ok(h.ui.monitorLutStatus.textContent.includes(failure), "local UI keeps the actionable error");
  Object.assign(h.state, { media: [], localVideoSupport: {}, localVideoInputs: [],
    lastError: "Synthetic independent camera error" });
  Object.assign(h.context, { diagnostics: require("../open_eos_bridge/static/diagnostics.js"),
    diagnosticCapabilities: () => null, previewActive: () => false,
    currentRtpAudioStatus: () => null, currentMonitorFrameEvidence: () => null });
  vm.runInContext(production("diagnosticReport"), h.context);
  const report = h.context.diagnosticReport(), serialized = JSON.stringify(report);
  assert.equal(report.liveView.monitoring.lutImportFailed, true);
  assert.equal(report.liveView.monitoring.lutImportPending, false);
  assert.equal(report.liveView.monitoring.lutReadPending, false);
  assert.deepEqual(report.liveView.monitoring.lut, { loaded: true, size: 2 });
  for (const canary of [privateName, privateTitle, privatePath, privateContents]) {
    assert.equal(serialized.includes(canary), false, canary);
  }
  assert.equal(report.lastError, "Synthetic independent camera error");
  h.state.monitorLutImportError = null;
  assert.equal(h.context.diagnosticReport().liveView.monitoring.lutImportFailed, false);
});

// Model the focus eviction observed in real Firefox when the focused button is hidden.
function focusedLutControl(h, key, onHide = () => {}) {
  const button = h.ui[key];
  let hidden = false;
  Object.defineProperty(button, "hidden", { configurable: true, get: () => hidden, set(value) {
    hidden = value; if (value && h.document.activeElement === button) h.document.activeElement = h.document.body;
    if (value) onHide();
  } });
  h.ui.monitoringDialog.open = true;
  h.ui.monitoringDialog.contains = element => [button, h.ui.monitorLutLoad].includes(element);
  Object.assign(h.ui.monitorLutLoad, { isConnected: true, hidden: false, disabled: false,
    getClientRects: () => [{}], focus(options) { this.focusOptions = options; h.document.activeElement = this; } });
  h.document.activeElement = button;
  return button;
}

test("keyboard LUT removal returns to its visible load control", () => {
  const h = fixture(); focusedLutControl(h, "monitorLutRemove");
  h.context.removeCubeLut();
  assert.equal(h.document.activeElement, h.ui.monitorLutLoad);
  assert.equal(h.ui.monitorLutLoad.focusOptions.preventScroll, true);
});

test("cancelling a pending LUT import preserves keyboard continuation", async () => {
  const h = fixture(), input = h.file("Held", { hold: true });
  const work = h.context.importCubeLutFile(input); focusedLutControl(h, "monitorLutCancel");
  h.context.cancelCubeLutImport();
  assert.equal(h.document.activeElement, h.ui.monitorLutLoad);
  input.gate.resolve(); await work;
  assert.equal(h.state.monitorSettings.cubeLut, h.previous);
});

test("settled pending LUT read returns a disappearing cancel control only", async () => {
  const h = fixture(), input = h.file("New", { hold: true });
  const work = h.context.importCubeLutFile(input); focusedLutControl(h, "monitorLutCancel");
  input.gate.resolve(); await work;
  assert.equal(h.document.activeElement, h.ui.monitorLutLoad);
  assert.equal(h.state.monitorSettings.cubeLut.name, "New");
});

for (const boundary of ["closed", "other-focus", "detached", "disabled", "hidden", "no-rect", "other-dialog"]) {
  test(`retiring LUT control does not steal focus across ${boundary}`, () => {
    const h = fixture(); focusedLutControl(h, "monitorLutRemove");
    const other = {};
    if (boundary === "closed") h.ui.monitoringDialog.open = false;
    if (boundary === "other-focus") h.document.activeElement = other;
    if (boundary === "detached") h.ui.monitorLutLoad.isConnected = false;
    if (boundary === "disabled") h.ui.monitorLutLoad.disabled = true;
    if (boundary === "hidden") h.ui.monitorLutLoad.hidden = true;
    if (boundary === "no-rect") h.ui.monitorLutLoad.getClientRects = () => [];
    if (boundary === "other-dialog") h.document.querySelector = () => ({});
    h.context.removeCubeLut();
    assert.notEqual(h.document.activeElement, h.ui.monitorLutLoad);
    if (boundary === "other-focus") assert.equal(h.document.activeElement, other);
  });
}

test("LUT hiding does not steal focus moved by a synchronous blur handler", () => {
  const h = fixture(), other = {};
  focusedLutControl(h, "monitorLutRemove", () => { h.document.activeElement = other; });
  h.context.removeCubeLut();
  assert.equal(h.document.activeElement, other);
});

test("standalone browser module loads before the app and exports the same controller API", () => {
  const filename = "lut-import-controller.js";
  const html = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/index.html"), "utf8");
  const marker = `<script src="/app/${filename}"></script>`;
  assert.equal(html.split(marker).length, 2, "The production page loads the controller exactly once");
  assert.ok(html.indexOf(marker) < html.indexOf('<script src="/app/app.js"></script>'));
  const browser = vm.createContext({});
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static", filename), "utf8"), browser);
  assert.equal(typeof browser.OpenEOSLutImport.createController, "function");
  assert.deepEqual(Object.keys(browser.OpenEOSLutImport), Object.keys(lutImport));
  assert.ok(source.includes("const lutImport = globalThis.OpenEOSLutImport;"));
  assert.ok(production("cubeLutImportController").includes("lutImport.createController("));
  assert.doesNotMatch(source, /function readPendingCubeLut\(/,
    "Production queue ownership exists only in the imported module");
});

function controllerFixture(onRender = () => {}) {
  const state = { monitorSettings: { cubeLut: null }, monitorLutImportOwner: null,
    monitorLutReadOwner: null, monitorLutImportError: null, monitorLutRevision: 0 };
  const reads = [], published = [], failures = [];
  const controller = lutImport.createController({ state,
    readBytes(file) { reads.push(file); return file.gate.promise; },
    parseBytes: (bytes, name) => ({ bytes, name }), prepareRenderer() {}, renderFrame() {},
    reportSuccess() { published.push(state.monitorSettings.cubeLut.name); },
    reportFailure(message) { failures.push(message); },
    renderState() { onRender(controller, state); },
  });
  return { controller, state, reads, published, failures };
}

test("imported controller instances do not share active reads or replacement ownership", async () => {
  const first = controllerFixture(), second = controllerFixture();
  const a = { name: "A", gate: deferred() }, b = { name: "B", gate: deferred() };
  const aWork = first.controller.select(a), bWork = second.controller.select(b);
  first.controller.cancel();
  assert.equal(first.state.monitorLutImportOwner, null);
  assert.equal(first.state.monitorLutReadOwner.file, a);
  assert.equal(second.state.monitorLutImportOwner.file, b);
  b.gate.resolve("B bytes"); await bWork;
  a.gate.resolve("A bytes"); await aWork;
  assert.deepEqual(first.published, []);
  assert.deepEqual(second.published, ["B"]);
  assert.equal(first.state.monitorSettings.cubeLut, null);
  assert.equal(first.state.monitorLutReadOwner, null);
  assert.equal(second.state.monitorLutReadOwner, null);
});

test("imported controller respects synchronous cancellation before physical read admission", async () => {
  const h = controllerFixture((controller, state) => {
    if (state.monitorLutImportOwner && !state.monitorLutReadOwner) controller.cancel();
  });
  await h.controller.select({ name: "Cancelled", gate: deferred() });
  assert.deepEqual(h.reads, []);
  assert.deepEqual(h.published, []);
  assert.deepEqual(h.failures, []);
  assert.equal(h.state.monitorLutImportOwner, null);
  assert.equal(h.state.monitorLutReadOwner, null);
});

test("imported controller admits only a reentrant successor from state publication", async () => {
  const a = { name: "A", gate: deferred() }, b = { name: "B", gate: deferred() };
  let bWork;
  const h = controllerFixture((controller, state) => {
    if (state.monitorLutImportOwner?.file === a) bWork = controller.select(b);
  });
  await h.controller.select(a);
  assert.deepEqual(h.reads, [b]);
  assert.equal(h.state.monitorLutReadOwner.file, b);
  b.gate.resolve("B bytes"); await bWork;
  assert.deepEqual(h.published, ["B"]);
  assert.equal(h.state.monitorLutRevision, 1);
  assert.equal(h.state.monitorLutReadOwner, null);
});
