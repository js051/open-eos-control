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
      .replace(/((?:password|token)\s*[=:]\s*)[^\s&,]+/gi, "$1[redacted]")
      .replace(/\b[A-Z]:[\\/]+[^\r\n,;"'}\]]+/gi, "[local-path]")
      .replace(/\\\\[^\\\r\n\s"']+\\[^\r\n,;"'}\]]+/g, "[local-path]")
      .replace(/\bfile:\/\/[^\r\n,;"'}\]]+/gi, "[local-path]")
      .replace(
        /(^|[^A-Za-z0-9_])\/(?:Users|home|tmp|var\/folders|private\/var|data\/user|storage\/emulated|mnt\/[a-z])\/[^\r\n,;"'}\]]+/g,
        "$1[local-path]",
      );
    secrets.filter(Boolean).forEach((secret) => {
      safe = safe.replaceAll(String(secret), "[redacted]");
    });
    return safe;
  }

  function physicalValidationSummary(
    capabilities,
    { connected = false, isPreview = false, info = null, confirmedFeatures = [] } = {},
  ) {
    const advertisedFeatures = [...new Set(capabilities?.supported || [])].sort();
    const observedFeatures = [...new Set(capabilities?.evidence?.observedFeatures || [])].sort();
    const observedSet = new Set(observedFeatures);
    const simulatorValues = [info?.api, info?.model, capabilities?.evidence?.source]
      .filter(Boolean)
      .map((value) => String(value).toLowerCase());
    const sessionStatus = !connected
      ? "DISCONNECTED"
      : isPreview
        ? "OFFLINE_PREVIEW"
        : simulatorValues.some((value) => value.includes("simulat"))
          ? "SIMULATOR"
          : "READY";
    const eligibleFeatures = sessionStatus === "READY"
      ? advertisedFeatures.filter((feature) => observedSet.has(feature))
      : [];
    const eligibleSet = new Set(eligibleFeatures);
    const operatorConfirmedFeatures = [...new Set(confirmedFeatures)]
      .filter((feature) => eligibleSet.has(feature))
      .sort();
    return {
      sessionStatus,
      advertisedFeatures,
      observedFeatures,
      eligibleFeatures,
      operatorConfirmedFeatures,
    };
  }

  async function physicalValidationRecord({
    summary,
    cameraModel = "unknown",
    transport = "unknown",
    generatedAt = "unknown",
    productVersion = "unknown",
    diagnosticReport,
  }) {
    if (summary?.sessionStatus !== "READY") {
      throw new Error("A physical camera session is required to create a validation record.");
    }
    if (!globalThis.crypto?.subtle) {
      throw new Error("Web Crypto SHA-256 is unavailable in this browser context.");
    }
    const normalizedDiagnostic = String(diagnosticReport).replaceAll("\r\n", "\n");
    const digest = await globalThis.crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(normalizedDiagnostic),
    );
    const hash = [...new Uint8Array(digest)]
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("");
    const advertisedSet = new Set(summary.advertisedFeatures || []);
    const observedSet = new Set(summary.observedFeatures || []);
    const confirmedSet = new Set(summary.operatorConfirmedFeatures || []);
    const features = [...new Set([...advertisedSet, ...observedSet])].sort();
    return [
      "# Open EOS Control physical camera validation",
      "",
      "- Record schema: 1",
      `- Generated at: ${markdownCell(generatedAt)}`,
      `- App version: ${markdownCell(productVersion)}`,
      `- Camera model: ${markdownCell(cameraModel)}`,
      `- Transport: ${markdownCell(transport)}`,
      `- Diagnostic SHA-256: \`${hash}\``,
      "",
      "Operator confirmation is a manual in-app attestation that the physical camera visibly performed the operation.",
      "",
      "| Feature | Advertised | Observed this session | Operator confirmed |",
      "| --- | --- | --- | --- |",
      ...features.map((feature) =>
        `| ${feature} | ${advertisedSet.has(feature)} | ${observedSet.has(feature)} | ${confirmedSet.has(feature)} |`),
    ].join("\n");
  }

  function markdownCell(value) {
    return String(value).replace(/[\r\n]/g, " ").replaceAll("|", "\\|").slice(0, 160);
  }

  return { featureSummary, safeValue, physicalValidationSummary, physicalValidationRecord };
});
