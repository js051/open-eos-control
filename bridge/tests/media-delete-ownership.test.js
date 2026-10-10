"use strict";

// Run the production connection lifecycle, availability, delete and error handlers.
// Deferred synthetic DELETE responses control ordering; unrelated renderers and
// transport teardown are stubbed. No camera, browser or elapsed-time waits are used.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, `Missing ${name}`);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  assert.notEqual(end, -1, `Missing end of ${name}`);
  return source.slice(start, start + 1 + end);
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function element() {
  return {
    value: "", hidden: false, disabled: false, open: false, textContent: "", dataset: {},
    classList: { toggle() {}, remove() {} }, style: { removeProperty() {} },
    append(child) { child.parentElement = this; }, setAttribute() {}, removeAttribute() {},
    querySelectorAll() { return []; }, pause() {}, load() {}, close() { this.open = false; },
  };
}

function context({ reuseSessionId = false, confirmed = true } = {}) {
  const parent = element();
  const ui = new Proxy({}, { get(target, key) {
    if (!target[key]) target[key] = { ...element(), parentElement: parent };
    return target[key];
  }});
  ui.cameraSelect.value = "synthetic-camera";
  ui.connectionView.hidden = false;
  ui.controlView.hidden = true;
  const state = {
    connectionMode: "usb", cameras: [{ id: "synthetic-camera", engine: "simulator" }],
    session: null, info: null, status: null, capabilities: null, busy: false, lastError: null,
    operatorConfirmedFeatures: new Set(), refreshGeneration: 0, captureMode: "photo",
    localVideoSupport: { available: false }, localVideoBusy: false, liveActive: false,
    previewInput: "CAMERA", mediaDownloadPreparing: false, mediaDownload: null, mediaUpload: null,
    media: [], mediaThumbnailUrls: new Map(), mediaThumbnailLoads: new Map(),
    mediaThumbnailFailures: new Set(), mediaGeneration: 0, mediaPreviewGeneration: 0,
    mediaDetailsGeneration: 0, mediaPreviewItem: null, mediaDetailsItem: null,
  };
  const capabilities = { supported: ["MEDIA_BROWSER", "MEDIA_DELETE"], liveView: {} };
  const heldDelete = deferred();
  const requests = [], confirmations = [], feedback = [], released = [], renders = [];
  let connections = 0;
  const sandbox = vm.createContext({
    state, ui, heldDelete, requests, confirmations, feedback, released, renders,
    FEATURES: new Proxy({}, { get: (_, key) => key }), Error, ApiError: class ApiError extends Error {},
    mediaThumbnailObserver: null,
    window: { confirm(message) { confirmations.push(message); return confirmed; } },
    document: { querySelector: () => element(), querySelectorAll: () => [] },
    api: async (url, options = {}) => {
      requests.push({ url, method: options.method || "GET" });
      if (url === "/v1/session" && options.method === "POST") {
        connections += 1;
        return { id: connections === 1 || reuseSessionId ? "synthetic-A" : "synthetic-B" };
      }
      if (options.method === "DELETE" && url.includes("/media/")) return heldDelete.promise;
      if (options.method === "DELETE" && /^\/v1\/session\/[^/]+$/.test(url)) return {};
      if (url.endsWith("/info")) return { model: "synthetic-model" };
      if (url.endsWith("/status")) return { recording: false };
      if (url.endsWith("/capabilities")) return capabilities;
      throw new Error(`Unexpected fake API ${url}`);
    },
    t: (key, values) => values?.name ? `${key}: ${values.name}` : key,
    clampFps: value => value, captureModeFromCamera: () => "photo", bulbControlLocked: () => false,
    isBulbMode: () => false, temperatureAllows: () => true, shutterAFSupported: () => false,
    canChangeShutterAF: () => false, localPreviewSelected: () => false,
    shutterReleaseUnconfirmed: () => false, effectiveTapAction: () => null,
    releaseObjectUrl: url => { if (url) released.push(url); },
    renderMedia: () => renders.push("media"), renderDiagnostics: () => renders.push("diagnostics"),
  });
  for (const name of ["clearConnectionError", "syncLiveMagnificationFromCapabilities", "startEventLoop",
    "refreshLatestMedia", "renderHealth", "cancelMediaDownload", "stopLiveLoop", "stopLocalVideo",
    "stopEventLoop", "cancelEventLoop", "cancelMediaUpload", "clearScheduledMediaTransferRender",
    "cancelLatestMediaRefresh", "clearBulbTimer", "renderLiveMagnification", "renderMediaTransfer",
    "renderLatestMedia", "renderMediaDetails", "renderMediaPreviewNavigation"]) sandbox[name] = () => {};
  for (const name of ["setOperationState", "showToast", "showConnectionError"]) {
    sandbox[name] = (...args) => feedback.push([name, ...args]);
  }
  sandbox.renderSession = () => sandbox.renderAvailability();
  const names = ["connectCamera", "disconnectCamera", "resetSession", "refreshSession",
    "featureSupported", "mediaTransferActive", "cameraInteractionBusy", "beginCameraInteraction",
    "renderAvailability", "deleteMedia", "captureError", "clearMediaThumbnails",
    "closeMediaPreview", "clearMediaPreview", "resetMediaPreviewTransform",
    "closeMediaDetails", "clearMediaDetails"];
  vm.runInContext(names.map(productionFunction).join("\n"), sandbox);
  return sandbox;
}

