"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const rtpAudio = require(path.join(__dirname, "..", "open_eos_bridge", "static", "rtp-audio.js"));

function pcmBuffer(values) {
  const content = new ArrayBuffer(values.length * 2);
  const view = new DataView(content);
  values.forEach((value, index) => view.setInt16(index * 2, value, true));
  return content;
}

const channels = rtpAudio.deinterleaveS16le(pcmBuffer([-32768, 32767, 0, 16384]), 2, 2);
assert.equal(channels.length, 2);
assert.deepEqual(Array.from(channels[0]), [-1, 0]);
assert.ok(Math.abs(channels[1][0] - (32767 / 32768)) < 0.000001);
assert.equal(channels[1][1], 0.5);
assert.throws(() => rtpAudio.deinterleaveS16le(new ArrayBuffer(2), 2, 2), RangeError);

assert.deepEqual(rtpAudio.scheduleTiming(10, 0, 0.02), {
  startTime: 10.08,
  nextStart: 10.1,
  reset: true,
});
assert.deepEqual(rtpAudio.scheduleTiming(10, 10.1, 0.02), {
  startTime: 10.1,
  nextStart: 10.12,
  reset: false,
});
assert.equal(rtpAudio.scheduleTiming(10, 10.1, 0.02, true).reset, true);

(async () => {
  const content = pcmBuffer([-32768, 32767, 0, 16384]);
  const response = new Response(content, {
    status: 200,
    headers: {
      "Content-Type": "audio/pcm;rate=48000;channels=2;format=s16le",
      "X-Open-EOS-Audio-Generation": "7",
      "X-Open-EOS-Audio-Sample-Rate": "48000",
      "X-Open-EOS-Audio-Channels": "2",
      "X-Open-EOS-Audio-Frames": "2",
      "X-Open-EOS-Audio-Discontinuity": "1",
    },
  });
  const chunk = await rtpAudio.readPcmResponse(response);
  assert.equal(chunk.generation, 7);
  assert.equal(chunk.sampleRate, 48000);
  assert.equal(chunk.channels, 2);
  assert.equal(chunk.sampleFrames, 2);
  assert.equal(chunk.discontinuity, true);
  assert.equal(chunk.byteLength, 8);
  assert.equal(await rtpAudio.readPcmResponse(new Response(null, { status: 204 })), null);

  const invalid = new Response(new Uint8Array(2), {
    status: 200,
    headers: {
      "Content-Type": "audio/pcm",
      "X-Open-EOS-Audio-Generation": "1",
      "X-Open-EOS-Audio-Sample-Rate": "48000",
      "X-Open-EOS-Audio-Channels": "2",
      "X-Open-EOS-Audio-Frames": "2",
    },
  });
  await assert.rejects(rtpAudio.readPcmResponse(invalid), RangeError);
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
