package dev.openeos.control.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CcapiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.UUID

@SdkSuppress(minSdkVersion = 29)
class CameraMediaGalleryStoreInstrumentedTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val model = "Open EOS Control Test ${UUID.randomUUID()}"
    private val store = CameraMediaGalleryStore(resolver)
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val jpeg = ByteArrayOutputStream().apply {
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).let {
            it.eraseColor(android.graphics.Color.CYAN)
            it.compress(Bitmap.CompressFormat.JPEG, 90, this)
            it.recycle()
        }
    }.toByteArray().let { bytes ->
        val file = File.createTempFile("gallery-fixture", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(bytes)
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:08:10 10:00:00")
                setAttribute("OffsetTimeOriginal", "+00:00")
                setAttribute(ExifInterface.TAG_DATETIME, "2026:08:10 10:00:00")
                saveAttributes()
            }
            file.readBytes()
        } finally {
            file.delete()
        }
    }
    private val item = CameraMediaItem(
        "fixture", "IMG_0001.JPG", "image", jpeg.size.toLong(), "2026-08-10T10:00:00Z",
    )

    private fun testUris(): List<Uri> = resolver.query(
        collection.buildUpon().appendQueryParameter("includePending", "1").build(), arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(cameraGalleryPath(model)), null,
    )!!.use { cursor -> buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) } }

    @After fun cleanupOnlyThisTestsMedia() {
        testUris().forEach { resolver.delete(it, null, null) }
    }

    @Test fun jpegIsPendingUntilCompleteThenPublishedWithOriginalBytesAndDate() = runBlocking {
        val uri = store.save(model, item) { output ->
            val pending = testUris().single()
            resolver.query(pending, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATE_TAKEN), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(cameraGalleryPath(model), it.getString(0))
            assertEquals(0, it.getInt(1))
            assertEquals(item.captureTime.toMediaInstant()!!.toEpochMilli(), it.getLong(2))
        }
        assertArrayEquals(jpeg, resolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun identicalNamesDoNotOverwriteExistingDownload() = runBlocking {
        suspend fun save() = store.save(model, item) { output ->
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), null)
        }
        val first = save()
        val second = save()
        assertNotEquals(first, second)
        assertEquals(2, testUris().size)
        assertArrayEquals(jpeg, resolver.openInputStream(first)!!.use { it.readBytes() })
    }

    @Test fun cancelledAndTruncatedTransfersLeaveNoGalleryEntry() = runBlocking {
        listOf(CancellationException("fixture cancel"), IOException("fixture timeout")).forEach { failure ->
            val result = runCatching {
                store.save(model, item) { output ->
                    output.write(jpeg, 0, 10)
                    throw failure
                }
            }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(testUris().isEmpty())
        }
        val result = runCatching {
            store.save(model, item) { output ->
                output.write(jpeg, 0, 10)
                CameraMediaDownloadResult(item, 10L, null)
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(testUris().isEmpty())
    }

    @Test fun retriedTransferPublishesOnlyOneCompleteEntry() = runBlocking {
        var attempts = 0
        retryMediaRead(longArrayOf(0)) {
            store.save(model, item) { output ->
                attempts++
                if (attempts == 1) {
                    output.write(0)
                    throw IOException("fixture transport failure")
                }
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), null)
            }
        }
        assertEquals(2, attempts)
        assertEquals(1, testUris().size)
    }

    @Test fun supportedOriginalsUseTheSameModelFolderWithoutTranscoding() = runBlocking {
        // Synthetic format bodies test storage, not a codec or a physical camera.
        listOf("MP4" to "video/mp4", "CR2" to "image/x-canon-cr2").forEach { (extension, mime) ->
            val media = item.copy(name = "TEST_0001.$extension", contentType = "application/octet-stream")
            val uri = store.save(model, media) { output ->
                output.write(jpeg)
                CameraMediaDownloadResult(media, jpeg.size.toLong(), null)
            }
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(cameraGalleryPath(model), it.getString(0))
                assertTrue(it.getString(1).endsWith(".$extension"))
            }
            assertEquals(mime, galleryMimeType(media))
            assertArrayEquals(jpeg, resolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun unrecognizedRawRequiresDocumentDestinationWithoutCreatingPartialEntry() = runBlocking {
        val raw = item.copy(name = "TEST.CR3")
        if (!canSaveMediaToGallery(raw)) {
            var invoked = false
            val result = runCatching {
                store.save(model, raw) {
                    invoked = true
                    CameraMediaDownloadResult(raw, 0L, null)
                }
            }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertFalse(invoked)
            assertTrue(testUris().isEmpty())
        }
    }

    @Test fun ccapiOriginalStreamPublishesToAndroidGallery() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            var received = 0L
            val uri = store.save(model, item) { output ->
                client.downloadMedia(item, output) { received = it.bytesTransferred }
            }
            assertEquals("/ccapi/media/fixture", server.takeRequest().path)
            assertEquals(jpeg.size.toLong(), received)
            assertArrayEquals(jpeg, resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            server.shutdown()
        }
    }
}
