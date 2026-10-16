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
  if not allowed(base_url) or username or password:raise BridgeError('FIXTURE_ORIGIN_ONLY','Only credential-free synthetic loopback simulator permitted',status_code=403)
  return super().open_connection(base_url)
class RecordingRunner(FakeRunner):
 def run(self,arguments,**kwargs):
  result=super().run(arguments,**kwargs)
  if any('--set-config' in arg for arg in arguments):record('fake-write','USB',arguments=list(arguments),values=self.values)
  return result
runner=RecordingRunner()
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