function populateMedia(subject, owner) {
  const item = { id: "DCIM/IMG_0001.JPG", name: "IMG_0001.JPG", owner };
  const other = { id: "DCIM/IMG_0002.JPG", name: "IMG_0002.JPG", owner };
  Object.assign(subject.state, {
    media: [item, other], mediaPreviewItem: item, mediaDetailsItem: item,
    mediaThumbnailUrls: new Map([[item.id, `blob:${owner}-target`], [other.id, `blob:${owner}-other`]]),
    mediaThumbnailLoads: new Map([[item.id, { owner }], [other.id, { owner }]]),
    mediaThumbnailFailures: new Set([item.id, other.id]),
  });
  subject.ui.mediaPreviewDialog.open = true;
  subject.ui.mediaDetailsDialog.open = true;
  return { item, other };
}

function mediaDeletes(subject) {
  return subject.requests.filter(({ url, method }) => method === "DELETE" && url.includes("/media/"));
}

function snapshot(subject) {
  const { state, ui } = subject;
  return {
    session: state.session, busy: state.busy, refreshGeneration: state.refreshGeneration,
    media: Array.from(state.media), thumbnailUrls: Array.from(state.mediaThumbnailUrls),
    thumbnailLoads: Array.from(state.mediaThumbnailLoads), thumbnailFailures: Array.from(state.mediaThumbnailFailures),
    preview: state.mediaPreviewItem, details: state.mediaDetailsItem,
    previewOpen: ui.mediaPreviewDialog.open, detailsOpen: ui.mediaDetailsDialog.open,
    previewGeneration: state.mediaPreviewGeneration, detailsGeneration: state.mediaDetailsGeneration,
    dateRange: state.mediaDateRange, dateDialogSession: state.mediaDateDialogSession,
    dateDialogOpen: ui.mediaDateDialog.open,
    lastError: state.lastError, buttonDisabled: ui.mediaDetailsDelete.disabled,
    feedback: subject.feedback.slice(), released: subject.released.slice(), renders: subject.renders.slice(),
  };
}

async function pendingDelete(options) {
  const subject = context(options);
  await subject.connectCamera();
  assert.equal(subject.ui.controlView.hidden, false);
  const media = populateMedia(subject, "A");
  const pending = subject.deleteMedia(media.item, subject.ui.mediaDetailsDelete);
  assert.equal(subject.ui.mediaDetailsDelete.disabled, true);
  subject.renderAvailability();
  assert.equal(subject.state.busy, false, "Deletion must not acquire a new global lock");
  assert.equal(subject.ui.disconnectButton.disabled, false, "Production UI permits disconnect during deletion");
  assert.equal(subject.ui.refreshButton.disabled, false, "Production UI permits refresh during deletion");
  assert.deepEqual(subject.confirmations, ["deleteConfirm: IMG_0001.JPG"]);
  assert.deepEqual(mediaDeletes(subject), [{
    url: "/v1/session/synthetic-A/media/DCIM%2FIMG_0001.JPG", method: "DELETE",
  }]);
  return { subject, pending, ...media };
}

