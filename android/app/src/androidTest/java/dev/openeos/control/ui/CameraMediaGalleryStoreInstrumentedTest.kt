package dev.openeos.control.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CcapiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
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

    @Test fun cancellationImmediatelyAfterPublicationKeepsTheOriginalAndOneCompletion() = runBlocking {
        val owner = Job()
        var completions = 0
        val save = launch(owner) {
            store.save(model, item, onFinalized = {
                completions++
                owner.cancel()
            }) { output ->
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
            }
        }
        save.join()
        assertTrue(save.isCancelled)
        assertEquals(1, completions)
        val uri = testUris().single()
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        assertArrayEquals(jpeg, resolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun failingCompletionObserverCannotRemoveOrFailPublishedOriginal() = runBlocking {
        var completions = 0
        val uri = store.save(model, item, onFinalized = {
            completions++
            throw IOException("Synthetic history observer failure")
        }) { output ->
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        assertEquals(1, completions)
        assertEquals(listOf(uri.lastPathSegment), testUris().map { it.lastPathSegment })
        assertArrayEquals(jpeg, resolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun allBytesWrittenButLengthValidationFailedDoesNotReportCompletion() = runBlocking {
        var completions = 0
        val failure = runCatching {
            store.save(model, item, onFinalized = { completions++ }) { output ->
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong() + 1L, "image/jpeg")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, completions)
        assertTrue(testUris().isEmpty())
    }

    @Test fun publicationEvidenceMatchesTheAlreadyPublishedRealRowAndExactOriginalDigest() = runBlocking {
        val delivered = DeliveredJpegStore()
        val camera = fixtureCamera()
        var observed: PublishedGalleryOriginal? = null
        var completions = 0
        val uri = store.save(
            model, item,
            onFinalized = {
                completions++
                assertEquals(1, delivered.state.value.entries.size)
            },
            onPublished = { evidence ->
                observed = evidence
                resolver.query(evidence.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                // This read is a test assertion only; the production observer performs no IO.
                assertArrayEquals(jpeg, resolver.openInputStream(evidence.uri)!!.use { it.readBytes() })
                delivered.recordPublished(camera, item, evidence)
            },
        ) { output ->
            assertTrue(delivered.state.value.entries.isEmpty())
            output.write(jpeg[0].toInt())
            output.write(jpeg, 1, jpeg.size - 1)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        val evidence = requireNotNull(observed)
        assertEquals(uri, evidence.uri)
        assertEquals(jpeg.size.toLong(), evidence.byteLength)
        assertEquals(jpegDigest(), evidence.sha256)
        assertEquals("image/jpeg", evidence.mimeType)
        assertEquals(1, completions)
        assertEquals(uri, delivered.state.value.entries.single().uri)
        assertEquals(CameraImportOriginalEvidence(jpeg.size.toLong(), jpegDigest()), delivered.state.value.entries.single().evidence)
    }

    @Test fun cancelledSuspendReturnRetainsPublishedOriginalAndOneRegistryEntry() = runBlocking {
        val delivered = DeliveredJpegStore()
        val owner = Job()
        val publishingStore = CameraMediaGalleryStore(resolver, publishOutput = { uri, values ->
            resolver.update(uri, values, null, null).also { published ->
                assertEquals(1, published)
                // Cancel at the actual irreversible boundary, before onPublished or confirm.
                owner.cancel()
            }
        })
        var original: PublishedGalleryOriginal? = null
        var completions = 0
        val save = launch(owner) {
            publishingStore.save(
                model, item,
                onFinalized = { completions++ },
                onPublished = { evidence ->
                    assertTrue(owner.isCancelled)
                    original = evidence
                    delivered.recordPublished(fixtureCamera(), item, evidence)
                },
            ) { output ->
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
            }
            fail("Cancellation must prevent the IO result from returning normally.")
        }
        save.join()
        assertTrue(save.isCancelled)
        assertEquals(1, completions)
        val entry = delivered.state.value.entries.single()
        assertEquals(entry.id, delivered.recordPublished(fixtureCamera(), item, requireNotNull(original)))
        assertEquals(1, delivered.state.value.entries.size)
        assertEquals(jpegDigest(), entry.evidence.sha256)
        assertEquals(listOf(entry.uri.lastPathSegment), testUris().map { it.lastPathSegment })
        assertPublishedOriginal(entry.uri)
    }

    @Test fun failingRegistryAndAvailabilityObserversStillRunMandatoryFinalization() = runBlocking {
        var publications = 0
        var unavailable = 0
        var completions = 0
        val uri = store.save(
            model, item,
            onFinalized = { completions++ },
            onPublished = {
                publications++
                throw IOException("Synthetic registry observer failure")
            },
            onPublicationObserverFailure = {
                unavailable++
                throw IOException("Synthetic availability observer failure")
            },
        ) { output ->
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        assertEquals(1, publications)
        assertEquals(1, unavailable)
        assertEquals(1, completions)
        assertPublishedOriginal(uri)
    }

    @Test fun failingHistoryObserverRetainsRegistrationAndPublishedOriginal() = runBlocking {
        val delivered = DeliveredJpegStore()
        var publicationSuccess = 0
        var historyCompletions = 0
        var unavailable = 0
        val uri = store.save(
            model, item,
            onFinalized = {
                publicationSuccess++
                historyCompletions++
                throw IOException("Synthetic history observer failure")
            },
            onPublished = { delivered.recordPublished(fixtureCamera(), item, it) },
            onPublicationObserverFailure = { unavailable++ },
        ) { output ->
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        assertEquals(1, publicationSuccess)
        assertEquals(1, historyCompletions)
        assertEquals(0, unavailable)
        assertEquals(uri, delivered.state.value.entries.single().uri)
        assertPublishedOriginal(uri)
    }

    @Test fun registryFailureSurfacesAvailabilityWithoutFailingGallerySuccess() = runBlocking {
        val delivered = DeliveredJpegStore()
        var completions = 0
        val uri = store.save(
            model, item,
            onFinalized = { completions++ },
            onPublished = { throw IOException("Synthetic unavailable registry") },
            onPublicationObserverFailure = delivered::markRegistrationUnavailable,
        ) { output ->
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        assertEquals(1, completions)
        assertTrue(delivered.state.value.entries.isEmpty())
        assertTrue(delivered.state.value.registrationUnavailable)
        assertPublishedOriginal(uri)
    }

    @Test fun cancelledTruncatedAndMismatchedOutputsNeverRegister() = runBlocking {
        val delivered = DeliveredJpegStore()
        var publications = 0
        var completions = 0
        val scenarios: List<suspend (OutputStream) -> CameraMediaDownloadResult> = listOf(
            { output -> output.write(jpeg, 0, 10); throw CancellationException("Synthetic transfer cancellation") },
            { output -> output.write(jpeg, 0, 10); throw IOException("Synthetic transfer failure") },
            { output -> output.write(jpeg, 0, 10); CameraMediaDownloadResult(item, 10L, "image/jpeg") },
            { output -> output.write(jpeg); CameraMediaDownloadResult(item, jpeg.size.toLong() + 1L, "image/jpeg") },
            { _ -> CameraMediaDownloadResult(item, 0L, "image/jpeg") },
        )
        scenarios.forEach { download ->
            val failure = runCatching {
                store.save(
                    model, item,
                    onFinalized = { completions++ },
                    onPublished = {
                        publications++
                        delivered.recordPublished(fixtureCamera(), item, it)
                    },
                    download = download,
                )
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(testUris().isEmpty())
            assertTrue(delivered.state.value.entries.isEmpty())
        }
        assertEquals(0, publications)
        assertEquals(0, completions)
    }

    @Test fun writeCloseAndPublishFailuresRemoveTheRealPendingRowWithoutRegistration() = runBlocking {
        val delivered = DeliveredJpegStore()
        var publications = 0
        var completions = 0
        val failingStores = listOf(
            CameraMediaGalleryStore(resolver, openOutput = { uri ->
                object : FilterOutputStream(resolver.openOutputStream(uri, "w")!!) {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        out.write(buffer, offset, minOf(10, length))
                        throw IOException("Synthetic output write failure")
                    }
                }
            }),
            CameraMediaGalleryStore(resolver, openOutput = { uri ->
                object : FilterOutputStream(resolver.openOutputStream(uri, "w")!!) {
                    override fun close() {
                        super.close()
                        throw IOException("Synthetic output close failure")
                    }
                }
            }),
            CameraMediaGalleryStore(resolver, publishOutput = { _, _ -> 0 }),
            CameraMediaGalleryStore(resolver, publishOutput = { _, _ -> throw IOException("Synthetic publish failure") }),
        )
        failingStores.forEach { failingStore ->
            val failure = runCatching {
                failingStore.save(
                    model, item,
                    onFinalized = { completions++ },
                    onPublished = {
                        publications++
                        delivered.recordPublished(fixtureCamera(), item, it)
                    },
                ) { output ->
                    output.write(jpeg)
                    CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(testUris().isEmpty())
            assertTrue(delivered.state.value.entries.isEmpty())
        }
        assertEquals(0, publications)
        assertEquals(0, completions)
    }

    @Test fun nonJpegGallerySavesDoNotProduceJpegEvidenceOrInvokeObserver() = runBlocking {
        var publications = 0
        var completions = 0
        listOf("MP4", "CR2").forEach { extension ->
            val media = item.copy(name = "TEST.$extension", contentType = "application/octet-stream")
            val uri = store.save(
                model, media,
                onFinalized = { completions++ },
                onPublished = { publications++ },
            ) { output ->
                output.write(jpeg)
                CameraMediaDownloadResult(media, jpeg.size.toLong(), null)
            }
            assertPublishedOriginal(uri)
        }
        assertEquals(0, publications)
        assertEquals(2, completions)
    }

    @Test fun equalNamesRegisterSeparateRealGalleryCopies() = runBlocking {
        val delivered = DeliveredJpegStore()
        repeat(2) {
            store.save(model, item, onPublished = { delivered.recordPublished(fixtureCamera(), item, it) }) { output ->
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
            }
        }
        val entries = delivered.state.value.entries
        assertEquals(2, entries.size)
        assertNotEquals(entries[0].id, entries[1].id)
        assertNotEquals(entries[0].uri, entries[1].uri)
        assertEquals(entries[0].evidence, entries[1].evidence)
        entries.forEach { assertPublishedOriginal(it.uri) }
    }

    private fun fixtureCamera() = CameraInfo(true, model, "SYNTHETIC-TEST-SERIAL", "fixture")

    private fun jpegDigest(): String = MessageDigest.getInstance("SHA-256").digest(jpeg)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertPublishedOriginal(uri: Uri) {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        assertArrayEquals(jpeg, resolver.openInputStream(uri)!!.use { it.readBytes() })
    }
}
