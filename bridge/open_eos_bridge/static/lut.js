(function (root, factory) {
  const lut = factory();
  if (typeof module === "object" && module.exports) module.exports = lut;
  root.OpenEOSLut = lut;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MIN_CUBE_SIZE = 2;
  const MAX_CUBE_SIZE = 64;
  const MAX_CUBE_BYTES = 16 * 1024 * 1024;
  const MAX_NAME_LENGTH = 120;

  function parseCubeLut(text, fallbackName = "Imported LUT.cube") {
    if (typeof text !== "string") throw new TypeError("3D LUT must be UTF-8 text.");
    if (new TextEncoder().encode(text).byteLength > MAX_CUBE_BYTES) {
      throw new RangeError("3D LUT exceeds the 16 MiB limit.");
    }
    let title = null;
    let size = null;
    let domainMin = [0, 0, 0];
    let domainMax = [1, 1, 1];
    let hasDomainMin = false;
    let hasDomainMax = false;
    const values = [];

    text.split(/\r?\n/).forEach((sourceLine, zeroBasedLine) => {
      const line = sourceLine.split("#", 1)[0].trim();
      if (!line) return;
      const tokens = line.split(/\s+/);
      const directive = tokens[0].toUpperCase();
      const lineNumber = zeroBasedLine + 1;
      if (directive === "TITLE") {
        if (title !== null) throw new Error(`Duplicate TITLE at line ${lineNumber}.`);
        title = line.slice(tokens[0].length).trim().replace(/^"|"$/g, "") || null;
      } else if (directive === "LUT_3D_SIZE") {
        const parsed = Number(tokens[1]);
        if (size !== null || values.length || tokens.length !== 2 || !Number.isInteger(parsed) ||
            parsed < MIN_CUBE_SIZE || parsed > MAX_CUBE_SIZE) {
          throw new RangeError(`3D LUT size must be between ${MIN_CUBE_SIZE} and ${MAX_CUBE_SIZE} (line ${lineNumber}).`);
        }
        size = parsed;
      } else if (directive === "DOMAIN_MIN") {
        if (hasDomainMin || values.length) throw new Error(`Duplicate or late DOMAIN_MIN at line ${lineNumber}.`);
        domainMin = parseVector(tokens, lineNumber);
        hasDomainMin = true;
      } else if (directive === "DOMAIN_MAX") {
        if (hasDomainMax || values.length) throw new Error(`Duplicate or late DOMAIN_MAX at line ${lineNumber}.`);
        domainMax = parseVector(tokens, lineNumber);
        hasDomainMax = true;
      } else if (directive === "LUT_3D_INPUT_RANGE") {
        if (hasDomainMin || hasDomainMax || values.length || tokens.length !== 3) {
          throw new Error(`Duplicate or conflicting input range at line ${lineNumber}.`);
        }
        const minimum = finiteNumber(tokens[1], lineNumber);
        const maximum = finiteNumber(tokens[2], lineNumber);
        domainMin = [minimum, minimum, minimum];
        domainMax = [maximum, maximum, maximum];
        hasDomainMin = true;
        hasDomainMax = true;
      } else if (directive === "LUT_1D_SIZE") {
        throw new Error("1D and shaper LUTs are not supported.");
      } else {
        if (size === null) throw new Error(`LUT_3D_SIZE must appear before table data (line ${lineNumber}).`);
        if (tokens.length !== 3) throw new Error(`Invalid 3D LUT row at line ${lineNumber}.`);
        values.push(...tokens.map((token) => finiteNumber(token, lineNumber)));
        if (values.length > size * size * size * 3) {
          throw new Error("3D LUT contains more rows than LUT_3D_SIZE declares.");
        }
      }
    });

    if (size === null) throw new Error("LUT_3D_SIZE is required.");
    if (!domainMin.every((minimum, index) => Number.isFinite(minimum) && minimum < domainMax[index])) {
      throw new Error("Every LUT domain minimum must be lower than its maximum.");
    }
    const expectedRows = size * size * size;
    if (values.length !== expectedRows * 3) {
      throw new Error(`3D LUT requires ${expectedRows} RGB rows; found ${Math.floor(values.length / 3)}.`);
    }
    const fallback = String(fallbackName).split(/[\\/]/).at(-1).replace(/\.cube$/i, "").trim() || "Imported LUT";
    return Object.freeze({
      name: String(title || fallback).slice(0, MAX_NAME_LENGTH),
      size,
      domainMin: Object.freeze(domainMin),
      domainMax: Object.freeze(domainMax),
      values: new Float32Array(values),
    });
  }

  function sampleCubeLut(lut, red, green, blue) {
    const coordinates = [red, green, blue].map((value, channel) =>
      clamp01((value - lut.domainMin[channel]) / (lut.domainMax[channel] - lut.domainMin[channel])) * (lut.size - 1));
    const lower = coordinates.map(Math.floor);
    const upper = lower.map((value) => Math.min(lut.size - 1, value + 1));
    const amount = coordinates.map((value, channel) => value - lower[channel]);
    return [0, 1, 2].map((channel) => {
      const c00 = lerp(valueAt(lut, lower[0], lower[1], lower[2], channel), valueAt(lut, upper[0], lower[1], lower[2], channel), amount[0]);
      const c10 = lerp(valueAt(lut, lower[0], upper[1], lower[2], channel), valueAt(lut, upper[0], upper[1], lower[2], channel), amount[0]);
      const c01 = lerp(valueAt(lut, lower[0], lower[1], upper[2], channel), valueAt(lut, upper[0], lower[1], upper[2], channel), amount[0]);
      const c11 = lerp(valueAt(lut, lower[0], upper[1], upper[2], channel), valueAt(lut, upper[0], upper[1], upper[2], channel), amount[0]);
      return clamp01(lerp(lerp(c00, c10, amount[1]), lerp(c01, c11, amount[1]), amount[2]));
    });
  }

  function createWebGLRenderer(canvas) {
    if (!(canvas instanceof HTMLCanvasElement)) throw new TypeError("LUT renderer requires a canvas.");
    const gl = canvas.getContext("webgl2", { alpha: false, antialias: false, depth: false });
    if (!gl) throw new Error("WebGL2 3D textures are unavailable in this browser.");
    const program = createProgram(gl, VERTEX_SHADER, FRAGMENT_SHADER);
    const sourceTexture = gl.createTexture();
    const lutTexture = gl.createTexture();
    const vertexBuffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, vertexBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
      -1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1,
      -1, 1, 0, 1, 1, -1, 1, 0, 1, 1, 1, 1,
    ]), gl.STATIC_DRAW);
    const position = gl.getAttribLocation(program, "aPosition");
    const textureCoordinate = gl.getAttribLocation(program, "aTextureCoordinate");
    const sourceUniform = gl.getUniformLocation(program, "uSource");
    const lutUniform = gl.getUniformLocation(program, "uLut");
    const domainMinUniform = gl.getUniformLocation(program, "uDomainMin");
    const domainMaxUniform = gl.getUniformLocation(program, "uDomainMax");
    let activeLut = null;

    function uploadLut(lut) {
      const rgba = new Float32Array(lut.size * lut.size * lut.size * 4);
      for (let source = 0, destination = 0; source < lut.values.length; source += 3, destination += 4) {
        rgba[destination] = lut.values[source];
        rgba[destination + 1] = lut.values[source + 1];
        rgba[destination + 2] = lut.values[source + 2];
        rgba[destination + 3] = 1;
      }
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_3D, lutTexture);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
      gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE);
      gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGBA32F, lut.size, lut.size, lut.size, 0, gl.RGBA, gl.FLOAT, rgba);
      activeLut = lut;
    }

    return {
      render(source, lut) {
        const dimensions = sourceDimensions(source);
        if (!dimensions.width || !dimensions.height) throw new Error("Live View frame dimensions are unavailable.");
        if (activeLut !== lut) uploadLut(lut);
        const scale = Math.min(1, 1920 / dimensions.width, 1080 / dimensions.height);
        canvas.width = Math.max(1, Math.round(dimensions.width * scale));
        canvas.height = Math.max(1, Math.round(dimensions.height * scale));
        gl.viewport(0, 0, canvas.width, canvas.height);
        gl.useProgram(program);
        gl.bindBuffer(gl.ARRAY_BUFFER, vertexBuffer);
        gl.enableVertexAttribArray(position);
        gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 16, 0);
        gl.enableVertexAttribArray(textureCoordinate);
        gl.vertexAttribPointer(textureCoordinate, 2, gl.FLOAT, false, 16, 8);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, sourceTexture);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
        gl.pixelStorei(gl.UNPACK_ALIGNMENT, 4);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
        gl.uniform1i(sourceUniform, 0);
        gl.uniform1i(lutUniform, 1);
        gl.uniform3fv(domainMinUniform, lut.domainMin);
        gl.uniform3fv(domainMaxUniform, lut.domainMax);
        gl.drawArrays(gl.TRIANGLES, 0, 6);
      },
      dispose() {
        gl.deleteTexture(sourceTexture);
        gl.deleteTexture(lutTexture);
        gl.deleteBuffer(vertexBuffer);
        gl.deleteProgram(program);
      },
    };
  }

  function sourceDimensions(source) {
    return {
      width: source.videoWidth || source.naturalWidth || source.width || 0,
      height: source.videoHeight || source.naturalHeight || source.height || 0,
    };
  }

  function createProgram(gl, vertexSource, fragmentSource) {
    const vertex = compileShader(gl, gl.VERTEX_SHADER, vertexSource);
    const fragment = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
    const program = gl.createProgram();
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    gl.linkProgram(program);
    gl.deleteShader(vertex);
    gl.deleteShader(fragment);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      const message = gl.getProgramInfoLog(program) || "Unknown WebGL link error.";
      gl.deleteProgram(program);
      throw new Error(message);
    }
    return program;
  }

  function compileShader(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const message = gl.getShaderInfoLog(shader) || "Unknown WebGL shader error.";
      gl.deleteShader(shader);
      throw new Error(message);
    }
    return shader;
  }

  function parseVector(tokens, lineNumber) {
    if (tokens.length !== 4) throw new Error(`Invalid LUT domain at line ${lineNumber}.`);
    return tokens.slice(1).map((token) => finiteNumber(token, lineNumber));
  }

  function finiteNumber(value, lineNumber) {
    const number = Number(value);
    if (!Number.isFinite(number)) throw new Error(`Invalid finite number at line ${lineNumber}.`);
    return number;
  }

  function valueAt(lut, red, green, blue, channel) {
    return lut.values[((blue * lut.size * lut.size + green * lut.size + red) * 3) + channel];
  }

  function clamp01(value) { return Math.min(1, Math.max(0, value)); }
  function lerp(start, end, amount) { return start + (end - start) * amount; }

  const VERTEX_SHADER = `#version 300 es
    in vec2 aPosition;
    in vec2 aTextureCoordinate;
    out vec2 vTextureCoordinate;
    void main() {
      gl_Position = vec4(aPosition, 0.0, 1.0);
      vTextureCoordinate = aTextureCoordinate;
    }
  `;
  const FRAGMENT_SHADER = `#version 300 es
    precision highp float;
    precision highp sampler3D;
    uniform sampler2D uSource;
    uniform sampler3D uLut;
    uniform vec3 uDomainMin;
    uniform vec3 uDomainMax;
    in vec2 vTextureCoordinate;
    out vec4 outputColor;
    vec3 sampleLut(vec3 coordinate) {
      ivec3 cubeSize = textureSize(uLut, 0);
      vec3 scaled = coordinate * vec3(cubeSize - ivec3(1));
      ivec3 lower = ivec3(floor(scaled));
      ivec3 upper = min(lower + ivec3(1), cubeSize - ivec3(1));
      vec3 amount = fract(scaled);
      vec3 c000 = texelFetch(uLut, ivec3(lower.x, lower.y, lower.z), 0).rgb;
      vec3 c100 = texelFetch(uLut, ivec3(upper.x, lower.y, lower.z), 0).rgb;
      vec3 c010 = texelFetch(uLut, ivec3(lower.x, upper.y, lower.z), 0).rgb;
      vec3 c110 = texelFetch(uLut, ivec3(upper.x, upper.y, lower.z), 0).rgb;
      vec3 c001 = texelFetch(uLut, ivec3(lower.x, lower.y, upper.z), 0).rgb;
      vec3 c101 = texelFetch(uLut, ivec3(upper.x, lower.y, upper.z), 0).rgb;
      vec3 c011 = texelFetch(uLut, ivec3(lower.x, upper.y, upper.z), 0).rgb;
      vec3 c111 = texelFetch(uLut, ivec3(upper.x, upper.y, upper.z), 0).rgb;
      vec3 c00 = mix(c000, c100, amount.x);
      vec3 c10 = mix(c010, c110, amount.x);
      vec3 c01 = mix(c001, c101, amount.x);
      vec3 c11 = mix(c011, c111, amount.x);
      return clamp(mix(mix(c00, c10, amount.y), mix(c01, c11, amount.y), amount.z), 0.0, 1.0);
    }
    void main() {
      vec4 source = texture(uSource, vTextureCoordinate);
      vec3 coordinate = clamp((source.rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
      outputColor = vec4(sampleLut(coordinate), source.a);
    }
  `;

  return { parseCubeLut, sampleCubeLut, createWebGLRenderer, MAX_CUBE_BYTES };
});
