package dev.openeos.control.data

/** Public MediaFormat color concepts, without Android dependencies in the ISO mapping. */
internal data class H264DecoderColorHints(
    val standard: Standard,
    val range: Range,
    val transfer: Transfer,
) {
    enum class Standard { BT709, BT601_PAL, BT601_NTSC, BT2020 }
    enum class Range { FULL, LIMITED }
    enum class Transfer { LINEAR, SDR_VIDEO, ST2084, HLG }
}

/**
 * Mirror only complete VUI tuples representable by public MediaFormat constants.
 *
 * ISO values are not Android values. In particular, standard combines BOTH primaries
 * and matrix: mixed/unspecified tuples must not be relabelled from either one alone.
 * These mappings follow AOSP ColorUtils sIsoPrimaries/sIsoMatrixCoeffs/sIsoTransfers
 * and sStandards/sTransfers. Omitted/private/extended standards and transfers stay in
 * the original SPS only; do not approximate them, infer from dimensions, or inspect
 * decoder/device names. This is an input hint, not proof of native pixel conversion.
 */
internal fun H264VuiColor.completeDecoderColorHints(): H264DecoderColorHints? {
    val range = when (fullRange) {
        true -> H264DecoderColorHints.Range.FULL
        false -> H264DecoderColorHints.Range.LIMITED
        null -> return null
    }
    val standard = when {
        primaries == 1 && matrix == 1 -> H264DecoderColorHints.Standard.BT709
        primaries == 5 && matrix in setOf(5, 6) -> H264DecoderColorHints.Standard.BT601_PAL
        primaries in setOf(6, 7) && matrix in setOf(5, 6) -> H264DecoderColorHints.Standard.BT601_NTSC
        primaries == 9 && matrix == 9 -> H264DecoderColorHints.Standard.BT2020
        else -> return null
    }
    val transfer = when (this.transfer) {
        1, 6, 14, 15 -> H264DecoderColorHints.Transfer.SDR_VIDEO
        8 -> H264DecoderColorHints.Transfer.LINEAR
        16 -> H264DecoderColorHints.Transfer.ST2084
        18 -> H264DecoderColorHints.Transfer.HLG
        else -> return null
    }
    return H264DecoderColorHints(standard, range, transfer)
}
