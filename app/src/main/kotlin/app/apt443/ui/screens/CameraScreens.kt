package app.apt443.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.navigation.NavHostController
import androidx.tv.material3.Text
import app.apt443.alert.AlertPermissions
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.manualRtsp
import app.apt443.domain.residentGreeting
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.DataSourceMode
import app.apt443.domain.Feed
import app.apt443.domain.ProtectLight
import app.apt443.domain.ProtectSensor
import app.apt443.domain.PtzMove
import app.apt443.domain.StreamQuality
import app.apt443.domain.displayLabel
import app.apt443.ui.Routes
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptChip
import app.apt443.ui.components.AptFocusSurface
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconButton
import app.apt443.ui.components.AptIconKind
import app.apt443.screensaver.SleepPreview
import app.apt443.ui.components.AptSegmented
import app.apt443.ui.components.AptSwitchRow
import app.apt443.ui.components.AvailabilityLine
import app.apt443.ui.components.EmptyState
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.LoadingState
import app.apt443.ui.components.PageHeader
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.rememberAlertPermissions
import app.apt443.ui.player.LiveStreamTile
import app.apt443.ui.player.StreamPhase
import app.apt443.ui.player.StreamPlayback
import app.apt443.ui.player.StreamUrlCache
import app.apt443.ui.player.lower
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptMotion
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import app.apt443.ui.theme.LocalReducedMotion
import app.apt443.ui.theme.aptTween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val HUD_HIDE_DELAY_MS = 4_500L

/** How long an empty camera list counts as "still connecting" before the empty state shows. */
private const val FIRST_LOAD_GRACE_MS = 4_000L

fun TileStatus.tone(): StatusTone = when (this) {
    TileStatus.Live -> StatusTone.Live
    TileStatus.Connecting -> StatusTone.Neutral
    TileStatus.Reconnecting -> StatusTone.Warning
    TileStatus.Snapshots -> StatusTone.Info
    TileStatus.Offline -> StatusTone.Offline
    TileStatus.Unavailable -> StatusTone.Offline
}

// ---------------------------------------------------------------------------------------------
// Live Feed grid
// ---------------------------------------------------------------------------------------------

