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
const { datedListingResponse } = require("./media-listing-browser-fixture.js");

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

async function capture(page, simulatorOrigin, expectedCaptureCount = 1) {
  await page.uncheck("#shutter-af-toggle");
  await page.click("#shutter-button");
  await waitForPeer(simulatorOrigin, (state) => state.capture_count === expectedCaptureCount, "the exact explicit capture count");
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

async function startForegroundImportAfterGallerySettles(page, {
  expectedNames = ["SYNTHETIC_OLD_A.JPG", "SYNTHETIC_OLD_B.JPG"],
  expectedLatest = "SYNTHETIC_OLD_A.JPG", previousPickerCalls = 0,
} = {}) {
  // Finish the explicitly expected baseline's real gallery
  // reads before the single Start gesture; lazy thumbnail admission can otherwise
  // change the button's availability while that gesture is being delivered.
  await page.waitForFunction(count => document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE" &&
    document.querySelectorAll("#media-list .media-thumbnail").length === count, expectedNames.length);
  assert.deepEqual((await page.locator("#media-list .media-copy strong").allTextContents()).sort(),
    [...expectedNames].sort());
  for (let index = 0; index < expectedNames.length; index += 1) {
    // Both cards need their own observation at the narrow 200% layout.
    await page.locator("#media-list .media-thumbnail").nth(index).scrollIntoViewIfNeeded();
    await page.waitForFunction((position) => {
      const thumbnail = document.querySelectorAll("#media-list .media-thumbnail")[position];
      const image = thumbnail?.querySelector("img");
      return !thumbnail?.classList.contains("loading") && image?.complete &&
        image.naturalWidth === 32 && image.naturalHeight === 24;
    }, index);
  }
  await page.waitForFunction(expected => {
    const image = document.querySelector("#latest-media-thumbnail img");
    return document.querySelector("#latest-media-label")?.textContent === expected &&
      image?.complete && image.naturalWidth === 32 && image.naturalHeight === 24;
  }, expectedLatest);
  await page.locator("#media-auto-start").scrollIntoViewIfNeeded();
  await page.waitForFunction(() => document.querySelector("#media-auto-start")?.disabled === false);
  assert.equal(await page.evaluate(() => window.__batchPickerCalls), previousPickerCalls);
  await page.click("#media-auto-start");
  await page.waitForFunction(expected => window.__batchPickerCalls === expected, previousPickerCalls + 1);
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

// Only the browser's directory/writer surface is synthetic. Production DOM,
// selection, queue, Bridge HTTP and Canon-shaped original bytes remain real.
function batchFilePlatform({ pauseFirstWrite = false, unsupported = false }) {
  if (unsupported) {
    Object.defineProperty(window, "showDirectoryPicker", { configurable: true, value: undefined });
    return;
  }
  window.__batchFiles = [];
  window.__batchPickerCalls = 0;
  const directories = new Map();
  const missing = () => new DOMException("Synthetic entry absent", "NotFoundError");
  window.showDirectoryPicker = async (options) => {
    if (options?.mode !== "readwrite") throw new Error("Expected explicit readwrite directory picker");
    window.__batchPickerCalls += 1;
    return {
      kind: "directory",
      name: "Synthetic destination",
      async getDirectoryHandle(name, { create = false } = {}) {
        if (directories.has(name)) return directories.get(name);
        if (!create) throw missing();
        const files = new Map();
        const directory = {
          kind: "directory",
          name,
          async getFileHandle(filename, { create: createFile = false } = {}) {
            if (files.has(filename)) return files.get(filename);
            if (!createFile) throw missing();
            const output = { name: filename, directory: name, bytes: [], closed: false, aborted: false };
            window.__batchFiles.push(output);
            const handle = { kind: "file", name: filename, async createWritable() {
              return {
                async write(chunk) {
                  if (pauseFirstWrite && window.__batchFiles[0] === output && !output.writeStarted) {
                    output.writeStarted = true;
                    await new Promise((resolve, reject) => {
                      const timer = setTimeout(() => reject(new Error("Batch synthetic write gate timed out")), 20_000);
                      window.__releaseBatchWrite = () => { clearTimeout(timer); resolve(); };
                    });
                  }
                  for (const byte of new Uint8Array(chunk)) output.bytes.push(byte);
                },
                async close() { output.closed = true; },
                async abort() { output.aborted = true; },
              };
            } };
            files.set(filename, handle);
            return handle;
          },
        };
        directories.set(name, directory);
        return directory;
      },
    };
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
    const decorated = datedListingResponse(await response.text(), response.headers(), dateForName);
    await route.fulfill({ response, ...decorated });
  });
}

async function holdNextResponse(page, pattern, routeCleanups) {
  let release;
  const released = new Promise((resolve) => { release = resolve; });
  let pending;
  const handler = (route) => {
    pending = (async () => {
      // Keep the real Bridge response; only its delivery order is controlled.
      const response = await route.fetch({ timeout: 12_000 });
      await released;
      await route.fulfill({ response });
    })();
    return pending;
  };
  routeCleanups.push(async () => {
    release();
    await page.unroute(pattern, handler);
    await pending;
  });
  await page.route(pattern, handler, { times: 1 });
  return { release, settled: () => {
    assert.ok(pending, "The held response must have reached its handler");
    return pending;
  } };
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
  {
    name: "closing pending image preview cancels native fetch and permits reopening",
    run: async ({ page, simulatorOrigin, cameraWrites, routeCleanups }) => {
      const writes = cameraWrites.slice();
      const before = await page.evaluate(() => ({
        toast: document.querySelector("#toast").textContent,
        lastError: JSON.parse(document.querySelector("#diagnostics-output").textContent).lastError,
      }));
      await page.evaluate(() => {
        const originalFetch = window.fetch;
        window.__syntheticPreviewFetch = { observed: false, outcome: null };
        window.fetch = function (...args) {
          const result = originalFetch.apply(this, args);
          const url = String(args[0]);
          if (!window.__syntheticPreviewFetch.observed && /\/media\/[^/]+\/preview$/.test(url)) {
            window.__syntheticPreviewFetch.observed = true;
            // Observe native rejection without replacing the response or signal.
            return result.then((response) => {
              window.__syntheticPreviewFetch.outcome = "resolved";
              return response;
            }, (error) => {
              window.__syntheticPreviewFetch.outcome = error.name;
              throw error;
            });
          }
          return result;
        };
        window.__restoreSyntheticPreviewFetch = () => { window.fetch = originalFetch; };
      });
      routeCleanups.push(() => page.evaluate(() => window.__restoreSyntheticPreviewFetch()));
      const previewPattern = /\/v1\/session\/[^/]+\/media\/[^/]+\/preview$/;
      const held = await holdNextResponse(page, previewPattern, routeCleanups);
      await Promise.all([page.waitForRequest(previewPattern), page.click("#latest-media-button")]);
      await page.waitForSelector("#media-preview-dialog[open]");
      assert.equal(await page.locator("#media-preview-image").getAttribute("src"), null);
      assert.equal(await page.locator("#media-preview-loading").isVisible(), true);
      await page.click("#media-preview-close");
      // The real fetch must reject before the held response or cleanup is released.
      await page.waitForFunction(() => window.__syntheticPreviewFetch.outcome === "AbortError");
      assert.equal(await page.locator("#media-preview-dialog").evaluate((dialog) => dialog.open), false);
      held.release();
      await held.settled();
      assert.equal(await page.locator("#media-preview-image").getAttribute("src"), null);
      assert.deepEqual(await page.evaluate(() => ({
        toast: document.querySelector("#toast").textContent,
        lastError: JSON.parse(document.querySelector("#diagnostics-output").textContent).lastError,
      })), before);
      await openPreview(page);
      assert.equal(await page.locator("#media-preview-title").innerText(), "SYNTHETIC_OLD_A.JPG");
      await page.click("#media-preview-close");
      assert.deepEqual(cameraWrites, writes);
      assert.equal((await peerState(simulatorOrigin)).capture_count, 0);
    },
  },
  ...["en", "zh-TW"].map((language) => ({
    name: `${language} out-of-range capture review stays isolated`,
    run: async ({ page, simulatorOrigin, listingRequests, cameraWrites, infoRequests, routeCleanups }) => {
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
      const capabilitiesPattern = /\/v1\/session\/[^/]+\/capabilities$/;
      const capabilities = await holdNextResponse(page, capabilitiesPattern, routeCleanups);
      // The capture's contents event refreshes capabilities before reloading the
      // already-opened library. Hold that real response until review owns the UI.
      const [contentsEvent] = await Promise.all([
        page.waitForResponse(async (response) => {
          if (response.request().method() !== "GET" ||
            !/\/v1\/session\/[^/]+\/events$/.test(response.url())) return false;
          return (await response.json()).changedKeys?.some((key) => String(key).toLowerCase().includes("content"));
        }),
        page.waitForRequest(capabilitiesPattern),
        capture(page, simulatorOrigin),
      ]);
      assert.ok((await contentsEvent.json()).changedKeys?.some((key) => String(key).toLowerCase().includes("content")),
        "The held capabilities refresh follows the capture's real contents event");
      await waitForNew(page);
      let preview;
      let readySource;
      if (language === "en") {
        await openPreview(page);
        readySource = await page.locator("#media-preview-image").getAttribute("src");
      } else {
        const previewPattern = /\/v1\/session\/[^/]+\/media\/[^/]+\/preview$/;
        preview = await holdNextResponse(page, previewPattern, routeCleanups);
        await Promise.all([
          page.waitForRequest(previewPattern),
          page.click("#latest-media-button"),
        ]);
        await page.waitForSelector("#media-preview-dialog[open]");
      }
      const reloaded = page.waitForResponse((response) => response.request().method() === "GET" &&
        /\/v1\/session\/[^/]+\/media\?limit=61$/.test(response.url()));
      capabilities.release();
      await reloaded;
      // COMPLETE alone could still describe the old two-item listing. Require
      // the rendered three-item total to prove the new response was consumed.
      await page.waitForFunction((expectedCount) =>
        document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE" &&
        document.querySelector("#media-date-summary")?.textContent.includes(expectedCount),
      language === "en" ? "2 of 3 loaded items match" : "已載入 3 項中符合 2 項");
      assert.equal(await page.locator("#media-preview-dialog").evaluate((dialog) => dialog.open), true,
        "A delayed contents refresh must preserve the independently opened capture review");
      if (preview) {
        assert.equal(await page.locator("#media-preview-image").getAttribute("src"), null,
          "The preview response is still held while the contents refresh completes");
        preview.release();
      } else {
        assert.equal(await page.locator("#media-preview-image").getAttribute("src"), readySource,
          "A contents refresh must preserve the already-decoded preview object URL");
      }
      await page.waitForFunction(() => {
        const image = document.querySelector("#media-preview-image");
        return document.querySelector("#media-preview-dialog")?.open && image && !image.hidden &&
          image.complete && image.naturalWidth === 160 && image.naturalHeight === 120;
      });
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
          document.querySelector("#latest-media-review-status")?.textContent ===
            "No new media was observed in the completed checks. Check again without taking another photo.";
      });
      assert.equal(await page.locator("#latest-media-review-status").innerText(),
        "No new media was observed in the completed checks. Check again without taking another photo.");
      assert.equal(listingRequests.length, 5, "One connection read and four capture reads");
      assert.equal(await page.locator("#latest-media-label").innerText(), "SYNTHETIC_OLD_A.JPG");
      await page.click("#latest-media-button");
      await page.waitForSelector("#media-preview-dialog[open]");
      assert.equal(await page.locator("#media-preview-title").innerText(), "SYNTHETIC_OLD_A.JPG");
      await page.click("#media-preview-close");
      const writes = cameraWrites.slice();
      const retryBefore = await retry.boundingBox();
      const halfPressBefore = await page.locator("#half-press-button").boundingBox();
      assert.ok(retryBefore && halfPressBefore);
      // Fixed synthetic controls only: distinguish a repeated recovery gesture
      // from a camera control moving under its second click.
      await page.evaluate(() => {
        const ids = new Set(["latest-media-retry", "half-press-button", "autofocus-button"]);
        window.__captureReviewGesture = [];
        for (const type of ["pointerdown", "pointerup", "click", "dblclick"]) {
          document.addEventListener(type, (event) => {
            const button = event.target.closest?.("button");
            if (!ids.has(button?.id) || window.__captureReviewGesture.length >= 24) return;
            const rect = button.getBoundingClientRect();
            window.__captureReviewGesture.push({ type, id: button.id, detail: event.detail,
              x: event.clientX, y: event.clientY, top: rect.top, height: rect.height,
              disabled: button.disabled });
          }, true);
        }
      });
      await retry.dblclick();
      assert.deepEqual(cameraWrites, writes, "The repeated recovery gesture emits no camera write");
      assert.equal((await retry.boundingBox())?.y, retryBefore.y,
        "Searching must keep the recovery target under the repeated gesture");
      await page.waitForFunction(() => {
        const button = document.querySelector("#latest-media-retry");
        return button && !button.hidden && !button.disabled &&
          document.querySelector("#latest-media-review-status")?.textContent ===
            "No new media was observed in the completed checks. Check again without taking another photo.";
      });
      assert.equal(listingRequests.length, 9, "Repeated click must share one bounded review owner");
      await configure(simulatorOrigin, { mode: "new-second" });
      await retry.click();
      await waitForNew(page);
      assert.deepEqual(cameraWrites, writes, "The complete recovery emits no new camera write");
      assert.ok((await page.locator("#half-press-button").boundingBox())?.y >= halfPressBefore.y,
        "Discovery must not pull a camera mutation into the former recovery target");
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

for (const language of ["en", "zh-TW"]) {
  cases.push({
    name: `batch originals preserve hidden selection and confirm closed bytes ${language}`,
    batch: {},
    run: async ({ page, simulatorOrigin, originalRequests, infoRequests, listingRequests, cameraWrites }) => {
      await page.locator("#control-view .language-select").selectOption(language);
      await page.setViewportSize({ width: 390, height: 844 });
      await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 2);
      const before = { info: infoRequests.length, listing: listingRequests.length, writes: cameraWrites.length };
      await page.click("#media-select-filtered");
      await page.click('[data-media-filter="video"]');
      assert.equal(await page.locator(".media-select-checkbox").count(), 0);
      assert.equal(await page.isDisabled("#media-batch-start"), false, "Hidden selection remains transferable");
      await page.click("#media-batch-start");
      await page.waitForFunction(() => document.querySelectorAll('#media-batch-list [data-status="completed"]').length === 2);
      const files = await page.evaluate(() => window.__batchFiles);
      const expected = Buffer.from(await (await fetch(`${simulatorOrigin}/__test/representation/original`)).arrayBuffer());
      assert.equal(files.length, 2);
      for (const file of files) {
        assert.equal(file.closed, true); assert.equal(file.aborted, false);
        assert.deepEqual(Buffer.from(file.bytes), expected, "Saved bytes are the original representation");
      }
      assert.equal(new Set(files.map(file => file.directory)).size, 1);
      assert.equal(new Set(files.map(file => file.name)).size, 2);
      assert.deepEqual(files.map(file => file.name).sort(), ["SYNTHETIC_OLD_A.JPG", "SYNTHETIC_OLD_B.JPG"]);
      assert.equal(originalRequests.length, 2);
      const requestedIds = originalRequests.map(url => decodeURIComponent(new URL(url).pathname.split("/").at(-1)));
      assert.equal(new Set(requestedIds).size, 2, "Each selected raw media ID is requested once");
      assert.equal(await page.evaluate(() => window.__batchPickerCalls), 1);
      assert.deepEqual({ info: infoRequests.length, listing: listingRequests.length, writes: cameraWrites.length }, before,
        "Batch selection and delivery add no metadata, listing or camera mutation requests");
      assert.equal((await peerState(simulatorOrigin)).capture_count, 0);
      await page.locator("#media-batch-panel").scrollIntoViewIfNeeded();
      const width = await page.evaluate(() => ({ viewport: innerWidth, page: document.documentElement.scrollWidth }));
      assert.ok(width.page <= width.viewport + 1, "Batch summary fits narrow 200% layout");
      fs.mkdirSync(RESULTS_DIR, { recursive: true });
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-batch-${language}.png`), fullPage: true });
      await page.locator('#control-view [data-open-media-history]').click();
      await page.waitForFunction(() => document.querySelectorAll('#media-history-list [data-outcome="completed"]').length === 2);
      await page.keyboard.press("Escape");
      await page.reload({ waitUntil: "networkidle" });
      await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
      await page.locator('#connection-view [data-open-media-history]').click();
      await page.waitForFunction(() => document.querySelectorAll('#media-history-list [data-outcome="completed"]').length === 2);
      assert.equal(originalRequests.length, 2, "Opening persisted history must not replay original requests");
      assert.equal(await page.locator('#media-history-dialog').evaluate(dialog => dialog.contains(document.activeElement)), true);
      const historyWidth = await page.locator('#media-history-dialog').evaluate(dialog => ({width: dialog.clientWidth, content: dialog.scrollWidth}));
      assert.ok(historyWidth.content <= historyWidth.width + 1, "History fits narrow 200% layout");
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-batch-history-${language}.png`) });
      await page.click('#media-history-clear');
      await page.click('#media-history-confirm-no');
      assert.equal(await page.locator('#media-history-list li').count(), 2);
      await page.click('#media-history-clear');
      await page.click('#media-history-confirm-yes');
      await page.waitForFunction(() => document.querySelector('#media-history-confirm').hidden && document.querySelectorAll('#media-history-list li').length === 0);
      await page.keyboard.press("Escape");
      await page.locator('#connection-view [data-open-media-history]').click();
      await page.waitForFunction(() => document.querySelector('#media-history-clear').disabled);
      assert.equal(await page.locator('#media-history-list li').count(), 0);
      await page.keyboard.press("Escape");
      assert.equal(await page.locator('#connection-view [data-open-media-history]').evaluate(button => document.activeElement === button), true);

    },
  });
}
cases.push({
  name: "unsupported directory API keeps single-file controls without batch requests",
  batch: { unsupported: true },
  run: async ({ page, originalRequests }) => {
    await page.click('.tab[data-view="media"]');
    await page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 2);
    await page.click("#media-select-filtered");
    assert.equal(await page.isVisible("#media-batch-unavailable"), true);
    assert.equal(await page.isDisabled("#media-batch-start"), true);
    assert.deepEqual(originalRequests, []);
    await page.locator(".media-actions button").first().click();
    await page.waitForSelector("#media-details-dialog[open]");
    await page.waitForSelector("#media-details-download:not([disabled])");
    assert.equal(await page.isDisabled("#media-details-download"), false);
    await page.keyboard.press("Escape");
  },
});
cases.push({
  name: "batch cancellation stops later originals and preserves uncertain current output",
  batch: { pauseFirstWrite: true },
  run: async ({ page, originalRequests }) => {
    await page.click('.tab[data-view="media"]');
    await page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 2);
    await page.click("#media-select-filtered");
    await page.click("#media-batch-start");
    await page.waitForFunction(() => typeof window.__releaseBatchWrite === "function");
    await page.click("#media-batch-cancel");
    await page.evaluate(() => window.__releaseBatchWrite());
    await page.waitForFunction(() => window.__batchFiles[0]?.aborted);
    await page.waitForFunction(() => document.querySelector('#media-batch-list [data-status="not-started"]'));
    assert.equal(originalRequests.length, 1, "Cancellation prevents the second camera original request");
    assert.equal(await page.evaluate(() => window.__batchFiles.some(file => file.closed)), false);
    assert.equal(await page.locator('#media-batch-list [data-status="completed"]').count(), 0);
  },
});


