package dev.openeos.control.data

import java.math.BigInteger

/** Media-only wire decoding. Unknown values never become false, zero, or confirmation. */
internal fun normalizedMediaMetadataInteger(value: Any?, allowed: Set<Int>): Int? {
    val parsed = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> value.toString().toIntOrNull()
        is String -> allowed.firstOrNull { value == it.toString() }
        else -> null // Includes floating JSON numbers, booleans, null, arrays and objects.
    }
    return parsed?.takeIf { it in allowed }
}

internal fun normalizedMediaMetadataBoolean(value: Any?): Boolean? = value as? Boolean

internal fun canonicalMediaMetadataFlag(value: Any?): Boolean? = when (value) {
    "enable" -> true
    "disable" -> false
    else -> null
}

internal fun canonicalMediaMetadataRating(value: Any?): Int? = when (value) {
    "off" -> 0
    "1" -> 1
    "2" -> 2
    "3" -> 3
    "4" -> 4
    "5" -> 5
    else -> null
}

internal fun canonicalMediaMetadataRotation(value: Any?): Int? = when (value) {
    "0" -> 0
    "90" -> 90
    "180" -> 180
    "270" -> 270
    else -> null
}

enum class MediaMetadataField { PROTECTION, ARCHIVE, RATING, ROTATION }

data class MediaMetadataTarget(val field: MediaMetadataField, val value: Int) {
    init {
        require(when (field) {
            MediaMetadataField.PROTECTION, MediaMetadataField.ARCHIVE -> value in 0..1
            MediaMetadataField.RATING -> value in 0..5
            MediaMetadataField.ROTATION -> value in setOf(0, 90, 180, 270)
        })
    }

    fun matches(requestedId: String, readback: MediaMetadataReadback): Boolean =
        requestedId.isNotBlank() && readback.id == requestedId && when (field) {
            MediaMetadataField.PROTECTION -> readback.protected == (value == 1)
            MediaMetadataField.ARCHIVE -> readback.archived == (value == 1)
            MediaMetadataField.RATING -> readback.rating == value
            MediaMetadataField.ROTATION -> readback.rotationDegrees == value
        }
}

data class MediaMetadataReadback(
    val id: String,
    val protected: Boolean? = null,
    val archived: Boolean? = null,
    val rating: Int? = null,
    val rotationDegrees: Int? = null,
)
