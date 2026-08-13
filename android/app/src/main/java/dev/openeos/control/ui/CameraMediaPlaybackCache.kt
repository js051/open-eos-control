package dev.openeos.control.ui

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
    require(expectedBytes <= MAX_AUTOMATIC_PLAYBACK_CACHE_BYTES) {
        "This video is too large for automatic local playback preparation. Download the original instead."
    }
    cacheDirectory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith("camera-video-") }
        .forEach(File::delete)
    val usableSpace = cacheDirectory.usableSpace
    val reserveBytes = minOf(PLAYBACK_CACHE_RESERVE_BYTES, usableSpace / 10L)
    check(usableSpace - reserveBytes >= expectedBytes) {
        "There is not enough free space to prepare this video for playback."
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

private const val PLAYBACK_COPY_BUFFER_BYTES = 256 * 1024
private const val PLAYBACK_CACHE_RESERVE_BYTES = 128L * 1024L * 1024L
private const val MAX_AUTOMATIC_PLAYBACK_CACHE_BYTES = 1L * 1024L * 1024L * 1024L
