"use strict";
// Extract real app callbacks; exercise actual queue/evidence modules with
// synthetic camera responses, explicit deferred boundaries, and inert DOM nodes.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaMutations = require("../open_eos_bridge/static/media-mutations.js");
const mediaBatch = require("../open_eos_bridge/static/media-batch.js");
const mediaTransfer = require("../open_eos_bridge/static/media-transfer.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, name);
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function element() {
  return { value: "rating:3", hidden: false, disabled: false, textContent: "", dataset: {}, children: [],
    replaceChildren() { this.children = []; }, append(...children) { this.children.push(...children); },
    querySelectorAll() { return []; }, setAttribute() {} };
}
function fixture(options = {}) {
  const items = ["a", "b", "gone"].map((id) => ({ id, name: "SYNTHETIC.JPG", kind: "image", archived: false, rating: 0 }));
  const selection = mediaBatch.createSelection(); selection.replaceLoaded(items); selection.setMany(items.map((item) => item.id), true);
  const state = { session: { id: "session-one" }, media: items.slice(0, 2), mediaSelection: selection,
    mediaMutationOwner: null, mediaMutation: null, mediaDetailsItem: null, busy: false,
    mediaListingOwner: { cancel() { calls.listCancel += 1; } } };
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const calls = { writes: [], renders: 0, toasts: [], listCancel: 0 };
  const disabled = new Set();
  const context = vm.createContext({ state, ui, mediaMutations, AbortController, RangeError,
    document: { createElement: element },
    t: (key, values = {}) => `${key}:${JSON.stringify(values)}`,
    featureSupported: (feature) => !disabled.has(feature),
    mediaMetadataSupported: () => true,
    displayedMedia: () => [state.media[0]].filter(Boolean),
    renderMedia: () => { calls.renders += 1; selection.replaceLoaded(state.media); },
    renderAvailability: () => { calls.renders += 1; },
    renderMediaDetails: () => { calls.renders += 1; },
    renderMediaPreviewNavigation: () => { calls.renders += 1; },
    showToast: (...args) => calls.toasts.push(args),
    api: async (url, request) => {
      calls.writes.push({ url, request });
      if (options.reply) return options.reply(url, request, context, calls.writes.length);
      const id = decodeURIComponent(url.split("/").at(-2));
      const field = { rating: "rating", rotation: "rotationDegrees", archive: "archived", protection: "protected" }[url.split("/").at(-1)];
      return { id, [field]: request.json.value ?? request.json.degrees ?? request.json.enabled };
    },
  });
  vm.runInContext(["selectedMediaMutation", "renderMediaMutationControls", "ownsMediaMutation", "renderMediaMutation", "cancelMediaMutation", "runMediaMutation", "updateSelectedMediaMetadata", "updateMediaMetadata", "replaceMediaItem", "mediaTransferActive", "cameraInteractionBusy"].map(extract).join("\n"), context);
  return { context, state, ui, calls, items, disabled };
}

for (const [suffix, value, field] of [["protection", true, "protected"], ["protection", false, "protected"],
  ["rating", 0, "rating"], ["rating", 5, "rating"], ["rotation", 0, "rotationDegrees"], ["rotation", 270, "rotationDegrees"],
  ["archive", true, "archived"], ["archive", false, "archived"]]) {
  test(`real batch control sends exact ${suffix}=${value}, preserving hidden and unloaded rows`, async () => {
    const c = fixture(); c.ui.mediaMutationOperation.value = `${suffix}:${value}`;
    await c.context.updateSelectedMediaMetadata();
    assert.deepEqual(c.calls.writes.map((call) => call.url), [`/v1/session/session-one/media/a/${suffix}`, `/v1/session/session-one/media/b/${suffix}`]);
    assert.ok(c.calls.writes.every((call) => call.request.method === "PUT" && call.request.redirect === "error" && call.request.responseType === "mediaMetadata"));
    assert.deepEqual(Array.from(c.state.mediaMutation.queue.results, (row) => row.status), ["confirmed", "confirmed", "not-started"]);
    assert.equal(c.state.mediaMutation.queue.results[1].hidden, true);
    assert.equal(c.state.mediaMutation.queue.results[2].loaded, false);
    assert.equal(c.state.media[0][field], value);
    assert.equal(c.state.mediaMutationOwner, null);
    c.context.renderMediaMutation();
    assert.equal(c.ui.mediaMutationList.children.length, 3);
    assert.ok(c.ui.mediaMutationList.children[2].children[3].textContent.includes("mediaMutationUnavailable"));
  });
}

test("cancellation keeps prior confirmed values and blocks every remaining exact ID", async () => {
  const gate = deferred();
  const c = fixture({ reply: async (url, request, context, count) => count === 1 ? { id: "a", rating: 3 } : gate.promise });
  const pending = c.context.updateSelectedMediaMetadata();
  while (c.calls.writes.length < 2) await Promise.resolve();
  c.context.cancelMediaMutation();
  assert.equal(c.state.media[0].rating, 3);
  gate.reject(Object.assign(new Error("Synthetic cancellation"), { name: "AbortError" }));
  await pending;
  assert.deepEqual(Array.from(c.state.mediaMutation.queue.results, (row) => row.status), ["confirmed", "uncertain", "not-started"]);
  assert.equal(c.calls.writes.length, 2);
});

test("late same-owner exact readback confirms cancelled in-flight write, but never starts later rows", async () => {
  const gate = deferred(), c = fixture({ reply: () => gate.promise });
  const pending = c.context.updateSelectedMediaMetadata();
  c.context.cancelMediaMutation(); gate.resolve({ id: "a", rating: 3 }); await pending;
  assert.deepEqual(Array.from(c.state.mediaMutation.queue.results, (row) => row.status), ["confirmed", "not-started", "not-started"]);
  assert.equal(c.calls.writes.length, 1);
});

test("session replacement with reused session ID cannot publish stale data, UI, toast or later writes", async () => {
  const gate = deferred(), c = fixture({ reply: () => gate.promise });
  const pending = c.context.updateSelectedMediaMetadata();
  const oldBatch = c.state.mediaMutation;
  c.state.session = { id: "session-one" };
  c.state.mediaMutationOwner = null; c.state.mediaMutation = null;
  c.state.media = [{ id: "a", rating: 0, name: "NEW.JPG" }];
  const renders = c.calls.renders;
  gate.resolve({ id: "a", rating: 3 }); await pending;
  assert.equal(c.calls.renders, renders); assert.equal(c.calls.toasts.length, 0);
  assert.equal(c.state.media[0].rating, 0); assert.equal(c.state.mediaMutation, null);
  assert.equal(c.calls.writes.length, 1);
  assert.equal(oldBatch.queue.results[0].status, "running", "retired owner never republishes an old snapshot");
});

test("changed selection and loaded list cannot rewrite a frozen admitted roster", async () => {
  const gate = deferred(), c = fixture({ reply: (url, _, __, count) => count === 1 ? gate.promise : Promise.resolve({ id: "b", rating: 3 }) });
  const pending = c.context.updateSelectedMediaMetadata();
  c.state.mediaSelection.clear();
  c.state.media.push({ id: "injected", name: "EXTRA.JPG" });
  c.state.mediaSelection.replaceLoaded(c.state.media); c.state.mediaSelection.setSelected("injected", true);
  gate.resolve({ id: "a", rating: 3 }); await pending;
  assert.deepEqual(c.calls.writes.map((call) => decodeURIComponent(call.url.split("/").at(-2))), ["a", "b"]);
  assert.deepEqual(Array.from(c.state.mediaMutation.queue.results, (row) => row.id), ["a", "b", "gone"]);
});

test("capability loss before next dispatch and unknown archive rows do not send", async () => {
  const c = fixture({ reply: async (_, __, ___, count) => { if (count === 1) c.disabled.add("MEDIA_RATING"); return { id: "a", rating: 3 }; } });
  await c.context.updateSelectedMediaMetadata();
  assert.equal(c.calls.writes.length, 1);
  assert.deepEqual(Array.from(c.state.mediaMutation.queue.results, (row) => row.status), ["confirmed", "not-started", "not-started"]);
  const archive = fixture(); archive.ui.mediaMutationOperation.value = "archive:true";
  archive.state.media[0].archived = null;
  await archive.context.updateSelectedMediaMetadata();
  assert.deepEqual(archive.calls.writes.map((call) => call.url), ["/v1/session/session-one/media/b/archive"]);
});

test("every post-dispatch error or malformed ACK stops without success UI or optimistic metadata", async () => {
  for (const reply of [async () => { throw new Error("Synthetic HTTP error"); }, async () => ({ ok: true }),
    async () => ({ id: "wrong", rating: 3 }), async () => ({ id: "a", rating: true }), async () => ({ id: "a", rating: 2 })]) {
    const c = fixture({ reply }); await c.context.updateSelectedMediaMetadata();
    assert.equal(c.calls.writes.length, 1); assert.equal(c.state.media[0].rating, 0);
    assert.equal(c.state.mediaMutation.queue.results[0].status, "uncertain");
    assert.ok(c.calls.toasts.at(-1)[0].startsWith("mediaMutationUncertain"));
  }
});

test("single-file details use the same strict confirmation and cannot accept unrelated response fields", async () => {
  const c = fixture({ reply: async () => ({ id: "a", rating: 3, protected: true, name: "WRONG.JPG" }) });
  c.state.mediaDetailsItem = c.items[0];
  await c.context.updateMediaMetadata("MEDIA_RATING", "rating", { value: 3 });
  assert.equal(c.calls.writes.length, 1); assert.equal(c.state.mediaDetailsItem.rating, 3);
  assert.equal(c.state.mediaDetailsItem.name, "SYNTHETIC.JPG"); assert.equal(c.state.mediaDetailsItem.protected, undefined);
  assert.equal(c.state.mediaMutation.queue.results[0].status, "confirmed");
});

test("the real API path preserves integral-float, exponent and rounded fractional tokens without retry", async () => {
  for (const literal of ["3.0", "3e0", "3.00000000000000000001"]) {
    const c = fixture(); let fetches = 0;
    Object.assign(c.context, { Headers, mediaTransfer,
      fetch: async () => { fetches += 1; return new Response(`{"id":"a","rating":${literal}}`); },
    });
    vm.runInContext(extract("api"), c.context);
    await c.context.updateSelectedMediaMetadata();
    assert.equal(fetches, 1, literal); assert.equal(c.state.mediaMutation.queue.results[0].status, "uncertain", literal);
    assert.equal(c.state.media[0].rating, 0, literal);
  }
});

test("whole batch rejects above limit without dispatch, listing cancellation or owner allocation", async () => {
  const c = fixture();
  const rows = Array.from({ length: 1001 }, (_, index) => ({ id: String(index), item: { id: String(index) }, loaded: true }));
  await c.context.runMediaMutation(rows, mediaMutations.operation("rating", 3));
  assert.equal(c.calls.writes.length, 0); assert.equal(c.calls.listCancel, 0); assert.equal(c.state.mediaMutationOwner, null);
  assert.ok(c.calls.toasts[0][0].startsWith("mediaMutationLimit"));
});

test("all fourteen choices are reachable and bilingual; no delete or transport option added", () => {
  const html = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/index.html"), "utf8");
  const choices = html.slice(html.indexOf('<select id="media-mutation-operation"'), html.indexOf('</select>', html.indexOf('<select id="media-mutation-operation"')));
  assert.equal((choices.match(/<option /g) || []).length, 14);
  assert.ok(!choices.includes('value="delete'));
  for (const key of choices.matchAll(/data-i18n="([^"]+)"/g)) {
    assert.equal((source.match(new RegExp(`^\\s+${key[1]}:`, "gm")) || []).length, 2, key[1]);
  }
  assert.ok(html.indexOf('/app/media-mutations.js') < html.indexOf('/app/app.js'));
});

