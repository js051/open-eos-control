package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraControlBackend
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaThumbnail
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CapabilityMatrix
import dev.openeos.control.data.ExposureState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual VM/repository callers and Android bitmap decoding, with controlled backend reads.
 * The synthetic connected state is not a camera connection or byte-identity proof. */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class CameraMediaThumbnailOwnershipTest {
    private val main = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(main) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun finalRefreshRetiresChangedNameAndDemandsReplacement() = runTest(main) {
        withFixture {
            seedCache()
            listing = listOf(replacement)
            model.refreshMedia()
            awaitListing()
            assertEquals(listOf(replacement), model.uiState.value.mediaItems)
            assertNull("THUMB_FINAL_REPLACEMENT", model.uiState.value.mediaThumbnails[original.id])
            model.loadMediaThumbnail(replacement)
            awaitThumbnail()
            assertEquals(listOf(replacement), thumbnailReads)
            assertNotSame(cached, model.uiState.value.mediaThumbnails[replacement.id])
            assertNotNull(model.uiState.value.mediaThumbnails[replacement.id])
        }
    }

    @Test fun partialRefreshRetiresChangedNameBeforeFinalPage() = runTest(main) {
        withFixture {
            seedCache()
            listing = listOf(replacement)
            partial = listing
            listRelease = CompletableDeferred()
            model.refreshMedia()
            runCurrent()
            assertTrue(model.uiState.value.mediaLibraryLoading)
            assertEquals(listOf(replacement), model.uiState.value.mediaItems)
            assertNull("THUMB_PARTIAL_REPLACEMENT", model.uiState.value.mediaThumbnails[original.id])
            listRelease!!.complete(Unit)
            awaitListing()
            assertNull(model.uiState.value.mediaThumbnails[original.id])
        }
    }

    @Test fun eventRefreshRetiresSameIdChangedVisualFamily() = runTest(main) {
        withFixture {
            val photo = original.copy(name = "SYNTHETIC.DAT")
            val video = photo.copy(kind = "video")
            state.value = state.value.copy(mediaItems = listOf(photo), mediaThumbnails = mapOf(photo.id to cached),
                mediaThumbnailLoadingIds = setOf(photo.id))
            state.value = with(model) { state.value.withEventMediaItems(listOf(video)) }
            assertEquals(listOf(video), state.value.mediaItems)
            assertNull("THUMB_EVENT_FAMILY", state.value.mediaThumbnails[photo.id])
            assertTrue(state.value.mediaThumbnailLoadingIds.isEmpty())
        }
    }

    @Test fun infoReadbackRetiresChangedName() = runTest(main) {
        withFixture {
            seedCache()
            infoReadback = replacement
            model.loadMediaInfo(original)
            awaitInfo()
            assertEquals(listOf(replacement), state.value.mediaItems)
            assertNull("THUMB_INFO_REPLACEMENT", state.value.mediaThumbnails[original.id])
            model.loadMediaThumbnail(replacement)
            awaitThumbnail()
            assertEquals(listOf(replacement), thumbnailReads)
            assertNotNull(state.value.mediaThumbnails[replacement.id])
        }
    }

    @Test fun oldTileCannotReadReplacement() = runTest(main) {
        withFixture {
            state.value = state.value.copy(mediaItems = listOf(replacement))
            model.loadMediaThumbnail(original)
            runCurrent()
            assertEquals("THUMB_STALE_ADMISSION", emptyList<CameraMediaItem>(), thumbnailReads)
            assertTrue(state.value.mediaThumbnailLoadingIds.isEmpty())
        }
    }

    @Test fun oldTileCannotCancelReplacementRead() = runTest(main) {
        withFixture {
            state.value = state.value.copy(mediaItems = listOf(replacement))
            val successor = gate(replacement)
            model.loadMediaThumbnail(replacement)
            runCurrent()
            assertTrue(successor.entered.isCompleted)
            model.cancelMediaThumbnail(original)
            runCurrent()
            assertTrue("THUMB_STALE_CANCELLATION", successor.job.isActive)
            assertEquals(setOf(replacement.id), state.value.mediaThumbnailLoadingIds)
            successor.finish.complete(Unit)
            awaitThumbnail()
            assertNotNull(state.value.mediaThumbnails[replacement.id])
        }
    }

    @Test fun changedInfoRetiresTheOldPendingThumbnailOwner() = runTest(main) {
        withFixture {
            val old = gate(original, holdCleanup = true)
            model.loadMediaThumbnail(original)
            runCurrent()
            assertTrue(old.entered.isCompleted)
            infoReadback = replacement
            model.loadMediaInfo(original)
            awaitInfo()
            assertTrue("THUMB_INFO_PENDING_OWNER", old.job.isCancelled)
            assertFalse(old.job.isCompleted)
            assertTrue(state.value.mediaThumbnailLoadingIds.isEmpty())
            old.cleanup.complete(Unit)
            old.job.join()
            assertNull(state.value.mediaThumbnails[original.id])
        }
    }

    @Test fun lateOldCleanupCannotClearReplacementLoading() = runTest(main) {
        withFixture {
            val old = gate(original, holdCleanup = true)
            val successor = gate(replacement)
            model.loadMediaThumbnail(original)
            runCurrent()
            infoReadback = replacement
            model.loadMediaInfo(original)
            awaitInfo()
            model.loadMediaThumbnail(replacement)
            runCurrent()
            assertEquals("THUMB_SUCCESSOR_ADMISSION", listOf(original, replacement), thumbnailReads)
            old.cleanup.complete(Unit)
            old.job.join()
            assertTrue(successor.job.isActive)
            assertEquals(setOf(replacement.id), state.value.mediaThumbnailLoadingIds)
            assertNull(state.value.mediaThumbnails[replacement.id])
            successor.finish.complete(Unit)
            awaitThumbnail()
            assertNotNull(state.value.mediaThumbnails[replacement.id])
            assertEquals(listOf(replacement), state.value.mediaItems)
        }
    }

    @Test fun changedReturnedVisualFamilyCannotSupplyTheThumbnail() = runTest(main) {
        withFixture {
            val photo = original.copy(name = "SYNTHETIC.DAT")
            state.value = state.value.copy(mediaItems = listOf(photo))
            returnedThumbnailItem = photo.copy(kind = "video")
            model.loadMediaThumbnail(photo)
            awaitThumbnail()
            assertEquals(listOf(photo), thumbnailReads)
            assertNull("THUMB_RETURNED_FAMILY", state.value.mediaThumbnails[photo.id])
            assertNull(state.value.error)
        }
    }

    @Test fun unchangedSignatureRetainsBitmapWithoutBackendReadAcrossRefresh() = runTest(main) {
        withFixture {
            val other = original.copy(id = "synthetic-other", name = "OTHER.JPG")
            val updated = original.copy(rating = 4, protected = true, sizeBytes = 123)
            state.value = state.value.copy(mediaItems = listOf(original, other),
                mediaThumbnails = linkedMapOf(original.id to cached, other.id to cached))
            listing = listOf(updated, other)
            model.refreshMedia()
            awaitListing()
            assertSame(cached, state.value.mediaThumbnails[original.id])
            model.loadMediaThumbnail(updated)
            assertSame(cached, state.value.mediaThumbnails[original.id])
            assertSame(cached, state.value.mediaThumbnails[other.id])
            assertEquals(setOf(other.id, original.id), state.value.mediaThumbnails.keys)
            assertTrue(thumbnailReads.isEmpty())
        }
    }

    @Test fun ordinaryInfoMetadataRetainsCacheAndPendingRead() = runTest(main) {
        withFixture {
            seedCache()
            infoReadback = original.copy(rating = 5, protected = true, archived = true)
            model.loadMediaInfo(original)
            awaitInfo()
            assertSame(cached, state.value.mediaThumbnails[original.id])
            assertEquals(5, state.value.mediaItems.single().rating)
            state.value = state.value.copy(mediaThumbnails = emptyMap())
            val read = gate(original)
            model.loadMediaThumbnail(original)
            runCurrent()
            model.loadMediaInfo(original)
            awaitInfo()
            assertTrue(read.job.isActive)
            assertEquals(setOf(original.id), state.value.mediaThumbnailLoadingIds)
            read.finish.complete(Unit)
            awaitThumbnail()
            assertNotNull(state.value.mediaThumbnails[original.id])
        }
    }

    @Test fun unknownOrConflictingReplacementCannotRetainVisualCache() = runTest(main) {
        withFixture {
            for (replacementItem in listOf(original.copy(name = "UNKNOWN.DAT", kind = "unknown"),
                original.copy(kind = "video"))) {
                seedCache()
                state.value = with(model) { state.value.withEventMediaItems(listOf(replacementItem)) }
                assertNull("THUMB_NONVISUAL_REPLACEMENT", state.value.mediaThumbnails[original.id])
                model.loadMediaThumbnail(replacementItem)
                runCurrent()
                assertTrue(thumbnailReads.isEmpty())
            }
        }
    }

    @Test fun currentCancellationRemainsRetryableAndKeepsOtherCacheEntries() = runTest(main) {
        withFixture {
            val other = original.copy(id = "other", name = "OTHER.JPG")
            state.value = state.value.copy(mediaItems = listOf(original, other), mediaThumbnails = mapOf(other.id to cached))
            val read = gate(original)
            model.loadMediaThumbnail(original)
            runCurrent()
            model.cancelMediaThumbnail(original)
            runCurrent()
            assertTrue(read.job.isCancelled)
            assertTrue(state.value.mediaThumbnailLoadingIds.isEmpty())
            assertSame(cached, state.value.mediaThumbnails[other.id])
            gates.remove(original.name)
            model.loadMediaThumbnail(original)
            awaitThumbnail()
            assertEquals(listOf(original, original), thumbnailReads)
            assertNotNull(state.value.mediaThumbnails[original.id])
            assertSame(cached, state.value.mediaThumbnails[other.id])
        }
    }

    @Test fun removedIdCannotBeRepublishedByPendingRead() = runTest(main) {
        withFixture {
            val read = gate(original, holdCleanup = true)
            model.loadMediaThumbnail(original)
            runCurrent()
            listing = emptyList()
            model.refreshMedia()
            awaitListing()
            assertTrue(read.job.isCancelled)
            read.cleanup.complete(Unit)
            read.job.join()
            assertTrue(state.value.mediaItems.isEmpty())
            assertTrue(state.value.mediaThumbnails.isEmpty())
            assertTrue(state.value.mediaThumbnailLoadingIds.isEmpty())
        }
    }

    private suspend fun TestScope.withFixture(block: suspend ThumbnailOwnershipFixture.() -> Unit) {
        val fixture = ThumbnailOwnershipFixture()
        try { fixture.block() } finally {
            fixture.listRelease?.complete(Unit)
            fixture.allGates.forEach { it.finish.complete(Unit); it.cleanup.complete(Unit) }
            fixture.model.viewModelScope.cancel()
            fixture.model.viewModelScope.coroutineContext.job.join()
            runCurrent()
        }
    }
}

