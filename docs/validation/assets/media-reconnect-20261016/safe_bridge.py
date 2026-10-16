import json,time,threading
from pathlib import Path
from urllib.parse import urlsplit
from fastapi.responses import JSONResponse
import open_eos_bridge.edsdk_loader as loader
loader.load_edsdk_provider = lambda *a, **k: loader.EdsdkProviderLoadResult(provider=None, detail='Synthetic fixture: EDSDK disabled')
from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, UrllibCcapiTransport
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.errors import BridgeError
from tests.fakes import FakeRunner
ORIGIN = 'http://127.0.0.1:18766'
fixture_events=[]
fixture_lock=threading.Lock()
fixture_connections=0
def record(kind, label, **data):
 with fixture_lock:
  event=dict(time=time.time(),kind=kind,label=label,**data)
  fixture_events.append(event)
  with (Path(__file__).parent/'wire-events.jsonl').open('a') as out:out.write(json.dumps(event)+'\n')

def allowed(url):
 p=urlsplit(url)
 return p.scheme=='http' and p.netloc=='127.0.0.1:18766' and not p.username and not p.password
class LocalTransport(UrllibCcapiTransport):
 def _open(self,method,url,**kwargs):
  if not allowed(url):raise BridgeError('FIXTURE_ORIGIN_ONLY','Synthetic loopback origin required.',status_code=403)
  kwargs['follow_redirects']=False
  return super()._open(method,url,**kwargs)
class LocalCcapi(CcapiEngine):
 def open_connection(self,base_url,username='',password=''):
  if not allowed(base_url) or username or password:raise BridgeError('FIXTURE_ORIGIN_ONLY','Only the credential-free synthetic loopback simulator is permitted.',status_code=403)
  global fixture_connections
  session=super().open_connection(base_url)
  fixture_connections+=1
  label='A' if fixture_connections==1 else 'B'
  info=session.info
  session.info=lambda:info().model_copy(update={'model':'SYNTHETIC CAMERA '+label})
  session.camera=session.camera.model_copy(update={'model':'SYNTHETIC CAMERA '+label})
  listing=session.list_media
  progressive=session.list_media_progressive
  metadata=session.media_info
  def mapped(item):
   return item.model_copy(update={'name':label+'_SAME_ID.PNG','size_bytes':111111 if label=='A' else 222222,'rating':1 if label=='A' else 5})
  def list_items(maximum_items=None):
   record('latest-start',label)
   time.sleep(2)
   result=[mapped(item) for item in listing(maximum_items)]
   record('latest-complete',label,items=[item.model_dump(mode='json',by_alias=True) for item in result])
   return result
  def list_progress(maximum_items=None,*,on_progress,cancelled):
   record('gallery-start',label)
   for _ in range(30):
    if cancelled():raise BridgeError('MEDIA_LIST_CANCELLED','Synthetic delay cancelled',status_code=409)
    time.sleep(.1)
   result=progressive(maximum_items,on_progress=lambda rows:on_progress([mapped(item) for item in rows]),cancelled=cancelled)
   result=[mapped(item) for item in result]
   record('gallery-complete',label,items=[item.model_dump(mode='json',by_alias=True) for item in result])
   return result
  def media_info(media_id,*,fresh=False):
   record('info-start',label,media_id=media_id)
   time.sleep(5)
   result=mapped(metadata(media_id,fresh=fresh))
   record('info-complete',label,item=result.model_dump(mode='json',by_alias=True))
   return result
  session.list_media=list_items
  session.list_media_progressive=list_progress
  session.media_info=media_info
  record('connected',label)
  return session
runner=FakeRunner()
captures=Path(__file__).parent/'captures';captures.mkdir(exist_ok=True)
engine=GPhoto2Engine(runner,capture_directory=captures)
app=create_app(engine=engine,edsdk_engine=engine,ccapi_engine=LocalCcapi(transport_factory=LocalTransport,rtp_session_factory=None,route_resolver=lambda _:None),token='')
@app.get('/__test/state',include_in_schema=False)
def state():
 return {'synthetic_only':True,'ccapi_origin':ORIGIN,'rtp_disabled':True,'commands':[list(c) for c in runner.commands],'values':runner.values}
@app.middleware('http')
async def bounded_requests(request,call_next):
 if int(request.headers.get('content-length','0'))>1024*1024:return JSONResponse({'detail':'Fixture request limit 1MiB'},status_code=413)
 return await call_next(request)
