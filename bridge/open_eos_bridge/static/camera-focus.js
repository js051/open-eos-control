(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.OpenEOSCameraFocus = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const STATUSES = new Set(["STANDBY", "FOCUSED", "UNFOCUSED", "SERVO_OFF", "FOCUSING", "UNKNOWN"]);
  const KINDS = new Set(["GENERIC", "NORMAL", "FACE", "TRACKING", "UNKNOWN"]);
  const clip = value => Math.max(0, Math.min(1, value));
  function normalize(value, imageAspectRatio) {
    if (!value || typeof value !== "object" || Array.isArray(value) ||
        value.magnification !== 1 || !Number.isFinite(value.imageAspectRatio) || value.imageAspectRatio <= 0 ||
        !Number.isFinite(imageAspectRatio) || imageAspectRatio <= 0 ||
        Math.abs(value.imageAspectRatio / imageAspectRatio - 1) > 0.02 ||
        !Array.isArray(value.frames) || value.frames.length > 512) return null;
    const frames = [];
    for (const frame of value.frames) {
      if (!frame || ![frame.left, frame.top, frame.right, frame.bottom].every(Number.isFinite)) continue;
      const [left, top, right, bottom] = [frame.left, frame.top, frame.right, frame.bottom].map(clip);
      if (right <= left || bottom <= top) continue;
      const kind = KINDS.has(frame.kind) ? frame.kind : "UNKNOWN";
      const status = kind !== "UNKNOWN" && STATUSES.has(frame.status) ? frame.status : "UNKNOWN";
      frames.push({ left, top, right, bottom, status, kind });
    }
    return { frames, magnification: 1, imageAspectRatio: value.imageAspectRatio };
  }
  async function readResponse(response) {
    const limit = 256 * 1024;
    const declared = response.headers.get("Content-Length");
    if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > limit)) {
      await response.body?.cancel();
      throw new Error("Focus information response exceeds its size limit.");
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error("Focus information requires a bounded response stream.");
    const chunks = [];
    let length = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        length += value.byteLength;
        if (length > limit) throw new Error("Focus information response exceeds its size limit.");
        chunks.push(value);
      }
      const bytes = new Uint8Array(length);
      let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
      return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    } catch (error) {
      try { await reader.cancel(); } catch (_) { /* Preserve the original parse/read error. */ }
      throw error;
    } finally {
      reader.releaseLock();
    }
  }
  return Object.freeze({ normalize, readResponse });
});
