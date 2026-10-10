"use strict";

// Production PC DOM -> real Bridge HTTP -> independent Canon-shaped synthetic peer.
// Browser platform file handles are controlled to observe unpublished/closed output;
// no application state or app.js function is replaced by this test.
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");

const BRIDGE_ROOT = path.resolve(__dirname, "..");
const SIMULATOR_ROOT = path.resolve(BRIDGE_ROOT, "../simulator");
const RESULTS_DIR = path.join(BRIDGE_ROOT, "test-results");

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

function startServer(root, module, port, environment = {}) {
  const child = spawn(process.env.PYTHON || (process.platform === "win32" ? "python" : "python3"),
    ["-m", "uvicorn", module, "--host", "127.0.0.1", "--port", String(port), "--log-level", "warning"],
    { cwd: root, env: { ...globalThis.process.env, ...environment }, stdio: ["ignore", "pipe", "pipe"] });
  let errors = "";
  child.stderr.on("data", (chunk) => { errors += chunk.toString(); });
  return { process: child, errors: () => errors };
}

async function waitForServer(origin, server) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (server.process.exitCode !== null) throw new Error(`Server exited: ${server.errors()}`);
    try { if ((await fetch(`${origin}/health`)).ok) return; } catch (_) { /* Server is starting. */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Server not ready: ${server.errors()}`);
}

async function stopServer(server) {
  if (!server || server.process.exitCode !== null) return;
  server.process.kill();
  await Promise.race([
    new Promise((resolve) => server.process.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 5000)),
  ]);
  if (server.process.exitCode === null) server.process.kill("SIGKILL");
}

async function configure(origin, payload) {
  const response = await fetch(`${origin}/__test/capture-review`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload),
  });
  assert.equal(response.ok, true, `Synthetic fixture returned HTTP ${response.status}`);
  return response.json();
}

async function peerState(origin) {
  const response = await fetch(`${origin}/__test/capture-review`);
  assert.equal(response.ok, true);
  return response.json();
}

async function waitForPeer(origin, predicate, description) {
  const deadline = Date.now() + 10_000;
  let latest;
  while (Date.now() < deadline) {
    latest = await peerState(origin);
    if (predicate(latest)) return latest;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.fail(`Timed out waiting for ${description}: ${JSON.stringify(latest)}`);
}

async function connect(page, simulatorOrigin) {
  await page.click("#ccapi-mode-button");
  await page.fill("#ccapi-url-input", simulatorOrigin);
  await page.click("#connect-button");
  await page.waitForSelector("#control-view:not([hidden])");
  await page.waitForFunction(() => document.querySelector("#latest-media-label")?.textContent === "SYNTHETIC_OLD_A.JPG");
}

async function capture(page, simulatorOrigin) {
  await page.uncheck("#shutter-af-toggle");
  await page.click("#shutter-button");
  await waitForPeer(simulatorOrigin, (state) => state.capture_count === 1, "one physical-command-shaped POST");
}

async function waitForNew(page) {
  await page.waitForFunction(() => document.querySelector("#latest-media-label")?.textContent === "SIM_0003.JPG");
}

async function openPreview(page) {
  await page.click("#latest-media-button");
  await page.waitForFunction(() => {
    const image = document.querySelector("#media-preview-image");
    return document.querySelector("#media-preview-dialog")?.open && image && !image.hidden &&
      image.complete && image.naturalWidth === 160 && image.naturalHeight === 120;
  });
}

function filePlatform({ mode, delayWritable, pauseSecondWrite }) {
  if (mode === "blob") {
    Object.defineProperty(window, "showSaveFilePicker", { configurable: true, value: undefined });
    return;
  }
  window.__syntheticFiles = [];
  window.__syntheticPickerCalls = 0;
  if (pauseSecondWrite) {
    const fetchOriginal = window.fetch.bind(window);
    window.fetch = async (input, options) => {
      const response = await fetchOriginal(input, options);
      const url = new URL(typeof input === "string" ? input : input.url, location.href);
      if (!/^\/v1\/session\/[^/]+\/media\/[^/]+$/.test(url.pathname) || !response.body) return response;
      // Preserve the real Bridge request and bytes, but make the synthetic network
      // chunk size deterministic. Chromium may otherwise coalesce the whole route
      // response into one chunk, so a second OS write would never be reached.
      const framed = response.body.pipeThrough(new TransformStream({
        transform(chunk, controller) {
          for (let offset = 0; offset < chunk.byteLength; offset += 64 * 1024) {
            controller.enqueue(chunk.subarray(offset, offset + 64 * 1024));
          }
        },
      }));
      return new Response(framed, { status: response.status, statusText: response.statusText, headers: response.headers });
    };
  }
  window.showSaveFilePicker = async () => {
    window.__syntheticPickerCalls += 1;
    return { createWritable: async () => {
      // The OS picker has already returned. createWritable is asynchronous; while
      // it waits, normal DOM controls are accessible without bypassing a modal OS UI.
      if (delayWritable) {
        await new Promise((resolve) => { window.__releaseSyntheticWritable = resolve; });
      }
      const output = { bytes: [], closed: false, aborted: false, writeCount: 0 };
      window.__syntheticFiles.push(output);
      return {
        write: async (chunk) => {
          output.writeCount += 1;
          if (pauseSecondWrite && output.writeCount === 2) {
            await new Promise((resolve, reject) => {
              const timeout = setTimeout(() => reject(new Error("Synthetic writable gate exceeded 20 seconds")), 20_000);
              window.__releaseSyntheticWrite = () => { clearTimeout(timeout); resolve(); };
            });
          }
          // Avoid a large spread argument when the browser coalesces stream chunks.
          for (const byte of new Uint8Array(chunk)) output.bytes.push(byte);
        },
        close: async () => { output.closed = true; },
        abort: async () => { output.aborted = true; },
      };
    } };
  };
}

async function applyMediaDateRange(page, start, end) {
  await page.click("#media-date-button");
  await page.waitForSelector("#media-date-dialog[open]");
  await page.fill("#media-date-from", start);
  await page.fill("#media-date-to", end);
  await page.click("#media-date-apply");
  await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
}

async function installDatedListing(page, dateForName) {
  await page.route(/\/v1\/session\/[^/]+\/media(?:\?.*)?$/, async (route) => {
    const response = await route.fetch();
    const payload = await response.json();
    await route.fulfill({ response, json: {
      ...payload,
      items: payload.items.map((item) => ({ ...item, captureTime: dateForName(item.name) })),
    } });
  });
}

async function assertOneCapture(origin) {
  const state = await peerState(origin);
  assert.equal(state.capture_count, 1);
  assert.deepEqual(state.shutter_af_requests, [false], "Read-only recovery and transfer retry preserve AF intent");
}

async function assertNarrowRecoveryLayout(page, language) {
  await page.locator("#latest-media-review").scrollIntoViewIfNeeded();
  const geometry = await page.evaluate(() => {
    const selectors = ["#latest-media-review-status", "#latest-media-review [data-i18n='latestMediaReviewLimit']", "#latest-media-retry"];
    return {
      viewportWidth: innerWidth,
      pageWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
      elements: selectors.map((selector) => {
        const element = document.querySelector(selector);
        const bounds = element.getBoundingClientRect();
        return { selector, left: bounds.left, right: bounds.right, width: bounds.width, height: bounds.height,
          scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
          scrollHeight: element.scrollHeight, clientHeight: element.clientHeight };
      }),
    };
  });
  assert.equal(geometry.viewportWidth, 390);
  assert.ok(geometry.pageWidth <= geometry.viewportWidth + 1, `${language}: no horizontal page overflow`);
  for (const element of geometry.elements) {
    assert.ok(element.width > 0 && element.height > 0, `${language}: ${element.selector} must be visible`);
    assert.ok(element.left >= -1 && element.right <= geometry.viewportWidth + 1,
      `${language}: ${element.selector} must fit the narrow viewport`);
    assert.ok(element.scrollWidth <= element.clientWidth + 1 && element.scrollHeight <= element.clientHeight + 1,
      `${language}: ${element.selector} must not clip its content`);
  }
  await page.locator("#latest-media-retry").click({ trial: true });
  fs.mkdirSync(RESULTS_DIR, { recursive: true });
  await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-capture-review-narrow-${language}.png`), fullPage: true });
}

