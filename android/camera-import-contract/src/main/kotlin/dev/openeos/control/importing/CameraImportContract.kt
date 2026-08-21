package dev.openeos.control.importing

import java.net.URI
import java.time.OffsetDateTime

const val CAMERA_IMPORT_CONTRACT_VERSION = "1.0"
const val CAMERA_IMPORT_CONTRACT_ARTIFACT_VERSION = "1.1.0"
const val CAMERA_IMPORT_ANDROID_HANDOFF_VERSION = "1.0"
const val CAMERA_IMPORT_MAX_BATCH_ITEMS = 10_000
const val OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID = "dev.openeos.control"
const val MINIMUM_OPEN_EOS_CAMERA_IMPORT_PROVIDER_VERSION = "0.5.0"

object CameraImportAndroidIntentV1 {
    const val ACTION = "dev.photo.workflow.action.IMPORT_CAMERA_MEDIA"
    const val MIME_TYPE = "application/vnd.openeos.camera-import.v1+json"
    const val RECEIPT_MIME_TYPE = "application/vnd.openeos.camera-import-receipt.v1+json"
    const val OPEN_NEGATIVE_PACKAGE = "dev.photo.workflow"
}

enum class CameraImportMediaKind {
    RAW,
    JPEG,
    HEIF,
    VIDEO,
    SIDECAR,
    OTHER,
}

enum class CameraImportRepresentation {
    THUMBNAIL,
    PREVIEW,
    ORIGINAL,
    METADATA,
    SIDECAR,
}

data class CameraImportTargetSizeV1(
    val maximumWidth: Int,
    val maximumHeight: Int,
) {
    init {
        require(maximumWidth in 1..16_384 && maximumHeight in 1..16_384) {
            "Target dimensions must be between 1 and 16384 pixels."
        }
    }
}

data class CameraImportRepresentationRequestV1(
    val contractVersion: String,
    val providerId: String,
    val sessionId: String,
    val mediaId: String,
    val representation: CameraImportRepresentation,
    val targetSize: CameraImportTargetSizeV1?,
) {
    init {
        require(contractVersion == CAMERA_IMPORT_CONTRACT_VERSION) { "Unsupported Camera Import contract version." }
        requireSafeOpaqueId(providerId, "providerId")
        requireSafeOpaqueId(sessionId, "sessionId")
        requireSafeOpaqueId(mediaId, "mediaId")
        if (representation in setOf(
                CameraImportRepresentation.ORIGINAL,
                CameraImportRepresentation.METADATA,
                CameraImportRepresentation.SIDECAR,
            )
        ) {
            require(targetSize == null) { "Only thumbnail and preview representations accept targetSize." }
        }
    }
}

fun interface CameraImportRepresentationOpenerV1<ReadableSource> {
    fun openRepresentation(request: CameraImportRepresentationRequestV1): ReadableSource
}

enum class CameraImportCompanionRole {
    PRIMARY,
    RAW,
    JPEG,
    HEIF,
    VIDEO,
    SIDECAR,
}

enum class CameraImportChecksumAlgorithm {
    SHA_256,
    SHA_512,
    MD5,
}

enum class CameraImportChecksumScope {
    FULL_ORIGINAL,
    FULL_REPRESENTATION,
    TRANSPORT_PAYLOAD,
}

data class CameraImportSourceChecksumV1(
    val algorithm: CameraImportChecksumAlgorithm,
    val value: String,
    val scope: CameraImportChecksumScope,
) {
    init {
        val expectedLength = when (algorithm) {
            CameraImportChecksumAlgorithm.SHA_256 -> 64
            CameraImportChecksumAlgorithm.SHA_512 -> 128
            CameraImportChecksumAlgorithm.MD5 -> 32
        }
        require(value.length == expectedLength && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            "Checksum value does not match ${algorithm.name}."
        }
    }

    val supportsExactDuplicate: Boolean
        get() = scope == CameraImportChecksumScope.FULL_ORIGINAL &&
            algorithm in setOf(CameraImportChecksumAlgorithm.SHA_256, CameraImportChecksumAlgorithm.SHA_512)
}

