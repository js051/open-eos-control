'use strict';
// Production app ownership/renderer orchestration under controlled JS collaborators.
// This is not browser, GPU, compositor, or physical-camera evidence.
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const lifecycle = require('../open_eos_bridge/static/liveview-lifecycle.js');
const source = fs.readFileSync(process.env.EOS_APP_SOURCE || path.join(__dirname, '../open_eos_bridge/static/app.js'), 'utf8');
function production(name) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.ok(start >= 0, name);
  const end = source.slice(start + 1).search(/\n  (?:async )?function /);
  return source.slice(start, start + 1 + end);
}
const vectors = require('../../contracts/rtp-presentation/v1/monitor-vectors.json');
for (const item of vectors.cases) test(`monitor contract: ${item.name}`, () =>
  assert.deepEqual(lifecycle.evaluateRtpMonitorEvidence(item.input), item.expected));

function setup({lut = true, analysis = true} = {}) {
  const media = {naturalWidth: 2, naturalHeight: 2}, owner = {confirmedStart: true}, session = {};
  const sample = {image: media, owner, session, generation: 1, frame: 7, decodedAt: 100};
  const state = {liveActive: true, liveDecodedImage: media, liveFrameSample: sample, liveViewStopOwner: owner,
    session, liveGeneration: 1, monitorLutRevision: 3, monitorSettings: {cubeLut: lut ? {} : null}};
  const ui = {liveImage: media, monitorLutPreview: {hidden: true}, monitorPixelOverlay: {hidden: false},
    monitorHistogram: {hidden: false}, monitorWaveform: {hidden: false}, monitorGuidesOverlay: {hidden: false}};
  const calls = [], clock = {now: 150}, behavior = {failure: false, afterAnalysis: null};
  const canvas = {getContext: () => ({drawImage: image => calls.push({analysisSource: image}),
    getImageData: () => ({data: new Uint8ClampedArray(16)})})};
  let creations = 0;
  const ctx = vm.createContext({state, ui, performance: {now: () => clock.now}, liveViewLifecycle: lifecycle,
    activePreviewElement: () => ui.liveImage, previewDimensions: () => ({width: 2, height: 2}),
    applyLiveViewLayout: () => ({}), monitorNeedsPixelAnalysis: () => analysis, monitorAnalysisCanvas: canvas,
    lutPreviewRenderer: null, lut: {createWebGLRenderer: () => {creations++; return {
      render: image => {calls.push({lutSource: image}); if (behavior.failure) throw Error('synthetic LUT failure');},
      dispose: () => calls.push({disposed: true}),
    };}},
    monitoring: {analysisDimensions: () => ({width: 2, height: 2}), analyzePixels: () => {
      behavior.afterAnalysis?.(); return {histogram: [], waveform: []};
    }}, renderMonitorPixelOverlay: () => {calls.push({overlayPublished: true}); ui.monitorPixelOverlay.hidden = false;},
    renderMonitorHistogram: () => {ui.monitorHistogram.hidden = false;},
    renderMonitorWaveform: () => {ui.monitorWaveform.hidden = false;}, clearCameraFocusFrames() {},
  });
  vm.runInContext(['cameraMonitorPolicy', 'currentMonitorFrameEvidence', 'renderMonitoringFrame', 'renderLutPreview', 'clearMonitoringLayers'].map(production).join('\n'), ctx);
  return {ctx, state, sample, ui, media, calls, clock, behavior, creations: () => creations};
}

test('decoded frame, display object, LUT submission and analysis share the same revision', () => {
  const h = setup(); h.ctx.renderMonitoringFrame();
  assert.equal(h.calls.find(v => v.lutSource).lutSource, h.media);
  assert.equal(h.calls.find(v => v.analysisSource).analysisSource, h.ui.monitorLutPreview);
  assert.equal(h.state.monitorFrameEvidence.frameRevision, 7);
  assert.equal(h.state.monitorFrameEvidence.lutRevision, 3);
  assert.equal(h.state.monitorFrameEvidence.sourceEvidence, 'surface-submitted');
  assert.equal(h.state.monitorFrameEvidence.presentationConfirmed, false);
});

