"use strict";
// Deterministic host regression. Executes production API, frame reader, poll
// loop, Stop lifecycle, and focus handler. Fetch and rendering are controlled.
// The normal success shape is emitted by the production Bridge/CcapiEngine;
// malformed-response cases below are explicitly defensive protocol tests.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const root = path.resolve(__dirname, "../..");
const source = fs.readFileSync(path.join(root, "bridge/open_eos_bridge/static/app.js"), "utf8");
const actual = {status: 200, body: {accepted: true, direction: "NEAR", step: "SMALL"}};
function production(name) {
  const found = new RegExp(`^  (?:async )?function ${name}\\(`, "m").exec(source);
  assert.ok(found, name);
  return source.slice(found.index, source.indexOf("\n  }", found.index) + 4);
}
function gate() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise,resolve,reject}; }
async function turns() { for (let i=0;i<5;i++) await new Promise(setImmediate); }
function fixture() {
  const requests=[], frame=gate(), focus=gate(), stop=gate(), messages=[], errors=[], toasts=[];
  const state={session:{id:"synthetic-session-A"},liveActive:true,liveGeneration:1,refreshGeneration:0,
    activeLiveSource:"CCAPI_JPEG_POLLING",focusStep:"SMALL",busy:false,frameTimes:[],status:{}};
  state.liveViewStopOwner={session:state.session,confirmedStart:true};
  const f={state,requests,frame,focus,stop,messages,errors,toasts};
  const ctx=vm.createContext({state,Error,TypeError,AbortController,Headers,Response,performance,setTimeout,clearTimeout,
    FEATURES:{FOCUS_DRIVE:"FOCUS_DRIVE"}, featureSupported:()=>true, mediaTransferActive:()=>false,
    ui:{liveImage:{removeAttribute(){}},focusReticle:{hidden:true}},
    liveViewLifecycle:require(path.join(root,"bridge/open_eos_bridge/static/liveview-lifecycle.js")),
    mediaTransfer:{isAbortError:e=>e?.name==="AbortError"},maintenancePaused:()=>false,
    cancelCameraFocusInfo(){},stopRtpAudio(){},releaseObjectUrl(){},clearMonitoringLayers(){},
    renderLiveState(){},renderFrameIndicator(){},renderAvailability(){},
    setOperationState:(message,error=false)=>{f.operation={message,error};messages.push(f.operation);},
    captureError:e=>{errors.push({message:e.message,code:e.code});return e;},
    showToast:(message,error=false)=>toasts.push({message,error}),t:key=>key,
    fetch:(url,options)=>{requests.push({url,method:options.method,body:options.body});
      if(url.endsWith("/liveview/frame"))return frame.promise;
      if(url.endsWith("/focus/drive"))return focus.promise;
      if(url.endsWith("/liveview/stop"))return stop.promise;
      throw new Error("Unexpected request");},
  });
  const start=source.indexOf("  class ApiError extends Error {");
  const apiError=source.slice(start,source.indexOf("\n  }",start)+4);
  vm.runInContext(apiError+"\n"+["api","cameraInteractionBusy","beginCameraInteraction","driveFocus",
    "readDecodedLiveFrame","pollLiveView","stopLiveLoop","liveViewControlRequest","stopLiveView","adoptCameraStatus"].map(production).join("\n"),ctx);
  return Object.assign(f,{ctx});
}
for (const outcome of ["accepted", "network-failure"]) {
  test(`focus ${outcome} must not erase a later automatic Live View Stop owner`, async()=>{
    const f=fixture();
    const polling=f.ctx.pollLiveView(1);
    const driving=f.ctx.driveFocus("NEAR");
    assert.equal(f.state.busy,true);
    f.frame.reject(new TypeError("Synthetic browser network disconnect during frame read"));
    await turns();
    assert.equal(f.requests.length,3);
    assert.equal(f.state.liveActive,false);
    assert.equal(f.state.refreshGeneration,2);
    assert.equal(f.state.busy,true);
    const stopOwner=f.state.liveViewStopOwner;
    const before={busy:f.state.busy,operation:f.operation,generation:f.state.refreshGeneration};
    if(outcome==="accepted") f.focus.resolve(new Response(JSON.stringify(actual.body),{status:actual.status,headers:{"content-type":"application/json"}}));
    else f.focus.reject(new TypeError("Synthetic old focus response lost"));
    await driving;
    const observed={busy:f.state.busy,operation:f.operation,generation:f.state.refreshGeneration,
      stopPending:f.state.liveViewStopOwner===stopOwner&&Boolean(stopOwner.controlController),
      commands:f.requests.map(r=>({path:r.url.replace("synthetic-session-A","{id}"),method:r.method}))};
    // The older completion must leave the newer owner and frame error untouched.
    f.stop.resolve(new Response(JSON.stringify({active:false,liveViewStopUnconfirmed:false}),{status:200,headers:{"content-type":"application/json"}}));
    await polling;
    assert.equal(observed.stopPending,true);
    assert.equal(observed.busy,true,"old focus finally unlocked a pending automatic Stop");
    assert.deepEqual(observed.operation,before.operation,"old focus reply replaced the Live View failure");
  });
}

