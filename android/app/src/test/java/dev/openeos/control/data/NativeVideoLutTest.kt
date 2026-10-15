package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NativeVideoLutTest {
    private fun table(size: Int = 2, transform: (Float, Float, Float) -> FloatArray = { r, g, b -> floatArrayOf(r, g, b) }): FloatArray =
        (0 until size).flatMap { b -> (0 until size).flatMap { g -> (0 until size).flatMap { r ->
            transform(r.toFloat() / (size - 1), g.toFloat() / (size - 1), b.toFloat() / (size - 1)).toList()
        } } }.toFloatArray()

    @Test fun identityAndAsymmetricChannelOrder() {
        val lut = NativeVideoLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), table())
        assertArrayEquals(floatArrayOf(.11f, .47f, .83f), lut.sample(.11f, .47f, .83f), 0.000001f)
        val texture = lut.rgbaTexture()
        val firstTwo = FloatArray(8); texture.get(firstTwo)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f), firstTwo, 0f)
    }

    @Test fun nonUnitDomainsClampInputAndUseAllEightCorners() {
        val lut = NativeVideoLut(2, floatArrayOf(-1f, 0f, .25f), floatArrayOf(1f, 2f, .75f), table { r, g, b ->
            floatArrayOf(r * g, g * b, b * r)
        })
        assertArrayEquals(floatArrayOf(.125f, .375f, .1875f), lut.sample(-.5f, 1f, .625f), .000001f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), lut.sample(-2f, 3f, 1f), .000001f)
    }

    @Test fun outputIsClampedOnlyAfterInterpolation() {
        val lut = NativeVideoLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), table { r, g, b ->
            floatArrayOf(r * 3 - 1, g * 3 - 1, b * 3 - 1)
        })
        assertArrayEquals(floatArrayOf(0f, .5f, 1f), lut.sample(.25f, .5f, .75f), .000001f)
        assertEquals(-1f, lut.rgbaTexture().get(0), 0f)
    }

    @Test fun sourceAndExportedArraysCannotMutateTheSnapshot() {
        val min = floatArrayOf(0f, 0f, 0f); val max = floatArrayOf(1f, 1f, 1f); val values = table()
        val lut = NativeVideoLut(2, min, max, values)
        min.fill(100f); max.fill(-100f); values.fill(0f); lut.domainMinimum().fill(99f)
        lut.rgbaTexture().put(0, 99f)
        assertArrayEquals(floatArrayOf(.1f, .2f, .3f), lut.sample(.1f, .2f, .3f), .000001f)
    }

    @Test fun rejectsMalformedAndNonFiniteTables() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                NativeVideoLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), table().also { it[1] = bad })
            }
        }
        assertThrows(IllegalArgumentException::class.java) { NativeVideoLut(65, FloatArray(3), FloatArray(3) { 1f }, FloatArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { NativeVideoLut(2, FloatArray(3), FloatArray(3), table()) }
        assertThrows(IllegalArgumentException::class.java) { NativeVideoLut(2, FloatArray(3), FloatArray(3) { 1f }, FloatArray(23)) }
    }

    @Test fun hdrUnknownTaggedTransferAndHighBitDepthNeverBecomeSdrLut() {
        assertTrue(nativeLutColorSupported(1, 3, 8))
        assertTrue(nativeLutColorSupported(null, null, null)) // untagged display-preview approximation
        for (transfer in listOf(0, 1, 6, 7, 999)) assertFalse(nativeLutColorSupported(1, transfer, 8))
        assertFalse(nativeLutColorSupported(6, 3, 8))
        // Mixed SD-default primaries / BT.709 matrix from an incompletely tagged SPS.
        assertFalse(nativeLutColorSupported(75, 3, 8))
        assertFalse(nativeLutColorSupported(1, 3, 10))
    }

    @Test fun highDepthAndMalformedSpsCannotEnablePreviewLut() {
        for (profile in listOf(66, 77, 88, 100)) {
            assertTrue(nativeLutH264ProfileSupported(byteArrayOf(0x67, profile.toByte(), 0, 31)))
            assertTrue(nativeLutH264ProfileSupported(byteArrayOf(0, 0, 0, 1, 0x67, profile.toByte(), 0, 31)))
        }
        for (profile in listOf(44, 110, 122, 244, 0, 255)) {
            assertFalse(nativeLutH264ProfileSupported(byteArrayOf(0x67, profile.toByte(), 0, 31)))
        }
        assertFalse(nativeLutH264ProfileSupported(null))
        assertFalse(nativeLutH264ProfileSupported(byteArrayOf(0, 0, 0, 1)))
        assertFalse(nativeLutH264ProfileSupported(byteArrayOf(0x68, 100, 0, 31)))
    }

    @Test fun retiringSettingsSourceOrPauseNeverPublishesOldFrame() {
        val gate = NativeVideoPresentationGate()
        assertNull(gate.begin()); gate.configure(true)
        val old = requireNotNull(gate.begin()); assertTrue(gate.owns(old))
        gate.configure(true); assertFalse(gate.owns(old))
        val current = requireNotNull(gate.begin()); assertTrue(gate.owns(current))
        gate.configure(false); assertFalse(gate.owns(current)); assertNull(gate.begin())
    }
    @Test fun compressedInputGapsRequireIdrWithoutDroppingFramesForDisplayThrottling() {
        val gate = NativeVideoDecodeContinuity()
        assertFalse(gate.admit(1, false))
        assertTrue(gate.admit(2, true)); gate.accepted()
        assertTrue(gate.admit(3, false)); gate.accepted()
        assertTrue(gate.admit(4, false)); gate.accepted()
        assertFalse(gate.admit(6, false)) // DROP_OLDEST or missing input
        assertFalse(gate.admit(7, false))
        assertTrue(gate.admit(8, true)); gate.accepted()
        gate.dropped() // MediaCodec could not accept an input buffer
        assertFalse(gate.admit(9, false))
        assertTrue(gate.admit(10, true)); gate.accepted()
        assertTrue(gate.admit(11, false))
    }

    @Test fun rapidCancelledReplacementsCannotOvertakeThePhysicalDecoderDrain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val original = launch {
            try { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            finally { withContext(NonCancellable) { drain.await() } }
        }
        entered.await(); original.cancel()
        val middle = launchDrainedNativeVideoJob(this, original) { error("Cancelled middle must never decode") }
        middle.cancel()
        var replacementStarted = false
        val replacement = launchDrainedNativeVideoJob(this, middle) { replacementStarted = true }
        repeat(3) { yield() }
        assertFalse(middle.isCompleted)
        assertFalse(replacementStarted)
        drain.complete(Unit)
        replacement.join()
        assertTrue(replacementStarted)
    }

    @Test fun replacementSessionAndCancelledIntermediateCannotOvertakeTheSameSurfaceWriter() = runBlocking {
        val registry = NativeVideoSurfaceDrainRegistry()
        val target = Any()
        val entered = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val original = registry.launch(this, target, null) {
            try { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            finally { withContext(NonCancellable) { drain.await() } }
        }
        entered.await()
        val middle = registry.launch(this, target, null) { error("Cancelled middle cannot write") }
        middle.cancel()
        var started = false
        val replacement = registry.launch(this, target, null) { started = true }
        repeat(3) { yield() }
        assertFalse(original.isCompleted)
        assertFalse(started)
        drain.complete(Unit)
        replacement.join()
        assertTrue(started)
    }

}
