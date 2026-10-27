"use strict";

// Exercise the ordinary child-script entrypoint, not a browser or product timeout.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

const TARGET = path.join(__dirname, "media-auto-import.test.js");
const CONTROLLER = path.resolve(__dirname, "../open_eos_bridge/static/media-auto-import.js");

function runChild(preload, target = TARGET) {
  const result = spawnSync(process.execPath, [
    "--test-reporter=tap",
    ...(preload ? ["--require", preload] : []),
    target,
  ], {
    cwd: path.resolve(__dirname, ".."),
    encoding: "utf8",
    timeout: 15_000,
    maxBuffer: 1024 * 1024,
  });
  // An outer kill/setup failure must never stand in for framework cancellation.
  assert.ifError(result.error);
  assert.equal(result.signal, null, result.stderr);
  return result;
}

function count(output, field) {
  const match = output.match(new RegExp(`^# ${field} (\\d+)\\r?$`, "m"));
  assert.ok(match, `Missing child ${field} summary:\n${output}`);
  return Number(match[1]);
}

function assertFrameworkCancellation(output) {
  assert.match(output, /failureType: 'cancelledByParent'/);
  assert.match(output, /code: 'ERR_TEST_FAILURE'/);
  // Node 22 cancels when its event loop drains; this Node 24 environment reaches
  // the explicit suite deadline instead. Both must be actual framework failures.
  const deadline = /failureType: 'testTimeoutFailure'/.test(output);
  const pendingAtExit = /error: 'Promise resolution is still pending but the event loop has already resolved'/.test(output);
  assert.ok(deadline || pendingAtExit, "Expected a framework deadline or pending-Promise cancellation");
}

test("completion reason does not mistake unrelated failures for pending-work cancellation", () => {
  const cancelled = "failureType: 'cancelledByParent'\ncode: 'ERR_TEST_FAILURE'\n";
  const deadline = cancelled + "failureType: 'testTimeoutFailure'\n";
  const pending = cancelled + "error: 'Promise resolution is still pending but the event loop has already resolved'\n";
  assert.doesNotThrow(() => assertFrameworkCancellation(deadline));
  assert.doesNotThrow(() => assertFrameworkCancellation(pending));
  for (const unrelated of [
    cancelled,
    cancelled + "error: 'unrelated assertion failed'\n",
    deadline.replace("ERR_TEST_FAILURE", "ERR_OTHER"),
    deadline.replace("cancelledByParent", "testCodeFailure"),
    "failureType: 'testTimeoutFailure'\n",
  ]) {
    assert.throws(() => assertFrameworkCancellation(unrelated), { name: "AssertionError" });
  }
});

test("ordinary media auto import runner completes all 20 preserved cases", () => {
  const result = runChild();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(count(result.stdout, "tests"), 20);
  assert.equal(count(result.stdout, "pass"), 20);
  assert.equal(count(result.stdout, "fail"), 0);
  assert.equal(count(result.stdout, "cancelled"), 0);
  assert.equal(count(result.stdout, "skipped"), 0);
  assert.equal(count(result.stdout, "todo"), 0);
});