function answer(f, body = actual.body) {
  f.focus.resolve(new Response(JSON.stringify(body), {status:200,headers:{"content-type":"application/json"}}));
}
function complete(f, outcome) {
  if (outcome === "accepted") answer(f);
  else f.focus.reject(new TypeError("old focus response lost"));
}
function clearFeedback(f) { f.messages.length=0; f.errors.length=0; f.toasts.length=0; }
function assertNoOldFeedback(f) {
  assert.equal(f.messages.length,0);
  assert.equal(f.errors.length,0);
  assert.equal(f.toasts.length,0);
}

test("current focus acceptance is neutral acknowledgement and sends one exact command", async()=>{
  const f=fixture(); const run=f.ctx.driveFocus("NEAR"); answer(f); await run;
  assert.equal(f.state.busy,false);
  assert.deepEqual(f.operation,{message:"focusDriveAccepted",error:false});
  assert.equal(f.errors.length,0);
  assert.deepEqual(f.requests,[{url:"/v1/session/synthetic-session-A/focus/drive",method:"POST",
    body:JSON.stringify({direction:"NEAR",step:"SMALL"})}]);
  assert.equal(f.state.liveActive,true);
  assert.equal(f.state.liveGeneration,1);
});

test("current FAR request snapshots its admitted step and labels only acknowledgement", async()=>{
  const f=fixture(); f.state.focusStep="LARGE";
  const run=f.ctx.driveFocus("FAR"); f.state.focusStep="SMALL";
  answer(f,{accepted:true,direction:"FAR",step:"LARGE"}); await run;
  assert.equal(f.state.busy,false);
  assert.equal(f.operation.message,"focusDriveAccepted");
  assert.equal(f.requests[0].body,JSON.stringify({direction:"FAR",step:"LARGE"}));
});

test("current network loss reports once, unlocks its own operation, and never resends", async()=>{
  const f=fixture(); const run=f.ctx.driveFocus("NEAR");
  f.focus.reject(new TypeError("current focus response lost")); await run;
  assert.equal(f.state.busy,false); assert.equal(f.requests.length,1);
  assert.equal(f.operation.message,"current focus response lost");
  assert.equal(f.operation.error,true); assert.equal(f.errors.length,1); assert.equal(f.toasts.length,1);
});

test("repeated Near/Far clicks while pending send only the first command", async()=>{
  const f=fixture(); const run=f.ctx.driveFocus("NEAR");
  await f.ctx.driveFocus("FAR"); await f.ctx.driveFocus("NEAR");
  assert.equal(f.requests.length,1); answer(f); await run;
  assert.equal(f.requests.length,1); assert.equal(f.state.busy,false);
});

test("disconnected, unsupported, busy, and inactive controls send nothing", async()=>{
  const disconnected=fixture(); disconnected.state.session=null; await disconnected.ctx.driveFocus("NEAR");
  const unsupported=fixture(); unsupported.ctx.featureSupported=()=>false; await unsupported.ctx.driveFocus("NEAR");
  const busy=fixture(); busy.state.busy=true; await busy.ctx.driveFocus("NEAR");
  const inactive=fixture(); inactive.state.liveActive=false; await inactive.ctx.driveFocus("NEAR");
  for (const f of [disconnected,unsupported,busy,inactive]) assert.equal(f.requests.length,0);
});

