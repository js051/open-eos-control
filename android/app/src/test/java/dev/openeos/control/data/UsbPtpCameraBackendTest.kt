package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class UsbPtpCameraBackendTest {
    @Test
    fun backendExposesOnlyAdvertisedStandardOperationsAndRunsMediaPath() = runTest {
        val transport = ScriptedTransport(advertiseCapture = true)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3", CANON_USB_VENDOR_ID, 0x1234),
            transportFactory = PtpTransportFactory { transport },
        )

        backend.initialize()
        val info = backend.info()
        val status = backend.status()
        val capabilities = backend.capabilities()
        val media = backend.listMedia()
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()
        val download = backend.downloadMedia(media.single(), output, progress::add)
        backend.captureStill()
        backend.close()

        assertEquals("Canon EOS R6 Mark III", info.model)
        assertEquals("ptp-usb/1.00", info.api)
        assertEquals(true, status.mediaAvailable)
        assertTrue(status.rawStorageJson.contains("EOS_CARD"))
        assertTrue(capabilities.matrix.supports(CameraFeature.USB_DIAGNOSTICS))
        assertTrue(capabilities.matrix.supports(CameraFeature.STORAGE_STATUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DOWNLOAD))
        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertFalse(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertEquals("IMG_0042.JPG", media.single().name)
        assertEquals("2026-07-21 14:30:25", media.single().captureTime)
        assertArrayEquals(OBJECT_BYTES, output.toByteArray())
        assertEquals(OBJECT_BYTES.size.toLong(), download.bytesTransferred)
        assertEquals(OBJECT_BYTES.size.toLong(), progress.last().bytesTransferred)
        assertTrue(PtpOperationCode.INITIATE_CAPTURE in transport.sentOperations)
        assertArrayEquals(
            ByteArray(8),
            transport.sentContainers.single { it.code == PtpOperationCode.INITIATE_CAPTURE }.payload,
        )
        assertTrue(transport.closed)
    }

    @Test
    fun captureRemainsPlannedWhenDeviceInfoDoesNotAdvertiseInitiateCapture() = runTest {
        val transport = ScriptedTransport(advertiseCapture = false)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )

        backend.initialize()
        val capabilities = backend.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.STILL_CAPTURE))
        backend.close()
    }

    private class ScriptedTransport(
        private val advertiseCapture: Boolean,
    ) : PtpTransport {
        private val incoming = ArrayDeque<PtpContainer>()
        val sentOperations = mutableListOf<Int>()
        val sentContainers = mutableListOf<PtpContainer>()
        var closed = false

        override suspend fun send(container: PtpContainer) {
            sentOperations += container.code
            sentContainers += container
            val transaction = container.transactionId
            when (container.code) {
                PtpOperationCode.GET_DEVICE_INFO -> {
                    incoming += data(container.code, transaction, deviceInfoPayload(advertiseCapture))
                    incoming += ok(transaction)
                }

                PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.CLOSE_SESSION,
                PtpOperationCode.INITIATE_CAPTURE,
                -> incoming += ok(transaction)

                PtpOperationCode.GET_STORAGE_IDS -> {
                    incoming += data(container.code, transaction, Writer().apply {
                        u32(1)
                        u32(STORAGE_ID)
                    }.bytes())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_STORAGE_INFO -> {
                    incoming += data(container.code, transaction, storageInfoPayload())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_OBJECT_HANDLES -> {
                    incoming += data(container.code, transaction, Writer().apply {
                        u32(1)
                        u32(OBJECT_HANDLE)
                    }.bytes())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_OBJECT_INFO -> {
                    incoming += data(container.code, transaction, objectInfoPayload())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_OBJECT -> {
                    incoming += data(container.code, transaction, OBJECT_BYTES)
                    incoming += ok(transaction)
                }

                else -> error("Unexpected operation 0x${container.code.toString(16)}")
            }
        }

        override suspend fun receive(maxPayloadBytes: Int): PtpContainer =
            incoming.removeFirstOrNull() ?: error("No scripted response is queued.")

        override fun close() {
            closed = true
        }

        private fun data(operation: Int, transaction: Long, payload: ByteArray) =
            PtpContainer(PtpContainerType.DATA, operation, transaction, payload)

        private fun ok(transaction: Long) =
            PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, transaction)
    }

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun u16(value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        fun u32(value: Int) = u32(value.toLong())

        fun u32(value: Long) {
            repeat(4) { index -> output.write(((value ushr (index * 8)) and 0xFF).toInt()) }
        }

        fun u64(value: ULong) {
            repeat(8) { index -> output.write(((value shr (index * 8)) and 0xFFUL).toInt()) }
        }

        fun u16Array(values: List<Int>) {
            u32(values.size)
            values.forEach(::u16)
        }

        fun string(value: String) {
            if (value.isEmpty()) {
                output.write(0)
                return
            }
            val encoded = value.toByteArray(StandardCharsets.UTF_16LE)
            output.write(encoded.size / 2 + 1)
            output.write(encoded)
            output.write(0)
            output.write(0)
        }

        fun bytes() = output.toByteArray()
    }

    companion object {
        private const val STORAGE_ID = 0x00010001L
        private const val OBJECT_HANDLE = 0x42L
        private val OBJECT_BYTES = byteArrayOf(1, 3, 3, 7, 9)

        private fun deviceInfoPayload(advertiseCapture: Boolean): ByteArray = Writer().apply {
            u16(100)
            u32(0x0000000B)
            u16(100)
            string("Canon extension")
            u16(0)
            u16Array(
                buildList {
                    add(PtpOperationCode.GET_DEVICE_INFO)
                    add(PtpOperationCode.OPEN_SESSION)
                    add(PtpOperationCode.CLOSE_SESSION)
                    add(PtpOperationCode.GET_STORAGE_IDS)
                    add(PtpOperationCode.GET_STORAGE_INFO)
                    add(PtpOperationCode.GET_OBJECT_HANDLES)
                    add(PtpOperationCode.GET_OBJECT_INFO)
                    add(PtpOperationCode.GET_OBJECT)
                    if (advertiseCapture) add(PtpOperationCode.INITIATE_CAPTURE)
                }
            )
            u16Array(emptyList())
            u16Array(emptyList())
            u16Array(listOf(PtpObjectFormat.EXIF_JPEG))
            u16Array(listOf(PtpObjectFormat.EXIF_JPEG))
            string("Canon")
            string("EOS R6 Mark III")
            string("1.0.0")
            string("TEST-SERIAL-0001")
        }.bytes()

        private fun storageInfoPayload(): ByteArray = Writer().apply {
            u16(3)
            u16(2)
            u16(0)
            u64(64UL * 1024UL * 1024UL * 1024UL)
            u64(32UL * 1024UL * 1024UL * 1024UL)
            u32(1234)
            string("SD")
            string("EOS_CARD")
        }.bytes()

        private fun objectInfoPayload(): ByteArray = Writer().apply {
            u32(STORAGE_ID)
            u16(PtpObjectFormat.EXIF_JPEG)
            u16(0)
            u32(OBJECT_BYTES.size)
            u16(PtpObjectFormat.EXIF_JPEG)
            u32(0)
            u32(0)
            u32(0)
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
    }
}
