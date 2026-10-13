package app.apt443.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Wi-Fi or Ethernet under the VPN. Tailscale's own sockets must use this, not the tunnel. */
internal object PhysicalNetwork {
    /** Changes when the TV joins a different Wi-Fi or Ethernet link, including after a reboot. */
    fun signature(context: Context): String {
        val manager = manager(context) ?: return ""
        val network = pick(context) ?: return ""
        val link = manager.getLinkProperties(network)
        val caps = manager.getNetworkCapabilities(network)
        val addresses = link?.linkAddresses?.joinToString(",") { it.address?.hostAddress.orEmpty() }.orEmpty()
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return "${network.networkHandle}|$addresses|${gateway(context)}|${dnsConfig(context)}|$validated"
    }

    fun gateway(context: Context): String {
        val manager = manager(context) ?: return ""
        val link = manager.getLinkProperties(pick(context) ?: return "") ?: return ""
        val route = link.routes.firstOrNull { it.gateway != null && it.destination.prefixLength == 0 }
            ?: link.routes.firstOrNull { it.gateway != null }
        return route?.gateway?.hostAddress.orEmpty()
    }

    /** Same shape Tailscale asks for: DNS servers, then search domains. */
    fun dnsConfig(context: Context): String {
        val manager = manager(context) ?: return ""
        val link = manager.getLinkProperties(pick(context) ?: return "") ?: return ""
        val servers = link.dnsServers.mapNotNull { it.hostAddress }.joinToString(" ")
        return "$servers\n${link.domains.orEmpty()}".trim()
    }

    private fun manager(context: Context): ConnectivityManager? {
        return context.applicationContext.getSystemService(ConnectivityManager::class.java)
    }

    fun pick(context: Context): Network? {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val matches = manager.allNetworks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            network to caps
        }
        return matches.firstOrNull { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }?.first
            ?: matches.firstOrNull()?.first
    }
}
