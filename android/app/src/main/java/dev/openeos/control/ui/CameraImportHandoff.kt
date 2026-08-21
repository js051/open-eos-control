package dev.openeos.control.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.isVideoMedia
import dev.openeos.control.importing.CAMERA_IMPORT_ANDROID_HANDOFF_VERSION
import dev.openeos.control.importing.CAMERA_IMPORT_CONTRACT_VERSION
import dev.openeos.control.importing.CAMERA_IMPORT_MAX_BATCH_ITEMS
import dev.openeos.control.importing.CameraImportAndroidHandoffItemV1
import dev.openeos.control.importing.CameraImportAndroidHandoffManifestV1
import dev.openeos.control.importing.CameraImportAndroidIntentV1
import dev.openeos.control.importing.CameraImportChecksumAlgorithm
import dev.openeos.control.importing.CameraImportChecksumScope
import dev.openeos.control.importing.CameraImportCompanionRole
import dev.openeos.control.importing.CameraImportJsonCodecV1
import dev.openeos.control.importing.CameraImportMediaDescriptorV1
import dev.openeos.control.importing.CameraImportMediaKind
import dev.openeos.control.importing.CameraImportOutcome
import dev.openeos.control.importing.CameraImportPlatformRepresentationV1
import dev.openeos.control.importing.CameraImportReceiptBatchV1
import dev.openeos.control.importing.CameraImportRepresentation
import dev.openeos.control.importing.CameraImportSourceChecksumV1
import dev.openeos.control.importing.OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

data class CameraImportHandoffSession(
    val sessionId: String,
    val manifestUri: Uri,
    val representationUris: List<Uri>,
    val mediaIds: Set<String>,
    val expectedOriginals: Map<String, CameraImportOriginalEvidence>,
    val itemCount: Int,
)

data class CameraImportOriginalEvidence(
    val byteLength: Long,
    val sha256: String,
)

data class CameraImportReceiptSummary(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val cancelled: Int,
) {
    val completed: Int
        get() = imported + duplicates
}

internal object OpenNegativeImportIntents {
    fun isAvailable(context: Context, targetPackage: String = CameraImportAndroidIntentV1.OPEN_NEGATIVE_PACKAGE): Boolean =
        probe(targetPackage).resolveActivity(context.packageManager) != null

    fun create(
        session: CameraImportHandoffSession,
        targetPackage: String = CameraImportAndroidIntentV1.OPEN_NEGATIVE_PACKAGE,
    ): Intent = probe(targetPackage).apply {
        setDataAndType(session.manifestUri, CameraImportAndroidIntentV1.MIME_TYPE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("Open EOS Camera Import", session.manifestUri).apply {
            session.representationUris.forEach { addItem(ClipData.Item(it)) }
        }
    }

    private fun probe(targetPackage: String): Intent = Intent(CameraImportAndroidIntentV1.ACTION)
        .setPackage(targetPackage)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .setType(CameraImportAndroidIntentV1.MIME_TYPE)
}

internal class CameraImportHandoffStorage(private val context: Context) {
    private val root = File(context.cacheDir, ROOT_DIRECTORY)
    private val authority = "${context.packageName}.camera_import"

