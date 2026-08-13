package dev.openeos.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.RandomAccessFile

interface CameraMediaStreamSource : Closeable {
    val item: CameraMediaItem

    suspend fun open(position: Long): CameraMediaStreamHandle

    override fun close() = Unit
}

interface CameraMediaStreamHandle : Closeable {
    val bytesRemaining: Long?
    val contentType: String?

    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

internal class OkHttpCameraMediaStreamSource(
    override val item: CameraMediaItem,
    private val httpClient: OkHttpClient,
    private val requestFactory: (Long) -> List<Request>,
) : CameraMediaStreamSource {
    override suspend fun open(position: Long): CameraMediaStreamHandle = withContext(Dispatchers.IO) {
        require(position >= 0L) { "Media stream position cannot be negative." }
        var latestFailure: String? = null
        requestFactory(position).forEach { request ->
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                latestFailure = "HTTP ${response.code}: ${response.body?.string().orEmpty().take(MAX_STREAM_ERROR_CHARS)}"
                response.close()
                return@forEach
            }
            val body = response.body ?: run {
                response.close()
                latestFailure = "empty response body"
                return@forEach
            }
            val contentRange = response.header("Content-Range")?.toContentRange()
            if (response.code == 206 && contentRange?.start != position) {
                response.close()
                error("Camera returned an invalid media byte range for ${item.name}.")
            }
            if (response.code == 206 && contentRange == null) {
                response.close()
                error("Camera returned a partial video response without a valid Content-Range for ${item.name}.")
            }
            if (
                response.code == 206 && contentRange?.total != null && item.sizeBytes != null &&
                contentRange.total != item.sizeBytes
            ) {
                response.close()
                error("Camera changed the reported size of ${item.name} during playback.")
            }
            val responseLength = body.contentLength().takeIf { it >= 0L }
            val rangeLength = contentRange?.let { it.endInclusive - it.start + 1L }
            if (rangeLength != null && responseLength != null && responseLength != rangeLength) {
                response.close()
                error("Camera returned a media range with an invalid Content-Length for ${item.name}.")
            }
            if (
                response.code == 200 && item.sizeBytes != null && responseLength != null &&
                responseLength != item.sizeBytes
            ) {
                response.close()
                error("Camera returned ${responseLength} bytes for ${item.name}, expected ${item.sizeBytes}.")
            }
            val responseContentType = response.header("Content-Type")?.substringBefore(';')?.trim()
            if (responseContentType.isTextMediaResponse()) {
                latestFailure = "unexpected $responseContentType response"
                response.close()
                return@forEach
            }
            val input = body.byteStream()
            if (response.code == 200 && position > 0L) input.skipExactly(position, item.name)
            val totalBytes = contentRange?.total ?: item.sizeBytes ?: responseLength
            val remaining = rangeLength ?: totalBytes?.let { (it - position).coerceAtLeast(0L) }
                ?: responseLength?.let { length ->
                    if (response.code == 200) (length - position).coerceAtLeast(0L) else length
                }
            return@withContext OkHttpCameraMediaStreamHandle(response, remaining)
        }
        error("Camera video stream failed for ${item.name}: ${latestFailure ?: "no usable media endpoint"}")
    }
}

internal class ChunkedCameraMediaStreamSource(
    override val item: CameraMediaItem,
    private val contentType: String?,
    private val reader: suspend (position: Long, maxBytes: Int) -> ByteArray,
) : CameraMediaStreamSource {
    override suspend fun open(position: Long): CameraMediaStreamHandle {
        val size = requireNotNull(item.sizeBytes) { "${item.name} does not report a size for seeking." }
        require(position in 0L..size) { "Media stream position $position is outside ${item.name}." }
        return object : CameraMediaStreamHandle {
            private var offset = position
            override val bytesRemaining: Long = size - position
            override val contentType: String? = this@ChunkedCameraMediaStreamSource.contentType

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (this.offset >= size) return -1
                val requested = minOf(length.toLong(), size - this.offset, MAX_STREAM_CHUNK_BYTES.toLong()).toInt()
                val bytes = reader(this.offset, requested)
                check(bytes.isNotEmpty() && bytes.size <= requested) {
                    "Camera returned an invalid video chunk for ${item.name} at ${this.offset}."
                }
                bytes.copyInto(buffer, destinationOffset = offset)
                this.offset += bytes.size
                return bytes.size
            }

            override fun close() = Unit
        }
    }
}

internal class FileCameraMediaStreamSource(
    override val item: CameraMediaItem,
    private val file: java.io.File,
    private val contentType: String?,
) : CameraMediaStreamSource {
    override suspend fun open(position: Long): CameraMediaStreamHandle = withContext(Dispatchers.IO) {
        val fileSize = file.length()
        require(position in 0L..fileSize) { "Media stream position $position is outside ${item.name}." }
        val input = RandomAccessFile(file, "r").apply { seek(position) }
        object : CameraMediaStreamHandle {
            override val bytesRemaining: Long = fileSize - position
            override val contentType: String? = this@FileCameraMediaStreamSource.contentType

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                withContext(Dispatchers.IO) { input.read(buffer, offset, length) }

            override fun close() = input.close()
        }
    }
}

private class OkHttpCameraMediaStreamHandle(
    private val response: Response,
    override val bytesRemaining: Long?,
) : CameraMediaStreamHandle {
    private val input = requireNotNull(response.body).byteStream()
    override val contentType: String? = response.header("Content-Type")?.substringBefore(';')?.trim()

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        input.read(buffer, offset, length)
    }

    override fun close() = response.close()
}

private data class HttpContentRange(val start: Long, val endInclusive: Long, val total: Long?)

private fun String.toContentRange(): HttpContentRange? {
    val match = CONTENT_RANGE.matchEntire(trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val endInclusive = match.groupValues[2].toLongOrNull() ?: return null
    if (endInclusive < start) return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (total != null && (total <= endInclusive || start >= total)) return null
    return HttpContentRange(start, endInclusive, total)
}

private fun String?.isTextMediaResponse(): Boolean =
    this != null && (startsWith("text/", ignoreCase = true) || contains("json", ignoreCase = true))

private fun java.io.InputStream.skipExactly(bytes: Long, name: String) {
    var remaining = bytes
    val discard = ByteArray(STREAM_DISCARD_BUFFER_BYTES)
    while (remaining > 0L) {
        val count = read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
        check(count >= 0) { "Camera video $name ended before byte $bytes." }
        if (count == 0) continue
        remaining -= count
    }
}

private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
private const val MAX_STREAM_ERROR_CHARS = 2_000
private const val STREAM_DISCARD_BUFFER_BYTES = 64 * 1024
private const val MAX_STREAM_CHUNK_BYTES = 256 * 1024
