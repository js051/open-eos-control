"use strict";

// Exercise extracted production callbacks with real selection, queue, streaming
// and destination modules. Browser handles and camera responses are synthetic;
// deferred promises control every ownership/commit boundary without timers.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaBatch = require("../open_eos_bridge/static/media-batch.js");
const mediaBatchDestination = require("../open_eos_bridge/static/media-batch-destination.js");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, name);
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function extractHistoryEvents() {
  const start = source.indexOf('    document.querySelectorAll("[data-open-media-history]")');
  const end = source.indexOf('    ui.mediaBatchStart.addEventListener', start);
  assert.ok(start >= 0 && end > start, "production history event bindings");
  return source.slice(start, end);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function missing() { return Object.assign(new Error("Missing synthetic entry"), { name: "NotFoundError" }); }
function element() {
  const listeners = new Map();
  return { value: "", textContent: "", hidden: false, disabled: false, open: false, dataset: {}, children: [],
    focuses: 0,
    classList: { toggle() {}, remove() {} }, style: { removeProperty() {} },
    replaceChildren() { this.children = []; }, append(...children) { this.children.push(...children); },
    querySelectorAll() { return []; }, setAttribute() {}, removeAttribute() {},
    addEventListener(type, listener) { const list = listeners.get(type) || []; list.push(listener); listeners.set(type, list); },
    dispatchEvent(event) { for (const listener of listeners.get(event.type) || []) listener(event); },
    focus() { this.focuses += 1; }, showModal() { this.open = true; },
    close() { this.open = false; this.dispatchEvent({ type: "close" }); } };
}

function fixture(options = {}) {
  const items = [
    { id: "synthetic:card1/PAIR.CR3", name: "PAIR.CR3", kind: "raw", sizeBytes: 3 },
    { id: "synthetic:card1/PAIR.JPG", name: "PAIR.JPG", kind: "image", sizeBytes: 3 },
    { id: "synthetic:card2/THIRD.JPG", name: "THIRD.JPG", kind: "image", sizeBytes: 3 },
  ];
  const selection = mediaBatch.createSelection();
  selection.replaceLoaded(items);
  selection.setMany(items.slice(0, options.count || 2).map(item => item.id), true);
  const state = {
    session: { id: "synthetic-session" }, media: items, mediaSelection: selection,
    mediaBatchOwner: null, mediaBatch: null, mediaDownloadOwner: null, mediaDownload: null,
    mediaUpload: null, mediaDownloadPreparing: false, busy: false, refreshGeneration: 0,
    mediaFilter: "all", mediaSort: "camera", mediaDateRange: null, mediaRatingFilter: "all",
    mediaFolderFilter: { kind: "all" }, operatorConfirmedFeatures: new Set(),
  };
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const calls = { pickers: [], requests: [], opens: [], raw: [], feedback: [], renders: [], history: [], historyWarnings: 0,
    historyRenders: 0, anchor: 0, directories: 0 };
  const entered = { writer: deferred(), write: deferred(), close: deferred(), abort: deferred(), api: deferred(),
    createBatchTicket: deferred(), startAttempt: deferred(), beginPlannedBatch: deferred(), beginPlannedAttempt: deferred(),
    finishAttempt: deferred(), clear: deferred(), historyRender: deferred() };
  // Script only the storage API boundary. IndexedDB transaction/epoch behavior is
  // covered separately; these records establish what the production caller sends
  // and whether it retries or recreates receipts after a rejected store operation.
  const historyPlan = options.history || {};
  const historyRows = new Map();
  const historyPlans = new Map();
  const historyTicket = Object.freeze({ batchId: "synthetic-batch", epoch: 0 });
  let nextReceipt = 0;
  async function historyOperation(operation, args, defaultResult) {
    const call = { operation, args };
    calls.history.push(call);
    entered[operation]?.resolve();
    const configured = historyPlan[operation] || {};
    const operationIndex = calls.history.filter(entry => entry.operation === operation).length - 1;
    const plan = Array.isArray(configured) ? configured[operationIndex] || {} : configured;
    plan.entered?.resolve();
    if (plan.gate) await plan.gate.promise;
    if (plan.error) throw plan.error;
    call.result = Object.hasOwn(plan, "result") ? plan.result : defaultResult();
    return call.result;
  }
  const mediaHistoryStore = {
    createBatchTicket: (...args) => historyOperation("createBatchTicket", args, () => ({ ok: true, ticket: historyTicket })),
    startAttempt: (ticket, input) => historyOperation("startAttempt", [ticket, input], () => {
      const receipt = Object.freeze({ receiptId: `synthetic-receipt-${++nextReceipt}`, batchId: ticket.batchId, epoch: ticket.epoch });
      historyRows.set(receipt, { ...receipt, ...input, startedAtMillis: 1700000000000,
        outcome: "in-progress", mayRemain: false, outcomeUnknown: false, bytesTransferred: null });
      return { ok: true, receipt };
    }),
    finishAttempt: (receipt, input) => historyOperation("finishAttempt", [receipt, input], () => {
      const row = historyRows.get(receipt);
      assert.ok(row, "a successful mock finish requires its admitted receipt");
      Object.assign(row, input);
      return { ok: true, updated: true };
    }),
    list: (...args) => historyOperation("list", args, () => {
      const entries = Array.from(historyRows.values(), row => options.planned && row.outcome === "in-progress"
        ? { ...row, outcome: "cancelled-or-unknown", outcomeUnknown: true } : { ...row });
      return { ok: true, entries, plans: Array.from(historyPlans.values(), plan => ({ ...plan,
        rows: plan.rows.map(row => ({ ...row, receipt: entries.find(entry => entry.receiptId === row.rowId) || null })),
      })) };
    }),
    clear: (...args) => historyOperation("clear", args, () => { historyRows.clear(); historyPlans.clear(); return { ok: true }; }),
  };
  // Planned mode is opt-in so every existing legacy test keeps exercising the
  // compatibility API. Only storage replies are synthetic; destination and queue
  // behavior still come from the production modules below.
  if (options.planned) Object.assign(mediaHistoryStore, {
    beginPlannedBatch: filenames => historyOperation("beginPlannedBatch", [filenames], () => {
      const rows = Object.freeze(Array.from(filenames, (filename, order) => Object.freeze({
        rowId: `synthetic-row-${order}`, order, filename,
      })));
      const ticket = Object.freeze({ ...historyTicket, rows });
      historyPlans.set(ticket.batchId, { batchId: ticket.batchId, createdAtMillis: 1700000000000, rows });
      return { ok: true, ticket };
    }),
    beginPlannedAttempt: (ticket, rowId) => historyOperation("beginPlannedAttempt", [ticket, rowId], () => {
      const row = historyPlans.get(ticket.batchId)?.rows.find(entry => entry.rowId === rowId);
      assert.ok(row, "a successful mock admission requires a saved plan row");
      assert.ok(!Array.from(historyRows.values()).some(entry => entry.receiptId === rowId), "one receipt per planned row");
      const receipt = Object.freeze({ receiptId: rowId, batchId: ticket.batchId, epoch: ticket.epoch });
      historyRows.set(receipt, { ...receipt, filename: row.filename, destination: "browser-directory", startedAtMillis: 1700000000000,
        outcome: "in-progress", mayRemain: false, outcomeUnknown: false, bytesTransferred: null });
      return { ok: true, receipt };
    }),
  });
  const historyWarning = element();
  if (options.historyWarningError) Object.defineProperty(historyWarning, "textContent", {
    set() { throw options.historyWarningError; },
  });
  if (options.historyOpen) ui.mediaHistoryDialog.open = true;
  if (options.historyRenderError) ui.mediaHistoryList.replaceChildren = () => {
    calls.historyRenders += 1;
    entered.historyRender.resolve();
    throw options.historyRenderError;
  };
  const root = { kind: "directory", name: "Synthetic chosen folder", async getDirectoryHandle(name, opts) {
    if (!opts.create) throw missing();
    calls.directories += 1;
    const files = new Map();
    return { kind: "directory", name, async getFileHandle(fileName, fileOpts) {
      if (!fileOpts.create) { if (files.has(fileName)) return files.get(fileName); throw missing(); }
      const handle = { kind: "file", name: fileName, async createWritable() {
        const index = calls.raw.length;
        const record = { name: fileName, writes: [], closes: 0, aborts: 0 };
        calls.raw.push(record);
        const raw = {
          async write(chunk) {
            record.writes.push(Array.from(chunk));
            entered.write.resolve();
            if (index === (options.holdIndex || 0) && options.writeGate) await options.writeGate.promise;
            if (index === (options.failIndex || 0) && options.writeError) throw options.writeError;
          },
          async close() {
            record.closes += 1;
            entered.close.resolve();
            if (index === (options.holdIndex || 0) && options.closeGate) await options.closeGate.promise;
            if (index === (options.failIndex || 0) && options.closeError) throw options.closeError;
          },
          async abort() {
            record.aborts += 1;
            entered.abort.resolve();
            if (options.abortGate) await options.abortGate.promise;
            if (options.abortError) throw options.abortError;
          },
        };
        entered.writer.resolve();
        if (index === 0 && options.writerGate) await options.writerGate.promise;
        return raw;
      } };
      files.set(fileName, handle);
      return handle;
    } };
  } };
  const context = vm.createContext({
    state, ui, calls, mediaBatch, mediaTransfer, mediaLibrary, mediaHistoryStore,
    mediaBatchDestination: { createDirectoryBatchDestination(args) {
      const destination = mediaBatchDestination.createDirectoryBatchDestination(args);
      return { ...destination, open(item, index) {
        calls.opens.push({ item, index });
        return destination.open(item, index);
      } };
    } },
    AbortController, crypto: { randomUUID: () => "00000000-0000-4000-8000-000000000001" },
    window: { showDirectoryPicker(opts) {
      calls.pickers.push(opts);
      if (options.pickerError) return Promise.reject(options.pickerError);
      return options.pickerGate ? options.pickerGate.promise : Promise.resolve(root);
    } },
    document: { createElement: () => element(), querySelectorAll(selector) {
      if (selector === "[data-open-media-history]") return [ui.mediaHistoryOpen];
      assert.equal(selector, "[data-media-history-warning]");
      calls.historyWarnings += 1;
      return [historyWarning];
    } },
    FEATURES: { MEDIA_DOWNLOAD: "download" }, featureSupported: () => true,
    resolvedLanguage: () => "en", formatBytes: value => `${value} B`,
    t: (key, values = {}) => `${key}:${JSON.stringify(values)}`,
    api: async (url, request) => {
      calls.requests.push({ url, request });
      entered.api.resolve();
      if (options.apiGate) await options.apiGate.promise;
      if (options.apiError) throw options.apiError;
      return new Response(new Uint8Array([1, 2, 3]), { headers: { "content-length": "3" } });
    },
    saveMediaBlob: () => { calls.anchor += 1; },
    captureError: error => ({ message: error.message }),
    scheduleMediaTransferRender: () => calls.renders.push("scheduled"),
    clearScheduledMediaTransferRender: () => calls.renders.push("clear-timer"),
    renderMedia: () => { state.mediaSelection.replaceLoaded(state.media); calls.renders.push("media"); },
    renderAvailability: () => calls.renders.push("availability"),
    renderMediaPreviewNavigation: () => calls.renders.push("preview"),
    setOperationState: (...args) => calls.feedback.push(["operation", ...args]),
    showToast: (...args) => calls.feedback.push(["toast", ...args]),
  });
  for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaUpload",
    "clearMediaThumbnails", "cancelLatestMediaRefresh", "closeMediaPreview", "closeMediaDetails",
    "releaseObjectUrl", "clearBulbTimer", "renderHealth"]) context[name] = () => {};
  vm.runInContext("let mediaHistoryWarning = false; let mediaHistoryViewGeneration = 0; let mediaHistoryClearOwner = null;\n" +
    ["mediaHistoryCall", "refreshMediaHistory", "openMediaHistory", "clearMediaHistory", "mediaTransferActive", "cameraInteractionBusy", "displayedMedia", "renderMediaSelection",
    "renderMediaBatch", "ownsMediaBatch", "assertMediaBatchCurrent", "transferMediaBatchItem",
    "downloadSelectedMedia", "cancelMediaBatch", "cancelMediaDownload", "resetSession"].map(extract).join("\n") + "\n" + extractHistoryEvents(), context);
  context.renderMediaTransfer = () => context.renderMediaBatch();
  return { ...context, context, state, ui, calls, entered, root, items, historyRows, historyPlans, historyTicket, historyPlan, historyWarning };
}
const statuses = subject => Array.from(subject.state.mediaBatch.queue.results, entry => entry.status);
const historyCalls = (subject, operation) => subject.calls.history.filter(call => call.operation === operation);
const plannedRows = subject => Array.from(subject.historyPlans.values()).flatMap(plan => plan.rows);
function assertNoOriginalIO(subject) {
  assert.equal(subject.calls.opens.length, 0, "destination.open has not been called");
  assert.equal(subject.calls.directories, 0);
  assert.equal(subject.calls.raw.length, 0);
  assert.equal(subject.calls.requests.length, 0);
  assert.equal(subject.calls.anchor, 0);
}

