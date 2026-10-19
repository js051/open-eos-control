"use strict";
// Real production page, Fetch, DOM and synthetic ordinary-setting responses.
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
const RESULTS = process.env.OPEN_EOS_SHOOTING_SETTING_BROWSER_RESULTS || path.join(ROOT, "test-results");
function deferred() { let resolve; const promise = new Promise((yes) => { resolve = yes; }); return { promise, resolve }; }
async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer(); server.once("error", reject);
    server.listen(0, "127.0.0.1", () => { const port = server.address().port; server.close((error) => error ? reject(error) : resolve(port)); });
  });
}
async function startServer(directory) {
  const port = await freePort();
  const child = spawn(process.env.PYTHON || "python3", ["-m", "uvicorn", "tests.browser_server:app", "--host", "127.0.0.1", "--port", String(port), "--log-level", "warning"],
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

const setting = value => ({key:'iso',label:'ISO',value,values:['400','800'],inputKind:'choice',maxLength:null});
async function bounded(promise) {
  let timer;
  try {return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Synthetic setting deadline')),20000);})]);}
  finally {clearTimeout(timer);}
}
async function journey(browser,origin,mode,language) {
  const context=await browser.newContext({viewport:{width:1300,height:1000}}),page=await context.newPage();
  const writes=[],reads=[],replays=[],errors=[],gate=deferred(),entered=deferred();
  page.on('pageerror',error=>errors.push(error.message));
  await page.route(/\/capabilities$/,async route=>{
    const response=await route.fetch(),json=await response.json();json.settings=[setting('400')];
    await route.fulfill({response,json});
  });
  await page.route(/\/settings\/iso\/outcome$/,async route=>{
    writes.push({method:route.request().method(),payload:route.request().postDataJSON()});entered.resolve();
    if(mode==='cancel'||mode==='reconnect')await gate.promise;
    if(mode==='lost'){await route.abort('failed');return;}
    if(mode==='redirect'){await route.fulfill({status:307,headers:{location:'/synthetic-replay'}});return;}
    const json={version:1,engine:'libgphoto2',key:'iso',requestedValue:'800',writeEvidence:'response-received',
      readbackEvidence:mode==='mismatch'?'mismatch':mode==='read-failed'?'unavailable':'matched',
      setting:mode==='read-failed'?null:setting(mode==='mismatch'?'400':'800')};
    try {
      if(mode==='duplicate')await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(json).replace('"version":1','"version":1,"version":2')});
      else await route.fulfill({status:200,json});
    } catch(error){if(!['cancel','reconnect'].includes(mode))throw error;}
  });
  await page.route(/\/settings\/iso$/,async route=>{
    assert.equal(route.request().method(),'GET');reads.push('GET');
    await route.fulfill({status:200,json:{version:1,engine:'libgphoto2',setting:setting('800')}});
  });
  await page.route('**/synthetic-replay',async route=>{replays.push(route.request().method());await route.fulfill({json:{}});});
  try {
    await page.goto(origin,{waitUntil:'networkidle'});
    await page.locator('#connection-view .language-select').selectOption(language);
    await page.waitForSelector('#connect-button:not([disabled])');await page.click('#connect-button');
    await page.waitForSelector('#control-view:not([hidden])');
    await page.click('.exposure-control[data-setting-key="iso"]');
    await page.locator('#setting-dialog button[data-setting-value="800"]').click();
    assert.equal(writes.length,0,'selection alone never writes');
    await page.locator('#setting-dialog .button.primary').click();await bounded(entered.promise);
    if(mode==='cancel') {await page.keyboard.press('Escape');gate.resolve();}
    if(mode==='reconnect') {
      await page.keyboard.press('Escape');await page.click('#disconnect-button');
      await page.waitForSelector('#connection-view:not([hidden])');await page.click('#connect-button');
      await page.waitForSelector('#control-view:not([hidden])');gate.resolve();
    }
    await page.waitForSelector('[data-setting-history] [data-setting-outcome-action="recover"]');
    assert.equal(writes.length,1);assert.deepEqual(writes[0],{method:'POST',payload:{value:'800'}});
    assert.equal(replays.length,0);
    if(!['matched','cancel','reconnect'].includes(mode)) {
      if(await page.locator('#setting-dialog').evaluate(d=>d.open))await page.keyboard.press('Escape');
      const recovery=page.locator('[data-setting-history] [data-setting-outcome-action="recover"]').last();
      await recovery.click();await page.waitForFunction(()=>document.querySelector('[data-setting-history] small')?.textContent?.includes('800'));
      assert.deepEqual(reads,['GET']);assert.equal(writes.length,1);
      if(['lost','duplicate','redirect'].includes(mode))assert.match(await page.locator('[data-setting-history]').textContent(),language==='en'?/Write result unknown/:/寫入結果不確定/);
    }
    if(mode==='reconnect')assert.equal(await page.locator('[data-setting-history] [data-setting-outcome-action="recover"]').last().isDisabled(),true);
    assert.deepEqual(errors,[]);
    console.log(`ordinary setting ${mode} ${language}: passed`);
    await retireFixtureSession({ page });
  } finally {gate.resolve();await context.close();}
}
(async()=>{
  fs.mkdirSync(RESULTS,{recursive:true});const directory=fs.mkdtempSync(path.join(os.tmpdir(),'eos-settings-browser-'));
  let server,browser;
  try {
    server=await startServer(directory);browser=await chromium.launch({headless:true});
    for(const mode of ['matched','mismatch','read-failed','lost','duplicate','redirect','cancel','reconnect'])await journey(browser,server.origin,mode,'en');
    await journey(browser,server.origin,'read-failed','zh-TW');
  } finally {if(browser)await browser.close();await stopServer(server);fs.rmSync(directory,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
