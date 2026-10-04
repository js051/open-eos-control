package dev.openeos.control.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives production Compose launchers, their actual saved keys, and ViewModel/repository I/O.
 * A controlled ActivityResultRegistry replaces only the external picker, not its callback.
 * StateRestorationTester recreates the composition with its saved instance state; this does
 * not claim that sensor rotation recreates MainActivity (its manifest handles orientation).
 * Every camera and file is synthetic. These tests do not validate a physical camera or SAF UI.
 */
class CameraMediaPickerSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val cameraA = CameraSessionTestSimulator("picker-A")
    private val cameraB = CameraSessionTestSimulator("picker-B")
    private val stores = mutableListOf<ViewModelStore>()
    private val models = mutableListOf<CameraViewModel>()
    private lateinit var viewModel: CameraViewModel
    private lateinit var restoration: StateRestorationTester
    private lateinit var directory: File
    private var registry = DeferredPickerRegistry()
    private val uploads = CopyOnWriteArrayList<ByteArray>()
    private val uploadedItems = CopyOnWriteArrayList<JSONObject>()

    @Before
    fun setUp() {
        directory = File(compose.activity.cacheDir, "camera-import/picker-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        for (camera in listOf(cameraA, cameraB)) {
            camera.intercept = { request ->
                val url = requireNotNull(request.requestUrl)
                when {
                    request.method == "GET" && url.queryParameter("kind") == "info" -> {
                        val index = camera.itemIds.indexOf(url.pathSegments.last()).coerceAtLeast(0)
                        camera.json(JSONObject(camera.mediaJson()).getJSONArray("items").getJSONObject(index).toString())
                    }
                    request.method == "POST" && url.encodedPath == "/ccapi/media" -> {
                        val bytes = request.body.readByteArray()
                        uploads += bytes
                        val item = JSONObject().put("id", "synthetic-upload")
                            .put("name", url.queryParameter("filename"))
                            .put("kind", "image").put("size_bytes", bytes.size)
                        uploadedItems += item
                        camera.json(item.toString())
                    }
                    request.method == "GET" && url.encodedPath == "/ccapi/media" && uploadedItems.isNotEmpty() -> {
                        val items = JSONObject(camera.mediaJson()).getJSONArray("items")
                        uploadedItems.forEach { items.put(it) }
                        camera.json(JSONObject().put("items", items).toString())
                    }
                    else -> null
                }
            }
            camera.start()
        }
        viewModel = newViewModel()
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                OpenEosControlApp(viewModel)
            }
        }
        compose.waitForIdle()
        connect(cameraA)
        openAlbum()
    }

    @After
    fun tearDown() {
        cameraA.releaseGates()
        cameraB.releaseGates()
        try {
            val jobs = models.map { requireNotNull(it.viewModelScope.coroutineContext[Job]) }
            compose.runOnIdle { stores.forEach(ViewModelStore::clear) }
            compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { jobs.all(Job::isCompleted) }
        } finally {
            try {
                cameraA.server.shutdown()
            } finally {
                cameraB.server.shutdown()
                if (::directory.isInitialized) directory.deleteRecursively()
            }
        }
    }

    @Test
    fun sameViewModelRestorationPreservesSingleDocumentRequest() {
        val item = viewModel.uiState.value.mediaItems.first()
        launchSingleDocument(item)
        val request = registry.lastRequest(ActivityResultContracts.CreateDocument::class.java)
        val destination = document("restored-original.jpg")
        restoreComposition()
        compose.runOnIdle {
            registry.deliver(request, destination)
            val state = viewModel.uiState.value
            assertTrue(
                "The restored CreateDocument callback lost its selected camera item: " +
                    "active=${state.activeMediaDownloadName}, saved=${state.lastDownloadedMediaName}, error=${state.error}",
                state.activeMediaDownloadName == item.name || state.lastDownloadedMediaName == item.name,
            )
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            viewModel.uiState.value.lastDownloadedMediaName == item.name
        }
        assertArrayEquals(cameraA.imageBytes, read(destination))
        assertEquals(listOf("/ccapi/media/${item.id}"), cameraA.originalReads.toList())
        assertTrue(cameraB.originalReads.isEmpty())
    }

    @Test
    fun processRestoredUploadCannotReplayWhenUserConnectsAnotherCameraAndReopensAlbum() {
        launchUpload()
        val request = registry.lastRequest(ActivityResultContracts.OpenDocument::class.java)
        val original = "synthetic-private-upload".toByteArray()
        val source = document("source.jpg", original)
        restoreComposition(replaceViewModel = true)
        assertFalse(viewModel.uiState.value.connected)
        compose.runOnIdle { registry.deliver(request, source) }
        val rejectionBeforeConnect = compose.runOnIdle { viewModel.uiState.value.error }
        connect(cameraB)
        openAlbum()
        if (rejectionBeforeConnect == null) {
            // On the unfixed app the conditional MediaScreen registers its restored key here
            // and actually uploads to B. Wait for that terminal evidence, not a timed sleep.
            compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
                val state = viewModel.uiState.value
                state.lastUploadedMediaName != null || state.error != null
            }
        }
        assertTrue("A restored picker must not upload into B", cameraB.mutations.none { it == "POST /ccapi/media" })
        assertTrue(uploads.isEmpty())
        assertNotNull("Explain that the user must choose the file again", rejectionBeforeConnect ?: viewModel.uiState.value.error)
        assertArrayEquals(original, read(source))
    }

    @Test
    fun sameViewModelRestorationPreservesUploadAndConsumesItsResultOnce() {
        launchUpload()
        val request = registry.lastRequest(ActivityResultContracts.OpenDocument::class.java)
        val bytes = "synthetic-original-upload".toByteArray()
        val source = document("valid-upload.jpg", bytes)
        restoreComposition()
        compose.runOnIdle { registry.deliver(request, source) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            viewModel.uiState.value.lastUploadedMediaName == "valid-upload.jpg" && !viewModel.uiState.value.busy
        }
        compose.runOnIdle { registry.deliver(request, source, requireRegistered = false) }
        compose.waitForIdle()
        assertEquals(1, uploads.size)
        assertArrayEquals(bytes, uploads.single())
        assertArrayEquals(bytes, read(source))
    }

    @Test
    fun foregroundDisconnectDisposesDeleteConfirmationBeforeReplacementAlbum() {
        val item = viewModel.uiState.value.mediaItems.first()
        openMetadata(item)
        compose.onNodeWithText(compose.activity.getString(R.string.delete_media, item.name))
            .performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.delete_media_title)).assertIsDisplayed()
        compose.runOnIdle { viewModel.disconnect() }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.delete_media_title)).assertDoesNotExist()
        connect(cameraB)
        openAlbum()
        compose.onNodeWithText(compose.activity.getString(R.string.delete_media_title)).assertDoesNotExist()
        assertTrue(cameraA.deletes.isEmpty())
        assertTrue(cameraB.deletes.isEmpty())
    }

    private fun newViewModel(): CameraViewModel = CameraViewModel().also { model ->
        stores += ViewModelStore().apply { put("picker", model) }
        models += model
    }

    private fun connect(camera: CameraSessionTestSimulator) {
        compose.waitForIdle()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !viewModel.uiState.value.busy }
        compose.runOnIdle {
            viewModel.setLiveViewAutoRefresh(false)
            viewModel.useDevSimulatorPreset()
            viewModel.setBaseUrl(camera.baseUrl)
            viewModel.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            state.connected && state.info?.model == camera.model && !state.busy && !state.captureReviewLoading
        }
    }

    private fun openAlbum() {
        compose.runOnIdle { viewModel.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.mediaLibraryLoading && state.mediaItems.isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun openMetadata(item: CameraMediaItem) {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_actions, item.name), useUnmergedTree = true)
            .performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !viewModel.uiState.value.isBusy(CameraOperation.MEDIA) }
    }

    private fun launchSingleDocument(item: CameraMediaItem) {
        openMetadata(item)
        compose.onNodeWithText(compose.activity.getString(R.string.media_save_to_folder))
            .performScrollTo().performClick()
    }

    private fun launchUpload() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.upload_media)).performClick()
    }

    private fun restoreComposition(replaceViewModel: Boolean = false) {
        val savedRegistry = Bundle()
        compose.runOnIdle {
            registry.onSaveInstanceState(savedRegistry)
            registry = DeferredPickerRegistry().apply { onRestoreInstanceState(savedRegistry) }
            if (replaceViewModel) {
                stores.last().clear()
                viewModel = newViewModel()
            }
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
    }

    private fun document(name: String, bytes: ByteArray = byteArrayOf()): Uri {
        val file = File(directory, name).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.camera_import", file)
    }

    private fun read(uri: Uri): ByteArray = requireNotNull(compose.activity.contentResolver.openInputStream(uri)).use { it.readBytes() }
}

private class DeferredPickerRegistry : ActivityResultRegistry() {
    data class Request(val code: Int, val contract: Class<*>)
    private val requests = mutableListOf<Request>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        requests += Request(requestCode, contract.javaClass)
    }

    fun lastRequest(contract: Class<*>): Request = requests.last { it.contract == contract }

    fun deliver(request: Request, uri: Uri?, requireRegistered: Boolean = true) {
        val accepted = dispatchResult(
            request.code,
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) },
        )
        // A completed request may already have been unregistered. Android can reject a
        // duplicate at that boundary; the app must not upload twice in either case.
        if (requireRegistered) assertTrue("The launched request code must be restored", accepted)
    }
}