data class CameraImportMediaDescriptorV1(
    val contractVersion: String,
    val providerId: String,
    val providerVersion: String,
    val sessionId: String,
    val mediaId: String,
    val captureCorrelationId: String?,
    val capturedAt: String?,
    val filename: String,
    val mediaKind: CameraImportMediaKind,
    val mimeType: String?,
    val byteLength: Long?,
    val sourceChecksum: CameraImportSourceChecksumV1?,
    val sourceRevision: String?,
    val cameraId: String,
    val cameraModel: String,
    val storageId: String,
    val storageLabel: String?,
    val width: Int?,
    val height: Int?,
    val orientation: Int?,
    val captureGroupHint: String?,
    val companionRole: CameraImportCompanionRole?,
    val availableRepresentations: List<CameraImportRepresentation>,
    val rangeSupported: Boolean,
    val resumeSupported: Boolean,
    val cancelSupported: Boolean,
) {
    init {
        require(contractVersion == CAMERA_IMPORT_CONTRACT_VERSION) { "Unsupported Camera Import contract version." }
        requireSafeOpaqueId(providerId, "providerId")
        requireProviderVersion(providerId, providerVersion)
        requireSafeOpaqueId(sessionId, "sessionId")
        requireSafeOpaqueId(mediaId, "mediaId")
        captureCorrelationId?.let { requireSafeOpaqueId(it, "captureCorrelationId") }
        capturedAt?.let(::requireOffsetDateTime)
        require(filename.isNotBlank() && filename.length <= 255 && filename != "." && filename != ".." && '/' !in filename && '\\' !in filename) {
            "filename must be a basename."
        }
        require(mimeType == null || mimeType.length <= 127 && MIME_TYPE.matches(mimeType)) { "mimeType is invalid." }
        require(byteLength == null || byteLength > 0) { "byteLength must be positive when known." }
        sourceRevision?.let { requireSafeOpaqueId(it, "sourceRevision") }
        requireSafeOpaqueId(cameraId, "cameraId")
        require(cameraModel.isNotBlank() && cameraModel.length <= 120) { "cameraModel is invalid." }
        requireSafeOpaqueId(storageId, "storageId")
        require(storageLabel == null || storageLabel.length <= 120) { "storageLabel is too long." }
        require(width == null || width > 0) { "width must be positive when known." }
        require(height == null || height > 0) { "height must be positive when known." }
        require(orientation == null || orientation in 1..8) { "orientation must be an EXIF orientation value." }
        captureGroupHint?.let { requireSafeOpaqueId(it, "captureGroupHint") }
        require(availableRepresentations.isNotEmpty()) { "At least one representation is required." }
        require(availableRepresentations.distinct().size == availableRepresentations.size) {
            "availableRepresentations must be unique."
        }
        require(!resumeSupported || rangeSupported) { "resumeSupported requires rangeSupported." }
    }
}

data class CameraImportPlatformRepresentationV1(
    val representation: CameraImportRepresentation,
    val contentUri: String,
) {
    init {
        requireReadOnlyContentUri(contentUri)
    }
}

data class CameraImportAndroidHandoffItemV1(
    val descriptor: CameraImportMediaDescriptorV1,
    val representations: List<CameraImportPlatformRepresentationV1>,
) {
    init {
        require(representations.isNotEmpty()) { "A handoff item requires a representation handle." }
        require(representations.map { it.representation }.distinct().size == representations.size) {
            "Handoff representation handles must be unique."
        }
        require(representations.all { it.representation in descriptor.availableRepresentations }) {
            "Handoff representation handles must be advertised by the descriptor."
        }
    }
}

data class CameraImportAndroidHandoffManifestV1(
    val handoffVersion: String,
    val contractVersion: String,
    val providerId: String,
    val providerVersion: String,
    val sessionId: String,
    val items: List<CameraImportAndroidHandoffItemV1>,
) {
    init {
        require(handoffVersion == CAMERA_IMPORT_ANDROID_HANDOFF_VERSION) {
            "Unsupported Camera Import Android handoff version."
        }
        require(contractVersion == CAMERA_IMPORT_CONTRACT_VERSION) { "Unsupported Camera Import contract version." }
        requireSafeOpaqueId(providerId, "providerId")
        requireProviderVersion(providerId, providerVersion)
        requireSafeOpaqueId(sessionId, "sessionId")
        require(items.isNotEmpty() && items.size <= CAMERA_IMPORT_MAX_BATCH_ITEMS) {
            "A handoff requires between 1 and $CAMERA_IMPORT_MAX_BATCH_ITEMS items."
        }
        require(items.map { it.descriptor.mediaId }.distinct().size == items.size) {
            "Handoff media IDs must be unique."
        }
        require(items.all { item ->
            item.descriptor.contractVersion == contractVersion &&
                item.descriptor.providerId == providerId &&
                item.descriptor.providerVersion == providerVersion &&
                item.descriptor.sessionId == sessionId
        }) { "Handoff descriptors must match their envelope." }
    }
}

enum class CameraImportTransferState {
    QUEUED,
    RUNNING,
    PAUSED,
    CANCEL_PENDING,
    CANCELLED,
    FAILED,
    COMPLETED,
}