for (const outcome of ["success", "rejection"]) {
  for (const replacement of ["disconnected", "different ID", "reused ID"]) {
    test(`late media-delete ${outcome} cannot change ${replacement} state`, async () => {
      const { subject, pending } = await pendingDelete({ reuseSessionId: replacement === "reused ID" });
      const oldSession = subject.state.session;
      subject.state.mediaDateRange = { start: "2026-10-01", end: "2026-10-01" };
      subject.state.mediaDateDialogSession = oldSession;
      subject.ui.mediaDateDialog.open = true;
      await subject.disconnectCamera();
      assert.equal(subject.state.session, null);
      assert.equal(subject.state.mediaDateRange, null);
      assert.equal(subject.state.mediaDateDialogSession, null);
      assert.equal(subject.ui.mediaDateDialog.open, false);
      assert.equal(subject.ui.connectionView.hidden, false);
      assert.equal(subject.ui.connectButton.disabled, false, "Production UI permits reconnect");
      if (replacement !== "disconnected") {
        await subject.connectCamera();
        assert.notEqual(subject.state.session, oldSession);
        assert.equal(subject.state.session.id, replacement === "reused ID" ? oldSession.id : "synthetic-B");
        populateMedia(subject, "B");
        subject.beginCameraInteraction();
        subject.ui.mediaDetailsDelete.disabled = true;
      }
      const newerError = { message: "synthetic newer feedback" };
      subject.state.lastError = newerError;
      const expected = snapshot(subject);
      const requests = subject.requests.slice();
      if (outcome === "success") subject.heldDelete.resolve({});
      else subject.heldDelete.reject(new Error("synthetic old delete failed"));
      await pending;
      assert.deepEqual(subject.requests, requests, "Late settlement must not retry or send a replacement-session command");
      assert.equal(mediaDeletes(subject).length, 1);
      assert.deepEqual(snapshot(subject), expected, "Old settlement must not mutate current media, dialogs, feedback or controls");
      assert.equal(subject.state.lastError, newerError);
    });
  }

  for (const activity of ["no refresh", "manual refresh", "interaction and refresh"]) {
    test(`same-session media-delete ${outcome} preserves normal behavior with ${activity}`, async () => {
      const { subject, pending, item, other } = await pendingDelete();
      const session = subject.state.session;
      const generation = subject.state.refreshGeneration;
      const otherInteraction = activity === "interaction and refresh";
      if (activity !== "no refresh") {
        if (otherInteraction) subject.beginCameraInteraction();
        assert.equal(await subject.refreshSession(), true);
        assert.equal(subject.state.session, session);
        assert.equal(subject.state.refreshGeneration, generation + Number(otherInteraction));
      }
      const expected = snapshot(subject);
      const requests = subject.requests.slice();
      if (outcome === "success") subject.heldDelete.resolve({});
      else subject.heldDelete.reject(new Error("synthetic current delete failed"));
      await pending;
      assert.deepEqual(subject.requests, requests, "Delete settlement makes no follow-up request");
      assert.equal(mediaDeletes(subject).length, 1);
      assert.equal(subject.state.session, session);
      assert.equal(subject.state.busy, otherInteraction, "Delete settlement must not release another interaction's lock");
      assert.equal(subject.state.refreshGeneration, expected.refreshGeneration);
      if (outcome === "success") {
        assert.deepEqual(Array.from(subject.state.media), [other]);
        assert.deepEqual(Array.from(subject.state.mediaThumbnailUrls), [[other.id, "blob:A-other"]]);
        assert.deepEqual(Array.from(subject.state.mediaThumbnailLoads.keys()), [other.id]);
        assert.deepEqual(Array.from(subject.state.mediaThumbnailFailures), [other.id]);
        assert.equal(subject.state.mediaPreviewItem, null);
        assert.equal(subject.state.mediaDetailsItem, null);
        assert.equal(subject.ui.mediaPreviewDialog.open, false);
        assert.equal(subject.ui.mediaDetailsDialog.open, false);
        assert.deepEqual(subject.released, ["blob:A-target"]);
        assert.deepEqual(subject.renders, ["media"]);
        assert.equal(subject.state.lastError, null);
        assert.deepEqual(subject.feedback.at(-1), ["showToast", `deleted: ${item.name}`]);
      } else {
        const actual = snapshot(subject);
        for (const key of ["lastError", "buttonDisabled", "feedback", "renders"]) delete actual[key];
        for (const key of ["lastError", "buttonDisabled", "feedback", "renders"]) delete expected[key];
        assert.deepEqual(actual, expected, "Failed deletion retains media, caches and dialogs");
        assert.equal(subject.state.lastError.message, "synthetic current delete failed");
        assert.equal(subject.ui.mediaDetailsDelete.disabled, false);
        assert.deepEqual(subject.renders, ["diagnostics"]);
        assert.deepEqual(subject.feedback.at(-1), ["showToast", "synthetic current delete failed", true]);
      }
    });
  }
}

for (const guard of ["no session", "unsupported", "busy", "download preparing", "download active", "upload active", "cancelled"]) {
  test(`media deletion does nothing when ${guard}`, async () => {
    const subject = context({ confirmed: guard !== "cancelled" });
    if (guard !== "no session") await subject.connectCamera();
    const { item } = populateMedia(subject, "A");
    if (guard === "unsupported") subject.state.capabilities.supported = ["MEDIA_BROWSER"];
    if (guard === "busy") subject.state.busy = true;
    if (guard === "download preparing") subject.state.mediaDownloadPreparing = true;
    if (guard === "download active") subject.state.mediaDownload = {};
    if (guard === "upload active") subject.state.mediaUpload = {};
    const expected = snapshot(subject);
    const requests = subject.requests.slice();
    await subject.deleteMedia(item, subject.ui.mediaDetailsDelete);
    assert.deepEqual(subject.requests, requests);
    assert.equal(mediaDeletes(subject).length, 0);
    assert.deepEqual(snapshot(subject), expected);
    assert.equal(subject.confirmations.length, guard === "cancelled" ? 1 : 0);
  });
}
