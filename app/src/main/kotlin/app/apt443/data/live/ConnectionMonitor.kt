package app.apt443.data.live

import app.apt443.data.remote.VpnReach
import app.apt443.data.remote.VpnStatus
import app.apt443.tailscale.TailscaleLogin
import app.apt443.domain.AppSettings
import app.apt443.domain.ConnectionMode
import app.apt443.domain.DataSourceMode
import app.apt443.domain.SiteVpnKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConnectionMonitor(
    private val settings: Flow<AppSettings>,
    private val http: UnifiHttp,
    private val vpn: VpnReach,
) {
    private val publishGate = Mutex()
    private val _mode = MutableStateFlow(ConnectionMode.Unconfigured)
    private val _detail = MutableStateFlow("Starting")

    val mode: StateFlow<ConnectionMode> = _mode
    val detail: StateFlow<String> = _detail

    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.collectLatest { current ->
                publish(current)
            }
        }
        scope.launch {
            while (isActive) {
                delay(45_000)
                publish(settings.first())
            }
        }
    }

    private suspend fun publish(current: AppSettings) {
        publishGate.withLock {
            if (!current.siteConfigured || current.dataSource != DataSourceMode.Live) {
                _mode.value = if (current.siteConfigured) ConnectionMode.Demo else ConnectionMode.Unconfigured
                _detail.value = if (current.siteConfigured) {
                    "Preview of UPort. Sign in to use the console."
                } else {
                    "This TV is not connected yet."
                }
                return
            }
            if (consoleHost(current).isBlank()) {
                _mode.value = ConnectionMode.Unconfigured
                _detail.value = "This TV is not connected yet."
                return
            }
            when (val probe = http.probe(current)) {
                ProbeResult.Reachable -> markOnNetwork(VpnStatus())
                is ProbeResult.Untrusted -> {
                    _mode.value = ConnectionMode.OnNetwork
                    _detail.value = probe.message
                }
                is ProbeResult.Unreachable -> {
                    if (current.vpnKind == SiteVpnKind.Tailscale) {
                        if (vpn.prepared()) {
                            runCatching { vpn.ensureSavedTunnel(current, askPermission = false) }
                        }
                        val status = vpn.prepare(current)
                        applyProbe(current, probe, status, retryWhenVpnUp = true)
                    } else {
                        _mode.value = ConnectionMode.Unconfigured
                        _detail.value = "The console is not reachable from here."
                    }
                }
            }
        }
    }

    private suspend fun applyProbe(
        current: AppSettings,
        first: ProbeResult,
        status: VpnStatus,
        retryWhenVpnUp: Boolean,
    ) {
        val probe = if (first is ProbeResult.Unreachable && retryWhenVpnUp && vpnMayReach(status)) {
            http.probe(current)
        } else {
            first
        }
        when (probe) {
            ProbeResult.Reachable -> markOnNetwork(status)
            is ProbeResult.Untrusted -> {
                _mode.value = ConnectionMode.OnNetwork
                _detail.value = probe.message
            }
            is ProbeResult.Unreachable -> markRemote(current, remoteMessage(status))
        }
    }

    private fun vpnMayReach(status: VpnStatus): Boolean = status.ourTunnelUp

    private fun markOnNetwork(status: VpnStatus) {
        _mode.value = ConnectionMode.OnNetwork
        _detail.value = if (status.ourTunnelUp) {
            "Console reachable over Tailscale."
        } else {
            "Console reachable on this network."
        }
    }

    private fun markRemote(current: AppSettings, message: String) {
        _mode.value = ConnectionMode.Remote
        _detail.value = if (current.consoleId.isBlank()) {
            "$message Add the Site Manager console ID for cloud status."
        } else {
            message
        }
    }

    private fun remoteMessage(status: VpnStatus): String {
        if (status.ourTunnelUp) return TailscaleLogin.CONSOLE_UNREACHABLE
        if (status.summary.isNotBlank()) return status.summary
        return "The console is not reachable from here."
    }
}
