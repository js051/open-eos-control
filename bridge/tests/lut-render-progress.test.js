'use strict';
// Controlled WebGL API collaborator. This verifies failure fencing, not GPU pixels.
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path');
const source = fs.readFileSync(process.env.EOS_LUT_SOURCE || path.join(__dirname, '../open_eos_bridge/static/lut.js'), 'utf8');
function setup() {
  const state = {lost: false, error: 0, afterDraw: null}, calls = [];
  const methods = {NO_ERROR: 0, isContextLost: () => state.lost, getError: () => state.error,
    getShaderParameter: () => true, getProgramParameter: () => true,
    getAttribLocation: () => 0, getUniformLocation: () => ({}),
    createShader: () => ({}), createProgram: () => ({}), createBuffer: () => ({}), createTexture: () => ({}),
    texImage2D: (...args) => calls.push({uploaded: args.at(-1)}),
    drawArrays: () => {calls.push({draw: true}); state.afterDraw?.();},
  };
  const gl = new Proxy(methods, {get: (obj, key) => key in obj ? obj[key] : /^[A-Z0-9_]+$/.test(key) ? 1 : () => {}});
  class Canvas {getContext() {return gl;}}
  const ctx = vm.createContext({HTMLCanvasElement: Canvas, TextEncoder});
  vm.runInContext(source, ctx);
  const renderer = ctx.OpenEOSLut.createWebGLRenderer(new Canvas());
  const image = {naturalWidth: 2, naturalHeight: 2}, cube = {size: 2, values: new Float32Array(24), domainMin: [0,0,0], domainMax: [1,1,1]};
  return {state, calls, renderer, image, cube};
}
test('renderer submits the exact decoded image object without claiming physical presentation', () => {
  const h = setup(); h.renderer.render(h.image, h.cube);
  assert.equal(h.calls[0].uploaded, h.image);
  assert.equal(h.calls[1].draw, true);
});
test('lost context cannot claim a submitted LUT frame', () => {
  const h = setup(); h.state.lost = true;
  assert.throws(() => h.renderer.render(h.image, h.cube), /context is lost/);
  assert.equal(h.calls.length, 0);
});
for (const mode of ['api-error', 'lost-during-draw']) test(`post-draw ${mode} is a failed submission`, () => {
  const h = setup(); h.state.afterDraw = () => {
    if (mode === 'api-error') h.state.error = 1282;
    else h.state.lost = true;
  };
  assert.throws(() => h.renderer.render(h.image, h.cube), /submission failed/);
});
