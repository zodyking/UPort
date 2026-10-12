package app.apt443.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.data.remote.VpnStatus
import app.apt443.data.settings.SecretKeys
import app.apt443.domain.AppSettings
import app.apt443.domain.ConnectionMode
import app.apt443.domain.SiteVpnKind
import app.apt443.domain.label
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptHero
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.AptListRow
import app.apt443.ui.components.AptMetric
import app.apt443.ui.components.EmptyState
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.SectionLabel
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType

@Composable
fun RemoteScreen(container: AppContainer, onOpenSettings: () -> Unit = {}) {
    val settings by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val vpn by container.vpnStatus.collectAsStateWithLifecycle(initialValue = VpnStatus())
    val mode by container.protect.connectionMode.collectAsStateWithLifecycle(initialValue = ConnectionMode.Unconfigured)
    var hasKey by remember { mutableStateOf(false) }
    val tailscale = settings.vpnKind == SiteVpnKind.Tailscale
    val listState = rememberLazyListState()

    LaunchedEffect(settings.vpnKind) {
        hasKey = !container.secrets.get(SecretKeys.TAILSCALE_AUTH_KEY).isNullOrBlank()
    }

    val tone = when {
        vpn.ourTunnelUp -> StatusTone.Online
        mode == ConnectionMode.OnNetwork -> StatusTone.Online
        vpn.needsPermission -> StatusTone.Warning
        else -> StatusTone.Neutral
    }
    val pill = when {
        vpn.ourTunnelUp -> "Connected"
        mode == ConnectionMode.OnNetwork -> "On this network"
        mode == ConnectionMode.Remote -> "Away from the console"
        else -> vpn.stateLabel.ifBlank { "Not connected" }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
        verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
        contentPadding = PaddingValues(
            start = AptSpace.focusInset,
            end = AptSpace.focusInset,
            top = AptSpace.xs,
            bottom = AptSpace.xl,
        ),
    ) {
        item(key = "hero") {
            AptHero(
                eyebrow = "Tailscale",
                title = if (tailscale) "Remote access" else "Not set up",
                badges = { StatusPill(pill, tone) },
                metrics = {
                    AptMetric("Tunnel", if (vpn.ourTunnelUp) "Up" else "Down")
                    AptMetric("Connection", mode.label())
                    AptMetric("Auth key", if (tailscale && hasKey) "Saved" else "Missing")
                },
                action = {
                    AptButton(
                        text = "Settings",
                        icon = AptIconKind.Settings,
                        style = AptButtonStyle.Secondary,
                        onClick = onOpenSettings,
                    )
                },
            )
        }
        if (!tailscale) {
            item(key = "none") {
                EmptyState(
                    title = "No Tailscale key on this TV",
                    message = "Setup did not include a Tailscale auth key. Add one in the site file and set this TV up again.",
                    icon = AptIconKind.Remote,
                    expand = false,
                    action = {
                        AptButton(
                            text = "Open Settings",
                            icon = AptIconKind.Settings,
                            style = AptButtonStyle.Primary,
                            onClick = onOpenSettings,
                        )
                    },
                )
            }
            return@LazyColumn
        }
        item(key = "setup-label") { SectionLabel("From setup") }
        item(key = "key") {
            AptListRow(
                title = "Auth key",
                meta = "The auth key stays on this TV and is not shown.",
                icon = AptIconKind.Lock,
            ) {
                StatusPill(
                    text = if (hasKey) "Saved" else "Missing",
                    tone = if (hasKey) StatusTone.Online else StatusTone.Warning,
                )
            }
        }
        item(key = "server") {
            AptListRow(
                title = "Server",
                meta = settings.tailscaleLoginServer.ifBlank { "Tailscale" },
                icon = AptIconKind.Network,
            )
        }
        if (!hasKey) {
            item(key = "missing") {
                InlineMessage("The site file did not include a Tailscale auth key.", StatusTone.Warning)
            }
        } else if (vpn.summary.isNotBlank()) {
            item(key = "summary") {
                Text(vpn.summary, fontSize = AptType.body, color = AptColors.Secondary)
            }
        }
    }
}