test("planned batch confirms the entire ordered roster and first row before destination.open or GET", async () => {
  const planGate = deferred(), rowGate = deferred();
  const c = fixture({ planned: true, count: 3, history: {
    beginPlannedBatch: { gate: planGate }, beginPlannedAttempt: [{ gate: rowGate }],
  } });
  const pending = c.downloadSelectedMedia();
  assert.equal(c.calls.pickers.length, 1, "picker runs directly in the initiating task");
  await c.entered.beginPlannedBatch.promise;
  assert.equal(c.cameraInteractionBusy(), true);
  assert.deepEqual(Array.from(historyCalls(c, "beginPlannedBatch")[0].args[0]), c.items.map(item => item.name));
  assert.equal(c.historyPlans.size, 0);
  assert.equal(historyCalls(c, "beginPlannedAttempt").length, 0);
  assertNoOriginalIO(c);
  planGate.resolve();
  await c.entered.beginPlannedAttempt.promise;
  const ticket = historyCalls(c, "beginPlannedBatch")[0].result.ticket;
  assert.deepEqual(plannedRows(c).map(({ order, filename }) => ({ order, filename })),
    c.items.map((item, order) => ({ order, filename: item.name })));
  assert.equal(new Set(ticket.rows.map(row => row.rowId)).size, 3);
  assert.equal(c.historyRows.size, 0, "the entire roster exists while first attempt admission is still pending");
  assertNoOriginalIO(c);
  rowGate.resolve();
  await pending;
  assert.deepEqual(statuses(c), ["completed", "completed", "completed"]);
  assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
  const starts = historyCalls(c, "beginPlannedAttempt"), finishes = historyCalls(c, "finishAttempt");
  assert.equal(starts.length, 3);
  assert.equal(finishes.length, 3);
  for (let index = 0; index < starts.length; index += 1) {
    assert.deepEqual(starts[index].args, [ticket, ticket.rows[index].rowId]);
    assert.equal(finishes[index].args[0], starts[index].result.receipt);
    assert.equal(finishes[index].args[1].outcome, "completed");
  }
  assert.equal(historyCalls(c, "createBatchTicket").length, 0);
  assert.equal(historyCalls(c, "startAttempt").length, 0);
  assert.equal(c.calls.opens.length, 3);
  assert.equal(c.calls.requests.length, 3);
  assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
});

