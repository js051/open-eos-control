package dev.openeos.control.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.R
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
import dev.openeos.control.saf.SyntheticDocumentsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Production App -> ActivityResult launcher -> real DocumentsUI -> cross-UID URI grant ->
 * HTTP -> real DocumentsProvider. These tests never inject an ActivityResult, wrap a resolver,
 * grant shell permissions, persist a grant, or replace StrictMode. All documents are synthetic.
 */
@SdkSuppress(minSdkVersion = 29)
class CameraSafOsJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val runId = "saf-${UUID.randomUUID()}"
    private val camera = CameraSessionTestSimulator(runId)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val picker = DocumentsUiDriver()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(client, CameraNetworkDiagnostics.Empty)
        },
    ))
    private val models = ViewModelStore()
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val singleDocuments = mutableSetOf<Uri>()
    private val grantedTrees = mutableSetOf<Uri>()
    private lateinit var authority: String
    private lateinit var historyDirectory: File
    private lateinit var history: DownloadHistoryStore
    private lateinit var model: CameraViewModel
    private var originalBytes = camera.imageBytes
    private var originalResponse: () -> MockResponse = { completeOriginalResponse() }
    private val resolver get() = compose.activity.contentResolver

    @Before fun setUp() {
        // Read only this already-loaded test APK's manifest. An installed-provider lookup here
        // could be filtered by package visibility before the OS grants the selected URI.
        val testApplication = instrumentation.context.applicationInfo
        val archive = requireNotNull(instrumentation.context.packageManager.getPackageArchiveInfo(
            testApplication.sourceDir, PackageManager.GET_PROVIDERS,
        ))
        val provider = requireNotNull(archive.providers).single { it.name == SyntheticDocumentsProvider::class.java.name }
        authority = provider.authority
        assertEquals(instrumentation.context.packageName, provider.packageName)
        assertNotEquals("A remote process under the app UID is not SAF permission evidence",
            compose.activity.applicationInfo.uid, testApplication.uid)
        assertEquals("android.permission.MANAGE_DOCUMENTS", provider.readPermission)
        assertEquals("android.permission.MANAGE_DOCUMENTS", provider.writePermission)
        assertTrue(provider.exported && provider.grantUriPermissions)
        assertEquals(PackageManager.PERMISSION_DENIED,
            compose.activity.checkSelfPermission("android.permission.MANAGE_DOCUMENTS"))
        assertNoPersistentGrant()
        historyDirectory = File(compose.activity.cacheDir, "$runId-history").apply { check(mkdirs()) }
        history = DownloadHistoryStore(DownloadHistoryFileStorage(historyDirectory), historyScope,
            Dispatchers.IO, System::currentTimeMillis)
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            when {
                request.method == "GET" && url.encodedPath == "/ccapi/media" -> camera.json(mediaListing().toString())
                request.method == "GET" && url.queryParameter("kind") == "info" -> {
                    val items = mediaListing().getJSONArray("items")
                    val index = camera.itemIds.indexOf(url.pathSegments.last()).coerceAtLeast(0)
                    camera.json(items.getJSONObject(index).toString())
                }
                request.method == "GET" && url.encodedPath.startsWith("/ccapi/media/") && url.query == null -> originalResponse()
                else -> null
            }
        }
        // MockWebServer.url resolves host information here, never in setContent/runOnMainSync.
        camera.start()
    }

    @After fun tearDown() {
        camera.releaseGates()
        try {
            picker.dismissIfOpen()
            if (::model.isInitialized) {
                val job = requireNotNull(model.viewModelScope.coroutineContext[Job])
                instrumentation.runOnMainSync { models.clear() }
                runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { job.join() } }
            }
            runBlocking {
                withTimeout(SESSION_TEST_TIMEOUT_MILLIS) {
                    repository.disconnect()
                    if (::history.isInitialized) history.awaitIdle()
                    historyScope.cancel()
                    historyScope.coroutineContext[Job]?.join()
                }
            }
        } finally {
            try {
                // Only URIs belonging to this random test run, acquired through its OS picker.
                singleDocuments.forEach { uri ->
                    if (hasGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                        DocumentsContract.deleteDocument(resolver, uri)
                    }
                }
                grantedTrees.forEach { tree ->
                    if (hasGrant(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                        snapshot(tree).files.forEach { document ->
                            val uri = documentUri(tree, document.id)
                            if (DocumentsContract.getTreeDocumentId(tree).startsWith(SyntheticDocumentsProvider.REFUSING_ROOT + "/")) {
                                // Explicit test cleanup after assertions; never part of the product path.
                                resolver.openOutputStream(uri, "wt")!!.close()
                            }
                            DocumentsContract.deleteDocument(resolver, uri)
                        }
                        DocumentsContract.deleteDocument(resolver,
                            documentUri(tree, DocumentsContract.getTreeDocumentId(tree)))
                    }
                }
                assertNoPersistentGrant()
            } finally {
                client.dispatcher.cancelAll()
                try { camera.server.shutdown() } finally {
                    client.connectionPool.evictAll()
                    historyScope.cancel()
                    if (::historyDirectory.isInitialized) historyDirectory.deleteRecursively()
                }
            }
        }
    }

    @Test fun createDocumentBackThenChooseAgainSavesOriginalAndCompletedReceipt() {
        installAppAndOpenAlbum()
        val item = model.uiState.value.mediaItems.first()
        val destination = DocumentsContract.buildDocumentUri(authority,
            "${SyntheticDocumentsProvider.NORMAL_ROOT}/${item.name}")
        assertNoGrant(destination)
        launchSingleDocument(item)
        picker.awaitPicker()
        assertWaitingForDestination(item)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaSaveFeedback[item.id] == MediaSaveFeedback.Cancelled
        }
        assertTrue(camera.originalReads.isEmpty())
        assertTrue(history.state.value.entries.isEmpty())
        assertNoGrant(destination)

        singleDocuments += destination
        launchSingleDocument(item)
        picker.saveDocument(SyntheticDocumentsProvider.NORMAL_TITLE)
        awaitTransferFinished(item)
        assertGranted(destination)
        assertArrayEquals(originalBytes, read(destination))
        assertTrue(model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saved)
        assertReceipt(item, DownloadHistoryDestination.DOCUMENT, DownloadHistoryOutcome.COMPLETED)
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())
        // Even after selection, the app has only the selected file, not the unrelated root file.
        assertNoGrant(DocumentsContract.buildDocumentUri(authority,
            "${SyntheticDocumentsProvider.NORMAL_ROOT}/${SyntheticDocumentsProvider.SENTINEL}"))
    }

    @Test fun treePickerSavesExactBatchBytesAndCompletedReceipts() {
        installAppAndOpenAlbum()
        val items = model.uiState.value.mediaItems
        val tree = launchTree(items)
        awaitTransferFinished(items.last())
        assertGranted(tree)
        val saved = snapshot(tree)
        assertEquals(items.map { it.name }.sorted(), saved.files.map { it.name }.sorted())
        saved.files.forEach { assertArrayEquals(originalBytes, read(documentUri(tree, it.id))) }
        items.forEach { assertReceipt(it, DownloadHistoryDestination.FOLDER, DownloadHistoryOutcome.COMPLETED) }
        assertEquals(items.map { "/ccapi/media/${it.id}" }, camera.originalReads.toList())
        assertEquals(2, saved.created.size)
        assertTrue(saved.deleted.isEmpty())
        assertSentinel(tree)
    }

    @Test fun cancellingAfterProviderHasBytesDeletesOnlyTheNewDocument() {
        originalBytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        originalResponse = { completeOriginalResponse().throttleBody(32 * 1024, 2, TimeUnit.SECONDS) }
        installAppAndOpenAlbum()
        val item = model.uiState.value.mediaItems.first()
        val tree = launchTree(listOf(item))
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { snapshot(tree).files.singleOrNull()?.size?.let { it > 0 } == true }
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, history.state.value.entries.single().outcome)
        assertSentinel(tree)
        compose.onNodeWithContentDescription(text(R.string.cancel_media_download)).performClick()
        compose.waitUntil(5_000) {
            model.uiState.value.mediaSaveFeedback[item.id] == MediaSaveFeedback.Cancelled &&
                !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        val cleaned = snapshot(tree)
        assertTrue(cleaned.files.isEmpty())
        assertEquals(1, cleaned.created.size)
        assertEquals(cleaned.created, cleaned.deleted)
        assertTrue(cleaned.refused.isEmpty())
        assertSentinel(tree)
        assertReceipt(item, DownloadHistoryDestination.FOLDER, DownloadHistoryOutcome.CANCELLED)
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())
    }

    @Test fun truncatedTransferRemovesEveryPartialAndPreservesTheUnrelatedSentinel() {
        useTruncatedOriginal()
        installAppAndOpenAlbum()
        val item = model.uiState.value.mediaItems.first()
        val tree = launchTree(listOf(item))
        awaitTransferFinished(item)
        val cleaned = snapshot(tree)
        val attempts = MEDIA_READ_RETRY_DELAYS_MILLIS.size + 1
        assertEquals(attempts, camera.originalReads.size)
        assertEquals(attempts, cleaned.created.size)
        assertEquals(cleaned.created, cleaned.deleted)
        assertTrue(cleaned.files.isEmpty())
        assertTrue(cleaned.refused.isEmpty())
        assertTrue(model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Failed)
        assertReceipt(item, DownloadHistoryDestination.FOLDER, DownloadHistoryOutcome.FAILED)
        assertSentinel(tree)
    }

    @Test fun cancelledRefusedCleanupWarnsOnlyForTheStartedOriginal() {
        originalBytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        originalResponse = { completeOriginalResponse().throttleBody(32 * 1024, 2, TimeUnit.SECONDS) }
        installAppAndOpenAlbum()
        val items = model.uiState.value.mediaItems
        val tree = launchTree(items, refusing = true)
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { snapshot(tree).files.singleOrNull()?.size?.let { it > 0 } == true }
        assertEquals(MediaSaveFeedback.Queued, model.uiState.value.mediaSaveFeedback[items.last().id])
        compose.onNodeWithContentDescription(text(R.string.cancel_media_download)).performClick()
        awaitTransferFinished(items.last())

        assertEquals(MediaSaveFeedback.IncompleteFile(cancelled = true), model.uiState.value.mediaSaveFeedback[items.first().id])
        assertEquals(MediaSaveFeedback.Cancelled, model.uiState.value.mediaSaveFeedback[items.last().id])
        val residual = snapshot(tree)
        assertEquals(1, residual.files.size)
        assertEquals(1, residual.created.size)
        assertEquals(residual.created, residual.refused)
        assertTrue(residual.deleted.isEmpty())
        assertEquals(listOf("/ccapi/media/${items.first().id}"), camera.originalReads.toList())
        assertEquals(1, history.state.value.entries.size)
        assertReceipt(items.first(), DownloadHistoryDestination.FOLDER, DownloadHistoryOutcome.CANCELLED,
            cleanupUnconfirmed = true)
        assertSentinel(tree)
    }

    @Test fun refusedCleanupShowsResidualWarningWithoutCreatingAnotherPartial() {
        useTruncatedOriginal()
        installAppAndOpenAlbum()
        val item = model.uiState.value.mediaItems.first()
        val tree = launchTree(listOf(item), refusing = true)
        awaitTransferFinished(item)
        assertEquals(MediaSaveFeedback.IncompleteFile(cancelled = false), model.uiState.value.mediaSaveFeedback[item.id])
        compose.onNodeWithTag("media-save-cleanup-unconfirmed").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_save_cleanup_unconfirmed)).assertIsDisplayed()
        val residual = snapshot(tree)
        assertEquals(1, residual.files.size)
        assertTrue(residual.files.single().size in 1 until originalBytes.size.toLong())
        assertEquals(1, residual.created.size)
        assertEquals(residual.created, residual.refused)
        assertTrue(residual.deleted.isEmpty())
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())
        assertReceipt(item, DownloadHistoryDestination.FOLDER, DownloadHistoryOutcome.FAILED, cleanupUnconfirmed = true)
        assertSentinel(tree)
        val receipt = history.state.value.entries.single()
        compose.onNodeWithTag("download-history-open").performClick()
        compose.onNodeWithTag("download-history-cleanup-${receipt.receiptId}").assertIsDisplayed()
        val restored = DownloadHistoryStore(DownloadHistoryFileStorage(historyDirectory), historyScope,
            Dispatchers.IO, System::currentTimeMillis)
        runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { restored.state.first { !it.loading } } }
        assertEquals(receipt, restored.state.value.entries.single())
        assertTrue(restored.state.value.entries.single().cleanupUnconfirmed)
    }

    private fun installAppAndOpenAlbum() {
        model = CameraViewModel(repository, downloadHistoryFactory = { history })
        models.put("real-saf-journey", model)
        compose.setContent { OpenEosControlApp(model) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.downloadHistoryState.value.loading }
        compose.runOnIdle {
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
            model.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.connected && !model.uiState.value.busy && !model.uiState.value.captureReviewLoading
        }
        compose.runOnIdle { model.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            !model.uiState.value.mediaLibraryLoading && model.uiState.value.mediaItems.size == 2
        }
        assertNull(model.uiState.value.error)
    }

    private fun launchSingleDocument(item: CameraMediaItem) {
        compose.onNodeWithContentDescription(text(R.string.media_actions, item.name), useUnmergedTree = true)
            .performScrollTo().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.isBusy(CameraOperation.MEDIA) }
        compose.onNodeWithText(text(R.string.media_save_to_folder)).performScrollTo().performClick()
    }

    private fun launchTree(items: List<CameraMediaItem>, refusing: Boolean = false): Uri {
        val root = if (refusing) SyntheticDocumentsProvider.REFUSING_ROOT else SyntheticDocumentsProvider.NORMAL_ROOT
        val title = if (refusing) SyntheticDocumentsProvider.REFUSING_TITLE else SyntheticDocumentsProvider.NORMAL_TITLE
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "$root/$runId")
        assertNoGrant(tree)
        compose.onNodeWithContentDescription(text(R.string.preview_media, items.first().name))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        if (items.size > 1) compose.onNodeWithContentDescription(text(R.string.select_all_media)).performClick()
        compose.onNodeWithContentDescription(text(R.string.edit_selected_media, items.size)).performClick()
        compose.onNodeWithText(text(R.string.media_save_to_folder)).performScrollTo().performClick()
        picker.awaitPicker()
        items.forEach(::assertWaitingForDestination)
        // Create a random subfolder IN the OS picker. No prior run can have its URI grant.
        picker.selectNewTree(title, runId) { assertNoGrant(tree) }
        grantedTrees += tree
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { hasGrant(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        assertGranted(tree)
        return tree
    }

    private fun assertWaitingForDestination(item: CameraMediaItem) {
        assertEquals(MediaSaveFeedback.SelectingDestination, model.uiState.value.mediaSaveFeedback[item.id])
        assertTrue(camera.originalReads.isEmpty())
        assertTrue(history.state.value.entries.isEmpty())
    }

    private fun awaitTransferFinished(item: CameraMediaItem) {
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.mediaSaveFeedback[item.id]?.isPending == false && !state.isBusy(CameraOperation.MEDIA)
        }
        runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { history.awaitIdle() } }
    }

    private fun assertReceipt(
        item: CameraMediaItem,
        destination: DownloadHistoryDestination,
        outcome: DownloadHistoryOutcome,
        cleanupUnconfirmed: Boolean = false,
    ) {
        runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { history.awaitIdle() } }
        val receipt = history.state.value.entries.single { it.filename == item.name }
        assertEquals(destination, receipt.destination)
        assertEquals(outcome, receipt.outcome)
        assertEquals(cleanupUnconfirmed, receipt.cleanupUnconfirmed)
        assertTrue(receipt.finishedAtMillis != null)
        assertNull(history.state.value.warning)
    }

    private fun mediaListing(): JSONObject = JSONObject(camera.mediaJson()).apply {
        val items = getJSONArray("items")
        repeat(items.length()) { items.getJSONObject(it).put("size_bytes", originalBytes.size) }
    }

    private fun completeOriginalResponse() = MockResponse().setHeader("Content-Type", "image/jpeg")
        .setBody(Buffer().write(originalBytes))

    private fun useTruncatedOriginal() {
        originalBytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        originalResponse = { completeOriginalResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
    }

    private data class SavedDocument(val id: String, val name: String, val size: Long)
    private data class TreeSnapshot(val files: List<SavedDocument>, val created: List<String>, val deleted: List<String>, val refused: List<String>)

    private fun snapshot(tree: Uri): TreeSnapshot {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE),
            null, null, null)!!.use { cursor ->
            val files = buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) != SyntheticDocumentsProvider.SENTINEL) {
                        add(SavedDocument(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                    }
                }
            }
            TreeSnapshot(files, cursor.extras.getStringArrayList(SyntheticDocumentsProvider.CREATED_IDS).orEmpty(),
                cursor.extras.getStringArrayList(SyntheticDocumentsProvider.DELETED_IDS).orEmpty(),
                cursor.extras.getStringArrayList(SyntheticDocumentsProvider.REFUSED_IDS).orEmpty())
        }
    }

    private fun assertSentinel(tree: Uri) {
        assertArrayEquals(SyntheticDocumentsProvider.sentinelBytes(), read(documentUri(tree,
            DocumentsContract.getTreeDocumentId(tree) + "/" + SyntheticDocumentsProvider.SENTINEL)))
    }

    private fun documentUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun read(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private fun hasGrant(uri: Uri, mode: Int): Boolean = compose.activity.checkUriPermission(uri,
        Process.myPid(), compose.activity.applicationInfo.uid, mode) == PackageManager.PERMISSION_GRANTED
    private fun assertNoGrant(uri: Uri) {
        assertFalse("Unexpected preexisting read grant: $uri", hasGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertFalse("Unexpected preexisting write grant: $uri", hasGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
    private fun assertGranted(uri: Uri) {
        assertTrue("Missing OS read grant: $uri", hasGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertTrue("Missing OS write grant: $uri", hasGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        val queryUri = if (DocumentsContract.isTreeUri(uri)) {
            documentUri(uri, DocumentsContract.getTreeDocumentId(uri))
        } else uri
        resolver.query(queryUri, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val providerUid = cursor.extras.getInt(SyntheticDocumentsProvider.PROVIDER_UID, -1)
            val callerUid = cursor.extras.getInt(SyntheticDocumentsProvider.CALLER_UID, -1)
            assertEquals("Provider must run under the separately installed test APK UID",
                instrumentation.context.applicationInfo.uid, providerUid)
            assertNotEquals("The successful URI query must cross a real UID boundary",
                compose.activity.applicationInfo.uid, providerUid)
            assertEquals("The Binder query must originate in the target app, without shell identity",
                compose.activity.applicationInfo.uid, callerUid)
        }
        assertNoPersistentGrant()
    }
    private fun assertNoPersistentGrant() {
        if (::authority.isInitialized) assertTrue(resolver.persistedUriPermissions.none { it.uri.authority == authority })
    }
}

/** Bounded navigation of the platform picker with APIs already supplied by Instrumentation. */
@Suppress("DEPRECATION")
private class DocumentsUiDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    fun awaitPicker() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        awaitNode("DocumentsUI window") { it.packageName?.toString()?.contains("documentsui") == true }.recycle()
    }

    fun saveDocument(rootTitle: String) {
        chooseRoot(rootTitle)
        click("Save document") { it.isEnabled && it.text?.toString()?.equals("Save", ignoreCase = true) == true }
    }

    fun selectNewTree(rootTitle: String, folderName: String, beforeGrant: () -> Unit) {
        chooseRoot(rootTitle)
        // The platform exposes this in the toolbar or its overflow menu depending on width.
        val create = find { it.isVisibleToUser && (it.viewIdResourceName?.endsWith(":id/option_menu_create_dir") == true ||
            it.contentDescription?.toString() in listOf("New folder", "Create folder")) }
        if (create != null) {
            clickNode(create)
        } else {
            click("DocumentsUI overflow menu") { it.contentDescription?.toString() == "More options" }
            click("New folder menu item") { it.text?.toString() in listOf("New folder", "Create folder") }
        }
        val input = awaitNode("New folder name field") { it.className?.toString() == "android.widget.EditText" }
        try {
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, folderName)
            }))
        } finally { input.recycle() }
        click("Create synthetic folder") { it.viewIdResourceName == "android:id/button1" && it.isEnabled }
        awaitNode("New synthetic folder breadcrumb") { it.text?.toString() == folderName }.recycle()
        beforeGrant()
        click("Use this folder") { it.isEnabled && it.text?.toString()?.equals("Use this folder", ignoreCase = true) == true }
        click("Allow access to synthetic folder") { it.isEnabled && it.viewIdResourceName == "android:id/button1" &&
            it.text?.toString()?.equals("Allow", ignoreCase = true) == true }
    }

    fun dismissIfOpen() {
        repeat(3) {
            val root = automation.rootInActiveWindow ?: return
            val isPicker = root.packageName?.toString()?.contains("documentsui") == true
            root.recycle()
            if (!isPicker) return
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(100)
        }
    }

    private fun chooseRoot(title: String) {
        awaitPicker()
        // Always navigate through the root drawer, regardless of DocumentsUI's last location.
        val drawer = find { it.contentDescription?.toString() in listOf("Show roots", "Open navigation drawer") }
        if (drawer != null) clickNode(drawer)
        click("Synthetic provider root '$title'") { it.text?.toString() == title && it.isVisibleToUser }
        awaitNode("Synthetic root contents") { it.text?.toString() == SyntheticDocumentsProvider.SENTINEL }.recycle()
    }

    private fun click(description: String, predicate: (AccessibilityNodeInfo) -> Boolean) = clickNode(awaitNode(description, predicate))

    private fun clickNode(node: AccessibilityNodeInfo) {
        var current = node
        try {
            repeat(5) {
                if (current.isClickable && current.isEnabled) {
                    assertTrue("Platform picker node rejected click", current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    return
                }
                val parent = current.parent ?: error("Platform picker node has no clickable ancestor")
                current.recycle()
                current = parent
            }
            error("Platform picker node has no clickable ancestor within five levels")
        } finally { current.recycle() }
    }

    private fun awaitNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + SESSION_TEST_TIMEOUT_MILLIS
        do {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out waiting for $description. Platform picker nodes: ${describeWindow()}")
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            try {
                if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
                repeat(node.childCount) { index -> node.getChild(index)?.let { child -> visit(child)?.let { return it } } }
                return null
            } finally { node.recycle() }
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun describeWindow(): String {
        val nodes = mutableListOf<String>()
        find { node ->
            if (nodes.size < 50 && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())) {
                nodes += "${node.viewIdResourceName}: ${node.text ?: node.contentDescription}"
            }
            false
        }
        return nodes.joinToString(" | ")
    }
}
