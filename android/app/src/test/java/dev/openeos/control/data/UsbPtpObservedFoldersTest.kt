package dev.openeos.control.data

import dev.openeos.control.ui.MediaFilter
import dev.openeos.control.ui.MediaFolderFilter
import dev.openeos.control.ui.MediaSort
import dev.openeos.control.ui.mediaItemsForDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real backend and PtpSession over synthetic USB containers; no physical-camera claim. */
class UsbPtpObservedFoldersTest {
    @Test
    fun observedParentChainAndRootNeedOnlyExistingListingCommands() = runTest {
        val dcim = folder(10, "DCIM")
        val photos = folder(11, "100EOS", parent = 10)
        val image = media(42, parent = 11)
        val root = media(43)
        val wire = FolderWire(listOf(dcim, photos, image, root))
        val backend = backend(wire)
        backend.initialize()
        val progress = mutableListOf<List<CameraMediaItem>>()
        val listed = backend.listMedia(onProgress = progress::add)
        backend.close()

        wire.assertCommands(listCommands(listOf(dcim, photos, image, root)))
        assertEquals(listOf("Storage 00010001 / DCIM / 100EOS", "Storage 00010001"), listed.map { it.folder?.label })
        assertEquals(listOf("ptp:00010001:0000000B", "ptp:00010001:00000000"), listed.map { it.folder?.id })
        assertEquals(listOf(listed), progress)
    }

    @Test
    fun itemLimitDoesNotReadUnobservedParentOrBorrowEarlierListing() = runTest {
        val parent = folder(10, "DCIM")
        val image = media(42, parent = 10)
        val wire = FolderWire(listOf(parent, image), listings = listOf(listOf(10, 42), listOf(42, 10)))
        val backend = backend(wire)
        backend.initialize()
        assertEquals("Storage 00010001 / DCIM", backend.listMedia().single().folder?.label)
        val limited = backend.listMedia(maximumItems = 1)
        val refreshed = backend.mediaInfo(limited.single())
        backend.close()

        assertNull(limited.single().folder)
        assertNull(refreshed.folder)
        wire.assertCommands(listCommands(listOf(parent, image)) + listCommands(listOf(image)) + command(PtpOperationCode.GET_OBJECT_INFO, 42))
    }

    @Test
    fun progressUsesOnlyParentsObservedAtThatBatchAndKeepsPublishedItemsImmutable() = runTest {
        val images = (100L..149L).map { media(it, parent = 10) }
        val parent = folder(10, "DCIM")
        val wire = FolderWire(images + parent)
        val backend = backend(wire)
        backend.initialize()
        val progress = mutableListOf<List<CameraMediaItem>>()
        val listed = backend.listMedia(onProgress = progress::add)
        backend.close()

        assertEquals(2, progress.size)
        assertTrue(progress.first().all { it.folder == null })
        assertTrue(listed.all { it.folder?.label == "Storage 00010001 / DCIM" })
        assertEquals(listed, progress.last())
        wire.assertCommands(listCommands(images + parent))
    }

