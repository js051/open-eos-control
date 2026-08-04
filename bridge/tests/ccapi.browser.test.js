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
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error") pageErrors.push(message.text());
    });

    await page.goto(bridgeOrigin, { waitUntil: "networkidle" });
    await page.click("#ccapi-mode-button");
    await page.fill("#ccapi-url-input", simulatorOrigin);
    await page.click("#connect-button");
    await page.waitForSelector("#control-view:not([hidden])");
    assert.match(await page.locator("#camera-name").innerText(), /R6 Mark III/);
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2418 shots"));
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
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2417 shots"));

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
    const soundRecordingKeys = ["soundrecording", "soundrecordinglevel", "windfilter", "attenuator"];
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
    await page.waitForFunction(() => document.querySelector("#storage-value")?.textContent?.includes("2417 shots"));

    await page.selectOption('#advanced-settings select[data-setting-key="shootingmode"]', "Bulb");
    await waitForSimulatorState(simulatorOrigin, (state) => state.mode === "Bulb", "Canon Bulb mode write");
    await page.waitForFunction(() => {
      const shutter = document.querySelector("#shutter-button");
      return shutter?.classList.contains("bulb") && !shutter.disabled;
    });
    assert.equal((await readSimulatorState(simulatorOrigin)).capture_count, 1);
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

    await page.click('.tab[data-view="media"]');
    const capturedMedia = page.locator(".media-row").filter({ hasText: "SIM_0003.JPG" });
    await capturedMedia.waitFor({ state: "visible" });
    await capturedMedia.locator("button.media-thumbnail").click();
    await page.waitForSelector("#media-preview-dialog[open] #media-preview-image:not([hidden])");
    await page.click("#media-preview-close");
    page.once("dialog", (dialog) => dialog.accept());
    await capturedMedia.locator('button[aria-label="Delete SIM_0003.JPG"]').click();
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => !state.media_ids.includes("SIM_0003.JPG"),
      "confirmed exact Canon media deletion",
    );
    const deliveredBeforeExternalCapture = (await readSimulatorState(simulatorOrigin))
      .canonical.event_delivery_count;
    const externalCapture = await fetch(`${simulatorOrigin}/ccapi/capture/still`, { method: "POST" });
    assert.equal(externalCapture.ok, true);
    await page.locator(".media-row").filter({ hasText: "SIM_0004.PNG" }).waitFor({ state: "visible" });
    await waitForSimulatorState(
      simulatorOrigin,
      (state) => state.canonical.event_delivery_count > deliveredBeforeExternalCapture,
      "external camera contents event to refresh the open media view",
    );

    await page.click('.tab[data-view="diagnostics"]');
    await page.waitForSelector("#diagnostics-panel:not([hidden])");
    const diagnostics = await page.locator("#diagnostics-output").innerText();
    assert.match(diagnostics, /CCAPI_NETWORK|ccapi/i);
    assert.match(diagnostics, /EVENT_POLLING/);
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
        state.canonical.live_view_stop_count === 1 &&
        state.canonical.event_delete_count >= 1 &&
        state.canonical.event_active_requests === 0,
      "CCAPI camera sleep to stop Live View, event polling, and disconnect",
    );
    assert.equal(finalState.canonical.af_start_count, finalState.canonical.af_stop_count);
    assert.equal(finalState.half_press_count, finalState.shutter_release_count);
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
