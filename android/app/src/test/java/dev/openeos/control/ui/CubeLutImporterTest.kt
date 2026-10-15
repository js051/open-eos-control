package dev.openeos.control.ui

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** Drives the real production controller with deterministic queues, without coroutine-test. */
class CubeLutImporterTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun next() { check(queue.isNotEmpty()); queue.removeFirst().run() }
    }
    private class Harness {
        val main = QueueDispatcher()
        val io = QueueDispatcher()
        val parse = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val snapshots = mutableListOf<CubeLutImportState>()
        val importer = CubeLutImporter(scope, io, parse) { snapshots += it }
        val state get() = importer.state
        fun drain() {
            var steps = 0
            while (listOf(main, io, parse).any { it.queue.isNotEmpty() }) {
                check(steps++ < 1000) { "Pipeline failed to settle" }
                for (dispatcher in listOf(main, io, parse)) if (dispatcher.queue.isNotEmpty()) dispatcher.next()
            }
        }
        fun load(name: String) { importer.select { document(name) }; drain() }
    }

    @Test fun successOnlyReplacesAppliedLutAfterReadAndParseComplete() {
        val h = Harness(); h.load("old")
        val old = h.state.lut
        h.importer.select { document("new") }
        assertSame(old, h.state.lut); assertTrue(h.state.status.loading)
        h.io.next(); h.main.next()
        assertSame(old, h.state.lut)
        h.parse.next()
        assertSame(old, h.state.lut)
        h.drain()
        assertEquals("new", h.state.lut?.name); assertFalse(h.state.status.loading)
    }

    @Test fun replacementBeforeIoDispatchCannotStrandLazyWorkOrOpenOldSource() {
        val h = Harness(); var oldReads = 0
        h.importer.select { oldReads++; document("old") }
        h.importer.select { document("new") }
        h.drain()
        assertEquals(0, oldReads); assertEquals("new", h.state.lut?.name)
        assertFalse(h.state.status.loading); assertFalse(h.state.status.stopping)
    }

    @Test fun cancelBeforeIoDispatchSettlesAndNextSelectionCanRun() {
        val h = Harness(); var reads = 0
        h.importer.select { reads++; document("never") }; h.importer.cancel(); h.drain()
        assertEquals(0, reads); assertFalse(h.state.status.stopping); assertNull(h.state.status.error)
        h.load("retry"); assertEquals("retry", h.state.lut?.name)
    }

    @Test fun nonCooperativeReadAllowsOnlyOneActiveAndTheLatestPendingSelection() {
        val h = Harness(); var continuation: Continuation<CubeLutDocument>? = null
        val opened = mutableListOf<String>()
        h.importer.select { opened += "A"; suspendCoroutine { continuation = it } }; h.io.next()
        for (name in listOf("B", "C", "D")) h.importer.select { opened += name; document(name) }
        h.drain()
        assertEquals(listOf("A"), opened); assertTrue(h.state.status.waitingForPrevious)
        assertTrue(h.state.status.stopping); assertTrue(h.state.status.loading)
        continuation!!.resume(document("A")); h.drain()
        assertEquals(listOf("A", "D"), opened); assertEquals("D", h.state.lut?.name)
        assertFalse(h.state.status.stopping)
    }

    @Test fun retiredReadFailureCannotOverwriteTheLatestLoadingOrError() {
        val h = Harness(); h.load("applied")
        var continuation: Continuation<CubeLutDocument>? = null
        h.importer.select { suspendCoroutine { continuation = it } }; h.io.next()
        h.importer.select { throw IOException("latest failure") }
        continuation!!.resumeWithException(IOException("old failure")); h.drain()
        assertEquals("applied", h.state.lut?.name)
        assertEquals("3D LUT: latest failure", h.state.status.error)
        assertTrue(h.snapshots.none { it.status.error?.contains("old failure") == true })
    }

    @Test fun oldParsedResultQueuedForMainCannotReclaimNewSelectionIdentity() {
        val h = Harness(); h.load("applied")
        h.importer.select { document("old") }; h.io.next(); h.main.next(); h.parse.next()
        h.importer.select { document("latest") }; h.drain()
        assertEquals("latest", h.state.lut?.name)
        assertTrue(h.snapshots.none { it.lut?.name == "old" })
    }

    @Test fun clearAfterParseBeforePublicationFencesResurrection() {
        val h = Harness(); h.load("applied")
        h.importer.select { document("old") }; h.io.next(); h.main.next(); h.parse.next()
        h.importer.remove(); h.drain()
        assertNull(h.state.lut); assertFalse(h.state.status.loading); assertNull(h.state.status.error)
    }

    @Test fun cancelRetainsAppliedLutAndShowsPhysicalRetirementUntilReadExits() {
        val h = Harness(); h.load("applied"); val applied = h.state.lut
        var continuation: Continuation<CubeLutDocument>? = null
        h.importer.select { suspendCoroutine { continuation = it } }; h.io.next()
        h.importer.cancel()
        assertSame(applied, h.state.lut); assertFalse(h.state.status.loading); assertTrue(h.state.status.stopping)
        continuation!!.resumeWithException(IOException("late")); h.drain()
        assertSame(applied, h.state.lut); assertNull(h.state.status.error); assertFalse(h.state.status.stopping)
    }

    @Test fun removeDropsPendingAndAppliedButNeverPretendsBlockedReadStopped() {
        val h = Harness(); h.load("applied")
        var continuation: Continuation<CubeLutDocument>? = null; var pendingReads = 0
        h.importer.select { suspendCoroutine { continuation = it } }; h.io.next()
        h.importer.select { pendingReads++; document("pending") }; h.importer.remove()
        assertNull(h.state.lut); assertTrue(h.state.status.stopping)
        continuation!!.resume(document("late")); h.drain()
        assertEquals(0, pendingReads); assertNull(h.state.lut); assertNull(h.state.status.error)
    }

    @Test fun readParseAndEncodingFailuresPreserveAppliedLutAndRetryClearsOnlyImportError() {
        val h = Harness(); h.load("applied"); val applied = h.state.lut
        val failures: List<suspend () -> CubeLutDocument> = listOf(
            { throw IOException("unreadable") },
            { CubeLutDocument("bad", "LUT_1D_SIZE 2") },
            { CubeLutDocument("bad", readCubeLutText(ByteArrayInputStream(byteArrayOf(0x80.toByte())))) },
        )
        failures.forEach { read ->
            h.importer.select(read); h.drain()
            assertSame(applied, h.state.lut); assertNotNull(h.state.status.error); assertFalse(h.state.status.loading)
        }
        h.importer.select { document("retry") }; assertNull(h.state.status.error)
        assertSame(applied, h.state.lut); h.drain(); assertEquals("retry", h.state.lut?.name)
    }

    @Test fun cancellationExceptionIsNeverAnError() {
        val h = Harness(); h.load("applied")
        h.importer.select { throw CancellationException("cancelled provider") }; h.drain()
        assertEquals("applied", h.state.lut?.name); assertNull(h.state.status.error); assertFalse(h.state.status.loading)
    }

    @Test fun openingAndDismissingPickerDoNotSupersedeAcceptedWork() {
        val h = Harness(); h.importer.select { document("accepted") }
        val ticket = h.importer.beginPicker()!!
        assertNull(h.importer.beginPicker()); assertTrue(h.state.status.pickerOpen)
        h.importer.completePicker(ticket, null); h.importer.select(null); h.drain()
        assertEquals("accepted", h.state.lut?.name); assertNull(h.state.status.error)
        assertFalse(h.state.status.pickerOpen)
    }

    @Test fun removeRevokesOutstandingPickerAndDuplicateCallbackCannotBecomeNewSelection() {
        val h = Harness(); h.load("applied"); var reads = 0
        val old = h.importer.beginPicker()!!; h.importer.remove()
        assertNull(h.importer.beginPicker()) // One outstanding OS request, even after revocation.
        h.importer.completePicker(old) { reads++; document("late") }; h.drain()
        assertNull(h.state.lut); assertEquals(0, reads)
        val latest = h.importer.beginPicker()!!
        h.importer.completePicker(old) { reads++; document("duplicate") }
        assertTrue(h.state.status.pickerOpen)
        h.importer.completePicker(latest) { document("latest") }; h.drain()
        assertEquals("latest", h.state.lut?.name); assertEquals(0, reads)
    }

    @Test fun oldPickerSuccessCannotSupersedeSelectionAcceptedAfterItOpened() {
        val h = Harness(); var staleReads = 0
        val old = h.importer.beginPicker()!!
        h.importer.select { document("new") }
        h.importer.completePicker(old) { staleReads++; document("old") }; h.drain()
        assertEquals(0, staleReads); assertEquals("new", h.state.lut?.name)
    }

    @Test fun restoredTicketWithoutOriginalOwnerStaysVisibleAndCanOnlyRetire() {
        val h = Harness(); var reads = 0
        h.importer.restorePicker("previous-process")
        assertTrue(h.state.status.pickerRecoveryRequired); assertTrue(h.state.status.pickerOpen)
        assertNull(h.importer.beginPicker())
        h.importer.completePicker("previous-process") { reads++; document("old process") }; h.drain()
        assertEquals(0, reads); assertFalse(h.state.status.pickerOpen)
        assertFalse(h.state.status.pickerRecoveryRequired); assertNotNull(h.importer.beginPicker())
    }

    @Test fun explicitCancelRevokesPickerButRetainsApplied() {
        val h = Harness(); h.load("applied"); val ticket = h.importer.beginPicker()!!
        h.importer.cancel(); h.importer.completePicker(ticket) { document("late") }; h.drain()
        assertEquals("applied", h.state.lut?.name); assertNull(h.state.status.error)
    }

    @Test fun pickerFailureBelongsOnlyToItsTicketAndSelectionGeneration() {
        val h = Harness(); h.load("applied"); val old = h.importer.beginPicker()!!
        h.importer.select { document("new") }; h.importer.pickerFailed(old, "old launch"); h.drain()
        assertEquals("new", h.state.lut?.name); assertNull(h.state.status.error)
        val current = h.importer.beginPicker()!!
        h.importer.pickerFailed(current, "no picker available")
        assertEquals("new", h.state.lut?.name); assertEquals("3D LUT: no picker available", h.state.status.error)
        h.importer.pickerFailed(current, "duplicate"); assertEquals("3D LUT: no picker available", h.state.status.error)
    }

    @Test fun closeAndScopeCancellationCannotStartOrPublishPendingWork() {
        val h = Harness(); h.importer.select { document("old") }
        h.importer.select { document("pending") }; h.importer.close(); h.scope.cancel(); h.drain()
        assertNull(h.state.lut); assertFalse(h.state.status.loading); assertFalse(h.state.status.stopping)
        h.importer.select { document("after close") }; assertNull(h.importer.beginPicker())
        assertNull(h.state.lut)
    }

    companion object {
        internal val CUBE = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"
        private fun document(name: String) = CubeLutDocument("$name.cube", CUBE)
    }
}

