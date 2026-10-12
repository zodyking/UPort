package app.apt443.signin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import app.apt443.data.live.ProbeResult
import app.apt443.data.live.UnifiHttp
import app.apt443.data.live.apiKeyHeader
import app.apt443.domain.AppSettings
import app.apt443.domain.DataSourceMode
import javax.net.ssl.SSLException

/**
 * Finds the UniFi console on the TV's LAN by checking the Wi-Fi gateway,
 * saved host, and a few common console addresses.
 */
object ConsoleDiscovery {
    const val DEFAULT_CONSOLE_HOST = "192.168.0.1"
    const val DEFAULT_CONSOLE_PORT = 443
    const val DEFAULT_OS_USERNAME = "admin"
    const val DEFAULT_API_KEY = "DtCQIoJhBSGEpN5gu6Ti1e4zYTWOypLk"

    private val COMMON_HOSTS = listOf(
        DEFAULT_CONSOLE_HOST,
        "192.168.1.1",
        "192.168.10.1",
    )

    fun gatewayIp(context: Context): String? = try {
        if (activeNetworkIsVpn(context)) wifiGateway(context) ?: linkGateway(context)
        else linkGateway(context) ?: wifiGateway(context)
    } catch (_: Exception) {
        null
    }

    private fun activeNetworkIsVpn(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun linkGateway(context: Context): String? {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val link = manager.getLinkProperties(manager.activeNetwork) ?: return null
        return link.routes.firstOrNull { route ->
            val gateway = route.gateway
            gateway is Inet4Address && route.destination.prefixLength == 0
        }?.gateway?.hostAddress
    }

    @Suppress("DEPRECATION")
    private fun wifiGateway(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val gateway = wifi.dhcpInfo?.gateway ?: return null
        if (gateway == 0) return null
        return intToIp(gateway)
    }

    suspend fun locate(
        http: UnifiHttp,
        context: Context,
        savedHost: String,
        apiKey: String?,
        trustCertificate: Boolean,
    ): String? {
        val candidates = linkedSetOf<String>()
        gatewayIp(context)?.let { candidates.add(it) }
        savedHost.trim().takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        candidates.addAll(COMMON_HOSTS)

        for (host in candidates) {
            val found = try {
                val settings = probeSettings(host, trustCertificate)
                if (!apiKey.isNullOrBlank()) {
                    probeWithKey(http, settings, apiKey)
                } else {
                    when (http.probe(settings)) {
                        ProbeResult.Reachable -> ProbeMatch.Found
                        is ProbeResult.Untrusted -> ProbeMatch.Untrusted
                        is ProbeResult.Unreachable -> ProbeMatch.NotFound
                    }
                }
            } catch (_: Exception) {
                ProbeMatch.NotFound
            }
            if (found == ProbeMatch.Found || found == ProbeMatch.Untrusted) return host
        }
        return savedHost.trim().takeIf { it.isNotBlank() }
    }

    private suspend fun probeWithKey(http: UnifiHttp, settings: AppSettings, apiKey: String): ProbeMatch {
        val url = "https://${settings.consoleHost}/proxy/protect/integration/v1/cameras"
        val response = http.text(settings, url, "GET", apiKeyHeader(apiKey)).getOrElse { error ->
            if (generateSequence<Throwable>(error) { it.cause }.any { it is SSLException }) {
                return ProbeMatch.Untrusted
            }
            return ProbeMatch.NotFound
        }
        return when (response.code) {
            in 200..299 -> ProbeMatch.Found
            else -> ProbeMatch.NotFound
        }
    }

    private fun probeSettings(host: String, trustCertificate: Boolean) = AppSettings(
        consoleHost = host,
        trustConsoleCertificate = trustCertificate,
        dataSource = DataSourceMode.Live,
    )

    private fun intToIp(value: Int): String {
        return listOf(
            value and 0xff,
            value shr 8 and 0xff,
            value shr 16 and 0xff,
            value shr 24 and 0xff,
        ).joinToString(".")
    }

    private enum class ProbeMatch {
        Found,
        Untrusted,
        NotFound,
    }
}
