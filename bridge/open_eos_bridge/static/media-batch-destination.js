(function (root, factory) {
  const destination = factory();
  if (typeof module === "object" && module.exports) module.exports = destination;
  root.OpenEOSMediaBatchDestination = destination;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function destinationError(code, message) {
    const error = new Error(message);
    error.name = "MediaBatchDestinationError";
    error.code = code;
    return error;
  }

  function cancelled() {
    const error = new Error("Media batch destination cancelled");
    error.name = "AbortError";
    return error;
  }

  function annotate(error, property, value) {
    try {
      error[property] = value;
      if (error[property] === value) return error;
    } catch (_) {
      // Frozen/primitive failures still need machine-readable outcome evidence.
    }
    const wrapped = new Error(String(error?.message ?? error));
    wrapped.name = typeof error?.name === "string" ? error.name : "Error";
    wrapped.cause = error;
    if (error?.code !== undefined) wrapped.code = error.code;
    if (error?.outcomeUnknown !== undefined) wrapped.outcomeUnknown = error.outcomeUnknown;
    if (error?.abortError !== undefined) wrapped.abortError = error.abortError;
    wrapped[property] = value;
    return wrapped;
  }

  /**
   * The caller obtains directoryHandle with showDirectoryPicker({mode: "readwrite"})
   * directly in a user gesture and supplies a fresh cryptographic nonce per batch.
   * No picker, permission request, persistence, retry, or deletion happens here.
   *
   * Directory/file probes refuse detected collisions, but the browser APIs do NOT
   * provide atomic exclusive creation. An external writer can create/replace an
   * entry between a probe and create, even in this random batch directory. The
   * writable's exclusive mode is a stream lock, not protection from that race.
   * Do not describe this adapter as race-free or safe against external writers.
   * Never delete placeholders: a returned handle does not establish sole ownership.
   *
   * See https://developer.mozilla.org/en-US/docs/Web/API/FileSystemDirectoryHandle/getDirectoryHandle
   * and https://developer.mozilla.org/en-US/docs/Web/API/FileSystemFileHandle/createWritable
   */
  function createDirectoryBatchDestination({
    directoryHandle,
    nonce,
    safeDownloadName,
    signal = null,
    isCurrent = () => true,
    abortTimeoutMs = 1000,
  } = {}) {
    if (directoryHandle?.kind !== "directory" || typeof directoryHandle.getDirectoryHandle !== "function") {
      throw new TypeError("A selected directory handle is required");
    }
    if (typeof safeDownloadName !== "function" || typeof isCurrent !== "function") {
      throw new TypeError("safeDownloadName and isCurrent must be functions");
    }
    if (typeof nonce !== "string" || !/^(?:[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12})$/i.test(nonce)) {
      throw new TypeError("A fresh cryptographic UUID or 32 hexadecimal character nonce is required");
    }
    if (!Number.isInteger(abortTimeoutMs) || abortTimeoutMs < 1 || abortTimeoutMs > 5000) {
      throw new TypeError("abortTimeoutMs must be an integer between 1 and 5000");
    }

    const batchName = `Open-EOS-${nonce}`;
    const files = [];
    const reservedNames = new Set();
    let directory = null;
    let directoryState = "not-created";
    let directoryMayRemain = false;
    let directoryError = null;
    let opening = false;
    let active = null;

    function guard() {
      if (signal?.aborted || isCurrent() !== true) throw cancelled();
    }

    function fileNameFor(item) {
      const input = item?.name ?? item?.filename;
      if (typeof input !== "string" || !input.trim()) throw new TypeError("A media filename is required");
      const name = safeDownloadName(input);
      // Validate the helper's result too: never pass a path to a browser handle.
      if (typeof name !== "string" || !name || name === "." || name === ".."
        || /[\\/\u0000-\u001f\u007f<>:"|?*]/.test(name) || /[. ]$/.test(name)
        || /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)/i.test(name)) {
        throw destinationError("MEDIA_BATCH_INVALID_NAME", "The sanitized filename is not a safe leaf name");
      }
      return name;
    }

    async function requireMissing(getHandle, name) {
      guard();
      try {
        await getHandle(name, { create: false });
      } catch (error) {
        guard();
        if (error?.name === "NotFoundError") return;
        if (error?.name !== "TypeMismatchError") throw error;
        throw destinationError("MEDIA_BATCH_COLLISION", "The destination entry already exists");
      }
      guard();
      throw destinationError("MEDIA_BATCH_COLLISION", "The destination entry already exists");
    }

    async function ensureDirectory() {
      guard();
      if (directoryError) throw directoryError;
      if (directory) return directory;
      try {
        await requireMissing(directoryHandle.getDirectoryHandle.bind(directoryHandle), batchName);
        guard();
        directoryMayRemain = true;
        directoryState = "create-pending";
        let handle;
        try {
          handle = await directoryHandle.getDirectoryHandle(batchName, { create: true });
        } catch (error) {
          directoryState = "unknown";
          throw annotate(error, "outcomeUnknown", true);
        }
        directoryState = "created";
        guard();
        if (handle?.kind !== "directory" || handle.name !== batchName || typeof handle.getFileHandle !== "function") {
          directoryState = "unknown";
          throw destinationError("MEDIA_BATCH_INVALID_HANDLE", "The batch directory handle does not match the requested entry");
        }
        directory = handle;
        return directory;
      } catch (error) {
        directoryError = directoryState === "unknown" ? annotate(error, "outcomeUnknown", true) : error;
        throw directoryError;
      }
    }

    function wrapWritable(raw, record) {
      let abortPromise = null;
      function abort(reason) {
        if (record.closeSucceeded) return Promise.resolve();
        if (abortPromise) return abortPromise;
        record.abortState = "pending";
        record.state = "aborting";
        abortPromise = (async () => {
          let timer;
          try {
            await Promise.race([
              Promise.resolve().then(() => {
                if (typeof raw?.abort !== "function") throw new TypeError("The writer cannot be aborted");
                return raw.abort(reason);
              }),
              new Promise((_, reject) => {
                timer = setTimeout(() => reject(destinationError(
                  "MEDIA_BATCH_ABORT_TIMEOUT", "The writer's abort outcome is unknown",
                )), abortTimeoutMs);
              }),
            ]);
            record.abortState = "aborted";
            record.state = record.outcomeUnknown ? "unknown" : "aborted";
          } catch (error) {
            record.abortState = error?.code === "MEDIA_BATCH_ABORT_TIMEOUT" ? "timeout" : "failed";
            record.outcomeUnknown = true;
            record.state = "unknown";
            throw error;
          } finally {
            clearTimeout(timer);
          }
        })();
        return abortPromise;
      }

      async function fail(error) {
        try {
          await abort(error);
        } catch (abortError) {
          error = annotate(error, "abortError", abortError);
        }
        if (record.outcomeUnknown) error = annotate(error, "outcomeUnknown", true);
        throw error;
      }

      const writable = {
        async write(chunk) {
          try {
            guard();
            if (record.state !== "open") throw destinationError("MEDIA_BATCH_WRITER_STATE", "The writer is not open");
            record.state = "writing";
            await raw.write(chunk);
            guard();
            if (record.abortState !== "not-requested") throw cancelled();
            record.state = "open";
          } catch (error) {
            return fail(error);
          }
        },
        async close() {
          try {
            guard();
            if (record.state !== "open") throw destinationError("MEDIA_BATCH_WRITER_STATE", "The writer is not open");
          } catch (error) {
            return fail(error);
          }
          record.state = "closing";
          try {
            await raw.close();
          } catch (error) {
            // The replacement may already have happened. Successful abort cannot
            // convert a rejected close into evidence of no file or of completion.
            record.outcomeUnknown = true;
            return fail(annotate(error, "outcomeUnknown", true));
          }
          // Close is the commit boundary. Preserve its receipt if cancellation or
          // session retirement happened while it was pending; the caller owns UI.
          record.closeSucceeded = true;
          record.completed = true;
          record.state = "closed";
        },
        abort,
      };
      return { writable, fail };
    }

    async function open(item, index) {
      guard();
      if (!Number.isSafeInteger(index) || index < 0) throw new TypeError("A nonnegative item index is required");
      if (opening || (active && !["closed", "aborted"].includes(active.state))) {
        throw destinationError("MEDIA_BATCH_BUSY", "Finish the current destination before opening another");
      }
      const name = fileNameFor(item);
      const key = name.normalize("NFC").toLowerCase();
      if (reservedNames.has(key)) throw destinationError("MEDIA_BATCH_COLLISION", "A filename is already reserved by this batch");
      reservedNames.add(key);
      const record = {
        index, name, state: "not-created", mayRemain: false,
        closeSucceeded: false, completed: false, abortState: "not-requested", outcomeUnknown: false,
      };
      files.push(record);
      opening = true;
      let wrapped = null;
      try {
        const batchDirectory = await ensureDirectory();
        guard();
        await requireMissing(batchDirectory.getFileHandle.bind(batchDirectory), name);
        guard();
        record.mayRemain = true;
        record.state = "create-pending";
        let handle;
        try {
          handle = await batchDirectory.getFileHandle(name, { create: true });
        } catch (error) {
          record.outcomeUnknown = true;
          throw annotate(error, "outcomeUnknown", true);
        }
        record.state = "placeholder";
        guard();
        if (handle?.kind !== "file" || handle.name !== name || typeof handle.createWritable !== "function") {
          record.outcomeUnknown = true;
          throw destinationError("MEDIA_BATCH_INVALID_HANDLE", "The file handle does not match the requested entry");
        }
        guard();
        record.state = "writer-pending";
        let raw;
        try {
          raw = await handle.createWritable({ keepExistingData: false, mode: "exclusive" });
        } catch (error) {
          record.outcomeUnknown = true;
          throw annotate(error, "outcomeUnknown", true);
        }
        record.state = "open";
        wrapped = wrapWritable(raw, record);
        active = record;
        guard();
        if (typeof raw?.write !== "function" || typeof raw?.close !== "function" || typeof raw?.abort !== "function") {
          throw new TypeError("A writable file stream is required");
        }
        return { writable: wrapped.writable, name };
      } catch (error) {
        if (wrapped) return await wrapped.fail(error);
        record.state = record.outcomeUnknown ? "unknown" : (record.mayRemain ? "placeholder" : "not-created");
        throw record.outcomeUnknown ? annotate(error, "outcomeUnknown", true) : error;
      } finally {
        opening = false;
      }
    }

    function snapshot() {
      return {
        batchName,
        directoryState,
        directoryMayRemain,
        files: files.map((record) => ({ ...record })),
        leftoverCount: files.filter((record) => record.mayRemain && !record.completed).length,
        unknownCount: files.filter((record) => record.outcomeUnknown).length,
      };
    }

    return { open, snapshot };
  }

  return { createDirectoryBatchDestination };
});
