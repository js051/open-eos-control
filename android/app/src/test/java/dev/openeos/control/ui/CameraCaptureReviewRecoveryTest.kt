package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production VM -> repository -> CCAPI, independent HTTP responses and virtual Main timing. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraCaptureReviewRecoveryTest {
    private val main = StandardTestDispatcher()
    private val peer = CaptureReviewPeer()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory { CameraHttpTransport(http, CameraNetworkDiagnostics.Empty) },
    ))
    private lateinit var viewModel: CameraViewModel

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        // Cache all MockWebServer address access off Main, including for instrumented reuse.
        withContext(Dispatchers.IO) { peer.start() }
        viewModel = CameraViewModel(repository)
    }

    @After fun tearDown() = runBlocking {
        peer.releaseGates()
        try {
            viewModel.disconnect()
            http.dispatcher.cancelAll()
            pumpUntil { http.dispatcher.runningCallsCount() == 0 }
            val scopeJob = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
            viewModel.viewModelScope.cancel()
            // HTTP completion precedes its coroutine's Main continuation. Join the whole
            // cancelled scope before resetting Main, including the event-polling child.
            assertTrue("ViewModel children did not finish teardown", pumpUntil { scopeJob.isCompleted })
        } finally {
            withContext(Dispatchers.IO) { peer.server.shutdown() }
            http.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }

    @Test fun acknowledgedSimulatorCommandStillReviewsWhenSeparateStatusReadFails() = runBlocking {
        connect()
        peer.failStatus.set(true)
        val reads = peer.reviewReads.get()
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { !viewModel.uiState.value.busy && peer.captureWrites.get() == 1 })
        assertTrue("ACK followed by status 503 must still start a read-only review; reads=${peer.reviewReads.get() - reads}",
            pumpUntil(advanceTime = true) { peer.reviewReads.get() > reads })
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(1, peer.captureWrites.get())
        assertNotEquals(CameraOperation.CAPTURE, viewModel.uiState.value.errorOperation)
        assertTrue(viewModel.uiState.value.captureStatusReadbackFailed)
    }

    @Test fun successfulSimulatorCommandAndStatusReadStartsReview() = runBlocking {
        connect()
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(1, peer.captureWrites.get())
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun rejectedCommandDoesNotReviewOrClaimAcknowledgement() = runBlocking {
        connect()
        peer.rejectCapture.set(true)
        val reads = peer.reviewReads.get()
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.errorOperation == CameraOperation.CAPTURE })
        main.scheduler.advanceTimeBy(5_000)
        settle()
        assertEquals(reads, peer.reviewReads.get())
        assertFalse(viewModel.uiState.value.captureStatusReadbackFailed)
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun nativeStatus503IsCurrentlyOptionalAndDoesNotReproduceSimulatorFailure() = runBlocking {
        connect(native = true)
        peer.failStatus.set(true)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertTrue(peer.failedStatusReads.get() > 0)
        assertEquals(1, peer.captureWrites.get())
        assertNull(viewModel.uiState.value.error)
        assertTrue(peer.pages.isNotEmpty())
        assertTrue("The eight-candidate query must stop before later advertised pages", peer.pages.all { it == 1 })
    }

    @Test fun exhaustedNativeReviewKeepsOldMediaSeparateAndRetryPreservesOriginalBoundary() = runBlocking {
        connect(native = true)
        peer.publishNew.set(false)
        val reads = peer.reviewReads.get()
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        assertEquals(reads + 4, peer.reviewReads.get())
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertFalse(viewModel.uiState.value.captureReviewLoading)
        assertEquals(1, peer.captureWrites.get())

        val writesAfterCapture = peer.writes.toList()
        viewModel.retryCaptureReview()
        viewModel.retryCaptureReview() // Repeated taps cannot start another concurrent listing.
        assertEquals(CaptureReviewStatus.SEARCHING, viewModel.uiState.value.captureReviewStatus)
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        assertEquals(reads + 8, peer.reviewReads.get())
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(1, peer.captureWrites.get())

        peer.publishNew.set(true)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) {
            viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" && !viewModel.uiState.value.captureReviewLoading
        })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals(1, peer.captureWrites.get())
        assertEquals(reads + 9, peer.reviewReads.get())
        assertEquals("Read-only retry must not send any camera write", writesAfterCapture, peer.writes.toList())
        assertTrue(peer.pages.isNotEmpty() && peer.pages.all { it == 1 })
    }

    @Test fun failedReviewReadsAreNotReportedAsMissingMediaAndRetryIsReadOnly() = runBlocking {
        connect()
        peer.failListing.set(true)
        val reads = peer.reviewReads.get()
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) {
            peer.reviewReads.get() >= reads + 4 && !viewModel.uiState.value.captureReviewLoading
        })
        assertEquals("READ_FAILED", viewModel.uiState.value.captureReviewStatus.name)
        assertEquals(reads + 4, peer.reviewReads.get())
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.captureStatusReadbackFailed)
        val writes = peer.writes.toList()
        peer.failListing.set(false)
        viewModel.retryCaptureReview()
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals(reads + 5, peer.reviewReads.get())
        assertEquals(writes, peer.writes.toList())
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun failedNativeReviewRetriesRemainBoundedThenSuccessfulOldListingBecomesNotReady() = runBlocking {
        connect(native = true)
        peer.failListing.set(true)
        val reads = peer.reviewReads.get()
        viewModel.captureStill()
        awaitReadFailed()
        assertEquals(reads + 4, peer.reviewReads.get())
        val writes = peer.writes.toList()
        viewModel.retryCaptureReview()
        viewModel.retryCaptureReview()
        assertEquals(CaptureReviewStatus.SEARCHING, viewModel.uiState.value.captureReviewStatus)
        awaitReadFailed()
        assertEquals(reads + 8, peer.reviewReads.get())
        peer.failListing.set(false)
        peer.publishNew.set(false)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        assertEquals(reads + 12, peer.reviewReads.get())
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        peer.publishNew.set(true)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(reads + 13, peer.reviewReads.get())
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals(writes, peer.writes.toList())
        assertEquals(1, peer.captureWrites.get())
        assertTrue(peer.pages.isNotEmpty() && peer.pages.all { it == 1 })
    }

    @Test fun aSuccessfulNativeRoundAnywhereInTheCheckMakesNoCandidateNotReady() = runBlocking {
        connect(native = true)
        peer.publishNew.set(false)
        for (successfulRound in 0..3) {
            peer.listingFailures.addAll((0..3).map { it != successfulRound })
            val reads = peer.reviewReads.get()
            viewModel.captureStill()
            assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
            assertEquals(reads + 4, peer.reviewReads.get())
            assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
            assertNull(viewModel.uiState.value.error)
        }
        assertEquals(4, peer.captureWrites.get())
    }

    @Test fun aNewNativeCandidateAfterFailedRoundsStillResolvesTheSameCapture() = runBlocking {
        connect(native = true)
        peer.listingFailures.addAll(listOf(true, true, false))
        val reads = peer.reviewReads.get()
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(reads + 3, peer.reviewReads.get())
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals(1, peer.captureWrites.get())
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun statusAndListingFailuresAreIndependentAndMediaRetryDoesNotClearStatusWarning() = runBlocking {
        connect()
        peer.failStatus.set(true)
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        assertTrue(viewModel.uiState.value.captureStatusReadbackFailed)
        assertNull(viewModel.uiState.value.error)
        val writes = peer.writes.toList()
        peer.failListing.set(false)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertTrue(viewModel.uiState.value.captureStatusReadbackFailed)
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun completeManualGalleryReadOfOldMediaResolvesReadFailureAndPreservesTheCaptureBoundary() = runBlocking {
        connect()
        peer.failStatus.set(true)
        peer.failListing.set(true)
        peer.publishNew.set(false)
        viewModel.captureStill()
        awaitReadFailed()
        val reads = peer.reviewReads.get()
        val writes = peer.writes.toList()
        peer.failListing.set(false)
        viewModel.refreshMedia()
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertEquals(reads + 1, peer.reviewReads.get())
        assertEquals(CaptureReviewStatus.NOT_READY, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertTrue(viewModel.uiState.value.captureStatusReadbackFailed)
        assertEquals(writes, peer.writes.toList())

        peer.publishNew.set(true)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertTrue(viewModel.uiState.value.captureStatusReadbackFailed)
        assertEquals(reads + 2, peer.reviewReads.get())
        assertEquals(writes, peer.writes.toList())
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun completeManualGalleryReadCanFindNewMediaWithoutAnotherReviewListingOrCapture() = runBlocking {
        connect()
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        val reads = peer.reviewReads.get()
        val writes = peer.writes.toList()
        peer.failListing.set(false)
        viewModel.refreshMedia()
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals("NEW.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(reads + 1, peer.reviewReads.get())
        assertEquals(writes, peer.writes.toList())
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun failedManualGalleryReadKeepsReadFailureAndPriorMedia() = runBlocking {
        connect()
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        val writes = peer.writes.toList()
        viewModel.refreshMedia()
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.FAILED })
        assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun partialManualGalleryCannotResolveReviewUntilTheEntireRequestedListingSucceeds() = runBlocking {
        val gate = startGatedFullGallery()
        val writes = peer.writes.toList()
        try {
            assertTrue(pumpUntil { gate.entered.count == 0L && viewModel.uiState.value.mediaItems.any { it.name == "NEW.JPG" } })
            assertTrue(viewModel.uiState.value.mediaLibraryLoading)
            assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
            assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        } finally { gate.release.countDown() }
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals("NEW.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(writes, peer.writes.toList())
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun failedLaterGalleryPageCannotUseAPartialNewCandidateToClearReadFailure() = runBlocking {
        val gate = startGatedFullGallery(failSecondPage = true)
        val writes = peer.writes.toList()
        try {
            assertTrue(pumpUntil { gate.entered.count == 0L && viewModel.uiState.value.mediaItems.any { it.name == "NEW.JPG" } })
            assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        } finally { gate.release.countDown() }
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.FAILED })
        assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun cancelledPartialGalleryCannotUseItsNewCandidateToClearReadFailure() = runBlocking {
        val gate = startGatedFullGallery()
        val writes = peer.writes.toList()
        try {
            assertTrue(pumpUntil { gate.entered.count == 0L && viewModel.uiState.value.mediaItems.any { it.name == "NEW.JPG" } })
            viewModel.cancelMediaLibraryLoad()
            assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
        } finally { gate.release.countDown() }
        settle()
        assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertFalse(viewModel.uiState.value.mediaLibraryLoading)
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun galleryStartedBeforeCaptureCannotDemoteTheNewCaptureReadFailure() = runBlocking {
        connect()
        val gate = peer.gateNextListing()
        try {
            viewModel.refreshMedia()
            assertTrue(pumpUntil { gate.entered.count == 0L })
            peer.failListing.set(true)
            viewModel.captureStill()
            awaitReadFailed()
        } finally { gate.release.countDown() }
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun galleryFromAnOlderCaptureCannotResolveANewerCaptureAttempt() = runBlocking {
        connect()
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        peer.failListing.set(false)
        val gate = peer.gateNextListing()
        try {
            viewModel.refreshMedia()
            assertTrue(pumpUntil { gate.entered.count == 0L }) // This response snapshots NEW.JPG.
            peer.failListing.set(true)
            viewModel.captureStill()
            awaitReadFailed()
        } finally { gate.release.countDown() }
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertTrue(viewModel.uiState.value.mediaItems.any { it.name == "NEW.JPG" })
        assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(2, peer.captureWrites.get())
    }

    @Test fun galleryStartedBeforeReadOnlyRetryCannotResolveItsNewerReviewGeneration() = runBlocking {
        connect()
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        peer.failListing.set(false)
        val gate = peer.gateNextListing()
        val writes = peer.writes.toList()
        try {
            viewModel.refreshMedia()
            assertTrue(pumpUntil { gate.entered.count == 0L })
            peer.failListing.set(true)
            viewModel.retryCaptureReview() // Same capture attempt, a newer bounded review generation.
            assertEquals(CaptureReviewStatus.SEARCHING, viewModel.uiState.value.captureReviewStatus)
            awaitReadFailed()
        } finally { gate.release.countDown() }
        assertTrue(pumpUntil { !viewModel.uiState.value.mediaLibraryLoading &&
            viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun cancelledOldSessionGalleryCannotChangeTheReplacementSessionReview() = runBlocking {
        connect()
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        peer.failListing.set(false)
        val gate = peer.gateNextListing()
        try {
            viewModel.refreshMedia()
            assertTrue(pumpUntil { gate.entered.count == 0L })
            viewModel.disconnect()
            assertTrue(pumpUntil { !viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
            peer.publishNew.set(false)
            connect()
            peer.failListing.set(true)
            viewModel.captureStill()
            awaitReadFailed()
        } finally { gate.release.countDown() }
        settle()
        assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(2, peer.captureWrites.get())
    }

    private suspend fun startGatedFullGallery(failSecondPage: Boolean = false): CaptureReviewPeer.ListingGate {
        connect(native = true)
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        peer.failListing.set(false)
        peer.pageCount.set(2)
        peer.failSecondPage.set(failSecondPage)
        val gate = peer.gateSecondNativePage()
        viewModel.setMediaLibraryScope(MediaLibraryScope.ALL)
        return gate
    }

    @Test fun successfulOldMediaEventResolvesReadFailureButKeepsOriginalCaptureBoundary() = runBlocking {
        connect()
        peer.failListing.set(true)
        peer.publishNew.set(false)
        viewModel.captureStill()
        awaitReadFailed()
        val writes = peer.writes.toList()
        peer.failListing.set(false)
        peer.enqueueMediaEvent()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        peer.publishNew.set(true)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun lateFailedRetryCannotOverwriteANewerCaptureReview() = runBlocking {
        connect(native = true)
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        val gate = peer.gateNextListing()
        try {
            viewModel.retryCaptureReview()
            assertTrue(pumpUntil { gate.entered.count == 0L })
            peer.failListing.set(false)
            viewModel.captureStill()
            assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        } finally { gate.release.countDown() }
        settle()
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals("NEW.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(2, peer.captureWrites.get())
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun disconnectCancelsFailedRetryAndReplacementSessionHasNoPendingAttempt() = runBlocking {
        connect(native = true)
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        val gate = peer.gateNextListing()
        try {
            viewModel.retryCaptureReview()
            assertTrue(pumpUntil { gate.entered.count == 0L })
            viewModel.disconnect()
        } finally { gate.release.countDown() }
        assertTrue(pumpUntil { !viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        peer.failListing.set(false)
        peer.publishNew.set(false)
        connect(native = true)
        val reads = peer.reviewReads.get()
        viewModel.retryCaptureReview()
        main.scheduler.advanceTimeBy(5_000)
        settle()
        assertEquals(reads, peer.reviewReads.get())
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun failedInitialListingDoesNotClaimCaptureButLaterCaptureCanRetryWithoutPreviousMedia() = runBlocking {
        peer.failListing.set(true)
        viewModel.useDevSimulatorPreset()
        viewModel.setBaseUrl(peer.baseUrl)
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.connect()
        assertTrue(pumpUntil(advanceTime = true) {
            viewModel.uiState.value.connected && !viewModel.uiState.value.busy &&
                peer.reviewReads.get() == 4 && !viewModel.uiState.value.captureReviewLoading
        })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertNull(viewModel.uiState.value.captureReviewItem)
        assertEquals(0, peer.captureWrites.get())
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.READ_FAILED })
        assertEquals(8, peer.reviewReads.get())
        assertNull(viewModel.uiState.value.captureReviewItem)
        assertNull(viewModel.uiState.value.error)
        val writes = peer.writes.toList()
        peer.failListing.set(false)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(9, peer.reviewReads.get())
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun failedReviewRetryDoesNotCompeteWithCurrentMediaOperation() = runBlocking {
        connect(native = true)
        peer.failListing.set(true)
        viewModel.captureStill()
        awaitReadFailed()
        val gate = peer.gateNextMediaInfo()
        val reads = peer.reviewReads.get()
        val writes = peer.writes.toList()
        try {
            viewModel.loadMediaInfo(requireNotNull(viewModel.uiState.value.captureReviewItem))
            assertTrue(pumpUntil { gate.entered.count == 0L })
            viewModel.retryCaptureReview()
            main.scheduler.advanceTimeBy(5_000)
            settle()
            assertEquals(reads, peer.reviewReads.get())
            assertEquals(CaptureReviewStatus.READ_FAILED, viewModel.uiState.value.captureReviewStatus)
            assertFalse(viewModel.uiState.value.captureReviewLoading)
        } finally { gate.release.countDown() }
        assertTrue(pumpUntil { CameraOperation.MEDIA !in viewModel.uiState.value.pendingOperations })
        peer.failListing.set(false)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(reads + 1, peer.reviewReads.get())
        assertEquals(writes, peer.writes.toList())
    }

    private suspend fun awaitReadFailed() {
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.READ_FAILED })
        assertFalse(viewModel.uiState.value.captureReviewLoading)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
    }

    @Test fun oldRetryCannotPublishOrClearANewerCaptureAttempt() = runBlocking {
        connect(native = true)
        peer.publishNew.set(false)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        // This old request snapshots OLD.JPG, then returns after the second command and its review.
        val gate = peer.gateNextListing()
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        peer.publishNew.set(true)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        gate.release.countDown()
        assertTrue(pumpUntil(advanceTime = true) { !viewModel.uiState.value.captureReviewLoading })
        settle()
        assertEquals("NEW.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals(2, peer.captureWrites.get())
    }

    @Test fun disconnectedRetryCannotLeakPendingStateIntoReplacementSession() = runBlocking {
        connect(native = true)
        peer.publishNew.set(false)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        val gate = peer.gateNextListing()
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        viewModel.disconnect()
        gate.release.countDown()
        assertTrue(pumpUntil { !viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertFalse(viewModel.uiState.value.captureStatusReadbackFailed)
        connect(native = true)
        val reads = peer.reviewReads.get()
        viewModel.retryCaptureReview()
        main.scheduler.advanceTimeBy(5_000)
        settle()
        assertEquals(reads, peer.reviewReads.get())
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun cancellationAfterCommandAckDoesNotBecomeReadbackFailureOrStartReview() = runBlocking {
        connect()
        val reads = peer.reviewReads.get()
        val gate = peer.gateNextStatus()
        viewModel.captureStill()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        assertEquals(1, peer.captureWrites.get())
        viewModel.disconnect()
        gate.release.countDown()
        assertTrue(pumpUntil { !viewModel.uiState.value.connected && http.dispatcher.runningCallsCount() == 0 })
        main.scheduler.advanceTimeBy(5_000)
        settle()
        assertEquals(reads, peer.reviewReads.get())
        assertFalse(viewModel.uiState.value.captureStatusReadbackFailed)
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertNull(viewModel.uiState.value.error)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun oldMediaEventDoesNotClearNotReadyAndNewEventCanResolveItWithoutAShutter() = runBlocking {
        connect()
        peer.publishNew.set(false)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        var reads = peer.reviewReads.get()
        peer.enqueueMediaEvent()
        assertTrue(pumpUntil(advanceTime = true) { peer.reviewReads.get() > reads && !viewModel.uiState.value.mediaLibraryLoading })
        assertEquals(CaptureReviewStatus.NOT_READY, viewModel.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", viewModel.uiState.value.captureReviewItem?.name)
        peer.publishNew.set(true)
        reads = peer.reviewReads.get()
        peer.enqueueMediaEvent()
        assertTrue(pumpUntil(advanceTime = true) {
            peer.reviewReads.get() > reads && viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG"
        })
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun reconciliationReadFailureAfterSuccessfulCaptureResponseStillKeepsAcknowledgement() = runBlocking {
        connect()
        val gate = peer.gateNextStatus()
        viewModel.captureStill()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        // A second operation crosses the first read, requiring latestCameraStatus to re-read.
        viewModel.refresh()
        assertTrue(pumpUntil { CameraOperation.STATUS !in viewModel.uiState.value.pendingOperations })
        assertNull(viewModel.uiState.value.error)
        peer.failStatus.set(true)
        gate.release.countDown()
        assertTrue(pumpUntil(advanceTime = true) {
            viewModel.uiState.value.captureStatusReadbackFailed && !viewModel.uiState.value.busy &&
                viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG"
        })
        assertNull(viewModel.uiState.value.error)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun onlySuccessfulStatusRefreshClearsReadbackWarningAndMediaAloneDoesNot() = runBlocking {
        connect()
        peer.failStatus.set(true)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) {
            viewModel.uiState.value.captureStatusReadbackFailed && !viewModel.uiState.value.busy &&
                viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG"
        })
        assertTrue(viewModel.uiState.value.captureStatusReadbackFailed)
        viewModel.refresh()
        assertTrue(pumpUntil { viewModel.uiState.value.errorOperation == CameraOperation.STATUS })
        assertTrue(viewModel.uiState.value.captureStatusReadbackFailed)
        peer.failStatus.set(false)
        viewModel.refresh()
        assertTrue(pumpUntil { !viewModel.uiState.value.busy && !viewModel.uiState.value.captureStatusReadbackFailed })
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun successfulEventStatusSnapshotClearsReadbackWarning() = runBlocking {
        connect()
        peer.failStatus.set(true)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureStatusReadbackFailed && !viewModel.uiState.value.busy })
        peer.failStatus.set(false)
        peer.enqueueMediaEvent()
        assertTrue(pumpUntil(advanceTime = true) { !viewModel.uiState.value.captureStatusReadbackFailed })
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun manualReviewRetryWaitsForTheCurrentMediaOperationThenRemainsAvailable() = runBlocking {
        connect(native = true)
        peer.publishNew.set(false)
        viewModel.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY })
        val oldItem = requireNotNull(viewModel.uiState.value.captureReviewItem)
        val gate = peer.gateNextMediaInfo()
        try {
            // Production MEDIA operation, independent metadata response held at the HTTP peer.
            viewModel.loadMediaInfo(oldItem)
            assertTrue(pumpUntil { gate.entered.count == 0L })
            assertTrue(CameraOperation.MEDIA in viewModel.uiState.value.pendingOperations)
            val reads = peer.reviewReads.get()
            val writes = peer.writes.toList()
            viewModel.retryCaptureReview()
            main.scheduler.advanceTimeBy(5_000)
            settle()
            assertEquals("A retry must not compete with an active MEDIA operation", reads, peer.reviewReads.get())
            assertEquals(CaptureReviewStatus.NOT_READY, viewModel.uiState.value.captureReviewStatus)
            assertFalse(viewModel.uiState.value.captureReviewLoading)
            assertEquals(writes, peer.writes.toList())
        } finally {
            gate.release.countDown()
        }
        assertTrue(pumpUntil { CameraOperation.MEDIA !in viewModel.uiState.value.pendingOperations })
        assertNull(viewModel.uiState.value.error)
        val reads = peer.reviewReads.get()
        peer.publishNew.set(true)
        viewModel.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { viewModel.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        assertEquals(reads + 1, peer.reviewReads.get())
        assertEquals(CaptureReviewStatus.IDLE, viewModel.uiState.value.captureReviewStatus)
        assertEquals(1, peer.captureWrites.get())
    }

    private suspend fun connect(native: Boolean = false) {
        peer.native.set(native)
        if (native) viewModel.useDirectCameraPreset() else viewModel.useDevSimulatorPreset()
        viewModel.setBaseUrl(peer.baseUrl)
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.connect()
        val connected = pumpUntil(advanceTime = true) {
            viewModel.uiState.value.connected && !viewModel.uiState.value.busy &&
                viewModel.uiState.value.captureReviewItem?.name == "OLD.JPG" && !viewModel.uiState.value.captureReviewLoading
        }
        assertTrue("Connection failed: ${viewModel.uiState.value.error}; item=${viewModel.uiState.value.captureReviewItem}; reads=${peer.reviewReads.get()}", connected)
    }

    private suspend fun settle() { repeat(5) { main.scheduler.runCurrent(); delay(10) } }
    private suspend fun pumpUntil(timeoutMillis: Long = 4_000, advanceTime: Boolean = false, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < end) {
            main.scheduler.runCurrent()
            if (condition()) return true
            if (advanceTime) main.scheduler.advanceTimeBy(100)
            delay(10)
        }
        main.scheduler.runCurrent()
        return condition()
    }
}

private class CaptureReviewPeer {
    val server = MockWebServer()
    lateinit var baseUrl: String
    val native = AtomicBoolean(false)
    val failStatus = AtomicBoolean(false)
    val failListing = AtomicBoolean(false)
    val listingFailures = ConcurrentLinkedQueue<Boolean>()
    private val nativeListingFailed = AtomicBoolean(false)
    val rejectCapture = AtomicBoolean(false)
    val publishNew = AtomicBoolean(true)
    val captureWrites = AtomicInteger()
    val writes = CopyOnWriteArrayList<String>()
    val failedStatusReads = AtomicInteger()
    val reviewReads = AtomicInteger()
    val pages = CopyOnWriteArrayList<Int>()
    val pageCount = AtomicInteger(1000)
    val failSecondPage = AtomicBoolean(false)
    class ListingGate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
    private val gates = CopyOnWriteArrayList<ListingGate>()
    private val nextListingGate = AtomicReference<ListingGate?>()
    fun gateNextListing() = ListingGate().also { gates += it; nextListingGate.set(it) }
    private val secondNativePageGate = AtomicReference<ListingGate?>()
    fun gateSecondNativePage() = ListingGate().also { gates += it; secondNativePageGate.set(it) }
    fun releaseGates() { gates.forEach { it.release.countDown() } }
    private val nextStatusGate = AtomicReference<ListingGate?>()
    fun gateNextStatus() = ListingGate().also { gates += it; nextStatusGate.set(it) }
    private val nextMediaInfoGate = AtomicReference<ListingGate?>()
    fun gateNextMediaInfo() = ListingGate().also { gates += it; nextMediaInfoGate.set(it) }
    private val eventPending = AtomicBoolean(false)
    private val eventSequence = AtomicInteger()
    fun enqueueMediaEvent() { eventPending.set(true) }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                if (request.method != "GET") writes += "${request.method} $path"
                if (request.method == "POST" && (path.endsWith("/shutterbutton") || path == "/ccapi/capture/still")) {
                    captureWrites.incrementAndGet()
                    return if (rejectCapture.get()) MockResponse().setResponseCode(503) else json("{}")
                }
                val statusShouldFail = failStatus.get()
                if (path == "/ccapi/status") nextStatusGate.getAndSet(null)?.let { gate ->
                    gate.entered.countDown()
                    check(gate.release.await(5, TimeUnit.SECONDS)) { "Status gate was not released" }
                }
                if (statusShouldFail && (path == "/ccapi/status" || path.endsWith("/devicestatus/battery"))) {
                    failedStatusReads.incrementAndGet()
                    return MockResponse().setResponseCode(503).setBody("Synthetic status read failure")
                }
                val latest = if (captureWrites.get() > 0 && !rejectCapture.get() && publishNew.get()) "NEW.JPG" else "OLD.JPG"
                return when {
                    path == "/ccapi" -> json("""{"ver110":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/control/shutterbutton","post":true},{"path":"/contents","get":true}]}""")
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Synthetic capture review","serialnumber":"TEST-REVIEW"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    path == "/ccapi/info" -> json("""{"connected":true,"model":"Synthetic capture review","serial":"TEST-REVIEW","api":"simulator"}""")
                    path == "/ccapi/status" -> json("""{"connected":true,"recording":false,"mode":"photo","battery":{},"media":{},"exposure":{}}""")
                    path == "/ccapi/capabilities" -> json("""{"iso":["100"],"shutter":["1/125"],"aperture":["4.0"],"white_balance":["auto"]}""")
                    path == "/ccapi/events" -> {
                        val keys = if (eventPending.getAndSet(false)) {
                            eventSequence.incrementAndGet()
                            listOf("contents")
                        } else emptyList()
                        json(JSONObject().put("sequence", eventSequence.get()).put("keys", JSONArray(keys)).toString())
                    }
                    path == "/ccapi/media" -> {
                        reviewReads.incrementAndGet()
                        val failed = listingFailures.poll() ?: failListing.get()
                        nextListingGate.getAndSet(null)?.let { gate ->
                            gate.entered.countDown()
                            check(gate.release.await(5, TimeUnit.SECONDS)) { "Simulator listing gate was not released" }
                        }
                        if (failed) return MockResponse().setResponseCode(503).setBody("Synthetic private failure detail")
                        json(JSONObject().put("items", JSONArray().put(JSONObject().put("id", latest)
                            .put("name", latest).put("kind", "jpeg"))).toString())
                    }
                    path.endsWith("/contents") && url.queryParameter("kind") == "number" -> {
                        reviewReads.incrementAndGet()
                        nativeListingFailed.set(listingFailures.poll() ?: failListing.get())
                        json("""{"pagenumber":${pageCount.get()}}""")
                    }
                    path.endsWith("/contents") && url.queryParameter("page") != null -> {
                        pages += url.queryParameter("page")!!.toInt()
                        val paths = listOf(latest) + (1..7).map { "OLDER$it.JPG" }
                        val failed = nativeListingFailed.get() ||
                            (url.queryParameter("page") == "2" && failSecondPage.get())
                        if (url.queryParameter("page") == "2") secondNativePageGate.getAndSet(null)?.let { gate ->
                            gate.entered.countDown()
                            check(gate.release.await(5, TimeUnit.SECONDS)) { "Second native page was not released" }
                        }
                        nextListingGate.getAndSet(null)?.let { gate ->
                            gate.entered.countDown()
                            check(gate.release.await(5, TimeUnit.SECONDS)) { "Listing gate was not released" }
                        }
                        if (failed) return MockResponse().setResponseCode(503).setBody("Synthetic private failure detail")
                        json(JSONObject().put("path", JSONArray(paths.map { "/ccapi/ver110/contents/$it" })).toString())
                    }
                    path.endsWith("/contents") -> json("{}")
                    url.queryParameter("kind") == "info" -> {
                        nextMediaInfoGate.getAndSet(null)?.let { gate ->
                            gate.entered.countDown()
                            check(gate.release.await(5, TimeUnit.SECONDS)) { "Media info gate was not released" }
                        }
                        json("{}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
