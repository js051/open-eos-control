package dev.openeos.control.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal fun cameraGalleryPath(cameraModel: String?): String =
    "Pictures/${safeMediaFilename(cameraModel.orEmpty(), "Open EOS Control")}/"

internal fun safeMediaFilename(value: String, fallback: String = "camera-media"): String = value
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .trim().trim('.').take(120).ifBlank { fallback }

internal fun galleryMimeType(item: CameraMediaItem): String? = when (item.name.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "cr3" -> "image/x-canon-cr3"
    "cr2" -> "image/x-canon-cr2"
    "dng" -> "image/x-adobe-dng"
    "heif", "heic" -> "image/heif"
    "png" -> "image/png"
    "tif", "tiff" -> "image/tiff"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "mkv" -> "video/x-matroska"
    else -> null
}

internal fun canSaveMediaToGallery(item: CameraMediaItem): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        galleryMimeType(item)?.let { MimeTypeMap.getSingleton().hasMimeType(it) } == true

/** Publishes only fully written originals; insert never replaces another app's file. */
@RequiresApi(Build.VERSION_CODES.Q)
internal class CameraMediaGalleryStore(
    private val resolver: ContentResolver,
    private val openOutput: (Uri) -> OutputStream? = { resolver.openOutputStream(it, "w") },
    private val publishOutput: (Uri, ContentValues) -> Int = { uri, values -> resolver.update(uri, values, null, null) },
) {
    /**
     * [onPublished] is JPEG-only, synchronous, and restricted to bounded in-memory bookkeeping.
     * It executes after ownership is protected, before [onFinalized], even if IO return is cancelled.
     * Its failure is reported separately and must never change the Gallery transfer outcome.
     */
    suspend fun save(
        cameraModel: String?,
        item: CameraMediaItem,
        onFinalized: () -> Unit = {},
        onCleanupFailure: () -> Unit = {},
        onPublished: ((PublishedGalleryOriginal) -> Unit)? = null,
        onPublicationObserverFailure: () -> Unit = {},
        download: suspend (OutputStream) -> CameraMediaDownloadResult,
    ): Uri {
        var created: Uri? = null
        return withMediaOutputFinalization(
            cleanupIncomplete = {
                created?.let { destination ->
                    try {
                        check(resolver.delete(destination, null, null) == 1) { "Android could not remove the incomplete download." }
                    } catch (failure: Exception) {
                        onCleanupFailure()
                        throw failure
                    }
                }
            },
            onFinalized = onFinalized,
        ) { finalization ->
            withContext(Dispatchers.IO) {
                savePending(
                    cameraModel, item, { created = it }, finalization, onFinalized,
                    onPublished, onPublicationObserverFailure, download,
                )
            }
        }
    }

    private suspend fun savePending(
        cameraModel: String?,
        item: CameraMediaItem,
        onCreated: (Uri) -> Unit,
        finalization: MediaOutputFinalization,
        onFinalized: () -> Unit,
        onPublished: ((PublishedGalleryOriginal) -> Unit)?,
        onPublicationObserverFailure: () -> Unit,
        download: suspend (OutputStream) -> CameraMediaDownloadResult,
    ): Uri {
        val mimeType = requireNotNull(galleryMimeType(item)) { "Choose a folder for this file format." }
        require(canSaveMediaToGallery(item)) { "Choose a folder for this file format." }
        val collection = if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeMediaFilename(item.name))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, cameraGalleryPath(cameraModel))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(collection, values)
            ?: error("Android could not create the gallery destination.")
        onCreated(destination)
        // An absent observer and unrelated RAW/video saves keep their existing streaming cost.
        val digest = if (mimeType == "image/jpeg" && onPublished != null) MessageDigest.getInstance("SHA-256") else null
        val raw = openOutput(destination)
            ?: error("Android could not open the gallery destination.")
        val counted = CountingMediaOutput(BufferedOutputStream(raw), digest)
        val result = counted.use { download(it) }
        check(counted.bytes > 0 && result.bytesTransferred == counted.bytes) { "Incomplete media download." }
        listOfNotNull(item.sizeBytes, result.item.sizeBytes).forEach { expected ->
            check(expected == counted.bytes) { "Media download length does not match the original." }
        }
        // Build evidence before the irreversible boundary. A failed close or validation never
        // reaches either observer, and no Gallery read or suspend-return owns the registration.
        val original = digest?.let {
            PublishedGalleryOriginal(
                uri = destination,
                byteLength = counted.bytes,
                sha256 = it.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') },
                mimeType = mimeType,
            )
        }
        coroutineContext.ensureActive()
        val published = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        check(publishOutput(destination, published) == 1) { "Android could not publish the downloaded media." }
        // No suspension between publication and ownership: prompt cancellation on the return
        // from IO must not delete a successfully published original or lose its receipt.
        finalization.confirm {
            try {
                if (original != null) {
                    try {
                        onPublished?.invoke(original)
                    } catch (_: Exception) {
                        // The caller surfaces list availability separately from Gallery success.
                        onPublicationObserverFailure()
                    }
                }
            } finally {
                // Automatic-import publication and durable history success remain mandatory,
                // including when either registry observer itself throws.
                onFinalized()
            }
        }
        return destination
    }
}

private class CountingMediaOutput(
    output: OutputStream,
    private val digest: MessageDigest? = null,
) : FilterOutputStream(output) {
    var bytes = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        digest?.update(value.toByte())
        bytes += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        digest?.update(buffer, offset, length)
        bytes += length
    }
}
