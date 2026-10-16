"""Run only from the existing cloud desktop Terminal, after desktop-owner handoff."""
import os,pathlib,subprocess,time,signal,json,hashlib,shutil,urllib.request
root=pathlib.Path(__file__).parent;R=root.parents[1];pc=R/'settings-ui-owner-20261016';receipt=root/'native-firefox-receipt.json';stop=root/'native-firefox.stop';children=[];logs=[]
state={'state':'starting','started':time.time(),'candidate':'6362b87edbf7b369d39b4427b1a62b9211cfc773','browser':'Existing native Mozilla Firefox157.0.1; NOT Playwright Chromium acceptance','url':'http://127.0.0.1:18765/app/index.html','runtime':'/workspace/shared/guzzzi-recovery-20261012/tools/firefox/firefox','limits':'sampled monitor; NOT kernel quota','floorBytes':4*1024**3,'stopCushionBytes':64*1024**2,'profileBudgetBytes':64*1024**2,'fixtureBudgetBytes':8*1024**2}
def save():receipt.write_text(json.dumps(state,indent=2)+'\n')
def members(group):
 result=[]
 for q in pathlib.Path('/proc').glob('[0-9]*'):
  try:
   x=(q/'stat').read_text().rsplit(')',1)[1].split()
   if int(x[2])==group:result.append(int(q.name))
  except (OSError,ValueError,IndexError):pass
 return result
save()
try:
 assert not stop.exists(),'Stale stop marker: reconcile first'
 assert shutil.disk_usage(R).free>state['floorBytes']+state['stopCushionBytes']+state['profileBudgetBytes']+state['fixtureBudgetBytes'],'Insufficient profile headroom'
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=pc,text=True).strip()==state['candidate']
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=pc,text=True).strip()
 state['sourceHashes']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [pc/'bridge/open_eos_bridge/static/app.js',root/'safe_bridge.py',root/'safe_simulator.py',root/'start.py']}
 env=os.environ.copy();env['LIBGL_ALWAYS_SOFTWARE']='true';env['PYTHONDONTWRITEBYTECODE']='1'
 assert not any(k.startswith('MOZ_DISABLE') and v not in ('','0') for k,v in env.items()),'Unsafe inherited Firefox option'
 for name,cmd in [('fixture',['python3',str(root/'start.py')])]:
  log=(root/(name+'-native.log')).open('w');logs.append(log);p=subprocess.Popen(cmd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);children.append(p)
 http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 for _ in range(60):
  try:
   with http.open('http://127.0.0.1:18765/health',timeout=1) as r:assert r.status==200
   break
  except Exception:
   if children[0].poll() is not None:raise RuntimeError('Fixture exited')
   time.sleep(.1)
 else:raise RuntimeError('Fixture health timeout')
 profile=root/'eos-native-firefox-profile';assert not profile.exists(),'Profile must be new EOS-only';profile.mkdir()
 log=(root/'firefox-native.log').open('w');logs.append(log)
 ff=subprocess.Popen([state['runtime'],'--new-instance','--profile',str(profile),state['url']],env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);children.append(ff)
 state.update(state='running',ready=time.time(),ownedGroups=[p.pid for p in children],profile=str(profile),minimumFreeBytes=shutil.disk_usage(R).free);save()
 while time.time()-state['started']<600 and ff.poll() is None and children[0].poll() is None and not stop.exists():
  profile_bytes=sum(x.stat().st_size for x in profile.rglob('*') if x.is_file())
  if profile_bytes>state['profileBudgetBytes']:state['stopReason']='PROFILE_64MIB_MONITOR_STOP';break
  free=shutil.disk_usage(R).free;state['minimumFreeBytes']=min(state['minimumFreeBytes'],free)
  if free<=state['floorBytes']+state['stopCushionBytes']:state['stopReason']='EARLY_DISK_MONITOR_STOP';break
  time.sleep(.05)
 state.setdefault('stopReason','UI_OWNER_STOP_OR_BROWSER_EXIT_OR_10MIN_TIMEOUT')
except Exception as e:state['error']=type(e).__name__+': '+str(e)
finally:
 try:
  with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:18765/__test/state',timeout=2) as response:
   (root/'final-fake-state.json').write_bytes(response.read())
 except Exception as error:state['finalFakeStateError']=type(error).__name__
 for p in reversed(children):
  if p.poll() is None:
   try:os.killpg(p.pid,signal.SIGTERM)
   except ProcessLookupError:pass
   try:p.wait(timeout=5)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
  for pid in members(p.pid):
   try:os.kill(pid,signal.SIGKILL)
   except ProcessLookupError:pass
 state.update(state='terminal',ended=time.time(),exitCodes=[p.returncode for p in children],ownedRemaining=[pid for p in children for pid in members(p.pid)]);save()
 for f in logs:f.close()
