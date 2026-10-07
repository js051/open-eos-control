package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaTransferProgress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val SAVED_JPEG_STAGING_RESERVE_BYTES = 64L * 1024 * 1024
internal const val SAVED_JPEG_MAX_MANIFEST_BYTES = 16 * 1024 * 1024

internal class CameraImportStagingReservation internal constructor(val sessionId: String) {
    @Volatile var cleanupUnconfirmed: Boolean = false
        internal set
}

internal class SavedJpegSourceChangedException : IllegalStateException(
    "The saved JPEG no longer matches the original that was published to Gallery.",
)

internal class SavedJpegStagingSpaceException : IllegalStateException(
    "There is not enough writable phone storage to prepare the selected JPEGs for Serein.",
)

/** Saturation makes an unrepresentable total fail the space check, never wrap into a small batch. */
internal fun savedJpegStagingBytes(originals: List<CameraImportOriginalEvidence>): Long {
    require(originals.isNotEmpty() && originals.size <= DeliveredJpegStore.MAX_ENTRIES) {
        "Select between 1 and ${DeliveredJpegStore.MAX_ENTRIES} saved JPEGs."
    }
    return originals.fold(0L) { total, original ->
        require(original.byteLength > 0 && original.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "The saved JPEG has no complete publication evidence."
        }
        if (Long.MAX_VALUE - total < original.byteLength) Long.MAX_VALUE else total + original.byteLength
    }
}

internal fun requireSavedJpegStagingSpace(requiredBytes: Long, usableBytes: Long, writable: Boolean) {
    require(requiredBytes > 0) { "A staged JPEG must contain bytes." }
    if (!writable || usableBytes < SAVED_JPEG_STAGING_RESERVE_BYTES ||
        requiredBytes > usableBytes - SAVED_JPEG_STAGING_RESERVE_BYTES
    ) throw SavedJpegStagingSpaceException()
}

/**
 * Copy a local original once. The caller owns both streams and its unpublished staging file.
 * Inspect at most one excess byte, and require the digest recorded at Gallery publication;
 * a same-length replacement is not the original the user selected.
 */
internal suspend fun copyPublishedJpegOriginal(
    input: InputStream,
    output: OutputStream,
    expected: CameraImportOriginalEvidence,
    onProgress: (CameraMediaTransferProgress) -> Unit,
): Long {
    require(expected.byteLength > 0 && expected.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
        "The saved JPEG has no complete publication evidence."
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = expected.byteLength - copied
        val requested = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
        val count = input.read(buffer, 0, requested)
        if (count < 0) break
        check(count != 0) { "Android could not read the saved JPEG." }
        if (count > remaining) throw SavedJpegSourceChangedException()
        currentCoroutineContext().ensureActive()
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        copied += count
        onProgress(CameraMediaTransferProgress(copied, expected.byteLength))
    }
    currentCoroutineContext().ensureActive()
    val actualHash = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    if (copied != expected.byteLength || !actualHash.equals(expected.sha256, ignoreCase = true)) {
        throw SavedJpegSourceChangedException()
    }
    return copied
}
