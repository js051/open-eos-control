package dev.openeos.control.data

/** Exact integer pagination evidence. Keep negative diagnostics distinct without accepting malformed counts. */
internal fun validatedMediaPageCount(value: Any?): Int {
    val count = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Camera returned an invalid media page count.")
    }
    check(count >= 0L) { "Camera returned a negative media page count." }
    check(count <= Int.MAX_VALUE.toLong()) { "Camera returned an invalid media page count." }
    return count.toInt()
}
