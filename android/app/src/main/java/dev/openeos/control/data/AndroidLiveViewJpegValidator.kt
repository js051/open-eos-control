package dev.openeos.control.data

import android.graphics.BitmapFactory

/** Real Android decode evidence for first-source selection, before publication or AUTO fallback. */
internal fun androidDecodesLiveViewJpeg(bytes: ByteArray): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        bounds.outWidth.toLong() * bounds.outHeight > 16_777_216L) return false
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
    return try { decoded.width > 0 && decoded.height > 0 } finally { decoded.recycle() }
}
