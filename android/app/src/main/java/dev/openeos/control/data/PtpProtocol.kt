package dev.openeos.control.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object PtpOperationCode {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_STORAGE_INFO = 0x1005
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val DELETE_OBJECT = 0x100B
    const val INITIATE_CAPTURE = 0x100E
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
}

object PtpResponseCode {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val INVALID_STORAGE_ID = 0x2008
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val DEVICE_BUSY = 0x2019
    const val INVALID_DEVICE_PROP_FORMAT = 0x201B
    const val INVALID_DEVICE_PROP_VALUE = 0x201C
    const val INVALID_PARAMETER = 0x201D
    const val SESSION_ALREADY_OPEN = 0x201E

    fun label(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "GeneralError"
        SESSION_NOT_OPEN -> "SessionNotOpen"
        OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        INVALID_STORAGE_ID -> "InvalidStorageID"
        INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        DEVICE_BUSY -> "DeviceBusy"
        INVALID_DEVICE_PROP_FORMAT -> "InvalidDevicePropFormat"
        INVALID_DEVICE_PROP_VALUE -> "InvalidDevicePropValue"
        INVALID_PARAMETER -> "InvalidParameter"
        SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        else -> "UnknownResponse"
    }
}

object PtpObjectFormat {
    const val ASSOCIATION = 0x3001
    const val EXIF_JPEG = 0x3801
    const val TIFF_EP = 0x3802
    const val PNG = 0x380B
    const val DNG = 0x3811
    const val MP4 = 0xB982
}

enum class PtpContainerType(val value: Int) {
    COMMAND(1),
    DATA(2),
    RESPONSE(3),
    EVENT(4),
    ;

    companion object {
        fun fromValue(value: Int): PtpContainerType = entries.firstOrNull { it.value == value }
            ?: throw PtpProtocolException("Unknown PTP container type 0x${value.toHex(4)}.")
    }
}

data class PtpContainerHeader(
    val length: Long,
    val type: PtpContainerType,
    val code: Int,
    val transactionId: Long,
) {
    val payloadLength: Long
        get() = length - PTP_USB_CONTAINER_HEADER_BYTES
}

data class PtpContainer(
    val type: PtpContainerType,
    val code: Int,
    val transactionId: Long,
    val payload: ByteArray = byteArrayOf(),
) {
    val header: PtpContainerHeader
        get() = PtpContainerHeader(
            length = PTP_USB_CONTAINER_HEADER_BYTES + payload.size.toLong(),
            type = type,
            code = code,
            transactionId = transactionId,
        )
}

data class PtpContainerReceipt(
    val header: PtpContainerHeader,
    val payload: ByteArray = byteArrayOf(),
)

object PtpCodec {
    fun command(operationCode: Int, transactionId: Long, parameters: List<Long> = emptyList()): PtpContainer {
        require(parameters.size <= 5) { "A PTP command supports at most five parameters." }
        val payload = ByteArray(parameters.size * 4)
        parameters.forEachIndexed { index, value -> payload.putU32(index * 4, value) }
        return PtpContainer(
            type = PtpContainerType.COMMAND,
            code = operationCode,
            transactionId = transactionId,
            payload = payload,
        )
    }

    fun encode(container: PtpContainer): ByteArray {
        val length = PTP_USB_CONTAINER_HEADER_BYTES + container.payload.size.toLong()
        require(length <= UINT32_MAX) { "PTP container exceeds the 32-bit USB container length." }
        val bytes = ByteArray(length.toInt())
        bytes.putU32(0, length)
        bytes.putU16(4, container.type.value)
        bytes.putU16(6, container.code)
        bytes.putU32(8, container.transactionId)
        container.payload.copyInto(bytes, destinationOffset = PTP_USB_CONTAINER_HEADER_BYTES)
        return bytes
    }

