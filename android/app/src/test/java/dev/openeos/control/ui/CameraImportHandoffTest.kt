package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.importing.CameraImportChecksumAlgorithm
import dev.openeos.control.importing.CameraImportChecksumScope
import dev.openeos.control.importing.CameraImportCompanionRole
import dev.openeos.control.importing.CameraImportMediaKind
import dev.openeos.control.importing.CameraImportSourceChecksumV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CameraImportHandoffTest {
    @Test
    fun descriptorSanitizesSourceIdentityAndGroupsRawJpegCompanions() {
        val camera = CameraInfo(
            connected = true,
            model = "Canon EOS R6 Mark III",
            serial = "PRIVATE-SERIAL",
            api = "ccapi",
        )
        val raw = item("camera/path/DEMO0001.CR3", "DEMO0001.CR3").toImportDescriptor(
            sessionId = "session-demo-0001",
            mediaId = "media-demo-raw-0001",
            camera = camera,
            opaqueCameraId = "camera-demo-0001",
            opaqueStorageId = "storage-demo-0001",
            providerVersion = "0.5.0",
            byteLength = 2048,
            checksum = checksum(),
        )
        val jpeg = item("camera/path/DEMO0001.JPG", "DEMO0001.JPG").toImportDescriptor(
            sessionId = "session-demo-0001",
            mediaId = "media-demo-jpeg-0001",
            camera = camera,
            opaqueCameraId = "camera-demo-0001",
            opaqueStorageId = "storage-demo-0001",
            providerVersion = "0.5.0",
            byteLength = 1024,
            checksum = checksum(),
        )

        assertEquals(CameraImportMediaKind.RAW, raw.mediaKind)
        assertEquals(CameraImportCompanionRole.RAW, raw.companionRole)
        assertEquals(CameraImportMediaKind.JPEG, jpeg.mediaKind)
        assertEquals(CameraImportCompanionRole.JPEG, jpeg.companionRole)
        assertEquals(raw.captureGroupHint, jpeg.captureGroupHint)
        assertEquals(raw.captureCorrelationId, jpeg.captureCorrelationId)
        assertEquals("camera-demo-0001", raw.cameraId)
        assertEquals("storage-demo-0001", raw.storageId)
        assertNull(raw.capturedAt)
        assertFalse(raw.toString().contains(camera.serial))
        assertFalse(raw.sourceRevision.orEmpty().contains("camera/path"))
    }

    @Test
    fun receiptSummaryDoesNotCountFailuresAsCompletedImports() {
        val summary = CameraImportReceiptSummary(imported = 2, duplicates = 1, failed = 1, cancelled = 1)
        assertEquals(3, summary.completed)
    }

    private fun item(id: String, name: String) = CameraMediaItem(
        id = id,
        name = name,
        kind = "image",
        sizeBytes = 1024,
        captureTime = "2026-08-21 12:34:56",
        contentType = null,
        widthPixels = 6000,
        heightPixels = 4000,
        rotationDegrees = 90,
    )

    private fun checksum() = CameraImportSourceChecksumV1(
        algorithm = CameraImportChecksumAlgorithm.SHA_256,
        value = "a".repeat(64),
        scope = CameraImportChecksumScope.FULL_ORIGINAL,
    )
}