data class CameraImportTransferEventV1(
    val contractVersion: String,
    val providerId: String,
    val sessionId: String,
    val transferId: String,
    val mediaId: String,
    val representation: CameraImportRepresentation,
    val state: CameraImportTransferState,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val sourceRevision: String?,
    val sourceChecksum: CameraImportSourceChecksumV1?,
    val canResume: Boolean,
    val canCancel: Boolean,
    val cancelRequested: Boolean,
    val attempt: Int,
    val safeErrorCode: String?,
    val retryable: Boolean,
    val updatedAt: String,
    val receivedByteLength: Long?,
    val transportChecksumVerified: Boolean?,
    val sanitizedEvidenceId: String?,
) {
    init {
        require(contractVersion == CAMERA_IMPORT_CONTRACT_VERSION) { "Unsupported Camera Import contract version." }
        requireSafeOpaqueId(providerId, "providerId")
        requireSafeOpaqueId(sessionId, "sessionId")
        requireSafeOpaqueId(transferId, "transferId")
        requireSafeOpaqueId(mediaId, "mediaId")
        require(bytesTransferred >= 0) { "bytesTransferred cannot be negative." }
        require(totalBytes == null || totalBytes > 0) { "totalBytes must be positive when known." }
        require(totalBytes == null || bytesTransferred <= totalBytes) { "bytesTransferred exceeds totalBytes." }
        sourceRevision?.let { requireSafeOpaqueId(it, "sourceRevision") }
        require(attempt > 0) { "attempt must be positive." }
        safeErrorCode?.let { require(SAFE_ERROR_CODE.matches(it)) { "safeErrorCode is invalid." } }
        requireOffsetDateTime(updatedAt)
        sanitizedEvidenceId?.let { requireSafeOpaqueId(it, "sanitizedEvidenceId") }

        if (state == CameraImportTransferState.COMPLETED) {
            require(receivedByteLength != null && receivedByteLength == bytesTransferred) {
                "Completed transfer requires a matching receivedByteLength."
            }
            require(totalBytes == null || receivedByteLength == totalBytes) {
                "Completed transfer must match totalBytes when known."
            }
            require(transportChecksumVerified != null) {
                "Completed transfer requires transport checksum evidence."
            }
            require(sanitizedEvidenceId != null) { "Completed transfer requires sanitized evidence." }
            require(safeErrorCode == null) { "Completed transfer cannot contain an error code." }
        } else {
            require(receivedByteLength == null && transportChecksumVerified == null && sanitizedEvidenceId == null) {
                "Completion evidence is only valid for completed transfers."
            }
        }
        if (state == CameraImportTransferState.FAILED) {
            require(safeErrorCode != null) { "Failed transfer requires a safe error code." }
        } else {
            require(safeErrorCode == null) { "Only failed transfers can contain an error code." }
        }
        if (state in setOf(CameraImportTransferState.CANCEL_PENDING, CameraImportTransferState.CANCELLED)) {
            require(cancelRequested) { "Cancelled state requires cancelRequested." }
        }
    }
}

enum class CameraImportOutcome {
    IMPORTED,
    DUPLICATE,
    FAILED,
    CANCELLED,
}

data class CameraImportReceiptV1(
    val contractVersion: String,
    val importId: String,
    val providerId: String,
    val sessionId: String,
    val mediaId: String,
    val outcome: CameraImportOutcome,
    val assetId: String?,
    val blobSha256: String?,
    val byteLength: Long?,
    val captureGroupId: String?,
    val preservedRepresentationIds: List<String>,
    val safeErrorCode: String?,
    val completedAt: String,
) {
    init {
        require(contractVersion == CAMERA_IMPORT_CONTRACT_VERSION) { "Unsupported Camera Import contract version." }
        requireSafeOpaqueId(importId, "importId")
        requireSafeOpaqueId(providerId, "providerId")
        requireSafeOpaqueId(sessionId, "sessionId")
        requireSafeOpaqueId(mediaId, "mediaId")
        assetId?.let { requireSafeOpaqueId(it, "assetId") }
        require(blobSha256 == null || SHA_256.matches(blobSha256)) { "blobSha256 is invalid." }
        require(byteLength == null || byteLength > 0) { "byteLength must be positive when known." }
        captureGroupId?.let { requireSafeOpaqueId(it, "captureGroupId") }
        preservedRepresentationIds.forEach { requireSafeOpaqueId(it, "preservedRepresentationId") }
        require(preservedRepresentationIds.distinct().size == preservedRepresentationIds.size) {
            "preservedRepresentationIds must be unique."
        }
        safeErrorCode?.let { require(SAFE_ERROR_CODE.matches(it)) { "safeErrorCode is invalid." } }
        requireOffsetDateTime(completedAt)

        if (outcome in setOf(CameraImportOutcome.IMPORTED, CameraImportOutcome.DUPLICATE)) {
            require(assetId != null && blobSha256 != null && byteLength != null) {
                "Successful receipt requires committed asset evidence."
            }
            require(preservedRepresentationIds.isNotEmpty()) {
                "Successful receipt requires preserved representations."
            }
            require(safeErrorCode == null) { "Successful receipt cannot contain an error code." }
        } else {
            require(assetId == null && blobSha256 == null && byteLength == null && captureGroupId == null) {
                "Uncommitted receipt cannot contain asset evidence."
            }
            require(preservedRepresentationIds.isEmpty()) {
                "Uncommitted receipt cannot contain preserved representations."
            }
        }
        if (outcome == CameraImportOutcome.FAILED) {
            require(safeErrorCode != null) { "Failed receipt requires a safe error code." }
        } else {
            require(safeErrorCode == null) { "Only failed receipts can contain an error code." }
        }
    }
}

