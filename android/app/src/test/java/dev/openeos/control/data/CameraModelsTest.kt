package dev.openeos.control.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelsTest {
    @Test
    fun integerRangeRejectsInvalidBoundsAndSteps() {
        assertFalse(CameraIntegerRange(minimum = 2, maximum = 1, step = 1).accepts("1"))
        assertFalse(CameraIntegerRange(minimum = 1, maximum = 2, step = 0).accepts("1"))
        assertFalse(CameraIntegerRange(minimum = 1, maximum = 5, step = 2).accepts("2"))
        assertTrue(CameraIntegerRange(minimum = 1, maximum = 5, step = 2).accepts("3"))
    }
}