test("planned roster follows selected exact-ID order even when the current listing omits an item", async () => {
  const c = fixture({ planned: true, count: 3 });
  c.state.mediaSelection.clear();
  const selected = [c.items[2], c.items[0], c.items[1]];
  c.state.mediaSelection.setMany(selected.map(item => item.id), true);
  c.state.media = [c.items[0]];
  c.state.mediaSelection.replaceLoaded(c.state.media);
  await c.downloadSelectedMedia();
  assert.deepEqual(Array.from(historyCalls(c, "beginPlannedBatch")[0].args[0]), selected.map(item => item.name));
  assert.deepEqual(c.calls.requests.map(call => decodeURIComponent(call.url.split("/media/")[1])), selected.map(item => item.id));
  assert.deepEqual(plannedRows(c).map(row => row.filename), selected.map(item => item.name));
  assert.deepEqual(statuses(c), ["completed", "completed", "completed"]);
});

test("only an explicitly absent initial plan allows manual unrecorded saving with a warning", async () => {
  const c = fixture({ planned: true, history: { beginPlannedBatch: {
    result: { ok: false, code: "unavailable", planAbsent: true },
  } } });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["completed", "completed"]);
  assert.equal(c.calls.requests.length, 2);
  assert.equal(c.calls.opens.length, 2);
  assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
  assert.equal(c.historyPlans.size, 0);
  assert.equal(c.historyRows.size, 0);
  assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
  for (const operation of ["beginPlannedAttempt", "createBatchTicket", "startAttempt", "finishAttempt"]) {
    assert.equal(historyCalls(c, operation).length, 0, operation);
  }
  assert.equal(c.calls.historyWarnings, 1);
  assert.equal(c.historyWarning.hidden, false);
  assert.match(c.historyWarning.textContent, /mediaHistoryWarning/);
  assert.equal(c.calls.anchor, 0);
});

test("unconfirmed initial plan results and exceptions stop the entire batch before destination or camera I/O", async () => {
  for (const failure of [
    { result: { ok: false, code: "transaction-unconfirmed", planAbsent: false } },
    { result: { ok: false, code: "transaction-failed" } },
    { result: { ok: false, code: "unavailable", planAbsent: "true" } },
    { result: undefined }, { result: false }, { error: new Error("Synthetic plan admission exception") },
  ]) {
    const c = fixture({ planned: true, count: 3, history: { beginPlannedBatch: failure } });
    await c.downloadSelectedMedia();
    assertNoOriginalIO(c);
    assert.deepEqual(statuses(c), ["not-started", "not-started", "not-started"]);
    assert.match(c.state.mediaBatch.errorMessage, /mediaWorklistAdmissionUnknown/);
    assert.equal(c.state.mediaBatchOwner, null);
    assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
    for (const operation of ["beginPlannedAttempt", "createBatchTicket", "startAttempt", "finishAttempt"]) {
      assert.equal(historyCalls(c, operation).length, 0, operation);
    }
  }
});

test("failed row admission preserves the completed receipt and every remaining queued row without I/O or retry", async () => {
  for (const failure of [
    ...["transaction-failed", "transaction-unconfirmed", "missing-plan-row", "missing-receipt", "invalid-ticket", "already-started"]
      .map(code => ({ result: { ok: false, code } })),
    { result: undefined }, { error: new Error("Synthetic row admission exception") },
  ]) {
    const c = fixture({ planned: true, count: 3, history: { beginPlannedAttempt: [{}, failure] } });
    await c.downloadSelectedMedia();
    assert.deepEqual(statuses(c), ["completed", "not-started", "not-started"]);
    assert.equal(c.state.mediaBatch.queue.stopReason, "not-started");
    assert.match(c.state.mediaBatch.queue.results[1].error.message, /mediaWorklistAdmissionFailed/);
    assert.equal(c.calls.requests.length, 1);
    assert.equal(c.calls.opens.length, 1);
    assert.equal(c.calls.raw.length, 1);
    assert.equal(c.calls.raw[0].closes, 1);
    assert.equal(c.calls.raw[0].aborts, 0);
    assert.equal(plannedRows(c).length, 3);
    assert.equal(c.historyRows.size, 1);
    assert.equal(Array.from(c.historyRows.values())[0].outcome, "completed");
    assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
    assert.equal(historyCalls(c, "beginPlannedAttempt").length, 2);
    assert.equal(historyCalls(c, "finishAttempt").length, 1);
    assert.equal(historyCalls(c, "startAttempt").length, 0);
    c.ui.mediaHistoryDialog.open = true;
    await c.refreshMediaHistory();
    assert.deepEqual(c.ui.mediaHistoryList.children.map(row => row.dataset.outcome), ["completed", "queued", "queued"]);
  }
});

test("a missing planned-attempt API fails closed before the first open without falling back to legacy recording", async () => {
  const c = fixture({ planned: true, count: 3 });
  delete c.mediaHistoryStore.beginPlannedAttempt;
  await c.downloadSelectedMedia();
  assertNoOriginalIO(c);
  assert.deepEqual(statuses(c), ["not-started", "not-started", "not-started"]);
  assert.equal(c.state.mediaBatch.queue.stopReason, "not-started");
  assert.equal(plannedRows(c).length, 3);
  assert.equal(c.historyRows.size, 0);
  assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
  assert.equal(historyCalls(c, "createBatchTicket").length, 0);
  assert.equal(historyCalls(c, "startAttempt").length, 0);
  assert.equal(historyCalls(c, "finishAttempt").length, 0);
  assert.equal(c.calls.historyWarnings, 1);
});

test("a planned finish failure cannot undo successful closes or hide any selected row", async () => {
  for (const failure of [{ result: { ok: false, code: "transaction-failed" } }, { error: new Error("Synthetic finish exception") }]) {
    const c = fixture({ planned: true, count: 3, history: { finishAttempt: failure } });
    await c.downloadSelectedMedia();
    assert.deepEqual(statuses(c), ["completed", "completed", "completed"]);
    assert.equal(c.calls.requests.length, 3);
    assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
    assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
    assert.equal(historyCalls(c, "beginPlannedAttempt").length, 3);
    assert.equal(historyCalls(c, "finishAttempt").length, 3);
    assert.equal(c.calls.historyWarnings, 3);
    assert.equal(plannedRows(c).length, 3);
    assert.equal(c.historyRows.size, 3);
    c.ui.mediaHistoryDialog.open = true;
    await c.refreshMediaHistory();
    assert.equal(c.ui.mediaHistoryList.children.length, 3);
    assert.ok(c.ui.mediaHistoryList.children.every(row => row.dataset.outcome === "cancelled-or-unknown"));
  }
});

