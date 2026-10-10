"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");

const BRIDGE_ROOT = path.resolve(__dirname, "..");
const REPOSITORY_ROOT = path.resolve(BRIDGE_ROOT, "..");
const SIMULATOR_ROOT = path.join(REPOSITORY_ROOT, "simulator");
const RESULTS_DIR = path.join(BRIDGE_ROOT, "test-results");

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      server.close((error) => error ? reject(error) : resolve(address.port));
    });
  });
}

async function waitForServer(url, process, stderr, timeoutMillis = 20_000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    if (process.exitCode !== null) {
      throw new Error(`Test server exited early (${process.exitCode}): ${stderr()}`);
    }
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch (_) {
      // The process may still be binding its loopback socket.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Test server did not become ready: ${stderr()}`);
}

async function stopProcess(process) {
  if (!process || process.exitCode !== null) return;
  process.kill();
  await Promise.race([
    new Promise((resolve) => process.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 5_000)),
  ]);
  if (process.exitCode === null) process.kill("SIGKILL");
}

async function readSimulatorState(origin) {
  const response = await fetch(`${origin}/ccapi/test/state`);
  assert.equal(response.ok, true, `Simulator state returned HTTP ${response.status}`);
  return response.json();
}

async function waitForSimulatorState(origin, predicate, description, timeoutMillis = 10_000) {
  const deadline = Date.now() + timeoutMillis;
  let latest = null;
  while (Date.now() < deadline) {
    latest = await readSimulatorState(origin);
    if (predicate(latest)) return latest;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${description}: ${JSON.stringify(latest)}`);
}

function spawnUvicorn(python, root, module, port, environment = {}) {
  const process = spawn(
    python,
    ["-m", "uvicorn", module, "--host", "127.0.0.1", "--port", String(port), "--log-level", "warning"],
    {
      cwd: root,
      env: { ...processEnv(), ...environment },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let stderr = "";
  process.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
  return { process, stderr: () => stderr };
}

function processEnv() {
  return { ...globalThis.process.env };
}

async function verifyCapabilityResponseOwnership(browser, origin) {
  for (const reconnect of [false, true]) {
    const context = await browser.newContext({ locale: "en-US" });
    const page = await context.newPage();
    const pageErrors = [];
    const writes = [];
    const sessionReads = new Map();
    let releaseResponse;
    const responseGate = new Promise((resolve) => { releaseResponse = resolve; });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("request", (request) => {
      if (request.method() !== "GET") {
        writes.push({ method: request.method(), path: new URL(request.url()).pathname });
      }
    });
    // Use the existing real Bridge and fake USB backend. Only capability contents
    // and delivery order are controlled, as in this suite's other route fixtures.
    await page.route(/\/capabilities$/, async (route) => {
      const response = await route.fetch();
      assert.equal(response.ok(), true);
      const capabilities = await response.json();
      const sessionId = new URL(route.request().url()).pathname.split("/")[3];
      const reads = (sessionReads.get(sessionId) || 0) + 1;
      sessionReads.set(sessionId, reads);
      const firstConnection = sessionId === sessionReads.keys().next().value;
      capabilities.profile = firstConnection ? "synthetic-owner-A" : "synthetic-owner-B";
      capabilities.supported = firstConnection ? ["STILL_CAPTURE"] : [];
      capabilities.settings = [];
      if (firstConnection && reads === 2) {
        await page.evaluate(() => { window.__capabilityResponsePending = true; });
        await responseGate;
      }
      await route.fulfill({ response, json: capabilities });
    });
    try {
      await page.goto(origin, { waitUntil: "networkidle" });
      await page.waitForSelector("#connect-button:not([disabled])");
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      assert.equal(await page.locator("#shutter-button").isEnabled(), true);
      await page.evaluate(() => {
        window.__capabilityDiagnosticRenders = 0;
        new MutationObserver(() => { window.__capabilityDiagnosticRenders += 1; })
          .observe(document.querySelector("#diagnostics-output"), { childList: true });
      });
      await page.click('.tab[data-view="diagnostics"]');
      await page.waitForFunction(() => window.__capabilityResponsePending === true);
      assert.equal(await page.locator("#disconnect-button").isEnabled(), true);
      await page.click("#disconnect-button");
      await page.waitForSelector("#connection-view:not([hidden])");
      if (reconnect) {
        assert.equal(await page.locator("#connect-button").isEnabled(), true);
        await page.click("#connect-button");
        await page.waitForSelector("#control-view:not([hidden])");
        await page.waitForFunction(() => (
          JSON.parse(document.querySelector("#diagnostics-output").textContent)
            .capabilities?.profile === "synthetic-owner-B"
        ));
        assert.equal(sessionReads.size, 2, "Normal reconnect creates a replacement connection");
        assert.equal(await page.locator("#shutter-button").isDisabled(), true);
      }
      const rendersBeforeResponse = await page.evaluate(() => window.__capabilityDiagnosticRenders);
      const writesBeforeResponse = writes.slice();
      releaseResponse();
      // Require the late callback to render before checking unchanged state. A
      // pre-response assertion of B would otherwise pass on the broken product.
      await page.waitForFunction((previous) => window.__capabilityDiagnosticRenders > previous,
        rendersBeforeResponse);
      const report = JSON.parse(await page.locator("#diagnostics-output").textContent());
      assert.equal(report.capabilities?.profile || null, reconnect ? "synthetic-owner-B" : null);
      if (reconnect) {
        await page.click('.tab[data-view="live"]');
        await page.click("#photo-mode-button");
        assert.equal(await page.locator("#shutter-button").isDisabled(), true,
          "A-only capture remains disabled when current availability is rendered");
      } else {
        assert.equal(report.camera, null);
        assert.equal(await page.locator("#connection-view").isVisible(), true);
      }
      assert.deepEqual(writes, writesBeforeResponse, "Late capability delivery sends no camera writes");
      assert.equal(writes.filter(({ method }) => method === "POST").length, reconnect ? 2 : 1);
      assert.equal(writes.every(({ method, path }) => (
        (method === "POST" && path === "/v1/session") ||
        (method === "DELETE" && /^\/v1\/session\/[^/]+$/.test(path))
      )), true, "Only explicit connection lifecycle requests are permitted");
      assert.deepEqual(pageErrors, []);
      if (reconnect) {
        await page.click("#disconnect-button");
        await page.waitForSelector("#connection-view:not([hidden])");
      }
      console.log(`Capability response ownership: ${reconnect ? "reconnect" : "disconnect"} passed`);
    } finally {
      releaseResponse();
      await context.close();
    }
  }
}

async function verifyMediaDeleteResponseOwnership(browser, origin) {
  for (const rejected of [false, true]) {
    const context = await browser.newContext({ locale: "en-US" });
    const page = await context.newPage();
    const writes = [], pageErrors = [];
    let releaseResponse;
    const gate = new Promise(resolve => { releaseResponse = resolve; });
    const item = { id: "SYNTHETIC_SHARED.JPG", name: "SYNTHETIC_SHARED.JPG", kind: "image",
      sizeBytes: 1024, captureTime: null, previewAvailable: false };
    // Observe consumption of the synthetic response, then a new browser task.
    // The production api/delete continuation microtasks finish before this marker.
    await context.addInitScript(() => {
      const fetchOriginal = window.fetch;
      window.fetch = async (...args) => {
        const response = await fetchOriginal(...args);
        if (args[1]?.method === "DELETE" && String(args[0]).includes("/media/")) {
          const json = response.json.bind(response);
          response.json = async () => {
            const value = await json();
            const channel = new MessageChannel();
            channel.port1.onmessage = () => {
              window.__deleteResponseConsumed = true;
              channel.port1.close(); channel.port2.close();
            };
            channel.port2.postMessage(null);
            return value;
          };
        }
        return response;
      };
    });
    page.on("pageerror", error => pageErrors.push(error.message));
    page.on("request", request => {
      if (request.method() !== "GET") writes.push({ method: request.method(), path: new URL(request.url()).pathname });
    });
    await page.route(/\/capabilities$/, async route => {
      const response = await route.fetch();
      const capabilities = await response.json();
      capabilities.supported = ["MEDIA_BROWSER", "MEDIA_DELETE"];
      capabilities.settings = [];
      await route.fulfill({ response, json: capabilities });
    });
    await page.route(/\/v1\/session\/[^/]+\/media(?:\?.*)?$/, route => route.fulfill({ json: { items: [item] } }));
    await page.route(/\/v1\/session\/[^/]+\/media\/SYNTHETIC_SHARED.JPG$/, async route => {
      assert.equal(route.request().method(), "DELETE");
      // This synthetic delete never reaches the fake backend or a physical camera.
      await page.evaluate(() => { window.__deleteResponsePending = true; });
      await gate;
      await route.fulfill({ status: rejected ? 500 : 200,
        json: rejected ? { error: { code: "SYNTHETIC_DELETE_FAILURE", message: "Old A deletion failed" } } : {} });
    });
    try {
      await page.goto(origin, { waitUntil: "networkidle" });
      await page.waitForSelector("#connect-button:not([disabled])");
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      await page.click('.tab[data-view="media"]');
      await page.locator(".media-card .media-actions button").click();
      await page.waitForSelector("#media-details-dialog[open]");
      page.once("dialog", dialog => dialog.accept());
      await page.click("#media-details-delete");
      await page.waitForFunction(() => window.__deleteResponsePending === true);
      await page.keyboard.press("Escape");
      await page.waitForSelector("#media-details-dialog:not([open])", { state: "attached" });
      assert.equal(await page.locator("#disconnect-button").isEnabled(), true);
      await page.click("#disconnect-button");
      await page.waitForSelector("#connection-view:not([hidden])");
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      await page.click('.tab[data-view="media"]');
      await page.locator(".media-card .media-actions button").click();
      await page.waitForSelector("#media-details-dialog[open]");
      assert.equal(await page.locator("#media-details-delete").isEnabled(), true);
      const requestsBefore = writes.slice();
      const errorBefore = JSON.parse(await page.locator("#diagnostics-output").textContent()).lastError;
      releaseResponse();
      await page.waitForFunction(() => window.__deleteResponseConsumed === true);
      assert.equal(await page.locator(".media-card").count(), 1, "B's same-ID media survives A's response");
      assert.equal(await page.locator("#media-details-dialog").evaluate(dialog => dialog.open), true);
      assert.equal(await page.locator("#media-details-delete").isEnabled(), true);
      assert.deepEqual(JSON.parse(await page.locator("#diagnostics-output").textContent()).lastError, errorBefore);
      assert.deepEqual(writes, requestsBefore, "Late response never retries or deletes from B");
      assert.equal(writes.filter(x => x.method === "DELETE" && x.path.includes("/media/")).length, 1);
      assert.deepEqual(pageErrors, []);
      await page.keyboard.press("Escape");
      await page.click("#disconnect-button");
      await page.waitForSelector("#connection-view:not([hidden])");
      console.log(`Media delete response ownership: stale ${rejected ? "rejection" : "success"} passed`);
    } finally {
      releaseResponse();
      await context.close();
    }
  }
}

function syntheticDatedMedia() {
  const entries = [
    ["DATE_ONLY.JPG", "2026-10-07"],
    ["START.JPG", "2026-10-07T00:00:00-07:00"],
    ["END.JPG", "2026-10-07T23:59:59-07:00"],
    ["OFFSET_IN.JPG", "2026-10-08T01:00:00Z"],
    ["OFFSET_OUT.JPG", "2026-10-07T01:00:00Z"],
    ["VIDEO.MP4", "2026-10-07T12:00:00-07:00"],
    ["UNKNOWN.JPG", null],
    ["INVALID.JPG", "2026-02-30T12:00:00Z"],
    ["COMPACT.JPG", "20261007T120000"],
    ["ENRICH.JPG", "2026-10-07T15:00:00-07:00"],
    ...Array.from({ length: 75 }, (_, index) => [`EARLIER_${index + 1}.JPG`, "2026-10-05"]),
  ];
  return entries.map(([name, captureTime]) => ({
    id: name, name, captureTime, kind: name.endsWith(".MP4") ? "video" : "image",
    sizeBytes: 2048, previewAvailable: true,
  }));
}

async function applyDateRange(page, start, end) {
  await page.click("#media-date-button");
  await page.waitForSelector("#media-date-dialog[open]");
  await page.fill("#media-date-from", start);
  await page.fill("#media-date-to", end);
  await page.click("#media-date-apply");
  await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
}

async function verifyDateDialogLayout(page, language) {
  for (const viewport of [{ width: 390, height: 844 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport);
    // Enlarge text through the public document styling, never the app's state.
    await page.locator("html").evaluate((element) => { element.style.fontSize = "32px"; });
    await page.locator("#media-date-button").focus();
    assert.equal(await page.locator("#media-date-button").evaluate((element) => document.activeElement === element), true);
    await page.keyboard.press("Enter");
    await page.waitForSelector("#media-date-dialog[open]");
    assert.equal(await page.locator("#media-date-from").evaluate((element) => document.activeElement === element), true);
    const visited = new Set();
    const focusTrace = [];
    fs.mkdirSync(RESULTS_DIR, { recursive: true });
    const evidenceName = `desktop-media-date-${language}-${viewport.width}x${viewport.height}-text200`;
    await page.screenshot({ path: path.join(RESULTS_DIR, `${evidenceName}-focus-start.png`) });
    for (let index = 0; index <= 28; index += 1) {
      const focus = await page.evaluate(() => {
        const active = document.activeElement;
        const dialog = document.querySelector("#media-date-dialog");
        return {
          id: active?.id || "", tag: active?.tagName || null,
          inside: dialog.contains(active), documentHasFocus: document.hasFocus(),
          documentRoot: active === document.body || active === document.documentElement,
          open: dialog.open, modal: dialog.matches(":modal"),
        };
      });
      focusTrace.push({ index, ...focus });
      fs.writeFileSync(path.join(RESULTS_DIR, `${evidenceName}-focus.json`), JSON.stringify(focusTrace, null, 2));
      assert.equal(focus.open && focus.modal, true, "Keyboard traversal retains the native modal");
      // HTML sequential navigation permits the browser's own controls at the
      // document boundary. Only its unfocused root sentinel is acceptable here;
      // a background app control must fail regardless of document focus.
      assert.equal(focus.inside || (!focus.documentHasFocus && focus.documentRoot), true,
        `Keyboard focus must not enter the background app: ${JSON.stringify(focus)}`);
      if (focus.inside && focus.documentHasFocus) visited.add(focus.id);
      if (index < 28) await page.keyboard.press("Tab");
    }
    for (const id of ["media-date-from", "media-date-to", "media-date-close", "media-date-dialog-clear", "media-date-cancel", "media-date-apply"]) {
      assert.ok(visited.has(id), `${language}: ${id} is keyboard reachable`);
      const control = page.locator(`#${id}`);
      await control.scrollIntoViewIfNeeded();
      await control.click({ trial: true });
      const bounds = await control.boundingBox();
      assert.ok(bounds && bounds.x >= 0 && bounds.y >= 0 &&
        bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1,
      `${language}: ${id} fits the ${viewport.width}x${viewport.height} viewport after scrolling`);
    }
    const geometry = await page.locator("#media-date-dialog").evaluate((dialog) => ({
      width: dialog.clientWidth, scrollWidth: dialog.scrollWidth,
      clipped: [...dialog.querySelectorAll("h2, p, label, button")].filter((element) => !element.hidden)
        .filter((element) => element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1)
        .map((element) => element.id || element.tagName),
    }));
    fs.writeFileSync(path.join(RESULTS_DIR, `${evidenceName}-geometry.json`), JSON.stringify(geometry, null, 2));
    assert.ok(geometry.scrollWidth <= geometry.width + 1, `${language}: dialog has no horizontal overflow`);
    assert.deepEqual(geometry.clipped, [], `${language}: enlarged labels and actions are not clipped`);
    fs.mkdirSync(RESULTS_DIR, { recursive: true });
    await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-media-date-${language}-${viewport.width}x${viewport.height}-text200.png`) });
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
    assert.equal(await page.locator("#media-date-button").evaluate((element) => document.activeElement === element), true);
    await page.locator("html").evaluate((element) => element.style.removeProperty("font-size"));
  }
  await page.setViewportSize({ width: 1440, height: 900 });
}

async function verifyMediaDateJourneys(browser, bridgeOrigin, simulatorOrigin) {
  for (const language of ["en", "zh-TW"]) {
    const reset = await fetch(`${simulatorOrigin}/ccapi/test/reset`, { method: "POST" });
    assert.equal(reset.ok, true);
    const context = await browser.newContext({
      locale: language === "en" ? "en-US" : "zh-TW", timezoneId: "America/Los_Angeles",
      viewport: { width: 1440, height: 900 },
    });
    const page = await context.newPage();
    page.setDefaultTimeout(12_000);
    const items = syntheticDatedMedia();
    const requests = { listing: [], info: [], writes: [] };
    const errors = [];
    let failListing = false;
    let allDatesUnknown = false;
    let scenarioError = null;
    const snapshot = () => Object.fromEntries(Object.entries(requests).map(([key, value]) => [key, value.length]));
    const names = () => page.locator(".media-card .media-copy strong").allTextContents();
    const expectNames = async (expected) => assert.deepEqual(await names(), expected);
    const sameDayPhotos = ["DATE_ONLY.JPG", "START.JPG", "END.JPG", "OFFSET_IN.JPG", "COMPACT.JPG", "ENRICH.JPG"];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("request", (request) => {
      const pathname = new URL(request.url()).pathname;
      if (!pathname.startsWith("/v1/session")) return;
      if (request.method() !== "GET") requests.writes.push(pathname);
      else if (/\/media$/.test(pathname)) requests.listing.push(request.url());
      else if (/\/media\/[^/]+\/info$/.test(pathname)) requests.info.push(pathname);
    });
    await page.route(/\/v1\/session\/[^/]+\/media(?:\?.*)?$/, (route) => {
      if (failListing) return route.fulfill({ status: 503, json: { message: "Synthetic listing unavailable" } });
      const limit = Number(new URL(route.request().url()).searchParams.get("limit"));
      const listed = allDatesUnknown ? items.map((item) => ({ ...item, captureTime: null })) : items;
      return route.fulfill({ json: { items: limit > 0 ? listed.slice(0, limit) : listed } });
    });
    const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", "base64");
    await page.route(/\/media\/[^/]+\/(?:thumbnail|preview)$/, (route) => route.fulfill({ contentType: "image/png", body: png }));
    await page.route(/\/media\/[^/]+\/info$/, (route) => {
      const id = decodeURIComponent(new URL(route.request().url()).pathname.split("/").at(-2));
      const item = items.find((entry) => entry.id === id);
      assert.ok(item, "Only synthetic items may receive an explicit details read");
      if (id === "ENRICH.JPG") item.captureTime = "2026-10-09";
      return route.fulfill({ json: item });
    });
    try {
      await page.goto(bridgeOrigin, { waitUntil: "networkidle" });
      await page.click("#ccapi-mode-button");
      await page.fill("#ccapi-url-input", simulatorOrigin);
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      await page.locator("#control-view .language-select").selectOption(language);
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE");
      assert.equal(await page.locator(".media-card").count(), 60);
      assert.match(await page.locator("#media-summary").innerText(), language === "en" ? /Latest 60.*more on card/ : /最新 60.*尚有更多/);
      await page.selectOption("#media-sort-select", "camera");
      const beforeDate = snapshot();
      await applyDateRange(page, "2026-10-07", "2026-10-07");
      await expectNames([...sameDayPhotos.slice(0, 4), "VIDEO.MP4", ...sameDayPhotos.slice(4)]);
      assert.deepEqual(snapshot(), beforeDate, "Apply adds no listing, per-item info, or camera-write request");
      const summary = await page.locator("#media-date-summary").innerText();
      assert.match(summary, language === "en" ? /7 of 60 loaded.*2 loaded items have unknown dates/ : /60 項中符合 7 項.*2 項日期不明/);
      assert.match(summary, /America\/Los_Angeles/);
      assert.match(await page.locator("#media-summary").innerText(), language === "en" ? /more on card/ : /尚有更多/);

      // Inclusive boundaries, literal dates and UTC offsets agree with the visible day heading.
      await page.selectOption("#media-sort-select", "newest");
      assert.deepEqual(await page.locator(".media-date-heading").allTextContents(), [language === "en" ? "October 7, 2026" : "2026年10月7日"]);
      assert.equal(await page.locator(".media-card").filter({ hasText: "DATE_ONLY.JPG" }).locator(".media-copy span").innerText(), "IMAGE",
        "A date-only file must not acquire an invented time of day");
      await page.click('#media-filter-control [data-media-filter="video"]');
      await expectNames(["VIDEO.MP4"]);
      await page.click('#media-filter-control [data-media-filter="photo"]');
      await page.selectOption("#media-sort-select", "name");
      await expectNames(["COMPACT.JPG", "DATE_ONLY.JPG", "END.JPG", "ENRICH.JPG", "OFFSET_IN.JPG", "START.JPG"]);
      await page.selectOption("#media-sort-select", "camera");
      await expectNames(sameDayPhotos);

      for (const dismiss of ["cancel", "close", "escape"]) {
        await page.click("#media-date-button");
        await page.fill("#media-date-from", "2026-10-01");
        await page.fill("#media-date-to", "2026-10-09");
        if (dismiss === "escape") await page.keyboard.press("Escape");
        else await page.click(`#media-date-${dismiss}`);
        await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
        assert.equal(await page.locator("#media-date-button").evaluate((element) => document.activeElement === element), true);
        await expectNames(sameDayPhotos);
        await page.click("#media-date-button");
        assert.equal(await page.inputValue("#media-date-from"), "2026-10-07");
        assert.equal(await page.inputValue("#media-date-to"), "2026-10-07");
        await page.click("#media-date-cancel");
        await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
      }
      await page.click("#media-date-button");
      await page.fill("#media-date-from", "2026-10-08");
      await page.click("#media-date-apply");
      assert.match(await page.locator("#media-date-error").innerText(), language === "en" ? /on or after/ : /不能早於/);
      assert.equal(await page.locator("#media-date-dialog").evaluate((dialog) => dialog.open), true);
      await expectNames(sameDayPhotos);
      // Native date inputs normalize impossible dates to an empty value. Empty
      // required input is the real browser invalid-input path; parser edge cases
      // are covered by the strict helper tests without replacing the input type.
      await page.fill("#media-date-from", "");
      await page.click("#media-date-apply");
      assert.match(await page.locator("#media-date-error").innerText(), language === "en" ? /both dates/ : /開始與結束日期/);
      assert.equal(await page.getAttribute("#media-date-from", "aria-invalid"), "true");
      await expectNames(sameDayPhotos);
      await page.keyboard.press("Escape");
      await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);

      // Viewer navigation enumerates exactly the filtered photo sequence.
      await page.locator('.media-card [data-media-id="DATE_ONLY.JPG"]').click();
      for (let index = 0; index < sameDayPhotos.length; index += 1) {
        await page.waitForFunction((name) => document.querySelector("#media-preview-title")?.textContent === name, sameDayPhotos[index]);
        assert.match(await page.locator("#media-preview-meta").innerText(), language === "en"
          ? new RegExp(`${index + 1} of 6`) : new RegExp(`第 ${index + 1} 個，共 6 個`));
        assert.equal(await page.isDisabled("#media-preview-previous"), index === 0);
        assert.equal(await page.isDisabled("#media-preview-next"), index === sameDayPhotos.length - 1);
        if (index < sameDayPhotos.length - 1) await page.click("#media-preview-next");
      }
      await page.click("#media-preview-previous");
      await page.waitForFunction(() => document.querySelector("#media-preview-title")?.textContent === "COMPACT.JPG");
      await page.click("#media-preview-close");
      const beforeInfo = snapshot();
      await page.locator(".media-card").filter({ hasText: "ENRICH.JPG" }).locator(".media-actions button").click();
      await page.waitForFunction(() => document.querySelector("#media-details-summary")?.textContent?.includes("2026") &&
        document.querySelector("#media-details-loading")?.hidden);
      await expectNames(sameDayPhotos.slice(0, -1));
      assert.equal(await page.locator("#media-details-name").innerText(), "ENRICH.JPG",
        "Enrichment can remove the card without changing the explicitly opened item");
      assert.deepEqual(snapshot(), { ...beforeInfo, info: beforeInfo.info + 1 });
      await page.click("#media-details-close");

      await page.click('#media-scope-control [data-media-scope="all"]');
      await page.waitForFunction(() => document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE");
      assert.match(await page.locator("#media-date-summary").innerText(), language === "en" ? /5 of 85/ : /85 項中符合 5 項/);
      failListing = true;
      await page.click("#media-refresh-button");
      await page.waitForFunction(() => document.querySelector("#media-summary")?.dataset.loadStatus === "FAILED");
      const failedReads = snapshot();
      await applyDateRange(page, "2026-09-01", "2026-09-01");
      assert.equal(await page.locator(".media-card").count(), 0);
      assert.match(await page.locator("#media-date-summary").innerText(), language === "en" ? /0 of 85.*2 loaded items have unknown dates/ : /85 項中符合 0 項.*2 項日期不明/);
      assert.match(await page.locator("#media-list").innerText(), language === "en" ? /No loaded media matches/ : /沒有符合篩選/);
      assert.match(await page.locator("#media-summary").innerText(), language === "en" ? /refresh failed.*85 previous/ : /重新整理失敗.*85/);
      assert.deepEqual(snapshot(), failedReads, "Applying a zero-match range preserves the failed-refresh warning without retries");
      await verifyDateDialogLayout(page, language);

      await page.click('#media-filter-control [data-media-filter="all"]');
      await applyDateRange(page, "2026-10-05", "2026-10-09");
      await page.selectOption("#media-sort-select", "newest");
      assert.deepEqual(await page.locator(".media-date-heading").allTextContents(), language === "en"
        ? ["October 9, 2026", "October 7, 2026", "October 6, 2026", "October 5, 2026"]
        : ["2026年10月9日", "2026年10月7日", "2026年10月6日", "2026年10月5日"],
      "Mixed date-only and offset timestamps keep each displayed day in one ordered group");
      await page.selectOption("#media-sort-select", "camera");
      await page.click("#media-page-next");
      assert.match(await page.locator("#media-page-status").innerText(), /73.*83/);
      const beforeClear = snapshot();
      await page.click("#media-date-clear");
      assert.deepEqual(snapshot(), beforeClear, "Clear adds no metadata, listing or camera-write traffic");
      assert.match(await page.locator("#media-page-status").innerText(), /1.*72.*85/);
      assert.ok((await names()).includes("UNKNOWN.JPG"));
      assert.ok((await names()).includes("INVALID.JPG"));
      assert.equal(await page.locator("#media-date-summary").isHidden(), true);
      assert.equal(await page.getAttribute("#media-date-button", "aria-pressed"), "false");
      await applyDateRange(page, "2026-10-07", "2026-10-07");
      await page.click("#media-date-button");
      await page.fill("#media-date-from", "2026-01-01");
      const beforeDialogClear = snapshot();
      await page.click("#media-date-dialog-clear");
      await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
      assert.deepEqual(snapshot(), beforeDialogClear);
      assert.equal(await page.locator("#media-date-summary").isHidden(), true);

      // Disconnect and reconnect through the normal controls; no synthetic session
      // replacement can accidentally make the reset assertions pass.
      await applyDateRange(page, "2026-10-07", "2026-10-07");
      await page.click("#media-date-button");
      await page.fill("#media-date-from", "2026-01-01");
      await page.keyboard.press("Escape");
      await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
      await page.click("#disconnect-button");
      await page.waitForSelector("#connection-view:not([hidden])");
      failListing = false;
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      await page.click('.tab[data-view="media"]');
      await page.waitForFunction(() => document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE");
      assert.equal(await page.getAttribute("#media-date-button", "aria-pressed"), "false");
      assert.equal(await page.locator("#media-date-summary").isHidden(), true);
      await page.click("#media-date-button");
      assert.equal(await page.inputValue("#media-date-from"), "");
      assert.equal(await page.inputValue("#media-date-to"), "");
      assert.equal(await page.locator("#media-date-error").isHidden(), true);
      await page.click("#media-date-cancel");
      await page.waitForFunction(() => !document.querySelector("#media-date-dialog").open);
      allDatesUnknown = true;
      await page.click("#media-refresh-button");
      await page.waitForFunction(() => document.querySelector("#media-summary")?.dataset.loadStatus === "COMPLETE");
      const beforeUnknownRange = snapshot();
      const unknownTotal = await page.locator("#media-page-status").innerText();
      assert.match(unknownTotal, /85/);
      await applyDateRange(page, "2026-10-07", "2026-10-07");
      assert.equal(await page.locator(".media-card").count(), 0);
      assert.match(await page.locator("#media-date-summary").innerText(), language === "en"
        ? /0 of 85 loaded.*85 loaded items have unknown dates/ : /85 項中符合 0 項.*85 項日期不明/);
      await page.click("#media-date-clear");
      assert.equal(await page.locator(".media-card").count(), 72);
      assert.deepEqual(snapshot(), beforeUnknownRange,
        "A CCAPI list with no dates remains honest and causes no fallback info fan-out");
      assert.deepEqual(errors, []);
      console.log(`PASS: synthetic PC media date range: ${language}`);
    } catch (error) {
      scenarioError = error;
      throw error;
    } finally {
      const cleanupErrors = [];
      try {
        if (await page.locator("dialog[open]").count()) await page.keyboard.press("Escape");
        if (await page.locator("#control-view:not([hidden])").count()) {
          await page.click("#disconnect-button");
          await page.waitForSelector("#connection-view:not([hidden])");
        }
      } catch (error) { cleanupErrors.push(error); }
      try { await context.close(); } catch (error) { cleanupErrors.push(error); }
      if (cleanupErrors.length) {
        if (!scenarioError) throw new AggregateError(cleanupErrors, `Date journey cleanup failed: ${language}`);
        console.error(`Date journey cleanup also failed: ${language}`, cleanupErrors);
      }
    }
  }
}

