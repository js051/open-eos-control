package dev.openeos.control.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val PTP_INTERFACE_SUBCLASS = 1
private const val PTP_INTERFACE_PROTOCOL = 1

data class UsbPtpDiagnostics(
    val devices: List<UsbCameraDevice> = emptyList(),
    val scannedAtMillis: Long = 0L,
) {
    val canonDeviceCount: Int
        get() = devices.count { it.isCanon }

    val ptpDeviceCount: Int
        get() = devices.count { it.hasPtpInterface }

    companion object {
        val Empty = UsbPtpDiagnostics()
    }
}

data class UsbCameraDevice(
    val deviceName: String,
    val manufacturerName: String?,
    val productName: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val hasPermission: Boolean,
    val interfaces: List<UsbCameraInterface>,
) {
    val isCanon: Boolean
        get() = vendorId == CANON_USB_VENDOR_ID

    val hasPtpInterface: Boolean
        get() = interfaces.any { it.isPtp }

    val displayName: String
        get() = listOfNotNull(manufacturerName, productName)
            .joinToString(" ")
            .ifBlank { deviceName }

    val diagnosticState: UsbDiagnosticState
        get() = when {
            isCanon && hasPtpInterface && hasPermission -> UsbDiagnosticState.READY
            isCanon && hasPtpInterface -> UsbDiagnosticState.PERMISSION_NEEDED
            isCanon -> UsbDiagnosticState.CANON_NON_PTP
            hasPtpInterface -> UsbDiagnosticState.NON_CANON_PTP
            else -> UsbDiagnosticState.UNKNOWN_USB
        }
}

data class UsbCameraInterface(
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbCameraEndpoint>,
) {
    val isPtp: Boolean
        get() = interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
            interfaceSubclass == PTP_INTERFACE_SUBCLASS &&
            interfaceProtocol == PTP_INTERFACE_PROTOCOL
}

data class UsbCameraEndpoint(
    val address: Int,
    val direction: String,
    val transferType: String,
    val maxPacketSize: Int,
    val interval: Int,
)

enum class UsbDiagnosticState(
    val label: String,
) {
    READY("Ready for PTP session"),
    PERMISSION_NEEDED("USB permission needed"),
    CANON_NON_PTP("Canon USB device"),
    NON_CANON_PTP("PTP device"),
    UNKNOWN_USB("USB device"),
}

class UsbPtpDiagnosticScanner {
    fun scan(context: Context): UsbPtpDiagnostics {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values
            .sortedWith(compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceName })
            .map { device -> device.toUsbCameraDevice(usbManager) }

        return UsbPtpDiagnostics(
            devices = devices,
            scannedAtMillis = System.currentTimeMillis(),
        )
    }

    suspend fun requestPermission(context: Context, deviceName: String): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList[deviceName] ?: return false
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val action = "$USB_PERMISSION_ACTION.${deviceName.hashCode()}"
            val registered = AtomicBoolean(true)
            lateinit var receiver: BroadcastReceiver

            fun unregisterReceiver() {
                if (registered.compareAndSet(true, false)) {
                    runCatching { context.unregisterReceiver(receiver) }
                }
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != action) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        usbManager.hasPermission(device)
                    unregisterReceiver()
                    if (continuation.isActive) continuation.resume(granted)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, IntentFilter(action))
            }

            continuation.invokeOnCancellation { unregisterReceiver() }

            val permissionIntent = PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(action)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_USB_DEVICE_NAME, deviceName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun UsbDevice.toUsbCameraDevice(usbManager: UsbManager): UsbCameraDevice =
        UsbCameraDevice(
            deviceName = deviceName,
            manufacturerName = safeText { manufacturerName },
            productName = safeText { productName },
            vendorId = vendorId,
            productId = productId,
            deviceClass = deviceClass,
            deviceSubclass = deviceSubclass,
            deviceProtocol = deviceProtocol,
            hasPermission = usbManager.hasPermission(this),
            interfaces = List(interfaceCount) { index -> getInterface(index).toUsbCameraInterface() },
        )

    private fun UsbInterface.toUsbCameraInterface(): UsbCameraInterface =
        UsbCameraInterface(
            id = id,
            interfaceClass = interfaceClass,
            interfaceSubclass = interfaceSubclass,
            interfaceProtocol = interfaceProtocol,
            endpoints = List(endpointCount) { index -> getEndpoint(index).toUsbCameraEndpoint() },
        )

    private fun UsbEndpoint.toUsbCameraEndpoint(): UsbCameraEndpoint =
        UsbCameraEndpoint(
            address = address,
            direction = direction.toEndpointDirection(),
            transferType = type.toTransferType(),
            maxPacketSize = maxPacketSize,
            interval = interval,
        )

    private fun safeText(block: () -> String?): String? =
        try {
            block()?.takeIf { it.isNotBlank() }
        } catch (exception: SecurityException) {
            null
        }

    private fun Int.toEndpointDirection(): String =
        when (this) {
            UsbConstants.USB_DIR_IN -> "in"
            UsbConstants.USB_DIR_OUT -> "out"
            else -> "unknown"
        }

    private fun Int.toTransferType(): String =
        when (this) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
            else -> "unknown"
        }

}

const val USB_PERMISSION_ACTION = "dev.openeos.control.USB_PERMISSION"
const val EXTRA_USB_DEVICE_NAME = "dev.openeos.control.extra.USB_DEVICE_NAME"
