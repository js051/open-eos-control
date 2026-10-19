"use strict";
// Real production page, Fetch, DOM and synthetic maintenance responses.
// No production state injection or real camera writes.
// No app function/state injection. These are not physical-camera tests.
const assert = require("node:assert/strict");
const { retireFixtureSession } = require("./browser-session-retirement.cjs");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");
const ROOT = path.resolve(__dirname, "..");
const RESULTS = process.env.OPEN_EOS_MAINTENANCE_BROWSER_RESULTS || path.join(ROOT, "test-results");
function deferred() { let resolve; const promise = new Promise((yes) => { resolve = yes; }); return { promise, resolve }; }
async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer(); server.once("error", reject);
    server.listen(0, "127.0.0.1", () => { const port = server.address().port; server.close((error) => error ? reject(error) : resolve(port)); });
  });
}
async function startServer(directory) {
  const port = await freePort();
  const child = spawn(process.env.PYTHON || "python3", ["-m", "uvicorn", "tests.maintenance_browser_server:application", "--factory", "--host", "127.0.0.1", "--port", String(port), "--log-level", "warning"],
    { cwd: ROOT, env: { ...process.env, OPEN_EOS_BROWSER_CAPTURE_DIR: directory }, stdio: ["ignore", "pipe", "pipe"] });
  let errors = ""; child.stderr.on("data", (chunk) => { errors += chunk; });
  const origin = `http://127.0.0.1:${port}`, deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Bridge exited: ${errors}`);
    try { if ((await fetch(`${origin}/health`)).ok) return { child, origin }; } catch (_) { /* Await socket binding. */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  child.kill(); throw new Error(`Bridge did not start: ${errors}`);
}
async function stopServer(server) {
  if (!server || server.child.exitCode !== null) return;
  server.child.kill();
  await Promise.race([new Promise((resolve) => server.child.once("exit", resolve)), new Promise((resolve) => setTimeout(resolve, 5000))]);
  if (server.child.exitCode === null) server.child.kill("SIGKILL");
}

async function bounded(promise) {
 let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Synthetic maintenance deadline')),20000);})]);}finally{clearTimeout(timer);}
}
async function journey(browser,origin,mode,language){
 const context=await browser.newContext({viewport:{width:1300,height:1000}}),page=await context.newPage();
 const writes=[],reads=[],replays=[],errors=[],entered=deferred(),gate=deferred();
 page.on('pageerror',error=>errors.push(error.message));
 await page.route(/\/capabilities$/,async route=>{const response=await route.fetch(),json=await response.json();
  json.supported=[...new Set([...json.supported.filter(feature=>feature!=='EVENT_POLLING'),'SENSOR_CLEANING','CAMERA_SLEEP'])];await route.fulfill({response,json});});
 await page.route(/\/maintenance\/(sensor-cleaning|sleep)\/outcome$/,async route=>{
  const operation=route.request().url().includes('/sleep/')?'sleep':'sensor-cleaning';writes.push({operation,method:route.request().method(),payload:route.request().postDataJSON()});entered.resolve();
  if(['cancel','reconnect'].includes(mode))await gate.promise;
  if(mode==='lost'){await route.abort('failed');return;}
  if(mode==='redirect'){await route.fulfill({status:307,headers:{location:'/synthetic-replay'}});return;}
  const json={version:1,engine:'libgphoto2',operation,autoPowerOff:false,responseEvidence:'response-received',clockReference:null,clockObservation:null,clockReadEvidence:'not-applicable'};
  try{if(mode==='duplicate')await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(json).replace('"version":1','"version":1,"version":2')});else await route.fulfill({status:200,json});}
  catch(error){if(!['cancel','reconnect'].includes(mode))throw error;}
 });
 await page.route(/\/maintenance\/(sensor-cleaning|sleep)$/,async route=>{assert.equal(route.request().method(),'GET');reads.push('GET');
  await route.fulfill({status:200,json:{version:1,engine:'libgphoto2',operation:mode==='sleep'?'sleep':'sensor-cleaning',observationEvidence:'reachable',clockObservation:null}});});
 await page.route('**/synthetic-replay',async route=>{replays.push(route.request().method());await route.fulfill({json:{}});});
 try{
  await page.goto(origin,{waitUntil:'networkidle'});await page.locator('#connection-view .language-select').selectOption(language);
  await page.waitForSelector('#connect-button:not([disabled])');await page.click('#connect-button');await page.waitForSelector('#control-view:not([hidden])');
  const command=mode==='sleep'?'sleep':'sensor-cleaning';page.once('dialog',d=>d.accept());
  await page.locator(`[data-camera-command="${command}"]`).first().click();await bounded(entered.promise);
  if(['cancel','reconnect'].includes(mode)){await page.locator('[data-maintenance-action="cancel"]').last().click();gate.resolve();}
  await page.waitForSelector('[data-maintenance-history] [data-maintenance-action="recover"]:not([disabled])');
  assert.equal(writes.length,1);assert.equal(writes[0].method,'POST');assert.deepEqual(writes[0].payload,{autoPowerOff:false});
  assert.deepEqual(reads,[],'no automatic post-action read');assert.deepEqual(replays,[]);
  assert.equal(await page.locator('#live-toggle-button').isDisabled(),true);
  if(mode==='reconnect'){
   await page.click('#disconnect-button');await page.waitForSelector('#connection-view:not([hidden])');await page.click('#connect-button');await page.waitForSelector('#control-view:not([hidden])');
   assert.equal(await page.locator('[data-maintenance-action="recover"]').last().isDisabled(),true);
  }else{
   await page.locator('[data-maintenance-action="recover"]').last().click();await page.waitForSelector('[data-maintenance-action="recover"]:not([disabled])');
   assert.deepEqual(reads,['GET']);assert.equal(writes.length,1);
   if(mode==='clean'){
    page.once('dialog',d=>d.accept());await page.locator('[data-maintenance-action="restore"]').last().click();
    await page.waitForSelector('#live-toggle-button:not([disabled])');assert.deepEqual(reads,['GET','GET']);
    assert.match(await page.locator('#live-toggle-button').textContent(),language==='en'?/Start/:/開始/);
   }else if(mode!=='sleep')assert.equal(await page.locator('[data-maintenance-action="restore"]').last().isDisabled(),true);
   if(['lost','duplicate','redirect'].includes(mode))assert.match(await page.locator('[data-maintenance-history]').textContent(),language==='en'?/Command response: Unknown/:/動作回應: 結果不確定/);
  }
  assert.equal(writes.length,1);assert.deepEqual(errors,[]);console.log(`maintenance ${mode} ${language}: passed`);
  await retireFixtureSession({ page }, { maintenance: !['clean', 'reconnect'].includes(mode) });
 }finally{gate.resolve();await context.close();}
}
(async()=>{
 fs.mkdirSync(RESULTS,{recursive:true});const directory=fs.mkdtempSync(path.join(os.tmpdir(),'eos-maintenance-browser-'));let server,browser;
 try{server=await startServer(directory);browser=await chromium.launch({headless:true});
  for(const mode of ['clean','sleep','lost','duplicate','redirect','cancel','reconnect'])await journey(browser,server.origin,mode,'en');
  await journey(browser,server.origin,'clean','zh-TW');
 }finally{if(browser)await browser.close();await stopServer(server);fs.rmSync(directory,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
