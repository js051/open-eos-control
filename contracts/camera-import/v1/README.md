# Camera Import Contract V1

[Traditional Chinese](README.zh-TW.md)

This artifact is the transport-neutral boundary between Open EOS Control and a catalog or editing application. Open EOS Control owns camera sessions, capability discovery, media enumeration, representations, and transfer integrity evidence. A consumer owns staging, content hashing, catalog commits, non-destructive editing, export, and long-term library management.

## Wire Contract

- Wire version `1.0` is strict. Consumers must reject unsupported major or minor versions, missing required fields, and unknown fields.
- IDs are opaque, session-scoped where documented, and sanitized. They never contain Canon URLs, PTP opcodes, USB endpoints, camera paths, credentials, serial numbers, email addresses, or IP addresses.
- `openRepresentation(media_id, representation, target_size?)` accepts `representation-request.schema.json` and returns a short-lived native readable source. The handle itself is platform-specific and must not be persisted.
- A receipt records an import outcome. It never grants permission to delete or mutate camera media.
- Filename, byte length, capture time, and media ID are duplicate candidates only. Exact duplicate decisions require a trusted full strong checksum or a SHA-256 calculated by the consumer after receiving all bytes.

## Files

- `media-descriptor.schema.json`: media identity, source, grouping hints, and available representations.
- `representation-request.schema.json`: a fail-closed request for one advertised representation and optional preview bounds.
- `transfer-event.schema.json`: resumable transfer progress and terminal integrity evidence.
- `import-receipt.schema.json`: consumer acknowledgement after atomic import or exact duplicate detection.
- `android-handoff-manifest.schema.json`: short-lived Android `content://` representation mapping.
- `import-receipt-batch.schema.json`: batch consumer results for one handoff session.
- `compatibility.json`: fail-closed compatibility policy.
- `semantic-rules.json`: normative cross-field and trust rules shared by every implementation.
- `fixtures/valid` and `fixtures/invalid`: portable producer and consumer conformance examples.
- `contract-lock.json`: SHA-256 manifest for every packaged contract source.

The JSON Schemas use Draft 2020-12. Timestamps are RFC 3339 values with an explicit offset. `orientation` is an EXIF orientation integer from 1 through 8 and does not replace metadata preserved in the original file.

JSON Schema validates each document's shape. Cross-field numeric equality and ordering rules that Draft 2020-12 cannot express are normative and listed in `semantic-rules.json`; consumers must implement them. The repository Python validator and strict Kotlin codec enforce these rules against the shared fixtures. `preserved_representation_ids` are consumer-generated IDs for blobs that were atomically preserved; they are not source representation IDs and grant no source mutation authority.

Android handoff grants the receiving app temporary read-only access through a manifest URI and `ClipData`. A manifest `content_uri` is a platform transport handle, not a `media_id`, and must not become durable Catalog identity. The consumer must finish preflight and preserve content before returning a receipt batch; cancellation or a missing receipt is never import success.

The Android input intent uses action `dev.photo.workflow.action.IMPORT_CAMERA_MEDIA`, MIME type `application/vnd.openeos.camera-import.v1+json`, and package `dev.photo.workflow`. Its data URI is the manifest; `ClipData` contains that same manifest first followed by the ordered representation URIs. It carries `FLAG_GRANT_READ_URI_PERMISSION` only and no JSON extras. After a session exists, the receiver returns `RESULT_OK` with a read-granted receipt-batch data URI and MIME type `application/vnd.openeos.camera-import-receipt.v1+json`; a user cancellation is represented by exact-coverage `CANCELLED` receipts. `RESULT_CANCELED` is reserved for rejection before a session exists.

The Kotlin JAR targets JVM 17 and is Android-first. Its strict JSON codec uses Android's platform `org.json`; non-Android JVM consumers must provide a compatible `org.json` implementation.
