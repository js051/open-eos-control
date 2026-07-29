"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const localVideo = require(path.join(__dirname, "..", "open_eos_bridge", "static", "local-video.js"));

assert.deepEqual(
  localVideo.supportState({ secureContext: false, mediaDevices: {} }),
  { available: false, reason: "INSECURE_CONTEXT" },
);
assert.deepEqual(
  localVideo.supportState({ secureContext: true, mediaDevices: {} }),
  { available: false, reason: "MEDIA_DEVICES_UNAVAILABLE" },
);
assert.deepEqual(
  localVideo.supportState({
    secureContext: true,
    mediaDevices: { getUserMedia() {}, enumerateDevices() {} },
  }),
  { available: true, reason: null },
);

assert.deepEqual(localVideo.buildConstraints("capture-card"), {
  audio: false,
  video: {
    width: { ideal: 3840 },
    height: { ideal: 2160 },
    frameRate: { ideal: 30, max: 60 },
    deviceId: { exact: "capture-card" },
  },
});
assert.equal("deviceId" in localVideo.buildConstraints().video, false);

(async () => {
  const inputs = await localVideo.enumerateInputs({
    async enumerateDevices() {
      return [
        { kind: "audioinput", deviceId: "microphone", label: "Microphone" },
        { kind: "videoinput", deviceId: "camera-a", label: "Capture card" },
        { kind: "videoinput", deviceId: "", label: "" },
      ];
    },
  });
  assert.deepEqual(inputs, [
    { deviceId: "camera-a", label: "Capture card", index: 1 },
    { deviceId: "", label: "", index: 2 },
  ]);

  let requested = null;
  let stopped = false;
  const track = {
    getSettings: () => ({
      width: 1920,
      height: 1080,
      frameRate: 59.94,
      aspectRatio: 16 / 9,
      deviceId: "private-device-id",
      groupId: "private-group-id",
    }),
    stop: () => { stopped = true; },
  };
  const stream = {
    getVideoTracks: () => [track],
    getTracks: () => [track],
  };
  const started = await localVideo.start({
    async getUserMedia(constraints) {
      requested = constraints;
      return stream;
    },
  }, "camera-a");
  assert.deepEqual(requested, localVideo.buildConstraints("camera-a"));
  assert.equal(started.stream, stream);
  assert.equal(started.track, track);
  assert.deepEqual(started.settings, {
    width: 1920,
    height: 1080,
    frameRate: 59.94,
    aspectRatio: 16 / 9,
  });
  assert.equal(JSON.stringify(started.settings).includes("private"), false);
  localVideo.stop(stream);
  assert.equal(stopped, true);

  let emptyStreamStopped = false;
  await assert.rejects(
    localVideo.start({
      async getUserMedia() {
        return {
          getVideoTracks: () => [],
          getTracks: () => [{ stop: () => { emptyStreamStopped = true; } }],
        };
      },
    }),
    { name: "NotFoundError" },
  );
  assert.equal(emptyStreamStopped, true);

  assert.deepEqual(localVideo.safeTrackSettings({
    width: Number.NaN,
    resizeMode: "crop-and-scale".repeat(8),
    facingMode: "environment",
    deviceId: "private-device-id",
    groupId: "private-group-id",
    unknown: "not-diagnostic-data",
  }), {
    resizeMode: "crop-and-scalecrop-and-scalecrop-and-scalecrop-and-scalecrop-and",
    facingMode: "environment",
  });

  assert.equal(localVideo.errorCode({ name: "NotAllowedError" }), "NOT_ALLOWED");
  assert.equal(localVideo.errorCode({ name: "NotReadableError" }), "NOT_READABLE");
  assert.equal(localVideo.errorCode({ name: "LocalVideoPlaybackError" }), "PLAYBACK");
  assert.equal(localVideo.errorCode(new Error("unknown")), "LOCAL_VIDEO_ERROR");

  const rolling = localVideo.rollingFps([0, 100, 200, 900], 1000, 1000);
  assert.deepEqual(rolling.timestamps, [0, 100, 200, 900, 1000]);
  assert.equal(rolling.fps, 4);
  assert.deepEqual(localVideo.rollingFps([], 1000), { timestamps: [1000], fps: 0 });
  assert.throws(() => localVideo.rollingFps([], Number.NaN), RangeError);
  assert.throws(() => localVideo.rollingFps([], 1000, 0), RangeError);

  assert.equal(localVideo.presentedFrameCount({
    getVideoPlaybackQuality: () => ({ totalVideoFrames: 42 }),
    webkitDecodedFrameCount: 99,
  }), 42);
  assert.equal(localVideo.presentedFrameCount({ webkitDecodedFrameCount: 21 }), 21);
  assert.equal(localVideo.presentedFrameCount({}), null);
  const frameCount = localVideo.rollingFrameCount(
    [{ at: 0, frames: 10 }, { at: 500, frames: 25 }],
    1000,
    40,
    1000,
  );
  assert.deepEqual(frameCount.samples, [
    { at: 0, frames: 10 },
    { at: 500, frames: 25 },
    { at: 1000, frames: 40 },
  ]);
  assert.equal(frameCount.fps, 30);
  assert.deepEqual(localVideo.rollingFrameCount([], 1000, null), { samples: [], fps: null });
  assert.equal(localVideo.rollingFrameCount([{ at: 0, frames: 20 }], 1000, 10).fps, null);
  assert.throws(() => localVideo.rollingFrameCount([], Number.NaN, 0), RangeError);
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
