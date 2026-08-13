package dev.openeos.control.ui

import android.system.ErrnoException
import android.system.OsConstants
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaStreamHandle
import dev.openeos.control.data.CameraMediaStreamSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraMediaPlaybackCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cachesCompleteCameraVideoForFileBackedPlayback() = runTest {
        val bytes = "complete-camera-video".toByteArray()
        val source = ByteArrayMediaSource(bytes, declaredSize = bytes.size.toLong())

        val cached = cacheCameraMediaForPlayback(source, temporaryFolder.root)

        assertArrayEquals(bytes, cached.readBytes())
        assertTrue(cached.delete())
    }

    @Test
    fun rejectsAndDeletesTruncatedCameraVideo() = runTest {
        val source = ByteArrayMediaSource("short".toByteArray(), declaredSize = 10L)

        val failure = runCatching {
            cacheCameraMediaForPlayback(source, temporaryFolder.root)
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("5 of 10 bytes"))
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { it.name.startsWith("camera-video-") })
    }

    @Test
    fun capacityPolicyAllowsVideosLargerThanOneGiBWhenSpaceExists() {
        val twoGiB = 2L * 1024L * 1024L * 1024L

        assertTrue(hasPlaybackCacheCapacity(twoGiB, twoGiB + 512L * 1024L * 1024L))
    }

    @Test
    fun capacityPolicyPreservesFreeSpaceReserve() {
        val oneGiB = 1024L * 1024L * 1024L

        assertFalse(hasPlaybackCacheCapacity(oneGiB, oneGiB + 64L * 1024L * 1024L))
    }

    @Test
    fun classifiesStorageFailureThroughWrappedErrno() {
        val error = java.io.IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))

        assertTrue(error.isPlaybackStorageFailure())
    }

    private class ByteArrayMediaSource(
        private val bytes: ByteArray,
        declaredSize: Long,
    ) : CameraMediaStreamSource {
        override val item = CameraMediaItem(
            id = "test-video",
            name = "TEST_VIDEO.MP4",
            kind = "video",
            sizeBytes = declaredSize,
            streamAvailable = true,
        )

        override suspend fun open(position: Long): CameraMediaStreamHandle = object : CameraMediaStreamHandle {
            private var cursor = position.toInt()
            override val bytesRemaining: Long = item.sizeBytes!! - position
            override val contentType: String = "video/mp4"

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (cursor >= bytes.size) return -1
                val count = minOf(length, bytes.size - cursor)
                bytes.copyInto(buffer, offset, cursor, cursor + count)
                cursor += count
                return count
            }

            override fun close() = Unit
        }
    }
}