class CubeLutReaderTest {
    @Test fun sharedByteVectorsPassThroughTheProductionDecoderAndExistingParser() {
        val vectors = loadCubeLutUtf8Vectors()
        assertEquals(16, vectors.size)
        for (vector in vectors) {
            if (vector.accepted) {
                val text = readCubeLutText(ByteArrayInputStream(vector.bytes))
                assertEquals(vector.id, vector.normalizedText, text)
                assertEquals(vector.id, 2, parseCubeLut(text, "fallback.cube").size)
            } else {
                assertThrows(vector.id, IllegalArgumentException::class.java) {
                    readCubeLutText(ByteArrayInputStream(vector.bytes))
                }
            }
        }
    }

    @Test fun exactByteLimitAcceptedAndOversizedStreamReadsOnlyOneSentinel() {
        val exact = CountingStream(MAX_CUBE_LUT_BYTES)
        assertEquals(MAX_CUBE_LUT_BYTES, readCubeLutText(exact).length)
        assertEquals(MAX_CUBE_LUT_BYTES, exact.count)
        val oversized = CountingStream(MAX_CUBE_LUT_BYTES * 4)
        assertThrows(IllegalArgumentException::class.java) { readCubeLutText(oversized) }
        assertEquals(MAX_CUBE_LUT_BYTES + 1, oversized.count)
    }

