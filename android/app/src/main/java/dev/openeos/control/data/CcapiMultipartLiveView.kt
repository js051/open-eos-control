package dev.openeos.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import java.io.Closeable
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

internal suspend fun closeCcapiMultipartSession(session: AutoCloseable?) {
    if (session == null) return
    withContext(Dispatchers.IO) { session.close() }
}

internal const val CCAPI_MULTIPART_MAX_FRAME_BYTES = 12 * 1024 * 1024
private const val MAX_BOUNDARY_CHARS = 200
private const val MAX_LINE_BYTES = 8 * 1024
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_HEADER_COUNT = 32

internal fun parseCcapiMultipartBoundary(contentType: String?): String {
    val value = contentType?.trim().orEmpty()
    val components = value.split(';')
    require(components.firstOrNull()?.trim()?.equals("multipart/x-mixed-replace", ignoreCase = true) == true) {
        "Canon multipart Live View returned ${contentType ?: "no Content-Type"}; expected multipart/x-mixed-replace."
    }
    val rawBoundary = components.drop(1).firstNotNullOfOrNull { parameter ->
        val name = parameter.substringBefore('=', missingDelimiterValue = "").trim()
        if (!name.equals("boundary", ignoreCase = true)) return@firstNotNullOfOrNull null
        parameter.substringAfter('=', missingDelimiterValue = "").trim()
    }.orEmpty()
    val boundary = if (rawBoundary.length >= 2 && rawBoundary.first() == '"' && rawBoundary.last() == '"') {
        rawBoundary.substring(1, rawBoundary.lastIndex)
    } else {
        rawBoundary
    }.removePrefix("--")
    require(boundary.isNotBlank() && boundary.length <= MAX_BOUNDARY_CHARS) {
        "Canon multipart Live View returned a missing or invalid boundary."
    }
    require(boundary.all { it.code in 33..126 && it != '"' }) {
        "Canon multipart Live View returned a non-ASCII boundary."
    }
    return boundary
}

internal class CcapiMultipartFrameReader(
    private val input: InputStream,
    boundary: String,
) {
    private val delimiter = "--$boundary"
    private val closingDelimiter = "$delimiter--"

    fun nextFrame(): ByteArray? {
        when (seekBoundary()) {
            Boundary.NEXT -> Unit
            Boundary.CLOSED -> return null
        }

        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine() ?: error("Canon multipart Live View ended while reading part headers.")
            if (line.isEmpty()) break
            headerBytes += line.toByteArray(StandardCharsets.ISO_8859_1).size
            require(headerBytes <= MAX_HEADER_BYTES && headers.size < MAX_HEADER_COUNT) {
                "Canon multipart Live View part headers exceeded the safety limit."
            }
            val separator = line.indexOf(':')
            require(separator > 0) { "Canon multipart Live View returned a malformed part header." }
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            require(name !in headers) { "Canon multipart Live View returned a duplicate $name header." }
            headers[name] = value
        }

        require(headers["content-type"]?.substringBefore(';')?.trim()
            ?.equals("image/jpeg", ignoreCase = true) == true) {
            "Canon multipart Live View part is not image/jpeg."
        }
        val lengthText = headers["content-length"]
            ?: error("Canon multipart Live View part is missing Content-Length.")
        require(lengthText.isNotEmpty() && lengthText.all(Char::isDigit)) {
            "Canon multipart Live View returned an invalid Content-Length."
        }
        val length = lengthText.toLongOrNull()
            ?: error("Canon multipart Live View returned an invalid Content-Length.")
        require(length in 1..CCAPI_MULTIPART_MAX_FRAME_BYTES.toLong()) {
            "Canon multipart Live View frame length $length is outside the safety limit."
        }
        val frame = ByteArray(length.toInt())
        var offset = 0
        while (offset < frame.size) {
            val count = input.read(frame, offset, frame.size - offset)
            if (count < 0) error("Canon multipart Live View ended before the JPEG frame was complete.")
            offset += count
        }
        require(
            frame.size >= 4 &&
                frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte() &&
                frame[frame.lastIndex - 1] == 0xFF.toByte() && frame[frame.lastIndex] == 0xD9.toByte()
        ) { "Canon multipart Live View part did not contain a complete JPEG frame." }
        return frame
    }

    private fun seekBoundary(): Boundary {
        var scanned = 0
        while (true) {
            val line = readLine() ?: error("Canon multipart Live View ended before the next boundary.")
            scanned += line.length
            require(scanned <= MAX_HEADER_BYTES) {
                "Canon multipart Live View preamble exceeded the safety limit."
            }
            when (line) {
                delimiter -> return Boundary.NEXT
                closingDelimiter -> return Boundary.CLOSED
            }
        }
    }

    private fun readLine(): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.isEmpty()) return null
                break
            }
            if (value == '\n'.code) break
            require(bytes.size < MAX_LINE_BYTES) { "Canon multipart Live View line exceeded the safety limit." }
            bytes.add(value.toByte())
        }
        if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private enum class Boundary { NEXT, CLOSED }
}

internal class CcapiMultipartLiveViewSession(
    private val call: Call,
    private val response: Response,
    private val sourceUrl: String,
    boundary: String,
) : Closeable {
    private val monitor = Object()
    private var latestFrame: ByteArray? = null
    private var producedGeneration = 0L
    private var consumedGeneration = 0L
    private var terminalError: Throwable? = null
    private var closed = false
    private val worker = Thread({ drain(boundary) }, "ccapi-multipart-live-view").apply {
        isDaemon = true
        start()
    }

    fun nextFrame(timeoutSeconds: Long = 15): LiveViewFrame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        synchronized(monitor) {
            while (!closed && producedGeneration <= consumedGeneration && terminalError == null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) error("Timed out waiting for the next Canon multipart Live View frame.")
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
            if (producedGeneration > consumedGeneration) {
                val frame = checkNotNull(latestFrame)
                consumedGeneration = producedGeneration
                return LiveViewFrame(frame.copyOf(), "image/jpeg", sourceUrl)
            }
            terminalError?.let { throw IllegalStateException("Canon multipart Live View stream failed.", it) }
            check(!closed) { "Canon multipart Live View stream is closed." }
            error("Canon multipart Live View did not produce a frame.")
        }
    }

    private fun drain(boundary: String) {
        try {
            val body = response.body ?: error("Canon multipart Live View returned an empty response body.")
            val reader = CcapiMultipartFrameReader(body.byteStream(), boundary)
            while (!closed) {
                val frame = reader.nextFrame() ?: error("Canon multipart Live View stream ended unexpectedly.")
                synchronized(monitor) {
                    latestFrame = frame
                    producedGeneration += 1
                    monitor.notifyAll()
                }
            }
        } catch (exception: Throwable) {
            synchronized(monitor) {
                if (!closed) terminalError = exception
                monitor.notifyAll()
            }
        } finally {
            response.close()
        }
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            monitor.notifyAll()
        }
        call.cancel()
        response.close()
        worker.interrupt()
    }
}
