package app.apt443.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Wi-Fi or Ethernet under the VPN. Tailscale's own sockets must use this, not the tunnel. */
internal object PhysicalNetwork {
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
