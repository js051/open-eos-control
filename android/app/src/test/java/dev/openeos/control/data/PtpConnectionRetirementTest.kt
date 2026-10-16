package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Full production PtpSession and codec against synthetic containers; never USB/device evidence. */
class PtpConnectionRetirementTest {
    @Test fun cancelledOpenStillClosesTheAttemptedSessionBeforeTransport() = runBlocking {
        val peer = Peer(holdOpen = true)
        val session = PtpSession(peer)
        val opening = launch(start = CoroutineStart.UNDISPATCHED) { session.initialize() }
        peer.openEntered.await(); opening.cancelAndJoin()
        session.shutdownConnectionAttempt()
        assertEquals(listOf(PtpOperationCode.GET_DEVICE_INFO, PtpOperationCode.OPEN_SESSION, PtpOperationCode.CLOSE_SESSION), peer.sent)
        assertTrue(peer.closed)
    }

    @Test fun failedCloseAckCannotBecomeConfirmedRetirement() = runBlocking {
        val peer = Peer(closeResponse = PtpResponseCode.GENERAL_ERROR)
        val session = PtpSession(peer); session.initialize()
        val failure = runCatching { session.shutdownConnectionAttempt() }.exceptionOrNull()
        assertTrue(failure is PtpResponseException)
        assertTrue(peer.closed)
        session.shutdownConnectionAttempt()
        assertEquals(1, peer.sent.count { it == PtpOperationCode.CLOSE_SESSION })
    }

    @Test fun explicitSessionNotOpenIsConfirmedAlreadyRetired() = runBlocking {
        val peer = Peer(closeResponse = PtpResponseCode.SESSION_NOT_OPEN)
        val session = PtpSession(peer); session.initialize(); session.shutdownConnectionAttempt()
        assertTrue(peer.closed)
    }

    @Test fun cancellationBeforeOpenDispatchClosesOnlyTransport() = runBlocking {
        val peer = Peer(holdInfo = true); val session = PtpSession(peer)
        val opening = launch(start = CoroutineStart.UNDISPATCHED) { session.initialize() }
        peer.infoEntered.await(); opening.cancelAndJoin(); session.shutdownConnectionAttempt()
        assertEquals(listOf(PtpOperationCode.GET_DEVICE_INFO), peer.sent); assertTrue(peer.closed)
    }

    @Test fun ordinaryShutdownRetainsExistingBestEffortBehavior() = runBlocking {
        val peer = Peer(closeResponse = PtpResponseCode.GENERAL_ERROR)
        val session = PtpSession(peer); session.initialize(); session.shutdown()
        assertTrue(peer.closed)
    }

    @Test fun definiteOpenRejectionNeverClosesAnExistingSession() = runBlocking {
        val peer = Peer(openResponse = PtpResponseCode.SESSION_ALREADY_OPEN)
        val session = PtpSession(peer)
        assertTrue(runCatching { session.initialize() }.exceptionOrNull() is PtpResponseException)
        session.shutdownConnectionAttempt()
        assertEquals(listOf(PtpOperationCode.GET_DEVICE_INFO, PtpOperationCode.OPEN_SESSION), peer.sent)
        assertTrue(peer.closed)
    }

    private class Peer(val holdOpen: Boolean = false, val holdInfo: Boolean = false,
        val closeResponse: Int = PtpResponseCode.OK, val openResponse: Int = PtpResponseCode.OK) : PtpTransport {
        val sent = mutableListOf<Int>(); var closed = false
        val openEntered = CompletableDeferred<Unit>(); val infoEntered = CompletableDeferred<Unit>()
        private var command: PtpContainer? = null; private var receivedData = false
        override suspend fun send(container: PtpContainer) { command = container; receivedData = false; sent += container.code }
        override suspend fun receive(maxPayloadBytes: Int): PtpContainer {
            val current = requireNotNull(command)
            if (current.code == PtpOperationCode.GET_DEVICE_INFO && !receivedData) {
                infoEntered.complete(Unit); if (holdInfo) awaitCancellation()
                receivedData = true
                return PtpContainer(PtpContainerType.DATA, current.code, current.transactionId, deviceInfo())
            }
            if (current.code == PtpOperationCode.OPEN_SESSION) {
                openEntered.complete(Unit); if (holdOpen) awaitCancellation()
            }
            return PtpContainer(PtpContainerType.RESPONSE,
                when (current.code) { PtpOperationCode.CLOSE_SESSION -> closeResponse; PtpOperationCode.OPEN_SESSION -> openResponse; else -> PtpResponseCode.OK },
                current.transactionId)
        }
        override fun close() { closed = true }
        private fun deviceInfo(): ByteArray {
            val bytes = ByteArrayOutputStream()
            fun u16(value: Int) { bytes.write(value and 255); bytes.write((value ushr 8) and 255) }
            fun u32(value: Int) { u16(value and 65535); u16(value ushr 16) }
            u16(100); u32(0); u16(100); bytes.write(0); u16(0)
            repeat(5) { u32(0) }
            repeat(4) { bytes.write(0) }
            return bytes.toByteArray()
        }
    }
}
