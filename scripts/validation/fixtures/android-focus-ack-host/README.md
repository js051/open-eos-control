# Original focus command receipt host boundary

The bounded runner extracts the exact production `driveFocus`, `tapFocus`,
`clickWhiteBalance`, `commandOk` and `requestOk` methods. The same nine malformed
ACK cases fail on baseline `27e6e3cb` and pass after the narrow receipt gate.
Complete JSON, empty 204/200, plain-text 200 and plain-text 202 receipts remain
accepted. HTTP 503 with an empty range header retains its typed HTTP failure.

Assertions cover no observed-feature promotion on failure, one original mutation,
original method/path/payload, unchanged coordinate-owner checks, no replay after a
status read, unchanged strict-client selection, unrelated methods/paths and ranges,
and stale coordinate snapshots refusing dispatch. Removing the new receipt gate
must restore the original Android receipt method byte-for-byte.

Call/Response/InputStream, discovery, geometry and status are synthetic. This is
not full Android, real OkHttp, sockets, browser or physical-device validation.
`CcapiFocusAckReceiptTest.kt` is separately authored MockWebServer coverage and is
NOT_RUN locally. The runner verifies the already installed JVM artifacts and uses
`-Xmx256m -XX:ActiveProcessorCount=1`; it installs or downloads nothing.
