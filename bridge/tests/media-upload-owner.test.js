"use strict";
// Real app functions and production upload owner/evidence module. Inert inputs
// and controllable Fetch exercise browser callback boundaries without claiming a
// browser run or physical camera evidence.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaUpload = require("../open_eos_bridge/static/media-upload.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(match, name);
  return source.slice(match.index, source.indexOf("\n  }", match.index) + 4);
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function element() {
  return { hidden: false, disabled: false, textContent: "", dataset: {}, listeners: {}, files: [],
    cloneNode() { return element(); }, replaceWith(next) { this.replacement = next; },
    setAttribute(name, value) { this[name] = value; }, removeAttribute(name) { delete this[name]; },
    addEventListener(name, callback) { this.listeners[name] = callback; }, click() { this.clicked = true; },
    fire(name) { return this.listeners[name]?.(); } };
}
function fixture(options = {}) {
  const calls = { requests: [], toasts: [], operations: [], renders: 0, lists: 0, listCancels: 0, aborts: 0 };
  const state = { session: { id: "synthetic-session" }, token: "", refreshGeneration: 1,
    mediaUpload: null, mediaUploadOwner: null, mediaUploadPicker: null, mediaUploadResult: null,
    media: options.listing || [], busy: false, mediaListingOwner: { cancel() { calls.listCancels += 1; } } };
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const disabled = new Set();
  class Request {
    constructor() { this.gate = deferred(); }
    reply(text = '{"id":"camera:receipt","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":4}', status = 201) {
      this.gate.resolve(new Response([204, 205, 304].includes(status) ? null : text, { status }));
    }
    onerror() { this.gate.reject(new Error("Synthetic transport failure")); }
    ontimeout() { this.onerror(); }
    onabort() { this.gate.reject(Object.assign(new Error("Synthetic cancel"), { name: "AbortError" })); }
    onload() {}
  }
  const context = vm.createContext({ state, ui, mediaUpload, AbortController,
    Headers: options.openError ? class { constructor() { throw new Error("Synthetic setup failure"); } } : Headers,
    fetch(url, request) {
      const pending = new Request();
      calls.requests.push({ method: request.method, url, file: request.body, request, pending });
      request.signal.addEventListener("abort", () => {
        calls.aborts += 1;
        if (!options.lateResponse) pending.onabort();
      });
      if (options.sendError) throw new Error("Synthetic ambiguous dispatch failure");
      return pending.gate.promise;
    },
    FEATURES: { MEDIA_UPLOAD: "MEDIA_UPLOAD", MEDIA_BROWSER: "MEDIA_BROWSER" },
    t: (key, values = {}) => `${key}:${JSON.stringify(values)}`,
    featureSupported: (key) => !disabled.has(key),
    cameraInteractionBusy: () => state.busy || Boolean(state.mediaUpload),
    setOperationState: (...values) => calls.operations.push(values), showToast: (...values) => calls.toasts.push(values),
    renderMediaTransfer: () => { calls.renders += 1; }, renderAvailability: () => { calls.renders += 1; },
    clearScheduledMediaTransferRender() {}, scheduleMediaTransferRender() { calls.renders += 1; },
    refreshMedia: () => { calls.lists += 1; return options.refresh ? options.refresh() : Promise.resolve(true); },
  });
  vm.runInContext(["ownsMediaUpload", "renderMediaUploadOutcome", "openMediaUploadPicker", "finishMediaUpload", "uploadMedia", "sendMediaUpload", "cancelMediaUpload"].map(extract).join("\n"), context);
  function choose(file = { name: "SYNTHETIC.JPG", size: 4, type: "image/jpeg" }) {
    context.openMediaUploadPicker(); const input = ui.mediaUploadInput;
    input.files = [file]; input.fire("change"); return calls.requests.at(-1)?.pending;
  }
  return { context, state, ui, calls, disabled, choose };
}
const tick = () => new Promise(setImmediate);

test("native opening owner dispatches once and exact valid 201 receipt confirms before refresh", async () => {
  const gate = deferred(), c = fixture({ refresh: () => gate.promise });
  const pending = c.choose(); const input = c.ui.mediaUploadInput;
  assert.equal(c.calls.requests.length, 1);
  input.fire("change"); assert.equal(c.calls.requests.length, 1);
  assert.match(c.calls.requests[0].url, /^\/v1\/session\/synthetic-session\/media\?filename=SYNTHETIC.JPG$/);
  pending.reply(); await tick();
  assert.equal(c.state.mediaUploadResult.outcome, "confirmed");
  assert.equal(c.state.mediaUploadResult.receipt.id, "camera:receipt");
  assert.equal(c.calls.lists, 1); assert.equal(c.state.mediaUpload, null);
  gate.resolve(false); await tick();
  assert.equal(c.state.mediaUploadResult.outcome, "confirmed");
  assert.match(c.calls.toasts.at(-1)[0], /^uploaded:/);
});

