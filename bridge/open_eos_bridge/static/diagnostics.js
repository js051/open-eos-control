(function (root, factory) {
  const diagnostics = factory();
  if (typeof module === "object" && module.exports) module.exports = diagnostics;
  root.OpenEOSDiagnostics = diagnostics;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function featureSummary(capabilities) {
    const advertised = [...new Set(capabilities?.supported || [])].sort();
    const observed = [...new Set(capabilities?.evidence?.observedFeatures || [])].sort();
    const advertisedSet = new Set(advertised);
    const observedSet = new Set(observed);
    return {
      advertisedFeatureCount: advertised.length,
      observedFeatureCount: observed.length,
      validatedAdvertisedFeatureCount: advertised.filter((feature) => observedSet.has(feature)).length,
      unverifiedAdvertisedFeatures: advertised.filter((feature) => !observedSet.has(feature)),
      observedWithoutAdvertisement: observed.filter((feature) => !advertisedSet.has(feature)),
    };
  }

  function safeValue(value, { key = "", secrets = [] } = {}) {
    if (["serial", "password", "token", "authorization"].includes(key.toLowerCase())) {
      const normalized = value == null ? "" : String(value).trim().toLowerCase();
      return !normalized || normalized === "unknown" || normalized === "none" ? "unknown" : "[redacted]";
    }
    if (Array.isArray(value)) return value.map((item) => safeValue(item, { secrets }));
    if (value && typeof value === "object") {
      return Object.fromEntries(
        Object.entries(value).map(([childKey, childValue]) => [
          childKey,
          safeValue(childValue, { key: childKey, secrets }),
        ]),
      );
    }
    if (typeof value !== "string") return value;
    let safe = value
      .replace(/(authorization\s*[:=]\s*(?:bearer|basic)?\s*)[^\s,]+/gi, "$1[redacted]")
      .replace(/((?:password|token)\s*[=:]\s*)[^\s&,]+/gi, "$1[redacted]");
    secrets.filter(Boolean).forEach((secret) => {
      safe = safe.replaceAll(String(secret), "[redacted]");
    });
    return safe;
  }

  return { featureSummary, safeValue };
});
