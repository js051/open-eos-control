"use strict";

// Real production event/state/media functions, controlled synthetic API replies
// and timers. No browser, Bridge HTTP, elapsed-time, or physical-camera claim.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");

function productionFunction(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, `Missing production function ${name}`);
  const end = source.indexOf("\n  }", match.index);
  assert.notEqual(end, -1);
  return source.slice(match.index, end + 4);
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function settle() {
  for (let turn = 0; turn < 12; turn += 1) await new Promise(setImmediate);
}

const OLD = { id: "SYNTHETIC_OLD.JPG", kind: "jpeg" };
const NEW = { id: "SYNTHETIC_NEW.JPG", kind: "jpeg" };
const capabilities = () => ({ supported: ["EVENT_POLLING", "MEDIA_BROWSER"], iso: ["100", "800"] });

function fixture(t, { ignorePollAbort = false } = {}) {
  const state = {
    session: { id: "synthetic-session" }, eventGeneration: 0, eventController: null,
    refreshGeneration: 0, busy: false, info: { model: "old" }, status: { recording: false, iso: "100" },
    capabilities: { ...capabilities(), iso: ["100"] }, recordingRecovery: null, recordingCommandOwner: null,
    requestedFps: 15, media: [OLD], mediaLoaded: true, mediaLoadStatus: "COMPLETE",
    mediaRefreshPromise: null, mediaScope: "recent", mediaHasMore: false,
  };
  const ui = { mediaRefreshButton: {}, mediaPreviewDialog: { open: true }, focusReticle: {} };
  const requests = [], polls = [], snapshots = [], listings = [], timers = [], errors = [], renders = [];
  const context = vm.createContext({
    state, ui, mediaTransfer, AbortController,
    FEATURES: { EVENT_POLLING: "EVENT_POLLING", MEDIA_BROWSER: "MEDIA_BROWSER" },
    clampFps: value => value, syncLiveMagnificationFromCapabilities() {},
    t: key => key, setOperationState() {}, showToast() {}, renderAvailability() {},
    renderSession: () => renders.push({ status: state.status, info: state.info, capabilities: state.capabilities }),
    renderMediaSummary() {}, renderMedia() {}, renderMediaPreviewNavigation() {}, clearMediaThumbnails() {},
    closeMediaPreview: () => { ui.mediaPreviewDialog.open = false; },
    recordingRecoveryActive: () => Boolean(state.recordingRecovery), refreshLatestMedia: async () => {},
    captureError: error => { errors.push(error.message); return error; },
    sleep: delay => { const gate = deferred(); timers.push({ ...gate, delay }); return gate.promise; },
    api: (url, options = {}) => {
      const method = options.method || "GET";
      requests.push({ url, method });
      assert.equal(method, "GET", "Recovery must never send a camera mutation");
      const gate = { ...deferred(), url };
      if (url.endsWith("/events")) {
        polls.push(gate);
        if (!ignorePollAbort) options.signal.addEventListener("abort", () => gate.reject(mediaTransfer.cancellationError()), { once: true });
      } else if (/\/(info|status|capabilities)$/.test(url)) {
        const key = url.split("/").at(-1);
        if (key === "info") snapshots.push({});
        snapshots.at(-1)[key] = gate;
      } else if (/\/media(?:\?|$)/.test(url)) listings.push(gate);
      else assert.fail(`Unexpected API read ${url}`);
      return gate.promise;
    },
  });
  vm.runInContext(["startEventLoop", "cancelEventLoop", "refreshSession", "featureSupported",
    "refreshMedia", "refreshMediaWhenCurrent", "mediaTransferActive", "cameraInteractionBusy",
    "beginCameraInteraction"].map(productionFunction).join("\n"), context, { timeout: 1000 });
  t.after(() => {
    context.cancelEventLoop();
    state.session = null;
    for (const timer of timers) timer.resolve();
    for (const read of [...snapshots.flatMap(row => Object.values(row)), ...listings]) {
      read.reject(mediaTransfer.cancellationError());
    }
  });
  return Object.assign(context, { requests, polls, snapshots, listings, timers, errors, renders });
}

