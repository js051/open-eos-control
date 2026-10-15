"use strict";
const assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),vm=require("node:vm"),test=require("node:test");
const policy=require("../open_eos_bridge/static/maintenance-lifecycle.js");
const source=fs.readFileSync(path.join(__dirname,"../open_eos_bridge/static/app.js"),"utf8");
function production(name){const start=source.search(new RegExp(`^  (?:async )?function ${name}\\(`,"m"));assert.notEqual(start,-1,name);const end=source.slice(start+1).search(/\n  (?:async )?function /);assert.notEqual(end,-1,name);return source.slice(start,start+1+end);}
function deferred(){let resolve,reject;const promise=new Promise((r,j)=>{resolve=r;reject=j;});return{promise,resolve,reject};}
const sample={epochSeconds:1700000000,utcOffsetMinutes:480,daylightSaving:false};
function response(operation="sensor-cleaning",autoPowerOff=false){return{status:200,body:{version:1,engine:"ccapi",operation,autoPowerOff,responseEvidence:"response-received",clockReference:operation==="clock-sync"?{earliestEpochSeconds:1699999990,latestEpochSeconds:1700000010,utcOffsetMinutes:480,daylightSaving:false}:null,clockObservation:operation==="clock-sync"?sample:null,clockReadEvidence:operation==="clock-sync"?"matched":"not-applicable"}};}
function readResponse(operation="sensor-cleaning"){return{status:200,body:{version:1,engine:"ccapi",operation,observationEvidence:operation==="clock-sync"?"observed":"reachable",clockObservation:operation==="clock-sync"?{...sample,epochSeconds:1700001000}:null}};}
function setup(){
 const owner={id:"SYNTHETIC",engine:"ccapi"},calls=[],state={session:owner,token:"",busy:false,status:{recording:false},liveActive:false,refreshGeneration:0,maintenanceSequence:0,maintenanceHistory:[],maintenanceOwner:null,maintenanceHold:null};
 const FEATURES={CAMERA_CLOCK_SYNC:"clock",SENSOR_CLEANING:"clean",CAMERA_SLEEP:"sleep",EVENT_POLLING:"events"};
 const h={state,calls,owner,stopFailure:false,eventFailure:false,bulb:false,recRecovery:false,events:false,write:async(op,auto)=>response(op,auto),read:async(op)=>readResponse(op)};
 const context=vm.createContext({state,FEATURES,AbortController,setTimeout,clearTimeout,maintenanceLifecycle:policy,
 liveCapabilities:()=>({defaultSize:"MEDIUM"}),featureSupported:f=>f!=="events"||h.events,recordingRecoveryActive:()=>h.recRecovery,bulbControlLocked:()=>h.bulb,mediaTransferActive:()=>false,
 beginCameraInteraction:()=>{state.busy=true;state.refreshGeneration++;},renderAdvancedSettings(){},renderAvailability(){},renderSession(){},setOperationState(){},t:k=>k,
 liveViewStopRequired:()=>h.stopFailure,stopLiveView:async()=>{calls.push("stop-live");if(h.stopGate)await h.stopGate.promise;if(h.stopFailure)return false;state.liveActive=false;state.busy=false;return true;},stopLiveLoop:()=>calls.push("stop-local"),
 stopEventLoop:async(_id,options)=>{assert.equal(options.strict,true);calls.push("stop-events");if(h.eventFailure)throw new Error("synthetic event stop");},
 startLiveView:async(options)=>{assert.equal(options.maintenanceRestore.restoreAdmitted,true);calls.push("start-live");state.liveActive=true;},startEventLoop:()=>calls.push("start-events"),
 captureConfiguration:{transfer:async(url,options)=>{calls.push({url,options});const op=url.match(/maintenance\/([^/]+)/)[1];return options.method==="POST"?h.write(op,options.payload.autoPowerOff):h.read(op);}},
 });
 for(const name of ["maintenanceHeld","maintenancePaused","maintenanceCurrent","maintenancePublishable","maintenanceSafetyClear","maintenanceOtherBusy","cameraInteractionBusy","cancelMaintenance","suspendMaintenanceForLifecycle","maintenanceControlRequest", "runMaintenance","maintenanceRead","recoverMaintenance","restoreMaintenance","syncCameraClock","sleepCamera","cleanSensor"])vm.runInContext(production(name),context);
 h.context=context;return h;
}
const transfers=h=>h.calls.filter(x=>typeof x==="object");
test("clock action retains original proof and later observed clock never rewrites it",async()=>{
 const h=setup();assert.equal(await h.context.runMaintenance("clock-sync",false),true);const r=h.state.maintenanceHistory[0];
 assert.equal(r.clockReadEvidence,"matched");assert.equal(h.state.maintenanceHold,null);const initial=JSON.stringify(r.clockObservation);
 await h.context.recoverMaintenance(r);assert.equal(r.latestObservation.observationEvidence,"observed");assert.equal(JSON.stringify(r.clockObservation),initial);
 assert.equal(r.clockReadEvidence,"matched");assert.equal(transfers(h).length,2);assert.equal(transfers(h)[1].options.method,undefined);
});
test("late response after cancel preserves history but cannot publish current clock",async()=>{
 const h=setup(),gate=deferred();h.write=()=>gate.promise;const task=h.context.runMaintenance("clock-sync",false);h.context.cancelMaintenance();gate.resolve(response("clock-sync"));await task;
 assert.equal(h.state.lastClockSyncAt,undefined);assert.equal(h.state.maintenanceHistory[0].responseEvidence,"response-received");assert.equal(h.state.maintenanceHistory[0].cancelled,true);
});
test("failed Live View stop prevents command and leaves owned hold",async()=>{
 const h=setup();h.state.liveActive=true;h.stopFailure=true;await h.context.runMaintenance("sleep",false);
 assert.equal(transfers(h).length,0);assert.equal(h.state.maintenanceHistory[0].stopEvidence,"unknown");assert.equal(h.context.maintenanceHeld(),true);
});
test("failed event stop prevents command even without Live View",async()=>{
 const h=setup();h.eventFailure=true;h.events=true;await h.context.runMaintenance("sensor-cleaning",false);
 assert.equal(transfers(h).length,0);assert.equal(h.state.maintenanceHistory[0].stopEvidence,"unknown");assert.equal(h.state.maintenanceHistory[0].responseEvidence,"not-sent");
});
test("cancel during non-cooperative stop never admits maintenance action",async()=>{
 const h=setup();h.state.liveActive=true;h.stopGate=deferred();const task=h.context.runMaintenance("sleep",false);h.context.cancelMaintenance();h.stopGate.resolve();await task;
 assert.equal(transfers(h).length,0);assert.equal(h.context.maintenancePaused(),true);
});
test("late sleep response cannot reset or restart an equal-id replacement session",async()=>{
 const h=setup(),gate=deferred();h.write=()=>gate.promise;const task=h.context.runMaintenance("sleep",false);await new Promise(setImmediate);
 h.context.cancelMaintenance({retire:true});const replacement={id:"SYNTHETIC",engine:"ccapi"};h.state.session=replacement;h.state.busy=true;
 gate.resolve(response("sleep"));await task;assert.equal(h.state.session,replacement);assert.equal(h.state.busy,true);assert.equal(h.calls.includes("start-events"),false);assert.equal(h.calls.includes("start-live"),false);
 assert.equal(h.state.maintenanceHistory[0].responseEvidence,"response-received");
});
test("unknown sleep plus required reachable read never upgrades command or restores",async()=>{
 const h=setup();h.write=async()=>{throw new Error("synthetic response lost");};await h.context.runMaintenance("sleep",false);const r=h.state.maintenanceHistory[0];
 await h.context.recoverMaintenance(r);assert.equal(r.responseEvidence,"unknown");assert.equal(r.latestObservation.observationEvidence,"reachable");assert.equal(h.context.maintenanceHeld(),true);
 assert.equal(await h.context.restoreMaintenance(r),false);assert.equal(h.calls.includes("start-live"),false);
});
test("clean response is retained when later explicit read fails",async()=>{
 const h=setup();await h.context.runMaintenance("sensor-cleaning",false);const r=h.state.maintenanceHistory[0];h.read=async()=>{throw new Error("synthetic read");};await h.context.recoverMaintenance(r);
 assert.equal(r.responseEvidence,"response-received");assert.equal(r.latestObservation.observationEvidence,"unavailable");assert.equal(h.context.maintenancePaused(),true);assert.equal(transfers(h).filter(x=>x.options.method==="POST").length,1);
});
test("restore uses a new required read then restores only previously active Live View",async()=>{
 for(const wasLive of[false,true]){const h=setup();h.state.liveActive=wasLive;h.events=true;await h.context.runMaintenance("sensor-cleaning",false);const r=h.state.maintenanceHistory[0];
  await h.context.recoverMaintenance(r);const readsBefore=transfers(h).length;assert.equal(await h.context.restoreMaintenance(r),true);
  assert.equal(transfers(h).length,readsBefore+1);assert.equal(h.calls.includes("start-live"),wasLive);assert.equal(h.calls.includes("start-events"),true);assert.equal(h.context.maintenanceHeld(),false);assert.equal(r.restoreEvidence,"confirmed");}
});
test("restore denied after failed fresh probe even when earlier read was reachable",async()=>{
 const h=setup();h.state.liveActive=true;await h.context.runMaintenance("sensor-cleaning",false);const r=h.state.maintenanceHistory[0];await h.context.recoverMaintenance(r);h.read=async()=>({status:200,body:{}});
 assert.equal(await h.context.restoreMaintenance(r),false);assert.equal(h.calls.includes("start-live"),false);assert.equal(h.context.maintenanceHeld(),true);
});
test("cancel or replacement during restore probe prevents hidden start",async()=>{
 for(const change of["cancel","replace"]){const h=setup();h.state.liveActive=true;await h.context.runMaintenance("sensor-cleaning",false);const r=h.state.maintenanceHistory[0],gate=deferred();h.read=()=>gate.promise;
  const task=h.context.restoreMaintenance(r);if(change==="cancel")h.context.cancelMaintenance();else{h.context.cancelMaintenance({retire:true});h.state.session={id:"SYNTHETIC",engine:"ccapi"};}
  gate.resolve(readResponse());await task;assert.equal(h.calls.includes("start-live"),false);assert.equal(r.restoreEvidence,"unknown");}
});
test("REC, uncertain recording Stop and Bulb gates reject before all I/O",async()=>{
 for(const kind of["rec","unknown-stop","bulb"]){const h=setup();if(kind==="rec")h.state.status.recording=true;if(kind==="unknown-stop")h.recRecovery=true;if(kind==="bulb")h.bulb=true;
  for(const operation of["clock-sync","sensor-cleaning","sleep"])assert.equal(await h.context.runMaintenance(operation,false),false);
  assert.equal(h.calls.length,0);assert.equal(h.state.maintenanceHistory.length,0);}
});
test("retained history bounded20 and old-owner recovery does no read",async()=>{
 const h=setup();for(let i=0;i<23;i++)await h.context.runMaintenance("clock-sync",false);assert.equal(h.state.maintenanceHistory.length,20);
 const r=h.state.maintenanceHistory[0],before=h.calls.length;h.state.session={id:"SYNTHETIC",engine:"ccapi"};assert.equal(await h.context.recoverMaintenance(r),false);assert.equal(h.calls.length,before);
});
test("stopEventLoop strict maintenance path rejects rather than swallowing stop failure",async()=>{
 const h=setup();Object.assign(h.context,{cancelEventLoop(){},api:async()=>{throw new Error("stop failed");},captureError(){}});h.events=true;vm.runInContext(production("stopEventLoop"),h.context);
 await assert.rejects(h.context.stopEventLoop(h.owner.id,{strict:true}),/stop failed/);assert.equal(await h.context.stopEventLoop(h.owner.id),false);
});
test('maintenance prerequisite control freezes token, abort signal, no replay parser and exact status',async()=>{
 const h=setup(),record={owner:h.owner,token:'SYNTHETIC-ORIGINAL',retired:false},invocation={record,controller:new AbortController(),cancelled:false};
 h.state.maintenanceOwner=invocation;h.state.token='SYNTHETIC-REPLACEMENT';
 const sent=[];h.context.captureConfiguration.transfer=async(url,options)=>{sent.push({url,options});return{status:options.method==='DELETE'?204:200,body:options.method==='DELETE'?null:{active:false}};};
 for(const [path,method]of[['liveview/stop','POST'],['events','DELETE'],['liveview/start','POST']])await h.context.maintenanceControlRequest(invocation,'/synthetic/'+path,{method});
 assert.equal(sent.length,3);for(const {options}of sent){assert.equal(options.token,'SYNTHETIC-ORIGINAL');assert.equal(options.signal,invocation.controller.signal);assert.equal(options.strictSettings,true);assert.equal(options.strictIntegerTokens,true);}
 h.context.cancelMaintenance();await assert.rejects(h.context.maintenanceControlRequest(invocation,'/synthetic/liveview/start'));assert.equal(sent.length,3);
});
function installLifecycle(h,event){
 const handlers={};Object.assign(h.context,{document:{visibilityState:'hidden',addEventListener:(name,handler)=>{handlers[name]=handler;}},window:{addEventListener:(name,handler)=>{handlers[name]=handler;}},
 cancelEventLoop:()=>h.calls.push('cancel-events-local'),stopMediaAutoImport(){},cancelMediaMutation(){},releaseHeldAutofocusForLifecycle(){},cancelCameraFocusInfo(){},cancelMediaUpload(){},cancelCameraScan(){},
 cancelMediaDownload(){},clearMediaThumbnails(){},closeMediaPreview(){},closeMediaDetails(){},stopLocalVideo(){},stopRtpAudio(){},lutPreviewRenderer:null,
 api:(...args)=>{h.calls.push({unloadRemote:args});return Promise.resolve();},refreshMediaDateZone(){},renderMediaAutoImport(){}});
 const marker=event==='visibilitychange'?'    document.addEventListener?.("visibilitychange"':'    window.addEventListener("'+event+'"';
 const begin=source.indexOf(marker);assert.notEqual(begin,-1);const end=source.indexOf('\n    });',begin)+8;
 vm.runInContext(source.slice(begin,end),h.context);return handlers[event];
}
for(const event of ['visibilitychange','pagehide','beforeunload'])test(`${event} cancels pending restore and prevents a late probe from starting monitoring`,async()=>{
 const h=setup();h.state.liveActive=true;await h.context.runMaintenance('sensor-cleaning',false);const r=h.state.maintenanceHistory[0],gate=deferred();h.read=()=>gate.promise;
 const restore=h.context.restoreMaintenance(r);installLifecycle(h,event)();gate.resolve(readResponse());assert.equal(await restore,false);
 assert.equal(h.calls.includes('start-live'),false);assert.equal(h.calls.some(c=>c.unloadRemote),false);assert.equal(h.state.maintenanceHold,r);
});
test('beforeunload after acknowledged sleep performs local cleanup but zero hidden remote DELETE',async()=>{
 const h=setup();h.events=true;await h.context.runMaintenance('sleep',false);const before=transfers(h).length;
 installLifecycle(h,'beforeunload')();assert.equal(h.calls.some(c=>c.unloadRemote),false);assert.equal(transfers(h).length,before);
 assert.ok(h.calls.includes('cancel-events-local'));assert.ok(h.calls.includes('stop-local'));
});
test('ordinary non-maintenance beforeunload retains existing event and session cleanup',()=>{
 const h=setup();h.events=true;installLifecycle(h,'beforeunload')();assert.equal(h.calls.filter(c=>c.unloadRemote).length,2);
});
test('beforeunload after successful restore with a strict Stop owner never falls back to generic DELETE',()=>{
 const h=setup();h.events=true;h.state.liveViewStopOwner={session:h.owner,maintenanceRecord:{owner:h.owner}};
 installLifecycle(h,'beforeunload')();assert.equal(h.calls.some(c=>c.unloadRemote),false);
 assert.ok(h.calls.includes('cancel-events-local'));assert.ok(h.calls.includes('stop-local'));
});
