"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm"), test = require("node:test");
const policy = require("../open_eos_bridge/static/shooting-settings.js");
const source = fs.readFileSync(path.join(__dirname,"../open_eos_bridge/static/app.js"),"utf8");
function production(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`,"m")); assert.notEqual(start,-1,name);
  const end=source.slice(start+1).search(/\n  (?:async )?function /); assert.notEqual(end,-1,name);
  return source.slice(start,start+1+end);
}
function deferred() { let resolve,reject; const promise = new Promise((r,j)=>{resolve=r;reject=j;});return {promise,resolve,reject}; }
const setting = (value="400")=>({key:"iso",label:"ISO",value,values:["400","800"],inputKind:"choice",maxLength:null});
const body = (value="800")=>({status:200,body:{version:1,engine:"ccapi",key:"iso",requestedValue:"800",writeEvidence:"response-received",readbackEvidence:value==="800"?"matched":"mismatch",setting:setting(value)}});
function setup() {
  const gate=deferred(),calls=[],owner={id:"SYNTHETIC",engine:"ccapi"};
  const state={session:owner,token:"",settingDrafts:{iso:"800"},settingDraftRevisions:{iso:1},settingConsumedDrafts:{},
    settingHistory:[],settingSequence:0,settingEditor:1,settingOwner:null,refreshGeneration:0,busy:false,
    capabilities:{settings:[setting()]},status:{exposure:{iso:"400"}}};
  const context=vm.createContext({state,AbortController,setTimeout,clearTimeout,shootingSettings:policy,
    settingByKey:(key)=>state.capabilities.settings.find((s)=>s.key===key),cameraInteractionBusy:()=>state.busy,bulbControlLocked:()=>false,
    beginCameraInteraction:()=>{state.busy=true;state.refreshGeneration++;},renderAdvancedSettings(){},renderAvailability(){},renderSession(){},
    setOperationState(){},t:(key)=>key,captureConfiguration:{transfer:async(url,options)=>{calls.push({url,options});return gate.promise;}},
    api:async(url)=>{calls.push({url});return gate.promise;}});
  for (const name of ["editSettingDraft","settingDraftAvailable","settingCurrent","cancelSetting","publishSetting","updateSetting","recoverSetting","refreshSession","refreshCapabilityEvidence"]) vm.runInContext(production(name),context);
  return {state,context,gate,calls,owner};
}
test("one write retains response and consumes only exact fresh matching draft",async()=>{
  const h=setup(),task=h.context.updateSetting(setting(),"800",{});assert.equal(h.calls.length,1);
  h.gate.resolve(body());assert.equal(await task,true);assert.equal(h.state.status.exposure.iso,"800");
  assert.equal(h.state.settingDrafts.iso,undefined);assert.equal(h.state.settingHistory[0].writeEvidence,"response-received");
  await h.context.updateSetting(setting(),"800",{});assert.equal(h.calls.length,1);
});
test("later read failure retains response and draft without automatic mutation",async()=>{
  const h=setup(),task=h.context.updateSetting(setting(),"800",{});const response=body();response.body.readbackEvidence="unavailable";response.body.setting=null;
  h.gate.resolve(response);assert.equal(await task,false);assert.equal(h.state.settingDrafts.iso,"800");
  assert.equal(h.state.settingHistory[0].writeEvidence,"response-received");assert.equal(h.state.status.exposure.iso,"400");
});
test("same-value edit during flight retains new draft revision",async()=>{
  const h=setup(),task=h.context.updateSetting(setting(),"800",{});h.context.editSettingDraft("iso","800");h.gate.resolve(body());
  assert.equal(await task,false);assert.equal(h.state.settingDrafts.iso,"800");assert.equal(h.state.status.exposure.iso,"800");
});
test("cancelled or replaced editor never publishes late response",async()=>{
  for (const kind of ["cancel","editor","owner"]) {
    const h=setup(),task=h.context.updateSetting(setting(),"800",{},1);
    if(kind==="cancel")h.context.cancelSetting();
    if(kind==="editor")h.state.settingEditor++;
    if(kind==="owner"){h.context.cancelSetting({retire:true});h.state.session={id:"SYNTHETIC",engine:"ccapi"};h.state.refreshGeneration++;}
    h.gate.resolve(body());assert.equal(await task,false);assert.equal(h.state.status.exposure.iso,"400");
    assert.equal(h.state.settingDrafts.iso,"800");assert.equal(h.state.settingHistory[0].writeEvidence,"response-received");
  }
});
test("unknown plus exact later GET never upgrades write or consumes draft",async()=>{
  const h=setup(),task=h.context.updateSetting(setting(),"800",{});h.gate.reject(new Error("synthetic lost response"));await task;
  const record=h.state.settingHistory[0];h.context.captureConfiguration.transfer=async(url,options)=>{h.calls.push({url,options});return {status:200,body:{version:1,engine:"ccapi",setting:setting("800")}};};
  await h.context.recoverSetting(record);assert.equal(record.writeEvidence,"unknown");assert.equal(record.readbackEvidence,"matched");
  assert.equal(h.state.settingDrafts.iso,"800");assert.equal(h.calls[1].options.method,"GET");assert.equal(h.calls.length,2);
});
test("mismatch is observed honestly and never publishes requested value",async()=>{
  const h=setup(),task=h.context.updateSetting(setting(),"800",{});h.gate.resolve(body("400"));assert.equal(await task,false);
  assert.equal(h.state.settingHistory[0].readbackEvidence,"mismatch");assert.equal(h.state.status.exposure.iso,"400");
});
test("diagnostics and capability reads cannot enter while setting write active",async()=>{
  const h=setup(),task=h.context.updateSetting(setting(),"800",{});
  assert.equal(await h.context.refreshSession({quiet:true}),false);await h.context.refreshCapabilityEvidence();assert.equal(h.calls.length,1);
  h.gate.resolve(body());await task;
});
test("capability read begun before write cannot replace fresh result",async()=>{
  const h=setup();const old=h.context.refreshCapabilityEvidence();const task=h.context.updateSetting(setting(),"800",{});
  h.gate.resolve(body());await Promise.all([old,task]);assert.equal(h.state.capabilities.settings[0].value,"800");
});
test("retained history max20 and old-owner recovery is read-free",async()=>{
  const h=setup();h.context.captureConfiguration.transfer=async()=>body();
  for(let i=0;i<23;i++){h.context.editSettingDraft("iso","800");await h.context.updateSetting(setting(),"800",{});}
  assert.equal(h.state.settingHistory.length,20);const record=h.state.settingHistory[0];h.state.session={id:"SYNTHETIC",engine:"ccapi"};
  assert.equal(await h.context.recoverSetting(record),false);assert.equal(h.calls.length,0);
});
test("camera mode selection is draft-only and Apply is one guarded outcome request",async()=>{
  const h=setup(),mode={key:"moviemode",label:"Mode",value:"off",values:["off","on"],inputKind:"choice",maxLength:null};
  h.state.capabilities.settings.push(mode);h.state.captureMode="photo";
  Object.assign(h.context,{FEATURES:{VIDEO_RECORDING:"video"},featureSupported:()=>true,recordingRecoveryActive:()=>false,
    renderCaptureMode(){},ui:{modeApplyButton:{}}});
  for(const name of ["selectCaptureMode","applyCaptureMode"])vm.runInContext(production(name),h.context);
  await h.context.selectCaptureMode("video");assert.equal(h.calls.length,0);assert.equal(h.state.captureMode,"photo");
  assert.equal(h.state.settingDrafts.moviemode,"on");
  const task=h.context.applyCaptureMode();assert.equal(h.calls.length,1);
  h.gate.resolve({status:200,body:{version:1,engine:"ccapi",key:"moviemode",requestedValue:"on",writeEvidence:"response-received",readbackEvidence:"matched",setting:{...mode,value:"on"}}});
  assert.equal(await task,true);assert.equal(h.state.capabilities.settings[1].value,"on");
  h.state.status.recording=true;h.context.editSettingDraft("moviemode","off");
  assert.equal(await h.context.applyCaptureMode(),false);assert.equal(h.calls.length,1);
});
