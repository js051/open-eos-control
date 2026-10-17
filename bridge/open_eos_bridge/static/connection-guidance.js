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
    if (error?.status === 0 && error?.code === "BRIDGE_HEALTH_TIMEOUT") return ["connectionBridgeTimeout", "bridge"];
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

  function cleanupWarningKey(code) {
    if (code === "SHUTTER_RELEASE_UNCONFIRMED") return "connectionPreviousShutterUnconfirmed";
    if (code === "LIVE_VIEW_STOP_UNCONFIRMED") return "connectionPreviousLiveViewUnconfirmed";
    if (code === "RECORDING_COMMAND_UNCONFIRMED") return "connectionPreviousRecordingUnconfirmed";
    return null;
  }

  function sessionCreationRejected(error) {
    // Only terminal Bridge rejection evidence releases an unknown-session gate.
    // Network loss, redirects, malformed success and unreadable 502 stay unknown.
    if (["SHUTTER_RELEASE_UNCONFIRMED", "LIVE_VIEW_STOP_UNCONFIRMED", "RECORDING_COMMAND_UNCONFIRMED"].includes(error?.code)) return false;
    if ([400, 401, 403, 404, 405, 409, 422, 429].includes(error?.status)) return true;
    if ([501, 503].includes(error?.status) && error?.code === "ENGINE_UNAVAILABLE") return true;
    return error?.status === 502 && error?.code === "CCAPI_DISCOVERY_FAILED" && error?.engine === "ccapi";
  }

  function availableUsbEngine(health) {
    return ["libgphoto2", "edsdk"].find((name) => health?.engines?.[name]?.available === true) || null;
  }

  return Object.freeze({ validCameraOrigin, failureGuidance, cleanupWarningKey, sessionCreationRejected, availableUsbEngine });
});