for (const operation of ["beginPlannedBatch", "beginPlannedAttempt"]) {
  for (const interruption of ["cancel", "disconnect", "replace-owner", "replace-session"]) {
    test(`${interruption} while ${operation} waits preserves the roster and prevents late file/network I/O`, async () => {
      const gate = deferred(), c = fixture({ planned: true, count: 3, history: { [operation]: { gate } } });
      const pending = c.downloadSelectedMedia();
      await c.entered[operation].promise;
      const owner = c.state.mediaBatchOwner;
      const replacement = { session: interruption === "replace-session" ? { id: "synthetic-session" } : c.state.session,
        controller: new AbortController() };
      const replacementBatch = { newBatch: true };
      if (interruption === "cancel") c.cancelMediaBatch();
      else if (interruption === "disconnect") c.resetSession();
      else {
        c.state.session = replacement.session;
        c.state.mediaBatchOwner = replacement;
        c.state.mediaBatch = replacementBatch;
      }
      const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
      if (interruption === "cancel") assert.equal(c.cameraInteractionBusy(), true);
      assertNoOriginalIO(c);
      gate.resolve();
      await pending;
      assertNoOriginalIO(c);
      assert.equal(plannedRows(c).length, 3);
      assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
      assert.equal(historyCalls(c, "beginPlannedAttempt").length, operation === "beginPlannedBatch" ? 0 : 1);
      assert.equal(historyCalls(c, "finishAttempt").length, operation === "beginPlannedBatch" ? 0 : 1);
      if (operation === "beginPlannedAttempt") {
        assert.deepEqual({ ...historyCalls(c, "finishAttempt")[0].args[1] }, {
          outcome: "cancelled-or-unknown", mayRemain: false, outcomeUnknown: false,
        });
      }
      if (interruption.startsWith("replace")) {
        assert.equal(c.state.mediaBatchOwner, replacement);
        assert.equal(c.state.mediaBatch, replacementBatch);
        assert.equal(c.calls.renders.length, renders);
        assert.equal(c.calls.feedback.length, feedback);
      } else if (interruption === "disconnect") {
        assert.equal(c.state.mediaBatchOwner, null);
        assert.equal(c.state.mediaBatch, null);
        assert.equal(c.calls.renders.length, renders);
        assert.equal(c.calls.feedback.length, feedback);
      } else {
        assert.notEqual(c.state.mediaBatchOwner, owner);
        assert.equal(c.cameraInteractionBusy(), false);
        assert.deepEqual(statuses(c), [operation === "beginPlannedBatch" ? "not-started" : "cancelled-or-unknown", "not-started", "not-started"]);
      }
    });
  }
}

test("clear during a planned close retires the complete roster and later saves never recreate it", async () => {
  const closeGate = deferred(), c = fixture({ planned: true, count: 3, closeGate, historyOpen: true });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  assert.equal(plannedRows(c).length, 3);
  assert.equal(c.historyRows.size, 1);
  c.historyPlan.finishAttempt = { result: { ok: false, code: "stale-ticket" } };
  c.historyPlan.beginPlannedAttempt = { result: { ok: false, code: "stale-ticket" } };
  await c.clearMediaHistory();
  assert.equal(c.historyPlans.size, 0);
  assert.equal(c.historyRows.size, 0);
  closeGate.resolve();
  await pending;
  await c.refreshMediaHistory();
  assert.deepEqual(statuses(c), ["completed", "completed", "completed"]);
  assert.equal(c.calls.requests.length, 3);
  assert.equal(c.calls.opens.length, 3);
  assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
  assert.equal(historyCalls(c, "beginPlannedBatch").length, 1);
  assert.equal(historyCalls(c, "beginPlannedAttempt").length, 2, "the stale second row clears the ticket before the third save");
  assert.equal(historyCalls(c, "finishAttempt").length, 1);
  assert.equal(historyCalls(c, "createBatchTicket").length, 0);
  assert.equal(historyCalls(c, "startAttempt").length, 0);
  assert.equal(c.historyPlans.size, 0);
  assert.equal(c.historyRows.size, 0);
  assert.equal(c.calls.historyWarnings, 0);
  assert.equal(c.ui.mediaHistoryList.children.length, 0);
});

test("complete history renders duplicate labels as distinct ordered rows without duplicating their receipts", async () => {
  const c = fixture({ planned: true, count: 3, failIndex: 1, writeError: new Error("Synthetic disk full") });
  c.items[0].name = c.items[2].name = "<img src=x> $& {filename}.JPG";
  await c.downloadSelectedMedia();
  c.ui.mediaHistoryDialog.open = true;
  await c.refreshMediaHistory();
  const ticket = historyCalls(c, "beginPlannedBatch")[0].result.ticket;
  assert.notEqual(ticket.rows[0].rowId, ticket.rows[2].rowId);
  const rows = c.ui.mediaHistoryList.children;
  assert.equal(rows.length, 3, "two receipts already represented by planned rows do not appear again");
  assert.deepEqual(rows.map(row => row.children[0].textContent), c.items.map(item => item.name));
  assert.deepEqual(rows.map(row => row.dataset.outcome), ["completed", "failed", "queued"]);
  assert.ok(rows.every(row => row.dataset.worklist === ticket.batchId));
  rows.forEach((row, index) => {
    assert.match(row.children[1].textContent, new RegExp(`"position":${index + 1},"total":3`));
    assert.ok(row.children.every(child => child.innerHTML === undefined));
  });
  assert.match(rows[2].children[2].textContent, /mediaHistoryQueued/);
  assert.match(c.ui.mediaHistoryStatus.textContent, /"count":3/);
  assert.equal(historyCalls(c, "beginPlannedAttempt").length, 2);
  assert.equal(c.historyRows.size, 2);
});

test("planned history deduplicates by row identity while retaining a same-label legacy receipt", async () => {
  const c = fixture({ planned: true, count: 1 });
  await c.downloadSelectedMedia();
  const legacyReceipt = { receiptId: "synthetic-legacy-receipt", batchId: "synthetic-legacy-batch", epoch: 0 };
  c.historyRows.set(legacyReceipt, { ...legacyReceipt, filename: c.items[0].name, startedAtMillis: 1700000000000,
    outcome: "failed", mayRemain: false, outcomeUnknown: false });
  c.ui.mediaHistoryDialog.open = true;
  await c.refreshMediaHistory();
  const rows = c.ui.mediaHistoryList.children;
  assert.equal(rows.length, 2);
  assert.deepEqual(rows.map(row => row.children[0].textContent), [c.items[0].name, c.items[0].name]);
  assert.deepEqual(rows.map(row => row.dataset.outcome), ["completed", "failed"]);
  assert.equal(rows[0].dataset.worklist, historyCalls(c, "beginPlannedBatch")[0].result.ticket.batchId);
  assert.equal(rows[1].dataset.worklist, undefined);
  assert.match(rows[1].children[1].textContent, /mediaHistoryLegacyAttempt/);
  assert.match(c.ui.mediaHistoryStatus.textContent, /"count":2/);
});