function resolveSnapshot(s, index, { failed, recording = true, model = "new", iso = "800", caps = capabilities() } = {}) {
  const snapshot = s.snapshots[index];
  assert.ok(snapshot, `Snapshot ${index} exists`);
  for (const [key, response] of Object.entries({ info: { model }, status: { recording, iso }, capabilities: caps })) {
    if (key === failed) snapshot[key].reject(new Error(`synthetic ${key} read failure`));
    else snapshot[key].resolve(response);
  }
}

async function beginHint(s, keys = ["recbutton", "iso", "contents"]) {
  s.startEventLoop();
  s.polls[0].resolve({ changedKeys: keys });
  await settle();
}

function assertReadsOnly(s) {
  assert.equal(s.requests.every(row => row.method === "GET"), true);
  assert.equal(s.requests.some(row => /\/(capture|recording|shutter)(\/|$)/.test(row.url)), false);
}

for (const failed of ["info", "status", "capabilities"]) {
  test(`single consumed event recovers ${failed} failure without another nonempty event`, async t => {
    const s = fixture(t);
    await beginHint(s);
    resolveSnapshot(s, 0, { failed });
    await settle();
    assert.equal(s.state.status.recording, false, "No partial snapshot publication");
    assert.equal(s.state.info.model, "old");
    assert.deepEqual(Array.from(s.state.capabilities.iso), ["100"]);
    // Exercise the original loss path too: subsequent polls contain no new hint.
    if (s.polls[1]) { s.polls[1].resolve({ changedKeys: [] }); await settle(); }
    assert.equal(s.timers[0]?.delay, 1000, "The consumed hint must schedule recovery");
    s.timers[0].resolve();
    await settle();
    resolveSnapshot(s, 1);
    await settle();
    assert.equal(s.state.status.recording, true);
    assert.equal(s.state.status.iso, "800");
    assert.equal(s.state.info.model, "new");
    assert.deepEqual(Array.from(s.state.capabilities.iso), ["100", "800"]);
    assert.equal(s.listings.length, 1, "Retained contents hint follows recovered state");
    s.listings[0].resolve({ items: [NEW] });
    await settle();
    assert.deepEqual(Array.from(s.state.media), [NEW]);
    assert.equal(s.ui.mediaPreviewDialog.open, true);
    assert.equal(s.polls.length, 2, "Resume polling only after the entire hint succeeds");
    s.polls[1].resolve({ changedKeys: [] });
    await settle();
    assert.equal(s.snapshots.length, 2, "Empty events after success do not repeat recovery");
    assert.equal(s.listings.length, 1);
    assertReadsOnly(s);
  });
}

test("status success followed by media failure retains the content recovery", async t => {
  const s = fixture(t);
  await beginHint(s);
  resolveSnapshot(s, 0);
  await settle();
  assert.equal(s.state.status.recording, true, "Control state publishes before media completes");
  s.listings[0].reject(new Error("synthetic media failure"));
  await settle();
  if (s.polls[1]) { s.polls[1].resolve({ changedKeys: [] }); await settle(); }
  assert.equal(s.timers[0]?.delay, 1000, "Media failure must retain the consumed hint");
  s.timers[0].resolve();
  await settle();
  // A retry may renew the authoritative snapshot before retrying its media list.
  if (s.snapshots[1]) { resolveSnapshot(s, 1); await settle(); }
  assert.equal(s.listings.length, 2);
  s.listings[1].resolve({ items: [NEW] });
  await settle();
  assert.deepEqual(Array.from(s.state.media), [NEW]);
  assert.equal(s.state.mediaLoadStatus, "COMPLETE");
  assert.equal(s.ui.mediaPreviewDialog.open, true);
  assertReadsOnly(s);
});

