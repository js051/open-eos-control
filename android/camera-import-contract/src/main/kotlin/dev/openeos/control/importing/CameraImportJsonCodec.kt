package dev.openeos.control.importing

import org.json.JSONArray
import org.json.JSONObject

object CameraImportJsonCodecV1 {
    fun encodeMediaDescriptor(value: CameraImportMediaDescriptorV1): String = value.toJson().toString()

    fun encodeReceipt(value: CameraImportReceiptV1): String = value.toJson().toString()

    fun encodeAndroidHandoffManifest(value: CameraImportAndroidHandoffManifestV1): String = JSONObject()
        .put("handoff_version", value.handoffVersion)
        .put("contract_version", value.contractVersion)
        .put("provider_id", value.providerId)
        .put("provider_version", value.providerVersion)
        .put("session_id", value.sessionId)
        .put("items", JSONArray().apply {
            value.items.forEach { item ->
                put(JSONObject()
                    .put("descriptor", item.descriptor.toJson())
                    .put("representations", JSONArray().apply {
                        item.representations.forEach { handle ->
                            put(JSONObject()
                                .put("representation", handle.representation.name)
                                .put("content_uri", handle.contentUri))
                        }
                    }))
            }
        })
        .toString()

    fun encodeReceiptBatch(value: CameraImportReceiptBatchV1): String = JSONObject()
        .put("handoff_version", value.handoffVersion)
        .put("contract_version", value.contractVersion)
        .put("provider_id", value.providerId)
        .put("session_id", value.sessionId)
        .put("receipts", JSONArray().apply { value.receipts.forEach { put(it.toJson()) } })
        .toString()

    fun decodeMediaDescriptor(value: String): CameraImportMediaDescriptorV1 {
        val json = JSONObject(value).requireExactKeys(MEDIA_DESCRIPTOR_KEYS)
        return CameraImportMediaDescriptorV1(
            contractVersion = json.getString("contract_version"),
            providerId = json.getString("provider_id"),
            providerVersion = json.getString("provider_version"),
            sessionId = json.getString("session_id"),
            mediaId = json.getString("media_id"),
            captureCorrelationId = json.nullableString("capture_correlation_id"),
            capturedAt = json.nullableString("captured_at"),
            filename = json.getString("filename"),
            mediaKind = CameraImportMediaKind.valueOf(json.getString("media_kind")),
            mimeType = json.nullableString("mime_type"),
            byteLength = json.nullableLong("byte_length"),
            sourceChecksum = json.nullableChecksum("source_checksum"),
            sourceRevision = json.nullableString("source_revision"),
            cameraId = json.getString("camera_id"),
            cameraModel = json.getString("camera_model"),
            storageId = json.getString("storage_id"),
            storageLabel = json.nullableString("storage_label"),
            width = json.nullableInt("width"),
            height = json.nullableInt("height"),
            orientation = json.nullableInt("orientation"),
            captureGroupHint = json.nullableString("capture_group_hint"),
            companionRole = json.nullableString("companion_role")?.let(CameraImportCompanionRole::valueOf),
            availableRepresentations = json.getJSONArray("available_representations").representationList(),
            rangeSupported = json.requiredBoolean("range_supported"),
            resumeSupported = json.requiredBoolean("resume_supported"),
            cancelSupported = json.requiredBoolean("cancel_supported"),
        )
    }

    fun decodeAndroidHandoffManifest(value: String): CameraImportAndroidHandoffManifestV1 {
        val json = JSONObject(value).requireExactKeys(ANDROID_HANDOFF_KEYS)
        val items = json.getJSONArray("items").objects().map { item ->
            item.requireExactKeys(ANDROID_HANDOFF_ITEM_KEYS)
            CameraImportAndroidHandoffItemV1(
                descriptor = decodeMediaDescriptor(item.getJSONObject("descriptor").toString()),
                representations = item.getJSONArray("representations").objects().map { handle ->
                    handle.requireExactKeys(PLATFORM_REPRESENTATION_KEYS)
                    CameraImportPlatformRepresentationV1(
                        representation = CameraImportRepresentation.valueOf(handle.getString("representation")),
                        contentUri = handle.getString("content_uri"),
                    )
                },
            )
        }
        return CameraImportAndroidHandoffManifestV1(
            handoffVersion = json.getString("handoff_version"),
            contractVersion = json.getString("contract_version"),
            providerId = json.getString("provider_id"),
            providerVersion = json.getString("provider_version"),
            sessionId = json.getString("session_id"),
            items = items,
        )
    }

    fun decodeReceiptBatch(value: String): CameraImportReceiptBatchV1 {
        val json = JSONObject(value).requireExactKeys(RECEIPT_BATCH_KEYS)
        return CameraImportReceiptBatchV1(
            handoffVersion = json.getString("handoff_version"),
            contractVersion = json.getString("contract_version"),
            providerId = json.getString("provider_id"),
            sessionId = json.getString("session_id"),
            receipts = json.getJSONArray("receipts").objects().map { decodeReceipt(it.toString()) },
        )
    }

