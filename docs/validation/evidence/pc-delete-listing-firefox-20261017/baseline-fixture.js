(() => {
  'use strict';
  const realFetch = window.fetch.bind(window), events = [];
  const removed = {id:'ccapi:synthetic-deleted',name:'SYNTHETIC_DELETE.JPG',kind:'jpeg',contentType:'image/jpeg',protected:false,writable:true};
  const kept = {id:'ccapi:synthetic-kept',name:'SYNTHETIC_KEEP.JPG',kind:'jpeg',contentType:'image/jpeg',protected:false,writable:true};
  let pendingDelete = null, pendingListing = null, deleted = false, freshReusesId = false;
  const json = value => new Response(JSON.stringify(value), {headers:{'content-type':'application/json'}});
  const note = event => { events.push({event,at:Date.now()}); };
  window.fetch = async (input, options={}) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href), p = url.pathname;
    if (url.origin === location.origin && p === '/__receipt') return realFetch(input, options);
    if (p === '/health') return json({ok:true,authRequired:false,engines:{libgphoto2:{available:true}}});
    if (p === '/v1/cameras') return json({cameras:[{id:'synthetic',port:'synthetic',model:'SYNTHETIC listing receipt',engine:'ccapi'}]});
    if (p === '/v1/session') return json({id:'synthetic-delete-listing',engine:'ccapi'});
    if (p.endsWith('/capabilities')) return json({supported:['MEDIA_BROWSER','MEDIA_DELETE'],settings:[],liveView:{}});
    if (p.endsWith('/status')) return json({recording:false,settings:[],battery:{level:100}});
    if (p.endsWith('/info')) return json({model:'SYNTHETIC listing receipt',manufacturer:'Fixture',engine:'ccapi'});
    if (p.endsWith('/media')) {
      if (pendingDelete) {
        const buffered = [removed, kept]; note('listing-buffered-before-delete');
        return new Promise(resolve => { pendingListing = () => { note('older-listing-delivered'); resolve(json({items:buffered})); }; });
      }
      note('fresh-listing'); return json({items:!deleted || freshReusesId ? [removed, kept] : [kept]});
    }
    if (options.method === 'DELETE' && decodeURIComponent(p).endsWith('/media/'+removed.id)) {
      note('delete-pending');
      return new Promise(resolve => { pendingDelete = () => { deleted=true; note('delete-204-receipt'); resolve(new Response(null,{status:204})); }; });
    }
    if (options.method === 'DELETE' && /\/session\/[^/]+$/.test(p)) return new Response(null,{status:204});
    return new Response('{}',{status:404,headers:{'content-type':'application/json'}});
  };
  window.addEventListener('DOMContentLoaded', () => {
    const panel = document.createElement('section');
    panel.style='position:fixed;z-index:999999;bottom:8px;left:8px;right:8px;padding:10px;background:#fff;color:#111;border:3px solid #e90;font:14px sans-serif';
    const title=document.createElement('div');title.textContent='SYNTHETIC ONLY: Connect → Media → Delete SYNTHETIC_DELETE.JPG and confirm → Refresh while DELETE pending → Release DELETE → Release older listing. KEEP must remain; DELETE must stay absent.';panel.append(title);
    for (const [label, action] of [
      ['1 Release DELETE receipt',()=>{if(pendingDelete){const fn=pendingDelete;pendingDelete=null;fn();}}],
      ['2 Release older listing',()=>{if(pendingListing){const fn=pendingListing;pendingListing=null;fn();}}],
      ['3 Permit same ID on next fresh Refresh',()=>{freshReusesId=true;note('fresh-id-reuse-enabled');}],
    ]) { const b=document.createElement('button');b.textContent=label;b.onclick=action;panel.append(b); }
    const info=document.createElement('div');panel.append(info);document.body.append(panel);
    let previous='';setInterval(()=>{
      const state={events,pendingDelete:!!pendingDelete,pendingListing:!!pendingListing,cards:[...document.querySelectorAll('.media-card')].map(n=>({id:n.querySelector("[data-media-id]")?.dataset.mediaId,text:n.textContent})),history:document.getElementById('media-delete-list')?.textContent,refreshDisabled:document.getElementById('media-refresh-button')?.disabled};
      info.textContent=JSON.stringify({events:events.map(x=>x.event),pendingDelete:state.pendingDelete,pendingListing:state.pendingListing,cards:state.cards.map(x=>x.id)});
      const key=JSON.stringify(state);if(key!==previous){previous=key;realFetch('/__receipt',{method:'POST',body:key});}
    },200);
  });
})();
