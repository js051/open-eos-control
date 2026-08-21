package dev.openeos.control.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraImportContractTest {
    @Test
    fun fullRawFixtureMatchesKotlinContract() {
        assertEquals(CAMERA_IMPORT_CONTRACT_ARTIFACT_VERSION, resourceText("VERSION").trim())
        val descriptor = CameraImportJsonCodecV1.decodeMediaDescriptor(
            resourceText("fixtures/valid/media-full-raw.json"),
        )

        assertEquals(CameraImportMediaKind.RAW, descriptor.mediaKind)
        assertEquals(48_234_496L, descriptor.byteLength)
        assertTrue(descriptor.sourceChecksum?.supportsExactDuplicate == true)
    }

    @Test
    fun completedTransferRequiresMatchingIntegrityEvidence() {
        val event = CameraImportJsonCodecV1.decodeTransferEvent(
            resourceText("fixtures/valid/transfer-completed.json"),
        )
        assertEquals(CameraImportTransferState.COMPLETED, event.state)

        assertThrows(IllegalArgumentException::class.java) {
            event.copy(receivedByteLength = null, transportChecksumVerified = null, sanitizedEvidenceId = null)
        }
    }

    @Test
    fun receiptHasNoCameraDeletionAuthority() {
        val receipt = CameraImportJsonCodecV1.decodeReceipt(
            resourceText("fixtures/valid/receipt-imported.json"),
        )
        assertEquals(CameraImportOutcome.IMPORTED, receipt.outcome)
    }

    @Test
    fun strictDecoderRejectsUnknownReceiptAuthority() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraImportJsonCodecV1.decodeReceipt(
                resourceText("fixtures/invalid/receipt-delete-authorization.json"),
            )
        }
    }

    @Test
    fun strictDecoderAcceptsUnknownMetadataAsExplicitNulls() {
        val descriptor = CameraImportJsonCodecV1.decodeMediaDescriptor(
            resourceText("fixtures/valid/media-minimal.json"),
        )
        assertEquals(null, descriptor.capturedAt)
        assertEquals(listOf(CameraImportRepresentation.ORIGINAL), descriptor.availableRepresentations)
    }

    @Test
    fun strictDecoderDoesNotCoerceJsonScalarTypes() {
        val coerced = resourceText("fixtures/valid/media-minimal.json")
            .replace("\"cancel_supported\": true", "\"cancel_supported\": \"true\"")
        assertThrows(IllegalArgumentException::class.java) {
            CameraImportJsonCodecV1.decodeMediaDescriptor(coerced)
        }
    }

    @Test
    fun opaqueIdentifiersRejectTransportLocatorsAndPrivateAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            validDescriptor(mediaId = "https://example.invalid/DCIM/DEMO.CR3")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validDescriptor(mediaId = "000000000000")
        }
    }

    @Test
    fun openEosProviderRejectsVersionsOlderThanContractMinimum() {
        assertThrows(IllegalArgumentException::class.java) {
            validDescriptor(mediaId = "media-demo-0001", providerVersion = "0.4.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validDescriptor(mediaId = "media-demo-0001", providerVersion = "0.5.0-preview.1")
        }
    }

    @Test
    fun representationRequestOnlyResizesVisualDerivatives() {
        val preview = CameraImportJsonCodecV1.decodeRepresentationRequest(
            resourceText("fixtures/valid/representation-request-preview.json"),
        )
        assertEquals(2048, preview.targetSize?.maximumWidth)
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(representation = CameraImportRepresentation.ORIGINAL)
        }
    }

    @Test
    fun representationCollectionsRejectDuplicates() {
        assertThrows(IllegalArgumentException::class.java) {
            validDescriptor(
                mediaId = "media-demo-0001",
                representations = listOf(CameraImportRepresentation.ORIGINAL, CameraImportRepresentation.ORIGINAL),
            )
        }
    }

    @Test
    fun onlyStrongFullOriginalChecksumSupportsExactDuplicate() {
        val md5 = CameraImportSourceChecksumV1(
            algorithm = CameraImportChecksumAlgorithm.MD5,
            value = "a".repeat(32),
            scope = CameraImportChecksumScope.FULL_ORIGINAL,
        )
        val representationHash = CameraImportSourceChecksumV1(
            algorithm = CameraImportChecksumAlgorithm.SHA_256,
            value = "a".repeat(64),
            scope = CameraImportChecksumScope.FULL_REPRESENTATION,
        )
        assertFalse(md5.supportsExactDuplicate)
        assertFalse(representationHash.supportsExactDuplicate)
        assertThrows(IllegalArgumentException::class.java) {
            CameraImportSourceChecksumV1(
                algorithm = CameraImportChecksumAlgorithm.SHA_256,
                value = "\u0661".repeat(64),
                scope = CameraImportChecksumScope.FULL_ORIGINAL,
            )
        }
    }

    private fun validDescriptor(
        mediaId: String,
        representations: List<CameraImportRepresentation> = listOf(CameraImportRepresentation.ORIGINAL),
        providerVersion: String = "0.5.0",
    ) = CameraImportMediaDescriptorV1(
        contractVersion = CAMERA_IMPORT_CONTRACT_VERSION,
        providerId = "dev.openeos.control",
        providerVersion = providerVersion,
        sessionId = "session-demo-0001",
        mediaId = mediaId,
        captureCorrelationId = null,
        capturedAt = null,
        filename = "DEMO.CR3",
        mediaKind = CameraImportMediaKind.RAW,
        mimeType = "image/x-canon-cr3",
        byteLength = null,
        sourceChecksum = null,
        sourceRevision = null,
        cameraId = "camera-session-0001",
        cameraModel = "Canon EOS Camera",
        storageId = "storage-session-0001",
        storageLabel = null,
        width = null,
        height = null,
        orientation = null,
        captureGroupHint = null,
        companionRole = null,
        availableRepresentations = representations,
        rangeSupported = false,
        resumeSupported = false,
        cancelSupported = true,
    )

    private fun resourceText(path: String): String {
        val resource = checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing fixture $path" }
        return resource.readText()
    }
}
