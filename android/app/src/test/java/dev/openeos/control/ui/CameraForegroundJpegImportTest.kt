package dev.openeos.control.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production VM/repository/CCAPI HTTP, with synthetic sinks. This does not exercise MediaStore. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraForegroundJpegImportTest {
    private val main = StandardTestDispatcher()
    private val peer = ForegroundImportPeer()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory { CameraHttpTransport(http, CameraNetworkDiagnostics.Empty) },
    ))
    private val outputs = mutableListOf<ImportOutputProbe>()
    private lateinit var viewModel: CameraViewModel

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        withContext(Dispatchers.IO) { peer.start() }
        viewModel = CameraViewModel(repository)
        connect()
        peer.clearObservations()
    }

    @After fun tearDown() = runBlocking {
        peer.releaseGates()
        outputs.forEach { it.releaseCleanup.complete(Unit) }
        try {
            viewModel.disconnect()
            http.dispatcher.cancelAll()
            val scope = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
            viewModel.viewModelScope.cancel()
            assertTrue("ViewModel owner and every IO child must finish before resetting Main", pumpUntil { scope.isCompleted })
            // Public repository teardown joins its connection mutex, including detached onCleared cleanup.
            val backendCleanup = async(Dispatchers.IO) { repository.disconnect() }
            assertTrue("Backend teardown must join before resetting Main", pumpUntil { backendCleanup.isCompleted })
            backendCleanup.await()
            main.scheduler.runCurrent()
            assertTrue("Cancelled HTTP must also unwind before the fixture is closed", pumpUntil { http.dispatcher.runningCallsCount() == 0 })
        } finally {
            withContext(Dispatchers.IO) { peer.server.shutdown() }
            http.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }

    @Test fun connectionForegroundAndMediaRefreshNeverImplicitlyEnableImport() = runBlocking {
        val output = output()
        peer.items.set(listOf("old.JPG", "new.JPG"))
        viewModel.setAppForeground(false)
        viewModel.setAppForeground(true)
        viewModel.refresh()
        assertTrue(pumpUntil { !viewModel.uiState.value.busy })
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertTrue(peer.originalRequests.isEmpty())
        assertEquals(0, output.attempts.get())
        assertFalse(start(output, platformSupported = false))
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertTrue(peer.originalRequests.isEmpty())
    }

    @Test fun bothBaselineSetsCompleteBeforeAnyOldOrNewOriginalCanBeRead() = runBlocking {
        peer.items.set(listOf("old.JPG", "older.JPG"))
        val second = peer.gateListing(2)
        val output = output()
        assertTrue(start(output))
        assertTrue(pumpUntil { second.entered.count == 0L })
        assertEquals(ForegroundImportPhase.BASELINING, status.phase)
        assertTrue(peer.infoRequests.isEmpty())
        assertTrue(peer.originalRequests.isEmpty())
        second.release.countDown()
        awaitWatching()
        assertEquals(2, status.baselineCount)
        assertTrue(peer.infoRequests.isEmpty())
        assertTrue(peer.originalRequests.isEmpty())
        viewModel.stopForegroundJpegImport()
        awaitStopped(ForegroundImportStopReason.USER)
    }

    @Test fun equalBaselineCountsWithDifferentIdentitiesFailClosed() = runBlocking {
        peer.scriptListings(listOf("old.JPG"), listOf("replacement.JPG"))
        assertTrue(start(output()))
        awaitStopped(ForegroundImportStopReason.BASELINE_CHANGED)
        assertEquals(2, peer.listReads.get())
        assertTrue(peer.infoRequests.isEmpty())
        assertTrue(peer.originalRequests.isEmpty())
    }

    @Test fun nativeCcapiRawJpegPairDownloadsOnlyTheNewJpegOnceWithoutControlReplay() = runBlocking {
        viewModel.disconnect()
        assertTrue(pumpUntil { !viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
        connect(native = true)
        peer.clearObservations()
        val output = output()
        assertTrue(start(output))
        awaitWatching()
        peer.items.set(listOf("old.JPG", "new.CR3", "new.JPG"))
        assertTrue("New JPEG should finish through the real original-download callback", pumpUntil { status.completedCount == 1 })
        repeat(5) { pumpSteps(4) }
        assertEquals(listOf("new.JPG", "new.JPG"), peer.infoRequests.toList())
        assertEquals(listOf("${ForegroundImportPeer.CONTENTS}/new.JPG"), peer.originalRequests.toList())
        assertEquals(1, output.attempts.get())
        assertEquals(1, output.publishedBytes.size)
        assertArrayEquals(peer.originalBytes, output.publishedBytes.single())
        assertEquals(1, status.completedCount)
        assertTrue("Import is read-only at the camera", peer.controlRequests.isEmpty())
        viewModel.stopForegroundJpegImport()
        awaitStopped(ForegroundImportStopReason.USER)
    }

    @Test fun staleInventorySizeAndOneFreshPositiveReadCannotFabricateStability() = runBlocking {
        val output = output()
        assertTrue(start(output))
        awaitWatching()
        peer.infoSize.set(null)
        peer.items.set(listOf("old.JPG", "new.JPG"))
        assertTrue(pumpUntil { peer.infoRequests.size >= 3 })
        assertTrue(peer.originalRequests.isEmpty())
        assertEquals(ForegroundImportPhase.WAITING, status.phase)
        peer.infoSize.set(peer.originalBytes.size.toLong())
        val nextRead = peer.infoRequests.size + 1
        val firstPositive = peer.gateInfo(nextRead)
        val secondPositive = peer.gateInfo(nextRead + 1)
        assertTrue(pumpUntil { firstPositive.entered.count == 0L })
        assertTrue(peer.originalRequests.isEmpty())
        firstPositive.release.countDown()
        assertTrue(pumpUntil { secondPositive.entered.count == 0L })
        assertTrue("One returned positive metadata observation is not stability", peer.originalRequests.isEmpty())
        secondPositive.release.countDown()
        assertTrue(pumpUntil { status.completedCount == 1 })
        assertEquals(1, output.attempts.get())
        assertArrayEquals(peer.originalBytes, output.publishedBytes.single())
        assertTrue(peer.infoRequests.all { it == "new.JPG" })
    }

    @Test fun queueOverflowStopsBeforeAnyCandidateMetadataOrOriginalRead() = runBlocking {
        val output = output()
        assertTrue(start(output, limits = ForegroundImportLimits(queuedItems = 1)))
        awaitWatching()
        peer.items.set(listOf("old.JPG", "a.JPG", "b.JPG"))
        awaitStopped(ForegroundImportStopReason.QUEUE_LIMIT)
        assertEquals(1, status.knownCount)
        assertTrue(peer.infoRequests.isEmpty())
        assertTrue(peer.originalRequests.isEmpty())
        assertEquals(0, output.attempts.get())
    }

    @Test fun identityOverflowStopsAtomicallyEvenWhenOldIdentityDisappears() = runBlocking {
        assertTrue(start(output(), limits = ForegroundImportLimits(baselineItems = 1, knownItems = 2)))
        awaitWatching()
        peer.items.set(listOf("a.JPG", "b.CR3"))
        awaitStopped(ForegroundImportStopReason.IDENTITY_LIMIT)
        assertEquals(1, status.knownCount)
        assertTrue(peer.infoRequests.isEmpty())
        assertTrue(peer.originalRequests.isEmpty())
    }

    @Test fun failedOriginalIsSingleAttemptAndCannotReplayCameraControls() = runBlocking {
        val output = output()
        assertTrue(start(output))
        awaitWatching()
        peer.originalStatus.set(503)
        peer.items.set(listOf("old.JPG", "new.JPG"))
        awaitStopped(ForegroundImportStopReason.TRANSFER_FAILED)
        pumpSteps(20)
        assertEquals(listOf("/ccapi/media/new.JPG"), peer.originalRequests.toList())
        assertEquals(1, output.attempts.get())
        assertTrue(output.publishedBytes.isEmpty())
        assertEquals(0, status.completedCount)
        assertTrue(peer.controlRequests.isEmpty())
    }

    @Test fun stopBeforeOwnerBodyStartsRetiresOwnershipAndAllowsExplicitReenable() = runBlocking {
        val output = output()
        assertTrue(start(output))
        assertTrue(viewModel.uiState.value.foregroundImportOwnerActive)
        viewModel.stopForegroundJpegImport()
        viewModel.stopForegroundJpegImport()
        awaitStopped(ForegroundImportStopReason.USER)
        assertEquals(0, peer.listReads.get())
        assertTrue(peer.infoRequests.isEmpty())
        assertTrue(peer.originalRequests.isEmpty())
        assertFalse(viewModel.uiState.value.busy)
        assertFalse(viewModel.uiState.value.foregroundImportOwnerActive)
        assertTrue("A cancelled unscheduled owner must not retain the enable latch", start(output))
        awaitWatching()
    }

    @Test fun stopBeforeOperationBodyStartsClearsItsMediaAdmissionWithoutAnyHttp() = runBlocking {
        var stopped = false
        val observer = viewModel.viewModelScope.launch(UnconfinedTestDispatcher(main.scheduler)) {
            viewModel.uiState.collect { state ->
                if (!stopped && state.foregroundJpegImport.phase == ForegroundImportPhase.BASELINING &&
                    CameraOperation.MEDIA in state.pendingOperations) {
                    stopped = true
                    viewModel.stopForegroundJpegImport()
                }
            }
        }
        assertTrue(start(output()))
        awaitStopped(ForegroundImportStopReason.USER)
        observer.cancel()
        assertTrue(stopped)
        assertFalse("Cancelled pre-start MEDIA must not permanently block the session", viewModel.uiState.value.busy)
        assertEquals(0, peer.listReads.get())
        assertTrue(peer.originalRequests.isEmpty())
        assertTrue(start(output()))
        awaitWatching()
    }

    @Test fun idleStopDoesNotScanAgainAndDoesNotResumeWithoutAnotherEnable() = runBlocking {
        assertTrue(start(output()))
        awaitWatching()
        viewModel.stopForegroundJpegImport()
        awaitStopped(ForegroundImportStopReason.USER)
        val reads = peer.listReads.get()
        peer.items.set(listOf("old.JPG", "new.JPG"))
        pumpSteps(20)
        assertEquals(reads, peer.listReads.get())
        assertTrue(peer.originalRequests.isEmpty())
        assertTrue(start(output()))
        awaitWatching()
        assertEquals(2, status.baselineCount)
        assertTrue("A newly enabled session treats existing files as its baseline", peer.originalRequests.isEmpty())
    }

    @Test fun manualMetadataOwnsPriorityAndStopDuringThatWaitDoesNotCancelManualWork() = runBlocking {
        assertTrue(start(output()))
        awaitWatching()
        val old = requireNotNull(viewModel.uiState.value.captureReviewItem)
        val gate = peer.gateInfo(1)
        viewModel.loadMediaInfo(old)
        assertTrue(pumpUntil { gate.entered.count == 0L })
        val reads = peer.listReads.get()
        peer.items.set(listOf("old.JPG", "new.JPG"))
        pumpSteps(12)
        assertEquals("Automatic inventory must yield while manual MEDIA owns the camera", reads, peer.listReads.get())
        assertTrue(peer.originalRequests.isEmpty())
        viewModel.stopForegroundJpegImport()
        awaitStopped(ForegroundImportStopReason.USER)
        assertTrue("Manual metadata is still running after automatic Stop", viewModel.uiState.value.busy)
        gate.release.countDown()
        assertTrue(pumpUntil { !viewModel.uiState.value.busy })
        assertEquals(listOf("old.JPG"), peer.infoRequests.toList())
        assertEquals(reads, peer.listReads.get())
        assertTrue(peer.originalRequests.isEmpty())
    }

    @Test fun backgroundStopsAndReturningToForegroundCannotRestartTheOldOwner() = runBlocking {
        assertTrue(start(output()))
        awaitWatching()
        viewModel.setAppForeground(false)
        awaitStopped(ForegroundImportStopReason.BACKGROUND)
        assertFalse(start(output()))
        val reads = peer.listReads.get()
        peer.items.set(listOf("old.JPG", "new.JPG"))
        viewModel.setAppForeground(true)
        pumpSteps(20)
        assertEquals(reads, peer.listReads.get())
        assertTrue(peer.originalRequests.isEmpty())
        assertTrue(start(output()))
        awaitWatching()
        assertEquals(2, status.baselineCount)
    }

    @Test fun immediateBackgroundForegroundAndRepeatedEnableCannotCreateTwoOwners() = runBlocking {
        val output = output()
        assertTrue(start(output))
        assertFalse(start(output))
        viewModel.setAppForeground(false)
        viewModel.setAppForeground(true)
        awaitStopped(ForegroundImportStopReason.BACKGROUND)
        assertEquals(0, peer.listReads.get())
        assertEquals(0, output.attempts.get())
        assertTrue(start(output))
        assertFalse(start(output))
        awaitWatching()
        assertTrue(peer.originalRequests.isEmpty())
    }

    @Test fun publicationBeforeStopKeepsBytesReceiptAndFeedbackWithoutADuplicateSave() = runBlocking {
        val output = output(holdAfterDownload = true)
        startNewCandidate(output)
        assertTrue(pumpUntil { output.downloadReturned.isCompleted })
        assertEquals(1, output.publishedBytes.size)
        assertEquals(ForegroundImportPhase.SAVING, status.phase)
        viewModel.stopForegroundJpegImport()
        awaitStopped(ForegroundImportStopReason.USER)
        assertEquals(1, status.completedCount)
        assertArrayEquals(peer.originalBytes, output.publishedBytes.single())
        assertEquals(MediaSaveFeedback.Saved("Synthetic Gallery"), viewModel.uiState.value.mediaSaveFeedback["new.JPG"])
        assertEquals("new.JPG", viewModel.uiState.value.lastDownloadedMediaName)
        assertNull(viewModel.uiState.value.activeMediaDownloadName)
        assertNull(viewModel.uiState.value.mediaDownloadProgress)
        pumpSteps(20)
        assertEquals(1, output.attempts.get())
        assertEquals(1, peer.originalRequests.size)
    }

    @Test fun recordingSafetyStopReachesCameraBeforeDelayedOutputCleanupCompletes() = runBlocking {
        val output = output(publish = false, holdAfterDownload = true, holdCleanup = true)
        startNewCandidate(output)
        assertTrue(pumpUntil { output.downloadReturned.isCompleted })
        peer.recording.set(true)
        viewModel.refresh()
        assertTrue(pumpUntil { viewModel.uiState.value.status?.recording == true && CameraOperation.STATUS !in viewModel.uiState.value.pendingOperations })
        assertEquals(ForegroundImportPhase.SAVING, status.phase)
        viewModel.toggleRecording()
        assertTrue("Safety STOP must reach the camera while Gallery cleanup remains gated", pumpUntil {
            output.cleanupStarted.isCompleted && peer.controlRequests.contains("POST /ccapi/record/stop")
        })
        assertFalse(output.releaseCleanup.isCompleted)
        assertEquals(listOf("POST /ccapi/record/stop"), peer.controlRequests.toList())
        assertEquals(1, peer.originalRequests.size)
        output.releaseCleanup.complete(Unit)
        awaitStopped(ForegroundImportStopReason.USER)
        assertTrue(pumpUntil { viewModel.uiState.value.status?.recording == false })
    }

    @Test fun previouslyOwnedBulbWithStaleUiStatusStopsBeforeDelayedImportCleanup() = runBlocking {
        // Establish a real backend-owned shutter release before the VM receives its status.
        // No private state/reflection: setup traverses the same production repository and HTTP.
        assertTrue(repository.startBulbExposure().bulbExposureActive == true)
        assertEquals(listOf("POST /ccapi/bulb/start"), peer.controlRequests.toList())
        assertFalse(viewModel.uiState.value.bulbExposureActive)
        peer.clearObservations()
        val output = output(publish = false, holdAfterDownload = true, holdCleanup = true)
        startNewCandidate(output)
        assertTrue(pumpUntil { output.downloadReturned.isCompleted })
        viewModel.refresh()
        assertTrue(pumpUntil {
            viewModel.uiState.value.bulbExposureActive && CameraOperation.STATUS !in viewModel.uiState.value.pendingOperations
        })
        assertEquals(ForegroundImportPhase.SAVING, status.phase)
        val reads = peer.listReads.get()
        val metadataReads = peer.infoRequests.size
        viewModel.toggleBulbExposure()
        assertTrue("Bulb safety STOP must reach the camera while Gallery cleanup remains gated", pumpUntil {
            output.cleanupStarted.isCompleted && peer.controlRequests.contains("POST /ccapi/bulb/stop")
        })
        assertFalse(output.releaseCleanup.isCompleted)
        assertEquals(listOf("POST /ccapi/bulb/stop"), peer.controlRequests.toList())
        assertEquals(1, peer.originalRequests.size)
        peer.items.set(listOf("old.JPG", "new.JPG", "later.JPG"))
        pumpSteps(12)
        assertEquals(reads, peer.listReads.get())
        assertEquals(metadataReads, peer.infoRequests.size)
        assertEquals(ForegroundImportPhase.STOPPING, status.phase)
        output.releaseCleanup.complete(Unit)
        awaitStopped(ForegroundImportStopReason.USER)
        assertTrue(pumpUntil { !viewModel.uiState.value.bulbExposureActive && !viewModel.uiState.value.busy })
        pumpSteps(12)
        assertEquals(reads, peer.listReads.get())
        assertEquals(metadataReads, peer.infoRequests.size)
        assertEquals(1, peer.originalRequests.size)
        assertEquals(1, output.attempts.get())
        assertEquals(listOf("POST /ccapi/bulb/stop"), peer.controlRequests.toList())
    }

    @Test fun unconfirmedCleanupIsLatchedAndCannotEnableAnotherOutput() = runBlocking {
        val output = output(publish = false, holdAfterDownload = true, failCleanup = true)
        startNewCandidate(output)
        assertTrue(pumpUntil { output.downloadReturned.isCompleted })
        viewModel.stopForegroundJpegImport()
        awaitStopped(ForegroundImportStopReason.CLEANUP_UNCONFIRMED)
        assertTrue(viewModel.uiState.value.foregroundImportCleanupUnconfirmed)
        assertFalse(viewModel.uiState.value.foregroundImportOwnerActive)
        assertEquals(0, status.completedCount)
        assertFalse(start(output()))
        assertEquals(1, peer.originalRequests.size)
    }

    @Test fun reconnectWaitsForDelayedCleanupAndKeepsOldOwnerFailureVisible() = runBlocking {
        val output = output(publish = false, holdAfterDownload = true, holdCleanup = true, failCleanup = true)
        startNewCandidate(output)
        assertTrue(pumpUntil { output.downloadReturned.isCompleted })
        val connections = peer.connectReads.get()
        viewModel.connect()
        assertTrue(pumpUntil { output.cleanupStarted.isCompleted })
        pumpSteps(12)
        assertEquals("Backend replacement must wait for old output cleanup", connections, peer.connectReads.get())
        assertTrue("The old output owner survives the cleared session UI", viewModel.uiState.value.foregroundImportOwnerActive)
        assertFalse(start(output()))
        assertEquals(1, peer.originalRequests.size)
        output.releaseCleanup.complete(Unit)
        assertTrue(pumpUntil { peer.connectReads.get() > connections && viewModel.uiState.value.connected && !viewModel.uiState.value.busy && !viewModel.uiState.value.captureReviewLoading })
        assertTrue("Cleanup failure belongs to the app, even after the old connection generation ends", viewModel.uiState.value.foregroundImportCleanupUnconfirmed)
        assertFalse("Acknowledgement is available only after the old owner retires", viewModel.uiState.value.foregroundImportOwnerActive)
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertTrue(viewModel.uiState.value.mediaSaveFeedback.isEmpty())
        assertNull(viewModel.uiState.value.lastDownloadedMediaName)
        assertFalse(start(output()))
    }

    @Test fun disconnectAndNewSessionIgnoreOldPublishedOwnerCallbacksAfterCleanupReturns() = runBlocking {
        val output = output(holdAfterDownload = true, holdCleanup = true)
        startNewCandidate(output)
        assertTrue(pumpUntil { output.downloadReturned.isCompleted })
        val connections = peer.connectReads.get()
        viewModel.disconnect()
        viewModel.connect()
        assertTrue(pumpUntil { output.cleanupStarted.isCompleted })
        assertEquals(connections, peer.connectReads.get())
        assertNull(viewModel.uiState.value.lastDownloadedMediaName)
        output.releaseCleanup.complete(Unit)
        assertTrue(pumpUntil { peer.connectReads.get() > connections && viewModel.uiState.value.connected && !viewModel.uiState.value.busy && !viewModel.uiState.value.captureReviewLoading })
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertEquals(0, status.completedCount)
        assertNull(viewModel.uiState.value.lastDownloadedMediaName)
        assertTrue(viewModel.uiState.value.mediaSaveFeedback.isEmpty())
        assertArrayEquals(peer.originalBytes, output.publishedBytes.single())
        assertEquals(1, peer.originalRequests.size)
        assertTrue(start(output()))
        awaitWatching()
        assertEquals(2, status.baselineCount)
        assertEquals(1, peer.originalRequests.size)
    }

    @Test fun baselineReadTimeoutCancelsTheHttpOwnerWithoutStartingAnyOriginal() = runBlocking {
        val gate = peer.gateListing(1)
        assertTrue(start(output()))
        assertTrue(pumpUntil { gate.entered.count == 0L })
        main.scheduler.advanceTimeBy(30_001)
        main.scheduler.runCurrent()
        gate.release.countDown()
        awaitStopped(ForegroundImportStopReason.READ_FAILED)
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(1, peer.listReads.get())
        assertTrue(peer.infoRequests.isEmpty())
        assertTrue(peer.originalRequests.isEmpty())
    }

    @Test fun metadataTimeoutRetiresItsOperationWithoutDownloadingOrRetrying() = runBlocking {
        assertTrue(start(output()))
        awaitWatching()
        val gate = peer.gateInfo(1)
        peer.items.set(listOf("old.JPG", "new.JPG"))
        assertTrue(pumpUntil { gate.entered.count == 0L })
        main.scheduler.advanceTimeBy(30_001)
        main.scheduler.runCurrent()
        gate.release.countDown()
        awaitStopped(ForegroundImportStopReason.READ_FAILED)
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(listOf("new.JPG"), peer.infoRequests.toList())
        assertTrue(peer.originalRequests.isEmpty())
        val reads = peer.listReads.get()
        pumpSteps(20)
        assertEquals(reads, peer.listReads.get())
    }

    @Test fun clearingViewModelWaitsForOutputCleanupAndNeverStartsAnotherRead() = runBlocking {
        val output = output(publish = false, holdAfterDownload = true, holdCleanup = true)
        startNewCandidate(output)
        assertTrue(pumpUntil { output.downloadReturned.isCompleted })
        val scope = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
        val store = ViewModelStore().apply { put("synthetic-import", viewModel) }
        store.clear()
        assertTrue(pumpUntil { output.cleanupStarted.isCompleted })
        assertFalse("ViewModel completion cannot outrun its owned output cleanup", scope.isCompleted)
        val reads = peer.listReads.get()
        output.releaseCleanup.complete(Unit)
        assertTrue(pumpUntil { scope.isCompleted })
        assertEquals(reads, peer.listReads.get())
        assertEquals(1, peer.originalRequests.size)
        assertTrue(output.publishedBytes.isEmpty())
    }

    private val status get() = viewModel.uiState.value.foregroundJpegImport
    private fun start(output: ImportOutputProbe, platformSupported: Boolean = true, limits: ForegroundImportLimits = ForegroundImportLimits()) =
        viewModel.startForegroundJpegImport(output, platformSupported = platformSupported, limits = limits,
            elapsedMillis = { main.scheduler.currentTime }, pollMillis = 100)

    private fun output(publish: Boolean = true, holdAfterDownload: Boolean = false, holdCleanup: Boolean = false, failCleanup: Boolean = false) =
        ImportOutputProbe(publish, holdAfterDownload, holdCleanup, failCleanup).also(outputs::add)

    private suspend fun startNewCandidate(output: ImportOutputProbe) {
        assertTrue(start(output))
        awaitWatching()
        peer.items.set(listOf("old.JPG", "new.JPG"))
    }

    private suspend fun awaitWatching() {
        assertTrue("Import must arm after two complete snapshots: ${viewModel.uiState.value.error}", pumpUntil {
            status.phase == ForegroundImportPhase.WATCHING && !viewModel.uiState.value.busy
        })
    }

    private suspend fun awaitStopped(reason: ForegroundImportStopReason) {
        assertTrue("Import owner must finish: expected $reason, actual $status", pumpUntil { status.phase == ForegroundImportPhase.STOPPED })
        assertEquals(reason, status.stopReason)
        assertNull(status.activeName)
        assertEquals(0, status.pendingCount)
    }

    private suspend fun connect(native: Boolean = false) {
        if (native) viewModel.useDirectCameraPreset() else viewModel.useDevSimulatorPreset()
        viewModel.setBaseUrl(peer.baseUrl)
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.connect()
        assertTrue("Synthetic CCAPI connection must settle: ${viewModel.uiState.value.error}", pumpUntil {
            val state = viewModel.uiState.value
            state.connected && !state.busy && state.captureReviewItem != null && !state.captureReviewLoading
        })
    }

    private suspend fun pumpSteps(count: Int) {
        repeat(count) { main.scheduler.runCurrent(); main.scheduler.advanceTimeBy(100); delay(2) }
        main.scheduler.runCurrent()
    }

    private suspend fun pumpUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            if (condition()) return true
            main.scheduler.advanceTimeBy(25)
            delay(2)
        }
        main.scheduler.runCurrent()
        return condition()
    }
}

