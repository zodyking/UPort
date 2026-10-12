package app.apt443.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.alert.AlertController
import app.apt443.alert.AlertPermissions
import app.apt443.alert.AlertSetupVerifier
import app.apt443.alert.AlertVerifyResult
import app.apt443.alert.DiagnosisAction
import app.apt443.alert.LocalAlertSetupServer
import app.apt443.data.AppContainer
import app.apt443.data.remote.VpnStatus
import app.apt443.domain.AlertCorner
import app.apt443.domain.AlertStyle
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.ConnectionMode
import app.apt443.domain.Feed
import app.apt443.domain.MAX_SPEAKER_PHRASES
import app.apt443.domain.PHRASE_LABEL_LIMIT
import app.apt443.domain.PHRASE_MESSAGE_LIMIT
import app.apt443.domain.PopupLayout
import app.apt443.domain.PopupLook
import app.apt443.domain.PopupPicture
import app.apt443.domain.PopupSize
import app.apt443.domain.QuietDuration
import app.apt443.domain.SiteVpnKind
import app.apt443.domain.SpeakerPhrase
import app.apt443.domain.StreamQuality
import app.apt443.domain.VoiceTarget
import app.apt443.domain.alertsSilenced
import app.apt443.domain.cleanPhrases
import app.apt443.domain.coerceFadeSeconds
import app.apt443.domain.coerceOpenCount
import app.apt443.domain.defaultCameraPhrases
import app.apt443.domain.defaultDoorPhrases
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptCard
import app.apt443.ui.components.AptChip
import app.apt443.ui.components.AptDropdown
import app.apt443.ui.components.AptFocusSurface
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.AptSegmented
import app.apt443.ui.components.AptSwitchRow
import app.apt443.ui.components.AptTextField
import app.apt443.ui.components.ControlCaption
import app.apt443.ui.components.EmptyState
import app.apt443.ui.components.InfoRow
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.ModeBadge
import app.apt443.ui.components.PageHeader
import app.apt443.ui.components.SettingEditCard
import app.apt443.ui.components.SettingFieldButton
import app.apt443.ui.components.SettingsSection
import app.apt443.ui.components.StatusPill
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.TvDevice
import app.apt443.ui.components.aptFocusFrame
import app.apt443.ui.components.aptPanel
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val sectionListWidth = 360.dp

/** Root of the open page. Opening a page from the section list moves focus to its first row. */
private val LocalSettingsPageFocus = staticCompositionLocalOf<FocusRequester?> { null }

