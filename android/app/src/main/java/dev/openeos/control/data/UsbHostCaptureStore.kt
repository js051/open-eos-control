package dev.openeos.control.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import java.util.UUID

interface UsbHostCaptureStore {
    fun owns(item: CameraMediaItem): Boolean

    suspend fun save(
        filename: String,
        kind: String,
        expectedSizeBytes: Long,
        writer: suspend (OutputStream) -> Long,
    ): CameraMediaItem

    suspend fun list(): List<CameraMediaItem>

    suspend fun thumbnail(item: CameraMediaItem): CameraMediaThumbnail

    suspend fun preview(item: CameraMediaItem): CameraMediaPreview

    suspend fun download(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult

    suspend fun delete(item: CameraMediaItem)
}

class AndroidUsbHostCaptureStore(context: Context) : UsbHostCaptureStore {
    private val directory = File(context.applicationContext.filesDir, CAPTURE_DIRECTORY)

    override fun owns(item: CameraMediaItem): Boolean = item.id.startsWith(HOST_MEDIA_ID_PREFIX)

    override suspend fun save(
        filename: String,
        kind: String,
        expectedSizeBytes: Long,
        writer: suspend (OutputStream) -> Long,
    ): CameraMediaItem = withContext(Dispatchers.IO) {
        require(expectedSizeBytes > 0L) { "A host capture must advertise a positive object size." }
        ensureDirectory()
        val finalFile = uniqueFile(sanitizeFilename(filename))
        val partialFile = File(directory, ".${finalFile.name}.${UUID.randomUUID()}.part")
        try {
            val written = FileOutputStream(partialFile).use { output ->
                writer(output).also {
                    output.flush()
                    output.fd.sync()
                }
            }
            if (written != expectedSizeBytes || partialFile.length() != expectedSizeBytes) {
                throw PtpProtocolException(
                    "Canon EOS host capture expected $expectedSizeBytes bytes but stored ${partialFile.length()} bytes."
                )
            }
            if (!partialFile.renameTo(finalFile)) {
                throw PtpProtocolException("Could not finalize host capture ${finalFile.name}.")
            }
            finalFile.toMediaItem(kind)
        } catch (exception: Exception) {
            partialFile.delete()
            throw exception
        }
    }

    override suspend fun list(): List<CameraMediaItem> = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext emptyList()
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filterNot { it.name.startsWith(".") || it.name.endsWith(".part") }
            .sortedByDescending(File::lastModified)
            .take(MAX_HOST_CAPTURE_ITEMS)
            .map { it.toMediaItem(kindForFilename(it.name)) }
            .toList()
    }

