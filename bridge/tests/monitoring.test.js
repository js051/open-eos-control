"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const monitoring = require(path.join(__dirname, "..", "open_eos_bridge", "static", "monitoring.js"));

assert.deepEqual(monitoring.analysisDimensions(4000, 3000), { width: 107, height: 80 });
assert.deepEqual(monitoring.analysisDimensions(3000, 4000), { width: 60, height: 80 });
assert.deepEqual(monitoring.analysisDimensions(80, 40), { width: 80, height: 40 });

const fitted = monitoring.fitRect(3000, 2000, 400, 400);
assert.equal(fitted.left, 0);
assert.ok(Math.abs(fitted.top - 66.6666667) < 0.001);
assert.equal(fitted.width, 400);
assert.ok(Math.abs(fitted.height - 266.6666667) < 0.001);

const histogram = monitoring.analyzePixels(
  new Uint8ClampedArray([
    0, 0, 0, 255,
    128, 128, 128, 255,
    255, 255, 255, 255,
    255, 0, 0, 255,
  ]),
  4,
  1,
);
assert.equal(histogram.histogram.length, 64);
assert.equal([...histogram.histogram].reduce((total, count) => total + count, 0), 4);
assert.equal(histogram.overlay, null);

const falseColor = monitoring.analyzePixels(
  new Uint8ClampedArray([0, 0, 0, 255]),
  1,
  1,
  { falseColorEnabled: true },
);
assert.deepEqual([...falseColor.overlay], [80, 22, 122, 224]);

const zebra = monitoring.analyzePixels(
  new Uint8ClampedArray(Array(8).fill([255, 255, 255, 255]).flat()),
  8,
  1,
  { zebraThresholdPercent: 90 },
);
assert.deepEqual([...zebra.overlay.slice(0, 4)], [255, 255, 255, 176]);
assert.deepEqual([...zebra.overlay.slice(16, 20)], [0, 0, 0, 120]);

const edgePixels = new Uint8ClampedArray(3 * 3 * 4);
for (let y = 0; y < 3; y += 1) {
  for (let x = 0; x < 3; x += 1) {
    const offset = (y * 3 + x) * 4;
    const value = x === 2 ? 255 : 0;
    edgePixels.set([value, value, value, 255], offset);
  }
}
const peaking = monitoring.analyzePixels(edgePixels, 3, 3, { focusPeakingEnabled: true });
assert.deepEqual([...peaking.overlay.slice(16, 20)], [40, 197, 217, 255]);

assert.throws(
  () => monitoring.analyzePixels(new Uint8ClampedArray(4), 1, 1, { zebraThresholdPercent: 49 }),
  /between 50 and 100/,
);
