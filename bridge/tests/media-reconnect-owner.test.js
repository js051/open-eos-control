"use strict";

// Production reset/session/card handlers with a minimal DOM and controlled API.
// Detached handlers are retained deliberately: removing DOM alone cannot revoke
// a queued click. These are deterministic client tests, not camera/browser proof.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const mediaBatch = require("../open_eos_bridge/static/media-batch.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, `Missing production function ${name}`);
  const dialogDependencies = ["openMediaPreview", "openMediaDetails", "renderMediaPreviewNavigation",
    "renderMediaDetails", "closeMediaPreview", "closeMediaDetails"].includes(name)
    ? ["mediaDialogFocusTarget", "beginMediaDialogFocus", "restoreMediaDialogFocus", "setMediaDialogActionDisabled"]
      .map(extract).join("\n") + "\n" : "";
  return dialogDependencies + source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function element(tagName = "div", document = null) {
  const listeners = {};
  return { tagName: tagName.toUpperCase(), isConnected: true, children: [], dataset: {}, className: "", hidden: false, disabled: false, open: false, value: "",
    classList: { toggle() {}, remove() {} }, style: { removeProperty() {} },
    append(...nodes) { nodes.forEach(node => { node.parent = this; }); this.children.push(...nodes); },
    replaceChildren(...nodes) {
      this.querySelectorAll("*").forEach(node => { node.isConnected = false; });
      this.children = []; this.append(...nodes);
    },
    contains(node) { return node === this || this.querySelectorAll("*").includes(node); },
    closest() { return this.hidden ? this : null; }, getClientRects() { return this.hidden ? [] : [1]; },
    focus() { document.activeElement = this; },
    setAttribute() {}, removeAttribute() {}, close() { this.open = false; document.activeElement = document.body; }, showModal() { this.open = true; document.activeElement = this; },
    addEventListener(type, callback) { listeners[type] = callback; },
    dispatch(type, event = {}) { return listeners[type]?.({ target: this, ...event }); },
    querySelectorAll(selector) {
      const all = this.children.flatMap(node => [node, ...node.querySelectorAll("*")]);
      return selector === "button[data-media-id][data-media-action]" ? all.filter(node => node.tagName === "BUTTON" && node.dataset.mediaId && node.dataset.mediaAction) : selector === "*" ? all : selector.startsWith(".")
        ? all.filter(node => node.className.split(" ").includes(selector.slice(1))) : [];
    },
  };
}
const oldItem = () => ({ id: "shared-file-id", name: "OLD_CAMERA.JPG", kind: "jpeg", previewAvailable: true });
function fixture({ thumbnails = false } = {}) {
  const state = { session: { id: "session-a" }, activeView: "media", capabilities: {}, status: {},
    media: [oldItem()], mediaLoaded: true, mediaLoadStatus: "COMPLETE", mediaScope: "all",
    mediaSort: "camera", mediaFilter: "all", mediaPage: 0, refreshGeneration: 0,
    mediaSelection: mediaBatch.createSelection(), mediaThumbnailUrls: new Map(), operatorConfirmedFeatures: new Set(),
    mediaPreviewGeneration: 0, mediaDetailsGeneration: 0, settingEditor: 0,
    mediaGeneration: 0, mediaThumbnailLoads: new Set(), mediaThumbnailFailures: new Set(),
    cameras: [{ id: "camera", engine: "synthetic" }],
  };
  const document = { body: {}, activeElement: null };
  document.activeElement = document.body;
  document.createElement = tag => element(tag, document);
  const ui = new Proxy({}, { get(target, name) { return target[name] ||= element("div", document); } });
  for (const kind of ["Preview", "Details"]) ui[`media${kind}Close`] = element("button", document);
  document.querySelector = () => [ui.mediaPreviewDialog, ui.mediaDetailsDialog].find(dialog => dialog.open) || null;
  const requests = [], observers = [];
  const panels = [Object.assign(element(), { id: "live-panel", hidden: true }), Object.assign(element(), { id: "media-panel", hidden: false })];
  class Observer {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe() {} unobserve() {} disconnect() {}
    deliver(target) { this.callback([{ target, isIntersecting: true }], this); }
  }
  const c = vm.createContext({ state, ui, requests, observers, panels, mediaLibrary, mediaBatch, AbortController, Map, Set, Intl, URL,
    IntersectionObserver: Observer,
    MEDIA_PAGE_SIZE: 72, MAX_MEDIA_PREVIEW_BYTES: 32 * 1024 * 1024,
    FEATURES: new Proxy({}, { get: (_, name) => name }),
    featureSupported: feature => thumbnails || feature !== "MEDIA_THUMBNAIL",
    document: Object.assign(document, { querySelectorAll: selector => selector === ".view-panel" ? panels : [] }), window: { location: { href: "http://synthetic.invalid/" }, IntersectionObserver: Observer },
    connectionRetirementBlocked: () => false, clampFps: value => value, captureModeFromCamera: () => "photo",
    adoptCameraStatus: status => { state.status = status; },
    showConnectionError: error => { throw error; },
    sleep: () => Promise.resolve(),
    t: key => key, displayedMedia: () => state.media, mediaDateGroup: () => "Synthetic date",
    mediaIsVideo: mediaLibrary.isVideo, mediaMetadataSupported: () => true, cameraInteractionBusy: () => false,
    formatMediaTime: () => "", formatMediaDimensions: () => "", formatMediaSize: () => "",
    clearMediaPreview() { state.mediaPreviewGeneration++; state.mediaPreviewItem = null; },
    api(url, options = {}) {
      requests.push({ url, options });
      // Never resolve media reads: tests inspect admission, not response handling.
      return new Promise(() => {});
    },
  });
  for (const name of ["stopLiveLoop", "stopLocalVideo", "cancelEventLoop", "cancelMediaDownload", "cancelMediaUpload",
    "clearScheduledMediaTransferRender", "clearMediaThumbnails", "cancelLatestMediaRefresh", "cancelSetting",
    "releaseObjectUrl", "clearBulbTimer", "renderAvailability", "renderHealth", "renderCaptureMode", "renderExposure",
    "renderAdvancedSettings", "renderPreviewInput", "renderLiveSource", "renderFps", "renderTapAction",
    "renderTemperatureStatus", "syncBulbTimer", "renderDiagnostics", "renderRecordingHistory", "renderMediaDetails",
    "renderMediaPreviewNavigation", "renderMediaSummary", "renderMediaSelection", "renderMediaThumbnail",
    "clearConnectionError", "renderConnectionGuidance", "syncLiveMagnificationFromCapabilities", "setOperationState",
    "startEventLoop", "refreshLatestMedia", "showToast", "updateVisibleMediaThumbnail", "renderHeldAutofocus"])
    c[name] = () => {};
  const names = ["resetSession", "renderSession", "renderMedia", "refreshMedia", "refreshMediaWhenCurrent",
    "openMediaDetails", "clearMediaDetails", "closeMediaDetails", "openMediaPreview", "closeMediaPreview",
    "connectCamera", "observeMediaThumbnail", "loadMediaThumbnail", "selectView"];
  vm.runInContext("let mediaThumbnailObserver = null;\n" + names.map(extract).join("\n"), c);
  c.renderMedia();
  return c;
}
function controls(c) {
  return { selection: c.ui.mediaList.querySelectorAll(".media-select-checkbox")[0],
    preview: c.ui.mediaList.querySelectorAll(".media-card-preview")[0],
    details: c.ui.mediaList.querySelectorAll(".icon-button")[0] };
}
function reconnect(c, sameSessionId = false) {
  const id = c.state.session.id;
  c.resetSession();
  c.state.session = { id: sameSessionId ? id : "session-b" };
  c.state.status = {};
  c.state.capabilities = {};
}