    suspend fun prepare(
        items: List<CameraMediaItem>,
        camera: CameraInfo,
        providerVersion: String,
        onItem: (index: Int, total: Int, name: String) -> Unit,
        onProgress: (CameraMediaTransferProgress) -> Unit,
        download: suspend (
            item: CameraMediaItem,
            destination: OutputStream,
            onProgress: (CameraMediaTransferProgress) -> Unit,
        ) -> CameraMediaDownloadResult,
    ): CameraImportHandoffSession = withContext(Dispatchers.IO) {
        val selected = items.distinctBy(CameraMediaItem::id)
        require(selected.isNotEmpty()) { "Camera Import requires at least one media item." }
        require(selected.size <= CAMERA_IMPORT_MAX_BATCH_ITEMS) {
            "Camera Import supports at most $CAMERA_IMPORT_MAX_BATCH_ITEMS items per handoff."
        }
        cleanupExpiredSessions()
        val sessionId = "session-${UUID.randomUUID()}"
        val sessionDirectory = stagingDirectory(sessionId)
        check(sessionDirectory.mkdirs()) { "Android could not create a Camera Import staging directory." }
        val sourceIdentity = sourceIdentity(camera)

        try {
            val staged = selected.mapIndexed { index, item ->
                onItem(index, selected.size, item.name)
                stageItem(
                    sessionDirectory = sessionDirectory,
                    sessionId = sessionId,
                    item = item,
                    camera = camera,
                    sourceIdentity = sourceIdentity,
                    providerVersion = providerVersion,
                    onProgress = onProgress,
                    download = download,
                )
            }
            val manifest = CameraImportAndroidHandoffManifestV1(
                handoffVersion = CAMERA_IMPORT_ANDROID_HANDOFF_VERSION,
                contractVersion = CAMERA_IMPORT_CONTRACT_VERSION,
                providerId = OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID,
                providerVersion = providerVersion,
                sessionId = sessionId,
                items = staged.map(StagedItem::handoffItem),
            )
            val manifestFile = File(sessionDirectory, MANIFEST_FILENAME)
            writeAtomically(manifestFile, CameraImportJsonCodecV1.encodeAndroidHandoffManifest(manifest).toByteArray())
            CameraImportHandoffSession(
                sessionId = sessionId,
                manifestUri = contentUri(manifestFile),
                representationUris = staged.map(StagedItem::contentUri),
                mediaIds = staged.mapTo(linkedSetOf()) { it.handoffItem.descriptor.mediaId },
                expectedOriginals = staged.associate { stagedItem ->
                    val descriptor = stagedItem.handoffItem.descriptor
                    descriptor.mediaId to CameraImportOriginalEvidence(
                        byteLength = requireNotNull(descriptor.byteLength),
                        sha256 = requireNotNull(descriptor.sourceChecksum).value.lowercase(),
                    )
                },
                itemCount = staged.size,
            )
        } catch (error: Throwable) {
            sessionDirectory.deleteRecursively()
            throw error
        }
    }

    fun cleanup(sessionId: String) {
        runCatching { stagingDirectory(sessionId) }
            .getOrNull()
            ?.deleteRecursively()
    }

    fun cleanupExpiredSessions(nowMillis: Long = System.currentTimeMillis()) {
        root.listFiles()?.filter { file ->
            file.isDirectory && nowMillis - file.lastModified() >= SESSION_MAX_AGE_MILLIS
        }?.forEach(File::deleteRecursively)
    }

    private suspend fun stageItem(
        sessionDirectory: File,
        sessionId: String,
        item: CameraMediaItem,
        camera: CameraInfo,
        sourceIdentity: SourceIdentity,
        providerVersion: String,
        onProgress: (CameraMediaTransferProgress) -> Unit,
        download: suspend (CameraMediaItem, OutputStream, (CameraMediaTransferProgress) -> Unit) ->
            CameraMediaDownloadResult,
    ): StagedItem {
        val mediaId = "media-${sha256("${sourceIdentity.cameraId}|${item.id}").take(32)}"
        val filename = item.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "camera-media" }
        val safeExtension = filename.substringAfterLast('.', "")
            .filter(Char::isLetterOrDigit)
            .lowercase()
            .take(12)
        val stagedName = if (safeExtension.isEmpty()) mediaId else "$mediaId.$safeExtension"
        val destination = File(sessionDirectory, stagedName)
        val temporary = File(sessionDirectory, "$stagedName.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        val result = FileOutputStream(temporary).use { rawOutput ->
            val output = BufferedOutputStream(DigestOutputStream(rawOutput, digest))
            val completed = download(item, output, onProgress)
            output.flush()
            rawOutput.fd.sync()
            completed
        }
        check(result.bytesTransferred == temporary.length()) {
            "Camera transfer length did not match the staged file for ${item.name}."
        }
        item.sizeBytes?.let { expected ->
            check(expected == temporary.length()) { "Camera changed the reported size of ${item.name}." }
        }
        check(temporary.renameTo(destination)) { "Android could not commit the staged copy of ${item.name}." }
        val contentUri = contentUri(destination)
        val checksum = CameraImportSourceChecksumV1(
            algorithm = CameraImportChecksumAlgorithm.SHA_256,
            value = digest.digest().toHex(),
            scope = CameraImportChecksumScope.FULL_ORIGINAL,
        )
        val descriptor = item.toImportDescriptor(
            sessionId = sessionId,
            mediaId = mediaId,
            camera = camera,
            opaqueCameraId = sourceIdentity.cameraId,
            opaqueStorageId = sourceIdentity.storageId,
            providerVersion = providerVersion,
            byteLength = destination.length(),
            checksum = checksum,
        )
        return StagedItem(
            contentUri = contentUri,
            handoffItem = CameraImportAndroidHandoffItemV1(
                descriptor = descriptor,
                representations = listOf(
                    CameraImportPlatformRepresentationV1(
                        representation = CameraImportRepresentation.ORIGINAL,
                        contentUri = contentUri.toString(),
                    ),
                ),
            ),
        )
    }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    private fun stagingDirectory(sessionId: String): File {
        val directory = File(root, sessionId).canonicalFile
        check(directory.parentFile == root.canonicalFile) { "Camera Import session escaped its staging root." }
        return directory
    }

