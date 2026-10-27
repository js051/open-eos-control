(function (root, factory) {
  const imports = factory();
  if (typeof module === "object" && module.exports) module.exports = imports;
  root.OpenEOSLutImport = imports;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // Page-owned LUT selection/read lifetime only. The app retains native picker
  // tickets, rendering resources and its one shared state snapshot. This owner
  // never acquires camera-operation, session, Stop or acknowledgement duties.
  function createController({ state, readBytes, parseBytes, prepareRenderer,
    renderFrame, reportSuccess, reportFailure, renderState }) {
    function cancel() {
      if (!state.monitorLutImportOwner) return;
      state.monitorLutImportOwner = null;
      // Blob.arrayBuffer cannot be aborted: keep the physical read represented
      // until it actually settles rather than admitting another parallel read.
      renderState();
    }

    function select(file) {
      if (!file) return;
      // One physical read and one latest queued replacement, including when an
      // older selection has been logically cancelled but its read still runs.
      state.monitorLutImportOwner = { file };
      state.monitorLutImportError = null;
      renderState();
      return readPending();
    }

    async function readPending() {
      if (state.monitorLutReadOwner || !state.monitorLutImportOwner) return;
      const owner = state.monitorLutImportOwner;
      state.monitorLutReadOwner = owner;
      const current = () => state.monitorLutImportOwner === owner;
      try {
        const bytes = await readBytes(owner.file);
        if (!current()) return;
        const parsed = parseBytes(bytes, owner.file.name);
        if (!current()) return;
        prepareRenderer();
        if (!current()) return;
        state.monitorSettings = { ...state.monitorSettings, cubeLut: parsed };
        state.monitorLutRevision = (state.monitorLutRevision || 0) + 1;
        state.monitorFrameEvidence = null;
        state.monitorAnalysisError = null;
        renderFrame();
        reportSuccess();
      } catch (error) {
        if (!current()) return;
        const message = error instanceof Error ? error.message : String(error);
        state.monitorLutImportError = message;
        reportFailure(message);
      } finally {
        state.monitorLutReadOwner = null;
        if (current()) state.monitorLutImportOwner = null;
        renderState();
        if (state.monitorLutImportOwner) void readPending();
      }
    }

    return { select, cancel };
  }

  return { createController };
});