/** Counts real downloaded bytes and exposes publication/cleanup return races without OS APIs. */
private class ImportOutputProbe(
    private val publish: Boolean,
    private val holdAfterDownload: Boolean,
    private val holdCleanup: Boolean,
    private val failCleanup: Boolean,
) : ForegroundJpegImportOutput {
    val attempts = AtomicInteger()
    val publishedBytes = CopyOnWriteArrayList<ByteArray>()
    val downloadReturned = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()

    override suspend fun save(item: CameraMediaItem, receipt: ForegroundImportReceipt, download: suspend (OutputStream) -> CameraMediaDownloadResult) {
        attempts.incrementAndGet()
        val destination = ByteArrayOutputStream()
        try {
            val result = download(destination)
            assertEquals(item.id, result.item.id)
            assertEquals(destination.size().toLong(), result.bytesTransferred)
            if (publish) {
                publishedBytes += destination.toByteArray()
                receipt.markPublished()
            }
            downloadReturned.complete(Unit)
            if (holdAfterDownload) awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                if (holdCleanup) releaseCleanup.await()
                if (!receipt.published && failCleanup) receipt.markCleanupUnconfirmed()
                destination.close()
            }
        }
    }
}

/** Obvious synthetic names only. Native and simulator routes both execute the production client. */
private class ForegroundImportPeer {
    val server = MockWebServer()
    lateinit var baseUrl: String
    val originalBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
    val items = AtomicReference(listOf("old.JPG"))
    val infoSize = AtomicReference<Long?>(originalBytes.size.toLong())
    val originalStatus = AtomicInteger(200)
    val recording = AtomicBoolean(false)
    val bulbExposureActive = AtomicBoolean(false)
    val connectReads = AtomicInteger()
    val listReads = AtomicInteger()
    val infoRequests = CopyOnWriteArrayList<String>()
    val originalRequests = CopyOnWriteArrayList<String>()
    val controlRequests = CopyOnWriteArrayList<String>()
    class Gate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
    private val listingGates = ConcurrentHashMap<Int, Gate>()
    private val infoGates = ConcurrentHashMap<Int, Gate>()
    private val gates = CopyOnWriteArrayList<Gate>()
    private var listingScript = ArrayDeque<List<String>>()

