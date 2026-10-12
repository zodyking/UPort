package app.apt443.data.remote

import android.content.Context
import app.apt443.data.settings.KeystoreSecretStore
import app.apt443.data.settings.SecretKeys
import app.apt443.data.settings.SecretStore
import app.apt443.domain.AppSettings
import app.apt443.domain.SiteVpnKind
import app.apt443.signin.VpnReachCopy
import app.apt443.tailscale.TailscaleEngine
import app.apt443.tailscale.VpnConsentGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-app Tailscale. Connected is reported only while this app's tunnel is up. */
class VpnReach(
    context: Context,
    private val secrets: SecretStore,
) {
    private val engine = TailscaleEngine(
        context,
        KeystoreSecretStore(context, "tailscale-state.json"),
    )
    val consent: VpnConsentGate = engine.consent
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status

    fun prepared(): Boolean = consent.prepared()

    suspend fun prepare(settings: AppSettings): VpnStatus {
        val next = snapshot(settings)
        _status.value = next
        return next
    }

    suspend fun ensureSavedTunnel(settings: AppSettings, askPermission: Boolean): Result<Unit> {
        return join(settings, askPermission, replaceExisting = false)
    }

    /** Setup always drops the previous tunnel and logs in again with the key from the form. */
    suspend fun registerForSetup(settings: AppSettings): Result<Unit> {
        stopOurTunnel(clearStatus = true)
        delay(400)
        return join(settings, askPermission = true, replaceExisting = true)
    }

    private suspend fun join(settings: AppSettings, askPermission: Boolean, replaceExisting: Boolean): Result<Unit> {
        if (settings.vpnKind != SiteVpnKind.Tailscale) return Result.success(Unit)
        val key = try {
            secrets.get(SecretKeys.TAILSCALE_AUTH_KEY).orEmpty()
        } catch (error: Exception) {
            return Result.failure(IllegalStateException("The saved Tailscale key could not be read yet.", error))
        }
        val result = engine.ensureRunning(key, settings.tailscaleLoginServer, askPermission, replaceExisting)
        _status.value = snapshot(settings)
        return result
    }

    suspend fun stopOurTunnel(clearStatus: Boolean = false) {
        engine.logout()
        if (clearStatus) _status.value = idle()
    }

    private suspend fun snapshot(settings: AppSettings): VpnStatus {
        if (settings.vpnKind != SiteVpnKind.Tailscale) return idle()
        val hasKey = !secrets.get(SecretKeys.TAILSCALE_AUTH_KEY).isNullOrBlank()
        val up = engine.tunnelUp()
        val needsPermission = hasKey && !consent.prepared()
        val line = VpnReachCopy.tailscale(
            hasKey = hasKey,
            tunnelUp = up,
            needsPermission = needsPermission,
        )
        return VpnStatus(
            kind = SiteVpnKind.Tailscale,
            stateLabel = line.stateLabel,
            summary = line.summary,
            needsPermission = needsPermission,
            ourTunnelUp = up,
        )
    }

    private fun idle(): VpnStatus {
        val line = VpnReachCopy.none()
        return VpnStatus(kind = SiteVpnKind.None, stateLabel = line.stateLabel, summary = line.summary)
    }
}

data class VpnStatus(
    val kind: SiteVpnKind = SiteVpnKind.None,
    val stateLabel: String = "Not configured",
    val summary: String = "",
    val needsPermission: Boolean = false,
    val ourTunnelUp: Boolean = false,
    val foreignVpnActive: Boolean = false,
    val tailscaleInstalled: Boolean = false,
)
