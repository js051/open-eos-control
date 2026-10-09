"use strict";

// Exercise the production media-card, details and preview-summary renderers.
// Only DOM mechanics, unrelated session widgets and external effects are stubbed.
// Wire sizeBytes=0 is ambiguous (unknown in path-only CCAPI/gphoto listings), so
// these surfaces must wait for positive numeric metadata before claiming a size.
// This is a synthetic renderer contract, not browser, transport or camera proof.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const mediaLibrary = require("../open_eos_bridge/static/media-library.js");
const source = fs.readFileSync(path.join(__dirname, "../open_eos_bridge/static/app.js"), "utf8");

function productionFunction(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, "m"));
  assert.notEqual(start, -1, `Missing production function ${name}`);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  assert.notEqual(end, -1, `Cannot locate end of production function ${name}`);
  return source.slice(start, start + 1 + end);
}

function element(tagName = "div") {
  const attributes = new Map();
  return {
    tagName, children: [], dataset: {}, className: "", textContent: "", hidden: false,
    disabled: false, open: false, value: "",
    classList: { toggle() {} },
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = [...children]; },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    removeAttribute(name) { attributes.delete(name); },
    getAttribute(name) { return attributes.get(name) ?? null; },
    addEventListener() {},
    querySelectorAll() { return []; },
  };
}

function media(overrides = {}) {
  return {
    id: "synthetic-photo", name: "SYNTHETIC.JPG", kind: "jpeg",
    contentType: "image/jpeg", previewAvailable: true,
    widthPixels: 6000, heightPixels: 4000,
    ...overrides,
  };
}

