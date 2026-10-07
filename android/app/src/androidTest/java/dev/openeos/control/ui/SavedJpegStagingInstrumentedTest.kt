package dev.openeos.control.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.importing.CameraImportChecksumAlgorithm
import dev.openeos.control.importing.CameraImportChecksumScope
import dev.openeos.control.importing.CameraImportJsonCodecV1
import dev.openeos.control.importing.CameraImportMediaKind
import dev.openeos.control.importing.CameraImportRepresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Real Android storage tests with generated JPEGs, not physical-camera or receiver validation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SavedJpegStagingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    @Test
    fun sameNamesFromDifferentCamerasPreserveEachPublicationAndExactBytes() = runBlocking {
        withOwnedFixtures {
            val originals = listOf(
                publish(cameraNumber = 1, color = Color.CYAN),
                publish(cameraNumber = 2, color = Color.MAGENTA),
                publish(cameraNumber = 1, color = Color.YELLOW),
            )
            assertEquals(1, originals.map { it.saved.item.name }.toSet().size)
            assertEquals(1, originals.map { it.saved.item.id }.toSet().size)
            assertEquals(3, originals.map { it.saved.uri }.toSet().size)
            val storage = CameraImportHandoffStorage(context)
            val reservation = reserve(storage)
            val announcedItems = mutableListOf<Int>()
            val session = storage.preparePublishedJpegs(
                originals.map { it.saved }, reservation, PROVIDER_VERSION,
                onItem = { index, total, name ->
                    assertEquals(3, total)
                    assertEquals("DUPLICATE.JPG", name)
                    announcedItems += index
                },
                onProgress = {},
            )
            val manifestText = readBytes(session.manifestUri).toString(Charsets.UTF_8)
            val manifest = CameraImportJsonCodecV1.decodeAndroidHandoffManifest(manifestText)
            assertEquals(listOf(0, 1, 2), announcedItems)
            assertEquals(reservation.sessionId, session.sessionId)
            assertEquals(session.sessionId, manifest.sessionId)
            assertEquals(3, session.itemCount)
            assertEquals(3, session.mediaIds.size)
            assertEquals(3, session.representationUris.toSet().size)
            assertEquals(3, manifest.items.size)
            assertEquals(manifest.items[0].descriptor.cameraId, manifest.items[2].descriptor.cameraId)
            assertNotEquals(manifest.items[0].descriptor.cameraId, manifest.items[1].descriptor.cameraId)
            assertEquals(manifest.items.map { it.descriptor.mediaId }.toSet(), session.mediaIds)
            originals.zip(manifest.items).forEachIndexed { index, (original, staged) ->
                val descriptor = staged.descriptor
                val expected = CameraImportOriginalEvidence(original.bytes.size.toLong(), sha256(original.bytes))
                assertEquals(expected, original.saved.evidence)
                assertEquals(expected, session.expectedOriginals[descriptor.mediaId])
                assertEquals("DUPLICATE.JPG", descriptor.filename)
                assertEquals(original.saved.camera.model, descriptor.cameraModel)
                assertEquals(CameraImportMediaKind.JPEG, descriptor.mediaKind)
                assertEquals("image/jpeg", descriptor.mimeType)
                assertEquals(expected.byteLength, descriptor.byteLength)
                val checksum = requireNotNull(descriptor.sourceChecksum)
                assertEquals(CameraImportChecksumAlgorithm.SHA_256, checksum.algorithm)
                assertEquals(CameraImportChecksumScope.FULL_ORIGINAL, checksum.scope)
                assertEquals(expected.sha256, checksum.value)
                val representation = staged.representations.single()
                assertEquals(CameraImportRepresentation.ORIGINAL, representation.representation)
                val stagedUri = Uri.parse(representation.contentUri)
                assertEquals(session.representationUris[index], stagedUri)
                assertNotEquals(original.saved.uri, stagedUri)
                assertArrayEquals(original.bytes, readBytes(stagedUri))
                assertFalse(manifestText.contains(original.saved.uri.toString()))
                assertFalse(manifestText.contains(original.saved.camera.serial))
                assertOriginal(original)
            }
            assertTrue(storage.cleanup(reservation))
            assertFalse(reservation.cleanupUnconfirmed)
            assertFalse(directory(reservation).exists())
            originals.forEach { assertOriginal(it) }
        }
    }

    @Test
    fun pendingOriginalIsRejectedWithoutDeletingTheGalleryRow() = runBlocking {
        withOwnedFixtures {
            val original = publish()
            assertEquals(1, resolver.update(original.saved.uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }, null, null))
            val storage = CameraImportHandoffStorage(context)
            val reservation = reserve(storage)
            var progressCalls = 0
            val failure = runCatching {
                storage.preparePublishedJpegs(
                    listOf(original.saved), reservation, PROVIDER_VERSION,
                    onItem = { _, _, _ -> }, onProgress = { progressCalls++ },
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(0, progressCalls)
            assertFalse(directory(reservation).exists())
            assertFalse(reservation.cleanupUnconfirmed)
            assertOriginal(original, pending = 1)
        }
    }

    @Test
    fun deletedOriginalRejectsBatchAndCleansOnlyItsOwnedStagingDirectory() = runBlocking {
        withOwnedFixtures {
            val retained = publish()
            val deleted = publish(cameraNumber = 2)
            deleteOriginal(deleted)
            val storage = CameraImportHandoffStorage(context)
            val reservation = reserve(storage)
            val unrelated = reserve(storage)
            assertTrue(directory(unrelated).mkdirs())
            val marker = File(directory(unrelated), "owned-test-marker")
            marker.writeText("unrelated reserved session")
            var sawCompletedFirstCopy = false
            val failure = runCatching {
                storage.preparePublishedJpegs(
                    listOf(retained.saved, deleted.saved), reservation, PROVIDER_VERSION,
                    onItem = { index, _, _ ->
                        if (index == 1) {
                            val staged = File(directory(reservation), "media-${retained.saved.id.value}.jpg")
                            assertArrayEquals(retained.bytes, staged.readBytes())
                            sawCompletedFirstCopy = true
                        }
                    },
                    onProgress = {},
                )
            }.exceptionOrNull()
            assertTrue(failure is Exception)
            assertTrue(sawCompletedFirstCopy)
            assertFalse(directory(reservation).exists())
            assertFalse(reservation.cleanupUnconfirmed)
            assertEquals("unrelated reserved session", marker.readText())
            assertOriginal(retained)
            resolver.query(deleted.saved.uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
                assertEquals(0, it.count)
            }
        }
    }

    @Test
    fun sameLengthChangedOriginalIsRejectedWithoutDeletingOrRestoringItsBytes() = runBlocking {
        withOwnedFixtures {
            val original = publish()
            val changed = original.bytes.copyOf().apply {
                val offset = size / 2
                this[offset] = (this[offset].toInt() xor 1).toByte()
            }
            assertEquals(original.bytes.size, changed.size)
            assertNotEquals(sha256(original.bytes), sha256(changed))
            requireNotNull(resolver.openOutputStream(original.saved.uri, "rwt")).use { it.write(changed) }
            assertOriginal(original, bytes = changed)
            val storage = CameraImportHandoffStorage(context)
            val reservation = reserve(storage)
            val failure = runCatching {
                storage.preparePublishedJpegs(
                    listOf(original.saved), reservation, PROVIDER_VERSION,
                    onItem = { _, _, _ -> }, onProgress = {},
                )
            }.exceptionOrNull()
            assertTrue(failure is SavedJpegSourceChangedException)
            assertFalse(directory(reservation).exists())
            assertFalse(reservation.cleanupUnconfirmed)
            assertEquals(sha256(original.bytes), original.saved.evidence.sha256)
            assertOriginal(original, bytes = changed)
        }
    }

    @Test
    fun failedCleanupKeepsReservationAndOriginalFailureUntilExplicitRetrySucceeds() = runBlocking {
        withOwnedFixtures {
            val original = publish()
            // Exercise both a failed deletion result and a thrown filesystem error.
            listOf(false, true).forEach { throwOnDelete ->
                var allowDelete = false
                val attemptedDirectories = mutableListOf<File>()
                val storage = CameraImportHandoffStorage(context, deleteSessionDirectory = { owned ->
                    attemptedDirectories += owned
                    when {
                        allowDelete -> owned.deleteRecursively()
                        throwOnDelete -> throw IOException("Synthetic cache cleanup failure")
                        else -> false
                    }
                })
                val reservation = reserve(storage)
                val expectedFailure = IOException("Synthetic staging progress failure")
                val failure = runCatching {
                    storage.preparePublishedJpegs(
                        listOf(original.saved), reservation, PROVIDER_VERSION,
                        onItem = { _, _, _ -> }, onProgress = { throw expectedFailure },
                    )
                }.exceptionOrNull()
                assertSame(expectedFailure, failure)
                assertTrue(reservation.cleanupUnconfirmed)
                assertTrue(directory(reservation).exists())
                assertTrue(directory(reservation).listFiles().orEmpty().any { it.name.endsWith(".partial") })
                assertFalse(storage.cleanup(reservation))
                assertTrue(reservation.cleanupUnconfirmed)
                allowDelete = true
                assertTrue(storage.cleanup(reservation))
                assertFalse(reservation.cleanupUnconfirmed)
                assertFalse(directory(reservation).exists())
                assertEquals(3, attemptedDirectories.size)
                assertTrue(attemptedDirectories.all { it.canonicalFile == directory(reservation).canonicalFile })
                assertOriginal(original)
            }
        }
    }

    @Test
    fun cancellationAfterCompleteIoResultBeforeCallerResumeCleansReservedSession() = runBlocking {
        withOwnedFixtures {
            val original = publish()
            listOf(false, true).forEach { failFirstCleanup ->
                var allowDelete = !failFirstCleanup
                val storage = CameraImportHandoffStorage(context, deleteSessionDirectory = { owned ->
                    allowDelete && owned.deleteRecursively()
                })
                val reservation = reserve(storage)
                val caller = QueuedCallerDispatcher()
                val scope = CoroutineScope(caller)
                val callerSuspended = CountDownLatch(1)
                var returnedNormally = false
                val preparation = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    storage.preparePublishedJpegs(
                        listOf(original.saved), reservation, PROVIDER_VERSION,
                        onItem = { _, _, _ ->
                            check(callerSuspended.await(20, TimeUnit.SECONDS)) {
                                "The staging caller did not suspend in time."
                            }
                        },
                        onProgress = {},
                    ).also { returnedNormally = true }
                }
                try {
                    // Ensure IO cannot win the initial suspend race on a particularly fast device.
                    callerSuspended.countDown()
                    // The first queued dispatch is the return from the real Dispatchers.IO block.
                    // Hold it until all staged bytes and the final manifest are demonstrably ready.
                    caller.awaitQueuedReturn()
                    assertFalse(preparation.isCompleted)
                    val manifest = CameraImportJsonCodecV1.decodeAndroidHandoffManifest(
                        File(directory(reservation), "manifest.json").readText(),
                    )
                    assertEquals(reservation.sessionId, manifest.sessionId)
                    val stagedUri = Uri.parse(manifest.items.single().representations.single().contentUri)
                    assertArrayEquals(original.bytes, readBytes(stagedUri))
                    preparation.cancel(CancellationException("Synthetic cancellation at completed IO return"))
                    caller.runUntilCompleted(preparation)
                    assertTrue(preparation.isCancelled)
                    assertFalse(returnedNormally)
                    assertTrue(runCatching { preparation.await() }.exceptionOrNull() is CancellationException)
                    assertEquals(failFirstCleanup, directory(reservation).exists())
                    assertEquals(failFirstCleanup, reservation.cleanupUnconfirmed)
                    if (failFirstCleanup) {
                        assertArrayEquals(original.bytes, readBytes(stagedUri))
                        allowDelete = true
                        assertTrue(storage.cleanup(reservation))
                    }
                    assertFalse(directory(reservation).exists())
                    assertFalse(reservation.cleanupUnconfirmed)
                    assertOriginal(original)
                } finally {
                    callerSuspended.countDown()
                    scope.cancel()
                    caller.runUntilCompleted(preparation)
                }
            }
        }
    }

    private suspend fun withOwnedFixtures(block: suspend OwnedFixtures.() -> Unit) {
        val fixtures = OwnedFixtures()
        try {
            fixtures.block()
        } finally {
            fixtures.close()
        }
    }

    private inner class OwnedFixtures {
        private val fixtureId = UUID.randomUUID().toString()
        private val uris = mutableListOf<Uri>()
        private val reservations = mutableListOf<CameraImportStagingReservation>()
        private val delivered = DeliveredJpegStore()
        private val gallery = CameraMediaGalleryStore(resolver, openOutput = { uri ->
            // Track the exact inserted row before any write can fail; never enumerate Gallery.
            uris += uri
            resolver.openOutputStream(uri, "w")
        })

        suspend fun publish(cameraNumber: Int = 1, color: Int = Color.CYAN): PublishedFixture {
            val bytes = jpeg(color)
            val camera = CameraInfo(
                true, "Saved JPEG Test $fixtureId Camera $cameraNumber",
                "SYNTHETIC-SAVED-JPEG-CAMERA-$cameraNumber", "fixture",
            )
            val item = CameraMediaItem(
                "synthetic-shared-item", "DUPLICATE.JPG", "image", bytes.size.toLong(),
                "2026-10-01T12:00:00Z", contentType = "image/jpeg",
            )
            var publishedId: DeliveredJpegId? = null
            val uri = gallery.save(camera.model, item, onPublished = {
                publishedId = delivered.recordPublished(camera, item, it)
            }) { output ->
                output.write(bytes)
                CameraMediaDownloadResult(item, bytes.size.toLong(), "image/jpeg")
            }
            val saved = delivered.snapshot(setOf(requireNotNull(publishedId))).single()
            assertEquals(uri, saved.uri)
            return PublishedFixture(saved, bytes).also { assertOriginal(it) }
        }

        fun reserve(storage: CameraImportHandoffStorage): CameraImportStagingReservation =
            storage.reserveLocalSession().also { reservations += it }

        fun deleteOriginal(original: PublishedFixture) {
            val uri = original.saved.uri
            check(uri in uris)
            assertEquals(1, resolver.delete(uri, null, null))
            // Retire only a confirmed deletion. MediaStore may reject a second
            // delete once that exact row no longer establishes our ownership.
            check(uris.remove(uri))
        }

        fun close() {
            // Attempt every owned cleanup even if an earlier one fails.
            var cleanupFailure: Throwable? = null
            reservations.forEach { reservation ->
                runCatching {
                    val owned = directory(reservation)
                    check(!owned.exists() || owned.deleteRecursively())
                }.exceptionOrNull()?.let {
                    if (cleanupFailure == null) cleanupFailure = it else cleanupFailure!!.addSuppressed(it)
                }
            }
            uris.forEach { uri ->
                runCatching { resolver.delete(uri, null, null) }.exceptionOrNull()?.let {
                    if (cleanupFailure == null) cleanupFailure = it else cleanupFailure!!.addSuppressed(it)
                }
            }
            cleanupFailure?.let { throw it }
        }
    }

    private data class PublishedFixture(val saved: DeliveredJpeg, val bytes: ByteArray)

    private fun directory(reservation: CameraImportStagingReservation): File =
        File(context.cacheDir, "camera-import/${reservation.sessionId}")

    private fun readBytes(uri: Uri): ByteArray = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

    private fun assertOriginal(original: PublishedFixture, pending: Int = 0, bytes: ByteArray = original.bytes) {
        requireNotNull(resolver.query(original.saved.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertEquals(pending, it.getInt(0))
        }
        assertArrayEquals(bytes, readBytes(original.saved.uri))
    }

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private class QueuedCallerDispatcher : CoroutineDispatcher() {
        private val dispatched = CountDownLatch(1)
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
            dispatched.countDown()
        }

        fun awaitQueuedReturn() {
            check(dispatched.await(20, TimeUnit.SECONDS)) { "The staging IO result did not return in time." }
        }

        fun runUntilCompleted(job: Job) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!job.isCompleted) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "The cancelled staging task did not finish cleanup in time." }
                val task = checkNotNull(tasks.poll(remaining, TimeUnit.NANOSECONDS)) {
                    "The cancelled staging task did not resume in time."
                }
                task.run()
            }
        }
    }

    private companion object {
        const val PROVIDER_VERSION = "0.5.0"
    }
}
