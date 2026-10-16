'use strict';
const assert=require('node:assert/strict'),test=require('node:test');
const p=require('../open_eos_bridge/static/recording-lifecycle.js');
const vectors=require('../../contracts/recording-lifecycle/v1/recording-vectors.json');
for(const x of vectors.observations)test(x.name,()=>assert.equal(p.validObservation(x.response,'ccapi'),x.valid));
for(const x of vectors.ownership)test(x.name,()=>assert.deepEqual(p.ownership(x.input),x.expected));
test('outer/nested/version/status mismatch never produce ACK',()=>{
 const response={version:1,engine:'ccapi',operation:'stop',responseEvidence:'response-received',observation:p.empty('ccapi')};
 for(const [status,value]of [[206,response],[200,{...response,version:true}],[200,{...response,operation:'start'}],[200,{...response,observation:p.empty('libgphoto2')}]])assert.equal(p.parseOutcome({status,engine:'ccapi',operation:'stop',response:value}).responseEvidence,'unconfirmed');
});
test('exact schema rejects unknown fields and prototype engine names safely',()=>{
 const observed={...p.empty('ccapi'),observationEvidence:'observed',recording:false,source:'ccapi-event'};
 assert.equal(p.validObservation({...observed,extra:true},'ccapi'),false);
 assert.equal(p.validObservation({...observed,engine:'__proto__'},'__proto__'),false);
 const response={version:1,engine:'ccapi',operation:'stop',responseEvidence:'response-received',observation:p.empty('ccapi'),extra:true};
 assert.equal(p.parseOutcome({status:200,engine:'ccapi',operation:'stop',response}).responseEvidence,'unconfirmed');
});
