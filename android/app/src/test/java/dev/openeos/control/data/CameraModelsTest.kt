package dev.openeos.control.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelsTest {
    @Test
    fun directCcapiCapabilitiesPartitionEveryApplicableFeature() {
        val supported = setOf(CameraFeature.CAMERA_IDENTITY, CameraFeature.BATTERY_STATUS)
        val matrix = CapabilityMatrix.ccapiNetwork(supported)
        val expected = CameraFeature.entries.toSet() - setOf(
            CameraFeature.USB_DIAGNOSTICS,
            CameraFeature.DESKTOP_BRIDGE,
        )

        assertEquals(expected, matrix.supported + matrix.planned)
        assertTrue(matrix.supported.intersect(matrix.planned).isEmpty())
        assertTrue(matrix.isPlanned(CameraFeature.LIVE_VIEW))
        assertTrue(matrix.isPlanned(CameraFeature.MEDIA_UPLOAD))
        assertFalse(matrix.isPlanned(CameraFeature.USB_DIAGNOSTICS))
        assertFalse(matrix.isPlanned(CameraFeature.DESKTOP_BRIDGE))
    }

    @Test
    fun integerRangeRejectsInvalidBoundsAndSteps() {
        assertFalse(CameraIntegerRange(minimum = 2, maximum = 1, step = 1).accepts("1"))
        assertFalse(CameraIntegerRange(minimum = 1, maximum = 2, step = 0).accepts("1"))
        assertFalse(CameraIntegerRange(minimum = 1, maximum = 5, step = 2).accepts("2"))
        assertTrue(CameraIntegerRange(minimum = 1, maximum = 5, step = 2).accepts("3"))
    }
}
