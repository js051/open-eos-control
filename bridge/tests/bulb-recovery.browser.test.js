"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const path = require("node:path");
const { chromium } = require("playwright");

const root = path.resolve(__dirname, "..");

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function run() {
  const port = await freePort();
  const origin = `http://127.0.0.1:${port}`;
  const server = spawn(process.env.PYTHON || (process.platform === "win32" ? "python" : "python3"), [
    "-m", "uvicorn", "tests.bulb_browser_server:app", "--host", "127.0.0.1",
    "--port", String(port), "--log-level", "warning",
  ], { cwd: root, stdio: ["ignore", "pipe", "pipe"] });
  let stderr = "";
  server.stderr.on("data", (chunk) => { stderr += chunk; });
  let browser;
  try {
    let peer;
    for (let attempt = 0; attempt < 100; attempt += 1) {
      try {
        peer = await (await fetch(`${origin}/__test/peer`)).json();
        break;
      } catch (_) {
        if (server.exitCode !== null) throw new Error(stderr);
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
    }
    assert.ok(peer, `Bridge did not start: ${stderr}`);
    const configure = async (json) => {
      const response = await fetch(`${peer.origin}/__test/config`, {
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(json),
      });
      assert.equal(response.status, 200);
    };
    const cameraState = async () => (await fetch(`${peer.origin}/__test/state`)).json();
    browser = await chromium.launch({
      headless: true,
      ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}),
    });
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, locale: "en-US" });
    // Status refresh updates the server's settings cache. A second settled refresh avoids
    // depending on the unrelated pre-existing parallel status/capability cache ordering.
    const refreshMode = async () => {
      for (let attempt = 0; attempt < 2; attempt += 1) {
        await page.click("#refresh-button");
        await page.waitForFunction(() => document.querySelector("#operation-state")?.textContent === "Ready");
      }
    };
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    let withdrawBulbCapability = false;
    await page.route(/\/capabilities$/, async (route) => {
      const response = await route.fetch();
      const json = await response.json();
      if (withdrawBulbCapability) json.supported = json.supported.filter((value) => value !== "BULB_EXPOSURE");
      await route.fulfill({ response, json });
    });
    const requests = [];
    page.on("request", (request) => {
      if (/\/bulb\/(start|stop)$/.test(request.url())) requests.push(request.url());
    });
    await page.goto(origin);
    await page.click("#ccapi-mode-button");
    await page.fill("#ccapi-url-input", peer.origin);
    await page.click("#connect-button");
    await page.waitForSelector("#control-view:not([hidden])");
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Start Bulb exposure");

    // The camera receives full_press; both the reply and compensating release fail.
    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Retry Stop Bulb");
    assert.equal(await page.isDisabled("#shutter-button"), false);
    assert.equal(await page.isDisabled("#photo-mode-button"), true);
    assert.equal(await page.isDisabled("#video-mode-button"), true);
    assert.equal(await page.isDisabled("#half-press-button"), true);
    assert.match(await page.textContent("#bulb-indicator"), /release unconfirmed/i);
    assert.equal((await cameraState()).commands.length, 2);

    // Neither a changed mode nor a failed status refresh may turn Stop into Start.
    await configure({ mode: "Manual", drop_status: true });
    await page.click("#refresh-button");
    await page.waitForFunction(() => document.querySelector("#operation-state")?.classList.contains("error-text"));
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Retry Stop Bulb");
    await configure({ drop_status: false });
    await refreshMode();
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Retry Stop Bulb");

    await page.click("#shutter-button");
    await page.waitForFunction(() => !document.querySelector("#shutter-button").disabled);
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Retry Stop Bulb");
    assert.equal((await cameraState()).commands.length, 3);
    const results = path.join(root, "test-results");
    fs.mkdirSync(results, { recursive: true });
    await page.screenshot({ path: path.join(results, "bulb-release-unconfirmed.png"), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(results, "bulb-release-unconfirmed-mobile.png"), fullPage: true });
    await page.setViewportSize({ width: 1280, height: 900 });

    await configure({ reject_release: false });
    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#toast")?.textContent === "Shutter release confirmed");
    assert.equal(await page.isVisible("#bulb-indicator"), false);
    assert.equal(await page.isDisabled("#photo-mode-button"), false);
    assert.equal((await cameraState()).active, false);
    assert.equal(requests.filter((url) => url.endsWith("/bulb/start")).length, 1);
    assert.equal(requests.filter((url) => url.endsWith("/bulb/stop")).length, 2);
    assert.equal((await cameraState()).commands.filter(([, , body]) => body.action === "full_press").length, 1);

    // An acknowledged exposure keeps Stop when the body switches away from Bulb.
    await configure({ mode: "Bulb", drop_press: false, reject_release: false });
    await refreshMode();
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Start Bulb exposure");
    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Stop Bulb exposure");
    withdrawBulbCapability = true;
    await configure({ mode: "Manual", status_failure_after_release: true });
    await refreshMode();
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Stop Bulb exposure");
    assert.equal(await page.isDisabled("#shutter-button"), false);
    const beforeStop = (await cameraState()).commands.length;
    const stopResponse = page.waitForResponse((response) => response.url().endsWith("/bulb/stop"));
    await page.click("#shutter-button");
    assert.equal((await stopResponse).status(), 502);
    await page.waitForFunction(() => document.querySelector("#toast")?.textContent === "Shutter release confirmed");
    assert.equal(await page.isVisible("#bulb-indicator"), false);
    assert.equal(await page.isDisabled("#photo-mode-button"), false);
    assert.equal((await cameraState()).commands.length, beforeStop + 1);
    assert.equal((await cameraState()).active, false);
    withdrawBulbCapability = false;
    await configure({ status_failure_after_release: false });

    // Teardown reports the unresolved release and does not carry stop responsibility to a new session.
    await configure({ mode: "Bulb", drop_press: true, reject_release: true });
    await refreshMode();
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Start Bulb exposure");
    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Retry Stop Bulb");
    await page.click("#disconnect-button");
    await page.waitForSelector("#connection-view:not([hidden])");
    assert.match(await page.textContent("#connection-error"), /stop the exposure on the camera before reconnecting/);
    assert.notEqual(await page.textContent("#toast"), "Camera disconnected");
    const beforeReconnect = (await cameraState()).commands.length;
    await page.click("#connect-button");
    await page.waitForSelector("#control-view:not([hidden])");
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Start Bulb exposure");
    assert.equal((await cameraState()).commands.length, beforeReconnect);
    assert.equal(await page.isVisible("#shutter-disconnect-warning"), true);
    assert.match(await page.textContent("#shutter-disconnect-warning"), /previous connection/);
    await page.screenshot({ path: path.join(results, "bulb-previous-session-warning.png"), fullPage: true });
    await page.click("#shutter-disconnect-confirm");
    assert.equal(await page.isVisible("#shutter-disconnect-warning"), false);
    assert.equal((await cameraState()).commands.length, beforeReconnect);
    await page.click("#disconnect-button");
    await page.waitForSelector("#connection-view:not([hidden])");
    assert.equal((await cameraState()).commands.length, beforeReconnect);
    assert.deepEqual(pageErrors, []);
    console.log("PASS: real Bridge HTTP peer + browser stop-only recovery, status failure, mode change, retry, teardown and fresh-session isolation");
  } finally {
    await browser?.close();
    server.kill();
    await Promise.race([
      new Promise((resolve) => server.once("exit", resolve)),
      new Promise((resolve) => setTimeout(resolve, 5000)),
    ]);
    if (server.exitCode === null) server.kill("SIGKILL");
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