    override suspend fun thumbnail(item: CameraMediaItem): CameraMediaThumbnail = withContext(Dispatchers.IO) {
        val file = requireFile(item)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PtpProtocolException("${item.name} does not contain an Android-decodable image preview.")
        }
        var sample = 1
        while (bounds.outWidth / sample > HOST_THUMBNAIL_EDGE * 2 ||
            bounds.outHeight / sample > HOST_THUMBNAIL_EDGE * 2
        ) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw PtpProtocolException("Could not decode ${item.name} for a thumbnail.")
        val scaled = scaleToFit(decoded, HOST_THUMBNAIL_EDGE)
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, HOST_THUMBNAIL_QUALITY, output)) {
                    throw PtpProtocolException("Could not encode a thumbnail for ${item.name}.")
                }
                output.toByteArray()
            }
            CameraMediaThumbnail(item = item, bytes = bytes, contentType = "image/jpeg")
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    override suspend fun preview(item: CameraMediaItem): CameraMediaPreview = withContext(Dispatchers.IO) {
        val file = requireFile(item)
        if (!item.previewAvailable || file.length() !in 1..MAX_HOST_PREVIEW_BYTES) {
            throw PtpProtocolException("${item.name} does not have a bounded Android-decodable image preview.")
        }
        val bytes = file.inputStream().buffered().use { input ->
            input.readNBytes((MAX_HOST_PREVIEW_BYTES + 1L).toInt())
        }
        if (bytes.size.toLong() > MAX_HOST_PREVIEW_BYTES) {
            throw PtpProtocolException("${item.name} exceeds the bounded image preview limit.")
        }
        val contentType = hostPreviewContentType(item.name, bytes)
            ?: throw PtpProtocolException("${item.name} is not a complete JPEG or PNG image.")
        CameraMediaPreview(item = item, bytes = bytes, contentType = contentType)
    }

    override suspend fun download(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult = withContext(Dispatchers.IO) {
        val file = requireFile(item)
        val total = file.length()
        var transferred = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(FILE_COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                destination.write(buffer, 0, count)
                transferred += count
                onProgress(CameraMediaTransferProgress(transferred, total))
            }
        }
        CameraMediaDownloadResult(
            item = item,
            bytesTransferred = transferred,
            contentType = hostContentType(item.name, item.kind),
        )
    }

    override suspend fun delete(item: CameraMediaItem) = withContext(Dispatchers.IO) {
        val file = requireFile(item)
        if (!file.delete()) throw PtpProtocolException("Could not delete host capture ${item.name}.")
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw PtpProtocolException("Could not create the private USB host-capture directory.")
        }
    }

    private fun uniqueFile(filename: String): File {
        val first = File(directory, filename)
        if (!first.exists()) return first
        val extension = filename.substringAfterLast('.', "").takeIf(String::isNotEmpty)
        val stem = if (extension == null) filename else filename.dropLast(extension.length + 1)
        for (suffix in 1..MAX_FILENAME_COLLISIONS) {
            val candidate = File(directory, "$stem-$suffix${extension?.let { ".$it" }.orEmpty()}")
            if (!candidate.exists()) return candidate
        }
        throw PtpProtocolException("Could not allocate a unique filename for $filename.")
    }

    private fun requireFile(item: CameraMediaItem): File {
        if (!owns(item)) throw PtpProtocolException("Media item ${item.id} is not a USB host capture.")
        val encodedName = item.id.removePrefix(HOST_MEDIA_ID_PREFIX)
        if (encodedName.isBlank() || encodedName != File(encodedName).name) {
            throw PtpProtocolException("USB host media item ${item.id} has an invalid filename.")
        }
        val file = File(directory, encodedName)
        if (!file.isFile) throw PtpProtocolException("Host capture ${item.name} no longer exists.")
        return file
    }

    private fun File.toMediaItem(kind: String): CameraMediaItem = CameraMediaItem(
        id = HOST_MEDIA_ID_PREFIX + name,
        name = name,
        kind = kind,
        sizeBytes = length(),
        captureTime = Instant.ofEpochMilli(lastModified()).toString(),
        previewAvailable = kind == "image" && length() in 1..MAX_HOST_PREVIEW_BYTES && hasHostPreviewExtension(name),
        ratingWritable = false,
    )
}

private fun sanitizeFilename(filename: String): String {
    val leaf = File(filename).name
    val safe = leaf.map { character ->
        if (character.isLetterOrDigit() || character in "._-") character else '_'
    }.joinToString("").trim('.', ' ')
    return safe.take(MAX_CAPTURE_FILENAME_CHARS).ifBlank { "capture.jpg" }
}

private fun kindForFilename(filename: String): String =
    when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "cr2", "cr3", "dng", "raw" -> "raw"
        "mp4", "mov", "avi", "mkv" -> "video"
        else -> "image"
    }

private fun hostContentType(filename: String, kind: String): String =
    when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "tif", "tiff" -> "image/tiff"
        "dng" -> "image/x-adobe-dng"
        "cr2", "cr3" -> "image/x-canon-raw"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> if (kind == "video") "video/*" else "application/octet-stream"
    }

private fun hasHostPreviewExtension(filename: String): Boolean =
    filename.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png")

private fun hostPreviewContentType(filename: String, bytes: ByteArray): String? = when {
    filename.endsWith(".png", ignoreCase = true) && bytes.hasCompletePngMarkers() -> "image/png"
    filename.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg") &&
        bytes.hasCompleteJpegMarkers() -> "image/jpeg"
    else -> null
}

private fun ByteArray.hasCompleteJpegMarkers(): Boolean =
    size >= 4 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
        this[lastIndex - 1] == 0xFF.toByte() && this[lastIndex] == 0xD9.toByte()

private fun ByteArray.hasPngSignature(): Boolean =
    size >= 8 && copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    )

private fun ByteArray.hasCompletePngMarkers(): Boolean =
    hasPngSignature() && size >= 20 && copyOfRange(size - 12, size).contentEquals(
        byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()),
    )

private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / largest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

private const val HOST_MEDIA_ID_PREFIX = "usb-host:"
private const val CAPTURE_DIRECTORY = "usb-host-captures"
private const val MAX_HOST_CAPTURE_ITEMS = 500
private const val MAX_CAPTURE_FILENAME_CHARS = 160
private const val MAX_FILENAME_COLLISIONS = 9_999
private const val HOST_THUMBNAIL_EDGE = 512
private const val HOST_THUMBNAIL_QUALITY = 85
private const val MAX_HOST_PREVIEW_BYTES = 32L * 1024L * 1024L
private const val FILE_COPY_BUFFER_BYTES = 64 * 1024
