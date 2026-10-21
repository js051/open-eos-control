package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraControlBackend
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaThumbnail
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraMediaUploadResult
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CapabilityMatrix
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.MediaUploadEvidence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InputStream

/** These eight tests and their fixture use only the pre-repair production API.
 * The baseline must fail actual hit/eviction assertions, not compilation or setup. */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class CameraMediaThumbnailRecencyTest {
    private val main = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(main) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun cacheHitPublishesTwoEntryRecency() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            model.loadMediaThumbnail(items.first())
            assertSame(cached, state.value.mediaThumbnails[items.first().id])
            assertTrue(thumbnailReads.isEmpty())
            assertEquals("LRU_HIT_ORDER", listOf(items.last().id, items.first().id), keys())
        }
    }

    @Test fun hotBitmapSurvivesNinetySeventhInsertion() = runTest(main) {
        withRecencyFixture {
            val items = seed(96)
            model.loadMediaThumbnail(items.first())
            assertTrue(thumbnailReads.isEmpty())
            val next = appendItem(96)
            model.loadMediaThumbnail(next)
            awaitThumbnail()
            assertEquals(96, state.value.mediaThumbnails.size)
            assertEquals(listOf(next), thumbnailReads)
            assertSame("LRU_HOT_RETENTION", cached, state.value.mediaThumbnails[items.first().id])
            assertFalse(state.value.mediaThumbnails.containsKey(items[1].id))
            assertNotNull(state.value.mediaThumbnails[next.id])
        }
    }

    @Test fun retainedHotBitmapDoesNotRefetchAfterInsertion() = runTest(main) {
        withRecencyFixture {
            val items = seed(96)
            model.loadMediaThumbnail(items.first())
            val next = appendItem(96)
            model.loadMediaThumbnail(next)
            awaitThumbnail()
            model.loadMediaThumbnail(items.first())
            awaitThumbnail()
            assertEquals("LRU_NO_HOT_REFETCH", listOf(next), thumbnailReads)
            assertSame(cached, state.value.mediaThumbnails[items.first().id])
            assertEquals(96, state.value.mediaThumbnails.size)
        }
    }

    @Test fun repeatedHitsEvictTheOldestUntouchedBitmap() = runTest(main) {
        withRecencyFixture {
            val items = seed(96)
            model.loadMediaThumbnail(items[0])
            model.loadMediaThumbnail(items[1])
            model.loadMediaThumbnail(items[0])
            val next = appendItem(96)
            model.loadMediaThumbnail(next)
            awaitThumbnail()
            assertEquals(96, state.value.mediaThumbnails.size)
            assertEquals("LRU_REPEATED_HIT_EVICTION",
                items.drop(3).map { it.id } + listOf(items[1].id, items[0].id, next.id), keys())
            assertFalse(state.value.mediaThumbnails.containsKey(items[2].id))
            assertEquals(listOf(next), thumbnailReads)
        }
    }

    @Test fun currentMostRecentHitIsAnUnchangedState() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            val before = state.value
            repeat(3) { model.loadMediaThumbnail(items.last()) }
            assertSame(before, state.value)
            assertEquals(items.map { it.id }, keys())
            assertTrue(thumbnailReads.isEmpty())
        }
    }

    @Test fun staleAndMissingIdentityCannotTouchTheCache() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            val before = state.value
            model.loadMediaThumbnail(items.first().copy(name = "STALE.JPG"))
            model.loadMediaThumbnail(item(1000))
            runCurrent()
            assertSame(before, state.value)
            assertEquals(items.map { it.id }, keys())
            assertTrue(thumbnailReads.isEmpty())
        }
    }

    @Test fun cancellingAnotherReadPreservesCachedOrder() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            val next = appendItem(2)
            thumbnailRelease = CompletableDeferred()
            model.loadMediaThumbnail(next)
            runCurrent()
            assertEquals(listOf(next), thumbnailReads)
            assertEquals(setOf(next.id), state.value.mediaThumbnailLoadingIds)
            model.cancelMediaThumbnail(next)
            runCurrent()
            assertTrue(state.value.mediaThumbnailLoadingIds.isEmpty())
            assertEquals(items.map { it.id }, keys())
            items.forEach { assertSame(cached, state.value.mediaThumbnails[it.id]) }
        }
    }

    @Test fun unchangedRefreshRetainsCacheAndItsOrder() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            listing = items.map { it.copy(rating = 4) }
            model.refreshMedia()
            awaitListing()
            assertEquals(items.map { it.id }, keys())
            items.forEach { assertSame(cached, state.value.mediaThumbnails[it.id]) }
            assertTrue(thumbnailReads.isEmpty())
        }
    }
}

