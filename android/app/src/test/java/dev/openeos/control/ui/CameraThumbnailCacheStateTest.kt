package dev.openeos.control.ui

import android.net.Uri
import dev.openeos.control.data.MediaUploadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Candidate-only invariants for the new state field. The RED class never depends on it. */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class CameraThumbnailCacheStateTest {
    private val main = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(main) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun equalMapPairsWithChangedOrderAreDifferentStateValues() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            val before = state.value
            val reordered = touchMediaCacheEntry(before.mediaThumbnails, items.first().id)
            val after = before.withMediaThumbnailCache(reordered)
            assertEquals(before.mediaThumbnails, after.mediaThumbnails)
            assertNotEquals(before, after)
            val flow = MutableStateFlow(before)
            flow.value = after
            assertSame(after, flow.value)
            assertEquals(listOf(items.last().id, items.first().id), flow.value.mediaThumbnailCacheOrder)
            assertCanonical(flow.value)
        }
    }

    @Test fun priorSnapshotsStayImmutableAndCurrentMruStaysANoop() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            val before = state.value
            model.loadMediaThumbnail(items.first())
            val after = state.value
            assertEquals(items.map { it.id }, before.mediaThumbnails.keys.toList())
            assertEquals(items.map { it.id }, before.mediaThumbnailCacheOrder)
            assertEquals(items.reversed().map { it.id }, after.mediaThumbnailCacheOrder)
            repeat(4) { model.loadMediaThumbnail(items.first()) }
            assertSame(after, state.value)
            assertCanonical(state.value)
            assertTrue(thumbnailReads.isEmpty())
        }
    }

    @Test fun touchesAndInsertionsKeepExactlyOneBoundedOrderSnapshot() = runTest(main) {
        withRecencyFixture {
            val items = seed(96)
            repeat(12) { model.loadMediaThumbnail(items[it % 3]) }
            assertCanonical(state.value)
            for (index in 96..97) {
                val next = appendItem(index)
                model.loadMediaThumbnail(next)
                awaitThumbnail()
                assertEquals(96, state.value.mediaThumbnails.size)
                assertCanonical(state.value)
            }
            assertEquals(listOf(item(96), item(97)), thumbnailReads)
        }
    }

    @Test fun infoReadbackPreservesMetadataButRetiresChangedSourceOrder() = runTest(main) {
        withRecencyFixture {
            val items = seed(3)
            model.loadMediaThumbnail(items[0])
            val order = keys()
            infoReadback = items[1].copy(rating = 5, protected = true)
            model.loadMediaInfo(items[1])
            awaitMediaOperation()
            assertEquals(order, state.value.mediaThumbnailCacheOrder)
            assertSame(cached, state.value.mediaThumbnails[items[1].id])
            infoReadback = items[1].copy(name = "REPLACED.JPG")
            model.loadMediaInfo(items[1])
            awaitMediaOperation()
            assertEquals(listOf(items[2].id, items[0].id), state.value.mediaThumbnailCacheOrder)
            assertNull(state.value.mediaThumbnails[items[1].id])
            assertCanonical(state.value)
            assertTrue(thumbnailReads.isEmpty())
        }
    }

    @Test fun partialAndFinalListingsPublishOnlyRetainedOrder() = runTest(main) {
        withRecencyFixture {
            val items = seed(3)
            model.loadMediaThumbnail(items[0])
            partial = listOf(items[0], items[2])
            listing = listOf(items[0])
            listRelease = CompletableDeferred()
            model.refreshMedia()
            runCurrent()
            assertTrue(state.value.mediaLibraryLoading)
            assertEquals(listOf(items[2].id, items[0].id), state.value.mediaThumbnailCacheOrder)
            assertCanonical(state.value)
            listRelease!!.complete(Unit)
            awaitListing()
            assertEquals(listOf(items[0].id), state.value.mediaThumbnailCacheOrder)
            assertCanonical(state.value)
            assertTrue(thumbnailReads.isEmpty())
        }
    }

    @Test fun eventReplacementAndEmptyRefreshCannotKeepRemovedIds() = runTest(main) {
        withRecencyFixture {
            val items = seed(3)
            model.loadMediaThumbnail(items[0])
            val replacement = items[1].copy(name = "REPLACED.JPG")
            state.value = with(model) { state.value.withEventMediaItems(listOf(items[0], replacement)) }
            assertEquals(listOf(items[0].id), state.value.mediaThumbnailCacheOrder)
            assertCanonical(state.value)
            listing = emptyList()
            model.refreshMedia()
            awaitListing()
            assertTrue(state.value.mediaThumbnails.isEmpty())
            assertTrue(state.value.mediaThumbnailCacheOrder.isEmpty())
            assertCanonical(state.value)
        }
    }

    @Test fun confirmedSyntheticDeletionNormalizesThePublishedSnapshot() = runTest(main) {
        withRecencyFixture {
            val items = seed(2)
            // The production preview deletion path produces a synthetic receipt, never a camera write.
            state.value = state.value.copy(previewMode = true)
            model.deleteMedia(items.first())
            runCurrent()
            awaitMediaOperation()
            assertEquals(1, state.value.mediaDeleteReceipt?.confirmed)
            assertEquals(listOf(items.last().id), state.value.mediaItems.map { it.id })
            assertEquals(listOf(items.last().id), state.value.mediaThumbnailCacheOrder)
            assertSame(cached, state.value.mediaThumbnails[items.last().id])
            assertCanonical(state.value)
        }
    }

    @Test fun sessionClearingDropsCachedIdsWithoutRetainingHistory() = runTest(main) {
        withRecencyFixture {
            seed(2)
            state.value = state.value.copy(previewMode = true)
            model.disconnect()
            runCurrent()
            assertFalse(state.value.connected)
            assertTrue(state.value.mediaThumbnails.isEmpty())
            assertTrue(state.value.mediaThumbnailCacheOrder.isEmpty())
            assertCanonical(state.value)
        }
    }

    @Test fun enteringOfflinePreviewClearsThePreviousCacheOrder() = runTest(main) {
        withRecencyFixture {
            seed(2)
            val before = state.value
            val preview = before.withOfflinePreview()
            assertTrue(preview.previewMode)
            assertTrue(preview.mediaThumbnails.isEmpty())
            assertTrue(preview.mediaThumbnailCacheOrder.isEmpty())
            assertEquals(2, before.mediaThumbnails.size)
            assertEquals(2, before.mediaThumbnailCacheOrder.size)
            assertCanonical(preview)
        }
    }

    @Test fun confirmedUploadRefreshClearsBothCacheAndOrder() = runTest(main) {
        withRecencyFixture {
            seed(2)
            val context = RuntimeEnvironment.getApplication()
            val source = File.createTempFile("synthetic-recency-", ".JPG", context.cacheDir)
            try {
                source.writeBytes(validJpeg())
                model.uploadMedia(context, Uri.fromFile(source))
                awaitMediaOperation()
                assertEquals(1, uploadCalls)
                assertEquals(MediaUploadStatus.CONFIRMED, state.value.mediaUploadStatus)
                assertFalse(state.value.mediaUploadRefreshFailed)
                assertEquals(listOf("synthetic-upload"), state.value.mediaItems.map { it.id })
                assertTrue(state.value.mediaThumbnails.isEmpty())
                assertTrue(state.value.mediaThumbnailCacheOrder.isEmpty())
                assertCanonical(state.value)
            } finally { assertTrue(source.delete()) }
        }
    }

    private fun assertCanonical(value: CameraUiState) {
        assertEquals(value.mediaThumbnails.keys.toList(), value.mediaThumbnailCacheOrder)
        assertEquals(value.mediaThumbnailCacheOrder.size, value.mediaThumbnailCacheOrder.distinct().size)
        assertTrue(value.mediaThumbnailCacheOrder.size <= 96)
    }
}
