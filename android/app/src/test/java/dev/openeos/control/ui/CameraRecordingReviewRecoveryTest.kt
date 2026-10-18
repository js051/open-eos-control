package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.RecordingCommand
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real VM/repository clients: simulator explicit snapshots prove REC; native queued hints never do. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraRecordingReviewRecoveryTest {
    private val main = StandardTestDispatcher()
    private val peer = RecordingReviewPeer()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory { CameraHttpTransport(http, CameraNetworkDiagnostics.Empty) },
    ))
    private lateinit var model: CameraViewModel

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        withContext(Dispatchers.IO) { peer.start() }
        model = CameraViewModel(repository)
    }

    @After fun tearDown() = runBlocking {
        try {
            peer.releaseGates()
            model.disconnect()
            http.dispatcher.cancelAll()
            try {
                assertTrue("HTTP calls did not finish teardown", pumpUntil { http.dispatcher.runningCallsCount() == 0 })
            } finally {
                finishTestSession()
            }
        } finally {
            withContext(Dispatchers.IO) { peer.server.shutdown() }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            Dispatchers.resetMain()
        }
    }

    @Test fun fixtureCleanupWaitsForDetachedEventDeleteAfterViewModelScopeCompletes() = runBlocking {
        connect(native = true)
        val gate = peer.gateNextEventDelete()
        model.disconnect()
        assertTrue("Disconnect must enter the actual event subscription DELETE", pumpUntil { gate.entered.count == 0L })
        try {
            model.cancelAndAwaitTestScope(main)
            assertTrue(requireNotNull(model.viewModelScope.coroutineContext[Job]).isCompleted)
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { finishTestSession() }
            main.scheduler.runCurrent()
            assertFalse("Completed ViewModel scope cannot substitute for detached repository cleanup", cleanup.isCompleted)
            assertEquals(1L, gate.release.count)
            gate.release.countDown()
            assertTrue("Fixture cleanup must finish after the peer acknowledges DELETE", pumpUntil { cleanup.isCompleted })
            cleanup.await()
        } finally {
            gate.release.countDown()
            // Failure cleanup is independent of the helper being tested.
            val repositoryCleanup = async(start = CoroutineStart.UNDISPATCHED) { repository.disconnect() }
            try {
                assertTrue(pumpUntil { repositoryCleanup.isCompleted })
                repositoryCleanup.await()
                main.scheduler.runCurrent()
            } finally { repositoryCleanup.cancel() }
        }
    }

    private suspend fun finishTestSession() = coroutineScope {
        model.cancelAndAwaitTestScope(main)
        // disconnect() owns a NonCancellable job outside viewModelScope's child tree.
        // Join its repository lock while pumping the same Main dispatcher; merely cancelling
        // attached children (or observing HTTP idle) cannot prove that cleanup returned.
        val repositoryCleanup = async(start = CoroutineStart.UNDISPATCHED) { repository.disconnect() }
        try {
            assertTrue("Detached repository cleanup did not finish", pumpUntil { repositoryCleanup.isCompleted })
            repositoryCleanup.await()
            main.scheduler.runCurrent()
        } finally { repositoryCleanup.cancel() }
    }

    @Test fun stoppedObservedRecordingFindsDelayedMp4WithoutContentsEventsOrAnotherCommand() = runBlocking {
        connect()
        startRecording()
        val reads = peer.listingReads.get()
        peer.publishAfterStopRead.set(3)
        model.toggleRecording()
        model.toggleRecording() // A second tap while RECORDING is pending cannot replay Stop.
        assertTrue(pumpUntil(advanceTime = true) { !model.uiState.value.busy && model.uiState.value.status?.recording == false })
        assertTrue("Successful REC Stop must start bounded review; listing delta=${peer.listingReads.get() - reads}",
            pumpUntil(advanceTime = true) { peer.listingReads.get() > reads })
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
        assertEquals(reads + 3, peer.listingReads.get())
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
        assertEquals(0, peer.nonemptyEvents.get())
        assertTrue(peer.eventReads.get() > 0)
        assertTrue(peer.pages.all { it == 1 })
        assertNull(model.uiState.value.error)
    }

    @Test fun newJpegCannotFinishRecordingReviewBeforeDelayedMp4() = runBlocking {
        connect()
        startRecording()
        val reads = peer.listingReads.get()
        peer.publishNewJpeg.set(true)
        peer.publishAfterStopRead.set(3)
        model.toggleRecording()
        val foundVideo = pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" }
        assertTrue("Stop review selected ${model.uiState.value.captureReviewItem?.name}; listing delta=${peer.listingReads.get() - reads}", foundVideo)
        assertEquals(reads + 3, peer.listingReads.get())
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
        assertEquals(0, peer.nonemptyEvents.get())
    }

    @Test fun previouslyReadVideoCannotFinishReviewWhenOnlyTheLatestPhotoWasDisplayed() = runBlocking {
        connect()
        assertTrue(model.uiState.value.mediaItems.isEmpty())
        startRecording()
        model.toggleRecording()
        awaitNotReady()
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun rejectedStillAttemptPreservesKnownVideoBoundaryForLaterRecordingStop() = runBlocking {
        connect()
        assertTrue(model.uiState.value.mediaItems.isEmpty())
        peer.rejectCapture.set(true)
        model.captureStill()
        assertTrue(pumpUntil { model.uiState.value.errorOperation == CameraOperation.CAPTURE && !model.uiState.value.busy })
        assertEquals(1, peer.captureWrites.get())
        startRecording()
        model.toggleRecording()
        awaitNotReady()
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun newlyVisibleVideoWithOlderTimestampIsNotHiddenByKnownVideo() = runBlocking {
        peer.historicalTimestamps.set(true)
        connect()
        startRecording()
        peer.publishAfterStopRead.set(0)
        model.toggleRecording()
        val found = pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" }
        assertTrue("Known timestamp must not hide a new visible ID; state=${model.uiState.value.captureReviewStatus}; item=${model.uiState.value.captureReviewItem?.name}", found)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun newJpegContentsEventKeepsVideoPendingAndLaterVideoEventResolvesIt() = runBlocking {
        connect()
        startRecording()
        model.toggleRecording()
        awaitNotReady()
        peer.publishNewJpeg.set(true)
        var reads = peer.listingReads.get()
        peer.enqueueContentsEvent()
        assertTrue(pumpUntil(advanceTime = true) { peer.listingReads.get() > reads && !model.uiState.value.mediaLibraryLoading })
        assertEquals(CaptureReviewStatus.NOT_READY, model.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        peer.publishAfterStopRead.set(0)
        reads = peer.listingReads.get()
        peer.enqueueContentsEvent()
        assertTrue(pumpUntil(advanceTime = true) {
            peer.listingReads.get() > reads && model.uiState.value.captureReviewItem?.name == "NEW.MP4"
        })
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun exhaustedStopReviewRetainsItsBoundaryAndRetriesOnlyReads() = runBlocking {
        connect()
        startRecording()
        val reads = peer.listingReads.get()
        model.toggleRecording()
        awaitNotReady()
        assertEquals(reads + 4, peer.listingReads.get())
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        val writes = peer.writes.toList()
        model.retryCaptureReview()
        model.retryCaptureReview()
        awaitNotReady()
        assertEquals(reads + 8, peer.listingReads.get())
        peer.publishAfterStopRead.set(0)
        model.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
        assertEquals(reads + 9, peer.listingReads.get())
        assertEquals(writes, peer.writes.toList())
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun failedStopReviewCanRetryReadsWithoutReplayingStopOrAcceptingANewPhoto() = runBlocking {
        connect()
        startRecording()
        val reads = peer.listingReads.get()
        peer.failListing.set(true)
        model.toggleRecording()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewStatus == CaptureReviewStatus.READ_FAILED })
        assertEquals(reads + 4, peer.listingReads.get())
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        assertFalse(model.uiState.value.captureReviewLoading)
        assertNull(model.uiState.value.error)
        val writes = peer.writes.toList()
        peer.failListing.set(false)
        peer.publishNewJpeg.set(true)
        model.retryCaptureReview()
        model.retryCaptureReview()
        awaitNotReady()
        assertEquals(reads + 8, peer.listingReads.get())
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        peer.publishAfterStopRead.set(0)
        model.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
        assertEquals(reads + 9, peer.listingReads.get())
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(writes, peer.writes.toList())
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
        assertEquals(0, peer.captureWrites.get())
        assertTrue(peer.pages.all { it == 1 })
    }

    @Test fun twoKnownOldIdsReorderedDuringStopAndEarlyContentsEventDoNotResolveReview() = runBlocking {
        connect()
        model.refreshMedia()
        assertTrue(pumpUntil(advanceTime = true) { !model.uiState.value.mediaLibraryLoading && model.uiState.value.mediaItems.size == 8 })
        startRecording()
        val gate = peer.gateNextStop()
        model.toggleRecording()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        peer.reverseOldItems.set(true)
        peer.enqueueContentsEvent()
        // The event is consumed before ACK; its state/media synchronization waits for RECORDING.
        assertTrue(pumpUntil(advanceTime = true) { peer.nonemptyEvents.get() == 1 })
        gate.release.countDown()
        awaitNotReady()
        // Both IDs were already loaded before Stop. Reordering is not newly visible media.
        assertNotEquals("NEW.MP4", model.uiState.value.captureReviewItem?.name)
        peer.enqueueContentsEvent()
        val afterStop = peer.listingReads.get()
        assertTrue(pumpUntil(advanceTime = true) { peer.listingReads.get() > afterStop && !model.uiState.value.mediaLibraryLoading })
        assertEquals(CaptureReviewStatus.NOT_READY, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
        peer.publishAfterStopRead.set(0)
        model.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
    }

    @Test fun startDirectionDoesNotReviewEvenWhenReadbackSaysNotRecording() = runBlocking {
        connect(native = false)
        val reads = peer.listingReads.get()
        peer.reportedRecording.set(false)
        model.toggleRecording()
        assertTrue(pumpUntil { peer.recordingActions.size == 1 && !model.uiState.value.busy })
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start"), peer.recordingActions.toList())
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertTrue(model.uiState.value.recordingStartNotConfirmed)
    }

    @Test fun rejectedStopDoesNotReviewOrReplayTheCommand() = runBlocking {
        connect()
        startRecording()
        peer.rejectStop.set(true)
        val reads = peer.listingReads.get()
        model.toggleRecording()
        assertTrue(pumpUntil { model.uiState.value.errorOperation == CameraOperation.RECORDING })
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals(true, model.uiState.value.status?.recording)
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun rejectedStartDoesNotReviewOrInventRecordingState() = runBlocking {
        connect(native = true)
        val previousRecording = model.uiState.value.status?.recording
        peer.rejectStart.set(true)
        val reads = peer.listingReads.get()
        model.toggleRecording()
        assertTrue(pumpUntil { model.uiState.value.errorOperation == CameraOperation.RECORDING })
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals(previousRecording, model.uiState.value.status?.recording)
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start"), peer.recordingActions.toList())
    }

    @Test fun stopThatReturnsRecordingDoesNotStartReview() = runBlocking {
        connect(native = false)
        startRecording()
        peer.reportedRecording.set(true)
        val reads = peer.listingReads.get()
        model.toggleRecording()
        assertTrue(pumpUntil { peer.recordingActions.size == 2 && !model.uiState.value.busy })
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals(true, model.uiState.value.status?.recording)
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun stopThatReturnsUnknownRecordingDoesNotStartReview() = runBlocking {
        connect(native = false)
        startRecording()
        peer.unknownRecording.set(true)
        val reads = peer.listingReads.get()
        model.toggleRecording()
        assertTrue(pumpUntil { peer.recordingActions.size == 2 && !model.uiState.value.busy })
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertNull(model.uiState.value.status?.recording)
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun acknowledgedStopReadbackFailureKeepsStatusAndWaitsForReadOnlyRefresh() = runBlocking {
        connect(native = false)
        startRecording()
        peer.failStatus.set(true)
        val reads = peer.listingReads.get()
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.STOP)
        assertFalse(model.uiState.value.recordingStopAvailable)
        assertNull(model.uiState.value.pendingRecordingCommand)
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(true, model.uiState.value.status?.recording)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.captureStatusReadbackFailed)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun stoppedRecordingRetryWaitsForMediaOperationAndRemainsReadOnly() = runBlocking {
        connect()
        startRecording()
        model.toggleRecording()
        awaitNotReady()
        val gate = peer.gateNextInfo()
        model.loadMediaInfo(requireNotNull(model.uiState.value.captureReviewItem))
        assertTrue(pumpUntil { gate.entered.count == 0L })
        assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
        val reads = peer.listingReads.get()
        val writes = peer.writes.toList()
        model.retryCaptureReview()
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals(CaptureReviewStatus.NOT_READY, model.uiState.value.captureReviewStatus)
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.isBusy(CameraOperation.MEDIA) })
        peer.publishAfterStopRead.set(0)
        model.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
        assertEquals(writes, peer.writes.toList())
    }

    @Test fun automaticStopReviewWaitsForExistingMediaOperation() = runBlocking {
        connect()
        startRecording()
        val gate = peer.gateNextInfo()
        model.loadMediaInfo(requireNotNull(model.uiState.value.captureReviewItem))
        assertTrue(pumpUntil { gate.entered.count == 0L })
        val reads = peer.listingReads.get()
        peer.publishAfterStopRead.set(0)
        model.toggleRecording()
        assertTrue(pumpUntil { model.uiState.value.status?.recording == false && CameraOperation.RECORDING !in model.uiState.value.pendingOperations })
        advanceAndSettle()
        assertEquals("Automatic review must not compete with MEDIA", reads, peer.listingReads.get())
        assertEquals(CaptureReviewStatus.SEARCHING, model.uiState.value.captureReviewStatus)
        gate.release.countDown()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun oldRetryCannotPublishOrClearANewerStoppedRecordingAttempt() = runBlocking {
        connect()
        startRecording()
        model.toggleRecording()
        awaitNotReady()
        peer.publishAfterStopRead.set(0)
        val gate = peer.gateNextListing()
        model.retryCaptureReview()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        peer.publishAfterStopRead.set(Int.MAX_VALUE)
        startRecording()
        model.toggleRecording()
        awaitNotReady()
        gate.release.countDown()
        advanceAndSettle()
        assertEquals(CaptureReviewStatus.NOT_READY, model.uiState.value.captureReviewStatus)
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        assertEquals(listOf("start", "stop", "start", "stop"), peer.recordingActions.toList())
    }

    @Test fun disconnectedLateReviewCannotPublishIntoReplacementSession() = runBlocking {
        connect()
        startRecording()
        peer.publishAfterStopRead.set(0)
        val gate = peer.gateNextListing()
        model.toggleRecording()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        model.disconnect()
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.connected && !model.uiState.value.busy })
        peer.publishAfterStopRead.set(Int.MAX_VALUE)
        connect()
        val reads = peer.listingReads.get()
        model.retryCaptureReview()
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun disconnectedStopResponseCannotStartReviewInReplacementSession() = runBlocking {
        connect()
        startRecording()
        val gate = peer.gateNextStop()
        val reads = peer.listingReads.get()
        model.toggleRecording()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        model.disconnect()
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.connected && !model.uiState.value.busy })
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertNull(model.uiState.value.error)
        connect()
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun acknowledgedStartFailureKeepsOldStatusAndRefreshNeverReplaysRecording() = runBlocking {
        connect(native = false)
        peer.failStatus.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        assertEquals(false, model.uiState.value.status?.recording)
        assertNull(model.uiState.value.error)
        model.setCaptureMode(CaptureMode.PHOTO)
        assertEquals(CaptureMode.VIDEO, model.uiState.value.captureMode)
        model.captureStill()
        advanceAndSettle()
        assertEquals(0, peer.captureWrites.get())
        val writes = peer.writes.toList()
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy })
        assertEquals(RecordingCommand.START, model.uiState.value.pendingRecordingCommand)
        peer.failStatus.set(false)
        peer.unknownRecording.set(true)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy })
        assertEquals(RecordingCommand.START, model.uiState.value.pendingRecordingCommand)
        peer.unknownRecording.set(false)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.pendingRecordingCommand == null })
        assertEquals(true, model.uiState.value.status?.recording)
        assertEquals(writes, peer.writes.toList())
        assertEquals(listOf("start"), peer.recordingActions.toList())
    }

    @Test fun unknownStartUsesStopOnlyAndConfirmedStopRefreshResumesOriginalVideoReview() = runBlocking {
        connect(native = false)
        peer.unknownRecording.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        assertNull(model.uiState.value.status?.recording)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.STOP)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
        val reads = peer.listingReads.get()
        advanceAndSettle()
        assertEquals(reads, peer.listingReads.get())
        peer.unknownRecording.set(false)
        model.refreshRecordingStatus()
        awaitNotReady()
        assertEquals("OLD.JPG", model.uiState.value.captureReviewItem?.name)
        peer.publishAfterStopRead.set(0)
        model.retryCaptureReview()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun repeatedPendingStopRetainsPreStopIdsAfterAnInterveningGalleryRead() = runBlocking {
        connect(native = false)
        startRecording()
        peer.unknownRecording.set(true)
        peer.dropNextRecordingResponse.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.STOP)
        peer.publishAfterStopRead.set(0)
        model.refreshMedia()
        assertTrue(pumpUntil { !model.uiState.value.mediaLibraryLoading &&
            model.uiState.value.mediaItems.any { it.name == "NEW.MP4" } })
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.STOP)
        peer.unknownRecording.set(false)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil(advanceTime = true) {
            model.uiState.value.pendingRecordingCommand == null &&
                model.uiState.value.captureReviewStatus == CaptureReviewStatus.IDLE &&
                model.uiState.value.captureReviewItem?.name == "NEW.MP4"
        })
        assertEquals(listOf("start", "stop", "stop"), peer.recordingActions.toList())
    }

    @Test fun rejectedSafetyStopRetainsDutyUntilFreshFalseWithoutRewritingResponse() = runBlocking {
        connect(native = false)
        peer.unknownRecording.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        peer.rejectStop.set(true)
        model.toggleRecording()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.errorOperation == CameraOperation.RECORDING })
        assertEquals(RecordingCommand.STOP, model.uiState.value.pendingRecordingCommand)
        val reads = peer.listingReads.get()
        peer.unknownRecording.set(false)
        peer.reportedRecording.set(false)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.pendingRecordingCommand == null })
        assertEquals(dev.openeos.control.data.RecordingResponseEvidence.REJECTED,
            model.uiState.value.recordingReceipt?.response)
        assertFalse(model.uiState.value.recordingStopAvailable)
        assertTrue(model.uiState.value.recordingReceipt?.mayReviewStoppedVideo == true)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun lateRefreshCannotClearNewerPendingStopOrPublishItsOldBoolean() = runBlocking {
        connect(native = false)
        peer.unknownRecording.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        peer.unknownRecording.set(false)
        peer.reportedRecording.set(false)
        val gate = peer.gateNextStatus()
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        peer.unknownRecording.set(true)
        model.toggleRecording()
        assertTrue(pumpUntil { model.uiState.value.recordingReceipt?.command == RecordingCommand.STOP &&
            CameraOperation.RECORDING !in model.uiState.value.pendingOperations })
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.busy })
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertFalse(model.uiState.value.recordingStopAvailable)
        assertNull(model.uiState.value.status?.recording)
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun refreshDuringInFlightRecordingDoesNotReplaceItsOwnedResponseOrReplay() = runBlocking {
        connect(native = false)
        val gate = peer.gateNextStatus()
        model.toggleRecording()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { CameraOperation.STATUS !in model.uiState.value.pendingOperations })
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.status?.recording == true })
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertEquals(listOf("start"), peer.recordingActions.toList())
    }

    @Test fun disconnectCancelsAcceptedReadbackAndLateResponseCannotRestorePending() = runBlocking {
        connect(native = false)
        val gate = peer.gateNextStatus()
        model.toggleRecording()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        model.disconnect()
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.connected && !model.uiState.value.busy })
        advanceAndSettle()
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertNull(model.uiState.value.error)
        connect(native = false)
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertEquals(listOf("start"), peer.recordingActions.toList())
    }

    @Test fun malformedRecordingObservationsNeverClearAcknowledgedPendingOrReplacePriorSnapshot() = runBlocking {
        connect(native = false)
        for (command in RecordingCommand.entries) {
            val previousRecording = model.uiState.value.status?.recording
            peer.recordingJson.set("0")
            model.toggleRecording()
            awaitRecordingPending(command)
            assertFalse(model.uiState.value.recordingCommandUnconfirmed)
            assertEquals(previousRecording, model.uiState.value.status?.recording)
            assertEquals(command == RecordingCommand.START, peer.recording.get())
            val actions = peer.recordingActions.toList()
            for (invalid in listOf("0", "1", "2", "\"true\"", "\"false\"", "\"unknown\"", "[]", "{}")) {
                peer.recordingJson.set(invalid)
                model.refreshRecordingStatus()
                assertTrue(pumpUntil { !model.uiState.value.busy })
                assertEquals(invalid, command.takeIf { it == RecordingCommand.START }, model.uiState.value.pendingRecordingCommand)
                assertFalse(invalid, model.uiState.value.recordingCommandUnconfirmed)
                assertEquals(invalid, previousRecording, model.uiState.value.status?.recording)
                assertEquals(actions, peer.recordingActions.toList())
                assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
            }
            peer.recordingJson.set(if (command == RecordingCommand.START) "true" else "false")
            model.refreshRecordingStatus()
            assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.pendingRecordingCommand == null })
            assertEquals(command == RecordingCommand.START, model.uiState.value.status?.recording)
            assertEquals(actions, peer.recordingActions.toList())
            peer.recordingJson.set(null)
        }
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun malformedRefreshRetainsUnknownOwnerAndSnapshotUntilExplicitStop() = runBlocking {
        connect(native = false)
        peer.dropNextRecordingResponse.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        assertTrue(model.uiState.value.recordingCommandUnconfirmed)
        assertEquals(false, model.uiState.value.status?.recording)
        for (invalid in listOf("0", "1", "2", "\"false\"", "[]", "{}")) {
            peer.recordingJson.set(invalid)
            model.refreshRecordingStatus()
            assertTrue(pumpUntil { !model.uiState.value.busy })
            assertTrue(invalid, model.uiState.value.recordingCommandUnconfirmed)
            assertEquals(RecordingCommand.START, model.uiState.value.pendingRecordingCommand)
            assertEquals(false, model.uiState.value.status?.recording)
            assertEquals(listOf("start"), peer.recordingActions.toList())
        }
        peer.recordingJson.set(null)
        model.toggleRecording()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.pendingRecordingCommand == null })
        assertFalse(model.uiState.value.recordingCommandUnconfirmed)
        assertFalse(peer.recording.get())
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun appliedStartWithLostResponseCannotBeClearedByCachedFalseOrOldEventsAndNextTapStops() = runBlocking {
        connect(native = true)
        peer.enqueueRecordingEvent("stop")
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.status?.recording == false })
        peer.dropNextRecordingResponse.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        assertTrue(peer.recording.get()) // Independent camera truth, changed before losing the response.
        assertTrue(model.uiState.value.recordingCommandUnconfirmed)
        assertEquals(false, model.uiState.value.status?.recording)
        val request = peer.recordingRequests.single()
        assertNull(request.failure)
        assertEquals("POST", request.method)
        assertEquals("start", JSONObject(request.body.clone().readUtf8()).getString("action"))
        val writes = peer.recordingActions.toList()
        repeat(2) {
            model.refreshRecordingStatus()
            assertTrue(pumpUntil { !model.uiState.value.busy })
            assertTrue(model.uiState.value.recordingCommandUnconfirmed)
            assertEquals(RecordingCommand.START, model.uiState.value.pendingRecordingCommand)
        }
        advanceAndSettle() // No new event: only the pre-admission cached false remains.
        assertTrue(model.uiState.value.recordingCommandUnconfirmed)
        assertTrue(model.uiState.value.recordingStopAvailable)
        assertEquals(writes, peer.recordingActions.toList())
        // Withdrawal of optional capabilities or a temperature restriction must not hide Stop.
        overrideState { it.copy(capabilities = null, status = it.status?.copy(
            temperature = dev.openeos.control.data.CameraTemperatureStatus.RESTRICTION_MOVIE_RECORDING)) }
        assertFalse(model.uiState.value.movieRecordingTemperatureAllowed)
        model.toggleRecording()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.pendingRecordingCommand == null })
        assertFalse(peer.recording.get())
        assertFalse(model.uiState.value.recordingCommandUnconfirmed)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun rejectedStartNeverCreatesDutyAndRejectedStopRetainsItsOwnResponse() = runBlocking {
        connect(native = false)
        peer.rejectStart.set(true)
        model.toggleRecording()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.errorOperation == CameraOperation.RECORDING })
        assertFalse(peer.recording.get())
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertFalse(model.uiState.value.recordingCommandUnconfirmed)
        peer.rejectStart.set(false)
        peer.dropNextRecordingResponse.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        peer.rejectStop.set(true)
        model.toggleRecording()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.errorOperation == CameraOperation.RECORDING })
        peer.reportedRecording.set(false)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy })
        assertTrue(peer.recording.get())
        assertEquals(dev.openeos.control.data.RecordingResponseEvidence.REJECTED, model.uiState.value.recordingReceipt?.response)
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertFalse(model.uiState.value.recordingStopAvailable)
        assertEquals(listOf("start", "start", "stop"), peer.recordingActions.toList())
    }

    @Test fun lostStopFreshFalseKeepsUnknownResponseAndFindsVideoWithoutAnotherStop() = runBlocking {
        connect(native = false)
        startRecording()
        peer.dropNextRecordingResponse.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.STOP)
        assertFalse(peer.recording.get())
        assertTrue(model.uiState.value.recordingCommandUnconfirmed)
        peer.reportedRecording.set(false)
        peer.publishAfterStopRead.set(0)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.captureReviewItem?.name == "NEW.MP4" })
        assertTrue(model.uiState.value.recordingCommandUnconfirmed)
        assertEquals(dev.openeos.control.data.RecordingResponseEvidence.UNKNOWN, model.uiState.value.recordingReceipt?.response)
        assertFalse(model.uiState.value.recordingStopAvailable)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun cancelBeforeSendHasNoResponsibilityButCancelAfterPeerAppliedStartRetainsStop() = runBlocking {
        connect(native = true)
        val preflight = peer.gateNextTemperature()
        model.toggleRecording()
        assertTrue(pumpUntil { preflight.entered.count == 0L })
        val cancelledBeforeSend = recordingJob()
        cancelledBeforeSend.cancel()
        preflight.release.countDown()
        assertTrue(pumpUntil { cancelledBeforeSend.isCompleted && !model.uiState.value.busy })
        assertTrue(cancelledBeforeSend.isCancelled)
        assertTrue(peer.recordingActions.isEmpty())
        assertNull(model.uiState.value.pendingRecordingCommand)
        val gate = peer.gateNextRecording()
        model.toggleRecording()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        assertTrue(peer.recording.get())
        val job = recordingJob()
        job.cancel()
        gate.release.countDown()
        assertTrue(pumpUntil { job.isCompleted && !model.uiState.value.busy })
        assertTrue(job.isCancelled)
        assertTrue(model.uiState.value.recordingCommandUnconfirmed)
        assertEquals(RecordingCommand.START, model.uiState.value.pendingRecordingCommand)
        peer.reportedRecording.set(false)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy })
        assertTrue(model.uiState.value.recordingCommandUnconfirmed)
        peer.reportedRecording.set(null)
        model.toggleRecording()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.pendingRecordingCommand == null })
        assertFalse(peer.recording.get())
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun lateUnknownRefreshCannotRestoreResponsibilityAfterNewManualStopAck() = runBlocking {
        connect(native = false)
        peer.dropNextRecordingResponse.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        peer.reportedRecording.set(false)
        val gate = peer.gateNextStatus()
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        model.toggleRecording()
        assertTrue(pumpUntil { CameraOperation.RECORDING !in model.uiState.value.pendingOperations &&
            model.uiState.value.pendingRecordingCommand == null })
        assertFalse(peer.recording.get())
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.busy })
        assertFalse(model.uiState.value.recordingCommandUnconfirmed)
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertEquals(false, model.uiState.value.status?.recording)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun disconnectAfterSendWarnsAboutOldCameraWithoutSendingStopOrInheritingOwner() = runBlocking {
        connect(native = false)
        val gate = peer.gateNextRecording()
        model.toggleRecording()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        assertTrue(peer.recording.get())
        model.disconnect()
        assertTrue(model.uiState.value.recordingDisconnectWarning)
        gate.release.countDown()
        assertTrue(pumpUntil { !model.uiState.value.connected && !model.uiState.value.busy })
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertFalse(model.uiState.value.recordingCommandUnconfirmed)
        peer.reportedRecording.set(false)
        connect(native = false)
        advanceAndSettle()
        assertTrue(model.uiState.value.recordingDisconnectWarning)
        assertNull(model.uiState.value.pendingRecordingCommand)
        assertFalse(model.uiState.value.recordingStopAvailable)
        assertEquals(listOf("start"), peer.recordingActions.toList())
    }

    @Test fun nativeQueuedFalseAfterLostStartIsOnlyDisplayHintAndNeverResolvesDutyOrReviews() = runBlocking {
        connect(native = true)
        peer.dropNextRecordingResponse.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.START)
        peer.enqueueRecordingEvent("stop")
        advanceAndSettle()
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy })
        assertTrue(model.uiState.value.recordingStopAvailable)
        assertNull(model.uiState.value.recordingReceipt?.observation)
        assertEquals(dev.openeos.control.data.RecordingResponseEvidence.UNKNOWN, model.uiState.value.recordingReceipt?.response)
        assertFalse(model.uiState.value.recordingReceipt?.mayReviewStoppedVideo == true)
        assertEquals(listOf("start"), peer.recordingActions.toList())
        model.toggleRecording()
        assertTrue(pumpUntil { !model.uiState.value.busy && !model.uiState.value.recordingStopAvailable })
        assertNull(model.uiState.value.recordingReceipt?.observation)
        assertFalse(model.uiState.value.recordingReceipt?.mayReviewStoppedVideo == true)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
    }

    @Test fun newPhotoRetiresDeferredStopReviewButKeepsOriginalReceiptAndReadRecovery() = runBlocking {
        connect(native = false)
        startRecording()
        peer.failStatus.set(true)
        model.toggleRecording()
        awaitRecordingPending(RecordingCommand.STOP)
        assertFalse(model.uiState.value.recordingStopAvailable)
        assertNull(model.uiState.value.recordingReceipt?.observation)
        peer.failStatus.set(false)
        peer.publishNewJpeg.set(true)
        model.captureStill()
        assertTrue(pumpUntil(advanceTime = true) { !model.uiState.value.busy && model.uiState.value.captureReviewItem?.name == "NEW.JPG" })
        val reads = peer.listingReads.get()
        peer.publishAfterStopRead.set(0)
        model.refreshRecordingStatus()
        assertTrue(pumpUntil { !model.uiState.value.busy && model.uiState.value.recordingReceipt?.observation?.recording == false })
        advanceAndSettle()
        assertEquals("NEW.JPG", model.uiState.value.captureReviewItem?.name)
        assertEquals(reads, peer.listingReads.get())
        assertEquals(dev.openeos.control.data.RecordingResponseEvidence.RESPONSE_RECEIVED, model.uiState.value.recordingReceipt?.response)
        assertEquals(listOf("start", "stop"), peer.recordingActions.toList())
        assertEquals(1, peer.captureWrites.get())
    }

    private fun recordingJob(): Job {
        val field = CameraViewModel::class.java.getDeclaredField("cameraOperationJobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return requireNotNull((field.get(model) as Map<CameraOperation, Job>)[CameraOperation.RECORDING])
    }

    private fun overrideState(update: (CameraUiState) -> CameraUiState) {
        val field = CameraViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as MutableStateFlow<CameraUiState>
        state.value = update(state.value)
    }

    private suspend fun awaitRecordingPending(command: RecordingCommand) {
        assertTrue("Pending ${model.uiState.value.pendingRecordingCommand}; error=${model.uiState.value.error}",
            pumpUntil { !model.uiState.value.busy && (model.uiState.value.pendingRecordingCommand == command ||
                (command == RecordingCommand.STOP && model.uiState.value.recordingReceipt?.command == command)) })
    }

    private suspend fun connect(native: Boolean = false) {
        if (native) model.useDirectCameraPreset() else model.useDevSimulatorPreset()
        model.setBaseUrl(peer.baseUrl)
        model.setLiveViewAutoRefresh(false)
        model.connect()
        assertTrue("Connection: ${model.uiState.value.error}", pumpUntil(advanceTime = true) {
            model.uiState.value.connected && !model.uiState.value.busy &&
                model.uiState.value.captureReviewItem?.name == "OLD.JPG" && !model.uiState.value.captureReviewLoading
        })
    }

    private suspend fun startRecording() {
        val reads = peer.listingReads.get()
        model.toggleRecording()
        assertTrue(pumpUntil(advanceTime = true) { model.uiState.value.status?.recording == true && !model.uiState.value.busy })
        assertEquals("REC Start must not query media", reads, peer.listingReads.get())
    }

    private suspend fun awaitNotReady() {
        val notReady = pumpUntil(advanceTime = true) { model.uiState.value.captureReviewStatus == CaptureReviewStatus.NOT_READY }
        assertTrue("Review state: ${model.uiState.value.captureReviewStatus}; item=${model.uiState.value.captureReviewItem?.name}; reads=${peer.listingReads.get()}", notReady)
    }

    private suspend fun advanceAndSettle() {
        main.scheduler.advanceTimeBy(5_000)
        repeat(10) { main.scheduler.runCurrent(); delay(10) }
    }

    private suspend fun pumpUntil(timeoutMillis: Long = 4_000, advanceTime: Boolean = false, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < end) {
            main.scheduler.runCurrent()
            model.uiState.value.recordingReceipt?.takeIf { !model.uiState.value.busy && !it.requestCancelled &&
                it.response == dev.openeos.control.data.RecordingResponseEvidence.RESPONSE_RECEIVED }
                ?.let { peer.publishAcknowledgedRecordingEvent(it.command.name.lowercase()) }
            if (condition()) return true
            if (advanceTime) main.scheduler.advanceTimeBy(100)
            delay(10)
        }
        main.scheduler.runCurrent()
        return condition()
    }
}