cases.push({
  name: "complete batch worklist survives reload with uncertain attempt and queued row without automatic requests",
  batch: { pauseFirstWrite: true },
  run: async ({ page, originalRequests }) => {
    await page.click('.tab[data-view="media"]');
    await page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 2);
    await page.click("#media-select-filtered");
    await page.click("#media-batch-start");
    await page.waitForFunction(() => typeof window.__releaseBatchWrite === "function");
    assert.equal(originalRequests.length, 1);
    await page.reload({ waitUntil: "networkidle" });
    await page.locator('#connection-view [data-open-media-history]').click();
    await page.waitForFunction(() => document.querySelectorAll('#media-history-list [data-outcome="cancelled-or-unknown"]').length === 1);
    assert.equal(await page.locator('#media-history-list [data-outcome="completed"]').count(), 0);
    assert.equal(originalRequests.length, 1, "Reload only displays history; it cannot resume camera commands");
    const stored = await page.evaluate(async () => {
      const store = OpenEOSMediaTransferHistory.createHistoryStore();
      const result = await store.list(); store.close(); return result;
    });
    assert.equal(stored.ok, true);
    assert.equal(stored.entries[0].unconfirmed, true);
    assert.equal(stored.plans.length, 1);
    assert.equal(stored.plans[0].rows.length, 2, "The complete selected roster survives reload");
    assert.deepEqual(stored.plans[0].rows.map(row => row.outcome), ["cancelled-or-unknown", "queued"]);
    assert.equal(stored.plans[0].rows[0].receipt.receiptId, stored.plans[0].rows[0].rowId);
    assert.equal(stored.plans[0].rows[1].receipt, null);
    assert.equal(await page.locator('#media-history-list [data-outcome="queued"]').count(), 1);
    assert.equal(await page.locator('#media-history-list li').count(), 2, "The first receipt is not duplicated outside its worklist");
    assert.equal(JSON.stringify(stored).includes('/ccapi/'), false);
    assert.equal(JSON.stringify(stored).includes('synthetic-session'), false);
    await page.keyboard.press("Escape");
  },
});
for (const language of ["en", "zh-TW"]) {
  cases.push({
    name: `manual unfinished worklist continuation requires binding and saves a linked new result ${language}`,
    batch: { pauseFirstWrite: true },
    run: async ({ page, simulatorOrigin, originalRequests, infoRequests, listingRequests }) => {
      await page.locator("#control-view .language-select").selectOption(language);
      await page.setViewportSize({ width: 390, height: 844 });
      await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 2);
      await page.click("#media-select-filtered");
      await page.click("#media-batch-start");
      await page.waitForFunction(() => typeof window.__releaseBatchWrite === "function");
      await page.click("#media-batch-cancel");
      await page.evaluate(() => window.__releaseBatchWrite());
      await page.waitForFunction(() => !document.querySelector("#media-batch-start").disabled);
      await page.locator('#control-view [data-open-media-history]').click();
      await page.waitForFunction(() => document.querySelectorAll('#media-history-list select').length === 2);
      const readHistory = () => page.evaluate(async () => {
        const store = OpenEOSMediaTransferHistory.createHistoryStore();
        const result = await store.list(); store.close(); return result;
      });
      const geometry = await page.locator("#media-history-dialog").evaluate(dialog => ({ width: dialog.clientWidth, content: dialog.scrollWidth }));
      assert.ok(geometry.content <= geometry.width + 1, "Source context must fit the narrow large-font dialog");
      const listGeometry = await page.locator("#media-history-list").evaluate(list => ({ width: list.clientWidth, content: list.scrollWidth }));
      assert.ok(listGeometry.content <= listGeometry.width + 1, "Source controls must not hide overflow inside the history list");
      const before = await readHistory();
      const traffic = { info: infoRequests.length, listing: listingRequests.length, original: originalRequests.length };
      assert.equal(await page.isDisabled("#media-continuation-start"), true);
      const queued = page.locator('#media-history-list [data-outcome="queued"] select');
      await queued.selectOption("1");
      assert.equal(await page.isDisabled("#media-continuation-start"), true, "Even queued rows may still be owned by another tab");
      await page.check("#media-continuation-acknowledge");
      assert.equal(await page.isDisabled("#media-continuation-start"), false);
      assert.deepEqual({ info: infoRequests.length, listing: listingRequests.length, original: originalRequests.length }, traffic,
        "Mapping and confirmation do not request any camera bytes");
      await page.click("#media-continuation-start");
      await page.waitForFunction(() => document.querySelectorAll('#media-batch-list [data-status="completed"]').length === 1);
      assert.equal(originalRequests.length, traffic.original + 1);
      assert.equal(await page.evaluate(() => window.__batchPickerCalls), 2);
      const files = await page.evaluate(() => window.__batchFiles.filter(file => file.closed));
      assert.equal(files.length, 1);
      const expected = Buffer.from(await (await fetch(`${simulatorOrigin}/__test/representation/original`)).arrayBuffer());
      assert.deepEqual(Buffer.from(files[0].bytes), expected);
      const after = await readHistory();
      assert.equal(after.plans.length, 2);
      assert.deepEqual(after.plans[1], before.plans[0], "The old worklist is preserved exactly");
      assert.equal(after.plans[0].rows[0].outcome, "completed");
      assert.equal(after.plans[0].rows[0].origin.batchId, before.plans[0].batchId);
      assert.equal(after.plans[0].rows[0].origin.rowId, before.plans[0].rows[1].rowId);
    },
  });
}

