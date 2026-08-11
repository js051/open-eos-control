package dev.openeos.control.data

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AndroidCcapiRtpSessionTest {
    @Test
    fun rtpAudioIsDefaultMutedAndOnlyQueuesSamplesAfterExplicitEnable() {
        val videoPort = availableUdpPort()
        val audioPort = availableUdpPort(excluding = setOf(videoPort))
        val playerStarts = AtomicInteger()
        val received = Collections.synchronizedList(mutableListOf<AacAccessUnit>())
        val playerStarted = CountDownLatch(1)
        val player = RtpAudioPlayer { accessUnits, onStats ->
            playerStarts.incrementAndGet()
            playerStarted.countDown()
            var decoded = 0L
            for (accessUnit in accessUnits) {
                received += accessUnit
                decoded += 1
                onStats(
                    RtpAudioPlaybackStats(
                        decodedAccessUnits = decoded,
                        playedSampleFrames = decoded * 1_024,
                        sampleRate = accessUnit.format.sampleRate,
                        channels = accessUnit.format.channels,
                        underruns = 0,
                        lastPcmAtMillis = System.currentTimeMillis(),
                    )
                )
            }
        }
        val session = session(
            videoPort = videoPort,
            audioPort = audioPort,
            extractorFactory = { PassthroughLatmExtractor() },
            audioPlayerFactory = { player },
        )
        val sender = DatagramSocket()

        try {
            session.start()
            await { session.audioStatus.available }
            assertFalse(session.audioStatus.enabled)

            sender.send(audioPacket(audioPort, sequence = 1, timestamp = 1_000, payload = byteArrayOf(1)))
            await { session.audioStatus.accessUnitsReceived == 1L }
            assertEquals(0, playerStarts.get())
            assertTrue(received.isEmpty())

            session.setAudioEnabled(true)
            assertTrue(playerStarted.await(2, TimeUnit.SECONDS))
            await { session.audioStatus.enabled }
            sender.send(audioPacket(audioPort, sequence = 2, timestamp = 2_024, payload = byteArrayOf(2)))
            await { received.size == 1 }
            await { session.audioStatus.decodedAccessUnits == 1L }

            assertEquals(byteArrayOf(2).toList(), received.single().bytes.toList())
            assertEquals(1_024L, session.audioStatus.playedSampleFrames)

            session.setAudioEnabled(false)
            await { !session.audioStatus.enabled }
        } finally {
            sender.close()
            session.close()
        }
    }

    @Test
    fun latmParseFailureRemainsAnAudioStatusErrorAndDoesNotFailVideo() {
        val videoPort = availableUdpPort()
        val audioPort = availableUdpPort(excluding = setOf(videoPort))
        val events = Collections.synchronizedList(mutableListOf<NativeLiveViewEvent>())
        val session = session(
            videoPort = videoPort,
            audioPort = audioPort,
            extractorFactory = { ThrowingLatmExtractor() },
            audioPlayerFactory = { error("Audio player must not start while muted.") },
        )
        val sender = DatagramSocket()

        try {
            session.setListener(events::add)
            session.start()
            await { session.audioStatus.available }
            sender.send(audioPacket(audioPort, sequence = 1, timestamp = 1_000, payload = byteArrayOf(7)))
            await { session.audioStatus.error?.contains("LATM parse failed") == true }

            assertTrue(session.audioStatus.available)
            assertFalse(session.audioStatus.enabled)
            assertTrue(events.none { it is NativeLiveViewEvent.Failed })
        } finally {
            sender.close()
            session.close()
        }
    }

    @Test
    fun rtpReadinessRequiresSpsPpsAndAKeyFrame() = runBlocking {
        val videoPort = availableUdpPort()
        val audioPort = availableUdpPort(excluding = setOf(videoPort))
        val session = session(
            videoPort = videoPort,
            audioPort = audioPort,
            extractorFactory = { PassthroughLatmExtractor() },
            audioPlayerFactory = { error("Audio player is not used by this test.") },
        )
        val sender = DatagramSocket()

        try {
            session.start()
            sender.send(videoPacket(videoPort, sequence = 1, timestamp = 1_000, payload = byteArrayOf(0x67, 0x42)))
            sender.send(videoPacket(videoPort, sequence = 2, timestamp = 2_000, payload = byteArrayOf(0x68, 0x01)))
            sender.send(videoPacket(videoPort, sequence = 3, timestamp = 3_000, payload = byteArrayOf(0x65, 0x01)))

            session.awaitReady(timeoutMillis = 2_000)
        } finally {
            sender.close()
            session.close()
        }
    }

    @Test
    fun rtpReadinessUsesSdpParameterSetsWhenTheStreamOnlySendsAKeyFrame() = runBlocking {
        val videoPort = availableUdpPort()
        val audioPort = availableUdpPort(excluding = setOf(videoPort))
        val session = session(
            videoPort = videoPort,
            audioPort = audioPort,
            videoFormatParameters = mapOf("sprop-parameter-sets" to "Z0I=,aAE="),
            extractorFactory = { PassthroughLatmExtractor() },
            audioPlayerFactory = { error("Audio player is not used by this test.") },
        )
        val sender = DatagramSocket()

        try {
            session.start()
            sender.send(videoPacket(videoPort, sequence = 1, timestamp = 1_000, payload = byteArrayOf(0x65, 0x01)))

            session.awaitReady(timeoutMillis = 2_000)
            assertTrue(session.videoStatus.ready)
            assertTrue(session.videoStatus.hasSequenceParameterSet)
            assertTrue(session.videoStatus.hasPictureParameterSet)
            assertEquals(1L, session.videoStatus.datagramsReceived)
            assertEquals(1L, session.videoStatus.accessUnitsReceived)
            assertEquals(1L, session.videoStatus.keyFramesReceived)
        } finally {
            sender.close()
            session.close()
        }
    }

    @Test
    fun rtpReadinessTimesOutWithPacketDiagnostics() {
        val videoPort = availableUdpPort()
        val audioPort = availableUdpPort(excluding = setOf(videoPort))
        val session = session(
            videoPort = videoPort,
            audioPort = audioPort,
            extractorFactory = { PassthroughLatmExtractor() },
            audioPlayerFactory = { error("Audio player is not used by this test.") },
        )

        try {
            session.start()
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { session.awaitReady(timeoutMillis = 100) }
            }

            assertTrue(failure.message.orEmpty().contains("UDP datagrams=0"))
            assertTrue(failure.message.orEmpty().contains("H.264 access units=0"))
            assertTrue(failure.message.orEmpty().contains("key frames=0"))
        } finally {
            session.close()
        }
    }

    private fun session(
        videoPort: Int,
        audioPort: Int,
        videoFormatParameters: Map<String, String> = emptyMap(),
        extractorFactory: () -> LatmSampleExtractor,
        audioPlayerFactory: () -> RtpAudioPlayer,
    ) = AndroidCcapiRtpSession(
        description = CcapiRtpSessionDescription(
            rawSdp = "test",
            video = RtpMediaDescription(
                "video",
                videoPort,
                103,
                "H264",
                90_000,
                formatParameters = videoFormatParameters,
            ),
            audio = RtpMediaDescription("audio", audioPort, 106, "MP4A-LATM", 48_000, 2),
        ),
        destinationAddress = "127.0.0.1",
        socketBinder = DatagramSocketBinder { },
        latmExtractorFactory = extractorFactory,
        audioPlayerFactory = audioPlayerFactory,
    )

    private fun audioPacket(
        port: Int,
        sequence: Int,
        timestamp: Long,
        payload: ByteArray,
    ): DatagramPacket {
        val bytes = byteArrayOf(
            0x80.toByte(),
            (0x80 or 106).toByte(),
            (sequence ushr 8).toByte(),
            sequence.toByte(),
            (timestamp ushr 24).toByte(),
            (timestamp ushr 16).toByte(),
            (timestamp ushr 8).toByte(),
            timestamp.toByte(),
            0,
            0,
            0,
            1,
        ) + payload
        return DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port)
    }

    private fun videoPacket(
        port: Int,
        sequence: Int,
        timestamp: Long,
        payload: ByteArray,
    ): DatagramPacket {
        val bytes = byteArrayOf(
            0x80.toByte(),
            (0x80 or 103).toByte(),
            (sequence ushr 8).toByte(),
            sequence.toByte(),
            (timestamp ushr 24).toByte(),
            (timestamp ushr 16).toByte(),
            (timestamp ushr 8).toByte(),
            timestamp.toByte(),
            0,
            0,
            0,
            1,
        ) + payload
        return DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port)
    }

    private fun availableUdpPort(excluding: Set<Int> = emptySet()): Int {
        while (true) {
            val port = DatagramSocket(0).use { it.localPort }
            if (port !in excluding) return port
        }
    }

    private fun await(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Condition was not met within ${timeoutMillis}ms", condition())
    }

    private class PassthroughLatmExtractor : LatmSampleExtractor {
        override fun consume(accessUnit: LatmAccessUnit, presentationTimeUs: Long): List<AacAccessUnit> =
            listOf(
                AacAccessUnit(
                    bytes = accessUnit.audioMuxElement,
                    presentationTimeUs = presentationTimeUs,
                    format = AacStreamFormat(48_000, 2, byteArrayOf(0x11, 0x90.toByte()), "mp4a.40.2"),
                    discontinuity = accessUnit.discontinuity,
                )
            )

        override fun reset() = Unit
    }

    private class ThrowingLatmExtractor : LatmSampleExtractor {
        override fun consume(accessUnit: LatmAccessUnit, presentationTimeUs: Long): List<AacAccessUnit> =
            error("invalid StreamMuxConfig")

        override fun reset() = Unit
    }
}
