package dev.openeos.control.data

enum class RecordingCommand { START, STOP }

/** The REC command was acknowledged; its separate status read failed.
 * The requested direction is local evidence, not a confirmed recording state.
 */
internal class RecordingStatusReadbackException(
    val command: RecordingCommand,
    cause: Exception,
) : Exception("Recording command acknowledged, but the following camera status read failed.", cause)
