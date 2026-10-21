package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraControlBackend
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.CapabilityMatrix
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.MediaDeleteEvidence
import dev.openeos.control.data.MediaDeleteOutcome
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

/** Actual ViewModel/repository callers; the backend never opens a camera or deletes a file.
 * All eight cases also compile against the frozen pre-repair production sources. */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class CameraMediaDeleteConfirmationTest {
    private val main = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(main) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun acceptedReplacementCannotUseSingleConfirmation() = runTest(main) {
        withDeleteFixture {
            val selection = review(listOf(original))
            val openingOwner = state.value.info
            val openingGeneration = state.value.mediaSessionGeneration
            refresh(listOf(replacement))
            assertSame(openingOwner, state.value.info)
            assertEquals(openingGeneration, state.value.mediaSessionGeneration)
            assertEquals(original.name, selection.requests.single().name)
            val receipt = delete(selection)
            assertTrue("STALE_DELETE_SINGLE_NAME", wireDeletes.isEmpty())
            assertEquals(0, backendInvocations)
            assertEquals(MediaDeleteOutcome.SKIPPED, receipt.rows.single().outcome)
            assertEquals("ITEM_CHANGED", receipt.rows.single().reason.name)
            assertEquals(listOf(replacement), state.value.mediaItems)
        }
    }

    @Test fun acceptedReplacementCannotUseBatchConfirmation() = runTest(main) {
        withDeleteFixture {
            val other = original.copy(id = "synthetic-delete-b", name = "UNCHANGED.JPG")
            val selection = review(listOf(other, original))
            refresh(listOf(other, replacement))
            val receipt = delete(selection)
            assertEquals("STALE_DELETE_BATCH_NAME", listOf(other), wireDeletes)
            assertEquals(listOf(MediaDeleteOutcome.CONFIRMED, MediaDeleteOutcome.SKIPPED), receipt.rows.map { it.outcome })
            assertEquals(original.name, receipt.rows.last().request.name)
            assertEquals("ITEM_CHANGED", receipt.rows.last().reason.name)
            assertEquals(listOf(replacement), state.value.mediaItems)
        }
    }

    @Test fun confirmedNameComparisonIsExact() = runTest(main) {
        withDeleteFixture {
            // No case folding, trimming or Unicode normalization may silently change the review.
            for ((oldName, newName) in listOf("SYNTHETIC.JPG" to "synthetic.JPG",
                "SYNTHETIC.JPG" to "SYNTHETIC.JPG ", "\u00e9.JPG" to "e\u0301.JPG")) {
                val old = original.copy(name = oldName)
                val current = old.copy(name = newName)
                val selection = review(listOf(old))
                refresh(listOf(current))
                val receipt = delete(selection)
                assertTrue("STALE_DELETE_EXACT_NAME", wireDeletes.isEmpty())
                assertEquals(MediaDeleteOutcome.SKIPPED, receipt.rows.single().outcome)
                assertEquals("ITEM_CHANGED", receipt.rows.single().reason.name)
                assertEquals(listOf(current), state.value.mediaItems)
            }
        }
    }

    @Test fun changedNameAtDispatchGateSendsNothing() = runTest(main) {
        withDeleteFixture {
            val selection = review(listOf(original))
            // A synthetic interleaving at the backend boundary exercises the real VM's
            // second preflight. This is not a claim that event polling runs during DELETE.
            beforeBoundary = { acceptSnapshot(listOf(replacement)) }
            val receipt = delete(selection)
            assertTrue("STALE_DELETE_BOUNDARY_NAME", wireDeletes.isEmpty())
            assertEquals(1, backendInvocations)
            assertEquals(MediaDeleteOutcome.NOT_SENT, receipt.rows.single().outcome)
            assertEquals("ITEM_CHANGED", receipt.rows.single().reason.name)
            assertEquals(listOf(replacement), state.value.mediaItems)
        }
    }

    @Test fun unchangedNameWithNewMetadataStillUsesOneConfirmation() = runTest(main) {
        withDeleteFixture {
            val selection = review(listOf(original))
            val current = original.copy(rating = 4, archived = true, protected = false, deleteWritable = true)
            refresh(listOf(current))
            val receipt = delete(selection)
            assertEquals(listOf(current), wireDeletes)
            assertEquals(1, backendInvocations)
            assertEquals(MediaDeleteOutcome.CONFIRMED, receipt.rows.single().outcome)
            assertTrue(state.value.mediaItems.isEmpty())
        }
    }

    @Test fun spentConfirmationCannotDispatchAgain() = runTest(main) {
        withDeleteFixture {
            val selection = review(listOf(original))
            assertEquals(MediaDeleteOutcome.CONFIRMED, delete(selection).rows.single().outcome)
            refresh(listOf(original))
            model.deleteMediaBatch(selection)
            scope.runCurrent()
            assertEquals(listOf(original), wireDeletes)
            assertEquals(1, backendInvocations)
            assertEquals(listOf(original), state.value.mediaItems)
        }
    }

    @Test fun knownProtectionAndReadOnlyVetoStillSkip() = runTest(main) {
        withDeleteFixture {
            for ((current, reason) in listOf(original.copy(protected = true) to "PROTECTED",
                original.copy(deleteWritable = false) to "NOT_WRITABLE")) {
                val selection = review(listOf(original))
                refresh(listOf(current))
                val receipt = delete(selection)
                assertTrue(wireDeletes.isEmpty())
                assertEquals(0, backendInvocations)
                assertEquals(MediaDeleteOutcome.SKIPPED, receipt.rows.single().outcome)
                assertEquals(reason, receipt.rows.single().reason.name)
                assertEquals(listOf(current), state.value.mediaItems)
            }
        }
    }

    @Test fun lostAckStopsRemainderAndCannotReplay() = runTest(main) {
        withDeleteFixture {
            val other = original.copy(id = "synthetic-delete-b", name = "NEXT.JPG")
            val selection = review(listOf(original, other))
            loseAck = true
            val receipt = delete(selection)
            assertEquals(listOf(original), wireDeletes)
            assertEquals(listOf(MediaDeleteOutcome.UNCERTAIN, MediaDeleteOutcome.NOT_STARTED), receipt.rows.map { it.outcome })
            assertEquals(listOf(original, other), state.value.mediaItems)
            model.deleteMediaBatch(selection)
            scope.runCurrent()
            assertEquals(1, backendInvocations)
            assertEquals(listOf(original), wireDeletes)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.withDeleteFixture(block: suspend DeleteConfirmationFixture.() -> Unit) {
    val fixture = DeleteConfirmationFixture(this)
    try { fixture.block() } finally {
        fixture.model.viewModelScope.cancel()
        fixture.model.viewModelScope.coroutineContext.job.join()
        runCurrent()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DeleteConfirmationFixture(val scope: TestScope) {
    val repository = CameraRepository()
    val model = CameraViewModel(repository)
    val state: MutableStateFlow<CameraUiState>
    val original = CameraMediaItem("synthetic-delete-a", "REVIEWED.JPG", "image")
    val replacement = original.copy(name = "REPLACEMENT.JPG")
    val wireDeletes = mutableListOf<CameraMediaItem>()
    var backendInvocations = 0
    var beforeBoundary: (() -> Unit)? = null
    var loseAck = false
    private var listing = emptyList<CameraMediaItem>()

    init {
        val capabilities = CameraCapabilities(emptyList(), emptyList(), emptyList(), emptyList(),
            matrix = CapabilityMatrix(setOf(CameraFeature.MEDIA_BROWSER, CameraFeature.MEDIA_DELETE)))
        val info = CameraInfo(true, "Synthetic delete confirmation", "TEST-DELETE", "synthetic/v1")
        val field = CameraViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(model) as MutableStateFlow<CameraUiState>
        state = flow
        state.value = state.value.copy(info = info, transport = CameraTransport.CCAPI_NETWORK,
            status = CameraStatus(true, 90, "normal", false, "photo", true, null,
                ExposureState("100", "1/100", "4", "auto")), capabilities = capabilities,
            mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE)
        val backendField = CameraRepository::class.java.getDeclaredField("backend").apply { isAccessible = true }
        val delegate = backendField.get(repository) as CameraControlBackend
        backendField.set(repository, object : CameraControlBackend by delegate {
            override suspend fun info() = info
            override suspend fun capabilities() = capabilities
            override suspend fun listMedia(maximumItems: Int?, onProgress: (List<CameraMediaItem>) -> Unit) = listing
            override suspend fun deleteMedia(item: CameraMediaItem, evidence: MediaDeleteEvidence) {
                backendInvocations++
                beforeBoundary?.invoke()
                evidence.beforeDispatch()
                wireDeletes += item
                if (loseAck) error("Synthetic response lost after dispatch")
                evidence.onConfirmed()
            }
        })
    }

    fun acceptSnapshot(items: List<CameraMediaItem>) {
        listing = items
        state.value = with(model) { state.value.withEventMediaItems(items) }
    }
    fun review(items: List<CameraMediaItem>): MediaDeleteSelection {
        acceptSnapshot(items)
        return MediaDeleteSelection(items, MediaDeleteOwner(state.value.info, state.value.mediaSessionGeneration),
            requireNotNull(state.value.transport))
    }
    suspend fun refresh(items: List<CameraMediaItem>) {
        listing = items
        model.refreshMedia()
        val completed = model.uiState.first { !it.mediaLibraryLoading }
        assertEquals(MediaLibraryLoadStatus.COMPLETE, completed.mediaLibraryLoadStatus)
        assertEquals(items, completed.mediaItems)
        assertNull(completed.error)
        scope.runCurrent()
    }
    suspend fun delete(selection: MediaDeleteSelection): MediaDeleteReceipt {
        model.deleteMediaBatch(selection)
        val completed = model.uiState.first {
            it.mediaDeleteReceipt?.let { receipt -> receipt.attemptId == selection.attemptId && !receipt.running } == true &&
                !it.isBusy(CameraOperation.MEDIA)
        }
        assertNull(completed.error)
        scope.runCurrent()
        return requireNotNull(completed.mediaDeleteReceipt)
    }
}