test("reset removes every old media card synchronously and clears selection", () => {
  const c = fixture();
  c.state.mediaSelection.setSelected("shared-file-id", true);
  assert.equal(c.ui.mediaList.querySelectorAll(".media-card").length, 1);
  c.resetSession();
  assert.equal(c.state.session, null);
  assert.equal(c.state.media.length, 0);
  assert.equal(c.state.mediaSelection.snapshot().length, 0);
  assert.equal(c.ui.mediaList.querySelectorAll(".media-card").length, 0, "Reset must remove stale camera DOM");
  assert.equal(c.requests.length, 0);
});

for (const reuseId of [false, true]) {
  test(`reconnect in Media starts exactly one fresh listing (${reuseId ? "same" : "different"} session ID)`, async () => {
    const c = fixture(), id = reuseId ? c.state.session.id : "session-b";
    c.resetSession();
    c.ui.cameraSelect.value = "camera";
    c.api = (url, options = {}) => {
      c.requests.push({ url, options });
      if (options.method === "POST") return Promise.resolve({ id });
      if (/\/(info|status|capabilities)$/.test(url)) return Promise.resolve({});
      return new Promise(() => {});
    };
    await c.connectCamera();
    await Promise.resolve();
    c.renderSession(); c.renderSession();
    const listings = c.requests.filter(row => /\/media(?:\?|$)/.test(row.url));
    assert.equal(listings.length, 1, "Visible Media must reload without a tab switch; repeated renders coalesce");
    assert.equal(listings[0].url, `/v1/session/${id}/media`);
    assert.equal(listings[0].options.responseType, "mediaListing");
  });
  for (const action of ["selection", "preview", "details"]) {
    test(`retired ${action} closure cannot touch same file ID after ${reuseId ? "same" : "different"} session ID reconnect`, async () => {
      const c = fixture(), old = controls(c)[action];
      reconnect(c, reuseId);
      c.state.media = [{ ...oldItem(), name: "NEW_CAMERA.JPG" }];
      c.state.mediaLoaded = true;
      c.state.mediaLoadStatus = "COMPLETE";
      c.renderMedia();
      old.checked = true;
      // Do not await an admitted API call: an erroneous admission intentionally
      // remains pending and is diagnosed by the assertions below.
      old.dispatch(action === "selection" ? "change" : "click");
      await Promise.resolve();
      assert.equal(c.requests.length, 0, "Retired card must not read new-session info or preview");
      assert.equal(c.state.mediaSelection.has("shared-file-id"), false, "Retired checkbox must not select replacement media");
      assert.equal(c.ui.mediaDetailsDialog.open, false);
      assert.equal(c.ui.mediaPreviewDialog.open, false);
    });
  }
}

