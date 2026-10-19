"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { createHash } = require("node:crypto");
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
    if (await page.locator("#mode-apply-button").isVisible()) await page.click("#mode-apply-button");
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
  for (const responseCase of ["confirmed", "rejected", "server-error", "non-authoritative-success"]) {
    const context = await browser.newContext({ locale: "en-US" });
    const page = await context.newPage();
    const writes = [], pageErrors = [];
    let releaseResponse;
    const gate = new Promise(resolve => { releaseResponse = resolve; });
    const item = { id: "gphoto2:SYNTHETIC_SHARED.JPG", name: "SYNTHETIC_SHARED.JPG", kind: "image",
      sizeBytes: 1024, captureTime: null, previewAvailable: false };
    // Deliver this one retired synthetic DELETE despite AbortSignal, then mark
    // actual EOF consumption. The owner fence, not network cancellation, must
    // protect B. Real status/headers/body remain observable by production Fetch.
    await context.addInitScript(() => {
      const fetchOriginal = window.fetch;
      window.fetch = async (input, options) => {
        const staleDelete = options?.method === "DELETE" && String(input).includes("/media/");
        const response = await fetchOriginal(input, staleDelete ? { ...options, signal: undefined } : options);
        if (staleDelete) {
          const consumed = () => {
            const channel = new MessageChannel();
            channel.port1.onmessage = () => {
              window.__deleteResponseConsumed = true;
              channel.port1.close(); channel.port2.close();
            };
            channel.port2.postMessage(null);
          };
          if (response.body) {
            const getReader = response.body.getReader.bind(response.body);
            response.body.getReader = (...args) => {
              const reader = getReader(...args), read = reader.read.bind(reader);
              reader.read = async (...readArgs) => {
                const result = await read(...readArgs);
                if (result.done) consumed();
                return result;
              };
              return reader;
            };
          } else consumed();
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
    await page.route(/\/v1\/session\/[^/]+\/media\/gphoto2%3ASYNTHETIC_SHARED\.JPG$/, async route => {
      assert.equal(route.request().method(), "DELETE");
      // This synthetic delete never reaches the fake backend or a physical camera.
      await page.evaluate(() => { window.__deleteResponsePending = true; });
      await gate;
      if (responseCase === "confirmed") {
        await route.fulfill({ status: 204, body: "" });
      } else {
        const status = { rejected: 403, "server-error": 500, "non-authoritative-success": 200 }[responseCase];
        await route.fulfill({ status,
          json: responseCase === "non-authoritative-success" ? {} :
            { error: { code: "SYNTHETIC_DELETE_FAILURE", message: "Old A deletion failed" } } });
      }
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
      console.log(`Media delete response ownership: stale ${responseCase} passed`);
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
    ...(["DATE_ONLY.JPG", "START.JPG", "END.JPG"].includes(name)
      ? { folderId: name === "END.JPG" ? "synthetic:B" : "synthetic:A", folderLabel: "card1/DCIM/SAME" }
      : name.startsWith("EARLIER_") ? { folderId: `synthetic:folder-${Number(name.match(/\d+/)[0]) % 40}`,
        folderLabel: `long-camera-path/${"folder-name-".repeat(12)}${Number(name.match(/\d+/)[0]) % 40}` } : {}),
    ...({ "DATE_ONLY.JPG": 0, "START.JPG": 5, "END.JPG": 3 }[name] === undefined ? {}
      : { rating: { "DATE_ONLY.JPG": 0, "START.JPG": 5, "END.JPG": 3 }[name] }),
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

async function verifyLoadedFolderJourney(page, language, snapshot) {
  const names = () => page.locator(".media-card .media-copy strong").allTextContents();
  const before = snapshot();
  await page.click("#media-folder-button");
  const choices = await page.locator("#media-folder-filter-select option").evaluateAll(options =>
    options.map(option => ({ value: option.value, label: option.textContent })));
  const sameLabel = choices.filter(option => option.label.startsWith("card1/DCIM/SAME"));
  assert.equal(sameLabel.length, 2);
  assert.notEqual(sameLabel[0].label, sameLabel[1].label);
  assert.ok(choices.length >= 42, "Long loaded folder list remains available");
  await page.selectOption("#media-folder-filter-select", sameLabel[0].value);
  await page.click("#media-folder-apply");
  assert.deepEqual(await names(), ["DATE_ONLY.JPG", "START.JPG"]);
  await page.click("#media-rating-button");
  await page.selectOption("#media-rating-filter-select", "5");
  await page.click("#media-rating-apply");
  await applyDateRange(page, "2026-10-07", "2026-10-07");
  assert.deepEqual(await names(), ["START.JPG"], "Folder/date/rating intersect");
  await page.click("#media-folder-clear");
  assert.deepEqual(await names(), ["START.JPG"], "Clearing folder retains rating and date");
  await page.click("#media-rating-clear");
  await page.click("#media-date-clear");
  await page.click("#media-folder-button");
  await page.selectOption("#media-folder-filter-select", "1");
  await page.click("#media-folder-apply");
  assert.equal((await names()).length, 7, "Unknown provenance is explicit");
  await page.click("#media-folder-clear");
  assert.equal((await names()).length, 60);
  assert.deepEqual(snapshot(), before, "Folder operations add no listing/info/write requests");

  for (const viewport of [{ width: 390, height: 844 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport);
    await page.locator("html").evaluate(element => { element.style.fontSize = "32px"; });
    await page.locator("#media-folder-button").focus(); await page.keyboard.press("Enter");
    await page.waitForSelector("#media-folder-dialog[open]");
    assert.equal(await page.locator("#media-folder-filter-select").evaluate(element => document.activeElement === element), true);
    const visited = new Set();
    for (let step = 0; step < 16; step += 1) {
      await page.keyboard.press("Tab");
      const focus = await page.evaluate(() => ({ id: document.activeElement?.id,
        background: document.activeElement && document.activeElement !== document.body &&
          !document.querySelector("#media-folder-dialog").contains(document.activeElement) }));
      assert.equal(Boolean(focus.background), false); visited.add(focus.id);
    }
    for (const id of ["media-folder-filter-select", "media-folder-close", "media-folder-dialog-clear", "media-folder-cancel", "media-folder-apply"]) {
      assert.equal(visited.has(id), true, `Keyboard reaches ${id}`);
      const control = page.locator(`#${id}`); await control.scrollIntoViewIfNeeded(); await control.click({ trial: true });
      const bounds = await control.boundingBox();
      assert.ok(bounds && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= viewport.width + 1 &&
        bounds.y + bounds.height <= viewport.height + 1, `${id} fits after scrolling`);
    }
    await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-media-folder-${language}-${viewport.width}x${viewport.height}-text200.png`) });
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.querySelector("#media-folder-dialog").open);
    assert.equal(await page.locator("#media-folder-button").evaluate(element => document.activeElement === element), true);
    await page.locator("html").evaluate(element => element.style.removeProperty("font-size"));
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  assert.equal((await names()).length, 60, "Escape preserves current folder filter");
}

async function verifyLoadedRatingJourney(page, language, snapshot) {
  const apply = async (value) => {
    await page.click("#media-rating-button");
    await page.waitForSelector("#media-rating-dialog[open]");
    await page.selectOption("#media-rating-filter-select", value);
    await page.click("#media-rating-apply");
    await page.waitForFunction(() => !document.querySelector("#media-rating-dialog").open);
  };
  const names = () => page.locator(".media-card .media-copy strong").allTextContents();
  const before = snapshot();
  await apply("unrated");
  assert.deepEqual(await names(), ["DATE_ONLY.JPG"]);
  await apply("unknown");
  assert.equal((await names()).length, 57);
  assert.equal((await names()).includes("DATE_ONLY.JPG"), false);
  await apply("3-plus");
  assert.deepEqual(await names(), ["START.JPG", "END.JPG"]);
  await applyDateRange(page, "2026-10-07", "2026-10-07");
  assert.deepEqual(await names(), ["START.JPG", "END.JPG"]);
  await page.click("#media-date-clear");
  await page.selectOption("#media-sort-select", "rating-highest");
  assert.deepEqual(await names(), ["START.JPG", "END.JPG"]);
  assert.equal(await page.locator(".media-date-heading").count(), 0);
  await page.click("#media-rating-clear");
  assert.deepEqual((await names()).slice(0, 3), ["START.JPG", "END.JPG", "DATE_ONLY.JPG"]);
  assert.equal(await page.locator(".media-date-heading").count(), 0);
  await page.selectOption("#media-sort-select", "camera");
  assert.deepEqual(snapshot(), before, "Rating operations add no listing/info/write requests");

  for (const viewport of [{ width: 390, height: 844 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport);
    await page.locator("html").evaluate(element => { element.style.fontSize = "32px"; });
    await page.locator("#media-rating-button").focus();
    await page.keyboard.press("Enter");
    await page.waitForSelector("#media-rating-dialog[open]");
    assert.equal(await page.locator("#media-rating-filter-select").evaluate(element => document.activeElement === element), true);
    const visited = new Set();
    for (let step = 0; step < 16; step += 1) {
      await page.keyboard.press("Tab");
      const focus = await page.evaluate(() => ({
        id: document.activeElement?.id,
        background: document.activeElement && document.activeElement !== document.body &&
          !document.querySelector("#media-rating-dialog").contains(document.activeElement),
      }));
      assert.equal(Boolean(focus.background), false, "Native dialog must not focus background controls");
      visited.add(focus.id);
    }
    for (const id of ["media-rating-filter-select", "media-rating-close", "media-rating-dialog-clear", "media-rating-cancel", "media-rating-apply"]) {
      assert.equal(visited.has(id), true, `Keyboard reaches ${id}`);
      const control = page.locator(`#${id}`);
      await control.scrollIntoViewIfNeeded();
      await control.click({ trial: true });
      const bounds = await control.boundingBox();
      assert.ok(bounds && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= viewport.width + 1 &&
        bounds.y + bounds.height <= viewport.height + 1, `${id} fits after scrolling`);
    }
    await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-media-rating-${language}-${viewport.width}x${viewport.height}-text200.png`) });
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.querySelector("#media-rating-dialog").open);
    assert.equal(await page.locator("#media-rating-button").evaluate(element => document.activeElement === element), true);
    await page.locator("html").evaluate(element => element.style.removeProperty("font-size"));
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  assert.equal(await page.locator(".media-card").count(), 60, "Escape does not change filters");
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
      await verifyLoadedRatingJourney(page, language, snapshot);
      await verifyLoadedFolderJourney(page, language, snapshot);
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
      await page.locator('.media-card button.media-card-preview[data-media-id="DATE_ONLY.JPG"]').click();
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
      assert.equal(await page.locator("#media-summary").innerText(), language === "en"
        ? "Media refresh failed - keeping 85 item(s)" : "媒體重新整理失敗 - 保留 85 個");
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

async function verifyRecordingReviewJourneys(browser, bridgeOrigin, simulatorOrigin) {
  const videoBytes = fs.readFileSync(path.join(
    REPOSITORY_ROOT, "ios", "OpenEOSControl", "Tests", "Fixtures", "valid-h264-baseline.mp4",
  ));
  const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
  const videoHash = sha256(videoBytes);
  assert.equal(videoBytes.length, 1813);
  assert.equal(videoHash, "2a8036931d1d15c7c9d1e7d4412e6796dc0faa8517871cdc64c2800f8a776627");
  const oldVideo = "EARLIER_RECORDING.MP4";
  const newVideo = "NEWLY_VISIBLE_RECORDING.MP4";
  const firstPhoto = "POST_STOP_PHOTO.JPG";
  const frontPhoto = "NEWER_VISIBLE_PHOTO.JPG";
  const uploadFixture = async (name, bytes, contentType) => {
    const response = await fetch(`${simulatorOrigin}/ccapi/media?filename=${encodeURIComponent(name)}`, {
      method: "POST",
      headers: { "Content-Type": contentType, "Content-Length": String(bytes.length) },
      body: bytes,
    });
    assert.equal(response.status, 201, `Synthetic fixture upload failed: ${name}`);
    const item = await response.json();
    assert.equal(item.id, name);
    assert.equal(item.name, name);
    assert.equal(item.kind, contentType === "video/mp4" ? "video" : "image");
    assert.equal(item.size_bytes, bytes.length);
  };

  const scenarios = ["en", "zh-TW"].flatMap(language =>
    ["real-ccapi", "explicit-fresh-event-fixture"].map(observationProvider => ({language, observationProvider})));
  for (const {language, observationProvider} of scenarios) {
    const reset = await fetch(`${simulatorOrigin}/ccapi/test/reset`, { method: "POST" });
    assert.equal(reset.ok, true);
    const photoResponse = await fetch(`${simulatorOrigin}/ccapi/ver100/contents/card1/100CANON/SIM_0002.PNG`);
    assert.equal(photoResponse.ok, true);
    assert.match(photoResponse.headers.get("content-type"), /^image\/jpeg/);
    const photoBytes = Buffer.from(await photoResponse.arrayBuffer());
    // REC/STOP in the existing simulator changes status only. Inject actual media
    // through its existing upload API; do not claim STOP created these files.
    await uploadFixture(oldVideo, videoBytes, "video/mp4");
    const context = await browser.newContext({
      locale: language === "en" ? "en-US" : "zh-TW",
      viewport: { width: 1440, height: 900 },
      acceptDownloads: true,
    });
    const page = await context.newPage();
    if (observationProvider === "explicit-fresh-event-fixture") {
      // Hypothetical supported authoritative event provider. The real CCAPI
      // outcome is fetched and checked first; only this clearly labelled fixture
      // injects fresh observation. Never derive it from CameraStatus cache.
      await page.route("**/v1/session/*/recording/*/outcome", async route => {
        const response = await route.fetch();
        assert.equal(response.status(), 200);
        const body = await response.json();
        assert.equal(body.responseEvidence, "response-received");
        assert.equal(body.observation.observationEvidence, "unavailable");
        // A separate current test-peer read supplies this synthetic provider's
        // observation. It is neither the requested command nor Bridge's cache.
        // /ccapi/test/state is fixture administration, never a production reader.
        const peer = await readSimulatorState(simulatorOrigin);
        assert.equal(typeof peer.recording, "boolean");
        body.observation = {version:1, engine:"ccapi", observationEvidence:"observed",
          recording:peer.recording, source:"ccapi-event"};
        await route.fulfill({status:200, contentType:"application/json", body:JSON.stringify(body)});
      });
    }
    page.setDefaultTimeout(15_000);
    const requests = [];
    const errors = [];
    let scenarioError = null;
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (!url.pathname.startsWith("/v1/session")) return;
      requests.push({ method: request.method(), pathname: url.pathname, limit: url.searchParams.get("limit") });
    });
    const notReady = language === "en"
      ? "No new video was observed in the completed checks. Check again without sending another REC command."
      : "已完成的查找尚未觀察到新影片。可重新查找，不會再次送出錄影命令。";
    const found = language === "en" ? "A newly visible video was found" : "已找到新可見影片";
    const waitForNotReady = () => page.waitForFunction((text) => {
      const retry = document.querySelector("#latest-media-retry");
      return document.querySelector("#latest-media-review-status")?.textContent === text &&
        retry && !retry.hidden && !retry.disabled;
    }, notReady);
    try {
      await page.goto(bridgeOrigin, { waitUntil: "networkidle" });
      await page.click("#ccapi-mode-button");
      await page.fill("#ccapi-url-input", simulatorOrigin);
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      await page.locator("#control-view .language-select").selectOption(language);
      await page.waitForFunction((name) => document.querySelector("#latest-media-label")?.textContent === name, oldVideo);
      await page.click("#video-mode-button");
    if (await page.locator("#mode-apply-button").isVisible()) await page.click("#mode-apply-button");
      await waitForSimulatorState(simulatorOrigin, (state) => state.movie_mode === "on", "recording journey movie mode");
      const startResponse = page.waitForResponse(response => response.url().endsWith("/recording/start/outcome"));
      await page.click("#shutter-button");
      assert.equal((await startResponse).status(), 200);
      await waitForSimulatorState(simulatorOrigin,
        (state) => state.recording && state.record_start_count === 1, "recording journey start");
      await page.waitForFunction(() => !document.querySelector("#shutter-button").disabled);
      const beforeStop = requests.length;
      const stopResponse = page.waitForResponse(response => response.url().endsWith("/recording/stop/outcome"));
      await page.click("#shutter-button");
      const stopResult = await stopResponse;
      assert.equal(stopResult.status(), 200);
      const stopOutcome = await stopResult.json();
      assert.equal(stopOutcome.responseEvidence, "response-received");
      await waitForSimulatorState(simulatorOrigin,
        (state) => !state.recording && state.record_stop_count === 1, "recording journey peer-applied stop");
      if (observationProvider === "real-ccapi") {
        const responseOnly = language === "en"
          ? "Recording response received; current camera state unavailable. No video was selected."
          : "已收到錄影命令回應，目前相機狀態未知，未自動挑選影片。";
        assert.equal(stopOutcome.observation.observationEvidence, "unavailable");
        assert.equal(stopOutcome.observation.recording, null);
        await page.waitForFunction(text => document.querySelector("#operation-state")?.textContent === text, responseOnly);
        assert.equal(await page.locator("#latest-media-label").innerText(), oldVideo);
        assert.equal(await page.locator("#latest-media-retry").isHidden(), true);
        assert.equal(requests.slice(beforeStop).some(request => request.pathname.endsWith("/media")), false,
          "A real CCAPI Stop receipt must not start automatic stopped-video selection");
        await uploadFixture(newVideo, videoBytes, "video/mp4");
        const observationResponse = page.waitForResponse(response => response.url().endsWith("/recording/observation"));
        await page.locator("#recording-lifecycle-history button").first().click();
        const observed = await (await observationResponse).json();
        assert.equal(observed.observationEvidence, "unavailable"); assert.equal(observed.recording, null);
        await page.waitForFunction(() => !document.querySelector("#shutter-button").disabled);
        assert.equal(await page.locator("#latest-media-label").innerText(), oldVideo,
          "A newly uploaded clip cannot manufacture evidence that recording stopped");
        assert.equal(requests.slice(beforeStop).some(request => request.pathname.endsWith("/media")), false);
        assert.deepEqual(requests.filter(request => /\/recording\/(?:start|stop)\/outcome$/.test(request.pathname))
          .map(request => request.pathname.split("/").at(-2)), ["start", "stop"]);
        assert.deepEqual(errors, []);
        fs.mkdirSync(RESULTS_DIR, {recursive:true});
        await page.screenshot({path:path.join(RESULTS_DIR, `desktop-recording-response-only-${language}.png`)});
        console.log(`PASS: real CCAPI receipt without fresh observation does not auto-select video: ${language}`);
        continue;
      }
      assert.equal(stopOutcome.observation.observationEvidence, "observed");
      assert.equal(stopOutcome.observation.recording, false);
      await waitForNotReady();
      assert.equal(await page.locator("#latest-media-label").innerText(), oldVideo,
        "A known old video cannot satisfy the stop review");
      const stopRequests = requests.slice(beforeStop);
      const stopIndex = stopRequests.findIndex((request) => request.pathname.endsWith("/recording/stop/outcome"));
      const listIndex = stopRequests.findIndex((request) => request.pathname.endsWith("/media"));
      assert.ok(stopIndex >= 0 && listIndex > stopIndex, "STOP must precede the review's bounded listing reads");
      assert.ok(stopRequests.filter((request) => request.pathname.endsWith("/media"))
        .every((request) => request.method === "GET" && request.limit === "8"));
      const stopped = await readSimulatorState(simulatorOrigin);
      assert.equal(stopped.media_ids.includes(newVideo), false, "The fixture does not manufacture a recording output");
      assert.equal(stopped.capture_count, 0);
      fs.mkdirSync(RESULTS_DIR, { recursive: true });
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-recording-review-not-ready-${language}.png`) });

      // A new photo alone must leave video review pending, without another REC.
      await uploadFixture(firstPhoto, photoBytes, "image/jpeg");
      const beforePhotoRetry = requests.length;
      await page.click("#latest-media-retry");
      await waitForNotReady();
      assert.equal(await page.locator("#latest-media-label").innerText(), oldVideo);
      assert.ok(requests.slice(beforePhotoRetry).length > 0);
      assert.ok(requests.slice(beforePhotoRetry).every((request) => request.method === "GET"),
        "Photo-only retry may read media but must not send a camera mutation");

      await uploadFixture(newVideo, videoBytes, "video/mp4");
      await uploadFixture(frontPhoto, photoBytes, "image/jpeg");
      const newContents = await fetch(`${simulatorOrigin}/ccapi/ver100/contents`);
      assert.equal(newContents.ok, true);
      const listedPaths = (await newContents.json()).path;
      assert.ok(listedPaths[0].endsWith(frontPhoto) && listedPaths[1].endsWith(newVideo),
        "The camera order deliberately places a newly visible photo before the new video");
      const beforeVideoRetry = requests.length;
      await page.click("#latest-media-retry");
      await page.waitForFunction(({ name, status }) =>
        document.querySelector("#latest-media-label")?.textContent === name &&
        document.querySelector("#latest-media-review-status")?.textContent.includes(status) &&
        document.querySelector("#latest-media-retry")?.hidden,
      { name: newVideo, status: found });
      assert.ok(requests.slice(beforeVideoRetry).every((request) => request.method === "GET"),
        "Finding the new video is read-only, even with a newer photo in front");
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-recording-review-available-${language}.png`) });
      await page.click("#latest-media-button");
      await page.waitForFunction((name) => {
        const video = document.querySelector("#media-preview-video");
        return document.querySelector("#media-preview-dialog")?.open &&
          document.querySelector("#media-preview-title")?.textContent === name &&
          video && !video.hidden && video.readyState >= 2 && video.videoWidth === 160 &&
          video.videoHeight === 90 && !video.error;
      }, newVideo);
      assert.equal(await page.locator("#media-preview-image").isHidden(), true);
      assert.equal(await page.locator("#media-preview-unavailable").isHidden(), true);
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-recording-review-video-${language}.png`) });

      // Canon listings omit size. Use the normal explicit Details read to learn
      // the small file size before choosing the normal browser-download path.
      // No picker override, synthetic writable, playback response, or App state is substituted.
      const [infoResponse] = await Promise.all([
        page.waitForResponse((response) => response.request().method() === "GET" &&
          /\/v1\/session\/[^/]+\/media\/[^/]+\/info$/.test(new URL(response.url()).pathname)),
        page.click("#media-preview-details"),
      ]);
      assert.equal(infoResponse.ok(), true);
      const videoInfo = await infoResponse.json();
      assert.equal(videoInfo.name, newVideo);
      assert.equal(videoInfo.sizeBytes, videoBytes.length);
      await page.waitForSelector("#media-details-dialog[open] #media-details-loading[hidden]", { state: "attached" });
      assert.match(await page.locator("#media-details-summary").innerText(), /video\/mp4/);
      const beforeDownload = requests.length;
      const [download] = await Promise.all([
        page.waitForEvent("download"),
        page.click("#media-details-download"),
      ]);
      assert.equal(await download.failure(), null);
      assert.equal(download.suggestedFilename(), newVideo);
      const downloadedBytes = fs.readFileSync(await download.path());
      assert.deepEqual(downloadedBytes, videoBytes, "The browser must receive the original MP4, not a thumbnail or display image");
      assert.equal(sha256(downloadedBytes), videoHash);
      assert.ok(requests.slice(beforeDownload).some((request) => request.method === "GET" &&
        /\/media\/[^/]+$/.test(request.pathname)), "Download must use the real original-media endpoint");
      const delivered = await readSimulatorState(simulatorOrigin);
      assert.equal(delivered.record_start_count, 1);
      assert.equal(delivered.record_stop_count, 1);
      assert.equal(delivered.capture_count, 0);
      assert.deepEqual(delivered.canonical.shutter_af_requests, []);
      assert.deepEqual(requests.filter((request) => /\/recording\/(?:start|stop)\/outcome$/.test(request.pathname))
        .map((request) => request.pathname.split("/").at(-2)), ["start", "stop"]);
      assert.equal(requests.some((request) => request.method !== "GET" && request.pathname.includes("/capture/")), false);
      assert.deepEqual(errors, []);
      await page.screenshot({ path: path.join(RESULTS_DIR, `desktop-recording-review-original-${language}.png`) });
      console.log(`PASS: explicit fresh-event fixture admits read-only video review and exact original download: ${language}`);
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
        if (!scenarioError) throw new AggregateError(cleanupErrors, `Recording journey cleanup failed: ${language}`);
        console.error(`Recording journey cleanup also failed: ${language}`, cleanupErrors);
      }
    }
  }
}

