"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");
const app = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
const at = app.indexOf("  function handleLocalVideoFrame(");
const body = app.slice(at, app.indexOf("\n  }", at) + 4);
function harness() {
  const state = {localVideoActive: true, localVideoGeneration: 1, localVideoFrameTimes: [],
    localVideoFrameSamples: [], localVideoLastRenderAt: 0, lastFrameAt: null};
  let frames = 10, clock = 1000;
  const context = {state, ui: {localVideo: {getVideoPlaybackQuality: () => ({totalVideoFrames: frames})}},
    localVideo: require("../open_eos_bridge/static/local-video.js"), LOCAL_VIDEO_RENDER_INTERVAL_MILLIS: 100,
    Date: class extends Date {constructor() {super(clock);}}, renderMonitoringFrame() {}, renderFrameIndicator() {},
    scheduleLocalVideoFrame() {}};
  vm.createContext(context); vm.runInContext(body, context);
  return {state, run(now, count, presented = false, generation = 1) {
    frames = count; clock = now; context.handleLocalVideoFrame(now, generation, presented);
  }};
}
test("fallback polling cannot refresh lastFrameAt while the native frame count is unchanged", () => {
  const h = harness(); h.run(1000, 10); const first = h.state.lastFrameAt;
  h.run(2000, 10); assert.equal(h.state.lastFrameAt, first);
  h.run(3000, 11); assert.notEqual(h.state.lastFrameAt, first);
});
for (const count of [0, null, undefined, NaN]) {
  test(`fallback count ${count} is not fresh frame evidence`, () => {
    const h = harness(); h.run(1000, count); assert.equal(h.state.lastFrameAt, null);
  });
}
test("native presented callback supplies frame evidence even without a fallback count", () => {
  const h = harness(); h.run(1000, undefined, true); assert.equal(h.state.lastFrameAt, new Date(1000).toISOString());
});
test("retired generation cannot publish a frame timestamp", () => {
  const h = harness(); h.run(1000, 10, true, 0); assert.equal(h.state.lastFrameAt, null);
});
test("fresh evidence during render throttling keeps its own time, not the later poll time", () => {
  const h = harness(); h.run(1000, 10); h.run(1050, 11);
  assert.equal(h.state.lastFrameAt, new Date(1050).toISOString());
  h.run(1200, 11); assert.equal(h.state.lastFrameAt, new Date(1050).toISOString());
});
test("counter reset does not invent a frame but the next observed increment is fresh", () => {
  const h = harness(); h.run(1000, 10); const first = h.state.lastFrameAt;
  h.run(2000, 0); assert.equal(h.state.lastFrameAt, first);
  h.run(3000, 1); assert.equal(h.state.lastFrameAt, new Date(3000).toISOString());
});
test("unknown fallback evidence preserves the last confirmed timestamp", () => {
  const h = harness(); h.run(1000, 10); const first = h.state.lastFrameAt;
  h.run(2000, undefined); assert.equal(h.state.lastFrameAt, first);
});
test("unknown sample cannot make the same known frame counter look new when it returns", () => {
  const h = harness(); h.run(1000, 10); const first = h.state.lastFrameAt;
  h.run(2000, undefined); h.run(3000, 10); assert.equal(h.state.lastFrameAt, first);
  h.run(4000, 11); assert.equal(h.state.lastFrameAt, new Date(4000).toISOString());
});