    private fun sourceIdentity(camera: CameraInfo): SourceIdentity {
        val preferences = context.getSharedPreferences(IDENTITY_PREFERENCES, Context.MODE_PRIVATE)
        val salt = preferences.getString(IDENTITY_SALT_KEY, null) ?: UUID.randomUUID().toString().also { generated ->
            check(preferences.edit().putString(IDENTITY_SALT_KEY, generated).commit()) {
                "Android could not persist the Camera Import identity salt."
            }
        }
        val source = camera.serial.takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
            ?: "${camera.manufacturer.orEmpty()}|${camera.model}|${camera.api}"
        val cameraId = "camera-${sha256("$salt|$source").take(32)}"
        return SourceIdentity(
            cameraId = cameraId,
            storageId = "storage-${sha256("$cameraId|primary").take(32)}",
        )
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.partial")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        check(temporary.renameTo(destination)) { "Android could not commit the Camera Import manifest." }
    }

    private data class StagedItem(
        val handoffItem: CameraImportAndroidHandoffItemV1,
        val contentUri: Uri,
    )

    private data class SourceIdentity(
        val cameraId: String,
        val storageId: String,
    )

    companion object {
        private const val ROOT_DIRECTORY = "camera-import"
        private const val MANIFEST_FILENAME = "manifest.json"
        private const val IDENTITY_PREFERENCES = "camera_import_identity"
        private const val IDENTITY_SALT_KEY = "installation_salt"
        private const val SESSION_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

internal fun readCameraImportReceiptBatch(
    context: Context,
    receiptUri: Uri,
    expectedSession: CameraImportHandoffSession,
): CameraImportReceiptSummary {
    require(receiptUri.scheme == "content") { "Open Negative returned a non-content receipt URI." }
    val bytes = context.contentResolver.openInputStream(receiptUri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_RECEIPT_BYTES) { "Open Negative receipt is too large." }
        }
        output.toByteArray()
    } ?: error("Android could not open the Open Negative receipt.")
    val batch: CameraImportReceiptBatchV1 = CameraImportJsonCodecV1.decodeReceiptBatch(bytes.toString(Charsets.UTF_8))
    require(batch.sessionId == expectedSession.sessionId) { "Open Negative returned a receipt for another session." }
    require(batch.receipts.mapTo(hashSetOf()) { it.mediaId } == expectedSession.mediaIds) {
        "Open Negative receipt does not cover every selected item."
    }
    batch.receipts.forEach { receipt ->
        if (receipt.outcome in setOf(CameraImportOutcome.IMPORTED, CameraImportOutcome.DUPLICATE)) {
            val expected = requireNotNull(expectedSession.expectedOriginals[receipt.mediaId]) {
                "Open Negative returned evidence for an unknown original."
            }
            require(receipt.byteLength == expected.byteLength) {
                "Open Negative receipt length does not match the staged original."
            }
            require(receipt.blobSha256.equals(expected.sha256, ignoreCase = true)) {
                "Open Negative receipt checksum does not match the staged original."
            }
        }
    }
    return CameraImportReceiptSummary(
        imported = batch.receipts.count { it.outcome == CameraImportOutcome.IMPORTED },
        duplicates = batch.receipts.count { it.outcome == CameraImportOutcome.DUPLICATE },
        failed = batch.receipts.count { it.outcome == CameraImportOutcome.FAILED },
        cancelled = batch.receipts.count { it.outcome == CameraImportOutcome.CANCELLED },
    )
}

