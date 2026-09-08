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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
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
internal class CameraMediaGalleryStore(private val resolver: ContentResolver) {
    suspend fun save(
        cameraModel: String?,
        item: CameraMediaItem,
        download: suspend (OutputStream) -> CameraMediaDownloadResult,
    ): Uri {
        var created: Uri? = null
        try {
            return withContext(Dispatchers.IO) { savePending(cameraModel, item, { created = it }, download) }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                created?.let { destination ->
                    try {
                        check(resolver.delete(destination, null, null) == 1) { "Android could not remove the incomplete download." }
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
            }
            throw failure
        }
    }

    private suspend fun savePending(
        cameraModel: String?,
        item: CameraMediaItem,
        onCreated: (Uri) -> Unit,
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
        val raw = resolver.openOutputStream(destination, "w")
            ?: error("Android could not open the gallery destination.")
        val counted = CountingMediaOutput(BufferedOutputStream(raw))
        val result = counted.use { download(it) }
        check(counted.bytes > 0 && result.bytesTransferred == counted.bytes) { "Incomplete media download." }
        listOfNotNull(item.sizeBytes, result.item.sizeBytes).forEach { expected ->
            check(expected == counted.bytes) { "Media download length does not match the original." }
        }
        coroutineContext.ensureActive()
        val published = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        check(resolver.update(destination, published, null, null) == 1) { "Android could not publish the downloaded media." }
        return destination
    }
}

private class CountingMediaOutput(output: OutputStream) : FilterOutputStream(output) {
    var bytes = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        bytes += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        bytes += length
    }
}