private class RecordingReviewPeer {
    val server = MockWebServer()
    lateinit var baseUrl: String
    val recordingActions = CopyOnWriteArrayList<String>()
    val recordingRequests = CopyOnWriteArrayList<RecordedRequest>()
    val dropNextRecordingResponse = AtomicBoolean(false)
    private val recordingEvent = AtomicReference<String?>()
    private val pendingAcknowledgedRecordingEvent = AtomicReference<String?>()
    val writes = CopyOnWriteArrayList<String>()
    val recording = AtomicBoolean(false)
    val reportedRecording = AtomicReference<Boolean?>()
    val recordingJson = AtomicReference<String?>()
    val unknownRecording = AtomicBoolean(false)
    val rejectStop = AtomicBoolean(false)
    val rejectStart = AtomicBoolean(false)
    val rejectCapture = AtomicBoolean(false)
    val captureWrites = AtomicInteger()
    val historicalTimestamps = AtomicBoolean(false)
    val failStatus = AtomicBoolean(false)
    val failListing = AtomicBoolean(false)
    val listingReads = AtomicInteger()
    val stopReviewReads = AtomicInteger()
    val publishAfterStopRead = AtomicInteger(Int.MAX_VALUE)
    val publishNewJpeg = AtomicBoolean(false)
    val reverseOldItems = AtomicBoolean(false)
    val pages = CopyOnWriteArrayList<Int>()
    val eventReads = AtomicInteger()
    val nonemptyEvents = AtomicInteger()
    private val contentsPending = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    class Gate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
    private val gates = CopyOnWriteArrayList<Gate>()
    private val nextStatus = AtomicReference<Gate?>()
    private val nextStop = AtomicReference<Gate?>()
    private val nextRecording = AtomicReference<Gate?>()
    private val nextTemperature = AtomicReference<Gate?>()
    private val nextListing = AtomicReference<Gate?>()
    private val nextInfo = AtomicReference<Gate?>()
    private val nextEventDelete = AtomicReference<Gate?>()
    fun gateNextStatus() = Gate().also { gates += it; nextStatus.set(it) }
    fun gateNextStop() = Gate().also { gates += it; nextStop.set(it) }
    fun gateNextRecording() = Gate().also { gates += it; nextRecording.set(it) }
    fun gateNextTemperature() = Gate().also { gates += it; nextTemperature.set(it) }
    fun enqueueRecordingEvent(action: String) { recordingEvent.set(action) }
    fun publishAcknowledgedRecordingEvent(command: String) {
        val pending = pendingAcknowledgedRecordingEvent.get()
        if (pending == command && pendingAcknowledgedRecordingEvent.compareAndSet(pending, null)) recordingEvent.set(command)
    }
    fun gateNextListing() = Gate().also { gates += it; nextListing.set(it) }
    fun gateNextInfo() = Gate().also { gates += it; nextInfo.set(it) }
    fun gateNextEventDelete() = Gate().also { gates += it; nextEventDelete.set(it) }
    fun releaseGates() { gates.forEach { it.release.countDown() } }
    fun enqueueContentsEvent() { contentsPending.set(true) }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                if (request.method != "GET") writes += "${request.method} $path ${request.body.clone().readUtf8()}"
                if ((path.endsWith("/shutterbutton") || path == "/ccapi/capture/still") && request.method == "POST") {
                    captureWrites.incrementAndGet()
                    return if (rejectCapture.get()) MockResponse().setResponseCode(503) else json("{}")
                }
                if (request.method == "POST" && (path.endsWith("/recbutton") || path.startsWith("/ccapi/record/"))) {
                    val action = if (path.endsWith("/recbutton")) JSONObject(request.body.clone().readUtf8()).getString("action")
                        else path.substringAfterLast('/')
                    recordingRequests += request
                    recordingActions += action
                    if (action == "stop" && rejectStop.get()) return MockResponse().setResponseCode(503)
                    if (action == "start" && rejectStart.get()) return MockResponse().setResponseCode(503)
                    recording.set(action == "start")
                    if (action == "stop") {
                        stopped.set(true)
                        stopReviewReads.set(0)
                        hold(nextStop)
                    }
                    hold(nextRecording)
                    if (dropNextRecordingResponse.getAndSet(false)) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    // Native REC state now comes from an explicit post-admission event, never command intent.
                    if (path.endsWith("/recbutton")) pendingAcknowledgedRecordingEvent.set(action)
                    return json("{}")
                }
                return when {
                    path == "/ccapi" -> json("""{"ver110":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/devicestatus/temperature","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/control/recbutton","post":true},{"path":"/shooting/control/shutterbutton","post":true},{"path":"/contents","get":true},{"path":"/event/polling","get":true,"delete":true}]}""")
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Synthetic recording review","serialnumber":"TEST-REC-REVIEW"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/devicestatus/temperature") -> { hold(nextTemperature); json("""{"status":"normal"}""") }
                    path.endsWith("/shooting/settings") -> json("{}")
                    path == "/ccapi/info" -> json("""{"connected":true,"model":"Synthetic recording review","serial":"TEST-REC-REVIEW","api":"simulator"}""")
                    path == "/ccapi/status" -> {
                        val response = if (failStatus.get()) MockResponse().setResponseCode(503)
                            else json("""{"connected":true,"recording":${recordingJson.get() ?: if (unknownRecording.get()) "null" else reportedRecording.get() ?: recording.get()},"mode":"video","battery":{},"media":{},"exposure":{}}""")
                        hold(nextStatus)
                        response
                    }
                    path == "/ccapi/capabilities" -> json("""{"iso":["100"],"shutter":["1/125"],"aperture":["4.0"],"white_balance":["auto"]}""")
                    path.endsWith("/event/polling") && request.method == "DELETE" -> { hold(nextEventDelete); json("{}") }
                    path.endsWith("/event/polling") || path == "/ccapi/events" -> {
                        eventReads.incrementAndGet()
                        val changed = contentsPending.getAndSet(false)
                        if (changed) nonemptyEvents.incrementAndGet()
                        if (path == "/ccapi/events") json(JSONObject().put("sequence", nonemptyEvents.get())
                            .put("keys", JSONArray(if (changed) listOf("contents") else emptyList<String>())).toString())
                        else {
                            val action = recordingEvent.getAndSet(null)
                            json(if (action != null) """{"recbutton":{"action":"$action"}}""" else if (changed) """{"contents":{}}""" else "{}")
                        }
                    }
                    path == "/ccapi/media" -> {
                        countListing()
                        if (failListing.get()) return MockResponse().setResponseCode(503)
                        val names = visibleItems()
                        hold(nextListing)
                        json(JSONObject().put("items", JSONArray(names.map { name -> JSONObject()
                            .put("id", name).put("name", name).put("kind", if (name.endsWith("MP4")) "mp4" else "jpeg")
                            .apply { if (historicalTimestamps.get()) put("capture_time", when (name) {
                                "OLD.JPG" -> "2026-10-05T00:00:00Z"; "HISTORY.MP4" -> "2026-10-04T00:00:00Z"
                                "NEW.MP4" -> "2026-10-03T00:00:00Z"; else -> "2026-01-01T00:00:00Z"
                            }) } })).toString())
                    }
                    path.endsWith("/contents") && url.queryParameter("kind") == "number" -> {
                        countListing()
                        json("""{"pagenumber":1}""")
                    }
                    path.endsWith("/contents") && url.queryParameter("page") != null -> {
                        pages += requireNotNull(url.queryParameter("page")).toInt()
                        val names = visibleItems()
                        hold(nextListing)
                        if (failListing.get()) return MockResponse().setResponseCode(503)
                        json(JSONObject().put("path", JSONArray(names.map { "/ccapi/ver110/contents/$it" })).toString())
                    }
                    path.endsWith("/contents") -> json("{}")
                    url.queryParameter("kind") == "info" -> {
                        hold(nextInfo)
                        val date = if (!historicalTimestamps.get()) null else when (path.substringAfterLast('/')) {
                            "OLD.JPG" -> "2026-10-05T00:00:00Z"
                            "HISTORY.MP4" -> "2026-10-04T00:00:00Z"
                            "NEW.MP4" -> "2026-10-03T00:00:00Z"
                            else -> "2026-01-01T00:00:00Z"
                        }
                        json(JSONObject().apply { if (date != null) put("lastmodifieddate", date) }.toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }

    private fun countListing() {
        listingReads.incrementAndGet()
        if (stopped.get()) stopReviewReads.incrementAndGet()
    }
    private fun visibleItems(): List<String> {
        val old = listOf("OLD.JPG", "HISTORY.MP4").let { if (reverseOldItems.get()) it.reversed() else it }
        val new = buildList {
            if (stopped.get() && publishNewJpeg.get()) add("NEW.JPG")
            if (stopped.get() && stopReviewReads.get() >= publishAfterStopRead.get()) add("NEW.MP4")
        }
        return (new + old + (1..6).map { "OLDER$it.JPG" }).take(8)
    }
    private fun hold(next: AtomicReference<Gate?>) {
        next.getAndSet(null)?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(5, TimeUnit.SECONDS)) { "HTTP gate was not released" }
        }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
