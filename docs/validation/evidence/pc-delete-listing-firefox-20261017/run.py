import pathlib,http.server,threading,subprocess,os,time,json,signal,shutil,hashlib
root=pathlib.Path(__file__).resolve().parent; repo=root.parent/os.environ.get('EOS_DELETE_LISTING_REPO','pc-delete-listing-receipt-20261017'); static=repo/'bridge/open_eos_bridge/static'; stop=root/'stop'; profile=root.parent/'local-preview-firefox-evidence-20261017/profile'; mode=os.environ.get('EOS_DELETE_LISTING_MODE','fixed'); evidence=root/mode; evidence.mkdir(exist_ok=True); receipt=evidence/'receipt.json'
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_POST(self):
  if self.path!='/__receipt':self.send_error(404);return
  length=int(self.headers.get('content-length','0'))
  if length>8192:self.send_error(413);return
  data=self.rfile.read(length)
  with (evidence/'events.jsonl').open('ab') as f:f.write(data+b'\n')
  self.send_response(204);self.end_headers()
 def do_GET(self):
  if self.path=='/fixture.js':p=root/'fixture.js'
  elif self.path.startswith('/app/'):
   relative=self.path.removeprefix('/app/').split('?')[0] or 'index.html'
   p=(static/relative).resolve()
   if not p.is_relative_to(static.resolve()):self.send_error(404);return
  else:self.send_error(404);return
  if not p.is_file():self.send_error(404);return
  data=(root/'baseline-app.js').read_bytes() if mode=='baseline' and p.name=='app.js' else p.read_bytes()
  if p.name=='index.html':data=data.replace(b'<head>',b'<head><script src="/fixture.js"></script>')
  self.send_response(200);self.send_header('Cache-Control','no-store');self.send_header('Content-Type','text/javascript' if p.suffix=='.js' else 'text/css' if p.suffix=='.css' else 'text/html' if p.suffix=='.html' else 'application/octet-stream');self.end_headers();self.wfile.write(data)
state={'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'source':'Production HTML/JS; synthetic delayed listing delivered after confirmed DELETE, no camera/USB','started':time.time()}
state['worktreeHead']=state['head']; state['head']='3358c0286e0a05e06e1128b53108dd03d9a68049' if mode=='baseline' else state['head']; state['mode']=mode; state['appSha256']=hashlib.sha256(((root/'baseline-app.js') if mode=='baseline' else static/'app.js').read_bytes()).hexdigest()
assert mode in ('baseline','fixed');assert not stop.exists();assert profile.exists();assert shutil.disk_usage(root).free>2.5*1024**3
server=http.server.ThreadingHTTPServer(('127.0.0.1',18792),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
env=os.environ.copy();env['LIBGL_ALWAYS_SOFTWARE']='true';env['XDG_CACHE_HOME']=str(root/'cache');env['XDG_CONFIG_HOME']=str(root/'config');env['XDG_DATA_HOME']=str(root/'data')
assert not any(k.startswith('MOZ_DISABLE') and v not in ('','0') for k,v in env.items())
f=(evidence/'firefox.log').open('w');p=subprocess.Popen(['/workspace/shared/guzzzi-recovery-20261012/tools/firefox/firefox','--new-instance','--profile',str(profile),'http://127.0.0.1:18792/app/index.html'],env=env,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
state.update(pid=p.pid,state='running');receipt.write_text(json.dumps(state,indent=2))
try:
 while p.poll() is None and not stop.exists() and time.time()-state['started']<300:
  if shutil.disk_usage(root).free<2.3*1024**3:state['reason']='disk-floor';break
  available=int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
  if available<1024**3:state['reason']='memory-floor';break
  time.sleep(.2)
finally:
 if p.poll() is None:os.killpg(p.pid,signal.SIGTERM)
 try:p.wait(timeout=5)
 except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
 server.shutdown();server.server_close();f.close();state.update(state='terminal',exit=p.returncode,ended=time.time());receipt.write_text(json.dumps(state,indent=2))
