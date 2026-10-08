package dev.openeos.control.ui

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.IdentityHashMap
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real app → actions → ViewModel → repository with a synthetic HTTP peer. No camera evidence. */
class CameraMediaDateFilterJourneyTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val diagnostics = DateFilterFailureDiagnostics()
    // Outside Compose so setup, teardown, and the rule's own cleanup retain diagnostic coverage.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(diagnostics).around(compose)
    private val camera = CameraSessionTestSimulator("date-filter")
    private val store = ViewModelStore()
    private val models = mutableListOf<CameraViewModel>()
    private lateinit var model: CameraViewModel
    private lateinit var restoration: StateRestorationTester
    private lateinit var directory: File
    private lateinit var originalZone: TimeZone
    private val records = listOf(
        Triple("latest", "LATEST.JPG", "2026-08-16T12:00:00Z"),
        Triple("raw", "PAIR.CR3", "2026-08-14T12:00:00Z"),
        Triple("jpeg", "PAIR.JPG", "2026-08-14T12:00:00Z"),
        Triple("older", "OLDER.JPG", "2026-08-13T12:00:00Z"),
        Triple("unknown", "UNKNOWN.JPG", "2026-02-30T12:00:00Z"),
    )

    @Before fun setUp() {
        diagnostics.phase("setup:start")
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        directory = File(compose.activity.cacheDir, "camera-import/date-filter-${UUID.randomUUID()}")
        check(directory.mkdirs())
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (request.method == "GET" && url.encodedPath == "/ccapi/media") {
                camera.json(JSONObject().put("items", JSONArray(records.map { (id, name, date) ->
                    JSONObject().put("id", id).put("name", name).put("kind", "image")
                        .put("size_bytes", camera.imageBytes.size).put("capture_time", date)
                })).toString())
            } else null
        }
        // Resolves MockWebServer URL/hostname only on the test thread, with StrictMode unchanged.
        camera.start()
        model = CameraViewModel().also(models::add)
        store.put("date-filter", model)
        restoration = StateRestorationTester(compose)
        diagnostics.phase("setup:set-content")
        restoration.setContent { OpenEosControlApp(model) }
        diagnostics.phase("setup:connect")
        connect()
        diagnostics.phase("setup:open-album")
        openAlbum()
    }

    @After fun tearDown() {
        diagnostics.phase("teardown:release-gates")
        camera.releaseGates()
        try {
            if (::model.isInitialized) {
                val jobs = models.map { requireNotNull(it.viewModelScope.coroutineContext[Job]) }
                diagnostics.phase("teardown:clear-models")
                compose.runOnIdle { store.clear() }
                diagnostics.phase("teardown:await-model-jobs")
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { jobs.all(Job::isCompleted) }
            }
        } finally {
            diagnostics.phase("teardown:shutdown-server")
            try { camera.server.shutdown() } finally {
                diagnostics.phase("teardown:restore-fixture")
                if (::directory.isInitialized) directory.deleteRecursively()
                if (::originalZone.isInitialized) TimeZone.setDefault(originalZone)
            }
        }
    }

    @Test fun rangeAppliesToLoadedRawAndJpegAndCancelDoesNotChangeIt() {
        val before = model.uiState.value.mediaItems
        val reads = camera.mediaReads.get()
        applyRange("2026-08-14", "2026-08-14")
        assertGalleryItemVisible("raw")
        assertGalleryItemVisible("jpeg")
        assertExcludedFromGallery("older", "unknown")
        compose.onNodeWithText(text(R.string.media_date_loaded_results, 2, 5, 1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_date_partial)).performScrollTo().assertIsDisplayed()
        val applied = model.uiState.value.mediaDateRange
        openDateDialog()
        compose.onNodeWithTag("media-date-start").performTextReplacement("2026-02-30")
        compose.onNodeWithTag("media-date-apply").assertIsNotEnabled()
        compose.onNodeWithTag("media-date-cancel").performScrollTo().performClick()
        assertEquals(applied, model.uiState.value.mediaDateRange)
        assertEquals(before, model.uiState.value.mediaItems)
        assertEquals(reads, camera.mediaReads.get())
        assertTrue(camera.mutations.isEmpty())
        compose.onNodeWithContentDescription(text(R.string.media_date_clear)).performClick()
        assertGalleryItemVisible("older")
        assertNull(model.uiState.value.mediaDateRange)
    }

    @Test fun hiddenSelectionsAreCountedAndBatchDeleteKeepsExactIdsEvenWithNoResults() {
        compose.onNodeWithContentDescription(text(R.string.preview_media, "OLDER.JPG"))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        applyRange("2026-08-14", "2026-08-14")
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.select_all_media)).performClick()
        applyRange("2020-01-01", "2020-01-01")
        compose.onNodeWithText(text(R.string.no_filtered_media)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 3)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.delete_selected_media, 3)).performClick()
        compose.onNode(hasText(text(R.string.media_hidden_selected, 3)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete)).performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { model.uiState.value.lastMediaBatchResult != null }
        assertEquals(setOf("/ccapi/media/raw", "/ccapi/media/jpeg", "/ccapi/media/older"), camera.deletes.toSet())
        assertEquals(3, camera.deletes.size)
    }

    @Test fun sameViewModelRecreationAndAlbumReentryKeepRangeButNewSessionClearsIt() {
        applyRange("2026-08-14", "2026-08-14")
        val applied = model.uiState.value.mediaDateRange
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(applied, model.uiState.value.mediaDateRange)
        compose.onNodeWithTag("media-date-range").assertIsDisplayed()
        compose.runOnIdle { model.setUiMode(UiMode.CONTROL) }
        openAlbum()
        assertEquals(applied, model.uiState.value.mediaDateRange)
        val previous = model.uiState.value
        compose.runOnIdle { model.disconnect() }
        connect()
        openAlbum()
        compose.runOnIdle { model.setMediaDateRange(applied, previous.info, previous.mediaSessionGeneration) }
        assertNull(model.uiState.value.mediaDateRange)
        val fresh = CameraViewModel().also(models::add)
        store.put("fresh-date-filter", fresh)
        compose.runOnIdle { fresh.setMediaDateRange(applied, previous.info, previous.mediaSessionGeneration) }
        assertNull(fresh.uiState.value.mediaDateRange)
        assertGalleryItemVisible("older")
    }

    @Test fun captureReviewOutsideRangeStillOpensTheExactLatestItemAsOneOfOne() {
        val review = requireNotNull(model.uiState.value.captureReviewItem)
        assertEquals("latest", review.id)
        applyRange("2026-08-14", "2026-08-14")
        compose.runOnIdle { model.openCaptureReview() }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.mediaPreviewLoading }
        assertEquals(review.id, model.uiState.value.mediaPreviewItem?.id)
        compose.onNodeWithText(text(R.string.media_viewer_position, 1, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.previous_media)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.next_media)).assertDoesNotExist()
        assertEquals(review, model.uiState.value.captureReviewItem)
        assertEquals("/ccapi/media/latest", camera.previewReads.last())
    }

    @Test fun filteringAnActiveDownloadDoesNotChangeItsOwnerOrOriginalBytes() {
        diagnostics.phase("download:prepare-destination")
        val item = model.uiState.value.mediaItems.single { it.id == "older" }
        val file = File(directory, item.name).apply { check(createNewFile()) }
        val uri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.camera_import", file)
        val gate = camera.gate()
        val original = camera.intercept
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/ccapi/media/older" && url.query == null) {
                gate.blockResponse()
                camera.imageResponse()
            } else original(request)
        }
        diagnostics.phase("download:start")
        compose.runOnIdle { model.downloadMedia(compose.activity, item, uri) }
        diagnostics.phase("download:await-gate")
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        applyRange("2026-08-14", "2026-08-14")
        diagnostics.phase("download:assert-owner-and-request")
        assertEquals(item.name, model.uiState.value.activeMediaDownloadName)
        compose.onNodeWithContentDescription(text(R.string.cancel_media_download)).assertIsDisplayed()
        assertEquals(listOf("/ccapi/media/older"), camera.originalReads.toList())
        diagnostics.phase("download:release-gate")
        gate.release()
        diagnostics.phase("download:await-completion")
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            !model.uiState.value.isBusy(CameraOperation.MEDIA) && model.uiState.value.lastDownloadedMediaName == item.name
        }
        diagnostics.phase("download:assert-bytes-and-request")
        assertArrayEquals(camera.imageBytes, file.readBytes())
        assertEquals(listOf("/ccapi/media/older"), camera.originalReads.toList())
        diagnostics.phase("download:verified")
    }

    private fun assertGalleryItemVisible(itemId: String) {
        val name = records.single { it.first == itemId }.second
        compose.onNodeWithTag("media-gallery-grid").performScrollToKey(itemId)
        compose.onNodeWithContentDescription(text(R.string.preview_media, name)).assertIsDisplayed()
    }

    private fun assertExcludedFromGallery(vararg itemIds: String) {
        val grid = compose.onNodeWithTag("media-gallery-grid").fetchSemanticsNode()
        compose.runOnIdle {
            itemIds.forEach { itemId ->
                // IndexForKey checks all items, including tiles outside the composed viewport.
                assertEquals("Filtered item $itemId must be absent from the gallery", -1, grid.config[SemanticsProperties.IndexForKey](itemId))
            }
        }
    }

    private fun connect() {
        compose.waitForIdle()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.busy }
        compose.runOnIdle {
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
            model.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.connected && !state.busy && !state.captureReviewLoading
        }
    }

    private fun openAlbum() {
        compose.runOnIdle { model.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaItems.size == records.size && !model.uiState.value.mediaLibraryLoading
        }
    }

    private fun openDateDialog() = compose.onNodeWithContentDescription(text(
        if (model.uiState.value.mediaDateRange == null) R.string.media_date_filter else R.string.media_date_edit,
    )).performClick()

    private fun applyRange(start: String, end: String) {
        diagnostics.phase("date:open-dialog")
        openDateDialog()
        diagnostics.phase("date:scroll-start")
        val startField = compose.onNodeWithTag("media-date-start").performScrollTo()
        diagnostics.phase("date:replace-start")
        startField.performTextReplacement(start)
        diagnostics.phase("date:scroll-end")
        val endField = compose.onNodeWithTag("media-date-end").performScrollTo()
        diagnostics.phase("date:replace-end")
        endField.performTextReplacement(end)
        diagnostics.phase("date:scroll-apply")
        val applyButton = compose.onNodeWithTag("media-date-apply").performScrollTo()
        diagnostics.phase("date:click-apply")
        applyButton.performClick()
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
}

