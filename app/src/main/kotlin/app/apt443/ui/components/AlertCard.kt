package app.apt443.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.apt443.alert.AlertController
import app.apt443.alert.QuietPromptController
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Text
import app.apt443.domain.AlertCorner
import app.apt443.domain.AlertInset
import app.apt443.domain.AlertKind
import app.apt443.domain.DetectionType
import app.apt443.domain.PopupFrame
import app.apt443.domain.PopupLayout
import app.apt443.domain.PopupSize
import app.apt443.domain.PopupSlot
import app.apt443.domain.QuietPrompt
import app.apt443.domain.SpeakerPhrase
import app.apt443.domain.defaultCameraPhrases
import app.apt443.domain.defaultDoorPhrases
import app.apt443.domain.headline
import app.apt443.ui.player.FittedStream
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Composable
fun BoxScope.AlertInsetHost(
    corner: AlertCorner = AlertCorner.TopRight,
    fill: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (fill) {
        Box(modifier = Modifier.fillMaxSize(), content = content)
        return
    }
    Box(
        modifier = Modifier
            .align(corner.alignment())
            .padding(corner.insetPadding())
            .wrapContentHeight(),
        content = content,
    )
}

/** One row of popups, newest nearest the chosen corner, sized so two fit on a TV. */
@Composable
fun AlertPopupGroup(
    slots: List<PopupSlot>,
    prompt: QuietPrompt?,
    corner: AlertCorner,
    compact: Boolean,
    client: OkHttpClient,
    onAnnounce: suspend (cameraId: String, message: String) -> Result<String>,
    phrasesFor: (forDoor: Boolean) -> List<SpeakerPhrase>,
    fill: Boolean = false,
    size: PopupSize = PopupSize.Small,
    minimal: Boolean = false,
    onGroupSize: (widthDp: Float, heightDp: Float) -> Unit = { _, _ -> },
) {
    val metrics = LocalContext.current.resources.displayMetrics
    val panes = (slots.size + if (prompt != null) 1 else 0).coerceAtLeast(1)
    val cardWidth = if (fill) {
        null
    } else {
        PopupLayout.cardWidthDp(
            screenWidthDp = metrics.widthPixels / metrics.density,
            screenHeightDp = metrics.heightPixels / metrics.density,
            panes = panes,
            compact = compact,
            size = size,
        ).dp
    }
    val newestAtStart = corner == AlertCorner.TopLeft || corner == AlertCorner.BottomLeft
    val ordered = if (newestAtStart) slots.asReversed() else slots
    val groupFocus = remember { FocusRequester() }
    var groupHeld by remember { mutableStateOf(false) }
    var groupSeen by remember { mutableStateOf(false) }
    LaunchedEffect(groupHeld) {
        if (groupHeld) groupSeen = true
    }
    LaunchedEffect(groupHeld, groupSeen, prompt) {
        if (!groupSeen || groupHeld || prompt != null) return@LaunchedEffect
        delay(32)
        runCatching { groupFocus.requestFocus() }
    }
    val aspects = remember { mutableStateMapOf<String, Float>() }
    val pictureOnly = minimal && !compact
    val screenWidthDp = metrics.widthPixels / metrics.density
    val screenHeightDp = metrics.heightPixels / metrics.density
    val minimalFrames: Map<String, PopupFrame> = if (pictureOnly && cardWidth != null) {
        val promptExtra = if (prompt != null) {
            cardWidth.value + if (ordered.isNotEmpty()) PopupLayout.GAP_DP else 0f
        } else {
            0f
        }
        val frames = PopupLayout.minimalRowDp(
            aspects = ordered.map { aspects[it.alert.id] ?: (16f / 9f) },
            size = size,
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp,
            extraDp = promptExtra,
        )
        ordered.zip(frames).associate { (slot, frame) -> slot.alert.id to frame }
    } else {
        emptyMap()
    }
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(measured) {
        if (fill || compact || measured.width <= 0 || measured.height <= 0) return@LaunchedEffect
        with(density) { onGroupSize(measured.width.toDp().value, measured.height.toDp().value) }
    }
    val newestId = slots.lastOrNull()?.alert?.id
    val focusManager = LocalFocusManager.current
    val focusModifier = Modifier
        .focusRequester(groupFocus)
        .onFocusChanged { groupHeld = it.hasFocus }
        .focusRestorer()
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusGroup()
        .onKeyEvent { event ->
            val direction = directionOf(event) ?: return@onKeyEvent false
            if (prompt != null) return@onKeyEvent true
            focusManager.moveFocus(direction)
            true
        }
    val bottomCorner = corner == AlertCorner.BottomLeft ||
        corner == AlertCorner.BottomCenter ||
        corner == AlertCorner.BottomRight
    Row(
        modifier = Modifier
            .then(
                if (fill) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .onSizeChanged { measured = it }
                },
            )
            .then(focusModifier),
        horizontalArrangement = Arrangement.spacedBy(PopupLayout.GAP_DP.dp),
        verticalAlignment = if (bottomCorner) Alignment.Bottom else Alignment.Top,
    ) {
        if (prompt != null && newestAtStart) {
            QuietPromptCard(prompt, modifier = if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth())
        }
        ordered.forEach { slot ->
            val frame = minimalFrames[slot.alert.id]
            val cardModifier = when {
                frame != null -> Modifier.size(width = frame.widthDp.dp, height = frame.heightDp.dp)
                cardWidth != null -> Modifier.width(cardWidth)
                else -> Modifier.weight(1f)
            }.then(if (prompt != null) Modifier.refuseFocusEntry() else Modifier)
            val busy = slots.any { it.engaged }
            val focus = slot.alert.id == newestId && !busy
            val reportAspect: (Float) -> Unit = { ratio ->
                if (aspects[slot.alert.id] != ratio) aspects[slot.alert.id] = ratio
            }
            when {
                compact -> Box(modifier = cardModifier) {
                    ProtectNotice(slot.alert) { AlertController.watch(slot.alert.cameraId) }
                }
                pictureOnly -> MinimalAlertCard(
                    alert = slot.alert,
                    client = client,
                    modifier = cardModifier,
                    sized = frame != null,
                    onAspect = reportAspect,
                )
                else -> ProtectAlertCard(
                    alert = slot.alert,
                    client = client,
                    onIgnore = { AlertController.ignore(slot.alert.id) },
                    onWatch = { AlertController.watch(slot.alert.cameraId) },
                    onUnlock = { doorId -> AlertController.unlock(doorId) },
                    onAnnounce = onAnnounce,
                    onInteract = { AlertController.interact(slot.alert.id) },
                    modifier = cardModifier,
                    phrasesFor = phrasesFor,
                    takeFocus = focus && prompt == null,
                    iconsOnly = size != PopupSize.Large,
                )
            }
        }
        if (prompt != null && !newestAtStart) {
            QuietPromptCard(prompt, modifier = if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QuietPromptCard(prompt: QuietPrompt, modifier: Modifier = Modifier) {
    val noFocus = remember { FocusRequester() }
    val durationFocus = remember { FocusRequester() }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AptRadius.sheet)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(prompt.packageName, prompt.recheck, held) {
        if (held) return@LaunchedEffect
        val target = if (prompt.recheck) durationFocus else noFocus
        repeat(5) { attempt ->
            delay(if (attempt == 0) 16 else 32)
            val landed = runCatching { target.requestFocus() }.getOrNull()
            if (landed != false) return@LaunchedEffect
        }
    }
    Column(
        modifier = modifier
            .onFocusChanged { held = it.hasFocus }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .clip(shape)
            .border(1.dp, AptColors.HairlineStrong, shape)
            .background(AptColors.Surface)
            .padding(AptSpace.sm)
            .onPreviewKeyEvent { event ->
                val back = event.type == KeyEventType.KeyUp && (event.key == Key.Back || event.key == Key.Escape)
                if (back) {
                    QuietPromptController.decline()
                    return@onPreviewKeyEvent true
                }
                val direction = directionOf(event) ?: return@onPreviewKeyEvent false
                focusManager.moveFocus(direction)
                true
            },
        verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
    ) {
        Text(
            text = "WHILE YOU WATCH",
            fontSize = AptType.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = AptColors.Primary,
        )
        Text(
            text = if (prompt.recheck) {
                "Still watching ${prompt.label}. Keep alerts silent?"
            } else {
                "Silence alerts while you watch ${prompt.label}?"
            },
            fontSize = PopupTitle,
            fontWeight = FontWeight.SemiBold,
            color = AptColors.OnBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "You're watching ${prompt.label}.",
            fontSize = AptType.label,
            color = AptColors.Secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
        ) {
            AptButton(
                text = "No",
                compact = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(durationFocus)
                    .focusRequester(noFocus),
                onClick = { QuietPromptController.decline() },
            )
            AptButton(
                text = "Yes",
                compact = true,
                style = AptButtonStyle.Primary,
                modifier = Modifier.weight(1f),
                onClick = { QuietPromptController.accept() },
            )
        }
    }
}

fun AlertCorner.alignment(): Alignment = when (this) {
    AlertCorner.TopLeft -> Alignment.TopStart
    AlertCorner.TopCenter -> Alignment.TopCenter
    AlertCorner.TopRight -> Alignment.TopEnd
    AlertCorner.Center -> Alignment.Center
    AlertCorner.BottomLeft -> Alignment.BottomStart
    AlertCorner.BottomCenter -> Alignment.BottomCenter
    AlertCorner.BottomRight -> Alignment.BottomEnd
}

fun AlertCorner.insetPadding(): PaddingValues {
    val x = AptSpace.overscanX
    val y = AptSpace.overscanY
    return when (this) {
        AlertCorner.TopLeft -> PaddingValues(start = x, top = y)
        AlertCorner.TopCenter -> PaddingValues(top = y)
        AlertCorner.TopRight -> PaddingValues(end = x, top = y)
        AlertCorner.Center -> PaddingValues(0.dp)
        AlertCorner.BottomLeft -> PaddingValues(start = x, bottom = y)
        AlertCorner.BottomCenter -> PaddingValues(bottom = y)
        AlertCorner.BottomRight -> PaddingValues(end = x, bottom = y)
    }
}

/** Headline such as "Person detected"; falls back to the event title for access events. */
fun AlertInset.displayHeadline(): String {
    if (detections.isNotEmpty()) return detections.headline()
    return title.ifBlank { "Alert" }
}

fun AlertInset.displayPlace(): String {
    val place = cameraName.ifBlank { doorName.orEmpty() }
    if (place.isNotBlank()) return place
    return subtitle
}

private fun AlertInset.icon(): AptIconKind = when (kind) {
    AlertKind.Doorbell -> AptIconKind.Bell
    AlertKind.AccessDoor -> AptIconKind.Door
    AlertKind.Motion, AlertKind.SmartDetect ->
        if (DetectionType.Person in detections || DetectionType.Face in detections) AptIconKind.Account else AptIconKind.Camera
}

@Composable
private fun AlertKindBadge(alert: AlertInset) {
    val tint = if (alert.kind == AlertKind.Doorbell) AptColors.Warning else AptColors.Primary
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tint.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AptIcon(alert.icon(), modifier = Modifier.size(AptSize.icon), tint = tint)
    }
}

@Composable
fun ProtectNotice(alert: AlertInset, onOpen: () -> Unit) {
    AptFocusSurface(onClick = onOpen, modifier = Modifier.fillMaxWidth(), scale = false, fill = AptColors.SurfaceRaised) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AptSpace.md, vertical = AptSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
        ) {
            AlertKindBadge(alert)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    alert.displayHeadline(),
                    fontSize = AptType.heading,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val place = alert.displayPlace()
                if (place.isNotBlank()) {
                    Text(
                        place,
                        fontSize = AptType.body,
                        color = AptColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AptIcon(AptIconKind.ChevronRight, modifier = Modifier.size(AptSize.icon), tint = AptColors.Tertiary)
        }
    }
}

/**
 * Card look: a large rounded picture with a headline bar under it. Select opens the camera full
 * screen, Back ignores, and Down opens a Door / Say / Ignore row that Up puts away again.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ProtectAlertCard(
    alert: AlertInset,
    client: OkHttpClient,
    onIgnore: () -> Unit,
    onWatch: () -> Unit,
    onUnlock: suspend (doorId: String) -> Result<String>,
    onAnnounce: suspend (cameraId: String, message: String) -> Result<String>,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = true,
    minimal: Boolean = false,
    iconsOnly: Boolean = false,
    phrasesFor: (forDoor: Boolean) -> List<SpeakerPhrase> = { forDoor ->
        if (forDoor) defaultDoorPhrases() else defaultCameraPhrases()
    },
    onAspect: (Float) -> Unit = {},
) {
    if (minimal) {
        MinimalAlertCard(alert = alert, client = client, modifier = modifier, sized = false, onAspect = onAspect)
        return
    }
    val cardFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val phraseFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var armed by remember(alert.id) { mutableStateOf(false) }
    var saying by remember(alert.id) { mutableStateOf(false) }
    var speaking by remember(alert.id) { mutableStateOf(false) }
    var status by remember(alert.id) { mutableStateOf("") }
    var statusOk by remember(alert.id) { mutableStateOf(false) }
    var playbackFailed by remember(alert.id) { mutableStateOf(false) }
    var playing by remember(alert.id) { mutableStateOf(false) }
    var actionsOpen by remember(alert.id) { mutableStateOf(false) }
    var cardFocused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var focusedControl by remember(alert.id) { mutableStateOf<String?>(null) }
    val showDoor = alert.kind == AlertKind.Doorbell ||
        alert.kind == AlertKind.AccessDoor ||
        !alert.doorId.isNullOrBlank()
    val lines = phrasesFor(showDoor)
    val bitmap = remember(alert.id) {
        alert.snapshotJpeg?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    val shape = RoundedCornerShape(AptRadius.sheet)
    val titleSize = if (iconsOnly) PopupTitle else AptType.label
    val labelSize = if (iconsOnly) PopupLabel else AptType.caption
    val streaming = !alert.streamUrl.isNullOrBlank() && !playbackFailed

    fun userMovedFocus(control: String) {
        val previous = focusedControl
        focusedControl = control
        if (previous != null && previous != control) onInteract()
    }

    fun pressDoor() {
        onInteract()
        val doorId = alert.doorId
        if (!armed) {
            statusOk = false
            if (doorId.isNullOrBlank()) {
                status = "No door is linked to this event."
            } else {
                armed = true
                status = "Press again to unlock ${alert.doorName ?: "the door"}."
            }
        } else if (!doorId.isNullOrBlank()) {
            scope.launch {
                val result = onUnlock(doorId)
                statusOk = result.isSuccess
                status = result.fold(
                    onSuccess = { it },
                    onFailure = { it.message ?: "Unlock failed" },
                )
                armed = false
            }
        }
    }

    fun pressSay() {
        onInteract()
        saying = true
        status = ""
    }

    LaunchedEffect(takeFocus, saying) {
        if (!takeFocus && !saying) return@LaunchedEffect
        val target = when {
            saying -> phraseFocus
            actionsOpen -> actionFocus
            else -> cardFocus
        }
        runCatching { target.requestFocus() }
    }
    LaunchedEffect(actionsOpen) {
        if (actionsOpen && !saying) runCatching { actionFocus.requestFocus() }
    }
    LaunchedEffect(held) {
        if (held || !actionsOpen) return@LaunchedEffect
        delay(250)
        if (!speaking) {
            actionsOpen = false
            saying = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { held = it.hasFocus }
            .onPreviewKeyEvent { event ->
                val back = event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Back || event.key == Key.Escape)
                if (!back) {
                    if (event.type == KeyEventType.KeyDown) onInteract()
                    false
                } else if (saying && !speaking) {
                    saying = false
                    true
                } else if (actionsOpen && !speaking) {
                    actionsOpen = false
                    runCatching { cardFocus.requestFocus() }
                    true
                } else {
                    onIgnore()
                    true
                }
            },
        verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(cardFocus)
                .onFocusChanged {
                    if (it.isFocused && !cardFocused) {
                        userMovedFocus("card")
                        if (actionsOpen && !speaking) {
                            actionsOpen = false
                            saying = false
                        }
                    }
                    cardFocused = it.isFocused
                }
                .onKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyUp && !speaking) actionsOpen = true
                            true
                        }
                        Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                if (actionsOpen) focusManager.moveFocus(FocusDirection.Down) else actionsOpen = true
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .aptFocusFrame(cardFocused, shape, scale = false, idleBorder = AptColors.Hairline)
                .clip(shape)
                .background(AptColors.Scrim),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(AptColors.PreviewWell),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    streaming -> {
                        FittedStream(
                            url = alert.streamUrl,
                            client = client,
                            modifier = Modifier.fillMaxSize(),
                            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                            onPlaying = { playing = true },
                            onError = { _, _ ->
                                playing = false
                                playbackFailed = true
                            },
                        )
                    }
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = alert.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AptIcon(alert.icon(), modifier = Modifier.size(32.dp), tint = AptColors.Tertiary)
                            Text(
                                text = if (playbackFailed) "Picture unavailable" else "No live picture",
                                fontSize = labelSize,
                                color = AptColors.Tertiary,
                                modifier = Modifier.padding(top = AptSpace.xs),
                            )
                        }
                    }
                }
                if (streaming && playing) {
                    LivePill(
                        fontSize = labelSize,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(if (iconsOnly) AptSpace.xs else AptSpace.sm),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (iconsOnly) AptSpace.xs else AptSpace.sm,
                        vertical = AptSpace.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (iconsOnly) AptSpace.xs else AptSpace.sm),
            ) {
                AptIcon(
                    alert.icon(),
                    modifier = Modifier.size(if (iconsOnly) AptSize.iconSmall else AptSize.icon),
                    tint = AptColors.OnVideo,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = alert.displayHeadline(),
                        fontSize = titleSize,
                        fontWeight = FontWeight.Bold,
                        color = AptColors.OnVideo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Press Select for actions",
                        fontSize = labelSize,
                        color = AptColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (actionsOpen || saying || status.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AptRadius.card))
                    .background(AptColors.Scrim)
                    .padding(AptSpace.xxs),
                verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
            ) {
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        fontSize = labelSize,
                        color = if (statusOk) AptColors.Online else AptColors.Warning,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = AptSpace.xxs),
                    )
                }
                if (saying) {
                    Text(
                        text = "Plays on the camera speaker",
                        fontSize = labelSize,
                        color = AptColors.Secondary,
                        modifier = Modifier.padding(horizontal = AptSpace.xxs),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AptSpace.xxs),
                        verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
                    ) {
                        lines.forEachIndexed { index, line ->
                            var focused by remember { mutableStateOf(false) }
                            AptChip(
                                text = line.label,
                                selected = false,
                                enabled = !speaking,
                                onClick = {
                                    onInteract()
                                    val cameraId = alert.cameraId
                                    if (cameraId.isNullOrBlank()) {
                                        statusOk = false
                                        status = "This alert is not tied to a camera."
                                        return@AptChip
                                    }
                                    speaking = true
                                    statusOk = false
                                    status = "Playing on ${alert.cameraName.ifBlank { "the camera" }}..."
                                    scope.launch {
                                        val result = onAnnounce(cameraId, line.message)
                                        statusOk = result.isSuccess
                                        status = result.fold(
                                            onSuccess = { it },
                                            onFailure = { it.message ?: "Could not play that message." },
                                        )
                                        speaking = false
                                        saying = false
                                    }
                                },
                                fontSize = labelSize,
                                modifier = (if (index == 0) Modifier.focusRequester(phraseFocus) else Modifier)
                                    .onFocusChanged {
                                        if (it.isFocused && !focused) userMovedFocus("say-$index")
                                        focused = it.isFocused
                                    },
                            )
                        }
                    }
                } else if (actionsOpen) {
                    PopupActionRow(
                        showDoor = showDoor,
                        hasSpeaker = alert.hasSpeaker,
                        armed = armed,
                        speaking = speaking,
                        withIcons = !iconsOnly,
                        fontSize = labelSize,
                        firstFocus = actionFocus,
                        onWatch = {
                            onInteract()
                            onWatch()
                        },
                        onDoor = { pressDoor() },
                        onSay = { pressSay() },
                        onIgnore = onIgnore,
                        onFocusMoved = { userMovedFocus(it) },
                    )
                }
            }
        }
    }
}

/** Direction pad movement, ignoring the key-up that follows a press. */
private fun directionOf(event: KeyEvent): FocusDirection? {
    if (event.type != KeyEventType.KeyDown) return null
    return when (event.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> null
    }
}

/** Popup cards stay out of the remote while the watching question is on screen. */
private fun Modifier.refuseFocusEntry(): Modifier =
    focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()

private val PopupTitle = 10.sp
private val PopupLabel = 9.sp

@Composable
private fun LivePill(fontSize: TextUnit, modifier: Modifier = Modifier) {
    Text(
        text = "LIVE",
        modifier = modifier
            .background(AptColors.Warning, RoundedCornerShape(AptRadius.chip))
            .padding(horizontal = AptSpace.xs, vertical = 2.dp),
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = AptColors.OnPrimary,
    )
}

/** Watch, Door (two presses to unlock), Say, Ignore. The first button takes focus when the row opens. */
@Composable
private fun PopupActionRow(
    showDoor: Boolean,
    hasSpeaker: Boolean,
    armed: Boolean,
    speaking: Boolean,
    withIcons: Boolean,
    fontSize: TextUnit,
    firstFocus: FocusRequester,
    onWatch: () -> Unit,
    onDoor: () -> Unit,
    onSay: () -> Unit,
    onIgnore: () -> Unit,
    onFocusMoved: (String) -> Unit,
) {
    val actions = buildList {
        add(PopupAction.Watch)
        if (showDoor) add(PopupAction.Door)
        if (hasSpeaker) add(PopupAction.Say)
        add(PopupAction.Ignore)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AptSpace.xxs),
    ) {
        actions.forEachIndexed { index, action ->
            val label = when (action) {
                PopupAction.Watch -> "Watch"
                PopupAction.Door -> if (armed) "Confirm" else "Door"
                PopupAction.Say -> "Say"
                PopupAction.Ignore -> "Ignore"
            }
            val icon = when (action) {
                PopupAction.Watch -> AptIconKind.Play
                PopupAction.Door -> AptIconKind.Lock
                PopupAction.Say -> AptIconKind.Speaker
                PopupAction.Ignore -> AptIconKind.Close
            }
            AptButton(
                text = label,
                onClick = when (action) {
                    PopupAction.Watch -> onWatch
                    PopupAction.Door -> onDoor
                    PopupAction.Say -> onSay
                    PopupAction.Ignore -> onIgnore
                },
                modifier = Modifier
                    .weight(1f)
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                style = when {
                    action == PopupAction.Watch -> AptButtonStyle.Primary
                    action == PopupAction.Door && armed -> AptButtonStyle.Danger
                    else -> AptButtonStyle.Secondary
                },
                enabled = !speaking,
                icon = if (withIcons) icon else null,
                compact = true,
                fontSize = fontSize,
                onFocused = { onFocusMoved(action.name) },
            )
        }
    }
}