async function run() {
  const [simulatorPort, bridgePort] = await Promise.all([freePort(), freePort()]);
  const simulatorOrigin = `http://127.0.0.1:${simulatorPort}`;
  const bridgeOrigin = `http://127.0.0.1:${bridgePort}`;
  const captureDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-ccapi-browser-test-"));
  const python = process.env.PYTHON || (process.platform === "win32" ? "python" : "python3");
  const simulator = spawnUvicorn(python, SIMULATOR_ROOT, "main:app", simulatorPort);
  let bridge = null;
  let browser = null;
  try {
    await waitForServer(`${simulatorOrigin}/health`, simulator.process, simulator.stderr);
    const reset = await fetch(`${simulatorOrigin}/ccapi/test/reset`, { method: "POST" });
    assert.equal(reset.ok, true);

    bridge = spawnUvicorn(python, BRIDGE_ROOT, "tests.browser_server:app", bridgePort, {
      OPEN_EOS_BROWSER_CAPTURE_DIR: captureDirectory,
    });
    await waitForServer(`${bridgeOrigin}/health`, bridge.process, bridge.stderr);

    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({
      locale: "en-US",
      viewport: { width: 1440, height: 900 },
    });
    const page = await context.newPage();
    const pageErrors = [];
    const boundedMediaRequests = [];
    const shutterRequests = [];
    let withdrawShutterAF = false;
    await page.route(/\/capabilities$/, async (route) => {
      const response = await route.fetch();
      const json = await response.json();
      if (withdrawShutterAF) delete json.shutterAutofocusSupported;
      await route.fulfill({ response, json });
    });
    let retryLatestMedia = false;
    let retryLatestMediaRequests = 0;
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error") pageErrors.push(message.text());
    });
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (request.method() === "GET" && url.pathname.endsWith("/media")) {
        boundedMediaRequests.push(url.searchParams.get("limit"));
      }
      if (request.method() === "POST" && url.pathname.endsWith("/capture/still")) {
        shutterRequests.push(request.postDataJSON());
      }
    });
    await page.route(/\/media\?limit=8$/, async (route) => {
      if (!retryLatestMedia) {
        await route.continue();
        return;
      }
      retryLatestMediaRequests += 1;
      if (retryLatestMediaRequests !== 1) {
        await route.continue();
        return;
      }
      const response = await route.fetch();
      const payload = await response.json();
      payload.items = [];
      await route.fulfill({ response, json: payload });
    });
    await page.addInitScript(() => {
      const revokeObjectUrl = URL.revokeObjectURL.bind(URL);
      window.__objectUrlRevocationViolations = [];
      URL.revokeObjectURL = (url) => {
        const references = Array.from(document.querySelectorAll("[src], [href]"))
          .filter((element) => [element.getAttribute("src"), element.getAttribute("href")].includes(url))
          .map((element) => `${element.tagName.toLowerCase()}#${element.id || "unknown"}`);
        if (references.length) {
          window.__objectUrlRevocationViolations.push({ url, references });
        }
        revokeObjectUrl(url);
      };
    });

    await page.goto(bridgeOrigin, { waitUntil: "networkidle" });
    await page.click("#ccapi-mode-button");
    await page.fill("#ccapi-url-input", simulatorOrigin);
    await page.click("#connect-button");
    await page.waitForSelector("#control-view:not([hidden])");
    assert.match(await page.locator("#camera-name").innerText(), /R6 Mark III/);
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2418 shots"));
    await page.waitForFunction(() => {
      const button = document.querySelector("#latest-media-button");
      return button && !button.hidden && button.querySelector("#latest-media-label")?.textContent === "SIM_0002.PNG";
    });
    assert.ok(boundedMediaRequests.includes("8"), `expected bounded media request, got ${boundedMediaRequests}`);
    assert.equal(boundedMediaRequests.includes(null), false, "connect shortcut must not enumerate the full card");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.canonical.event_poll_count >= 1 && state.canonical.event_active_requests === 1,
      "production CCAPI event long polling to become active",
    );

    const restrictedTemperature = await fetch(
      `${simulatorOrigin}/ccapi/test/temperature?status=disablerelease`,
      { method: "POST" },
    );
    assert.equal(restrictedTemperature.ok, true);
    await page.waitForFunction(() => {
      const warning = document.querySelector("#temperature-warning");
      const shutter = document.querySelector("#shutter-button");
      return warning && !warning.hidden && shutter?.disabled;
    });
    assert.match(await page.locator("#temperature-warning-text").innerText(), /Shutter unavailable/i);

    const normalTemperature = await fetch(
      `${simulatorOrigin}/ccapi/test/temperature?status=normal`,
      { method: "POST" },
    );
    assert.equal(normalTemperature.ok, true);
    await page.waitForFunction(() => {
      const warning = document.querySelector("#temperature-warning");
      const shutter = document.querySelector("#shutter-button");
      return warning?.hidden && shutter && !shutter.disabled;
    });

    await page.click('.exposure-control[data-setting-key="iso"]');
    await page.waitForSelector("#setting-dialog[open]");
    await page.getByRole("button", { name: "1600", exact: true }).click();
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.exposure.iso === "1600",
      "PC ISO control to reach Canon-style CCAPI",
    );
    const deliveredBeforeExternalSetting = await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.canonical.event_delivery_count >= 1,
      "the camera setting event to reach the PC event loop",
    );
    const externalSetting = await fetch(`${simulatorOrigin}/ccapi/exposure`, {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ iso: "3200" }),
    });
    assert.equal(externalSetting.ok, true);
    await page.waitForFunction(() =>
      document.querySelector('.exposure-control[data-setting-key="iso"] strong')?.textContent?.trim() === "3200",
    );
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.canonical.event_delivery_count > deliveredBeforeExternalSetting.canonical.event_delivery_count,
      "external camera ISO event to refresh the PC UI without a manual refresh",
    );

    await page.waitForSelector(".settings-command button:not([disabled])");
    await page.click(".settings-command button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.clock_sync_count === 1,
      "camera clock synchronization",
    );

    await page.click("#autofocus-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.canonical.af_start_count === 1 && state.canonical.af_stop_count === 1,
      "balanced Canon AF start and stop",
    );
    await page.click("#half-press-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.half_press_count === 1 && state.shutter_release_count === 1 && !state.half_pressed,
      "balanced Canon half-press and release",
    );

    await page.waitForSelector("#shutter-af-control:not([hidden])");
    assert.equal(await page.isChecked("#shutter-af-toggle"), true);
    const beforeAFChoice = await readSimulatorState(simulatorOrigin);
    await page.uncheck("#shutter-af-toggle");
    const afterAFChoice = await readSimulatorState(simulatorOrigin);
    assert.equal(afterAFChoice.capture_count, beforeAFChoice.capture_count, "AF choice must not trigger a shutter");
    assert.equal(afterAFChoice.canonical.af_start_count, beforeAFChoice.canonical.af_start_count);
    assert.match(await page.locator("#shutter-af-description").innerText(), /does not ask.*autofocus/i);
    await page.locator("#control-view .language-select").selectOption("zh-TW");
    assert.match(await page.locator("#shutter-af-description").innerText(), /不要求自動對焦/);
    assert.equal(await page.isChecked("#shutter-af-toggle"), false);
    await page.locator("#control-view .language-select").selectOption("en");
    withdrawShutterAF = true;
    await page.click("#refresh-button");
    await page.waitForFunction(() => document.querySelector("#operation-state")?.textContent === "Ready");
    assert.equal(await page.isChecked("#shutter-af-toggle"), false, "Lost capability cannot silently change intent to AF on");
    assert.equal(await page.isDisabled("#shutter-button"), true);
    withdrawShutterAF = false;
    await page.click("#refresh-button");
    await page.waitForFunction(() => !document.querySelector("#shutter-button")?.disabled);
    let releaseCapture;
    const captureGate = new Promise((resolve) => { releaseCapture = resolve; });
    await page.route(/\/capture\/still$/, async (route) => { await captureGate; await route.continue(); });
    await page.click("#shutter-button");
    assert.equal(await page.isDisabled("#shutter-af-toggle"), true);
    await page.evaluate(() => {
      const toggle = document.querySelector("#shutter-af-toggle");
      toggle.checked = true;
      toggle.dispatchEvent(new Event("change", { bubbles: true }));
      document.querySelector("#shutter-button").click();
    });
    assert.equal(await page.isChecked("#shutter-af-toggle"), false);
    releaseCapture();
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.capture_count === 1 && state.media_ids.includes("SIM_0003.JPG"),
      "still capture and camera media creation",
    );
    await page.waitForFunction(() => {
      const button = document.querySelector("#latest-media-button");
      return button?.querySelector("#latest-media-label")?.textContent === "SIM_0003.JPG" && !button.disabled;
    });
    assert.deepEqual((await readSimulatorState(simulatorOrigin)).canonical.shutter_af_requests, [false]);
    assert.deepEqual(shutterRequests, [{ af: false }]);
    await page.unroute(/\/capture\/still$/);
    await page.click("#latest-media-button");
    await page.waitForFunction(() => {
      const dialog = document.querySelector("#media-preview-dialog");
      return dialog?.open && document.querySelector("#media-preview-title")?.textContent === "SIM_0003.JPG";
    });
    await page.waitForFunction(() => {
      const image = document.querySelector("#media-preview-image");
      return image && !image.hidden && image.complete && image.naturalWidth > 0 && image.naturalHeight > 0;
    });
    await page.click("#media-preview-close");

    retryLatestMedia = true;
    await page.route(/\/thumbnail$/, (route) => route.fulfill({
      status: 200,
      contentType: "text/plain",
      body: "not-an-image",
    }));
    await page.check("#shutter-af-toggle");
    await page.click("#shutter-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.capture_count === 2 && state.media_ids.includes("SIM_0004.JPG"),
      "second still capture with thumbnail enrichment unavailable",
    );
    await page.waitForFunction(() => {
      const button = document.querySelector("#latest-media-button");
      return button?.querySelector("#latest-media-label")?.textContent === "SIM_0004.JPG" && !button.disabled;
    });
    assert.deepEqual((await readSimulatorState(simulatorOrigin)).canonical.shutter_af_requests, [false, true]);
    assert.ok(retryLatestMediaRequests >= 2, "capture review should retry until the camera reports a new item");
    assert.equal(await page.locator("#operation-state").innerText(), "Photo captured");
    await page.unroute(/\/thumbnail$/);
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2416 shots"));

    await page.click("#live-toggle-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.canonical.live_view_active &&
        state.canonical.live_view_start_count === 1 &&
        state.canonical.live_view_size_rejections === 1,
      "Live View size fallback and successful Canon start",
    );
    await page.waitForSelector("#live-image:not([hidden])");
    await page.waitForFunction(() => {
      const image = document.querySelector("#live-image");
      return image.complete && image.naturalWidth > 0 && image.naturalHeight > 0;
    });

    const imageBounds = await page.locator("#live-image").boundingBox();
    assert.ok(imageBounds && imageBounds.width > 0 && imageBounds.height > 0);
    await page.mouse.click(
      imageBounds.x + imageBounds.width * 0.65,
      imageBounds.y + imageBounds.height * 0.35,
    );
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.focus.count === 1 &&
        state.canonical.focus_position?.x >= 3900 && state.canonical.focus_position?.x <= 4100 &&
        state.canonical.focus_position?.y >= 1500 && state.canonical.focus_position?.y <= 1700,
      "geometry-backed Canon Tap AF",
    );

    await page.selectOption("#tap-action-select", "whiteBalance");
    await page.mouse.click(
      imageBounds.x + imageBounds.width * 0.35,
      imageBounds.y + imageBounds.height * 0.65,
    );
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.click_white_balance.count === 1 &&
        state.canonical.click_wb_position?.x >= 2100 && state.canonical.click_wb_position?.x <= 2300 &&
        state.canonical.click_wb_position?.y >= 2700 && state.canonical.click_wb_position?.y <= 2900,
      "geometry-backed Canon Click White Balance",
    );

    await page.click('#focus-step-control button[data-step="LARGE"]');
    await page.click("#focus-near-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.focus_drive.count === 1 &&
        state.focus_drive.direction === "near" && state.focus_drive.step === "large",
      "Canon drivefocus near3 command",
    );

    await page.click("#video-mode-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.movie_mode === "on" && state.movie_mode_update_count === 1,
      "Canon movie mode on",
    );
    const focusBracketingKeys = [
      "focusbracketing",
      "focusbracketingnumberofshots",
      "focusbracketingfocusincrement",
      "focusbracketingexposuresmoothing",
    ];
    await page.waitForFunction((keys) => keys.every(
      (key) => !document.querySelector(`#advanced-settings [data-setting-key="${key}"]`),
    ), focusBracketingKeys);
    for (const key of focusBracketingKeys) {
      assert.equal(
        await page.locator(`#advanced-settings [data-setting-key="${key}"]`).count(),
        0,
        `${key} must be hidden in Video mode`,
      );
    }
    const movieSettingKeys = ["moviequality", "highframerate", "moviecropping", "movieformat"];
    await page.waitForFunction((keys) => keys.every(
      (key) => document.querySelector(`#advanced-settings [data-setting-key="${key}"]`),
    ), movieSettingKeys);
    assert.deepEqual(
      await page.locator('#advanced-settings select[data-setting-key="moviequality"] option').allInnerTexts(),
      ["3840x2160 / 59.94p / IPB", "1920x1080 / 29.97p / IPB"],
    );
    await page.selectOption(
      '#advanced-settings select[data-setting-key="moviequality"]',
      "1920x1080_2997_ipb_standard",
    );
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.movie_quality.value === "1920x1080_2997_ipb_standard" &&
        state.movie_quality.update_count === 1,
      "Canon movie quality string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="highframerate"]', "enable");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.high_frame_rate.value === "enable" && state.high_frame_rate.update_count === 1,
      "Canon high frame rate string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="moviecropping"]', "enable");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.movie_cropping.value === "enable" && state.movie_cropping.update_count === 1,
      "Canon movie cropping string write",
    );
    assert.deepEqual(
      await page.locator('#advanced-settings select[data-setting-key="movieformat"] option').allInnerTexts(),
      ["RAW", "MP4"],
    );
    await page.selectOption('#advanced-settings select[data-setting-key="movieformat"]', "raw");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.movie_format.value === "raw" && state.movie_format.update_count === 1,
      "Canon movie format string write",
    );
    const soundLevel = page.locator(
      '#advanced-settings input[type="range"][data-setting-key="soundrecordinglevel"]',
    );
    await soundLevel.waitFor({ state: "visible" });
    await page.waitForFunction(() => {
      const input = document.querySelector(
        '#advanced-settings input[type="range"][data-setting-key="soundrecordinglevel"]',
      );
      return input && !input.disabled;
    });
    await soundLevel.evaluate((input) => {
      input.value = "48";
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.sound_recording_level.value === 48 &&
        state.sound_recording_level.update_count === 1,
      "Canon sound recording level integer write",
    );
    const sourceSoundLevel = page.locator(
      '#advanced-settings input[type="range"][data-setting-key="soundrecordinglevelintmic"]',
    );
    await sourceSoundLevel.waitFor({ state: "visible" });
    await page.waitForFunction(() => {
      const input = document.querySelector(
        '#advanced-settings input[type="range"][data-setting-key="soundrecordinglevelintmic"]',
      );
      return input && !input.disabled;
    });
    await sourceSoundLevel.evaluate((input) => {
      input.value = "41";
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.sound_recording_level_intmic.value === 41 &&
        state.sound_recording_level_intmic.update_count === 1,
      "Canon internal microphone level integer write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="windfilterintmic"]', "disable");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.wind_filter_intmic.value === "disable" &&
        state.wind_filter_intmic.update_count === 1,
      "Canon internal microphone wind filter write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="windfilter"]', "enable");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.wind_filter.value === "enable" && state.wind_filter.update_count === 1,
      "Canon wind filter string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="attenuator"]', "manual");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.attenuator.value === "manual" && state.attenuator.update_count === 1,
      "Canon attenuator string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="soundrecording"]', "auto");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.sound_recording.value === "auto" && state.sound_recording.update_count === 1,
      "Canon sound recording mode string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="cardselectionmovie"]', "card1");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.movie_card_selection === "card1" && state.card_selection_update_count === 1,
      "Canon movie card selection",
    );
    const movieIndexInput = page.locator('input[aria-label="Movie index"]');
    await movieIndexInput.waitFor({ state: "visible" });
    await movieIndexInput.fill("B_");
    await page.waitForTimeout(500);
    assert.equal(await movieIndexInput.inputValue(), "B_");
    const movieIndexRequest = page.waitForRequest((request) =>
      request.method() === "PUT" && request.url().includes("/file-naming/"));
    await movieIndexInput.locator("xpath=..").getByRole("button", { name: "Apply" }).click();
    const movieIndexWrite = await movieIndexRequest;
    assert.match(movieIndexWrite.url(), /\/file-naming\/movie-index$/);
    assert.deepEqual(movieIndexWrite.postDataJSON(), { value: "B_" });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.file_naming.movieIndex === "B_" && state.file_naming_update_count === 1,
      "Canon movie filename index",
    );
    assert.deepEqual(
      await page.locator('#advanced-settings select[data-setting-key="beep"] option').allInnerTexts(),
      ["Enable", "Disable", "Touch sounds off"],
    );
    await page.selectOption('#advanced-settings select[data-setting-key="beep"]', "disabletouch");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.beep.value === "disabletouch" && state.beep.update_count === 1,
      "Canon beep setting",
    );
    assert.deepEqual(
      await page.locator('#advanced-settings select[data-setting-key="displayoff"] option').allInnerTexts(),
      ["10 seconds", "20 seconds", "30 seconds", "1 minute", "2 minutes", "3 minutes"],
    );
    await page.selectOption('#advanced-settings select[data-setting-key="displayoff"]', "120");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.display_off.value === "120" && state.display_off.update_count === 1,
      "Canon auto display off setting",
    );
    assert.deepEqual(
      await page.locator('#advanced-settings select[data-setting-key="autopoweroff"] option').allInnerTexts(),
      ["30 seconds", "1 minute", "2 minutes", "3 minutes", "5 minutes", "10 minutes", "Disable"],
    );
    await page.selectOption('#advanced-settings select[data-setting-key="autopoweroff"]', "300");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.auto_power_off.value === "300" && state.auto_power_off.update_count === 1,
      "Canon auto power off timed setting",
    );
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2:00:00 remaining"));
    await page.click("#shutter-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.recording && state.record_start_count === 1,
      "Canon recording start",
    );
    await page.click("#shutter-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => !state.recording && state.record_stop_count === 1,
      "Canon recording stop",
    );
    await page.click("#photo-mode-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.movie_mode === "off" && state.movie_mode_update_count === 2,
      "Canon movie mode off",
    );
    const soundRecordingKeys = [
      "soundrecording", "soundrecordinglevel", "windfilter", "attenuator",
      "soundrecordingmodeintmic", "soundrecordinglevelintmic", "windfilterintmic",
    ];
    await page.waitForFunction(({ hiddenKeys, visibleKeys }) => {
      const settings = document.querySelector("#advanced-settings");
      const photoMode = document.querySelector("#photo-mode-button");
      return photoMode?.classList.contains("active") &&
        hiddenKeys.every((key) => !settings?.querySelector(`[data-setting-key="${key}"]`)) &&
        visibleKeys.every((key) => settings?.querySelector(`[data-setting-key="${key}"]`));
    }, { hiddenKeys: [...soundRecordingKeys, ...movieSettingKeys], visibleKeys: focusBracketingKeys });
    for (const key of [...soundRecordingKeys, ...movieSettingKeys]) {
      assert.equal(
        await page.locator(`#advanced-settings [data-setting-key="${key}"]`).count(),
        0,
        `${key} must be hidden in Photo mode`,
      );
    }
    const stillPrefixInput = page.locator('input[aria-label="User setting 1"]');
    await stillPrefixInput.waitFor({ state: "visible" });
    await stillPrefixInput.fill("R6M_");
    const stillPrefixRequest = page.waitForRequest((request) =>
      request.method() === "PUT" && request.url().includes("/file-naming/"));
    await stillPrefixInput.locator("xpath=..").getByRole("button", { name: "Apply" }).click();
    const stillPrefixWrite = await stillPrefixRequest;
    assert.match(stillPrefixWrite.url(), /\/file-naming\/still-user-setting-1$/);
    assert.deepEqual(stillPrefixWrite.postDataJSON(), { value: "R6M_" });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.file_naming.stillUserSetting1 === "R6M_" && state.file_naming_update_count === 2,
      "Canon still filename prefix",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="focusbracketing"]', "enable");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.focus_bracketing.value === "enable" &&
        state.focus_bracketing.update_count === 1,
      "Canon focus bracketing string write",
    );
    const shotsRangeSelector =
      '#advanced-settings input[type="range"][data-setting-key="focusbracketingnumberofshots"]';
    await page.waitForFunction((selector) => {
      const input = document.querySelector(selector);
      return input && !input.disabled;
    }, shotsRangeSelector);
    await page.locator(shotsRangeSelector).evaluate((input) => {
      input.value = "248";
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.focus_bracketing_shots.value === 250 &&
        state.focus_bracketing_shots.update_count === 1,
      "Canon focus bracketing shot count integer write",
    );
    const incrementRangeSelector =
      '#advanced-settings input[type="range"][data-setting-key="focusbracketingfocusincrement"]';
    await page.waitForFunction((selector) => {
      const input = document.querySelector(selector);
      return input && !input.disabled;
    }, incrementRangeSelector);
    await page.locator(incrementRangeSelector).evaluate((input) => {
      input.value = "6";
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.focus_bracketing_increment.value === 7 &&
        state.focus_bracketing_increment.update_count === 1,
      "Canon focus bracketing increment integer write",
    );
    const smoothingSelector =
      '#advanced-settings select[data-setting-key="focusbracketingexposuresmoothing"]';
    await page.waitForFunction((selector) => {
      const input = document.querySelector(selector);
      return input && !input.disabled;
    }, smoothingSelector);
    await page.selectOption(
      smoothingSelector,
      "enable",
    );
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.focus_bracketing_exposure_smoothing.value === "enable" &&
        state.focus_bracketing_exposure_smoothing.update_count === 1,
      "Canon focus bracketing exposure smoothing string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="cardselectionstillimage"]', "card2");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.still_card_selection === "card2" && state.card_selection_update_count === 2,
      "Canon still-image card selection",
    );
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2416 shots"));

    await page.selectOption('#advanced-settings select[data-setting-key="shootingmode"]', "Bulb");
    await waitForSimulatorState(simulatorOrigin, (state) => state.mode === "Bulb", "Canon Bulb mode write");
    await page.waitForFunction(() => {
      const shutter = document.querySelector("#shutter-button");
      return shutter?.classList.contains("bulb") && !shutter.disabled;
    });
    assert.equal((await readSimulatorState(simulatorOrigin)).capture_count, 2);
    await page.click("#shutter-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.bulb_exposure_active && state.bulb_start_count === 1,
      "Canon Bulb full press",
    );
    await page.click("#shutter-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => !state.bulb_exposure_active && state.bulb_stop_count === 1,
      "Canon Bulb release",
    );

    const bulkMedia = Array.from({ length: 145 }, (_, index) => ({
      id: `BULK_${index + 1}.JPG`,
      name: `BULK_${index + 1}.JPG`,
      kind: "image",
      sizeBytes: 2048 + index,
      captureTime: null,
      previewAvailable: false,
    }));
    const mediaListRoute = /\/v1\/session\/[^/]+\/media(?:\?.*)?$/;
    const bulkThumbnailRoute = /\/v1\/session\/[^/]+\/media\/BULK_[^/]+\/thumbnail(?:\?.*)?$/;
    const bulkThumbnail = Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
      "base64",
    );
    let releaseBulkMediaResponse = () => {};
    const bulkMediaResponseHeld = new Promise((resolve) => {
      releaseBulkMediaResponse = resolve;
    });
    let holdBulkMediaResponse = true;
    await page.route(mediaListRoute, async (route) => {
      if (holdBulkMediaResponse) {
        holdBulkMediaResponse = false;
        await bulkMediaResponseHeld;
      }
      const requestURL = new URL(route.request().url());
      const limit = Number(requestURL.searchParams.get("limit"));
      await route.fulfill({
        json: { items: Number.isSafeInteger(limit) && limit > 0 ? bulkMedia.slice(0, limit) : bulkMedia },
      });
    });
    await page.route(bulkThumbnailRoute, (route) => route.fulfill({
      status: 200,
      contentType: "image/png",
      body: bulkThumbnail,
    }));
    await page.click('.tab[data-view="media"]');
    await page.waitForSelector("#media-panel:not([hidden])");
    await page.waitForFunction(() => {
      const summary = document.querySelector("#media-summary");
      return summary?.dataset.loadStatus === "LOADING" && summary.textContent.includes("Loading");
    });
    releaseBulkMediaResponse();
    assert.equal(await page.locator("#media-sort-select").inputValue(), "newest");
    await page.waitForFunction(() => document.querySelectorAll(".media-card").length === 60);
    assert.equal(await page.locator("#media-summary").innerText(), "Latest 60 media item(s) - more on card");
    assert.equal(await page.locator('#media-scope-control button[aria-pressed="true"]').innerText(), "Recent");
    await page.click('#media-scope-control button[data-media-scope="all"]');
    await page.waitForFunction(() => document.querySelectorAll(".media-card").length === 72);
    assert.equal(await page.locator("#media-summary").getAttribute("data-load-status"), "COMPLETE");
    assert.equal(await page.locator("#media-summary").innerText(), "145 media item(s)");
    assert.equal(await page.locator("#media-page-status").innerText(), "1-72 of 145");
    await page.click("#media-page-next");
    assert.equal(await page.locator(".media-card").count(), 72);
    assert.equal(await page.locator("#media-page-status").innerText(), "73-144 of 145");
    await page.click("#media-page-next");
    assert.equal(await page.locator(".media-card").count(), 1);
    assert.equal(await page.locator("#media-page-status").innerText(), "145-145 of 145");
    await page.selectOption("#media-sort-select", "name");
    assert.equal(await page.locator("#media-page-status").innerText(), "1-72 of 145");
    assert.match(await page.locator(".media-card").first().innerText(), /BULK_1\.JPG/);
    fs.mkdirSync(RESULTS_DIR, { recursive: true });
    await page.locator("#media-panel").screenshot({
      path: path.join(RESULTS_DIR, "desktop-large-media-library.png"),
    });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    assert.equal(await page.locator("#media-scope-control").isVisible(), true);
    const narrowScopeBounds = await page.locator("#media-scope-control").boundingBox();
    const narrowFilterBounds = await page.locator("#media-filter-control").boundingBox();
    assert.ok(narrowScopeBounds && narrowScopeBounds.x >= 0 && narrowScopeBounds.x + narrowScopeBounds.width <= 390);
    assert.ok(narrowFilterBounds && narrowScopeBounds.y + narrowScopeBounds.height <= narrowFilterBounds.y);
    await page.locator("#media-panel").screenshot({
      path: path.join(RESULTS_DIR, "narrow-large-media-library.png"),
    });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.unroute(mediaListRoute);
    await page.unroute(bulkThumbnailRoute);
    const selectedMediaInfoRoute = /\/v1\/session\/[^/]+\/media\/[^/]+\/info(?:\?.*)?$/;
    let selectedMediaSize = 4096;
    let selectedMediaInfoReads = 0;
    await page.route(selectedMediaInfoRoute, async (route) => {
      selectedMediaInfoReads += 1;
      const response = await route.fetch();
      const item = await response.json();
      await route.fulfill({
        response,
        json: { ...item, sizeBytes: selectedMediaSize, contentType: "image/jpeg", widthPixels: 6000, heightPixels: 4000 },
      });
    });
    await page.click("#media-refresh-button");
    const capturedMedia = page.locator(".media-card").filter({ hasText: "SIM_0003.JPG" });
    await capturedMedia.waitFor({ state: "visible" });
    assert.equal(await capturedMedia.locator(".media-size").innerText(), "");
    assert.equal(selectedMediaInfoReads, 0, "listing must not fetch size metadata automatically");
    assert.equal(await page.locator("#media-filter-control button.active").innerText(), "All");
    await page.click('#media-filter-control button[data-media-filter="video"]');
    assert.equal(await capturedMedia.isVisible(), false);
    await page.click('#media-filter-control button[data-media-filter="photo"]');
    await capturedMedia.waitFor({ state: "visible" });
    await page.selectOption("#media-sort-select", "name");
    await capturedMedia.locator("button.media-thumbnail").click();
    await page.waitForSelector("#media-preview-dialog[open] #media-preview-image:not([hidden])");
    await page.screenshot({ path: path.join(RESULTS_DIR, "desktop-media-viewer.png") });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.screenshot({ path: path.join(RESULTS_DIR, "narrow-media-viewer.png") });
    await page.setViewportSize({ width: 1440, height: 900 });
    assert.match(await page.locator("#media-preview-meta").innerText(), /\d+ of \d+/);
    assert.doesNotMatch(await page.locator("#media-preview-meta").innerText(), /(?:^| · )0 B(?:$| · )/);
    assert.equal(selectedMediaInfoReads, 0, "opening preview must not fetch size metadata");
    assert.equal(await page.locator("#media-preview-download").isVisible(), true);
    assert.equal(await page.locator("#media-preview-details").isVisible(), true);
    await page.locator("#media-preview-image").dblclick();
    await page.waitForSelector("#media-preview-reset-zoom:not([hidden])");
    assert.match(await page.locator("#media-preview-image").getAttribute("style"), /scale\(2\.5\)/);
    await page.click("#media-preview-reset-zoom");
    assert.equal(await page.locator("#media-preview-reset-zoom").isHidden(), true);
    await page.click("#media-preview-details");
    await page.waitForSelector("#media-details-dialog[open]");
    assert.equal(await page.locator("#media-details-name").innerText(), "SIM_0003.JPG");
    await page.waitForFunction(() => document.querySelector("#media-details-summary")?.textContent?.includes("6000 x 4000"));
    assert.match(await page.locator("#media-details-summary").innerText(), /image\/jpeg/);
    await page.waitForSelector("#media-details-loading[hidden]", { state: "attached" });
    assert.match(await page.locator("#media-details-summary").innerText(), /4\.0 KB/);
    assert.equal(await capturedMedia.locator(".media-size").innerText(), "6000 x 4000 · 4.0 KB");
    assert.equal(selectedMediaInfoReads, 1);
    await page.click("#media-details-close");
    await capturedMedia.locator("button.media-thumbnail").click();
    await page.waitForSelector("#media-preview-dialog[open] #media-preview-image:not([hidden])");
    assert.match(await page.locator("#media-preview-meta").innerText(), /4\.0 KB/);
    selectedMediaSize = 0;
    await page.click("#media-preview-details");
    await page.waitForSelector("#media-details-dialog[open] #media-details-loading[hidden]", { state: "attached" });
    assert.equal(selectedMediaInfoReads, 2);
    assert.doesNotMatch(await page.locator("#media-details-summary").innerText(), /(?:0 B|4\.0 KB)/);
    assert.equal(await capturedMedia.locator(".media-size").innerText(), "6000 x 4000");
    await page.screenshot({ path: path.join(RESULTS_DIR, "desktop-unknown-media-size-details.png") });
    await page.click("#media-details-close");
    await capturedMedia.locator("button.media-thumbnail").click();
    await page.waitForSelector("#media-preview-dialog[open] #media-preview-image:not([hidden])");
    assert.doesNotMatch(await page.locator("#media-preview-meta").innerText(), /(?:0 B|4\.0 KB)/);
    assert.match(await page.locator("#media-preview-meta").innerText(), /6000 x 4000/);
    await page.screenshot({ path: path.join(RESULTS_DIR, "desktop-unknown-media-size-preview.png") });
    await page.click("#media-preview-details");
    await page.waitForSelector("#media-details-dialog[open] #media-details-loading[hidden]", { state: "attached" });
    assert.equal(selectedMediaInfoReads, 3, "only explicit details requests may fetch metadata");
    console.log("CCAPI unknown media size -> known metadata -> unknown metadata render journey passed");
    await page.unroute(selectedMediaInfoRoute);
    page.once("dialog", (dialog) => dialog.accept());
    await page.click("#media-details-delete");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => !state.media_ids.includes("SIM_0003.JPG"),
      "confirmed exact Canon media deletion",
    );
    const deliveredBeforeExternalCapture = (await readSimulatorState(simulatorOrigin))
      .canonical.event_delivery_count;
    const externalCapture = await fetch(`${simulatorOrigin}/ccapi/capture/still`, { method: "POST" });
    assert.equal(externalCapture.ok, true);
    await page.locator(".media-card").filter({ hasText: "SIM_0005.PNG" }).waitFor({ state: "visible" });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.canonical.event_delivery_count > deliveredBeforeExternalCapture,
      "external camera contents event to refresh the open media view",
    );

    await page.click('.tab[data-view="live"]');
    await page.waitForSelector('[data-camera-command="sensor-cleaning"]:not([disabled])');
    page.once("dialog", (dialog) => dialog.accept());
    await page.locator('[data-camera-command="sensor-cleaning"]').first().click();
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.sensor_cleaning.count === 1 &&
        !state.sensor_cleaning.auto_power_off &&
        state.canonical.live_view_active &&
        state.canonical.live_view_start_count === 2 &&
        state.canonical.live_view_stop_count === 1,
      "CCAPI sensor cleaning and Live View restoration",
    );

    await page.click('.tab[data-view="diagnostics"]');
    await page.waitForSelector("#diagnostics-panel:not([hidden])");
    const diagnostics = await page.locator("#diagnostics-output").innerText();
    assert.match(diagnostics, /CCAPI_NETWORK|ccapi/i);
    assert.match(diagnostics, /EVENT_POLLING/);
    assert.match(diagnostics, /"discoveryTrace"/);
    assert.match(diagnostics, /"endpoint": "GET \/ccapi"/);
    fs.mkdirSync(RESULTS_DIR, { recursive: true });
    await page.screenshot({ path: path.join(RESULTS_DIR, "desktop-ccapi-e2e.png"), fullPage: true });

    await page.click('.tab[data-view="live"]');
    await page.waitForSelector('[data-camera-command="sleep"]:not([disabled])');
    page.once("dialog", (dialog) => dialog.accept());
    await page.click('[data-camera-command="sleep"]');
    await page.waitForSelector("#connection-view:not([hidden])");
    const finalState = await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.camera_sleep_count === 1 &&
        !state.canonical.live_view_active &&
        state.canonical.live_view_stop_count === 2 &&
        state.canonical.event_delete_count >= 1 &&
        state.canonical.event_active_requests === 0,
      "CCAPI camera sleep to stop Live View, event polling, and disconnect",
    );
    assert.equal(finalState.canonical.af_start_count, finalState.canonical.af_stop_count);
    assert.equal(finalState.half_press_count, finalState.shutter_release_count);
    await page.waitForTimeout(1100);
    assert.deepEqual(await page.evaluate(() => window.__objectUrlRevocationViolations), []);
    assert.deepEqual(pageErrors, []);
    await context.close();
    await verifyCapabilityResponseOwnership(browser, bridgeOrigin);
    await verifyMediaDeleteResponseOwnership(browser, bridgeOrigin);
    await verifyMediaDateJourneys(browser, bridgeOrigin, simulatorOrigin);
  } finally {
    if (browser) await browser.close();
    await stopProcess(bridge?.process);
    await stopProcess(simulator.process);
    fs.rmSync(captureDirectory, { recursive: true, force: true });
  }
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