test("history keeps untouched queued rows distinct from an admitted attempt whose close is still unknown", async () => {
  const closeGate = deferred(), c = fixture({ planned: true, count: 3, closeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  c.ui.mediaHistoryDialog.open = true;
  await c.refreshMediaHistory();
  const rows = c.ui.mediaHistoryList.children;
  assert.equal(rows.length, 3);
  assert.deepEqual(rows.map(row => row.dataset.outcome), ["cancelled-or-unknown", "queued", "queued"]);
  assert.match(rows[0].children[2].textContent, /mediaHistoryUnconfirmed/);
  assert.match(rows[0].children[4].textContent, /mediaHistoryLeftover/);
  assert.ok(rows.slice(1).every(row => row.children.length === 4 && /mediaHistoryQueued/.test(row.children[2].textContent)));
  c.cancelMediaBatch();
  closeGate.resolve();
  await pending;
  await c.refreshMediaHistory();
  assert.deepEqual(c.ui.mediaHistoryList.children.map(row => row.dataset.outcome), ["completed", "queued", "queued"]);
  assert.deepEqual(statuses(c), ["completed", "not-started", "not-started"]);
  assert.equal(c.calls.requests.length, 1);
});

test("batch uses one user picker and sequential exact-ID GETs with close receipts", async () => {
  const c = fixture();
  await c.downloadSelectedMedia();
  assert.equal(c.calls.pickers.length, 1);
  assert.equal(c.calls.pickers[0].mode, "readwrite");
  assert.equal(c.calls.directories, 1);
  assert.deepEqual(c.calls.requests.map(call => decodeURIComponent(call.url.split("/media/")[1])), c.items.slice(0, 2).map(item => item.id));
  assert.deepEqual(statuses(c), ["completed", "completed"]);
  assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
  assert.equal(c.calls.anchor, 0);
  assert.equal(c.state.mediaBatchOwner, null);
  assert.match(c.state.mediaBatch.destinationName, /Synthetic chosen folder \/ Open-EOS-/);
});

test("unsupported and denied folder access request zero originals and have no fallback", async () => {
  for (const unsupported of [true, false]) {
    const c = fixture({ pickerError: Object.assign(new Error("Denied"), { name: "NotAllowedError" }) });
    if (unsupported) delete c.window.showDirectoryPicker;
    await c.downloadSelectedMedia();
    assert.equal(c.calls.requests.length, 0);
    assert.equal(c.calls.directories, 0);
    assert.equal(c.calls.anchor, 0);
    assert.equal(c.state.mediaBatchOwner, null);
    if (!unsupported) assert.deepEqual(statuses(c), ["not-started", "not-started"]);
  }
});

test("dismissed first picker and missing UUID generation fail before any destination or GET", async () => {
  for (const missingCrypto of [false, true]) {
    const c = fixture(missingCrypto ? {} : { pickerError: Object.assign(new Error("Dismissed"), { name: "AbortError" }) });
    if (missingCrypto) c.context.crypto = {};
    await c.downloadSelectedMedia();
    assert.equal(c.calls.requests.length, 0);
    assert.equal(c.calls.directories, 0);
    assert.equal(c.calls.anchor, 0);
    assert.deepEqual(statuses(c), ["not-started", "not-started"]);
    assert.equal(c.state.mediaBatchOwner, null);
  }
});

test("cancel during picker stays busy until settlement and never creates a destination", async () => {
  const pickerGate = deferred(), c = fixture({ pickerGate });
  const pending = c.downloadSelectedMedia();
  assert.equal(c.cameraInteractionBusy(), true);
  c.cancelMediaDownload();
  assert.equal(c.cameraInteractionBusy(), true);
  assert.deepEqual(statuses(c), ["not-started", "not-started"]);
  pickerGate.resolve(c.root);
  await pending;
  assert.equal(c.cameraInteractionBusy(), false);
  assert.equal(c.calls.requests.length, 0);
  assert.equal(c.calls.directories, 0);
});

test("late picker with reused session ID cannot mutate or clear a new owner", async () => {
  const pickerGate = deferred(), c = fixture({ pickerGate });
  const pending = c.downloadSelectedMedia();
  c.state.session = { id: "synthetic-session" };
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  pickerGate.resolve(c.root);
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 0);
  assert.equal(c.calls.directories, 0);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
});

test("late writable is aborted before the first GET after reset", async () => {
  const writerGate = deferred(), c = fixture({ writerGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.writer.promise;
  c.resetSession();
  writerGate.resolve();
  await pending;
  assert.equal(c.calls.requests.length, 0);
  assert.equal(c.calls.raw[0].aborts, 1);
  assert.equal(c.state.mediaBatch, null);
  assert.equal(c.state.mediaBatchOwner, null);
  assert.equal(c.state.mediaSelection.snapshot().length, 0);
  assert.equal(c.state.mediaSelection.setSelected(c.items[0].id, true), false);
});

test("cancel during write waits for write and cleanup, then stops every later GET", async () => {
  const writeGate = deferred(), abortGate = deferred(), c = fixture({ writeGate, abortGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.write.promise;
  c.cancelMediaBatch();
  assert.equal(c.cameraInteractionBusy(), true);
  assert.equal(c.calls.requests.length, 1);
  writeGate.resolve();
  await c.entered.abort.promise;
  assert.equal(c.cameraInteractionBusy(), true);
  assert.equal(c.calls.requests.length, 1);
  abortGate.resolve();
  await pending;
  assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  assert.equal(c.calls.raw[0].closes, 0);
  assert.equal(c.cameraInteractionBusy(), false);
});

test("cancel during close preserves successful completed file and does not start next", async () => {
  const closeGate = deferred(), c = fixture({ closeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  assert.deepEqual(statuses(c), ["running", "pending"]);
  c.cancelMediaBatch();
  assert.equal(c.cameraInteractionBusy(), true);
  closeGate.resolve();
  await pending;
  assert.deepEqual(statuses(c), ["completed", "not-started"]);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].aborts, 0);
  assert.equal(c.state.mediaBatch.queue.results[0].receipt.bytesTransferred, 3);
});

test("second-file write failure preserves completed receipt and stops third", async () => {
  const c = fixture({ count: 3, failIndex: 1, writeError: new Error("Synthetic disk full") });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["completed", "failed", "not-started"]);
  assert.equal(c.calls.requests.length, 2);
  assert.equal(c.calls.raw[1].closes, 0);
  assert.equal(c.calls.raw[1].aborts, 1);
  assert.equal(c.state.mediaBatch.destination.leftoverCount, 1);
});

test("close rejection remains uncertain even when abort succeeds", async () => {
  const c = fixture({ closeError: new Error("Synthetic close result unknown") });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.state.mediaBatch.destination.unknownCount, 1);
});

test("HTTP failure plus failed cleanup reports uncertainty and retains the primary failure", async () => {
  const c = fixture({ apiError: Object.freeze(new Error("Synthetic HTTP failure")), abortError: new Error("Synthetic abort failure") });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.state.mediaBatch.queue.results[0].error.message, "Synthetic HTTP failure");
  assert.equal(c.state.mediaBatch.destination.unknownCount, 1);
});

test("old close/progress/finally cannot overwrite a replacement session or owner", async () => {
  const closeGate = deferred(), c = fixture({ closeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  const retiredBatch = c.state.mediaBatch;
  c.state.session = { id: "synthetic-session" };
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  closeGate.resolve();
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
  assert.equal(retiredBatch.queue.results[0].status, "running");
});

test("replaced batch owner in the same session rejects late API bytes and leaves the new UI alone", async () => {
  const apiGate = deferred(), c = fixture({ apiGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.api.promise;
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  apiGate.resolve();
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].writes.length, 0);
  assert.equal(c.calls.raw[0].closes, 0);
  assert.equal(c.calls.raw[0].aborts, 1);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
});

test("reused session ID during a pending write cannot publish late progress", async () => {
  const writeGate = deferred(), c = fixture({ writeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.write.promise;
  c.state.session = { id: "synthetic-session" };
  const replacement = { session: c.state.session, controller: new AbortController() };
  const replacementBatch = { newBatch: true };
  c.state.mediaBatchOwner = replacement;
  c.state.mediaBatch = replacementBatch;
  const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
  writeGate.resolve();
  await pending;
  assert.equal(c.state.mediaBatchOwner, replacement);
  assert.equal(c.state.mediaBatch, replacementBatch);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].closes, 0);
  assert.equal(c.calls.raw[0].aborts, 1);
  assert.equal(c.calls.renders.length, renders);
  assert.equal(c.calls.feedback.length, feedback);
});

test("selection summary distinguishes hidden loaded from omitted partial-list entries", () => {
  const c = fixture();
  c.state.media = [c.items[0]];
  c.state.mediaSelection.replaceLoaded(c.state.media);
  c.renderMediaSelection([]);
  assert.match(c.ui.mediaSelectionSummary.textContent, /"selected":2,"hidden":1,"notLoaded":1/);
  assert.equal(c.ui.mediaBatchStart.disabled, false);
  delete c.window.showDirectoryPicker;
  c.renderMediaSelection([]);
  assert.equal(c.ui.mediaBatchStart.disabled, true);
  assert.equal(c.ui.mediaBatchUnavailable.hidden, false);
});

test("batch result DOM uses text nodes for untrusted names/errors and never claims running saved", () => {
  const c = fixture();
  c.state.mediaBatch = { queue: { results: [
    { id: "unsafe", item: { name: "<img src=x>" }, status: "running", error: { message: "<script>unsafe</script>" } },
  ] }, destinationName: "<b>folder</b>", destination: null };
  c.renderMediaBatch();
  assert.equal(c.ui.mediaBatchList.children[0].children[0].textContent, "<img src=x>");
  assert.equal(c.ui.mediaBatchList.children[0].children[2].textContent, "<script>unsafe</script>");
  assert.match(c.ui.mediaBatchSummary.textContent, /"completed":0/);
  assert.ok(c.ui.mediaBatchList.children[0].children.every(child => child.innerHTML === undefined));
});

test("destination names remain literal with production translation interpolation", () => {
  const c = fixture();
  c.context.messages = { en: { mediaBatchDestination: "Destination: {name}" } };
  vm.runInContext(extract("t"), c.context);
  const name = "camera $& $` $' {index}";
  c.state.mediaBatch = { queue: { results: [] }, destinationName: name, destination: null };
  c.renderMediaBatch();
  assert.equal(c.ui.mediaBatchDestination.textContent, `Destination: ${name}`);
});

test("production history calls record only attempted items and finish each exact receipt after close", async () => {
  const c = fixture({ count: 3, failIndex: 1, writeError: new Error("Synthetic disk full") });
  await c.downloadSelectedMedia();
  const tickets = historyCalls(c, "createBatchTicket"), starts = historyCalls(c, "startAttempt"), finishes = historyCalls(c, "finishAttempt");
  assert.equal(tickets.length, 1);
  assert.deepEqual(tickets[0].args, []);
  assert.equal(starts.length, 2);
  assert.equal(finishes.length, 2);
  for (let index = 0; index < starts.length; index += 1) {
    assert.equal(starts[index].args[0], c.historyTicket);
    assert.deepEqual({ ...starts[index].args[1] }, { filename: c.items[index].name, destination: "browser-directory" });
    assert.equal(finishes[index].args[0], starts[index].result.receipt);
  }
  assert.deepEqual({ ...finishes[0].args[1] }, { outcome: "completed", mayRemain: false, outcomeUnknown: false, bytesTransferred: 3 });
  assert.deepEqual({ ...finishes[1].args[1] }, { outcome: "failed", mayRemain: true, outcomeUnknown: false });
  assert.deepEqual(Array.from(c.historyRows.values(), row => row.outcome), ["completed", "failed"]);
  assert.deepEqual(statuses(c), ["completed", "failed", "not-started"]);
  assert.equal(c.calls.requests.length, 2);
  assert.equal(c.calls.raw[0].closes, 1);
  assert.equal(c.calls.raw[1].closes, 0);
});

test("history ticket/start/finish failures and exceptions never change successful close or GET counts", async () => {
  for (const operation of ["createBatchTicket", "startAttempt", "finishAttempt"]) {
    for (const failure of [{ result: { ok: false, code: "transaction-failed" } }, { error: new Error("Synthetic history exception") }]) {
      const c = fixture({ history: { [operation]: failure } });
      await c.downloadSelectedMedia();
      assert.deepEqual(statuses(c), ["completed", "completed"], operation);
      assert.equal(c.calls.requests.length, 2, operation);
      assert.equal(c.calls.raw.length, 2, operation);
      assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0), operation);
      assert.equal(c.calls.anchor, 0);
      assert.equal(c.state.mediaBatchOwner, null);
      assert.equal(c.calls.historyWarnings, operation === "createBatchTicket" ? 1 : 2);
      assert.equal(historyCalls(c, "createBatchTicket").length, 1);
      assert.equal(historyCalls(c, "startAttempt").length, operation === "createBatchTicket" ? 0 : 2);
      assert.equal(historyCalls(c, "finishAttempt").length, operation === "finishAttempt" ? 2 : 0);
      assert.equal(c.historyRows.size, operation === "finishAttempt" ? 2 : 0);
    }
  }
});

test("a successful close persists completed after cancellation or disconnect without starting another GET", async () => {
  for (const disconnect of [false, true]) {
    const closeGate = deferred(), c = fixture({ closeGate });
    const pending = c.downloadSelectedMedia();
    await c.entered.close.promise;
    assert.equal(historyCalls(c, "finishAttempt").length, 0, "close has not supplied a receipt yet");
    if (disconnect) c.resetSession();
    else c.cancelMediaBatch();
    closeGate.resolve();
    await pending;
    assert.equal(c.calls.requests.length, 1);
    assert.equal(c.calls.raw[0].closes, 1);
    assert.equal(c.calls.raw[0].aborts, 0);
    assert.equal(historyCalls(c, "startAttempt").length, 1);
    assert.equal(historyCalls(c, "finishAttempt").length, 1);
    const row = Array.from(c.historyRows.values())[0];
    assert.equal(row.outcome, "completed");
    assert.equal(row.bytesTransferred, 3);
    assert.equal(row.mayRemain, false);
    assert.equal(row.outcomeUnknown, false);
    if (disconnect) assert.equal(c.state.mediaBatch, null);
    else assert.deepEqual(statuses(c), ["completed", "not-started"]);
  }
});

test("close rejection and abort failure persist uncertainty and retain the original close failure", async () => {
  const c = fixture({ closeError: Object.freeze(new Error("Synthetic close unconfirmed")), abortError: new Error("Synthetic abort unconfirmed") });
  await c.downloadSelectedMedia();
  assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  assert.equal(c.state.mediaBatch.queue.results[0].error.message, "Synthetic close unconfirmed");
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].closes, 1);
  assert.equal(c.calls.raw[0].aborts, 1);
  const finishes = historyCalls(c, "finishAttempt");
  assert.equal(finishes.length, 1);
  assert.deepEqual({ ...finishes[0].args[1] }, { outcome: "cancelled-or-unknown", mayRemain: true, outcomeUnknown: true });
  assert.equal(Array.from(c.historyRows.values())[0].outcome, "cancelled-or-unknown");
});

