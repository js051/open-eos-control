package dev.openeos.control.data

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Executes the real PtpSession with synthetic containers, never a USB device. */
class PtpUploadBoundaryTest {
    @Test fun cancelledGatePreventsSendObjectInfoAtTheActualProtocolBoundary() = runBlocking {
        val transport = FixtureTransport()
        val session = PtpSession(transport); session.initialize()
        val attempt = MediaUploadAttempt().also { it.cancel() }
        val failure = runCatching {
            session.uploadObject(1, 0, objectInfo(), ByteArrayInputStream(byteArrayOf(7)),
                beforeDispatch = attempt::beforeDispatch)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(transport.sent.none { it.code == PtpOperationCode.SEND_OBJECT_INFO })
        assertEquals(MediaUploadStatus.NOT_SENT, attempt.finish())
    }
    @Test fun oneDispatchGatePrecedesActualSendObjectInfoAndReturnedHandleIsRetained() = runBlocking {
        val transport = FixtureTransport()
        val session = PtpSession(transport); session.initialize()
        var dispatches = 0
        val result = session.uploadObject(1, 0, objectInfo(), ByteArrayInputStream(byteArrayOf(7)), beforeDispatch = {
            assertTrue(transport.sent.none { it.code == PtpOperationCode.SEND_OBJECT_INFO })
            dispatches++
        })
        assertEquals(1, dispatches)
        assertEquals(PtpSendObjectInfoResult(1, 0, 7), result)
        assertEquals(listOf(PtpOperationCode.SEND_OBJECT_INFO, PtpOperationCode.SEND_OBJECT),
            transport.sent.filter { it.type == PtpContainerType.COMMAND && it.code in
                setOf(PtpOperationCode.SEND_OBJECT_INFO, PtpOperationCode.SEND_OBJECT) }.map { it.code })
    }
    @Test fun lostSendObjectResponseLeavesDispatchedAttemptUnknownWithoutReplay() = runBlocking {
        val transport = FixtureTransport(omitFinalResponse = true)
        val session = PtpSession(transport); session.initialize()
        val attempt = MediaUploadAttempt()
        assertNotNull(runCatching {
            session.uploadObject(1, 0, objectInfo(), ByteArrayInputStream(byteArrayOf(7)),
                beforeDispatch = attempt::beforeDispatch)
        }.exceptionOrNull())
        assertEquals(MediaUploadStatus.UNCERTAIN, attempt.finish())
        assertEquals(1, transport.sent.count { it.type == PtpContainerType.COMMAND && it.code == PtpOperationCode.SEND_OBJECT })
    }
    private fun objectInfo() = PtpObjectInfo(
        handle = 0, storageId = 1, objectFormat = PtpObjectFormat.EXIF_JPEG, protectionStatus = 0,
        sizeBytes = 1, thumbnailFormat = 0, thumbnailSizeBytes = 0, thumbnailWidth = 0, thumbnailHeight = 0,
        imageWidth = 0, imageHeight = 0, imageBitDepth = 0, parentObject = 0, associationType = 0,
        associationDescription = 0, sequenceNumber = 0, filename = "SYNTHETIC.JPG", captureDate = "",
        modificationDate = "", keywords = "",
    )
    private class FixtureTransport(omitFinalResponse: Boolean = false) : PtpTransport {
        val sent = mutableListOf<PtpContainer>()
        private val incoming = ArrayDeque<PtpContainer>().apply {
            // Minimal standard device-info dataset: five empty arrays, no personal/device data.
            add(PtpContainer(PtpContainerType.DATA, PtpOperationCode.GET_DEVICE_INFO, 0, ByteArray(31).also { it[0] = 100 }))
            add(ok(0)); add(ok(0)); add(ok(1, 1, 0, 7))
            if (!omitFinalResponse) add(ok(2))
        }
        override suspend fun send(container: PtpContainer) { sent += container }
        override suspend fun receive(maxPayloadBytes: Int): PtpContainer = incoming.removeFirstOrNull()
            ?: throw PtpProtocolException("Synthetic lost response")
        override fun close() = Unit
        companion object {
            private fun ok(transaction: Long, vararg parameters: Long) = PtpContainer(
                PtpContainerType.RESPONSE, PtpResponseCode.OK, transaction,
                ByteArray(parameters.size * 4).also { bytes -> parameters.forEachIndexed { index, value ->
                    repeat(4) { byte -> bytes[index * 4 + byte] = (value ushr (byte * 8)).toByte() }
                } },
            )
        }
    }
}
