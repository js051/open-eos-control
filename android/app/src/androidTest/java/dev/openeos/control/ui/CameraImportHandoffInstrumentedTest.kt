package dev.openeos.control.ui

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.importing.CAMERA_IMPORT_ANDROID_HANDOFF_VERSION
import dev.openeos.control.importing.CAMERA_IMPORT_CONTRACT_VERSION
import dev.openeos.control.importing.CameraImportAndroidIntentV1
import dev.openeos.control.importing.CameraImportJsonCodecV1
import dev.openeos.control.importing.CameraImportOutcome
import dev.openeos.control.importing.CameraImportReceiptBatchV1
import dev.openeos.control.importing.CameraImportReceiptV1
import dev.openeos.control.importing.OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class CameraImportHandoffInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun stagedOriginalUsesStrictManifestAndReadOnlyIntentGrant() = runBlocking {
        val bytes = "synthetic-camera-original".toByteArray()
        val item = CameraMediaItem(
            id = "synthetic-media-0001",
            name = "DEMO0001.CR3",
            kind = "raw",
            sizeBytes = bytes.size.toLong(),
            captureTime = "2026-08-21T12:34:56+08:00",
            contentType = "image/x-canon-cr3",
        )
        val storage = CameraImportHandoffStorage(context)
        val session = storage.prepare(
            items = listOf(item),
            camera = CameraInfo(true, "Canon EOS R6 Mark III", "PRIVATE-SERIAL", "ccapi"),
            providerVersion = "0.5.0",
            onItem = { _, _, _ -> },
            onProgress = {},
        ) { media, output, progress ->
            output.write(bytes)
            progress(CameraMediaTransferProgress(bytes.size.toLong(), bytes.size.toLong()))
            CameraMediaDownloadResult(media, bytes.size.toLong(), media.contentType)
        }

        try {
            val manifestText = context.contentResolver.openInputStream(session.manifestUri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Manifest was not readable")
            val manifest = CameraImportJsonCodecV1.decodeAndroidHandoffManifest(manifestText)
            assertEquals(session.sessionId, manifest.sessionId)
            assertEquals(session.mediaIds.single(), manifest.items.single().descriptor.mediaId)
            assertTrue(manifest.items.single().descriptor.sourceChecksum?.supportsExactDuplicate == true)
            val originalUri = session.representationUris.single()
            assertFalse(originalUri.toString().contains(item.name))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(originalUri)?.use { it.readBytes() })

            val secondSession = storage.prepare(
                items = listOf(item),
                camera = CameraInfo(true, "Canon EOS R6 Mark III", "PRIVATE-SERIAL", "ccapi"),
                providerVersion = "0.5.0",
                onItem = { _, _, _ -> },
                onProgress = {},
            ) { media, output, _ ->
                output.write(bytes)
                CameraMediaDownloadResult(media, bytes.size.toLong(), media.contentType)
            }
            try {
                val secondManifest = context.contentResolver.openInputStream(secondSession.manifestUri)
                    ?.bufferedReader()
                    ?.use { CameraImportJsonCodecV1.decodeAndroidHandoffManifest(it.readText()) }
                    ?: error("Second manifest was not readable")
                assertEquals(
                    manifest.items.single().descriptor.cameraId,
                    secondManifest.items.single().descriptor.cameraId,
                )
                assertEquals(
                    manifest.items.single().descriptor.mediaId,
                    secondManifest.items.single().descriptor.mediaId,
                )
                assertFalse(secondManifest.toString().contains("PRIVATE-SERIAL"))
            } finally {
                storage.cleanup(secondSession.sessionId)
            }

            val intent = SereinImportIntents.create(session)
            assertEquals(CameraImportAndroidIntentV1.ACTION, intent.action)
            assertEquals(CameraImportAndroidIntentV1.OPEN_NEGATIVE_PACKAGE, intent.`package`)
            assertEquals(CameraImportAndroidIntentV1.MIME_TYPE, intent.type)
            assertEquals(session.manifestUri, intent.data)
            assertEquals(2, intent.clipData?.itemCount)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)

            val availabilityProbe = SereinImportIntents.availabilityProbe()
            assertEquals(CameraImportAndroidIntentV1.ACTION, availabilityProbe.action)
            assertEquals(CameraImportAndroidIntentV1.OPEN_NEGATIVE_PACKAGE, availabilityProbe.`package`)
            assertEquals(CameraImportAndroidIntentV1.MIME_TYPE, availabilityProbe.type)
            assertEquals("content", availabilityProbe.data?.scheme)
        } finally {
            storage.cleanup(session.sessionId)
        }
    }

    @Test
    fun receiptMustCoverTheExactHandoffSession() = runBlocking {
        val storage = CameraImportHandoffStorage(context)
        val bytes = byteArrayOf(1, 2, 3)
        val item = CameraMediaItem("synthetic-media-0002", "DEMO0002.JPG", "image", bytes.size.toLong())
        val session = storage.prepare(
            items = listOf(item),
            camera = CameraInfo(true, "Canon EOS Camera", "PRIVATE-SERIAL", "ccapi"),
            providerVersion = "0.5.0",
            onItem = { _, _, _ -> },
            onProgress = {},
        ) { media, output, _ ->
            output.write(bytes)
            CameraMediaDownloadResult(media, bytes.size.toLong(), "image/jpeg")
        }
        try {
            val receipt = CameraImportReceiptV1(
                contractVersion = CAMERA_IMPORT_CONTRACT_VERSION,
                importId = "import-demo-0002",
                providerId = OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID,
                sessionId = session.sessionId,
                mediaId = session.mediaIds.single(),
                outcome = CameraImportOutcome.IMPORTED,
                assetId = "asset-demo-0002",
                blobSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
                byteLength = bytes.size.toLong(),
                captureGroupId = "group-demo-0002",
                preservedRepresentationIds = listOf("blob-demo-original-0002"),
                safeErrorCode = null,
                completedAt = "2026-08-21T12:35:12+08:00",
            )
            val batch = CameraImportReceiptBatchV1(
                handoffVersion = CAMERA_IMPORT_ANDROID_HANDOFF_VERSION,
                contractVersion = CAMERA_IMPORT_CONTRACT_VERSION,
                providerId = OPEN_EOS_CAMERA_IMPORT_PROVIDER_ID,
                sessionId = session.sessionId,
                receipts = listOf(receipt),
            )
            val receiptFile = File(context.cacheDir, "camera-import/${session.sessionId}/receipt.json")
            receiptFile.writeText(
                CameraImportJsonCodecV1.encodeReceiptBatch(
                    batch.copy(receipts = listOf(receipt.copy(blobSha256 = "b".repeat(64)))),
                ),
            )
            val receiptUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.camera_import",
                receiptFile,
            )
            assertThrows(IllegalArgumentException::class.java) {
                readCameraImportReceiptBatch(context, receiptUri, session)
            }

            receiptFile.writeText(CameraImportJsonCodecV1.encodeReceiptBatch(batch))
            val summary = readCameraImportReceiptBatch(context, receiptUri, session)
            assertEquals(1, summary.completed)
            assertEquals(0, summary.failed)
        } finally {
            storage.cleanup(session.sessionId)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