cases.push({
  name: "clearing history from a second tab prevents late batch records without stopping files",
  batch: { pauseFirstWrite: true },
  run: async ({ page, originalRequests }) => {
    await page.click('.tab[data-view="media"]');
    await page.waitForFunction(() => document.querySelectorAll(".media-select-checkbox").length === 2);
    await page.click("#media-select-filtered");
    await page.click("#media-batch-start");
    await page.waitForFunction(() => typeof window.__releaseBatchWrite === "function");
    const observer = await page.context().newPage();
    try {
      await observer.goto(page.url(), { waitUntil: "networkidle" });
      await observer.locator('#connection-view [data-open-media-history]').click();
      await observer.waitForFunction(() => document.querySelectorAll('#media-history-list li').length === 2);
      assert.equal(await observer.locator('#media-history-list [data-outcome="cancelled-or-unknown"]').count(), 1);
      assert.equal(await observer.locator('#media-history-list [data-outcome="queued"]').count(), 1);
      await observer.click('#media-history-clear');
      await observer.click('#media-history-confirm-yes');
      await observer.waitForFunction(() => document.querySelector('#media-history-confirm').hidden && document.querySelectorAll('#media-history-list li').length === 0);
      await page.evaluate(() => window.__releaseBatchWrite());
      await page.waitForFunction(() => document.querySelectorAll('#media-batch-list [data-status="completed"]').length === 2);
      assert.equal(originalRequests.length, 2);
      assert.equal(await page.evaluate(() => window.__batchFiles.filter(file => file.closed).length), 2);
      await observer.keyboard.press("Escape");
      await observer.locator('#connection-view [data-open-media-history]').click();
      await observer.waitForFunction(() => document.querySelector('#media-history-clear').disabled);
      assert.equal(await observer.locator('#media-history-list li').count(), 0, "Old epoch finish and later attempts cannot rebuild cleared history");
    } finally { await observer.close(); }
  },
});

