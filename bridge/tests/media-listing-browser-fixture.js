"use strict";

const assert = require("node:assert/strict");

const STREAM_TYPE = "application/vnd.open-eos.media-list.v1+ndjson";

// Test-only date decoration of the real Bridge reply. Retain every stream frame,
// identity, revision, offset and terminal; never convert browsing to inventory.
function datedListingResponse(body, responseHeaders, dateForName) {
  const headers = Object.fromEntries(Object.entries(responseHeaders).map(([name, value]) => [name.toLowerCase(), value]));
  const mediaType = headers["content-type"]?.split(";", 1)[0].trim().toLowerCase();
  const dated = (payload) => {
    assert.ok(Array.isArray(payload.items), "The real listing must contain an item array");
    return { ...payload, items: payload.items.map((item) => ({ ...item, captureTime: dateForName(item.name) })) };
  };
  let decorated;
  if (mediaType === "application/json") {
    decorated = JSON.stringify(dated(JSON.parse(body)));
  } else {
    assert.equal(mediaType, STREAM_TYPE, "Unexpected real listing response format");
    // Retain delimiters and non-snapshot records byte-for-byte, including an
    // incomplete final line. The fixture must not repair a broken stream.
    decorated = body.split(/(\r?\n)/).map((line) => {
      if (!line.trim()) return line;
      const record = JSON.parse(line);
      return record.type === "snapshot" ? JSON.stringify(dated(record)) : line;
    }).join("");
  }
  // route.fetch() supplies a decoded body. Its old length/transport encoding
  // cannot describe the decorated response fulfilled by the browser harness.
  delete headers["content-encoding"];
  delete headers["transfer-encoding"];
  headers["content-length"] = String(Buffer.byteLength(decorated));
  return { body: decorated, headers };
}

module.exports = { datedListingResponse };
