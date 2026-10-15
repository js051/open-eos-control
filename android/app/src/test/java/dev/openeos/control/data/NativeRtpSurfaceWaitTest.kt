package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

class NativeRtpSurfaceWaitTest {
    @Test fun noSurfaceWaitBeforeActualDecodedOutput() {
        val wait = NativeRtpSurfaceWait(5_000)
        assertFalse(wait.expired(50_000))
        wait.onDecodedOutput(50_000)
        assertFalse(wait.expired(54_999))
        assertTrue(wait.expired(55_000))
    }

    @Test fun continuousDecodeCannotExtendTheFirstAttachmentDeadline() {
        val wait = NativeRtpSurfaceWait(5_000)
        wait.onDecodedOutput(0)
        (1..5).forEach { wait.onDecodedOutput(it * 1_000L) }
        assertTrue(wait.expired(5_000))
    }

    @Test fun attachTransfersResponsibilityAndDetachStartsANewNonSlidingWait() {
        val wait = NativeRtpSurfaceWait(5_000)
        wait.onDecodedOutput(0)
        wait.onSurfaceAttached()
        assertFalse(wait.expired(10_000))
        wait.onSurfaceDetached(10_000)
        wait.onSurfaceDetached(14_999)
        wait.onDecodedOutput(14_999)
        assertFalse(wait.expired(14_999))
        assertTrue(wait.expired(15_000))
    }

    @Test fun detachBeforeAnyDecodedOutputStillUsesDecoderReadinessDeadline() {
        val wait = NativeRtpSurfaceWait(5_000)
        wait.onSurfaceAttached()
        wait.onSurfaceDetached(0)
        assertFalse(wait.expired(10_000))
        wait.onDecodedOutput(10_000)
        assertTrue(wait.expired(15_000))
    }

    @Test fun pauseCancelsTheWaitAndResumeWithoutSurfaceStartsOneFreshWait() {
        val wait = NativeRtpSurfaceWait(5_000)
        wait.onDecodedOutput(0)
        wait.setRenderingEnabled(false, 4_999)
        wait.onDecodedOutput(20_000)
        wait.onSurfaceDetached(20_001)
        assertFalse(wait.expired(30_000))
        wait.setRenderingEnabled(true, 30_000)
        wait.setRenderingEnabled(true, 34_999)
        wait.onDecodedOutput(34_999)
        assertFalse(wait.expired(34_999))
        assertTrue(wait.expired(35_000))
    }

    @Test fun firstDecodeWhilePausedDoesNotStartAWaitUntilResume() {
        val wait = NativeRtpSurfaceWait(5_000)
        wait.setRenderingEnabled(false, 0)
        wait.onDecodedOutput(1)
        assertFalse(wait.expired(100_000))
        wait.setRenderingEnabled(true, 100_000)
        assertTrue(wait.expired(105_000))
    }

    @Test fun attachBeforeExpiryCancelsWaitAndAttachedResumeDoesNotArmIt() {
        val wait = NativeRtpSurfaceWait(5_000)
        wait.onDecodedOutput(0)
        wait.onSurfaceAttached()
        wait.setRenderingEnabled(false, 1)
        wait.setRenderingEnabled(true, 50_000)
        assertFalse(wait.expired(100_000))
    }

    @Test fun retirementPermanentlyRejectsLateDecodedAttachDetachAndResume() {
        val wait = NativeRtpSurfaceWait(5_000)
        wait.onDecodedOutput(0)
        wait.retire()
        wait.onDecodedOutput(20_000)
        wait.onSurfaceAttached()
        wait.onSurfaceDetached(20_001)
        wait.setRenderingEnabled(false, 20_002)
        wait.setRenderingEnabled(true, 20_003)
        assertFalse(wait.expired(100_000))
    }
}