test("delayed history ticket rechecks cancellation and replacement sessions before any destination or camera I/O", async () => {
  for (const replaceSession of [false, true]) {
    const gate = deferred(), c = fixture({ history: { createBatchTicket: { gate } } });
    const pending = c.downloadSelectedMedia();
    assert.equal(c.calls.pickers.length, 1, "picker still runs in the initiating click task");
    await c.entered.createBatchTicket.promise;
    assert.equal(c.cameraInteractionBusy(), true);
    const replacement = { session: { id: "synthetic-session" }, controller: new AbortController() };
    const replacementBatch = { newBatch: true };
    if (replaceSession) {
      c.state.session = replacement.session;
      c.state.mediaBatchOwner = replacement;
      c.state.mediaBatch = replacementBatch;
    } else c.cancelMediaBatch();
    const renders = c.calls.renders.length, feedback = c.calls.feedback.length;
    gate.resolve();
    await pending;
    assert.equal(c.calls.directories, 0);
    assert.equal(c.calls.raw.length, 0);
    assert.equal(c.calls.requests.length, 0);
    assert.equal(c.calls.anchor, 0);
    assert.equal(historyCalls(c, "startAttempt").length, 0);
    assert.equal(historyCalls(c, "finishAttempt").length, 0);
    if (replaceSession) {
      assert.equal(c.state.mediaBatchOwner, replacement);
      assert.equal(c.state.mediaBatch, replacementBatch);
      assert.equal(c.calls.renders.length, renders);
      assert.equal(c.calls.feedback.length, feedback);
    } else {
      assert.deepEqual(statuses(c), ["not-started", "not-started"]);
      assert.equal(c.cameraInteractionBusy(), false);
    }
  }
});

