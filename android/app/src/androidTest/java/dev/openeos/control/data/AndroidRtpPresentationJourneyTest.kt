package dev.openeos.control.data

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Authored device regression: production receiver + MediaCodec + real Surface callbacks. */
class AndroidRtpPresentationJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture = Base64.getDecoder().decode("AAAAAWdkEAqsuI9NQEBAUAAAAwAQAAADACBAAAAAAWjuASyyLAAAAAFliIQ6/vOwfmWVcMjc9396sU68Xq2KSZ/Z1yk56ylhRBv1U+mU30I0Mj4UmV3uYf7yU5xO2A99Sa1rES0kusaJZgSJBlnVOP5+YjUvqLBSBdOb96m0lFYjjsqbyoKCtoK2/Ui3FUTivt/uYo03ooGWdmlKBYZOq6rTjaSQ+vk=")

    @Test fun actualPresentationPrecedesFrameEventAndSilenceRetiresItsPixels() = runBlocking {
        val surface = createSurface()
        val port = DatagramSocket(0).use { it.localPort }
        val events = CopyOnWriteArrayList<NativeLiveViewEvent>()
        val session = AndroidCcapiRtpSession(CcapiRtpSessionDescription("synthetic", RtpMediaDescription("video", port, 103, "H264", 90_000), null),
            "127.0.0.1", DatagramSocketBinder {})
        val sender = DatagramSocket()
        var sending: Job? = null
        try {
            session.setListener(events::add); session.start()
            compose.runOnIdle { session.attachSurface(surface) }
            sending = launch(Dispatchers.IO) {
                var sequence = 0
                while (isActive) { sender.send(packet(port, sequence++)); delay(60) }
            }
            session.awaitReady(2_000)
            withTimeout(5_000) { while (events.none { it is NativeLiveViewEvent.FrameRendered }) delay(20) }
            assertNotNull(session.renderedFrames.value)
            assertTrue(events.any { it is NativeLiveViewEvent.FrameRendered })
            sending.cancelAndJoin()
            withTimeout(7_000) { while (events.none { it is NativeLiveViewEvent.Failed }) delay(20) }
            assertNull(session.renderedFrames.value)
            assertFalse(session.videoStatus.ready)
            assertEquals(1, events.count { it is NativeLiveViewEvent.Failed })
        } finally { sending?.cancelAndJoin(); sender.close(); session.close() }
    }

    @Test fun attachedSurfaceWithNoDecodedOutputFailsWithinItsOwnBound() = runBlocking {
        val surface = createSurface()
        val port = DatagramSocket(0).use { it.localPort }
        val events = CopyOnWriteArrayList<NativeLiveViewEvent>()
        val session = AndroidCcapiRtpSession(CcapiRtpSessionDescription("synthetic", RtpMediaDescription("video", port, 103, "H264", 90_000), null),
            "127.0.0.1", DatagramSocketBinder {})
        try {
            session.setListener(events::add); session.start()
            compose.runOnIdle { session.attachSurface(surface) }
            withTimeout(7_000) { while (events.none { it is NativeLiveViewEvent.Failed }) delay(20) }
            assertTrue(events.none { it is NativeLiveViewEvent.FrameRendered })
            assertNull(session.renderedFrames.value)
        } finally { session.close() }
    }

    private fun createSurface(): Surface {
        val ready = CompletableFuture<Surface>()
        compose.setContent { AndroidView(factory = { context -> SurfaceView(context).also { view ->
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { ready.complete(holder.surface) }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        } }, modifier = Modifier.size(128.dp, 96.dp)) }
        return ready.get(8, TimeUnit.SECONDS)
    }

    private fun packet(port: Int, sequence: Int): DatagramPacket {
        val starts = (0 until fixture.size - 3).filter { i -> fixture[i] == 0.toByte() && fixture[i + 1] == 0.toByte() && fixture[i + 2] == 0.toByte() && fixture[i + 3] == 1.toByte() }
        val payload = java.io.ByteArrayOutputStream().apply {
            write(24)
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: fixture.size
                val size = end - start - 4
                write(size ushr 8); write(size and 255); write(fixture, start + 4, size)
            }
        }.toByteArray()
        val timestamp = sequence * 5_400
        val bytes = byteArrayOf(0x80.toByte(), (0x80 or 103).toByte(), (sequence ushr 8).toByte(), sequence.toByte(),
            (timestamp ushr 24).toByte(), (timestamp ushr 16).toByte(), (timestamp ushr 8).toByte(), timestamp.toByte(), 0, 0, 0, 1) + payload
        return DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port)
    }
}
