package dev.openeos.control.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.openeos.control.R

private const val DECODE_FAILURE_PREFIX = "[OpenEOS.LiveViewDecode:v1:"

/** One app-authored failure, retained independently of the UI's current language. */
internal data class LiveViewDecodeFailure(val byteCount: Int, val contentType: String?) {
    init { require(byteCount >= 0) }

    // Fixed-width UTF-16 code units preserve every Kotlin String, including unpaired surrogates.
    // n is null; s followed by zero or more code units is a present (possibly empty) value.
    val messageToken: String get() = buildString {
        append(DECODE_FAILURE_PREFIX)
        append(byteCount)
        append(':')
        if (contentType == null) append('n') else {
            append('s')
            contentType.forEach { append(it.code.toString(16).padStart(4, '0')) }
        }
        append(']')
    }
}

internal class LiveViewFrameDecodeException(byteCount: Int, contentType: String?) :
    IllegalStateException("LiveViewFrameDecodeFailure") {
    val failure = LiveViewDecodeFailure(byteCount, contentType)
}

/** Entire canonical tokens only. Never inspect a substring of a camera/provider diagnostic. */
internal fun liveViewDecodeFailure(message: String): LiveViewDecodeFailure? {
    if (!message.startsWith(DECODE_FAILURE_PREFIX) || !message.endsWith(']')) return null
    val payload = message.substring(DECODE_FAILURE_PREFIX.length, message.length - 1)
    val separator = payload.indexOf(':')
    if (separator < 0) return null
    val byteCount = payload.substring(0, separator).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val encodedType = payload.substring(separator + 1)
    val contentType = when {
        encodedType == "n" -> null
        encodedType.startsWith('s') -> {
            val units = encodedType.substring(1)
            if (units.length % 4 != 0 || units.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            buildString { units.chunked(4).forEach { append(it.toInt(16).toChar()) } }
        }
        else -> return null
    }
    return LiveViewDecodeFailure(byteCount, contentType).takeIf { it.messageToken == message }
}

@Composable
internal fun liveViewDecodeFailureMessage(message: String): String? {
    val failure = liveViewDecodeFailure(message) ?: return null
    return stringResource(
        R.string.camera_error_live_view_decode_failed,
        failure.byteCount,
        failure.contentType ?: stringResource(R.string.camera_error_live_view_content_type_unknown),
    )
}
