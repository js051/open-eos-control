import pathlib,subprocess,os,socket,time,urllib.request,json,hashlib,signal
R=pathlib.Path('/workspace/shared/eos-parity-recovery-20261013');root=pathlib.Path(__file__).parent; pc=R/'settings-ui-owner-20261016';py=R/'bridge-validation-venv-20261014/bin/python'
for port in (18765,18766):
 with socket.socket() as s:s.bind(('127.0.0.1',port))
env=os.environ.copy();env['PYTHONPATH']=str(root)+':'+str(pc/'bridge');env['PYTHONDONTWRITEBYTECODE']='1';children=[]
try:
 for name,module,cwd,port in [('simulator','safe_simulator:app',pc/'simulator',18766),('bridge','safe_bridge:app',pc/'bridge',18765)]:
  log=(root/(name+'.log')).open('w');p=subprocess.Popen([str(py),'-m','uvicorn',module,'--host','127.0.0.1','--port',str(port),'--log-level','warning'],cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT);children.append(p)
 for port in (18765,18766):
  for i in range(50):
   try:
    with urllib.request.urlopen(f'http://127.0.0.1:{port}/health',timeout=1) as r:assert r.status==200
    break
   except Exception:
    if any(p.poll() is not None for p in children):raise RuntimeError('Fixture child exited')
    time.sleep(.1)
  else:raise RuntimeError('Health timeout')
 paths=[pc/'bridge/open_eos_bridge/static/app.js',pc/'bridge/open_eos_bridge/app.py',pc/'bridge/tests/fakes.py',pc/'simulator/main.py',root/'safe_bridge.py',root/'safe_simulator.py']
 receipt={'candidate':'6362b87edbf7b369d39b4427b1a62b9211cfc773','pids':[p.pid for p in children],'launcher_pid':os.getpid(),'ui_url':'http://127.0.0.1:18765/app/index.html','simulator_url':'http://127.0.0.1:18766/ccapi','bind':'127.0.0.1 only','source_hashes':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},'restrictions':['FakeRunner gphoto only; no physical USB','EDSDK provider loading disabled by test wrapper','CCAPI exact synthetic origin only, no credentials, no redirects','RTP disabled; no UDP socket','No public bind/tunnel/deploy','Request body limit1MiB'],'ui_validation':'NOT_YET_RUN'}
 (root/'server-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)
 while all(p.poll() is None for p in children):time.sleep(1)
finally:
 for p in children:
  if p.poll() is None:p.terminate()
 for p in children:
  try:p.wait(timeout=5)
  except subprocess.TimeoutExpired:p.kill();p.wait()
