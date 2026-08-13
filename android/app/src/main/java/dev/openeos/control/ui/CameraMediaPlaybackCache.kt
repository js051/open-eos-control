package dev.openeos.control.ui

import android.system.ErrnoException
import android.system.OsConstants
import dev.openeos.control.data.CameraMediaStreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

@Suppress("UsableSpace")
internal suspend fun cacheCameraMediaForPlayback(
    source: CameraMediaStreamSource,
    cacheDirectory: File,
    onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
): File = withContext(Dispatchers.IO) {
    cacheDirectory.mkdirs()
    val expectedBytes = requireNotNull(source.item.sizeBytes?.takeIf { it > 0L }) {
        "The camera did not report a file size, so a bounded local preview cannot be prepared."
    }
    cacheDirectory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith("camera-video-") }
        .forEach(File::delete)
    val usableSpace = cacheDirectory.usableSpace
    if (!hasPlaybackCacheCapacity(expectedBytes, usableSpace)) {
        throw CameraMediaPlaybackStorageException(expectedBytes, usableSpace)
    }
    val suffix = source.item.name.substringAfterLast('.', "mp4")
        .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        ?.let { ".$it" }
        ?: ".mp4"
    val temporary = File.createTempFile("camera-video-", suffix, cacheDirectory)
    try {
        source.open(0L).use { handle ->
            handle.bytesRemaining?.let { reported ->
                check(reported == expectedBytes) {
                    "The camera reported $reported playback bytes for a file whose size is $expectedBytes."
                }
            }
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(PLAYBACK_COPY_BUFFER_BYTES)
                var transferred = 0L
                onProgress(0L, expectedBytes)
                while (transferred < expectedBytes) {
                    coroutineContext.ensureActive()
                    val requested = minOf(buffer.size.toLong(), expectedBytes - transferred).toInt()
                    val count = handle.read(buffer, 0, requested)
                    if (count < 0) {
                        throw IOException(
                            "Camera video ended after $transferred of $expectedBytes bytes.",
                        )
                    }
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    transferred += count
                    onProgress(transferred, expectedBytes)
                }
                val extra = handle.read(buffer, 0, 1)
                check(extra < 0) { "Camera video exceeds its reported size of $expectedBytes bytes." }
            }
        }
        temporary
    } catch (error: Throwable) {
        temporary.delete()
        throw error
    }
}

internal class CameraMediaPlaybackStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : IOException("There is not enough free space to prepare this video for playback.")

internal fun hasPlaybackCacheCapacity(expectedBytes: Long, usableSpace: Long): Boolean {
    if (expectedBytes <= 0L || usableSpace <= 0L) return false
    val reserveBytes = minOf(PLAYBACK_CACHE_RESERVE_BYTES, usableSpace / 10L)
    return expectedBytes <= usableSpace - reserveBytes
}

internal fun Throwable.isPlaybackStorageFailure(): Boolean {
    var current: Throwable? = this
    repeat(8) {
        when (val error = current) {
            is CameraMediaPlaybackStorageException -> return true
            is ErrnoException -> if (error.errno == OsConstants.ENOSPC) return true
        }
        current = current?.cause
        if (current == null) return false
    }
    return false
}

private const val PLAYBACK_COPY_BUFFER_BYTES = 256 * 1024
private const val PLAYBACK_CACHE_RESERVE_BYTES = 128L * 1024L * 1024L