    @Test fun bomBytesCountTowardLimitBeforeNormalization() {
        val bytes = ByteArray(MAX_CUBE_LUT_BYTES) { ' '.code.toByte() }
        bytes[0] = 0xEF.toByte(); bytes[1] = 0xBB.toByte(); bytes[2] = 0xBF.toByte()
        assertEquals(MAX_CUBE_LUT_BYTES - 3, readCubeLutText(ByteArrayInputStream(bytes)).length)
        assertThrows(IllegalArgumentException::class.java) { readCubeLutText(ByteArrayInputStream(bytes + 32.toByte())) }
    }

    @Test fun zeroLengthBulkReadFallsBackWithoutSpinningOrLosingBytes() {
        val bytes = CubeLutImporterTest.CUBE.toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertEquals(CubeLutImporterTest.CUBE, readCubeLutText(input))
    }

    @Test fun callerClosesStreamOnReadDecodeAndCancellationFailure() {
        for (mode in listOf("io", "utf8", "cancel")) {
            var closed = false
            val input = object : ByteArrayInputStream(byteArrayOf(0x80.toByte())) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (mode == "io") throw IOException("synthetic")
                    return super.read(b, off, len)
                }
                override fun close() { closed = true; super.close() }
            }
            assertThrows(Exception::class.java) {
                input.use { readCubeLutText(it) { if (mode == "cancel") throw CancellationException("synthetic") } }
            }
            assertTrue(mode, closed)
        }
    }

    @Test fun parserCooperativelyChecksCancellationInsideLines() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            parseCubeLut(CubeLutImporterTest.CUBE, "valid") { if (++checks == 4) throw CancellationException("stop") }
        }
        assertEquals(4, checks)
    }

    private class CountingStream(private val size: Int) : InputStream() {
        var count = 0
        override fun read(): Int = if (count == size) -1 else { count++; 32 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val amount = minOf(len, size - count)
            if (amount == 0) return -1
            java.util.Arrays.fill(b, off, off + amount, 32.toByte()); count += amount
            return amount
        }
    }
}
