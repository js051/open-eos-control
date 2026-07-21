package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class PtpProtocolTest {
    @Test
    fun bufferedInputPreservesPayloadReceivedWithTheHeader() = runTest {
        val completeContainer = PtpCodec.encode(
            PtpContainer(
                type = PtpContainerType.DATA,
                code = PtpOperationCode.GET_STORAGE_IDS,
                transactionId = 1,
                payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            )
        )
        var usbReads = 0
        val input = PtpBufferedInput(64) { destination ->
            usbReads += 1
            completeContainer.copyInto(destination)
            completeContainer.size
        }

        val header = PtpCodec.decodeHeader(input.readExact(PTP_USB_CONTAINER_HEADER_BYTES))
        val payload = input.readExact(header.payloadLength.toInt())

        assertEquals(1, usbReads)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), payload)
    }

    @Test
    fun openSessionCommandMatchesUsbPtpReferencePacket() {
        val bytes = PtpCodec.encode(
            PtpCodec.command(
                operationCode = PtpOperationCode.OPEN_SESSION,
                transactionId = 0L,
                parameters = listOf(1L),
            )
        )

        assertArrayEquals(
            byteArrayOf(
                0x10, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x02, 0x10,
                0x00, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00,
            ),
            bytes,
        )
        val decoded = PtpCodec.decode(bytes)
        assertEquals(PtpContainerType.COMMAND, decoded.type)
        assertEquals(PtpOperationCode.OPEN_SESSION, decoded.code)
        assertEquals(0L, decoded.transactionId)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), decoded.payload)
    }

    @Test
    fun deviceInfoParserReadsPtpStringsAndOperationArrays() {
        val parsed = PtpDatasets.deviceInfo(deviceInfoPayload())

        assertEquals(100, parsed.standardVersion)
        assertEquals(0x0000000BL, parsed.vendorExtensionId)
        assertEquals("Canon EOS R6 Mark III", parsed.model)
        assertEquals("TEST-SERIAL-0001", parsed.serialNumber)
        assertTrue(parsed.supports(PtpOperationCode.GET_STORAGE_IDS))
        assertTrue(parsed.supports(PtpOperationCode.GET_OBJECT))
        assertFalse(parsed.supports(PtpOperationCode.SET_DEVICE_PROP_VALUE))
    }

    @Test
    fun storageAndObjectDatasetsUseUnsignedLittleEndianFields() {
        val storage = PtpDatasets.storageInfo(0x00010001, storageInfoPayload())
        val objectInfo = PtpDatasets.objectInfo(0x55, objectInfoPayload())

        assertEquals(64UL * 1024UL * 1024UL * 1024UL, storage.maxCapacityBytes)
        assertEquals(32UL * 1024UL * 1024UL * 1024UL, storage.freeSpaceBytes)
        assertEquals("SD", storage.description)
        assertEquals(0x00010001L, objectInfo.storageId)
        assertEquals("IMG_0042.JPG", objectInfo.filename)
        assertEquals(24_000_000L, objectInfo.sizeBytes)
        assertEquals("20260721T143025", objectInfo.captureDate)
    }

    @Test
    fun sessionOpensAndKeepsMonotonicTransactionIds() = runTest {
        val transport = FakePtpTransport(
            data(PtpOperationCode.GET_DEVICE_INFO, 0, deviceInfoPayload()),
            ok(0),
            ok(0),
            data(PtpOperationCode.GET_STORAGE_IDS, 1, u32Array(0x00010001)),
            ok(1),
            data(PtpOperationCode.GET_STORAGE_INFO, 2, storageInfoPayload()),
            ok(2),
            ok(3),
        )
        val session = PtpSession(transport)

        val info = session.initialize()
        val storageIds = session.storageIds()
        val storage = session.storageInfo(storageIds.single())
        session.shutdown()

        assertEquals("Canon EOS R6 Mark III", info.model)
        assertEquals(listOf(0x00010001L), storageIds)
        assertEquals("EOS_CARD", storage.volumeLabel)
        assertEquals(
            listOf(
                PtpOperationCode.GET_DEVICE_INFO,
                PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.GET_STORAGE_IDS,
                PtpOperationCode.GET_STORAGE_INFO,
                PtpOperationCode.CLOSE_SESSION,
            ),
            transport.sent.map { it.code },
        )
        assertEquals(listOf(0L, 0L, 1L, 2L, 3L), transport.sent.map { it.transactionId })
        assertTrue(transport.closed)
    }

    @Test
    fun sessionSurfacesPtpResponseCodeAndOperation() = runTest {
        val transport = FakePtpTransport(
            data(PtpOperationCode.GET_DEVICE_INFO, 0, deviceInfoPayload()),
            ok(0),
            PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.DEVICE_BUSY, 0),
        )
        val exception = try {
            PtpSession(transport).initialize()
            fail("Expected OpenSession to fail.")
            error("unreachable")
        } catch (exception: PtpResponseException) {
            exception
        }

        assertEquals(PtpOperationCode.OPEN_SESSION, exception.operationCode)
        assertEquals(PtpResponseCode.DEVICE_BUSY, exception.responseCode)
        assertTrue(exception.message.orEmpty().contains("DeviceBusy"))
        assertEquals("DevicePropNotSupported", PtpResponseCode.label(PtpResponseCode.DEVICE_PROP_NOT_SUPPORTED))
        assertEquals("InvalidDevicePropFormat", PtpResponseCode.label(PtpResponseCode.INVALID_DEVICE_PROP_FORMAT))
        assertEquals("InvalidDevicePropValue", PtpResponseCode.label(PtpResponseCode.INVALID_DEVICE_PROP_VALUE))
    }

    @Test
    fun getObjectStreamsDataAndReportsProgress() = runTest {
        val objectBytes = ByteArray(128 * 1024) { index -> (index and 0xFF).toByte() }
        val transport = FakePtpTransport(
            data(PtpOperationCode.GET_DEVICE_INFO, 0, deviceInfoPayload()),
            ok(0),
            ok(0),
            data(PtpOperationCode.GET_OBJECT, 1, objectBytes),
            ok(1),
            ok(2),
        )
        val session = PtpSession(transport)
        session.initialize()
        val destination = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long>>()

        val bytesTransferred = session.downloadObject(0x42, destination) { transferred, total ->
            progress += transferred to total
        }
        session.shutdown()

        assertEquals(objectBytes.size.toLong(), bytesTransferred)
        assertArrayEquals(objectBytes, destination.toByteArray())
        assertEquals(objectBytes.size.toLong() to objectBytes.size.toLong(), progress.last())
        assertEquals(PtpOperationCode.GET_OBJECT, transport.sent[2].code)
        assertArrayEquals(byteArrayOf(0x42, 0, 0, 0), transport.sent[2].payload)
    }

    @Test
    fun propertyReadAndWriteUseStandardPtpDataPhases() = runTest {
        val descriptorPayload = DatasetWriter().apply {
            u16(PtpDevicePropertyCode.EXPOSURE_INDEX)
            u16(PtpDataType.UINT16)
            u8(1)
            u16(100)
            u16(400)
            u8(2)
            u16(3)
            u16(100)
            u16(400)
            u16(800)
        }.bytes()
        val transport = FakePtpTransport(
            data(PtpOperationCode.GET_DEVICE_INFO, 0, deviceInfoPayload()),
            ok(0),
            ok(0),
            data(PtpOperationCode.GET_DEVICE_PROP_DESC, 1, descriptorPayload),
            ok(1),
            data(PtpOperationCode.GET_DEVICE_PROP_VALUE, 2, byteArrayOf(0x20, 0x03)),
            ok(2),
            ok(3),
            ok(4),
        )
        val session = PtpSession(transport)
        session.initialize()

        val descriptor = session.devicePropertyDescriptor(PtpDevicePropertyCode.EXPOSURE_INDEX)
        val value = session.devicePropertyValue(descriptor.code, descriptor.dataType)
        session.setDevicePropertyValue(
            descriptor.code,
            descriptor.dataType,
            PtpPropertyValue.Unsigned(800UL),
        )
        session.shutdown()

        assertEquals(PtpPropertyValue.Unsigned(800UL), value)
        assertEquals(PtpContainerType.COMMAND, transport.sent[4].type)
        assertEquals(PtpContainerType.DATA, transport.sent[5].type)
        assertEquals(PtpOperationCode.SET_DEVICE_PROP_VALUE, transport.sent[5].code)
        assertEquals(3L, transport.sent[5].transactionId)
        assertArrayEquals(byteArrayOf(0x20, 0x03), transport.sent[5].payload)
    }

    private class FakePtpTransport(vararg incoming: PtpContainer) : PtpTransport {
        private val incoming = ArrayDeque(incoming.toList())
        val sent = mutableListOf<PtpContainer>()
        var closed = false

        override suspend fun send(container: PtpContainer) {
            sent += container
        }

        override suspend fun receive(maxPayloadBytes: Int): PtpContainer {
            val next = incoming.removeFirstOrNull() ?: error("No fake PTP response is queued.")
            if (next.payload.size > maxPayloadBytes) error("Fake payload exceeds metadata limit.")
            return next
        }

        override suspend fun receiveTo(
            destination: OutputStream,
            expectedOperationCode: Int,
            expectedTransactionId: Long,
            onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
        ): PtpContainerReceipt {
            val next = incoming.removeFirstOrNull() ?: error("No fake PTP response is queued.")
            if (next.type == PtpContainerType.DATA) {
                if (next.code != expectedOperationCode || next.transactionId != expectedTransactionId) {
                    error("Unexpected fake PTP data container.")
                }
                destination.write(next.payload)
                onProgress(next.payload.size.toLong(), next.payload.size.toLong())
            }
            return PtpContainerReceipt(next.header, next.payload)
        }

        override fun close() {
            closed = true
        }
    }

    private fun deviceInfoPayload(): ByteArray = DatasetWriter().apply {
        u16(100)
        u32(0x0000000B)
        u16(100)
        string("Canon extension")
        u16(0)
        u16Array(
            PtpOperationCode.GET_DEVICE_INFO,
            PtpOperationCode.OPEN_SESSION,
            PtpOperationCode.CLOSE_SESSION,
            PtpOperationCode.GET_STORAGE_IDS,
            PtpOperationCode.GET_STORAGE_INFO,
            PtpOperationCode.GET_OBJECT_HANDLES,
            PtpOperationCode.GET_OBJECT_INFO,
            PtpOperationCode.GET_OBJECT,
            PtpOperationCode.INITIATE_CAPTURE,
        )
        u16Array()
        u16Array(0x5001)
        u16Array(PtpObjectFormat.EXIF_JPEG)
        u16Array(PtpObjectFormat.EXIF_JPEG, PtpObjectFormat.DNG)
        string("Canon")
        string("Canon EOS R6 Mark III")
        string("1.0.0")
        string("TEST-SERIAL-0001")
    }.bytes()

    private fun storageInfoPayload(): ByteArray = DatasetWriter().apply {
        u16(3)
        u16(2)
        u16(0)
        u64(64UL * 1024UL * 1024UL * 1024UL)
        u64(32UL * 1024UL * 1024UL * 1024UL)
        u32(1234)
        string("SD")
        string("EOS_CARD")
    }.bytes()

    private fun objectInfoPayload(): ByteArray = DatasetWriter().apply {
        u32(0x00010001)
        u16(PtpObjectFormat.EXIF_JPEG)
        u16(0)
        u32(24_000_000)
        u16(PtpObjectFormat.EXIF_JPEG)
        u32(16_384)
        u32(160)
        u32(120)
        u32(6000)
        u32(4000)
        u32(24)
        u32(0)
        u16(0)
        u32(0)
        u32(42)
        string("IMG_0042.JPG")
        string("20260721T143025")
        string("20260721T143026")
        string("")
    }.bytes()

    private fun u32Array(vararg values: Long): ByteArray = DatasetWriter().apply {
        u32(values.size.toLong())
        values.forEach(::u32)
    }.bytes()

    private fun data(operation: Int, transaction: Long, payload: ByteArray): PtpContainer =
        PtpContainer(PtpContainerType.DATA, operation, transaction, payload)

    private fun ok(transaction: Long): PtpContainer =
        PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, transaction)

    private class DatasetWriter {
        private val output = ByteArrayOutputStream()

        fun u16(value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        fun u8(value: Int) {
            output.write(value and 0xFF)
        }

        fun u32(value: Int) = u32(value.toLong())

        fun u32(value: Long) {
            repeat(4) { index -> output.write(((value ushr (index * 8)) and 0xFF).toInt()) }
        }

        fun u64(value: ULong) {
            repeat(8) { index -> output.write(((value shr (index * 8)) and 0xFFUL).toInt()) }
        }

        fun u16Array(vararg values: Int) {
            u32(values.size)
            values.forEach(::u16)
        }

        fun string(value: String) {
            if (value.isEmpty()) {
                output.write(0)
                return
            }
            val encoded = value.toByteArray(StandardCharsets.UTF_16LE)
            val codeUnits = encoded.size / 2
            require(codeUnits < 255)
            output.write(codeUnits + 1)
            output.write(encoded)
            output.write(0)
            output.write(0)
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
