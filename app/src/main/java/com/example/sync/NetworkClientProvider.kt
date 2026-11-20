package com.example.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Centralized, network-handoff-resilient HTTP client provider for all sync, API, GPS, and
 * handshake calls.
 *
 * Solves common Android Mobile Data <-> Wi-Fi transition stalls & IP/DNS conflicts:
 * 1. Uses a single shared [ConnectionPool] with a short keep-alive (25s) that is immediately
 *    evicted on any network transport, handle, or IP/DNS change via [onNetworkChanged].
 * 2. Resolves DNS directly against [ConnectivityManager.getActiveNetwork] first (bypassing stale
 *    process-level DNS cache from the previous interface) and prioritizes IPv4 over IPv6 to avoid
 *    dual-stack IPv6 blackholes / carrier-ISP IP reflection conflicts.
 * 3. Creates TCP sockets via the currently active [Network.getSocketFactory] so connections never
 *    linger on a dead Wi-Fi or cellular route.
 * 4. Automatically recovers from mid-handoff [IOException]s by clearing stale process bindings,
 *    evicting dead sockets, and retrying once on the newly active interface.
 */
object NetworkClientProvider {

    private val lock = Any()
    private val sharedConnectionPool = ConnectionPool(
        maxIdleConnections = 6,
        keepAliveDuration = 25L,
        timeUnit = TimeUnit.SECONDS
    )

    @Volatile
    private var baseClient: OkHttpClient? = null

    fun getClient(context: Context): OkHttpClient {
        baseClient?.let { return it }
        synchronized(lock) {
            baseClient?.let { return it }
            val appCtx = context.applicationContext
            val created = OkHttpClient.Builder()
                .connectionPool(sharedConnectionPool)
                .dns(ResilientActiveNetworkDns(appCtx))
                .socketFactory(ActiveNetworkSocketFactory(appCtx))
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .callTimeout(28, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(NetworkSwitchRecoveryInterceptor(appCtx))
                .build()
            baseClient = created
            return created
        }
    }

    /**
     * Derives a client with custom timeouts while sharing the same [ConnectionPool],
     * [ResilientActiveNetworkDns], [ActiveNetworkSocketFactory], and recovery interceptor.
     */
    fun getClientWithTimeouts(
        context: Context,
        connectSeconds: Long = 12L,
        readSeconds: Long = 15L,
        writeSeconds: Long = 15L,
        callSeconds: Long = 35L
    ): OkHttpClient {
        return getClient(context)
            .newBuilder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeSeconds, TimeUnit.SECONDS)
            .callTimeout(callSeconds, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Immediately flushes stale process network bindings and evicts all pooled HTTP/TCP sockets
     * whenever Android switches networks (Wi-Fi <-> Mobile Data, VPN, DHCP IP change, etc.).
     */
    fun onNetworkChanged(context: Context) {
        val appCtx = context.applicationContext
        runCatching {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            // Guarantee process is never stuck bound to an old/lost network interface
            cm?.bindProcessToNetwork(null)
        }
        runCatching {
            sharedConnectionPool.evictAll()
        }
    }

    /**
     * Queries DNS directly on the currently active OS network interface to avoid stale DNS cache
     * from a previous ISP/Wi-Fi, and places IPv4 addresses first so broken IPv6 routes never
     * stall or cause IP-reflection conflicts during Wi-Fi <-> Mobile Data shifts.
     */
    private class ResilientActiveNetworkDns(
        private val appContext: Context
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = runCatching { cm?.activeNetwork }.getOrNull()

            val resolved: Array<InetAddress> = try {
                val activeResults = activeNetwork?.let { network ->
                    runCatching { network.getAllByName(hostname) }.getOrNull()
                }
                if (!activeResults.isNullOrEmpty()) {
                    activeResults
                } else {
                    InetAddress.getAllByName(hostname)
                }
            } catch (ex: UnknownHostException) {
                // Retry once via system resolver if activeNetwork lookup threw
                try {
                    InetAddress.getAllByName(hostname)
                } catch (_: Exception) {
                    throw ex
                }
            }

            if (resolved.isEmpty()) {
                throw UnknownHostException("No addresses returned for $hostname")
            }

            // Prioritize IPv4 before IPv6 while keeping relative order within each family
            val ipv4 = resolved.filterIsInstance<Inet4Address>()
            val others = resolved.filter { it !is Inet4Address }
            return ipv4 + others
        }
    }

    /**
     * Binds every newly created TCP socket to Android's currently active default network,
     * preventing old-interface routing conflicts after rapid Mobile Data <-> Wi-Fi transitions.
     */
    private class ActiveNetworkSocketFactory(
        private val appContext: Context
    ) : SocketFactory() {
        private fun currentFactory(): SocketFactory {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            return runCatching { cm?.activeNetwork?.socketFactory }.getOrNull()
                ?: getDefault()
        }

        override fun createSocket(): Socket = currentFactory().createSocket()

        override fun createSocket(host: String?, port: Int): Socket =
            currentFactory().createSocket(host, port)

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket = currentFactory().createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            currentFactory().createSocket(host, port)

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket = currentFactory().createSocket(address, port, localAddress, localPort)
    }

    /**
     * Automatically evicts dead connections and performs one clean retry on a fresh socket
     * if a request hits a mid-handoff network error (socket reset, DNS hiccup, IP change, etc.).
     */
    private class NetworkSwitchRecoveryInterceptor(
        private val appContext: Context
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            return try {
                chain.proceed(request)
            } catch (firstError: IOException) {
                if (chain.call().isCanceled()) throw firstError

                // Evict stale sockets & clear any stale process network binding, then retry once
                onNetworkChanged(appContext)
                try {
                    Thread.sleep(250L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw firstError
                }

                if (chain.call().isCanceled()) throw firstError

                val retryRequest = request.newBuilder()
                    .header("Connection", "close")
                    .build()
                chain.proceed(retryRequest)
            }
        }
    }
}
