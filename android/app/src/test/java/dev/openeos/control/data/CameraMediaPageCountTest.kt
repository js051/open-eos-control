package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

class CameraMediaPageCountTest {
    @Test fun negativeCountsKeepThePreciseDiagnosticAndAreNeverAccepted() {
        for (value in listOf(-1, -1L, Long.MIN_VALUE)) {
            val failure = runCatching { validatedMediaPageCount(value) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("negative media page count"))
        }
    }
    @Test fun nonIntegerAndOverflowCountsRemainRejected() {
        for (value in listOf(null, "1", 1.0, true, Int.MAX_VALUE.toLong() + 1L, Long.MAX_VALUE)) {
            assertTrue(runCatching { validatedMediaPageCount(value) }.exceptionOrNull() is IllegalStateException)
        }
    }
    @Test fun validZeroAndBoundedCountsStayExact() {
        assertEquals(0, validatedMediaPageCount(0))
        assertEquals(1000, validatedMediaPageCount(1000L))
        assertEquals(Int.MAX_VALUE, validatedMediaPageCount(Int.MAX_VALUE.toLong()))
    }
}
