package dev.openeos.control.ui

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryFileStorage
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryWarning
import dev.openeos.control.data.DownloadHistoryWritePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real ViewModel/HTTP/document-save route with private files; no picker or physical-camera evidence. */
@SdkSuppress(minSdkVersion = 29)
class CameraDownloadHistorySafJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("saf-history")
    // Cancellation must finish before either this HTTP timeout or the bounded response gate.
    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(client = http, diagnostics = CameraNetworkDiagnostics.Empty)
        },
    ))
    private val viewModels = ViewModelStore()
    private val historyScopes = mutableListOf<CoroutineScope>()
    private val histories = mutableListOf<DownloadHistoryStore>()
    private lateinit var root: File
    private lateinit var unrelated: File
    private lateinit var provider: PrivateDocumentProvider
    private lateinit var documentContext: Context
    private lateinit var history: DownloadHistoryStore
    private lateinit var model: CameraViewModel
    private val unrelatedBytes = byteArrayOf(11, 19, 37, 53)

    @Before fun setUp() {
        val context = compose.activity.applicationContext
        root = File(context.cacheDir, "download-history-saf-fixture-${UUID.randomUUID()}")
        check(root.mkdirs())
        unrelated = File(root, "unrelated-synthetic.bin").apply { writeBytes(unrelatedBytes) }
        // Represents the new, empty document returned by a successful CreateDocument picker.
        val destination = File(root, "SYNTHETIC-OWNED.JPG").apply { check(createNewFile()) }
        provider = PrivateDocumentProvider(destination)
        provider.attachInfo(context, ProviderInfo().apply {
            authority = provider.destinationUri.authority
            exported = false
        })
        documentContext = ScopedDocumentContext(context, ContentResolver.wrap(provider))
        // Resolve the simulator hostname on the instrumentation thread; leave StrictMode intact.
        camera.start()
    }

    @After fun tearDown() {
        camera.releaseGates()
        try {
            if (::model.isInitialized) {
                val job = requireNotNull(model.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { viewModels.clear() }
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { job.isCompleted }
            }
            runBlocking {
                withTimeout(SESSION_TEST_TIMEOUT_MILLIS) {
                    // onCleared also closes the repository outside viewModelScope.
                    repository.disconnect()
                    histories.forEach { it.awaitIdle() }
                }
            }
        } finally {
            historyScopes.forEach { it.cancel() }
            try {
                runBlocking {
                    withTimeout(SESSION_TEST_TIMEOUT_MILLIS) {
                        historyScopes.forEach { it.coroutineContext[Job]?.join() }
                    }
                }
            } finally {
                http.dispatcher.cancelAll()
                try {
                    camera.server.shutdown()
                } finally {
                    http.connectionPool.evictAll()
                    if (::provider.isInitialized) provider.shutdown()
                    if (::root.isInitialized) root.deleteRecursively()
                }
            }
        }
    }

    @Test fun terminalHistoryWriteFailurePreservesSavedDocumentAndDoesNotRepeatOriginalRead() {
        val moves = AtomicInteger()
        val directory = File(root, "write-failure-history")
        history = newHistory(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE && moves.incrementAndGet() > 1) {
                throw IOException("Synthetic terminal receipt write failure")
            }
        }
        val item = connectAndChooseOriginal()

        compose.runOnIdle { model.downloadMedia(documentContext, item, provider.destinationUri) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saved &&
                !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        awaitHistoryIdle()

        val completed = history.state.value.entries.single()
        assertEquals(DownloadHistoryDestination.DOCUMENT, completed.destination)
        assertEquals(DownloadHistoryOutcome.COMPLETED, completed.outcome)
        assertEquals(item.name, completed.filename)
        assertNotNull(completed.finishedAtMillis)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, history.state.value.warning)
        assertEquals(2, moves.get()) // Durable admission, then the failed terminal replacement.
        assertEquals(item.name, model.uiState.value.lastDownloadedMediaName)
        assertNull(model.uiState.value.error)
        assertEquals(listOf("w"), provider.openModes.toList())
        assertTrue(provider.deletedUris.isEmpty())
        assertArrayEquals(camera.imageBytes, provider.destination.readBytes())
        assertArrayEquals(unrelatedBytes, unrelated.readBytes())
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())

        val restored = newHistory(directory)
        awaitHistoryLoaded(restored)
        val pending = restored.state.value.entries.single()
        assertEquals(completed.receiptId, pending.receiptId)
        assertEquals(DownloadHistoryDestination.DOCUMENT, pending.destination)
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, pending.outcome)
        assertNull(pending.finishedAtMillis)
        assertNull(restored.state.value.warning)
        // Opening durable history cannot retry the transfer or remove the successful original.
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())
        assertArrayEquals(camera.imageBytes, provider.destination.readBytes())
        assertTrue(provider.deletedUris.isEmpty())
        assertTrue(model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saved)
    }

    @Test fun cancellingGatedOriginalRecordsCancelledAndDeletesOnlyItsOwnedDocument() {
        val directory = File(root, "cancelled-history")
        history = newHistory(directory)
        val item = connectAndChooseOriginal()
        val gate = camera.gate()
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (request.method == "GET" && url.encodedPath == "/ccapi/media/${item.id}" && url.query == null) {
                gate.blockResponse()
                camera.imageResponse()
            } else null
        }

        compose.runOnIdle { model.downloadMedia(documentContext, item, provider.destinationUri) }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val pending = history.state.value.entries.single()
        assertEquals(DownloadHistoryDestination.DOCUMENT, pending.destination)
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, pending.outcome)
        assertNull(pending.finishedAtMillis)
        assertEquals(listOf("w"), provider.openModes.toList())
        assertTrue(provider.destination.isFile)
        assertEquals(0L, provider.destination.length())

        compose.runOnIdle { model.cancelMediaDownload() }
        // Prove cancellation/cleanup without first unblocking HTTP or waiting for socket timeout.
        compose.waitUntil(5_000L) {
            model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Cancelled &&
                !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        assertFalse(provider.destination.exists())
        assertEquals(listOf(provider.destinationUri), provider.deletedUris.toList())
        assertArrayEquals(unrelatedBytes, unrelated.readBytes())
        gate.release()
        awaitHistoryIdle()

        val cancelled = history.state.value.entries.single()
        assertEquals(pending.receiptId, cancelled.receiptId)
        assertEquals(item.name, cancelled.filename)
        assertEquals(DownloadHistoryDestination.DOCUMENT, cancelled.destination)
        assertEquals(DownloadHistoryOutcome.CANCELLED, cancelled.outcome)
        assertNotNull(cancelled.finishedAtMillis)
        assertNull(history.state.value.warning)
        assertNull(model.uiState.value.lastDownloadedMediaName)
        assertNull(model.uiState.value.error)
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())

        val restored = newHistory(directory)
        awaitHistoryLoaded(restored)
        assertEquals(cancelled, restored.state.value.entries.single())
        assertFalse(provider.destination.exists())
        assertArrayEquals(unrelatedBytes, unrelated.readBytes())
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())
    }

    private fun connectAndChooseOriginal(): CameraMediaItem {
        compose.runOnIdle {
            model = CameraViewModel(repository, downloadHistoryFactory = { history })
            viewModels.put("saf-history", model)
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
            model.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.connected && !state.busy && state.captureReviewItem != null && !state.captureReviewLoading
        }
        assertNull(model.uiState.value.error)
        // The item comes from the production camera listing, rather than an injected UI snapshot.
        return requireNotNull(model.uiState.value.captureReviewItem)
    }

    private fun newHistory(
        directory: File,
        beforeWrite: (DownloadHistoryWritePhase) -> Unit = {},
    ): DownloadHistoryStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(historyScopes::add)
        return DownloadHistoryStore(DownloadHistoryFileStorage(directory, beforeWrite), scope, Dispatchers.IO, System::currentTimeMillis)
            .also(histories::add)
    }

    private fun awaitHistoryIdle() = runBlocking {
        withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { history.awaitIdle() }
    }

    private fun awaitHistoryLoaded(store: DownloadHistoryStore) = runBlocking {
        withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { store.state.first { !it.loading } }
    }

    private class ScopedDocumentContext(base: Context, private val resolver: ContentResolver) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getContentResolver(): ContentResolver = resolver
    }

    /** No registration, global provider, system resolver, or URI-to-filesystem path mapping. */
    private class PrivateDocumentProvider(val destination: File) : ContentProvider() {
        val destinationUri: Uri = Uri.parse("content://dev.openeos.synthetic.history/document/owned")
        val openModes = CopyOnWriteArrayList<String>()
        val deletedUris = CopyOnWriteArrayList<Uri>()

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String {
            check(uri == destinationUri)
            return "image/jpeg"
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(uri == destinationUri && mode == "w")
            openModes += mode
            return ParcelFileDescriptor.open(destination,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            check(uri == destinationUri && selection == null && selectionArgs == null)
            deletedUris += uri
            return if (destination.delete()) 1 else 0
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            error("Synthetic document fixture does not support queries")
        override fun insert(uri: Uri, values: ContentValues?): Uri? =
            error("Synthetic document fixture does not create documents")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Synthetic document fixture does not update documents")
    }
}
