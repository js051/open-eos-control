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

    await page.click("#shutter-button");
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.capture_count === 1 && state.media_ids.includes("SIM_0003.JPG"),
      "still capture and camera media creation",
    );
    await page.waitForFunction(() => {
      const button = document.querySelector("#latest-media-button");
      return button?.querySelector("#latest-media-label")?.textContent === "SIM_0003.JPG" && !button.disabled;
    });
    await page.click("#latest-media-button");
    await page.waitForFunction(() => {
      const dialog = document.querySelector("#media-preview-dialog");
      return dialog?.open && document.querySelector("#media-preview-title")?.textContent === "SIM_0003.JPG";
    });
    await page.click("#media-preview-close");

    retryLatestMedia = true;
    await page.route(/\/thumbnail$/, (route) => route.fulfill({
      status: 200,
      contentType: "text/plain",
      body: "not-an-image",
    }));
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
    await page.click("#media-refresh-button");
    const selectedMediaInfoRoute = /\/v1\/session\/[^/]+\/media\/[^/]+\/info(?:\?.*)?$/;
    await page.route(selectedMediaInfoRoute, async (route) => {
      const response = await route.fetch();
      const item = await response.json();
      await route.fulfill({
        response,
        json: { ...item, contentType: "image/jpeg", widthPixels: 6000, heightPixels: 4000 },
      });
    });
    const capturedMedia = page.locator(".media-card").filter({ hasText: "SIM_0003.JPG" });
    await capturedMedia.waitFor({ state: "visible" });
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