    fun decodeRepresentationRequest(value: String): CameraImportRepresentationRequestV1 {
        val json = JSONObject(value).requireExactKeys(REPRESENTATION_REQUEST_KEYS)
        val targetSize = if (json.isNull("target_size")) {
            null
        } else {
            json.getJSONObject("target_size")
                .requireExactKeys(TARGET_SIZE_KEYS)
                .let {
                    CameraImportTargetSizeV1(
                        maximumWidth = it.requiredInt("maximum_width"),
                        maximumHeight = it.requiredInt("maximum_height"),
                    )
                }
        }
        return CameraImportRepresentationRequestV1(
            contractVersion = json.getString("contract_version"),
            providerId = json.getString("provider_id"),
            sessionId = json.getString("session_id"),
            mediaId = json.getString("media_id"),
            representation = CameraImportRepresentation.valueOf(json.getString("representation")),
            targetSize = targetSize,
        )
    }

    fun decodeTransferEvent(value: String): CameraImportTransferEventV1 {
        val json = JSONObject(value).requireExactKeys(TRANSFER_EVENT_KEYS)
        return CameraImportTransferEventV1(
            contractVersion = json.getString("contract_version"),
            providerId = json.getString("provider_id"),
            sessionId = json.getString("session_id"),
            transferId = json.getString("transfer_id"),
            mediaId = json.getString("media_id"),
            representation = CameraImportRepresentation.valueOf(json.getString("representation")),
            state = CameraImportTransferState.valueOf(json.getString("state")),
            bytesTransferred = json.requiredLong("bytes_transferred"),
            totalBytes = json.nullableLong("total_bytes"),
            sourceRevision = json.nullableString("source_revision"),
            sourceChecksum = json.nullableChecksum("source_checksum"),
            canResume = json.requiredBoolean("can_resume"),
            canCancel = json.requiredBoolean("can_cancel"),
            cancelRequested = json.requiredBoolean("cancel_requested"),
            attempt = json.requiredInt("attempt"),
            safeErrorCode = json.nullableString("safe_error_code"),
            retryable = json.requiredBoolean("retryable"),
            updatedAt = json.getString("updated_at"),
            receivedByteLength = json.nullableLong("received_byte_length"),
            transportChecksumVerified = json.nullableBoolean("transport_checksum_verified"),
            sanitizedEvidenceId = json.nullableString("sanitized_evidence_id"),
        )
    }

    fun decodeReceipt(value: String): CameraImportReceiptV1 {
        val json = JSONObject(value).requireExactKeys(RECEIPT_KEYS)
        return CameraImportReceiptV1(
            contractVersion = json.getString("contract_version"),
            importId = json.getString("import_id"),
            providerId = json.getString("provider_id"),
            sessionId = json.getString("session_id"),
            mediaId = json.getString("media_id"),
            outcome = CameraImportOutcome.valueOf(json.getString("outcome")),
            assetId = json.nullableString("asset_id"),
            blobSha256 = json.nullableString("blob_sha256"),
            byteLength = json.nullableLong("byte_length"),
            captureGroupId = json.nullableString("capture_group_id"),
            preservedRepresentationIds = json.getJSONArray("preserved_representation_ids").stringList(),
            safeErrorCode = json.nullableString("safe_error_code"),
            completedAt = json.getString("completed_at"),
        )
    }

    private fun JSONObject.nullableChecksum(key: String): CameraImportSourceChecksumV1? {
        if (isNull(key)) return null
        val checksum = getJSONObject(key).requireExactKeys(CHECKSUM_KEYS)
        return CameraImportSourceChecksumV1(
            algorithm = CameraImportChecksumAlgorithm.valueOf(checksum.getString("algorithm").replace('-', '_')),
            value = checksum.getString("value"),
            scope = CameraImportChecksumScope.valueOf(checksum.getString("scope")),
        )
    }

