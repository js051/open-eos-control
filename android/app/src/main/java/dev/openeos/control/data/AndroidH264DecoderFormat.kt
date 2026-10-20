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
 * VUI in these bytes. Complete, exactly representable VUI also supplies input hints
 * before the first picture; absent/unfamiliar metadata must never become guessed
 * BT.709 (or an emulator-specific color override). Output pixels still need validation.
 */
internal fun createNativeRtpAvcFormat(
    configuration: H264DecoderConfiguration,
    codec: MediaCodec,
): MediaFormat = MediaFormat.createVideoFormat(
    "video/avc", configuration.width, configuration.height,
).apply {
    setByteBuffer("csd-0", ByteBuffer.wrap(configuration.csd0))
    setByteBuffer("csd-1", ByteBuffer.wrap(configuration.csd1))
    configuration.color.completeDecoderColorHints()?.let { color ->
        setInteger(MediaFormat.KEY_COLOR_STANDARD, when (color.standard) {
            H264DecoderColorHints.Standard.BT709 -> MediaFormat.COLOR_STANDARD_BT709
            H264DecoderColorHints.Standard.BT601_PAL -> MediaFormat.COLOR_STANDARD_BT601_PAL
            H264DecoderColorHints.Standard.BT601_NTSC -> MediaFormat.COLOR_STANDARD_BT601_NTSC
            H264DecoderColorHints.Standard.BT2020 -> MediaFormat.COLOR_STANDARD_BT2020
        })
        setInteger(MediaFormat.KEY_COLOR_RANGE, when (color.range) {
            H264DecoderColorHints.Range.FULL -> MediaFormat.COLOR_RANGE_FULL
            H264DecoderColorHints.Range.LIMITED -> MediaFormat.COLOR_RANGE_LIMITED
        })
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, when (color.transfer) {
            H264DecoderColorHints.Transfer.LINEAR -> MediaFormat.COLOR_TRANSFER_LINEAR
            H264DecoderColorHints.Transfer.SDR_VIDEO -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            H264DecoderColorHints.Transfer.ST2084 -> MediaFormat.COLOR_TRANSFER_ST2084
            H264DecoderColorHints.Transfer.HLG -> MediaFormat.COLOR_TRANSFER_HLG
        })
    }
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
