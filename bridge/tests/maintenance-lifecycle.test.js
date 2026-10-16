"use strict";
const assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),test=require("node:test");
const policy=require("../open_eos_bridge/static/maintenance-lifecycle.js");
const vectors=JSON.parse(fs.readFileSync(path.join(__dirname,"../../contracts/maintenance-lifecycle/v1/maintenance-vectors.json"),"utf8"));
for(const row of vectors.clock)test(`clock ${row.name}`,()=>{
  assert.equal(policy.validReference(row.reference)&&policy.validClock(row.observation),row.valid);
  assert.equal(policy.clockMatches(row.reference,row.observation),row.matched);
});
for(const row of vectors.wire)test(`wire ${row.name}`,()=>{
  const result=policy.parseOutcome(row);assert.equal(result.responseEvidence==="response-received",row.expectedResponse);
  assert.equal(result.clockReadEvidence,row.expectedClockRead);
});
for(const [i,row]of vectors.ownership.entries())test(`ownership ${i}`,()=>assert.deepEqual(policy.ownership(row.input),{canPublish:row.canPublish,canRestore:row.canRestore}));
test("read-only observation never invents original action evidence",()=>{
  const response={version:1,engine:"ccapi",operation:"clock-sync",observationEvidence:"observed",clockObservation:vectors.clock[0].observation};
  const result=policy.parseObservation({status:200,engine:"ccapi",operation:"clock-sync",response});
  assert.equal(result.observationEvidence,"observed");assert.equal(result.responseEvidence,undefined);
  for(const patch of [{version:true},{engine:"gphoto2"},{operation:"sleep"},{clockObservation:null}])
    assert.equal(policy.parseObservation({status:200,engine:"ccapi",operation:"clock-sync",response:{...response,...patch}}).observationEvidence,"unavailable");
});
const transport = require('../open_eos_bridge/static/capture-configuration.js');
test('maintenance wire integer lexemes preserve independent response but reject decimal/exponent evidence', async()=>{
 const previous=global.fetch;
 const body={version:1,engine:'ccapi',operation:'clock-sync',autoPowerOff:false,responseEvidence:'response-received',clockReadEvidence:'matched',
 clockReference:{earliestEpochSeconds:1699999990,latestEpochSeconds:1700000010,utcOffsetMinutes:0,daylightSaving:false},
 clockObservation:{epochSeconds:1700000000,utcOffsetMinutes:0,daylightSaving:false}};
 try{
  for(const token of ['1700000000.0','1.7e9']){
   global.fetch=async()=>new Response(JSON.stringify(body).replace('"epochSeconds":1700000000',`"epochSeconds":${token}`));
   const response=await transport.transfer('/synthetic',{strictSettings:true,strictIntegerTokens:true});
   const parsed=policy.parseOutcome({status:200,engine:'ccapi',operation:'clock-sync',autoPowerOff:false,response:response.body});
   assert.equal(parsed.responseEvidence,'response-received');assert.equal(parsed.clockReadEvidence,'unavailable');
  }
  global.fetch=async()=>new Response(JSON.stringify(body).replace('"version":1','"version":1.0'));
  const response=await transport.transfer('/synthetic',{strictSettings:true,strictIntegerTokens:true});
  assert.equal(policy.parseOutcome({status:200,engine:'ccapi',operation:'clock-sync',autoPowerOff:false,response:response.body}).responseEvidence,'unknown');
 }finally{global.fetch=previous;}
});
test('maintenance strict HTTP rejects partial, encoded, ambiguous length, duplicate, truncated and oversized responses',async()=>{
 const previous=global.fetch,calls=[];
 try{
  for(const [body,headers] of [['{}',{'content-range':'bytes 0-1/2'}],['{}',{'content-encoding':'gzip'}],['{}',{'content-length':'3'}],
  ['{}',{'content-length':'2','transfer-encoding':'chunked'}],['{"version":1,"version":1}',{}],['{',{}],['x'.repeat(65537),{}]]){
   global.fetch=async(url,init)=>{calls.push({url,init});return new Response(body,{headers});};
   const before=calls.length;await assert.rejects(transport.transfer('/synthetic/maintenance/sleep/outcome',{method:'POST',payload:{autoPowerOff:false},strictSettings:true,strictIntegerTokens:true}));
   assert.equal(calls.length,before+1);assert.equal(calls.at(-1).init.redirect,'error');assert.equal(calls.at(-1).init.credentials,'omit');
  }
 }finally{global.fetch=previous;}
});
test('maintenance event Stop accepts only the explicit empty 204 contract',async()=>{
 const previous=global.fetch;
 try{
  global.fetch=async()=>new Response(null,{status:204});
  assert.deepEqual(await transport.transfer('/synthetic/events',{method:'DELETE',strictSettings:true,emptyResponseStatus:204}),{status:204,body:null});
  for(const headers of [{'content-length':'1'},{'transfer-encoding':'chunked'}]){
   global.fetch=async()=>new Response(null,{status:204,headers});
   await assert.rejects(transport.transfer('/synthetic/events',{method:'DELETE',strictSettings:true,emptyResponseStatus:204}));
  }
 }finally{global.fetch=previous;}
});