for (const outcome of ["accepted","network-failure"]) {
  test(`${outcome}: natural local reader retirement releases its own busy without publishing`, async()=>{
    const f=fixture(); const run=f.ctx.driveFocus("NEAR");
    f.ctx.stopLiveLoop(); clearFeedback(f); complete(f,outcome); await run;
    assertNoOldFeedback(f); assert.equal(f.state.busy,false); assert.equal(f.state.liveActive,false);
    assert.equal(f.requests.length,1);
  });
  test(`${outcome}: replacement session with a reused wire ID retains its busy and feedback`, async()=>{
    const f=fixture(); const run=f.ctx.driveFocus("NEAR");
    f.state.session={id:"synthetic-session-A"}; f.state.busy=true;
    clearFeedback(f); complete(f,outcome); await run;
    assertNoOldFeedback(f); assert.equal(f.state.busy,true); assert.equal(f.requests.length,1);
  });
  test(`${outcome}: same-session new interaction retains busy and feedback`, async()=>{
    const f=fixture(); const run=f.ctx.driveFocus("NEAR");
    f.ctx.beginCameraInteraction(); clearFeedback(f); complete(f,outcome); await run;
    assertNoOldFeedback(f); assert.equal(f.state.busy,true);
  });
  test(`${outcome}: completed Stop and new source reject the older focus response`, async()=>{
    const f=fixture(); const run=f.ctx.driveFocus("NEAR");
    const stopping=f.ctx.stopLiveView({announce:false});
    f.stop.resolve(new Response(JSON.stringify({active:false,liveViewStopUnconfirmed:false}),{status:200}));
    await stopping;
    // This is the state of a newly admitted source after the acknowledged Stop.
    f.ctx.beginCameraInteraction(); f.state.liveGeneration++;
    f.state.liveActive=true; f.state.activeLiveSource="CCAPI_RTP";
    f.state.liveViewStopOwner={session:f.state.session,confirmedStart:true};
    const replacement=f.state.liveViewStopOwner;
    clearFeedback(f); complete(f,outcome); await run;
    assertNoOldFeedback(f); assert.equal(f.state.busy,true);
    assert.equal(f.state.liveViewStopOwner,replacement); assert.equal(f.state.activeLiveSource,"CCAPI_RTP");
    assert.equal(f.requests.filter(r=>r.url.endsWith("/focus/drive")).length,1);
  });
  for (const field of ["owner","source","generation"]) {
    test(`${outcome}: changed ${field} without a new interaction retires feedback but not cleanup`, async()=>{
      const f=fixture(); const run=f.ctx.driveFocus("NEAR");
      if(field==="owner") f.state.liveViewStopOwner={session:f.state.session};
      if(field==="source") f.state.activeLiveSource="CCAPI_RTP";
      if(field==="generation") f.state.liveGeneration++;
      clearFeedback(f); complete(f,outcome); await run;
      assertNoOldFeedback(f); assert.equal(f.state.busy,false);
    });
  }
}

// These values exercise a defensive client contract. Existing built-in Bridge
// engines return a complete accepted=true result or throw; they do not naturally
// produce accepted=false, missing/mismatched fields, or valid-JSON garbage.
for (const [name,body] of [
  ["false acceptance",{accepted:false,direction:"NEAR",step:"SMALL"}],
  ["string acceptance",{accepted:"true",direction:"NEAR",step:"SMALL"}],
  ["missing acknowledgement",{}], ["null",null], ["array",[]],
  ["wrong direction",{accepted:true,direction:"FAR",step:"SMALL"}],
  ["wrong step",{accepted:true,direction:"NEAR",step:"LARGE"}],
  ["missing direction",{accepted:true,step:"SMALL"}],
]) {
  test(`defensive contract: ${name} cannot claim accepted or repeat the command`,async()=>{
    const f=fixture(); const run=f.ctx.driveFocus("NEAR"); answer(f,body); await run;
    assert.equal(f.state.busy,false); assert.equal(f.requests.length,1);
    assert.deepEqual(f.operation,{message:"focusDriveUnconfirmed",error:true});
    assert.equal(f.errors[0].code,"FOCUS_DRIVE_UNCONFIRMED");
  });
}

test("invalid JSON remains an error and never repeats the command",async()=>{
  const f=fixture(); const run=f.ctx.driveFocus("NEAR");
  f.focus.resolve(new Response("{",{status:200,headers:{"content-type":"application/json"}})); await run;
  assert.equal(f.operation.error,true); assert.equal(f.state.busy,false); assert.equal(f.requests.length,1);
});

test("both translations describe command acknowledgement without lens-motion proof",()=>{
  assert.equal(source.includes("focusMoved:"),false);
  assert.match(source,/focusDriveAccepted: "Focus drive command accepted: \{direction\}"/);
  assert.match(source,/focusDriveAccepted: "向\{direction\}微調對焦命令已接受"/);
});
