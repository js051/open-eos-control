"use strict";

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
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