internal fun CameraMediaItem.toImportDescriptor(
    sessionId: String,
    mediaId: String,
    camera: CameraInfo,
    opaqueCameraId: String,
    opaqueStorageId: String,
    providerVersion: String,
    byteLength: Long,
    checksum: CameraImportSourceChecksumV1,
): CameraImportMediaDescriptorV1 {
    val extension = name.substringAfterLast('.', "").lowercase()
    val kind = when {
        extension in setOf("cr2", "cr3") -> CameraImportMediaKind.RAW
        extension in setOf("jpg", "jpeg") -> CameraImportMediaKind.JPEG
        extension in setOf("heif", "heic", "hif") -> CameraImportMediaKind.HEIF
        isVideoMedia -> CameraImportMediaKind.VIDEO
        extension in setOf("xmp", "thm", "ctg") -> CameraImportMediaKind.SIDECAR
        else -> CameraImportMediaKind.OTHER
    }
    val role = when (kind) {
        CameraImportMediaKind.RAW -> CameraImportCompanionRole.RAW
        CameraImportMediaKind.JPEG -> CameraImportCompanionRole.JPEG
        CameraImportMediaKind.HEIF -> CameraImportCompanionRole.HEIF
        CameraImportMediaKind.VIDEO -> CameraImportCompanionRole.VIDEO
        CameraImportMediaKind.SIDECAR -> CameraImportCompanionRole.SIDECAR
        CameraImportMediaKind.OTHER -> CameraImportCompanionRole.PRIMARY
    }
    val filename = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "camera-media" }
    val groupSource = "$opaqueCameraId|${captureTime.orEmpty()}|${filename.substringBeforeLast('.').lowercase()}"
    return CameraImportMediaDescriptorV1(
        contractVersion = CAMERA_IMPORT_CONTRACT_VERSION,
        providerId = OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID,
        providerVersion = providerVersion,
        sessionId = sessionId,
        mediaId = mediaId,
        captureCorrelationId = "capture-${sha256(groupSource).take(32)}",
        capturedAt = captureTime?.let { runCatching { OffsetDateTime.parse(it).toString() }.getOrNull() },
        filename = filename,
        mediaKind = kind,
        mimeType = contentType?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty) ?: mimeTypeFor(extension),
        byteLength = byteLength,
        sourceChecksum = checksum,
        sourceRevision = "revision-${sha256("$opaqueCameraId|$id|$byteLength|${captureTime.orEmpty()}").take(32)}",
        cameraId = opaqueCameraId,
        cameraModel = camera.model.ifBlank { "Canon EOS Camera" },
        storageId = opaqueStorageId,
        storageLabel = null,
        width = widthPixels,
        height = heightPixels,
        orientation = when (rotationDegrees) {
            0 -> 1
            90 -> 6
            180 -> 3
            270 -> 8
            else -> null
        },
        captureGroupHint = "group-${sha256(groupSource).take(32)}",
        companionRole = role,
        availableRepresentations = listOf(CameraImportRepresentation.ORIGINAL),
        rangeSupported = true,
        resumeSupported = false,
        cancelSupported = false,
    )
}

private fun mimeTypeFor(extension: String): String? = when (extension) {
    "cr3" -> "image/x-canon-cr3"
    "cr2" -> "image/x-canon-cr2"
    "jpg", "jpeg" -> "image/jpeg"
    "heif", "heic", "hif" -> "image/heif"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "xmp" -> "application/rdf+xml"
    else -> null
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val MAX_RECEIPT_BYTES = 5 * 1024 * 1024
