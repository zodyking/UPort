package app.apt443.domain

/** What a Protect event detected. One event can carry several smart detections. */
enum class DetectionType(val label: String) {
    Ring("Doorbell ring"),
    Motion("Motion"),
    Person("Person"),
    Vehicle("Vehicle"),
    Animal("Animal"),
    Package("Package"),
    Face("Face"),
    LicensePlate("License plate"),
    Sensor("Sensor"),
    Other("Other"),
}

/** Where a spoken alert comes out. */
enum class VoiceTarget(val label: String) {
    /** This TV's own speakers. */
    Tv("This TV"),

    /** Reply choices on the popup, played on the camera speaker. */
    CameraSpeaker("Camera speaker"),

    Both("TV and camera"),
}

/**
 * Alert rules for one camera (or the default for cameras without an override).
 * [popups] gates the overlay card and the fallback notification; [voice] gates TTS.
 */
data class CameraAlertPrefs(
    val popups: Boolean = true,
    val voice: Boolean = true,
    val voiceTarget: VoiceTarget = VoiceTarget.Tv,
    val detections: Set<DetectionType> = DetectionType.entries.toSet(),
    /**
     * Detections that are spoken. Null means every detection, which is how cameras
     * saved before the wizard behaved.
     */
    val spokenDetections: Set<DetectionType>? = null,
) {
    fun allows(type: DetectionType): Boolean = type in detections

    val speaksOnTv: Boolean get() = voiceTarget != VoiceTarget.CameraSpeaker

    val speaksOnCamera: Boolean get() = voiceTarget != VoiceTarget.Tv
}

fun smartLabelDetection(label: String): DetectionType? {
    return when (label.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")) {
        "person" -> DetectionType.Person
        "vehicle" -> DetectionType.Vehicle
        "animal" -> DetectionType.Animal
        "package" -> DetectionType.Package
        "face" -> DetectionType.Face
        "licenseplate", "plate" -> DetectionType.LicensePlate
        else -> null
    }
}

fun ProtectEvent.detectionTypes(): Set<DetectionType> = when (type) {
    ProtectEventType.Ring -> setOf(DetectionType.Ring)
    ProtectEventType.Motion -> setOf(DetectionType.Motion)
    ProtectEventType.SmartDetect -> {
        val mapped = smartLabels.map { smartLabelDetection(it) ?: DetectionType.Other }.toSet()
        mapped.ifEmpty { setOf(DetectionType.Other) }
    }
    ProtectEventType.Sensor -> setOf(DetectionType.Sensor)
    ProtectEventType.Disconnect, ProtectEventType.Other -> setOf(DetectionType.Other)
}

/** Wizard starting set. A camera with no saved rules begins at motion only. */
fun wizardTriggerSelection(settings: AppSettings, cameraId: String): Set<DetectionType> {
    val saved = settings.cameraAlertPrefs[cameraId] ?: return setOf(DetectionType.Motion)
    return saved.detections.ifEmpty { setOf(DetectionType.Motion) }
}

/** Cameras with no saved setup. A new console camera stays here until its wizard is finished. */
fun AppSettings.camerasNeedingSetup(cameras: List<Camera>): List<Camera> =
    cameras.filter { it.id.isNotBlank() && it.id !in cameraAlertPrefs }

fun AppSettings.alertPrefsFor(cameraId: String?): CameraAlertPrefs {
    if (cameraId.isNullOrBlank()) return defaultAlertPrefs
    return cameraAlertPrefs[cameraId] ?: defaultAlertPrefs
}

fun AppSettings.watchesPersistently(cameraId: String?): Boolean =
    !cameraId.isNullOrBlank() && persistentCameras.any { it.cameraId == cameraId }

fun AppSettings.shouldAlert(cameraId: String?, detections: Set<DetectionType>): Boolean {
    if (watchesPersistently(cameraId)) return false
    val prefs = alertPrefsFor(cameraId)
    if (!prefs.popups) return false
    return detections.any { prefs.allows(it) }
}

fun AppSettings.shouldAlert(event: ProtectEvent): Boolean =
    shouldAlert(event.cameraId, event.detectionTypes())

fun AppSettings.shouldSpeak(cameraId: String?): Boolean =
    shouldSpeak(cameraId, DetectionType.entries.toSet())

fun AppSettings.shouldSpeak(cameraId: String?, detections: Set<DetectionType>): Boolean {
    if (!voiceAlerts) return false
    val prefs = alertPrefsFor(cameraId)
    if (!prefs.voice || !prefs.speaksOnTv) return false
    val spoken = prefs.spokenDetections ?: return true
    return detections.any { it in spoken }
}

/**
 * Reply choices on a popup. This is the camera-speaker option from setup.
 * It does not announce the detection.
 */
fun AppSettings.cameraReplies(cameraId: String?, hardwareSpeaker: Boolean): Boolean {
    if (!hardwareSpeaker) return false
    val prefs = alertPrefsFor(cameraId)
    return prefs.voice && prefs.speaksOnCamera
}

/**
 * Folds the legacy comma list of muted cameras into per-camera overrides.
 * Explicit overrides always win; legacy ids without one become `popups = false`.
 */
fun migrateLegacyMutes(
    legacyMuted: Set<String>,
    overrides: Map<String, CameraAlertPrefs>,
): Map<String, CameraAlertPrefs> {
    if (legacyMuted.isEmpty()) return overrides
    val merged = overrides.toMutableMap()
    legacyMuted.filter { it.isNotBlank() && it !in overrides }.forEach { id ->
        merged[id] = CameraAlertPrefs(popups = false)
    }
    return merged
}

/** Legacy mirror of [AppSettings.cameraAlertPrefs]: cameras whose override turns popups off. */
fun legacyMutedIds(overrides: Map<String, CameraAlertPrefs>): Set<String> =
    overrides.filterValues { !it.popups }.keys

/** Human phrase for an alert, e.g. "Person detected". */
fun Set<DetectionType>.headline(): String {
    val order = listOf(
        DetectionType.Ring,
        DetectionType.Person,
        DetectionType.Vehicle,
        DetectionType.Package,
        DetectionType.Animal,
        DetectionType.Face,
        DetectionType.LicensePlate,
        DetectionType.Motion,
        DetectionType.Sensor,
    )
    val top = order.firstOrNull { it in this } ?: return "Alert"
    return when (top) {
        DetectionType.Ring -> "Doorbell ring"
        DetectionType.Sensor -> "Sensor alert"
        else -> "${top.label} detected"
    }
}
