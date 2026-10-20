package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class H264DecoderConfigurationTest {
    @Test fun originalFixtureHasExactCsdPictureAndBt709Metadata() {
        assertEquals("b48b0a7e9a6a54e6b3f34298ef8cbde86034fe1ed850bb3035f1d35c43d88fdf", sha(fixture))
        val prepared = requireNotNull(H264DecoderConfigurationStore().prepare(au(fixture)))
        assertEquals(64, prepared.configuration.width)
        assertEquals(48, prepared.configuration.height)
        assertEquals(100, prepared.configuration.profileIdc)
        assertEquals(1, prepared.configuration.chromaFormatIdc)
        assertEquals(8, prepared.configuration.bitDepthLuma)
        assertEquals(8, prepared.configuration.bitDepthChroma)
        assertEquals(H264VuiColor(false, 1, 1, 1), prepared.configuration.color)
        assertArrayEquals(fixture.copyOfRange(0, 27), prepared.configuration.csd0)
        assertArrayEquals(fixture.copyOfRange(27, 37), prepared.configuration.csd1)
        assertArrayEquals(fixture.copyOfRange(37, fixture.size), prepared.mediaBytes)
        assertEquals(90_000L, prepared.rtpTimestamp)
        assertTrue(prepared.keyFrame)
    }

    @Test fun zeroValuedGolombFieldsHaveNoSuffixAndDoNotReadTheFollowingBits() {
        // SPS ID, PPS IDs, frame-number/POC sizes, refs and first_mb are all UE(0).
        val sequence = sps(id = 0, profile = 66, poc = 0, color = null)
        val pictureSet = pps(id = 0, sequenceId = 0)
        val prepared = requireNotNull(H264DecoderConfigurationStore(listOf(sequence, pictureSet)).prepare(au(slice(ppsId = 0))))
        assertEquals(64, prepared.configuration.width)
        assertEquals(48, prepared.configuration.height)
        assertArrayEquals(sequence, prepared.configuration.csd0)
        assertArrayEquals(pictureSet, prepared.configuration.csd1)
        assertArrayEquals(slice(), prepared.mediaBytes)
    }

    @Test fun absentVuiDoesNotGuessColor() {
        assertEquals(H264VuiColor(), config(sps(color = null)).color)
    }

    @Test fun unspecifiedIsoValuesRemainUnknown() {
        assertEquals(H264VuiColor(false), config(sps(color = H264VuiColor(false, 2, 2, 2))).color)
    }

    @Test fun mixedColorTupleIsPreservedInsteadOfRelabelledBt709() {
        assertEquals(H264VuiColor(true, 1, 6, 5), config(sps(color = H264VuiColor(true, 1, 6, 5))).color)
    }

    @Test fun dimensionsAccountFor420Cropping() {
        val config = config(sps(widthMbs = 120, heightMaps = 68, crop = intArrayOf(0, 0, 0, 4)))
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
    }

    @Test fun interlacedDimensionsAndCropUnitsAreDistinct() {
        val config = config(sps(widthMbs = 4, heightMaps = 2, frameOnly = false, crop = intArrayOf(1, 1, 1, 1)))
        assertEquals(60, config.width)
        assertEquals(56, config.height)
    }

    @Test fun separateColorPlaneUsesMonochromeCropUnits() {
        val config = config(sps(chroma = 3, separatePlane = true, crop = intArrayOf(1, 1, 1, 1)))
        assertEquals(62, config.width)
        assertEquals(46, config.height)
    }

    @Test fun scalingListsAreSkippedWithoutLosingDimensions() {
        assertEquals(64, config(sps(scaling = true)).width)
    }

    @Test fun pocZeroAndOneSyntaxRemainAligned() {
        for (poc in listOf(0, 1, 2)) assertEquals(48, config(sps(poc = poc)).height)
    }

    @Test fun baselineProfileUsesImplicit420AndEightBitSyntax() {
        val config = config(sps(profile = 66))
        assertEquals(66, config.profileIdc)
        assertEquals(1, config.chromaFormatIdc)
        assertEquals(8, config.bitDepthLuma)
    }

    @Test fun higherBitDepthIsReportedNotSilentlyAdmittedAsEightBit() {
        val config = config(sps(profile = 110, depth = 10))
        assertEquals(10, config.bitDepthLuma)
        assertEquals(10, config.bitDepthChroma)
    }

    @Test fun hrdAndBitstreamRestrictionRemainBoundedAndAligned() {
        assertEquals(H264VuiColor(false, 1, 1, 1), config(sps(hrd = true, restriction = true)).color)
    }

    @Test fun validSdpSetsConfigurePictureWithoutInBandDuplicates() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val picture = slice()
        val prepared = requireNotNull(store.prepare(au(picture)))
        assertArrayEquals(picture, prepared.mediaBytes)
        assertArrayEquals(sps(), prepared.configuration.csd0)
    }

    @Test fun resolutionUsesPpsAndSpsIdsRatherThanFirstPair() {
        val store = H264DecoderConfigurationStore(listOf(
            sps(id = 2, widthMbs = 8), pps(id = 3, sequenceId = 2),
            pps(id = 0, sequenceId = 1), sps(id = 1, widthMbs = 4),
        ))
        assertEquals(128, requireNotNull(store.prepare(au(slice(ppsId = 3)))).configuration.width)
        assertEquals(64, requireNotNull(store.prepare(au(slice(ppsId = 0)))).configuration.width)
    }

    @Test fun unrelatedSpsCannotCompleteMismatchedPps() {
        val store = H264DecoderConfigurationStore(listOf(sps(id = 0), pps(sequenceId = 1)))
        assertNull(store.prepare(au(slice())))
        assertNotNull(store.prepare(au(sps(id = 1) + slice())))
    }

    @Test fun missingPpsDoesNotReplayTheOldPictureWhenItArrives() {
        val store = H264DecoderConfigurationStore(listOf(sps()))
        assertNull(store.prepare(au(slice(), timestamp = 10)))
        assertNull(store.prepare(au(pps(), timestamp = 20)))
        assertEquals(30L, requireNotNull(store.prepare(au(slice(), timestamp = 30))).rtpTimestamp)
    }

    @Test fun repeatedIdenticalParametersHaveContentEquality() {
        val store = H264DecoderConfigurationStore()
        val first = requireNotNull(store.prepare(au(sps() + pps() + slice()))).configuration
        val second = requireNotNull(store.prepare(au(sps() + pps() + slice()))).configuration
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test fun reusedSpsIdWithChangedBytesProducesDifferentConfiguration() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val first = requireNotNull(store.prepare(au(slice()))).configuration
        val second = requireNotNull(store.prepare(au(sps(widthMbs = 8) + slice()))).configuration
        assertNotEquals(first, second)
        assertEquals(64, first.width)
        assertEquals(128, second.width)
    }

    @Test fun reusedPpsIdFollowsItsNewReferencedSps() {
        val store = H264DecoderConfigurationStore(listOf(sps(), sps(id = 1, widthMbs = 8), pps()))
        val first = requireNotNull(store.prepare(au(slice()))).configuration
        val second = requireNotNull(store.prepare(au(pps(sequenceId = 1) + slice()))).configuration
        assertNotEquals(first, second)
        assertEquals(128, second.width)
    }

    @Test fun unreferencedNewSetsDoNotChangeCurrentPictureConfiguration() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val first = requireNotNull(store.prepare(au(slice()))).configuration
        val next = requireNotNull(store.prepare(au(sps(id = 1, widthMbs = 8) + slice()))).configuration
        assertEquals(first, next)
    }

    @Test fun parameterAnnouncementAfterVclAffectsOnlyFuturePictures() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val first = requireNotNull(store.prepare(au(slice() + sps(widthMbs = 8))))
        val next = requireNotNull(store.prepare(au(slice())))
        assertEquals(64, first.configuration.width)
        assertEquals(128, next.configuration.width)
        assertArrayEquals(slice(), first.mediaBytes)
    }

    @Test fun parameterSetsAfterAnUnresolvedPictureCannotRetroactivelyResolveIt() {
        val store = H264DecoderConfigurationStore()
        assertNull(store.prepare(au(slice() + sps() + pps())))
        assertNotNull(store.prepare(au(slice())))
    }

    @Test fun snapshotsDefendAgainstCallerAndGetterMutation() {
        val sequence = sps()
        val pictureSet = pps()
        val store = H264DecoderConfigurationStore(listOf(sequence, pictureSet))
        sequence.fill(0)
        pictureSet.fill(0)
        val input = slice()
        val prepared = requireNotNull(store.prepare(au(input)))
        input.fill(0)
        prepared.configuration.csd0.fill(0)
        prepared.configuration.csd1.fill(0)
        prepared.mediaBytes.fill(0)
        assertArrayEquals(sps(), prepared.configuration.csd0)
        assertArrayEquals(pps(), prepared.configuration.csd1)
        assertArrayEquals(slice(), prepared.mediaBytes)
    }

    @Test fun configOnlyTrafficUpdatesPresenceButNeverReturnsPicture() {
        val store = H264DecoderConfigurationStore()
        assertNull(store.prepare(au(sps())))
        assertTrue(store.hasSequenceParameterSet)
        assertFalse(store.hasPictureParameterSet)
        assertNull(store.prepare(au(pps())))
        assertTrue(store.hasPictureParameterSet)
    }

    @Test fun truncatedSetsRemainObservedButCannotConfigure() {
        val store = H264DecoderConfigurationStore(listOf(nal(0x67, 0x42), nal(0x68, 1)))
        assertTrue(store.hasSequenceParameterSet)
        assertTrue(store.hasPictureParameterSet)
        assertNull(store.prepare(au(slice())))
    }

    @Test fun inBandTruncatedSetsPreserveBothPresenceDiagnostics() {
        val store = H264DecoderConfigurationStore()
        assertNull(store.prepare(au(nal(0x67, 0x42) + nal(0x68, 1) + slice())))
        assertTrue(store.hasSequenceParameterSet)
        assertTrue(store.hasPictureParameterSet)
    }

    @Test fun malformedReplacementInvalidatesAmbiguousCachedConfiguration() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(nal(0x67, 100))))
        assertNull(store.prepare(au(slice())))
        assertNotNull(store.prepare(au(sps() + pps() + slice())))
    }

    @Test fun keyFrameComesFromActualVclNotCallerFlag() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertFalse(requireNotNull(store.prepare(au(slice(idr = false), keyFrame = true))).keyFrame)
        assertTrue(requireNotNull(store.prepare(au(slice(), keyFrame = false))).keyFrame)
    }

    @Test fun mixedIdrAndNonIdrCannotSeedOnePicture() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(slice() + slice(idr = false))))
    }

    @Test fun slicesReferencingDifferentPpsAreRejected() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps(), pps(id = 1)))
        assertNull(store.prepare(au(slice() + slice(ppsId = 1))))
    }

    @Test fun parameterChangeBetweenSlicesCannotRewriteOnePicture() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(slice() + sps(widthMbs = 8) + slice())))
    }

    @Test fun audSeiAndThreeByteMediaStartCodesRemainByteExact() {
        val aud = byteArrayOf(0, 0, 1, 9, 0xF0.toByte())
        val sei = nal(6, 5, 1, 0x42, 0x80)
        val picture = slice().copyOfRange(1, slice().size)
        val prepared = requireNotNull(H264DecoderConfigurationStore().prepare(au(aud + sps() + sei + pps() + picture)))
        assertArrayEquals(aud + sei + picture, prepared.mediaBytes)
    }

    @Test fun legalLeadingZerosBeforeAudAndSeiRemainByteExact() {
        val leading = byteArrayOf(0, 0)
        val aud = nal(9, 0xf0)
        val sei = nal(6, 5, 1, 0x42, 0x80)
        val prepared = requireNotNull(H264DecoderConfigurationStore().prepare(au(leading + aud + sei + sps() + pps() + slice())))
        assertArrayEquals(leading + aud + sei + slice(), prepared.mediaBytes)
    }

    @Test fun standaloneAudSeiAndFillerPrecedeTheNextActualPictureExactlyOnce() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val prefix = nal(9, 0xf0) + nal(6, 5, 1, 0x42, 0x80) + nal(12, 0xff, 0x80)
        assertNull(store.prepare(au(prefix, timestamp = 1)))
        val picture = requireNotNull(store.prepare(au(slice(), timestamp = 2)))
        assertArrayEquals(prefix + slice(), picture.mediaBytes)
        assertEquals(2L, picture.rtpTimestamp)
        assertArrayEquals(slice(), requireNotNull(store.prepare(au(slice(), timestamp = 3))).mediaBytes)
    }

    @Test fun transportLossSurvivesConfigOnlyAndDependentPicturesUntilResolvedIdr() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNotNull(store.prepare(au(slice())))
        assertNull(store.prepare(au(sps() + pps()).copy(discontinuity = true)))
        assertNull(store.prepare(au(slice(idr = false))))
        assertNull(store.prepare(au(slice(idr = false)).copy(discontinuity = true)))
        val recovered = requireNotNull(store.prepare(au(slice()).copy(discontinuity = true)))
        assertArrayEquals(sps(), recovered.configuration.csd0) // Known parameter inventory survives.
        assertArrayEquals(pps(), recovered.configuration.csd1)
        assertNotNull(store.prepare(au(slice(idr = false))))
    }

    @Test fun flaggedUnresolvedIdrCannotClearTheResolversRecoveryRequirement() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(slice(ppsId = 1)).copy(discontinuity = true)))
        // The transport has emitted an IDR and may clear its Boolean, but resolution failed.
        assertNull(store.prepare(au(pps(id = 1))))
        assertNull(store.prepare(au(slice(ppsId = 1, idr = false))))
        assertNotNull(store.prepare(au(slice(ppsId = 1))))
        assertNotNull(store.prepare(au(slice(ppsId = 1, idr = false))))
    }

    @Test fun recoveryDropsStandalonePrefixesButPreservesMetadataCoLocatedWithItsIdr() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val oldPrefix = nal(6, 5, 1, 0x11, 0x80)
        val duringRecovery = nal(6, 5, 1, 0x22, 0x80)
        val ownedPrefix = nal(9, 0xf0) + nal(6, 5, 1, 0x33, 0x80)
        assertNull(store.prepare(au(oldPrefix)))
        assertNull(store.prepare(au(duringRecovery).copy(discontinuity = true)))
        assertNull(store.prepare(au(sps()).copy(discontinuity = true))) // Another flagged interval.
        assertNull(store.prepare(au(duringRecovery)))
        val recovered = requireNotNull(store.prepare(au(ownedPrefix + slice()).copy(discontinuity = true)))
        assertArrayEquals(ownedPrefix + slice(), recovered.mediaBytes)
        // Normal standalone metadata resumes, without replaying either discarded prefix.
        assertNull(store.prepare(au(duringRecovery)))
        assertArrayEquals(duringRecovery + slice(idr = false),
            requireNotNull(store.prepare(au(slice(idr = false)))).mediaBytes)
    }

    @Test fun rejectedFlaggedIdrCannotLeakLaterStandaloneMetadataIntoRecovery() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val prefix = nal(6, 5, 1, 0x42, 0x80)
        assertNull(store.prepare(au(slice(ppsId = 1)).copy(discontinuity = true)))
        assertNull(store.prepare(au(prefix))) // Transport Boolean can already have cleared.
        assertNull(store.prepare(au(pps(id = 1))))
        assertArrayEquals(slice(ppsId = 1), requireNotNull(store.prepare(au(slice(ppsId = 1)))).mediaBytes)
    }

    @Test fun ancillaryBudgetOverflowCannotLeakOldPrefixIntoRecoveryPicture() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        val fullBudget = nal(6) + ByteArray(8 * 1024 * 1024 - 5) { 0x55 }
        assertNull(store.prepare(au(fullBudget)))
        assertNull(store.prepare(au(slice())))
        val recovered = requireNotNull(store.prepare(au(sps() + pps() + slice())))
        assertArrayEquals(slice(), recovered.mediaBytes)
    }

    @Test fun accumulatedAncillaryNalCountIsBoundedAcrossConfigOnlyUnits() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        repeat(1025) { assertNull(store.prepare(au(nal(9, 0xf0)))) }
        val recovered = requireNotNull(store.prepare(au(sps() + pps() + slice())))
        assertArrayEquals(slice(), recovered.mediaBytes)
    }

    @Test fun unresolvedPictureConsumesPendingAncillaryAndRequiresIdrRecovery() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(nal(6, 5, 1, 0x42, 0x80))))
        assertNull(store.prepare(au(slice(ppsId = 1))))
        assertNull(store.prepare(au(slice(idr = false))))
        assertArrayEquals(slice(), requireNotNull(store.prepare(au(slice()))).mediaBytes)
    }

    @Test fun renderingRetirementDiscardsOnlyPendingMedia() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(nal(6, 5, 1, 0x42, 0x80))))
        store.discardPendingMedia()
        val next = requireNotNull(store.prepare(au(slice())))
        assertArrayEquals(slice(), next.mediaBytes)
        assertArrayEquals(sps(), next.configuration.csd0)
        assertTrue(store.hasSequenceParameterSet)
    }

    @Test fun terminatorsAndUnsupportedAncillaryAreNotReplayedOntoLaterPicture() {
        for (type in listOf(10, 11, 13, 14, 15, 16, 17, 18, 22, 23)) {
            val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
            assertNull(store.prepare(au(nal(6, 5, 1, 0x42, 0x80))))
            assertNull(store.prepare(au(nal(type, 0x80))))
            assertNull(store.prepare(au(sps() + pps() + slice(idr = false))))
            assertArrayEquals(slice(), requireNotNull(store.prepare(au(slice()))).mediaBytes)
        }
    }

    @Test fun fragmentedSpsIsResolvedFromCompletedAnnexBBytes() {
        val sequence = sps().copyOfRange(4, sps().size)
        val depacketizer = H264RtpDepacketizer(103)
        val split = sequence.size / 2
        assertNull(depacketizer.accept(packet(1, byteArrayOf(0x7c, 0x87.toByte()) + sequence.copyOfRange(1, split), false)))
        val complete = requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x7c, 0x47) + sequence.copyOfRange(split, sequence.size), true)))
        assertNull(complete.sequenceParameterSet) // Existing optional field cannot represent this case.
        val store = H264DecoderConfigurationStore()
        assertNull(store.prepare(complete))
        assertNotNull(store.prepare(au(pps() + slice())))
    }

    @Test fun oversizedParametersAndInitialInventoriesAreRejected() {
        assertNull(H264DecoderConfigurationStore().prepare(au(nal(0x67) + ByteArray(65_536) { 0x55 })))
        val many = (0..8).map { sps(id = it) }
        assertNull(H264DecoderConfigurationStore(many).prepare(au(pps() + slice())))
    }

    @Test fun inBandParameterInventoryCannotGrowUnbounded() {
        val store = H264DecoderConfigurationStore()
        assertNull(store.prepare(au((0..8).fold(byteArrayOf()) { bytes, id -> bytes + sps(id = id) })))
        assertNull(store.prepare(au(pps() + slice())))
    }

    @Test fun truncatedAndOverlongGolombSyntaxIsRejected() {
        for (bytes in listOf(nal(0x67, 100, 0, 10), nal(0x67, 100, 0, 10, 0, 0, 3, 0, 0, 3, 0))) {
            assertNull(H264DecoderConfigurationStore().prepare(au(bytes + pps() + slice())))
        }
    }

    @Test fun illegalParameterEmulationPreventionCannotProduceAConfiguration() {
        for (bytes in listOf(
            nal(0x67, 100, 0, 10, 0, 0, 3, 4, 0x80),
            nal(0x67, 100, 0, 10, 0, 0, 2, 0x80),
        )) assertNull(H264DecoderConfigurationStore().prepare(au(bytes + pps() + slice())))
    }

    @Test fun truncatedVuiAndInvalidCropAreRejected() {
        assertNull(H264DecoderConfigurationStore().prepare(au(sps().dropLast(1).toByteArray() + pps() + slice())))
        assertNull(H264DecoderConfigurationStore().prepare(au(sps(crop = intArrayOf(100, 0, 0, 0)) + pps() + slice())))
    }

    @Test fun hugeCodedWidthCannotHideBehindTinyCrop() {
        val hidden = sps(widthMbs = 300, crop = intArrayOf(0, 2399, 0, 0))
        assertNull(H264DecoderConfigurationStore().prepare(au(hidden + pps() + slice())))
    }

    @Test fun codedPixelBudgetIsCheckedBeforeCrop() {
        val hidden = sps(widthMbs = 256, heightMaps = 256, crop = intArrayOf(0, 2047, 0, 2047))
        assertNull(H264DecoderConfigurationStore().prepare(au(hidden + pps() + slice())))
    }

    @Test fun codedDimensionsAtExistingBudgetRemainAccepted() {
        val config = config(sps(widthMbs = 256, heightMaps = 128))
        assertEquals(4096, config.width)
        assertEquals(2048, config.height)
    }

    @Test fun unsupportedPartitionedVclCannotMasqueradeAsAncillaryData() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(nal(0x42, 0x80) + slice())))
    }

    @Test fun forbiddenHeaderAndMissingStartCodeAreRejected() {
        val store = H264DecoderConfigurationStore(listOf(sps(), pps()))
        assertNull(store.prepare(au(nal(0xe5, 0x80))))
        assertNull(store.prepare(au(byteArrayOf(0x65, 0x80.toByte()))))
    }

    private fun config(sequence: ByteArray): H264DecoderConfiguration =
        requireNotNull(H264DecoderConfigurationStore().prepare(au(sequence + pps() + slice()))).configuration

    private fun au(bytes: ByteArray, timestamp: Long = 90_000, keyFrame: Boolean = true) =
        H264AccessUnit(bytes, timestamp, keyFrame, null, null, hasPicture = keyFrame)

    private fun nal(vararg bytes: Int) = byteArrayOf(0, 0, 0, 1) + bytes.map(Int::toByte).toByteArray()

    private fun sps(
        id: Int = 0,
        widthMbs: Int = 4,
        heightMaps: Int = 3,
        profile: Int = 100,
        chroma: Int = 1,
        depth: Int = 8,
        separatePlane: Boolean = false,
        frameOnly: Boolean = true,
        crop: IntArray? = null,
        scaling: Boolean = false,
        poc: Int = 2,
        color: H264VuiColor? = H264VuiColor(false, 1, 1, 1),
        hrd: Boolean = false,
        restriction: Boolean = false,
    ): ByteArray = Writer().apply {
        u(profile, 8); u(0, 8); u(10, 8); ue(id)
        if (profile !in listOf(66, 77, 88)) {
            ue(chroma); if (chroma == 3) bit(separatePlane)
            ue(depth - 8); ue(depth - 8); bit(false); bit(scaling)
            if (scaling) repeat(if (chroma == 3) 12 else 8) { index ->
                bit(index == 0)
                if (index == 0) repeat(16) { se(0) }
            }
        }
        ue(0); ue(poc)
        if (poc == 0) ue(0)
        if (poc == 1) { bit(false); se(0); se(0); ue(2); se(-1); se(1) }
        ue(0); bit(false); ue(widthMbs - 1); ue(heightMaps - 1); bit(frameOnly)
        if (!frameOnly) bit(false)
        bit(true); bit(crop != null)
        crop?.forEach(::ue)
        bit(color != null)
        if (color != null) {
            bit(false); bit(false); bit(true); u(5, 3); bit(color.fullRange == true); bit(true)
            u(color.primaries ?: 2, 8); u(color.transfer ?: 2, 8); u(color.matrix ?: 2, 8)
            bit(false); bit(true); u(1, 32); u(2, 32); bit(false)
            bit(hrd)
            if (hrd) { ue(0); u(0, 8); ue(0); ue(0); bit(false); u(0, 20) }
            bit(false)
            if (hrd) bit(false)
            bit(false); bit(restriction)
            if (restriction) { bit(true); ue(0); ue(0); ue(0); ue(0); ue(0); ue(1) }
        }
    }.nal(0x67)

    private fun pps(id: Int = 0, sequenceId: Int = 0) = Writer().apply {
        ue(id); ue(sequenceId); bit(false); bit(false)
        ue(0); ue(0); ue(0); bit(false); u(0, 2)
        se(0); se(0); se(0); bit(true); bit(false); bit(false)
    }.nal(0x68)

    private fun slice(ppsId: Int = 0, idr: Boolean = true) = Writer().apply {
        ue(0); ue(2); ue(ppsId)
    }.nal(if (idr) 0x65 else 0x41)

    /** Small synthetic syntax generator, not a production encoder or a native color oracle. */
    private class Writer {
        private val bits = StringBuilder()
        fun bit(value: Boolean) { bits.append(if (value) '1' else '0') }
        fun u(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) bit((value.toLong() ushr shift) and 1L != 0L)
        }
        fun ue(value: Int) {
            val binary = (value.toLong() + 1).toString(2)
            repeat(binary.length - 1) { bits.append('0') }
            bits.append(binary)
        }
        fun se(value: Int) = ue(if (value <= 0) -2 * value else 2 * value - 1)
        fun nal(header: Int): ByteArray {
            bits.append('1')
            while (bits.length % 8 != 0) bits.append('0')
            val escaped = mutableListOf<Byte>()
            var zeros = 0
            for (chunk in bits.toString().chunked(8)) {
                val value = chunk.toInt(2)
                if (zeros == 2 && value <= 3) { escaped += 3.toByte(); zeros = 0 }
                escaped += value.toByte()
                zeros = if (value == 0) zeros + 1 else 0
            }
            return byteArrayOf(0, 0, 0, 1, header.toByte()) + escaped.toByteArray()
        }
    }

    private fun packet(sequence: Int, payload: ByteArray, marker: Boolean): ByteArray =
        byteArrayOf(0x80.toByte(), ((if (marker) 0x80 else 0) or 103).toByte(),
            (sequence ushr 8).toByte(), sequence.toByte(), 0, 0, 0, 1, 0, 0, 0, 1) + payload

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val fixture = Base64.getDecoder().decode(
        "AAAAAWdkEAqsuI9NQEBAUAAAAwAQAAADACBAAAAAAWjuASyyLAAAAAFliIQ6/vOwfmWVcMjc9396sU68Xq2KSZ/Z1yk56ylhRBv1U+mU30I0Mj4UmV3uYf7yU5xO2A99Sa1rES0kusaJZgSJBlnVOP5+YjUvqLBSBdOb96m0lFYjjsqbyoKCtoK2/Ui3FUTivt/uYo03ooGWdmlKBYZOq6rTjaSQ+vk="
    )
}
