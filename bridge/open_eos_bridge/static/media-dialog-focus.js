(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.OpenEOSMediaDialogFocus = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // UI focus ownership only: no camera requests, mutations, Stop or ACK duties.
  function isTarget(button) {
    return button?.tagName === "BUTTON" && button.isConnected && !button.disabled &&
      !button.closest("[hidden], [inert]") && button.getClientRects().length > 0;
  }

  function createController({ state, ui, document }) {
    function begin(kind, inherited = null) {
      const key = `media${kind}FocusOwner`;
      if (ui[`media${kind}Dialog`].open && state[key]?.session === state.session) return;
      const original = inherited?.session === state.session ? inherited.original : document.activeElement;
      state[key] = {
        session: state.session,
        original,
        itemId: inherited?.session === state.session ? inherited.itemId : original?.dataset?.mediaId,
        role: inherited?.session === state.session ? inherited.role : original?.dataset?.mediaAction,
      };
    }

    function restore(owner, eligible, closedDialog, preventScroll = true) {
      if (!eligible || !owner || owner.session !== state.session ||
          state.mediaPreviewFocusOwner || state.mediaDetailsFocusOwner ||
          document.querySelector("dialog[open]")) return;
      // Firefox may retain Close as activeElement synchronously after native close().
      // Only the exact retired dialog can supply that focus; never override a newer choice.
      const retainedDialogFocus = closedDialog && !closedDialog.open && closedDialog.contains(document.activeElement);
      if (document.activeElement !== document.body && document.activeElement !== owner.original && !retainedDialogFocus) return;
      const cardInvoker = Boolean(owner.itemId && owner.role);
      const currentCards = cardInvoker && state.activeView === "media" &&
        state.mediaRenderOwner?.session === state.session && state.media.some(item => item.id === owner.itemId)
        ? Array.from(ui.mediaList.querySelectorAll("button[data-media-id][data-media-action]")) : [];
      let target = isTarget(owner.original) && (!cardInvoker || currentCards.includes(owner.original))
        ? owner.original : null;
      if (!target && cardInvoker) {
        target = currentCards.find(button => button.dataset.mediaId === owner.itemId &&
          button.dataset.mediaAction === owner.role && isTarget(button));
      }
      target?.focus({ preventScroll });
    }

    function setActionDisabled(kind, button, disabled) {
      const owner = state[`media${kind}FocusOwner`];
      const dialog = ui[`media${kind}Dialog`];
      const close = ui[`media${kind}Close`];
      if (disabled && owner && owner.session === state.session && dialog.open &&
          document.activeElement === button && dialog.contains(button) && isTarget(close)) {
        close.focus({ preventScroll: true });
      }
      button.disabled = disabled;
    }

    return { begin, restore, setActionDisabled };
  }

  return { isTarget, createController };
});
