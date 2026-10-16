(function (root, factory) {
  const rtpAudio = factory();
  if (typeof module === "object" && module.exports) module.exports = rtpAudio;
  root.OpenEOSRtpAudio = rtpAudio;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const BYTES_PER_SAMPLE = 2;
  const MAX_CHANNELS = 8;
  const MAX_SAMPLE_RATE = 192000;
  const MAX_SAMPLE_FRAMES = 12000;
  const FRESH_PCM_MS = 1000;
  const STALL_MS = 5000;
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
    const ageHeader = response.headers.get("x-open-eos-audio-age-ms");
    if (ageHeader === null) throw new TypeError("RTP audio decoded age is missing.");
    const decodedAgeMs = boundedInteger(ageHeader, "decoded age", 0, FRESH_PCM_MS - 1);
    if (sampleRate !== 48000 || channels !== 2) throw new RangeError("Unsupported Canon PCM format.");
    const discontinuity = response.headers.get("x-open-eos-audio-discontinuity") === "1";
    const content = await response.arrayBuffer();
    const expectedBytes = sampleFrames * channels * BYTES_PER_SAMPLE;
    if (content.byteLength !== expectedBytes) {
      throw new RangeError(`RTP audio payload has ${content.byteLength} bytes; expected ${expectedBytes}.`);
    }
    return {
      generation,
      decodedAgeMs,
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
    if (duration > 0.25) throw new RangeError("RTP audio chunk exceeds the bounded playback queue.");
    const reset = discontinuity || invalidTimeline || nextStart + duration > currentTime + MAX_SCHEDULE_LEAD_SECONDS;
    const startTime = reset ? currentTime + INITIAL_LEAD_SECONDS : Math.max(nextStart, currentTime + 0.005);
    return { startTime, nextStart: startTime + duration, reset };
  }

  // Tracks local software evidence only. A destination output timestamp is an
  // output-device timeline estimate, not proof of speakers, volume or hearing.
  class PlaybackProgress {
    constructor(now, outputSupported) {
      this.startedAt = this.lastCheckAt = now;
      this.outputSupported = outputSupported;
      this.lastOutputProgressAt = now;
      this.decodedAt = this.scheduledAt = this.outputAt = null;
      this.decodedGeneration = this.scheduledGeneration = 0;
      this.scheduledFrames = this.outputFrames = 0;
      this.intervals = [];
      this.timestamp = null;
    }

    decoded(chunk, now, requestStartedAt) {
      if (chunk.generation <= this.decodedGeneration) throw new Error("RTP audio generation did not advance.");
      // Conservative transit bound includes the long-poll wait. No server/client
      // wall-clock synchronization or camera capture-time claim is required.
      if (now - requestStartedAt < 0 || now - requestStartedAt + chunk.decodedAgeMs >= FRESH_PCM_MS) {
        throw new Error("RTP audio PCM expired in transit; enable audio again.");
      }
      this.decodedAt = requestStartedAt - chunk.decodedAgeMs;
      this.decodedGeneration = chunk.generation;
    }

    scheduled(chunk, timing, now, contextTime) {
      if (this.decodedAt !== null && now + (timing.nextStart - contextTime) * 1000 >= this.decodedAt + FRESH_PCM_MS) {
        throw new Error("RTP audio PCM would expire in the playback queue; enable audio again.");
      }
      if (timing.reset) this.clearQueue();
      if (this.outputSupported) this.intervals.push({ start: timing.startTime, end: timing.nextStart, rate: chunk.sampleRate,
        expiresAt: this.decodedAt + FRESH_PCM_MS });
      this.scheduledAt = now;
      this.scheduledGeneration = chunk.generation;
      this.scheduledFrames += chunk.sampleFrames;
    }

    clearQueue() {
      this.intervals = [];
      this.outputAt = null;
    }

    observeOutput(context, now) {
      if (!this.outputSupported || context.state !== "running") return;
      const stamp = context.getOutputTimestamp();
      if (!stamp || !Number.isFinite(stamp.contextTime) || !Number.isFinite(stamp.performanceTime) ||
          stamp.contextTime < 0 || stamp.performanceTime <= 0 || stamp.performanceTime > now ||
          now - stamp.performanceTime >= FRESH_PCM_MS) return;
      const previous = this.timestamp;
      if (previous && (stamp.contextTime < previous.contextTime || stamp.performanceTime < previous.performanceTime)) {
        throw new Error("RTP audio output clock moved backwards; enable audio again.");
      }
      if (previous && stamp.contextTime > previous.contextTime && stamp.performanceTime > previous.performanceTime) {
        let frames = 0;
        for (const interval of this.intervals) {
          if (now >= interval.expiresAt) continue;
          frames += Math.max(0, Math.min(stamp.contextTime, interval.end) -
            Math.max(previous.contextTime, interval.start)) * interval.rate;
        }
        if (frames > 0) {
          this.outputFrames += frames;
          this.outputAt = this.lastOutputProgressAt = now;
        }
        this.intervals = this.intervals.filter(interval => interval.end > stamp.contextTime);
      }
      this.timestamp = { contextTime: stamp.contextTime, performanceTime: stamp.performanceTime };
    }

    check(now, context, enabled) {
      const gap = now - this.lastCheckAt;
      this.lastCheckAt = now;
      if (gap < 0 || gap >= FRESH_PCM_MS) return "interrupted";
      if (enabled && context.state !== "running") return "suspended";
      if (enabled) this.observeOutput(context, now);
      if (now - (this.decodedAt ?? this.startedAt) >= STALL_MS) return "decoded-stalled";
      if (enabled && this.outputSupported && now - this.lastOutputProgressAt >= STALL_MS) {
        return "output-stalled";
      }
      return null;
    }

    snapshot(now) {
      return {
        decodedFresh: this.decodedAt !== null && now - this.decodedAt < FRESH_PCM_MS,
        decodedGeneration: this.decodedGeneration,
        scheduledGeneration: this.scheduledGeneration,
        scheduledFrames: this.scheduledFrames,
        outputProgressSupported: this.outputSupported,
        outputProgressFresh: this.outputAt !== null && now - this.outputAt < FRESH_PCM_MS,
        outputFrames: Math.floor(this.outputFrames),
        physicalAudibilityConfirmed: false,
      };
    }
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
    FRESH_PCM_MS,
    STALL_MS,
    PlaybackProgress,
    deinterleaveS16le,
    readPcmResponse,
    scheduleTiming,
  };
});
