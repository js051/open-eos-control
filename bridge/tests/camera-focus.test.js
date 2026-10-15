"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const cameraFocus = require("../open_eos_bridge/static/camera-focus.js");
const frame = { left: 0.2, top: 0.3, right: 0.6, bottom: 0.7, status: "FOCUSED", kind: "NORMAL" };
const info = (patch = {}) => ({ frames: [frame], magnification: 1, imageAspectRatio: 1.5, ...patch });
test("camera metadata retains statuses and kinds separately from command acceptance", () => {
  for (const status of ["STANDBY", "FOCUSED", "UNFOCUSED", "SERVO_OFF", "FOCUSING", "UNKNOWN"]) {
    for (const kind of ["GENERIC", "NORMAL", "FACE", "TRACKING"]) {
      assert.equal(cameraFocus.normalize(info({ frames: [{ ...frame, status, kind }] }), 1.5).frames[0].status, status);
    }
  }
});
test("clip finite rectangles to image bounds and omit empty/malformed rectangles", () => {
  const result = cameraFocus.normalize(info({ frames: [
    { ...frame, left: -1, bottom: 3 }, { ...frame, left: 2 }, { ...frame, right: Infinity },
    { ...frame, bottom: NaN }, { ...frame, top: "0.1" }, null,
  ] }), 1.5);
  assert.deepEqual(result.frames, [{ ...frame, left: 0, bottom: 1 }]);
});
test("unknown kind or status never becomes focused", () => {
  const result = cameraFocus.normalize(info({ frames: [{ ...frame, kind: "NEW" }, { ...frame, status: "accepted" }] }), 1.5);
  assert.equal(result.frames[0].status, "UNKNOWN");
  assert.equal(result.frames[1].status, "UNKNOWN");
});
test("reject mismatched aspect, zoom, unbounded frame counts and invalid envelope", () => {
  for (const value of [null, [], {}, info({ magnification: 5 }), info({ magnification: "1" }),
    info({ imageAspectRatio: 1.531 }), info({ imageAspectRatio: NaN }), info({ imageAspectRatio: 0 }),
    info({ frames: Array(513).fill(frame) }), info({ frames: {} })]) {
    assert.equal(cameraFocus.normalize(value, 1.5), null);
  }
  assert.ok(cameraFocus.normalize(info({ imageAspectRatio: 1.529 }), 1.5));
  assert.equal(cameraFocus.normalize(info(), 0), null);
  assert.equal(cameraFocus.normalize(info(), Infinity), null);
  assert.equal(cameraFocus.normalize(info({ frames: Array(512).fill(frame) }), 1.5).frames.length, 512);
});

test("aspect ratio agreement uses relative two-percent tolerance for portrait and landscape", () => {
  assert.ok(cameraFocus.normalize(info({ imageAspectRatio: 1.525 }), 1.5));
  assert.ok(cameraFocus.normalize(info({ imageAspectRatio: 0.5099 }), 0.5));
  assert.equal(cameraFocus.normalize(info({ imageAspectRatio: 0.511 }), 0.5), null);
});
test("bounded streaming JSON supports small and chunked telemetry", async () => {
  assert.deepEqual(await cameraFocus.readResponse(new Response(JSON.stringify(info()))), info());
  const response = new Response(new ReadableStream({ start(controller) {
    controller.enqueue(new TextEncoder().encode('{"frames":'));
    controller.enqueue(new TextEncoder().encode('[],"magnification":1,"imageAspectRatio":1.5}'));
    controller.close();
  } }));
  assert.deepEqual(await cameraFocus.readResponse(response), info({ frames: [] }));
});
test("declared overflow cancels before consuming a body", async () => {
  let cancelled = false;
  const response = new Response(new ReadableStream({ cancel() { cancelled = true; } }), {
    headers: { "Content-Length": "262145" },
  });
  await assert.rejects(cameraFocus.readResponse(response), /size limit/);
  assert.equal(cancelled, true);
});
test("actual chunked overflow cancels even when declared size is absent or misleading", async () => {
  for (const headers of [{}, { "Content-Length": "12" }]) {
    let cancelled = false;
    const response = new Response(new ReadableStream({ start(controller) {
      controller.enqueue(new Uint8Array(128 * 1024));
      controller.enqueue(new Uint8Array(128 * 1024 + 1));
    }, cancel() { cancelled = true; } }), { headers });
    await assert.rejects(cameraFocus.readResponse(response), /size limit/);
    assert.equal(cancelled, true);
  }
});
test("invalid JSON and UTF-8 never produce telemetry", async () => {
  await assert.rejects(cameraFocus.readResponse(new Response('{"frames":')));
  await assert.rejects(cameraFocus.readResponse(new Response(new Uint8Array([255, 255]))));
});