private enum class PopupAction { Watch, Door, Say, Ignore }

/**
 * Minimal look: the picture alone with a small name chip. It holds nothing focusable, so Card
 * popups and the watching prompt keep the remote. [sized] means the caller fixed width and height.
 */
@Composable
private fun MinimalAlertCard(
    alert: AlertInset,
    client: OkHttpClient,
    modifier: Modifier,
    sized: Boolean,
    onAspect: (Float) -> Unit,
) {
    var playbackFailed by remember(alert.id) { mutableStateOf(false) }
    val bitmap = remember(alert.id) {
        alert.snapshotJpeg?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    val snapshotRatio = bitmap?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() }
    var ratio by remember(alert.id) { mutableFloatStateOf(snapshotRatio ?: (16f / 9f)) }
    LaunchedEffect(ratio) { onAspect(ratio) }
    val shape = RoundedCornerShape(AptRadius.card)
    val frameRatio = ratio.coerceIn(PopupLayout.MINIMAL_MIN_ASPECT, PopupLayout.MINIMAL_MAX_ASPECT)
    Box(
        modifier = modifier
            .then(if (sized) Modifier else Modifier.fillMaxWidth().aspectRatio(frameRatio))
            .clip(shape)
            .background(AptColors.PreviewWell),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !alert.streamUrl.isNullOrBlank() && !playbackFailed -> {
                FittedStream(
                    url = alert.streamUrl,
                    client = client,
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    onError = { _, _ -> playbackFailed = true },
                    onVideoAspect = { next ->
                        if (next.isFinite() && next in 0.25f..4f) ratio = next
                    },
                )
            }
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = alert.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            else -> {
                AptIcon(alert.icon(), modifier = Modifier.size(24.dp), tint = AptColors.Tertiary)
            }
        }
        val name = alert.displayPlace().ifBlank { alert.displayHeadline() }
        if (name.isNotBlank()) {
            Text(
                text = name,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(AptSpace.xxs)
                    .background(AptColors.ScrimSoft, RoundedCornerShape(AptRadius.chip))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                fontSize = PopupLabel,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnVideo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
