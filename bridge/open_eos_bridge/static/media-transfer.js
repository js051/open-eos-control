(function (root, factory) {
  const mediaTransfer = factory();
  if (typeof module === "object" && module.exports) module.exports = mediaTransfer;
  root.OpenEOSMediaTransfer = mediaTransfer;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const DIRECT_FILE_WRITE_THRESHOLD_BYTES = 64 * 1024 * 1024;

  function positiveByteCount(value) {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
  }

  function totalBytesForResponse(response, expectedBytes = null) {
    const contentLength = positiveByteCount(response?.headers?.get?.("content-length"));
    return contentLength || positiveByteCount(expectedBytes);
  }

  function cancellationError() {
    const error = new Error("Media download cancelled");
    error.name = "AbortError";
    return error;
  }

  function lengthMismatchError(expectedBytes, actualBytes) {
    const error = new Error(
      `Media response length mismatch: received ${actualBytes} of ${expectedBytes} bytes`,
    );
    error.name = "MediaLengthMismatchError";
    error.code = "MEDIA_LENGTH_MISMATCH";
    error.expectedBytes = expectedBytes;
    error.actualBytes = actualBytes;
    return error;
  }

  function isAbortError(error) {
    return error?.name === "AbortError" || error?.code === 20;
  }

  function throwIfAborted(signal) {
    if (signal?.aborted) throw cancellationError();
  }

  function safeDownloadName(value) {
    const leaf = String(value || "")
      .split(/[\\/]/)
      .pop()
      .replace(/[\u0000-\u001f<>:"|?*]/g, "_")
      .replace(/[. ]+$/g, "")
      .trim();
    if (!leaf || leaf === "." || leaf === "..") return "camera-media";
    const stem = leaf.split(".", 1)[0].toUpperCase();
    return /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/.test(stem) ? `_${leaf}` : leaf;
  }

  function shouldUseDirectWriter(sizeBytes, pickerAvailable) {
    if (!pickerAvailable) return false;
    const size = positiveByteCount(sizeBytes);
    return size === null || size >= DIRECT_FILE_WRITE_THRESHOLD_BYTES;
  }

  async function readResponse(
    response,
    { signal = null, expectedBytes = null, onProgress = () => {}, writeChunk = null } = {},
  ) {
    if (!response || typeof response.arrayBuffer !== "function") {
      throw new TypeError("A Fetch Response is required");
    }
    if (typeof onProgress !== "function") throw new TypeError("onProgress must be a function");
    if (writeChunk !== null && typeof writeChunk !== "function") {
      throw new TypeError("writeChunk must be a function");
    }

    throwIfAborted(signal);
    const declaredBytes = totalBytesForResponse(response, expectedBytes);
    let totalBytes = declaredBytes;
    let bytesTransferred = 0;
    const chunks = writeChunk ? null : [];
    const report = () => onProgress({ bytesTransferred, totalBytes });
    report();

    const acceptChunk = async (value) => {
      const chunk = value instanceof Uint8Array ? value : new Uint8Array(value);
      if (chunk.byteLength === 0) return;
      if (writeChunk) await writeChunk(chunk);
      else chunks.push(chunk);
      bytesTransferred += chunk.byteLength;
      if (declaredBytes !== null && bytesTransferred > declaredBytes) {
        throw lengthMismatchError(declaredBytes, bytesTransferred);
      }
      report();
    };

    if (response.body && typeof response.body.getReader === "function") {
      const reader = response.body.getReader();
      const cancelReader = () => {
        try {
          Promise.resolve(reader.cancel(cancellationError())).catch(() => {});
        } catch (_) {
          // A reader that has already closed needs no additional cancellation.
        }
      };
      signal?.addEventListener("abort", cancelReader, { once: true });
      try {
        while (true) {
          throwIfAborted(signal);
          const { done, value } = await reader.read();
          throwIfAborted(signal);
          if (done) break;
          await acceptChunk(value);
        }
      } finally {
        signal?.removeEventListener("abort", cancelReader);
        reader.releaseLock?.();
      }
    } else {
      await acceptChunk(new Uint8Array(await response.arrayBuffer()));
      throwIfAborted(signal);
    }

    throwIfAborted(signal);
    if (declaredBytes !== null && bytesTransferred !== declaredBytes) {
      throw lengthMismatchError(declaredBytes, bytesTransferred);
    }
    totalBytes = bytesTransferred;
    report();
    const contentType = response.headers?.get?.("content-type") || "application/octet-stream";
    return {
      blob: chunks ? new Blob(chunks, { type: contentType }) : null,
      bytesTransferred,
      totalBytes,
      contentType,
    };
  }

  return {
    DIRECT_FILE_WRITE_THRESHOLD_BYTES,
    cancellationError,
    lengthMismatchError,
    isAbortError,
    readResponse,
    safeDownloadName,
    shouldUseDirectWriter,
    totalBytesForResponse,
  };
});
