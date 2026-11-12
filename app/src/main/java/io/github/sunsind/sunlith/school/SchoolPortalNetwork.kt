package io.github.sunsind.sunlith.school

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal data class SchoolNetworkCandidate<T>(val network: T, val transports: Set<Int>, val capabilities: Set<Int>)

@Suppress("InlinedApi")
internal fun <T> schoolDirectNetwork(vpnActive: Boolean, candidates: List<SchoolNetworkCandidate<T>>): T? {
    if (!vpnActive) return null
    val required = setOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
        NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    return candidates.filter {
        it.capabilities.containsAll(required) && NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL !in it.capabilities &&
            NetworkCapabilities.TRANSPORT_VPN !in it.transports
    }.mapNotNull { candidate ->
        val priority = when {
            NetworkCapabilities.TRANSPORT_WIFI in candidate.transports || NetworkCapabilities.TRANSPORT_ETHERNET in candidate.transports -> 0
            NetworkCapabilities.TRANSPORT_CELLULAR in candidate.transports -> 1
            else -> return@mapNotNull null
        }
        priority to candidate.network
    }.minByOrNull { it.first }?.second
}

internal object SchoolPortalNetwork {
    @SuppressLint("InlinedApi")
    fun routes(context: Context): List<(URI) -> HttpsURLConnection> {
        val default: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return listOf(default)
        val direct = try {
            val active = manager.activeNetwork
            val vpn = active?.let(manager::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (!vpn) return listOf(default)
            @Suppress("DEPRECATION")
            val candidates = manager.allNetworks.mapNotNull { network ->
                val state = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val transports = setOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET,
                    NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN).filterTo(mutableSetOf(), state::hasTransport)
                val capabilities = setOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL).filterTo(mutableSetOf(), state::hasCapability)
                // NOT_SUSPENDED is an inlined key; pre-28 networks are assumed unsuspended without querying it.
                if (Build.VERSION.SDK_INT < 28 || state.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                    capabilities += NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
                }
                SchoolNetworkCandidate(network, transports, capabilities)
            }
            schoolDirectNetwork(true, candidates)
        } catch (_: SecurityException) { null }
        if (direct == null) return listOf(default)
        // Network.openConnection scopes both DNS and sockets to this phone network. Android still enforces VPN lockdown.
        return listOf({ uri -> direct.openConnection(uri.toURL()) as HttpsURLConnection }, default)
    }
}
