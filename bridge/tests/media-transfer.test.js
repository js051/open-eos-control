"use strict";

const { describe, it: test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const mediaTransfer = require(path.join(
  __dirname,
  "..",
  "open_eos_bridge",
  "static",
  "media-transfer.js",
));

async function testBufferedStreamingProgress() {
  const response = new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(new Uint8Array([1, 2, 3]));
        controller.enqueue(new Uint8Array([4, 5]));
        controller.close();
      },
    }),
    { headers: { "content-length": "5", "content-type": "image/jpeg" } },
  );
  const progress = [];
  const result = await mediaTransfer.readResponse(response, {
    expectedBytes: 99,
    onProgress: (value) => progress.push({ ...value }),
  });

  assert.equal(result.bytesTransferred, 5);
  assert.equal(result.totalBytes, 5);
  assert.equal(result.contentType, "image/jpeg");
  assert.equal(result.blob.size, 5);
  assert.equal(result.blob.type, "image/jpeg");
  assert.deepEqual(progress, [
    { bytesTransferred: 0, totalBytes: 5 },
    { bytesTransferred: 3, totalBytes: 5 },
    { bytesTransferred: 5, totalBytes: 5 },
    { bytesTransferred: 5, totalBytes: 5 },
  ]);
}

async function testDirectWriterAvoidsBlobBuffer() {
  const writes = [];
  const response = new Response(new Uint8Array([9, 8, 7]));
  const result = await mediaTransfer.readResponse(response, {
    expectedBytes: 3,
    writeChunk: async (chunk) => writes.push([...chunk]),
  });

  assert.deepEqual(writes, [[9, 8, 7]]);
  assert.equal(result.blob, null);
  assert.equal(result.bytesTransferred, 3);
}

async function testCancellationStopsTheReader() {
  const abortController = new AbortController();
  const response = new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(new Uint8Array([1, 2]));
      },
    }),
  );
  const transfer = mediaTransfer.readResponse(response, {
    signal: abortController.signal,
    onProgress: ({ bytesTransferred }) => {
      if (bytesTransferred === 2) abortController.abort();
    },
  });

  await assert.rejects(transfer, (error) => mediaTransfer.isAbortError(error));
}

async function testDeclaredLengthMismatchFails() {
  const response = new Response(new Uint8Array([1, 2, 3]), {
    headers: { "content-length": "4", "content-type": "video/mp4" },
  });
  await assert.rejects(
    mediaTransfer.readResponse(response),
    (error) => error.code === "MEDIA_LENGTH_MISMATCH" && error.expectedBytes === 4 && error.actualBytes === 3,
  );
}

async function testProcessingFailureCancelsOpenResponse() {
  for (const failure of ["write", "overlength", "initial-progress", "chunk-progress", "already-aborted"]) {
    const cancelled = [];
    const response = new Response(new ReadableStream({
      start(controller) { controller.enqueue(new Uint8Array([1, 2, 3, 4])); },
      cancel(reason) { cancelled.push(reason); },
    }), { headers: { "content-length": failure === "overlength" ? "3" : "4" } });
    const primary = new Error(`Synthetic ${failure} failure`);
    const controller = new AbortController();
    if (failure === "already-aborted") controller.abort();
    let caught;
    try {
      await mediaTransfer.readResponse(response, {
        signal: controller.signal,
        writeChunk: failure === "write" ? async () => { throw primary; } : null,
        onProgress: ({ bytesTransferred }) => {
          if ((failure === "initial-progress" && bytesTransferred === 0) ||
              (failure === "chunk-progress" && bytesTransferred > 0)) throw primary;
        },
      });
    } catch (error) { caught = error; }
    assert.ok(caught, failure);
    assert.equal(cancelled.length, 1, `${failure} must retire its still-open original response`);
    assert.equal(cancelled[0], caught, `${failure} must cancel with its primary failure`);
    assert.equal(response.body.locked, false, `${failure} must release the reader`);
    if (failure === "overlength") assert.equal(caught.code, "MEDIA_LENGTH_MISMATCH");
    else if (failure === "already-aborted") assert.ok(mediaTransfer.isAbortError(caught));
    else assert.equal(caught, primary);
  }
}

