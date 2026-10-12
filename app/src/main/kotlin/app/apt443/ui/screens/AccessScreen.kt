package app.apt443.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.domain.AccessDevice
import app.apt443.domain.AccessEvent
import app.apt443.domain.AccessFeed
import app.apt443.domain.Availability
import app.apt443.domain.ConnectionMode
import app.apt443.domain.Door
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptCard
import app.apt443.ui.components.AptHero
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.AptListRow
import app.apt443.ui.components.AptMetric
import app.apt443.ui.components.EmptyState
import app.apt443.ui.components.InlineEmpty
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.SectionLabel
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.shortTime
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.launch

@Composable
fun AccessScreen(container: AppContainer, onOpenRemote: () -> Unit = {}) {
    val mode by container.protect.connectionMode.collectAsStateWithLifecycle(initialValue = ConnectionMode.Unconfigured)
    val feed by container.access.feed().collectAsStateWithLifecycle(initialValue = emptyAccess())
    val onNetwork = mode == ConnectionMode.OnNetwork
    val canUnlock = onNetwork && feed.unlockAvailability is Availability.Ready
    var pending by remember { mutableStateOf<Door?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val readers = feed.devices
    val locked = feed.doors.count { it.locked == true }
    val listState = rememberLazyListState()
    val doorFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun doorFocusFor(id: String) = doorFocus.getOrPut(id) { FocusRequester() }

    fun closeConfirm() {
        returnTo = pending?.id
        pending = null
    }

    // The confirm card replaces the list, so focus goes back to the door that was picked.
    LaunchedEffect(returnTo, pending) {
        val target = returnTo ?: return@LaunchedEffect
        if (pending != null) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (runCatching { doorFocusFor(target).requestFocus() }.getOrDefault(false)) {
                returnTo = null
                return@LaunchedEffect
            }
        }
        returnTo = null
    }

    BackHandler(enabled = pending != null) { closeConfirm() }
    val door = pending
    if (door != null) {
        ConfirmUnlock(
            door = door,
            onConfirm = {
                scope.launch {
                    val sent = container.access.unlock(door.id, confirmed = true)
                    resultOk = sent.isSuccess
                    result = sent.fold(onSuccess = { it }, onFailure = { it.message ?: "Unlock failed" })
                    closeConfirm()
                }
            },
            onCancel = { closeConfirm() },
        )
        return
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
            AccessHero(
                doorCount = feed.doors.size,
                locked = locked,
                readersOnline = readers.count { it.online },
                recent = feed.events.size,
                onNetwork = onNetwork,
            )
        }
        result?.let {
            item(key = "result") {
                InlineMessage(it, if (resultOk) StatusTone.Online else StatusTone.Warning)
            }
        }
        if (!onNetwork) {
            item(key = "away") {
                EmptyState(
                    title = "Home network only",
                    message = "Doors, readers, and unlock need this TV on the same network as the console.",
                    icon = AptIconKind.Door,
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
        if (feed.detail.isNotBlank()) {
            item(key = "detail") { InlineMessage(feed.detail, StatusTone.Warning) }
        }
        if (feed.doors.isEmpty() && readers.isEmpty() && feed.events.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "No doors yet",
                    message = (feed.availability as? Availability.NeedsSetup)?.message?.ifBlank { null }
                        ?: "Doors and readers appear when the console answers.",
                    icon = AptIconKind.Door,
                    expand = false,
                    action = {
                        AptButton(text = "Check connection", icon = AptIconKind.Remote, onClick = onOpenRemote)
                    },
                )
            }
            return@LazyColumn
        }
        item(key = "door-label") { SectionLabel("Doors") }
        if (feed.doors.isEmpty()) {
            item(key = "door-empty") { InlineEmpty("No doors loaded.", icon = AptIconKind.Door) }
        } else {
            items(feed.doors, key = { "door-${it.id}" }) { item ->
                DoorRow(item, canUnlock, Modifier.focusRequester(doorFocusFor(item.id))) {
                    result = null
                    if (canUnlock) pending = item
                }
            }
        }
        if (readers.isNotEmpty()) {
            item(key = "reader-label") { SectionLabel("Readers") }
            items(readers, key = { "reader-${it.id}" }) { device -> ReaderRow(device) }
        }
        item(key = "recent-label") { SectionLabel("Recent") }
        if (feed.events.isEmpty()) {
            item(key = "recent-empty") { InlineEmpty("No recent activity.") }
        } else {
            items(feed.events, key = { "event-${it.id}" }) { event -> EventRow(event) }
        }
    }
}