for (const reuseId of [false, true]) {
  for (const outcome of ["resolve", "reject"]) {
    test(`retired event listing ${outcome} never publishes or retries into ${reuseId ? "same-ID" : "different-ID"} replacement`, async t => {
      const s = fixture(t);
      await beginHint(s);
      resolveSnapshot(s, 0);
      await settle();
      s.cancelEventLoop();
      s.state.refreshGeneration += 1;
      s.state.session = { id: reuseId ? "synthetic-session" : "synthetic-replacement" };
      s.state.status = { recording: false, owner: "replacement" };
      s.state.media = [OLD];
      const errors = s.errors.length;
      if (outcome === "resolve") s.listings[0].resolve({ items: [NEW] });
      else s.listings[0].reject(new Error("retired listing failed"));
      await settle();
      assert.equal(s.listings.length, 1, "The retired listing has no authority to read the replacement camera");
      assert.equal(s.state.mediaRefreshPromise, null);
      assert.deepEqual(Array.from(s.state.media), [OLD]);
      assert.equal(s.state.status.owner, "replacement");
      assert.equal(s.errors.length, errors);
      assertReadsOnly(s);
    });
  }
}

for (const failureKind of ["snapshot", "media", "poll"]) {
  test(`${failureKind} failures use capped backoff and success resets the next recovery`, async t => {
    const s = fixture(t);
    if (failureKind === "poll") s.startEventLoop();
    else await beginHint(s);
    for (const [index, delay] of [1000, 2000, 5000, 5000].entries()) {
      if (failureKind === "poll") s.polls[index].reject(new Error("synthetic event failure"));
      else {
        resolveSnapshot(s, index, failureKind === "snapshot" ? { failed: "status" } : {});
        await settle();
        if (failureKind === "media") s.listings[index].reject(new Error("synthetic listing failure"));
      }
      await settle();
      assert.equal(s.timers[index]?.delay, delay);
      const count = s.requests.length;
      await settle();
      assert.equal(s.requests.length, count, "No retries before the controlled timer is released");
      if (failureKind !== "poll") assert.equal(s.polls.length, 1, "Keep the one consumed event until recovery");
      s.timers[index].resolve();
      await settle();
    }
    if (failureKind === "poll") {
      s.polls[4].resolve({ changedKeys: [] });
      await settle();
      s.polls[5].reject(new Error("new event failure"));
    } else {
      resolveSnapshot(s, 4);
      await settle();
      s.listings.at(-1).resolve({ items: [NEW] });
      await settle();
      s.polls[1].resolve({ changedKeys: ["iso"] });
      await settle();
      resolveSnapshot(s, 5, { failed: "info" });
    }
    await settle();
    assert.equal(s.timers[4]?.delay, 1000, "A fully successful cycle resets capped backoff");
    assertReadsOnly(s);
  });
}

for (const busyOwner of ["busy", "mediaDownload", "mediaUpload", "mediaBatchOwner", "mediaAutoImportReading", "recordingCommandOwner"]) {
  test(`event recovery waits for ${busyOwner} ownership before any authoritative read`, async t => {
    const s = fixture(t);
    s.state[busyOwner] = busyOwner === "recordingCommandOwner" ? { session: s.state.session } : true;
    await beginHint(s);
    assert.equal(s.snapshots.length, 0);
    assert.equal(s.timers[0]?.delay, 25, "Use the existing interaction wait");
    s.state[busyOwner] = false;
    s.timers[0].resolve();
    await settle();
    resolveSnapshot(s, 0);
    await settle();
    s.listings[0].resolve({ items: [NEW] });
    await settle();
    assert.equal(s.state.status.recording, true);
    assertReadsOnly(s);
  });
}

test("a newer interaction retires an in-flight snapshot without losing its contents hint", async t => {
  const s = fixture(t);
  await beginHint(s);
  s.beginCameraInteraction();
  resolveSnapshot(s, 0);
  await settle();
  assert.equal(s.state.status.recording, false);
  assert.equal(s.listings.length, 0);
  assert.equal(s.timers[0]?.delay, 1000);
  s.timers[0].resolve();
  await settle();
  assert.equal(s.snapshots.length, 1, "Busy interaction still blocks the retry");
  assert.equal(s.timers[1]?.delay, 25);
  s.state.busy = false;
  s.timers[1].resolve();
  await settle();
  resolveSnapshot(s, 1, { model: "after interaction" });
  await settle();
  s.listings[0].resolve({ items: [NEW] });
  await settle();
  assert.equal(s.state.info.model, "after interaction");
  assert.deepEqual(Array.from(s.state.media), [NEW]);
  assertReadsOnly(s);
});