    fun decode(bytes: ByteArray): PtpContainer {
        val header = decodeHeader(bytes)
        if (header.length != bytes.size.toLong()) {
            throw PtpProtocolException(
                "PTP container declared ${header.length} bytes but ${bytes.size} bytes were supplied."
            )
        }
        return PtpContainer(
            type = header.type,
            code = header.code,
            transactionId = header.transactionId,
            payload = bytes.copyOfRange(PTP_USB_CONTAINER_HEADER_BYTES, bytes.size),
        )
    }

    fun decodeHeader(bytes: ByteArray): PtpContainerHeader {
        if (bytes.size < PTP_USB_CONTAINER_HEADER_BYTES) {
            throw PtpProtocolException(
                "PTP USB container header requires $PTP_USB_CONTAINER_HEADER_BYTES bytes; received ${bytes.size}."
            )
        }
        val length = bytes.u32(0)
        if (length < PTP_USB_CONTAINER_HEADER_BYTES) {
            throw PtpProtocolException("Invalid PTP container length $length.")
        }
        return PtpContainerHeader(
            length = length,
            type = PtpContainerType.fromValue(bytes.u16(4)),
            code = bytes.u16(6),
            transactionId = bytes.u32(8),
        )
    }
}

interface PtpTransport {
    suspend fun send(container: PtpContainer)

    suspend fun receive(maxPayloadBytes: Int = DEFAULT_PTP_METADATA_BYTES): PtpContainer

    suspend fun receiveTo(
        destination: OutputStream,
        expectedOperationCode: Int,
        expectedTransactionId: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): PtpContainerReceipt {
        val container = receive()
        if (container.type == PtpContainerType.DATA) {
            if (container.code != expectedOperationCode || container.transactionId != expectedTransactionId) {
                throw PtpProtocolException("Refusing to stream an unexpected PTP data container.")
            }
            destination.write(container.payload)
            onProgress(container.payload.size.toLong(), container.payload.size.toLong())
        }
        return PtpContainerReceipt(container.header, container.payload)
    }

    fun close()
}

fun interface PtpTransportFactory {
    suspend fun open(connection: CameraConnection.AndroidUsbPtp): PtpTransport
}

data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDescription: String,
    val functionalMode: Int,
    val operations: Set<Int>,
    val events: Set<Int>,
    val deviceProperties: Set<Int>,
    val captureFormats: Set<Int>,
    val imageFormats: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    fun supports(operationCode: Int): Boolean = operationCode in operations
}

data class PtpStorageInfo(
    val storageId: Long,
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacityBytes: ULong,
    val freeSpaceBytes: ULong,
    val freeSpaceImages: Long,
    val description: String,
    val volumeLabel: String,
)

data class PtpObjectInfo(
    val handle: Long,
    val storageId: Long,
    val objectFormat: Int,
    val protectionStatus: Int,
    val sizeBytes: Long,
    val thumbnailFormat: Int,
    val thumbnailSizeBytes: Long,
    val thumbnailWidth: Long,
    val thumbnailHeight: Long,
    val imageWidth: Long,
    val imageHeight: Long,
    val imageBitDepth: Long,
    val parentObject: Long,
    val associationType: Int,
    val associationDescription: Long,
    val sequenceNumber: Long,
    val filename: String,
    val captureDate: String,
    val modificationDate: String,
    val keywords: String,
)

