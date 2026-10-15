"use strict";
// Real production page + native file input/FileChooser + Fetch and DOM. Synthetic
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
const RESULTS = process.env.OPEN_EOS_UPLOAD_BROWSER_RESULTS || path.join(ROOT, "test-results");
const FILE = { name: "SYNTHETIC.JPG", mimeType: "image/jpeg", buffer: Buffer.from("four") };
const RECEIPT = { id: "synthetic:upload-receipt", name: FILE.name, kind: "photo", sizeBytes: 4, previewAvailable: false };
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
async function fixture(browser, origin, mode = "confirmed", language = "en") {
  const context = await browser.newContext({ locale: language === "en" ? "en-US" : "zh-TW", viewport: { width: 390, height: 844 } });
  const page = await context.newPage(), errors = [], writes = [], reads = [], redirectedWrites = [], gate = deferred();
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route(/\/capabilities$/, async (route) => {
    const response = await route.fetch(), capabilities = await response.json();
    capabilities.supported = ["MEDIA_BROWSER", "MEDIA_UPLOAD"]; capabilities.settings = [];
    await route.fulfill({ response, json: capabilities });
  });
  await page.route(/\/v1\/session\/[^/]+\/media(?:\?.*)?$/, async (route) => {
    if (route.request().method() === "POST") {
      writes.push({ url: route.request().url(), body: route.request().postDataBuffer() });
      assert.deepEqual(writes.at(-1).body, FILE.buffer);
      if (mode.startsWith("redirect-")) {
        await route.fulfill({ status: Number(mode.split("-")[1]), headers: { Location: "/synthetic-upload-redirect" } });
        return;
      }
      if (mode === "cancel" || mode === "disconnect") await gate.promise;
      if (mode === "lost-response") { await route.abort("failed"); return; }
      try { await route.fulfill({ status: 201, contentType: "application/json", json: mode === "invalid" ? { ...RECEIPT, sizeBytes: null } : RECEIPT }); }
      catch (error) { if (!["cancel", "disconnect"].includes(mode)) throw error; }
      return;
    }
    assert.equal(route.request().method(), "GET"); reads.push(route.request().url());
    if (mode === "refresh-reconnect" && reads.length === 2) await gate.promise;
    // Pre-existing same name and size in another folder is intentionally present.
    try { await route.fulfill({ status: 200, contentType: "application/json", json: { items: [{ ...RECEIPT, id: "synthetic:existing-other-folder" }] } }); }
    catch (error) { if (mode !== "refresh-reconnect") throw error; }
  });
  await page.route("**/synthetic-upload-redirect", async (route) => {
    redirectedWrites.push(route.request().url());
    await route.fulfill({ status: 201, json: RECEIPT });
  });
  await page.route(/\/media\/[^/]+\/info(?:\?.*)?$/, () => { throw new Error("Unexpected metadata fanout"); });
  await page.goto(origin, { waitUntil: "networkidle" });
  await page.locator("#connection-view .language-select").selectOption(language);
  await page.waitForSelector("#connect-button:not([disabled])"); await page.click("#connect-button");
  await page.waitForSelector("#control-view:not([hidden])"); await page.click('.tab[data-view="media"]');
  await page.waitForSelector(".media-card");
  return { context, page, errors, writes, reads, redirectedWrites, gate };
}
async function picker(page) {
  const waiting = page.waitForEvent("filechooser"); await page.click("#media-upload-button"); return waiting;
}
async function choose(page) { const chooser = await picker(page); await chooser.setFiles(FILE); return chooser; }
async function outcome(page, value) { await page.waitForSelector(`#media-upload-outcome[data-outcome="${value}"]:not([hidden])`); }
async function reconnect(page) {
  await page.click("#disconnect-button"); await page.waitForSelector("#connection-view:not([hidden])");
  await page.click("#connect-button"); await page.waitForSelector("#control-view:not([hidden])"); await page.click('.tab[data-view="media"]');
}
async function confirmed(browser, origin, language) {
  const f = await fixture(browser, origin, "confirmed", language);
  try {
    const chooser = await choose(f.page); await outcome(f.page, "confirmed");
    await chooser.element().evaluate((input) => input.dispatchEvent(new Event("change", { bubbles: true })));
    assert.equal(f.writes.length, 1);
    assert.match(await f.page.locator("#media-upload-outcome-message").innerText(), language === "en" ? /Upload confirmed/ : /已確認上傳/);
    await f.page.waitForSelector("#media-upload-review:not([disabled])");
    const count = f.reads.length; await f.page.click("#media-upload-review");
    await f.page.waitForFunction(() => !document.querySelector("#media-upload-review").disabled);
    assert.equal(f.reads.length, count + 1); assert.equal(f.writes.length, 1); await outcome(f.page, "confirmed");
    await f.page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await f.page.locator("#media-upload-outcome").scrollIntoViewIfNeeded();
    assert.ok(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await f.page.screenshot({ path: path.join(RESULTS, `desktop-upload-confirmed-${language}.png`), fullPage: true });
    assert.deepEqual(f.errors, []);
  } finally { await f.context.close(); }
}
async function uncertain(browser, origin, mode, language = "en") {
  const f = await fixture(browser, origin, mode, language);
  try {
    await choose(f.page);
    if (mode === "cancel") { await f.page.waitForSelector("#media-transfer:not([hidden])"); await f.page.click("#media-transfer-cancel"); f.gate.resolve(); }
    await outcome(f.page, "uncertain");
    assert.equal(f.writes.length, 1); assert.equal(f.redirectedWrites.length, 0); assert.equal(f.reads.length, 1, "uncertainty does not trigger a reconciliation scan");
    assert.match(await f.page.locator("#media-upload-outcome-message").innerText(), language === "en" ? /result unknown/ : /結果不明/);
    await f.page.click("#media-upload-review"); await f.page.waitForSelector("#media-upload-review:not([disabled])");
    await outcome(f.page, "uncertain"); assert.equal(f.writes.length, 1);
    await f.page.screenshot({ path: path.join(RESULTS, `desktop-upload-${mode}-${language}.png`), fullPage: true });
    assert.deepEqual(f.errors, []);
  } finally { f.gate.resolve(); await f.context.close(); }
}
async function oldPicker(browser, origin, newerPicker) {
  const f = await fixture(browser, origin);
  try {
    const old = await picker(f.page);
    if (newerPicker) await picker(f.page); else await reconnect(f.page);
    await old.element().evaluate((input) => {
      const data = new DataTransfer(); data.items.add(new File(["four"], "SYNTHETIC.JPG", { type: "image/jpeg" }));
      input.files = data.files; input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await f.page.waitForTimeout(100);
    assert.equal(f.writes.length, 0); assert.deepEqual(f.errors, []);
  } finally { await f.context.close(); }
}
async function retired(browser, origin, mode) {
  const f = await fixture(browser, origin, mode);
  try {
    await choose(f.page);
    if (mode === "refresh-reconnect") await outcome(f.page, "confirmed");
    await reconnect(f.page); f.gate.resolve();
    await outcome(f.page, mode === "refresh-reconnect" ? "confirmed" : "uncertain");
    await f.page.waitForSelector("#media-upload-previous-camera:not([hidden])");
    assert.equal(f.writes.length, 1); assert.deepEqual(f.errors, []);
  } finally { f.gate.resolve(); await f.context.close(); }
}
async function run() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-upload-browser-"));
  let server, browser;
  try {
    // Launch first: a missing browser must be a clear NOT_RUN prerequisite,
    // without unnecessarily starting the synthetic camera server.
    browser = await chromium.launch({ headless: true, ...(process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE ? { executablePath: process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE } : {}) });
    server = await startServer(directory); fs.mkdirSync(RESULTS, { recursive: true });
    for (const language of ["en", "zh-TW"]) { await confirmed(browser, server.origin, language); await uncertain(browser, server.origin, "cancel", language); }
    await uncertain(browser, server.origin, "lost-response"); await uncertain(browser, server.origin, "invalid");
    await uncertain(browser, server.origin, "redirect-307"); await uncertain(browser, server.origin, "redirect-308");
    await oldPicker(browser, server.origin, false); await oldPicker(browser, server.origin, true);
    await retired(browser, server.origin, "disconnect"); await retired(browser, server.origin, "refresh-reconnect");
    console.log("PASS: 12 real-browser upload journeys, bilingual outcomes, native picker owners, cancel, reconnect and read-only review");
  } finally {
    if (browser) await browser.close(); await stopServer(server);
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
run().catch((error) => { console.error(error); process.exitCode = 1; });
