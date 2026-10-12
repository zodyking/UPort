package app.apt443.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.Camera
import app.apt443.domain.Feed
import app.apt443.domain.PersistentCamera
import app.apt443.domain.PopupLook
import app.apt443.domain.PopupPicture
import app.apt443.ui.components.AptSegmented
import app.apt443.ui.components.AptSwitchRow
import app.apt443.ui.components.ControlCaption
import app.apt443.ui.components.SettingsSection
import app.apt443.ui.components.aptPanel
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.launch

private const val NUDGE = 0.01f

private enum class AdjustMode(val label: String) { Move("Move"), Size("Size") }

@Composable
fun PersistentPage(container: AppContainer, saved: AppSettings, cameras: Feed<Camera>) {
    val scope = rememberCoroutineScope()
    val known = cameras.items.filter { it.id.isNotBlank() }
    val extra = saved.persistentCameras.map { it.cameraId }.filter { id -> known.none { it.id == id } }
    val rows = known.map { it.id to it.name.ifBlank { "Camera" } } + extra.map { it to "Saved camera" }
    SettingsList {
        item(key = "intro") {
            SettingsSection(
                title = "Always on screen",
                description = "These stay up like a popup. They do not also pop up.",
            ) {
                if (rows.isEmpty()) {
                    Text("Cameras appear here once the console lists them.", fontSize = AptType.body, color = AptColors.Secondary)
                }
            }
        }
        items(rows, key = { it.first }) { (cameraId, name) ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                PersistentCameraEditor(
                    container = container,
                    saved = saved,
                    cameraId = cameraId,
                    name = name,
                    scope = scope,
                )
            }
        }
    }
}

@Composable
private fun PersistentCameraEditor(
    container: AppContainer,
    saved: AppSettings,
    cameraId: String,
    name: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val item = saved.persistentCameras.firstOrNull { it.cameraId == cameraId }
    val on = item != null
    AptSwitchRow(
        label = name,
        description = if (on) "On screen. Popups for this camera stay off." else "Off",
        checked = on,
        onToggle = {
            scope.launch {
                if (on) {
                    container.removePersistent(cameraId)
                } else {
                    container.upsertPersistent(freshPersistent(saved, cameraId))
                }
            }
        },
    )
    if (item == null) return
    ControlCaption("Layout")
    AptSegmented(
        options = PopupLook.entries,
        selected = item.look,
        label = { it.label },
        onSelect = { look -> scope.launch { container.upsertPersistent(item.copy(look = look)) } },
    )
    Text(
        text = if (item.look == PopupLook.Minimal) "Picture only, with the camera name on it." else "Name, then the picture. Same layout as a popup.",
        fontSize = AptType.caption,
        color = AptColors.Tertiary,
    )
    ControlCaption("Picture")
    AptSegmented(
        options = PopupPicture.entries,
        selected = item.picture,
        label = { it.label },
        onSelect = { picture -> scope.launch { container.upsertPersistent(item.copy(picture = picture)) } },
    )
    PlacementEditor(item = item, onChange = { next -> scope.launch { container.upsertPersistent(next) } })
}

@Composable
private fun PlacementEditor(item: PersistentCamera, onChange: (PersistentCamera) -> Unit) {
    var adjusting by remember(item.cameraId) { mutableStateOf(false) }
    var mode by remember(item.cameraId) { mutableStateOf(AdjustMode.Move) }
    val focus = remember(item.cameraId) { FocusRequester() }
    BackHandler(enabled = adjusting) { adjusting = false }
    AptSwitchRow(
        label = "Place and size",
        description = if (adjusting) {
            "${mode.label}. Select switches to ${if (mode == AdjustMode.Move) "size" else "move"}. Back leaves this."
        } else {
            "Left ${percent(item.x)}, top ${percent(item.y)}, ${percent(item.width)} wide, ${percent(item.height)} tall."
        },
        checked = adjusting,
        onToggle = { adjusting = !adjusting },
    )
    if (!adjusting) return
    LaunchedEffect(adjusting) { runCatching { focus.requestFocus() } }
    Spacer(modifier = Modifier.height(AptSpace.xs))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aptPanel(fill = AptColors.SurfaceSunken)
            .padding(AptSpace.lg)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        mode = if (mode == AdjustMode.Move) AdjustMode.Size else AdjustMode.Move
                        true
                    }
                    Key.DirectionLeft -> {
                        onChange((if (mode == AdjustMode.Move) item.copy(x = item.x - NUDGE) else item.copy(width = item.width - NUDGE)).placed())
                        true
                    }
                    Key.DirectionRight -> {
                        onChange((if (mode == AdjustMode.Move) item.copy(x = item.x + NUDGE) else item.copy(width = item.width + NUDGE)).placed())
                        true
                    }
                    Key.DirectionUp -> {
                        onChange((if (mode == AdjustMode.Move) item.copy(y = item.y - NUDGE) else item.copy(height = item.height - NUDGE)).placed())
                        true
                    }
                    Key.DirectionDown -> {
                        onChange((if (mode == AdjustMode.Move) item.copy(y = item.y + NUDGE) else item.copy(height = item.height + NUDGE)).placed())
                        true
                    }
                    else -> false
                }
            },
    ) {
        Text(
            text = if (mode == AdjustMode.Move) "Moving" else "Sizing",
            fontSize = AptType.heading,
            fontWeight = FontWeight.SemiBold,
            color = AptColors.OnBackground,
        )
        Text(
            text = "The picture on screen follows the arrows. It will not take the remote once you leave.",
            fontSize = AptType.body,
            color = AptColors.Secondary,
        )
    }
}

private fun freshPersistent(settings: AppSettings, cameraId: String): PersistentCamera {
    val index = settings.persistentCameras.size
    return PersistentCamera(
        cameraId = cameraId,
        look = settings.popupLook,
        picture = settings.popupPicture,
        x = 0.04f + (index % 3) * 0.32f,
        y = 0.06f + (index / 3) * 0.36f,
    ).placed()
}

private fun percent(fraction: Float): String = "${(fraction * 100).toInt()}%"
