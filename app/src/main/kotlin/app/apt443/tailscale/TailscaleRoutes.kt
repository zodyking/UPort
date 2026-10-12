package app.apt443.tailscale

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tailscale advertises subnet routes into the VPN. A route that covers the TV's
 * own Wi-Fi network would send that LAN through the tunnel, including the console
 * sitting next to the TV. Those prefixes stay on the local network.
 */
object TailscaleRoutes {
    fun overlapsLocalLan(
        address: String,
        prefix: Int,
        local: List<Pair<String, Int>> = localIpv4Prefixes(),
    ): Boolean {
        if (prefix <= 0 || address.contains(':')) return false
        val route = parse(address, prefix) ?: return false
        return local.any { (ip, bits) ->
            val home = parse(ip, bits) ?: return@any false
            overlaps(route, home)
        }
    }

    fun localIpv4Prefixes(): List<Pair<String, Int>> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        val prefixes = mutableListOf<Pair<String, Int>>()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("ipsec")) continue
            for (address in nif.interfaceAddresses) {
                val ip = address.address
                if (ip is Inet4Address && !ip.isLoopbackAddress) {
                    val host = ip.hostAddress ?: continue
                    prefixes.add(host to address.networkPrefixLength.toInt())
                }
            }
        }
        return prefixes
    }

    private fun overlaps(left: Prefix, right: Prefix): Boolean {
        val bits = minOf(left.bits, right.bits)
        if (bits <= 0) return true
        val mask = if (bits >= 32) -1 else (-1 shl (32 - bits))
        return (left.addr and mask) == (right.addr and mask)
    }

    private fun parse(address: String, bits: Int): Prefix? {
        val parts = address.split('.')
        if (parts.size != 4 || bits !in 0..32) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return Prefix(value, bits)
    }

    private data class Prefix(val addr: Int, val bits: Int)
}