test("old picker completion after reconnect with reused textual session ID cannot read file metadata or dispatch", async () => {
  const c = fixture(); c.context.openMediaUploadPicker(); const old = c.ui.mediaUploadInput;
  const file = { get name() { throw new Error("Stale file was inspected"); } };
  old.files = [file]; c.state.session = { id: "synthetic-session" };
  old.fire("change"); assert.equal(c.calls.requests.length, 0);
});

test("a second picker retires the first input and its late result cannot consume the new owner", async () => {
  const c = fixture(); c.context.openMediaUploadPicker(); const old = c.ui.mediaUploadInput;
  c.context.openMediaUploadPicker(); const next = c.ui.mediaUploadInput;
  old.files = [{ get name() { throw new Error("Old source inspected"); } }]; old.fire("change");
  assert.notEqual(old, next); assert.equal(c.calls.requests.length, 0);
  next.files = [{ name: "SYNTHETIC.JPG", size: 4 }]; next.fire("change"); next.fire("change");
  assert.equal(c.calls.requests.length, 1);
});

for (const boundary of ["cancel", "retire", "unsupported", "busy"]) {
  test(`picker admission blocks source dispatch after ${boundary}`, async () => {
    const c = fixture(); c.context.openMediaUploadPicker(); const input = c.ui.mediaUploadInput;
    if (boundary === "cancel") input.fire("cancel");
    if (boundary === "retire") c.context.cancelMediaUpload({ silent: true, retire: true });
    if (boundary === "unsupported") c.disabled.add("MEDIA_UPLOAD");
    if (boundary === "busy") c.state.busy = true;
    input.files = [{ name: "SYNTHETIC.JPG", size: 4 }]; input.fire("change");
    assert.equal(c.calls.requests.length, 0);
  });
}

for (const size of [0, null, -1, true, "4", 4.5, 4294967296]) {
  test(`invalid local size ${JSON.stringify(size)} remains not sent`, async () => {
    const c = fixture(); c.choose({ name: "SYNTHETIC.JPG", size });
    assert.equal(c.calls.requests.length, 0); assert.equal(c.state.mediaUploadResult.outcome, "not-sent");
  });
}

for (const listing of [[], [{ id: "same-folder", name: "SYNTHETIC.JPG", sizeBytes: 4 }],
  [{ id: "other-folder", name: "synthetic.jpg", sizeBytes: 4 }], [{ id: "zero", name: "SYNTHETIC.JPG", sizeBytes: 0 }]]) {
  test(`cancel after dispatch never derives success from listing ${JSON.stringify(listing)}`, async () => {
    const c = fixture({ listing }); c.choose(); c.context.cancelMediaUpload();
    assert.equal(c.state.mediaUploadResult.outcome, "uncertain"); assert.equal(c.calls.lists, 0);
    assert.equal(c.calls.requests.length, 1); assert.equal(c.calls.aborts, 1);
    assert.match(c.calls.toasts.at(-1)[0], /^uploadUncertain:/);
    c.context.renderMediaUploadOutcome();
    assert.equal(c.ui.mediaUploadOutcome.dataset.outcome, "uncertain");
  });
}

for (const fault of ["onerror", "ontimeout", "onabort"]) {
  test(`post-dispatch ${fault} stays uncertain without retry or reconciliation reads`, async () => {
    const c = fixture(); const pending = c.choose(); pending[fault](); await tick();
    assert.equal(c.state.mediaUploadResult.outcome, "uncertain");
    assert.equal(c.calls.requests.length, 1); assert.equal(c.calls.lists, 0);
  });
}

test("pre-send setup failure is not-sent, a send exception is uncertain", async () => {
  const before = fixture({ openError: true }); before.choose();
  assert.equal(before.state.mediaUploadResult.outcome, "not-sent"); assert.equal(before.calls.requests.length, 0);
  const after = fixture({ sendError: true }); after.choose();
  assert.equal(after.state.mediaUploadResult.outcome, "uncertain"); assert.equal(after.calls.requests.length, 1);
});