@Composable
fun CamerasScreen(container: AppContainer, client: OkHttpClient, nav: NavHostController) {
    val settings by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val feed by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val lights by container.protect.lights().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val sensors by container.protect.sensors().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val scope = rememberCoroutineScope()
    val alertPermissions = rememberAlertPermissions()
    val preview = settings.dataSource == DataSourceMode.Demo
    val gridState = rememberLazyGridState()
    val visibleKeys by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
    }
    val needing by container.camerasNeedingRtsp.collectAsStateWithLifecycle()
    val online = feed.items.count { it.online }
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun focusFor(id: String) = tileFocus.getOrPut(id) { FocusRequester() }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(FIRST_LOAD_GRACE_MS)
        settled = true
    }
    val firstLoad = feed.items.isEmpty() && !settled &&
        (feed.availability as? Availability.NeedsSetup)?.message.isNullOrBlank()

    // Coming back from the full-screen viewer lands on the camera that was opened.
    LaunchedEffect(Unit) {
        val target = lastOpened ?: return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            val index = feed.items.indexOfFirst { it.id == target }
            if (index >= 0) {
                if (target !in visibleKeys) gridState.scrollToItem(index + 1)
                withFrameNanos { }
                if (runCatching { focusFor(target).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
            delay(80)
        }
    }

    val sleepOn by SleepPreview.open.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { onEnter = { if (sleepOn) cancelFocusChange() } },
    ) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val columns = if (maxWidth.value >= 700) 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(
                start = AptSpace.focusInset,
                end = AptSpace.focusInset,
                top = AptSpace.xs,
                bottom = AptSpace.xl,
            ),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AptSpace.lg),
            modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(AptSpace.md)) {
                    PageHeader(
                        title = settings.residentGreeting(),
                        subtitle = settings.locationName.ifBlank { null },
                        eyebrow = "Live feed",
                    ) {
                        if (feed.items.isNotEmpty()) {
                            StatusPill(
                                text = "$online of ${feed.items.size} online",
                                tone = when {
                                    online == 0 -> StatusTone.Offline
                                    online < feed.items.size -> StatusTone.Warning
                                    else -> StatusTone.Online
                                },
                            )
                        }
                        if (preview) {
                            AptButton(
                                text = "Preview doorbell",
                                icon = AptIconKind.Bell,
                                onClick = { scope.launch { container.protect.previewAlert() } },
                                style = AptButtonStyle.Secondary,
                            )
                        }
                    }
                    feed.items.filter { it.id in needing }.forEach { camera ->
                        SetupBanner(
                            text = "${camera.name.displayLabel("Camera")} has no Protect stream",
                            detail = "Add an RTSP address. It stays on this TV.",
                            onClick = { nav.navigate(Routes.stream(camera.id)) },
                        )
                    }
                    if (!alertPermissions.alertsReady) {
                        SetupBanner(
                            text = AlertPermissions.statusLine(alertPermissions),
                            detail = "Open Settings, allow alerts, then run Test alert.",
                            onClick = { nav.navigate(Routes.Settings) { launchSingleTop = true } },
                        )
                    }
                    AvailabilityLine(feed.availability)
                    if (lights.items.isNotEmpty() || sensors.items.isNotEmpty()) {
                        HouseholdRow(container, lights.items, sensors.items)
                    }
                    when {
                        firstLoad -> LoadingState(
                            label = "Connecting to cameras",
                            modifier = Modifier.height(280.dp),
                        )
                        feed.items.isEmpty() -> EmptyState(
                            title = "No cameras yet",
                            message = (feed.availability as? Availability.NeedsSetup)?.message?.ifBlank { null }
                                ?: "Cameras appear here as soon as the console is reachable.",
                            icon = AptIconKind.Camera,
                            expand = false,
                            action = {
                                AptButton(
                                    text = "Open Settings",
                                    style = AptButtonStyle.Primary,
                                    icon = AptIconKind.Settings,
                                    onClick = { nav.navigate(Routes.Settings) { launchSingleTop = true } },
                                )
                            },
                        )
                    }
                }
            }
            items(items = feed.items, key = { it.id }) { camera ->
                LiveCameraTile(
                    camera = camera,
                    client = client,
                    container = container,
                    needsStream = camera.id in needing,
                    onOpen = {
                        lastOpened = camera.id
                        nav.navigate(Routes.live(camera.id))
                    },
                    playing = camera.id in visibleKeys && !sleepOn,
                    modifier = Modifier.focusRequester(focusFor(camera.id)),
                )
            }
        }
    }
        AptSwitchRow(
            label = "Sleep screen",
            description = "Show the camera wall on this TV.",
            checked = sleepOn,
            icon = AptIconKind.Camera,
            onToggle = { SleepPreview.open.value = !sleepOn },
            modifier = Modifier.padding(bottom = AptSpace.xs),
        )
    }
}

@Composable
private fun SetupBanner(text: String, detail: String, onClick: () -> Unit) {
    AptFocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        corner = AptRadius.row,
        fill = AptColors.WarningSoft.copy(alpha = 0.10f),
        focusedFill = AptColors.GlassFocused,
        focusScale = AptMotion.focusScaleRow,
        idleBorder = AptColors.Warning.copy(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AptSpace.lg, vertical = AptSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.md),
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(AptColors.WarningSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AptIcon(AptIconKind.Warning, modifier = Modifier.size(AptSize.icon), tint = AptColors.Warning)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AptSpace.xxs)) {
                Text(text, fontSize = AptType.body, fontWeight = FontWeight.SemiBold, color = AptColors.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = AptType.label, color = AptColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AptIcon(AptIconKind.ChevronRight, modifier = Modifier.size(AptSize.iconSmall), tint = AptColors.Secondary)
        }
    }
}

@Composable
private fun LiveCameraTile(
    camera: Camera,
    client: OkHttpClient,
    container: AppContainer,
    needsStream: Boolean,
    onOpen: () -> Unit,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    var phase by remember(camera.id) { mutableStateOf<StreamPhase?>(null) }
    val status = tileStatus(phase, camera.online)
    AptFocusSurface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        corner = AptRadius.tile,
        fill = AptColors.PreviewWell,
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            LiveStreamTile(
                cameraId = camera.id,
                quality = StreamQuality.Low,
                container = container,
                client = client,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                enabled = camera.online,
                playing = playing,
                paused = rememberPausedForAlert(container),
                onPhase = { phase = it },
                placeholder = {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AptSpace.xs),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .border(1.dp, AptColors.Hairline, CircleShape)
                                .background(AptColors.Glass, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            AptIcon(
                                kind = if (camera.isDoorbell) AptIconKind.Bell else AptIconKind.Camera,
                                modifier = Modifier.size(28.dp),
                                tint = AptColors.Tertiary,
                            )
                        }
                        if (!camera.online) {
                            Text("Camera offline", fontSize = AptType.label, color = AptColors.Tertiary)
                        }
                    }
                },
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xB3000000), Color(0xE6000000)),
                        ),
                    )
                    .padding(start = AptSpace.md, end = AptSpace.md, top = AptSpace.xl, bottom = AptSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = camera.name.displayLabel("Camera"),
                        fontSize = AptType.camera,
                        fontWeight = FontWeight.SemiBold,
                        color = AptColors.OnVideo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (needsStream) {
                        Text(
                            "No Protect stream",
                            fontSize = AptType.caption,
                            color = AptColors.Warning,
                            maxLines = 1,
                        )
                    }
                }
                StatusPill(text = status.label, tone = status.tone(), filled = status == TileStatus.Live)
            }
        }
    }
}

