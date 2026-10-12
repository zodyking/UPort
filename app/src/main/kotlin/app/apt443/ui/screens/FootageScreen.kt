package app.apt443.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.alert.AlertController
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.Feed
import app.apt443.domain.FootageClip
import app.apt443.domain.PlaybackOffer
import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType
import app.apt443.domain.detectionTypes
import app.apt443.domain.displayLabel
import app.apt443.domain.headline
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptCard
import app.apt443.ui.components.AptChip
import app.apt443.ui.components.AptDropdown
import app.apt443.ui.components.AptSwitchRow
import app.apt443.ui.components.AptFocusSurface
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.AptStepper
import app.apt443.ui.components.AvailabilityLine
import app.apt443.ui.components.EmptyState
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.LoadingState
import app.apt443.ui.components.PageHeader
import app.apt443.ui.components.SectionLabel
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.aptPanel
import app.apt443.ui.player.FittedStream
import app.apt443.ui.player.StreamFailure
import app.apt443.ui.player.StreamPosterLayer
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** True while the user asked playback to stop and at least one alert popup is open. */
@Composable
fun rememberPausedForAlert(container: AppContainer): Boolean {
    val settings by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val slots by AlertController.slots.collectAsStateWithLifecycle()
    return settings.pausePlaybackOnAlert && slots.isNotEmpty()
}

private enum class OpenMenu { Time, Events, Cameras, Sort }

private const val CUSTOM_RANGE = "Custom date and time…"

