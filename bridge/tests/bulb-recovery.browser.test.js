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

async function assertActionable(page, selector) {
  await page.locator(selector).scrollIntoViewIfNeeded();
  const geometry = await page.evaluate((target) => {
    const button = document.querySelector(target);
    const box = button.getBoundingClientRect();
    const points = [[0.5, 0.5], [0.25, 0.5], [0.75, 0.5], [0.5, 0.25], [0.5, 0.75]];
    return {
      visible: box.width > 0 && box.height > 0 && box.left >= 0 && box.top >= 0 &&
        box.right <= innerWidth && box.bottom <= innerHeight,
      enabled: !button.disabled,
      unobstructed: points.every(([x, y]) => button.contains(document.elementFromPoint(
        box.left + box.width * x, box.top + box.height * y,
      ))),
    };
  }, selector);
  assert.deepEqual(geometry, { visible: true, enabled: true, unobstructed: true }, `${selector} must remain reachable`);
}

async function assertWarningDoesNotOverlapControls(page) {
  const result = await page.evaluate(() => {
    const warning = document.querySelector("#shutter-disconnect-warning");
    const box = warning.getBoundingClientRect();
    const overlap = (selector) => {
      const control = document.querySelector(selector).getBoundingClientRect();
      return Math.min(box.right, control.right) > Math.max(box.left, control.left) &&
        Math.min(box.bottom, control.bottom) > Math.max(box.top, control.top);
    };
    return { normalFlow: getComputedStyle(warning).position === "static",
      shutter: overlap("#shutter-button"), exposure: overlap("#exposure-strip") };
  });
  assert.deepEqual(result, { normalFlow: true, shutter: false, exposure: false });
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
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Retry camera stop");
    assert.equal(await page.isDisabled("#shutter-button"), false);
    assert.equal(await page.isDisabled("#photo-mode-button"), true);
    assert.equal(await page.isDisabled("#video-mode-button"), true);
    assert.equal(await page.isDisabled("#half-press-button"), true);
    assert.equal(await page.isDisabled("#live-toggle-button"), true);
    assert.equal(await page.isDisabled("#rail-live-button"), true);
    assert.equal(await page.isVisible("#toast"), false);
    assert.match(await page.textContent("#bulb-indicator"), /stop unconfirmed/i);
    assert.equal((await cameraState()).commands.length, 2);

    // Neither a changed mode nor a failed status refresh may turn Stop into Start.
    await configure({ mode: "Manual", drop_status: true });
    await page.click("#refresh-button");
    await page.waitForFunction(() => document.querySelector("#operation-state")?.classList.contains("error-text"));
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Retry camera stop");
    await configure({ drop_status: false });
    await refreshMode();
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Retry camera stop");

    await page.click("#shutter-button");
    await page.waitForFunction(() => !document.querySelector("#shutter-button").disabled);
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Retry camera stop");
    assert.equal((await cameraState()).commands.length, 3);
    const results = path.join(root, "test-results");
    fs.mkdirSync(results, { recursive: true });
    await page.screenshot({ path: path.join(results, "bulb-release-unconfirmed.png"), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await assertActionable(page, "#shutter-button");
    assert.equal(await page.isDisabled("#live-toggle-button"), true);
    assert.equal(await page.isDisabled("#rail-live-button"), true);
    assert.equal(await page.isVisible("#toast"), false);
    // A real narrow-viewport retry must remain clickable immediately after the next failure.
    await page.click("#shutter-button");
    await page.waitForFunction(() => !document.querySelector("#shutter-button").disabled);
    assert.equal((await cameraState()).commands.length, 4);
    await assertActionable(page, "#shutter-button");
    assert.equal(await page.isVisible("#toast"), false);
    await page.screenshot({ path: path.join(results, "bulb-release-unconfirmed-mobile.png"), fullPage: true });

    await configure({ reject_release: false });
    await page.click("#shutter-button");
    await page.waitForFunction(() => {
      const toast = document.querySelector("#toast");
      return toast && !toast.hidden && toast.textContent === "Camera stop confirmed";
    });
    assert.equal(await page.isVisible("#bulb-indicator"), false);
    assert.equal(await page.isDisabled("#photo-mode-button"), false);
    assert.equal((await cameraState()).active, false);
    assert.equal(requests.filter((url) => url.endsWith("/bulb/start")).length, 1);
    assert.equal(requests.filter((url) => url.endsWith("/bulb/stop")).length, 3);
    assert.equal((await cameraState()).commands.filter(([, , body]) => body.action === "full_press").length, 1);

    await page.setViewportSize({ width: 1280, height: 900 });
    // An acknowledged exposure keeps Stop when the body switches away from Bulb.
    await configure({ mode: "Bulb", drop_press: false, reject_release: false });
    await refreshMode();
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Start Bulb exposure");
    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Stop Bulb exposure");
    assert.equal(await page.isVisible("#toast"), false);
    await page.setViewportSize({ width: 390, height: 844 });
    await assertActionable(page, "#shutter-button");
    assert.equal(await page.isVisible("#toast"), false);
    await page.setViewportSize({ width: 1280, height: 900 });
    withdrawBulbCapability = true;
    await configure({ mode: "Manual", status_failure_after_release: true });
    await refreshMode();
    assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "Stop Bulb exposure");
    assert.equal(await page.isDisabled("#shutter-button"), false);
    const beforeStop = (await cameraState()).commands.length;
    // A previous successful Stop leaves the same text in the now-hidden toast. Wait for
    // this operation to publish a visible confirmation, not that stale DOM text.
    assert.equal(await page.isVisible("#toast"), false);
    const stopResponse = page.waitForResponse((response) => response.url().endsWith("/bulb/stop"));
    await page.click("#shutter-button");
    assert.equal((await stopResponse).status(), 502);
    await page.waitForFunction(() => {
      const toast = document.querySelector("#toast");
      return toast && !toast.hidden && toast.textContent === "Camera stop confirmed";
    });
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
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Retry camera stop");
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
    // A new exposure is explicitly requested; the old warning must never obstruct its Stop.
    await configure({ drop_press: false, reject_release: false });
    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Stop Bulb exposure");
    assert.equal((await cameraState()).commands.length, beforeReconnect + 1);
    assert.equal(await page.isVisible("#toast"), false);
    await assertActionable(page, "#shutter-button");
    await assertActionable(page, "#shutter-disconnect-confirm");
    await assertWarningDoesNotOverlapControls(page);
    await page.screenshot({ path: path.join(results, "bulb-previous-session-warning.png"), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await assertActionable(page, "#shutter-button");
    await assertActionable(page, "#shutter-disconnect-confirm");
    await assertWarningDoesNotOverlapControls(page);
    await page.screenshot({ path: path.join(results, "bulb-previous-session-warning-mobile.png"), fullPage: true });
    await page.click("#shutter-disconnect-confirm");
    assert.equal(await page.isVisible("#shutter-disconnect-warning"), false);
    assert.equal((await cameraState()).commands.length, beforeReconnect + 1);
    await assertActionable(page, "#shutter-button");
    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Start Bulb exposure");
    assert.equal((await cameraState()).commands.length, beforeReconnect + 2);
    await page.click("#disconnect-button");
    await page.waitForSelector("#connection-view:not([hidden])");
    assert.equal((await cameraState()).commands.length, beforeReconnect + 2);

    // Short controls own the same reachable stop-only recovery even outside Bulb mode.
    await page.setViewportSize({ width: 1280, height: 900 });
    await configure({ mode: "Manual", drop_press: false, reject_release: true,
      direct_shutter: false, dedicated_af: true, media_enabled: true, media_ready: false, capture_count: 0 });
    await page.click("#connect-button");
    await page.waitForSelector("#control-view:not([hidden])");
    await page.waitForFunction(() => document.querySelector("#latest-media-label")?.textContent === "SYNTHETIC_OLD.JPG");
    for (const [selector, startAction, stopAction] of [
      ["#shutter-button", "full_press", "release"],
      ["#half-press-button", "half_press", "release"],
      ["#autofocus-button", "start", "stop"],
    ]) {
      await configure({ reject_release: true });
      const before = (await cameraState()).commands.length;
      await page.click(selector);
      await page.waitForFunction(() => document.querySelector("#shutter-button")?.getAttribute("aria-label") === "Retry camera stop");
      assert.match(await page.textContent("#bulb-indicator"), /shutter or autofocus may still be active/i);
      assert.doesNotMatch(await page.textContent("#bulb-indicator"), /Bulb/);
      assert.equal(await page.isDisabled("#half-press-button"), true);
      assert.equal(await page.isDisabled("#autofocus-button"), true);
      await page.setViewportSize({ width: 390, height: 844 });
      await assertActionable(page, "#shutter-button");
      if (startAction === "start") {
        await page.locator("#control-view .language-select").selectOption("zh-TW");
        assert.equal(await page.getAttribute("#shutter-button", "aria-label"), "重試停止相機操作");
        assert.match(await page.textContent("#bulb-indicator"), /快門或自動對焦可能仍在作用/);
        assert.doesNotMatch(await page.textContent("#bulb-indicator"), /Bulb|長曝光/);
        await assertActionable(page, "#shutter-button");
        await page.screenshot({ path: path.join(results, "bridge-short-control-stop-zh-TW.png"), fullPage: true });
        await page.locator("#control-view .language-select").selectOption("en");
      }
      await configure({ reject_release: false });
      await page.click("#shutter-button");
      await page.waitForFunction(() => !document.querySelector("#bulb-indicator") || document.querySelector("#bulb-indicator").hidden);
      assert.deepEqual((await cameraState()).commands.slice(before).map(([, , body]) => body.action),
        [startAction, stopAction, stopAction], "Retry sends only the original control's stop");
      await page.setViewportSize({ width: 1280, height: 900 });
    }

    // The real shutter and release ACK, then the TCP status read is lost. The page
    // preserves the warning while its existing read-only JPEG journey stays usable.
    await page.uncheck("#shutter-af-toggle");
    await configure({ status_failure_after_release: true, media_ready: false });
    const beforeCapture = (await cameraState()).commands.length;
    const reviewRequests = [];
    page.on("request", (request) => {
      if (/\/media\?limit=8$/.test(request.url())) reviewRequests.push(request.url());
    });
    const capturedResponse = page.waitForResponse((response) => response.url().endsWith("/capture/still"));
    await page.click("#shutter-button");
    const captured = await capturedResponse;
    assert.equal(captured.status(), 502);
    assert.equal((await captured.json()).error.code, "CAPTURE_STATUS_READBACK_FAILED");
    await page.getByRole("button", { name: /check again/i }).waitFor({ state: "visible" });
    // The retry is already visible but disabled during the automatic bounded search.
    // Keep the peer unready until that search ends, so only this manual click finds NEW.
    await page.waitForFunction(() => {
      const button = document.querySelector("#latest-media-retry");
      return button && !button.hidden && !button.disabled;
    });
    await assertActionable(page, "#latest-media-retry");
    assert.equal(reviewRequests.length, 4, "The automatic search exhausts its four bounded reads");
    assert.equal(await page.locator("#latest-media-label").innerText(), "SYNTHETIC_OLD.JPG");
    await configure({ media_ready: true });
    await page.getByRole("button", { name: /check again/i }).click();
    await page.waitForFunction(() => document.querySelector("#latest-media-label")?.textContent === "SYNTHETIC_NEW.JPG");
    assert.equal(reviewRequests.length, 5, "The enabled manual retry performs one new read");
    await page.click("#latest-media-button");
    await page.waitForFunction(() => {
      const image = document.querySelector("#media-preview-image");
      return document.querySelector("#media-preview-dialog")?.open && image?.complete && image.naturalWidth === 32;
    });
    assert.deepEqual((await cameraState()).commands.slice(beforeCapture), [
      ["PUT", "/ccapi/ver100/shooting/control/shutterbutton/manual", { af: false, action: "full_press" }],
      ["PUT", "/ccapi/ver100/shooting/control/shutterbutton/manual", { af: false, action: "release" }],
    ], "Read-only review retry and JPEG preview never resend the shutter");
    await page.screenshot({ path: path.join(results, "bridge-capture-readback-jpeg-recovery.png"), fullPage: true });
    await page.click("#media-preview-close");
    await page.click("#disconnect-button");
    await page.waitForSelector("#connection-view:not([hidden])");
    assert.deepEqual(pageErrors, []);
    console.log("PASS: real Bridge HTTP peer + browser Bulb/short-control stop-only recovery and acknowledged capture JPEG recovery");
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
