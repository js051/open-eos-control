package dev.openeos.control.ui

import dev.openeos.control.data.NativeVideoLut
import org.junit.Assert.*
import org.junit.Test

class LiveViewLutNumericContractTest {
    private val fixture = loadLiveViewLutNumericFixture()

    @Test fun fixtureDeclaresDisplayValuesWithTightFloatAndFinalByteContracts() {
        assertTrue(fixture.byteCount in 1..64 * 1024)
        assertEquals(1, fixture.schemaVersion)
        assertEquals("srgb-display-values", fixture.colorEncoding)
        assertEquals("trilinear", fixture.interpolation)
        assertEquals("red-fastest", fixture.tableOrder)
        assertEquals("nearest-ties-up-after-output-clamp", fixture.uint8Rounding)
        assertEquals(0.000002f, fixture.cpuAbsoluteTolerance, 0f)
        assertEquals(12, fixture.vectors.size)
        assertEquals(6, fixture.vectors.count { it.inputRgb8 != null })
        assertEquals(fixture.vectors.size, fixture.vectors.map { it.id }.toSet().size)
        assertEquals(fixture.luts.size, fixture.luts.map { it.id }.toSet().size)
        fixture.vectors.forEach { vector ->
            assertEquals(vector.id, 3, vector.inputRgb.size)
            assertEquals(vector.id, 3, vector.expectedRgb.size)
            vector.inputRgb8?.let { bytes ->
                assertEquals(vector.id, 3, bytes.size)
                assertEquals(vector.id, 3, requireNotNull(vector.expectedRgb8).size)
                bytes.forEachIndexed { channel, byte ->
                    assertTrue(vector.id, byte in 0..255)
                    assertEquals(vector.id, byte / 255f, vector.inputRgb[channel], 0f)
                }
            }
        }
    }

    @Test fun decodedCubeAndNativeVideoUseTheSameFixedNumericOracle() {
        fixture.vectors.forEach { vector ->
            val definition = fixture.luts.single { it.id == vector.lutId }
            val decoded = parseCubeLut(definition.cubeText(), "${definition.id}.cube")
            val native = NativeVideoLut(definition.size, definition.domainMin, definition.domainMax, definition.values)
            val (red, green, blue) = vector.inputRgb
            assertArrayEquals("decoded ${vector.id}", vector.expectedRgb, decoded.sample(red, green, blue), fixture.cpuAbsoluteTolerance)
            assertArrayEquals("native ${vector.id}", vector.expectedRgb, native.sample(red, green, blue), fixture.cpuAbsoluteTolerance)
        }
    }

    @Test fun decodedByteOutputsRoundOnlyAtTheEndAndKeepAlpha() {
        fixture.vectors.filter { it.inputRgb8 != null }.forEach { vector ->
            val definition = fixture.luts.single { it.id == vector.lutId }
            val decoded = parseCubeLut(definition.cubeText(), "${definition.id}.cube")
            for (alpha in listOf(0, 173, 255)) {
                val input = argb(alpha, requireNotNull(vector.inputRgb8))
                val expected = argb(alpha, requireNotNull(vector.expectedRgb8))
                assertEquals("${vector.id} alpha=$alpha", expected, decoded.sampleArgb(input))
            }
        }
    }

    @Test fun exactlyRepresentableHalfRoundsUpAndFinalOutputClamps() {
        fixture.quantizationVectors.forEach { vector ->
            val definition = NumericLut(vector.id, 2, FloatArray(3), FloatArray(3) { 1f }, FloatArray(24) { vector.rgb[it % 3] })
            val decoded = parseCubeLut(definition.cubeText(), "${definition.id}.cube")
            assertEquals(vector.id, argb(173, vector.expectedRgb8), decoded.sampleArgb(argb(173, intArrayOf(51, 102, 204))))
        }
    }

    @Test fun parsedAndTextureNodesRetainRedFastestOrderAndExtendedValues() {
        fixture.luts.forEach { definition ->
            val decoded = parseCubeLut(definition.cubeText(), "${definition.id}.cube")
            assertArrayEquals(definition.id, definition.values, decoded.values, 0f)
            val native = NativeVideoLut(definition.size, definition.domainMin, definition.domainMax, definition.values)
            val texture = native.rgbaTexture()
            for (node in 0 until definition.size * definition.size * definition.size) {
                for (channel in 0..2) assertEquals(definition.id, definition.values[node * 3 + channel], texture.get(node * 4 + channel), 0f)
                assertEquals(definition.id, 1f, texture.get(node * 4 + 3), 0f)
            }
        }
    }

    private fun argb(alpha: Int, rgb: IntArray): Int =
        (alpha shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
}