for (const action of ["selection", "preview", "details"]) {
  test(`retired ${action} closure is inert with no session`, async () => {
    const c = fixture(), old = controls(c)[action];
    c.resetSession(); old.checked = true;
    old.dispatch(action === "selection" ? "change" : "click");
    await Promise.resolve();
    assert.equal(c.requests.length, 0);
    assert.equal(c.state.mediaSelection.has("shared-file-id"), false);
    assert.equal(c.ui.mediaDetailsDialog.open, false);
    assert.equal(c.ui.mediaPreviewDialog.open, false);
  });
}

test("empty session render and refresh cannot start a listing", async () => {
  const c = fixture(); c.resetSession(); c.renderSession();
  assert.equal(await c.refreshMedia(), false);
  assert.equal(c.requests.length, 0);
});

test("current card handlers still admit selection, preview and metadata", () => {
  for (const action of ["selection", "preview", "details"]) {
    const c = fixture(), current = controls(c)[action];
    current.checked = true;
    current.dispatch(action === "selection" ? "change" : "click");
    if (action === "selection") assert.equal(c.state.mediaSelection.has("shared-file-id"), true);
    else assert.equal(c.requests[0].url, `/v1/session/session-a/media/shared-file-id/${action === "preview" ? "preview" : "info"}`);
  }
});

for (const reuseId of [false, true]) test(`queued old thumbnail entry cannot read replacement media (${reuseId ? "same" : "different"} session ID)`, () => {
  const c = fixture({ thumbnails: true }), old = controls(c).preview, observer = c.observers[0];
  assert.ok(observer, "Production render must register thumbnail demand");
  reconnect(c, reuseId);
  c.state.media = [{ ...oldItem(), name: "NEW_CAMERA.JPG" }];
  c.state.mediaLoaded = true; c.state.mediaLoadStatus = "COMPLETE";
  c.renderMedia();
  observer.deliver(old);
  assert.equal(c.requests.length, 0, "A queued observer entry from retired DOM cannot read a new camera's matching ID");
  c.observers.at(-1).deliver(controls(c).preview);
  assert.equal(c.requests.length, 1, "Current observer demand must still load its thumbnail");
  assert.equal(c.requests[0].url, `/v1/session/${c.state.session.id}/media/shared-file-id/thumbnail`);
});