test('without a LUT, monitoring reads decoded output and makes no surface/compositor claim', () => {
  const h = setup({lut: false}); h.ctx.renderMonitoringFrame();
  assert.equal(h.calls.find(v => v.analysisSource).analysisSource, h.media);
  assert.equal(h.state.monitorFrameEvidence.sourceEvidence, 'decoded-output');
  assert.equal(h.state.monitorFrameEvidence.presentationConfirmed, false);
});

for (const analysis of [true, false]) test(`selected LUT failure is retained and never falls back to false success (analysis ${analysis})`, () => {
  const h = setup({analysis}); h.behavior.failure = true; h.ctx.renderMonitoringFrame();
  assert.match(h.state.monitorAnalysisError, /synthetic LUT failure/);
  assert.equal(h.state.monitorFrameEvidence, null);
  assert.equal(h.calls.filter(v => v.analysisSource).length, 0);
  assert.equal(h.ui.monitorLutPreview.hidden, true);
  assert.equal(h.ui.monitorPixelOverlay.hidden, true);
  assert.equal(h.ui.monitorHistogram.hidden, true);
  assert.equal(h.ui.monitorWaveform.hidden, true);
  h.behavior.failure = false; h.ctx.renderMonitoringFrame();
  assert.equal(h.creations(), 2);
  assert.equal(h.state.monitorAnalysisError, null);
  assert.equal(h.state.monitorFrameEvidence.frameRevision, 7);
});

for (const reason of ['old-frame', 'new-owner', 'new-renderer', 'paused', 'expired', 'missing-sample']) {
  test(`stale evidence cannot be sampled or publish: ${reason}`, () => {
    const h = setup();
    if (reason === 'old-frame') h.clock.now = 5101;
    if (reason === 'new-owner') h.state.liveViewStopOwner = {confirmedStart: true};
    if (reason === 'new-renderer') h.ui.liveImage = {...h.media};
    if (reason === 'paused') h.state.livePollingSuspended = true;
    if (reason === 'expired') h.sample.expired = true;
    if (reason === 'missing-sample') h.state.liveFrameSample = null;
    h.ctx.renderMonitoringFrame();
    assert.equal(h.calls.length, 0);
    assert.equal(h.state.monitorFrameEvidence, null);
    assert.equal(h.ui.monitorPixelOverlay.hidden, true);
  });
}

for (const reason of ['age', 'frame', 'lut', 'owner']) test(`analysis completion rechecks ${reason} before publication`, () => {
  const h = setup();
  h.behavior.afterAnalysis = () => {
    if (reason === 'age') h.clock.now = 5101;
    if (reason === 'frame') h.state.liveFrameSample = {...h.sample, frame: 8};
    if (reason === 'lut') h.state.monitorLutRevision++;
    if (reason === 'owner') h.state.liveViewStopOwner = {confirmedStart: true};
  };
  h.ctx.renderMonitoringFrame();
  assert.equal(h.calls.filter(v => v.overlayPublished).length, 0);
  assert.equal(h.state.monitorFrameEvidence, null);
  assert.equal(h.ui.monitorPixelOverlay.hidden, true);
  assert.equal(h.ui.monitorHistogram.hidden, true);
  assert.equal(h.ui.monitorWaveform.hidden, true);
});

for (const reason of ['clock', 'owner', 'lut', 'frame', 'stop']) test(`diagnostics reject stale evidence even before expiry timer runs: ${reason}`, () => {
  const h = setup(); h.ctx.renderMonitoringFrame();
  assert.equal(h.ctx.currentMonitorFrameEvidence(), h.state.monitorFrameEvidence);
  if (reason === 'clock') h.clock.now = 5101;
  if (reason === 'owner') h.state.liveViewStopOwner = {confirmedStart: true};
  if (reason === 'lut') h.state.monitorLutRevision++;
  if (reason === 'frame') h.state.liveFrameSample = {...h.sample, frame: 8};
  if (reason === 'stop') h.sample.owner.stopUnconfirmed = true;
  assert.equal(h.ctx.currentMonitorFrameEvidence(), null);
});
