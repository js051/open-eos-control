package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewMonitoringTest {
    @Test
    fun nativeDisplaySamplingPreservesBoundsAndDoesNotDesqueezeTwice() {
        assertEquals(120 to 68, analysisDimensions(1920, 1080))
        assertEquals(45 to 80, analysisDimensions(1080, 1920))
        assertEquals(120 to 34, analysisDimensions(3840, 1080))
        assertEquals(1 to 80, analysisDimensions(1, 8000))
    }

    @Test
    fun srgbArgbChannelsAndAllAssistsUseTheSamePixelInput() {
        val analysis = analyzeLiveViewPixels(
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()),
            3, 1, 90, true, true, true,
        )
        assertEquals(1, analysis.histogram[13])
        assertEquals(1, analysis.histogram[45])
        assertEquals(1, analysis.histogram[4])
        assertEquals(3, requireNotNull(analysis.waveform).density.sum())
        assertEquals(0xE029B6D1.toInt(), requireNotNull(analysis.overlayPixels)[0])
        assertEquals(0xE0F2D13D.toInt(), requireNotNull(analysis.overlayPixels)[1])
        assertEquals(0xE0203DA0.toInt(), requireNotNull(analysis.overlayPixels)[2])
    }

    @Test
    fun histogramPlacesBlackAndWhiteInEndpointBuckets() {
        val analysis = analyzeLiveViewPixels(
            pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()),
            width = 2,
            height = 1,
            zebraThresholdPercent = null,
            focusPeakingEnabled = false,
        )

        assertEquals(1, analysis.histogram.first())
        assertEquals(1, analysis.histogram.last())
        assertEquals(2, analysis.histogram.sum())
        assertNull(analysis.overlayPixels)
        assertNull(analysis.waveform)
    }

    @Test
    fun waveformPreservesHorizontalPositionAndLuminance() {
        val analysis = analyzeLiveViewPixels(
            pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()),
            width = 2,
            height = 1,
            zebraThresholdPercent = null,
            focusPeakingEnabled = false,
            waveformVisible = true,
        )

        val waveform = requireNotNull(analysis.waveform)
        assertEquals(64, waveform.width)
        assertEquals(64, waveform.height)
        assertEquals(2, waveform.density.sum())
        assertEquals(1, waveform.density[63 * waveform.width])
        assertEquals(1, waveform.density[waveform.width - 1])
    }

    @Test
    fun zebraMarksOnlyPixelsAtOrAboveTheThreshold() {
        val analysis = analyzeLiveViewPixels(
            pixels = intArrayOf(0xffbfbfbf.toInt(), 0xfff5f5f5.toInt()),
            width = 2,
            height = 1,
            zebraThresholdPercent = 90,
            focusPeakingEnabled = false,
        )

        val overlay = requireNotNull(analysis.overlayPixels)
        assertEquals(0, overlay[0])
        assertTrue(overlay[1] != 0)
    }

    @Test
    fun focusPeakingMarksAHighContrastEdge() {
        val black = 0xff000000.toInt()
        val white = 0xffffffff.toInt()
        val pixels = intArrayOf(
            black, black, white, white, white,
            black, black, white, white, white,
            black, black, white, white, white,
        )

        val analysis = analyzeLiveViewPixels(
            pixels = pixels,
            width = 5,
            height = 3,
            zebraThresholdPercent = null,
            focusPeakingEnabled = true,
        )

        val overlay = requireNotNull(analysis.overlayPixels)
        assertEquals(0xff28c5d9.toInt(), overlay[7])
        assertEquals(0, overlay[8])
    }

    @Test
    fun falseColorMapsDarkAndBrightPixelsToDifferentOpaqueColors() {
        val analysis = analyzeLiveViewPixels(
            pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()),
            width = 2,
            height = 1,
            zebraThresholdPercent = null,
            focusPeakingEnabled = false,
            falseColorEnabled = true,
        )

        val overlay = requireNotNull(analysis.overlayPixels)
        assertTrue(overlay[0] != 0)
        assertTrue(overlay[1] != 0)
        assertTrue(overlay[0] != overlay[1])
    }

    @Test
    fun settingsRequestPixelAnalysisOnlyForPixelBasedAssists() {
        assertFalse(LiveViewMonitorSettings().needsPixelAnalysis)
        assertFalse(
            LiveViewMonitorSettings(
                frameGuide = LiveViewFrameGuide.RATIO_2_39,
                safeAreaVisible = true,
                desqueeze = LiveViewDesqueeze.X2,
            ).needsPixelAnalysis
        )
        assertTrue(LiveViewMonitorSettings(histogramVisible = true).needsPixelAnalysis)
        assertTrue(LiveViewMonitorSettings(waveformVisible = true).needsPixelAnalysis)
        assertTrue(LiveViewMonitorSettings(zebraThresholdPercent = 95).needsPixelAnalysis)
        assertTrue(LiveViewMonitorSettings(falseColorEnabled = true).needsPixelAnalysis)
        assertTrue(LiveViewMonitorSettings(focusPeakingEnabled = true).needsPixelAnalysis)
    }

    @Test
    fun cubeLutParsesRedFastRowsAndInterpolatesWithinTheDeclaredDomain() {
        val lut = parseCubeLut(INVERT_LUT, "fallback.cube")

        assertEquals("Invert", lut.name)
        assertEquals(2, lut.size)
        val result = lut.sample(0.25f, 0.5f, 0.75f)
        assertEquals(0.75f, result[0], 0.0001f)
        assertEquals(0.5f, result[1], 0.0001f)
        assertEquals(0.25f, result[2], 0.0001f)
        assertEquals(0x7fbf7f40.toInt(), lut.sampleArgb(0x7f4080bf))
    }

    @Test
    fun cubeLutRejectsIncompleteAndOneDimensionalFiles() {
        assertTrue(runCatching { parseCubeLut("LUT_3D_SIZE 2\n0 0 0", "bad.cube") }.isFailure)
        assertTrue(runCatching { parseCubeLut("LUT_1D_SIZE 2\n0 0 0\n1 1 1", "bad.cube") }.isFailure)
    }

    private companion object {
        const val INVERT_LUT = """
            TITLE "Invert"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1 1 1
            1 1 1
            0 1 1
            1 0 1
            0 0 1
            1 1 0
            0 1 0
            1 0 0
            0 0 0
        """
    }
}
