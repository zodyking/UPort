package app.apt443.ui.screens

import app.apt443.domain.AlertCorner
import app.apt443.domain.AppSettings
import app.apt443.domain.Camera
import app.apt443.domain.CameraAlertPrefs
import app.apt443.domain.DetectionType
import app.apt443.domain.alertPrefsFor
import app.apt443.ui.player.StreamPhase

/** Pure view-model helpers for the Settings and Live Feed screens. No Android classes. */

enum class SettingsPage(val title: String, val description: String) {
    General("General", "Sleep, screensaver, playback"),
    Alerts("Alerts", "Cameras, detections, quiet, voice"),
    Popups("Popups", "Look, size, timing, corner"),
    AlwaysOn("Always on", "Cameras that stay on screen"),
    Phrases("Phrases", "Lines the camera speaker says"),
    Cameras("Cameras", "Guided setup for each camera"),
    Setup("Setup", "Site, account, alert check"),
    About("About", "Version, site name, reset"),
}

/** Detection chips offered for a camera. Ring only makes sense on doorbells; Sensor is not a camera event. */
fun detectionChipsFor(camera: Camera): List<DetectionType> = DetectionType.entries.filter { type ->
    when (type) {
        DetectionType.Ring -> camera.isDoorbell
        DetectionType.Sensor -> false
        else -> true
    }
}

/** Chips offered for the defaults page: everything, since defaults also cover sensors. */
fun defaultDetectionChips(): List<DetectionType> = DetectionType.entries.toList()

fun CameraAlertPrefs.toggled(type: DetectionType): CameraAlertPrefs =
    copy(detections = if (allows(type)) detections - type else detections + type)

/**
 * Short explanation of how a camera's override differs from the defaults, or null when the
 * camera uses defaults (or an override identical to them).
 */
fun overrideSummary(prefs: CameraAlertPrefs, defaults: CameraAlertPrefs, offered: List<DetectionType>): String? {
    val parts = mutableListOf<String>()
    if (prefs.popups != defaults.popups) parts += if (prefs.popups) "Popups on" else "Popups off"
    if (prefs.voice != defaults.voice) parts += if (prefs.voice) "Voice on" else "Voice off"
    val relevant = offered.toSet()
    val off = relevant.filter { it !in prefs.detections && it in defaults.detections }
    val on = relevant.filter { it in prefs.detections && it !in defaults.detections }
    if (off.isNotEmpty() || on.isNotEmpty()) {
        val count = off.size + on.size
        parts += if (count == 1) {
            val type = (off + on).first()
            "${type.label} ${if (type in off) "off" else "on"}"
        } else {
            "$count detections changed"
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

data class CameraAlertRow(
    val camera: Camera,
    val prefs: CameraAlertPrefs,
    val hasOverride: Boolean,
    val chips: List<DetectionType>,
    val summary: String?,
)

fun cameraAlertRow(camera: Camera, settings: AppSettings): CameraAlertRow {
    val prefs = settings.alertPrefsFor(camera.id)
    val chips = detectionChipsFor(camera)
    val hasOverride = camera.id in settings.cameraAlertPrefs
    return CameraAlertRow(
        camera = camera,
        prefs = prefs,
        hasOverride = hasOverride,
        chips = chips,
        summary = if (hasOverride) overrideSummary(prefs, settings.defaultAlertPrefs, chips) else null,
    )
}

fun AlertCorner.label(): String = when (this) {
    AlertCorner.TopLeft -> "Top left"
    AlertCorner.TopCenter -> "Top center"
    AlertCorner.TopRight -> "Top right"
    AlertCorner.Center -> "Center"
    AlertCorner.BottomLeft -> "Bottom left"
    AlertCorner.BottomCenter -> "Bottom center"
    AlertCorner.BottomRight -> "Bottom right"
}

/** 3x3 placement grid, row-major. Middle-left and middle-right are not valid placements. */
fun cornerAt(row: Int, column: Int): AlertCorner? = when (row to column) {
    0 to 0 -> AlertCorner.TopLeft
    0 to 1 -> AlertCorner.TopCenter
    0 to 2 -> AlertCorner.TopRight
    1 to 1 -> AlertCorner.Center
    2 to 0 -> AlertCorner.BottomLeft
    2 to 1 -> AlertCorner.BottomCenter
    2 to 2 -> AlertCorner.BottomRight
    else -> null
}

enum class TileStatus(val label: String) {
    Live("Live"),
    Connecting("Connecting"),
    Reconnecting("Reconnecting"),
    Snapshots("Snapshots"),
    Offline("Offline"),
    Unavailable("No stream"),
}

/** Footer status for a grid tile from the engine phase and the camera's reported state. */
fun tileStatus(phase: StreamPhase?, online: Boolean): TileStatus = when {
    !online -> TileStatus.Offline
    phase == null || phase == StreamPhase.Connecting -> TileStatus.Connecting
    phase == StreamPhase.Live -> TileStatus.Live
    phase == StreamPhase.Reconnecting -> TileStatus.Reconnecting
    phase == StreamPhase.SnapshotOnly -> TileStatus.Snapshots
    else -> TileStatus.Unavailable
}

/**
 * Suggested grid columns for a content width in dp. A 1080p TV at TV density is about 960dp
 * wide, which leaves roughly 800dp for content: three columns. Very wide layouts get four.
 */
fun gridColumnsFor(widthDp: Int): Int = when {
    widthDp >= 1200 -> 4
    widthDp >= 640 -> 3
    else -> 2
}