/** Shared source-compatible fixture. Its synthetic state does not establish camera acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.withRecencyFixture(block: suspend ThumbnailRecencyFixture.() -> Unit) {
    val fixture = ThumbnailRecencyFixture()
    try { fixture.block() } finally {
        fixture.listRelease?.complete(Unit)
        fixture.thumbnailRelease?.complete(Unit)
        fixture.model.viewModelScope.cancel()
        fixture.model.viewModelScope.coroutineContext.job.join()
        runCurrent()
    }
}

internal class ThumbnailRecencyFixture {
    val repository = CameraRepository()
    val model = CameraViewModel(repository)
    val cached: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val state: MutableStateFlow<CameraUiState>
    val thumbnailReads = mutableListOf<CameraMediaItem>()
    var listing = emptyList<CameraMediaItem>()
    var partial: List<CameraMediaItem>? = null
    var listRelease: CompletableDeferred<Unit>? = null
    var thumbnailRelease: CompletableDeferred<Unit>? = null
    var infoReadback: CameraMediaItem? = null
    var uploadCalls = 0

    init {
        val capabilities = CameraCapabilities(emptyList(), emptyList(), emptyList(), emptyList(),
            matrix = CapabilityMatrix(setOf(CameraFeature.MEDIA_BROWSER, CameraFeature.MEDIA_THUMBNAIL,
                CameraFeature.MEDIA_DELETE, CameraFeature.MEDIA_UPLOAD)))
        val info = CameraInfo(true, "Synthetic recency fixture", "TEST-RECENCY", "synthetic/v1")
        val field = CameraViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(model) as MutableStateFlow<CameraUiState>
        state = flow
        state.value = state.value.copy(info = info,
            status = CameraStatus(true, 90, "normal", false, "photo", true, null,
                ExposureState("100", "1/100", "4", "auto")), capabilities = capabilities,
            mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE)
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
            override suspend fun mediaInfo(item: CameraMediaItem) = infoReadback ?: item
            override suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
                thumbnailReads += item
                thumbnailRelease?.await()
                return CameraMediaThumbnail(item, validJpeg(), "image/jpeg")
            }
            override suspend fun uploadMedia(name: String, sizeBytes: Long, contentType: String?, source: InputStream,
                evidence: MediaUploadEvidence, onProgress: (CameraMediaTransferProgress) -> Unit): CameraMediaUploadResult {
                evidence.beforeDispatch()
                uploadCalls += 1
                val bytes = source.readBytes()
                check(bytes.size.toLong() == sizeBytes)
                val uploaded = CameraMediaItem("synthetic-upload", name, "image", sizeBytes = sizeBytes)
                val result = CameraMediaUploadResult(uploaded, sizeBytes)
                evidence.onConfirmed(result)
                listing = listOf(uploaded)
                return result
            }
        })
    }

    fun item(index: Int) = CameraMediaItem("synthetic-$index", "SYNTHETIC_$index.JPG", "image", previewAvailable = true)
    fun seed(count: Int): List<CameraMediaItem> {
        require(count in 1..96)
        val items = (0 until count).map(::item)
        listing = items
        state.value = state.value.copy(mediaItems = items,
            mediaThumbnails = items.associateTo(linkedMapOf()) { it.id to cached })
        // Baseline-compatible production call canonicalizes candidate-only state metadata.
        // It must not change the seeded order or issue any backend read.
        model.loadMediaThumbnail(items.last())
        check(keys() == items.map { it.id } && thumbnailReads.isEmpty())
        return items
    }
    fun appendItem(index: Int): CameraMediaItem = item(index).also {
        listing = listing + it
        state.value = state.value.copy(mediaItems = listing)
    }
    fun keys(): List<String> = state.value.mediaThumbnails.keys.toList()
    suspend fun awaitListing() {
        val completed = model.uiState.first { !it.mediaLibraryLoading }
        assertEquals(MediaLibraryLoadStatus.COMPLETE, completed.mediaLibraryLoadStatus)
        assertNull(completed.error)
    }
    suspend fun awaitMediaOperation() {
        val completed = model.uiState.first { !it.isBusy(CameraOperation.MEDIA) }
        assertNull(completed.error)
    }
    suspend fun awaitThumbnail() { model.uiState.first { it.mediaThumbnailLoadingIds.isEmpty() } }

    // Exact synthetic one-pixel JPEG already used by the unchanged ownership/first-frame suites.
    fun validJpeg(): ByteArray = java.util.Base64.getDecoder().decode(VALID_JPEG_BASE64)
    companion object { const val VALID_JPEG_BASE64 = "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwAooooA/9k=" }
}
