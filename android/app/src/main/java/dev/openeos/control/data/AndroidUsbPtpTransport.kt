package dev.openeos.control.data

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class AndroidUsbPtpTransportFactory(context: Context) : PtpTransportFactory {
    private val applicationContext = context.applicationContext

    override suspend fun open(connection: CameraConnection.AndroidUsbPtp): PtpTransport =
        withContext(Dispatchers.IO) {
            AndroidUsbPtpTransport.open(applicationContext, connection)
        }
}

class AndroidUsbPtpTransport private constructor(
    private val deviceConnection: UsbDeviceConnection,
    private val ptpInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : PtpTransport {
    @Volatile
    private var closed = false
    private val bufferedInput = PtpBufferedInput(USB_TRANSFER_CHUNK_BYTES) { buffer ->
        currentCoroutineContext().ensureActive()
        val count = deviceConnection.bulkTransfer(
            bulkIn,
            buffer,
            0,
            buffer.size,
            USB_TRANSFER_TIMEOUT_MILLIS,
        )
        if (count <= 0) throw usbTransferFailure("read", count)
        count
    }

    override suspend fun send(container: PtpContainer) = withContext(Dispatchers.IO) {
        checkOpen()
        writeAll(PtpCodec.encode(container))
    }

    override suspend fun receive(maxPayloadBytes: Int): PtpContainer = withContext(Dispatchers.IO) {
        checkOpen()
        val header = readHeader()
        if (header.payloadLength > maxPayloadBytes.toLong()) {
            throw PtpProtocolException(
                "PTP ${header.type.name.lowercase()} payload is ${header.payloadLength} bytes; " +
                    "the metadata limit is $maxPayloadBytes bytes."
            )
        }
        val payload = readExact(header.payloadLength.toInt())
        PtpContainer(
            type = header.type,
            code = header.code,
            transactionId = header.transactionId,
            payload = payload,
        )
    }

    override suspend fun receiveTo(
        destination: OutputStream,
        expectedOperationCode: Int,
        expectedTransactionId: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): PtpContainerReceipt = withContext(Dispatchers.IO) {
        checkOpen()
        val header = readHeader()
        if (header.type != PtpContainerType.DATA) {
            if (header.payloadLength > DEFAULT_PTP_METADATA_BYTES.toLong()) {
                throw PtpProtocolException(
                    "Unexpected ${header.type.name.lowercase()} payload is ${header.payloadLength} bytes."
                )
            }
            return@withContext PtpContainerReceipt(
                header = header,
                payload = readExact(header.payloadLength.toInt()),
            )
        }
        if (header.code != expectedOperationCode || header.transactionId != expectedTransactionId) {
            throw PtpProtocolException(
                "Refusing to stream PTP data for operation 0x${header.code.toString(16).uppercase()} " +
                    "and transaction 0x${header.transactionId.toString(16).uppercase()}."
            )
        }

        val totalBytes = header.payloadLength
        var transferred = 0L
        val buffer = ByteArray(USB_TRANSFER_CHUNK_BYTES)
        onProgress(0L, totalBytes)
        while (transferred < totalBytes) {
            currentCoroutineContext().ensureActive()
            val requested = minOf(buffer.size.toLong(), totalBytes - transferred).toInt()
            val count = bufferedInput.readInto(buffer, 0, requested)
            destination.write(buffer, 0, count)
            transferred += count
            onProgress(transferred, totalBytes)
        }
        PtpContainerReceipt(header)
    }

    override suspend fun sendFrom(
        source: InputStream,
        operationCode: Int,
        transactionId: Long,
        payloadLength: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        checkOpen()
        writeAll(
            PtpCodec.encodeHeader(
                type = PtpContainerType.DATA,
                code = operationCode,
                transactionId = transactionId,
                payloadLength = payloadLength,
            )
        )
        streamExactPtpPayload(
            source = source,
            payloadLength = payloadLength,
            onChunk = ::writeAll,
            onProgress = onProgress,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { deviceConnection.releaseInterface(ptpInterface) }
        deviceConnection.close()
    }

    private suspend fun readHeader(): PtpContainerHeader =
        PtpCodec.decodeHeader(bufferedInput.readExact(PTP_USB_CONTAINER_HEADER_BYTES))

    private suspend fun readExact(byteCount: Int): ByteArray = bufferedInput.readExact(byteCount)

    private suspend fun writeAll(bytes: ByteArray) {
        writeAll(bytes, bytes.size)
    }

    private suspend fun writeAll(bytes: ByteArray, byteCount: Int) {
        require(byteCount in 0..bytes.size) { "Invalid USB write length $byteCount for ${bytes.size}-byte buffer." }
        var offset = 0
        while (offset < byteCount) {
            currentCoroutineContext().ensureActive()
            val requested = minOf(USB_TRANSFER_CHUNK_BYTES, byteCount - offset)
            val count = deviceConnection.bulkTransfer(
                bulkOut,
                bytes,
                offset,
                requested,
                USB_TRANSFER_TIMEOUT_MILLIS,
            )
            if (count <= 0) throw usbTransferFailure("write", count)
            offset += count
        }
    }

    private fun checkOpen() {
        if (closed) throw PtpProtocolException("Android USB PTP transport is closed.")
    }

    private fun usbTransferFailure(action: String, result: Int): PtpProtocolException =
        PtpProtocolException(
            "Android USB bulk $action failed on endpoint 0x${
                (if (action == "read") bulkIn.address else bulkOut.address).toString(16).uppercase()
            } (result $result)."
        )

    companion object {
        fun open(context: Context, requested: CameraConnection.AndroidUsbPtp): AndroidUsbPtpTransport {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = manager.findRequestedDevice(requested)
            if (!manager.hasPermission(device)) {
                throw PtpProtocolException("USB permission has not been granted for ${device.deviceName}.")
            }
            val ptpInterface = device.findPtpInterface()
                ?: throw PtpProtocolException(
                    "USB device ${device.deviceName} does not expose a Still Image PTP interface (06/01/01)."
                )
            val bulkIn = ptpInterface.findEndpoint(UsbConstants.USB_DIR_IN)
                ?: throw PtpProtocolException("PTP interface ${ptpInterface.id} has no bulk IN endpoint.")
            val bulkOut = ptpInterface.findEndpoint(UsbConstants.USB_DIR_OUT)
                ?: throw PtpProtocolException("PTP interface ${ptpInterface.id} has no bulk OUT endpoint.")
            val connection = manager.openDevice(device)
                ?: throw PtpProtocolException("Android could not open USB device ${device.deviceName}.")
            if (!connection.claimInterface(ptpInterface, true)) {
                connection.close()
                throw PtpProtocolException("Android could not claim PTP interface ${ptpInterface.id}.")
            }
            return AndroidUsbPtpTransport(connection, ptpInterface, bulkIn, bulkOut)
        }

        private fun UsbManager.findRequestedDevice(requested: CameraConnection.AndroidUsbPtp): UsbDevice {
            val devices = deviceList.values.filter { device ->
                device.vendorId == requested.vendorId &&
                    (requested.productId == null || device.productId == requested.productId)
            }
            requested.deviceName?.let { name ->
                return devices.firstOrNull { it.deviceName == name }
                    ?: throw PtpProtocolException("Selected USB camera $name is no longer attached.")
            }
            return when (devices.size) {
                0 -> throw PtpProtocolException(
                    "No USB camera matches VID %04X%s.".format(
                        requested.vendorId,
                        requested.productId?.let { " / PID %04X".format(it) } ?: "",
                    )
                )

                1 -> devices.single()
                else -> throw PtpProtocolException("Multiple matching USB cameras are attached; select one device.")
            }
        }

        private fun UsbDevice.findPtpInterface(): UsbInterface? =
            (0 until interfaceCount)
                .map(::getInterface)
                .firstOrNull { usbInterface ->
                    usbInterface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                        usbInterface.interfaceSubclass == USB_PTP_INTERFACE_SUBCLASS &&
                        usbInterface.interfaceProtocol == USB_PTP_INTERFACE_PROTOCOL
                }

        private fun UsbInterface.findEndpoint(direction: Int): UsbEndpoint? =
            (0 until endpointCount)
                .map(::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction
                }
    }
}

internal suspend fun streamExactPtpPayload(
    source: InputStream,
    payloadLength: Long,
    bufferBytes: Int = USB_TRANSFER_CHUNK_BYTES,
    onChunk: suspend (bytes: ByteArray, byteCount: Int) -> Unit,
    onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
) {
    require(payloadLength >= 0L) { "PTP payload length cannot be negative." }
    require(bufferBytes > 0) { "PTP upload buffer must be positive." }
    val buffer = ByteArray(bufferBytes)
    var transferred = 0L
    onProgress(0L, payloadLength)
    while (transferred < payloadLength) {
        currentCoroutineContext().ensureActive()
        val requested = minOf(buffer.size.toLong(), payloadLength - transferred).toInt()
        val count = source.read(buffer, 0, requested)
        if (count < 0) {
            throw PtpProtocolException("PTP upload ended after $transferred of $payloadLength bytes.")
        }
        if (count == 0) continue
        onChunk(buffer, count)
        transferred += count
        onProgress(transferred, payloadLength)
    }
    currentCoroutineContext().ensureActive()
    if (source.read() != -1) {
        throw PtpProtocolException("PTP upload source exceeds its declared $payloadLength bytes.")
    }
}

private const val USB_PTP_INTERFACE_SUBCLASS = 1
private const val USB_PTP_INTERFACE_PROTOCOL = 1
private const val USB_TRANSFER_CHUNK_BYTES = 16 * 1024
private const val USB_TRANSFER_TIMEOUT_MILLIS = 10_000