async function verifyConnectionRecoveryJourneys(browser, origin, simulatorOrigin) {
  for (const language of ["en", "zh-TW"]) {
    const context = await browser.newContext({ locale: language === "en" ? "en-US" : "zh-TW",
      viewport: { width: 1100, height: 850 } });
    const page = await context.newPage();
    page.setDefaultTimeout(12_000);
    const pageErrors = [], writes = [], scanGates = new Map();
    let enginesAvailable = false, scans = 0;
    const text = language === "en" ? {
      unavailable: /No USB camera engine/, empty: /Scan finished with no cameras/, scanFailed: /Scan failed/,
      token: /Bridge rejected its token/, cancelled: /Scan cancelled/, invalid: /Enter a camera origin/,
      cameraAuth: /camera rejected CCAPI authentication/, missing: /discovery endpoints were not found/,
      unknown: /exact cause is unknown/, generic: /without a verified recovery reason/, tls: /TLS connection/,
      bridgeNetwork: /could not reach the Bridge/,
    } : {
      unavailable: /沒有可用的 USB 相機引擎/, empty: /掃描已完成，但未找到相機/, scanFailed: /掃描失敗/,
      token: /Bridge 拒絕了 token/, cancelled: /已取消掃描/, invalid: /請填入相機原始位址/,
      cameraAuth: /相機拒絕了 CCAPI 驗證/, missing: /找不到相機的 CCAPI 探索端點/,
      unknown: /確切原因不明/, generic: /目前沒有可確認的原因/, tls: /TLS 連線/,
      bridgeNetwork: /無法連上 Bridge/,
    };
    await context.addInitScript(() => {
      // Deliberately deliver retired scan responses even after AbortSignal fires.
      // The production owner check, not the browser's network cancellation, must
      // protect the replacement state. A task marker proves the body was consumed.
      const original = window.fetch;
      window.__scanBodiesConsumed = 0;
      window.fetch = async (input, options) => {
        const scan = new URL(String(input), location.href).pathname === "/v1/cameras";
        const response = await original(input, scan ? { ...options, signal: undefined } : options);
        if (scan) {
          const json = response.json.bind(response);
          response.json = async () => {
            try { return await json(); }
            finally { setTimeout(() => { window.__scanBodiesConsumed += 1; }, 0); }
          };
        }
        return response;
      };
    });
    page.on("pageerror", error => pageErrors.push(error.message));
    page.on("request", request => {
      if (request.method() !== "GET") writes.push({ method: request.method(), path: new URL(request.url()).pathname });
    });
    await page.route("**/health", route => route.fulfill({ json: {
      authRequired: true, engines: { libgphoto2: { available: false },
        edsdk: { available: enginesAvailable, version: "SYNTHETIC EDSDK" }, ccapi: { available: true } },
    } }));
    const camera = id => ({ id, model: `SYNTHETIC ${id}`, port: "USB", engine: "edsdk" });
    await page.route("**/v1/cameras", async route => {
      const attempt = ++scans;
      let gate = null;
      if ([4, 6].includes(attempt)) {
        let resolve;
        gate = new Promise(done => { resolve = done; });
        scanGates.set(attempt, resolve);
      }
      await page.evaluate(count => { window.__scanRequests = count; }, attempt);
      if (gate) await gate;
      if ([2, 6].includes(attempt)) {
        await route.fulfill({ status: 401, json: { error: { code: "SYNTHETIC", message: "Synthetic" } } });
      } else {
        await route.fulfill({ json: { cameras: attempt === 1 ? [] : [camera(attempt === 4 ? "OLD" : "NEW")] } });
      }
    });
    try {
      await page.goto(origin, { waitUntil: "networkidle" });
      await page.locator("#connection-view .language-select").selectOption(language);
      assert.match(await page.locator("#connection-status").innerText(), text.unavailable);
      assert.equal(scans, 0);
      enginesAvailable = true;
      await page.click("#health-retry-button");
      await page.waitForFunction(() => document.querySelector("#engine-state").textContent.includes("SYNTHETIC EDSDK"));
      assert.equal(scans, 0, "Check Bridge never silently retries a scan");
      await page.click("#scan-button");
      await page.waitForFunction(() => window.__scanBodiesConsumed === 1);
      assert.match(await page.locator("#connection-status").innerText(), text.empty);
      assert.equal(await page.locator("#connect-button").isDisabled(), true);
      await page.click("#scan-button");
      await page.waitForFunction(() => window.__scanBodiesConsumed === 2);
      assert.match(await page.locator("#connection-status").innerText(), text.scanFailed);
      assert.match(await page.locator("#connection-error").innerText(), text.token);
      await page.click("#connection-fix-button");
      assert.equal(await page.locator("#token-input").evaluate(input => input === document.activeElement), true);
      await page.fill("#token-input", "SYNTHETIC-TOKEN");
      await page.click("#scan-button");
      await page.waitForSelector("#connect-button:not([disabled])");
      assert.equal(await page.locator("#camera-select").inputValue(), "NEW");
      await page.click("#scan-button");
      await page.waitForSelector("#scan-cancel-button:not([hidden])");
      await page.waitForFunction(() => window.__scanRequests === 4);
      await page.click("#ccapi-mode-button");
      await page.click("#usb-mode-button");
      assert.equal(scans, 4, "Switching modes requires an explicit scan retry");
      await page.click("#scan-button");
      await page.waitForSelector("#connect-button:not([disabled])");
      const consumedBeforeOld = await page.evaluate(() => window.__scanBodiesConsumed);
      scanGates.get(4)();
      await page.waitForFunction(before => window.__scanBodiesConsumed > before, consumedBeforeOld);
      assert.equal(await page.locator("#camera-select").inputValue(), "NEW");
      assert.equal(await page.locator("#connection-error").isHidden(), true);
      await page.click("#scan-button");
      await page.waitForSelector("#scan-cancel-button:not([hidden])");
      await page.waitForFunction(() => window.__scanRequests === 6);
      await page.click("#scan-cancel-button");
      assert.match(await page.locator("#connection-status").innerText(), text.cancelled);
      const consumedBeforeCancel = await page.evaluate(() => window.__scanBodiesConsumed);
      scanGates.get(6)();
      await page.waitForFunction(before => window.__scanBodiesConsumed > before, consumedBeforeCancel);
      assert.match(await page.locator("#connection-status").innerText(), text.cancelled);
      assert.equal(await page.locator("#connection-error").isHidden(), true);
      assert.deepEqual(writes, [], "Setup, scans, cancellation and guidance send no camera writes");

      await page.click("#ccapi-mode-button");
      await page.fill("#ccapi-url-input", `${simulatorOrigin}/ccapi`);
      assert.match(await page.locator("#ccapi-url-validation").innerText(), text.invalid);
      assert.equal(await page.locator("#ccapi-url-input").getAttribute("aria-invalid"), "true");
      assert.equal(await page.locator("#connect-button").isDisabled(), true);
      await page.fill("#ccapi-url-input", simulatorOrigin);
      assert.equal(await page.locator("#ccapi-url-validation").isHidden(), true);
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      assert.equal(writes.filter(write => write.method === "POST").length, 1);
      await page.click("#disconnect-button");
      await page.waitForSelector("#connection-view:not([hidden])");
      assert.equal(writes.every(write => (write.method === "POST" && write.path === "/v1/session") ||
        (write.method === "DELETE" && /^\/v1\/session\/[^/]+(?:\/events)?$/.test(write.path))), true,
      "Recovery and connection lifecycle send no shutter, focus, Live View or setting commands");
      assert.deepEqual(pageErrors, []);
      console.log(`PC connection setup, scan ownership and recovery journey passed: ${language}`);
    } finally {
      for (const release of scanGates.values()) release();
      await context.close();
    }

    // Each error owns a fresh synthetic Bridge fixture. An unknown creation
    // must never be followed by a retry in that same owner. Disposing this test
    // context does not claim that reloading a real Bridge closes its camera.
    const failures = [
      { status: 502, reason: "AUTHENTICATION_REJECTED", expected: text.cameraAuth },
      { status: 502, reason: "ENDPOINT_NOT_FOUND", expected: text.missing },
      { status: 502, reason: { malformed: true }, expected: text.unknown },
      { status: 401, reason: "TLS_ERROR", expected: text.token },
      { status: 503, code: "UNAUTHORIZED", reason: "AUTHENTICATION_REJECTED", expected: text.generic, unknown: true },
      { abort: true, expected: text.bridgeNetwork, unknown: true },
      { status: 502, reason: "TLS_ERROR", expected: text.tls },
    ];
    for (const [index, failure] of failures.entries()) {
      const failureContext = await browser.newContext({ locale: language === "en" ? "en-US" : "zh-TW",
        viewport: { width: 1100, height: 850 } });
      const failurePage = await failureContext.newPage();
      failurePage.setDefaultTimeout(12_000);
      const failureWrites = [], failureErrors = [];
      failurePage.on("pageerror", error => failureErrors.push(error.message));
      failurePage.on("request", request => {
        if (request.method() !== "GET") failureWrites.push({ method: request.method(), path: new URL(request.url()).pathname });
      });
      await failurePage.route("**/health", route => route.fulfill({ json: {
        authRequired: true, engines: { ccapi: { available: true } },
      } }));
      await failurePage.route("**/v1/session", route => {
        assert.equal(route.request().method(), "POST");
        if (failure.abort) return route.abort("failed");
        return route.fulfill({ status: failure.status, json: { error: {
          code: failure.code || "CCAPI_DISCOVERY_FAILED", engine: "ccapi",
          connectionReason: failure.reason, message: "401 Unauthorized: synthetic body cannot establish authentication",
        } } });
      });
      try {
        await failurePage.goto(origin, { waitUntil: "networkidle" });
        await failurePage.locator("#connection-view .language-select").selectOption(language);
        await failurePage.click("#ccapi-mode-button");
        await failurePage.fill("#ccapi-url-input", simulatorOrigin);
        await failurePage.click("#connect-button");
        await failurePage.waitForSelector("#connection-error:not([hidden])");
        assert.match(await failurePage.locator("#connection-error").innerText(), failure.expected);
        assert.doesNotMatch(await failurePage.locator("#connection-error").innerText(), /synthetic body/);
        if (failure.unknown) {
          const cleanup = language === "en" ? /Previous connection cleanup is unconfirmed/ : /前次連線清理尚未確認/;
          await failurePage.waitForFunction(expected => document.querySelector("#connection-cleanup-notice").textContent.includes(expected),
            language === "en" ? "Previous connection cleanup is unconfirmed" : "前次連線清理尚未確認");
          assert.match(await failurePage.locator("#connection-cleanup-notice").innerText(), cleanup);
          assert.equal(await failurePage.locator("#connect-button").isDisabled(), true);
          await failurePage.fill("#token-input", "SYNTHETIC-REPLACEMENT-TOKEN");
          assert.equal(await failurePage.locator("#connect-button").isDisabled(), true,
            "Editing credentials cannot acknowledge an unknown previous session");
        } else {
          await failurePage.waitForSelector("#connect-button:not([disabled])");
        }
        if (index === 0) {
          await failurePage.click("#connection-fix-button");
          assert.equal(await failurePage.locator("#camera-credentials").getAttribute("open"), "");
          assert.equal(await failurePage.locator("#ccapi-username-input").evaluate(input => input === document.activeElement), true);
        }
        assert.deepEqual(failureWrites, [{ method: "POST", path: "/v1/session" }],
          "Each explicit Connect sends one request; uncertainty never silently retries or sends a camera command");
        assert.deepEqual(failureErrors, []);
      } finally {
        await failurePage.unrouteAll({ behavior: "wait" });
        await failureContext.close();
      }
    }
    console.log(`PC connection rejection and unknown-session admission passed: ${language}`);
  }
}

