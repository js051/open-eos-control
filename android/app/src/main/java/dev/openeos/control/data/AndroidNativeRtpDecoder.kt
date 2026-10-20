package dev.openeos.control.data

import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build

/**
 * Shared by production RTP and the real decoder/GLES acceptance test.
 * See docs/validation/android-native-rtp-color-2026-10-20.md for the goldfish evidence.
 */
internal fun createNativeRtpAvcDecoder(): MediaCodec {
    val mime = "video/avc"
    val defaultDecoder = MediaCodec.createDecoderByType(mime)
    val replacement = try {
        // Avoid even enumerating alternatives on real devices or other emulator codecs.
        if (nativeRtpUsesGoldfishHostDecoder(defaultDecoder.name, Build.HARDWARE)) {
            val available = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
                .map { it.name }
            nativeRtpReferenceAvcDecoder(defaultDecoder.name, Build.HARDWARE, available)
        } else null
    } catch (_: Exception) {
        // Enumeration failure is not permission to guess an unavailable component.
        null
    }
    if (replacement == null) return defaultDecoder

    // Never overlap physical decoders. A failed release must stop replacement.
    defaultDecoder.release()
    return try {
        MediaCodec.createByCodecName(replacement)
    } catch (_: Exception) {
        // Codec availability can change after enumeration. Preserve native playback
        // rather than install a codec or apply an unverified RGB correction.
        // The acceptance test records the actual codec and still checks BT.709 pixels.
        MediaCodec.createDecoderByType(mime)
    }
}
