(() => {
 const realFetch=window.fetch.bind(window), commands=[]; let savedOpener=null; let active=false,mag=1,holdNext=false,tokenSequence=0; const permits=new Map();
 const nativeClose=HTMLDialogElement.prototype.close;
 HTMLDialogElement.prototype.close=function(...args){const result=nativeClose.apply(this,args); commands.push({event:'native-close-return',dialog:this.id,activeId:document.activeElement?.id,activeTag:document.activeElement?.tagName,activeInsideClosedDialog:this.contains(document.activeElement)});return result;};
 const item={id:'synthetic-photo',name:'SYNTHETIC.JPG',kind:'jpeg',contentType:'image/jpeg',previewAvailable:true,sizeBytes:1024,captureTime:'2026-10-17T00:00:00Z',rating:0,ratingWritable:false,protected:false,protectionWritable:false,rotationDegrees:0,rotationWritable:false,archived:false,archiveWritable:false}; const setting={key:'iso',label:'ISO',value:'100',values:['100','200'],inputKind:'choice'}; const caps={supported:['SENSOR_CLEANING','LIVE_VIEW','LIVE_VIEW_MAGNIFICATION','TAP_FOCUS','MEDIA_BROWSER','MEDIA_RATING','MEDIA_PROTECT','MEDIA_ROTATE','MEDIA_ARCHIVE','MEDIA_DELETE','MEDIA_PREVIEW','MEDIA_DOWNLOAD'],settings:[setting],liveView:{maxFps:2,sizes:['MEDIUM'],defaultSize:'MEDIUM',sources:['DESKTOP_BRIDGE_STREAM'],defaultSource:'DESKTOP_BRIDGE_STREAM',magnifications:[1,5,10],currentMagnification:1}};
 const status=()=>({recording:false,liveViewActive:active,settings:[],exposure:{iso:setting.value},battery:{level:100}});
 const json=(x,status=200)=>new Response(JSON.stringify(x),{status,headers:{'content-type':'application/json','cache-control':'no-store'}});
 const canvas=document.createElement('canvas');canvas.width=640;canvas.height=420;const ctx=canvas.getContext('2d');ctx.fillStyle='#23405b';ctx.fillRect(0,0,640,420);ctx.fillStyle='#fff';ctx.font='28px sans-serif';ctx.fillText('SYNTHETIC RESTORE PERMIT',45,190);ctx.fillText('No physical camera',45,235);const jpeg=new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg'));
 window.fetch=async(input,options={})=>{
  const p=new URL(typeof input==='string'?input:input.url,location.href).pathname;
  if(p.startsWith('/__'))return realFetch(input,options);
  if(p==='/health')return json({ok:true,authRequired:false,engines:{libgphoto2:{available:true}}});
  if(p==='/v1/cameras')return json({cameras:[{id:'synthetic',port:'synthetic',model:'SYNTHETIC maintenance camera',engine:'libgphoto2'}]});
  if(p==='/v1/session')return json({id:'synthetic-keyboard',engine:'libgphoto2'});
  if(p.endsWith('/settings/iso/outcome')){const value=JSON.parse(options.body).value;setting.value=value;return json({version:1,engine:'libgphoto2',key:'iso',requestedValue:value,writeEvidence:'response-received',readbackEvidence:'matched',setting:{...setting}});}
  if(p.endsWith('/media/synthetic-photo/preview')){const body=await jpeg;return new Response(body,{status:200,headers:{'content-type':'image/jpeg','content-length':String(body.size)}});}
  if(p.endsWith('/media/synthetic-photo')){commands.push({request:'original',status:503});return new Response(JSON.stringify({error:{code:'SYNTHETIC_DOWNLOAD_FAILURE',message:'Synthetic download unavailable'}}),{status:503,headers:{'content-type':'application/json'}});}
  if(p.endsWith('/media'))return json({items:[item]});
  if(p.endsWith('/media/synthetic-photo/info')){commands.push({request:'metadata',status:200});return json(item);}
  if(p.endsWith('/info'))return json({model:'SYNTHETIC maintenance camera',manufacturer:'Fixture',engine:'libgphoto2'});
  if(p.endsWith('/status'))return json(status());
  if(p.endsWith('/capabilities'))return json({...caps,liveView:{...caps.liveView,currentMagnification:mag}});
  if(p.endsWith('/maintenance/sensor-cleaning/outcome')){commands.push({event:'cleaning',body:JSON.parse(options.body)});return json({version:1,engine:'libgphoto2',operation:'sensor-cleaning',autoPowerOff:false,responseEvidence:'response-received',clockReference:null,clockObservation:null,clockReadEvidence:'not-applicable'});}
  if(p.endsWith('/maintenance/sensor-cleaning')){commands.push({event:'fresh-read'});return json({version:1,engine:'libgphoto2',operation:'sensor-cleaning',observationEvidence:'reachable',clockObservation:null});}
  if(p.endsWith('/maintenance/control/liveview/stop')){
   const body=options.body?JSON.parse(options.body):{};commands.push({event:'maintenance-stop',body});
   if(body.prepareRestore){const token=String(++tokenSequence).padStart(32,'x');permits.set(token,{state:'prepared'});active=false;return json({active:false,liveViewStopUnconfirmed:false,restoreToken:token});}
   if(body.restoreToken){const permit=permits.get(body.restoreToken);if(!permit)return json({error:{message:'Unknown synthetic permit'}},409);
    if(['prepared','queued','cancelled-before-start'].includes(permit.state)){permit.state='cancelled-before-start';permit.finish?.();commands.push({event:'cancelled-before-start',token:body.restoreToken});return json({restoreToken:body.restoreToken,restoreOutcome:'cancelled-before-start'});}
    active=false;permit.state='stopped';return json({active:false,liveViewStopUnconfirmed:false,restoreToken:body.restoreToken,restoreOutcome:'stopped'});}
   active=false;return json({active:false,liveViewStopUnconfirmed:false});
  }
  if(p.endsWith('/maintenance/control/liveview/start')){
   const body=JSON.parse(options.body),permit=permits.get(body.restoreToken);commands.push({event:'restore-start',body});
   if(!permit||permit.state!=='prepared')return json({error:{message:'Retired synthetic restore permit'}},409);
   if(holdNext){holdNext=false;permit.state='queued';return new Promise((resolve,reject)=>{permit.finish=()=>{commands.push({event:'late-start-rejected'});resolve(json({error:{message:'Cancelled synthetic restore'}},409));};options.signal?.addEventListener('abort',()=>{commands.push({event:'browser-cancelled-start-wait'});reject(new DOMException('Synthetic cancelled wait','AbortError'));},{once:true});});}
   permit.state='started';active=true;return json({active:true,liveViewStopUnconfirmed:false,source:'DESKTOP_BRIDGE_STREAM',requestedFps:2,restoreToken:body.restoreToken,restoreOutcome:'started'});
  }
  if(p.endsWith('/liveview/start')){active=true;return json({active:true,source:'DESKTOP_BRIDGE_STREAM',requestedFps:2});}
  if(p.endsWith('/liveview/stop')){active=false;return json({active:false,liveViewStopUnconfirmed:false});}
  if(p.endsWith('/liveview/frame')){const body=await jpeg;return new Response(body,{status:200,headers:{'content-type':'image/jpeg','content-length':String(body.size)}});}
  if(p.endsWith('/liveview/magnification')){mag=JSON.parse(options.body).value;commands.push({command:'magnification',value:mag});return json({accepted:true,value:mag});}
  if(p.endsWith('/focus/tap')||p.endsWith('/whitebalance/click')){commands.push({command:p.split('/').slice(-2).join('/'),body:options.body});return json({accepted:true});}
  if(options.method==='DELETE')return new Response(null,{status:204});
  return new Response('{}',{status:404,headers:{'content-type':'application/json'}});
 };
 window.addEventListener('DOMContentLoaded',()=>{
  const panel=document.createElement('div');panel.style='position:fixed;z-index:999999;bottom:6px;left:6px;padding:8px;background:white;color:black;border:2px solid orange;font:10px sans-serif;max-width:90vw';
  for(const [label,id] of [['Hold next restore reply','hold-next-restore']]){const b=document.createElement('button');b.textContent=label;b.onclick=()=>{holdNext=true;commands.push({event:'hold-next-restore'});};panel.append(b);}
  const info=document.createElement('span');panel.append(info);document.body.append(panel);let previous='';
  setInterval(()=>{const v=document.getElementById('viewfinder'),z=document.getElementById('live-magnification-button');const bounds=(id)=>{const e=document.getElementById(id),r=e?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,height:r.height,scrollTop:e.scrollTop,clientHeight:e.clientHeight,scrollHeight:e.scrollHeight}:null;};const s={at:Date.now(),history:document.querySelector('[data-maintenance-history]')?.innerText,liveButton:document.getElementById('live-toggle-button')?.textContent,liveDisabled:document.getElementById('live-toggle-button')?.disabled,detailsBounds:bounds('media-details-dialog'),downloadBounds:bounds('media-details-download'),deleteBounds:bounds('media-details-delete'),viewport:{width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,clientWidth:document.documentElement.clientWidth},commands,focused:document.activeElement?.id,focusedTag:document.activeElement?.tagName,focusedSetting:document.activeElement?.dataset?.settingKey,focusedMediaId:document.activeElement?.dataset?.mediaId,focusedMediaAction:document.activeElement?.dataset?.mediaAction,focusedText:document.activeElement?.textContent?.slice(0,100),dialogOpen:[...document.querySelectorAll('dialog[open]')].map(d=>d.id),openerConnected:savedOpener?.isConnected,tapEnabled:v?.classList.contains('tap-focus-enabled'),zoomDisabled:z?.disabled,zoomHidden:z?.hidden,zoomLabel:z?.innerText};info.textContent=` ${innerWidth}x${innerHeight} focus=${s.focusedTag}:${s.focused} dialog=${s.dialogOpen}`;const key=JSON.stringify({...s,at:0});if(key!==previous){previous=key;realFetch('/__receipt',{method:'POST',body:JSON.stringify(s)});}},200);
 });
})();
