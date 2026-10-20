package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

class H264DecoderColorHintsTest {
    private val bt709 = H264VuiColor(false, 1, 1, 1)

    @Test fun explicitBt709FixtureTupleMapsWithoutChangingItsIsoValues() {
        assertEquals(H264DecoderColorHints(
            H264DecoderColorHints.Standard.BT709,
            H264DecoderColorHints.Range.LIMITED,
            H264DecoderColorHints.Transfer.SDR_VIDEO,
        ), bt709.completeDecoderColorHints())
        assertEquals(H264VuiColor(false, 1, 1, 1), bt709)
    }

    @Test fun fullAndLimitedRangesAreExplicitAndNeverInferred() {
        assertEquals(H264DecoderColorHints.Range.FULL,
            bt709.copy(fullRange = true).completeDecoderColorHints()?.range)
        assertEquals(H264DecoderColorHints.Range.LIMITED, bt709.completeDecoderColorHints()?.range)
        assertNull(bt709.copy(fullRange = null).completeDecoderColorHints())
    }

    @Test fun pal601RequiresBothPalPrimariesAndA601Matrix() {
        for (matrix in listOf(5, 6)) {
            assertEquals(H264DecoderColorHints.Standard.BT601_PAL,
                bt709.copy(primaries = 5, matrix = matrix).completeDecoderColorHints()?.standard)
        }
    }

    @Test fun ntsc601PreservesBothIsoAliasesWithoutTreatingTheMatrixAsPrimaries() {
        for (primaries in listOf(6, 7)) for (matrix in listOf(5, 6)) {
            assertEquals(H264DecoderColorHints.Standard.BT601_NTSC,
                bt709.copy(primaries = primaries, matrix = matrix).completeDecoderColorHints()?.standard)
        }
    }

    @Test fun bt2020NonConstantLuminanceIsNotConfusedWithConstantLuminance() {
        assertEquals(H264DecoderColorHints.Standard.BT2020,
            bt709.copy(primaries = 9, matrix = 9).completeDecoderColorHints()?.standard)
        assertNull(bt709.copy(primaries = 9, matrix = 10).completeDecoderColorHints())
    }

    @Test fun onlyTheFourAospSdrTransferAliasesMapToSdrVideo() {
        for (transfer in listOf(1, 6, 14, 15)) {
            assertEquals(H264DecoderColorHints.Transfer.SDR_VIDEO,
                bt709.copy(transfer = transfer).completeDecoderColorHints()?.transfer)
        }
    }

    @Test fun linearPqAndHlgRemainDistinctFromSdr() {
        for ((iso, expected) in listOf(
            8 to H264DecoderColorHints.Transfer.LINEAR,
            16 to H264DecoderColorHints.Transfer.ST2084,
            18 to H264DecoderColorHints.Transfer.HLG,
        )) {
            assertEquals(expected, bt709.copy(transfer = iso).completeDecoderColorHints()?.transfer)
        }
    }

    @Test fun anyMissingComponentOmitsTheEntireHintTuple() {
        for (color in listOf(
            H264VuiColor(), H264VuiColor(false), bt709.copy(fullRange = null),
            bt709.copy(primaries = null), bt709.copy(transfer = null), bt709.copy(matrix = null),
        )) assertNull(color.completeDecoderColorHints())
    }

    @Test fun unspecifiedAndUnrepresentableIsoValuesNeverBecomeDefaults() {
        for (value in listOf(-1, 0, 2, 3, 255, 256)) {
            assertNull(bt709.copy(primaries = value).completeDecoderColorHints())
            assertNull(bt709.copy(matrix = value).completeDecoderColorHints())
            assertNull(bt709.copy(transfer = value).completeDecoderColorHints())
        }
        // Gamma, SMPTE240M, logarithmic, extended gamut, sRGB and ST428 transfers
        // are not any of the public transfer constants this adapter represents.
        for (value in listOf(4, 5, 7, 9, 10, 11, 12, 13, 17)) {
            assertNull(bt709.copy(transfer = value).completeDecoderColorHints())
        }
    }

    @Test fun mixedAndPrivateStandardsAreNotRelabelledAsTheirNearestPublicStandard() {
        for ((primaries, matrix) in listOf(
            1 to 5, 1 to 6, 5 to 1, 6 to 1, 7 to 7, 6 to 7,
            9 to 1, 1 to 9, 9 to 10, 4 to 4, 8 to 1, 11 to 1, 12 to 1,
        )) {
            assertNull("ISO primaries=$primaries matrix=$matrix",
                bt709.copy(primaries = primaries, matrix = matrix).completeDecoderColorHints())
        }
    }
}
