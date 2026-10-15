package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaClassification
import dev.openeos.control.data.CameraMediaInventory
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.automaticJpegMetadataAdmitted
import dev.openeos.control.data.cameraMediaIsVideo
import dev.openeos.control.data.cameraMediaHasVideoTransportHint
import dev.openeos.control.data.cameraMediaContentTypeFromAliases
import dev.openeos.control.data.CAMERA_MEDIA_MIME_ALIASES
import dev.openeos.control.data.resolveCameraMediaMimeAliases
import dev.openeos.control.data.isAutomaticJpegEligible
import dev.openeos.control.data.isPhotoMedia
import dev.openeos.control.data.isVideoMedia
import dev.openeos.control.data.isVisualMedia
import dev.openeos.control.data.mediaClassification
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MediaTypeContractTest {
    private val fixture = loadMediaTypeFixture()

    @Test fun fixedContractIsComplete() {
        assertEquals(1, fixture.schemaVersion)
        assertEquals(71, fixture.cases.size)
        assertEquals(15, fixture.freshMetadataCases.size)
        assertEquals(4, fixture.selectionCases.size)
        assertEquals(19, fixture.mimeAliasCases.size)
        assertEquals(fixture.cases.size, fixture.cases.map { it.id }.toSet().size)
    }

    @Test fun dataAndUiClassificationAndStrictAutomaticAdmissionMatchEveryVector() {
        fixture.cases.forEach { v ->
            assertEquals(v.id, v.classification, v.item.mediaClassification.name.lowercase())
            assertEquals(v.id, v.isPhoto, v.item.isPhotoMedia)
            assertEquals(v.id, v.isPhoto, v.item.isPhoto)
            assertEquals(v.id, v.isVideo, v.item.isVideoMedia)
            assertEquals(v.id, v.isVideo, v.item.isVideo)
            assertEquals(v.id, v.isVideo, cameraMediaIsVideo(v.item.kind, v.item.name, v.item.contentType))
            assertEquals(v.id, v.isPhoto || v.isVideo, v.item.isVisualMedia)
            assertEquals(v.id, v.automaticJpegEligible, v.item.isAutomaticJpegEligible)
            assertEquals(v.id, v.automaticJpegEligible, isJpegImportCandidate(v.item))
        }
    }

    @Test fun galleryAndReviewKeepLoadedOrderAndOnlyExactFamilies() {
        val items = fixture.cases.map { it.item }
        assertEquals(items, mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA))
        assertEquals(fixture.cases.filter { it.isPhoto }.map { it.item },
            mediaItemsForDisplay(items, MediaFilter.PHOTOS, MediaSort.CAMERA))
        assertEquals(fixture.cases.filter { it.isVideo }.map { it.item },
            mediaItemsForDisplay(items, MediaFilter.VIDEOS, MediaSort.CAMERA))
        val byId = fixture.cases.associateBy { it.id }
        fixture.selectionCases.forEach { v ->
            val listing = v.listingCases.map { byId.getValue(it).item }
            val previous = v.knownCaseIds.map { byId.getValue(it).item.id }.toSet()
            assertEquals(v.id, v.expectedCaseId?.let { byId.getValue(it).item },
                selectCaptureReviewItem(listing, MediaReviewIntent.valueOf(v.intent.uppercase()), previous))
        }
    }

    @Test fun freshMetadataGateMatchesEveryVectorAndPolicyCannotClaimRejectedMetadata() {
        fixture.freshMetadataCases.forEach { v ->
            assertEquals(v.id, v.expectedAdmitted, automaticJpegMetadataAdmitted(v.original, v.metadata, v.fresh))
            val policy = ForegroundJpegImportPolicy()
            policy.acceptBaseline(emptyList(), complete = true)
            policy.observeInventory(listOf(v.original), complete = true, observedAtMillis = 0L)
            val metadata = v.metadata.copy(sizeBytes = 100L)
            if (v.fresh) {
                policy.acceptMetadata(v.original, metadata, 0L)
                policy.acceptMetadata(v.original, metadata, 1_000L)
            } else {
                policy.observeInventory(listOf(metadata), complete = true, observedAtMillis = 1_000L)
            }
            assertEquals(v.id, if (v.expectedAdmitted) metadata else null, policy.claimNext())
        }
    }

    @Test fun originalEligibilityAndExactIdentityCannotBeRepairedByLaterMetadata() {
        val eligible = fixture.cases.first().item
        fixture.cases.filterNot { it.automaticJpegEligible }.forEach { v ->
            assertFalse(v.id, automaticJpegMetadataAdmitted(v.item, eligible.copy(id = v.item.id, name = v.item.name), true))
        }
        assertFalse(eligible.copy(id = " ").isAutomaticJpegEligible)
        assertFalse(eligible.copy(name = "FRAME.JPG\n").isAutomaticJpegEligible)
    }

    @Test fun mimeControlsAndMalformedParametersCannotEnterDecoderAndClassificationDoesNotGrantTransport() {
        val item = fixture.cases.first().item
        for (mime in listOf("image/jpeg\n", "image/jpeg; x=\rvalue", "image/*", "image/é", "image /jpeg")) {
            assertEquals(mime, CameraMediaClassification.UNKNOWN, item.copy(contentType = mime).mediaClassification)
        }
        val mimeVideo = fixture.cases.single { it.id == "video-mime-only" }.item
        assertTrue(mimeVideo.isVideoMedia)
        assertFalse(mimeVideo.streamAvailable)
        assertFalse(cameraMediaHasVideoTransportHint(mimeVideo.kind, mimeVideo.name))
        assertFalse(item.previewAvailable)
    }

    @Test fun mimeAliasesPreserveExactDeclarationsAndCannotHideContradictions() {
        val item = fixture.cases.first().item
        for (alias in CAMERA_MEDIA_MIME_ALIASES) {
            for (value in listOf(null, "", " ", "image/jpeg", "IMAGE/JPEG; quality=90", "application/octet-stream")) {
                assertEquals(alias, value, cameraMediaContentTypeFromAliases(mapOf(alias to value)))
            }
            val invalid = cameraMediaContentTypeFromAliases(mapOf(alias to 123))
            assertEquals(CameraMediaClassification.UNKNOWN, item.copy(contentType = invalid).mediaClassification)
        }
        assertNull(cameraMediaContentTypeFromAliases(emptyMap()))
        assertNull(cameraMediaContentTypeFromAliases(mapOf("contentType" to null)))
        assertEquals("image/jpeg", cameraMediaContentTypeFromAliases(mapOf("contentType" to "image/jpeg", "mimeType" to "image/jpeg")))
        for (other in listOf(null, "", " ", "IMAGE/JPEG", "image/jpeg; quality=90", "video/mp4")) {
            val conflict = cameraMediaContentTypeFromAliases(mapOf("contentType" to "image/jpeg", "mimeType" to other))
            assertEquals(CameraMediaClassification.UNKNOWN, item.copy(contentType = conflict).mediaClassification)
            assertFalse(item.copy(contentType = conflict).isAutomaticJpegEligible)
        }
        val nullEmptyConflict = cameraMediaContentTypeFromAliases(mapOf("contentType" to null, "mimeType" to ""))
        assertFalse(item.copy(contentType = nullEmptyConflict).isAutomaticJpegEligible)
    }

    @Test fun fixedMimeAliasVectorsExerciseTheProductionResolver() {
        fixture.mimeAliasCases.forEach { v ->
            val resolved = resolveCameraMediaMimeAliases(v.fields)
            assertEquals(v.id, v.expectedValid, resolved.valid)
            assertEquals(v.id, v.expectedValue, resolved.value)
            val contentType = cameraMediaContentTypeFromAliases(v.fields)
            if (v.expectedValid) assertEquals(v.id, v.expectedValue, contentType)
            else assertEquals(v.id, CameraMediaClassification.UNKNOWN,
                fixture.cases.first().item.copy(contentType = contentType).mediaClassification)
        }
    }

    @Test fun runnerRejectsFreshTypeOrIdentityChangesWithoutAnOriginalRequest() = runBlocking {
        fixture.freshMetadataCases.filter { it.fresh && !it.expectedAdmitted }.forEach { v ->
            var inventories = 0
            var originals = 0
            val io = object : ForegroundJpegImportIo {
                override suspend fun awaitAvailable() = Unit
                override suspend fun inventory(maximumItems: Int) = CameraMediaInventory(
                    if (inventories++ < 2) emptyList() else listOf(v.original), true)
                override suspend fun freshInfo(item: CameraMediaItem) = v.metadata.copy(sizeBytes = 100L)
                override suspend fun saveOriginal(item: CameraMediaItem, receipt: ForegroundImportReceipt) { originals++ }
            }
            val runner = ForegroundJpegImportRunner(io, { 0L }, {})
            withTimeout(1_000L) { runner.run() }
            assertEquals(v.id, 0, originals)
            assertEquals(v.id, ForegroundImportStopReason.INVALID_SOURCE, runner.status.stopReason)
        }
    }

    @Test fun runnerTransfersOneEligibleStableOriginalAndRetainsStopOwnership() = runBlocking {
        val item = fixture.cases.first().item.copy(sizeBytes = 100L)
        var inventories = 0
        var metadataReads = 0
        var originals = 0
        lateinit var runner: ForegroundJpegImportRunner
        val io = object : ForegroundJpegImportIo {
            override suspend fun awaitAvailable() = Unit
            override suspend fun inventory(maximumItems: Int) = CameraMediaInventory(
                if (inventories++ < 2) emptyList() else listOf(item), true)
            override suspend fun freshInfo(item: CameraMediaItem): CameraMediaItem {
                assertNull(item.sizeBytes)
                assertNull(item.contentType)
                metadataReads++
                return item.copy(sizeBytes = 100L)
            }
            override suspend fun saveOriginal(item: CameraMediaItem, receipt: ForegroundImportReceipt) {
                originals++
                receipt.markPublished()
                runner.requestStop(ForegroundImportStopReason.USER)
            }
        }
        runner = ForegroundJpegImportRunner(io, { metadataReads.toLong() }, {},
            limits = ForegroundImportLimits(stableMillis = 1), idlePollMillis = 1)
        withTimeout(1_000L) { runner.run() }
        assertEquals(2, metadataReads)
        assertEquals(1, originals)
        assertEquals(1, runner.status.completedCount)
        assertEquals(ForegroundImportPhase.STOPPED, runner.status.phase)
    }
}
