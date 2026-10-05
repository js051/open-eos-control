package dev.openeos.control.data

import java.math.BigInteger
import org.json.JSONObject

/** Simulator and Bridge ratings are nullable integers; unknown is distinct from zero stars. */
internal fun JSONObject.normalizedMediaRating(): Int? {
    val value = opt("rating")
    // Both wire contracts publish integers. Reject floating-point values even when apparently
    // whole: Android's JSON parser may already have rounded a non-integral value to a Double.
    // Keep the existing integer-string compatibility without optInt's truncation or fallback 0.
    val integer = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger, is String -> value.toString().toIntOrNull()
        else -> null
    }
    return integer?.takeIf { it in 0..5 }
}
