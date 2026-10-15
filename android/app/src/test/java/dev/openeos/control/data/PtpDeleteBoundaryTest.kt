package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic containers only, with the real PtpSession. Never probes deletion on USB hardware. */
class PtpDeleteBoundaryTest {
    @Test fun invalidAndReservedHandlesNeverSendDelete() = runBlocking {
        for (id in listOf("ptp:00000000", "ptp:FFFFFFFF", "ptp:100000000", "00000042", "host:42", "ptp:-1", "ptp: 42")) {
            assertTrue(runCatching { mediaDeletePtpHandle(id) }.isFailure)
        }
        assertEquals(66L, mediaDeletePtpHandle("ptp:00000042"))
        val transport = FixtureTransport(); val session = PtpSession(transport); session.initialize()
        for (handle in listOf(0L, -1L, 0xFFFF_FFFFL, 0x1_0000_0000L)) assertTrue(runCatching { session.deleteObject(handle) }.isFailure)
        assertTrue(runCatching { session.deleteObject(66, objectFormat = 0x3801) }.isFailure)
        assertEquals(0, transport.deleteCount)
    }
    @Test fun cancelledGatePreventsActualDeleteSend() = runBlocking {
        val transport = FixtureTransport(); val session = PtpSession(transport); session.initialize()
        val attempt = MediaDeleteAttempt().also { it.cancel() }
        assertTrue(runCatching { session.deleteObject(66, evidence = evidence(attempt)) }.exceptionOrNull() is CancellationException)
        assertEquals(0, transport.deleteCount)
        assertEquals(MediaDeleteOutcome.NOT_SENT, attempt.finish())
    }
    @Test fun matchingOkAcknowledgesOneExactHandle() = runBlocking {
        val transport = FixtureTransport(); val session = PtpSession(transport); session.initialize()
        val attempt = MediaDeleteAttempt(); session.deleteObject(66, evidence = evidence(attempt))
        assertEquals(1, transport.deleteCount)
        assertEquals(MediaDeleteOutcome.CONFIRMED, attempt.finish())
        assertEquals(listOf(66L, 0L), PtpCodec.responseParameters(transport.sent.last().copy(type = PtpContainerType.RESPONSE)))
    }
    @Test fun nonOkMismatchAndLostAckStayUncertainWithoutADeleteSpecificRefusalContract() = runBlocking {
        for ((transport, expected) in listOf(
            FixtureTransport(response = PtpResponseCode.OBJECT_WRITE_PROTECTED) to MediaDeleteOutcome.UNCERTAIN,
            FixtureTransport(response = PtpResponseCode.GENERAL_ERROR) to MediaDeleteOutcome.UNCERTAIN,
            FixtureTransport(response = 0x2012) to MediaDeleteOutcome.UNCERTAIN, // PartialDeletion
            FixtureTransport(response = 0xA001) to MediaDeleteOutcome.UNCERTAIN, // unknown vendor response
            FixtureTransport(response = 0x2000) to MediaDeleteOutcome.UNCERTAIN, // undefined response
            FixtureTransport(transaction = 99) to MediaDeleteOutcome.UNCERTAIN,
            FixtureTransport(loseResponse = true) to MediaDeleteOutcome.UNCERTAIN)) {
            val session = PtpSession(transport); session.initialize(); val attempt = MediaDeleteAttempt()
            assertTrue(runCatching { session.deleteObject(66, evidence = evidence(attempt)) }.isFailure)
            assertEquals(1, transport.deleteCount); assertEquals(expected, attempt.finish())
        }
    }
    @Test fun unknownNativePtpResponseStopsTheRemainingBatch() = runBlocking {
        val transport = FixtureTransport(response = 0x2012)
        val session = PtpSession(transport); session.initialize()
        val result = dev.openeos.control.ui.executeMediaDeleteBatch("ptp-partial", dev.openeos.control.ui.MediaDeleteOwner(Any(), 1),
            listOf("ptp:00000042", "ptp:00000043").map {
                dev.openeos.control.ui.MediaDeleteRequest(it, "SAME.JPG", dev.openeos.control.ui.MediaDeleteSource.CAMERA_CARD)
            }, dev.openeos.control.ui.MediaDeleteBatchControl(), { true }, { null },
            { request, evidence -> session.deleteObject(mediaDeletePtpHandle(request.id), evidence = evidence) })
        assertEquals(listOf(MediaDeleteOutcome.UNCERTAIN, MediaDeleteOutcome.NOT_STARTED), result.rows.map { it.outcome })
        assertEquals(1, transport.deleteCount)
    }
    @Test fun receiptCapturedBeforeTransportPromptCancellationSurvives() = runBlocking {
        val transport = FixtureTransport(cancelAfterObserved = true); val session = PtpSession(transport); session.initialize()
        val attempt = MediaDeleteAttempt()
        assertTrue(runCatching { session.deleteObject(66, evidence = evidence(attempt)) }.exceptionOrNull() is CancellationException)
        assertEquals(MediaDeleteOutcome.CONFIRMED, attempt.finish())
        assertEquals(1, transport.deleteCount)
    }
    private fun evidence(attempt: MediaDeleteAttempt) = MediaDeleteEvidence(attempt::beforeDispatch, attempt::confirm, attempt::reject)
    private class FixtureTransport(response: Int = PtpResponseCode.OK, transaction: Long = 1,
        loseResponse: Boolean = false, private val cancelAfterObserved: Boolean = false) : PtpTransport {
        val sent = mutableListOf<PtpContainer>()
        val deleteCount get() = sent.count { it.code == PtpOperationCode.DELETE_OBJECT }
        private val incoming = ArrayDeque<PtpContainer>().apply {
            add(PtpContainer(PtpContainerType.DATA, PtpOperationCode.GET_DEVICE_INFO, 0, ByteArray(31).also { it[0] = 100 }))
            add(PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, 0))
            add(PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, 0))
            if (!loseResponse) add(PtpContainer(PtpContainerType.RESPONSE, response, transaction))
        }
        override suspend fun send(container: PtpContainer) { sent += container }
        override suspend fun receive(maxPayloadBytes: Int) = incoming.removeFirstOrNull() ?: error("Synthetic lost ACK")
        override suspend fun receiveObserved(onReceived: (PtpContainer) -> Unit): PtpContainer {
            val value = receive(); onReceived(value)
            if (cancelAfterObserved) throw CancellationException("Synthetic prompt cancellation")
            return value
        }
        override fun close() = Unit
    }
}
