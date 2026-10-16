"use strict";

// Production audio/lifecycle functions, deterministic clock and deferred external
// I/O. This is software ownership evidence, not browser/device/physical audibility.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");
const rtpAudio = require("../open_eos_bridge/static/rtp-audio.js");
const app = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const start = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(app)?.index;
  assert.notEqual(start, undefined, name);
  return app.slice(start, app.indexOf("\n  }", start) + 4);
}
const names = ["currentRtpAudioStatus", "renderRtpAudio", "ownsRtpAudio", "armRtpAudioWatchdog", "checkRtpAudioProgress",
  "toggleRtpAudio", "pollRtpAudio", "scheduleRtpAudioChunk", "stopRtpAudioSources", "stopRtpAudio"];
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const flush = async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); };
function pcm(generation = 1, frames = 1024, age = 0, bodyGate = null) {
  const values = new ArrayBuffer(frames * 4); // Silence is real PCM, not missing output.
  const headers = new Headers({"content-type": "audio/pcm", "x-open-eos-audio-generation": String(generation),
    "x-open-eos-audio-age-ms": String(age), "x-open-eos-audio-sample-rate": "48000",
    "x-open-eos-audio-channels": "2", "x-open-eos-audio-frames": String(frames)});
  return {ok: true, status: 200, headers, arrayBuffer: () => bodyGate ? bodyGate.promise : Promise.resolve(values), values};
}
function harness({ outputSupported = false, closePending = false } = {}) {
  let now = 10000, timerId = 0;
  const timers = new Map(), contexts = [], requests = [], sources = [], toasts = [], events = {};
  const element = () => ({hidden: false, disabled: false, textContent: "", classList: {toggle() {}},
    setAttribute(name, value) { this[name] = value; }});
  class Context {
    constructor() {
      this.state = "suspended"; this.createdAt = now; this.destination = {};
      this.resumeGate = deferred(); this.closeGate = deferred(); this.closeCount = 0;
      this.stamp = {contextTime: 0, performanceTime: now};
      if (outputSupported) this.getOutputTimestamp = () => this.stamp;
      contexts.push(this);
    }
    get currentTime() { return (now - this.createdAt) / 1000; }
    resume() { return this.resumeGate.promise.then(() => { if (this.state !== "closed") this.state = "running"; }); }
    close() { this.closeCount++; if (closePending) return this.closeGate.promise;
      this.state = "closed"; return Promise.resolve(); }
    createBuffer() { return {copyToChannel() {}}; }
    createBufferSource() {
      const source = {stopped: 0, ended: null, disconnect() {}, connect() {},
        addEventListener(_event, callback) { this.ended = callback; },
        start(at) { this.startAt = at; }, stop() { this.stopped++; this.ended?.(); }};
      sources.push(source); return source;
    }
  }
  const h = { console, Date, Set, Map, Promise, Number, Error, Headers, ArrayBuffer, AbortController, encodeURIComponent,
    localVideo: {supportState: () => ({available: true})}, navigator: {mediaDevices: {}},
    mediaBatch: require("../open_eos_bridge/static/media-batch.js"),
    rtpAudio, performance: {now: () => now}, window: {AudioContext: Context,
      addEventListener(name, fn) { events[name] = fn; }},
    document: {visibilityState: "visible", addEventListener(name, fn) { events[name] = fn; }},
    ui: {rtpAudioButton: element(), rtpAudioStatus: element()},
    readLanguagePreference: () => "en", t: key => key, localPreviewSelected: () => false,
    replaceButtonIcon() {}, captureError: error => error,
    showToast: (message, error) => toasts.push({message, error}),
    api: (url, options) => { const pending = deferred(); requests.push({url, ...options, ...pending}); return pending.promise; },
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, {fn, at: now + delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
  };
  for (const name of ["cancelCameraConnection", "cancelRecordingWait", "suspendMaintenanceForLifecycle", "stopMediaAutoImport", "cancelMediaMutation",
    "releaseHeldAutofocusForLifecycle", "cancelCameraFocusInfo", "refreshMediaDateZone", "renderAvailability",
    "renderMediaAutoImport", "cancelMediaUpload"]) h[name] = () => {};
  vm.createContext(h);
  const start = app.indexOf("  const state = {");
  const state = app.slice(start, app.indexOf("\n  };", start) + 5).replace("const state", "globalThis.state");
  const lifecycleStart = app.indexOf('    document.addEventListener?.("visibilitychange"');
  const lifecycle = app.slice(lifecycleStart, app.indexOf('    window.addEventListener("focus"', lifecycleStart));
  vm.runInContext(`${state}\n${names.map(extract).join("\n")}\n${lifecycle}`, h);
  Object.assign(h.state, {session: {id: "synthetic-owner"}, liveActive: true, activeLiveSource: "CCAPI_RTP",
    status: {raw: {rtpAudio: {advertised: true, available: false, decodingAvailable: true}}}});
  return Object.assign(h, {contexts, requests, sources, toasts, events,
    now: () => now,
    jump(ms) { now += ms; },
    async advance(ms) {
      const end = now + ms;
      while (true) {
        const due = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
        if (!due || due[1].at > end) break;
        now = due[1].at; timers.delete(due[0]); due[1].fn(); await flush();
      }
      now = end; await flush();
    },
    async start() { const pending = h.toggleRtpAudio(); contexts.at(-1).resumeGate.resolve(); await pending; await flush(); },
    async deliver(response) { requests.at(-1).resolve(response); await flush(); },
  });
}

function assertVideoUnaffected(h) { assert.equal(h.state.liveActive, true); assert.equal(h.state.activeLiveSource, "CCAPI_RTP"); }

test("manual enable remains waiting; fresh silent PCM is decoded and scheduled, never physical playback", async () => {
  const h = harness(); await h.start();
  assert.equal(h.ui.rtpAudioStatus.textContent, "cameraAudioWaiting");
  assert.equal(h.sources.length, 0);
  await h.deliver(pcm());
  const proof = h.state.rtpAudioProgress.snapshot(h.now());
  assert.equal(proof.decodedFresh, true); assert.equal(proof.scheduledFrames, 1024);
  assert.equal(proof.outputProgressSupported, false); assert.equal(proof.outputFrames, 0);
  assert.equal(proof.physicalAudibilityConfirmed, false);
  assert.equal(h.ui.rtpAudioStatus.textContent, "cameraAudioOutputUnknown");
  h.sources[0].ended();
  assert.equal(h.state.rtpAudioProgress.snapshot(h.now()).outputFrames, 0);
  h.stopRtpAudio(); assert.equal(h.state.rtpAudioLastAt, null); assertVideoUnaffected(h);
});

for (const stage of ["resume", "GET", "body"]) {
  for (const result of ["resolve", "reject"]) {
    test(`five-second ${stage} deadline retires owner; late ${result} cannot affect explicit replacement`, async () => {
      const h = harness(); let start, oldPromise;
      if (stage === "resume") { start = h.toggleRtpAudio(); oldPromise = h.contexts[0].resumeGate; }
      else {
        await h.start(); oldPromise = h.requests[0];
        if (stage === "body") {
          oldPromise = deferred(); await h.deliver(pcm(1, 1024, 0, oldPromise));
        }
      }
      await h.advance(5000);
      assert.equal(h.state.rtpAudioEnabled, false); assert.equal(h.state.rtpAudioBusy, false);
      assert.equal(h.contexts[0].closeCount, 1); assert.equal(h.sources.length, 0);
      assertVideoUnaffected(h);
      await h.start(); const owner = h.state.rtpAudioOwner; const count = h.requests.length;
      if (result === "reject") oldPromise.reject(new Error("synthetic late failure"));
      else oldPromise.resolve(stage === "GET" ? pcm() : stage === "body" ? new ArrayBuffer(4096) : undefined);
      if (start) await start; await flush();
      assert.equal(h.state.rtpAudioOwner, owner); assert.equal(h.state.rtpAudioEnabled, true);
      assert.equal(h.requests.length, count); assert.equal(h.sources.length, 0);
      h.stopRtpAudio();
    });
  }
}

for (const trigger of ["mute", "hidden", "pagehide", "suspended", "owner replacement", "long pause"]) {
  test(`${trigger} clears queue and intent without stopping video or automatically restarting audio`, async () => {
    const h = harness(); await h.start(); await h.deliver(pcm());
    const oldOwner = h.state.rtpAudioOwner, request = h.requests.at(-1);
    if (trigger === "mute") await h.toggleRtpAudio();
    if (trigger === "hidden") { h.document.visibilityState = "hidden"; h.events.visibilitychange(); }
    if (trigger === "pagehide") h.events.pagehide();
    if (trigger === "suspended") { h.contexts[0].state = "suspended"; h.contexts[0].onstatechange(); }
    if (trigger === "owner replacement") { h.state.session = {id: "synthetic-owner"}; await h.advance(250); }
    if (trigger === "long pause") { h.jump(1500); h.checkRtpAudioProgress(oldOwner); }
    assert.equal(h.state.rtpAudioEnabled, false); assert.equal(h.state.rtpAudioProgress, null);
    assert.equal(h.sources[0].stopped, 1); assert.equal(request.signal.aborted, true);
    const count = h.requests.length;
    h.document.visibilityState = "visible"; h.events.visibilitychange();
    await h.advance(6000); assert.equal(h.requests.length, count); assertVideoUnaffected(h);
    request.resolve(pcm(2)); await flush(); assert.equal(h.sources.length, 1);
  });
}

test("stalled decoded PCM stops even if destination clock keeps advancing", async () => {
  const h = harness({outputSupported: true}); await h.start(); await h.deliver(pcm());
  await h.advance(250); h.contexts[0].stamp = {contextTime: 0.1, performanceTime: h.now()};
  await h.advance(250); assert.equal(h.state.rtpAudioProgress.snapshot(h.now()).outputProgressFresh, true);
  await h.advance(4500); assert.equal(h.state.rtpAudioEnabled, false);
  assert.equal(h.state.rtpAudioError, "cameraAudioStalled"); assertVideoUnaffected(h);
});

test("fresh PCM with frozen supported output timestamps fails closed; missing API does not", async () => {
  for (const outputSupported of [true, false]) {
    const h = harness({outputSupported}); await h.start();
    for (let i = 1; i <= 20; i++) { await h.deliver(pcm(i)); await h.advance(250); }
    assert.equal(h.state.rtpAudioEnabled, !outputSupported);
    if (outputSupported) assert.equal(h.state.rtpAudioError, "cameraAudioOutputStalled");
    assertVideoUnaffected(h); h.stopRtpAudio();
  }
});

test("advancing output timestamp counts only submitted interval overlap; stop-ended adds no evidence", () => {
  const progress = new rtpAudio.PlaybackProgress(1000, true);
  const context = {state: "running", getOutputTimestamp: () => context.stamp, stamp: {contextTime: 10, performanceTime: 1000}};
  progress.observeOutput(context, 1000);
  context.stamp = {contextTime: 10.1, performanceTime: 1100}; progress.observeOutput(context, 1100);
  assert.equal(progress.snapshot(1100).outputFrames, 0, "clock alone is not sample progress");
  progress.decodedAt = 1100;
  progress.scheduled({generation: 1, sampleRate: 48000, sampleFrames: 480}, {startTime: 10.11, nextStart: 10.12, reset: false}, 1100);
  context.stamp = {contextTime: 10.13, performanceTime: 1130}; progress.observeOutput(context, 1130);
  assert.ok(progress.snapshot(1130).outputFrames >= 479);
  const frames = progress.outputFrames; progress.clearQueue();
  context.stamp = {contextTime: 11, performanceTime: 1200}; progress.observeOutput(context, 1200);
  assert.equal(progress.outputFrames, frames);
  context.state = "suspended"; context.stamp = {contextTime: 12, performanceTime: 1300};
  progress.observeOutput(context, 1300); assert.equal(progress.outputFrames, frames);
});

for (const kind of ["expired", "duplicate", "network error", "malformed"]) {
  test(`${kind} PCM fails closed without touching video`, async () => {
    const h = harness(); await h.start(); await h.deliver(pcm());
    if (kind === "expired") await h.deliver(pcm(2, 1024, 1000));
    if (kind === "duplicate") await h.deliver(pcm(1));
    if (kind === "network error") { h.requests.at(-1).reject(new Error("synthetic audio-only error")); await flush(); }
    if (kind === "malformed") { const response = pcm(2); response.arrayBuffer = async () => new ArrayBuffer(0); await h.deliver(response); }
    assert.equal(h.state.rtpAudioEnabled, false); assert.equal(h.sources[0].stopped, 1); assertVideoUnaffected(h);
  });
}

for (const outcome of ["pending", "rejected"]) {
  test(`a ${outcome} context close retains output reservation until platform closure is confirmed`, async () => {
    const h = harness({closePending: true}); await h.start(); await h.deliver(pcm());
    h.stopRtpAudio(); const old = h.contexts[0];
    if (outcome === "rejected") { old.closeGate.reject(new Error("synthetic close failure")); await flush(); }
    await h.toggleRtpAudio(); assert.equal(h.contexts.length, 1); assert.equal(h.state.rtpAudioEnabled, false);
    assert.equal(h.ui.rtpAudioButton.disabled, true); assertVideoUnaffected(h);
    old.state = "closed"; old.onstatechange(); await flush();
    assert.ok(h.state.rtpAudioRetirement, "closed state alone does not prove creation-blocking resources were released");
    if (outcome === "pending") {
      old.closeGate.resolve(); await flush(); assert.equal(h.state.rtpAudioRetirement, null);
      await h.start(); assert.equal(h.contexts.length, 2); assert.equal(h.state.rtpAudioEnabled, true); h.stopRtpAudio();
    } else {
      await h.toggleRtpAudio(); assert.equal(h.contexts.length, 1);
    }
  });
}


test("known PCM age is retained through freshness and whole-buffer queue admission", () => {
  const progress = new rtpAudio.PlaybackProgress(1000, false);
  const chunk = {generation: 1, decodedAgeMs: 600, sampleRate: 48000, sampleFrames: 1024};
  progress.decoded(chunk, 1200, 1100);
  assert.equal(progress.decodedAt, 500);
  assert.equal(progress.snapshot(1499).decodedFresh, true);
  assert.equal(progress.snapshot(1500).decodedFresh, false);
  assert.throws(() => progress.scheduled(chunk, {startTime: 0.3, nextStart: 0.4, reset: true}, 1200, 0), /expire/);
});

test("expired pending WebAudio nodes are stopped at one second before the five-second stall", async () => {
  const h = harness({outputSupported: true}); await h.start(); await h.deliver(pcm());
  await h.advance(1000);
  assert.equal(h.sources[0].stopped, 1); assert.equal(h.state.rtpAudioSources.size, 0);
  assert.equal(h.state.rtpAudioEnabled, true, "bounded recovery window still waits for new PCM");
  assert.equal(h.state.rtpAudioProgress.snapshot(h.now()).decodedFresh, false);
  await h.advance(4000); assert.equal(h.state.rtpAudioEnabled, false); assertVideoUnaffected(h);
});

test("output advancement observed on the deadline is evaluated before output-stall failure", () => {
  const progress = new rtpAudio.PlaybackProgress(1000, true);
  progress.lastCheckAt = 5750;
  progress.decodedAt = 5750;
  progress.timestamp = {contextTime: 4.7, performanceTime: 5700};
  progress.intervals = [{start: 4.8, end: 4.9, rate: 48000, expiresAt: 6750}];
  const context = {state: "running", getOutputTimestamp: () => ({contextTime: 4.95, performanceTime: 5950})};
  assert.equal(progress.check(6000, context, true), null);
  assert.equal(progress.snapshot(6000).outputProgressFresh, true);
});

test("future/stale/frozen timestamp and expired interval never become output progress", () => {
  const progress = new rtpAudio.PlaybackProgress(1000, true);
  progress.timestamp = {contextTime: 0, performanceTime: 1000};
  progress.intervals = [{start: 0.1, end: 0.2, rate: 48000, expiresAt: 1100}];
  const context = {state: "running", getOutputTimestamp: () => context.stamp};
  for (const stamp of [{contextTime: 0.15, performanceTime: 1300}, {contextTime: 0.15, performanceTime: 1},
    {contextTime: 0, performanceTime: 1000}, {contextTime: 0.2, performanceTime: 1150}]) {
    context.stamp = stamp; progress.observeOutput(context, 1200);
  }
  assert.equal(progress.snapshot(1200).outputFrames, 0);
});


test("pending PCM expiry between ordinary watchdog ticks gets its own deadline", async () => {
  const h = harness({outputSupported: true}); await h.start();
  await h.advance(125); await h.deliver(pcm(1)); h.sources[0].ended();
  await h.advance(75); await h.deliver(pcm(2));
  await h.advance(924); assert.equal(h.sources[1].stopped, 0);
  await h.advance(1); assert.equal(h.sources[1].stopped, 1);
  assert.equal(h.state.rtpAudioEnabled, true); h.stopRtpAudio();
});


test("continuous 20 ms PCM with ended callbacks cannot postpone the output watchdog", async () => {
  for (const outputSupported of [true, false]) {
    const h = harness({outputSupported}); await h.start();
    for (let generation = 1; generation <= 300 && h.state.rtpAudioEnabled; generation++) {
      await h.deliver(pcm(generation)); h.sources.at(-1).ended(); await h.advance(20);
    }
    assert.equal(h.state.rtpAudioEnabled, !outputSupported);
    if (outputSupported) {
      assert.equal(h.state.rtpAudioError, "cameraAudioOutputStalled");
      assert.equal(h.now(), 15000, "new chunks must not shift the original five-second progress deadline");
    } else {
      assert.equal(h.now(), 16000); assert.equal(h.state.rtpAudioError, null);
    }
    assertVideoUnaffected(h); h.stopRtpAudio();
  }
});
