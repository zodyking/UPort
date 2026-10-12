package app.apt443.net

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object LanAddress {
    fun ipv4(): String? {
        val preferred = mutableListOf<String>()
        val others = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
            val name = iface.name.lowercase()
            if (name.contains("p2p") || name.contains("dummy") || name.contains("wifi-aware")) continue
            for (address in iface.inetAddresses) {
                if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = address.hostAddress ?: continue
                if (emulatorNat(host)) continue
                if (host.startsWith("192.168.") || host.startsWith("10.")) {
                    preferred.add(host)
                } else {
                    others.add(host)
                }
            }
        }
        return preferred.firstOrNull() ?: others.firstOrNull()
    }

    fun publish(port: Int): String? = ipv4()?.let { "$it:$port" }

    /** Emulator NAT address is not reachable from a phone on the home network. */
    fun emulatorPublished(port: Int): String? {
        repeat(8) { attempt ->
            emulatorPublishedOnce()?.let { published ->
                if (published.substringAfter(':').toIntOrNull() == port) return published
            }
            if (attempt < 7) Thread.sleep(500)
        }
        return null
    }

    private fun emulatorPublishedOnce(): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("10.0.2.2", HOST_BRIDGE_PORT), 700)
            socket.getOutputStream().apply {
                write("GET /lan HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n".toByteArray())
                flush()
            }
            val payload = socket.getInputStream().bufferedReader().readText()
                .substringAfter("\r\n\r\n", missingDelimiterValue = "")
                .trim()
            payload.takeIf { PUBLISHED.matches(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun emulatorNat(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts[0] == "10" && parts[1] == "0" && parts[2] == "2"
    }

    private const val HOST_BRIDGE_PORT = 8742
    private val PUBLISHED = Regex("""\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}""")
}
