package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class H264RtpBoundsTest {
    @Test fun sdpExposureKeepsAllParameterSetsInOriginalOrder() {
        val sets = listOf(byteArrayOf(0x68, 1), byteArrayOf(0x67, 2), byteArrayOf(0x68, 3), byteArrayOf(0x67, 4))
        val description = description(sets.joinToString(",") { Base64.getEncoder().encodeToString(it) })
        val actual = requireNotNull(description.h264ParameterNalUnits())
        assertEquals(4, actual.size)
        sets.forEachIndexed { index, bytes -> assertArrayEquals(byteArrayOf(0, 0, 0, 1) + bytes, actual[index]) }
    }

    @Test fun oversizedBase64TokenAndOverallTextAreRejected() {
        val maxToken = ((65_536 + 2) / 3) * 4
        assertNull(description("A".repeat(maxToken + 1)).h264ParameterNalUnits())
        assertNull(description(" ".repeat(8 * (maxToken + 1) + 1) + "Z0I=,aAE=").h264ParameterNalUnits())
    }

    @Test fun excessiveCountInvalidNalTypeAndForbiddenBitAreRejected() {
        assertNull(description(List(9) { "Z0I=" }.joinToString(",")).h264ParameterNalUnits())
        assertNull(description("Z0I=,aAE=,ZYA=").h264ParameterNalUnits()) // IDR is not SDP parameter data.
        assertNull(description(Base64.getEncoder().encodeToString(byteArrayOf(0xe7.toByte(), 1))).h264ParameterNalUnits())
    }

    @Test fun originalTinyPresenceOnlySdpRemainsAvailableWithoutClaimingValidity() {
        val sets = requireNotNull(description("Z0I=,aAE=").h264ParameterNalUnits())
        assertEquals(2, sets.size)
        assertNotNull(description("Z0I=,aAE=").h264ParameterSets())
        val store = H264DecoderConfigurationStore(sets)
        assertTrue(store.hasSequenceParameterSet)
        assertTrue(store.hasPictureParameterSet)
        assertNull(store.prepare(H264AccessUnit(byteArrayOf(0, 0, 0, 1, 0x65, 0xb8.toByte()), 0, true, null, null)))
    }

    @Test fun oversizedSingleNalIsRefusedBeforeBufferGrowth() {
        val depacketizer = H264RtpDepacketizer(103)
        val huge = ByteArray(MAX_AU) { 0x55 }.also { it[0] = 0x65 }
        assertNull(depacketizer.accept(packet(1, huge, marker = false)))
        assertEquals(0, depacketizer.bufferedByteCount)
        assertNull(depacketizer.accept(packet(2, byteArrayOf(0x65, 1), marker = true)))
        assertEquals(0, depacketizer.bufferedByteCount)
        assertTrue(requireNotNull(depacketizer.accept(packet(3, byteArrayOf(0x65, 1), marker = true, timestamp = 2))).discontinuity)
    }

    @Test fun exactSingleNalBudgetIsAccepted() {
        val payload = ByteArray(MAX_AU - 4) { 0x55 }.also { it[0] = 0x65 }
        val complete = requireNotNull(H264RtpDepacketizer(103).accept(packet(1, payload, marker = true)))
        assertEquals(MAX_AU, complete.bytes.size)
    }

    @Test fun fragmentOverflowStaysInvalidAndCannotKeepGrowingBeforeMarker() {
        val depacketizer = H264RtpDepacketizer(103)
        val body = ByteArray(65_000) { 0x55 }
        assertNull(depacketizer.accept(packet(1, byteArrayOf(0x7c, 0x85.toByte()) + body, false)))
        for (sequence in 2..131) {
            assertNull(depacketizer.accept(packet(sequence, byteArrayOf(0x7c, 5) + body, false)))
            assertTrue(depacketizer.bufferedByteCount <= MAX_AU)
        }
        val retained = depacketizer.bufferedByteCount
        for (sequence in 132..135) {
            assertNull(depacketizer.accept(packet(sequence, byteArrayOf(0x7c, 5) + body, false)))
            assertEquals(retained, depacketizer.bufferedByteCount)
        }
        assertNull(depacketizer.accept(packet(136, byteArrayOf(0x7c, 0x45, 1), true)))
        assertEquals(0, depacketizer.bufferedByteCount)
        assertTrue(requireNotNull(depacketizer.accept(packet(137, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
    }

    @Test fun unsupportedVclStillConsumesPictureContinuityWithoutBecomingKeyFrame() {
        for (type in listOf(2, 3, 4, 19, 20, 21)) {
            val depacketizer = H264RtpDepacketizer(103)
            val complete = requireNotNull(depacketizer.accept(packet(1, byteArrayOf((0x60 or type).toByte(), 0x80.toByte()), true)))
            assertTrue("VCL type $type", complete.hasPicture)
            assertFalse(complete.keyFrame)
        }
    }

    @Test fun fragmentedUnsupportedVclAlsoConsumesPictureContinuity() {
        for (type in listOf(2, 3, 4, 19, 20, 21)) {
            val depacketizer = H264RtpDepacketizer(103)
            assertNull(depacketizer.accept(packet(1, byteArrayOf(0x7c, (0x80 or type).toByte(), 0x55), false)))
            val complete = requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x7c, (0x40 or type).toByte(), 0x55), true)))
            assertTrue(complete.hasPicture)
            assertFalse(complete.keyFrame)
        }
    }

    @Test fun contiguousMarkedAccessUnitsDoNotInventTransportLoss() {
        val depacketizer = H264RtpDepacketizer(103)
        for (sequence in 1..4) {
            val complete = requireNotNull(depacketizer.accept(
                packet(sequence, byteArrayOf(if (sequence == 1) 0x65 else 0x41, 1), true, timestamp = sequence)))
            assertFalse(complete.discontinuity)
        }
    }

    @Test fun sequenceWrapAcrossMarkersIsContiguous() {
        val depacketizer = H264RtpDepacketizer(103)
        assertFalse(requireNotNull(depacketizer.accept(packet(65_535, byteArrayOf(0x65, 1), true))).discontinuity)
        assertFalse(requireNotNull(depacketizer.accept(packet(0, byteArrayOf(0x41, 1), true, timestamp = 2))).discontinuity)
    }

    @Test fun repeatedAndOutOfOrderMediaTimestampsDoNotChangePacketContinuity() {
        val depacketizer = H264RtpDepacketizer(103)
        for ((sequence, timestamp) in listOf(1 to 30, 2 to 30, 3 to 20, 4 to 40)) {
            val complete = requireNotNull(depacketizer.accept(
                packet(sequence, byteArrayOf(0x65, sequence.toByte()), true, timestamp)))
            assertFalse(complete.discontinuity)
            assertEquals(timestamp.toLong(), complete.rtpTimestamp)
        }
    }

    @Test fun boundaryGapRejectsItsWholeAuAndStaysStickyUntilCompleteIdr() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNotNull(depacketizer.accept(packet(1, byteArrayOf(0x65, 1), true)))
        // An IDR-looking remainder cannot prove that the beginning of this AU was received.
        assertNull(depacketizer.accept(packet(3, byteArrayOf(0x65, 1), true, timestamp = 2)))
        for ((sequence, header) in listOf(4 to 0x67, 5 to 0x68, 6 to 0x41, 7 to 0x65)) {
            val complete = requireNotNull(depacketizer.accept(
                packet(sequence, byteArrayOf(header.toByte(), 1), true, timestamp = sequence)))
            assertTrue("Recovery packet $sequence", complete.discontinuity)
        }
        assertFalse(requireNotNull(depacketizer.accept(packet(8, byteArrayOf(0x41, 1), true, timestamp = 8))).discontinuity)
    }

    @Test fun sameTimestampGapCannotBeRepairedByARemainingIdr() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNull(depacketizer.accept(packet(1, byteArrayOf(0x67, 1), false)))
        assertNull(depacketizer.accept(packet(3, byteArrayOf(0x65, 1), true)))
        assertTrue(requireNotNull(depacketizer.accept(packet(4, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
    }

    @Test fun timestampChangeDiscardsUnmarkedDataEvenWithContiguousSequence() {
        for (partial in listOf(byteArrayOf(0x41, 1), byteArrayOf(0x7c, 0x85.toByte(), 1))) {
            val depacketizer = H264RtpDepacketizer(103)
            assertNull(depacketizer.accept(packet(1, partial, false)))
            val complete = requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x65, 2), true, timestamp = 2)))
            assertTrue(complete.discontinuity)
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 2), complete.bytes)
        }
    }

    @Test fun unfinishedFragmentAtMarkerLatchesLossForLaterAccessUnits() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNull(depacketizer.accept(packet(1, byteArrayOf(0x7c, 0x85.toByte(), 1), true)))
        assertTrue(requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x41, 1), true, timestamp = 2))).discontinuity)
        assertTrue(requireNotNull(depacketizer.accept(packet(3, byteArrayOf(0x65, 1), true, timestamp = 3))).discontinuity)
    }

    @Test fun malformedRtpInvalidatesAnOpenAuWithoutResettingItsSequence() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNull(depacketizer.accept(packet(1, byteArrayOf(0x67, 1), false)))
        assertNull(depacketizer.accept(byteArrayOf(0x40, 0, 0)))
        assertNull(depacketizer.accept(packet(2, byteArrayOf(0x65, 1), true)))
        assertTrue(requireNotNull(depacketizer.accept(packet(3, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
    }

    @Test fun malformedPacketBeforeAnyAuStillRequiresRecovery() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNull(depacketizer.accept(byteArrayOf(0x40, 0, 0)))
        assertNull(depacketizer.accept(packet(100, byteArrayOf(0x65, 1), true)))
        assertTrue(requireNotNull(depacketizer.accept(packet(101, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
    }

    @Test fun malformedStapAndForbiddenHeadersCannotClearLoss() {
        for (malformed in listOf(
            byteArrayOf(0x78, 0, 4, 0x65, 1), // Truncated length-delimited NAL.
            byteArrayOf(0x78, 0, 2, 0x65, 1, 0), // Stray trailing length byte.
            byteArrayOf(0x78, 0, 2, 0x78, 1), // Nested aggregation is not a single NAL.
            byteArrayOf(0xe5.toByte(), 1), // forbidden_zero_bit.
        )) {
            val depacketizer = H264RtpDepacketizer(103)
            assertNull(depacketizer.accept(packet(1, malformed, true)))
            assertTrue(requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
        }
    }

    @Test fun emptyStapWithoutMarkerCannotBeCompletedByALaterIdrNal() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNull(depacketizer.accept(packet(1, byteArrayOf(0x78), false)))
        assertNull(depacketizer.accept(packet(2, byteArrayOf(0x65, 1), true)))
        assertTrue(requireNotNull(depacketizer.accept(packet(3, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
    }

    @Test fun resetDropsPartialBytesButPreservesSequenceAndRecoveryRequirement() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNull(depacketizer.accept(packet(40, byteArrayOf(0x7c, 0x85.toByte(), 9), false)))
        depacketizer.resetAfterDiscontinuity()
        assertEquals(0, depacketizer.bufferedByteCount)
        // A new rendering owner may begin in the middle of the same stream's AU.
        assertNull(depacketizer.accept(packet(41, byteArrayOf(0x65, 9), false)))
        assertNull(depacketizer.accept(packet(42, byteArrayOf(0x65, 9), true)))
        assertTrue(requireNotNull(depacketizer.accept(packet(43, byteArrayOf(0x67, 1), true, timestamp = 2))).discontinuity)
        val idr = requireNotNull(depacketizer.accept(packet(44, byteArrayOf(0x65, 2), true, timestamp = 3)))
        assertTrue(idr.discontinuity)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 2), idr.bytes)
        assertFalse(requireNotNull(depacketizer.accept(packet(45, byteArrayOf(0x41, 1), true, timestamp = 4))).discontinuity)
    }

    @Test fun staleMarkerAfterResetCannotEndQuarantineAndAdmitAnIdrSuffix() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNotNull(depacketizer.accept(packet(10, byteArrayOf(0x65, 1), true)))
        assertNull(depacketizer.accept(packet(11, byteArrayOf(0x65, 2), false, timestamp = 2)))
        depacketizer.resetAfterDiscontinuity()
        assertNull(depacketizer.accept(packet(10, byteArrayOf(0x65, 1), true)))
        assertNull(depacketizer.accept(packet(12, byteArrayOf(0x65, 3), true, timestamp = 2)))
        assertTrue(requireNotNull(depacketizer.accept(packet(13, byteArrayOf(0x41, 1), true, timestamp = 3))).discontinuity)
        assertTrue(requireNotNull(depacketizer.accept(packet(14, byteArrayOf(0x65, 1), true, timestamp = 4))).discontinuity)
    }

    @Test fun duplicateAndLatePacketsNeverRewindExpectedSequenceOrReplayOldIdr() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNotNull(depacketizer.accept(packet(10, byteArrayOf(0x65, 1), true)))
        assertNull(depacketizer.accept(packet(10, byteArrayOf(0x65, 1), true)))
        assertNull(depacketizer.accept(packet(9, byteArrayOf(0x65, 1), true)))
        assertFalse(requireNotNull(depacketizer.accept(packet(11, byteArrayOf(0x41, 1), true, timestamp = 2))).discontinuity)
        assertFalse(requireNotNull(depacketizer.accept(packet(12, byteArrayOf(0x65, 1), true, timestamp = 3))).discontinuity)
        assertFalse(requireNotNull(depacketizer.accept(packet(13, byteArrayOf(0x41, 1), true, timestamp = 4))).discontinuity)
    }

    @Test fun halfSequenceSpaceAmbiguityIsRejectedWithoutRewinding() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNotNull(depacketizer.accept(packet(0, byteArrayOf(0x65, 1), true)))
        assertNull(depacketizer.accept(packet(0x8001, byteArrayOf(0x65, 1), true, timestamp = 2)))
        assertFalse(requireNotNull(depacketizer.accept(packet(1, byteArrayOf(0x65, 1), true, timestamp = 3))).discontinuity)
    }

    @Test fun lateDuplicateDoesNotDiscardAnOtherwiseContiguousOpenAu() {
        val depacketizer = H264RtpDepacketizer(103)
        val first = packet(1, byteArrayOf(0x7c, 0x85.toByte(), 1), false)
        assertNull(depacketizer.accept(first))
        assertNull(depacketizer.accept(first))
        val complete = requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x7c, 0x45, 2), true)))
        assertFalse(complete.discontinuity)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), complete.bytes)
    }

    @Test fun fuHeaderChangesAndInterleavedNalsInvalidateTheWholeAu() {
        for (interruption in listOf(
            byteArrayOf(0x7c, 0x41, 2), // Changed NAL type.
            byteArrayOf(0x5c, 0x45, 2), // Changed NRI.
            byteArrayOf(0x65, 2), // A single NAL cannot interrupt an unfinished FU.
            byteArrayOf(0x78, 0, 2, 0x65, 2),
        )) {
            val depacketizer = H264RtpDepacketizer(103)
            assertNull(depacketizer.accept(packet(1, byteArrayOf(0x7c, 0x85.toByte(), 1), false)))
            assertNull(depacketizer.accept(packet(2, interruption, false)))
            assertNull(depacketizer.accept(packet(3, byteArrayOf(0x7c, 0x45, 3), true)))
            assertTrue(requireNotNull(depacketizer.accept(packet(4, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
        }
    }

    @Test fun invalidFuSyntaxCannotProduceARecoveryIdr() {
        for (header in listOf(0xc5, 0xa5, 0x80, 0x98)) {
            val depacketizer = H264RtpDepacketizer(103)
            assertNull(depacketizer.accept(packet(1, byteArrayOf(0x7c, header.toByte(), 1), true)))
            assertTrue(requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x65, 1), true, timestamp = 2))).discontinuity)
        }
    }

    @Test fun unrelatedPayloadTypeDoesNotAlterTheVideoSequence() {
        val depacketizer = H264RtpDepacketizer(103)
        assertNotNull(depacketizer.accept(packet(1, byteArrayOf(0x65, 1), true)))
        val otherPayload = packet(50, byteArrayOf(0x41, 1), true).also { it[1] = 0xe8.toByte() }
        assertNull(depacketizer.accept(otherPayload))
        assertFalse(requireNotNull(depacketizer.accept(packet(2, byteArrayOf(0x41, 1), true, timestamp = 2))).discontinuity)
    }

    private fun description(value: String) = RtpMediaDescription(
        "video", 12000, 103, "H264", 90_000, formatParameters = mapOf("sprop-parameter-sets" to value),
    )

    private fun packet(sequence: Int, payload: ByteArray, marker: Boolean, timestamp: Int = 1): ByteArray =
        byteArrayOf(0x80.toByte(), ((if (marker) 0x80 else 0) or 103).toByte(),
            (sequence ushr 8).toByte(), sequence.toByte(), 0, 0, 0, timestamp.toByte(), 0, 0, 0, 1) + payload

    private companion object { const val MAX_AU = 8 * 1024 * 1024 }
}