test("delayed history admission rechecks cancellation and disconnect before opening a destination", async () => {
  for (const disconnect of [false, true]) {
    const gate = deferred(), c = fixture({ history: { startAttempt: { gate } } });
    const pending = c.downloadSelectedMedia();
    await c.entered.startAttempt.promise;
    if (disconnect) c.resetSession();
    else c.cancelMediaBatch();
    gate.resolve();
    await pending;
    assert.equal(c.calls.directories, 0);
    assert.equal(c.calls.raw.length, 0);
    assert.equal(c.calls.requests.length, 0);
    assert.equal(c.calls.anchor, 0);
    assert.equal(historyCalls(c, "startAttempt").length, 1);
    assert.equal(historyCalls(c, "finishAttempt").length, 1);
    assert.deepEqual({ ...historyCalls(c, "finishAttempt")[0].args[1] }, {
      outcome: "cancelled-or-unknown", mayRemain: false, outcomeUnknown: false,
    });
    if (disconnect) assert.equal(c.state.mediaBatch, null);
    else assert.deepEqual(statuses(c), ["cancelled-or-unknown", "not-started"]);
  }
});

test("clear during close leaves old receipts absent and rejects the old ticket without warning or retry", async () => {
  const closeGate = deferred(), c = fixture({ closeGate, historyOpen: true });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  assert.equal(c.historyRows.size, 1);
  // These are the store's contracted replies after clear advances its epoch.
  c.historyPlan.finishAttempt = { result: { ok: false, code: "stale-ticket" } };
  c.historyPlan.startAttempt = { result: { ok: false, code: "stale-ticket" } };
  await c.clearMediaHistory();
  assert.equal(c.historyRows.size, 0);
  closeGate.resolve();
  await pending;
  assert.deepEqual(statuses(c), ["completed", "completed"]);
  assert.equal(c.calls.requests.length, 2);
  assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
  assert.equal(c.historyRows.size, 0);
  assert.equal(historyCalls(c, "clear").length, 1);
  assert.equal(historyCalls(c, "createBatchTicket").length, 1);
  assert.equal(historyCalls(c, "startAttempt").length, 2);
  assert.equal(historyCalls(c, "finishAttempt").length, 1);
  assert.ok(historyCalls(c, "startAttempt").every(call => call.args[0] === c.historyTicket));
  assert.equal(c.calls.historyWarnings, 0);
  assert.equal(c.ui.mediaHistoryList.children.length, 0);
});

test("an evicted receipt stays absent after missing-receipt without retrying or warning", async () => {
  const closeGate = deferred(), c = fixture({ count: 1, closeGate });
  const pending = c.downloadSelectedMedia();
  await c.entered.close.promise;
  const receipt = historyCalls(c, "startAttempt")[0].result.receipt;
  // A bounded journal can prune an in-flight receipt after activity in another tab.
  c.historyRows.delete(receipt);
  c.historyPlan.finishAttempt = { result: { ok: false, code: "missing-receipt" } };
  closeGate.resolve();
  await pending;
  assert.deepEqual(statuses(c), ["completed"]);
  assert.equal(c.calls.requests.length, 1);
  assert.equal(c.calls.raw[0].closes, 1);
  assert.equal(c.calls.raw[0].aborts, 0);
  assert.equal(c.historyRows.size, 0);
  assert.equal(historyCalls(c, "createBatchTicket").length, 1);
  assert.equal(historyCalls(c, "startAttempt").length, 1);
  assert.equal(historyCalls(c, "finishAttempt").length, 1);
  assert.equal(historyCalls(c, "finishAttempt")[0].args[0], receipt);
  assert.equal(c.calls.historyWarnings, 0);
});

test("throwing history warning and refresh rendering cannot turn successful writes into failed transfers", async () => {
  for (const renderError of [false, true]) {
    const c = fixture(renderError
      ? { historyOpen: true, historyRenderError: new Error("Synthetic history renderer failure") }
      : { history: { finishAttempt: { result: { ok: false, code: "transaction-failed" } } },
        historyWarningError: new Error("Synthetic history warning failure") });
    await c.downloadSelectedMedia();
    if (renderError) await c.entered.historyRender.promise;
    assert.deepEqual(statuses(c), ["completed", "completed"]);
    assert.equal(c.calls.requests.length, 2);
    assert.ok(c.calls.raw.every(file => file.closes === 1 && file.aborts === 0));
    assert.equal(c.calls.anchor, 0);
    assert.equal(historyCalls(c, "finishAttempt").length, 2);
    if (renderError) {
      assert.ok(c.calls.historyRenders > 0);
      assert.deepEqual(Array.from(c.historyRows.values(), row => row.outcome), ["completed", "completed"]);
    } else assert.equal(c.calls.historyWarnings, 2);
  }
});

test("history renders untrusted display names literally using production refresh", async () => {
  const c = fixture({ count: 1 });
  c.items[0].name = "<img src=x onerror=alert(1)> $& {name}.JPG";
  await c.downloadSelectedMedia();
  c.ui.mediaHistoryDialog.open = true;
  await c.refreshMediaHistory();
  const row = c.ui.mediaHistoryList.children[0];
  assert.equal(row.children[0].textContent, c.items[0].name);
  assert.ok(row.children.every(child => child.innerHTML === undefined));
  assert.equal(row.dataset.outcome, "completed");
  assert.deepEqual(statuses(c), ["completed"]);
});