    @Test
    fun overlappingListingsKeepLocalProgressAndDoNotLetOlderFinalReplaceNewerSnapshot() = runTest {
        val parent = folder(10, "DCIM")
        val oldImages = (100L..150L).map { media(it, parent = 10) }
        val newImage = media(500, parent = 10)
        val wire = FolderWire(
            listOf(parent) + oldImages + newImage,
            listings = listOf(listOf(parent.handle) + oldImages.map { it.handle }, listOf(newImage.handle)),
        )
        val backend = backend(wire)
        backend.initialize()
        val firstProgress = mutableListOf<List<CameraMediaItem>>()
        val secondProgress = mutableListOf<List<CameraMediaItem>>()
        var second: List<CameraMediaItem>? = null
        val first = backend.listMedia { items ->
            firstProgress += items
            if (firstProgress.size == 1) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    second = backend.listMedia(onProgress = secondProgress::add)
                }
            }
        }
        val readback = backend.mediaInfo(requireNotNull(second).single())
        val olderPreview = backend.mediaPreview(first.last())
        backend.close()

        assertEquals(listOf(50, 51), firstProgress.map { it.size })
        assertTrue(firstProgress.flatten().all { it.folder?.label == "Storage 00010001 / DCIM" })
        assertEquals(first, firstProgress.last())
        assertEquals(listOf(listOf<CameraMediaFolder?>(null)), secondProgress.map { items -> items.map { it.folder } })
        assertNull(readback.folder)
        assertNull(olderPreview.item.folder)
        wire.assertCommands(
            listCommands(listOf(parent) + oldImages.take(50)) +
                listCommands(listOf(newImage)) + command(PtpOperationCode.GET_OBJECT_INFO, 150) +
                command(PtpOperationCode.GET_OBJECT_INFO, 500) + command(PtpOperationCode.GET_OBJECT_INFO, 150) +
                command(PtpOperationCode.GET_OBJECT, 150),
        )
    }

    @Test
    fun metadataPreviewAndProtectionUseObservedParentsInsteadOfCallerFolder() = runTest {
        val parent = folder(10, "DCIM")
        val image = media(42, parent = 10)
        val wire = FolderWire(listOf(parent, image))
        val backend = backend(wire)
        backend.initialize()
        val listed = backend.listMedia().single()
        val forged = listed.copy(folder = CameraMediaFolder("forged", "Other card / injected"))
        val info = backend.mediaInfo(forged)
        val preview = backend.mediaPreview(forged)
        val thumbnail = backend.mediaThumbnail(forged)
        val protected = backend.setMediaProtection(forged, true)
        val output = ByteArrayOutputStream()
        val saved = backend.downloadMedia(forged, output)
        wire.objects[42] = image.copy(parentObject = 99)
        val moved = backend.mediaInfo(forged)
        backend.close()

        assertEquals("Storage 00010001 / DCIM", info.folder?.label)
        assertEquals(info.folder, preview.item.folder)
        assertEquals(info.folder, thumbnail.item.folder)
        assertEquals(info.folder, protected.folder)
        assertEquals(info.folder, saved.item.folder)
        assertArrayEquals(JPEG, output.toByteArray())
        assertNull(moved.folder)
        wire.assertCommands(
            listCommands(listOf(parent, image)) + command(PtpOperationCode.GET_OBJECT_INFO, 42) +
                command(PtpOperationCode.GET_OBJECT, 42) + command(PtpOperationCode.GET_THUMB, 42) +
                command(PtpOperationCode.SET_OBJECT_PROTECTION, 42, 1) + command(PtpOperationCode.GET_OBJECT_INFO, 42) +
                command(PtpOperationCode.GET_OBJECT, 42) + command(PtpOperationCode.GET_OBJECT_INFO, 42),
        )
    }

    @Test
    fun cancellationDuringObjectInfoDoesNotPublishPartialSuccessOrContinueListing() = runTest {
        val first = media(42)
        val second = media(43)
        val third = media(44)
        val wire = FolderWire(listOf(first, second, third))
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        wire.beforeResponse = { request ->
            if (request.code == PtpOperationCode.GET_OBJECT_INFO && request.parameters() == listOf(43L)) {
                reached.complete(Unit)
                gate.await()
            }
        }
        val backend = backend(wire)
        backend.initialize()
        val progress = mutableListOf<List<CameraMediaItem>>()
        val listing = launch { backend.listMedia(onProgress = progress::add) }
        reached.await()
        listing.cancelAndJoin()
        backend.close()

        assertTrue(listing.isCancelled)
        assertTrue("Cancellation must not be converted into a successful partial listing", progress.isEmpty())
        wire.assertCommands(listCommands(listOf(first, second)))
    }

    @Test
    fun closeAndNewSessionDoNotReuseObservedParentsForSameNumericHandles() = runTest {
        val parent = folder(10, "DCIM")
        val image = media(42, parent = 10)
        val oldWire = FolderWire(listOf(parent, image))
        val newWire = FolderWire(listOf(image))
        val transports = ArrayDeque(listOf(oldWire, newWire))
        val backend = UsbPtpCameraBackend(
            CameraConnection.AndroidUsbPtp("synthetic-folders"),
            PtpTransportFactory { transports.removeFirst() },
        )
        backend.initialize()
        val oldItem = backend.listMedia().single()
        assertEquals("Storage 00010001 / DCIM", oldItem.folder?.label)
        backend.close()
        backend.initialize()
        val readback = backend.mediaInfo(oldItem)
        val listed = backend.listMedia().single()
        backend.close()

        assertNull(readback.folder)
        assertNull(listed.folder)
        oldWire.assertCommands(listCommands(listOf(parent, image)))
        newWire.assertCommands(command(PtpOperationCode.GET_OBJECT_INFO, 42) + listCommands(listOf(image)))
    }

    @Test
    fun cancellationKeepsDeliveredBatchFolderForSameSessionReadback() = runTest {
        val parent = folder(10, "DCIM")
        val images = (100L..150L).map { media(it, parent = 10) }
        val wire = FolderWire(listOf(parent) + images)
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        wire.beforeResponse = { request ->
            if (request.code == PtpOperationCode.GET_OBJECT_INFO && request.parameters() == listOf(150L)) {
                reached.complete(Unit)
                gate.await()
            }
        }
        val backend = backend(wire)
        backend.initialize()
        val progress = mutableListOf<List<CameraMediaItem>>()
        val listing = launch { backend.listMedia(onProgress = progress::add) }
        reached.await()
        listing.cancelAndJoin()
        val delivered = progress.single().first()
        val refreshed = backend.mediaInfo(delivered)
        val preview = backend.mediaPreview(delivered)
        backend.close()

        assertEquals("Storage 00010001 / DCIM", delivered.folder?.label)
        assertEquals(delivered.folder, refreshed.folder)
        assertEquals(delivered.folder, preview.item.folder)
        wire.assertCommands(listCommands(listOf(parent) + images) + command(PtpOperationCode.GET_OBJECT_INFO, 100) + command(PtpOperationCode.GET_OBJECT, 100))
    }

    @Test
    fun oldListingCannotPublishAfterCloseAndReplacementSession() = runTest {
        val parent = folder(10, "DCIM")
        val images = (100L..150L).map { media(it, parent = 10) }
        val oldWire = FolderWire(listOf(parent) + images)
        val newWire = FolderWire(listOf(images.first()))
        val transports = ArrayDeque(listOf(oldWire, newWire))
        val backend = UsbPtpCameraBackend(CameraConnection.AndroidUsbPtp("synthetic-folders"), PtpTransportFactory { transports.removeFirst() })
        backend.initialize()
        val oldProgress = mutableListOf<List<CameraMediaItem>>()
        var replacement: CameraMediaItem? = null
        val failure = runCatching {
            backend.listMedia { items ->
                oldProgress += items
                if (oldProgress.size == 1) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        backend.close()
                        backend.initialize()
                        replacement = backend.listMedia().single()
                    }
                }
            }
        }.exceptionOrNull()
        val readback = backend.mediaInfo(requireNotNull(replacement))
        backend.close()

        assertTrue(failure is CancellationException)
        assertEquals(1, oldProgress.size)
        assertEquals("Storage 00010001 / DCIM", oldProgress.single().first().folder?.label)
        assertNull(replacement?.folder)
        assertNull(readback.folder)
        oldWire.assertCommands(listCommands(listOf(parent) + images.take(50)))
        newWire.assertCommands(listCommands(listOf(images.first())) + command(PtpOperationCode.GET_OBJECT_INFO, 100))
    }

    @Test
    fun readbackStartedBeforeNewListingCannotBorrowItsFolderOrRepopulateItsCache() = runTest {
        val parent = folder(10, "DCIM")
        val image = media(42, parent = 10)
        val wire = FolderWire(listOf(parent, image), listings = listOf(listOf(10, 42), listOf(10, 42)))
        val backend = backend(wire)
        backend.initialize()
        val original = backend.listMedia().single()
        val gate = CompletableDeferred<Unit>()
        wire.beforeResponse = { request ->
            if (request.code == PtpOperationCode.GET_OBJECT_INFO && request.parameters() == listOf(42L)) gate.await()
        }
        val readback = async(start = CoroutineStart.UNDISPATCHED) { backend.mediaInfo(original) }
        wire.objects[10] = parent.copy(filename = "NEW")
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { backend.listMedia() }
        gate.complete(Unit)
        assertNull(readback.await().folder)
        val latest = replacement.await().single()
        val preview = backend.mediaPreview(latest)
        backend.close()

        assertEquals("Storage 00010001 / NEW", latest.folder?.label)
        assertEquals(latest.folder, preview.item.folder)
        wire.assertCommands(listCommands(listOf(parent, image)) + command(PtpOperationCode.GET_OBJECT_INFO, 42) +
            listCommands(listOf(parent, image)) + command(PtpOperationCode.GET_OBJECT, 42))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun previewReturningAfterSameListingFinalUsesNewlyObservedParent() = runTest {
        val images = (100L..149L).map { media(it, parent = 10) }
        val parent = folder(10, "DCIM")
        val wire = FolderWire(images + parent)
        val gate = CompletableDeferred<Unit>()
        wire.beforeResponse = { request -> if (request.code == PtpOperationCode.GET_OBJECT) gate.await() }
        val backend = backend(wire)
        backend.initialize()
        var preview: Deferred<CameraMediaPreview>? = null
        var finalPublished = false
        val listing = async(UnconfinedTestDispatcher(testScheduler)) {
            backend.listMedia { items ->
                if (preview == null) {
                    assertNull(items.first().folder)
                    preview = async(StandardTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
                        backend.mediaPreview(items.first())
                    }
                } else finalPublished = true
            }
        }
        gate.complete(Unit)
        val returned = requireNotNull(preview).await()
        val finalWasPublished = finalPublished
        val final = listing.await()
        backend.close()

        assertTrue("The queued parent read/final publication must run before the preview returns", finalWasPublished)
        assertEquals("Storage 00010001 / DCIM", final.first().folder?.label)
        assertEquals(final.first().folder, returned.item.folder)
        wire.assertCommands(listCommands(images) + command(PtpOperationCode.GET_OBJECT, 100) + command(PtpOperationCode.GET_OBJECT_INFO, 10))
    }

    @Test
    fun ratingReadbackCannotSendOldListingHandleIntoReplacementSession() = runTest {
        val image = media(42)
        val replacement = media(77)
        val oldWire = FolderWire(listOf(image), advertiseRating = true)
        val newWire = FolderWire(listOf(replacement))
        val transports = ArrayDeque(listOf(oldWire, newWire))
        val backend = UsbPtpCameraBackend(CameraConnection.AndroidUsbPtp("synthetic-folders"), PtpTransportFactory { transports.removeFirst() })
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        oldWire.beforeResponse = { request ->
            if (request.code == PtpOperationCode.GET_OBJECT_PROP_DESC) {
                reached.complete(Unit)
                gate.await()
            }
        }
        backend.initialize()
        val oldProgress = mutableListOf<List<CameraMediaItem>>()
        val oldListing = launch { backend.listMedia(onProgress = oldProgress::add) }
        reached.await()
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { backend.close() }
        backend.initialize()
        val latest = backend.listMedia().single()
        gate.complete(Unit)
        oldListing.join()
        closing.join()
        val refreshed = backend.mediaInfo(latest)
        backend.close()

        oldWire.assertCommands(listCommands(listOf(image)) + ratingContractCommands())
        newWire.assertCommands(listCommands(listOf(replacement)) + command(PtpOperationCode.GET_OBJECT_INFO, 77))
        assertTrue(oldListing.isCancelled)
        assertTrue(oldProgress.isEmpty())
        assertEquals(latest.folder, refreshed.folder)
    }

    @Test
    fun metadataAndMutationResultsAreRejectedAfterSessionReplacement() = runTest {
        val failures = listOf(LateMediaOperation.INFO, LateMediaOperation.PROTECTION, LateMediaOperation.RATING)
            .flatMap { verifyResultAfterReplacement(it) }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun binaryMediaResultsCannotPublishSuccessOrFeaturesAfterSessionReplacement() = runTest {
        val failures = listOf(LateMediaOperation.THUMBNAIL, LateMediaOperation.PREVIEW, LateMediaOperation.DOWNLOAD)
            .flatMap { verifyResultAfterReplacement(it) }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun oldVideoSourceCannotReadRangesFromReplacementSession() = runTest {
        val video = media(42).copy(filename = "OLD.MP4", objectFormat = PtpObjectFormat.MP4)
        val replacement = media(77)
        val oldWire = FolderWire(listOf(video))
        val newWire = FolderWire(listOf(replacement))
        val transports = ArrayDeque(listOf(oldWire, newWire))
        val backend = UsbPtpCameraBackend(CameraConnection.AndroidUsbPtp("synthetic-stream-lifecycle"), PtpTransportFactory { transports.removeFirst() })
        backend.initialize()
        val source = backend.openMediaStream(backend.listMedia().single())
        val range = source.open(0)
        backend.close()
        backend.initialize()
        backend.listMedia()
        val buffer = ByteArray(JPEG.size)
        val result = runCatching { range.read(buffer, 0, buffer.size) }
        range.close()
        source.close()
        backend.close()

        oldWire.assertCommands(listCommands(listOf(video)))
        newWire.assertCommands(listCommands(listOf(replacement)))
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertArrayEquals(ByteArray(JPEG.size), buffer)
    }

    @Test
    fun transientStorageInfoFailureKeepsObservedFolderThroughRecoveryAndInfoFilter() = runTest {
        val parent = folder(10, "DCIM")
        val image = media(42, parent = 10)
        val wire = FolderWire(
            listOf(parent, image),
            storageInfoResponses = listOf(PtpResponseCode.OK, PtpResponseCode.DEVICE_BUSY, PtpResponseCode.OK),
        )
        val backend = backend(wire)
        backend.initialize()
        assertEquals(1, backend.status().storageDeviceCount)
        val item = backend.listMedia().single()
        val selected = MediaFolderFilter.Folder(requireNotNull(item.folder))
        assertEquals(listOf(item.id), mediaItemsForDisplay(listOf(item), MediaFilter.ALL, MediaSort.CAMERA,
            folderFilter = selected).map { it.id })

        val failedStatus = backend.status()
        assertTrue(failedStatus.rawStorageJson.contains("DeviceBusy"))
        backend.capabilities()
        val refreshed = backend.mediaInfo(item)
        backend.close()

        wire.assertCommands(storageCommands() + listCommands(listOf(parent, image)) +
            storageCommands() + storageCommands() + command(PtpOperationCode.GET_OBJECT_INFO, 42))
        // The real library filter sees the replacement returned by mediaInfo, as the VM does.
        // This does not claim that opening preview alone replaces the library item.
        val visible = mediaItemsForDisplay(listOf(refreshed), MediaFilter.ALL, MediaSort.CAMERA,
            folderFilter = selected).map { it.id }
        println("StorageInfo failure/recovery: original=${item.folder}; refreshed=${refreshed.folder}; visible=$visible")
        assertEquals("Recovered metadata must remain in the selected observed folder", listOf(item.id), visible)
        assertEquals(item.folder, refreshed.folder)
    }

    @Test
    fun successfulEmptyStorageAfterTransientFailureRetiresObservedFolder() = runTest {
        verifyConfirmedStorageChangeAfterFailure(emptyList(), restoreOriginalStorage = true)
    }

    @Test
    fun successfulChangedStorageSetAfterTransientFailureRetiresObservedFolder() = runTest {
        // The original object is still legitimately on S; only the confirmed storage set changes.
        verifyConfirmedStorageChangeAfterFailure(listOf(STORAGE, STORAGE + 1), restoreOriginalStorage = false)
    }

    @Test
    fun firstSuccessfulStatusCanRetireStorageIdsAlreadyObservedByListing() = runTest {
        val parent = folder(10, "DCIM")
        val image = media(42, parent = 10)
        val changedIds = listOf(STORAGE, STORAGE + 1)
        val wire = FolderWire(
            listOf(parent, image),
            storageIdResponses = listOf(listOf(STORAGE), changedIds),
            storageInfoResponses = listOf(PtpResponseCode.OK, PtpResponseCode.OK),
        )
        val backend = backend(wire)
        backend.initialize()
        val item = backend.listMedia().single()
        assertEquals("Storage 00010001 / DCIM", item.folder?.label)
        assertEquals(2, backend.status().storageDeviceCount)
        val refreshed = backend.mediaInfo(item)
        backend.close()

        wire.assertCommands(listCommands(listOf(parent, image)) + storageCommands(changedIds) +
            command(PtpOperationCode.GET_OBJECT_INFO, 42))
        assertNull("A listing's observed storage IDs count even before the first status refresh", refreshed.folder)
    }

    private suspend fun verifyConfirmedStorageChangeAfterFailure(
        changedIds: List<Long>,
        restoreOriginalStorage: Boolean,
    ) {
        val parent = folder(10, "DCIM")
        val image = media(42, parent = 10)
        val finalIds = if (restoreOriginalStorage) listOf(listOf(STORAGE)) else emptyList()
        val infoResponses = listOf(PtpResponseCode.OK, PtpResponseCode.DEVICE_BUSY) +
            List(changedIds.size) { PtpResponseCode.OK } + if (restoreOriginalStorage) listOf(PtpResponseCode.OK) else emptyList()
        val wire = FolderWire(
            listOf(parent, image),
            storageIdResponses = listOf(listOf(STORAGE), listOf(STORAGE), listOf(STORAGE), changedIds) + finalIds,
            storageInfoResponses = infoResponses,
        )
        val backend = backend(wire)
        backend.initialize()
        assertEquals(1, backend.status().storageDeviceCount)
        val item = backend.listMedia().single()
        assertEquals("Storage 00010001 / DCIM", item.folder?.label)
        assertTrue(backend.status().rawStorageJson.contains("DeviceBusy"))
        backend.capabilities()
        if (restoreOriginalStorage) {
            // A card returns after a confirmed empty set. Reused numeric IDs cannot resurrect
            // a folder whose parent was not observed again; ObjectInfo is valid on storage S.
            assertEquals(1, backend.status().storageDeviceCount)
        }
        val refreshed = backend.mediaInfo(item)
        backend.close()

        wire.assertCommands(storageCommands() + listCommands(listOf(parent, image)) + storageCommands() +
            storageCommands(changedIds) + (if (restoreOriginalStorage) storageCommands() else emptyList()) +
            command(PtpOperationCode.GET_OBJECT_INFO, 42))
        println("Confirmed storage change after failure: ids=$changedIds; restored=$restoreOriginalStorage; folder=${refreshed.folder}")
        assertNull("A confirmed empty/changed storage set must retire earlier folder observations", refreshed.folder)
    }

    /** Same-backend lifecycle contract; CameraRepository normally creates a fresh backend on reconnect. */
    private suspend fun verifyResultAfterReplacement(operation: LateMediaOperation): List<String> = coroutineScope {
        val image = media(42)
        val replacement = media(77)
        val rating = operation == LateMediaOperation.RATING
        val oldWire = FolderWire(listOf(image), advertiseRating = rating)
        val newWire = FolderWire(listOf(replacement))
        val transports = ArrayDeque(listOf(oldWire, newWire))
        val backend = UsbPtpCameraBackend(CameraConnection.AndroidUsbPtp("synthetic-result-lifecycle"), PtpTransportFactory { transports.removeFirst() })
        backend.initialize()
        val item = backend.listMedia().single()
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val lastOperation = when (operation) {
            LateMediaOperation.INFO, LateMediaOperation.PROTECTION -> PtpOperationCode.GET_OBJECT_INFO
            LateMediaOperation.RATING -> PtpOperationCode.GET_OBJECT_PROP_VALUE
            LateMediaOperation.THUMBNAIL -> PtpOperationCode.GET_THUMB
            LateMediaOperation.PREVIEW, LateMediaOperation.DOWNLOAD -> PtpOperationCode.GET_OBJECT
        }
        oldWire.beforeResponse = { request ->
            if (request.code == lastOperation) { reached.complete(Unit); gate.await() }
        }
        val output = ByteArrayOutputStream()
        val pending = async {
            runCatching {
                when (operation) {
                    LateMediaOperation.INFO -> backend.mediaInfo(item)
                    LateMediaOperation.PROTECTION -> backend.setMediaProtection(item, true)
                    LateMediaOperation.RATING -> backend.setMediaRating(item, 3)
                    LateMediaOperation.THUMBNAIL -> backend.mediaThumbnail(item)
                    LateMediaOperation.PREVIEW -> backend.mediaPreview(item)
                    LateMediaOperation.DOWNLOAD -> backend.downloadMedia(item, output)
                }
            }
        }
        reached.await()
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { backend.close() }
        backend.initialize()
        backend.listMedia()
        val expectedFeatures = backend.observedFeatures()
        gate.complete(Unit)
        val result = pending.await()
        closing.join()
        val actualFeatures = backend.observedFeatures()
        backend.close()

        val requestedCommands = when (operation) {
            LateMediaOperation.INFO -> command(PtpOperationCode.GET_OBJECT_INFO, 42)
            LateMediaOperation.PROTECTION -> command(PtpOperationCode.SET_OBJECT_PROTECTION, 42, 1) + command(PtpOperationCode.GET_OBJECT_INFO, 42)
            LateMediaOperation.RATING -> command(PtpOperationCode.GET_OBJECT_INFO, 42) +
                command(PtpOperationCode.SET_OBJECT_PROP_VALUE, 42, MtpObjectPropertyCode.RATING.toLong()) +
                command(PtpOperationCode.GET_OBJECT_PROP_VALUE, 42, MtpObjectPropertyCode.RATING.toLong())
            else -> command(lastOperation, 42)
        }
        val listingRatingCommands = if (rating) ratingContractCommands() +
            command(PtpOperationCode.GET_OBJECT_PROP_VALUE, 42, MtpObjectPropertyCode.RATING.toLong()) else emptyList()
        oldWire.assertCommands(listCommands(listOf(image)) + listingRatingCommands + requestedCommands)
        newWire.assertCommands(listCommands(listOf(replacement)))
        if (rating) assertEquals(listOf(listOf(60.toByte(), 0.toByte())), oldWire.ratingWrites.map { it.toList() })
        if (operation == LateMediaOperation.DOWNLOAD) assertArrayEquals(JPEG, output.toByteArray())
        println("Session-replacement case $operation: cancelled=${result.exceptionOrNull() is CancellationException}; staleFeatures=${actualFeatures - expectedFeatures}")
        buildList {
            if (result.exceptionOrNull() !is CancellationException) add("$operation returned ${result.exceptionOrNull() ?: "success"} after replacement")
            if (actualFeatures != expectedFeatures) add("$operation added stale features ${actualFeatures - expectedFeatures}")
        }
    }

    private enum class LateMediaOperation { INFO, PROTECTION, RATING, THUMBNAIL, PREVIEW, DOWNLOAD }

    private fun backend(wire: FolderWire) = UsbPtpCameraBackend(
        CameraConnection.AndroidUsbPtp("synthetic-folders"),
        PtpTransportFactory { wire },
    )

    private class FolderWire(
        observed: List<PtpObjectInfo>,
        listings: List<List<Long>> = listOf(observed.map { it.handle }),
        private val advertiseRating: Boolean = false,
        storageIdResponses: List<List<Long>>? = null,
        storageInfoResponses: List<Int>? = null,
    ) : PtpTransport {
        val objects = observed.associateBy { it.handle }.toMutableMap()
        private val pendingListings = ArrayDeque(listings)
        private val pendingStorageIds = storageIdResponses?.let { ArrayDeque(it) }
        private val pendingStorageInfo = ArrayDeque(storageInfoResponses.orEmpty())
        private val advertiseStorageInfo = storageInfoResponses != null
        private val incoming = ArrayDeque<PtpContainer>()
        private var request: PtpContainer? = null
        private var pendingRatingWrite: PtpContainer? = null
        private var ratingWireValue = 40
        val ratingWrites = mutableListOf<ByteArray>()
        private val sent = mutableListOf<Pair<Int, List<Long>>>()
        var beforeResponse: suspend (PtpContainer) -> Unit = {}
        var closed = false

        override suspend fun send(container: PtpContainer) {
            if (container.type == PtpContainerType.DATA) {
                val pending = requireNotNull(pendingRatingWrite)
                check(container.code == pending.code && container.transactionId == pending.transactionId)
                ratingWireValue = (MtpObjectPropertyCodec.decodeValue(PtpDataType(PtpDataType.UINT16), container.payload) as PtpPropertyValue.Unsigned).value.toInt()
                ratingWrites += container.payload.copyOf()
                incoming += PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, container.transactionId)
                pendingRatingWrite = null
                return
            }
            check(container.type == PtpContainerType.COMMAND)
            check(!closed)
            sent += container.code to container.parameters()
            request = container
            val payload = when (container.code) {
                PtpOperationCode.GET_DEVICE_INFO -> deviceInfo(advertiseRating, advertiseStorageInfo)
                PtpOperationCode.GET_STORAGE_IDS -> u32Array(pendingStorageIds?.removeFirst() ?: listOf(STORAGE))
                PtpOperationCode.GET_STORAGE_INFO -> {
                    check(advertiseStorageInfo)
                    val response = pendingStorageInfo.removeFirst()
                    if (response != PtpResponseCode.OK) {
                        incoming += PtpContainer(PtpContainerType.RESPONSE, response, container.transactionId)
                        return
                    }
                    Writer().apply {
                        u16(4); u16(2); u16(0)
                        u64(1_048_576); u64(524_288); u32(100)
                        string("Synthetic storage"); string("SYNTHETIC")
                    }.bytes()
                }
                PtpOperationCode.GET_OBJECT_HANDLES -> u32Array(pendingListings.removeFirst().reversed())
                PtpOperationCode.GET_OBJECT_INFO -> PtpDatasets.encodeObjectInfo(objects.getValue(container.parameters().single()))
                PtpOperationCode.GET_OBJECT, PtpOperationCode.GET_THUMB -> JPEG
                PtpOperationCode.GET_PARTIAL_OBJECT -> JPEG.copyOfRange(
                    container.parameters()[1].toInt(),
                    minOf(JPEG.size, (container.parameters()[1] + container.parameters()[2]).toInt()),
                )
                PtpOperationCode.GET_OBJECT_PROPS_SUPPORTED -> Writer().apply {
                    check(advertiseRating)
                    u32(1); u16(MtpObjectPropertyCode.RATING)
                }.bytes()
                PtpOperationCode.GET_OBJECT_PROP_DESC -> Writer().apply {
                    check(advertiseRating)
                    u16(MtpObjectPropertyCode.RATING); u16(PtpDataType.UINT16); u8(1); u16(0); u32(0)
                    u8(1); u16(0); u16(100); u16(1)
                }.bytes()
                PtpOperationCode.GET_OBJECT_PROP_VALUE -> Writer().apply {
                    check(advertiseRating)
                    u16(ratingWireValue)
                }.bytes()
                PtpOperationCode.SET_OBJECT_PROP_VALUE -> {
                    check(advertiseRating)
                    pendingRatingWrite = container
                    return
                }
                PtpOperationCode.SET_OBJECT_PROTECTION -> {
                    val (handle, value) = container.parameters()
                    objects[handle] = objects.getValue(handle).copy(protectionStatus = value.toInt())
                    null
                }
                PtpOperationCode.OPEN_SESSION, PtpOperationCode.CLOSE_SESSION -> null
                else -> error("Unexpected PTP command: ${container.code.toString(16)}")
            }
            if (payload != null) incoming += PtpContainer(PtpContainerType.DATA, container.code, container.transactionId, payload)
            incoming += PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, container.transactionId)
        }

        override suspend fun receive(maxPayloadBytes: Int): PtpContainer {
            val result = incoming.removeFirst()
            if (result.type == PtpContainerType.RESPONSE) beforeResponse(requireNotNull(request))
            return result
        }

        override fun close() { closed = true }

        fun assertCommands(body: List<Pair<Int, List<Long>>>) {
            assertEquals(
                command(PtpOperationCode.GET_DEVICE_INFO) + command(PtpOperationCode.OPEN_SESSION, 1) +
                    body + command(PtpOperationCode.CLOSE_SESSION),
                sent,
            )
            assertTrue(closed)
            assertTrue(incoming.isEmpty())
            assertTrue(pendingStorageInfo.isEmpty())
            assertTrue(pendingStorageIds == null || pendingStorageIds.isEmpty())
        }
    }

    companion object {
        private const val STORAGE = 0x00010001L
        private val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())

        internal fun media(handle: Long, parent: Long = 0, storage: Long = STORAGE) = PtpObjectInfo(
            handle, storage, PtpObjectFormat.EXIF_JPEG, 0, JPEG.size.toLong(), PtpObjectFormat.EXIF_JPEG, JPEG.size.toLong(), 160, 120, 6000, 4000, 24,
            parent, 0, 0, 0, "IMG_$handle.JPG", "20261005T120000", "", "",
        )

        internal fun folder(handle: Long, name: String, parent: Long = 0, storage: Long = STORAGE) =
            media(handle, parent, storage).copy(objectFormat = PtpObjectFormat.ASSOCIATION, associationType = 1, filename = name)

        private fun command(code: Int, vararg params: Long) = listOf(code to params.toList())

        private fun PtpContainer.parameters(): List<Long> {
            check(payload.size % 4 == 0)
            return payload.indices.step(4).map { offset ->
                (0..3).fold(0L) { value, index -> value or ((payload[offset + index].toLong() and 0xff) shl (8 * index)) }
            }
        }

        private fun listCommands(objects: List<PtpObjectInfo>) =
            command(PtpOperationCode.GET_STORAGE_IDS) + command(PtpOperationCode.GET_OBJECT_HANDLES, STORAGE, 0, 0) +
                objects.flatMap { command(PtpOperationCode.GET_OBJECT_INFO, it.handle) }

        private fun storageCommands(ids: List<Long> = listOf(STORAGE)) =
            command(PtpOperationCode.GET_STORAGE_IDS) + ids.flatMap { command(PtpOperationCode.GET_STORAGE_INFO, it) }

        private fun ratingContractCommands() =
            command(PtpOperationCode.GET_OBJECT_PROPS_SUPPORTED, PtpObjectFormat.EXIF_JPEG.toLong()) +
                command(PtpOperationCode.GET_OBJECT_PROP_DESC, MtpObjectPropertyCode.RATING.toLong(), PtpObjectFormat.EXIF_JPEG.toLong())

        private fun u32Array(values: List<Long>) = Writer().apply { u32(values.size.toLong()); values.forEach(::u32) }.bytes()

        private fun deviceInfo(advertiseRating: Boolean, advertiseStorageInfo: Boolean) = Writer().apply {
            u16(100); u32(0); u16(100); string(""); u16(0)
            val operations = listOf(PtpOperationCode.GET_DEVICE_INFO, PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.CLOSE_SESSION, PtpOperationCode.GET_STORAGE_IDS, PtpOperationCode.GET_OBJECT_HANDLES,
                PtpOperationCode.GET_OBJECT_INFO, PtpOperationCode.GET_OBJECT, PtpOperationCode.GET_THUMB,
                PtpOperationCode.GET_PARTIAL_OBJECT, PtpOperationCode.SET_OBJECT_PROTECTION) +
                (if (advertiseRating) listOf(PtpOperationCode.GET_OBJECT_PROPS_SUPPORTED, PtpOperationCode.GET_OBJECT_PROP_DESC,
                    PtpOperationCode.GET_OBJECT_PROP_VALUE, PtpOperationCode.SET_OBJECT_PROP_VALUE) else emptyList()) +
                if (advertiseStorageInfo) listOf(PtpOperationCode.GET_STORAGE_INFO) else emptyList()
            u32(operations.size.toLong()); operations.forEach(::u16)
            repeat(4) { u32(0) }
            string("Synthetic"); string("Folder fixture"); string("1"); string("SYNTHETIC-ONLY")
        }.bytes()

        private class Writer {
            private val output = ByteArrayOutputStream()
            fun u8(value: Int) { output.write(value) }
            fun u16(value: Int) { repeat(2) { output.write(value ushr (8 * it) and 0xff) } }
            fun u32(value: Long) { repeat(4) { output.write((value ushr (8 * it) and 0xff).toInt()) } }
            fun u64(value: Long) { repeat(8) { output.write((value ushr (8 * it) and 0xff).toInt()) } }
            fun string(value: String) {
                if (value.isEmpty()) output.write(0) else {
                    output.write(value.length + 1); output.write(value.toByteArray(Charsets.UTF_16LE)); u16(0)
                }
            }
            fun bytes() = output.toByteArray()
        }
    }
}
