"use strict";

const assert = require("node:assert/strict");
const { createHash } = require("node:crypto");
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
const windowsHome = "C:" + "\\Users\\Private User\\capture.jpg";
const networkHome = "\\\\" + "PRIVATE-SERVER\\private\\capture.jpg";
const unixHome = "/" + "Users/private/capture.jpg";
const safe = diagnostics.safeValue(
  {
    info: { serial: privateSerial, model: "Canon EOS R6 Mark III" },
    unknownCamera: { serial: "unknown" },
    nested: [{ password: "camera-password" }, { authorization: `Bearer ${privateToken}` }],
    lastError: `Authorization: Bearer ${privateToken} token=${privateToken} camera=${privateSerial}`,
    localPaths: [
      windowsHome,
      networkHome,
      "C:/dev/capture.jpg",
      unixHome,
      "/private/var/mobile/frame.jpg",
      "file:///tmp/frame.jpg",
    ],
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
assert.ok(!serialized.includes("C:" + "\\Users"));
assert.ok(!serialized.includes("Private User"));
assert.ok(!serialized.includes("PRIVATE-SERVER"));
assert.ok(!serialized.includes("C:/dev"));
assert.ok(!serialized.includes("/" + "Users/private"));
assert.ok(!serialized.includes("/private/var"));
assert.ok(!serialized.includes("file:///tmp"));
assert.ok(serialized.includes("[local-path]"));

async function testPhysicalValidation() {
  const capabilities = {
    supported: ["LIVE_VIEW", "STILL_CAPTURE"],
    evidence: {
      source: "gphoto2 capability probe",
      observedFeatures: ["STILL_CAPTURE", "USB_DIAGNOSTICS"],
    },
  };
  const physical = diagnostics.physicalValidationSummary(capabilities, {
    connected: true,
    info: { model: "Canon EOS R6 Mark III", api: "gphoto2" },
    confirmedFeatures: ["STILL_CAPTURE", "LIVE_VIEW", "USB_DIAGNOSTICS"],
  });
  assert.deepEqual(physical, {
    sessionStatus: "READY",
    advertisedFeatures: ["LIVE_VIEW", "STILL_CAPTURE"],
    observedFeatures: ["STILL_CAPTURE", "USB_DIAGNOSTICS"],
    eligibleFeatures: ["STILL_CAPTURE"],
    operatorConfirmedFeatures: ["STILL_CAPTURE"],
  });

  const simulator = diagnostics.physicalValidationSummary(capabilities, {
    connected: true,
    info: { model: "Canon EOS R6 Mark III", api: "simulated-ccapi" },
    confirmedFeatures: ["STILL_CAPTURE"],
  });
  assert.equal(simulator.sessionStatus, "SIMULATOR");
  assert.deepEqual(simulator.eligibleFeatures, []);
  assert.deepEqual(simulator.operatorConfirmedFeatures, []);

  const diagnosticReport = JSON.stringify({
    generatedAt: "2026-08-01T00:00:00Z",
    productVersion: "0.1.8-test",
    info: { model: "Canon EOS R6 Mark III", serial: "[redacted]" },
    baseUrl: "http://192.168.1.2:8080",
    lastError: "Failed at " + "C:/Us" + "ers/private/capture.jpg",
  }, null, 2);
  const record = await diagnostics.physicalValidationRecord({
    summary: physical,
    cameraModel: "Canon EOS R6 Mark III",
    transport: "DESKTOP_BRIDGE_LIBGPHOTO2",
    generatedAt: "2026-08-01T00:00:00Z",
    productVersion: "0.1.8-test",
    diagnosticReport,
  });
  const expectedHash = createHash("sha256").update(diagnosticReport).digest("hex");
  assert.ok(record.includes(`Diagnostic SHA-256: \`${expectedHash}\``));
  assert.ok(record.includes("| STILL_CAPTURE | true | true | true |"));
  assert.ok(record.includes("| LIVE_VIEW | true | false | false |"));
  assert.ok(!record.includes("192.168.1.2"));
  assert.ok(!record.includes("C:/Us" + "ers"));
  assert.ok(!record.toLowerCase().includes("serial"));
  await assert.rejects(
    diagnostics.physicalValidationRecord({ summary: simulator, diagnosticReport }),
    /physical camera session/i,
  );
}

testPhysicalValidation().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
