package dev.openeos.control.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CcapiRtpTest {
    @Test
    fun parsesCanonH264AndLatmSessionDescription() {
        val description = CcapiRtpSessionDescriptionParser.parse(CANON_SDP)

        assertEquals(12000, description.video.port)
        assertEquals(103, description.video.payloadType)
        assertEquals("H264", description.video.codec)
        assertEquals(90_000, description.video.clockRate)
        assertEquals(12010, description.audio?.port)
        assertEquals("MP4A-LATM", description.audio?.codec)
        assertEquals(48_000, description.audio?.clockRate)
    }

    @Test
    fun rejectsSessionWithoutCanonH264Video() {
        val failure = runCatching {
            CcapiRtpSessionDescriptionParser.parse(
                CANON_SDP.replace("a=rtpmap:103 H264/90000", "a=rtpmap:103 JPEG/90000")
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("H.264"))
    }

    @Test
    fun parsesRtpHeaderWithCsrcExtensionAndPadding() {
        val datagram = rtpPacket(
            sequence = 0x1234,
            timestamp = 0xF1234567L,
            payload = byteArrayOf(0x65, 1, 2),
            marker = true,
            csrc = true,
            extension = true,
            padding = 4,
        )

        val packet = RtpPacketParser.parse(datagram)

        assertNotNull(packet)
        assertTrue(packet!!.marker)
        assertEquals(103, packet.payloadType)
        assertEquals(0x1234, packet.sequenceNumber)
        assertEquals(0xF1234567L, packet.timestamp)
        assertArrayEquals(byteArrayOf(0x65, 1, 2), packet.payload)
    }

    @Test
    fun emitsSingleNalAccessUnit() {
        val depacketizer = H264RtpDepacketizer(103)

        val accessUnit = depacketizer.accept(
            rtpPacket(1, 9000, byteArrayOf(0x65, 1, 2, 3), marker = true)
        )

        assertNotNull(accessUnit)
        assertTrue(accessUnit!!.keyFrame)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3), accessUnit.bytes)
    }

    @Test
    fun reassemblesFragmentedFuAAccessUnit() {
        val depacketizer = H264RtpDepacketizer(103)
        val first = depacketizer.accept(
            rtpPacket(20, 18_000, byteArrayOf(0x7C, 0x85.toByte(), 10, 11), marker = false)
        )
        val completed = depacketizer.accept(
            rtpPacket(21, 18_000, byteArrayOf(0x7C, 0x45, 12, 13), marker = true)
        )

        assertNull(first)
        assertNotNull(completed)
        assertTrue(completed!!.keyFrame)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 10, 11, 12, 13), completed.bytes)
    }

    @Test
    fun extractsParameterSetsFromStapA() {
        val depacketizer = H264RtpDepacketizer(103)
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val idr = byteArrayOf(0x65, 0x09)
        val stap = byteArrayOf(0x78) + sizedNal(sps) + sizedNal(pps) + sizedNal(idr)

        val completed = depacketizer.accept(rtpPacket(30, 27_000, stap, marker = true))

        assertNotNull(completed)
        assertTrue(completed!!.keyFrame)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps, completed.sequenceParameterSet)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + pps, completed.pictureParameterSet)
    }

    @Test
    fun discardsFragmentWhenRtpSequenceIsMissing() {
        val depacketizer = H264RtpDepacketizer(103)
        depacketizer.accept(
            rtpPacket(40, 36_000, byteArrayOf(0x7C, 0x85.toByte(), 1), marker = false)
        )

        val completed = depacketizer.accept(
            rtpPacket(42, 36_000, byteArrayOf(0x7C, 0x45, 3), marker = true)
        )

        assertNull(completed)
    }

    @Test
    fun rejectsMalformedRtpPacket() {
        assertNull(RtpPacketParser.parse(byteArrayOf(0x40, 0, 0)))
        assertFalse(RtpPacketParser.parse(rtpPacket(1, 1, byteArrayOf(1))) == null)
    }

    private fun sizedNal(nal: ByteArray): ByteArray =
        byteArrayOf((nal.size ushr 8).toByte(), nal.size.toByte()) + nal

    private fun rtpPacket(
        sequence: Int,
        timestamp: Long,
        payload: ByteArray,
        marker: Boolean = false,
        csrc: Boolean = false,
        extension: Boolean = false,
        padding: Int = 0,
    ): ByteArray {
        val csrcBytes = if (csrc) byteArrayOf(0, 0, 0, 1) else byteArrayOf()
        val extensionBytes = if (extension) byteArrayOf(0x10, 0x00, 0x00, 0x01, 9, 8, 7, 6) else byteArrayOf()
        val paddingBytes = if (padding > 0) ByteArray(padding).also { it[it.lastIndex] = padding.toByte() } else byteArrayOf()
        val first = 0x80 or (if (padding > 0) 0x20 else 0) or (if (extension) 0x10 else 0) or (if (csrc) 1 else 0)
        val second = (if (marker) 0x80 else 0) or 103
        val header = byteArrayOf(
            first.toByte(),
            second.toByte(),
            (sequence ushr 8).toByte(),
            sequence.toByte(),
            (timestamp ushr 24).toByte(),
            (timestamp ushr 16).toByte(),
            (timestamp ushr 8).toByte(),
            timestamp.toByte(),
            0, 0, 0, 1,
        )
        return header + csrcBytes + extensionBytes + payload + paddingBytes
    }

    private companion object {
        const val CANON_SDP = """
            v=0
            o=- 0 0 IN IP4 192.168.11.4
            s=RTP Session
            c=IN IP4 0.0.0.0
            t=0 0
            a=control *
            m=video 12000 RTP/AVP 103
            a=rtpmap:103 H264/90000
            m=audio 12010 RTP/AVP 106
            a=rtpmap:106 MP4A-LATM/48000
        """
    }
}
