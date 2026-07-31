(function (root, factory) {
  const rtpAudio = factory();
  if (typeof module === "object" && module.exports) module.exports = rtpAudio;
  root.OpenEOSRtpAudio = rtpAudio;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const BYTES_PER_SAMPLE = 2;
  const MAX_CHANNELS = 8;
  const MAX_SAMPLE_RATE = 192000;
  const MAX_SAMPLE_FRAMES = 192000;
  const INITIAL_LEAD_SECONDS = 0.08;
  const MAX_SCHEDULE_LEAD_SECONDS = 0.5;

  async function readPcmResponse(response) {
    if (!response || response.status === 204) return null;
    if (!response.ok) throw new Error(`RTP audio request failed with HTTP ${response.status}.`);
    const contentType = response.headers.get("content-type") || "";
    if (!contentType.toLowerCase().startsWith("audio/pcm")) {
      throw new TypeError(`Unexpected RTP audio content type: ${contentType || "missing"}.`);
    }
    const generation = positiveInteger(response.headers.get("x-open-eos-audio-generation"), "generation");
    const sampleRate = boundedInteger(
      response.headers.get("x-open-eos-audio-sample-rate"),
      "sample rate",
      1,
      MAX_SAMPLE_RATE,
    );
    const channels = boundedInteger(
      response.headers.get("x-open-eos-audio-channels"),
      "channel count",
      1,
      MAX_CHANNELS,
    );
    const sampleFrames = boundedInteger(
      response.headers.get("x-open-eos-audio-frames"),
      "sample frame count",
      1,
      MAX_SAMPLE_FRAMES,
    );
    const discontinuity = response.headers.get("x-open-eos-audio-discontinuity") === "1";
    const content = await response.arrayBuffer();
    const expectedBytes = sampleFrames * channels * BYTES_PER_SAMPLE;
    if (content.byteLength !== expectedBytes) {
      throw new RangeError(`RTP audio payload has ${content.byteLength} bytes; expected ${expectedBytes}.`);
    }
    return {
      generation,
      sampleRate,
      channels,
      sampleFrames,
      discontinuity,
      byteLength: content.byteLength,
      channelData: deinterleaveS16le(content, channels, sampleFrames),
    };
  }

  function deinterleaveS16le(content, channels, sampleFrames) {
    if (!(content instanceof ArrayBuffer)) throw new TypeError("PCM content must be an ArrayBuffer.");
    const view = new DataView(content);
    if (view.byteLength !== channels * sampleFrames * BYTES_PER_SAMPLE) {
      throw new RangeError("PCM dimensions do not match its byte length.");
    }
    const output = Array.from({ length: channels }, () => new Float32Array(sampleFrames));
    for (let frame = 0; frame < sampleFrames; frame += 1) {
      for (let channel = 0; channel < channels; channel += 1) {
        const offset = (frame * channels + channel) * BYTES_PER_SAMPLE;
        output[channel][frame] = view.getInt16(offset, true) / 32768;
      }
    }
    return output;
  }

  function scheduleTiming(currentTime, nextStart, duration, discontinuity = false) {
    if (![currentTime, duration].every(Number.isFinite) || currentTime < 0 || duration <= 0) {
      throw new RangeError("Audio timing values must be finite and positive.");
    }
    const invalidTimeline = !Number.isFinite(nextStart) || nextStart < currentTime - 0.05 ||
      nextStart > currentTime + MAX_SCHEDULE_LEAD_SECONDS;
    const reset = discontinuity || invalidTimeline;
    const startTime = reset ? currentTime + INITIAL_LEAD_SECONDS : Math.max(nextStart, currentTime + 0.005);
    return { startTime, nextStart: startTime + duration, reset };
  }

  function positiveInteger(value, label) {
    return boundedInteger(value, label, 1, Number.MAX_SAFE_INTEGER);
  }

  function boundedInteger(value, label, minimum, maximum) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
      throw new RangeError(`RTP audio ${label} is invalid.`);
    }
    return parsed;
  }

  return {
    deinterleaveS16le,
    readPcmResponse,
    scheduleTiming,
  };
});
