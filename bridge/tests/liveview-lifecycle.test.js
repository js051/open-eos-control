'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const lifecycle=require('../open_eos_bridge/static/liveview-lifecycle.js');
const cases=require('../../contracts/liveview-lifecycle/v1/liveview-vectors.json').cases;
for(const c of cases)test(`shared: ${c.name}`,()=>assert.deepEqual(lifecycle.evaluate(c.input),c.expected));
function response({status=200,redirected=false,type='image/jpeg',length=null,range=null,chunks=[new Uint8Array([255,216,1,2,255,217])],hang=false}={}){
 let cancelled=0,released=0,read=0;
 const reader={async read(){if(hang)return new Promise(()=>{});return read<chunks.length?{done:false,value:chunks[read++]}:{done:true};},async cancel(){cancelled++;},releaseLock(){released++;}};
 return {status,redirected,headers:new Headers({'content-type':type,...(length!==null?{'content-length':length}:{}),...(range!==null?{'content-range':range}:{})}),body:{getReader:()=>reader,cancel:async()=>{cancelled++;}},counts:()=>({cancelled,released,read})};
}
test('complete chunked JPEG is bounded encoded evidence only',async()=>{const r=response({chunks:[new Uint8Array([255]),new Uint8Array([216,1,2,255]),new Uint8Array([217])]});const b=await lifecycle.readFrame(r);assert.equal(b.size,6);assert.equal(b.type,'image/jpeg');assert.deepEqual(r.counts(),{cancelled:0,released:1,read:3});});
for(const options of [{status:206},{range:'bytes 0-5/6'},{redirected:true},{type:'text/html'},{length:'0'},{length:'01'},{length:'7'},{length:'5'},{length:'9999999999999999'},{chunks:[new Uint8Array([255,216,255])]},{chunks:[new Uint8Array([0,0,255,217])]},{chunks:[new Uint8Array([255,216,1,2,255,217,0])]}])test(`reject and cancel body ${JSON.stringify(options)}`,async()=>{const r=response(options);await assert.rejects(lifecycle.readFrame(r));assert.equal(r.counts().cancelled,1);});
test('body size bounded even without Content-Length',async()=>{const r=response();await assert.rejects(lifecycle.readFrame(r,{maxBytes:5}),/byte limit/);assert.equal(r.counts().cancelled,1);});
test('preaborted request cancels unread response body',async()=>{const c=new AbortController();c.abort();const r=response();await assert.rejects(lifecycle.readFrame(r,{signal:c.signal}),{name:'AbortError'});assert.equal(r.counts().cancelled,1);});
test('read cancellation never turns truncated result into success',async()=>{const c=new AbortController(),r=response();const get=r.body.getReader;r.body.getReader=()=>{const reader=get();return{...reader,read:async()=>{c.abort();return{done:true};}};};await assert.rejects(lifecycle.readFrame(r,{signal:c.signal}),{name:'AbortError'});assert.ok(r.counts().cancelled>=1);assert.equal(r.counts().released,1);});
