package app.apt443.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.apt443.data.AppContainer
import app.apt443.domain.Availability
import app.apt443.domain.ConnectionMode
import app.apt443.domain.NetworkClient
import app.apt443.domain.NetworkDashboard
import app.apt443.domain.NetworkDevice
import app.apt443.domain.WanStatus
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptHero
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.AptListRow
import app.apt443.ui.components.AptMetric
import app.apt443.ui.components.EmptyState
import app.apt443.ui.components.InlineEmpty
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.LoadingState
import app.apt443.ui.components.SectionLabel
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long an empty dashboard counts as "still loading" before the empty state shows. */
private const val NETWORK_GRACE_MS = 4_000L

@Composable
fun NetworkScreen(container: AppContainer, onOpenRemote: () -> Unit = {}) {
    val mode by container.protect.connectionMode.collectAsStateWithLifecycle(initialValue = ConnectionMode.Unconfigured)
    val dashboard by container.network.dashboard().collectAsStateWithLifecycle(initialValue = emptyDashboard())
    val onNetwork = mode == ConnectionMode.OnNetwork
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(NETWORK_GRACE_MS)
        settled = true
    }
    val listState = rememberLazyListState()
    val accessPoints = dashboard.devices.filter { it.kind == "Access point" && it.online }
    val otherDevices = dashboard.devices.filter { it.kind != "Access point" }
    val activeClients = dashboard.clients.filter { it.online }
    val nothingYet = dashboard.devices.isEmpty() && activeClients.isEmpty()
    val stillLoading = nothingYet && !settled &&
        (dashboard.availability as? Availability.NeedsSetup)?.message.isNullOrBlank()
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
            NetworkHero(
                wan = dashboard.wan,
                activeClients = activeClients.size,
                onNetwork = onNetwork,
                testing = testing || dashboard.wan.speedtest == "running",
                onSpeedTest = {
                    if (!onNetwork || testing) return@NetworkHero
                    scope.launch {
                        testing = true
                        note = container.network.startSpeedTest().fold(
                            onSuccess = { it },
                            onFailure = { it.message ?: "The speed test did not start." },
                        )
                        testing = false
                    }
                },
            )
        }
        if (note.isNotBlank()) {
            item(key = "note") {
                InlineMessage(note, if (note.startsWith("Speed")) StatusTone.Online else StatusTone.Warning)
            }
        }
        if (!onNetwork) {
            item(key = "away") {
                EmptyState(
                    title = "Home network only",
                    message = "Client lists, access points, and the speed test need this TV on the same network as the console.",
                    icon = AptIconKind.Network,
                    expand = false,
                    action = {
                        AptButton(
                            text = "Check Tailscale",
                            icon = AptIconKind.Remote,
                            style = AptButtonStyle.Primary,
                            onClick = onOpenRemote,
                        )
                    },
                )
            }
            return@LazyColumn
        }
        if (dashboard.detail.isNotBlank()) {
            item(key = "detail") { InlineMessage(dashboard.detail, StatusTone.Warning) }
        }
        if (stillLoading) {
            item(key = "loading") {
                LoadingState(label = "Asking the console for devices", modifier = Modifier.height(240.dp))
            }
        } else if (nothingYet) {
            item(key = "empty") {
                EmptyState(
                    title = "Nothing on the network yet",
                    message = (dashboard.availability as? Availability.NeedsSetup)?.message?.ifBlank { null }
                        ?: "Devices and clients appear when the console answers.",
                    icon = AptIconKind.Network,
                    expand = false,
                    action = {
                        AptButton(
                            text = "Check connection",
                            icon = AptIconKind.Remote,
                            onClick = onOpenRemote,
                        )
                    },
                )
            }
        } else {
            if (accessPoints.isNotEmpty()) {
                item(key = "ap-label") { SectionLabel("Access points") }
                items(accessPoints, key = { "ap-${it.id}" }) { device -> NetworkRow(device.toRow(), AptIconKind.Network) }
            }
            if (otherDevices.isNotEmpty()) {
                item(key = "device-label") { SectionLabel("Gateway and switches") }
                items(otherDevices, key = { "dev-${it.id}" }) { device -> NetworkRow(device.toRow(), AptIconKind.Home) }
            }
            item(key = "client-label") { SectionLabel("Active clients") }
            if (activeClients.isEmpty()) {
                item(key = "client-empty") { InlineEmpty("No active clients right now.") }
            } else {
                items(activeClients, key = { "client-${it.id}" }) { client -> NetworkRow(client.toRow(), null) }
            }
        }
    }
}