/** Bounded observations only; neither the test failure nor a fatal handler's decision is replaced. */
private class DateFilterFailureDiagnostics : TestRule {
    @Volatile private var active = false
    @Volatile private var currentPhase = "rule:start"
    @Volatile private var phaseBeforeTeardown = "not-entered"
    private val phaseCount = AtomicInteger()
    private val caughtRecorded = AtomicBoolean()
    private val fatalRecorded = AtomicBoolean()
    private lateinit var testThread: Thread

    override fun apply(base: Statement, description: Description): Statement {
        if (description.methodName != "filteringAnActiveDownloadDoesNotChangeItsOwnerOrOriginalBytes") return base
        return object : Statement() {
            override fun evaluate() {
                testThread = Thread.currentThread()
                active = true
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                val handler = previous?.let { delegate ->
                    Thread.UncaughtExceptionHandler { thread, failure ->
                        try {
                            capture("fatal", failure, thread, fatalRecorded)
                        } finally {
                            delegate.uncaughtException(thread, failure)
                        }
                    }
                }
                try {
                    if (handler != null) Thread.setDefaultUncaughtExceptionHandler(handler)
                    phase(if (handler != null) "rule:start" else "rule:start-without-default-handler")
                    base.evaluate()
                } catch (failure: Throwable) {
                    try {
                        capture("rule-failure", failure, Thread.currentThread(), caughtRecorded)
                    } finally {
                        throw failure
                    }
                } finally {
                    // Do not overwrite a handler another owner installed while the rule was active.
                    if (handler != null && Thread.getDefaultUncaughtExceptionHandler() === handler) {
                        Thread.setDefaultUncaughtExceptionHandler(previous)
                    }
                    active = false
                }
            }
        }
    }