test("Media reconnect without browser capability returns to Live and does not list", async () => {
  const c = fixture(); c.resetSession();
  c.ui.cameraSelect.value = "camera";
  c.featureSupported = () => false;
  c.api = (url, options = {}) => {
    c.requests.push({ url, options });
    return Promise.resolve(options.method === "POST" ? { id: "no-media-session" } : {});
  };
  await c.connectCamera();
  assert.equal(c.state.activeView, "live");
  assert.equal(c.panels.find(panel => panel.id === "media-panel").hidden, true);
  assert.equal(c.panels.find(panel => panel.id === "live-panel").hidden, false);
  assert.equal(c.requests.filter(row => /\/media(?:\?|$)/.test(row.url)).length, 0);
});

for (const status of ["FAILED", "CANCELLED", "COMPLETE"]) test(`session render never implicitly retries ${status} media listing`, () => {
  const c = fixture(); c.state.mediaLoadStatus = status; c.state.mediaLoaded = true;
  c.renderSession(); c.renderSession();
  assert.equal(c.requests.length, 0);
});

test("session render outside Media keeps listing lazy", () => {
  const c = fixture(); reconnect(c); c.state.activeView = "live";
  c.renderSession();
  assert.equal(c.requests.length, 0);
});

test("reconnect listing waits for the connection busy boundary before camera read", async () => {
  const c = fixture(), waits = [];
  c.resetSession(); c.ui.cameraSelect.value = "camera";
  c.cameraInteractionBusy = () => c.state.busy;
  c.sleep = () => new Promise(resolve => waits.push(resolve));
  c.api = (url, options = {}) => {
    c.requests.push({ url, options });
    if (options.method === "POST") return Promise.resolve({ id: "busy-boundary-session" });
    if (/\/(info|status|capabilities)$/.test(url)) return Promise.resolve({});
    return new Promise(() => {});
  };
  await c.connectCamera();
  assert.equal(c.state.busy, false);
  assert.equal(c.requests.filter(row => /\/media(?:\?|$)/.test(row.url)).length, 0);
  assert.equal(waits.length, 1, "Mounted Media must wait until connect unlocks the camera");
  waits[0]();
  for (let turn = 0; turn < 5; turn++) await Promise.resolve();
  assert.equal(c.requests.filter(row => /\/media(?:\?|$)/.test(row.url)).length, 1);
});

for (const pageChange of [false, true]) for (const action of ["selection", "preview", "details"]) {
  test(`${pageChange ? "page change" : "rerender"} in the same session retires old ${action} handler`, async () => {
    const c = fixture(), old = controls(c)[action], session = c.state.session;
    if (pageChange) {
      c.state.media = [c.state.media[0], ...Array.from({ length: 72 }, (_, index) => ({ ...oldItem(), id: `page-file-${index}` }))];
      c.state.mediaPage = 1;
    }
    c.renderMedia();
    assert.equal(c.state.session, session);
    old.checked = true;
    old.dispatch(action === "selection" ? "change" : "click");
    await Promise.resolve();
    assert.equal(c.requests.length, 0);
    assert.equal(c.state.mediaSelection.has("shared-file-id"), false);
    assert.equal(c.ui.mediaPreviewDialog.open, false);
    assert.equal(c.ui.mediaDetailsDialog.open, false);
    const current = controls(c)[action];
    current.checked = true;
    current.dispatch(action === "selection" ? "change" : "click");
    if (action === "selection") assert.equal(c.state.mediaSelection.has(pageChange ? "page-file-71" : "shared-file-id"), true);
    else assert.equal(c.requests.length, 1);
  });
}

test("same-session page change retires a queued thumbnail entry", () => {
  const c = fixture({ thumbnails: true }), old = controls(c).preview, observer = c.observers[0];
  c.state.media = Array.from({ length: 73 }, (_, index) => ({ ...oldItem(), id: `page-file-${index}` }));
  c.state.mediaPage = 1; c.renderMedia();
  observer.deliver(old);
  assert.equal(c.requests.length, 0);
  c.observers.at(-1).deliver(controls(c).preview);
  assert.equal(c.requests.length, 1);
  assert.match(c.requests[0].url, /page-file-72\/thumbnail$/);
});