@Composable
private fun HouseholdRow(container: AppContainer, lights: List<ProtectLight>, sensors: List<ProtectSensor>) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(AptSpace.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer()
                .focusGroup()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = AptSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lights.forEach { light ->
                key(light.id) {
                    AptChip(
                        text = light.name,
                        selected = light.on,
                        icon = AptIconKind.Light,
                        onClick = {
                            scope.launch {
                                note = container.protect.setLight(light.id, on = !light.on).fold(
                                    onSuccess = { it },
                                    onFailure = { it.message ?: "Could not change the light." },
                                )
                            }
                        },
                    )
                }
            }
            sensors.forEach { sensor ->
                StatusPill(
                    text = "${sensor.name}: ${sensor.status}",
                    tone = if (sensor.alarming) StatusTone.Danger else StatusTone.Neutral,
                )
            }
        }
        InlineMessage(note, StatusTone.Neutral)
    }
}

// ---------------------------------------------------------------------------------------------
// Full-screen viewer
// ---------------------------------------------------------------------------------------------

@Composable
fun LiveScreen(
    container: AppContainer,
    client: OkHttpClient,
    cameraId: String,
    onBack: () -> Unit,
    onAddStream: () -> Unit = {},
) {
    val cameras by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val settings by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val camera = cameras.items.firstOrNull { it.id == cameraId }
    val savedStream = settings.manualRtsp(cameraId)
    val scope = rememberCoroutineScope()
    var quality by remember(cameraId) { mutableStateOf(StreamPlayback.preferredLiveQuality()) }
    var retryNonce by remember(cameraId) { mutableIntStateOf(0) }
    var phase by remember(cameraId) { mutableStateOf<StreamPhase?>(null) }
    var aimNote by remember(cameraId) { mutableStateOf("") }
    var streamMessage by remember(cameraId) { mutableStateOf("") }
    var fallbackNote by remember(cameraId) { mutableStateOf("") }
    var hudVisible by remember { mutableStateOf(true) }
    var activityTick by remember { mutableIntStateOf(0) }
    val backFocus = remember { FocusRequester() }
    val reduced = LocalReducedMotion.current

    fun retryLive() {
        StreamUrlCache.invalidate(cameraId, quality)
        fallbackNote = ""
        streamMessage = ""
        retryNonce++
    }

    LaunchedEffect(cameraId) {
        repeat(3) {
            withFrameNanos { }
            if (runCatching { backFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(activityTick, hudVisible) {
        if (!hudVisible) return@LaunchedEffect
        delay(HUD_HIDE_DELAY_MS)
        hudVisible = false
    }

    val hudAlpha by animateFloatAsState(
        targetValue = if (hudVisible) 1f else 0f,
        animationSpec = aptTween(AptMotion.slow, reduced),
        label = "hud-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AptColors.PreviewWell)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val isBack = event.key == Key.Back || event.key == Key.Escape
                if (isBack) return@onPreviewKeyEvent false
                activityTick++
                if (!hudVisible) {
                    hudVisible = true
                    true
                } else {
                    false
                }
            },
    ) {
        key(retryNonce) {
            LiveStreamTile(
                cameraId = cameraId,
                quality = quality,
                container = container,
                client = client,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                highQualityPoster = true,
                paused = rememberPausedForAlert(container),
                onPhase = {
                    phase = it
                    if (it == StreamPhase.Live) {
                        fallbackNote = ""
                        streamMessage = ""
                    }
                },
                onMessage = { streamMessage = it },
                onDecoderFailure = {
                    val next = quality.lower()
                    if (next != null) {
                        StreamPlayback.rememberDecoderFailure()
                        fallbackNote = "Switching to ${next.label}..."
                        quality = next
                        true
                    } else {
                        fallbackNote = ""
                        false
                    }
                },
                placeholder = {
                    AptIcon(
                        kind = if (camera?.isDoorbell == true) AptIconKind.Bell else AptIconKind.Camera,
                        modifier = Modifier.align(Alignment.Center).size(48.dp),
                        tint = AptColors.Tertiary,
                    )
                },
                overlay = { current ->
                    val note = when (current) {
                        StreamPhase.SnapshotOnly -> "Showing snapshots. Live video is not available on this device."
                        StreamPhase.Failed -> streamMessage
                        StreamPhase.Connecting, StreamPhase.Reconnecting -> fallbackNote
                        StreamPhase.Live -> ""
                    }
                    if (note.isNotBlank()) {
                        Text(
                            text = note,
                            fontSize = AptType.body,
                            color = AptColors.OnVideo,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 132.dp)
                                .background(AptColors.Scrim, RoundedCornerShape(AptRadius.chip))
                                .padding(horizontal = AptSpace.lg, vertical = AptSpace.sm),
                        )
                    }
                },
            )
        }

        // HUD: always composed so focus and key handling stay alive; faded out when idle.
        Column(modifier = Modifier.fillMaxSize().alpha(hudAlpha)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(AptColors.Scrim, Color.Transparent)))
                    .padding(horizontal = AptSpace.overscanX, vertical = AptSpace.overscanY),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AptSpace.md),
            ) {
                AptIconButton(
                    icon = AptIconKind.Back,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backFocus),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = camera?.name ?: "Camera",
                        fontSize = AptType.title,
                        fontWeight = FontWeight.SemiBold,
                        color = AptColors.OnVideo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val model = camera?.model.orEmpty()
                    if (model.isNotBlank()) {
                        Text(model, fontSize = AptType.label, color = AptColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val status = tileStatus(phase, camera?.online ?: true)
                StatusPill(text = status.label, tone = status.tone(), filled = status == TileStatus.Live)
            }
            Box(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, AptColors.Scrim)))
                    .padding(horizontal = AptSpace.overscanX, vertical = AptSpace.overscanY),
                verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                InlineMessage(aimNote, StatusTone.Neutral)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer()
                        .focusGroup()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = AptSpace.xxs, horizontal = AptSpace.xxs),
                    horizontalArrangement = Arrangement.spacedBy(AptSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AptSegmented(
                        options = StreamQuality.entries,
                        selected = quality,
                        label = { it.label },
                        onSelect = { picked ->
                            if (picked == quality) {
                                retryLive()
                            } else {
                                fallbackNote = ""
                                streamMessage = ""
                                quality = picked
                            }
                        },
                    )
                    AptButton(text = "Retry live", icon = AptIconKind.Retry, onClick = { retryLive() })
                    AptButton(
                        text = if (savedStream == null) "Add stream" else "Change stream",
                        icon = AptIconKind.Settings,
                        onClick = onAddStream,
                    )
                    if (camera?.isPtz == true) {
                        PtzCluster(
                            onMove = { move ->
                                scope.launch {
                                    aimNote = container.protect.nudge(cameraId, move).fold(
                                        onSuccess = { it },
                                        onFailure = { it.message ?: "Could not aim the camera." },
                                    )
                                }
                            },
                            onPreset = { slot ->
                                scope.launch {
                                    aimNote = container.protect.gotoPreset(cameraId, slot).fold(
                                        onSuccess = { it },
                                        onFailure = { it.message ?: "Could not aim the camera." },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PtzCluster(onMove: (PtzMove) -> Unit, onPreset: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(1.dp).height(28.dp).background(AptColors.HairlineStrong))
        AptIconButton(AptIconKind.ArrowLeft, "Pan left", onClick = { onMove(PtzMove.Left) })
        AptIconButton(AptIconKind.ArrowUp, "Tilt up", onClick = { onMove(PtzMove.Up) })
        AptIconButton(AptIconKind.ArrowDown, "Tilt down", onClick = { onMove(PtzMove.Down) })
        AptIconButton(AptIconKind.ArrowRight, "Pan right", onClick = { onMove(PtzMove.Right) })
        AptIconButton(AptIconKind.ZoomIn, "Zoom in", onClick = { onMove(PtzMove.ZoomIn) })
        AptIconButton(AptIconKind.ZoomOut, "Zoom out", onClick = { onMove(PtzMove.ZoomOut) })
        AptIconButton(AptIconKind.Home, "Home position", onClick = { onPreset(-1) })
        AptButton(text = "View 1", compact = true, onClick = { onPreset(0) })
        AptButton(text = "View 2", compact = true, onClick = { onPreset(1) })
        AptButton(text = "View 3", compact = true, onClick = { onPreset(2) })
    }
}