data class CameraImportReceiptBatchV1(
    val handoffVersion: String,
    val contractVersion: String,
    val providerId: String,
    val sessionId: String,
    val receipts: List<CameraImportReceiptV1>,
) {
    init {
        require(handoffVersion == CAMERA_IMPORT_ANDROID_HANDOFF_VERSION) {
            "Unsupported Camera Import receipt handoff version."
        }
        require(contractVersion == CAMERA_IMPORT_CONTRACT_VERSION) { "Unsupported Camera Import contract version." }
        requireSafeOpaqueId(providerId, "providerId")
        requireSafeOpaqueId(sessionId, "sessionId")
        require(receipts.isNotEmpty() && receipts.size <= CAMERA_IMPORT_MAX_BATCH_ITEMS) {
            "A receipt batch requires between 1 and $CAMERA_IMPORT_MAX_BATCH_ITEMS receipts."
        }
        require(receipts.map { it.mediaId }.distinct().size == receipts.size) {
            "Receipt media IDs must be unique."
        }
        require(receipts.all { receipt ->
            receipt.contractVersion == contractVersion &&
                receipt.providerId == providerId &&
                receipt.sessionId == sessionId
        }) { "Receipts must match their batch envelope." }
    }
}

private val SEMANTIC_VERSION = Regex("^([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[-+][0-9A-Za-z.-]+)?$")
private val MIME_TYPE = Regex("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$")
private val SAFE_ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{2,63}$")
private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
private val SAFE_OPAQUE_ID = Regex("^[A-Za-z][A-Za-z0-9._-]{0,255}$")
private val IPV4 = Regex("(?:^|[^0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?:$|[^0-9])")

private fun requireSafeOpaqueId(value: String, field: String) {
    require(SAFE_OPAQUE_ID.matches(value)) { "$field must be a sanitized opaque identifier." }
    require("://" !in value && !IPV4.containsMatchIn(value)) { "$field must not contain an endpoint or IP address." }
}

private fun requireProviderVersion(providerId: String, providerVersion: String) {
    require(providerVersion.matches(SEMANTIC_VERSION)) { "providerVersion must be semantic versioning." }
    if (providerId == OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID) {
        val current = semanticVersionCore(providerVersion)
        val minimum = semanticVersionCore(MINIMUM_OPEN_EOS_CAMERA_IMPORT_PROVIDER_VERSION)
        require(current > minimum || current == minimum && '-' !in providerVersion.substringBefore('+')) {
            "Open EOS providerVersion must be 0.5.0 or newer."
        }
    }
}

private fun requireReadOnlyContentUri(value: String) {
    require(value.length <= 2_048) { "contentUri is too long." }
    val uri = runCatching { URI(value) }.getOrElse {
        throw IllegalArgumentException("contentUri is invalid.", it)
    }
    require(
        uri.scheme.equals("content", ignoreCase = true) &&
            !uri.authority.isNullOrBlank() &&
            !uri.path.isNullOrBlank() &&
            uri.userInfo == null &&
            '@' !in uri.rawAuthority.orEmpty() &&
            uri.fragment == null
    ) { "Only provider-owned content URIs are accepted." }
}

private fun requireOffsetDateTime(value: String) {
    runCatching { OffsetDateTime.parse(value) }
        .getOrElse { throw IllegalArgumentException("Timestamp must be RFC 3339 with an offset.", it) }
}

private fun semanticVersionCore(value: String): SemanticVersionCore {
    val match = requireNotNull(SEMANTIC_VERSION.matchEntire(value)) { "Invalid semantic version." }
    return SemanticVersionCore(
        major = match.groupValues[1].toLong(),
        minor = match.groupValues[2].toLong(),
        patch = match.groupValues[3].toLong(),
    )
}

private data class SemanticVersionCore(
    val major: Long,
    val minor: Long,
    val patch: Long,
) : Comparable<SemanticVersionCore> {
    override fun compareTo(other: SemanticVersionCore): Int =
        compareValuesBy(this, other, SemanticVersionCore::major, SemanticVersionCore::minor, SemanticVersionCore::patch)
}