test("a stalled controller step fails through node:test instead of exiting green", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-test-completion-"));
  const preload = path.join(directory, "stall-preload.cjs");
  try {
    // Only this child process's exported controller is wrapped. No source is edited,
    // no timer is installed by the fixture, and no pending operation is fabricated
    // into a success. The unchanged scenario stalls at its first real awaited step.
    fs.writeFileSync(preload, `
const api = require(${JSON.stringify(CONTROLLER)});
const original = api.createController;
api.createController = (...args) => {
  const controller = original(...args);
  return { ...controller, step() {
    console.log("SYNTHETIC_TEST_STALL: controller.step never settles");
    return new Promise(() => {});
  }};
};
`);
    const result = runChild(preload);
    assert.equal(result.status, 1, result.stdout + result.stderr);
    assert.match(result.stdout, /SYNTHETIC_TEST_STALL: controller\.step never settles/);
    assertFrameworkCancellation(result.stdout);
    assert.equal(count(result.stdout, "tests"), 20);
    assert.equal(count(result.stdout, "pass"), 0);
    assert.equal(count(result.stdout, "fail"), 0);
    assert.equal(count(result.stdout, "cancelled"), 20);
    assert.equal(count(result.stdout, "skipped"), 0);
    assert.equal(count(result.stdout, "todo"), 0);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

const REMAINING_ASYNC_RUNNERS = [
  { name: "diagnostics", method: "physicalValidationRecord", tests: 1, passedBeforeStall: 0 },
  { name: "local-video", method: "enumerateInputs", tests: 1, passedBeforeStall: 0 },
  { name: "rtp-audio", method: "readPcmResponse", tests: 1, passedBeforeStall: 0 },
  { name: "media-transfer", method: "readResponse", tests: 1, passedBeforeStall: 0 },
  { name: "media-batch-destination", method: "createDirectoryBatchDestination", nested: "open",
    tests: 40, passedBeforeStall: 1 },
  { name: "bulb-recovery", tests: 1, passedBeforeStall: 0 },
];

function stallPreload(spec) {
  const marker = `SYNTHETIC_STALL:${spec.name}.${spec.nested || spec.method || "operateShutter"}`;
  if (spec.name === "bulb-recovery") {
    // This existing test evaluates production functions in its own VM context.
    return `
const vm = require("node:vm");
const run = vm.runInContext;
vm.runInContext = function(code, context, options) {
  const result = run.apply(this, arguments);
  if (typeof context.operateShutter === "function") context.operateShutter = () => {
    console.log(${JSON.stringify(marker)});
    return new Promise(() => {});
  };
  return result;
};
`;
  }
  const modulePath = path.resolve(__dirname, `../open_eos_bridge/static/${spec.name}.js`);
  const replacement = spec.nested
    ? `(...args) => {
      const actual = original[${JSON.stringify(spec.method)}](...args);
      return { ...actual, [${JSON.stringify(spec.nested)}]: stall };
    }`
    : "stall";
  return `
const Module = require("node:module");
const load = Module._load;
const expected = ${JSON.stringify(modulePath)};
const stall = () => {
  console.log(${JSON.stringify(marker)});
  return new Promise(() => {});
};
Module._load = function(request, parent, isMain) {
  const original = load.apply(this, arguments);
  if (Module._resolveFilename(request, parent, isMain) !== expected) return original;
  return { ...original, [${JSON.stringify(spec.method)}]: ${replacement} };
};
`;
}

for (const spec of REMAINING_ASYNC_RUNNERS) {
  const target = path.join(__dirname, `${spec.name}.test.js`);
  test(`ordinary ${spec.name} runner completes its preserved scenarios`, () => {
    const result = runChild(undefined, target);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(count(result.stdout, "tests"), spec.tests);
    assert.equal(count(result.stdout, "suites"), 1);
    assert.equal(count(result.stdout, "pass"), spec.tests);
    assert.equal(count(result.stdout, "fail"), 0);
    assert.equal(count(result.stdout, "cancelled"), 0);
    assert.equal(count(result.stdout, "skipped"), 0);
    assert.equal(count(result.stdout, "todo"), 0);
  });

  test(`a stalled ${spec.name} dependency fails through node:test`, () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-test-completion-"));
    const preload = path.join(directory, "stall-preload.cjs");
    try {
      // The fault lives only in this child: original assertion/fixture bytes and
      // all product files remain unchanged. No product or fixture timer is added.
      fs.writeFileSync(preload, stallPreload(spec));
      const result = runChild(preload, target);
      assert.equal(result.status, 1, result.stdout + result.stderr);
      assert.ok(result.stdout.includes(
        `SYNTHETIC_STALL:${spec.name}.${spec.nested || spec.method || "operateShutter"}`,
      ));
      assertFrameworkCancellation(result.stdout);
      assert.equal(count(result.stdout, "tests"), spec.tests);
      assert.equal(count(result.stdout, "suites"), 1);
      assert.equal(count(result.stdout, "pass"), spec.passedBeforeStall);
      assert.equal(count(result.stdout, "fail"), 0);
      assert.equal(count(result.stdout, "cancelled"), spec.tests - spec.passedBeforeStall);
      assert.equal(count(result.stdout, "skipped"), 0);
      assert.equal(count(result.stdout, "todo"), 0);
    } finally {
      fs.rmSync(directory, { recursive: true, force: true });
    }
  });
}
