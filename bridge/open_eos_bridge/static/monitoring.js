(function (root, factory) {
  const monitoring = factory();
  if (typeof module === "object" && module.exports) module.exports = monitoring;
  root.OpenEOSMonitoring = monitoring;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MAX_ANALYSIS_WIDTH = 120;
  const MAX_ANALYSIS_HEIGHT = 80;
  const HISTOGRAM_BUCKETS = 64;
  const WAVEFORM_COLUMNS = 64;
  const WAVEFORM_LEVELS = 64;
  const FOCUS_PEAKING_GRADIENT = 72;
  const ZEBRA_LIGHT = [255, 255, 255, 176];
  const ZEBRA_DARK = [0, 0, 0, 120];
  const FOCUS_PEAKING_COLOR = [40, 197, 217, 255];

  function analysisDimensions(width, height) {
    assertDimensions(width, height);
    const scale = Math.min(1, MAX_ANALYSIS_WIDTH / width, MAX_ANALYSIS_HEIGHT / height);
    return {
      width: Math.max(1, Math.round(width * scale)),
      height: Math.max(1, Math.round(height * scale)),
    };
  }

  function fitRect(contentWidth, contentHeight, containerWidth, containerHeight) {
    assertDimensions(contentWidth, contentHeight);
    assertDimensions(containerWidth, containerHeight);
    const scale = Math.min(containerWidth / contentWidth, containerHeight / contentHeight);
    const width = contentWidth * scale;
    const height = contentHeight * scale;
    return {
      left: (containerWidth - width) / 2,
      top: (containerHeight - height) / 2,
      width,
      height,
    };
  }

  function analyzePixels(rgba, width, height, settings = {}) {
    assertDimensions(width, height);
    if (!rgba || rgba.length !== width * height * 4) {
      throw new RangeError("RGBA data must contain exactly width * height * 4 bytes.");
    }
    const zebraThresholdPercent = settings.zebraThresholdPercent ?? null;
    if (zebraThresholdPercent !== null && (zebraThresholdPercent < 50 || zebraThresholdPercent > 100)) {
      throw new RangeError("Zebra threshold must be between 50 and 100 percent.");
    }

    const pixelCount = width * height;
    const luminance = new Uint8Array(pixelCount);
    const histogram = new Uint32Array(HISTOGRAM_BUCKETS);
    const waveform = settings.waveformVisible
      ? new Uint32Array(WAVEFORM_COLUMNS * WAVEFORM_LEVELS)
      : null;
    for (let index = 0; index < pixelCount; index += 1) {
      const offset = index * 4;
      const value = (
        54 * rgba[offset] +
        183 * rgba[offset + 1] +
        19 * rgba[offset + 2]
      ) >> 8;
      luminance[index] = value;
      histogram[Math.min(HISTOGRAM_BUCKETS - 1, Math.floor(value * HISTOGRAM_BUCKETS / 256))] += 1;
      if (waveform) {
        const x = index % width;
        const column = width === 1 ? 0 : Math.floor(x * (WAVEFORM_COLUMNS - 1) / (width - 1));
        const level = Math.min(WAVEFORM_LEVELS - 1, Math.floor(value * WAVEFORM_LEVELS / 256));
        const row = WAVEFORM_LEVELS - 1 - level;
        waveform[row * WAVEFORM_COLUMNS + column] += 1;
      }
    }

    const falseColorEnabled = Boolean(settings.falseColorEnabled);
    const focusPeakingEnabled = Boolean(settings.focusPeakingEnabled);
    const needsOverlay = zebraThresholdPercent !== null || falseColorEnabled || focusPeakingEnabled;
    const overlay = needsOverlay ? new Uint8ClampedArray(pixelCount * 4) : null;
    const zebraLuminance = zebraThresholdPercent === null
      ? null
      : Math.floor(zebraThresholdPercent * 255 / 100);

    if (overlay) {
      for (let y = 0; y < height; y += 1) {
        for (let x = 0; x < width; x += 1) {
          const index = y * width + x;
          if (falseColorEnabled) writeColor(overlay, index, falseColor(luminance[index]));
          if (zebraLuminance !== null && luminance[index] >= zebraLuminance) {
            writeColor(overlay, index, Math.floor((x + y) / 2) % 4 < 2 ? ZEBRA_LIGHT : ZEBRA_DARK);
          }
        }
      }
      if (focusPeakingEnabled && width >= 3 && height >= 3) {
        for (let y = 1; y < height - 1; y += 1) {
          for (let x = 1; x < width - 1; x += 1) {
            const index = y * width + x;
            const gradient = Math.abs(luminance[index + 1] - luminance[index - 1]) +
              Math.abs(luminance[index + width] - luminance[index - width]);
            if (gradient >= FOCUS_PEAKING_GRADIENT) writeColor(overlay, index, FOCUS_PEAKING_COLOR);
          }
        }
      }
    }

    return {
      width,
      height,
      histogram,
      waveform: waveform ? { width: WAVEFORM_COLUMNS, height: WAVEFORM_LEVELS, density: waveform } : null,
      overlay,
    };
  }

  function writeColor(rgba, index, color) {
    const offset = index * 4;
    rgba[offset] = color[0];
    rgba[offset + 1] = color[1];
    rgba[offset + 2] = color[2];
    rgba[offset + 3] = color[3];
  }

  function falseColor(luminance) {
    if (luminance < 16) return [80, 22, 122, 224];
    if (luminance < 40) return [32, 61, 160, 224];
    if (luminance < 75) return [41, 182, 209, 224];
    if (luminance < 140) return [76, 175, 80, 224];
    if (luminance < 190) return [242, 209, 61, 224];
    if (luminance < 235) return [242, 140, 40, 224];
    return [232, 59, 53, 224];
  }

  function assertDimensions(width, height) {
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
      throw new RangeError("Image dimensions must be positive numbers.");
    }
  }

  return { analysisDimensions, fitRect, analyzePixels };
});