    fun phase(value: String) {
        if (!active) return
        if (value == "teardown:release-gates") phaseBeforeTeardown = currentPhase
        currentPhase = value
        if (phaseCount.incrementAndGet() <= 32) {
            emit(Log.INFO, "phase=$value uptime=${SystemClock.uptimeMillis()}".take(160))
        }
    }

    private fun capture(kind: String, failure: Throwable, failedThread: Thread, recorded: AtomicBoolean) {
        if (!active || !recorded.compareAndSet(false, true)) return
        val phaseAtFailure = currentPhase
        val stacks = Thread.getAllStackTraces()
        val threads = (listOf(Looper.getMainLooper().thread, failedThread, testThread) +
            stacks.entries.filter { (_, frames) -> frames.any { it.className.startsWith("androidx.compose.") } }
                .sortedByDescending { (_, frames) -> frames.any { it.className.endsWith("MeasureAndLayoutDelegate") } }
                .map { it.key }).distinct().take(6)
        val mainThread = Looper.getMainLooper().thread
        val report = buildString {
            appendLine("$kind last-observed-phase=$phaseAtFailure phase-before-teardown=$phaseBeforeTeardown uptime=${SystemClock.uptimeMillis()}")
            appendLine(throwableReport(failure))
            threads.forEach { thread ->
                val role = when (thread) {
                    mainThread -> "main"
                    testThread -> "instrumentation"
                    failedThread -> "failure"
                    else -> "compose-${threads.indexOf(thread)}"
                }
                val frames = stacks[thread] ?: thread.stackTrace
                appendLine("Thread role=$role state=${thread.state}, first 16/${frames.size} frames:")
                frames.take(16).forEach { appendLine("  ${it.toString().take(200)}") }
            }
        }
        val bounded = if (report.length <= 16000) report else report.take(15960) + "\n[Diagnostic output truncated]\n"
        bounded.chunked(1500).forEachIndexed { index, chunk -> emit(Log.ERROR, "$kind chunk=$index\n$chunk") }
    }

