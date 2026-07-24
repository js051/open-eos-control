package dev.openeos.control.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Inet4Address
import java.net.URI

data class CameraNetworkDiagnostics(
    val routing: CameraNetworkRouting = CameraNetworkRouting.SYSTEM_DEFAULT,
    val targetHost: String? = null,
    val networkHandle: Long? = null,
    val interfaceName: String? = null,
    val wifiAvailable: Boolean = false,
    val cellularAvailable: Boolean = false,
) {
    companion object {
        val Empty = CameraNetworkDiagnostics()
    }
}

enum class CameraNetworkRouting {
    SYSTEM_DEFAULT,
    WIFI_BOUND,
}

data class CameraHttpTransport(
    val client: OkHttpClient,
    val diagnostics: CameraNetworkDiagnostics,
    val rtpDestinationAddress: String? = null,
    val rtpSessionFactory: CcapiRtpSessionFactory? = null,
)

fun interface CameraHttpTransportFactory {
    fun create(baseUrl: String): CameraHttpTransport
}

class DefaultCameraHttpTransportFactory : CameraHttpTransportFactory {
    override fun create(baseUrl: String): CameraHttpTransport = CameraHttpTransport(
        client = OkHttpClient(),
        diagnostics = CameraNetworkDiagnostics(
            targetHost = runCatching { URI.create(baseUrl).host }.getOrNull(),
        ),
    )
}

class AndroidCameraHttpTransportFactory(
    context: Context,
) : CameraHttpTransportFactory {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun create(baseUrl: String): CameraHttpTransport {
        val uri = runCatching { URI.create(baseUrl) }
            .getOrElse { throw IllegalArgumentException("Invalid camera URL: $baseUrl", it) }
        val host = uri.host ?: throw IllegalArgumentException("Camera URL must include a host: $baseUrl")

        if (host.isDevelopmentHost(uri.port)) {
            return CameraHttpTransport(
                client = OkHttpClient(),
                diagnostics = currentDiagnostics(host),
            )
        }

        val networks = connectivityManager.allNetworks.toList()
        val wifiNetworks = networks.filter { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val targetAddress = host.toNumericAddressOrNull()
        val selected = wifiNetworks.firstOrNull { network ->
            targetAddress == null || connectivityManager.getLinkProperties(network)
                ?.routes
                ?.any { route -> route.destination.contains(targetAddress) }
                ?: false
        } ?: throw IllegalStateException(
            "No Wi-Fi route can reach camera host $host. " +
                "Connect the phone to the camera's Wi-Fi and keep mobile data enabled."
        )
        val linkProperties = connectivityManager.getLinkProperties(selected)
        val rtpDestinationAddress = linkProperties?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
        val diagnostics = CameraNetworkDiagnostics(
            routing = CameraNetworkRouting.WIFI_BOUND,
            targetHost = host,
            networkHandle = selected.networkHandle,
            interfaceName = linkProperties?.interfaceName,
            wifiAvailable = true,
            cellularAvailable = networks.any { it.isCellular() },
        )
        return CameraHttpTransport(
            client = OkHttpClient.Builder()
                .socketFactory(selected.socketFactory)
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            selected.getAllByName(hostname).toList()
                    }
                )
                .build(),
            diagnostics = diagnostics,
            rtpDestinationAddress = rtpDestinationAddress,
            rtpSessionFactory = AndroidCcapiRtpSessionFactory(selected),
        )
    }

    private fun currentDiagnostics(host: String): CameraNetworkDiagnostics {
        val networks = connectivityManager.allNetworks.toList()
        return CameraNetworkDiagnostics(
            targetHost = host,
            wifiAvailable = networks.any { it.isWifi() },
            cellularAvailable = networks.any { it.isCellular() },
        )
    }

    private fun Network.isWifi(): Boolean =
        connectivityManager.getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun Network.isCellular(): Boolean =
        connectivityManager.getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
}

private fun String.isDevelopmentHost(port: Int): Boolean =
    this == "localhost" ||
        this == "127.0.0.1" ||
        this == "::1" ||
        this == "10.0.2.2" ||
        port == 18080

private fun String.toNumericAddressOrNull(): InetAddress? {
    val isIpv4 = matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    val isIpv6 = contains(':')
    if (!isIpv4 && !isIpv6) return null
    return runCatching { InetAddress.getByName(this) }.getOrNull()
}