@Composable
private fun AccessHero(
    doorCount: Int,
    locked: Int,
    readersOnline: Int,
    recent: Int,
    onNetwork: Boolean,
) {
    val tone = when {
        !onNetwork -> StatusTone.Warning
        doorCount > 0 && locked == doorCount -> StatusTone.Online
        doorCount > 0 -> StatusTone.Warning
        else -> StatusTone.Neutral
    }
    val state = when {
        !onNetwork -> "Home only"
        doorCount == 0 -> "Checking"
        locked == doorCount -> "Locked"
        else -> "Open"
    }
    AptHero(
        eyebrow = "Access",
        title = if (onNetwork) "Doors" else "On the home network",
        badges = { StatusPill(state, tone) },
        metrics = {
            AptMetric("Doors", if (onNetwork) doorCount.toString() else "--")
            AptMetric("Locked", if (onNetwork && doorCount > 0) "$locked" else "--")
            AptMetric("Readers", if (onNetwork) readersOnline.toString() else "--")
            AptMetric("Recent", if (onNetwork) recent.toString() else "--")
        },
    )
}

@Composable
private fun ReaderRow(device: AccessDevice) {
    AptListRow(title = device.name, meta = device.kind, icon = AptIconKind.Sensor) {
        StatusPill(
            text = if (device.online) "Online" else "Offline",
            tone = if (device.online) StatusTone.Online else StatusTone.Offline,
        )
    }
}

@Composable
private fun EventRow(event: AccessEvent) {
    AptListRow(
        title = event.result,
        meta = listOf(event.doorName, event.actor).filter { it.isNotBlank() }.joinToString("  ·  "),
    ) {
        Text(event.epochMs.shortTime(), fontSize = AptType.label, color = AptColors.Tertiary)
    }
}

@Composable
private fun ConfirmUnlock(door: Door, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(door.id) {
        repeat(3) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AptCard(
            modifier = Modifier.widthIn(max = 560.dp).focusGroup(),
            fill = AptColors.SurfaceRaised,
            corner = AptRadius.sheet,
            padding = PaddingValues(AptSpace.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AptSpace.md)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, AptColors.Warning.copy(alpha = 0.3f), CircleShape)
                        .background(AptColors.WarningSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AptIcon(AptIconKind.Lock, modifier = Modifier.size(AptSize.icon + 2.dp), tint = AptColors.Warning)
                }
                Text("Unlock ${door.name}?", fontSize = AptType.title, fontWeight = FontWeight.SemiBold, color = AptColors.OnBackground)
            }
            Text(
                "This sends a momentary unlock. Press Confirm, or Back to cancel.",
                fontSize = AptType.body,
                color = AptColors.Secondary,
                modifier = Modifier.padding(top = AptSpace.xs, bottom = AptSpace.sm),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                AptButton(
                    text = "Confirm unlock",
                    onClick = onConfirm,
                    style = AptButtonStyle.Danger,
                    icon = AptIconKind.Lock,
                    modifier = Modifier.focusRequester(focus),
                )
                AptButton(text = "Cancel", onClick = onCancel, style = AptButtonStyle.Secondary)
            }
        }
    }
}

/**
 * The whole row is the focus target, so Up/Down move door to door instead of zig-zagging
 * between a row and a separate button. Select opens the confirm card when unlock is allowed.
 */
@Composable
private fun DoorRow(door: Door, canUnlock: Boolean, modifier: Modifier = Modifier, onUnlock: () -> Unit) {
    val lock = when (door.locked) {
        true -> "Locked"
        false -> "Unlocked"
        null -> "Unknown"
    }
    val tone = when (door.locked) {
        true -> StatusTone.Online
        false -> StatusTone.Warning
        null -> StatusTone.Offline
    }
    AptListRow(
        title = door.name,
        meta = door.position.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            ?: if (canUnlock) "Select to unlock" else "Unlock needs the home network",
        icon = AptIconKind.Door,
        modifier = modifier,
        onClick = onUnlock,
    ) {
        StatusPill(lock, tone)
        if (canUnlock) {
            Row(
                modifier = Modifier
                    .background(AptColors.PrimarySoft, CircleShape)
                    .padding(horizontal = AptSpace.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AptIcon(AptIconKind.Lock, modifier = Modifier.size(14.dp), tint = AptColors.Primary)
                Text("Unlock", fontSize = AptType.label, fontWeight = FontWeight.SemiBold, color = AptColors.Primary)
            }
        }
    }
}

private fun emptyAccess() = AccessFeed(
    doors = emptyList(),
    events = emptyList(),
    availability = Availability.NeedsSetup(""),
    unlockAvailability = Availability.HomeNetworkOnly(""),
    detail = "",
)
