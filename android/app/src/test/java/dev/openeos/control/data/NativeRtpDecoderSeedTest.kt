package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NativeRtpDecoderSeedTest {
    private data class Picture(val timestamp: Long, val configuration: String, val bytes: List<Byte>)
    private fun picture(frameId: Long = 7, rendering: Long = 2) = NativeRtpDecoderSeed(
        NativeRtpInputIdentity(frameId, rendering), Picture(91_234, "matched-sps-3-pps-5", listOf(0, 0, 1, 0x65)))

    @Test fun staleDequeuedInputRetiresOnlyItsStillCurrentCodec() {
        val identity = NativeRtpInputIdentity(7, 2)
        assertEquals(NativeRtpInputSlotAdmission.SUBMIT, nativeRtpInputSlotAdmission(true, identity, 2))
        // A pause/resume while dequeueInputBuffer waited cannot leak the acquired slot.
        assertEquals(NativeRtpInputSlotAdmission.RETIRE_CODEC, nativeRtpInputSlotAdmission(true, identity, 3))
        // A Surface/configuration replacement already owns the drain. Do not replace it again.
        assertEquals(NativeRtpInputSlotAdmission.RETIRED, nativeRtpInputSlotAdmission(false, identity, 2))
        assertEquals(NativeRtpInputSlotAdmission.RETIRED, nativeRtpInputSlotAdmission(false, identity, 3))
    }

    @Test fun configurationOnlyDoesNotBreakContinuityButRejectedPictureDoes() {
        val sequence = NativeRtpPictureSequence()
        val gate = NativeRtpDecoderInputGate("configuration-a")
        fun resolved(keyFrame: Boolean): NativeRtpInputAdmission = gate.admit(
            NativeRtpInputIdentity(requireNotNull(sequence.observe(true)), 2), 2, "configuration-a", keyFrame)
        assertEquals(NativeRtpInputAdmission.QUEUE, resolved(true))
        gate.submitted()
        assertNull(sequence.observe(false)) // Complete SPS/PPS-only packet, not a missing picture.
        assertEquals(NativeRtpInputAdmission.QUEUE, resolved(false))
        gate.submitted()
        assertEquals(3L, sequence.observe(true)) // Unresolved/invalid VCL is not queued.
        assertEquals(NativeRtpInputAdmission.DROP, resolved(false))
        assertEquals(NativeRtpInputAdmission.QUEUE, resolved(true))
    }

    @Test fun rejectedPartitionAndExtensionPicturesBreakRealIngressContinuityUntilIdr() {
        // Parameter bytes from the existing 64x48 fixture; tiny slice headers exercise
        // admission only and make no claim that this is a native-decodable picture.
        val sps = Base64.getDecoder().decode("AAAAAWdkEAqsuI9NQEBAUAAAAwAQAAADACBA")
        val pps = Base64.getDecoder().decode("AAAAAWjuASyyLA==")
        for (type in listOf(2, 3, 4, 19, 20, 21)) {
            val store = H264DecoderConfigurationStore(listOf(sps, pps))
            val depacketizer = H264RtpDepacketizer(103)
            val sequence = NativeRtpPictureSequence()
            var packetNumber = 0
            fun ingress(header: Int): Pair<Long?, H264PreparedAccessUnit?> {
                packetNumber++
                val datagram = byteArrayOf(0x80.toByte(), 0xe7.toByte(),
                    0, packetNumber.toByte(), 0, 0, 0, packetNumber.toByte(), 0, 0, 0, 1,
                    header.toByte(), 0xb8.toByte())
                val au = requireNotNull(depacketizer.accept(datagram))
                // Match production ordering: rejected VCL still consumes the sequence.
                val identity = sequence.observe(au.hasPicture)
                return identity to store.prepare(au)
            }
            val (firstSequence, firstPicture) = ingress(0x65)
            val first = requireNotNull(firstPicture)
            val gate = NativeRtpDecoderInputGate(first.configuration)
            fun admit(id: Long?, picture: H264PreparedAccessUnit?) = requireNotNull(picture).let {
                gate.admit(NativeRtpInputIdentity(requireNotNull(id), 2), 2, it.configuration, it.keyFrame)
            }
            assertEquals(NativeRtpInputAdmission.QUEUE, admit(firstSequence, first))
            gate.submitted()
            val (rejectedSequence, rejectedPicture) = ingress(0x60 or type)
            assertEquals(2L, rejectedSequence)
            assertNull(rejectedPicture)
            val (dependentSequence, dependentPicture) = ingress(0x41)
            assertEquals(3L, dependentSequence)
            assertNull(dependentPicture) // Resolver also withholds dependent P after rejected VCL.
            val (recoverySequence, recoveryPicture) = ingress(0x65)
            assertEquals(NativeRtpInputAdmission.QUEUE, admit(recoverySequence, recoveryPicture))
        }
    }

    @Test fun onlyAnIdrMayReplaceTheReferencedConfiguration() {
        data class Config(val sps: List<Int>, val pps: List<Int>)
        val original = Config(listOf(7, 3, 10), listOf(8, 5, 3))
        val changed = original.copy(sps = listOf(7, 3, 11)) // Same SPS ID, different content.
        val gate = NativeRtpDecoderInputGate(original)
        fun admit(frame: Long, config: Config, keyFrame: Boolean) =
            gate.admit(NativeRtpInputIdentity(frame, 2), 2, config, keyFrame)
        assertEquals(NativeRtpInputAdmission.QUEUE, admit(1, original, true))
        gate.submitted()
        assertEquals(NativeRtpInputAdmission.QUEUE, admit(2, original.copy(), false))
        gate.submitted()
        assertEquals(NativeRtpInputAdmission.DROP, admit(3, changed, false))
        assertEquals(NativeRtpInputAdmission.DROP, admit(4, original, false))
        assertEquals(NativeRtpInputAdmission.RECONFIGURE, admit(5, changed, true))
        assertEquals(NativeRtpInputAdmission.QUEUE, admit(6, original.copy(), true))
    }

    @Test fun retiredRenderingAndFailedNativeSubmissionRequireAnotherIdr() {
        val gate = NativeRtpDecoderInputGate("configuration-a")
        fun admit(frame: Long, rendering: Long = 2, keyFrame: Boolean = false) =
            gate.admit(NativeRtpInputIdentity(frame, rendering), 2, "configuration-a", keyFrame)
        assertEquals(NativeRtpInputAdmission.QUEUE, admit(1, keyFrame = true))
        gate.submitted()
        assertEquals(NativeRtpInputAdmission.DROP, admit(2, rendering = 1, keyFrame = true))
        assertEquals(NativeRtpInputAdmission.DROP, admit(3))
        assertEquals(NativeRtpInputAdmission.QUEUE, admit(4, keyFrame = true))
        gate.submitted()
        gate.dropped()
        assertEquals(NativeRtpInputAdmission.DROP, admit(5))
        assertEquals(NativeRtpInputAdmission.QUEUE, admit(6, keyFrame = true))
    }

    @Test fun cancelledReplacementsKeepTheExactUnsubmittedPictureAndConfiguration() {
        val owner = NativeRtpDecoderSeedOwner<Picture>()
        val original = picture()
        owner.restart(1, 2, original)
        owner.restart(2, 2)
        owner.restart(3, 2)
        assertNull(owner.peek(1, 2))
        assertFalse(owner.submitted(1, original))
        val transferred = requireNotNull(owner.peek(3, 2))
        assertSame(original, transferred)
        assertSame(original.input, transferred.input)
        assertEquals(7L, transferred.identity.frameId)
        assertEquals(91_234L, transferred.input.timestamp)
        assertEquals("matched-sps-3-pps-5", transferred.input.configuration)
        assertEquals(listOf<Byte>(0, 0, 1, 0x65), transferred.input.bytes)
    }

    @Test fun preInputFallbackKeepsSeedButPostInputFallbackCannotReplayIt() {
        val owner = NativeRtpDecoderSeedOwner<Picture>()
        val original = picture()
        owner.restart(1, 2, original)
        assertSame(original, owner.peek(1, 2))
        // Codec/renderer creation failed before an input buffer was successfully queued.
        assertSame(original, owner.peek(1, 2))
        assertTrue(owner.submitted(1, original))
        assertNull(owner.peek(1, 2))
        assertFalse(owner.submitted(1, original))
        owner.restart(2, 2)
        assertNull(owner.peek(2, 2))
    }

    @Test fun configurationChangeSeedSurvivesReplacementButOldOwnerCannotConsumeIt() {
        val owner = NativeRtpDecoderSeedOwner<Picture>()
        val old = picture()
        owner.restart(1, 2, old)
        assertTrue(owner.submitted(1, old))
        val changed = picture(8).copy(input = Picture(96_634, "matched-sps-4-pps-5", listOf(0, 0, 1, 0x65, 1)))
        owner.restart(2, 2, changed)
        owner.restart(3, 2) // Surface/LUT replacement while the original writer is draining.
        assertFalse(owner.submitted(2, changed))
        assertFalse(owner.offer(2, 2, old))
        assertSame(changed, owner.peek(3, 2))
        assertTrue(owner.submitted(3, changed))
        assertNull(owner.peek(3, 2))
    }

    @Test fun renderingRetirementDropsOldSeedInsteadOfRestampingItsIdentity() {
        val owner = NativeRtpDecoderSeedOwner<Picture>()
        val old = picture()
        owner.restart(1, 2, old)
        assertNull(owner.peek(1, 3))
        assertFalse(owner.offer(1, 3, old))
        val current = picture(8, rendering = 3)
        assertTrue(owner.offer(1, 3, current))
        assertSame(current, owner.peek(1, 3))
        assertFalse(owner.submitted(1, old))
        assertSame(current, owner.peek(1, 3))
    }

    @Test fun onlyTheFirstValidOfferOwnsTheWaitingDecoderAndClearRetiresIt() {
        val owner = NativeRtpDecoderSeedOwner<Picture>()
        owner.restart(4, 2)
        val first = picture()
        assertFalse(owner.offer(3, 2, first))
        assertTrue(owner.offer(4, 2, first))
        assertFalse(owner.offer(4, 2, picture(8)))
        assertSame(first, owner.peek(4, 2))
        owner.clear()
        assertNull(owner.peek(4, 2))
        assertFalse(owner.submitted(4, first))
        assertFalse(owner.offer(4, 2, first))
    }

    @Test fun transportGapAndConfigurationOnlyTrafficCannotResumeDependentPictures() {
        val ingress = TransportIngress()
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(1, idr = true).admission)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(2).admission)
        // Packet 3, potentially an entire reference picture, never arrives.
        assertNull(ingress.picture(4).accessUnit)
        val dependent = ingress.picture(5)
        assertTrue(requireNotNull(dependent.accessUnit).discontinuity)
        assertNull(dependent.prepared)
        val configurationOnly = ingress.parameters(6)
        assertTrue(requireNotNull(configurationOnly.accessUnit).discontinuity)
        assertNull(configurationOnly.frameId)
        assertNull(configurationOnly.prepared)
        assertNull(ingress.picture(7).prepared)
        val recovery = ingress.picture(8, idr = true)
        assertTrue(requireNotNull(recovery.accessUnit).discontinuity)
        assertEquals(NativeRtpInputAdmission.QUEUE, recovery.admission)
        val following = ingress.picture(9)
        assertFalse(requireNotNull(following.accessUnit).discontinuity)
        assertEquals(NativeRtpInputAdmission.QUEUE, following.admission)
    }

    @Test fun transportGapCannotUseTheIncompleteGapContainingIdrAsRecovery() {
        val ingress = TransportIngress()
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(1, idr = true).admission)
        // An IDR-looking remainder after a sequence gap is not a complete real IDR.
        assertNull(ingress.picture(3, idr = true).accessUnit)
        assertNull(ingress.picture(4).prepared)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(5, idr = true).admission)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(6).admission)
    }

    @Test fun malformedRtpAndMalformedAggregateEachRequireNewResolvedIdr() {
        for (malformedRtp in listOf(true, false)) {
            val ingress = TransportIngress()
            assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(1, idr = true).admission)
            val malformed = if (malformedRtp) byteArrayOf(0x80.toByte(), 0xe7.toByte())
                else transportPacket(2, byteArrayOf(24, 0, 4, 0x41, 0xb8.toByte()))
            assertNull(ingress.accept(malformed).accessUnit)
            // For an unreadable RTP header, the absent sequence is itself another loss.
            assertNull(ingress.picture(3).prepared)
            assertNull(ingress.parameters(4).prepared)
            assertNull(ingress.picture(5).prepared)
            assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(6, idr = true).admission)
        }
    }

    @Test fun timestampDiscardOfUnfinishedFuCannotDecodeAcrossMissingReference() {
        val ingress = TransportIngress()
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(1, idr = true).admission)
        assertNull(ingress.accept(transportPacket(2,
            byteArrayOf(0x7c, 0x81.toByte(), 0x55), marker = false)).accessUnit)
        // New timestamp abandons the prior fragmented picture before its end/marker.
        assertNull(ingress.picture(3).prepared)
        assertNull(ingress.parameters(4).prepared)
        assertNull(ingress.picture(5).prepared)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(6, idr = true).admission)
    }

    @Test fun rejectedFlaggedIdrCannotClearTheResolverLossLatch() {
        val ingress = TransportIngress()
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(1, idr = true).admission)
        assertNull(ingress.picture(3).accessUnit)
        // Actual type-5 VCL, but its slice references absent PPS 1.
        val rejectedIdr = ingress.accept(transportPacket(4, byteArrayOf(0x65, 0xb5.toByte())))
        assertTrue(requireNotNull(rejectedIdr.accessUnit).discontinuity)
        assertNull(rejectedIdr.prepared)
        val dependent = ingress.picture(5)
        assertFalse(requireNotNull(dependent.accessUnit).discontinuity)
        assertNull(dependent.prepared) // Transport recovery is not configuration admission.
        assertNull(ingress.parameters(6).prepared)
        assertNull(ingress.picture(7).prepared)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(8, idr = true).admission)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(9).admission)
    }

    @Test fun renderingResetRequiresIdrWithoutRelyingOnStaleQueuedPicture() {
        val ingress = TransportIngress()
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(1, idr = true).admission)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(2).admission)
        assertNull(ingress.accept(transportPacket(3,
            byteArrayOf(0x7c, 0x81.toByte(), 0x55), marker = false)).accessUnit)
        ingress.resetRendering()
        // There is no old queued identity to make the decoder gate notice retirement.
        val firstNewGeneration = ingress.picture(70)
        assertNull(firstNewGeneration.accessUnit) // Reset quarantines through the first marker.
        assertNull(firstNewGeneration.prepared)
        val configurationOnly = ingress.parameters(71)
        assertTrue(requireNotNull(configurationOnly.accessUnit).discontinuity)
        assertNull(configurationOnly.prepared)
        assertNull(ingress.picture(72).prepared)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(73, idr = true).admission)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(74).admission)
    }

    @Test fun renderingResetRejectsTrailingIdrSliceBeforeFreshMarkerDelimitedRecovery() {
        val ingress = TransportIngress()
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(1, idr = true).admission)
        assertNull(ingress.accept(transportPacket(2,
            byteArrayOf(0x65, 0xb8.toByte()), marker = false)).accessUnit)
        ingress.resetRendering()
        // A complete-looking type-5 NAL can be just the last slice of the discarded AU.
        val trailingSlice = ingress.accept(transportPacket(3,
            byteArrayOf(0x65, 0xb8.toByte()), timestamp = 2))
        assertNull(trailingSlice.accessUnit)
        assertNull(ingress.picture(4).prepared)
        // Repeated media timestamps remain valid: markers/sequence, not unique RTP PTS,
        // distinguish the fresh complete recovery picture from the discarded old suffix.
        val recovery = ingress.picture(5, idr = true, timestamp = 2)
        assertEquals(NativeRtpInputAdmission.QUEUE, recovery.admission)
        assertEquals(2L, requireNotNull(recovery.prepared).rtpTimestamp)
        assertEquals(NativeRtpInputAdmission.QUEUE, ingress.picture(6, timestamp = 2).admission)
    }

    private data class TransportResult(
        val accessUnit: H264AccessUnit? = null,
        val frameId: Long? = null,
        val prepared: H264PreparedAccessUnit? = null,
        val admission: NativeRtpInputAdmission? = null,
    )

    /** Exercises actual ingress/resolver/admission; QUEUE simulates acceptance, not native output. */
    private class TransportIngress {
        private val depacketizer = H264RtpDepacketizer(103)
        private val sps = Base64.getDecoder().decode("AAAAAWdkEAqsuI9NQEBAUAAAAwAQAAADACBA")
        private val pps = Base64.getDecoder().decode("AAAAAWjuASyyLA==")
        private val store = H264DecoderConfigurationStore(listOf(sps, pps))
        private val sequence = NativeRtpPictureSequence()
        private var renderingGeneration = 2L
        private var gate: NativeRtpDecoderInputGate<H264DecoderConfiguration>? = null

        fun picture(packet: Int, idr: Boolean = false, timestamp: Int = packet) =
            accept(transportPacket(packet, byteArrayOf((if (idr) 0x65 else 0x41).toByte(), 0xb8.toByte()), timestamp = timestamp))

        fun parameters(packet: Int) = accept(transportPacket(packet, sps.copyOfRange(4, sps.size)))

        fun resetRendering() {
            renderingGeneration++
            store.discardPendingMedia()
            depacketizer.resetAfterDiscontinuity()
        }

        fun accept(datagram: ByteArray): TransportResult {
            val accessUnit = depacketizer.accept(datagram) ?: return TransportResult()
            val frameId = sequence.observe(accessUnit.hasPicture)
            val prepared = store.prepare(accessUnit)
                ?: return TransportResult(accessUnit, frameId)
            val currentGate = gate ?: NativeRtpDecoderInputGate(prepared.configuration).also { gate = it }
            val admission = currentGate.admit(NativeRtpInputIdentity(requireNotNull(frameId), renderingGeneration),
                renderingGeneration, prepared.configuration, prepared.keyFrame)
            if (admission == NativeRtpInputAdmission.QUEUE) currentGate.submitted()
            return TransportResult(accessUnit, frameId, prepared, admission)
        }
    }

    private companion object {
        fun transportPacket(sequence: Int, payload: ByteArray, marker: Boolean = true, timestamp: Int = sequence): ByteArray =
            byteArrayOf(0x80.toByte(), ((if (marker) 0x80 else 0) or 103).toByte(),
                (sequence ushr 8).toByte(), sequence.toByte(),
                (timestamp ushr 24).toByte(), (timestamp ushr 16).toByte(), (timestamp ushr 8).toByte(), timestamp.toByte(),
                0, 0, 0, 1) + payload
    }
}
