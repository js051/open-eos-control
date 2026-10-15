"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const policy = require("../open_eos_bridge/static/shooting-settings.js");
const vectors = JSON.parse(fs.readFileSync(path.join(__dirname, "../../contracts/shooting-setting/v1/shooting-setting-vectors.json")));
for (const row of vectors.wire) test(`wire ${row.id}`, () => {
  const result = policy.parseOutcome(row.input);
  assert.deepEqual({writeEvidence: result.writeEvidence, readbackEvidence: result.readbackEvidence}, row.expected);
});
for (const [index,row] of vectors.ownership.entries()) test(`owner ${index}`, () => assert.deepEqual(policy.ownership(row.input),row.expected));
for (const [index,row] of vectors.outcome.entries()) test(`outcome ${index}`, () => assert.deepEqual(policy.outcome(row.input),row.expected));
const configuration = require("../open_eos_bridge/static/capture-configuration.js");
test("strict setting transport rejects ambiguous framing and complete-JSON ambiguity",async()=>{
  const original=global.fetch;
  try {
    for (const [headers,body] of [
      [{"content-range":"bytes 0-1/2"},"{}"],
      [{"content-length":"3"},"{}"],
      [{"content-length":"2","transfer-encoding":"chunked"},"{}"],
      [{"content-encoding":"gzip"},"{}"],
      [{},'{"version":1,"version":2}'],
      [{},'{"value":"\\ud800"}'],
    ]) {
      global.fetch=async()=>new Response(body,{status:200,headers});
      await assert.rejects(configuration.transfer("/synthetic",{strictSettings:true}));
    }
    let sent;
    global.fetch=async(_url,options)=>{sent=options;return new Response('{}',{status:200,headers:{"content-length":"2"}});};
    assert.deepEqual(await configuration.transfer("/synthetic",{method:"POST",payload:{value:"800"},strictSettings:true}),{status:200,body:{}});
    assert.equal(sent.redirect,"error");assert.equal(sent.credentials,"omit");assert.equal(sent.cache,"no-store");
  } finally {global.fetch=original;}
});


test("invalid setting representation cancels body without waiting for non-cooperative cleanup",async()=>{
  const original=global.fetch;
  try {
    for (const headers of [{"content-range":"bytes 0-1/2"},{"content-encoding":"gzip"}]) {
      let cancelled=0;
      const body=new ReadableStream({cancel(){cancelled+=1;return new Promise(()=>{});}});
      global.fetch=async()=>new Response(body,{status:200,headers});
      await assert.rejects(configuration.transfer("/synthetic",{strictSettings:true}),/Ambiguous setting response representation/);
      assert.equal(cancelled,1);
    }
  } finally {global.fetch=original;}
});