@Composable
fun SettingsScreen(container: AppContainer) {
    val saved by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val cameras by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    var page by remember { mutableStateOf(SettingsPage.General) }
    var focusPage by remember { mutableStateOf(false) }
    val pageFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val open: (SettingsPage) -> Unit = { next ->
        page = next
        focusPage = true
    }

    LaunchedEffect(page, focusPage) {
        if (!focusPage) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { pageFocus.requestFocus() }
        focusPage = false
    }

    var verifyResult by remember { mutableStateOf<AlertVerifyResult?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val phoneSetup = remember {
        LocalAlertSetupServer(
            onVerified = {
                scope.launch {
                    verifyResult = AlertVerifyResult.Running
                    verifyResult = AlertSetupVerifier.verify(context) { container.protect.previewAlert() }
                }
            },
        )
    }
    DisposableEffect(phoneSetup) {
        phoneSetup.start()
        onDispose { phoneSetup.stop() }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AptSpace.lg)) {
        PageHeader(
            title = "Settings",
            subtitle = saved.locationName.ifBlank { "This TV" },
        )
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(AptSpace.xl)) {
            SectionList(
                selected = page,
                onSelect = open,
                modifier = Modifier.width(sectionListWidth).fillMaxHeight(),
            )
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    text = page.title,
                    fontSize = AptType.title,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = page.description,
                    fontSize = AptType.body,
                    color = AptColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AptSpace.md))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalSettingsPageFocus provides pageFocus) {
                        key(page) {
                            when (page) {
                                SettingsPage.General -> GeneralPage(container, saved, onOpen = open)
                                SettingsPage.Alerts -> AlertsPage(container, saved, cameras, onOpen = open)
                                SettingsPage.Popups -> PopupsPage(container, saved)
                                SettingsPage.AlwaysOn -> PersistentPage(container, saved, cameras)
                                SettingsPage.Phrases -> PhrasesPage(container, saved)
                                SettingsPage.Cameras -> CamerasPage(container, saved, cameras)
                                SettingsPage.Setup -> SetupPage(
                                    container = container,
                                    saved = saved,
                                    cameras = cameras.items,
                                    phoneSetup = phoneSetup,
                                    verifyResult = verifyResult,
                                    verifying = verifying,
                                    onVerify = { cameraId ->
                                        if (verifying) return@SetupPage
                                        scope.launch {
                                            verifying = true
                                            verifyResult = AlertVerifyResult.Running
                                            verifyResult = AlertSetupVerifier.verify(context) {
                                                container.protect.previewAlert(cameraId)
                                            }
                                            verifying = false
                                        }
                                    },
                                )
                                SettingsPage.About -> AboutPage(container, saved)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every Settings page is one of these: a LazyColumn so Down scrolls, with the page's single
 * focus group and restorer at its root.
 */
@Composable
internal fun SettingsList(content: LazyListScope.() -> Unit) {
    val focus = LocalSettingsPageFocus.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .focusRestorer()
            .focusGroup(),
        contentPadding = PaddingValues(end = AptSpace.xs, bottom = AptSpace.lg),
        verticalArrangement = Arrangement.spacedBy(AptSpace.xl),
        content = content,
    )
}

private fun CoroutineScope.editSettings(container: AppContainer, transform: (AppSettings) -> AppSettings) {
    launch { container.saveSettings(transform(container.settings.first())) }
}

@Composable
private fun SectionList(selected: SettingsPage, onSelect: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .aptPanel(fill = AptColors.SurfaceSunken)
            .padding(AptSpace.md)
            .focusRestorer()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(AptSpace.xs),
    ) {
        items(SettingsPage.entries, key = { it.name }) { item ->
            SectionRow(item = item, selected = item == selected, onSelect = onSelect)
        }
    }
}

@Composable
private fun SectionRow(
    item: SettingsPage,
    selected: Boolean,
    onSelect: (SettingsPage) -> Unit,
) {
    AptFocusSurface(
        onClick = { onSelect(item) },
        selected = selected,
        scale = false,
        fill = AptColors.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = AptSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .background(
                        if (selected) AptColors.Primary else Color.Transparent,
                        RoundedCornerShape(2.dp),
                    ),
            )
            AptIcon(
                item.menuIcon(),
                modifier = Modifier.size(AptSize.icon),
                tint = if (selected) AptColors.Primary else AptColors.Secondary,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    fontSize = AptType.body,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) AptColors.OnBackground else AptColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.description,
                    fontSize = AptType.label,
                    color = AptColors.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun SettingsPage.menuIcon(): AptIconKind = when (this) {
    SettingsPage.General -> AptIconKind.Tv
    SettingsPage.Alerts -> AptIconKind.Bell
    SettingsPage.Popups -> AptIconKind.Footage
    SettingsPage.AlwaysOn -> AptIconKind.Play
    SettingsPage.Phrases -> AptIconKind.Speaker
    SettingsPage.Cameras -> AptIconKind.Camera
    SettingsPage.Setup -> AptIconKind.Settings
    SettingsPage.About -> AptIconKind.Info
}

@Composable
private fun Caption(text: String) {
    Text(text = text, fontSize = AptType.caption, color = AptColors.Tertiary)
}

// ---------------------------------------------------------------------------------------------
// General
// ---------------------------------------------------------------------------------------------

@Composable
private fun GeneralPage(container: AppContainer, saved: AppSettings, onOpen: (SettingsPage) -> Unit) {
    val scope = rememberCoroutineScope()
    val fireTv = remember { TvDevice.isFireTv() }
    SettingsList {
        item(key = "screen") {
            SettingsSection(title = "Screen") {
                AptSwitchRow(
                    label = "Keep TV awake",
                    description = if (saved.keepTvAwake) "The TV never sleeps on its own." else "The TV sleeps after 14 minutes idle.",
                    checked = saved.keepTvAwake,
                    icon = AptIconKind.Tv,
                    onToggle = { scope.editSettings(container) { it.copy(keepTvAwake = !it.keepTvAwake) } },
                )
                AptSwitchRow(
                    label = "Camera screensaver",
                    description = when {
                        saved.cameraScreensaver -> "Cameras show after 5 minutes idle."
                        fireTv -> "Amazon's screensaver shows instead."
                        else -> "The TV's own screensaver shows instead."
                    },
                    checked = saved.cameraScreensaver,
                    icon = AptIconKind.Camera,
                    onToggle = { scope.editSettings(container) { it.copy(cameraScreensaver = !it.cameraScreensaver) } },
                )
            }
        }
        item(key = "playback") {
            SettingsSection(title = "Playback") {
                AptSwitchRow(
                    label = "Pause for alerts",
                    description = "Live view and recordings pause while a popup is open.",
                    checked = saved.pausePlaybackOnAlert,
                    icon = AptIconKind.Play,
                    onToggle = { scope.editSettings(container) { it.copy(pausePlaybackOnAlert = !it.pausePlaybackOnAlert) } },
                )
            }
        }
        item(key = "speech") {
            SettingsSection(title = "TV speech") {
                SettingFieldButton(
                    label = "Read alerts aloud",
                    value = if (saved.voiceAlerts) "On" else "Off",
                    description = "Change it under Alerts.",
                    onClick = { onOpen(SettingsPage.Alerts) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Alerts
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertsPage(
    container: AppContainer,
    saved: AppSettings,
    cameras: Feed<Camera>,
    onOpen: (SettingsPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val defaults = saved.defaultAlertPrefs
    val known = cameras.items.filter { it.id.isNotBlank() }
    SettingsList {
        item(key = "rules") {
            SettingsSection(title = "Alert on", description = "Cameras without their own rules use these.") {
                AptSwitchRow(
                    label = "Popups",
                    description = "Show alert cards and notifications.",
                    checked = defaults.popups,
                    icon = AptIconKind.Bell,
                    onToggle = { scope.launch { container.setDefaultAlertPrefs(defaults.copy(popups = !defaults.popups)) } },
                )
                ControlCaption("Detections")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
                    verticalArrangement = Arrangement.spacedBy(AptSpace.xs),
                ) {
                    defaultDetectionChips().forEach { type ->
                        AptChip(
                            text = type.label,
                            selected = defaults.allows(type),
                            onClick = { scope.launch { container.setDefaultAlertPrefs(defaults.toggled(type)) } },
                        )
                    }
                }
            }
        }
        if (known.isNotEmpty()) {
            item(key = "cameras") {
                SettingsSection(title = "Cameras", description = "Popups for each camera. Full setup is under Cameras.") {
                    known.forEach { camera ->
                        val row = cameraAlertRow(camera, saved)
                        AptSwitchRow(
                            label = camera.name.ifBlank { "Camera" },
                            description = row.summary ?: if (row.hasOverride) "Own rules" else "Uses the rules above",
                            checked = row.prefs.popups,
                            onToggle = {
                                scope.launch { container.setCameraAlertPrefs(camera.id, row.prefs.copy(popups = !row.prefs.popups)) }
                            },
                        )
                    }
                    AptButton(
                        text = "Set up cameras",
                        icon = AptIconKind.Camera,
                        style = AptButtonStyle.Ghost,
                        onClick = { onOpen(SettingsPage.Cameras) },
                    )
                }
            }
        }
        item(key = "quiet") { QuietSection(container, saved) }
        item(key = "voice") { VoiceSection(container, saved, cameras.items) }
    }
}

@Composable
private fun QuietSection(container: AppContainer, saved: AppSettings) {
    val scope = rememberCoroutineScope()
    SettingsSection(title = "Quiet", description = "Hide camera popups and their voice for a while.") {
        if (saved.alertsSilenced(System.currentTimeMillis())) {
            val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(saved.alertQuietUntilEpochMs))
            InlineMessage("Camera alerts are off until $until.", StatusTone.Neutral)
            AptButton(
                text = "Resume alerts",
                icon = AptIconKind.Bell,
                onClick = { scope.editSettings(container) { it.copy(alertQuietUntilEpochMs = 0L) } },
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                QuietDuration.entries.forEach { duration ->
                    AptButton(
                        text = duration.label,
                        onClick = {
                            scope.editSettings(container) {
                                it.copy(alertQuietUntilEpochMs = System.currentTimeMillis() + duration.millis)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceSection(container: AppContainer, saved: AppSettings, cameras: List<Camera>) {
    val scope = rememberCoroutineScope()
    val defaults = saved.defaultAlertPrefs
    val voiceFailure by AlertController.lastVoiceFailure.collectAsStateWithLifecycle()
    var voiceTestStatus by remember { mutableStateOf<String?>(null) }
    val speakerCameras = remember(cameras) { cameras.filter { it.hasSpeaker } }
    var testSpeakerCameraId by remember { mutableStateOf<String?>(null) }
    var speakerPickerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(speakerCameras) {
        if (speakerCameras.isEmpty()) {
            testSpeakerCameraId = null
        } else if (testSpeakerCameraId == null || speakerCameras.none { it.id == testSpeakerCameraId }) {
            testSpeakerCameraId = speakerCameras.first().id
        }
    }
    val testSpeakerCamera = speakerCameras.firstOrNull { it.id == testSpeakerCameraId }
    SettingsSection(title = "Voice", description = "Spoken headline when an alert arrives.") {
        AptSwitchRow(
            label = "Read alerts aloud",
            description = "This TV speaks the alert headline.",
            checked = saved.voiceAlerts,
            icon = AptIconKind.Speaker,
            onToggle = { scope.editSettings(container) { it.copy(voiceAlerts = !it.voiceAlerts) } },
        )
        if (saved.voiceAlerts) {
            ControlCaption("Play on")
            AptSegmented(
                options = VoiceTarget.entries.toList(),
                selected = defaults.voiceTarget,
                label = { it.label },
                onSelect = { target ->
                    scope.launch { container.setDefaultAlertPrefs(defaults.copy(voice = true, voiceTarget = target)) }
                },
            )
            Caption(
                when (defaults.voiceTarget) {
                    VoiceTarget.Tv -> "This TV reads the alert out loud."
                    VoiceTarget.CameraSpeaker -> "Popups offer short replies on the camera speaker."
                    VoiceTarget.Both -> "This TV reads the alert. Popups can also reply on the camera."
                },
            )
            if (speakerCameras.isNotEmpty()) {
                SpeakerTestPicker(
                    cameras = speakerCameras,
                    selectedId = testSpeakerCameraId,
                    expanded = speakerPickerOpen,
                    onToggle = { speakerPickerOpen = !speakerPickerOpen },
                    onSelect = { id ->
                        testSpeakerCameraId = id
                        speakerPickerOpen = false
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                AptButton(
                    text = "Test TV voice",
                    icon = AptIconKind.Tv,
                    onClick = {
                        scope.launch {
                            voiceTestStatus = null
                            AlertController.clearVoiceFailure()
                            runCatching { container.speech.playOnTv("Test alert from UPort.") }
                                .onSuccess { voiceTestStatus = "TV voice played." }
                                .onFailure {
                                    val message = it.message ?: "TV voice failed."
                                    voiceTestStatus = message
                                    AlertController.reportVoiceFailure(message)
                                }
                        }
                    },
                )
                AptButton(
                    text = "Test camera speaker",
                    icon = AptIconKind.Speaker,
                    enabled = testSpeakerCamera != null,
                    onClick = {
                        val camera = testSpeakerCamera ?: return@AptButton
                        scope.launch {
                            voiceTestStatus = null
                            AlertController.clearVoiceFailure()
                            container.protect.announce(camera.id, "Test alert from UPort.")
                                .onSuccess {
                                    voiceTestStatus = it
                                    AlertController.clearVoiceFailure()
                                }
                                .onFailure { voiceTestStatus = it.message ?: "Camera speaker failed." }
                        }
                    },
                )
            }
            if (speakerCameras.isEmpty()) {
                InlineMessage("No camera with a speaker was found on this console.", StatusTone.Neutral)
            }
            if (voiceTestStatus == null) {
                voiceFailure?.let { InlineMessage(it, StatusTone.Warning) }
            }
            voiceTestStatus?.let {
                InlineMessage(
                    it,
                    tone = if (it.contains("failed", ignoreCase = true)) StatusTone.Warning else StatusTone.Online,
                )
            }
        }
    }
}

@Composable
private fun SpeakerTestPicker(
    cameras: List<Camera>,
    selectedId: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val labels = cameras.map { it.name }
    val selected = cameras.firstOrNull { it.id == selectedId }
    AptDropdown(
        title = "Test speaker on",
        value = selected?.name ?: "Choose a camera",
        options = labels,
        expanded = expanded,
        onToggle = onToggle,
        onSelect = { label ->
            cameras.firstOrNull { it.name == label }?.id?.let(onSelect)
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Popups
// ---------------------------------------------------------------------------------------------

@Composable
private fun PopupsPage(container: AppContainer, saved: AppSettings) {
    val scope = rememberCoroutineScope()
    SettingsList {
        item(key = "look") {
            SettingsSection(title = "Look and size") {
                ControlCaption("Look")
                AptSegmented(
                    options = PopupLook.entries,
                    selected = saved.popupLook,
                    label = { it.label },
                    onSelect = { look -> scope.editSettings(container) { it.copy(popupLook = look) } },
                )
                Caption(
                    if (saved.popupLook == PopupLook.Minimal) {
                        "Picture only. Buttons sit on the picture."
                    } else {
                        "Title, picture, and buttons."
                    },
                )
                ControlCaption("Size")
                AptSegmented(
                    options = PopupSize.entries,
                    selected = saved.popupSize,
                    label = { it.label },
                    onSelect = { size -> scope.editSettings(container) { it.copy(popupSize = size) } },
                )
            }
        }
        item(key = "picture") {
            SettingsSection(title = "Picture") {
                AptSegmented(
                    options = PopupPicture.entries,
                    selected = saved.popupPicture,
                    label = { it.label },
                    onSelect = { picture -> scope.editSettings(container) { it.copy(popupPicture = picture) } },
                )
                Caption(
                    if (saved.popupPicture == PopupPicture.Snapshot) {
                        "A still picture. No stream starts."
                    } else {
                        "A live stream. A still shows if the stream fails."
                    },
                )
                if (saved.popupPicture == PopupPicture.Live) {
                    ControlCaption("Stream quality")
                    AptSegmented(
                        options = StreamQuality.entries,
                        selected = saved.pipStreamQuality,
                        label = { it.label },
                        onSelect = { quality -> scope.editSettings(container) { it.copy(pipStreamQuality = quality) } },
                    )
                }
            }
        }
        item(key = "timing") {
            SettingsSection(title = "On screen") {
                ControlCaption("How many at once")
                AptSegmented(
                    options = (PopupLayout.MIN_OPEN..PopupLayout.MAX_OPEN).toList(),
                    selected = coerceOpenCount(saved.maxOpenPopups),
                    label = { it.toString() },
                    onSelect = { count -> scope.editSettings(container) { it.copy(maxOpenPopups = count) } },
                )
                ControlCaption("Close if untouched")
                AptSegmented(
                    options = PopupLayout.FADE_CHOICES,
                    selected = coerceFadeSeconds(saved.alertFadeSeconds),
                    label = { seconds -> if (seconds >= 60) "1 min" else "${seconds}s" },
                    onSelect = { seconds -> scope.editSettings(container) { it.copy(alertFadeSeconds = seconds) } },
                )
                Caption("A popup you used stays until you close it.")
            }
        }
        item(key = "corner") {
            SettingsSection(title = "Corner") {
                CornerPicker(
                    selected = saved.alertCorner,
                    onSelect = { corner -> scope.editSettings(container) { it.copy(alertCorner = corner) } },
                )
            }
        }
        item(key = "style") {
            SettingsSection(title = "Style") {
                AptSegmented(
                    options = AlertStyle.entries,
                    selected = saved.alertStyle,
                    label = { it.name },
                    onSelect = { style -> scope.editSettings(container) { it.copy(alertStyle = style) } },
                )
                Caption(
                    if (saved.alertStyle == AlertStyle.Picture) {
                        "A card with the camera over other apps."
                    } else {
                        "A compact notification banner."
                    },
                )
            }
        }
    }
}

@Composable
private fun CornerPicker(selected: AlertCorner, onSelect: (AlertCorner) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AptSpace.md)) {
        Column(
            modifier = Modifier
                .width(336.dp)
                .height(196.dp)
                .background(AptColors.SurfaceSunken, RoundedCornerShape(AptRadius.card))
                .border(1.dp, AptColors.HairlineStrong, RoundedCornerShape(AptRadius.card))
                .padding(AptSpace.sm),
            verticalArrangement = Arrangement.spacedBy(AptSpace.xs),
        ) {
            for (row in 0..2) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AptSpace.xs)) {
                    for (column in 0..2) {
                        val corner = cornerAt(row, column)
                        if (corner == null) {
                            Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                        } else {
                            CornerCell(
                                corner = corner,
                                selected = corner == selected,
                                onClick = { onSelect(corner) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(AptSpace.xxs)) {
            Text(selected.label(), fontSize = AptType.heading, fontWeight = FontWeight.SemiBold, color = AptColors.OnBackground)
            Text("Popups appear here", fontSize = AptType.body, color = AptColors.Secondary)
        }
    }
}

@Composable
private fun CornerCell(corner: AlertCorner, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .aptFocusFrame(focused = focused, shape = shape, scale = false, idleBorder = AptColors.Hairline)
            .clip(shape)
            .background(
                when {
                    selected -> AptColors.Primary
                    focused -> AptColors.SurfaceOverlay
                    else -> AptColors.SurfaceRaised
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .fillMaxHeight(0.5f)
                    .background(AptColors.OnPrimary.copy(alpha = 0.9f), RoundedCornerShape(3.dp)),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Phrases
// ---------------------------------------------------------------------------------------------

@Composable
private fun PhrasesPage(container: AppContainer, saved: AppSettings) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val custom = saved.customSpeakerPhrases
    SettingsList {
        item(key = "mode") {
            SettingsSection(title = "Speaker lines", description = "Shown when an alert offers to speak on the camera.") {
                AptSegmented(
                    options = listOf(false, true),
                    selected = saved.useCustomSpeakerPhrases,
                    label = { if (it) "My phrases" else "Defaults" },
                    onSelect = { customList -> scope.editSettings(container) { it.copy(useCustomSpeakerPhrases = customList) } },
                )
            }
        }
        if (!saved.useCustomSpeakerPhrases) {
            item(key = "door") { PhrasePreview("Doorbell", defaultDoorPhrases()) }
            item(key = "camera") { PhrasePreview("Cameras", defaultCameraPhrases()) }
        } else {
            item(key = "add") {
                AptCard(title = "Add a phrase", subtitle = "Up to $MAX_SPEAKER_PHRASES. The short name is the button.") {
                    AptTextField(
                        label = "Button",
                        value = label,
                        onValue = { label = it.take(PHRASE_LABEL_LIMIT) },
                        placeholder = "On the way",
                        keyboardType = KeyboardType.Text,
                    )
                    AptTextField(
                        label = "What the camera says",
                        value = message,
                        onValue = { message = it.take(PHRASE_MESSAGE_LIMIT) },
                        placeholder = "Someone is on the way to open the door.",
                        keyboardType = KeyboardType.Text,
                    )
                    AptButton(
                        text = "Add phrase",
                        icon = AptIconKind.Check,
                        style = AptButtonStyle.Primary,
                        enabled = label.isNotBlank() && message.isNotBlank() && custom.size < MAX_SPEAKER_PHRASES,
                        onClick = {
                            val next = (custom + SpeakerPhrase(label, message)).cleanPhrases()
                            scope.launch {
                                container.saveSettings(container.settings.first().copy(customSpeakerPhrases = next))
                                label = ""
                                message = ""
                            }
                        },
                    )
                }
            }
            if (custom.isEmpty()) {
                item(key = "none") {
                    Text(
                        "Alerts keep the default lines until you add one.",
                        fontSize = AptType.body,
                        color = AptColors.Secondary,
                    )
                }
            } else {
                items(custom, key = { "phrase-${it.label}-${it.message}" }) { phrase ->
                    AptCard(title = phrase.label, subtitle = phrase.message) {
                        AptButton(
                            text = "Remove",
                            icon = AptIconKind.Close,
                            style = AptButtonStyle.Ghost,
                            onClick = {
                                scope.editSettings(container) { latest ->
                                    latest.copy(customSpeakerPhrases = latest.customSpeakerPhrases.filterNot { it == phrase })
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
private fun PhrasePreview(title: String, phrases: List<SpeakerPhrase>) {
    AptCard(title = title) {
        phrases.forEach { phrase ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(phrase.label, fontSize = AptType.body, fontWeight = FontWeight.Medium, color = AptColors.OnBackground)
                Text(phrase.message, fontSize = AptType.label, color = AptColors.Secondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cameras
// ---------------------------------------------------------------------------------------------

@Composable
private fun CamerasPage(container: AppContainer, saved: AppSettings, cameras: Feed<Camera>) {
    val scope = rememberCoroutineScope()
    SettingsList {
        if (cameras.items.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "No cameras yet",
                    message = (cameras.availability as? Availability.NeedsSetup)?.message?.ifBlank { null }
                        ?: "Cameras appear here once the console is connected.",
                    icon = AptIconKind.Camera,
                    expand = false,
                )
            }
            return@SettingsList
        }
        item(key = "intro") {
            SettingsSection(
                title = "Guided setup",
                description = "One question at a time, with the live picture on screen.",
            ) {
                AptButton(
                    text = "Set up all cameras",
                    style = AptButtonStyle.Primary,
                    onClick = { CameraWizardBus.openAll() },
                )
            }
        }
        items(cameras.items, key = { it.id }) { camera ->
            val custom = camera.id in saved.cameraAlertPrefs
            AptCard(
                title = camera.name,
                subtitle = camera.model.ifBlank { if (camera.isDoorbell) "Doorbell" else "Camera" },
                trailing = { if (custom) StatusPill("Own rules", StatusTone.Info) },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                    AptButton(
                        text = "Set up this camera",
                        onClick = { CameraWizardBus.open(camera.id) },
                    )
                    if (custom) {
                        AptButton(
                            text = "Use default rules",
                            icon = AptIconKind.Retry,
                            style = AptButtonStyle.Ghost,
                            onClick = { scope.launch { container.setCameraAlertPrefs(camera.id, null) } },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Setup: site, account, alert check
// ---------------------------------------------------------------------------------------------

@Composable
private fun SetupPage(
    container: AppContainer,
    saved: AppSettings,
    cameras: List<Camera>,
    phoneSetup: LocalAlertSetupServer,
    verifyResult: AlertVerifyResult?,
    verifying: Boolean,
    onVerify: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val mode by container.protect.connectionMode.collectAsStateWithLifecycle(initialValue = ConnectionMode.Unconfigured)
    val detail by container.connectionDetail.collectAsStateWithLifecycle(initialValue = "")
    val vpn by container.vpnStatus.collectAsStateWithLifecycle(initialValue = VpnStatus())
    val trustValue = saved.trustConsoleCertificate

    var selectedCameraId by remember { mutableStateOf(cameras.firstOrNull()?.id) }
    LaunchedEffect(cameras) {
        if (selectedCameraId == null || cameras.none { it.id == selectedCameraId }) {
            selectedCameraId = cameras.firstOrNull()?.id
        }
    }
    val context = LocalContext.current
    val (diagnosis, recheck) = rememberAlertDiagnosis()
    val phoneUrl by phoneSetup.url.collectAsStateWithLifecycle()
    val phoneStatus by phoneSetup.status.collectAsStateWithLifecycle()
    val phoneLinked by phoneSetup.verified.collectAsStateWithLifecycle()

    SettingsList {
        item(key = "site") {
            AptCard(title = "Site", trailing = { ModeBadge(mode) }) {
                Text(
                    detail.ifBlank {
                        when (mode) {
                            ConnectionMode.OnNetwork -> "The console is reachable on this network."
                            ConnectionMode.Remote -> "Connected away from home."
                            ConnectionMode.Demo -> "Showing demo data."
                            ConnectionMode.Unconfigured -> "This TV connects from the site file."
                        }
                    },
                    fontSize = AptType.body,
                    color = AptColors.Secondary,
                )
                InfoRow("Tailscale", if (saved.vpnKind == SiteVpnKind.Tailscale) vpn.stateLabel.ifBlank { "Saved" } else "None")
                if (saved.vpnKind == SiteVpnKind.Tailscale && vpn.summary.isNotBlank()) {
                    Text(vpn.summary, fontSize = AptType.body, color = AptColors.Secondary)
                }
                AptSwitchRow(
                    label = "Trust console certificate",
                    description = if (trustValue) "Accepts this console's self-signed certificate." else "Uses system trust only.",
                    checked = trustValue,
                    icon = AptIconKind.Lock,
                    onToggle = { scope.editSettings(container) { it.copy(trustConsoleCertificate = !trustValue) } },
                )
            }
        }
        val account = listOf(
            "Name" to saved.accountName,
            "Email" to saved.accountEmail,
            "Role" to saved.accountRole,
        ).filter { it.second.isNotBlank() }
        if (account.isNotEmpty()) {
            item(key = "account") {
                AptCard(title = "Account") {
                    account.forEach { (label, value) -> InfoRow(label, value) }
                }
            }
        }
        item(key = "phone") {
            AptCard(
                title = "Phone link",
                subtitle = "Open this address on your phone while it is on the same Wi-Fi.",
            ) {
                val url = phoneUrl
                if (url.isNullOrBlank()) {
                    InlineMessage(phoneStatus, StatusTone.Neutral)
                } else {
                    Text(
                        text = url,
                        fontSize = AptType.display,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = AptColors.OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AptColors.SurfaceSunken, RoundedCornerShape(AptRadius.button))
                            .padding(horizontal = AptSpace.md, vertical = AptSpace.sm),
                    )
                    InlineMessage(
                        text = if (phoneLinked) "Phone linked." else phoneStatus,
                        tone = if (phoneLinked) StatusTone.Online else StatusTone.Neutral,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                        AptButton(text = "New code", onClick = { phoneSetup.refreshCode() }, icon = AptIconKind.Retry)
                    }
                }
            }
        }
        if (cameras.isNotEmpty()) {
            item(key = "test-camera") {
                AptCard(title = "Test camera", subtitle = "The test popup uses this camera. Move up and down, then press select.") {
                    Column(verticalArrangement = Arrangement.spacedBy(AptSpace.xs)) {
                        cameras.forEach { camera ->
                            val picked = camera.id == selectedCameraId
                            AptFocusSurface(
                                onClick = { selectedCameraId = camera.id },
                                selected = picked,
                                scale = false,
                                fill = AptColors.SurfaceSunken,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(AptSize.control)
                                        .padding(horizontal = AptSpace.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
                                ) {
                                    Text(
                                        text = camera.name.ifBlank { "Camera" },
                                        modifier = Modifier.weight(1f),
                                        fontSize = AptType.body,
                                        fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium,
                                        color = AptColors.OnBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (picked) {
                                        AptIcon(AptIconKind.Check, modifier = Modifier.size(AptSize.iconSmall), tint = AptColors.Primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "diagnosis") {
            AlertDiagnosisPanel(
                report = diagnosis,
                onRecheck = recheck,
                onSystemAction = { action ->
                    when (action) {
                        DiagnosisAction.OpenOverlaySettings -> AlertPermissions.openOverlaySettings(context)
                        DiagnosisAction.OpenAccessibilitySettings -> AlertPermissions.openAccessibilitySettings(context)
                    }
                },
            )
        }
        item(key = "test-alert") {
            AptCard(title = "Test alert", subtitle = "Sends one popup so you can see where it shows up.") {
                if (verifyResult != null) {
                    InlineMessage(
                        text = AlertSetupVerifier.message(verifyResult),
                        tone = when (verifyResult) {
                            AlertVerifyResult.NeedsPermission -> StatusTone.Warning
                            AlertVerifyResult.Running -> StatusTone.Neutral
                            else -> StatusTone.Online
                        },
                    )
                }
                AptButton(
                    text = if (verifying) "Testing alert..." else "Test alert",
                    style = AptButtonStyle.Primary,
                    icon = AptIconKind.Bell,
                    enabled = !verifying,
                    onClick = { onVerify(selectedCameraId) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------

@Composable
private fun AboutPage(container: AppContainer, saved: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingName by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(saved.locationName) }
    var resetArmed by remember { mutableStateOf(false) }
    LaunchedEffect(resetArmed) {
        if (!resetArmed) return@LaunchedEffect
        delay(RESET_CONFIRM_MS)
        resetArmed = false
    }
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName ?: "dev"} ($code)"
        }.getOrDefault("Unknown")
    }
    SettingsList {
        item(key = "name") {
            if (editingName) {
                SettingEditCard(
                    title = "Site name",
                    fieldLabel = "Name",
                    value = editValue,
                    onValue = { editValue = it },
                    placeholder = "UPort",
                    onSave = {
                        scope.launch {
                            val latest = container.settings.first()
                            container.saveSettings(latest.copy(locationName = editValue.trim().ifBlank { "UPort" }))
                            editingName = false
                        }
                    },
                    onCancel = { editingName = false },
                )
            } else {
                SettingsSection(title = "Site name") {
                    SettingFieldButton(
                        label = "Name",
                        value = saved.locationName.ifBlank { "Not set" },
                        description = "Shown in the rail and on live view.",
                        onClick = {
                            editValue = saved.locationName
                            editingName = true
                        },
                    )
                }
            }
        }
        item(key = "app") {
            AptCard(title = "UPort", subtitle = "UniFi Protect for the living room TV.") {
                InfoRow("Version", version)
                InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                InfoRow("Platform", if (TvDevice.isFireTv()) "Fire TV" else "Android TV")
                InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
        }
        item(key = "reset") {
            SettingsSection(title = "Reset", description = "Forgets the site file and sign-in. Setup starts over.") {
                AptButton(
                    text = if (resetArmed) "Press again to reset" else "Reset this TV",
                    style = AptButtonStyle.Danger,
                    icon = AptIconKind.Warning,
                    onClick = {
                        if (resetArmed) {
                            resetArmed = false
                            scope.launch { container.resetSite() }
                        } else {
                            resetArmed = true
                        }
                    },
                )
            }
        }
    }
}

private const val RESET_CONFIRM_MS = 5_000L
