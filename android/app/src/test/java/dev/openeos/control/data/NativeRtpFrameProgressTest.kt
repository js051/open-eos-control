package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

class NativeRtpFrameProgressTest {
    @Test fun encodedInputCannotAdvanceProgressOrInventPresentation() {
        val progress = NativeRtpFrameProgress().apply { reset(1) }
        assertFalse(progress.presented(1, 1))
        assertEquals(0L, progress.decodedCount)
        assertTrue(progress.decoded(1, 1))
        assertEquals(0L, progress.presentedCount)
        assertTrue(progress.presented(1, 1))
    }

    @Test fun repeatedOutOfOrderAndRetiredFrameIdsCannotRefreshProgress() {
        val progress = NativeRtpFrameProgress().apply { reset(1) }
        assertTrue(progress.decoded(1, 4))
        assertFalse(progress.decoded(1, 4))
        assertFalse(progress.decoded(1, 3))
        assertFalse(progress.presented(1, 3))
        assertTrue(progress.presented(1, 4))
        assertFalse(progress.presented(1, 4))
        progress.reset(2)
        assertFalse(progress.decoded(1, 5))
        assertFalse(progress.presented(1, 5))
        assertTrue(progress.decoded(2, 1))
        assertTrue(progress.presented(2, 1))
    }

    @Test fun decoderAndPresentationDeadlinesAreIndependentOfIncomingAccessUnits() {
        val decode = LiveViewInactivityDeadline(5_000)
        val presentation = LiveViewInactivityDeadline(5_000)
        decode.arm(0); presentation.arm(0)
        (1..4).forEach { decode.observe(it * 1_000L) }
        assertFalse(decode.expired(5_000))
        assertTrue(presentation.expired(5_000))
        decode.arm(0)
        assertTrue(decode.expired(5_000))
    }

    @Test fun repeatedAndOutOfOrderMediaTimestampsNeverReuseCallbackIdentity() {
        val timeline = NativeRtpCodecTimeline()
        assertEquals(listOf(100L, 101L, 102L, 200L), listOf(100L, 100L, 20L, 200L).map(timeline::next))
    }

    @Test fun pixelOwnershipCannotBeMutatedByProducerOrConsumer() {
        val source = intArrayOf(0xff123456.toInt())
        val pixels = NativeRtpFramePixels(1, 1, source)
        source[0] = 0
        pixels.copyPixels()[0] = 0
        assertEquals(0xff123456.toInt(), pixels.copyPixels()[0])
        assertThrows(IllegalArgumentException::class.java) { NativeRtpFramePixels(321, 1, IntArray(321)) }
    }
}