    private fun throwableReport(failure: Throwable): String {
        val ids = IdentityHashMap<Throwable, Int>()
        val failures = mutableListOf(failure)
        ids[failure] = 0
        // Record causal links before frames, so a long primary stack cannot hide a suppressed error.
        val links = mutableListOf<String>()
        var truncated = false
        fun link(from: Int, relation: String, target: Throwable) {
            if (links.size >= 32 || (target !in ids && failures.size >= 16)) {
                truncated = true
                return
            }
            val id = ids[target] ?: failures.size.also { ids[target] = it; failures += target }
            links += "throwable[$from] $relation -> throwable[$id]"
        }
        var index = 0
        while (index < failures.size) {
            val current = failures[index]
            current.cause?.let { link(index, "cause", it) }
            current.suppressed.take(32).forEachIndexed { suppressedIndex, suppressed ->
                link(index, "suppressed[$suppressedIndex]", suppressed)
            }
            if (current.suppressed.size > 32) truncated = true
            index++
        }
        return buildString {
            appendLine("Throwable graph (messages omitted; original rethrown/delegated unchanged):")
            failures.forEachIndexed { id, throwable -> appendLine("throwable[$id] ${throwable.javaClass.name.take(160)}") }
            links.forEach { appendLine(it) }
            if (truncated) appendLine("[Throwable graph exceeds 16 nodes/32 links; original Throwable remains intact]")
            failures.forEachIndexed { id, throwable ->
                appendLine("throwable[$id] first 16/${throwable.stackTrace.size} frames:")
                throwable.stackTrace.take(16).forEach { appendLine("  ${it.toString().take(200)}") }
            }
        }.let { if (it.length <= 8000) it else it.take(7960) + "\n[Throwable frames truncated]\n" }
    }

    private fun emit(priority: Int, message: String) {
        try {
            Log.println(priority, "EOSDateFilterDiag", message)
        } catch (_: Throwable) {
            // Diagnostic output must never replace an assertion or prevent fatal-handler delegation.
        }
    }
}
