package com.example.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class NetworkTransportType(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile Data"),
    ETHERNET("Ethernet"),
    VPN("VPN"),
    OTHER("Network"),
    NONE("Offline")
}

data class NetworkSnapshot(
    val isOnline: Boolean = true,
    val isValidated: Boolean = true,
    val transport: NetworkTransportType = NetworkTransportType.OTHER,
    val networkHandle: Long = -1L,
    val ipSignature: String = "",
    val generation: Long = 0L
)

enum class NetworkSwitchReason {
    RECONNECTED,
    TRANSPORT_CHANGED,
    IP_OR_DNS_CHANGED,
    VALIDATED_READY
}

data class NetworkSwitchEvent(
    val snapshot: NetworkSnapshot,
    val previousTransport: NetworkTransportType,
    val reason: NetworkSwitchReason
)

/**
 * Monitors Android's active default network using [ConnectivityManager.registerDefaultNetworkCallback]
 * so Wi-Fi <-> Mobile Data handoffs, VPN toggles, and IP/DNS/LinkProperties changes are detected
 * immediately — even when connectivity never drops to offline in between.
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val generationCounter = AtomicLong(0L)
    private val stateLock = Any()

    private val initialSnapshot = inspectActiveNetwork(generation = 0L)
    private val _networkState = MutableStateFlow(initialSnapshot)
    val networkState: StateFlow<NetworkSnapshot> = _networkState.asStateFlow()

    private val _isOnline = MutableStateFlow(initialSnapshot.isOnline)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _networkSwitchEvents = MutableSharedFlow<NetworkSwitchEvent>(
        extraBufferCapacity = 16
    )
    val networkSwitchEvents: SharedFlow<NetworkSwitchEvent> = _networkSwitchEvents.asSharedFlow()

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            evaluateNetworkChange(
                explicitNetwork = network,
                explicitCapabilities = runCatching {
                    connectivityManager.getNetworkCapabilities(network)
                }.getOrNull(),
                explicitLinkProps = runCatching {
                    connectivityManager.getLinkProperties(network)
                }.getOrNull()
            )
        }

        override fun onLost(network: Network) {
            // Re-inspect activeNetwork: on Wi-Fi -> Mobile handoff, activeNetwork may already
            // point to Mobile Data when old Wi-Fi fires onLost!
            evaluateNetworkChange(
                explicitNetwork = null,
                explicitCapabilities = null,
                explicitLinkProps = null
            )
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            evaluateNetworkChange(
                explicitNetwork = network,
                explicitCapabilities = networkCapabilities,
                explicitLinkProps = runCatching {
                    connectivityManager.getLinkProperties(network)
                }.getOrNull()
            )
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties
        ) {
            evaluateNetworkChange(
                explicitNetwork = network,
                explicitCapabilities = runCatching {
                    connectivityManager.getNetworkCapabilities(network)
                }.getOrNull(),
                explicitLinkProps = linkProperties
            )
        }
    }

    init {
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback)
        }
    }

    /**
     * Re-checks the live active network synchronously (e.g., on app resume or manual retry).
     * If the transport, network handle, or IP/DNS changed while backgrounded, immediately
     * flushes stale sockets and emits a [NetworkSwitchEvent].
     */
    fun refreshNow(): Boolean {
        return evaluateNetworkChange(
            explicitNetwork = null,
            explicitCapabilities = null,
            explicitLinkProps = null
        ).isOnline
    }

    fun unregister() {
        runCatching {
            connectivityManager.unregisterNetworkCallback(defaultNetworkCallback)
        }
    }

    private fun evaluateNetworkChange(
        explicitNetwork: Network?,
        explicitCapabilities: NetworkCapabilities?,
        explicitLinkProps: LinkProperties?
    ): NetworkSnapshot {
        val switchEvent: NetworkSwitchEvent?
        val updatedSnapshot: NetworkSnapshot

        synchronized(stateLock) {
            val prev = _networkState.value
            val currentNetwork = explicitNetwork ?: runCatching {
                connectivityManager.activeNetwork
            }.getOrNull()

            val raw = inspectNetwork(
                network = currentNetwork,
                capabilities = explicitCapabilities,
                linkProps = explicitLinkProps,
                generation = prev.generation
            )

            val cameOnline = !prev.isOnline && raw.isOnline
            val handleChanged = raw.isOnline &&
                prev.isOnline &&
                raw.networkHandle != -1L &&
                prev.networkHandle != -1L &&
                raw.networkHandle != prev.networkHandle
            val transportChanged = raw.isOnline &&
                prev.isOnline &&
                raw.transport != prev.transport
            val ipOrDnsChanged = raw.isOnline &&
                prev.isOnline &&
                raw.ipSignature.isNotBlank() &&
                prev.ipSignature.isNotBlank() &&
                raw.ipSignature != prev.ipSignature
            val becameValidated = raw.isOnline &&
                raw.isValidated &&
                !prev.isValidated &&
                prev.isOnline

            val reason: NetworkSwitchReason? = when {
                cameOnline -> NetworkSwitchReason.RECONNECTED
                handleChanged || transportChanged -> NetworkSwitchReason.TRANSPORT_CHANGED
                ipOrDnsChanged -> NetworkSwitchReason.IP_OR_DNS_CHANGED
                becameValidated -> NetworkSwitchReason.VALIDATED_READY
                else -> null
            }

            if (reason != null) {
                val nextGen = generationCounter.incrementAndGet()
                updatedSnapshot = raw.copy(generation = nextGen)
                switchEvent = NetworkSwitchEvent(
                    snapshot = updatedSnapshot,
                    previousTransport = prev.transport,
                    reason = reason
                )
            } else {
                updatedSnapshot = raw
                switchEvent = null
            }

            _networkState.value = updatedSnapshot
            _isOnline.value = updatedSnapshot.isOnline
        }

        if (switchEvent != null) {
            // Immediately flush stale OkHttp sockets & clear any old network binding
            NetworkClientProvider.onNetworkChanged(appContext)
            _networkSwitchEvents.tryEmit(switchEvent)
        } else if (!updatedSnapshot.isOnline) {
            // Also evict dead sockets when dropping offline
            NetworkClientProvider.onNetworkChanged(appContext)
        }

        return updatedSnapshot
    }

    private fun inspectActiveNetwork(generation: Long): NetworkSnapshot {
        val active = runCatching { connectivityManager.activeNetwork }.getOrNull()
        return inspectNetwork(active, null, null, generation)
    }

    private fun inspectNetwork(
        network: Network?,
        capabilities: NetworkCapabilities?,
        linkProps: LinkProperties?,
        generation: Long
    ): NetworkSnapshot {
        if (network == null) {
            return NetworkSnapshot(
                isOnline = false,
                isValidated = false,
                transport = NetworkTransportType.NONE,
                networkHandle = -1L,
                ipSignature = "",
                generation = generation
            )
        }

        val caps = capabilities ?: runCatching {
            connectivityManager.getNetworkCapabilities(network)
        }.getOrNull()
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!hasInternet) {
            return NetworkSnapshot(
                isOnline = false,
                isValidated = false,
                transport = NetworkTransportType.NONE,
                networkHandle = -1L,
                ipSignature = "",
                generation = generation
            )
        }

        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = resolveTransport(caps)
        val lp = linkProps ?: runCatching {
            connectivityManager.getLinkProperties(network)
        }.getOrNull()
        val ipSig = buildIpSignature(lp)

        return NetworkSnapshot(
            isOnline = true,
            isValidated = isValidated,
            transport = transport,
            networkHandle = runCatching { network.networkHandle }.getOrDefault(-1L),
            ipSignature = ipSig,
            generation = generation
        )
    }

    private fun resolveTransport(caps: NetworkCapabilities): NetworkTransportType {
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransportType.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
            else -> NetworkTransportType.OTHER
        }
    }

    private fun buildIpSignature(lp: LinkProperties?): String {
        if (lp == null) return ""
        val iface = lp.interfaceName.orEmpty()
        val addresses = lp.linkAddresses
            .mapNotNull { it.address?.hostAddress }
            .sorted()
            .joinToString(",")
        val dns = lp.dnsServers
            .mapNotNull { it.hostAddress }
            .sorted()
            .joinToString(",")
        return "$iface|$addresses|$dns"
    }

    companion object {
        fun isAnyNetworkAvailable(context: Context): Boolean {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val active = runCatching { cm.activeNetwork }.getOrNull() ?: return false
            val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull() ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