test("same-session rerender rejects old observer even when current card has identical file ID", () => {
  const c = fixture({ thumbnails: true }), old = controls(c).preview, observer = c.observers[0];
  c.renderMedia();
  observer.deliver(old);
  assert.equal(c.requests.length, 0);
  c.observers.at(-1).deliver(controls(c).preview);
  assert.equal(c.requests.length, 1);
});

for (const reuseId of [false, true]) test(`late metadata completion cannot publish into ${reuseId ? "same-ID" : "different-ID"} replacement session`, async () => {
  const c = fixture(), publications = [];
  let finish;
  c.api = (url, options) => {
    c.requests.push({ url, options });
    return new Promise(resolve => { finish = resolve; });
  };
  c.replaceMediaItem = item => publications.push(item);
  const pending = c.openMediaDetails(c.state.media[0]);
  assert.equal(c.requests.length, 1);
  reconnect(c, reuseId);
  const replacement = { ...oldItem(), name: "NEW_CAMERA.JPG" };
  c.state.media = [replacement]; c.state.mediaLoaded = true;
  c.renderMedia();
  const before = c.ui.mediaList.querySelectorAll(".media-card")[0];
  finish({ ...oldItem(), name: "LATE_OLD_METADATA.JPG" });
  await pending;
  assert.equal(publications.length, 0, "Old metadata cannot invoke publication into replacement camera");
  assert.equal(c.state.media[0], replacement);
  assert.equal(c.ui.mediaList.querySelectorAll(".media-card")[0], before, "Old completion must not rerender the new camera");
  assert.equal(c.ui.mediaDetailsDialog.open, false);
  assert.equal(c.state.mediaDetailsItem, null);
});

// Joint scheduling regression: latest-media functions must be production code.
// Stubbing refreshLatestMedia hides its deliberate listing-cancel admission path.
function latestJointFixture() {
  const c = fixture(), gallery = [], latest = [];
  c.mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
  c.LATEST_MEDIA_LIMIT = 8;
  c.LATEST_MEDIA_RETRY_DELAYS_MILLIS = [250, 750, 1500];
  c.state.latestMediaGeneration = 0;
  c.state.latestMediaController = null;
  c.renderLatestMedia = () => {};
  c.captureError = error => error;
  vm.runInContext(["refreshLatestMedia", "cancelLatestMediaRefresh", "latestMediaFrom", "publishLatestMedia",
    "loadLatestMediaThumbnail"].map(extract).join("\n"), c);
  c.resetSession(); c.ui.cameraSelect.value = "camera";
  c.api = (url, options = {}) => {
    c.requests.push({ url, options });
    if (options.method === "POST") return Promise.resolve({ id: "joint-session" });
    if (/\/(info|status|capabilities)$/.test(url)) return Promise.resolve({});
    const calls = url.endsWith("?limit=8") ? latest : gallery;
    return new Promise((resolve, reject) => calls.push({ url, options, resolve, reject }));
  };
  return Object.assign(c, { gallery, latest });
}
async function drainMicrotasks() {
  for (let turn = 0; turn < 20; turn++) await Promise.resolve();
}

