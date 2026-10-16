(function(root,factory){const value=factory();if(typeof module==='object'&&module.exports)module.exports=value;root.OpenEOSLiveViewLifecycle=value;})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  function evaluate(v){
    const alive=v?.sameOwner===true&&v.sameAttempt===true&&v.cancelled!==true&&v.retired!==true&&
      (v.foregroundRequired!==true||v.foreground===true);
    const admitted=v?.startAdmitted===true,stop=v?.stopEvidence,terminal=v?.terminal===true;
    const level=new Map([['encoded-frame',1],['decoded-frame',2],['presented-frame',3]]).get(v?.frameEvidence)||0;
    const ready=alive&&admitted&&v.startAcknowledged===true&&!terminal&&stop==='pending';
    return {canPublishInputReady:Boolean(ready&&level>=1),canPublishDecoded:Boolean(ready&&level>=2),
      canPublishPresented:Boolean(ready&&level>=3),canFallback:Boolean(alive&&terminal&&v.sourceMode==='AUTO'&&
      (stop==='confirmed'||(!admitted&&stop==='not-needed'))),stopRequired:admitted&&stop!=='confirmed'};
  }
  function aborted(){const error=new Error('Live View frame cancelled');error.name='AbortError';return error;}
  async function readFrame(response,{signal,maxBytes=12*1024*1024}={}){
    let reader;
    const cancel=()=>{try{Promise.resolve(reader ? reader.cancel(aborted()) : response?.body?.cancel(aborted())).catch(()=>{});}catch(_){}};
    try{
      if(signal?.aborted)throw aborted();
      if(response?.status!==200||response.redirected||response.headers?.get('content-range')!=null)throw new Error('Live View frame response is not a complete HTTP 200');
      const type=(response.headers?.get('content-type')||'').split(';')[0].trim().toLowerCase();
      if(type!=='image/jpeg')throw new Error('Live View frame is not JPEG');
      const raw=response.headers?.get('content-length');
      const length=raw==null?null:/^[1-9][0-9]*$/.test(raw)?Number(raw):NaN;
      if(length!==null&&(!Number.isSafeInteger(length)||length>maxBytes))throw new Error('Invalid Live View frame length');
      if(!response.body?.getReader)throw new Error('Bounded Live View frame reader unavailable');
      reader=response.body.getReader();signal?.addEventListener('abort',cancel,{once:true});
      const chunks=[];let size=0;
      while(true){
        if(signal?.aborted)throw aborted();
        const {done,value}=await reader.read();if(signal?.aborted)throw aborted();if(done)break;
        if(!(value instanceof Uint8Array)||size+value.byteLength>maxBytes)throw new Error('Live View frame exceeds byte limit');
        size+=value.byteLength;if(length!==null&&size>length)throw new Error('Live View frame length mismatch');chunks.push(value);
      }
      if(size<4||(length!==null&&size!==length))throw new Error('Incomplete Live View frame');
      const blob=new Blob(chunks,{type});
      const begin=new Uint8Array(await blob.slice(0,2).arrayBuffer()),end=new Uint8Array(await blob.slice(-2).arrayBuffer());
      if(begin[0]!==255||begin[1]!==216||end[0]!==255||end[1]!==217)throw new Error('Invalid Live View JPEG envelope');
      if(signal?.aborted)throw aborted();return blob;
    }catch(error){cancel();throw error;}finally{signal?.removeEventListener('abort',cancel);try{reader?.releaseLock();}catch(_){}}
  }
  function evaluateRtpMonitorEvidence(v) {
    const frame = value => Number.isSafeInteger(value) && value > 0;
    const lut = value => Number.isSafeInteger(value) && value >= 0;
    const max = v?.maxAgeMs;
    const age = value => Number.isFinite(value) && value >= 0 && value <= max;
    const alive = v?.active === true && v.sameOwner === true && v.sameRenderer === true &&
      v.terminal === false && v.stopPending === true &&
      (v.foregroundRequired === false || (v.foregroundRequired === true && v.foreground === true));
    const current = alive && frame(v.sampleFrame) && v.sampleFrame === v.currentFrame &&
      lut(v.sampleLut) && v.sampleLut === v.currentLut && Number.isFinite(max) && max > 0 && max <= 5000 &&
      age(v.sampleAgeMs) && age(v.frameAgeMs);
    const level = new Map([['decoded-output', 1], ['surface-submitted', 2], ['presentation-confirmed', 3]]).get(v?.evidence) || 0;
    return {canPublishDecodedAnalysis: Boolean(current && level >= 1),
      canPublishSubmittedAnalysis: Boolean(current && level >= 2), canClaimPresentation: Boolean(current && level === 3)};
  }
  return {evaluate,readFrame,evaluateRtpMonitorEvidence};
});
