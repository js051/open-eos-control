package dev.openeos.control.data

import org.json.JSONException
import org.json.JSONObject

enum class RecordingCommand { START, STOP }

/** The REC command was acknowledged; its separate status read failed.
 * The requested direction is local evidence, not a confirmed recording state.
 */
internal class RecordingStatusReadbackException(
    val command: RecordingCommand,
    cause: Exception,
) : Exception("Recording command acknowledged, but the following camera status read failed.", cause)

/** Only JSON booleans establish REC state; null/missing retains the nullable protocol contract. */
internal fun JSONObject.strictRecordingStatus(): Boolean? {
    val value = opt("recording")
    if (value == null || value === JSONObject.NULL) return null
    return value as? Boolean ?: throw JSONException("Recording status must be a JSON boolean or null.")
}
