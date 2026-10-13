package dev.openeos.control.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeVideoGeometryTest {
    @Test
    fun cropUsesInclusiveEdgesBeforeRotation() {
        assertEquals(1920 to 1080, nativeVideoDisplayDimensions(1920, 1088, 0, 0, 1919, 1079))
        assertEquals(1080 to 1920, nativeVideoDisplayDimensions(1920, 1088, 0, 0, 1919, 1079, 90))
        assertEquals(1080 to 1920, nativeVideoDisplayDimensions(1920, 1088, 0, 0, 1919, 1079, -90))
    }

    @Test
    fun missingOrMalformedCropDoesNotProduceInvalidDimensions() {
        assertEquals(1920 to 1080, nativeVideoDisplayDimensions(1920, 1080))
        assertEquals(1920 to 1080, nativeVideoDisplayDimensions(1920, 1080, 10, 0, 9, 9999))
        assertEquals(1 to 1, nativeVideoDisplayDimensions(0, -1))
    }
}
