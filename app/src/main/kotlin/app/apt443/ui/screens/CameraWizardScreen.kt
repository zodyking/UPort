package app.apt443.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.CameraAlertPrefs
import app.apt443.domain.DetectionType
import app.apt443.domain.Feed
import app.apt443.domain.StreamQuality
import app.apt443.domain.VoiceTarget
import app.apt443.domain.alertPrefsFor
import app.apt443.domain.wizardTriggerSelection
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptSwitchRow
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.aptPanel
import app.apt443.ui.player.LiveStreamTile
import app.apt443.ui.player.StreamSpinner
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Empty string asks for every camera. A camera id asks for that camera only. */
object CameraWizardBus {
    private val requests = MutableStateFlow<String?>(null)
    val request = requests.asStateFlow()

    fun openAll() {
        requests.value = ""
    }

    fun open(cameraId: String) {
        requests.value = cameraId
    }

    fun clear() {
        requests.value = null
    }
}

private enum class WizardPhase {
    Intro,
    Triggers,
    Popups,
    SpeakTv,
    SpeakCamera,
    Recap,
}

@Composable
fun CameraWizardScreen(
    container: AppContainer,
    settings: AppSettings,
    client: OkHttpClient,
    cameraIds: Set<String>?,
    allowSkip: Boolean,
    onFinished: () -> Unit,
    onSkip: () -> Unit,
) {
    BackHandler(enabled = !allowSkip) { }
    val feed by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    // The run keeps the cameras it started with, in order, so finishing one camera
    // never shifts which camera comes next.
    var roster by remember { mutableStateOf(emptyList<String>()) }
    val wanted = feed.items.map { it.id }.filter { cameraIds == null || it in cameraIds }
    val ids = roster + wanted.filterNot { it in roster }
    SideEffect { if (ids != roster) roster = ids }
    val cameras = remember(feed.items, ids) {
        ids.mapNotNull { id -> feed.items.firstOrNull { it.id == id } }
    }
    val scope = rememberCoroutineScope()
    var cameraIndex by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(WizardPhase.Intro) }
    val skipFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val firstTriggerFocus = remember { FocusRequester() }

    if (cameras.isEmpty()) {
        LaunchedEffect(Unit) {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { skipFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(AptColors.backgroundBrush), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .aptPanel(fill = AptColors.Glass, corner = AptRadius.sheet)
                    .padding(AptSpace.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AptSpace.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .border(1.dp, AptColors.Hairline, CircleShape)
                        .background(AptColors.GlassRaised, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    StreamSpinner(size = 34.dp)
                }
                Text("Waiting for cameras", fontSize = AptType.display, fontWeight = FontWeight.SemiBold, color = AptColors.OnBackground)
                Text(
                    text = if (allowSkip) {
                        "The wizard starts when this console lists a camera."
                    } else {
                        "A camera still needs setup. It will open here as soon as the console lists it."
                    },
                    fontSize = AptType.body,
                    color = AptColors.Secondary,
                    textAlign = TextAlign.Center,
                )
                if (allowSkip) {
                    AptButton(
                        text = "Not now",
                        style = AptButtonStyle.Secondary,
                        onClick = onSkip,
                        modifier = Modifier.focusRequester(skipFocus),
                    )
                }
            }
        }
        return
    }

    val camera = cameras[cameraIndex.coerceIn(0, cameras.lastIndex)]
    val chips = detectionChipsFor(camera)
    var draft by remember(camera.id) { mutableStateOf(settings.alertPrefsFor(camera.id)) }
    var triggers by remember(camera.id) { mutableStateOf(wizardTriggerSelection(settings, camera.id)) }
    var speakOnTv by remember(camera.id) { mutableStateOf(false) }
    val triggerScroll = rememberScrollState()
    val question = questionFor(camera, phase)
    val steps = wizardSteps(camera.hasSpeaker)
    LaunchedEffect(camera.id, phase) {
        val target = if (phase == WizardPhase.Triggers && chips.isNotEmpty()) firstTriggerFocus else actionFocus
        // Focus first: the phase swap removes the button that had focus, and speech can take a while.
        for (attempt in 0 until 3) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) break
        }
        runCatching { container.speech.playOnTv(question.spoken) }
    }

    // Answers stay in the draft until the camera is finished. Saving earlier marks the
    // camera as set up and the wizard would move on before its last steps.
    fun save(next: CameraAlertPrefs) {
        draft = next
    }

    fun go(nextPhase: WizardPhase) {
        phase = nextPhase
    }

    var finishing by remember { mutableStateOf(false) }
    fun finishCamera() {
        if (finishing) return
        finishing = true
        scope.launch {
            container.setCameraAlertPrefs(camera.id, draft)
            if (cameraIndex >= cameras.lastIndex) {
                onFinished()
            } else {
                cameraIndex += 1
                go(WizardPhase.Intro)
                finishing = false
            }
        }
    }

    fun enableVoice() {
        scope.launch {
            val current = container.settings.first()
            if (!current.voiceAlerts) container.saveSettings(current.copy(voiceAlerts = true))
        }
    }

    fun applyVoice(onTv: Boolean, onCamera: Boolean) {
        val enabled = onTv || onCamera
        val target = when {
            onTv && onCamera -> VoiceTarget.Both
            onCamera -> VoiceTarget.CameraSpeaker
            else -> VoiceTarget.Tv
        }
        save(
            draft.copy(
                voice = enabled,
                voiceTarget = target,
                detections = triggers,
                spokenDetections = triggers,
            ),
        )
        if (enabled) enableVoice()
        go(WizardPhase.Recap)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AptColors.backgroundBrush)
            .padding(horizontal = AptSpace.overscanX, vertical = AptSpace.overscanY),
        horizontalArrangement = Arrangement.spacedBy(AptSpace.xl),
    ) {
        Column(modifier = Modifier.weight(1.05f), verticalArrangement = Arrangement.spacedBy(AptSpace.md)) {
            Text(
                text = "CAMERA ${cameraIndex + 1} OF ${cameras.size}",
                fontSize = AptType.caption,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = AptType.capsTracking,
                color = AptColors.Tertiary,
            )
            Box(modifier = Modifier.fillMaxWidth().aptPanel(fill = AptColors.PreviewWell, corner = AptRadius.tile)) {
                LiveStreamTile(
                    cameraId = camera.id,
                    quality = StreamQuality.Low,
                    container = container,
                    client = client,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    enabled = camera.online,
                )
            }
            Text(
                camera.name,
                fontSize = AptType.title,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StatusPill(
                text = if (camera.online) "Live preview" else "Offline",
                tone = if (camera.online) StatusTone.Online else StatusTone.Offline,
            )
        }
        Column(
            modifier = Modifier
                .weight(0.95f)
                .widthIn(max = 640.dp)
                .fillMaxHeight()
                .aptPanel(fill = AptColors.Glass, corner = AptRadius.sheet)
                .focusGroup()
                .padding(AptSpace.xl),
        ) {
            WizardSteps(steps = steps, current = phase)
            Spacer(modifier = Modifier.height(AptSpace.lg))
            Text(
                question.title,
                fontSize = AptType.display,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(question.detail, fontSize = AptType.body, color = AptColors.Secondary)
            Spacer(modifier = Modifier.height(AptSpace.lg))
            if (phase == WizardPhase.Triggers) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(triggerScroll)
                        .padding(horizontal = AptSpace.xs, vertical = AptSpace.xxs),
                    verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
                ) {
                    chips.forEachIndexed { index, type ->
                        AptSwitchRow(
                            label = type.label,
                            checked = type in triggers,
                            modifier = if (index == 0) Modifier.focusRequester(firstTriggerFocus) else Modifier,
                            onToggle = {
                                triggers = if (type in triggers) triggers - type else triggers + type
                            },
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            WizardActions {
                val wide = Modifier.widthIn(min = 148.dp)
                when (phase) {
                    WizardPhase.Intro -> AptButton(
                        text = "Continue",
                        style = AptButtonStyle.Primary,
                        onClick = { go(WizardPhase.Triggers) },
                        modifier = wide.focusRequester(actionFocus),
                    )
                    WizardPhase.Triggers -> AptButton(
                        text = "Continue",
                        style = AptButtonStyle.Primary,
                        onClick = {
                            val chosen = triggers.ifEmpty { setOf(DetectionType.Motion) }
                            triggers = chosen
                            save(draft.copy(detections = chosen, spokenDetections = chosen))
                            go(WizardPhase.Popups)
                        },
                        modifier = wide.focusRequester(actionFocus),
                    )
                    WizardPhase.Popups -> {
                        AptButton(
                            text = "Yes",
                            style = AptButtonStyle.Primary,
                            onClick = {
                                save(draft.copy(popups = true, detections = triggers, spokenDetections = triggers))
                                go(WizardPhase.SpeakTv)
                            },
                            modifier = wide.focusRequester(actionFocus),
                        )
                        AptButton(
                            text = "No",
                            style = AptButtonStyle.Secondary,
                            onClick = {
                                save(draft.copy(popups = false, detections = triggers, spokenDetections = triggers))
                                go(WizardPhase.SpeakTv)
                            },
                            modifier = wide,
                        )
                    }
                    WizardPhase.SpeakTv -> {
                        AptButton(
                            text = "Yes",
                            style = AptButtonStyle.Primary,
                            onClick = {
                                speakOnTv = true
                                if (camera.hasSpeaker) go(WizardPhase.SpeakCamera) else applyVoice(onTv = true, onCamera = false)
                            },
                            modifier = wide.focusRequester(actionFocus),
                        )
                        AptButton(
                            text = "No",
                            style = AptButtonStyle.Secondary,
                            onClick = {
                                speakOnTv = false
                                if (camera.hasSpeaker) go(WizardPhase.SpeakCamera) else applyVoice(onTv = false, onCamera = false)
                            },
                            modifier = wide,
                        )
                    }
                    WizardPhase.SpeakCamera -> {
                        AptButton(
                            text = "Yes",
                            style = AptButtonStyle.Primary,
                            onClick = { applyVoice(onTv = speakOnTv, onCamera = true) },
                            modifier = wide.focusRequester(actionFocus),
                        )
                        AptButton(
                            text = "No",
                            style = AptButtonStyle.Secondary,
                            onClick = { applyVoice(onTv = speakOnTv, onCamera = false) },
                            modifier = wide,
                        )
                    }
                    WizardPhase.Recap -> AptButton(
                        text = if (cameraIndex >= cameras.lastIndex) "Finish" else "Next camera",
                        style = AptButtonStyle.Primary,
                        onClick = { finishCamera() },
                        modifier = wide.focusRequester(actionFocus),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (allowSkip) {
                    AptButton(text = "Not now", style = AptButtonStyle.Ghost, onClick = onSkip)
                }
            }
        }
    }
}

@Composable
private fun WizardSteps(steps: List<WizardPhase>, current: WizardPhase) {
    val index = steps.indexOf(current).coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
        Text(
            text = "STEP ${index + 1} OF ${steps.size}",
            fontSize = AptType.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = AptType.capsTracking,
            color = AptColors.Tertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { stepIndex, _ ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (stepIndex <= index) AptColors.Primary else AptColors.HairlineStrong,
                            RoundedCornerShape(AptRadius.chip),
                        ),
                )
            }
        }
    }
}

@Composable
private fun WizardActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun wizardSteps(hasSpeaker: Boolean): List<WizardPhase> {
    val all = WizardPhase.entries
    return if (hasSpeaker) all else all.filter { it != WizardPhase.SpeakCamera }
}

private data class WizardQuestion(val title: String, val detail: String, val spoken: String)

private fun questionFor(camera: Camera, phase: WizardPhase): WizardQuestion {
    val name = camera.name.ifBlank { "this camera" }
    return when (phase) {
        WizardPhase.Intro -> WizardQuestion(
            title = name,
            detail = "We'll choose what this camera alerts on, then where you hear it.",
            spoken = "This is $name.",
        )
        WizardPhase.Triggers -> WizardQuestion(
            title = "Which events trigger alerts?",
            detail = "Motion starts on. Turn on only the events you want.",
            spoken = "Which events should $name trigger? Motion is already on.",
        )
        WizardPhase.Popups -> WizardQuestion(
            title = "Show a popup on this TV?",
            detail = "A card appears when one of those events happens.",
            spoken = "Should $name show a popup on this TV?",
        )
        WizardPhase.SpeakTv -> WizardQuestion(
            title = "Speak on this TV?",
            detail = "The TV says what this camera saw.",
            spoken = "Should $name speak on this TV?",
        )
        WizardPhase.SpeakCamera -> WizardQuestion(
            title = "Reply from the camera?",
            detail = "Yes adds reply choices on the popup, such as “On the way.” The camera does not announce the detection.",
            spoken = "Should $name offer replies on its speaker?",
        )
        WizardPhase.Recap -> WizardQuestion(
            title = "$name is set",
            detail = "You can change this later from Settings.",
            spoken = "$name is set.",
        )
    }
}
