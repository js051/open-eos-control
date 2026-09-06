package dev.openeos.control.data

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CameraFocusStatus { STANDBY, FOCUSED, UNFOCUSED, SERVO_OFF, FOCUSING, UNKNOWN }
enum class CameraFocusFrameKind { GENERIC, NORMAL, FACE, TRACKING, UNKNOWN }

data class CameraFocusFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val status: CameraFocusStatus,
    val kind: CameraFocusFrameKind,
)

data class CameraFocusInfo(val frames: List<CameraFocusFrame>, val magnification: Int, val imageAspectRatio: Float)

internal const val MAX_FOCUS_INFO_BYTES = 256 * 1024
private const val MAX_AF_FRAMES = 512

internal fun parseCcapiFocusInfoPacket(payload: ByteArray): CameraFocusInfo? {
    require(payload.size in 9..MAX_FOCUS_INFO_BYTES) { "Invalid Canon focus information packet size." }
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
    require(buffer.short == 0xFF00.toShort() && buffer.get() == 1.toByte()) {
        "Expected a Canon incidental information packet."
    }
    val length = buffer.int
    require(length > 0 && length == payload.size - 9) { "Invalid Canon focus information length." }
    require(payload[payload.lastIndex - 1] == 0xFF.toByte() && payload.last() == 0xFF.toByte()) {
        "Invalid Canon focus information footer."
    }
    return parseCcapiFocusInfo(JSONObject(String(payload, 7, length, Charsets.UTF_8)))
}

internal fun parseCcapiFocusInfo(root: JSONObject): CameraFocusInfo? {
    val data = root.optJSONObject("liveviewdata") ?: return null
    val image = data.optJSONObject("image") ?: return null
    val originX = image.exactInt("positionx") ?: return null
    val originY = image.exactInt("positiony") ?: return null
    val width = image.exactInt("positionwidth") ?: return null
    val height = image.exactInt("positionheight") ?: return null
    if (originX < 0 || originY < 0 || width <= 0 || height <= 0) return null
    val magnification = data.optJSONObject("zoom")?.exactInt("magnification") ?: return null
    if (magnification <= 0) return null
    val frames = data.optJSONArray("afframe") ?: return null
    require(frames.length() <= MAX_AF_FRAMES) { "Canon AF frame count exceeds the safety limit." }
    // Canon's sample only overlays AF geometry at 1x; do not invent magnified coordinates.
    if (magnification != 1) return CameraFocusInfo(emptyList(), magnification, width.toFloat() / height)
    val result = buildList {
        for (index in 0 until frames.length()) {
            val frame = frames.optJSONObject(index) ?: continue
            if (frame.exactInt("select") != 1) continue
            val raw = frame.exactInt("status") ?: continue
            if (raw !in 0..255) continue
            val x = frame.exactInt("x") ?: continue
            val y = frame.exactInt("y") ?: continue
            val w = frame.exactInt("width") ?: continue
            val h = frame.exactInt("height") ?: continue
            if (w <= 0 || h <= 0) continue
            val left = ((x.toDouble() - originX) / width).coerceIn(0.0, 1.0).toFloat()
            val top = ((y.toDouble() - originY) / height).coerceIn(0.0, 1.0).toFloat()
            val right = ((x.toDouble() + w - originX) / width).coerceIn(0.0, 1.0).toFloat()
            val bottom = ((y.toDouble() + h - originY) / height).coerceIn(0.0, 1.0).toFloat()
            if (right <= left || bottom <= top) continue
            val kind = when (raw and 0xF0) {
                0x00 -> CameraFocusFrameKind.GENERIC
                0x10 -> CameraFocusFrameKind.NORMAL
                0x20 -> CameraFocusFrameKind.FACE
                0x30 -> CameraFocusFrameKind.TRACKING
                else -> CameraFocusFrameKind.UNKNOWN
            }
            val status = if (kind == CameraFocusFrameKind.UNKNOWN) CameraFocusStatus.UNKNOWN else when (raw and 0x0F) {
                0 -> CameraFocusStatus.STANDBY
                1 -> CameraFocusStatus.FOCUSED
                2 -> CameraFocusStatus.UNFOCUSED
                3 -> CameraFocusStatus.SERVO_OFF
                4 -> CameraFocusStatus.FOCUSING
                else -> CameraFocusStatus.UNKNOWN
            }
            add(CameraFocusFrame(left, top, right, bottom, status, kind))
        }
    }
    return CameraFocusInfo(result, magnification, width.toFloat() / height)
}

private fun JSONObject.exactInt(key: String): Int? {
    val number = opt(key) as? Number ?: return null
    val value = number.toDouble()
    return value.takeIf { it.isFinite() && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()
}
