package dev.openeos.control.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class AndroidRtpAudioPlayerInstrumentedTest {
    @Test
    fun realLatmFixtureIsExtractedDecodedAndWrittenToAudioTrack() = runBlocking {
        val extractor = Media3LatmSampleExtractor()
        val firstMux = Base64.getDecoder().decode(FIRST_LATM_MUX_BASE64)
        val repeatedMux = Base64.getDecoder().decode(REPEATED_LATM_MUX_BASE64)
        val accessUnits = buildList {
            repeat(FIXTURE_FRAME_COUNT) { index ->
                val mux = if (index == 0) firstMux else repeatedMux
                addAll(
                    extractor.consume(
                        LatmAccessUnit(mux, rtpTimestamp = index * 1_024L),
                        presentationTimeUs = index * AAC_FRAME_DURATION_US,
                    )
                )
            }
        }
        assertEquals(FIXTURE_FRAME_COUNT, accessUnits.size)

        val channel = Channel<AacAccessUnit>(capacity = 8)
        var latestStats: RtpAudioPlaybackStats? = null
        withTimeout(8_000) {
            val playback = launch(Dispatchers.IO) {
                AndroidRtpAudioPlayer().play(channel) { latestStats = it }
            }
            val producer = launch {
                accessUnits.forEach { accessUnit ->
                    channel.send(accessUnit)
                    delay(22)
                }
                channel.close()
            }
            joinAll(producer, playback)
        }

        val stats = latestStats
        assertNotNull(stats)
        assertEquals(48_000, stats?.sampleRate)
        assertEquals(2, stats?.channels)
        assertTrue("Expected raw AAC to reach MediaCodec", stats!!.decodedAccessUnits >= 20)
        assertTrue("Expected decoded PCM to reach AudioTrack", stats.playedSampleFrames > 0)
        assertNotNull(stats.lastPcmAtMillis)
    }

    private companion object {
        const val FIRST_LATM_MUX_BASE64 = "IAARkB/gvvAQAmMLsxmxkXGRwXGJgZACEQBGCMHA"
        const val REPEATED_LATM_MUX_BASE64 = "gxCIAjBGDgA="
        const val FIXTURE_FRAME_COUNT = 32
        const val AAC_FRAME_DURATION_US = 21_333L
    }
}
