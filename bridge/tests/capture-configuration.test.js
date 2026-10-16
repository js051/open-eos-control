"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const configuration = require("../open_eos_bridge/static/capture-configuration.js");
const vectors = JSON.parse(fs.readFileSync(path.join(__dirname, "../../contracts/capture-configuration/v1/capture-configuration-vectors.json"), "utf8"));
for (const row of vectors.receipt) test(`receipt ${row.id}`, () => assert.equal(configuration.validReceipt(row.input), row.valid));
for (const row of vectors.outcome) test(row.id, () => assert.deepEqual(configuration.outcome(row.input), row.expected));
for (const row of vectors.ownership) test(row.id, () => assert.deepEqual(configuration.ownership(row.input), row.expected));
for (const row of vectors.recovery) test(row.id, () => { assert.equal(configuration.canRecover(row.input), row.allowed); assert.equal(row.writes, 0); });
const naming = { stillFilenameMode: "preset_code", stillFilenameModeOptions: ["preset_code", "usersetting1", "usersetting2"],
  stillUserSetting1: "IMG_", stillUserSetting2: "IMG", movieIndex: "A_", movieReelNumber: 1,
  movieReelRange: { minimum: 1, maximum: 9999, step: 1 }, movieClipNumber: 1,
  movieClipRange: { minimum: 1, maximum: 999, step: 1 }, movieUserDefined: "ABCDE" };
module.exports = { naming };
test("only a complete strict naming group is accepted", () => {
  assert.equal(configuration.validNaming(naming), true);
  for (const key of Object.keys(naming)) { const partial = { ...naming }; delete partial[key]; assert.equal(configuration.validNaming(partial), false, key); }
  for (const invalid of [true, 1.5, "1", 0, 10000]) assert.equal(configuration.validNaming({ ...naming, movieReelNumber: invalid }), false);
  assert.equal(configuration.validNaming({ ...naming, stillFilenameModeOptions: ["preset_code", "preset_code"] }), false);
  assert.equal(configuration.validNaming({ ...naming, movieClipRange: { minimum: 1, maximum: 999, step: 0 } }), false);
});
test("directory readback is bounded unique current state, not a created-folder receipt", () => {
  assert.equal(configuration.validSelection({ value: "100EOSXX", ability: ["100EOSXX", "101ABCDE"] }), true);
  for (const value of [{ value: "100EOSXX", ability: [] }, { value: "100EOSXX", ability: ["100EOSXX", "100EOSXX"] },
    { value: "999OTHER", ability: ["100EOSXX"] }, { value: "ABCDE", ability: ["ABCDE"] },
    { value: "100EOSXX", ability: Array.from({ length: 257 }, (_, i) => `${String(i).padStart(3, "0")}EOSXX`) }]) {
    assert.equal(configuration.validSelection(value), false);
  }
});
test("transfer reads a bounded complete response and sends one logical request with redirect disabled", async () => {
  const previous = global.fetch, calls = [];
  global.fetch = async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify({ name: "ABCDE" }), { status: 200 }); };
  try {
    assert.deepEqual(await configuration.transfer("/synthetic", { method: "POST", payload: { name: "ABCDE" } }), { status: 200, body: { name: "ABCDE" } });
    assert.equal(calls.length, 1); assert.equal(calls[0].init.redirect, "error"); assert.equal(calls[0].init.credentials, "omit");
    assert.equal(calls[0].init.method, "POST");
    global.fetch = async () => new Response("x".repeat(65537), { status: 502 });
    await assert.rejects(configuration.transfer("/synthetic"), /exceeds limit/);
    global.fetch = async () => new Response("{", { status: 502 });
    await assert.rejects(configuration.transfer("/synthetic"), SyntaxError);
    global.fetch = async () => new Response(new Uint8Array([255]), { status: 502 });
    await assert.rejects(configuration.transfer("/synthetic"), TypeError);
  } finally { global.fetch = previous; }
});
const exact = JSON.parse(fs.readFileSync(path.join(__dirname, '../../contracts/capture-configuration/v1/exact-value-vectors.json'), 'utf8'));
for (const row of exact.receipt) test(`exact receipt ${row.id}`, () => assert.equal(configuration.validReceipt(row.input), row.valid));
for (const row of exact.naming) test(`exact naming ${row.id}`, () => assert.equal(configuration.validNaming(row.value), row.valid));
for (const row of exact.directorySelection) test(`exact selection ${row.id}`, () => assert.equal(configuration.validSelection(row.value), row.valid));
test('transfer rejects duplicate nested keys and lone escaped surrogate identities', async () => {
  const previous = global.fetch;
  try {
    for (const body of ['{"error":{"code":"OTHER","code":"CAPTURE_CONFIG_READBACK_FAILED"}}',
      '{"error":{"captureConfigReceipt":{"stage":"unknown","stage":"acknowledged"}}}', '{"value":"\\ud800"}',
      '{"receipt":{"field":"a","\\u0066ield":"b"}}']) {
      global.fetch = async () => new Response(body, { status: 502 });
      await assert.rejects(configuration.transfer('/synthetic'), /Ambiguous configuration JSON/);
    }
  } finally { global.fetch = previous; }
});
