(() => {
 const realFetch=window.fetch.bind(window); let healthCalls=0, aborted=0, lateResolve=null;
 const mediaDevices=new EventTarget(); mediaDevices.enumerateDevices=()=>new Promise(()=>{}); Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:mediaDevices});
 const healthy={ok:true,service:'open-eos-control-bridge',version:'0.13.0',authRequired:false,engines:{libgphoto2:{available:true}}};
 const response=body=>new Response(JSON.stringify(body),{status:200,headers:{'content-type':'application/json'}});
 window.fetch=(input,options={})=>{
  const p=new URL(typeof input==='string'?input:input.url,location.href).pathname;
  if(p.startsWith('/__'))return realFetch(input,options);
  if(p==='/health'){
   healthCalls++;
   if(healthCalls===1){options.signal?.addEventListener('abort',()=>aborted++);return new Promise(resolve=>lateResolve=()=>resolve(response({...healthy,authRequired:true})));}
   return Promise.resolve(response(healthy));
  }
  if(p==='/v1/cameras')return Promise.resolve(response({cameras:[]}));
  return Promise.resolve(new Response('{}',{status:404}));
 };
 window.addEventListener('DOMContentLoaded',()=>{
  const panel=document.createElement('div');panel.style='position:fixed;z-index:999999;bottom:8px;left:8px;padding:8px;background:white;color:black;border:2px solid orange;font:14px sans-serif';
  const release=document.createElement('button');release.textContent='Release stale health response';release.onclick=()=>{lateResolve?.();lateResolve=null;};
  const info=document.createElement('span');panel.append(release,info);document.body.append(panel);let previous='';
  setInterval(()=>{
   const by=id=>document.getElementById(id);
   const state={at:Date.now(),healthCalls,aborted,held:!!lateResolve,retryDisabled:by('health-retry-button')?.disabled,status:by('connection-status')?.innerText,error:by('connection-error')?.innerText,errorHidden:by('connection-error')?.hidden,engine:by('engine-state')?.innerText,tokenPlaceholder:by('token-input')?.placeholder};
   info.textContent=` healthCalls=${healthCalls} aborts=${aborted} held=${!!lateResolve}`;
   const key=JSON.stringify({...state,at:0});if(key!==previous){previous=key;realFetch('/__receipt',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(state)});}
  },200);
 });
})();
