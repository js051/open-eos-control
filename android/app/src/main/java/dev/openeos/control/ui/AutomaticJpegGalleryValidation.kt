package dev.openeos.control.ui

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import dev.openeos.control.data.AutomaticJpegEnvelope
import dev.openeos.control.data.InvalidAutomaticJpegException
import dev.openeos.control.data.validateAutomaticJpegSequentialEntropy

/** Local pending-row reads only. Neither validation gate can fetch or retry a camera original. */
@RequiresApi(Build.VERSION_CODES.Q)
internal fun validateAutomaticJpegPendingOriginal(
    resolver: ContentResolver,
    destination: Uri,
    byteLength: Long,
    sha256: String,
    checkActive: () -> Unit,
) {
    checkActive()
    val structure = resolver.openInputStream(destination)?.use {
        validateAutomaticJpegSequentialEntropy(it, byteLength, sha256, checkActive)
    } ?: throw InvalidAutomaticJpegException("could not read the pending original")
    checkActive()
    // ImageDecoder can conceal missing sequential MCUs even with the partial listener
    // rejecting input errors. The local gate above must establish entropy coverage first.
    // Native decode remains mandatory; structure/coverage alone cannot establish pixels.
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, destination)) { decoder, info, _ ->
        checkActive()
        val width = info.size.width
        val height = info.size.height
        // ImageDecoder applies EXIF orientation; a transpose changes the display axes, not
        // the input frame dimensions or the already-checked original pixel budget.
        val matches = (width == structure.width && height == structure.height) ||
            (width == structure.height && height == structure.width)
        if (info.mimeType != "image/jpeg" || !matches) {
            throw InvalidAutomaticJpegException("decoder disagrees with JPEG frame")
        }
        val longest = maxOf(width, height)
        val target = minOf(longest, AutomaticJpegEnvelope.DECODE_TARGET_SIDE)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(maxOf(1, (width.toLong() * target / longest).toInt()),
            maxOf(1, (height.toLong() * target / longest).toInt()))
        decoder.setOnPartialImageListener { false }
    }
    try {
        // Native decode is opaque: cancellation is observed before/after it, not claimed
        // to interrupt the decoder internally. No publication may follow cancelled work.
        checkActive()
        if (bitmap.width !in 1..AutomaticJpegEnvelope.MAX_DECODE_SIDE ||
            bitmap.height !in 1..AutomaticJpegEnvelope.MAX_DECODE_SIDE) {
            throw InvalidAutomaticJpegException("decoder output exceeds the automatic limit")
        }
    } finally {
        bitmap.recycle()
    }
}
