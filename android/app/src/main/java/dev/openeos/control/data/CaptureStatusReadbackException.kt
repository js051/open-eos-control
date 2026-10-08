package dev.openeos.control.data

/** The shutter command was acknowledged; its separate status read failed.
 * This does not establish an exposure or a newly visible media item.
 */
internal class CaptureStatusReadbackException(cause: Exception) : Exception(
    "Shutter command acknowledged, but the following camera status read failed.", cause,
)
