package dev.openeos.control.ui

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.handoff.SavedJpegReceiverActivity
import dev.openeos.control.handoff.SavedJpegReceiptProvider
import dev.openeos.control.importing.CameraImportAndroidIntentV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real MediaStore publication -> saved-list UI/VM -> production FileProvider -> another APK UID
 * -> real ActivityResult -> strict receipt validation and owned-session cleanup. The receiver is
 * a framework-only wire fixture, not evidence of Serein catalog or physical-camera compatibility.
 * Gallery reads/deletes target exact owned rows. Failed VM saves are located only by their
 * exact expected filename within this instance's random fixture output directory.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SavedJpegReceiverInstrumentedTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 1) val cleanup = TestRule { statement, _ ->
        object : Statement() {
            override fun evaluate() {
                var primaryFailure: Throwable? = null
                try {
                    statement.evaluate()
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    throw failure
                } finally {
                    try {
                        tearDown()
                    } catch (failure: Throwable) {
                        // Keep the failing assertion visible in JUnit XML; cleanup still fails
                        // an otherwise successful case and remains attached to a primary failure.
                        if (primaryFailure == null) throw failure
                        primaryFailure.addSuppressed(failure)
                    }
                }
            }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testPackage get() = instrumentation.context.packageName
    private val context get() = compose.activity.applicationContext
    private val resolver get() = context.contentResolver
    private val camera = CameraSessionTestSimulator("saved-jpeg-${UUID.randomUUID()}")
    private val cameraInfoReads = AtomicInteger()
    private val freshInfoReads = AtomicInteger()
    private val inventory = AtomicReference(camera.itemIds)
    private val replacementCameras = mutableListOf<CameraSessionTestSimulator>()
    private val store = DeliveredJpegStore()
    private lateinit var viewModels: ViewModelStore
    private val displayedModel = mutableStateOf<CameraViewModel?>(null)
    private val appContent: @Composable () -> Unit = { displayedModel.value?.let { OpenEosControlApp(it) } }
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val originals = mutableListOf<OwnedOriginal>()
    private val createdGalleryUris = linkedSetOf<Uri>()
    private val pendingVmOutputNames = linkedSetOf<String>()
    private lateinit var model: CameraViewModel
    private lateinit var history: DownloadHistoryStore
    private lateinit var historyDirectory: File
    private lateinit var initializationExpiryProbe: File
    private lateinit var unrelatedSession: File
    private lateinit var unrelatedSentinel: File
    private var serverStarted = false
    private var retainedJobsBeforeConnection = emptySet<Job>()
    private var receiverControl: IBinder? = null

    @Before fun setUp() {
        val testApplication = instrumentation.context.applicationInfo
        val archive = requireNotNull(instrumentation.context.packageManager.getPackageArchiveInfo(
            testApplication.sourceDir, PackageManager.GET_PROVIDERS))
        val receiptProvider = requireNotNull(archive.providers)
            .single { it.name == SavedJpegReceiptProvider::class.java.name }
        assertEquals(testPackage, receiptProvider.packageName)
        assertEquals("$testPackage.saved_jpeg_receipts", receiptProvider.authority)
        assertFalse("A public receipt provider would hide missing-grant failures", receiptProvider.exported)
        assertTrue(receiptProvider.grantUriPermissions)
        assertNotEquals(Process.myUid(), testApplication.uid)
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            when {
                request.method == "GET" && url.encodedPath == "/ccapi/info" -> {
                    cameraInfoReads.incrementAndGet()
                    null
                }
                request.method == "GET" && url.encodedPath == "/ccapi/media" ->
                    camera.json(JSONObject().put("items", JSONArray(inventory.get().map(::mediaJson))).toString())
                request.method == "GET" && url.encodedPath.startsWith("/ccapi/media/") &&
                    url.queryParameter("kind") == "info" -> {
                    freshInfoReads.incrementAndGet()
                    camera.json(mediaJson(url.pathSegments.last()).put("filesize", camera.imageBytes.size).toString())
                }
                else -> null
            }
        }
        camera.start() // Keep MockWebServer URL/hostname lookup off the Android main thread.
        serverStarted = true
        val identity = control(SavedJpegReceiverActivity.CONFIGURE, SavedJpegReceiverActivity.VALID)
        assertEquals(Process.myUid(), identity.getInt("caller_uid"))
        assertEquals(testApplication.uid, identity.getInt("receiver_uid"))
        assertNotEquals("The receiver must really execute as the test APK UID", Process.myUid(), identity.getInt("receiver_uid"))
        historyDirectory = File(context.cacheDir, "saved-jpeg-history-${UUID.randomUUID()}")
        check(historyDirectory.mkdirs())
        history = DownloadHistoryStore(historyDirectory, historyScope)
        // Observe the real asynchronous startup expiry pass before creating the unrelated
        // sentinel. Startup is allowed to purge old cache; the later local handoff is not.
        initializationExpiryProbe = File(context.cacheDir, "camera-import/session-${UUID.randomUUID()}")
        check(initializationExpiryProbe.mkdirs())
        File(initializationExpiryProbe, "synthetic-startup-expiry-probe.bin").writeBytes(SENTINEL)
        check(initializationExpiryProbe.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
        model = CameraViewModel(downloadHistoryFactory = { history }, deliveredJpegStore = store)
        compose.runOnIdle {
            viewModels = compose.activity.viewModelStore
            viewModels.put("saved-jpeg-receiver", model)
            assertEquals(CameraImportAndroidIntentV1.OPEN_NEGATIVE_PACKAGE, model.cameraImportTargetPackage)
            model.cameraImportTargetPackage = testPackage
        }
        displayedModel.value = model
        compose.setContent(appContent)
        compose.waitUntil(TIMEOUT) { !model.downloadHistoryState.value.loading && !initializationExpiryProbe.exists() }
        // cleanupExpiredSessions snapshots its candidates before deleting the expiry probe;
        // this newly created session cannot be part of that startup snapshot.
        unrelatedSession = File(context.cacheDir, "camera-import/session-${UUID.randomUUID()}")
        check(unrelatedSession.mkdirs())
        unrelatedSentinel = File(unrelatedSession, "unrelated-synthetic-sentinel.bin").apply { writeBytes(SENTINEL) }
        check(unrelatedSession.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
        compose.runOnIdle {
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
        }
        assertFalse(model.uiState.value.connected)
        assertTrue(model.savedJpegState.value.rows.isEmpty())
        assertTrue(store.state.value.entries.isEmpty())
        assertEquals(0, camera.server.requestCount)
    }

    private fun tearDown() {
        camera.releaseGates()
        try {
            // Release cross-process gates before waiting for the owning ViewModel to retire.
            if (receiverControl != null) {
                control(SavedJpegReceiverActivity.RELEASE_RECEIPT)
                control(SavedJpegReceiverActivity.FINISH_HELD)
            }
            if (::model.isInitialized) {
                val job = requireNotNull(model.viewModelScope.coroutineContext[Job])
                val children = compose.runOnIdle { job.children.toList() }
                compose.runOnIdle { displayedModel.value = null; viewModels.clear() }
                compose.waitUntil(TIMEOUT) { job.isCompleted && children.all(Job::isCompleted) }
            }
            if (::history.isInitialized) runBlocking { withTimeout(TIMEOUT) { history.awaitIdle() } }
        } finally {
            try {
                runBlocking {
                    historyScope.cancel()
                    withTimeout(TIMEOUT) { historyScope.coroutineContext[Job]?.join() }
                }
            } finally {
                try {
                    if (serverStarted) camera.server.shutdown()
                    replacementCameras.forEach { it.releaseGates(); it.server.shutdown() }
                } finally {
                    // Only the exact URIs returned by this instance's successful publication.
                    collectExactPendingFixtureOutputs()
                    createdGalleryUris.forEach { uri -> assertEquals(1, resolver.delete(uri, null, null)) }
                    if (::initializationExpiryProbe.isInitialized) initializationExpiryProbe.deleteRecursively()
                    if (::unrelatedSession.isInitialized) unrelatedSession.deleteRecursively()
                    if (::historyDirectory.isInitialized) historyDirectory.deleteRecursively()
                    if (receiverControl != null) control(SavedJpegReceiverActivity.CLEAN)
                }
            }
        }
    }

    @Test fun importedAndDuplicateReceiptsCrossUidAndPreserveGalleryOriginals() {
        val report = sendThroughSavedList(SavedJpegReceiverActivity.VALID)
        val outcome = requireNotNull(model.savedJpegState.value.outcome)
        assertNull(outcome.issue)
        assertEquals(CameraImportReceiptSummary(imported = 1, duplicates = 1, failed = 0, cancelled = 0), outcome.summary)
        assertReceiptReadByTarget(report)
        compose.onNodeWithTag("saved-jpegs-list").performScrollToNode(hasTestTag("saved-jpegs-result"))
        compose.onNodeWithTag("saved-jpegs-result").assertIsDisplayed()
        assertNoCameraReads()
    }

    @Test fun receiverCancellationNeverAcknowledgesImportsAndCleansOnlyOwnedCache() {
        assertRejected(SavedJpegReceiverActivity.CANCEL, CameraImportHandoffIssue.CANCELLED, receiptRead = false)
    }

    @Test fun okWithoutReceiptCannotAcknowledgeAnImport() {
        assertRejected(SavedJpegReceiverActivity.MISSING_RECEIPT, CameraImportHandoffIssue.RECEIPT_MISSING, receiptRead = false)
    }

    @Test fun receiptFromAnotherSessionIsRejected() {
        assertRejected(SavedJpegReceiverActivity.WRONG_SESSION, CameraImportHandoffIssue.RECEIPT_INVALID)
    }

    @Test fun receiptWithDifferentOriginalChecksumIsRejected() {
        assertRejected(SavedJpegReceiverActivity.WRONG_HASH, CameraImportHandoffIssue.RECEIPT_INVALID)
    }

    @Test fun receiptOmittingASelectedOriginalIsRejected() {
        assertRejected(SavedJpegReceiverActivity.INCOMPLETE_COVERAGE, CameraImportHandoffIssue.RECEIPT_INVALID)
    }

    @Test fun receiptWithoutTemporaryReadGrantCannotBeReadByTarget() {
        assertRejected(SavedJpegReceiverActivity.MISSING_RECEIPT_GRANT, CameraImportHandoffIssue.RECEIPT_INVALID,
            receiptRead = false)
    }

    @Test fun automaticJpegPublicationStopsDisconnectsAndSendsWithoutAnotherCameraRead() {
        assertTrue(store.state.value.entries.isEmpty())
        connectAndOpenGallery()
        assertTrue(store.state.value.entries.isEmpty())
        assertTrue(camera.originalReads.isEmpty())
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(IMPORT_TIMEOUT) {
            model.uiState.value.foregroundJpegImport.phase == ForegroundImportPhase.WATCHING && !model.uiState.value.busy
        }
        assertEquals(camera.itemIds.size, model.uiState.value.foregroundJpegImport.baselineCount)
        assertTrue(camera.originalReads.isEmpty())
        val newId = "${camera.label}-new"
        pendingVmOutputNames += mediaJson(newId).getString("name")
        inventory.set(camera.itemIds + newId)
        compose.waitUntil(IMPORT_TIMEOUT) {
            val state = model.uiState.value
            state.foregroundJpegImport.completedCount == 1 &&
                state.foregroundJpegImport.phase == ForegroundImportPhase.WATCHING &&
                !state.busy && state.foregroundJpegImport.activeName == null
        }
        captureVmPublishedOriginal(newId)
        assertEquals(listOf("/ccapi/media/$newId"), camera.originalReads.toList())
        assertTrue("Automatic import must use actual fresh metadata reads", freshInfoReads.get() >= 2)
        compose.onNodeWithTag("foreground-import-stop").assertIsEnabled().performClick()
        compose.waitUntil(IMPORT_TIMEOUT) {
            val state = model.uiState.value
            state.foregroundJpegImport.phase == ForegroundImportPhase.STOPPED &&
                !state.foregroundImportOwnerActive && !state.busy
        }
        assertEquals(ForegroundImportStopReason.USER, model.uiState.value.foregroundJpegImport.stopReason)
        assertEquals(0, model.uiState.value.foregroundJpegImport.pendingCount)
        disconnectAndAwaitSessionJobs()
        val before = cameraReadSnapshot()
        val report = sendThroughSavedList(SavedJpegReceiverActivity.VALID)
        assertEquals(CameraImportReceiptSummary(1, 0, 0, 0), model.savedJpegState.value.outcome?.summary)
        assertReceiptReadByTarget(report)
        assertEquals(before, cameraReadSnapshot())
        assertEquals(listOf("/ccapi/media/$newId"), camera.originalReads.toList())
        assertSingleGalleryHistoryReceipt()
    }

    @Test fun manualVmGallerySaveRegistersItsPublicationAndReusesOriginalAfterDisconnect() {
        assertTrue(store.state.value.entries.isEmpty())
        connectAndOpenGallery()
        val item = model.uiState.value.mediaItems.first()
        pendingVmOutputNames += item.name
        compose.runOnIdle { model.downloadMediaBatch(context, listOf(item)) }
        compose.waitUntil(IMPORT_TIMEOUT) {
            !model.uiState.value.isBusy(CameraOperation.MEDIA) &&
                model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saved
        }
        captureVmPublishedOriginal(item.id)
        assertEquals(ForegroundImportPhase.OFF, model.uiState.value.foregroundJpegImport.phase)
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())
        disconnectAndAwaitSessionJobs()
        val before = cameraReadSnapshot()
        val report = sendThroughSavedList(SavedJpegReceiverActivity.VALID)
        assertEquals(CameraImportReceiptSummary(1, 0, 0, 0), model.savedJpegState.value.outcome?.summary)
        assertReceiptReadByTarget(report)
        assertEquals(before, cameraReadSnapshot())
        assertEquals(1, camera.originalReads.size)
        assertSingleGalleryHistoryReceipt()
    }

    @Test fun realActivityRecreationRetainsTheLeaseAndDeliversExactlyOneExternalResult() {
        val savedStateKey = "saved-jpeg-recreation-probe"
        val savedStateToken = UUID.randomUUID().toString()
        compose.activityRule.scenario.onActivity { activity ->
            activity.savedStateRegistry.registerSavedStateProvider(savedStateKey) {
                Bundle().apply { putString("token", savedStateToken) }
            }
        }
        publishDefaultOriginals()
        beginSendThroughSavedList(SavedJpegReceiverActivity.HOLD_RESULT)
        compose.waitUntil(TIMEOUT) {
            model.cameraImportHandoffState.value.active?.phase == CameraImportHandoffPhase.AWAITING_RESULT
        }
        val heldReport = awaitReceiverReport { it.optBoolean("result_held") }
        assertReceiverValidatedInputs(heldReport)
        val lease = requireNotNull(model.cameraImportHandoffState.value.active)
        val directory = File(context.cacheDir, "camera-import/${lease.reservation.sessionId}")
        assertTrue(directory.isDirectory)
        assertEquals(1, heldReport.getInt("receiver_launches"))
        assertEquals(0, heldReport.getInt("receiver_results"))
        // Recreation legitimately runs startup expiry again; keep the unrelated sentinel fresh.
        check(unrelatedSession.setLastModified(System.currentTimeMillis()))
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        lateinit var previous: ComponentActivity
        val previousStopped = CountDownLatch(1)
        val destroyedForRecreation = AtomicBoolean()
        val recreatedHost = AtomicReference<ComponentActivity>()
        val recreatedStopped = CountDownLatch(1)
        val resumedAfterRelease = CountDownLatch(1)
        val releasingReceiver = AtomicBoolean()
        val resumedAfterSettlingWhileHeld = AtomicBoolean()
        val observer = ActivityLifecycleCallback { activity, stage ->
            if (activity === previous) {
                if (stage == Stage.STOPPED) previousStopped.countDown()
                if (stage == Stage.DESTROYED) destroyedForRecreation.set(activity.isChangingConfigurations)
            } else if (activity is ComponentActivity && activity.componentName == previous.componentName) {
                if (stage == Stage.CREATED) recreatedHost.compareAndSet(null, activity)
                if (activity === recreatedHost.get()) {
                    if (stage == Stage.STOPPED) recreatedStopped.countDown()
                    if (stage == Stage.RESUMED) {
                        // Relaunch may traverse RESUMED before returning to the original STOPPED
                        // state. Only a later resume violates the settled background interval;
                        // the completion latch must observe a fresh event after receiver release.
                        if (releasingReceiver.get()) resumedAfterRelease.countDown()
                        else if (recreatedStopped.count == 0L) resumedAfterSettlingWhileHeld.set(true)
                    }
                }
            }
        }
        compose.activityRule.scenario.onActivity { activity ->
            previous = activity
            lifecycle.addLifecycleCallback(observer)
            if (lifecycle.getLifecycleStageOf(activity) == Stage.STOPPED) previousStopped.countDown()
        }
        try {
            assertTrue("The real receiver must leave its caller stopped",
                previousStopped.await(TIMEOUT, TimeUnit.MILLISECONDS))
            compose.activityRule.scenario.onActivity { activity ->
                assertSame(previous, activity)
                assertEquals(Stage.STOPPED, lifecycle.getLifecycleStageOf(activity))
                // ActivityScenario.recreate first demands RESUMED. Android's actual recreate
                // returns to STOPPED on supported APIs while the external receiver holds its result.
                activity.recreate()
            }
            assertTrue("Android must recreate the caller and leave it stopped behind the receiver",
                recreatedStopped.await(TIMEOUT, TimeUnit.MILLISECONDS))
            assertTrue("The old host must really be destroyed for recreation", destroyedForRecreation.get())
            compose.activityRule.scenario.onActivity { recreated ->
                assertSame(recreatedHost.get(), recreated)
                assertNotEquals(System.identityHashCode(previous), System.identityHashCode(recreated))
                assertEquals(Stage.STOPPED, lifecycle.getLifecycleStageOf(recreated))
                assertEquals(Lifecycle.State.CREATED, recreated.lifecycle.currentState)
                assertEquals(savedStateToken,
                    recreated.savedStateRegistry.consumeRestoredStateForKey(savedStateKey)?.getString("token"))
                assertSame(viewModels, recreated.viewModelStore)
                assertSame(model, recreated.viewModelStore["saved-jpeg-receiver"])
                // Reattach the production composition to the actual recreated host. Its saved
                // ActivityResultRegistry remains intact; wait for Compose only after receiver completion.
                recreated.setContent(content = appContent)
                assertEquals(Stage.STOPPED, lifecycle.getLifecycleStageOf(recreated))
                assertEquals(Lifecycle.State.CREATED, recreated.lifecycle.currentState)
            }
            assertFalse("After settling, the caller must stay stopped until receiver release",
                resumedAfterSettlingWhileHeld.get())
            assertTrue(model.viewModelScope.coroutineContext[Job]!!.isActive)
            assertEquals(originals.mapTo(linkedSetOf()) { it.id }, model.savedJpegState.value.selectedIds)
            assertEquals(lease.token, model.cameraImportHandoffState.value.active?.token)
            assertEquals(CameraImportHandoffPhase.AWAITING_RESULT, model.cameraImportHandoffState.value.active?.phase)
            assertTrue(directory.isDirectory)
            val stillHeld = control(SavedJpegReceiverActivity.INSPECT)
            assertEquals(1, stillHeld.getInt("receiver_launches"))
            assertEquals(0, stillHeld.getInt("receiver_results"))
            assertNull("The held receiver must not complete the owner before release",
                model.cameraImportHandoffState.value.lastResult)
            assertEquals("Recreation must not satisfy the post-release resume wait", 1L, resumedAfterRelease.count)
            releasingReceiver.set(true)
            control(SavedJpegReceiverActivity.FINISH_HELD)
            assertTrue("Finishing the real receiver must naturally resume the recreated caller",
                resumedAfterRelease.await(TIMEOUT, TimeUnit.MILLISECONDS))
            assertFalse("The settled caller must not have resumed before receiver release",
                resumedAfterSettlingWhileHeld.get())
            compose.activityRule.scenario.onActivity { recreated ->
                assertSame(recreatedHost.get(), recreated)
                assertEquals(Stage.RESUMED, lifecycle.getLifecycleStageOf(recreated))
            }
            val completed = awaitHandoffResult()
            assertEquals(lease.token, model.cameraImportHandoffState.value.lastResult?.token)
            assertEquals(1, completed.getInt("receiver_launches"))
            assertEquals(1, completed.getInt("receiver_results"))
            assertReceiptReadByTarget(completed)
            assertEquals(CameraImportReceiptSummary(1, 1, 0, 0), model.savedJpegState.value.outcome?.summary)
            assertNoCameraReads()
        } finally {
            instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(observer) }
        }
    }

    @Test fun replacingTheCameraDuringReceiptIoPreservesTheLocalHandoffOutcome() {
        publishDefaultOriginals()
        connectAndOpenGallery()
        disconnectAndAwaitSessionJobs()
        val originalReads = cameraReadSnapshot()
        val replacement = CameraSessionTestSimulator("replacement-${UUID.randomUUID()}").also {
            it.start()
            replacementCameras += it
        }
        beginSendThroughSavedList(SavedJpegReceiverActivity.BLOCK_RECEIPT)
        compose.waitUntil(TIMEOUT) {
            model.cameraImportHandoffState.value.active?.phase == CameraImportHandoffPhase.READING_RECEIPT
        }
        val blocked = awaitReceiverReport { it.optBoolean("receipt_blocked") }
        assertReceiverValidatedInputs(blocked)
        val lease = requireNotNull(model.cameraImportHandoffState.value.active)
        try {
            compose.runOnIdle {
                model.setBaseUrl(replacement.baseUrl)
                model.connect()
            }
            compose.waitUntil(TIMEOUT) {
                model.uiState.value.connected && model.uiState.value.info?.model == replacement.model &&
                    !model.uiState.value.busy
            }
            assertEquals(lease.token, model.cameraImportHandoffState.value.active?.token)
            assertEquals(CameraImportHandoffPhase.READING_RECEIPT, model.cameraImportHandoffState.value.active?.phase)
            control(SavedJpegReceiverActivity.RELEASE_RECEIPT)
            val report = awaitHandoffResult()
            assertReceiptReadByTarget(report)
            assertEquals(lease.token, model.cameraImportHandoffState.value.lastResult?.token)
            assertEquals(CameraImportHandoffOrigin.SAVED_JPEG, model.cameraImportHandoffState.value.lastResult?.origin)
            assertEquals(CameraImportReceiptSummary(1, 1, 0, 0), model.savedJpegState.value.outcome?.summary)
            assertEquals(replacement.model, model.uiState.value.info?.model)
            assertNull(model.uiState.value.lastCameraImportReceiptSummary)
            assertEquals(originalReads, cameraReadSnapshot())
            assertTrue(replacement.originalReads.isEmpty())
        } finally {
            control(SavedJpegReceiverActivity.RELEASE_RECEIPT)
        }
    }

    @Test fun savedOriginalsStillTransferAfterDisconnectWithoutNewOriginalInventoryOrInfoReads() {
        publishDefaultOriginals()
        val retainedJobs = compose.runOnIdle { model.viewModelScope.coroutineContext[Job]!!.children.toSet() }
        compose.runOnIdle { model.connect() }
        compose.waitUntil(TIMEOUT) { model.uiState.value.connected && !model.uiState.value.busy }
        val connectionJobs = compose.runOnIdle {
            model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in retainedJobs }.toList()
        }
        compose.runOnIdle { model.disconnect() }
        compose.waitUntil(TIMEOUT) {
            !model.uiState.value.connected && !model.uiState.value.busy && connectionJobs.all(Job::isCompleted)
        }
        assertTrue(camera.server.requestCount > 0)
        val inventoryBefore = camera.mediaReads.get()
        val infoBefore = cameraInfoReads.get()
        val originalsBefore = camera.originalReads.size
        val report = sendThroughSavedList(SavedJpegReceiverActivity.VALID)
        assertReceiptReadByTarget(report)
        assertEquals(2, model.savedJpegState.value.outcome?.summary?.completed)
        assertEquals(inventoryBefore, camera.mediaReads.get())
        assertEquals(infoBefore, cameraInfoReads.get())
        assertEquals(originalsBefore, camera.originalReads.size)
    }

    @Test fun cleanupRevokesOneSessionWhileAnotherSessionKeepsItsActiveTemporaryGrants() = runBlocking {
        publishDefaultOriginals()
        val storage = CameraImportHandoffStorage(context)
        val retired = storage.reserveLocalSession()
        val retained = storage.reserveLocalSession()
        var pending: PendingActivityResult? = null
        try {
            val selected = store.snapshot(originals.mapTo(linkedSetOf()) { it.id })
            val first = storage.preparePublishedJpegs(selected, retired, SYNTHETIC_PROVIDER_VERSION, { _, _, _ -> }, {})
            val second = storage.preparePublishedJpegs(selected, retained, SYNTHETIC_PROVIDER_VERSION, { _, _, _ -> }, {})
            val firstUris = listOf(first.manifestUri) + first.representationUris
            val secondUris = listOf(second.manifestUri) + second.representationUris
            val ready = CountDownLatch(1)
            val readyCallback = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == Activity.RESULT_OK) ready.countDown()
                }
            }
            // Bundle writes the Parcelable's runtime class name. Send a framework receiver
            // backed by the same callback Binder, never this instrumentation-only subclass.
            val readyParcel = Parcel.obtain()
            val frameworkReadyCallback = try {
                readyCallback.writeToParcel(readyParcel, 0)
                readyParcel.setDataPosition(0)
                ResultReceiver.CREATOR.createFromParcel(readyParcel)
            } finally {
                readyParcel.recycle()
            }
            val intent = controlIntent(SavedJpegReceiverActivity.HOLD_GRANTS).apply {
                clipData = ClipData.newRawUri("Owned synthetic cache sessions", firstUris.first()).apply {
                    (firstUris.drop(1) + secondUris).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(SavedJpegReceiverActivity.READY_CALLBACK, frameworkReadyCallback)
            }
            pending = startForRealActivityResult(intent)
            assertTrue("The external Activity must hold both temporary grants before cleanup",
                ready.await(TIMEOUT, TimeUnit.MILLISECONDS))
            val before = control(SavedJpegReceiverActivity.PROBE_HELD_GRANTS)
            assertNotEquals(Process.myUid(), before.getInt("receiver_uid"))
            assertEquals(firstUris.size + secondUris.size, before.getJSONArray("held_readable").length())
            for (index in 0 until firstUris.size + secondUris.size) {
                assertTrue(before.getJSONArray("held_readable").getBoolean(index))
                assertTrue(before.getJSONArray("held_granted").getBoolean(index))
            }
            assertTrue(storage.cleanup(retired))
            val after = control(SavedJpegReceiverActivity.PROBE_HELD_GRANTS)
            firstUris.indices.forEach { index ->
                assertFalse("The retired session must lose its Android grant", after.getJSONArray("held_granted").getBoolean(index))
                assertFalse("The external UID must be denied the retired session", after.getJSONArray("held_readable").getBoolean(index))
            }
            secondUris.indices.forEach { index ->
                assertTrue(after.getJSONArray("held_granted").getBoolean(index + firstUris.size))
                assertTrue(after.getJSONArray("held_readable").getBoolean(index + firstUris.size))
            }
            assertTrue(File(context.cacheDir, "camera-import/${retained.sessionId}").isDirectory)
            assertOriginalsAndUnrelatedCacheUnchanged()
            assertNoCameraReads()
        } finally {
            try {
                control(SavedJpegReceiverActivity.FINISH_HELD)
                pending?.await()?.let { assertEquals(Activity.RESULT_OK, it.resultCode) }
            } finally {
                pending?.close()
                assertTrue(storage.cleanup(retired))
                assertTrue(storage.cleanup(retained))
            }
        }
    }

    @Test fun externalReceiverCannotOpenManifestOrOriginalsWhenInputGrantIsRemoved() = runBlocking {
        publishDefaultOriginals()
        control(SavedJpegReceiverActivity.CONFIGURE, SavedJpegReceiverActivity.EXPECT_NO_INPUT_GRANT)
        val storage = CameraImportHandoffStorage(context)
        val reservation = storage.reserveLocalSession()
        val session = storage.preparePublishedJpegs(store.snapshot(originals.mapTo(linkedSetOf()) { it.id }),
            reservation, SYNTHETIC_PROVIDER_VERSION, { _, _, _ -> }, {})
        try {
            val intent = SereinImportIntents.create(session, testPackage).apply {
                removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            val result = launchForRealActivityResult(intent)
            assertEquals(Activity.RESULT_OK, result.resultCode)
            val report = JSONObject(requireNotNull(result.data?.getStringExtra(SavedJpegReceiverActivity.REPORT)))
            assertFalse("The no-grant control must fail access checks, not receiver setup", report.has("failure"))
            assertNotEquals(Process.myUid(), report.getInt("receiver_uid"))
            assertEquals(session.itemCount + 1, report.getInt("input_reads_denied"))
            assertEquals(0, report.getInt("grant_flags"))
        } finally {
            assertTrue(storage.cleanup(reservation))
        }
        assertFalse(File(context.cacheDir, "camera-import/${session.sessionId}").exists())
        assertOriginalsAndUnrelatedCacheUnchanged()
        assertNoCameraReads()
    }

    private fun publishDefaultOriginals() {
        if (originals.isNotEmpty()) return
        check(store.state.value.entries.isEmpty()) { "A VM publication must be observed before fixture publication" }
        runBlocking {
            publishOriginal(0, camera.imageBytes)
            publishOriginal(1, camera.imageBytes + byteArrayOf(1, 7, 11, 19))
        }
        compose.waitUntil(TIMEOUT) { model.savedJpegState.value.rows.size == 2 }
    }

    private fun mediaJson(id: String): JSONObject = JSONObject()
        .put("id", id).put("name", "SYNTHETIC-${id.substringAfterLast('-')}.JPG")
        .put("kind", "image").put("size_bytes", camera.imageBytes.size)
        .put("content_type", "image/jpeg").put("capture_time", "2000-01-01T00:00:01Z")

    private fun connectAndOpenGallery() {
        compose.runOnIdle {
            retainedJobsBeforeConnection = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            model.connect()
        }
        compose.waitUntil(TIMEOUT) {
            val state = model.uiState.value
            state.connected && state.info?.model == camera.model && !state.busy &&
                state.captureReviewItem != null && !state.captureReviewLoading
        }
        compose.runOnIdle { model.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(TIMEOUT) {
            !model.uiState.value.mediaLibraryLoading && !model.uiState.value.busy &&
                model.uiState.value.mediaItems.size == inventory.get().size
        }
        compose.awaitForegroundActivityWindow(compose.activity)
    }

    private fun disconnectAndAwaitSessionJobs() {
        val sessionJobs = compose.runOnIdle {
            model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in retainedJobsBeforeConnection }.toList()
        }
        compose.runOnIdle { model.disconnect() }
        compose.waitUntil(TIMEOUT) {
            !model.uiState.value.connected && !model.uiState.value.busy && sessionJobs.all(Job::isCompleted)
        }
    }

    private fun captureVmPublishedOriginal(sourceId: String) {
        compose.waitUntil(TIMEOUT) { store.state.value.entries.any { it.item.id == sourceId } }
        val saved = store.state.value.entries.single()
        assertEquals(sourceId, saved.item.id)
        assertEquals(camera.model, saved.camera.model)
        assertEquals(camera.imageBytes.size.toLong(), saved.evidence.byteLength)
        assertEquals(sha256(camera.imageBytes), saved.evidence.sha256)
        createdGalleryUris += saved.uri
        originals += OwnedOriginal(saved.uri, saved.id, camera.imageBytes)
        pendingVmOutputNames.remove(saved.item.name)
        assertArrayEquals(camera.imageBytes, resolver.openInputStream(saved.uri)?.use { it.readBytes() })
    }

    private fun assertSingleGalleryHistoryReceipt() {
        runBlocking { withTimeout(TIMEOUT) { history.awaitIdle() } }
        val receipt = history.state.value.entries.single()
        assertEquals(DownloadHistoryDestination.GALLERY, receipt.destination)
        assertEquals(DownloadHistoryOutcome.COMPLETED, receipt.outcome)
        assertTrue(camera.deletes.isEmpty())
    }

    private fun cameraReadSnapshot() = CameraReadSnapshot(cameraInfoReads.get(), camera.mediaReads.get(),
        freshInfoReads.get(), camera.originalReads.toList())

    private fun awaitReceiverReport(predicate: (JSONObject) -> Boolean): JSONObject {
        var last = JSONObject()
        compose.waitUntil(TIMEOUT) {
            last = control(SavedJpegReceiverActivity.INSPECT)
            assertFalse("The external fixture must remain valid while held", last.has("failure"))
            predicate(last)
        }
        return last
    }

    private fun collectExactPendingFixtureOutputs() {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        pendingVmOutputNames.forEach { name ->
            // Failure cleanup cannot rely on the registration callback under test. This is
            // limited to the exact expected filename and random per-test camera directory.
            requireNotNull(resolver.query(collection.buildUpon().appendQueryParameter("includePending", "1").build(),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(cameraGalleryPath(camera.model), name), null)).use { cursor ->
                while (cursor.moveToNext()) createdGalleryUris += ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
    }

    private suspend fun publishOriginal(index: Int, bytes: ByteArray) {
        val item = CameraMediaItem("synthetic-saved-$index", "SYNTHETIC-$index.JPG", "image", bytes.size.toLong(),
            captureTime = "2026-10-07T00:00:00Z", contentType = "image/jpeg")
        val source = CameraInfo(true, camera.model, "SYNTHETIC-SERIAL-NOT-FROM-A-CAMERA", "simulator")
        var publishedId: DeliveredJpegId? = null
        val uri = CameraMediaGalleryStore(resolver).save(camera.model, item,
            onPublished = { evidence -> publishedId = store.recordPublished(source, item, evidence) },
        ) { output ->
            output.write(bytes)
            CameraMediaDownloadResult(item, bytes.size.toLong(), "image/jpeg")
        }
        createdGalleryUris += uri
        originals += OwnedOriginal(uri, requireNotNull(publishedId), bytes)
    }

    private fun sendThroughSavedList(mode: String): JSONObject {
        publishDefaultOriginals()
        beginSendThroughSavedList(mode)
        return awaitHandoffResult()
    }

    private fun beginSendThroughSavedList(mode: String) {
        control(SavedJpegReceiverActivity.CONFIGURE, mode)
        compose.awaitForegroundActivityWindow(compose.activity)
        compose.onNodeWithTag("saved-jpegs-open").performScrollTo().performClick()
        compose.onNodeWithTag("saved-jpegs-dialog").assertIsDisplayed()
        originals.forEach { original ->
            val tag = "saved-jpegs-entry-${original.id.value}"
            compose.onNodeWithTag("saved-jpegs-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
        }
        assertEquals(originals.mapTo(linkedSetOf()) { it.id }, model.savedJpegState.value.selectedIds)
        compose.onNodeWithTag("saved-jpegs-list").performScrollToNode(hasTestTag("saved-jpegs-send"))
        compose.onNodeWithTag("saved-jpegs-send").assertIsEnabled().performClick()
    }

    private fun awaitHandoffResult(): JSONObject {
        compose.waitUntil(TIMEOUT) {
            model.cameraImportHandoffState.value.lastResult != null && !model.cameraImportHandoffState.value.busy
        }
        val report = control(SavedJpegReceiverActivity.INSPECT)
        assertReceiverValidatedInputs(report)
        val session = report.getString("session_id")
        assertTrue(session.matches(Regex("session-[a-f0-9-]{36}")))
        assertFalse("The completed handoff's staged cache must be gone", File(context.cacheDir, "camera-import/$session").exists())
        assertFalse(model.savedJpegState.value.cleanupUnconfirmed)
        assertEquals(originals.size, store.state.value.entries.size)
        assertOriginalsAndUnrelatedCacheUnchanged()
        return report
    }

    private fun assertRejected(mode: String, issue: CameraImportHandoffIssue, receiptRead: Boolean = true) {
        val report = sendThroughSavedList(mode)
        val outcome = requireNotNull(model.savedJpegState.value.outcome)
        assertEquals(issue, outcome.issue)
        assertNull(outcome.summary)
        if (receiptRead) assertReceiptReadByTarget(report) else assertEquals(0, report.getInt("receipt_reads"))
        compose.onNodeWithTag("saved-jpegs-list").performScrollToNode(hasTestTag("saved-jpegs-issue"))
        compose.onNodeWithTag("saved-jpegs-issue").assertIsDisplayed()
        compose.onNodeWithTag("saved-jpegs-result").assertDoesNotExist()
        assertNoCameraReads()
    }

    private fun assertReceiverValidatedInputs(report: JSONObject) {
        assertFalse("The external fixture must validate every staged original", report.has("failure"))
        assertEquals(Process.myUid(), report.getInt("caller_uid"))
        assertEquals(instrumentation.context.applicationInfo.uid, report.getInt("receiver_uid"))
        assertNotEquals(Process.myUid(), report.getInt("receiver_uid"))
        assertTrue(report.getBoolean("manifest_first"))
        assertEquals(originals.size + 1, report.getInt("clip_count"))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, report.getInt("grant_flags"))
        assertEquals(originals.size, report.getInt("original_count"))
        assertEquals(originals.size + 1, report.getInt("write_denials"))
        assertTrue(report.getBoolean("original_evidence_matched"))
        originals.reversed().forEachIndexed { index, original ->
            assertEquals(original.bytes.size.toLong(), report.getJSONArray("original_lengths").getLong(index))
            assertEquals(sha256(original.bytes), report.getJSONArray("original_hashes").getString(index))
        }
    }

    private fun assertReceiptReadByTarget(report: JSONObject) {
        assertEquals(1, report.getInt("receipt_reads"))
        assertEquals(Process.myUid(), report.getInt("receipt_reader_uid"))
        assertEquals(report.getInt("receiver_uid"), report.getInt("receipt_provider_uid"))
    }

    private fun assertOriginalsAndUnrelatedCacheUnchanged() {
        originals.forEach { original ->
            assertArrayEquals(original.bytes, resolver.openInputStream(original.uri)?.use { it.readBytes() })
            requireNotNull(resolver.query(original.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE), null, null, null)).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals("image/jpeg", cursor.getString(1))
                assertEquals(original.bytes.size.toLong(), cursor.getLong(2))
            }
        }
        assertTrue(unrelatedSession.isDirectory)
        assertArrayEquals(SENTINEL, unrelatedSentinel.readBytes())
    }

    private fun assertNoCameraReads() {
        assertEquals(0, camera.server.requestCount)
        assertTrue(camera.originalReads.isEmpty())
        assertTrue(camera.deletes.isEmpty())
        assertTrue(history.state.value.entries.isEmpty())
    }

    private fun control(operation: String, mode: String? = null): JSONObject {
        if (operation != SavedJpegReceiverActivity.CONFIGURE) {
            check(mode == null)
            return controlThroughCapability(operation)
        }
        val result = launchForRealActivityResult(controlIntent(operation).apply {
            mode?.let { putExtra(SavedJpegReceiverActivity.MODE, it) }
        })
        assertEquals("The test APK control Activity must return a real ActivityResult", Activity.RESULT_OK, result.resultCode)
        receiverControl = requireNotNull(result.data?.extras?.getBinder(SavedJpegReceiverActivity.CONTROL_BINDER)) {
            "The validated receiver must return its cross-UID control capability"
        }
        return JSONObject(requireNotNull(result.data?.getStringExtra(SavedJpegReceiverActivity.REPORT)))
    }

    /** Only diagnostic/release commands use Binder. Import and receipt delivery use Android. */
    private fun controlThroughCapability(operation: String): JSONObject {
        val received = LinkedBlockingQueue<Pair<Int, Bundle?>>(1)
        val callback = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                received.offer(resultCode to resultData)
            }
        }
        val request = Parcel.obtain()
        val acknowledgement = Parcel.obtain()
        try {
            request.writeInterfaceToken(SavedJpegReceiverActivity.CONTROL_DESCRIPTOR)
            request.writeString(operation)
            // Match the receiver's explicit CREATOR; omit the anonymous callback class name.
            callback.writeToParcel(request, 0)
            assertTrue("The receiver must accept its diagnostic control transaction",
                requireNotNull(receiverControl).transact(SavedJpegReceiverActivity.CONTROL_TRANSACTION,
                    request, acknowledgement, 0))
            acknowledgement.readException()
        } finally {
            request.recycle()
            acknowledgement.recycle()
        }
        val response = received.poll(TIMEOUT, TimeUnit.MILLISECONDS)
        assertNotNull("The receiver did not acknowledge diagnostic control: $operation", response)
        val (resultCode, data) = requireNotNull(response)
        assertEquals("The receiver diagnostic control failed: $operation", Activity.RESULT_OK, resultCode)
        return JSONObject(requireNotNull(data?.getString(SavedJpegReceiverActivity.REPORT)))
    }

    private fun controlIntent(operation: String) = Intent(SavedJpegReceiverActivity.CONTROL_ACTION).apply {
        component = ComponentName(testPackage, SavedJpegReceiverActivity::class.java.name)
        putExtra(SavedJpegReceiverActivity.OPERATION, operation)
    }

    /** No ActivityMonitor/dispatchResult interception: Android must deliver the cross-UID result. */
    private fun launchForRealActivityResult(intent: Intent): ActivityResult {
        val pending = startForRealActivityResult(intent)
        return try { pending.await() } finally { pending.close() }
    }

    private fun startForRealActivityResult(intent: Intent): PendingActivityResult {
        val received = LinkedBlockingQueue<ActivityResult>(1)
        lateinit var launcher: ActivityResultLauncher<Intent>
        instrumentation.runOnMainSync {
            launcher = compose.activity.activityResultRegistry.register("saved-jpeg-${UUID.randomUUID()}",
                ActivityResultContracts.StartActivityForResult()) { received.offer(it) }
            launcher.launch(intent)
        }
        return PendingActivityResult(received, launcher)
    }

    private inner class PendingActivityResult(
        private val received: LinkedBlockingQueue<ActivityResult>,
        private val launcher: ActivityResultLauncher<Intent>,
    ) {
        fun await(): ActivityResult {
            val result = received.poll(TIMEOUT, TimeUnit.MILLISECONDS)
            assertNotNull("Android did not return the synthetic receiver ActivityResult", result)
            return requireNotNull(result)
        }
        fun close() {
            instrumentation.runOnMainSync { launcher.unregister() }
        }
    }

    private data class OwnedOriginal(val uri: Uri, val id: DeliveredJpegId, val bytes: ByteArray)
    private data class CameraReadSnapshot(val cameraInfo: Int, val inventory: Int, val freshInfo: Int,
        val originalPaths: List<String>)

    companion object {
        private const val TIMEOUT = 20_000L
        private const val IMPORT_TIMEOUT = 30_000L
        // Synthetic fixtures still obey the real contract's minimum provider version, 0.5.0.
        private const val SYNTHETIC_PROVIDER_VERSION = "0.5.1-synthetic-test"
        private val SENTINEL = "SYNTHETIC unrelated cache session remains intact".toByteArray()
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