test("production connect keeps gallery owner alive until completion, then publishes latest media", async () => {
  const c = latestJointFixture(), waits = [];
  c.cameraInteractionBusy = () => c.state.busy;
  c.sleep = () => new Promise(resolve => waits.push(resolve));
  await c.connectCamera();
  assert.equal(c.state.busy, false, "Connect must release its busy boundary");
  assert.equal(c.gallery.length, 0, "Gallery cannot issue a camera read while connect is busy");
  assert.equal(c.latest.length, 0, "Production latest cannot race a gallery waiting for connect");
  assert.equal(waits.length, 1);
  waits[0]();
  await drainMicrotasks();
  assert.equal(c.gallery.length, 1);
  assert.equal(c.latest.length, 0, "Latest review must not cancel or race the reconnect gallery");
  assert.equal(c.gallery[0].options.signal.aborted, false);
  assert.ok(c.state.mediaListingOwner, "The gallery owner must survive connect completion");
  const galleryPromise = c.state.mediaRefreshPromise;
  c.gallery[0].resolve({ items: [oldItem()] });
  assert.equal(await galleryPromise, true);
  await drainMicrotasks();
  assert.equal(c.state.mediaLoadStatus, "COMPLETE");
  assert.equal(c.latest.length, 1);
  c.latest[0].resolve({ items: [{ ...oldItem(), name: "LATEST_OBSERVED.JPG" }] });
  await drainMicrotasks();
  assert.equal(c.state.latestMediaItem.name, "LATEST_OBSERVED.JPG", "Production latest read and publication still complete");
  assert.equal(c.state.media[0].name, "OLD_CAMERA.JPG");
  assert.equal(c.state.mediaLoadStatus, "COMPLETE");
});

for (const outcome of ["cancel", "failure"]) test(`reconnect gallery ${outcome} never starts automatic latest review`, async () => {
  const c = latestJointFixture(); await c.connectCamera();
  assert.equal(c.latest.length, 0, "Latest review waits for successful gallery completion");
  const pending = c.state.mediaRefreshPromise;
  if (outcome === "cancel") c.state.mediaListingOwner.cancel();
  else c.gallery[0].reject(new Error("Synthetic gallery failure"));
  assert.equal(await pending, false);
  await drainMicrotasks();
  assert.equal(c.latest.length, 0);
  assert.equal(c.state.mediaLoadStatus, outcome === "cancel" ? "CANCELLED" : "FAILED");
});

test("retired same-ID session gallery completion cannot start latest review in replacement session", async () => {
  const c = latestJointFixture(); await c.connectCamera();
  assert.equal(c.latest.length, 0);
  const pending = c.state.mediaRefreshPromise;
  c.resetSession(); c.state.session = { id: "joint-session" };
  c.state.media = [{ ...oldItem(), name: "REPLACEMENT.JPG" }];
  c.gallery[0].resolve({ items: [oldItem()] });
  assert.equal(await pending, false);
  await drainMicrotasks();
  assert.equal(c.latest.length, 0);
  assert.equal(c.state.media[0].name, "REPLACEMENT.JPG");
  assert.equal(c.state.latestMediaItem, null);
});

test("non-Media connect retains production latest observation without a gallery traversal", async () => {
  const c = latestJointFixture(); c.state.activeView = "live";
  await c.connectCamera();
  assert.equal(c.gallery.length, 0);
  assert.equal(c.latest.length, 1);
  c.latest[0].resolve({ items: [oldItem()] });
  await drainMicrotasks();
  assert.equal(c.state.latestMediaItem.name, "OLD_CAMERA.JPG");
});

for (const competing of ["latest review", "replacement listing", "new camera interaction", "busy camera", "same-ID replacement session"]) {
  test(`gallery completion continuation cannot preempt ${competing}`, async () => {
    const c = latestJointFixture(); await c.connectCamera();
    assert.equal(c.latest.length, 0);
    const pending = c.state.mediaRefreshPromise;
    const render = c.renderMedia;
    let competingPromise, fired = false;
    // This hook runs after the production gallery releases its owner but before
    // its promise continuation, precisely the gap where new work can acquire it.
    c.renderMedia = () => {
      render();
      if (fired || c.state.mediaLoadStatus !== "COMPLETE") return;
      fired = true;
      if (competing === "latest review") competingPromise = c.refreshLatestMedia();
      if (competing === "replacement listing") competingPromise = c.refreshMedia();
      if (competing === "new camera interaction") c.state.refreshGeneration++;
      if (competing === "busy camera") c.cameraInteractionBusy = () => true;
      if (competing === "same-ID replacement session") {
        const id = c.state.session.id;
        c.resetSession(); c.state.session = { id };
      }
    };
    c.gallery[0].resolve({ items: [oldItem()] });
    assert.equal(await pending, true);
    await drainMicrotasks();
    assert.equal(fired, true);
    assert.equal(c.latest.length, competing === "latest review" ? 1 : 0,
      "Reconnect enrichment cannot start an extra latest read at a contested continuation boundary");
    if (competing === "latest review") {
      assert.equal(c.state.latestMediaRefreshPromise, competingPromise);
      assert.equal(c.latest[0].options.signal.aborted, false);
      c.latest[0].resolve({ items: [oldItem()] });
      assert.equal(await competingPromise, true);
      assert.equal(c.state.latestMediaItem.name, "OLD_CAMERA.JPG");
    }
    if (competing === "replacement listing") {
      assert.equal(c.gallery.length, 2);
      assert.equal(c.state.mediaRefreshPromise, competingPromise);
      assert.equal(c.gallery[1].options.signal.aborted, false);
      c.gallery[1].resolve({ items: [{ ...oldItem(), name: "NEW_LISTING.JPG" }] });
      assert.equal(await competingPromise, true);
      assert.equal(c.state.media[0].name, "NEW_LISTING.JPG");
    }
  });
}


