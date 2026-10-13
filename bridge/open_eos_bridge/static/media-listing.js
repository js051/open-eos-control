(function (root, factory) {
  const listing = factory();
  if (typeof module === "object" && module.exports) module.exports = listing;
  root.OpenEOSMediaListing = listing;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MEDIA_TYPE = "application/vnd.open-eos.media-list.v1+ndjson";
  const MAX_LINE_BYTES = 65_536;
  const MAX_ITEMS = 100_000;
  const MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
  const safe = (value) => Number.isSafeInteger(value) && value >= 0;
  const object = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
  function invalid(message = "Invalid or incomplete camera media listing.") {
    const error = new Error(message);
    error.code = "INVALID_MEDIA_LISTING";
    return error;
  }
  function aborted() {
    const error = new Error("Media loading cancelled.");
    error.name = "AbortError";
    return error;
  }
  function parseJson(text) {
    const value = JSON.parse(text);
    // JSON.parse silently takes the last duplicate key and accepts lone escaped
    // surrogates. Neither is safe for exact request or media identity binding.
    // Scan the already grammar-checked text without recursive parsing.
    const stack = [];
    for (let index = 0; index < text.length; index++) {
      const character = text[index];
      if (character === "{") stack.push({ keys: new Set(), key: true });
      else if (character === "[") stack.push({ keys: null });
      else if (character === "}" || character === "]") stack.pop();
      else if (character === "," && stack.at(-1)?.keys) stack.at(-1).key = true;
      else if (character === '"') {
        const start = index++;
        while (text[index] !== '"') {
          if (text[index] === "\\") index++;
          index++;
        }
        const decoded = JSON.parse(text.slice(start, index + 1));
        if (/[\uD800-\uDFFF]/u.test(decoded)) throw invalid();
        const object = stack.at(-1);
        if (object?.keys && object.key) {
          if (object.keys.has(decoded)) throw invalid();
          object.keys.add(decoded);
          object.key = false;
        }
      }
    }
    return value;
  }
  function validateItems(items, ids = new Set()) {
    if (!Array.isArray(items) || items.length > MAX_ITEMS) throw invalid();
    for (const item of items) {
      if (!object(item) || typeof item.id !== "string" || !item.id.length ||
          typeof item.name !== "string" || typeof item.kind !== "string" || ids.has(item.id)) throw invalid();
      ids.add(item.id);
    }
    return items;
  }

  // This parser consumes only the response to the original GET. A legacy JSON
  // reply never triggers another traversal, and browsing completion is never
  // strict inventory evidence. Both partial revisions and input frames are bounded.
  async function readResponse(response, { sessionId, limit = null, signal, onSnapshot = () => {} } = {}, errorResponse = false) {
    if (signal?.aborted) throw aborted();
    const contentType = response.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase();
    if ((contentType !== MEDIA_TYPE && contentType !== "application/json") ||
        (errorResponse && contentType !== "application/json")) {
      void response.body?.cancel?.().catch(() => {});
      throw invalid();
    }
    const reader = response.body?.getReader?.();
    if (!reader) throw invalid();
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let rejectAbort;
    const cancelled = new Promise((_, reject) => { rejectAbort = reject; });
    const cancel = () => {
      rejectAbort(aborted());
      void reader.cancel().catch(() => {});
    };
    signal?.addEventListener("abort", cancel, { once: true });
    let succeeded = false;
    try {
      if (contentType === "application/json") {
        let bytes = 0;
        const parts = [];
        while (true) {
          const { done, value } = await Promise.race([reader.read(), cancelled]);
          if (signal?.aborted) throw aborted();
          if (done) break;
          bytes += value.byteLength;
          if (bytes > (errorResponse ? MAX_LINE_BYTES : MAX_SNAPSHOT_BYTES)) throw invalid("Camera media listing exceeds the supported size.");
          parts.push(decoder.decode(value, { stream: true }));
        }
        parts.push(decoder.decode());
        const value = parseJson(parts.join(""));
        if (!object(value)) throw invalid();
        if (errorResponse) { succeeded = true; return value; }
        validateItems(value.items);
        if (limit !== null && value.items.length > limit) throw invalid();
        succeeded = true;
        return { items: value.items };
      }
      const scope = limit === null ? "full" : "recent";
      let requestId = null, seq = 0, revision = 0, lastItems = null;
      let pending = null, terminal = null;
      const line = new Uint8Array(MAX_LINE_BYTES);
      let used = 0;
      const record = (bytes) => {
        const value = parseJson(decoder.decode(bytes));
        if (!object(value) || terminal || !safe(value.seq) || value.seq !== seq++ ||
            value.sessionId !== sessionId || value.scope !== scope ||
            typeof value.requestId !== "string" || !value.requestId.length ||
            new TextEncoder().encode(value.requestId).byteLength > 256) throw invalid();
        if (requestId === null) {
          if (value.type !== "start" || value.seq !== 0 || value.limit !== limit) throw invalid();
          requestId = value.requestId;
          return;
        }
        if (value.requestId !== requestId) throw invalid();
        if (value.type === "snapshot") {
          if (!safe(value.revision) || value.revision !== revision + 1 ||
              !safe(value.offset) || !safe(value.total) || value.total > MAX_ITEMS ||
              (limit !== null && value.total > limit) || !Array.isArray(value.items) ||
              value.items.length > 128 || typeof value.last !== "boolean") throw invalid();
          if (!pending) pending = { items: [], ids: new Set(), total: value.total, bytes: 0 };
          pending.bytes += bytes.byteLength;
          if (pending.bytes > MAX_SNAPSHOT_BYTES || value.total !== pending.total ||
              value.offset !== pending.items.length || value.offset + value.items.length > value.total ||
              (!value.last && !value.items.length)) throw invalid();
          validateItems(value.items, pending.ids);
          pending.items.push(...value.items);
          if (value.last) {
            if (pending.items.length !== value.total) throw invalid();
            revision = value.revision;
            lastItems = pending.items;
            pending = null;
            onSnapshot(lastItems);
          } else if (pending.items.length === value.total) throw invalid();
          return;
        }
        if (value.type === "complete") {
          if (pending || lastItems === null || !safe(value.revision) || value.revision !== revision ||
              !safe(value.count) || value.count !== lastItems.length || value.scopeComplete !== true) throw invalid();
        } else if (value.type === "error") {
          if (typeof value.code !== "string" || !value.code || typeof value.message !== "string" || !value.message) throw invalid();
        } else throw invalid();
        terminal = value;
      };
      while (true) {
        const { done, value } = await Promise.race([reader.read(), cancelled]);
        if (signal?.aborted) throw aborted();
        if (done) break;
        for (let offset = 0; offset < value.byteLength;) {
          const newline = value.indexOf(10, offset);
          const end = newline < 0 ? value.byteLength : newline + 1;
          const length = end - offset;
          if (used + length > MAX_LINE_BYTES) throw invalid("Camera media listing frame exceeds the supported size.");
          line.set(value.subarray(offset, end), used);
          used += length;
          if (newline >= 0) {
            record(line.subarray(0, used));
            used = 0;
          }
          offset = end;
        }
      }
      if (used || !terminal) throw invalid();
      if (terminal.type === "error") {
        const error = new Error(terminal.message);
        error.code = terminal.code;
        throw error;
      }
      succeeded = true;
      return { items: lastItems };
    } finally {
      signal?.removeEventListener("abort", cancel);
      if (!succeeded) void reader.cancel().catch(() => {});
      reader.releaseLock();
    }
  }
  return { MEDIA_TYPE, MAX_LINE_BYTES, MAX_ITEMS, MAX_SNAPSHOT_BYTES, readResponse,
    readError: (response, options) => readResponse(response, options, true) };
});