const cases = [
  ...["en", "zh-TW"].map((language) => ({
    name: `${language} out-of-range capture review stays isolated`,
    run: async ({ page, simulatorOrigin, listingRequests, cameraWrites, infoRequests }) => {
      await page.locator("#control-view .language-select").selectOption(language);
      await installDatedListing(page, (name) => name.startsWith("SYNTHETIC_OLD_") ? "2026-10-07" : "2026-10-09");
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE");
      const reads = { listing: listingRequests.length, info: infoRequests.length, writes: cameraWrites.length };
      await applyMediaDateRange(page, "2026-10-07", "2026-10-07");
      assert.equal(await page.locator(".media-card").count(), 2);
      assert.deepEqual({ listing: listingRequests.length, info: infoRequests.length, writes: cameraWrites.length }, reads);
      await page.click('.tab[data-view="live"]');
      await configure(simulatorOrigin, { mode: "new-first" });
      await capture(page, simulatorOrigin);
      await waitForNew(page);
      await openPreview(page);
      assert.equal(await page.locator("#media-preview-title").innerText(), "SIM_0003.JPG");
      assert.match(await page.locator("#media-preview-meta").innerText(), language === "en" ? /1 of 1/ : /第 1 個，共 1 個/);
      assert.equal(await page.isDisabled("#media-preview-previous"), true);
      assert.equal(await page.isDisabled("#media-preview-next"), true);
      fs.mkdirSync(RESULTS_DIR, { recursive: true });
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-media-date-isolated-capture-${language}.png`) });
      await page.click("#media-preview-close");
      await page.click('.tab[data-view="media"]');
      assert.equal(await page.locator("#media-date-summary").isVisible(), true);
      assert.equal(await page.locator(".media-card").filter({ hasText: "SIM_0003.JPG" }).count(), 0,
        "Explicit capture review must not insert an out-of-range item into the filtered library");
      await assertOneCapture(simulatorOrigin);
    },
  })),
  ...[false, true].map((cancel) => ({
    name: `date filter preserves active original ${cancel ? "cancellation" : "delivery"}`,
    mode: "direct", pauseSecondWrite: true,
    run: async ({ page, simulatorOrigin, listingRequests, cameraWrites, originalRequests, infoRequests }) => {
      await installDatedListing(page, (name) => name === "SYNTHETIC_OLD_A.JPG" ? "2026-10-07" : "2026-10-09");
      const original = Buffer.from(await (await fetch(`${simulatorOrigin}/__test/representation/original`)).arrayBuffer());
      const expected = Buffer.alloc(2 * 1024 * 1024, 0x5a);
      original.copy(expected);
      // A bounded synthetic original lets Chromium deliver several chunks. The
      // fake OS writable pauses its second write, after real progress is visible.
      await page.route(/\/v1\/session\/[^/]+\/media\/[^/]+$/, (route) => route.fulfill({
        contentType: "image/jpeg", headers: { "content-length": String(expected.length) }, body: expected,
      }));
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE");
      const target = page.locator(".media-card").filter({ hasText: "SYNTHETIC_OLD_A.JPG" }).locator("button.media-thumbnail");
      const targetId = await target.getAttribute("data-media-id");
      assert.ok(targetId, "The original target is taken from the rendered camera item");
      await target.click();
      await page.waitForSelector("#media-preview-dialog[open]");
      await page.click("#media-preview-download");
      await page.waitForFunction(() => typeof window.__releaseSyntheticWrite === "function" &&
        Number(document.querySelector("#media-transfer-progress")?.value) > 0);
      await page.click("#media-preview-close");
      assert.equal(await page.locator("#media-transfer").isVisible(), true);
      const progressBefore = await page.locator("#media-transfer-progress").getAttribute("value");
      const bytesBefore = await page.evaluate(() => window.__syntheticFiles[0].bytes.length);
      assert.ok(bytesBefore > 0 && bytesBefore < expected.length, "Filtering interrupts neither an empty nor an already completed transfer");
      const trafficBefore = { listing: listingRequests.length, info: infoRequests.length, writes: cameraWrites.length, original: originalRequests.length };
      assert.equal(trafficBefore.original, 1);
      await applyMediaDateRange(page, "2026-10-09", "2026-10-09");
      assert.deepEqual(await page.locator(".media-card .media-copy strong").allTextContents(), ["SYNTHETIC_OLD_B.JPG"]);
      assert.equal(await page.locator("#media-transfer-name").innerText(), "SYNTHETIC_OLD_A.JPG");
      assert.equal(await page.locator("#media-transfer-progress").getAttribute("value"), progressBefore);
      assert.equal(await page.evaluate(() => window.__syntheticFiles[0].bytes.length), bytesBefore);
      assert.equal(await page.isEnabled("#media-transfer-cancel"), true);
      await page.click("#media-date-clear");
      assert.equal(await page.locator(".media-card").count(), 2);
      await applyMediaDateRange(page, "2026-10-09", "2026-10-09");
      assert.deepEqual({ listing: listingRequests.length, info: infoRequests.length, writes: cameraWrites.length, original: originalRequests.length }, trafficBefore,
        "Apply/Clear cannot replace a transfer or fan out metadata requests");
      assert.equal(decodeURIComponent(new URL(originalRequests[0]).pathname.split("/").at(-1)), targetId);
      assert.equal(await page.locator("#media-transfer-name").innerText(), "SYNTHETIC_OLD_A.JPG");
      assert.equal(await page.locator("#media-transfer-progress").getAttribute("value"), progressBefore);
      assert.equal(await page.locator("#media-transfer").isVisible(), true);
      fs.mkdirSync(RESULTS_DIR, { recursive: true });
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-media-date-active-original-${cancel ? "cancel" : "deliver"}.png`) });
      if (cancel) await page.click("#media-transfer-cancel");
      await page.evaluate(() => window.__releaseSyntheticWrite());
      await page.waitForFunction(() => window.__syntheticFiles[0]?.closed || window.__syntheticFiles[0]?.aborted);
      const output = await page.evaluate(() => window.__syntheticFiles[0]);
      if (cancel) {
        assert.equal(output.aborted, true);
        assert.equal(output.closed, false);
        assert.deepEqual(Buffer.from(output.bytes), expected.subarray(0, output.bytes.length),
          "The aborted OS destination contains only bytes from the original target");
      } else {
        assert.equal(output.closed, true);
        assert.equal(output.aborted, false);
        assert.deepEqual(Buffer.from(output.bytes), expected, "Filtering preserves the exact selected original bytes");
      }
      await page.waitForSelector("#media-transfer[hidden]", { state: "attached" });
      assert.equal(originalRequests.length, 1);
      assert.equal((await peerState(simulatorOrigin)).capture_count, 0);
    },
  })),
  {
    name: "known candidates reordered",
    run: async ({ page, simulatorOrigin }) => {
      await configure(simulatorOrigin, { mode: "reordered" });
      const before = await peerState(simulatorOrigin);
      await capture(page, simulatorOrigin);
      await waitForPeer(simulatorOrigin, (state) => state.listing_reads >= before.listing_reads + 4,
        "four bounded reviews of already known media");
      assert.equal(await page.locator("#latest-media-label").innerText(), "SYNTHETIC_OLD_A.JPG");
      await page.getByRole("button", { name: /check again/i }).waitFor({ state: "visible" });
      await assertOneCapture(simulatorOrigin);
    },
  },
  {
    name: "new second candidate",
    run: async ({ page, simulatorOrigin }) => {
      await configure(simulatorOrigin, { mode: "new-second" });
      await capture(page, simulatorOrigin);
      await waitForNew(page);
      await openPreview(page);
      await assertOneCapture(simulatorOrigin);
    },
  },
  {
    name: "not ready repeated read-only recovery",
    run: async ({ page, simulatorOrigin, listingRequests, cameraWrites }) => {
      await capture(page, simulatorOrigin);
      const retry = page.getByRole("button", { name: /check again/i });
      await retry.waitFor({ state: "visible" });
      await page.waitForFunction(() => {
        const button = document.querySelector("#latest-media-retry");
        return button && !button.hidden && !button.disabled &&
          /not.*visible|not.*ready/i.test(document.querySelector("#latest-media-review-status")?.textContent || "");
      });
      assert.match(await page.locator("#latest-media-review-status").innerText(), /not.*visible|not.*ready/i);
      assert.equal(listingRequests.length, 5, "One connection read and four capture reads");
      assert.equal(await page.locator("#latest-media-label").innerText(), "SYNTHETIC_OLD_A.JPG");
      await page.click("#latest-media-button");
      await page.waitForSelector("#media-preview-dialog[open]");
      assert.equal(await page.locator("#media-preview-title").innerText(), "SYNTHETIC_OLD_A.JPG");
      await page.click("#media-preview-close");
      const writes = cameraWrites.slice();
      await retry.dblclick();
      await page.waitForFunction(() => {
        const button = document.querySelector("#latest-media-retry");
        return button && !button.hidden && !button.disabled &&
          /not.*visible|not.*ready/i.test(document.querySelector("#latest-media-review-status")?.textContent || "");
      });
      assert.equal(listingRequests.length, 9, "Repeated click must share one bounded review owner");
      await configure(simulatorOrigin, { mode: "new-second" });
      await retry.click();
      await waitForNew(page);
      assert.deepEqual(cameraWrites, writes, "The complete recovery emits no new camera write");
      assert.equal(await page.isChecked("#shutter-af-toggle"), false);
      await assertOneCapture(simulatorOrigin);
    },
  },
  {
    name: "listing failure offers clear read-only recovery",
    run: async ({ page, simulatorOrigin, cameraWrites }) => {
      await configure(simulatorOrigin, { mode: "unavailable" });
      await capture(page, simulatorOrigin);
      const retry = page.getByRole("button", { name: /check again/i });
      await retry.waitFor({ state: "visible" });
      await page.waitForFunction(() => !document.querySelector("#latest-media-retry")?.disabled);
      assert.match(await page.locator("#latest-media-review-status").innerText(), /list could not be read/i);
      assert.match(await page.locator("#latest-media-review").innerText(), /up to 8.*older file/s);
      const writes = cameraWrites.slice();
      await page.setViewportSize({ width: 390, height: 844 });
      await assertNarrowRecoveryLayout(page, "en");
      await retry.click();
      await page.waitForFunction(() => {
        const button = document.querySelector("#latest-media-retry");
        return button && !button.disabled &&
          /list could not be read/i.test(document.querySelector("#latest-media-review-status")?.textContent || "");
      });
      await page.locator("#control-view .language-select").selectOption("zh-TW");
      assert.match(await page.locator("#latest-media-review-status").innerText(), /無法讀取.*不會再次拍攝/);
      assert.match(await page.locator("#latest-media-review").innerText(), /最多 8 筆.*舊檔/s);
      await assertNarrowRecoveryLayout(page, "zh-TW");
      await configure(simulatorOrigin, { mode: "new-second" });
      await page.getByRole("button", { name: "重新查找", exact: true }).click();
      await waitForNew(page);
      assert.deepEqual(cameraWrites, writes);
      await assertOneCapture(simulatorOrigin);
    },
  },
  ...["blob", "direct"].map((mode) => ({
    name: `${mode} original delivery and short-file retry`, mode,
    run: async ({ page, simulatorOrigin }) => {
      await configure(simulatorOrigin, { mode: "new-first" });
      await capture(page, simulatorOrigin);
      await waitForNew(page);
      await openPreview(page);
      const representations = await Promise.all(["thumbnail", "display", "original"].map(async (kind) =>
        Buffer.from(await (await fetch(`${simulatorOrigin}/__test/representation/${kind}`)).arrayBuffer())));
      assert.notDeepEqual(representations[0], representations[1]);
      assert.notDeepEqual(representations[1], representations[2]);
      await configure(simulatorOrigin, { short_original: true });
      const downloads = [];
      page.on("download", (download) => downloads.push(download));
      await page.click("#media-preview-download");
      await waitForPeer(simulatorOrigin, (state) => state.original_reads === 1, "one short original transfer");
      await page.waitForFunction(() => !document.querySelector("#media-preview-download")?.disabled);
      assert.equal(downloads.length, 0, "No partial Blob may become a downloadable file");
      if (mode === "direct") {
        assert.deepEqual(await page.evaluate(() => window.__syntheticFiles.map(({ closed, aborted }) => ({ closed, aborted }))),
          [{ closed: false, aborted: true }]);
      }
      await configure(simulatorOrigin, { short_original: false });
      if (mode === "blob") {
        const pending = page.waitForEvent("download");
        await page.click("#media-preview-download");
        const download = await pending;
        const stream = await download.createReadStream();
        const chunks = [];
        for await (const chunk of stream) chunks.push(chunk);
        assert.equal(download.suggestedFilename(), "SIM_0003.JPG");
        assert.deepEqual(Buffer.concat(chunks), representations[2]);
      } else {
        await page.click("#media-preview-download");
        await page.waitForFunction(() => window.__syntheticFiles[1]?.closed);
        const bytes = await page.evaluate(() => window.__syntheticFiles[1].bytes);
        assert.deepEqual(Buffer.from(bytes), representations[2]);
      }
      const state = await peerState(simulatorOrigin);
      assert.equal(state.original_reads, 2);
      assert.ok(state.thumbnail_reads > 0 && state.preview_reads > 0);
      await assertOneCapture(simulatorOrigin);
    },
  })),
  {
    name: "pending file creation can be disconnected through normal UI", mode: "direct", delayWritable: true,
    run: async ({ page, simulatorOrigin, originalRequests }) => {
      await configure(simulatorOrigin, { mode: "new-first" });
      await capture(page, simulatorOrigin);
      await waitForNew(page);
      await openPreview(page);
      await page.click("#media-preview-download");
      await page.waitForFunction(() => typeof window.__releaseSyntheticWritable === "function");
      await page.click("#media-preview-close");
      assert.equal(await page.isDisabled("#disconnect-button"), false,
        "Reachability must come from the ordinary UI, not calling a private function or changing app state");
      await page.click("#disconnect-button");
      await page.waitForSelector("#connection-view:not([hidden])");
      await configure(simulatorOrigin, { mode: "old" });
      await connect(page, simulatorOrigin);
      await page.evaluate(() => window.__releaseSyntheticWritable());
      await page.waitForFunction(() => window.__syntheticFiles[0]?.closed || window.__syntheticFiles[0]?.aborted);
      assert.deepEqual(originalRequests, [], "An old picker must not download its item through the replacement session");
      assert.deepEqual(await page.evaluate(() => window.__syntheticFiles.map(({ closed, aborted }) => ({ closed, aborted }))),
        [{ closed: false, aborted: true }]);
      await assertOneCapture(simulatorOrigin);
    },
  },
];