private class ThumbnailOwnershipFixture {
    val repository = CameraRepository()
    val model = CameraViewModel(repository)
    val original = CameraMediaItem("synthetic-reused-id", "ORIGINAL.JPG", "image", previewAvailable = true)
    val replacement = original.copy(name = "REPLACEMENT.JPG")
    val cached: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val state: MutableStateFlow<CameraUiState>
    val thumbnailReads = mutableListOf<CameraMediaItem>()
    val gates = mutableMapOf<String, ThumbnailReadGate>()
    val allGates = mutableListOf<ThumbnailReadGate>()
    var listing = listOf(original)
    var partial: List<CameraMediaItem>? = null
    var listRelease: CompletableDeferred<Unit>? = null
    var infoReadback = original
    var returnedThumbnailItem: CameraMediaItem? = null

    init {
        val capabilities = CameraCapabilities(emptyList(), emptyList(), emptyList(), emptyList(),
            matrix = CapabilityMatrix(setOf(CameraFeature.MEDIA_BROWSER, CameraFeature.MEDIA_THUMBNAIL)))
        val info = CameraInfo(true, "Synthetic thumbnail owner", "TEST-THUMBNAIL", "synthetic/v1")
        val field = CameraViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(model) as MutableStateFlow<CameraUiState>
        state = flow
        state.value = state.value.copy(info = info,
            status = CameraStatus(true, 90, "normal", false, "photo", true, null,
                ExposureState("100", "1/100", "4", "auto")), capabilities = capabilities,
            mediaItems = listOf(original), mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE)
        val backendField = CameraRepository::class.java.getDeclaredField("backend").apply { isAccessible = true }
        val delegate = backendField.get(repository) as CameraControlBackend
        backendField.set(repository, object : CameraControlBackend by delegate {
            override suspend fun info() = info
            override suspend fun capabilities() = capabilities
            override suspend fun listMedia(maximumItems: Int?, onProgress: (List<CameraMediaItem>) -> Unit): List<CameraMediaItem> {
                partial?.let(onProgress)
                listRelease?.await()
                return listing
            }
            override suspend fun mediaInfo(item: CameraMediaItem) = infoReadback
            override suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
                thumbnailReads += item
                gates[item.name]?.read()
                return CameraMediaThumbnail(returnedThumbnailItem ?: item, validJpeg(), "image/jpeg")
            }
        })
    }

    fun seedCache() { state.value = state.value.copy(mediaItems = listOf(original), mediaThumbnails = mapOf(original.id to cached)) }
    fun gate(item: CameraMediaItem, holdCleanup: Boolean = false) = ThumbnailReadGate(holdCleanup).also {
        gates[item.name] = it; allGates += it
    }
    suspend fun awaitListing() {
        val completed = model.uiState.first { !it.mediaLibraryLoading }
        assertEquals(MediaLibraryLoadStatus.COMPLETE, completed.mediaLibraryLoadStatus)
        assertNull(completed.error)
    }
    suspend fun awaitInfo() {
        val completed = model.uiState.first { !it.isBusy(CameraOperation.MEDIA) }
        assertNull(completed.error)
    }
    suspend fun awaitThumbnail() { model.uiState.first { it.mediaThumbnailLoadingIds.isEmpty() } }

    // Same one-pixel synthetic JPEG already used by CameraFirstFrameCancellationTest.
    private fun validJpeg(): ByteArray = java.util.Base64.getDecoder().decode(VALID_JPEG_BASE64)
    companion object {
        const val VALID_JPEG_BASE64 = "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwAooooA/9k="
    }
}

private class ThumbnailReadGate(private val holdCleanup: Boolean) {
    val entered = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    val cleanup = CompletableDeferred<Unit>()
    lateinit var job: Job
        private set
    suspend fun read() {
        job = currentCoroutineContext().job
        entered.complete(Unit)
        try { finish.await() } catch (failure: CancellationException) {
            if (holdCleanup) withContext(NonCancellable) { cleanup.await() }
            throw failure
        }
    }
}
