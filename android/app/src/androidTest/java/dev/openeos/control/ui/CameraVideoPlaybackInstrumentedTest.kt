package dev.openeos.control.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaStreamHandle
import dev.openeos.control.data.CameraMediaStreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
class CameraVideoPlaybackInstrumentedTest {
    @Test
    fun cameraDataSourceDecodesAndAdvancesValidH264Mp4() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = ByteArrayMediaSource(instrumentation.context.assets.open("valid-h264.mp4").use { it.readBytes() })
        val ready = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val surfaceTexture = SurfaceTexture(false).apply { setDefaultBufferSize(160, 90) }
        val surface = Surface(surfaceTexture)
        lateinit var player: ExoPlayer

        instrumentation.runOnMainSync {
            player = createPlayer(context, source).apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        failed.countDown()
                    }

                    override fun onRenderedFirstFrame() {
                        rendered.countDown()
                    }
                })
                setVideoSurface(surface)
                playWhenReady = true
                prepare()
            }
        }

        try {
            assertTrue("Valid H.264 fixture did not become ready", ready.await(10, TimeUnit.SECONDS))
            assertEquals("Playback failed before becoming ready", 1L, failed.count)
            assertTrue("Valid H.264 fixture did not render a frame", rendered.await(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            var position = 0L
            var width = 0
            var height = 0
            instrumentation.runOnMainSync {
                position = player.currentPosition
                width = player.videoSize.width
                height = player.videoSize.height
            }
            assertTrue("Decoded video did not expose dimensions", width > 0 && height > 0)
            assertTrue("Decoded video did not advance", position > 0L)
        } finally {
            instrumentation.runOnMainSync { player.release() }
            surface.release()
            surfaceTexture.release()
            source.close()
        }
    }

    private fun createPlayer(context: Context, source: CameraMediaStreamSource): ExoPlayer =
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(
                ProgressiveMediaSource.Factory(CameraMediaDataSource.Factory(source))
                    .createMediaSource(MediaItem.fromUri(Uri.parse("oec-media://test/valid-h264.mp4"))),
            )
        }

    private class ByteArrayMediaSource(private val bytes: ByteArray) : CameraMediaStreamSource {
        override val item = CameraMediaItem(
            id = "test-valid-h264",
            name = "TEST_VALID_H264.MP4",
            kind = "video",
            sizeBytes = bytes.size.toLong(),
            streamAvailable = true,
        )

        override suspend fun open(position: Long): CameraMediaStreamHandle = object : CameraMediaStreamHandle {
            private var cursor = position.toInt()
            override val bytesRemaining: Long = bytes.size - position
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