@Composable
private fun NetworkHero(
    wan: WanStatus,
    activeClients: Int,
    onNetwork: Boolean,
    testing: Boolean,
    onSpeedTest: () -> Unit,
) {
    val tone = when {
        !onNetwork -> StatusTone.Warning
        testing -> StatusTone.Info
        wan.up == true -> StatusTone.Online
        wan.up == false -> StatusTone.Danger
        else -> StatusTone.Warning
    }
    val state = when {
        !onNetwork -> "Home only"
        testing -> "Testing"
        wan.up == true -> "Online"
        wan.up == false -> "Offline"
        else -> "Checking"
    }
    AptHero(
        eyebrow = "Network",
        title = if (onNetwork) wan.ispName.ifBlank { "Internet" } else "On the home network",
        badges = {
            StatusPill(state, tone)
            if (onNetwork && activeClients > 0) {
                StatusPill("$activeClients active", StatusTone.Neutral)
            }
        },
        metrics = {
            AptMetric("Down now", if (onNetwork) mbps(wan.liveDownMbps) else "--")
            AptMetric("Up now", if (onNetwork) mbps(wan.liveUpMbps) else "--")
            AptMetric("Latency", if (onNetwork) wan.latencyMs?.let { "${it.toInt()} ms" } ?: "--" else "--")
            AptMetric("Last test", if (onNetwork) testPair(wan) else "--")
        },
        action = if (onNetwork) {
            {
                // Stays enabled while a test runs so focus is never dropped; the click is ignored instead.
                AptButton(
                    text = if (testing) "Testing…" else "Speed test",
                    icon = AptIconKind.Network,
                    style = AptButtonStyle.Primary,
                    onClick = onSpeedTest,
                )
            }
        } else {
            null
        },
    )
}

private fun testPair(wan: WanStatus): String {
    if (wan.downloadMbps == null && wan.uploadMbps == null) return "--"
    val down = wan.downloadMbps?.toInt()?.toString() ?: "--"
    val up = wan.uploadMbps?.toInt()?.toString() ?: "--"
    return "$down / $up"
}

private data class DeviceRow(val id: String, val name: String, val meta: String, val online: Boolean)

@Composable
private fun NetworkRow(row: DeviceRow, icon: AptIconKind?) {
    AptListRow(
        title = row.name,
        meta = row.meta,
        icon = icon,
        iconTint = if (row.online) AptColors.Secondary else AptColors.Tertiary,
    ) {
        StatusPill(
            text = if (row.online) "Online" else "Offline",
            tone = if (row.online) StatusTone.Online else StatusTone.Offline,
        )
    }
}

private fun mbps(value: Double?): String {
    if (value == null) return "--"
    return if (value >= 100) "${value.toInt()} Mbps" else String.format("%.1f Mbps", value)
}

private fun NetworkDevice.toRow(): DeviceRow {
    val clients = clientCount?.let { "$it clients" }.orEmpty()
    val meta = listOf(kind, model, ip, clients).filter { it.isNotBlank() }.joinToString("  ·  ")
    return DeviceRow(id, name, meta.ifBlank { kind }, online)
}

private fun NetworkClient.toRow(): DeviceRow {
    val speed = when {
        downMbps != null || upMbps != null ->
            "${downMbps?.toInt() ?: 0}↓ / ${upMbps?.toInt() ?: 0}↑ Mbps"
        else -> ""
    }
    val signal = signalDbm?.let { "$it dBm" }.orEmpty()
    val meta = listOf(connection, ssid, accessPoint, signal, speed, ip)
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
        .ifBlank { "Client" }
    return DeviceRow(id, name, meta, online)
}

private fun emptyDashboard() = NetworkDashboard(
    wan = WanStatus(null, "WAN", null, null, null),
    devices = emptyList(),
    clients = emptyList(),
    availability = Availability.NeedsSetup(""),
    detail = "",
)