test("pending history clear survives transfer refresh, prevents a duplicate clear, and focuses Close after success", async () => {
  const closeGate = deferred(), clearGate = deferred();
  const c = fixture({ count: 1, closeGate, historyOpen: true, history: { clear: { gate: clearGate } } });
  const transfer = c.downloadSelectedMedia();
  await c.entered.close.promise;
  c.ui.mediaHistoryClear.dispatchEvent({ type: "click" });
  const clearing = c.clearMediaHistory();
  await c.entered.clear.promise;
  assert.equal(c.ui.mediaHistoryConfirm.hidden, false);
  assert.equal(c.ui.mediaHistoryConfirmYes.disabled, true);
  assert.equal(c.ui.mediaHistoryClear.disabled, true);
  closeGate.resolve();
  await transfer;
  await c.refreshMediaHistory();
  assert.equal(c.ui.mediaHistoryList.children.length, 1);
  assert.equal(c.ui.mediaHistoryConfirmYes.disabled, true);
  assert.equal(c.ui.mediaHistoryClear.disabled, true, "refresh cannot enable Clear while its operation is pending");
  await c.clearMediaHistory();
  assert.equal(historyCalls(c, "clear").length, 1);
  clearGate.resolve();
  await clearing;
  assert.equal(c.historyRows.size, 0);
  assert.equal(c.ui.mediaHistoryList.children.length, 0);
  assert.equal(c.ui.mediaHistoryConfirmYes.disabled, false, "an overlapping refresh cannot strand confirmation");
  assert.equal(c.ui.mediaHistoryConfirm.hidden, true);
  assert.equal(c.ui.mediaHistoryClear.disabled, true, "empty history has nothing to clear");
  assert.equal(c.ui.mediaHistoryClose.disabled, false);
  assert.equal(c.ui.mediaHistoryClose.focuses, 1);
  assert.deepEqual(statuses(c), ["completed"]);
  assert.equal(c.calls.requests.length, 1);
});

test("overlapping history refresh cannot strand confirmation when clear fails or throws", async () => {
  for (const failure of [{ result: { ok: false, code: "transaction-failed" } }, { error: new Error("Synthetic clear failure") }]) {
    const clearGate = deferred(), c = fixture({ count: 1, historyOpen: true, history: { clear: { gate: clearGate, ...failure } } });
    await c.downloadSelectedMedia();
    c.ui.mediaHistoryClear.dispatchEvent({ type: "click" });
    const clearing = c.clearMediaHistory();
    await c.entered.clear.promise;
    await c.refreshMediaHistory();
    assert.equal(c.ui.mediaHistoryClear.disabled, true);
    clearGate.resolve();
    await clearing;
    assert.equal(c.ui.mediaHistoryConfirmYes.disabled, false);
    assert.equal(c.ui.mediaHistoryClear.disabled, false);
    assert.equal(c.ui.mediaHistoryConfirm.hidden, false);
    assert.match(c.ui.mediaHistoryStatus.textContent, /mediaHistoryClearFailed/);
    assert.equal(c.ui.mediaHistoryClose.focuses, 0);
    assert.equal(c.historyRows.size, 1);
    assert.equal(historyCalls(c, "clear").length, 1);
  }
});

test("closing and reopening history retires an old clear completion without changing the new confirmation", async () => {
  for (const startNewClear of [false, true]) {
    const oldGate = deferred(), newGate = deferred();
    const c = fixture({ count: 1, historyOpen: true, history: { clear: { gate: oldGate } } });
    await c.downloadSelectedMedia();
    c.ui.mediaHistoryClear.dispatchEvent({ type: "click" });
    const oldClear = c.clearMediaHistory();
    await c.entered.clear.promise;
    c.ui.mediaHistoryClose.dispatchEvent({ type: "click" });
    assert.equal(c.ui.mediaHistoryDialog.open, false);
    c.ui.mediaHistoryOpen.dispatchEvent({ type: "click" });
    await c.refreshMediaHistory();
    assert.equal(c.ui.mediaHistoryDialog.open, true);
    assert.equal(c.ui.mediaHistoryConfirm.hidden, true);
    assert.equal(c.ui.mediaHistoryConfirmYes.disabled, false);
    assert.equal(c.ui.mediaHistoryClear.disabled, false);
    c.ui.mediaHistoryClear.dispatchEvent({ type: "click" });
    c.historyPlan.clear = { gate: newGate };
    const newClear = startNewClear ? c.clearMediaHistory() : null;
    const before = {
      status: c.ui.mediaHistoryStatus.textContent,
      confirmDisabled: c.ui.mediaHistoryConfirmYes.disabled,
      clearDisabled: c.ui.mediaHistoryClear.disabled,
      focused: c.ui.mediaHistoryClose.focuses,
    };
    oldGate.resolve();
    await oldClear;
    assert.equal(c.ui.mediaHistoryConfirm.hidden, false);
    assert.equal(c.ui.mediaHistoryConfirmYes.disabled, before.confirmDisabled);
    assert.equal(c.ui.mediaHistoryClear.disabled, before.clearDisabled);
    assert.equal(c.ui.mediaHistoryStatus.textContent, before.status);
    assert.equal(c.ui.mediaHistoryClose.focuses, before.focused);
    assert.equal(historyCalls(c, "clear").length, startNewClear ? 2 : 1);
    if (newClear) {
      newGate.resolve();
      await newClear;
      assert.equal(c.ui.mediaHistoryConfirmYes.disabled, false);
      assert.equal(c.ui.mediaHistoryConfirm.hidden, true);
      assert.equal(c.ui.mediaHistoryClose.focuses, 1);
    }
  }
});

test("a completed clear awaiting list refresh cannot steal focus after history closes and reopens", async () => {
  const c = fixture({ count: 1, historyOpen: true });
  await c.downloadSelectedMedia();
  await c.refreshMediaHistory();
  const listGate = deferred(), listEntered = deferred();
  c.historyPlan.list = { gate: listGate, entered: listEntered };
  c.ui.mediaHistoryClear.dispatchEvent({ type: "click" });
  const clearing = c.clearMediaHistory();
  await listEntered.promise;
  assert.equal(c.ui.mediaHistoryConfirm.hidden, true);
  assert.equal(c.ui.mediaHistoryConfirmYes.disabled, false);
  assert.equal(c.ui.mediaHistoryClose.focuses, 1, "focus moves off the hidden confirmation before awaiting rendering");
  c.ui.mediaHistoryClose.dispatchEvent({ type: "click" });
  c.historyPlan.list = {};
  c.ui.mediaHistoryOpen.dispatchEvent({ type: "click" });
  await c.refreshMediaHistory();
  const status = c.ui.mediaHistoryStatus.textContent;
  const focuses = c.ui.mediaHistoryClose.focuses;
  listGate.resolve();
  await clearing;
  assert.equal(c.ui.mediaHistoryDialog.open, true);
  assert.equal(c.ui.mediaHistoryClose.focuses, focuses, "the old clear must not focus the reopened dialog");
  assert.equal(c.ui.mediaHistoryStatus.textContent, status);
  assert.equal(c.ui.mediaHistoryConfirm.hidden, true);
  assert.equal(c.ui.mediaHistoryConfirmYes.disabled, false);
  assert.equal(c.ui.mediaHistoryList.children.length, 0);
  assert.equal(historyCalls(c, "clear").length, 1);
});
