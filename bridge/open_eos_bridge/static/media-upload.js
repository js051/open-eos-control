(function (root, factory) {
  const upload = factory();
  if (typeof module === "object" && module.exports) module.exports = upload;
  root.OpenEOSMediaUpload = upload;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // A picker belongs to its opening connection object, never its reusable ID.
  // This owner holds metadata only. File bytes and credentials are not retained.
  function createOwner(session) {
    return { session, consumed: false, source: null, dispatched: false,
      cancelled: false, retired: false, receipt: null };
  }

  function admit(owner, file, { currentOwner, session, supported = true, busy = false } = {}) {
    if (!owner || owner !== currentOwner || owner.session !== session || !session ||
        owner.consumed || owner.cancelled || owner.retired || !supported || busy) return false;
    owner.consumed = true;
    if (!file || typeof file.name !== "string" || !file.name ||
        !Number.isSafeInteger(file.size) || file.size <= 0 || file.size > 4294967295) return false;
    owner.source = Object.freeze({ name: file.name, sizeBytes: file.size });
    return true;
  }

  function dispatch(owner) {
    if (!owner?.source || owner.dispatched || owner.cancelled || owner.retired) return false;
    // Set before send(): an exception or missing acknowledgment cannot prove
    // that the camera did not receive a request. No automatic retry is allowed.
    owner.dispatched = true;
    return true;
  }

  // Preserve raw numeric tokens and reject duplicate receipt fields. JSON.parse
  // alone turns 4.0/4e0/rounded fractions into the integer 4. The Bridge contract
  // emits an integer number, not a numeric string or an approximate size.
  function parseReceipt(text) {
    if (typeof text !== "string" || text.length > 262144) return null;
    let item;
    try { item = JSON.parse(text); } catch (_) { return null; }
    if (!item || typeof item !== "object" || Array.isArray(item)) return null;
    let cursor = 0;
    let sizeToken = null;
    const whitespace = () => { while (cursor < text.length && /\s/.test(text[cursor])) cursor += 1; };
    const stringEnd = () => {
      cursor += 1;
      while (cursor < text.length) {
        if (text[cursor] === "\\") cursor += 2;
        else if (text[cursor++] === '"') return;
      }
    };
    const value = (depth) => {
      if (depth > 64) throw new TypeError("Receipt nesting limit exceeded");
      whitespace();
      const character = text[cursor];
      if (character === '"') { stringEnd(); return; }
      if (character === "{") {
        cursor += 1; whitespace();
        const seen = new Set();
        while (text[cursor] !== "}") {
          const start = cursor; stringEnd();
          const key = JSON.parse(text.slice(start, cursor));
          if (seen.has(key)) throw new TypeError("Duplicate receipt key");
          seen.add(key); whitespace(); cursor += 1; whitespace();
          const tokenStart = cursor;
          value(depth + 1);
          if (depth === 0 && key === "sizeBytes") sizeToken = text.slice(tokenStart, cursor).trim();
          whitespace();
          if (text[cursor] !== ",") break;
          cursor += 1; whitespace();
        }
        cursor += 1;
      } else if (character === "[") {
        cursor += 1; whitespace();
        while (text[cursor] !== "]") {
          value(depth + 1); whitespace();
          if (text[cursor] !== ",") break;
          cursor += 1; whitespace();
        }
        cursor += 1;
      } else {
        while (cursor < text.length && !/[\s,}\]]/.test(text[cursor])) cursor += 1;
      }
    };
    // Syntax has already been checked by JSON.parse. Walk all nested objects
    // too, so duplicate unknown fields cannot create cross-platform ambiguity.
    try { value(0); } catch (_) { return null; }
    if (!/^[1-9][0-9]*$/.test(sizeToken || "")) return null;
    if (typeof item.id !== "string" || !item.id ||
        typeof item.name !== "string" || !item.name ||
        typeof item.kind !== "string" || !item.kind ||
        !Number.isSafeInteger(item.sizeBytes) || item.sizeBytes <= 0 || item.sizeBytes > 4294967295) return null;
    return Object.freeze({ id: item.id, name: item.name, kind: item.kind, sizeBytes: item.sizeBytes });
  }

  async function readResponseText(response) {
    if (!response.body?.getReader) throw new TypeError("Upload receipt stream unavailable");
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let bytes = 0, text = "";
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        if (bytes > 262144) throw new TypeError("Upload receipt exceeds limit");
        text += decoder.decode(value, { stream: true });
      }
      return text + decoder.decode();
    } catch (error) {
      try { await reader.cancel(); } catch (_) { /* Keep the original failure. */ }
      throw error;
    } finally { reader.releaseLock(); }
  }

  function asciiFold(value) {
    return value.replace(/[A-Z]/g, (character) => character.toLowerCase());
  }

  function observeReceipt(owner, { status, text, sameRequest = true } = {}) {
    if (!owner || !owner.dispatched || !sameRequest || status !== 201) return false;
    const receipt = parseReceipt(text);
    if (!receipt || asciiFold(receipt.name) !== asciiFold(owner.source.name) ||
        receipt.sizeBytes !== owner.source.sizeBytes) return false;
    // This is the response to this write. A listing, even with identical
    // name/size/ID, is deliberately not accepted by this API.
    owner.receipt ||= receipt;
    return true;
  }

  function outcome(owner) {
    return owner?.receipt ? "confirmed" : owner?.dispatched ? "uncertain" : "not-sent";
  }

  function cancel(owner, { retire = false } = {}) {
    if (!owner) return;
    owner.cancelled = true;
    owner.retired ||= retire;
  }

  function canPublish(owner, currentOwner, session) {
    return Boolean(owner && !owner.retired && owner === currentOwner && owner.session === session);
  }

  return Object.freeze({ createOwner, admit, dispatch, parseReceipt, readResponseText, observeReceipt, outcome, cancel, canPublish });
});