function context(item = media()) {
  const nodes = {};
  const ui = new Proxy(nodes, { get(target, key) {
    if (!target[key]) target[key] = element();
    return target[key];
  } });
  ui.mediaDetailsDialog.open = true;
  const state = {
    session: { id: "synthetic-session" }, capabilities: {}, status: { media: {} },
    captureMode: "photo", busy: false,
    media: [item], mediaLoaded: true, mediaLoadStatus: "COMPLETE", mediaHasMore: false,
    mediaSort: "camera", mediaScope: "all", mediaFilter: "all", mediaPage: 0,
    mediaThumbnailUrls: new Map(), mediaThumbnailLoads: new Set(),
    mediaDetailsItem: item, mediaDetailsBusy: false, mediaPreviewItem: item,
    latestMediaItem: null, mediaDownloadPreparing: false, mediaDownloadOwner: null,
    mediaDownload: null, mediaUpload: null,
  };
  const effects = [];
  const unexpected = (name) => (...args) => {
    effects.push({ name, args });
    assert.fail(`Rendering must not initiate ${name}`);
  };
  const supported = new Set(["MEDIA_BROWSER", "MEDIA_DOWNLOAD", "MEDIA_PREVIEW"]);
  const sandbox = vm.createContext({
    state, ui, mediaLibrary, effects, Map, Set,
    MEDIA_PAGE_SIZE: 24, mediaThumbnailObserver: null,
    FEATURES: new Proxy({}, { get: (_, key) => key }),
    featureSupported: (feature) => supported.has(feature),
    resolvedLanguage: () => "en",
    t: (key, values = {}) => {
      if (key === "mediaPreviewPosition") return `${values.position} / ${values.total}`;
      if (key === "mediaCount") return `${values.count} files`;
      if (key === "downloadProgressUnknown") return `${values.transferred} downloaded`;
      if (key === "downloadProgress" || key === "uploadProgress") {
        return `${values.transferred} / ${values.total} (${values.percent}%)`;
      }
      return key;
    },
    document: { createElement: element }, window: {},
    api: unexpected("API request"), fetch: unexpected("fetch"),
    downloadMedia: unexpected("download"), chooseMediaWritable: unexpected("destination picker"),
    cancelMediaDownload: unexpected("download cancellation"),
    observeMediaThumbnail: unexpected("thumbnail request"),
  });
  // Session widgets do not participate in the storage-size formatting contract.
  for (const name of ["renderCaptureMode", "renderExposure", "renderAdvancedSettings",
    "renderPreviewInput", "renderLiveSource", "renderFps", "renderTapAction",
    "renderTemperatureStatus", "syncBulbTimer", "renderAvailability", "renderDiagnostics"]) {
    sandbox[name] = () => {};
  }
  const names = [
    "formatBytes", "formatDate", "formatMediaDimensions", "formatMediaContentType",
    "mediaIsVideo", "mediaTime", "displayedMedia", "previewableMedia", "mediaDateGroup",
    "formatMediaTime", "renderMediaThumbnail", "mediaMetadataSupported", "mediaManagementSupported",
    "mediaTransferActive", "cameraInteractionBusy", "replaceMediaItem",
    "renderMedia", "renderMediaSummary", "renderMediaDetails", "renderMediaPreviewNavigation",
    "renderMediaTransfer", "renderSession",
  ];
  // The unchanged baseline must fail on its rendered claims, not a missing import.
  // Once present, load the production helper without supplying a test substitute.
  if (/^  function formatMediaSize\(/m.test(source)) names.push("formatMediaSize");
  vm.runInContext(names.map(productionFunction).join("\n"), sandbox);
  return sandbox;
}

const surfaces = [
  {
    name: "media card", render: (subject) => subject.renderMedia(),
    text(subject) {
      const cards = subject.ui.mediaList.children.filter((child) => child.className === "media-card");
      assert.equal(cards.length, 1);
      return cards[0].children.find((child) => child.className === "media-size").textContent;
    },
    expected: (size) => ["6000 x 4000", size].filter(Boolean).join(" · "),
  },
  {
    name: "media details", render: (subject) => subject.renderMediaDetails(),
    text: (subject) => subject.ui.mediaDetailsSummary.textContent,
    expected: (size) => ["6000 x 4000", size, "image/jpeg"].filter(Boolean).join(" · "),
  },
  {
    name: "media preview", render: (subject) => subject.renderMediaPreviewNavigation(),
    text: (subject) => subject.ui.mediaPreviewMeta.textContent,
    expected: (size) => ["1 / 1", "6000 x 4000", size].filter(Boolean).join(" · "),
  },
];

const unknownSizes = [
  ["wire zero sentinel", { sizeBytes: 0 }],
  ["null", { sizeBytes: null }],
  ["missing field", {}],
  ["undefined", { sizeBytes: undefined }],
  ["negative", { sizeBytes: -1 }],
  ["NaN", { sizeBytes: NaN }],
  ["positive infinity", { sizeBytes: Infinity }],
  ["negative infinity", { sizeBytes: -Infinity }],
  ["empty string", { sizeBytes: "" }],
  ["whitespace string", { sizeBytes: " " }],
  ["nonnumeric string", { sizeBytes: "unknown" }],
  ["numeric string", { sizeBytes: "1024" }],
  ["false", { sizeBytes: false }],
  ["true", { sizeBytes: true }],
  ["array", { sizeBytes: [1024] }],
  ["object", { sizeBytes: {} }],
];

for (const surface of surfaces) {
  for (const [label, overrides] of unknownSizes) {
    test(`${surface.name} omits unproven size: ${label}`, () => {
      const subject = context(media(overrides));
      surface.render(subject);
      assert.equal(surface.text(subject), surface.expected(""),
        "Keep independent dimensions/type/position without a false size or dangling separator");
      assert.deepEqual(subject.effects, []);
    });
  }

  for (const [sizeBytes, expected] of [[1, "1 B"], [1024, "1.0 KB"],
    [1048576, "1.0 MB"], [12582912, "12 MB"], [1073741824, "1.0 GB"]]) {
    test(`${surface.name} retains known positive size ${sizeBytes}`, () => {
      const subject = context(media({ sizeBytes }));
      surface.render(subject);
      assert.equal(surface.text(subject), surface.expected(expected));
    });
  }

  test(`${surface.name} rerender removes a previous known size for an unknown replacement`, () => {
    const subject = context(media({ sizeBytes: 1048576 }));
    surface.render(subject);
    assert.equal(surface.text(subject), surface.expected("1.0 MB"));
    subject.replaceMediaItem(media({ sizeBytes: 0 }));
    // Preview selection owns a separate item reference. Select the replacement as
    // the existing navigation does; this test does not claim a metadata-fetch flow.
    subject.state.mediaPreviewItem = subject.state.media[0];
    surface.render(subject);
    assert.equal(surface.text(subject), surface.expected(""));
  });

  test(`${surface.name} rerender adds size when replacement metadata becomes known`, () => {
    const subject = context(media({ sizeBytes: 0 }));
    surface.render(subject);
    assert.equal(surface.text(subject), surface.expected(""));
    subject.replaceMediaItem(media({ sizeBytes: 1048576 }));
    subject.state.mediaPreviewItem = subject.state.media[0];
    surface.render(subject);
    assert.equal(surface.text(subject), surface.expected("1.0 MB"));
  });
}

test("unknown size without dimensions leaves no placeholder or dangling separators", () => {
  const subject = context(media({ sizeBytes: 0, widthPixels: null, heightPixels: null }));
  for (const surface of surfaces) surface.render(subject);
  assert.equal(surfaces[0].text(subject), "");
  assert.equal(surfaces[1].text(subject), "image/jpeg");
  assert.equal(surfaces[2].text(subject), "1 / 1");
  assert.equal(subject.ui.mediaSummary.textContent, "1 files");
});

for (const [kind, name, contentType] of [["raw", "SYNTHETIC.CR3", "image/x-canon-cr3"],
  ["video", "SYNTHETIC.MP4", "video/mp4"]]) {
  test(`${kind} surfaces also omit ambiguous zero metadata`, () => {
    const subject = context(media({ kind, name, contentType, sizeBytes: 0 }));
    for (const surface of surfaces) surface.render(subject);
    assert.equal(surfaces[0].text(subject), "6000 x 4000");
    assert.equal(surfaces[1].text(subject), `6000 x 4000 · ${contentType}`);
    assert.equal(surfaces[2].text(subject), "1 / 1 · 6000 x 4000");
  });
}

test("generic byte formatter retains real zero and existing positive formatting", () => {
  const subject = context();
  assert.equal(subject.formatBytes(0), "0 B");
  assert.equal(subject.formatBytes(1024), "1.0 KB");
  assert.equal(subject.formatBytes(-1), "-");
});

for (const totalBytes of [null, 1048576]) {
  test(`download progress preserves zero transferred with ${totalBytes ? "known" : "unknown"} total`, () => {
    const subject = context();
    subject.state.mediaDownload = { name: "SYNTHETIC.JPG", bytesTransferred: 0, totalBytes, cancelling: false };
    subject.renderMediaTransfer();
    assert.equal(subject.ui.mediaTransferStatus.textContent,
      totalBytes ? "0 B / 1.0 MB (0%)" : "0 B downloaded");
  });
}

test("upload progress preserves zero transferred", () => {
  const subject = context();
  subject.state.mediaUpload = { name: "SYNTHETIC.JPG", bytesTransferred: 0, totalBytes: 1048576, cancelling: false };
  subject.renderMediaTransfer();
  assert.equal(subject.ui.mediaTransferStatus.textContent, "0 B / 1.0 MB (0%)");
});

test("session storage preserves known zero free bytes", () => {
  const subject = context();
  subject.state.status.media.freeBytes = 0;
  subject.renderSession();
  assert.equal(subject.ui.storageValue.textContent, "0 B");
});

test("media size rerenders make no requests and preserve active download ownership", () => {
  const subject = context(media({ sizeBytes: 0 }));
  const owner = Object.freeze({ session: subject.state.session, cancelled: false });
  const download = Object.freeze({ name: "SYNTHETIC.JPG", bytesTransferred: 0, totalBytes: null, cancelling: false });
  subject.state.mediaDownloadOwner = owner;
  subject.state.mediaDownload = download;
  subject.state.mediaDownloadPreparing = true;
  for (const sizeBytes of [0, 1048576, null]) {
    subject.replaceMediaItem(media({ sizeBytes }));
    subject.state.mediaPreviewItem = subject.state.media[0];
    for (const surface of surfaces) surface.render(subject);
    assert.equal(subject.state.mediaDownloadOwner, owner);
    assert.equal(subject.state.mediaDownload, download);
    assert.equal(subject.state.mediaDownloadPreparing, true);
    assert.equal(subject.state.mediaUpload, null);
    assert.equal(subject.ui.mediaPreviewDownload.disabled, true);
    assert.equal(subject.ui.mediaDetailsDownload.disabled, true);
  }
  assert.deepEqual(subject.effects, []);
});
