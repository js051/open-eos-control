"use strict";
// Actual production event binding/handlers with synthetic DOM targets and API replies.
// This checks routing and default-prevention, not native browser keyboard activation.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function production(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.ok(start >= 0, name);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  assert.ok(end >= 0, name);
  return source.slice(start, start + 1 + end);
}
function node(tagName, parentElement = null) {
  const attributes = {}, classes = new Set(), listeners = {};
  return { tagName, parentElement, attributes, style: {}, hidden: false, disabled: false,
    addEventListener: (kind, handler) => { listeners[kind] = handler; }, listeners,
    classList: { contains: name => classes.has(name), toggle(name, active) { if (active) classes.add(name); else classes.delete(name); } },
    setAttribute: (name, value) => { attributes[name] = value; },
    removeAttribute: name => { delete attributes[name]; },
    getBoundingClientRect: () => ({ left: 20, top: 30, width: 640, height: 480 }),
    closest(selector) {
      for (let current = this; current; current = current.parentElement) {
        if (selector === "button" && current.tagName === "BUTTON") return current;
      }
      return null;
    },
  };
}
function fixture(action = "focus") {
  const calls = [];
  const state = { session: { id: "SYNTHETIC-KEYBOARD" }, liveActive: true, livePollingSuspended: false,
    liveMagnificationFramePending: false, previewInput: "CAMERA", captureMode: "photo", busy: false,
    tapAction: action, refreshGeneration: 0, liveMagnification: 1,
    capabilities: { liveView: { magnifications: [1, 5, 10] } } };
  const ui = { viewfinder: node("DIV"), liveImage: node("IMG"), focusReticle: node("DIV"), liveMagnificationLabel: node("SPAN") };
  ui.liveMagnificationButton = node("BUTTON", ui.viewfinder);
  ui.rtpAudioButton = node("BUTTON", ui.viewfinder);
  ui.zoomIcon = node("SPAN", ui.liveMagnificationButton);
  ui.audioIcon = node("SPAN", ui.rtpAudioButton);
  ui.overlay = node("DIV", ui.viewfinder);
  const FEATURES = Object.fromEntries(["TAP_FOCUS", "CLICK_WHITE_BALANCE", "LIVE_VIEW_MAGNIFICATION"].map(name => [name, name]));
  const context = vm.createContext({ state, ui, FEATURES,
    featureSupported: feature => Object.values(FEATURES).includes(feature),
    cameraInteractionBusy: () => state.busy, bulbControlLocked: () => false,
    replaceButtonIcon() {}, cancelCameraFocusInfo() {}, renderAvailability() {}, renderSession() {},
    setOperationState() {}, showToast() {}, captureError: error => error, t: key => key,
    api: (url, options) => { calls.push({ url, options }); return new Promise(() => {}); },
  });
  vm.runInContext(["effectiveTapAction", "liveCapabilities", "liveMagnifications", "nextLiveMagnification",
    "localPreviewSelected", "renderLiveMagnification", "focusPointFromClient", "beginCameraInteraction",
    "tapFocus", "tapFocusFromPointer"].map(production).join("\n"), context);
  const start = source.indexOf('    ui.viewfinder.addEventListener("keydown", (event) => {');
  assert.ok(start >= 0);
  const end = source.indexOf("\n    });", start);
  assert.ok(end > start);
  vm.runInContext(source.slice(start, end + "\n    });".length), context);
  const availability = source.slice(source.indexOf("    const tapAction = effectiveTapAction();"),
    source.indexOf('    const mediaTab = document.querySelector(\'.tab[data-view="media"]\');'));
  assert.ok(availability.includes('ui.viewfinder.tabIndex = 0;'));
  vm.runInContext(`function renderTapAvailability() { const interactionBusy = cameraInteractionBusy(), bulbActive = bulbControlLocked();\n${availability}\n}`, context);
  context.renderLiveMagnification(); context.renderTapAvailability();
  function key(target, key) {
    const event = { key, target, currentTarget: ui.viewfinder, defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; } };
    ui.viewfinder.listeners.keydown(event);
    return event;
  }
  return Object.assign(context, { calls, key });
}