test("actual Manage metadata completion replaces cards and close restores the same current action", async () => {
  const c = fixture();
  let resolve;
  c.api = () => new Promise(done => { resolve = done; });
  c.replaceMediaItem = item => { c.state.media = [item]; c.state.mediaDetailsItem = item; };
  c.captureError = error => error;
  const original = controls(c).details;
  original.focus(); original.dispatch("click");
  assert.equal(c.state.mediaDetailsFocusOwner.original, original);
  resolve({ ...oldItem(), rating: 3 });
  await new Promise(done => setImmediate(done));
  assert.equal(original.isConnected, false);
  const replacement = controls(c).details;
  assert.notEqual(replacement, original);
  c.closeMediaDetails();
  assert.equal(c.document.activeElement, replacement);
});

test("actual Preview open, navigation/retry and Preview-to-Details retain the initial card invoker", () => {
  const c = fixture(), original = controls(c).preview;
  original.focus(); original.dispatch("click");
  const owner = c.state.mediaPreviewFocusOwner;
  assert.equal(owner.original, original);
  void c.openMediaPreview({ ...oldItem(), id: "next-item" });
  void c.openMediaPreview({ ...oldItem(), id: "next-item" });
  assert.equal(c.state.mediaPreviewFocusOwner, owner);
  // Execute the production toolbar event body, preserving its transfer behavior.
  const start = source.indexOf('    ui.mediaPreviewDetails.addEventListener("click", () => {');
  vm.runInContext(source.slice(start, source.indexOf("\n    });", start) + 8), c);
  c.ui.mediaPreviewDetails.dispatch("click");
  assert.equal(c.state.mediaPreviewFocusOwner, null);
  assert.equal(c.state.mediaDetailsFocusOwner.original, original);
  c.renderMedia(); c.closeMediaDetails();
  assert.equal(c.document.activeElement, controls(c).preview);
});

test("reset retires modal owners before close and cannot focus the old card", () => {
  const c = fixture(), original = controls(c).details;
  original.focus(); original.dispatch("click");
  let restored = 0; original.focus = () => { restored++; };
  c.resetSession();
  assert.equal(c.state.mediaDetailsFocusOwner, null);
  assert.equal(c.state.mediaPreviewFocusOwner, null);
  assert.equal(restored, 0);
});

test("queued close from an earlier opening cannot erase a newer same-session Details", () => {
  const c = fixture(); c.mediaMetadataSupported = () => false;
  const start = source.indexOf('    ui.mediaDetailsDialog.addEventListener("cancel",');
  vm.runInContext(source.slice(start, source.indexOf("\n    });", start) + 8), c);
  controls(c).details.focus(); void c.openMediaDetails(oldItem());
  let prevented = false;
  c.ui.mediaDetailsDialog.dispatch("cancel", { preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(c.ui.mediaDetailsDialog.open, false);
  assert.equal(c.document.activeElement, controls(c).details);
  controls(c).details.focus(); void c.openMediaDetails({ ...oldItem(), id: "new-opening" });
  const owner = c.state.mediaDetailsFocusOwner;
  c.ui.mediaDetailsDialog.dispatch("close");
  assert.equal(c.state.mediaDetailsItem.id, "new-opening");
  assert.equal(c.state.mediaDetailsFocusOwner, owner);
});