test("superseded event snapshot cannot erase a newer manual refresh or its recording evidence", async t => {
  const s = fixture(t);
  await beginHint(s);
  const manual = s.refreshSession();
  resolveSnapshot(s, 1, { recording: false, model: "manual" });
  assert.equal(await manual, true);
  const recovery = { session: s.state.session, direction: "stop", acknowledged: false };
  s.state.recordingRecovery = recovery;
  resolveSnapshot(s, 0);
  await settle();
  assert.equal(s.state.info.model, "manual");
  assert.equal(s.state.recordingRecovery, recovery);
  assert.equal(s.listings.length, 0);
  s.timers[0].resolve();
  await settle();
  resolveSnapshot(s, 2);
  await settle();
  s.listings[0].resolve({ items: [NEW] });
  await settle();
  assert.equal(s.state.recordingRecovery, recovery, "Read-only recovery cannot confirm an unacknowledged REC command");
  assertReadsOnly(s);
});

for (const outcome of ["resolve", "reject"]) {
  test(`cancelling polling retires an in-flight event snapshot ${outcome}`, async t => {
    const s = fixture(t);
    await beginHint(s);
    s.cancelEventLoop();
    resolveSnapshot(s, 0, outcome === "reject" ? { failed: "status" } : {});
    await settle();
    assert.equal(s.state.status.recording, false);
    assert.equal(s.state.info.model, "old");
    assert.equal(s.renders.length, 0);
    assert.equal(s.errors.length, 0);
    assert.equal(s.listings.length, 0);
    assert.equal(s.timers.length, 0);
  });

  test(`same-ID replacement rejects late event poll ${outcome} without new reads or feedback`, async t => {
    const s = fixture(t, { ignorePollAbort: true });
    s.startEventLoop();
    s.state.session = { id: "synthetic-session" };
    if (outcome === "resolve") s.polls[0].resolve({ changedKeys: ["contents"] });
    else s.polls[0].reject(new Error("retired event failure"));
    await settle();
    assert.equal(s.snapshots.length, 0);
    assert.equal(s.polls.length, 1);
    assert.equal(s.errors.length, 0);
    assert.equal(s.timers.length, 0);
  });
}

for (const phase of ["poll", "snapshot", "backoff", "busy"]) {
  test(`restarting during ${phase} discards old hints and preserves the new event owner`, async t => {
    const s = fixture(t, { ignorePollAbort: true });
    if (phase === "busy") s.state.busy = true;
    s.startEventLoop();
    if (phase !== "poll") {
      s.polls[0].resolve({ changedKeys: ["contents"] });
      await settle();
    }
    if (phase === "backoff") {
      resolveSnapshot(s, 0, { failed: "status" });
      await settle();
    }
    s.state.busy = false;
    s.startEventLoop();
    const controller = s.state.eventController;
    const errors = s.errors.length;
    if (phase === "poll") s.polls[0].resolve({ changedKeys: ["contents"] });
    if (phase === "snapshot") resolveSnapshot(s, 0);
    if (phase === "backoff" || phase === "busy") s.timers[0].resolve();
    await settle();
    assert.equal(s.state.eventController, controller, "Old finally cannot clear the new controller");
    assert.equal(s.errors.length, errors);
    assert.equal(s.listings.length, 0);
    const snapshotCount = s.snapshots.length;
    s.polls[1].resolve({ changedKeys: [] });
    await settle();
    assert.equal(s.snapshots.length, snapshotCount, "A new empty event must not inherit the old hint");
    s.polls[2].resolve({ changedKeys: ["iso"] });
    await settle();
    resolveSnapshot(s, snapshotCount);
    await settle();
    assert.equal(s.state.status.recording, true);
    assert.equal(s.listings.length, 0, "The new status-only hint must not inherit old contents");
    assertReadsOnly(s);
  });
}

