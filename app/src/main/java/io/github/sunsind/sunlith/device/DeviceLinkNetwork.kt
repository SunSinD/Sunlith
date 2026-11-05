package io.github.sunsind.sunlith.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Local addresses on Wi-Fi, Ethernet and VPN networks only. Cellular interfaces are never trusted, even when the
 * carrier hands out RFC 1918 or 100.64/10 (CGNAT) addresses.
 */
internal object DeviceLinkNetwork {
    private data class Candidate(val address: Inet4Address, val vpn: Boolean)
    private const val CACHE_MS = 5_000L
    @Volatile private var cache: Pair<Long, Set<InetAddress>>? = null

    fun addresses(context: Context): List<String> = candidates(context)
        .sortedWith(compareBy<Candidate> { !(it.vpn && isTailscale(it.address)) }.thenBy { !it.vpn })
        .mapNotNull { it.address.hostAddress }.distinct()

    fun isPrivate(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val a = address.address[0].toInt() and 0xff
        val b = address.address[1].toInt() and 0xff
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || isTailscale(address)
    }

    fun isTailscale(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        return (address.address[0].toInt() and 0xff) == 100 && (address.address[1].toInt() and 0xff) in 64..127
    }

    /** Admits a connection only if it arrived on an eligible local address from a private IPv4 peer. */
    fun admits(context: Context, local: InetAddress?, remote: InetAddress?): Boolean {
        if (local !is Inet4Address || remote !is Inet4Address || !isPrivate(remote)) return false
        if (local.isLoopbackAddress) return remote.isLoopbackAddress
        if (remote.isLoopbackAddress) return false
        val now = System.nanoTime() / 1_000_000
        val eligible = cache?.takeIf { now - it.first < CACHE_MS }?.second
            ?: candidates(context).mapTo(mutableSetOf<InetAddress>()) { it.address }.also { cache = now to it }
        return local in eligible
    }

    private fun candidates(context: Context): List<Candidate> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val result = mutableListOf<Candidate>()
        val cellularInterfaces = mutableSetOf<String>()
        val vpnInterfaces = mutableSetOf<String>()
        var vpnActive = false
        @Suppress("DEPRECATION")
        for (network in runCatching { manager.allNetworks }.getOrDefault(emptyArray())) {
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            val link = runCatching { manager.getLinkProperties(network) }.getOrNull()
            val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (!vpn && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                link?.interfaceName?.let(cellularInterfaces::add)
                continue
            }
            if (!vpn && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            if (vpn) {
                vpnActive = true
                link?.interfaceName?.let(vpnInterfaces::add)
            }
            link?.linkAddresses.orEmpty().forEach { linkAddress ->
                val address = linkAddress.address
                if (address is Inet4Address && isPrivate(address) && !address.isLoopbackAddress) result += Candidate(address, vpn)
            }
        }
        if (vpnActive) {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            for (networkInterface in interfaces) {
                val name = networkInterface.name ?: continue
                if (name in cellularInterfaces || !(name in vpnInterfaces || name.startsWith("tun"))) continue
                if (!runCatching { networkInterface.isUp }.getOrDefault(false)) continue
                networkInterface.inetAddresses.toList().filterIsInstance<Inet4Address>().filter(::isTailscale)
                    .forEach { result += Candidate(it, true) }
            }
        }
        return result.distinctBy { it.address }
    }
}