for (const language of ["en", "zh-TW"]) {
  cases.push({
    name: `foreground JPEG import establishes baseline and saves only a new original ${language}`,
    batch: {},
    run: async ({ page, simulatorOrigin, originalRequests, infoRequests }) => {
      await page.locator("#control-view .language-select").selectOption(language);
      await page.setViewportSize({ width: 390, height: 844 });
      await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
      await page.click('.tab[data-view="media"]');
      assert.equal(await page.evaluate(() => window.__batchPickerCalls), 0);
      await startForegroundImportAfterGallerySettles(page);
      await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "watching");
      assert.deepEqual(originalRequests, [], "Baseline files are never imported");
      assert.equal(await page.evaluate(() => window.__batchFiles.length), 0);
      await configure(simulatorOrigin, { mode: "new-first" });
      await page.click('.tab[data-view="live"]');
      await capture(page, simulatorOrigin);
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelectorAll('#media-auto-list [data-status="completed"]').length === 1,
        null, { timeout: 20_000 });
      await page.click("#media-auto-stop");
      await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "stopped");
      const files = await page.evaluate(() => window.__batchFiles);
      const expected = Buffer.from(await (await fetch(`${simulatorOrigin}/__test/representation/original`)).arrayBuffer());
      assert.equal(files.length, 1); assert.equal(files[0].name, "SIM_0003.JPG");
      assert.equal(files[0].closed, true); assert.equal(files[0].aborted, false);
      assert.deepEqual(Buffer.from(files[0].bytes), expected);
      assert.equal(originalRequests.length, 1);
      assert.equal(new URL(originalRequests[0]).searchParams.get("attempt"), "single");
      assert.equal(new URL(originalRequests[0]).searchParams.get("validation"), "jpeg-v1");
      const fresh = infoRequests.filter((url) => new URL(url).searchParams.get("fresh") === "true");
      assert.equal(fresh.length, 2, "Only the new JPEG receives two current size observations");
      assert.equal(new Set(fresh.map((url) => new URL(url).pathname)).size, 1);
      await assertOneCapture(simulatorOrigin);
      const width = await page.evaluate(() => ({ viewport: innerWidth, content: document.documentElement.scrollWidth }));
      assert.ok(width.content <= width.viewport + 1, "Foreground status fits narrow 200% layout");
      await page.locator("#media-auto-panel").scrollIntoViewIfNeeded();
      fs.mkdirSync(RESULTS_DIR, { recursive: true });
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-foreground-import-${language}.png`), fullPage: true });
      await page.reload({ waitUntil: "networkidle" });
      assert.equal(originalRequests.length, 1, "Reload cannot restart the foreground import");
      assert.equal(await page.evaluate(() => window.__batchPickerCalls), 0, "No restored handle or permission request");
    },
  });
}
cases.push({
  name: "foreground import refuses a legacy response without complete inventory evidence",
  batch: {},
  run: async ({ page, originalRequests, infoRequests, simulatorOrigin, routeCleanups }) => {
    const pattern = /\/v1\/session\/[^/]+\/media\?.*inventory=strict/;
    const handler = async (route) => {
      const response = await route.fetch();
      const headers = response.headers(); delete headers["x-open-eos-inventory"];
      await route.fulfill({ response, headers });
    };
    await page.route(pattern, handler);
    routeCleanups.push(() => page.unroute(pattern, handler));
    await page.click('.tab[data-view="media"]');
    await startForegroundImportAfterGallerySettles(page);
    await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "stopped");
    assert.deepEqual(originalRequests, []);
    assert.equal(infoRequests.filter((url) => new URL(url).searchParams.has("fresh")).length, 0);
    assert.equal(await page.evaluate(() => window.__batchFiles.length), 0);
    assert.equal((await peerState(simulatorOrigin)).capture_count, 0);
    assert.equal(await page.isDisabled("#media-auto-start"), false, "A later attempt needs a new explicit gesture");
  },
});
cases.push({
  name: "foreground import cancellation preserves uncertain output and prevents later reads",
  batch: { pauseFirstWrite: true },
  run: async ({ page, simulatorOrigin, originalRequests }) => {
    await page.click('.tab[data-view="media"]');
    await startForegroundImportAfterGallerySettles(page);
    await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "watching");
    await configure(simulatorOrigin, { mode: "new-first" });
    await page.click('.tab[data-view="live"]');
    await capture(page, simulatorOrigin);
    await page.click('.tab[data-view="media"]');
    await page.waitForFunction(() => typeof window.__releaseBatchWrite === "function", null, { timeout: 20_000 });
    await page.click("#media-auto-stop");
    await page.evaluate(() => window.__releaseBatchWrite());
    await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "stopped");
    assert.equal(originalRequests.length, 1);
    assert.equal(await page.evaluate(() => window.__batchFiles[0]?.closed), false);
    assert.equal(await page.evaluate(() => window.__batchFiles[0]?.aborted), true);
    assert.equal(await page.locator('#media-auto-list [data-status="completed"]').count(), 0);
    assert.equal(await page.locator('#media-auto-list [data-status="cancelled-or-unknown"]').count(), 1);
    await assertOneCapture(simulatorOrigin);
  },
});

for (const fault of ["missing-proof", "premature-entropy"]) {
  cases.push({
    name: `automatic JPEG validation rejects ${fault} and explicit recovery skips the failed ID`,
    batch: {},
    run: async ({ page, simulatorOrigin, originalRequests, routeCleanups }) => {
      const pattern = /\/v1\/session\/[^/]+\/media\/[^/?]+\?attempt=single&validation=jpeg-v1/;
      const handler = async (route) => {
        const response = await route.fetch();
        const headers = response.headers(); delete headers["x-open-eos-original-validation"];
        await route.fulfill({ response, headers });
      };
      if (fault === "missing-proof") {
        await page.route(pattern, handler);
        routeCleanups.push(() => page.unroute(pattern, handler));
      }
      await page.click('.tab[data-view="media"]');
      await startForegroundImportAfterGallerySettles(page);
      await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "watching");
      await configure(simulatorOrigin, { mode: "new-first", invalid_original: fault === "premature-entropy" });
      await page.click('.tab[data-view="live"]');
      await capture(page, simulatorOrigin);
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "stopped",
        null, { timeout: 20_000 });
      assert.equal(originalRequests.length, 1);
      assert.equal(await page.locator('#media-auto-list [data-status="failed"]').count(), 1);
      const files = await page.evaluate(() => window.__batchFiles);
      assert.equal(files.length, 1);
      assert.equal(files[0].closed, false);
      assert.equal(files[0].aborted, true);
      assert.equal(files[0].bytes.length, 0);
      await assertOneCapture(simulatorOrigin);
      if (fault === "missing-proof") await page.unroute(pattern, handler);
      await configure(simulatorOrigin, { invalid_original: false });
      await startForegroundImportAfterGallerySettles(page, {
        expectedNames: ["SYNTHETIC_OLD_A.JPG", "SYNTHETIC_OLD_B.JPG", "SIM_0003.JPG"],
        expectedLatest: "SIM_0003.JPG", previousPickerCalls: 1,
      });
      await page.waitForFunction(() => document.querySelector("#media-auto-panel")?.dataset.phase === "watching");
      assert.equal(originalRequests.length, 1, "explicit re-enable establishes fresh baselines, never retries old IDs");
      assert.equal((await peerState(simulatorOrigin)).capture_count, 1, "recovery never resends the shutter");
      await page.click('.tab[data-view="live"]');
      await capture(page, simulatorOrigin, 2);
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelectorAll('#media-auto-list [data-status="completed"]').length === 1,
        null, { timeout: 20_000 });
      await page.click("#media-auto-stop");
      assert.equal(originalRequests.length, 2);
      const records = await page.evaluate(async () => {
        const store = OpenEOSMediaTransferHistory.createHistoryStore();
        const result = await store.list(); store.close(); return result;
      });
      assert.equal(records.entries.some(row => row.outcome === "failed"), true);
      assert.equal(records.entries.some(row => row.outcome === "completed"), true);
      assert.equal(await page.evaluate(() => window.__batchFiles.filter(file => file.closed).length), 1);
    },
  });
}

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
      if (scenario.batch) await context.addInitScript(batchFilePlatform, scenario.batch);
      const page = await context.newPage();
      page.setDefaultTimeout(12_000);
      const listingRequests = [];
      const cameraWrites = [];
      const originalRequests = [];
      const infoRequests = [];
      const pageErrors = [];
      const routeCleanups = [];
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
        await scenario.run({ page, simulatorOrigin, listingRequests, cameraWrites, originalRequests, infoRequests, routeCleanups });
        assert.deepEqual(pageErrors, []);
        console.log(`PASS: synthetic PC capture review: ${scenario.name}`);
      } catch (error) {
        failures.push(scenario.name);
        console.error(`FAIL: synthetic PC capture review: ${scenario.name}`, error);
        // Capture the failed UI before cleanup can close the dialog or reset it.
        // Evidence failure never replaces the original scenario failure.
        try {
          fs.mkdirSync(RESULTS_DIR, { recursive: true });
          const label = scenario.name.replace(/[^a-zA-Z0-9-]+/g, "-");
          const preview = await page.evaluate(() => {
            const image = document.querySelector("#media-preview-image");
            return {
              recoveryGesture: window.__captureReviewGesture || [],
              dialogOpen: document.querySelector("#media-preview-dialog")?.open,
              title: document.querySelector("#media-preview-title")?.textContent,
              latest: document.querySelector("#latest-media-label")?.textContent,
              loadingHidden: document.querySelector("#media-preview-loading")?.hidden,
              unavailableHidden: document.querySelector("#media-preview-unavailable")?.hidden,
              image: image && { hidden: image.hidden, complete: image.complete,
                width: image.naturalWidth, height: image.naturalHeight, hasSource: Boolean(image.getAttribute("src")) },
              foregroundImport: {
                phase: document.querySelector("#media-auto-panel")?.dataset.phase,
                operation: document.querySelector("#media-auto-panel")?.dataset.operation,
                status: document.querySelector("#media-auto-status")?.textContent,
                reason: document.querySelector("#media-auto-import-reason")?.textContent,
                startDisabled: document.querySelector("#media-auto-start")?.disabled,
                pickerCalls: window.__batchPickerCalls,
                galleryLoadStatus: document.querySelector("#media-summary")?.dataset.loadStatus,
                thumbnails: Array.from(document.querySelectorAll("#media-list .media-thumbnail"), (thumbnail) => {
                  const image = thumbnail.querySelector("img");
                  return { id: thumbnail.dataset.mediaId, loading: thumbnail.classList.contains("loading"),
                    image: image && { complete: image.complete, width: image.naturalWidth, height: image.naturalHeight } };
                }),
              },
            };
          });
          fs.writeFileSync(path.join(RESULTS_DIR, `capture-review-failure-${label}.json`),
            JSON.stringify({ scenario: scenario.name, error: String(error), preview, pageErrors,
              listingRequests, infoRequests, cameraWrites, originalRequests }, null, 2));
          await page.screenshot({ path: path.join(RESULTS_DIR, `capture-review-failure-${label}.png`) });
        } catch (evidenceError) {
          console.error(`Failure evidence also failed: ${scenario.name}`, evidenceError);
        }
      } finally {
        const routeResults = await Promise.allSettled(routeCleanups.map((cleanup) => cleanup()));
        for (const result of routeResults) {
          if (result.status === "fulfilled") continue;
          failures.push(`${scenario.name} (route cleanup)`);
          console.error(`Capture review route cleanup failed: ${scenario.name}`, result.reason);
        }
        try {
          if (await page.locator("#media-date-dialog[open]").count()) await page.keyboard.press("Escape");
          if (await page.locator("#media-preview-dialog[open]").count()) await page.click("#media-preview-close");
          if (await page.locator("#media-details-dialog[open]").count()) await page.click("#media-details-close");
          await page.evaluate(() => window.__releaseSyntheticWrite?.());
          await page.evaluate(() => window.__releaseBatchWrite?.());
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