for (const mediaState of ["not loaded", "unsupported", "status-only", "empty"]) {
  test(`${mediaState} event control sends only the needed reads`, async t => {
    const s = fixture(t);
    if (mediaState === "not loaded") s.state.mediaLoaded = false;
    await beginHint(s, mediaState === "empty" ? [] : mediaState === "status-only" ? ["iso"] : ["contents"]);
    if (mediaState !== "empty") {
      const caps = capabilities();
      if (mediaState === "unsupported") caps.supported = ["EVENT_POLLING"];
      resolveSnapshot(s, 0, { caps });
      await settle();
      assert.equal(s.state.status.recording, true);
    }
    assert.equal(s.snapshots.length, mediaState === "empty" ? 0 : 1);
    assert.equal(s.listings.length, 0);
    assert.equal(s.timers.length, 0);
    assert.equal(s.polls.length, 2);
    assertReadsOnly(s);
  });
}

test("cancelling event recovery preserves an admitted same-session listing and preview", async t => {
  const s = fixture(t);
  await beginHint(s);
  resolveSnapshot(s, 0);
  await settle();
  s.cancelEventLoop();
  s.listings[0].resolve({ items: [NEW] });
  await settle();
  assert.deepEqual(Array.from(s.state.media), [NEW]);
  assert.equal(s.ui.mediaPreviewDialog.open, true);
  assert.equal(s.timers.length, 0);
  assert.equal(s.polls.length, 1);
});

test("same-ID replacement during a media busy wait cannot inherit the old listing", async t => {
  const s = fixture(t);
  s.state.busy = true;
  const old = s.refreshMedia({ preservePreview: true });
  assert.equal(s.timers[0]?.delay, 25);
  s.state.session = { id: "synthetic-session" };
  s.state.busy = false;
  s.timers[0].resolve();
  assert.equal(await old, false);
  assert.equal(s.listings.length, 0);
  assert.equal(s.state.mediaRefreshPromise, null);
});

for (const changed of ["interaction", "scope"]) {
  test(`same-session media ${changed} change retains the existing listing retry contract`, async t => {
    const s = fixture(t);
    const listing = s.refreshMedia({ preservePreview: true });
    if (changed === "interaction") {
      s.beginCameraInteraction();
      s.state.busy = false;
    } else s.state.mediaScope = "all";
    s.listings[0].resolve({ items: [OLD] });
    await settle();
    assert.equal(s.listings.length, 2);
    assert.equal(s.state.mediaLoadStatus, "LOADING");
    s.listings[1].resolve({ items: [NEW] });
    assert.equal(await listing, true);
    assert.deepEqual(Array.from(s.state.media), [NEW]);
    assert.equal(s.ui.mediaPreviewDialog.open, true);
    assertReadsOnly(s);
  });
}

for (const reuseId of [false, true]) {
  for (const outcome of ["resolve", "reject"]) {
    for (const order of ["old first", "replacement first"]) {
      test(`replacement listing survives old ${outcome} (${reuseId ? "same-ID" : "different-ID"}, ${order})`, async t => {
        const s = fixture(t);
        const old = s.refreshMedia({ preservePreview: true });
        s.state.session = { id: reuseId ? "synthetic-session" : "synthetic-replacement" };
        s.state.mediaLoaded = false;
        const current = s.refreshMedia();
        const currentOwner = s.state.mediaRefreshPromise;
        assert.equal(s.listings.length, 2, "Explicit replacement request must not wait for the retired read");
        assert.equal(s.ui.mediaPreviewDialog.open, false, "Explicit refresh retains preview-close behavior");
        if (order === "replacement first") {
          s.listings[1].resolve({ items: [NEW] });
          assert.equal(await current, true, "Replacement completes without waiting for the old read");
        }
        if (outcome === "resolve") s.listings[0].resolve({ items: [OLD] });
        else s.listings[0].reject(new Error("retired listing failed"));
        assert.equal(await old, false);
        assert.equal(s.errors.length, 0);
        if (order === "old first") {
          assert.equal(s.state.mediaRefreshPromise, currentOwner, "Old finalizer cannot clear the new promise");
          assert.equal(s.state.mediaLoaded, false);
          s.listings[1].resolve({ items: [NEW] });
          assert.equal(await current, true);
        }
        assert.deepEqual(Array.from(s.state.media), [NEW]);
        assert.equal(s.state.mediaRefreshPromise, null);
        assertReadsOnly(s);
      });
    }
  }
}
