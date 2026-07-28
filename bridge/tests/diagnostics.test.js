"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const diagnostics = require(path.join(__dirname, "..", "open_eos_bridge", "static", "diagnostics.js"));

const summary = diagnostics.featureSummary({
  supported: ["LIVE_VIEW", "STILL_CAPTURE", "LIVE_VIEW"],
  evidence: {
    observedFeatures: ["LIVE_VIEW", "USB_DIAGNOSTICS", "LIVE_VIEW"],
  },
});
assert.deepEqual(summary, {
  advertisedFeatureCount: 2,
  observedFeatureCount: 2,
  validatedAdvertisedFeatureCount: 1,
  unverifiedAdvertisedFeatures: ["STILL_CAPTURE"],
  observedWithoutAdvertisement: ["USB_DIAGNOSTICS"],
});

const privateSerial = "PRIVATE-CAMERA-SERIAL";
const privateToken = "PRIVATE-BRIDGE-TOKEN";
const safe = diagnostics.safeValue(
  {
    info: { serial: privateSerial, model: "Canon EOS R6 Mark III" },
    unknownCamera: { serial: "unknown" },
    nested: [{ password: "camera-password" }, { authorization: `Bearer ${privateToken}` }],
    lastError: `Authorization: Bearer ${privateToken} token=${privateToken} camera=${privateSerial}`,
  },
  { secrets: [privateToken, privateSerial] },
);

const serialized = JSON.stringify(safe);
assert.equal(safe.info.serial, "[redacted]");
assert.equal(safe.unknownCamera.serial, "unknown");
assert.equal(safe.nested[0].password, "[redacted]");
assert.equal(safe.nested[1].authorization, "[redacted]");
assert.ok(!serialized.includes(privateSerial));
assert.ok(!serialized.includes(privateToken));
assert.ok(!serialized.includes("camera-password"));
