package dev.openeos.control.ui

import dev.openeos.control.data.readCaptureConfigurationJsonText
import org.junit.Assert.*
import org.junit.Test

class CaptureConfigurationJsonBoundaryTest {
    @Test fun completeJsonHasExact64KiBMaximum() {
        val exact = "{}" + " ".repeat(65_534)
        assertEquals(exact, readCaptureConfigurationJsonText(exact.byteInputStream(), 65_536))
        val oversized = exact + " "
        assertTrue(runCatching { readCaptureConfigurationJsonText(oversized.byteInputStream(), -1) }.isFailure)
        assertTrue(runCatching { readCaptureConfigurationJsonText("{}".byteInputStream(), 65_537) }.isFailure)
    }
    @Test fun incompleteOrExtraDeclaredBodyCannotCreateAck() {
        assertTrue(runCatching { readCaptureConfigurationJsonText("{}".byteInputStream(), 3) }.isFailure)
        assertTrue(runCatching { readCaptureConfigurationJsonText("{} ".byteInputStream(), 2) }.isFailure)
        assertEquals("{}", readCaptureConfigurationJsonText("{}".byteInputStream(), -1))
    }
    @Test fun malformedUtf8AndDuplicateReceiptKeysAreRejected() {
        assertTrue(runCatching { readCaptureConfigurationJsonText(byteArrayOf(0xC3.toByte(), 0x28).inputStream(), 2) }.isFailure)
        val duplicate = """{"directoryname":"ABCDE","directoryname":"OTHER"}"""
        assertTrue(runCatching { readCaptureConfigurationJsonText(duplicate.byteInputStream(), -1) }.isFailure)
        val nested = """{"error":{"captureConfigReceipt":{"version":1,"version":1}}}"""
        assertTrue(runCatching { readCaptureConfigurationJsonText(nested.byteInputStream(), -1) }.isFailure)
    }
    @Test fun nonJsonAndTrailingJsonAreRejectedBeforeSemanticReceiptParsing() {
        for (invalid in listOf("{'directoryname':'ABCDE'}", "{\"directoryname\":\"ABCDE\"}{}", "{\"directoryname\":\"ABCDE\"", "NaN")) {
            assertTrue(invalid, runCatching { readCaptureConfigurationJsonText(invalid.byteInputStream(), -1) }.isFailure)
        }
    }
}