async function testCleanupFailureNeverMasksPrimaryFailure() {
  for (const cancellation of ["throws", "rejects", "pending"]) {
    for (const releaseThrows of [false, true]) {
      const primary = new Error("Synthetic response read failure");
      const cancelled = [];
      let released = 0;
      const response = { arrayBuffer() {}, headers: { get() { return null; } }, body: { getReader() { return {
        async read() { throw primary; },
        cancel(reason) {
          cancelled.push(reason);
          if (cancellation === "throws") throw new Error("Synthetic cancel failure");
          if (cancellation === "rejects") return Promise.reject(new Error("Synthetic cancel failure"));
          return new Promise(() => {});
        },
        releaseLock() { released += 1; if (releaseThrows) throw new Error("Synthetic release failure"); },
      }; } } };
      let timeout;
      try {
        await assert.rejects(Promise.race([
          mediaTransfer.readResponse(response),
          new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error("Cleanup blocked transfer failure")), 1000); }),
        ]), (error) => error === primary);
      } finally { clearTimeout(timeout); }
      assert.deepEqual(cancelled, [primary]);
      assert.equal(released, 1);
    }
  }
}

async function testAbortAndSuccessReaderCleanup() {
  let cancelCount = 0;
  const controller = new AbortController();
  const response = new Response(new ReadableStream({
    start(stream) { stream.enqueue(new Uint8Array([1, 2])); },
    cancel() { cancelCount += 1; },
  }));
  const result = mediaTransfer.readResponse(response, {
    signal: controller.signal,
    onProgress: ({ bytesTransferred }) => { if (bytesTransferred > 0) controller.abort(); },
  });
  await assert.rejects(result, (error) => mediaTransfer.isAbortError(error));
  assert.equal(cancelCount, 1, "Abort and error cleanup must cancel the reader only once");
  assert.equal(response.body.locked, false);

  let successfulCancelCount = 0;
  const successful = new Response(new ReadableStream({
    start(stream) { stream.enqueue(new Uint8Array([3, 4])); stream.close(); },
    cancel() { successfulCancelCount += 1; },
  }));
  const successController = new AbortController();
  const saved = await mediaTransfer.readResponse(successful, { signal: successController.signal });
  successController.abort();
  assert.equal(saved.bytesTransferred, 2);
  assert.equal(successfulCancelCount, 0, "Successful EOF must not trigger cancellation");
  assert.equal(successful.body.locked, false);
}

async function run() {
  assert.equal(mediaTransfer.safeDownloadName("DCIM/100CANON/IMG_0001.CR3"), "IMG_0001.CR3");
  assert.equal(mediaTransfer.safeDownloadName("../CON"), "_CON");
  assert.equal(mediaTransfer.safeDownloadName("bad:name?.JPG"), "bad_name_.JPG");
  assert.equal(mediaTransfer.safeDownloadName("IMG_0001.JPG. "), "IMG_0001.JPG");
  assert.equal(mediaTransfer.safeDownloadName(""), "camera-media");
  assert.equal(mediaTransfer.shouldUseDirectWriter(8 * 1024 * 1024, true), false);
  assert.equal(mediaTransfer.shouldUseDirectWriter(64 * 1024 * 1024 - 1, true), false);
  assert.equal(mediaTransfer.shouldUseDirectWriter(64 * 1024 * 1024, true), true);
  assert.equal(mediaTransfer.shouldUseDirectWriter(null, true), true);
  assert.equal(mediaTransfer.shouldUseDirectWriter(256 * 1024 * 1024, false), false);
  assert.equal(
    mediaTransfer.totalBytesForResponse(new Response(null), 2048),
    2048,
  );
  assert.equal(
    mediaTransfer.totalBytesForResponse(
      new Response(null, { headers: { "content-length": "invalid" } }),
      4096,
    ),
    4096,
  );
  await testBufferedStreamingProgress();
  await testDirectWriterAvoidsBlobBuffer();
  await testCancellationStopsTheReader();
  await testDeclaredLengthMismatchFails();
  await testProcessingFailureCancelsOpenResponse();
  await testCleanupFailureNeverMasksPrimaryFailure();
  await testAbortAndSuccessReaderCleanup();
}

describe("media-transfer completion", { concurrency: false, timeout: 5000 }, () => {
  test("existing sequential transfer phases", run);
});
