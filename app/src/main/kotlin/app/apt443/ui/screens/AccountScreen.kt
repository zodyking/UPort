package app.apt443.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.domain.AccountGate
import app.apt443.domain.AppSettings
import app.apt443.domain.DataSourceMode
import app.apt443.domain.ConnectionMode
import app.apt443.domain.label
import app.apt443.domain.ResidentAccess
import app.apt443.ui.isDpad
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptFocusSurface
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.PageHeader
import app.apt443.ui.components.SectionLabel
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.aptGlass
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptMotion
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun accessValue(values: List<String>, loaded: Boolean): String {
    val shown = values.take(8)
    val extra = values.size - shown.size
    return when {
        !loaded -> "Checking…"
        values.isEmpty() -> "None listed"
        else -> shown.joinToString(", ") + if (extra > 0) " +$extra" else ""
    }
}

@Composable
fun AccountScreen(container: AppContainer) {
    val saved by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var note by remember { mutableStateOf("") }
    var siteExportOpen by remember { mutableStateOf(false) }
    var accessInfo by remember { mutableStateOf(ResidentAccess()) }
    var accessLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val exportFocus = remember { FocusRequester() }
    val exportButtonFocus = remember { FocusRequester() }
    var exportClosed by remember { mutableStateOf(0) }
    LaunchedEffect(saved.accountGate, saved.identityUserId, saved.accountEmail) {
        if (saved.accountGate != AccountGate.Console) return@LaunchedEffect
        accessLoaded = false
        container.refreshResident()
        val latest = container.settings.first()
        accessInfo = container.access.grantsFor(latest.accountEmail, latest.identityUserId)
        accessLoaded = true
    }
    LaunchedEffect(siteExportOpen) {
        if (!siteExportOpen) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (runCatching { exportFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(exportClosed) {
        if (exportClosed == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { exportButtonFocus.requestFocus() }
    }
    val mode by container.protect.connectionMode.collectAsStateWithLifecycle(initialValue = ConnectionMode.Unconfigured)
    val signedIn = saved.accountGate == AccountGate.Console
    val fullName = listOf(saved.residentFirstName, saved.residentLastName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { saved.accountName }

    Box(modifier = Modifier.fillMaxSize()) {
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
            item(key = "header") {
                PageHeader(
                    title = "My account",
                    eyebrow = "Profile",
                    subtitle = if (signedIn && fullName.isNotBlank()) "Signed in as $fullName" else "This TV",
                ) {
                    StatusPill(
                        text = if (signedIn) "Signed in" else "Signed out",
                        tone = if (signedIn) StatusTone.Online else StatusTone.Offline,
                    )
                }
            }
            item(key = "profile") {
                ProfileCard(
                    name = fullName.ifBlank { "Resident" },
                    secondary = saved.accountName.takeIf { it.isNotBlank() && it != fullName },
                    email = saved.accountEmail.takeIf { signedIn && it.isNotBlank() },
                ) {
                    if (saved.siteConfigured) {
                        AptButton(
                            text = "Download site file",
                            style = AptButtonStyle.Primary,
                            icon = AptIconKind.Footage,
                            onClick = { siteExportOpen = true },
                            modifier = Modifier.focusRequester(exportButtonFocus),
                        )
                    }
                    AptButton(
                        text = if (signedIn) "Sign out" else "Sign in",
                        style = if (signedIn) AptButtonStyle.Secondary else AptButtonStyle.Primary,
                        icon = AptIconKind.Account,
                        onClick = {
                            scope.launch {
                                val current = container.settings.first()
                                container.saveSettings(
                                    current.copy(
                                        accountGate = AccountGate.SignedOut,
                                        dataSource = if (current.siteConfigured) DataSourceMode.Live else DataSourceMode.Demo,
                                        accountName = "",
                                        accountEmail = "",
                                        accountRole = "",
                                        identityUserId = "",
                                        residentFirstName = "",
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            if (note.isNotBlank()) {
                item(key = "note") { InlineMessage(note, StatusTone.Warning) }
            }
            if (signedIn) {
                item(key = "details-label") { SectionLabel("Details") }
                if (saved.accountEmail.isNotBlank()) item(key = "email") { DetailRow("Email", saved.accountEmail) }
                if (saved.residentStatus.isNotBlank()) item(key = "status") { DetailRow("Status", saved.residentStatus) }
                if (saved.accountRole.isNotBlank()) item(key = "role") { DetailRow("Role", saved.accountRole) }
                item(key = "connection") { DetailRow("Connection", mode.label()) }
                item(key = "perm-label") { SectionLabel("Permissions") }
                item(key = "groups") { DetailRow("Groups", accessValue(accessInfo.groups, accessLoaded)) }
                item(key = "policies") { DetailRow("Policies", accessValue(accessInfo.policies, accessLoaded)) }
                item(key = "doors") { DetailRow("Doors", accessValue(accessInfo.doors, accessLoaded)) }
                item(key = "cameras") { DetailRow("Cameras", accessValue(accessInfo.cameras, accessLoaded)) }
                if (accessLoaded && accessInfo.detail.isNotBlank()) {
                    item(key = "perm-detail") {
                        Text(
                            accessInfo.detail,
                            fontSize = AptType.body,
                            color = AptColors.Secondary,
                            modifier = Modifier.padding(horizontal = AptSpace.xs),
                        )
                    }
                }
            }
        }
        if (siteExportOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(exportFocus)
                    // The overlay covers the page; keep the remote inside it until it closes.
                    .focusProperties { onExit = { if (requestedFocusDirection.isDpad()) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                SiteExportOverlay(
                    container = container,
                    siteName = saved.locationName.ifBlank { "UPort" },
                    onDismiss = {
                        siteExportOpen = false
                        exportClosed++
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    name: String,
    secondary: String?,
    email: String?,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .aptGlass(corner = AptRadius.sheet, fill = AptColors.GlassRaised)
            .padding(horizontal = AptSpace.xl, vertical = AptSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AptSpace.lg),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .border(1.dp, AptColors.HairlineStrong, CircleShape)
                .background(AptColors.GlassFocused, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val initial = name.firstOrNull { it.isLetter() }?.uppercaseChar()
            if (initial != null) {
                Text(
                    initial.toString(),
                    fontSize = AptType.title,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnBackground,
                    textAlign = TextAlign.Center,
                )
            } else {
                AptIcon(AptIconKind.Account, modifier = Modifier.size(32.dp), tint = AptColors.Secondary)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                fontSize = AptType.title,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(secondary, fontSize = AptType.body, color = AptColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (email != null) {
                Text(email, fontSize = AptType.label, color = AptColors.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            modifier = Modifier.focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}

/** Read-only label/value row. Focusable so the remote can scroll through long permission lists. */
@Composable
private fun DetailRow(label: String, value: String) {
    AptFocusSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        corner = AptRadius.row,
        fill = AptColors.Glass,
        focusedFill = AptColors.GlassFocused,
        focusScale = AptMotion.focusScaleRow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = AptSpace.lg, vertical = AptSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.lg),
        ) {
            Text(label, fontSize = AptType.body, color = AptColors.Secondary, modifier = Modifier.widthIn(min = 120.dp))
            Text(
                value,
                fontSize = AptType.body,
                fontWeight = FontWeight.Medium,
                color = AptColors.OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
