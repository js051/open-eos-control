package dev.openeos.control.ui

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.MediaUploadStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real ViewModel/repository/HTTP cancellation. Only source provider and cameras are synthetic. */
@SdkSuppress(minSdkVersion = 29)
class CameraUploadOutcomeJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val cameraA = CameraSessionTestSimulator("upload-A")
    private val cameraB = CameraSessionTestSimulator("upload-B")
    private val store = ViewModelStore()
    private val repository = CameraRepository()
    private lateinit var viewModel: CameraViewModel
    private val sources = mutableListOf<UploadSource>()
    private val posts = AtomicInteger()
    private var currentName = "SAME.JPG"
    private var currentSize = 12L

    @Before fun setUp() {
        cameraA.start(); cameraB.start()
        cameraA.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/media") collisionList()
            else null
        }
        compose.runOnIdle {
            viewModel = CameraViewModel(repository); store.put("upload-outcome", viewModel)
            viewModel.useDevSimulatorPreset(); viewModel.setLiveViewAutoRefresh(false)
            viewModel.setBaseUrl(cameraA.baseUrl); viewModel.connect()
        }
        awaitCamera(cameraA)
    }
    @After fun tearDown() {
        sources.forEach { it.release.countDown() }
        cameraA.releaseGates(); cameraB.releaseGates()
        if (::viewModel.isInitialized) {
            val job = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
            compose.runOnIdle { store.clear() }
            compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { job.isCompleted }
        }
        runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { repository.disconnect() } }
        sources.forEach { it.shutdown(); it.file.delete() }
        cameraA.server.shutdown(); cameraB.server.shutdown()
    }
    @Test fun cancelDuringStagingCannotTurnOldNameSizeOrUnknownSizeIntoSuccess() {
        for ((name, size) in listOf("SAME.JPG" to 12L, "same.jpg" to null)) {
            currentName = "SAME.JPG"; currentSize = 12
            val source = source(name, size, blocked = true)
            compose.runOnIdle { viewModel.uploadMedia(context(source), source.uri) }
            assertTrue(source.opened.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val readsBeforeCancel = cameraA.mediaReads.get()
            compose.runOnIdle { viewModel.cancelMediaUpload() }
            source.release.countDown()
            awaitFinished()
            assertEquals(MediaUploadStatus.NOT_SENT, viewModel.uiState.value.mediaUploadStatus)
            assertNull(viewModel.uiState.value.lastUploadedMediaName)
            assertEquals(readsBeforeCancel, cameraA.mediaReads.get())
            assertTrue(cameraA.mutations.none { it == "POST /ccapi/media" })
            assertArrayEquals(ByteArray(12) { 7 }, source.file.readBytes())
        }
    }
    @Test fun emptyOwnedPickerSourcePublishesLocalizableAppFailureWithoutSending() {
        val source = source("EMPTY.JPG", 0L)
        source.file.writeBytes(byteArrayOf())
        compose.runOnIdle {
            val id = requireNotNull(viewModel.beginMediaPicker(CameraMediaPickerKind.UPLOAD))
            viewModel.completeMediaPicker(context(source), CameraMediaPickerKind.UPLOAD, id, source.uri)
        }
        awaitFinished()
        assertEquals(MediaUploadStatus.NOT_SENT, viewModel.uiState.value.mediaUploadStatus)
        assertEquals(CameraAppError.UPLOAD_SIZE_INVALID.messageToken, viewModel.uiState.value.error)
        assertEquals(CameraAppError.UPLOAD_SIZE_INVALID.messageResource,
            cameraAppErrorResource(requireNotNull(viewModel.uiState.value.error)))
        assertTrue(cameraA.mutations.none { it == "POST /ccapi/media" })
        assertNull(viewModel.uiState.value.lastUploadedMediaName)
    }
    @Test fun cancelledDispatchedUploadStaysUnknownAfterFreshMatchingInventory() {
        val gate = cameraA.gate()
        cameraA.intercept = { request -> when {
            request.method == "POST" && request.requestUrl?.encodedPath == "/ccapi/media" -> {
                posts.incrementAndGet(); gate.blockResponse(); receipt()
            }
            request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/media" -> collisionList()
            else -> null
        } }
        val source = source("SAME.JPG", null)
        compose.runOnIdle { viewModel.uploadMedia(context(source), source.uri) }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.runOnIdle { viewModel.cancelMediaUpload() }
        awaitFinished()
        assertEquals(MediaUploadStatus.UNCERTAIN, viewModel.uiState.value.mediaUploadStatus)
        gate.release()
        compose.runOnIdle { viewModel.refreshMedia() }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !viewModel.uiState.value.mediaLibraryLoading }
        assertEquals(MediaUploadStatus.UNCERTAIN, viewModel.uiState.value.mediaUploadStatus)
        assertNull(viewModel.uiState.value.lastUploadedMediaName)
        assertEquals(1, posts.get())
        assertTrue(cameraA.deletes.isEmpty())
    }
    @Test fun confirmedReceiptSurvivesRefreshFailureAndLateCancel() {
        cameraA.intercept = { request -> when {
            request.method == "POST" && request.requestUrl?.encodedPath == "/ccapi/media" -> {
                posts.incrementAndGet(); receipt()
            }
            request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/media" -> MockResponse().setResponseCode(500)
            else -> null
        } }
        val source = source("SAME.JPG", 12)
        compose.runOnIdle { viewModel.uploadMedia(context(source), source.uri) }
        awaitFinished()
        compose.runOnIdle { viewModel.cancelMediaUpload() }
        assertEquals(MediaUploadStatus.CONFIRMED, viewModel.uiState.value.mediaUploadStatus)
        assertEquals("SAME.JPG", viewModel.uiState.value.lastUploadedMediaName)
        assertTrue(viewModel.uiState.value.mediaUploadRefreshFailed)
        assertEquals(1, posts.get())
    }
    @Test fun reconnectDuringStagingCannotReadIntoOrClearTheReplacementOwner() {
        val source = source("SAME.JPG", 12, blocked = true)
        compose.runOnIdle { viewModel.uploadMedia(context(source), source.uri) }
        assertTrue(source.opened.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.runOnIdle { viewModel.disconnect(); viewModel.setBaseUrl(cameraB.baseUrl); viewModel.connect() }
        source.release.countDown()
        awaitCamera(cameraB)
        assertNull(viewModel.uiState.value.mediaUploadStatus)
        assertNull(viewModel.uiState.value.activeMediaUploadName)
        assertNull(viewModel.uiState.value.lastUploadedMediaName)
        assertTrue(cameraA.mutations.none { it == "POST /ccapi/media" })
        assertTrue(cameraB.mutations.none { it == "POST /ccapi/media" })
    }
    @Test fun reconnectDuringPostRejectsOldReceiptAndFinally() {
        val gate = cameraA.gate()
        cameraA.intercept = { request ->
            if (request.method == "POST" && request.requestUrl?.encodedPath == "/ccapi/media") {
                posts.incrementAndGet(); gate.blockResponse(); receipt()
            } else null
        }
        val source = source("SAME.JPG", 12)
        compose.runOnIdle { viewModel.uploadMedia(context(source), source.uri) }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.runOnIdle { viewModel.disconnect(); viewModel.setBaseUrl(cameraB.baseUrl); viewModel.connect() }
        gate.release(); awaitCamera(cameraB)
        assertNull(viewModel.uiState.value.mediaUploadStatus)
        assertNull(viewModel.uiState.value.lastUploadedMediaName)
        assertTrue(cameraB.mutations.none { it == "POST /ccapi/media" })
    }
    private fun collisionList(): MockResponse = cameraA.json("""{"items":[{"id":"old-card-item","name":"$currentName","kind":"image","size_bytes":$currentSize}]}""")
    private fun receipt(): MockResponse = cameraA.json("""{"id":"same-operation-receipt","name":"SAME.JPG","kind":"image","size_bytes":12}""").setResponseCode(201)
    private fun awaitFinished() = compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
        !viewModel.uiState.value.isBusy(CameraOperation.MEDIA) && viewModel.uiState.value.mediaUploadStatus in
            setOf(MediaUploadStatus.CONFIRMED, MediaUploadStatus.NOT_SENT, MediaUploadStatus.UNCERTAIN)
    }
    private fun awaitCamera(camera: CameraSessionTestSimulator) = compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
        viewModel.uiState.value.info?.model == camera.model && !viewModel.uiState.value.isBusy(CameraOperation.CONNECT)
    }
    private fun source(name: String, size: Long?, blocked: Boolean = false): UploadSource {
        val file = File(compose.activity.cacheDir, "synthetic-upload-${UUID.randomUUID()}.tmp").apply { writeBytes(ByteArray(12) { 7 }) }
        return UploadSource(file, name, size, blocked).also {
            it.attachInfo(compose.activity, ProviderInfo().apply { authority = "synthetic.upload"; exported = false })
            sources += it
        }
    }
    private fun context(source: UploadSource): Context {
        val resolver = ContentResolver.wrap(source)
        return object : ContextWrapper(compose.activity.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
    }
    private class UploadSource(val file: File, val name: String, val size: Long?, blocked: Boolean) : ContentProvider() {
        val uri: Uri = Uri.parse("content://synthetic.upload/source")
        val opened = CountDownLatch(1)
        val release = CountDownLatch(if (blocked) 1 else 0)
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(name, size)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r"); opened.countDown()
            check(release.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Source is read-only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Source is read-only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Source is read-only")
    }
}
