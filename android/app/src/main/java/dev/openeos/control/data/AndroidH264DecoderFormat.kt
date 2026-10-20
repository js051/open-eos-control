package dev.openeos.control.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/**
 * Configure the decoder from the parameter sets referenced by the admitted picture.
 *
 * MediaCodec submits csd-0/csd-1 automatically before media input. Callers therefore
 * queue the prepared picture, not a second copy of its SPS/PPS. Preserve the original
 * VUI in these bytes: absent or unfamiliar color metadata must not become guessed
 * BT.709 (or an emulator-specific color override).
 */
internal fun createNativeRtpAvcFormat(
    configuration: H264DecoderConfiguration,
    codec: MediaCodec,
): MediaFormat = MediaFormat.createVideoFormat(
    "video/avc", configuration.width, configuration.height,
).apply {
    setByteBuffer("csd-0", ByteBuffer.wrap(configuration.csd0))
    setByteBuffer("csd-1", ByteBuffer.wrap(configuration.csd1))
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
    setInteger(MediaFormat.KEY_PRIORITY, 0)
    setInteger(MediaFormat.KEY_OPERATING_RATE, 30)
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        runCatching {
            codec.codecInfo.getCapabilitiesForType("video/avc")
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }.getOrDefault(false)
    ) {
        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }
}