async function verifyHeldAutofocusAndFocusFrames(browser, origin) {
  // Real production DOM and application handlers; the camera contract alone is
  // supplied by loopback route fixtures. This is not physical-camera evidence.
  for (const advertised of [false, true]) {
    const context = await browser.newContext({ locale: "en-US", viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const errors = [], commands = [];
    let stopAttempts = 0, focusReads = 0, focusAspectRatio = 4 / 3;
    const jpegVector = JSON.parse(fs.readFileSync(path.join(REPOSITORY_ROOT,
      "contracts/jpeg-original/v1/jpeg-original-vectors.json"), "utf8")).byteVectors.find(row => row.id === "rgb-baseline");
    const jpeg = Buffer.from(jpegVector.base64, "base64");
    assert.equal(createHash("sha256").update(jpeg).digest("hex"), jpegVector.sha256);
    page.on("pageerror", error => errors.push(error.message));
    await page.route(/\/capabilities$/, async route => {
      const response = await route.fetch();
      const capabilities = await response.json();
      capabilities.supported = ["AUTOFOCUS", "LIVE_VIEW", "MEDIA_BROWSER"];
      capabilities.settings = [];
      if (advertised) {
        capabilities.heldAutofocusSupported = true;
        capabilities.focusInfoSupported = true;
      } else {
        delete capabilities.heldAutofocusSupported;
        delete capabilities.focusInfoSupported;
      }
      await route.fulfill({ response, json: capabilities });
    });
    await page.route(/\/focus\/auto\/(start|stop)$/, async route => {
      const owner = route.request().postDataJSON();
      const command = new URL(route.request().url()).pathname.endsWith("/start") ? "start" : "stop";
      commands.push({ command, ownerId: owner.ownerId });
      assert.match(owner.ownerId, /^[A-Za-z0-9._:-]{1,128}$/);
      if (command === "stop" && ++stopAttempts <= 2) {
        await route.fulfill({ status: 502, json: { error: { code: "SHUTTER_RELEASE_UNCONFIRMED", message: "Synthetic release is unconfirmed" } } });
      } else await route.fulfill({ json: { ownerId: owner.ownerId, active: command === "start", stopUnconfirmed: false } });
    });
    await page.route(/\/liveview\/(start|stop)$/, route => route.fulfill({ json: {
      active: route.request().url().endsWith("/start"), liveViewStopUnconfirmed: false,
      requestedFps: 4, source: "DESKTOP_BRIDGE_STREAM",
    } }));
    await page.route(/\/liveview\/frame$/, route => route.fulfill({ contentType: "image/jpeg", body: jpeg }));
    await page.route(/\/liveview\/focus-info$/, route => {
      focusReads++;
      return route.fulfill({ json: { magnification: 1, imageAspectRatio: focusAspectRatio,
        frames: [{ left: 0.25, top: 0.25, right: 0.75, bottom: 0.75, status: "FOCUSED", kind: "FACE" }],
      } });
    });
    try {
      await page.goto(origin, { waitUntil: "networkidle" });
      await page.waitForSelector("#connect-button:not([disabled])");
      await page.click("#connect-button");
      await page.waitForSelector("#control-view:not([hidden])");
      assert.equal(await page.locator("#held-autofocus-section").isVisible(), advertised);
      if (advertised) {
        await page.locator("#held-autofocus-start").focus();
        await page.keyboard.press("Enter");
        await page.waitForFunction(() => document.querySelector("#held-autofocus-status").textContent.includes("AF is held"));
        assert.equal(await page.locator("#held-autofocus-start").isDisabled(), true);
        assert.equal(await page.locator("#held-autofocus-stop").isEnabled(), true);
        await page.locator("#held-autofocus-stop").focus();
        await page.keyboard.press("Space");
        await page.waitForFunction(() => document.querySelector("#held-autofocus-status").textContent.includes("release is unconfirmed"));
        // Leaving the live panel makes one bounded automatic recovery attempt.
        // Stop remains globally visible and keyboard accessible while it settles.
        await page.click('.tab[data-view="diagnostics"]');
        await page.waitForFunction(() => document.querySelector("#held-autofocus-status").textContent.includes("release is unconfirmed"));
        assert.equal(await page.locator("#held-autofocus-stop").isVisible(), true);
        assert.equal(await page.locator("#held-autofocus-stop").isEnabled(), true);
        await page.locator("#held-autofocus-stop").focus();
        await page.keyboard.press("Enter");
        await page.waitForFunction(() => document.querySelector("#held-autofocus-stop").disabled);
        assert.equal(commands.length, 4);
        assert.deepEqual(commands.map(command => command.command), ["start", "stop", "stop", "stop"]);
        assert.equal(new Set(commands.map(command => command.ownerId)).size, 1);
        await page.click('.tab[data-view="live"]');
        // A new held command can be explicitly started and stopped after recovery.
        await page.click("#held-autofocus-start");
        await page.waitForFunction(() => document.querySelector("#held-autofocus-status").textContent.includes("AF is held"));
        await page.click("#held-autofocus-stop");
        await page.waitForFunction(() => document.querySelector("#held-autofocus-stop").disabled);
        assert.notEqual(commands[4].ownerId, commands[0].ownerId);
      }
      await page.click("#rail-live-button");
      await page.waitForSelector("#live-image:not([hidden])");
      if (advertised) {
        await page.waitForSelector("#camera-focus-overlay:not([hidden]) .status-focused.kind-face");
        const geometry = await page.evaluate(() => {
          const image = document.querySelector("#live-image").getBoundingClientRect();
          const overlay = document.querySelector("#camera-focus-overlay").getBoundingClientRect();
          const frame = document.querySelector(".camera-focus-frame").getBoundingClientRect();
          return { difference: Math.max(Math.abs(image.left - overlay.left), Math.abs(image.top - overlay.top),
            Math.abs(image.width - overlay.width), Math.abs(image.height - overlay.height)),
          widthRatio: frame.width / image.width, heightRatio: frame.height / image.height };
        });
        assert.ok(geometry.difference < 1, JSON.stringify(geometry));
        assert.ok(Math.abs(geometry.widthRatio - 0.5) < 0.01);
        assert.ok(Math.abs(geometry.heightRatio - 0.5) < 0.01);
        fs.mkdirSync(RESULTS_DIR, { recursive: true });
        await page.screenshot({ path: path.join(RESULTS_DIR, "desktop-camera-focus-frames.png"), fullPage: true });
        focusAspectRatio = 1.5;
        await page.waitForSelector("#camera-focus-overlay[hidden]", { state: "attached" });
        // Resize clears immediately, and invalid metadata must not reappear.
        await page.setViewportSize({ width: 390, height: 844 });
        assert.equal(await page.locator("#camera-focus-overlay").isVisible(), false);
        await page.click("#rail-live-button");
        await page.waitForSelector("#live-image[hidden]", { state: "attached" });
        assert.equal(await page.locator("#camera-focus-overlay").isVisible(), false);
        assert.equal(await page.locator("#camera-focus-legend").isVisible(), false);
      } else {
        await page.waitForFunction(() => parseFloat(document.querySelector("#frame-indicator").textContent) > 0);
        assert.equal(focusReads, 0, "Older Bridge without optional capability receives no telemetry request");
        assert.equal(commands.length, 0);
        assert.equal(await page.locator("#camera-focus-overlay").isVisible(), false);
      }
      assert.deepEqual(errors, []);
      await page.click("#disconnect-button");
      await page.waitForSelector("#connection-view:not([hidden])");
    } finally {
      await context.close();
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
  let mainPage = null;
  let scenarioError = null;
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
    mainPage = page;
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
    const isoOutcomeResponse = page.waitForResponse(response =>
      response.request().method() === "POST" && response.url().endsWith("/settings/iso/outcome"))
      .then(async response => ({ status: response.status(), outcome: await response.json() }));
    // Capture the body as soon as the response arrives, before click's idle wait.
    const [, isoResponse] = await Promise.all([
      page.locator("#setting-dialog .button.primary").click(), isoOutcomeResponse,
    ]);
    assert.equal(isoResponse.status, 200);
    const isoOutcome = isoResponse.outcome;
    assert.equal(isoOutcome.writeEvidence, "response-received");
    assert.equal(isoOutcome.readbackEvidence, "matched");
    assert.equal(isoOutcome.setting?.value, "1600");
    // A peer-side write/event is not completion of the scoped readback and editor.
    // Do not race the following external ISO change against that first transaction.
    await page.waitForFunction(() => !document.querySelector("#setting-dialog").open);
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
    await page.waitForFunction(() => document.querySelector("#operation-state")?.textContent ===
      "Focus command accepted; focus is not confirmed");
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
    await page.waitForFunction(() => document.querySelector("#operation-state")?.textContent === "Focus point accepted");
    assert.equal(await page.locator("#focus-reticle").getAttribute("class"), "focus-reticle accepted");

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
    if (await page.locator("#mode-apply-button").isVisible()) await page.click("#mode-apply-button");
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="moviequality"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.movie_quality.value === "1920x1080_2997_ipb_standard" &&
        state.movie_quality.update_count === 1,
      "Canon movie quality string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="highframerate"]', "enable");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="highframerate"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.high_frame_rate.value === "enable" && state.high_frame_rate.update_count === 1,
      "Canon high frame rate string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="moviecropping"]', "enable");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="moviecropping"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="movieformat"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="soundrecordinglevel"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="soundrecordinglevelintmic"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.sound_recording_level_intmic.value === 41 &&
        state.sound_recording_level_intmic.update_count === 1,
      "Canon internal microphone level integer write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="windfilterintmic"]', "disable");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="windfilterintmic"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.wind_filter_intmic.value === "disable" &&
        state.wind_filter_intmic.update_count === 1,
      "Canon internal microphone wind filter write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="windfilter"]', "enable");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="windfilter"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.wind_filter.value === "enable" && state.wind_filter.update_count === 1,
      "Canon wind filter string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="attenuator"]', "manual");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="attenuator"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.attenuator.value === "manual" && state.attenuator.update_count === 1,
      "Canon attenuator string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="soundrecording"]', "auto");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="soundrecording"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.sound_recording.value === "auto" && state.sound_recording.update_count === 1,
      "Canon sound recording mode string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="cardselectionmovie"]', "card1");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="cardselectionmovie"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="beep"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="displayoff"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="autopoweroff"]');
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
    if (await page.locator("#mode-apply-button").isVisible()) await page.click("#mode-apply-button");
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="focusbracketing"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="focusbracketingnumberofshots"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="focusbracketingfocusincrement"]');
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
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="focusbracketingexposuresmoothing"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.focus_bracketing_exposure_smoothing.value === "enable" &&
        state.focus_bracketing_exposure_smoothing.update_count === 1,
      "Canon focus bracketing exposure smoothing string write",
    );
    await page.selectOption('#advanced-settings select[data-setting-key="cardselectionstillimage"]', "card2");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="cardselectionstillimage"]');
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.still_card_selection === "card2" && state.card_selection_update_count === 2,
      "Canon still-image card selection",
    );
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2416 shots"));

    await page.selectOption('#advanced-settings select[data-setting-key="shootingmode"]', "Bulb");
    await page.click('#advanced-settings button[data-camera-command="setting"][data-setting-key="shootingmode"]');
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
    const maintenanceResponses = [];
    const maintenanceResponseListener = response => {
      let pathname;
      try { pathname = new URL(response.url()).pathname; } catch (_) { return; }
      const control = ["control/liveview/stop", "control/events", "sensor-cleaning/outcome"]
        .find(suffix => pathname.endsWith(`/maintenance/${suffix}`));
      if (control && maintenanceResponses.length < 16) {
        const headers = response.headers();
        maintenanceResponses.push({ control, status: response.status(),
          emptyLength: headers["content-length"] === undefined || headers["content-length"] === "0",
          transferEncoded: headers["transfer-encoding"] !== undefined,
          contentRanged: headers["content-range"] !== undefined,
          identityEncoding: headers["content-encoding"] === undefined || headers["content-encoding"] === "identity" });
      }
    };
    page.on("response", maintenanceResponseListener);
    page.once("dialog", (dialog) => dialog.accept());
    await page.locator('[data-camera-command="sensor-cleaning"]').first().click();
    try {
      await waitForSimulatorState(
        simulatorOrigin,
        (state) => state.sensor_cleaning.count === 1 &&
          !state.sensor_cleaning.auto_power_off &&
          !state.canonical.live_view_active &&
          state.canonical.live_view_start_count === 1 &&
          state.canonical.live_view_stop_count === 1,
        "CCAPI sensor cleaning leaves monitoring paused",
      );
    } catch (error) {
      console.log("OEC_MAINTENANCE_RESPONSES", JSON.stringify(maintenanceResponses));
      console.log("OEC_MAINTENANCE_ADMISSION", JSON.stringify(await page.evaluate(() => {
        const detail = document.querySelector("[data-maintenance-history] .maintenance-command small")?.textContent || "";
        let message = "";
        try { message = JSON.parse(document.querySelector("#diagnostics-output")?.textContent || "{}").lastError?.message || ""; } catch (_) { /* Missing diagnostics stays unknown. */ }
        const errors = ["Ambiguous setting response representation", "Ambiguous empty control response",
          "Configuration response stream unavailable", "Maintenance control response unavailable"];
        return { stopConfirmed: detail.includes("Stop preparation: Confirmed"),
          stopUnknown: detail.includes("Stop preparation: Unknown"), cancelled: detail.includes("Cancelled"),
          errorClass: errors.indexOf(message) };
      }).catch(() => ({ unavailable: true }))));
      throw error;
    } finally {
      page.off("response", maintenanceResponseListener);
    }
    const maintenanceRestore = page.locator('[data-maintenance-history] [data-maintenance-action="restore"]').last();
    await maintenanceRestore.waitFor({ state: "visible" });
    page.once("dialog", (dialog) => dialog.accept());
    await maintenanceRestore.click();
    await waitForSimulatorState(simulatorOrigin, (state) => state.canonical.live_view_active &&
      state.canonical.live_view_start_count === 2 && state.sensor_cleaning.count === 1,
      "Explicit restore uses a fresh reachable read without repeating sensor cleaning");

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
    await page.waitForSelector('[data-maintenance-history] [data-maintenance-action="recover"]:not([disabled])');
    assert.equal(await page.locator("#control-view").isVisible(), true);
    assert.equal(await page.locator("#live-toggle-button").isDisabled(), true);
    const finalState = await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.camera_sleep_count === 1 &&
        !state.canonical.live_view_active &&
        state.canonical.live_view_stop_count === 2 &&
        state.canonical.event_delete_count >= 1 &&
        state.canonical.event_active_requests === 0,
      "CCAPI camera sleep stops monitoring and retains outcome until explicit disconnect",
    );
    assert.equal(finalState.canonical.af_start_count, finalState.canonical.af_stop_count);
    assert.equal(finalState.half_press_count, finalState.shutter_release_count);
    await page.waitForTimeout(1100);
    assert.deepEqual(await page.evaluate(() => window.__objectUrlRevocationViolations), []);
    assert.deepEqual(pageErrors, []);
    // Sleep retains a maintenance owner even across page teardown. Finish its
    // explicit local-session retirement before the next independent journey
    // attempts to acquire the same synthetic camera through this Bridge.
    const maintenanceClosed = page.waitForResponse(response =>
      response.request().method() === "DELETE" &&
      /^\/v1\/session\/[^/]+\/maintenance\/control\/session$/.test(new URL(response.url()).pathname));
    await page.click("#disconnect-button");
    const retiredSession = await maintenanceClosed;
    assert.equal(retiredSession.status(), 204);
    assert.equal((await retiredSession.body()).length, 0);
    await page.waitForSelector("#connection-view:not([hidden])");
    assert.equal(await page.locator("#connection-error").isHidden(), true);
    await page.unrouteAll({ behavior: "wait" });
    await context.close();
    mainPage = null;
    await verifyConnectionRecoveryJourneys(browser, bridgeOrigin, simulatorOrigin);
    await verifyCapabilityResponseOwnership(browser, bridgeOrigin);
    await verifyMediaDeleteResponseOwnership(browser, bridgeOrigin);
    await verifyMediaDateJourneys(browser, bridgeOrigin, simulatorOrigin);
    await verifyRecordingReviewJourneys(browser, bridgeOrigin, simulatorOrigin);
    await verifyHeldAutofocusAndFocusFrames(browser, bridgeOrigin);
  } catch (error) {
    scenarioError = error;
    console.error("OEC_PRIMARY_JOURNEY_ERROR", error);
    throw error;
  } finally {
    if (mainPage && !mainPage.isClosed()) {
      try { await mainPage.unrouteAll({ behavior: "wait" }); }
      catch (error) {
        console.error("CCAPI route cleanup also failed", error);
        if (!scenarioError) process.exitCode = 1;
      }
    }
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
