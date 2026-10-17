"use strict";
// Deterministic DOM ownership regressions, not real-browser or camera evidence.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");
function extract(name) {
  const match = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  return match ? source.slice(match.index, source.indexOf("\n  }", match.index) + 4) : "";
}
function fixture() {
  const document = { body: {}, activeElement: null, querySelector: () => null };
  document.activeElement = document.body;
  function button(id = "a", role = "manage") {
    return { tagName: "BUTTON", isConnected: true, hidden: false, disabled: false,
      dataset: { mediaId: id, mediaAction: role }, closest: () => null,
      getClientRects: () => [1], focus() { document.activeElement = this; } };
  }
  const state = { session: { id: "same-id" }, activeView: "media", media: [{ id: "a" }] };
  state.mediaRenderOwner = { session: state.session };
  let cards = [];
  const ui = { mediaList: { querySelectorAll: () => cards } };
  for (const kind of ["Preview", "Details"]) {
    const close = button();
    const dialog = { open: false, contains: node => node === close || node?.dialog === dialog,
      close() { this.open = false; document.activeElement = document.body; } };
    ui[`media${kind}Dialog`] = dialog;
    ui[`media${kind}Close`] = close;
  }
  const context = vm.createContext({ state, ui, document,
    clearMediaPreview() {}, clearMediaDetails() {} });
  vm.runInContext(["mediaDialogFocusTarget", "beginMediaDialogFocus", "restoreMediaDialogFocus",
    "setMediaDialogActionDisabled", "closeMediaPreview", "closeMediaDetails"].map(extract).join("\n"), context);
  function open(kind, original) {
    const owner = { session: state.session, original, itemId: original.dataset.mediaId, role: original.dataset.mediaAction };
    cards = [original];
    state[`media${kind}FocusOwner`] = owner;
    ui[`media${kind}Dialog`].open = true;
    ui[`media${kind}Close`].focus();
    return owner;
  }
  return { context, state, ui, document, button, open, cards: values => { cards = values; } };
}
for (const kind of ["Preview", "Details"]) {
  test(`${kind}: detached opener restores exact current item and action`, () => {
    const c = fixture(), original = c.button(), replacement = c.button();
    c.open(kind, original); original.isConnected = false; c.cards([c.button("b"), replacement]);
    c.context[`closeMedia${kind}`]();
    assert.equal(c.document.activeElement, replacement);
  });
  test(`${kind}: prefers original connected invoker`, () => {
    const c = fixture(), original = c.button(); c.open(kind, original); c.cards([original, c.button()]);
    c.context[`closeMedia${kind}`](); assert.equal(c.document.activeElement, original);
  });
  for (const reason of ["filtered", "disabled", "hidden", "role", "session", "view", "render", "deleted"]) {
    test(`${kind}: no fallback for ${reason}`, () => {
      const c = fixture(), original = c.button(), replacement = c.button(); c.open(kind, original);
      original.isConnected = false;
      if (reason === "disabled") replacement.disabled = true;
      if (reason === "hidden") replacement.getClientRects = () => [];
      if (reason === "role") replacement.dataset.mediaAction = "preview";
      if (reason === "session") c.state.session = { id: "same-id" };
      if (reason === "view") c.state.activeView = "live";
      if (reason === "render") c.state.mediaRenderOwner = null;
      if (reason === "deleted") c.state.media = [];
      c.cards(reason === "filtered" ? [] : [replacement]); c.context[`closeMedia${kind}`]();
      assert.equal(c.document.activeElement, c.document.body);
    });
  }
  test(`${kind}: focused busy action moves to Close before disabling`, () => {
    const c = fixture(); c.open(kind, c.button()); const action = c.button();
    action.dialog = c.ui[`media${kind}Dialog`]; action.focus();
    assert.equal(typeof c.context.setMediaDialogActionDisabled, "function");
    c.context.setMediaDialogActionDisabled(kind, action, true);
    assert.equal(c.document.activeElement, c.ui[`media${kind}Close`]); assert.equal(action.disabled, true);
    const outside = c.button(); outside.focus(); c.context.setMediaDialogActionDisabled(kind, action, true);
    assert.equal(c.document.activeElement, outside);
  });
}
test("Escape uses synchronous helpers; queued close events cannot clear a reopened dialog", () => {
  for (const kind of ["Preview", "Details"]) {
    assert.match(source, new RegExp(`media${kind}Dialog.addEventListener\\("cancel",`));
    assert.doesNotMatch(source, new RegExp(`media${kind}Dialog.addEventListener\\("close", clearMedia${kind}`));
  }
});

for (const kind of ["Preview", "Details"]) {
  test(`${kind}: another dialog or a newer explicit focus choice is never overridden`, () => {
    for (const otherDialog of [false, true]) {
      const c = fixture(), original = c.button(); c.open(kind, original);
      const outside = c.button("outside");
      if (otherDialog) c.document.querySelector = () => ({ open: true });
      else outside.focus();
      let restored = 0; original.focus = () => { restored++; };
      c.context[`closeMedia${kind}`]();
      assert.equal(restored, 0);
    }
  });
}

for (const kind of ["Preview", "Details"]) {
  test(`${kind}: busy render from a retired session does not move focus`, () => {
    const c = fixture(); c.open(kind, c.button()); const action = c.button();
    action.dialog = c.ui[`media${kind}Dialog`]; action.focus();
    c.state.session = { id: "same-id" };
    c.context.setMediaDialogActionDisabled(kind, action, true);
    assert.equal(c.document.activeElement, action);
  });
  test(`${kind}: valid connected non-card invoker restores without inventing a gallery target`, () => {
    const c = fixture(), original = c.button(); original.dataset = {};
    c.open(kind, original); c.state.activeView = "live"; c.state.mediaRenderOwner = null;
    c.context[`closeMedia${kind}`](); assert.equal(c.document.activeElement, original);
  });
}

for (const kind of ["Preview", "Details"]) {
  test(`${kind}: native close retaining focus inside the just-closed dialog restores the current card`, () => {
    const c = fixture(), original = c.button(), replacement = c.button();
    c.open(kind, original); original.isConnected = false; c.cards([replacement]);
    // Firefox can retain the active Close element synchronously after close().
    c.ui[`media${kind}Dialog`].close = function () { this.open = false; };
    c.context[`closeMedia${kind}`]();
    assert.equal(c.document.activeElement, replacement);
  });
  test(`${kind}: native close focusing a different dialog cannot authorize restoration`, () => {
    const c = fixture(), original = c.button(); c.open(kind, original);
    const other = c.button("other-dialog");
    c.ui[`media${kind}Dialog`].close = function () { this.open = false; other.focus(); };
    c.context[`closeMedia${kind}`]();
    assert.equal(c.document.activeElement, other);
  });
}