test("cancel after verified response retains the receipt", async () => {
  const c = fixture(); const pending = c.choose();
  pending.reply('{"id":"camera:opaque","name":"synthetic.jpg","kind":"photo","sizeBytes":4}'); await tick();
  c.context.cancelMediaUpload();
  assert.equal(c.state.mediaUploadResult.outcome, "confirmed"); assert.equal(c.state.mediaUploadResult.receipt.id, "camera:opaque");
  assert.equal(c.calls.requests.length, 1);
});

test("late valid same-operation receipt may confirm cancellation, never another owner's response", async () => {
  const c = fixture({ lateResponse: true }); const old = c.choose(); c.context.cancelMediaUpload();
  old.reply(); await tick(); await tick(); assert.equal(c.state.mediaUploadResult.outcome, "confirmed");
  const next = c.choose({ name: "NEXT.JPG", size: 4 }); const owner = c.state.mediaUploadOwner;
  const result = c.state.mediaUploadResult, messages = c.calls.toasts.length;
  old.reply(); await tick(); await tick();
  assert.equal(c.state.mediaUploadOwner, owner); assert.equal(c.state.mediaUpload.controller.signal, c.calls.requests.at(-1).request.signal);
  assert.equal(c.state.mediaUploadResult, result); assert.equal(c.calls.toasts.length, messages);
});

test("retirement preserves uncertainty as a previous-connection record and blocks late events", async () => {
  const c = fixture(); const pending = c.choose(); c.context.cancelMediaUpload({ silent: true, retire: true });
  assert.equal(c.state.mediaUploadResult.outcome, "uncertain");
  c.state.session = { id: "synthetic-session" }; c.context.renderMediaUploadOutcome();
  assert.equal(c.ui.mediaUploadPreviousCamera.hidden, false);
  const messages = c.calls.toasts.length, renders = c.calls.renders;
  pending.reply(); await tick();
  assert.equal(c.state.mediaUploadResult.outcome, "uncertain");
  assert.equal(c.calls.toasts.length, messages); assert.equal(c.calls.renders, renders);
});

test("deferred old refresh cannot publish result/UI after a newer operation", async () => {
  const gate = deferred(), c = fixture({ refresh: () => gate.promise });
  c.choose().reply(); await tick(); const next = c.choose({ name: "NEXT.JPG", size: 4 });
  const result = c.state.mediaUploadResult, messages = c.calls.toasts.length, operations = c.calls.operations.length;
  c.ui.mediaUploadOutcome.dataset.outcome = "new-operation-marker";
  gate.resolve(false); await tick();
  assert.equal(c.state.mediaUpload.controller.signal, c.calls.requests.at(-1).request.signal); assert.equal(c.state.mediaUploadResult, result);
  assert.equal(c.ui.mediaUploadOutcome.dataset.outcome, "new-operation-marker");
  assert.equal(c.calls.toasts.length, messages); assert.equal(c.calls.operations.length, operations);
});

test("receipt survives refresh rejection and repeated load/error events do not repeat UI or reads", async () => {
  const c = fixture({ refresh: () => Promise.reject(new Error("Synthetic listing failure")) });
  const pending = c.choose(); pending.reply(); await tick(); pending.onload(); pending.onerror(); await tick();
  assert.equal(c.state.mediaUploadResult.outcome, "confirmed");
  assert.equal(c.calls.lists, 1); assert.equal(c.calls.toasts.length, 1);
});

for (const status of [200, 202, 204, 400, 409, 500]) {
  test(`HTTP ${status} cannot confirm even with a matching body`, async () => {
    const c = fixture(); c.choose().reply(undefined, status); await tick();
    assert.equal(c.state.mediaUploadResult.outcome, "uncertain"); assert.equal(c.calls.lists, 0);
  });
}

for (const body of ["", "{}", "[]", '{"id":"","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":4}',
  '{"id":"a","name":"WRONG.JPG","kind":"photo","sizeBytes":4}',
  ...["0", "null", "true", '"4"', "4.0", "4e0", "4.00000000000000000001", "5"].map((value) => `{"id":"a","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":${value}}`),
  '{"id":"a","id":"b","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":4}',
  '{"id":"a","name":"SYNTHETIC.JPG","sizeBytes":3,"kind":"photo","sizeBytes":4}']) {
  test(`malformed receipt cannot confirm: ${body}`, async () => {
    const c = fixture(); c.choose().reply(body); await tick();
    assert.equal(c.state.mediaUploadResult.outcome, "uncertain"); assert.equal(c.calls.lists, 0);
  });
}

