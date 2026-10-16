package dev.openeos.control.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.Base64
import org.json.JSONObject

/** Requires real Android ImageDecoder and MediaStore. JVM structure checks cannot replace this. */
@SdkSuppress(minSdkVersion = 29)
class AutomaticJpegGalleryValidationInstrumentedTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val model = "Automatic JPEG validation ${UUID.randomUUID()}"
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val jpeg = ByteArrayOutputStream().apply {
        Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).let { bitmap ->
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, android.graphics.Color.rgb((x * 17 + y * 3) % 256,
                    (x * 5 + y * 11) % 256, (x * 7 + y * 13) % 256))
            }
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, this))
            bitmap.recycle()
        }
    }.toByteArray()
    private val item = CameraMediaItem("synthetic-original", "SYNTHETIC.JPG", "image", jpeg.size.toLong(),
        contentType = "image/jpeg")

    @After fun removeOnlyOwnedRows() { rows().forEach { resolver.delete(it, null, null) } }

    @Test fun actualNativeDecodePublishesExactOriginalForMissingGenericAndJpegResponseTypes() = runBlocking {
        for (mime in listOf(null, "application/octet-stream", "image/jpeg")) {
            val delivered = DeliveredJpegStore()
            var finalized = 0
            var attempts = 0
            val uri = CameraMediaGalleryStore(resolver).save(model, item,
                onFinalized = { finalized++ }, requireAutomaticJpeg = true,
                onPublished = { delivered.recordPublished(camera(), item, it) }) { output ->
                attempts++
                assertTrue(delivered.state.value.entries.isEmpty())
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), mime)
            }
            assertEquals(1, attempts)
            assertEquals(1, finalized)
            assertEquals(digest(jpeg), delivered.state.value.entries.single().evidence.sha256)
            assertArrayEquals(jpeg, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertPublished(uri)
        }
    }

    @Test fun sharedRealJpegVectorsExerciseTheNativeDecoderSeparatelyFromStructure() = runBlocking {
        val vectors = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("jpeg-original-vectors.json").bufferedReader().use { it.readText() }).getJSONArray("byteVectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val bytes = Base64.getDecoder().decode(vector.getString("base64"))
            val media = item.copy(sizeBytes = bytes.size.toLong())
            val before = rows()
            val result = runCatching {
                CameraMediaGalleryStore(resolver).save(model, media, requireAutomaticJpeg = true) { output ->
                    output.write(bytes)
                    CameraMediaDownloadResult(media, bytes.size.toLong(), "image/jpeg")
                }
            }
            val accepted = vector.getBoolean("structuralValid") && vector.getBoolean("decodedValid")
            assertEquals(vector.getString("id"), accepted, result.isSuccess)
            if (accepted) {
                assertArrayEquals(bytes, resolver.openInputStream(result.getOrThrow())!!.use { it.readBytes() })
                assertPublished(result.getOrThrow())
            } else assertEquals(vector.getString("id"), before, rows())
        }
    }

    @Test fun sameLengthWrongFormatMissingEoiAndTrailingDataNeverPublishOrRegister() = runBlocking {
        val malformed = listOf(
            jpeg.copyOf().also { it[0] = 0 },
            jpeg.copyOf(jpeg.size - 2),
            jpeg + byteArrayOf(1),
            ByteArray(jpeg.size) { '<'.code.toByte() },
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        )
        malformed.forEach { bytes -> assertRejected(bytes) }
        listOf("", "image/png", "image/jpg", " ", "image/jpeg;", "image/jpeg\n").forEach {
            assertRejected(jpeg, it)
        }
    }

    @Test fun structuralEnvelopeAloneDoesNotAllowUndecodableEntropy() = runBlocking {
        val sos = (0 until jpeg.lastIndex).first { jpeg[it] == 0xff.toByte() && jpeg[it + 1] == 0xda.toByte() }
        val size = (jpeg[sos + 2].toInt() and 255) * 256 + (jpeg[sos + 3].toInt() and 255)
        val noImageData = jpeg.copyOf(sos + 2 + size) + byteArrayOf(0x3f, 0xff.toByte(), 0xd9.toByte())
        // It has all legal marker framing and one entropy byte, but cannot decode the frame.
        dev.openeos.control.data.validateAutomaticJpegStructure(noImageData.inputStream(),
            noImageData.size.toLong(), digest(noImageData))
        assertRejected(noImageData)
    }

    @Test fun cancellationAtLocalValidationCleansOnlyItsPendingRowAndKeepsPriorDeliveredOriginal() = runBlocking {
        val delivered = DeliveredJpegStore()
        var attempts = 0
        val prior = CameraMediaGalleryStore(resolver).save(model, item, requireAutomaticJpeg = true,
            onPublished = { delivered.recordPublished(camera(), item, it) }) { output ->
            attempts++
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        var published = 0
        var finalized = 0
        val stopped = CameraMediaGalleryStore(resolver, validateAutomaticOriginal = { _, _, _, _ ->
            throw CancellationException("Synthetic cancellation before decoder publication")
        })
        assertTrue(runCatching {
            stopped.save(model, item, requireAutomaticJpeg = true,
                onPublished = { published++ }, onFinalized = { finalized++ }) { output ->
                attempts++
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
            }
        }.exceptionOrNull() is CancellationException)
        assertEquals(2, attempts)
        assertEquals(0, published)
        assertEquals(0, finalized)
        assertEquals(listOf(prior.lastPathSegment), rows().map { it.lastPathSegment })
        assertEquals(prior, delivered.state.value.entries.single().uri)
        assertPublished(prior)
    }

    @Test fun validationAllocationFailureBecomesAnOrdinaryFailedTransferWithOwnedCleanup() = runBlocking {
        val memory = OutOfMemoryError("Synthetic bounded decode allocation failure")
        var completions = 0
        var publications = 0
        val store = CameraMediaGalleryStore(resolver, validateAutomaticOriginal = { _, _, _, _ -> throw memory })
        val failure = runCatching {
            store.save(model, item, requireAutomaticJpeg = true,
                onFinalized = { completions++ }, onPublished = { publications++ }) { output ->
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
            }
        }.exceptionOrNull()
        assertTrue(failure is dev.openeos.control.data.InvalidAutomaticJpegException)
        assertSame(memory, failure!!.cause)
        assertEquals(0, completions)
        assertEquals(0, publications)
        assertTrue(rows().isEmpty())
    }

    @Test fun oversizeFreshMetadataFailsBeforeInsertingOrRequestingTheOriginal() = runBlocking {
        var attempts = 0
        val before = rows()
        val oversized = item.copy(sizeBytes = dev.openeos.control.data.AutomaticJpegEnvelope.MAX_BYTES + 1L)
        assertNotNull(runCatching {
            CameraMediaGalleryStore(resolver).save(model, oversized, requireAutomaticJpeg = true) {
                attempts++
                error("An oversize original must not be requested")
            }
        }.exceptionOrNull())
        assertEquals(0, attempts)
        assertEquals(before, rows())
    }

    @Test fun decodeFailureRetiresOutputBeforeExplicitNewSaveAndDoesNotRetry() = runBlocking {
        var attempts = 0
        var decoderCalls = 0
        var publications = 0
        val failing = CameraMediaGalleryStore(resolver, validateAutomaticOriginal = { _, _, _, _ ->
            decoderCalls++
            throw java.io.IOException("Synthetic decoder rejection")
        })
        assertNotNull(runCatching {
            failing.save(model, item, requireAutomaticJpeg = true, onPublished = { publications++ }) { output ->
                attempts++
                output.write(jpeg)
                CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
            }
        }.exceptionOrNull())
        assertEquals(1, attempts)
        assertEquals(1, decoderCalls)
        assertEquals(0, publications)
        assertTrue(rows().isEmpty())
        val recovered = CameraMediaGalleryStore(resolver).save(model, item, requireAutomaticJpeg = true) { output ->
            attempts++
            output.write(jpeg)
            CameraMediaDownloadResult(item, jpeg.size.toLong(), "image/jpeg")
        }
        assertEquals(2, attempts)
        assertEquals(listOf(recovered.lastPathSegment), rows().map { it.lastPathSegment })
        assertPublished(recovered)
    }

    private suspend fun assertRejected(bytes: ByteArray, mime: String? = "image/jpeg") {
        val media = item.copy(sizeBytes = bytes.size.toLong())
        var attempts = 0
        var publications = 0
        var finalized = 0
        val before = rows()
        assertNotNull(runCatching {
            CameraMediaGalleryStore(resolver).save(model, media, requireAutomaticJpeg = true,
                onPublished = { publications++ }, onFinalized = { finalized++ }) { output ->
                attempts++
                output.write(bytes)
                CameraMediaDownloadResult(media, bytes.size.toLong(), mime)
            }
        }.exceptionOrNull())
        assertEquals(1, attempts)
        assertEquals(0, publications)
        assertEquals(0, finalized)
        assertEquals(before, rows())
    }

    private fun rows(): List<Uri> = resolver.query(
        collection.buildUpon().appendQueryParameter("includePending", "1").build(),
        arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(cameraGalleryPath(model)), null,
    )!!.use { cursor -> buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) } }

    private fun assertPublished(uri: Uri) {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }
    private fun camera() = CameraInfo(connected = true, model = model, serial = "SYNTHETIC", api = "fixture")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
