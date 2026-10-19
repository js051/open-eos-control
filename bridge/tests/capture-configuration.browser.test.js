"use strict";
// Real production page, Fetch, DOM and synthetic configuration responses.
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
const RESULTS = process.env.OPEN_EOS_CONFIGURATION_BROWSER_RESULTS || path.join(ROOT, "test-results");
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
const naming = { stillFilenameMode: 'preset_code', stillFilenameModeOptions: ['preset_code', 'usersetting1', 'usersetting2'],
  stillUserSetting1: 'IMG_', stillUserSetting2: 'IMG', movieIndex: 'A1', movieReelNumber: 1,
  movieReelRange: { minimum: 1, maximum: 9999, step: 1 }, movieClipNumber: 1,
  movieClipRange: { minimum: 1, maximum: 999, step: 1 }, movieUserDefined: 'ABCDE' };
async function bounded(promise, label) {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(label)), 20000); })]); }
  finally { clearTimeout(timer); }
}
async function fixture(browser, origin, mode, language) {
  const context = await browser.newContext({ viewport: { width: 1300, height: 1000 } });
  const page = await context.newPage(), writes = [], reads = [], errors = [], replays = [];
  const gate = deferred(), entered = deferred();
  page.on('pageerror', error => errors.push(error.message));
  await page.route(/\/v1\/session$/, async route => {
    const response = await route.fetch(), value = await response.json();
    if (route.request().method() === 'POST') { value.engine = 'ccapi'; value.camera.engine = 'ccapi'; }
    await route.fulfill({ response, json: value });
  });
  await page.route(/\/capabilities$/, async route => {
    const response = await route.fetch(), value = await response.json();
    value.supported = ['DIRECTORY_CONTROL', 'FILE_NAMING_CONTROL'];
    value.settings = [{ key: 'directoryselection', label: 'Directory', value: '100EOSXX', values: ['100EOSXX'] }];
    value.fileNaming = naming;
    await route.fulfill({ response, json: value });
  });
  await page.route(/\/(directories|file-naming\/still-user-setting-1)$/, async route => {
    const request = route.request(), payload = request.postDataJSON(); writes.push({ method: request.method(), payload }); entered.resolve();
    if (['cancel', 'same-value-edit', 'retire'].includes(mode)) await gate.promise;
    if (mode === 'success') { await route.fulfill({ status: 200, json: { name: payload.name } }); return; }
    if (mode === 'lost') { await route.abort('failed'); return; }
    if (mode === 'redirect') { await route.fulfill({ status: 307, headers: { Location: '/unexpected-configuration-replay' } }); return; }
    if (mode === 'same-value-edit') { await route.fulfill({ status: 200, json: { ...naming, stillUserSetting1: payload.value } }); return; }
    const receipt = { version: 1, operation: 'create-directory', stage: 'acknowledged', requestedValue: payload.name,
      acknowledgedValue: mode === 'newline' ? `${payload.name}\n` : payload.name };
    const json = { error: { code: 'CAPTURE_CONFIG_READBACK_FAILED', engine: 'ccapi', feature: 'DIRECTORY_CONTROL', captureConfigReceipt: receipt } };
    try {
      if (mode === 'duplicate') await route.fulfill({ status: 502, contentType: 'application/json',
        body: JSON.stringify(json).replace('"stage":"acknowledged"', '"stage":"unknown","stage":"acknowledged"') });
      else await route.fulfill({ status: 502, json });
    } catch (error) { if (!['cancel', 'retire'].includes(mode)) throw error; }
  });
  await page.route(/\/capture-configuration\/(create-directory|set-file-naming)$/, async route => {
    reads.push(route.request().method());
    await route.fulfill({ status: 200, json: { operation: 'create-directory', directorySelection: { value: '101ABCDE', ability: ['100EOSXX', '101ABCDE'] } } });
  });
  await page.route('**/unexpected-configuration-replay', async route => { replays.push(route.request().method()); await route.fulfill({ status: 200, json: { name: 'ABCDE' } }); });
  await page.goto(origin, { waitUntil: 'networkidle' });
  await page.locator('#connection-view .language-select').selectOption(language);
  await page.waitForSelector('#connect-button:not([disabled])'); await page.click('#connect-button');
  await page.waitForSelector('#control-view:not([hidden])');
  return { page, context, writes, reads, errors, replays, gate, entered };
}
async function journey(browser, origin, mode, language = 'en') {
  const f = await fixture(browser, origin, mode, language);
  try {
    const directory = f.page.locator('[data-camera-command="directory"]');
    if (mode === 'same-value-edit') {
      await f.page.locator('[data-file-naming-field="still-user-setting-1"]').fill('B2XY');
      await f.page.locator('.settings-command').filter({ has: f.page.locator('[data-file-naming-field="still-user-setting-1"]') }).locator('[data-camera-command="file-naming"]').click();
    } else {
      await directory.locator('..').locator('input').fill('ABCDE'); await directory.click();
    }
    if (['cancel', 'same-value-edit', 'retire'].includes(mode)) {
      await bounded(f.entered.promise, 'Configuration write was not observed');
      if (mode === 'cancel') await f.page.locator('[data-capture-configuration-history] button').filter({ hasText: language === 'en' ? /^Cancel$/ : /^取消$/ }).click();
      if (mode === 'same-value-edit') await f.page.locator('[data-file-naming-field="still-user-setting-1"]').dispatchEvent('input');
      if (mode === 'retire') {
        // A pending configuration write must be explicitly cancelled before
        // the busy-session guard admits Disconnect. Keep the late reply held.
        assert.equal(await f.page.locator('#disconnect-button').isDisabled(), true);
        await f.page.locator('[data-capture-configuration-history] button').filter({ hasText: language === 'en' ? /^Cancel$/ : /^取消$/ }).click();
        await f.page.waitForSelector('#disconnect-button:not([disabled])');
        await f.page.click('#disconnect-button');
        await f.page.waitForSelector('#connection-view:not([hidden])');
      }
      f.gate.resolve();
    }
    if (mode === 'retire') {
      await f.page.click('#connect-button'); await f.page.waitForSelector('#control-view:not([hidden])');
      await f.page.waitForSelector('[data-capture-configuration-recovery][disabled]');
    } else {
      await f.page.waitForSelector('[data-capture-configuration-recovery]:not([disabled])');
      if (mode === 'same-value-edit') assert.equal(await f.page.locator('[data-file-naming-field="still-user-setting-1"]').inputValue(), 'B2XY');
      else if (mode === 'success') {
        assert.deepEqual(f.reads, ['GET']);
        await f.page.locator('#advanced-settings select[data-setting-key="directoryselection"] option[value="101ABCDE"]').waitFor({ state: 'attached' });
      } else {
        assert.equal(await directory.isDisabled(), true, 'consumed draft does not automatically resend');
        const text = await f.page.locator('[data-capture-configuration-history]').innerText();
        if (mode === 'ack-failed') assert.match(text, language === 'en' ? /acknowledgment/i : /已保留相機寫入回應/);
        if (['lost', 'duplicate', 'newline', 'redirect', 'cancel'].includes(mode)) assert.match(text, /unknown|未知|未確認|不明/i);
        await f.page.locator('[data-capture-configuration-recovery]').click();
        await f.page.waitForSelector('[data-capture-configuration-recovery]:not([disabled])');
        assert.deepEqual(f.reads, ['GET']);
      }
    }
    assert.equal(f.writes.length, 1); assert.deepEqual(f.replays, []); assert.deepEqual(f.errors, []);
    await f.page.screenshot({ path: path.join(RESULTS, `capture-configuration-${mode}-${language}.png`), fullPage: true });
    await retireFixtureSession(f);
  } finally { f.gate.resolve(); await f.context.close(); }
}
async function run() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'open-eos-configuration-browser-')); let server, browser;
  try {
    browser = await chromium.launch({ headless: true, ...(process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE ? { executablePath: process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE } : {}) });
    server = await startServer(directory); fs.mkdirSync(RESULTS, { recursive: true });
    for (const language of ['en', 'zh-TW']) await journey(browser, server.origin, 'ack-failed', language);
    for (const mode of ['success', 'lost', 'duplicate', 'newline', 'redirect', 'cancel', 'same-value-edit', 'retire']) await journey(browser, server.origin, mode);
    console.log('PASS: 10 production capture configuration browser journeys; ACK/read separation, exact bytes, no replay and draft ownership');
  } finally { if (browser) await browser.close(); await stopServer(server); fs.rmSync(directory, { recursive: true, force: true }); }
}
run().catch(error => { console.error(error); process.exitCode = 1; });