@Composable
fun FootageScreen(container: AppContainer, client: OkHttpClient) {
    val cameras by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    var filters by remember { mutableStateOf(ClipFilters()) }
    var rangeEditor by remember { mutableStateOf<ClipDateRange?>(null) }
    var openMenu by remember { mutableStateOf<OpenMenu?>(null) }
    var playback by remember { mutableStateOf<PlaybackOffer?>(null) }
    var playingEvent by remember { mutableStateOf<ProtectEvent?>(null) }
    var prepareProgress by remember { mutableStateOf<Float?>(null) }
    var prepareJob by remember { mutableStateOf<Job?>(null) }
    var footage by remember { mutableStateOf(Feed(emptyList<ProtectEvent>(), Availability.NeedsSetup(""))) }
    var loading by remember { mutableStateOf(true) }
    var reloadNonce by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun tileFocusFor(id: String) = tileFocus.getOrPut(id) { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val timeMenuFocus = remember { FocusRequester() }
    val quickPickFocus = remember { FocusRequester() }
    var returnToClip by remember { mutableStateOf<String?>(null) }
    var returnToFilters by remember { mutableStateOf(0) }

    LaunchedEffect(filters.window, filters.cameraId, filters.kind, reloadNonce) {
        loading = true
        footage = container.protect.footage(filters.toQuery(System.currentTimeMillis()))
        loading = false
    }
    var stills by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var missedStills by remember { mutableStateOf(setOf<String>()) }
    val shown = remember(footage.items, filters) { filters.apply(footage.items) }
    val shownKey = remember(shown) { shown.joinToString { it.id } }
    LaunchedEffect(shownKey) {
        missedStills = emptySet()
    }
    val cameraLabel = cameras.items.firstOrNull { it.id == filters.cameraId }?.name ?: "All cameras"
    val current = playback
    val viewing = current != null && !current.url.isNullOrBlank()
    val preparing = prepareJob != null

    fun closeViewer() {
        returnToClip = playingEvent?.id
        prepareJob?.cancel()
        prepareJob = null
        prepareProgress = null
        playback = null
        playingEvent = null
    }

    BackHandler(enabled = viewing || preparing) { closeViewer() }

    val inViewer = viewing || preparing
    // The viewer replaces the grid, so focus is handed over explicitly in both directions.
    LaunchedEffect(inViewer) {
        if (!inViewer) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { closeFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(returnToClip, inViewer) {
        val target = returnToClip ?: return@LaunchedEffect
        if (inViewer) return@LaunchedEffect
        repeat(6) {
            withFrameNanos { }
            val index = shown.indexOfFirst { it.id == target }
            if (index < 0) return@repeat
            if (gridState.layoutInfo.visibleItemsInfo.none { it.key == target }) gridState.scrollToItem(index)
            withFrameNanos { }
            if (runCatching { tileFocusFor(target).requestFocus() }.getOrDefault(false)) {
                returnToClip = null
                return@LaunchedEffect
            }
        }
        returnToClip = null
    }
    LaunchedEffect(returnToFilters) {
        if (returnToFilters == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { timeMenuFocus.requestFocus() }
    }
    LaunchedEffect(rangeEditor != null) {
        if (rangeEditor == null) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { quickPickFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
        verticalArrangement = Arrangement.spacedBy(AptSpace.md),
    ) {
        if (viewing || preparing) {
            val clip = playingEvent
            PageHeader(
                title = clip?.cameraName?.displayLabel("Camera") ?: "Recording",
                eyebrow = "Playback",
                subtitle = listOfNotNull(
                    clip?.let { it.detectionTypes().headline() },
                    clip?.takeIf { it.startEpochMs > 0L }?.startEpochMs?.clipStamp(),
                ).joinToString("  ·  "),
            ) {
                AptButton(
                    text = "Close",
                    icon = AptIconKind.Close,
                    style = AptButtonStyle.Secondary,
                    onClick = ::closeViewer,
                    modifier = Modifier.focusRequester(closeFocus),
                )
            }
            val frame = Modifier
                .fillMaxWidth()
                .weight(1f)
                .fillMaxHeight()
                .padding(bottom = AptSpace.xs)
                .border(1.dp, AptColors.Hairline, RoundedCornerShape(AptRadius.card))
                .clip(RoundedCornerShape(AptRadius.card))
            val poster = clip?.id?.let { stills[it] }
            LaunchedEffect(clip?.id, clip?.thumbnailId) {
                val event = clip ?: return@LaunchedEffect
                if (stills[event.id] != null) return@LaunchedEffect
                val bytes = container.protect.eventStill(event.id, event.thumbnailId) ?: return@LaunchedEffect
                decodeStill(bytes)?.let { image ->
                    stills = stills + (event.id to image)
                }
            }
            if (preparing) {
                ClipPreparing(poster = poster, progress = prepareProgress, modifier = frame)
            } else {
                PlaybackViewer(
                    url = current?.url,
                    client = client,
                    poster = poster,
                    modifier = frame,
                    paused = rememberPausedForAlert(container),
                    onError = { message -> playback = current?.copy(url = null, message = message) },
                )
            }
            return@Column
        }

        PageHeader(title = "Playback", subtitle = "Recorded events from your cameras", eyebrow = "Recordings") {
            if (!loading) {
                StatusPill(text = "${shown.size} clips", tone = StatusTone.Neutral)
            }
        }
        FilterMenus(
            filters = filters,
            cameraLabel = cameraLabel,
            cameras = cameras.items,
            timeMenuFocus = timeMenuFocus,
            openMenu = openMenu,
            onOpen = { openMenu = if (openMenu == it) null else it },
            onFilters = {
                filters = it
                openMenu = null
            },
            onCustomRange = {
                rangeEditor = (filters.window as? ClipWindow.Range)?.range
                    ?: defaultClipRange(LocalDate.now())
                openMenu = null
            },
        )
        rangeEditor?.let { editing ->
            ClipRangeEditor(
                range = editing,
                firstFocus = quickPickFocus,
                onChange = { rangeEditor = it },
                onApply = {
                    filters = filters.copy(window = ClipWindow.Range(it.normalized()))
                    rangeEditor = null
                    returnToFilters++
                },
                onCancel = {
                    rangeEditor = null
                    returnToFilters++
                },
            )
        }
        AvailabilityLine(footage.availability)
        if (current != null && current.url.isNullOrBlank()) {
            InlineMessage(current.message, StatusTone.Warning)
        }
        when {
            loading && shown.isEmpty() -> LoadingState("Looking for clips")
            !loading && shown.isEmpty() -> EmptyState(
                title = "No clips match these filters",
                message = "Try a longer time window or a different camera.",
                icon = AptIconKind.Footage,
                action = {
                    AptButton(
                        text = "Refresh",
                        icon = AptIconKind.Retry,
                        style = AptButtonStyle.Primary,
                        onClick = { reloadNonce++ },
                    )
                },
            )
            else -> BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val columns = gridColumnsFor(maxWidth.value.toInt()).coerceAtLeast(3)
                LaunchedEffect(gridState, shownKey, columns) {
                    snapshotFlow {
                        val visible = gridState.layoutInfo.visibleItemsInfo
                        if (visible.isEmpty()) {
                            visibleClipIndexes(0, columns * 2 - 1, shown.size, 0)
                        } else {
                            visibleClipIndexes(
                                first = visible.minOf { it.index },
                                last = visible.maxOf { it.index },
                                total = shown.size,
                                extra = columns,
                            )
                        }
                    }
                        .distinctUntilChanged()
                        .collectLatest { indexes ->
                            for (index in indexes) {
                                val event = shown.getOrNull(index) ?: continue
                                if (event.id in stills || event.id in missedStills) continue
                                val bytes = container.protect.eventStill(event.id, event.thumbnailId)
                                val image = bytes?.let { decodeStill(it) }
                                if (image == null) {
                                    missedStills = missedStills + event.id
                                } else {
                                    val window = indexes.mapNotNull { shown.getOrNull(it)?.id }.toSet()
                                    val next = stills + (event.id to image)
                                    stills = if (next.size <= 36) next else next.filterKeys { it in window }
                                }
                            }
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = AptSpace.focusInset,
                        end = AptSpace.focusInset,
                        top = AptSpace.focusInset,
                        bottom = AptSpace.xl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AptSpace.gutter),
                    verticalArrangement = Arrangement.spacedBy(AptSpace.gutter),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer()
                        .focusGroup(),
                ) {
                    items(shown, key = { it.id }) { event ->
                        ClipTile(event, stills[event.id], Modifier.focusRequester(tileFocusFor(event.id))) {
                            if (event.cameraId.isNullOrBlank() || event.startEpochMs <= 0L) return@ClipTile
                            if (prepareJob != null) return@ClipTile
                            val clip = event.toClip()
                            playingEvent = event
                            playback = null
                            prepareProgress = null
                            prepareJob = scope.launch {
                                try {
                                    playback = container.protect.playback(clip) { prepareProgress = it }
                                } finally {
                                    prepareJob = null
                                    prepareProgress = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterMenus(
    filters: ClipFilters,
    cameraLabel: String,
    cameras: List<Camera>,
    timeMenuFocus: FocusRequester,
    openMenu: OpenMenu?,
    onOpen: (OpenMenu) -> Unit,
    onFilters: (ClipFilters) -> Unit,
    onCustomRange: () -> Unit,
) {
    val presetLabels = ClipPreset.entries.map { it.label }
    val timeValue = filters.window.label()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRestorer()
            .focusGroup()
            .padding(horizontal = AptSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AptDropdown(
            modifier = Modifier.focusRequester(timeMenuFocus),
            title = "Time",
            value = timeValue,
            options = presetLabels,
            expanded = openMenu == OpenMenu.Time,
            onToggle = { onOpen(OpenMenu.Time) },
            onSelect = { label ->
                ClipPreset.entries.firstOrNull { it.label == label }?.let {
                    onFilters(filters.copy(window = ClipWindow.Preset(it)))
                }
            },
            footer = {
                AptFocusSurface(
                    onClick = onCustomRange,
                    scale = false,
                    corner = AptRadius.button,
                    fill = AptColors.Glass,
                    focusedFill = AptColors.GlassFocused,
                    selected = filters.window is ClipWindow.Range,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AptSize.menuRow)
                            .padding(horizontal = AptSpace.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
                    ) {
                        AptIcon(
                            AptIconKind.Footage,
                            modifier = Modifier.size(AptSize.iconSmall),
                            tint = AptColors.Primary,
                        )
                        Text(
                            text = CUSTOM_RANGE,
                            fontSize = AptType.body,
                            fontWeight = FontWeight.Medium,
                            color = AptColors.OnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        )
        AptDropdown(
            title = "Events",
            value = filters.kind,
            options = CLIP_EVENT_KINDS,
            expanded = openMenu == OpenMenu.Events,
            onToggle = { onOpen(OpenMenu.Events) },
            onSelect = { onFilters(filters.copy(kind = it)) },
        )
        AptDropdown(
            title = "Camera",
            value = cameraLabel,
            options = listOf(ALL_CAMERAS) + cameras.map { it.name },
            expanded = openMenu == OpenMenu.Cameras,
            onToggle = { onOpen(OpenMenu.Cameras) },
            onSelect = { label ->
                val id = if (label == ALL_CAMERAS) null else cameras.firstOrNull { it.name == label }?.id
                onFilters(filters.copy(cameraId = id))
            },
        )
        AptDropdown(
            title = "Sort",
            value = filters.sort.label,
            options = ClipSort.entries.map { it.label },
            expanded = openMenu == OpenMenu.Sort,
            onToggle = { onOpen(OpenMenu.Sort) },
            onSelect = { label ->
                ClipSort.entries.firstOrNull { it.label == label }?.let { onFilters(filters.copy(sort = it)) }
            },
            valueWidth = 140.dp,
        )
    }
}

/** Date-only picker with optional hours filter hidden until the viewer asks for it. */
@Composable
private fun ClipRangeEditor(
    range: ClipDateRange,
    firstFocus: FocusRequester,
    onChange: (ClipDateRange) -> Unit,
    onApply: (ClipDateRange) -> Unit,
    onCancel: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var hoursOnly by remember(range) { mutableStateOf(!range.wholeDay) }
    AptCard(
        modifier = Modifier.fillMaxWidth().focusGroup(),
        title = "Pick dates",
        subtitle = "Choose which days of footage to show in the list below.",
        fill = AptColors.GlassRaised,
        corner = AptRadius.sheet,
    ) {
        Text(
            text = range.summaryLabel(),
            fontSize = AptType.body,
            fontWeight = FontWeight.Medium,
            color = AptColors.OnBackground,
        )
        Row(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
        ) {
            DateQuickPick.entries.forEachIndexed { index, pick ->
                AptChip(
                    text = pick.label,
                    selected = range == quickClipRange(pick, today),
                    onClick = { onChange(quickClipRange(pick, today)) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.lg),
            verticalAlignment = Alignment.Bottom,
        ) {
            DateFields(
                caption = "From",
                date = range.from,
                today = today,
                onDate = { onChange(range.copy(from = it)) },
            )
            DateFields(
                caption = "To",
                date = range.to,
                today = today,
                onDate = { onChange(range.copy(to = it)) },
            )
        }
        AptSwitchRow(
            label = "Only show events between certain hours",
            description = "Off means the whole day on each date above",
            checked = hoursOnly,
            onToggle = {
                hoursOnly = !hoursOnly
                if (!hoursOnly) {
                    onChange(range.copy(dayStart = LocalTime.MIN, dayEnd = LocalTime.MAX))
                }
            },
        )
        if (hoursOnly) {
            Row(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(AptSpace.lg),
                verticalAlignment = Alignment.Bottom,
            ) {
                TimeFields(
                    caption = "From",
                    time = range.dayStart,
                    onTime = { onChange(range.copy(dayStart = it)) },
                )
                TimeFields(
                    caption = "Until",
                    time = range.dayEnd,
                    onTime = { onChange(range.copy(dayEnd = it)) },
                )
            }
            if (range.wrapsMidnight) {
                InlineMessage("This window runs overnight, past midnight.", StatusTone.Info)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            AptButton(
                text = "Apply",
                icon = AptIconKind.Check,
                style = AptButtonStyle.Primary,
                onClick = { onApply(range.normalized()) },
            )
            AptButton(
                text = "Cancel",
                icon = AptIconKind.Close,
                style = AptButtonStyle.Ghost,
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun DateFields(caption: String, date: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AptSpace.xs)) {
        SectionLabel(caption)
        Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            AptStepper(
                label = DateField.Month.label,
                value = date.format(MONTH_LABEL),
                onStep = { onDate(shiftDate(date, DateField.Month, it, today)) },
            )
            AptStepper(
                label = DateField.Day.label,
                value = date.dayOfMonth.toString(),
                onStep = { onDate(shiftDate(date, DateField.Day, it, today)) },
            )
            AptStepper(
                label = DateField.Year.label,
                value = date.year.toString(),
                onStep = { onDate(shiftDate(date, DateField.Year, it, today)) },
            )
        }
    }
}

@Composable
private fun TimeFields(caption: String, time: LocalTime, onTime: (LocalTime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AptSpace.xs)) {
        SectionLabel(caption)
        Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            AptStepper(
                label = TimeField.Hour.label,
                value = time.format(HOUR_LABEL),
                onStep = { onTime(shiftTime(time, TimeField.Hour, it)) },
            )
            AptStepper(
                label = TimeField.Minute.label,
                value = time.format(MINUTE_LABEL),
                onStep = { onTime(shiftTime(time, TimeField.Minute, it)) },
            )
        }
    }
}

/** Shown while the console export is downloading, because clips play from local storage. */
@Composable
private fun ClipPreparing(poster: ImageBitmap?, progress: Float?, modifier: Modifier = Modifier) {
    val percent = progress?.takeIf { it >= 0f }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
    Box(modifier = modifier.background(AptColors.PreviewWell), contentAlignment = Alignment.Center) {
        StreamPosterLayer(poster = poster, spinning = true, modifier = Modifier.fillMaxSize())
        VideoNote(
            text = "Preparing recording$percent",
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlaybackViewer(
    url: String?,
    client: OkHttpClient,
    poster: ImageBitmap?,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onError: (String) -> Unit,
) {
    var snapshotNote by remember(url) { mutableStateOf<String?>(null) }
    var playing by remember(url) { mutableStateOf(false) }
    val note = snapshotNote
    Box(modifier = modifier.background(AptColors.PreviewWell), contentAlignment = Alignment.Center) {
        if (note != null && poster != null) {
            StreamPosterLayer(poster = poster, spinning = false, modifier = Modifier.fillMaxSize())
            VideoNote(text = note, modifier = Modifier.align(Alignment.BottomCenter))
        } else {
            FittedStream(
                url = url,
                client = client,
                modifier = Modifier.fillMaxSize(),
                paused = paused,
                onPlaying = { playing = true },
                onError = { message, failure ->
                    // Only a real codec limit falls back to a still. Everything else is
                    // something the viewer can act on, so it is reported as itself.
                    if (failure == StreamFailure.Decoder && poster != null) {
                        snapshotNote =
                            "This camera records in a format this TV cannot decode. Showing the latest snapshot."
                    } else {
                        onError(message)
                    }
                },
            )
            if (!playing) {
                StreamPosterLayer(poster = poster, spinning = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun VideoNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = AptType.body,
        color = AptColors.OnVideo,
        modifier = modifier
            .padding(AptSpace.md)
            .background(AptColors.Scrim, RoundedCornerShape(AptRadius.chip))
            .padding(horizontal = AptSpace.lg, vertical = AptSpace.sm),
    )
}

private fun ProtectEvent.badge(): Pair<String, StatusTone> {
    val label = when (type) {
        ProtectEventType.Ring -> "Ring"
        ProtectEventType.Motion -> "Motion"
        ProtectEventType.SmartDetect -> smartLabels.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Smart"
        ProtectEventType.Sensor -> "Sensor"
        ProtectEventType.Disconnect -> "Offline"
        ProtectEventType.Other -> "Event"
    }
    val tone = when (type) {
        ProtectEventType.Ring -> StatusTone.Warning
        ProtectEventType.SmartDetect -> StatusTone.Info
        ProtectEventType.Disconnect -> StatusTone.Danger
        else -> StatusTone.Neutral
    }
    return label to tone
}

private suspend fun decodeStill(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

@Composable
private fun ClipTile(event: ProtectEvent, still: ImageBitmap?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val playable = !event.cameraId.isNullOrBlank() && event.startEpochMs > 0L
    val (badge, tone) = remember(event.id) { event.badge() }
    AptFocusSurface(
        onClick = if (playable) onOpen else ({ }),
        modifier = modifier.fillMaxWidth(),
        corner = AptRadius.tile,
        fill = AptColors.Glass,
        focusedFill = AptColors.GlassFocused,
        enabled = playable,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(AptColors.PreviewWell),
            ) {
                if (still != null) {
                    Image(
                        bitmap = still,
                        contentDescription = event.summary,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    AptIcon(
                        AptIconKind.Camera,
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                        tint = AptColors.Tertiary,
                    )
                }
                StatusPill(
                    text = badge,
                    tone = tone,
                    filled = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(AptSpace.xs),
                )
                if (playable) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(44.dp)
                            .border(1.dp, AptColors.HairlineStrong, CircleShape)
                            .background(AptColors.ScrimSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        AptIcon(AptIconKind.Play, modifier = Modifier.size(18.dp), tint = AptColors.OnVideo)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = AptSpace.sm + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
            ) {
                Text(
                    event.cameraName.displayLabel("Camera"),
                    fontSize = AptType.label,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = event.startEpochMs.clipStamp(),
                    fontSize = AptType.caption,
                    color = AptColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun ProtectEvent.toClip() = FootageClip(
    id = id,
    cameraId = cameraId.orEmpty(),
    label = summary,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    kind = type.name,
)

private fun Long.clipStamp(): String {
    if (this <= 0L) return ""
    val stamp = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    return stamp.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
}
