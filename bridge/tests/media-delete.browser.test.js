"use strict";
// Real production page + permanent-delete confirmations + Fetch and DOM. Synthetic
// HTTP responses provide controlled acknowledgment/cancel/refresh boundaries.
// No app function/state injection. These are not physical-camera tests.
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");
const ROOT = path.resolve(__dirname, "..");
const RESULTS = process.env.OPEN_EOS_DELETE_BROWSER_RESULTS || path.join(ROOT, "test-results");
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
const ITEMS = [
  { id: "gphoto2:camera-A", name: "DUPLICATE.JPG", kind: "image", protected: false, writable: true, folderLabel: "/DCIM/A" },
  { id: "gphoto2-host:host-B", name: "DUPLICATE.JPG", kind: "image", protected: false, writable: true, folderLabel: "Desktop capture directory" },
  { id: "gphoto2:protected-C", name: "PROTECTED.JPG", kind: "image", protected: true, writable: true },
  { id: "unknown-D", name: "UNKNOWN.JPG", kind: "image", protected: false, writable: true },
];
async function fixture(browser, origin, mode, language = "en") {
  const context = await browser.newContext({ locale: language === "en" ? "en-US" : "zh-TW", viewport: { width: 900, height: 900 } });
  const page = await context.newPage(), errors = [], writes = [], reads = [], confirmations = [], gate = deferred(), entered = deferred();
  let current = ITEMS.map(item => ({ ...item }));
  page.on("pageerror", error => errors.push(error.message));
  page.on("dialog", async dialog => {
    confirmations.push(dialog.message());
    if (mode === "dismiss") await dialog.dismiss(); else await dialog.accept();
  });
  await page.route(/\/capabilities$/, async route => {
    const response = await route.fetch(), capabilities = await response.json();
    capabilities.supported = ["MEDIA_BROWSER", "MEDIA_DELETE"]; capabilities.settings = [];
    await route.fulfill({ response, json: capabilities });
  });
  await page.route(/\/v1\/session\/[^/]+\/media(?:\?.*)?$/, async route => {
    assert.equal(route.request().method(), "GET"); reads.push(route.request().url());
    await route.fulfill({ status: 200, contentType: "application/json", json: { items: current } });
  });
  await page.route(/\/v1\/session\/[^/]+\/media\/[^/?]+$/, async route => {
    assert.equal(route.request().method(), "DELETE");
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split("/").at(-1));
    writes.push(id); entered.resolve();
    assert.notEqual(id, "gphoto2:protected-C"); assert.notEqual(id, "unknown-D");
    if (["cancel", "overlap", "retire", "selection", "requalify"].includes(mode) && writes.length === 1) await gate.promise;
    if (mode === "lost") { await route.abort("failed"); return; }
    if (mode.startsWith("redirect")) {
      await route.fulfill({ status: Number(mode.split("-")[1]), headers: { Location: "/unexpected-delete-replay" } }); return;
    }
    const status = mode === "refused" && writes.length === 1 ? 403 : mode === "server-error" ? 503 : 204;
    if (status === 204) current = current.filter(item => item.id !== id);
    try { await route.fulfill({ status, ...(status === 204 ? {} : { body: "synthetic response" }) }); }
    catch (error) { if (!["cancel", "retire"].includes(mode)) throw error; }
  });
  const replays = [];
  await page.route("**/unexpected-delete-replay", async route => {
    replays.push(route.request().method()); await route.fulfill({ status: 204 });
  });
  await page.route(/\/media\/[^/]+\/info(?:\?.*)?$/, () => { throw new Error("No metadata fan-out allowed"); });
  await page.goto(origin, { waitUntil: "networkidle" });
  await page.locator("#connection-view .language-select").selectOption(language);
  await page.waitForSelector("#connect-button:not([disabled])"); await page.click("#connect-button");
  await page.waitForSelector("#control-view:not([hidden])"); await page.click('.tab[data-view="media"]');
  await page.waitForSelector(".media-card"); await page.click("#media-select-filtered");
  return { context, page, errors, writes, reads, confirmations, gate, entered, replays,
    protectSecond() { current = current.map(item => item.id === ITEMS[1].id ? { ...item, name: "PROTECTED-QUEUED.JPG", protected: true } : item); } };
}
async function runJourney(browser, origin, mode, language = "en") {
  const f = await fixture(browser, origin, mode, language);
  try {
    await f.page.click("#media-delete-start");
    assert.equal(f.confirmations.length, 1);
    for (const item of ITEMS) assert.ok(f.confirmations[0].includes(item.id));
    assert.match(f.confirmations[0], language === "en" ? /Connected desktop capture files/ : /已連線的桌面拍攝檔案/);
    assert.match(f.confirmations[0], language === "en" ? /Camera storage/ : /相機儲存空間/);
    if (mode === "dismiss") { assert.equal(f.writes.length, 0); assert.equal(await f.page.locator("#media-delete-panel").isVisible(), false); return; }
    if (["cancel", "overlap", "retire", "selection", "requalify"].includes(mode)) {
      await f.entered.promise;
      await f.page.waitForFunction(() => document.querySelector("#media-delete-cancel")?.hidden === false);
      if (mode === "cancel") await f.page.click("#media-delete-cancel");
      if (mode === "overlap") {
        await f.page.locator("#media-delete-start").evaluate(button => { button.disabled = false; button.click(); });
        assert.equal(f.confirmations.length, 1);
      }
      if (mode === "selection") {
        const checkbox = f.page.locator(`input[data-media-id="${ITEMS[0].id}"]`);
        await checkbox.uncheck(); await checkbox.check();
      }
      if (mode === "requalify") {
        f.protectSecond(); await f.page.click("#media-refresh-button");
        await f.page.getByText("PROTECTED-QUEUED.JPG", { exact: true }).waitFor({ state: "visible" });
      }
      if (mode === "retire") {
        await f.page.click("#disconnect-button"); await f.page.waitForSelector("#connection-view:not([hidden])");
        await f.page.click("#connect-button"); await f.page.waitForSelector("#control-view:not([hidden])");
        await f.page.click('.tab[data-view="media"]');
      }
      f.gate.resolve();
    }
    await f.page.waitForSelector("#media-delete-cancel[hidden]");
    const rows = await f.page.locator("#media-delete-list li").evaluateAll(nodes => nodes.map(node => ({ id: node.dataset.mediaId, status: node.dataset.outcome })));
    if (["lost", "server-error", "redirect-307", "redirect-308", "cancel", "retire"].includes(mode)) {
      assert.equal(f.writes.length, 1); assert.equal(rows[0].status, "uncertain");
      assert.ok(rows.slice(1).every(row => row.status === "not-started"));
    } else if (mode === "requalify") {
      assert.equal(f.writes.length, 1); assert.deepEqual(rows.map(row => row.status), ["confirmed", "skipped", "skipped", "skipped"]);
    } else {
      assert.deepEqual(f.writes, ITEMS.slice(0, 2).map(item => item.id));
      assert.deepEqual(rows.map(row => row.status), [mode === "refused" ? "rejected" : "confirmed", "confirmed", "skipped", "skipped"]);
      if (mode === "selection") assert.match(await f.page.locator("#media-selection-summary").innerText(), /3/);
    }
    assert.equal(f.replays.length, 0);
    const before = f.writes.length;
    await f.page.click("#media-delete-inspect"); await f.page.waitForSelector("#media-delete-inspect:not([disabled])");
    assert.equal(f.writes.length, before, "Read-only inspection never retries deletion");
    assert.deepEqual(f.errors, []);
    await f.page.screenshot({ path: path.join(RESULTS, `desktop-delete-${mode}-${language}.png`), fullPage: true });
  } finally { f.gate.resolve(); await f.context.close(); }
}
async function run() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-delete-browser-"));
  let server, browser;
  try {
    browser = await chromium.launch({ headless: true, ...(process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE ? { executablePath: process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE } : {}) });
    server = await startServer(directory); fs.mkdirSync(RESULTS, { recursive: true });
    for (const language of ["en", "zh-TW"]) await runJourney(browser, server.origin, "confirmed", language);
    for (const mode of ["dismiss", "refused", "lost", "server-error", "redirect-307", "redirect-308", "cancel", "overlap", "retire", "selection", "requalify"]) await runJourney(browser, server.origin, mode);
    console.log("PASS: 13 production browser deletion journeys; exact mixed targets, strict outcomes, cancellation, selection and owner isolation");
  } finally {
    if (browser) await browser.close(); await stopServer(server);
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
run().catch(error => { console.error(error); process.exitCode = 1; });