async function run() {
  const [simulatorPort, bridgePort] = await Promise.all([freePort(), freePort()]);
  const simulatorOrigin = `http://127.0.0.1:${simulatorPort}`;
  const bridgeOrigin = `http://127.0.0.1:${bridgePort}`;
  const captureDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-capture-review-test-"));
  const simulator = startServer(SIMULATOR_ROOT, "capture_review_browser_server:app", simulatorPort);
  let bridge;
  let browser;
  const failures = [];
  try {
    await waitForServer(simulatorOrigin, simulator);
    bridge = startServer(BRIDGE_ROOT, "tests.browser_server:app", bridgePort,
      { OPEN_EOS_BROWSER_CAPTURE_DIR: captureDirectory });
    await waitForServer(bridgeOrigin, bridge);
    const executablePath = process.env.OPEN_EOS_TEST_CHROMIUM_EXECUTABLE;
    browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
    console.log(`Synthetic capture-review browser: Chromium ${browser.version()}; ${executablePath || "Playwright bundle"}`);
    const selected = process.env.CAPTURE_REVIEW_BROWSER_CASE;
    const selectedCases = cases.filter((entry) => !selected || entry.name === selected);
    assert.ok(selectedCases.length > 0, "The selected browser scenario must exist");
    for (const scenario of selectedCases) {
      await configure(simulatorOrigin, { reset: true });
      const context = await browser.newContext({ locale: "en-US", viewport: { width: 1440, height: 900 } });
      await context.addInitScript(filePlatform, {
        mode: scenario.mode || "blob", delayWritable: Boolean(scenario.delayWritable),
        pauseSecondWrite: Boolean(scenario.pauseSecondWrite),
      });
      const page = await context.newPage();
      page.setDefaultTimeout(12_000);
      const listingRequests = [];
      const cameraWrites = [];
      const originalRequests = [];
      const infoRequests = [];
      const pageErrors = [];
      page.on("pageerror", (error) => pageErrors.push(error.message));
      page.on("request", (request) => {
        const url = new URL(request.url());
        if (request.method() === "GET" && url.pathname.endsWith("/media")) listingRequests.push(request.url());
        if (request.method() !== "GET" && url.pathname.startsWith("/v1/session/")) cameraWrites.push(request.url());
        if (request.method() === "GET" && /^\/v1\/session\/[^/]+\/media\/[^/]+$/.test(url.pathname)) {
          originalRequests.push(request.url());
        }
        if (request.method() === "GET" && /^\/v1\/session\/[^/]+\/media\/[^/]+\/info$/.test(url.pathname)) {
          infoRequests.push(request.url());
        }
      });
      try {
        await page.goto(bridgeOrigin, { waitUntil: "networkidle" });
        await connect(page, simulatorOrigin);
        await scenario.run({ page, simulatorOrigin, listingRequests, cameraWrites, originalRequests, infoRequests });
        assert.deepEqual(pageErrors, []);
        console.log(`PASS: synthetic PC capture review: ${scenario.name}`);
      } catch (error) {
        failures.push(scenario.name);
        console.error(`FAIL: synthetic PC capture review: ${scenario.name}`, error);
      } finally {
        try {
          if (await page.locator("#media-date-dialog[open]").count()) await page.keyboard.press("Escape");
          if (await page.locator("#media-preview-dialog[open]").count()) await page.click("#media-preview-close");
          await page.evaluate(() => window.__releaseSyntheticWrite?.());
          if (await page.locator("#control-view:not([hidden])").count()) {
            await page.click("#disconnect-button");
            await page.waitForSelector("#connection-view:not([hidden])");
          }
        } catch (error) {
          failures.push(`${scenario.name} (cleanup)`);
          console.error(`Capture review cleanup failed: ${scenario.name}`, error);
        }
        try { await context.close(); } catch (error) {
          failures.push(`${scenario.name} (context close)`);
          console.error(`Capture review context close failed: ${scenario.name}`, error);
        }
      }
    }
    assert.deepEqual(failures, [], "Every selected production journey must pass");
  } finally {
    if (browser) await browser.close();
    await stopServer(bridge);
    await stopServer(simulator);
    fs.rmSync(captureDirectory, { recursive: true, force: true });
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