object PtpDatasets {
    fun deviceInfo(bytes: ByteArray): PtpDeviceInfo {
        val reader = PtpDataReader(bytes)
        val standardVersion = reader.u16()
        val vendorExtensionId = reader.u32()
        val vendorExtensionVersion = reader.u16()
        val vendorDescription = reader.ptpString()
        val functionalMode = reader.u16()
        val operations = reader.u16Array().toSet()
        val events = reader.u16Array().toSet()
        val properties = reader.u16Array().toSet()
        val captureFormats = reader.u16Array().toSet()
        val imageFormats = reader.u16Array().toSet()
        return PtpDeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            vendorExtensionVersion = vendorExtensionVersion,
            vendorExtensionDescription = vendorDescription,
            functionalMode = functionalMode,
            operations = operations,
            events = events,
            deviceProperties = properties,
            captureFormats = captureFormats,
            imageFormats = imageFormats,
            manufacturer = reader.optionalPtpString(),
            model = reader.optionalPtpString(),
            deviceVersion = reader.optionalPtpString(),
            serialNumber = reader.optionalPtpString(),
        )
    }

    fun storageIds(bytes: ByteArray): List<Long> = PtpDataReader(bytes).u32Array()

    fun storageInfo(storageId: Long, bytes: ByteArray): PtpStorageInfo {
        val reader = PtpDataReader(bytes)
        return PtpStorageInfo(
            storageId = storageId,
            storageType = reader.u16(),
            filesystemType = reader.u16(),
            accessCapability = reader.u16(),
            maxCapacityBytes = reader.u64(),
            freeSpaceBytes = reader.u64(),
            freeSpaceImages = reader.u32(),
            description = reader.ptpString(),
            volumeLabel = reader.ptpString(),
        )
    }

    fun objectHandles(bytes: ByteArray): List<Long> = PtpDataReader(bytes).u32Array()

    fun objectInfo(handle: Long, bytes: ByteArray): PtpObjectInfo {
        val reader = PtpDataReader(bytes)
        return PtpObjectInfo(
            handle = handle,
            storageId = reader.u32(),
            objectFormat = reader.u16(),
            protectionStatus = reader.u16(),
            sizeBytes = reader.u32(),
            thumbnailFormat = reader.u16(),
            thumbnailSizeBytes = reader.u32(),
            thumbnailWidth = reader.u32(),
            thumbnailHeight = reader.u32(),
            imageWidth = reader.u32(),
            imageHeight = reader.u32(),
            imageBitDepth = reader.u32(),
            parentObject = reader.u32(),
            associationType = reader.u16(),
            associationDescription = reader.u32(),
            sequenceNumber = reader.u32(),
            filename = reader.ptpString(),
            captureDate = reader.optionalPtpString(),
            modificationDate = reader.optionalPtpString(),
            keywords = reader.optionalPtpString(),
        )
    }
}