test("real owner respects each explicit writable=false veto without extra metadata reads", async () => {
  for (const [suffix, target] of [["protection", true], ["rating", 3], ["rotation", 90], ["archive", true]]) {
    const c = fixture(); c.state.media[0][`${suffix}Writable`] = false;
    c.ui.mediaMutationOperation.value = `${suffix}:${target}`;
    await c.context.updateSelectedMediaMetadata();
    assert.equal(c.state.mediaMutation.queue.results[0].status, "not-started");
    assert.deepEqual(c.calls.writes.map((call) => call.url), [`/v1/session/session-one/media/b/${suffix}`]);
  }
});

const eligibilityFixture = JSON.parse(fs.readFileSync(path.join(__dirname, "../../contracts/media-mutation/v1/media-mutation-eligibility-vectors.json"), "utf8"));
for (const vector of eligibilityFixture.snapshotVectors) {
  test(`real selection refresh uses current loaded qualification: ${vector.id}`, async () => {
    const c = fixture();
    c.state.mediaSelection.clear();
    c.state.mediaSelection.replaceLoaded([vector.selected]);
    c.state.mediaSelection.setSelected(vector.selected.id, true);
    c.state.media = [vector.loaded];
    c.ui.mediaMutationOperation.value = `${vector.operation}:${vector.operation === "rating" ? 3 : true}`;
    await c.context.updateSelectedMediaMetadata();
    assert.equal(c.calls.writes.length > 0, vector.eligible);
    assert.equal(c.state.mediaMutation.queue.results[0].status, "not-started");
  });
}

test("disconnect cancels the roster before any awaited event shutdown, without replaying a late confirmation", async () => {
  const write = deferred(), events = deferred();
  const c = fixture({ reply: () => write.promise });
  const pending = c.context.updateSelectedMediaMetadata();
  Object.assign(c.context, {
    bulbControlLocked: () => false,
    beginCameraInteraction: () => { c.state.busy = true; },
    cancelMediaDownload() {}, stopLiveLoop() {}, stopLocalVideo() {},
    stopEventLoop: () => events.promise,
    resetSession: () => { c.state.session = null; c.state.mediaMutationOwner = null; },
  });
  vm.runInContext(extract("disconnectCamera"), c.context);
  const closing = c.context.disconnectCamera();
  assert.equal(c.state.mediaMutationOwner.controller.signal.aborted, true);
  write.resolve({ id: "a", rating: 3 }); await pending;
  assert.equal(c.calls.writes.length, 1, "the next selected file must not start while event shutdown waits");
  assert.deepEqual(Array.from(c.state.mediaMutation.queue.results, (row) => row.status), ["confirmed", "not-started", "not-started"]);
  c.context.api = async () => null;
  events.resolve(); await closing;
  assert.equal(c.state.session, null);
});
