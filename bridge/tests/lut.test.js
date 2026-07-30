"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const lutModule = require(path.join(__dirname, "..", "open_eos_bridge", "static", "lut.js"));

const invertText = `
TITLE "Invert"
LUT_3D_SIZE 2
DOMAIN_MIN 0 0 0
DOMAIN_MAX 1 1 1
1 1 1
0 1 1
1 0 1
0 0 1
1 1 0
0 1 0
1 0 0
0 0 0
`;

const lut = lutModule.parseCubeLut(invertText, "fallback.cube");
assert.equal(lut.name, "Invert");
assert.equal(lut.size, 2);
const sample = lutModule.sampleCubeLut(lut, 0.25, 0.5, 0.75);
assert.ok(Math.abs(sample[0] - 0.75) < 0.0001);
assert.ok(Math.abs(sample[1] - 0.5) < 0.0001);
assert.ok(Math.abs(sample[2] - 0.25) < 0.0001);

assert.throws(() => lutModule.parseCubeLut("LUT_3D_SIZE 2\n0 0 0", "bad.cube"), /requires 8 RGB rows/);
assert.throws(() => lutModule.parseCubeLut("LUT_1D_SIZE 2\n0 0 0\n1 1 1", "bad.cube"), /not supported/);
assert.throws(() => lutModule.parseCubeLut("LUT_3D_SIZE 65", "bad.cube"), /between 2 and 64/);