test("production availability permits zoom and keyboard tap on the same live photo frame", () => {
  const h = fixture();
  assert.equal(h.ui.liveMagnificationButton.hidden, false);
  assert.equal(h.ui.liveMagnificationButton.disabled, false);
  assert.equal(h.ui.viewfinder.classList.contains("tap-focus-enabled"), true);
  assert.equal(h.ui.viewfinder.tabIndex, 0);
});

for (const target of ["liveMagnificationButton", "rtpAudioButton", "zoomIcon", "audioIcon"]) {
  for (const key of ["Enter", " "]) {
    test(`${target} ${JSON.stringify(key)} retains native default and cannot dispatch tap focus`, () => {
      const h = fixture(); const event = h.key(h.ui[target], key);
      assert.equal(h.calls.length, 0, "a descendant's keyboard action must not send a camera coordinate mutation");
      assert.equal(event.defaultPrevented, false);
      assert.equal(h.state.busy, false);
    });
  }
}

for (const action of ["focus", "whiteBalance"]) {
  for (const key of ["Enter", " "]) {
    test(`focused viewfinder ${JSON.stringify(key)} still sends exactly one center ${action}`, () => {
      const h = fixture(action); const event = h.key(h.ui.viewfinder, key);
      assert.equal(event.defaultPrevented, true);
      assert.equal(h.calls.length, 1);
      assert.ok(h.calls[0].url.endsWith(action === "focus" ? "/focus/tap" : "/whitebalance/click"));
      assert.equal(h.calls[0].options.method, "POST");
      assert.equal(h.calls[0].options.json.x, 0.5); assert.equal(h.calls[0].options.json.y, 0.5);
      assert.equal(h.state.busy, true);
    });
  }
}

test("nonactivation keys and inactive tap surface neither prevent defaults nor send commands", () => {
  const h = fixture();
  for (const key of ["Tab", "Escape", "ArrowRight", "x"]) {
    assert.equal(h.key(h.ui.viewfinder, key).defaultPrevented, false);
  }
  h.ui.viewfinder.classList.toggle("tap-focus-enabled", false);
  assert.equal(h.key(h.ui.viewfinder, "Enter").defaultPrevented, false);
  assert.equal(h.calls.length, 0);
});

test("noninteractive overlay keyboard events cannot impersonate the focused viewfinder", () => {
  const h = fixture();
  assert.equal(h.key(h.ui.overlay, "Enter").defaultPrevented, false);
  assert.equal(h.calls.length, 0);
});

test("pointer buttons remain excluded, while a noninteractive overlay still supplies pointer coordinates", () => {
  const h = fixture();
  for (const target of [h.ui.liveMagnificationButton, h.ui.zoomIcon, h.ui.rtpAudioButton, h.ui.audioIcon]) {
    h.tapFocusFromPointer({ target, clientX: 180, clientY: 150 });
  }
  assert.equal(h.calls.length, 0);
  h.tapFocusFromPointer({ target: h.ui.overlay, clientX: 180, clientY: 150 });
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].options.json.x, 0.25); assert.equal(h.calls[0].options.json.y, 0.25);
});

test("existing command admission still rejects stale enabled presentation", () => {
  for (const patch of [{ busy: true }, { liveActive: false }, { livePollingSuspended: true },
    { liveMagnificationFramePending: true }, { previewInput: "LOCAL_VIDEO" }, { session: null }]) {
    const h = fixture(); Object.assign(h.state, patch);
    h.key(h.ui.viewfinder, "Enter"); assert.equal(h.calls.length, 0, JSON.stringify(patch));
  }
});

test("production HTML places both native controls inside the keyboard event host", () => {
  const html = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/index.html"), "utf8");
  const inside = html.split('<div id="viewfinder"', 2)[1].split('<p id="camera-focus-legend"', 1)[0];
  assert.match(inside, /<button id="live-magnification-button"[^>]*type="button"/);
  assert.match(inside, /<button id="rtp-audio-button"[^>]*type="button"/);
});