class PtpSession(
    private val transport: PtpTransport,
    private val sessionId: Long = 1L,
) {
    private val mutex = Mutex()
    private var nextTransactionId = 1L
    private var sessionOpen = false
    private var cachedDeviceInfo: PtpDeviceInfo? = null

    suspend fun initialize(): PtpDeviceInfo = mutex.withLock {
        cachedDeviceInfo?.let { return@withLock it }
        val infoPayload = executeLocked(
            operationCode = PtpOperationCode.GET_DEVICE_INFO,
            transactionId = 0L,
            expectData = true,
        ) ?: throw PtpProtocolException("GetDeviceInfo completed without a data container.")
        val info = PtpDatasets.deviceInfo(infoPayload)
        executeLocked(
            operationCode = PtpOperationCode.OPEN_SESSION,
            parameters = listOf(sessionId),
            transactionId = 0L,
            expectData = false,
        )
        sessionOpen = true
        cachedDeviceInfo = info
        info
    }

    suspend fun storageIds(): List<Long> = transaction(PtpOperationCode.GET_STORAGE_IDS) { payload ->
        PtpDatasets.storageIds(payload)
    }

    suspend fun storageInfo(storageId: Long): PtpStorageInfo =
        transaction(PtpOperationCode.GET_STORAGE_INFO, listOf(storageId)) { payload ->
            PtpDatasets.storageInfo(storageId, payload)
        }

    suspend fun objectHandles(
        storageId: Long,
        objectFormat: Long = 0L,
        associationHandle: Long = 0L,
    ): List<Long> = transaction(
        operationCode = PtpOperationCode.GET_OBJECT_HANDLES,
        parameters = listOf(storageId, objectFormat, associationHandle),
    ) { payload -> PtpDatasets.objectHandles(payload) }

    suspend fun objectInfo(handle: Long): PtpObjectInfo =
        transaction(PtpOperationCode.GET_OBJECT_INFO, listOf(handle)) { payload ->
            PtpDatasets.objectInfo(handle, payload)
        }

    suspend fun devicePropertyDescriptor(propertyCode: Int): PtpDevicePropertyDescriptor =
        transaction(PtpOperationCode.GET_DEVICE_PROP_DESC, listOf(propertyCode.toLong())) { payload ->
            PtpPropertyCodec.decodeDescriptor(payload).also { descriptor ->
                if (descriptor.code != propertyCode) {
                    throw PtpProtocolException(
                        "Requested property 0x${propertyCode.toString(16).uppercase()}, " +
                            "received descriptor 0x${descriptor.code.toString(16).uppercase()}."
                    )
                }
            }
        }

    suspend fun devicePropertyValue(
        propertyCode: Int,
        dataType: PtpDataType,
    ): PtpPropertyValue = transaction(
        PtpOperationCode.GET_DEVICE_PROP_VALUE,
        listOf(propertyCode.toLong()),
    ) { payload -> PtpPropertyCodec.decodeValue(dataType, payload) }

    suspend fun setDevicePropertyValue(
        propertyCode: Int,
        dataType: PtpDataType,
        value: PtpPropertyValue,
    ) {
        mutex.withLock {
            requireOpen()
            val transactionId = takeTransactionId()
            val payload = PtpPropertyCodec.encodeValue(dataType, value)
            transport.send(
                PtpCodec.command(
                    operationCode = PtpOperationCode.SET_DEVICE_PROP_VALUE,
                    transactionId = transactionId,
                    parameters = listOf(propertyCode.toLong()),
                )
            )
            transport.send(
                PtpContainer(
                    type = PtpContainerType.DATA,
                    code = PtpOperationCode.SET_DEVICE_PROP_VALUE,
                    transactionId = transactionId,
                    payload = payload,
                )
            )
            receiveResponseLocked(PtpOperationCode.SET_DEVICE_PROP_VALUE, transactionId)
        }
    }

    suspend fun initiateCapture(storageId: Long = 0L, objectFormat: Long = 0L) {
        mutex.withLock {
            requireOpen()
            executeLocked(
                operationCode = PtpOperationCode.INITIATE_CAPTURE,
                parameters = listOf(storageId, objectFormat),
                transactionId = takeTransactionId(),
                expectData = false,
            )
        }
    }

    suspend fun downloadObject(
        handle: Long,
        destination: OutputStream,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Long = mutex.withLock {
        requireOpen()
        val transactionId = takeTransactionId()
        transport.send(PtpCodec.command(PtpOperationCode.GET_OBJECT, transactionId, listOf(handle)))

        var bytesTransferred = 0L
        var receivedData = false
        while (true) {
            val receipt = if (!receivedData) {
                transport.receiveTo(
                    destination = destination,
                    expectedOperationCode = PtpOperationCode.GET_OBJECT,
                    expectedTransactionId = transactionId,
                ) { transferred, total ->
                    bytesTransferred = transferred
                    onProgress(transferred, total)
                }
            } else {
                val container = transport.receive()
                PtpContainerReceipt(container.header, container.payload)
            }
            val header = receipt.header
            if (header.type == PtpContainerType.EVENT) continue
            validateTransaction(header, transactionId)
            when (header.type) {
                PtpContainerType.DATA -> {
                    if (header.code != PtpOperationCode.GET_OBJECT) {
                        throw PtpProtocolException(
                            "GetObject received data for operation 0x${header.code.toHex(4)}."
                        )
                    }
                    if (receivedData) throw PtpProtocolException("GetObject returned multiple data containers.")
                    receivedData = true
                    bytesTransferred = header.payloadLength
                }

                PtpContainerType.RESPONSE -> {
                    checkResponse(header.code, PtpOperationCode.GET_OBJECT)
                    if (!receivedData) throw PtpProtocolException("GetObject completed without object data.")
                    return@withLock bytesTransferred
                }

                PtpContainerType.COMMAND -> throw PtpProtocolException("Camera returned a command container to GetObject.")
                PtpContainerType.EVENT -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE")
        0L
    }

    suspend fun shutdown() {
        mutex.withLock {
            try {
                if (sessionOpen) {
                    runCatching {
                        executeLocked(
                            operationCode = PtpOperationCode.CLOSE_SESSION,
                            transactionId = takeTransactionId(),
                            expectData = false,
                        )
                    }
                }
            } finally {
                sessionOpen = false
                cachedDeviceInfo = null
                transport.close()
            }
        }
    }

    private suspend fun <T> transaction(
        operationCode: Int,
        parameters: List<Long> = emptyList(),
        parser: (ByteArray) -> T,
    ): T = mutex.withLock {
        requireOpen()
        val payload = executeLocked(
            operationCode = operationCode,
            parameters = parameters,
            transactionId = takeTransactionId(),
            expectData = true,
        ) ?: throw PtpProtocolException(
            "Operation 0x${operationCode.toHex(4)} completed without a data container."
        )
        parser(payload)
    }

    private suspend fun executeLocked(
        operationCode: Int,
        parameters: List<Long> = emptyList(),
        transactionId: Long,
        expectData: Boolean,
    ): ByteArray? {
        transport.send(PtpCodec.command(operationCode, transactionId, parameters))
        var data: ByteArray? = null
        while (true) {
            val container = transport.receive()
            if (container.type == PtpContainerType.EVENT) continue
            validateTransaction(container.header, transactionId)
            when (container.type) {
                PtpContainerType.DATA -> {
                    if (container.code != operationCode) {
                        throw PtpProtocolException(
                            "Operation 0x${operationCode.toHex(4)} received data for 0x${container.code.toHex(4)}."
                        )
                    }
                    if (data != null) throw PtpProtocolException("PTP operation returned multiple data containers.")
                    data = container.payload
                }

                PtpContainerType.RESPONSE -> {
                    checkResponse(container.code, operationCode)
                    if (expectData && data == null) {
                        throw PtpProtocolException(
                            "Operation 0x${operationCode.toHex(4)} completed without a data container."
                        )
                    }
                    return data
                }

                PtpContainerType.COMMAND -> throw PtpProtocolException("Camera returned an unexpected command container.")
                PtpContainerType.EVENT -> Unit
            }
        }
    }

    private suspend fun receiveResponseLocked(operationCode: Int, transactionId: Long) {
        while (true) {
            val container = transport.receive()
            if (container.type == PtpContainerType.EVENT) continue
            validateTransaction(container.header, transactionId)
            when (container.type) {
                PtpContainerType.RESPONSE -> {
                    checkResponse(container.code, operationCode)
                    return
                }

                PtpContainerType.DATA -> throw PtpProtocolException(
                    "Operation 0x${operationCode.toHex(4)} returned an unexpected data container."
                )

                PtpContainerType.COMMAND -> throw PtpProtocolException("Camera returned an unexpected command container.")
                PtpContainerType.EVENT -> Unit
            }
        }
    }

    private fun requireOpen() {
        if (!sessionOpen) throw PtpProtocolException("PTP session is not open.")
    }

    private fun takeTransactionId(): Long {
        val current = nextTransactionId
        nextTransactionId = (nextTransactionId + 1L) and UINT32_MAX
        if (nextTransactionId == 0L) nextTransactionId = 1L
        return current
    }

    private fun validateTransaction(header: PtpContainerHeader, expected: Long) {
        if (header.transactionId != expected) {
            throw PtpProtocolException(
                "PTP transaction mismatch: expected 0x${expected.toHex(8)}, " +
                    "received 0x${header.transactionId.toHex(8)}."
            )
        }
    }

    private fun checkResponse(responseCode: Int, operationCode: Int) {
        if (responseCode != PtpResponseCode.OK) {
            throw PtpResponseException(operationCode, responseCode)
        }
    }
}

class PtpResponseException(
    val operationCode: Int,
    val responseCode: Int,
) : PtpProtocolException(
    "PTP operation 0x${operationCode.toHex(4)} failed with " +
        "${PtpResponseCode.label(responseCode)} (0x${responseCode.toHex(4)})."
)

open class PtpProtocolException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private class PtpDataReader(private val bytes: ByteArray) {
    private var offset = 0

    fun u16(): Int {
        requireBytes(2)
        return bytes.u16(offset).also { offset += 2 }
    }

    fun u32(): Long {
        requireBytes(4)
        return bytes.u32(offset).also { offset += 4 }
    }

    fun u64(): ULong {
        requireBytes(8)
        var value = 0UL
        repeat(8) { index ->
            value = value or ((bytes[offset + index].toUByte().toULong()) shl (index * 8))
        }
        offset += 8
        return value
    }

    fun u16Array(): List<Int> {
        val count = arrayCount("16-bit")
        requireArrayBytes(count, 2)
        return List(count) { u16() }
    }

    fun u32Array(): List<Long> {
        val count = arrayCount("32-bit")
        requireArrayBytes(count, 4)
        return List(count) { u32() }
    }

    fun ptpString(): String {
        requireBytes(1)
        val codeUnitCount = bytes[offset++].toUByte().toInt()
        if (codeUnitCount == 0) return ""
        val byteCount = codeUnitCount * 2
        requireBytes(byteCount)
        val textByteCount = (byteCount - 2).coerceAtLeast(0)
        val text = String(bytes, offset, textByteCount, StandardCharsets.UTF_16LE)
        offset += byteCount
        return text.trimEnd('\u0000')
    }

    fun optionalPtpString(): String = if (offset < bytes.size) ptpString() else ""

    private fun arrayCount(label: String): Int {
        val count = u32()
        if (count > Int.MAX_VALUE.toLong()) {
            throw PtpProtocolException("PTP $label array count $count exceeds Android limits.")
        }
        return count.toInt()
    }

    private fun requireArrayBytes(count: Int, elementBytes: Int) {
        val required = count.toLong() * elementBytes
        if (required > Int.MAX_VALUE || required > bytes.size - offset) {
            throw PtpProtocolException(
                "PTP dataset declares $count elements but only ${bytes.size - offset} bytes remain."
            )
        }
    }

    private fun requireBytes(count: Int) {
        if (count < 0 || offset > bytes.size - count) {
            throw PtpProtocolException(
                "PTP dataset ended at byte $offset; $count more bytes were required (${bytes.size} total)."
            )
        }
    }
}

private fun ByteArray.u16(offset: Int): Int =
    this[offset].toUByte().toInt() or (this[offset + 1].toUByte().toInt() shl 8)

private fun ByteArray.u32(offset: Int): Long =
    this[offset].toUByte().toLong() or
        (this[offset + 1].toUByte().toLong() shl 8) or
        (this[offset + 2].toUByte().toLong() shl 16) or
        (this[offset + 3].toUByte().toLong() shl 24)

private fun ByteArray.putU16(offset: Int, value: Int) {
    require(value in 0..0xFFFF) { "Value $value does not fit in an unsigned 16-bit field." }
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

private fun ByteArray.putU32(offset: Int, value: Long) {
    require(value in 0..UINT32_MAX) { "Value $value does not fit in an unsigned 32-bit field." }
    repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
}

private fun Number.toHex(width: Int): String = toLong().toString(16).uppercase().padStart(width, '0')

const val PTP_USB_CONTAINER_HEADER_BYTES = 12
const val DEFAULT_PTP_METADATA_BYTES = 16 * 1024 * 1024
const val UINT32_MAX = 0xFFFF_FFFFL
