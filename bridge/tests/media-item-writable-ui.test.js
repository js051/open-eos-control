"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const start = new RegExp(`^  function ${name}\\(`, "m").exec(source).index;
  return source.slice(start, source.indexOf("\n  }", start) + 4);
}
function element() {
  const buttons = [0, 1].map(value => ({ disabled: false, dataset: { mediaRating: String(value), mediaRotation: String(value * 90) },
    classList: { toggle() {} }, setAttribute() {} }));
  return { open: true, hidden: false, disabled: false, textContent: "", classList: { toggle() {} },
    setAttribute() {}, querySelectorAll() { return buttons; } };
}
function fixture(item = {}, disabled = []) {
  const state = { mediaDetailsItem: { id: "a", name: "a.JPG", archived: false, ...item }, mediaDetailsBusy: false };
  const ui = new Proxy({}, { get(target, key) { return target[key] ||= element(); } });
  const FEATURES = Object.fromEntries(["MEDIA_PROTECT", "MEDIA_RATING", "MEDIA_ROTATE", "MEDIA_ARCHIVE", "MEDIA_DOWNLOAD", "MEDIA_DELETE"].map(x => [x, x]));
  const context = vm.createContext({ state, ui, FEATURES, featureSupported: value => !disabled.includes(value),
    cameraInteractionBusy: () => false, formatDate: () => "", formatMediaDimensions: () => "", formatMediaSize: () => "",
    formatMediaContentType: () => "", t: key => key });
  vm.runInContext(extract("renderMediaDetails"), context);
  context.renderMediaDetails();
  return { state, ui, render: () => context.renderMediaDetails() };
}
const fields = [
  ["protection", "mediaProtectionSection", "mediaProtectionUnavailable", "MEDIA_PROTECT", c => [c.ui.mediaProtectButton, c.ui.mediaUnprotectButton]],
  ["rating", "mediaRatingSection", "mediaRatingUnavailable", "MEDIA_RATING", c => c.ui.mediaRatingControl.querySelectorAll()],
  ["rotation", "mediaRotationSection", "mediaRotationUnavailable", "MEDIA_ROTATE", c => c.ui.mediaRotationControl.querySelectorAll()],
  ["archive", "mediaArchiveSection", "mediaArchiveUnavailable", "MEDIA_ARCHIVE", c => [c.ui.mediaArchiveButton, c.ui.mediaUnarchiveButton]],
];
for (const [field, section, reason, feature, buttons] of fields) {
  test(`${field}: explicit false disables existing controls and shows a reason; renewed true/unknown re-enables`, () => {
    const c = fixture({ [`${field}Writable`]: false });
    assert.equal(c.ui[section].hidden, false);
    assert.ok(buttons(c).every(button => button.disabled), "explicit item veto must be visible before clicking");
    assert.equal(c.ui[reason].hidden, false);
    assert.equal(c.ui[reason].textContent, "mediaMetadataReadOnly");
    for (const value of [true, null, undefined]) {
      c.state.mediaDetailsItem[`${field}Writable`] = value; c.render();
      assert.ok(buttons(c).every(button => !button.disabled));
      assert.equal(c.ui[reason].hidden, true);
    }
    c.state.mediaDetailsBusy = true; c.render();
    assert.ok(buttons(c).every(button => button.disabled));
  });
  test(`${field}: writable=true never grants missing transport capability`, () => {
    const c = fixture({ [`${field}Writable`]: true }, [feature]);
    assert.equal(c.ui[section].hidden, true);
  });
}

test("one field veto does not disable unrelated supported metadata operations", () => {
  const c = fixture({ ratingWritable: false });
  assert.ok(c.ui.mediaRatingControl.querySelectorAll().every(button => button.disabled));
  assert.equal(c.ui.mediaProtectButton.disabled, false);
  assert.equal(c.ui.mediaArchiveButton.disabled, false);
  assert.ok(c.ui.mediaRotationControl.querySelectorAll().every(button => !button.disabled));
});

test("real markup binds each unavailable reason once and both UI languages supply text", () => {
  const html = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/index.html"), "utf8");
  for (const [field, , reason] of fields) {
    assert.equal(html.split(`id="media-${field}-unavailable"`).length - 1, 1);
    assert.ok(source.includes(`${reason}: byId("media-${field}-unavailable")`));
  }
  assert.equal((source.match(/mediaMetadataReadOnly: /g) || []).length, 2);
});
