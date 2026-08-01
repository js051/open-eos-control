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
      throw new Error(`Browser test server exited early (${process.exitCode}): ${stderr()}`);
    }
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch (_) {
      // The server may still be binding its loopback socket.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Browser test server did not become ready: ${stderr()}`);
}

async function stopProcess(process) {
  if (process.exitCode !== null) return;
  process.kill();
  await Promise.race([
    new Promise((resolve) => process.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 5_000)),
  ]);
  if (process.exitCode === null) process.kill("SIGKILL");
}

async function readBridgeState(origin) {
  const response = await fetch(`${origin}/__test/state`);
  assert.equal(response.ok, true, `Bridge test state returned HTTP ${response.status}`);
  return response.json();
}

async function waitForBridgeState(origin, predicate, description, timeoutMillis = 10_000) {
  const deadline = Date.now() + timeoutMillis;
  let latest = null;
  while (Date.now() < deadline) {
    latest = await readBridgeState(origin);
    if (predicate(latest)) return latest;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${description}: ${JSON.stringify(latest)}`);
}

function commandIndex(state, expected, startAt = 0) {
  return state.commands.findIndex(
    (command, index) => index >= startAt && command.length === expected.length &&
      command.every((argument, argumentIndex) => argument === expected[argumentIndex]),
  );
}

function hasCommandContaining(state, argument) {
  return state.commands.some((command) => command.includes(argument));
}

async function run() {
  const port = await freePort();
  const origin = `http://127.0.0.1:${port}`;
  const captureDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "open-eos-browser-test-"));
  const python = process.env.PYTHON || (process.platform === "win32" ? "python" : "python3");
  const server = spawn(
    python,
    ["-m", "uvicorn", "tests.browser_server:app", "--host", "127.0.0.1", "--port", String(port), "--log-level", "warning"],
    {
      cwd: BRIDGE_ROOT,
      env: { ...process.env, OPEN_EOS_BROWSER_CAPTURE_DIR: captureDirectory },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let serverError = "";
  server.stderr.on("data", (chunk) => { serverError += chunk.toString(); });
  let browser = null;
  try {
    await waitForServer(`${origin}/health`, server, () => serverError);
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({
      locale: "en-US",
      viewport: { width: 1440, height: 900 },
    });
    await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin });
    await context.addInitScript(() => {
      const testState = {
        getUserMediaCalls: 0,
        stoppedTracks: 0,
        deliveredVideoFrameCallbacks: 0,
        delayNextGetUserMedia: false,
        pendingGetUserMedia: false,
        rejectNextPlay: false,
        lastConstraints: null,
        currentTrack: null,
        audioContexts: 0,
        audioStarts: 0,
        audioStops: 0,
        audioCloses: 0,
      };
      let videoFrameCallbackId = 0;
      const videoFrameCallbacks = new Map();
      Object.defineProperty(HTMLVideoElement.prototype, "requestVideoFrameCallback", {
        configurable: true,
        value(callback) {
          const id = ++videoFrameCallbackId;
          const timer = setTimeout(() => {
            videoFrameCallbacks.delete(id);
            testState.deliveredVideoFrameCallbacks += 1;
            callback(performance.now(), { presentedFrames: id });
          }, 1000 / 30);
          videoFrameCallbacks.set(id, timer);
          return id;
        },
      });
      Object.defineProperty(HTMLVideoElement.prototype, "cancelVideoFrameCallback", {
        configurable: true,
        value(id) {
          const timer = videoFrameCallbacks.get(id);
          if (timer !== undefined) clearTimeout(timer);
          videoFrameCallbacks.delete(id);
        },
      });
      const deviceEvents = new EventTarget();
      Object.defineProperty(globalThis, "__openEosLocalVideoTest", {
        configurable: false,
        value: testState,
      });
      const originalMediaPlay = HTMLMediaElement.prototype.play;
      Object.defineProperty(HTMLMediaElement.prototype, "play", {
        configurable: true,
        value() {
          if (this.id === "local-video" && testState.rejectNextPlay) {
            testState.rejectNextPlay = false;
            return Promise.reject(new DOMException("Autoplay rejected for test.", "NotAllowedError"));
          }
          return originalMediaPlay.call(this);
        },
      });
      const mediaDevices = {
        addEventListener: deviceEvents.addEventListener.bind(deviceEvents),
        removeEventListener: deviceEvents.removeEventListener.bind(deviceEvents),
        dispatchEvent: deviceEvents.dispatchEvent.bind(deviceEvents),
        async enumerateDevices() {
          return [
            { kind: "audioinput", deviceId: "test-mic", label: "Test microphone" },
            { kind: "videoinput", deviceId: "test-capture-card", label: "Test HDMI capture" },
            { kind: "videoinput", deviceId: "test-usb-video", label: "Test USB video" },
          ];
        },
        async getUserMedia(constraints) {
          testState.getUserMediaCalls += 1;
          testState.lastConstraints = JSON.parse(JSON.stringify(constraints));
          const canvas = document.createElement("canvas");
          canvas.width = 1280;
          canvas.height = 720;
          const context = canvas.getContext("2d");
          let frame = 0;
          const draw = () => {
            context.fillStyle = frame % 2 ? "#20c4cb" : "#111416";
            context.fillRect(0, 0, canvas.width, canvas.height);
            context.fillStyle = "#ffffff";
            context.font = "48px sans-serif";
            context.fillText(`Open EOS ${frame}`, 48, 80);
            frame += 1;
          };
          draw();
          const timer = setInterval(draw, 1000 / 30);
          const stream = canvas.captureStream(30);
          const track = stream.getVideoTracks()[0];
          const originalStop = track.stop.bind(track);
          let stopped = false;
          Object.defineProperty(track, "getSettings", {
            configurable: true,
            value: () => ({
              width: 1280,
              height: 720,
              frameRate: 30,
              aspectRatio: 16 / 9,
              deviceId: "private-test-device-id",
              groupId: "private-test-group-id",
            }),
          });
          track.stop = () => {
            if (!stopped) {
              stopped = true;
              clearInterval(timer);
              testState.stoppedTracks += 1;
            }
            originalStop();
          };
          testState.currentTrack = track;
          if (testState.delayNextGetUserMedia) {
            testState.delayNextGetUserMedia = false;
            testState.pendingGetUserMedia = true;
            await new Promise((resolve) => {
              testState.releasePendingGetUserMedia = () => {
                testState.pendingGetUserMedia = false;
                resolve();
              };
            });
          }
          return stream;
        },
      };
      Object.defineProperty(navigator, "mediaDevices", {
        configurable: true,
        value: mediaDevices,
      });
      class FakeAudioContext {
        constructor(options) {
          testState.audioContexts += 1;
          testState.audioContextOptions = options;
          this.destination = {};
        }

        get currentTime() {
          return performance.now() / 1000;
        }

        resume() {
          return Promise.resolve();
        }

        close() {
          testState.audioCloses += 1;
          return Promise.resolve();
        }

        createBuffer(channels, frames, sampleRate) {
          testState.audioBuffer = { channels, frames, sampleRate };
          return { copyToChannel() {} };
        }

        createBufferSource() {
          const source = new EventTarget();
          source.connect = () => {};
          source.start = () => {
            testState.audioStarts += 1;
            setTimeout(() => source.dispatchEvent(new Event("ended")), 10);
          };
          source.stop = () => { testState.audioStops += 1; };
          return source;
        }
      }
      Object.defineProperty(globalThis, "AudioContext", {
        configurable: true,
        value: FakeAudioContext,
      });
    });

    const page = await context.newPage();
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error") pageErrors.push(message.text());
    });
    await page.goto(origin, { waitUntil: "networkidle" });
    await page.waitForFunction(() => document.querySelectorAll("#camera-select option").length > 1);
    await page.selectOption("#camera-select", { index: 1 });
    await page.click("#connect-button");
    await page.waitForSelector("#control-view:not([hidden])");
    assert.match(await page.locator("#camera-name").innerText(), /R6 Mark III/);

    await page.click('.exposure-control[data-setting-key="iso"]');
    await page.waitForSelector("#setting-dialog[open]");
    await page.getByRole("button", { name: "800", exact: true }).click();
    await waitForBridgeState(
      origin,
      (backend) => backend.values["/main/imgsettings/iso"] === "800" &&
        commandIndex(backend, ["--set-config-value", "/main/imgsettings/iso=800"]) >= 0,
      "the ISO control to reach GPhoto2Engine",
    );

    let backend = await readBridgeState(origin);
    const autofocusStart = backend.commands.length;
    await page.click("#autofocus-button");
    backend = await waitForBridgeState(
      origin,
      (candidate) => commandIndex(
        candidate,
        ["--set-config-value", "/main/actions/autofocuscancel=1"],
        autofocusStart,
      ) >= 0,
      "AF-ON to complete its drive and cancel sequence",
    );
    const autofocusDrive = commandIndex(
      backend,
      ["--set-config-value", "/main/actions/autofocusdrive=1"],
      autofocusStart,
    );
    const autofocusCancel = commandIndex(
      backend,
      ["--set-config-value", "/main/actions/autofocuscancel=1"],
      autofocusStart,
    );
    assert.ok(autofocusDrive >= autofocusStart && autofocusCancel > autofocusDrive);

    const halfPressStart = backend.commands.length;
    await page.click("#half-press-button");
    backend = await waitForBridgeState(
      origin,
      (candidate) => commandIndex(
        candidate,
        ["--set-config-value", "/main/actions/eosremoterelease=Release Half"],
        halfPressStart,
      ) >= 0,
      "half-press to release the shutter safely",
    );
    const halfPress = commandIndex(
      backend,
      ["--set-config-value", "/main/actions/eosremoterelease=Press Half"],
      halfPressStart,
    );
    const halfRelease = commandIndex(
      backend,
      ["--set-config-value", "/main/actions/eosremoterelease=Release Half"],
      halfPressStart,
    );
    assert.ok(halfPress >= halfPressStart && halfRelease > halfPress);

    await page.waitForSelector(".settings-command");
    assert.match(await page.locator(".settings-command").innerText(), /Camera date and time/);
    await page.click(".settings-command button");
    await page.waitForFunction(() => (
      document.querySelector(".settings-command small")?.textContent.includes("Verified at")
    ));

    await page.selectOption("#preview-input-select", "LOCAL_VIDEO");
    await page.waitForSelector("#local-video-device-row:not([hidden])");
    assert.equal(await page.locator("#local-video-device-select option").allTextContents().then((items) => items.includes("Test HDMI capture")), true);
    await page.selectOption("#local-video-device-select", "test-capture-card");
    await page.click("#live-toggle-button");
    await page.waitForFunction(() => {
      const video = document.querySelector("#local-video");
      return !video.hidden && video.srcObject && video.videoWidth === 1280 && video.videoHeight === 720;
    });
    try {
      await page.waitForFunction(
        () => document.querySelector("#frame-indicator").textContent !== "-- FPS",
        null,
        { timeout: 5_000 },
      );
    } catch (error) {
      const frameDebug = await page.evaluate(() => ({
        callbacks: globalThis.__openEosLocalVideoTest.deliveredVideoFrameCallbacks,
        indicator: document.querySelector("#frame-indicator").textContent,
      }));
      throw new Error(
        `Local video FPS did not update: ${JSON.stringify(frameDebug)}; ` +
        `pageErrors=${JSON.stringify(pageErrors)}; ${error.message}`,
      );
    }
    assert.equal(await page.locator("#preview-source-indicator").innerText(), "UVC");
    assert.match(await page.locator("#frame-indicator").innerText(), /^\d+\.\d FPS$/);
    const mediaState = await page.evaluate(() => ({
      calls: globalThis.__openEosLocalVideoTest.getUserMediaCalls,
      constraints: globalThis.__openEosLocalVideoTest.lastConstraints,
      trackState: globalThis.__openEosLocalVideoTest.currentTrack.readyState,
    }));
    assert.equal(mediaState.calls, 1);
    assert.equal(mediaState.constraints.audio, false);
    assert.deepEqual(mediaState.constraints.video.deviceId, { exact: "test-capture-card" });
    assert.equal(mediaState.trackState, "live");

    await page.click("#monitoring-button");
    await page.waitForSelector("#monitoring-dialog[open]");
    await page.check("#monitor-histogram-toggle");
    await page.click("#monitoring-dialog-close");
    await page.waitForFunction(() => {
      const histogram = document.querySelector("#monitor-histogram");
      return !histogram.hidden && histogram.width > 0 && histogram.height > 0;
    });
    await page.click("#monitoring-button");
    await page.waitForSelector("#monitoring-dialog[open]");
    await page.check("#monitor-waveform-toggle");
    await page.click("#monitoring-dialog-close");
    await page.waitForFunction(() => {
      const histogram = document.querySelector("#monitor-histogram");
      const waveform = document.querySelector("#monitor-waveform");
      return histogram.hidden && !waveform.hidden && waveform.width > 0 && waveform.height > 0;
    });
    await page.click("#monitoring-button");
    await page.waitForSelector("#monitoring-dialog[open]");
    await page.setInputFiles("#monitor-lut-file", {
      name: "browser-private-name.cube",
      mimeType: "text/plain",
      buffer: Buffer.from([
        'TITLE "Browser Invert"',
        "LUT_3D_SIZE 2",
        "1 1 1", "0 1 1", "1 0 1", "0 0 1",
        "1 1 0", "0 1 0", "1 0 0", "0 0 0",
      ].join("\n")),
    });
    await page.waitForFunction(() => {
      const canvas = document.querySelector("#monitor-lut-preview");
      return !canvas.hidden && canvas.width > 0 && canvas.height > 0;
    });
    assert.match(await page.locator("#monitor-lut-summary").innerText(), /Browser Invert/);
    const lutPixel = await page.evaluate(() => {
      const canvas = document.querySelector("#monitor-lut-preview");
      const gl = canvas.getContext("webgl2");
      const pixel = new Uint8Array(4);
      gl.drawArrays(gl.TRIANGLES, 0, 6);
      gl.readPixels(
        Math.floor(canvas.width / 2),
        Math.floor(canvas.height / 2),
        1,
        1,
        gl.RGBA,
        gl.UNSIGNED_BYTE,
        pixel,
      );
      return Array.from(pixel);
    });
    const expectedInvertedBackgrounds = [[238, 235, 233], [223, 59, 52]];
    assert.ok(expectedInvertedBackgrounds.some((expected) => expected.every((value, index) =>
      Math.abs(lutPixel[index] - value) <= 16)), `Unexpected post-LUT pixel: ${lutPixel}`);
    await page.click("#monitoring-dialog-close");

    fs.mkdirSync(RESULTS_DIR, { recursive: true });
    await page.screenshot({ path: path.join(RESULTS_DIR, "local-video-desktop.png") });
    await page.setViewportSize({ width: 720, height: 900 });
    await page.waitForFunction(() => {
      const viewfinder = document.querySelector("#viewfinder").getBoundingClientRect();
      const video = document.querySelector("#local-video").getBoundingClientRect();
      return video.left >= viewfinder.left && video.top >= viewfinder.top &&
        video.right <= viewfinder.right && video.bottom <= viewfinder.bottom;
    });
    const narrowLayout = await page.evaluate(() => {
      const viewfinder = document.querySelector("#viewfinder").getBoundingClientRect();
      const video = document.querySelector("#local-video").getBoundingClientRect();
      const lutPreview = document.querySelector("#monitor-lut-preview").getBoundingClientRect();
      const clockCommand = document.querySelector(".settings-command").getBoundingClientRect();
      const clockButton = document.querySelector(".settings-command button").getBoundingClientRect();
      return {
        noPageOverflow: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
        videoInsideViewfinder: video.left >= viewfinder.left && video.top >= viewfinder.top &&
          video.right <= viewfinder.right && video.bottom <= viewfinder.bottom,
        lutInsideViewfinder: lutPreview.left >= viewfinder.left && lutPreview.top >= viewfinder.top &&
          lutPreview.right <= viewfinder.right && lutPreview.bottom <= viewfinder.bottom,
        clockCommandFits: clockCommand.width <= document.querySelector("#advanced-settings").clientWidth + 1,
        clockButtonTarget: clockButton.width >= 48 && clockButton.height >= 48,
      };
    });
    assert.deepEqual(narrowLayout, {
      noPageOverflow: true,
      videoInsideViewfinder: true,
      lutInsideViewfinder: true,
      clockCommandFits: true,
      clockButtonTarget: true,
    });
    await page.screenshot({ path: path.join(RESULTS_DIR, "local-video-narrow.png"), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 900 });

    await page.selectOption("#local-video-device-select", "test-usb-video");
    await page.waitForFunction(() => (
      globalThis.__openEosLocalVideoTest.getUserMediaCalls === 2 &&
      globalThis.__openEosLocalVideoTest.stoppedTracks === 1 &&
      !document.querySelector("#local-video").hidden
    ));
    assert.deepEqual(
      await page.evaluate(() => globalThis.__openEosLocalVideoTest.lastConstraints.video.deviceId),
      { exact: "test-usb-video" },
    );

    await page.click("#shutter-button");
    await page.waitForFunction(() => document.querySelector("#operation-state").textContent === "Photo captured");
    assert.equal(await page.locator("#local-video").isVisible(), true);
    await waitForBridgeState(
      origin,
      (candidate) => hasCommandContaining(candidate, "--capture-image-and-download"),
      "still capture to reach the host capture command",
    );

    await page.click('.tab[data-view="diagnostics"]');
    await page.waitForSelector("#diagnostics-panel:not([hidden])");
    const reportText = await page.locator("#diagnostics-output").innerText();
    const report = JSON.parse(reportText);
    assert.equal(report.liveView.previewInput, "LOCAL_VIDEO");
    assert.equal(report.liveView.localVideo.active, true);
    assert.equal(report.liveView.localVideo.deviceCount, 2);
    assert.equal(report.liveView.localVideo.selection, "explicit");
    assert.equal(report.liveView.localVideo.settings.width, 1280);
    assert.equal(report.liveView.monitoring.analysisError, null);
    assert.equal(report.liveView.monitoring.histogramVisible, false);
    assert.equal(report.liveView.monitoring.waveformVisible, true);
    assert.deepEqual(report.liveView.monitoring.lut, { loaded: true, size: 2 });
    assert.equal(reportText.includes("browser-private-name"), false);
    assert.equal(reportText.includes("Browser Invert"), false);
    assert.equal(reportText.includes("private-test-device-id"), false);
    assert.equal(reportText.includes("private-test-group-id"), false);
    assert.equal(reportText.includes("Test HDMI capture"), false);
    assert.equal(reportText.includes("Test USB video"), false);
    const stillConfirmation = page.locator(
      '#physical-validation-list input[data-feature="STILL_CAPTURE"]',
    );
    await stillConfirmation.waitFor({ state: "visible" });
    assert.equal(await stillConfirmation.isChecked(), false);
    await stillConfirmation.check();
    assert.equal(await stillConfirmation.isChecked(), true);
    assert.match(
      await page.locator("#physical-validation-list").innerText(),
      /STILL_CAPTURE.*Confirmed on camera/s,
    );
    assert.equal(await page.locator("#copy-physical-validation-button").isEnabled(), true);
    await page.click("#copy-physical-validation-button");
    await page.waitForFunction(async () => (
      (await navigator.clipboard.readText()).startsWith("# Open EOS Control physical camera validation")
    ));
    const visibleDiagnostic = await page.locator("#diagnostics-output").textContent();
    const copiedValidation = await page.evaluate(() => navigator.clipboard.readText());
    const visibleHash = createHash("sha256").update(visibleDiagnostic).digest("hex");
    assert.ok(copiedValidation.includes(`Diagnostic SHA-256: \`${visibleHash}\``));

    await page.click('.tab[data-view="media"]');
    await page.waitForSelector("#media-panel:not([hidden])");
    const jpegMedia = page.locator(".media-row").filter({ hasText: "IMG_0001.JPG" });
    await jpegMedia.waitFor({ state: "visible" });
    await jpegMedia.locator("button.media-thumbnail").click();
    await page.waitForSelector("#media-preview-dialog[open] #media-preview-image:not([hidden])");
    await page.click("#media-preview-close");
    await waitForBridgeState(
      origin,
      (candidate) => commandIndex(candidate, [
        "--folder",
        "/store_00010001/DCIM/100CANON",
        "--get-file",
        "IMG_0001.JPG",
        "--stdout",
      ]) >= 0,
      "media preview to request the camera JPEG",
    );
    page.once("dialog", (dialog) => dialog.accept());
    await jpegMedia.locator('button[aria-label="Delete IMG_0001.JPG"]').click();
    await page.waitForFunction(() => (
      !Array.from(document.querySelectorAll(".media-row"))
        .some((row) => row.textContent.includes("IMG_0001.JPG"))
    ));
    await waitForBridgeState(
      origin,
      (candidate) => commandIndex(candidate, [
        "--folder",
        "/store_00010001/DCIM/100CANON",
        "--delete-file",
        "IMG_0001.JPG",
      ]) >= 0,
      "confirmed media deletion to reach GPhoto2Engine",
    );

    await page.click('.tab[data-view="live"]');
    await page.selectOption("#preview-input-select", "CAMERA");
    await page.waitForFunction(() => globalThis.__openEosLocalVideoTest.stoppedTracks === 2);
    assert.equal(await page.locator("#local-video").isVisible(), false);
    assert.equal(await page.locator("#preview-source-indicator").innerText(), "CAM");
    try {
      await page.waitForFunction(() => (
        document.querySelector("#live-toggle-button").getAttribute("aria-label") === "Stop Live View" &&
        !document.querySelector("#preview-input-select").disabled
      ), null, { timeout: 10_000 });
    } catch (error) {
      const transitionDebug = await page.evaluate(() => ({
        action: document.querySelector("#live-toggle-button").getAttribute("aria-label"),
        input: document.querySelector("#preview-input-select").value,
        inputDisabled: document.querySelector("#preview-input-select").disabled,
        operation: document.querySelector("#operation-state").textContent,
      }));
      throw new Error(
        `Camera preview did not become active: ${JSON.stringify(transitionDebug)}; ` +
        `pageErrors=${JSON.stringify(pageErrors)}; ${error.message}`,
      );
    }

    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/actions/viewfinder"] === "1" &&
        hasCommandContaining(candidate, "--capture-movie"),
      "camera Live View to enable the viewfinder and movie stream",
    );

    await page.waitForFunction(() => {
      const large = document.querySelector('#focus-step-control button[data-step="LARGE"]');
      const near = document.querySelector("#focus-near-button");
      return large && near && !large.disabled && !near.disabled;
    });
    await page.click('#focus-step-control button[data-step="LARGE"]');
    await page.click("#focus-near-button");
    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/actions/manualfocusdrive"] === "Near 3" &&
        commandIndex(candidate, ["--set-config-value", "/main/actions/manualfocusdrive=Near 3"]) >= 0,
      "large near focus drive to reach GPhoto2Engine",
    );

    await page.click("#live-magnification-button");
    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/actions/eoszoom"] === "5" &&
        commandIndex(candidate, ["--set-config-value", "/main/actions/eoszoom=5"]) >= 0,
      "5x Live View magnification to reach GPhoto2Engine",
    );

    await page.click("#video-mode-button");
    await page.click("#shutter-button");
    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/settings/movierecordtarget"] === "Card",
      "video recording start to select the camera card",
    );
    await page.click("#shutter-button");
    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/settings/movierecordtarget"] === "None" &&
        commandIndex(candidate, ["--set-config-value", "/main/settings/movierecordtarget=None"]) >= 0,
      "video recording stop to release the camera recorder",
    );
    await page.click("#photo-mode-button");

    await page.selectOption('#advanced-settings select[data-setting-key="shootingmode"]', "Bulb");
    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/capturesettings/autoexposuremode"] === "Bulb",
      "the advertised Bulb shooting mode selection",
    );
    backend = await readBridgeState(origin);
    const bulbStart = backend.commands.length;
    await page.click("#shutter-button");
    await waitForBridgeState(
      origin,
      (candidate) => commandIndex(
        candidate,
        ["--set-config-value", "/main/actions/eosremoterelease=Press Full"],
        bulbStart,
      ) >= 0,
      "Bulb press-full command",
    );
    await page.click("#shutter-button");
    backend = await waitForBridgeState(
      origin,
      (candidate) => commandIndex(
        candidate,
        ["--set-config-value", "/main/actions/eosremoterelease=Release Full"],
        bulbStart,
      ) >= 0,
      "Bulb release-full command",
    );
    const bulbPress = commandIndex(
      backend,
      ["--set-config-value", "/main/actions/eosremoterelease=Press Full"],
      bulbStart,
    );
    const bulbRelease = commandIndex(
      backend,
      ["--set-config-value", "/main/actions/eosremoterelease=Release Full"],
      bulbStart,
    );
    assert.ok(bulbPress >= bulbStart && bulbRelease > bulbPress);
    await page.selectOption('#advanced-settings select[data-setting-key="shootingmode"]', "Manual");
    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/capturesettings/autoexposuremode"] === "Manual",
      "the camera shooting mode to return to Manual",
    );

    let audioRequests = 0;
    await page.route("**/v1/session/*/status", async (route) => {
      const response = await route.fetch();
      const status = await response.json();
      status.raw = {
        ...status.raw,
        rtpAudio: {
          advertised: true,
          available: true,
          active: true,
          codec: "MP4A-LATM",
          sampleRate: 48000,
          channels: 2,
          generation: 0,
          reason: null,
          lastError: null,
        },
      };
      await route.fulfill({ response, json: status });
    });
    await page.route("**/v1/session/*/capabilities", async (route) => {
      const response = await route.fetch();
      const capabilities = await response.json();
      capabilities.liveView.sources = ["CCAPI_RTP", "DESKTOP_BRIDGE_STREAM"];
      capabilities.liveView.defaultSource = "CCAPI_RTP";
      await route.fulfill({ response, json: capabilities });
    });
    await page.route("**/v1/session/*/liveview/start", async (route) => {
      const request = route.request();
      const payload = request.postDataJSON();
      const response = await route.fetch({
        postData: JSON.stringify({ ...payload, source: "DESKTOP_BRIDGE_STREAM" }),
      });
      const state = await response.json();
      await route.fulfill({ response, json: { ...state, source: "CCAPI_RTP" } });
    });
    await page.route("**/v1/session/*/liveview/audio?*", async (route) => {
      audioRequests += 1;
      const after = Number(new URL(route.request().url()).searchParams.get("after") || 0);
      if (after >= 3) {
        await new Promise((resolve) => setTimeout(resolve, 50));
        await route.fulfill({ status: 204 });
        return;
      }
      const generation = after + 1;
      await route.fulfill({
        status: 200,
        contentType: "audio/pcm;rate=48000;channels=2;format=s16le",
        headers: {
          "X-Open-EOS-Audio-Generation": String(generation),
          "X-Open-EOS-Audio-Sample-Rate": "48000",
          "X-Open-EOS-Audio-Channels": "2",
          "X-Open-EOS-Audio-Frames": "1",
          "X-Open-EOS-Audio-Discontinuity": "0",
        },
        body: Buffer.from([0, 0, 0, 0]),
      });
    });
    await page.click("#rail-live-button");
    await page.waitForFunction(() => document.querySelector("#live-toggle-button").getAttribute("aria-label") === "Start Live View");
    await page.click("#refresh-button");
    await page.waitForFunction(() => (
      Array.from(document.querySelector("#live-source-select").options).some((option) => option.value === "CCAPI_RTP")
    ));
    await page.selectOption("#live-source-select", "CCAPI_RTP");
    await page.click("#rail-live-button");
    await page.waitForSelector("#rtp-audio-button:not([hidden]):not([disabled])");
    assert.equal(await page.locator("#rtp-audio-button").getAttribute("aria-pressed"), "false");
    assert.equal(audioRequests, 0);
    await page.click("#rtp-audio-button");
    await page.waitForFunction(() => globalThis.__openEosLocalVideoTest.audioStarts >= 1);
    assert.equal(await page.locator("#rtp-audio-button").getAttribute("aria-pressed"), "true");
    assert.deepEqual(await page.evaluate(() => globalThis.__openEosLocalVideoTest.audioBuffer), {
      channels: 2,
      frames: 1,
      sampleRate: 48000,
    });
    assert.deepEqual(await page.evaluate(() => globalThis.__openEosLocalVideoTest.audioContextOptions), {
      latencyHint: "interactive",
      sampleRate: 48000,
    });
    await page.click("#rtp-audio-button");
    await page.waitForFunction(() => globalThis.__openEosLocalVideoTest.audioCloses === 1);
    assert.equal(await page.locator("#rtp-audio-button").getAttribute("aria-pressed"), "false");

    await page.selectOption("#preview-input-select", "LOCAL_VIDEO");
    await page.waitForFunction(() => !document.querySelector("#local-video").hidden);
    await page.evaluate(() => globalThis.__openEosLocalVideoTest.currentTrack.dispatchEvent(new Event("ended")));
    await page.waitForFunction(() => document.querySelector("#operation-state").textContent === "The local video input was disconnected");
    assert.equal(await page.locator("#local-video").isVisible(), false);
    assert.equal(await page.evaluate(() => globalThis.__openEosLocalVideoTest.stoppedTracks), 3);

    await page.evaluate(() => { globalThis.__openEosLocalVideoTest.rejectNextPlay = true; });
    await page.click("#live-toggle-button");
    await page.waitForFunction(() => (
      document.querySelector("#operation-state").textContent ===
      "The browser could not start local video playback."
    ));
    assert.equal(await page.evaluate(() => document.querySelector("#local-video").srcObject), null);
    assert.equal(await page.evaluate(() => globalThis.__openEosLocalVideoTest.stoppedTracks), 4);

    await page.evaluate(() => { globalThis.__openEosLocalVideoTest.delayNextGetUserMedia = true; });
    await page.click("#live-toggle-button");
    await page.waitForFunction(() => globalThis.__openEosLocalVideoTest.pendingGetUserMedia === true);
    await page.click("#disconnect-button");
    await page.waitForSelector("#connection-view:not([hidden])");
    await page.evaluate(() => globalThis.__openEosLocalVideoTest.releasePendingGetUserMedia());
    await page.waitForFunction(() => globalThis.__openEosLocalVideoTest.stoppedTracks === 5);
    assert.equal(await page.evaluate(() => document.querySelector("#local-video").srcObject), null);
    await waitForBridgeState(
      origin,
      (candidate) => candidate.values["/main/actions/viewfinder"] === "0" &&
        candidate.movieStreams.length >= 1 && candidate.movieStreams.every((stream) => stream.closed),
      "disconnect cleanup to close every camera movie stream",
    );
    assert.deepEqual(pageErrors, []);
    await context.close();
  } finally {
    if (browser) await browser.close();
    await stopProcess(server);
    fs.rmSync(captureDirectory, { recursive: true, force: true });
  }
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
