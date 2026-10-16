package dev.openeos.control.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MediaUploadAttemptTest {
    private val receipt = CameraMediaUploadResult(CameraMediaItem("receipt-7", "SAME.JPG", "image", 12L), 12L)

    @Test fun localCancellationCannotDispatchOrConfirmAnInventoryMatch() {
        val attempt = MediaUploadAttempt()
        assertEquals(MediaUploadStatus.PREPARING, attempt.status())
        attempt.cancel()
        assertThrows(CancellationException::class.java) { attempt.beforeDispatch() }
        assertEquals(MediaUploadStatus.NOT_SENT, attempt.finish())
        assertNull(attempt.confirmedResult())
    }
    @Test fun receiptSurvivesCancellationAtDispatcherReturnBoundary() = runBlocking {
        val attempt = MediaUploadAttempt()
        val job = launch {
            withContext(Dispatchers.Default) {
                attempt.beforeDispatch()
                attempt.confirm(receipt)
                currentCoroutineContext().cancel()
            }
            fail("Prompt cancellation must prevent delivery of the return value")
        }
        job.join()
        attempt.cancel()
        assertEquals(MediaUploadStatus.CONFIRMED, attempt.finish())
        assertEquals(receipt, attempt.confirmedResult())
        assertFalse(attempt.acceptsProgress())
    }
    @Test fun cancelledInFlightAttemptCanStillRetainItsAlreadyValidatedReceipt() {
        val attempt = MediaUploadAttempt()
        attempt.beforeDispatch(); attempt.cancel(); attempt.confirm(receipt)
        assertEquals(MediaUploadStatus.CONFIRMED, attempt.finish())
    }
    @Test fun terminalAndDuplicateCallbacksCannotOverwriteTheFirstReceipt() {
        val attempt = MediaUploadAttempt()
        attempt.beforeDispatch(); attempt.confirm(receipt)
        attempt.confirm(receipt.copy(item = receipt.item.copy(id = "different")))
        attempt.finish(); attempt.confirm(receipt.copy(item = receipt.item.copy(name = "different")))
        assertEquals(receipt, attempt.confirmedResult())
    }
    @Test fun errorAfterDispatchIsUnknownAndNeverReplayed() {
        val attempt = MediaUploadAttempt()
        attempt.beforeDispatch()
        assertThrows(IllegalStateException::class.java) { attempt.beforeDispatch() }
        assertEquals(MediaUploadStatus.UNCERTAIN, attempt.finish())
        assertThrows(CancellationException::class.java) { attempt.beforeDispatch() }
        assertNull(attempt.confirmedResult())
    }
    @Test fun equalConnectionDataDoesNotMakeReplacementReferenceAnOwner() {
        val a = CameraInfo(true, "Synthetic", "SYNTHETIC-UPLOAD", "fixture")
        val token = Any()
        assertFalse(ownsMediaUpload(token, token, a, a.copy(), 1, 1))
        assertFalse(ownsMediaUpload(token, Any(), a, a, 1, 1))
        assertFalse(ownsMediaUpload(token, token, a, a, 1, 2))
        assertTrue(ownsMediaUpload(token, token, a, a, 1, 1))
    }
    @Test fun opaqueReceiptIdentityIsPreservedIncludingSurroundingWhitespace() {
        assertTrue(validUploadReceipt(201, "SAME.JPG", 12, 12, " id /%2f ", "SAME.JPG", 12, " ", "12"))
        val attempt = MediaUploadAttempt()
        attempt.beforeDispatch()
        attempt.confirm(receipt.copy(item = receipt.item.copy(id = " id /%2f ")))
        assertEquals(" id /%2f ", attempt.confirmedResult()?.item?.id)
    }
    @Test fun rawIntegerTokensRejectDecimalExponentAndRoundedPlatformNumbers() {
        for (raw in listOf("12.0", "12e0", "12.000000000000001", "\"12\"", "true")) {
            assertFalse(raw, validUploadReceipt(201, "SAME.JPG", 12, 12, "id", "SAME.JPG", 12, "image", raw))
        }
    }
    @Test fun sessionRetirementClosesDispatchGateEvenAfterAnEarlierOwnerCheckPassed() {
        val attempt = MediaUploadAttempt()
        val owner = Any()
        val checkedOwner = CountDownLatch(1)
        val continueDispatch = CountDownLatch(1)
        val writes = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                check(ownsMediaUpload(attempt, attempt, owner, owner, 1, 1))
                checkedOwner.countDown()
                check(continueDispatch.await(5, TimeUnit.SECONDS))
                attempt.beforeDispatch()
                writes.incrementAndGet()
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(checkedOwner.await(5, TimeUnit.SECONDS))
            // Same synchronous retirement gate used before generation changes / Job.cancel.
            attempt.cancel()
        } finally { continueDispatch.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertEquals(0, writes.get())
        assertEquals(MediaUploadStatus.NOT_SENT, attempt.finish())
    }
    @Test fun retirementAfterDispatchKeepsUncertaintyUnlessThatAttemptHasAReceipt() {
        for (confirmed in listOf(false, true)) {
            val attempt = MediaUploadAttempt()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val worker = Thread {
                attempt.beforeDispatch(); entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                if (confirmed) attempt.confirm(receipt)
            }
            worker.start()
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); attempt.cancel() }
            finally { resume.countDown(); worker.join(5_000) }
            assertFalse(worker.isAlive)
            assertEquals(if (confirmed) MediaUploadStatus.CONFIRMED else MediaUploadStatus.UNCERTAIN, attempt.finish())
        }
    }
    @Test fun bytesActuallyTransferredAndExpectedSizeBothMatter() {
        assertFalse(validUploadReceipt(201, "SAME.JPG", 12, 11, "id", "SAME.JPG", 12, "image", "12"))
        assertFalse(validUploadReceipt(201, "SAME.JPG", 0, 0, "id", "SAME.JPG", 0, "image", "0"))
    }
}
