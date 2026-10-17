// Execute unchanged production PC functions against production API result rows.
// API, DOM, timers and rendering are fixtures; this is not browser validation.
import fs from "node:fs";
import vm from "node:vm";
import assert from "node:assert/strict";

const [input, phase] = process.argv.slice(2);
assert(input && ["baseline", "current"].includes(phase));
const app = fs.readFileSync("bridge/open_eos_bridge/static/app.js", "utf8");
function exact(start, end) {
  const a = app.indexOf(start), b = app.indexOf(end, a);
  assert(a >= 0 && b > a);
  return app.slice(a, b);
}
const source = exact("  async function driveFocus(direction) {", "  function focusPointFromClient(")
  + exact("  async function tapFocus(point) {", "  function tapFocusFromPointer(");
const rows = fs.readFileSync(input, "utf8").trim().split("\n").map(JSON.parse);
assert.equal(rows.length, 21);
for (const row of rows) {
  const calls = [], feedback = [], toasts = [];
  const state = {
    session: { id: "synthetic-session" }, liveActive: true, liveGeneration: 1,
    refreshGeneration: 1, activeLiveSource: "CCAPI_JPEG_POLLING", liveViewStopOwner: {},
    focusStep: "MEDIUM", busy: false,
  };
  const ui = { focusReticle: { style: {}, hidden: true, className: "" } };
  const context = {
    state, ui, FEATURES: { FOCUS_DRIVE: "FOCUS_DRIVE" },
    cameraInteractionBusy: () => false, featureSupported: () => true, localPreviewSelected: () => false,
    beginCameraInteraction: () => { state.busy = true; },
    setOperationState: (text, error = false) => feedback.push({ text, error }),
    renderAvailability() {}, renderSession() {}, showToast: (...args) => toasts.push(args),
    t: key => key, effectiveTapAction: () => row.control === "white_balance" ? "whiteBalance" : "focus",
    adoptCameraStatus: status => { state.status = status; }, window: { setTimeout() {} },
    ApiError: Error, captureError: error => error,
    api: async (url, options) => {
      calls.push({ url, options });
      if (row.api_status !== 200) throw new Error(row.api_result.error.code);
      return row.api_result;
    },
  };
  vm.createContext(context);
  vm.runInContext(source + "\nglobalThis.run = {driveFocus,tapFocus};", context);
  if (row.control === "drive") await context.run.driveFocus("NEAR");
  else await context.run.tapFocus({ x: .4, y: .6, displayX: 10, displayY: 20 });
  assert.equal(calls.length, 1);
  assert.equal(state.busy, false);
  const shouldAccept = phase === "current" ? row.synthetic_mutation_applied : row.wire_status !== 503;
  assert.equal(row.api_status === 200, shouldAccept);
  const expected = !shouldAccept ? row.api_result.error.code
    : row.control === "drive" ? "focusDriveAccepted"
      : row.control === "tap" ? "focusAccepted" : "clickWhiteBalanceAccepted";
  assert.equal(feedback.at(-1).text, expected);
  if (shouldAccept && row.control !== "drive") {
    assert.equal(ui.focusReticle.className,
      row.control === "tap" ? "focus-reticle accepted" : "focus-reticle success");
  }
  if (!shouldAccept) {
    assert.equal(feedback.at(-1).error, true);
    assert(!["focus-reticle accepted", "focus-reticle success"].includes(ui.focusReticle.className));
  }
  console.log(JSON.stringify({
    control: row.control, variant: row.variant, synthetic_mutation_applied: row.synthetic_mutation_applied,
    feedback: feedback.at(-1), reticle: ui.focusReticle.className, api_calls: calls.length,
  }));
}