    private fun JSONObject.requireExactKeys(expected: Set<String>): JSONObject {
        val actual = keys().asSequence().toSet()
        require(actual == expected) {
            "Camera Import JSON keys differ; missing=${expected - actual}, unknown=${actual - expected}."
        }
        return this
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.requiredLong(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("$key must be a JSON integer within signed 64-bit range.")
    }

    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else requiredLong(key)

    private fun JSONObject.requiredInt(key: String): Int = get(key).let { value ->
        require(value is Int) { "$key must be a JSON integer within signed 32-bit range." }
        value
    }

    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else requiredInt(key)

    private fun JSONObject.requiredBoolean(key: String): Boolean = get(key).let { value ->
        require(value is Boolean) { "$key must be a JSON boolean." }
        value
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? = if (isNull(key)) null else requiredBoolean(key)

    private fun JSONArray.representationList(): List<CameraImportRepresentation> =
        (0 until length()).map { CameraImportRepresentation.valueOf(getString(it)) }

    private fun JSONArray.stringList(): List<String> = (0 until length()).map(::getString)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun CameraImportMediaDescriptorV1.toJson(): JSONObject = JSONObject()
        .put("contract_version", contractVersion)
        .put("provider_id", providerId)
        .put("provider_version", providerVersion)
        .put("session_id", sessionId)
        .put("media_id", mediaId)
        .putNullable("capture_correlation_id", captureCorrelationId)
        .putNullable("captured_at", capturedAt)
        .put("filename", filename)
        .put("media_kind", mediaKind.name)
        .putNullable("mime_type", mimeType)
        .putNullable("byte_length", byteLength)
        .putNullable("source_checksum", sourceChecksum?.toJson())
        .putNullable("source_revision", sourceRevision)
        .put("camera_id", cameraId)
        .put("camera_model", cameraModel)
        .put("storage_id", storageId)
        .putNullable("storage_label", storageLabel)
        .putNullable("width", width)
        .putNullable("height", height)
        .putNullable("orientation", orientation)
        .putNullable("capture_group_hint", captureGroupHint)
        .putNullable("companion_role", companionRole?.name)
        .put("available_representations", JSONArray().apply {
            availableRepresentations.forEach { put(it.name) }
        })
        .put("range_supported", rangeSupported)
        .put("resume_supported", resumeSupported)
        .put("cancel_supported", cancelSupported)

    private fun CameraImportReceiptV1.toJson(): JSONObject = JSONObject()
        .put("contract_version", contractVersion)
        .put("import_id", importId)
        .put("provider_id", providerId)
        .put("session_id", sessionId)
        .put("media_id", mediaId)
        .put("outcome", outcome.name)
        .putNullable("asset_id", assetId)
        .putNullable("blob_sha256", blobSha256)
        .putNullable("byte_length", byteLength)
        .putNullable("capture_group_id", captureGroupId)
        .put("preserved_representation_ids", JSONArray().apply {
            preservedRepresentationIds.forEach { put(it) }
        })
        .putNullable("safe_error_code", safeErrorCode)
        .put("completed_at", completedAt)

    private fun CameraImportSourceChecksumV1.toJson(): JSONObject = JSONObject()
        .put("algorithm", algorithm.name.replace('_', '-'))
        .put("value", value)
        .put("scope", scope.name)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private val CHECKSUM_KEYS = setOf("algorithm", "value", "scope")
    private val PLATFORM_REPRESENTATION_KEYS = setOf("representation", "content_uri")
    private val ANDROID_HANDOFF_ITEM_KEYS = setOf("descriptor", "representations")
    private val ANDROID_HANDOFF_KEYS = setOf(
        "handoff_version",
        "contract_version",
        "provider_id",
        "provider_version",
        "session_id",
        "items",
    )
    private val RECEIPT_BATCH_KEYS = setOf(
        "handoff_version",
        "contract_version",
        "provider_id",
        "session_id",
        "receipts",
    )
    private val TARGET_SIZE_KEYS = setOf("maximum_width", "maximum_height")
    private val REPRESENTATION_REQUEST_KEYS = setOf(
        "contract_version",
        "provider_id",
        "session_id",
        "media_id",
        "representation",
        "target_size",
    )
    private val MEDIA_DESCRIPTOR_KEYS = setOf(
        "contract_version",
        "provider_id",
        "provider_version",
        "session_id",
        "media_id",
        "capture_correlation_id",
        "captured_at",
        "filename",
        "media_kind",
        "mime_type",
        "byte_length",
        "source_checksum",
        "source_revision",
        "camera_id",
        "camera_model",
        "storage_id",
        "storage_label",
        "width",
        "height",
        "orientation",
        "capture_group_hint",
        "companion_role",
        "available_representations",
        "range_supported",
        "resume_supported",
        "cancel_supported",
    )
    private val TRANSFER_EVENT_KEYS = setOf(
        "contract_version",
        "provider_id",
        "session_id",
        "transfer_id",
        "media_id",
        "representation",
        "state",
        "bytes_transferred",
        "total_bytes",
        "source_revision",
        "source_checksum",
        "can_resume",
        "can_cancel",
        "cancel_requested",
        "attempt",
        "safe_error_code",
        "retryable",
        "updated_at",
        "received_byte_length",
        "transport_checksum_verified",
        "sanitized_evidence_id",
    )
    private val RECEIPT_KEYS = setOf(
        "contract_version",
        "import_id",
        "provider_id",
        "session_id",
        "media_id",
        "outcome",
        "asset_id",
        "blob_sha256",
        "byte_length",
        "capture_group_id",
        "preserved_representation_ids",
        "safe_error_code",
        "completed_at",
    )
}
