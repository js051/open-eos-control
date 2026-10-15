(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.OpenEOSRecordingLifecycle=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
  const sources=new Map([['ccapi',new Set(['ccapi-event','ccapi-simulator'])],['libgphoto2',new Set(['gphoto-config'])],['usb-ptp',new Set(['usb-evf-record-status'])]]);
  const exactKeys=(value,names)=>object(value)&&Object.keys(value).length===names.length&&names.every(k=>Object.hasOwn(value,k));
  const responses=new Set(['response-received','not-sent','unconfirmed','rejected']);
  function validObservation(value,engine){
    if(!exactKeys(value,['version','engine','observationEvidence','recording','source'])||value.version!==1||value.engine!==engine)return false;
    if(value.observationEvidence==='observed')return typeof value.recording==='boolean'&&sources.get(engine)?.has(value.source)===true;
    return ['unavailable','read-failed'].includes(value.observationEvidence)&&value.recording===null&&value.source===null;
  }
  function empty(engine,failed=false){return{version:1,engine,observationEvidence:failed?'read-failed':'unavailable',recording:null,source:null};}
  function parseObservation({status,engine,response}){return status===200&&validObservation(response,engine)?{...response}:empty(engine,true);}
  function parseOutcome({status,engine,operation,response}){
    const invalid={responseEvidence:'unconfirmed',observation:empty(engine)};
    if(status!==200||!['start','stop'].includes(operation)||!exactKeys(response,['version','engine','operation','responseEvidence','observation'])||response.version!==1||response.engine!==engine||response.operation!==operation||!responses.has(response.responseEvidence)||!validObservation(response.observation,engine))return invalid;
    return{responseEvidence:response.responseEvidence,observation:{...response.observation}};
  }
  function ownership(x){
    const canPublish=x.sameOwner===true&&x.sameAttempt===true&&x.cancelled!==true&&x.retired!==true;
    let stopRequired=x.priorStopRequired===true;
    const fresh=x.observationEvidence==='observed'&&typeof x.recording==='boolean';
    if(canPublish){
      if(fresh)stopRequired=x.recording;
      else if(x.responseEvidence==='response-received')stopRequired=x.operation==='start';
      else if(x.operation==='start'&&x.responseEvidence==='unconfirmed')stopRequired=true;
    }else if(x.sameOwner===true&&x.sameAttempt===true&&x.operation==='start'&&['response-received','unconfirmed'].includes(x.responseEvidence))stopRequired=true;
    return{canPublish,stopRequired,canReview:canPublish&&x.operation==='stop'&&fresh&&x.recording===false,retryStart:false};
  }
  return Object.freeze({empty,validObservation,parseObservation,parseOutcome,ownership});
});
