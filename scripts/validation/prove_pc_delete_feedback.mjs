// Actual API results drive unchanged production deletion functions and receipt module.
// DOM/render, fetch adapter and confirmation are synthetic; no browser/camera/files.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const root=process.cwd(), input=process.argv[2];
assert(input, 'Pass the actual API JSONL output path');
const source=fs.readFileSync(root+'/bridge/open_eos_bridge/static/app.js','utf8');
function exact(name){
 const start=source.search(new RegExp(`^  (?:async )?function ${name}\\(`,'m'));
 assert(start>=0,name);
 const end=source.slice(start+1).search(/\n  (?:async )?function /);
 assert(end>=0,name);return source.slice(start,start+1+end);
}
const module=require(root+'/bridge/open_eos_bridge/static/media-delete.js');
const rows=fs.readFileSync(input,'utf8').trim().split('\n').map(JSON.parse);
for(const row of rows){
 const item=row.item, other={...item,id:item.id+'-other',name:'OTHER_SYNTHETIC.JPG'};
 const session={id:'synthetic-owner',engine:'ccapi'};
 const calls=[], confirmations=[], consumed=[];
 const state={session,media:[item,other],mediaDeleteOwner:null,mediaDeleteHistory:[],refreshGeneration:1,
  mediaThumbnailUrls:new Map(),mediaThumbnailLoads:new Map(),mediaThumbnailFailures:new Set(),
  mediaSelection:{token:()=> 'original-token',consumeIfToken:(id,token)=>consumed.push({id,token})},
  mediaListingOwner:{session,cancel(){}},mediaPreviewItem:null,mediaDetailsItem:null};
 const context={state,Headers,AbortController,Date,FEATURES:{MEDIA_DELETE:'MEDIA_DELETE'},
  featureSupported:()=>true,cameraInteractionBusy:()=>false,t:key=>key,
  window:{location:{origin:'http://synthetic.invalid'},confirm:text=>{confirmations.push(text);return true}},
  renderMedia(){},renderMediaDetails(){},renderMediaDeleteHistory(){},releaseObjectUrl(){},showToast(){},
  mediaDelete:{...module,transferHttp:(url,options)=>module.transferHttp(url,{...options,fetchImpl:async(target,init)=>{
    calls.push({method:init.method,url:target});
    return new Response(row.api_status===204?null:row.api_body,{status:row.api_status,headers:row.api_headers});
  }})}};
 vm.createContext(context);
 const names=['mediaDeleteRows','mediaDeleteOwnerDescription','mediaDeleteConfirmation','applyConfirmedMediaDelete','sendMediaDelete','runMediaDelete','deleteMedia'];
 vm.runInContext(names.map(exact).join('\n'),context);
 await context.deleteMedia(item);
 const receipt=state.mediaDeleteHistory[0].results[0];
 const removed=!state.media.some(x=>x.id===item.id);
 assert.equal(calls.length,1);assert.equal(confirmations.length,1);
 assert.equal(removed,row.api_status===204);
 assert.equal(receipt.status,row.api_status===204?'confirmed':row.api_status===403?'rejected':'uncertain');
 assert(state.media.some(x=>x.id===other.id));
 assert.equal(consumed.length,removed?1:0);
 assert.equal(state.mediaListingOwner.confirmedDeletedIds?.has(item.id)??false,removed);
 console.log(JSON.stringify({variant:row.variant,api_status:row.api_status,receipt:receipt.status,
   ui_removed_target:removed,ui_retained_other:true,selection_consumed:consumed.length,
   pending_listing_suppresses_target:state.mediaListingOwner.confirmedDeletedIds?.has(item.id)??false,
   synthetic_item_exists:row.synthetic_item_exists,fresh_listing_contains_item:row.fresh_listing_contains_item,
   confirmation_count:confirmations.length,api_delete_count:calls.length}));
}
