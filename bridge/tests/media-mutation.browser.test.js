"use strict";

// Real Chromium DOM and production app served by the real Bridge. Synthetic
// HTTP route fixtures control media/capability responses and mutation readback;
// no app state/functions are exposed or replaced. This is browser integration
// evidence, not physical-camera or Canon transport validation.
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");
const BRIDGE_ROOT = path.resolve(__dirname, "..");
const RESULTS = path.join(BRIDGE_ROOT, "test-results");
const FEATURES = ["MEDIA_BROWSER", "MEDIA_PROTECT", "MEDIA_RATING", "MEDIA_ROTATE", "MEDIA_ARCHIVE"];
const FIELDS = { protection: "protected", rating: "rating", rotation: "rotationDegrees", archive: "archived" };
const CHOICES = ["protection:true", "protection:false", ...[0, 1, 2, 3, 4, 5].map((n) => `rating:${n}`),
  ...[0, 90, 180, 270].map((n) => `rotation:${n}`), "archive:true", "archive:false"];
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }
async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer(); server.once("error", reject);
    server.listen(0, "127.0.0.1", () => { const port = server.address().port; server.close((error) => error ? reject(error) : resolve(port)); });
  });
}
async function startServer(captureDirectory) {
  const port = await freePort();
  const child = spawn(process.env.PYTHON || (process.platform === "win32" ? "python" : "python3"),
    ["-m", "uvicorn", "tests.browser_server:app", "--host", "127.0.0.1", "--port", String(port), "--log-level", "warning"],
    { cwd: BRIDGE_ROOT, env: { ...process.env, OPEN_EOS_BROWSER_CAPTURE_DIR: captureDirectory }, stdio: ["ignore", "pipe", "pipe"] });
  let errors = ""; child.stderr.on("data", (chunk) => { errors += chunk.toString(); });
  const origin = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Bridge exited: ${errors}`);
    try { if ((await fetch(`${origin}/health`)).ok) return { child, origin }; } catch (_) { /* Binding socket. */ }
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
async function fixture(browser, origin, language = "en", mode = "confirmed") {
  const context = await browser.newContext({ locale: language === "en" ? "en-US" : "zh-TW", viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  const errors = [], writes = [], reads = [];
  const gate = deferred();
  let listing = ["a", "b", "c"].map((id) => ({ id: `synthetic:${id}`, name: `SYNTHETIC_${id}.JPG`, kind: "image",
    archived: false, protected: false, rating: 0, rotationDegrees: 0, sizeBytes: 3, previewAvailable: false }));
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route(/\/capabilities$/, async (route) => {
    const response = await route.fetch(); const capabilities = await response.json();
    capabilities.supported = FEATURES; capabilities.settings = [];
    await route.fulfill({ response, json: capabilities });
  });
  await page.route(/\/v1\/session\/[^/]+\/media(?:\?.*)?$/, async (route) => {
    assert.equal(route.request().method(), "GET"); reads.push(route.request().url());
    await route.fulfill({ status: 200, contentType: "application/json", json: { items: listing } });
  });
  await page.route(/\/v1\/session\/[^/]+\/media\/[^/]+\/(?:protection|rating|rotation|archive)$/, async (route) => {
    assert.equal(route.request().method(), "PUT");
    const url = new URL(route.request().url()); const suffix = url.pathname.split("/").at(-1);
    const id = decodeURIComponent(url.pathname.split("/").at(-2)); const body = route.request().postDataJSON();
    const value = body.value ?? body.degrees ?? body.enabled;
    writes.push({ id, suffix, value });
    if ((mode === "cancel" && writes.length === 2) || mode === "disconnect") await gate.promise;
    const response = mode === "uncertain" && writes.length === 2 ? { id, [FIELDS[suffix]]: null } : { id, [FIELDS[suffix]]: value };
    // A browser abort may retire the held route. It is not a successful readback.
    try { await route.fulfill({ status: 200, contentType: "application/json", json: response }); }
    catch (error) { if (!["cancel", "disconnect"].includes(mode)) throw error; }
  });
  // Any added preflight metadata fanout makes this case fail immediately.
  await page.route(/\/media\/[^/]+\/info(?:\?.*)?$/, () => { throw new Error("Unexpected metadata fanout"); });
  await page.goto(origin, { waitUntil: "networkidle" });
  await page.locator("#connection-view .language-select").selectOption(language);
  await page.waitForSelector("#connect-button:not([disabled])"); await page.click("#connect-button");
  try {
    await page.waitForSelector("#control-view:not([hidden])");
  } catch (error) {
    await page.screenshot({ path: path.join(RESULTS, `desktop-media-mutation-connect-${language}.png`) })
      .catch(() => {});
    console.log("OEC_MEDIA_MUTATION_CONNECT", JSON.stringify({ pageErrorCount: errors.length }));
    throw error;
  }
  await page.click('.tab[data-view="media"]');
  await page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 3);
  await page.click("#media-select-filtered");
  return { context, page, errors, writes, reads, gate, setListing: (next) => { listing = next; }, getListing: () => listing };
}
async function waitRows(page, statuses) {
  await page.waitForFunction((expected) => {
    const rows = Array.from(document.querySelectorAll("#media-mutation-list li"), (row) => row.dataset.status);
    return JSON.stringify(rows) === JSON.stringify(expected);
  }, statuses);
}
async function confirmedChoices(browser, origin, language) {
  const f = await fixture(browser, origin, language);
  try {
    assert.deepEqual(await f.page.locator("#media-mutation-operation option").evaluateAll((nodes) => nodes.map((node) => node.value)), CHOICES);
    const initialReads = f.reads.length;
    for (const choice of CHOICES) {
      const before = f.writes.length;
      await f.page.selectOption("#media-mutation-operation", choice);
      await f.page.click("#media-mutation-start");
      await waitRows(f.page, ["confirmed", "confirmed", "confirmed"]);
      await f.page.waitForSelector("#media-mutation-start:not([disabled])");
      assert.equal(f.writes.length, before + 3);
      assert.deepEqual(f.writes.slice(before).map((row) => row.id), ["synthetic:a", "synthetic:b", "synthetic:c"]);
      const [suffix, value] = choice.split(":");
      assert.ok(f.writes.slice(before).every((row) => row.suffix === suffix && row.value === JSON.parse(value)));
    }
    assert.equal(f.reads.length, initialReads, "metadata batches do not fan out listings or info reads");
    assert.match(await f.page.locator("#media-mutation-summary").innerText(), language === "en" ? /3 confirmed/ : /已確認 3/);
    await f.page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await f.page.locator("#media-mutation-panel").scrollIntoViewIfNeeded();
    assert.ok(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await f.page.screenshot({ path: path.join(RESULTS, `desktop-media-mutations-${language}.png`), fullPage: true });
    assert.deepEqual(f.errors, []);
  } finally { await f.context.close(); }
}
async function frozenRoster(browser, origin) {
  const f = await fixture(browser, origin);
  try {
    f.setListing(f.getListing().slice(0, 2));
    await f.page.click("#media-refresh-button");
    await f.page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 2);
    await f.page.click('[data-media-filter="video"]');
    assert.equal(await f.page.locator(".media-select-checkbox").count(), 0);
    assert.match(await f.page.locator("#media-selection-summary").innerText(), /3 selected.*2 hidden.*1 not currently loaded/);
    await f.page.selectOption("#media-mutation-operation", "rating:3");
    await f.page.click("#media-mutation-start");
    await waitRows(f.page, ["confirmed", "confirmed", "not-started"]);
    assert.deepEqual(f.writes.map((row) => row.id), ["synthetic:a", "synthetic:b"]);
    assert.deepEqual(await f.page.locator("#media-mutation-list li").evaluateAll((rows) => rows.map((row) => row.dataset.mediaId)),
      ["synthetic:a", "synthetic:b", "synthetic:c"]);
    assert.deepEqual(f.errors, []);
  } finally { await f.context.close(); }
}
async function stoppedBatch(browser, origin, mode) {
  const f = await fixture(browser, origin, "en", mode);
  try {
    await f.page.selectOption("#media-mutation-operation", "rotation:90");
    await f.page.click("#media-mutation-start");
    if (mode === "cancel") {
      await waitRows(f.page, ["confirmed", "running", "queued"]);
      assert.equal(await f.page.locator("#media-mutation-operation").isDisabled(), true);
      await f.page.click("#media-mutation-cancel");
      await waitRows(f.page, ["confirmed", "uncertain", "not-started"]);
      f.gate.resolve();
    }
    await waitRows(f.page, ["confirmed", "uncertain", "not-started"]);
    assert.equal(f.writes.length, 2);
    assert.deepEqual(f.writes.map((row) => row.id), ["synthetic:a", "synthetic:b"]);
    assert.match(await f.page.locator("#media-mutation-summary").innerText(), /1 confirmed.*1 uncertain.*1 not started/);
    assert.deepEqual(f.errors, []);
  } finally { f.gate.resolve(); await f.context.close(); }
}
async function disconnectedOwner(browser, origin) {
  const f = await fixture(browser, origin, "en", "disconnect");
  try {
    await f.page.click("#media-mutation-start");
    await waitRows(f.page, ["running", "queued", "queued"]);
    await f.page.click("#disconnect-button");
    await f.page.waitForSelector("#connection-view:not([hidden])");
    f.gate.resolve();
    await f.page.click("#connect-button"); await f.page.waitForSelector("#control-view:not([hidden])");
    await f.page.click('.tab[data-view="media"]');
    assert.equal(await f.page.locator("#media-mutation-panel").isHidden(), true);
    assert.equal(f.writes.length, 1, "disconnect prevents writes for every later selected ID");
    assert.deepEqual(f.errors, []);
  } finally { f.gate.resolve(); await f.context.close(); }
}
async function run() {
  const captureDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-mutation-browser-"));
  let server, browser;
  try {
    server = await startServer(captureDirectory);
    const executablePath = process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE;
    browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
    fs.mkdirSync(RESULTS, { recursive: true });
    for (const language of ["en", "zh-TW"]) await confirmedChoices(browser, server.origin, language);
    await frozenRoster(browser, server.origin);
    await stoppedBatch(browser, server.origin, "uncertain");
    await stoppedBatch(browser, server.origin, "cancel");
    await disconnectedOwner(browser, server.origin);
    console.log("PASS: real browser metadata controls, bilingual results, frozen roster, readback, cancellation and disconnect");
  } finally {
    if (browser) await browser.close(); await stopServer(server);
    fs.rmSync(captureDirectory, { recursive: true, force: true });
  }
}
run().catch((error) => { console.error(error); process.exitCode = 1; });
