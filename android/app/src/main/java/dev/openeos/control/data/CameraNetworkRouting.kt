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
    val cameraNetworkAvailable: Boolean = false,
    val wifiAvailable: Boolean = false,
    val cellularAvailable: Boolean = false,
    val cellularValidated: Boolean = false,
    val systemDefaultTransport: SystemNetworkTransport = SystemNetworkTransport.NONE,
    val systemDefaultValidated: Boolean = false,
    val systemDefaultNetworkHandle: Long? = null,
    val systemDefaultInterfaceName: String? = null,
) {
    val wifiCellularCoexistence: Boolean
        get() = routing == CameraNetworkRouting.WIFI_BOUND &&
            cameraNetworkAvailable &&
            systemDefaultTransport == SystemNetworkTransport.CELLULAR &&
            systemDefaultValidated

    companion object {
        val Empty = CameraNetworkDiagnostics()
    }
}

enum class CameraNetworkRouting {
    SYSTEM_DEFAULT,
    WIFI_BOUND,
}

enum class SystemNetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER,
}

data class CameraHttpTransport(
    val client: OkHttpClient,
    val diagnostics: CameraNetworkDiagnostics,
    val rtpDestinationAddress: String? = null,
    val rtpSessionFactory: CcapiRtpSessionFactory? = null,
    val diagnosticsProvider: () -> CameraNetworkDiagnostics = { diagnostics },
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
            val diagnostics = currentDiagnostics(host)
            return CameraHttpTransport(
                client = OkHttpClient(),
                diagnostics = diagnostics,
                diagnosticsProvider = { currentDiagnostics(host) },
            )
        }

        val networks = availableNetworksSnapshot()
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
        val diagnostics = currentDiagnostics(host, selected)
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
            diagnosticsProvider = { currentDiagnostics(host, selected) },
        )
    }

    private fun currentDiagnostics(host: String, cameraNetwork: Network? = null): CameraNetworkDiagnostics {
        val networks = availableNetworksSnapshot()
        val cameraNetworkAvailable = cameraNetwork != null && networks.any { network ->
            network.networkHandle == cameraNetwork.networkHandle && network.isWifi()
        }
        val defaultNetwork = connectivityManager.activeNetwork
        val defaultCapabilities = defaultNetwork?.let(connectivityManager::getNetworkCapabilities)
        val defaultLinkProperties = defaultNetwork?.let(connectivityManager::getLinkProperties)
        return CameraNetworkDiagnostics(
            routing = if (cameraNetwork == null) {
                CameraNetworkRouting.SYSTEM_DEFAULT
            } else {
                CameraNetworkRouting.WIFI_BOUND
            },
            targetHost = host,
            networkHandle = cameraNetwork?.networkHandle,
            interfaceName = cameraNetwork?.let(connectivityManager::getLinkProperties)?.interfaceName,
            cameraNetworkAvailable = cameraNetworkAvailable,
            wifiAvailable = networks.any { it.isWifi() },
            cellularAvailable = networks.any { it.isCellular() },
            cellularValidated = networks.any { network ->
                connectivityManager.getNetworkCapabilities(network).isValidatedCellular()
            },
            systemDefaultTransport = defaultCapabilities.systemTransport(),
            systemDefaultValidated = defaultCapabilities.isValidatedInternet(),
            systemDefaultNetworkHandle = defaultNetwork?.networkHandle,
            systemDefaultInterfaceName = defaultLinkProperties?.interfaceName,
        )
    }

    private fun Network.isWifi(): Boolean =
        connectivityManager.getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun Network.isCellular(): Boolean =
        connectivityManager.getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    @Suppress("DEPRECATION")
    private fun availableNetworksSnapshot(): List<Network> =
        // The camera Wi-Fi can be non-default while cellular is the active network.
        connectivityManager.allNetworks.toList()
}

private fun NetworkCapabilities?.isValidatedInternet(): Boolean =
    this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

private fun NetworkCapabilities?.isValidatedCellular(): Boolean =
    this?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true && isValidatedInternet()

private fun NetworkCapabilities?.systemTransport(): SystemNetworkTransport = when {
    this == null -> SystemNetworkTransport.NONE
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> SystemNetworkTransport.VPN
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> SystemNetworkTransport.CELLULAR
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> SystemNetworkTransport.WIFI
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> SystemNetworkTransport.ETHERNET
    else -> SystemNetworkTransport.OTHER
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
