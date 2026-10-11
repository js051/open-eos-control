(function (root, factory) {
  const guidance = factory();
  if (typeof module === "object" && module.exports) module.exports = guidance;
  root.OpenEOSConnectionGuidance = guidance;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const CAMERA_REASONS = Object.freeze({
    AUTHENTICATION_REJECTED: ["connectionCameraAuth", "credentials"],
    ENDPOINT_NOT_FOUND: ["connectionCameraEndpoint", "address"],
    HTTP_ERROR: ["connectionCameraHttp", "address"],
    TLS_ERROR: ["connectionCameraTls", "address"],
    HOST_NOT_FOUND: ["connectionCameraHost", "address"],
    TIMEOUT: ["connectionCameraTimeout", "address"],
    UNREACHABLE: ["connectionCameraNetwork", "address"],
    DISCOVERY_FAILED: ["connectionCameraDiscovery", "address"],
  });

  function validCameraOrigin(input) {
    if (typeof input !== "string") return false;
    const raw = input.trim();
    // URL() repairs missing slashes, backslashes and control characters. Reject
    // those spellings here so the displayed input and the submitted origin agree.
    if (!/^https?:\/\/[^/?#]+\/?$/i.test(raw) || /[\\\u0000-\u0020\u007f]/.test(raw)) return false;
    try {
      const value = new URL(raw);
      return ["http:", "https:"].includes(value.protocol) && Boolean(value.hostname) &&
        !value.username && !value.password && value.pathname === "/" && !value.search && !value.hash &&
        !raw.includes("?") && !raw.includes("#") && !raw.slice(raw.indexOf("://") + 3).includes("@");
    } catch (_) {
      return false;
    }
  }

  function failureGuidance(error, mode) {
    // Actual Bridge HTTP status takes precedence over every body-supplied field.
    if (error?.status === 401) return ["connectionBridgeAuth", "token"];
    if (error?.status === 403) return ["connectionBridgeAccess", "bridge"];
    if (error?.status === 404) return ["connectionBridgeEndpoint", "bridge"];
    if (error?.status === 0 && error?.code === "NETWORK_ERROR") return ["connectionBridgeNetwork", "bridge"];
    if (error?.status === 502 && error?.code === "CCAPI_DISCOVERY_FAILED" && error?.engine === "ccapi") {
      const reason = error.connectionReason;
      return typeof reason === "string" && Object.hasOwn(CAMERA_REASONS, reason)
        ? CAMERA_REASONS[reason] : ["connectionCameraDiscovery", "address"];
    }
    if (error?.status === 422 && error?.code === "INVALID_CCAPI_URL") return ["connectionInvalidOrigin", "address"];
    if ([501, 503].includes(error?.status) && error?.code === "ENGINE_UNAVAILABLE") {
      return [mode === "usb" ? "connectionUsbEngine" : "connectionCcapiEngine", "bridge"];
    }
    return ["connectionUnknownFailure", null];
  }

  function availableUsbEngine(health) {
    return ["libgphoto2", "edsdk"].find((name) => health?.engines?.[name]?.available === true) || null;
  }

  return Object.freeze({ validCameraOrigin, failureGuidance, availableUsbEngine });
});
