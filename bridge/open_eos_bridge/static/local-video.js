(function (root, factory) {
  const localVideo = factory();
  if (typeof module === "object" && module.exports) module.exports = localVideo;
  root.OpenEOSLocalVideo = localVideo;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const DEFAULT_WIDTH = 3840;
  const DEFAULT_HEIGHT = 2160;
  const DEFAULT_FRAME_RATE = 30;
  const MAX_FRAME_RATE = 60;
  const FPS_WINDOW_MILLIS = 2000;

  function supportState({ secureContext, mediaDevices }) {
    if (!secureContext) return { available: false, reason: "INSECURE_CONTEXT" };
    if (
      !mediaDevices ||
      typeof mediaDevices.getUserMedia !== "function" ||
      typeof mediaDevices.enumerateDevices !== "function"
    ) {
      return { available: false, reason: "MEDIA_DEVICES_UNAVAILABLE" };
    }
    return { available: true, reason: null };
  }

  function buildConstraints(deviceId = "") {
    const video = {
      width: { ideal: DEFAULT_WIDTH },
      height: { ideal: DEFAULT_HEIGHT },
      frameRate: { ideal: DEFAULT_FRAME_RATE, max: MAX_FRAME_RATE },
    };
    if (deviceId) video.deviceId = { exact: String(deviceId) };
    return { audio: false, video };
  }

  async function enumerateInputs(mediaDevices) {
    const devices = await mediaDevices.enumerateDevices();
    return devices
      .filter((device) => device?.kind === "videoinput")
      .map((device, index) => ({
        deviceId: String(device.deviceId || ""),
        label: String(device.label || ""),
        index: index + 1,
      }));
  }

  async function start(mediaDevices, deviceId = "") {
    const stream = await mediaDevices.getUserMedia(buildConstraints(deviceId));
    const track = stream?.getVideoTracks?.()[0] || null;
    if (!track) {
      stop(stream);
      const error = new Error("The selected stream did not provide a video track.");
      error.name = "NotFoundError";
      throw error;
    }
    return {
      stream,
      track,
      settings: safeTrackSettings(track.getSettings?.() || {}),
    };
  }

  function stop(stream) {
    stream?.getTracks?.().forEach((track) => track.stop());
  }

  function safeTrackSettings(settings) {
    const output = {};
    for (const key of ["width", "height", "frameRate", "aspectRatio", "resizeMode", "facingMode"]) {
      const value = settings?.[key];
      if (typeof value === "number" && Number.isFinite(value)) output[key] = value;
      else if (typeof value === "string" && value) output[key] = value.slice(0, 64);
    }
    return output;
  }

  function errorCode(error) {
    const name = String(error?.name || "");
    if (name === "LocalVideoPlaybackError") return "PLAYBACK";
    const known = new Set([
      "AbortError",
      "NotAllowedError",
      "NotFoundError",
      "NotReadableError",
      "OverconstrainedError",
      "SecurityError",
    ]);
    return known.has(name) ? name.replace(/Error$/, "").replace(/([a-z])([A-Z])/g, "$1_$2").toUpperCase() :
      "LOCAL_VIDEO_ERROR";
  }

  function rollingFps(timestamps, now, windowMillis = FPS_WINDOW_MILLIS) {
    const finiteNow = Number(now);
    const window = Number(windowMillis);
    if (!Number.isFinite(finiteNow) || !Number.isFinite(window) || window <= 0) {
      throw new RangeError("Rolling FPS requires a finite timestamp and positive window.");
    }
    const recent = [...timestamps, finiteNow]
      .map(Number)
      .filter((value) => Number.isFinite(value) && value >= finiteNow - window && value <= finiteNow)
      .sort((left, right) => left - right);
    if (recent.length < 2) return { timestamps: recent, fps: 0 };
    const duration = recent.at(-1) - recent[0];
    return {
      timestamps: recent,
      fps: duration > 0 ? ((recent.length - 1) * 1000) / duration : 0,
    };
  }

  function presentedFrameCount(video) {
    const playbackFrames = video?.getVideoPlaybackQuality?.().totalVideoFrames;
    if (Number.isFinite(playbackFrames) && playbackFrames >= 0) return playbackFrames;
    const webkitFrames = video?.webkitDecodedFrameCount;
    return Number.isFinite(webkitFrames) && webkitFrames >= 0 ? webkitFrames : null;
  }

  function rollingFrameCount(samples, now, frameCount, windowMillis = FPS_WINDOW_MILLIS) {
    const finiteNow = Number(now);
    const window = Number(windowMillis);
    if (!Number.isFinite(finiteNow) || !Number.isFinite(window) || window <= 0) {
      throw new RangeError("Rolling frame count requires a finite timestamp and positive window.");
    }
    if (frameCount === null || frameCount === undefined) return { samples: [], fps: null };
    const finiteFrameCount = Number(frameCount);
    if (!Number.isFinite(finiteFrameCount) || finiteFrameCount < 0) {
      return { samples: [], fps: null };
    }
    const recent = [...samples, { at: finiteNow, frames: finiteFrameCount }]
      .filter((sample) => (
        Number.isFinite(sample?.at) && Number.isFinite(sample?.frames) &&
        sample.at >= finiteNow - window && sample.at <= finiteNow && sample.frames >= 0
      ))
      .sort((left, right) => left.at - right.at);
    if (recent.length < 2) return { samples: recent, fps: null };
    const first = recent[0];
    const last = recent.at(-1);
    const duration = last.at - first.at;
    const frames = last.frames - first.frames;
    return {
      samples: recent,
      fps: duration > 0 && frames >= 0 ? (frames * 1000) / duration : null,
    };
  }

  return {
    buildConstraints,
    enumerateInputs,
    errorCode,
    presentedFrameCount,
    rollingFrameCount,
    rollingFps,
    safeTrackSettings,
    start,
    stop,
    supportState,
  };
});