    fun gateListing(read: Int) = Gate().also { gates += it; listingGates[read] = it }
    fun gateInfo(read: Int) = Gate().also { gates += it; infoGates[read] = it }
    fun releaseGates() = gates.forEach { it.release.countDown() }
    fun scriptListings(vararg snapshots: List<String>) { synchronized(this) { listingScript = ArrayDeque(snapshots.toList()) } }
    fun clearObservations() { listReads.set(0); infoRequests.clear(); originalRequests.clear(); controlRequests.clear() }

    private fun listedItems(): List<String> {
        val read = listReads.incrementAndGet()
        val captured = synchronized(this) {
            if (listingScript.isEmpty()) items.get() else if (listingScript.size > 1) listingScript.removeFirst() else listingScript.first()
        }
        listingGates.remove(read)?.await()
        return captured
    }

    private fun Gate.await() {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Synthetic CCAPI response gate was not released" }
    }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                if (request.method != "GET") controlRequests += "${request.method} $path"
                return when {
                    path == "/ccapi" -> json("""{"ver100":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/control/shutterbutton","post":true},{"path":"/contents","get":true}]}""")
                    path.endsWith("/deviceinformation") -> { connectReads.incrementAndGet(); json("""{"productname":"Synthetic foreground import","serialnumber":"TEST-FOREGROUND-IMPORT"}""") }
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    path == CONTENTS && url.queryParameter("kind") == "number" -> json("""{"pagenumber":1}""")
                    path == CONTENTS && url.queryParameter("page") != null -> json(JSONObject().put("path", JSONArray(listedItems().map { "$CONTENTS/$it" })).toString())
                    path == "/ccapi/info" -> { connectReads.incrementAndGet(); json("""{"connected":true,"model":"Synthetic foreground import","serial":"TEST-FOREGROUND-IMPORT","api":"simulator"}""") }
                    path == "/ccapi/status" -> json("""{"connected":true,"recording":${recording.get()},"bulb_exposure_active":${bulbExposureActive.get()},"mode":"photo","battery":{},"media":{},"exposure":{}}""")
                    path == "/ccapi/record/stop" && request.method == "POST" -> { recording.set(false); json("{}") }
                    path == "/ccapi/bulb/start" && request.method == "POST" -> { bulbExposureActive.set(true); json("{}") }
                    path == "/ccapi/bulb/stop" && request.method == "POST" -> { bulbExposureActive.set(false); json("{}") }
                    path == "/ccapi/capabilities" -> json("""{"iso":["100"],"shutter":["1/125"],"aperture":["4.0"],"white_balance":["auto"]}""")
                    path == "/ccapi/events" -> json("""{"sequence":0,"keys":[]}""")
                    path == "/ccapi/media" -> json(JSONObject().put("items", JSONArray(listedItems().map(::item))).toString())
                    (path.startsWith("/ccapi/media/") || path.startsWith("$CONTENTS/")) && url.queryParameter("kind") == "info" -> {
                        val name = url.pathSegments.last()
                        infoRequests += name
                        val size = infoSize.get()
                        infoGates.remove(infoRequests.size)?.await()
                        json(JSONObject().apply { if (size != null) put("filesize", size) }.toString())
                    }
                    (path.startsWith("/ccapi/media/") || path.startsWith("$CONTENTS/")) && url.query == null -> {
                        originalRequests += path
                        MockResponse().setResponseCode(originalStatus.get()).setHeader("Content-Type", "image/jpeg")
                            .setBody(Buffer().write(originalBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }

    private fun item(name: String) = JSONObject().put("id", name).put("name", name)
        .put("kind", if (name.endsWith(".CR3")) "raw" else "image")
        .put("size_bytes", originalBytes.size).put("content_type", if (name.endsWith(".CR3")) "application/octet-stream" else "image/jpeg")
        .put("capture_time", "2026-09-01T00:00:01Z")
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    companion object { const val CONTENTS = "/ccapi/ver100/contents" }
}