test("UI strings, script order, review and lifecycle entry points are wired into production", async () => {
  const html = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/index.html"), "utf8");
  for (const key of ["uploadNotSent", "uploadUncertain", "uploaded", "uploadOutcomeTitle", "uploadPreviousCamera", "uploadReviewFiles", "uploadReviewNotice"]) {
    assert.equal((source.match(new RegExp(`^\\s+${key}:`, "gm")) || []).length, 2, key);
  }
  assert.ok(html.indexOf('/app/media-upload.js') < html.indexOf('/app/app.js'));
  assert.match(source, /mediaUploadButton.addEventListener\("click", openMediaUploadPicker\)/);
  assert.match(source, /mediaUploadReview.addEventListener\("click",/);
  assert.match(source, /window.addEventListener\("pagehide", \(\) => \{\s+cancelMediaUpload\(\{ silent: true, retire: true \}\)/);
  assert.match(extract("resetSession"), /cancelMediaUpload\(\{ silent: true, retire: true \}\)/);
  assert.doesNotMatch(extract("cancelMediaUpload"), /state\.media\.some|deleteMedia|uploadMedia\(/);
});

for (const extra of ['"extra":{"x":1,"x":2}', '"extra":[{"x":1,"\\u0078":2}]', '"\\u0069d":"other"']) {
  test(`receipt rejects duplicate keys including nested/escaped keys: ${extra}`, async () => {
    const c = fixture();
    c.choose().reply(`{"id":"a","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":4,${extra}}`); await tick();
    assert.equal(c.state.mediaUploadResult.outcome, "uncertain");
  });
}

test("opaque ID with surrounding whitespace and percent escapes is preserved without normalization", async () => {
  const c = fixture(); c.choose().reply('{"id":"  folder%2Fsynthetic  ","name":"SYNTHETIC.JPG","kind":"unknown","sizeBytes":4}'); await tick();
  assert.equal(c.state.mediaUploadResult.outcome, "confirmed");
  assert.equal(c.state.mediaUploadResult.receipt.id, "  folder%2Fsynthetic  ");
});

test("overlarge or excessively nested receipt is bounded without confirmation", async () => {
  for (const extra of ['"padding":"' + "a".repeat(262144) + '"', '"extra":' + "[".repeat(70) + "0" + "]".repeat(70)]) {
    const c = fixture(); c.choose().reply(`{"id":"a","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":4,${extra}}`); await tick();
    assert.equal(c.state.mediaUploadResult.outcome, "uncertain");
  }
});

test("disconnect retires upload and picker synchronously before any awaited cleanup", async () => {
  const body = extract("disconnectCamera");
  assert.ok(body.indexOf('cancelMediaUpload({ silent: true, retire: true })') < body.indexOf("await "));
});

test("real listing refresh from a confirmed upload cannot mutate media after a newer upload", async () => {
  const gate = deferred(), c = fixture();
  c.state.mediaScope = "recent";
  c.state.media = [{ id: "before", name: "BEFORE.JPG" }];
  c.state.mediaListingOwner = null;
  Object.assign(c.context, {
    AbortController, renderMediaSummary() {}, clearMediaThumbnails() {}, closeMediaPreview() {},
    renderMedia() { c.calls.renders += 1; }, renderMediaPreviewNavigation() {},
    sleep: (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
    captureError: (error) => error,
    api: async () => { c.calls.lists += 1; return gate.promise; },
  });
  vm.runInContext(["refreshMedia", "refreshMediaWhenCurrent"].map(extract).join("\n"), c.context);
  c.choose().reply(); await tick(); assert.equal(c.calls.lists, 1);
  const pendingOwner = c.state.mediaListingOwner;
  const next = c.choose({ name: "NEXT.JPG", size: 4 });
  assert.equal(pendingOwner.outcome, "cancelled");
  const renders = c.calls.renders, operations = c.calls.operations.length;
  gate.resolve({ items: [{ id: "obsolete", name: "STALE.JPG" }] }); await tick();
  assert.equal(c.state.media[0].id, "before"); assert.equal(c.state.mediaUpload.controller.signal, c.calls.requests.at(-1).request.signal);
  assert.equal(c.calls.renders, renders); assert.equal(c.calls.operations.length, operations);
});

test("a newer non-upload camera interaction fences the awaited completion render", async () => {
  const gate = deferred(), c = fixture({ refresh: () => gate.promise });
  c.choose().reply(); await tick(); c.state.refreshGeneration += 1;
  c.ui.mediaUploadOutcome.dataset.outcome = "new-interaction-marker";
  gate.resolve(false); await tick();
  assert.equal(c.ui.mediaUploadOutcome.dataset.outcome, "new-interaction-marker");
});

test("retired response records original attempt evidence without replacing the current snapshot", async () => {
  const c = fixture({ lateResponse: true }), pending = c.choose(), oldOwner = c.state.mediaUploadOwner;
  c.context.cancelMediaUpload({ silent: true, retire: true }); c.state.session = { id: "synthetic-session" };
  const snapshot = c.state.mediaUploadResult, messages = c.calls.toasts.length;
  pending.reply(); await tick(); await tick();
  assert.equal(mediaUpload.outcome(oldOwner), "confirmed");
  assert.equal(c.state.mediaUploadResult, snapshot); assert.equal(snapshot.outcome, "uncertain");
  assert.equal(c.calls.toasts.length, messages);
});

test("actual upload transport rejects redirects and renders honest indeterminate progress", async () => {
  const c = fixture(); c.choose();
  const request = c.calls.requests[0].request;
  assert.equal(request.redirect, "error"); assert.equal(request.cache, "no-store");
  assert.equal(request.credentials, "omit");
  assert.equal(request.signal, c.state.mediaUpload.controller.signal);
  assert.equal(c.state.mediaUpload.progressKnown, false);
  Object.assign(c.context, { renderMediaSelection() {}, renderMediaBatch() {}, formatBytes() { throw new Error("No network byte progress exists"); } });
  c.ui.mediaTransferProgress.value = 0.5;
  vm.runInContext(extract("renderMediaTransfer"), c.context);
  c.context.renderMediaTransfer();
  assert.match(c.ui.mediaTransferStatus.textContent, /^uploadProgressUnknown:/);
  assert.equal(Object.hasOwn(c.ui.mediaTransferProgress, "value"), false);
});

for (const status of [307, 308]) {
  test(`real Fetch HTTP ${status} does not resend the file to its redirect target`, async () => {
    const http = require("node:http");
    const observed = [];
    const server = http.createServer((request, response) => {
      observed.push({ path: request.url, method: request.method });
      request.resume();
      if (request.url === "/synthetic-upload-redirect") {
        response.writeHead(201, { "Content-Type": "application/json" }); response.end('{"id":"bad","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":4}');
      } else { response.writeHead(status, { Location: "/synthetic-upload-redirect" }); response.end(); }
    });
    await new Promise((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
    const c = fixture(); let pendingFetch;
    try {
      c.context.fetch = (url, request) => {
        pendingFetch = fetch(`http://127.0.0.1:${server.address().port}${url}`, request);
        return pendingFetch;
      };
      c.choose(new File(["four"], "SYNTHETIC.JPG", { type: "image/jpeg" }));
      await assert.rejects(pendingFetch); await tick();
      assert.equal(observed.length, 1); assert.equal(observed[0].method, "POST");
      assert.equal(observed.filter((row) => row.path === "/synthetic-upload-redirect").length, 0);
      assert.equal(c.state.mediaUploadResult.outcome, "uncertain"); assert.equal(c.calls.lists, 0);
    } finally {
      server.closeAllConnections(); await new Promise((resolve) => server.close(resolve));
    }
  });
}

test("receipt response bytes are bounded before parsing and invalid UTF-8 never confirms", async () => {
  await assert.rejects(mediaUpload.readResponseText(new Response(new Uint8Array(262145))), /limit/);
  await assert.rejects(mediaUpload.readResponseText(new Response(new Uint8Array([0xff]))));
  const text = '{"id":"a","name":"SYNTHETIC.JPG","kind":"photo","sizeBytes":4}';
  assert.equal(await mediaUpload.readResponseText(new Response(text)), text);
});

test("partial response stream failure cannot create a receipt and releases its reader", async () => {
  let released = false, cancelled = false, reads = 0;
  const response = { body: { getReader() { return {
    async read() { if (reads++ === 0) return { done: false, value: new TextEncoder().encode('{"id":"partial"') }; throw new Error("Synthetic stream failure"); },
    async cancel() { cancelled = true; }, releaseLock() { released = true; },
  }; } } };
  await assert.rejects(mediaUpload.readResponseText(response), /stream failure/);
  assert.equal(released, true); assert.equal(cancelled, true);
});
