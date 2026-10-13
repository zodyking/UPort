package app.apt443.domain

enum class ConnectionMode {
    Demo,
    OnNetwork,
    Remote,
    Unconfigured,
}

enum class DataSourceMode {
    Demo,
    Live,
}

/** Whether the TV is still on the sign-in screen, previewing, or signed into the console. */
enum class AccountGate {
    SignedOut,
    Demo,
    Console,
}

/**
 * How this TV authenticated to the console.
 *
 * [ApiKeyOnly] — Protect integration key: live cameras, snapshots, lights.
 * [LocalUserAndApiKey] — local UniFi OS admin plus key: also recording export and OS profile.
 *
 * Cloud ui.com accounts are not supported for the local-user path.
 */
enum class ConsoleSignInMode {
    ApiKeyOnly,
    LocalUserAndApiKey,
}

/** Picture card with video, or a short text popup. */
enum class AlertStyle {
    Picture,
    Notification,
}

/** Card keeps the title and buttons. Minimal is the picture alone, with actions on top of it. */
enum class PopupLook(val label: String) {
    Card("Card"),
    Minimal("Minimal"),
}

/** How large one popup is. Small is the default. */
enum class PopupSize(val label: String, val fraction: Float, val maxDp: Float) {
    Small("Small", 0.18f, 190f),
    Medium("Medium", 0.24f, 240f),
    Large("Large", 0.30f, 280f),
}

/** Live opens a stream and falls back to a snapshot. Snapshot never starts a stream. */
enum class PopupPicture(val label: String) {
    Live("Live"),
    Snapshot("Snapshot"),
}

/**
 * One camera kept on screen. [x] and [y] are the top-left as fractions of the screen.
 * [width] and [height] are fractions of the screen. The picture uses the popup layout in [look].
 */
data class PersistentCamera(
    val cameraId: String,
    val look: PopupLook = PopupLook.Card,
    val picture: PopupPicture = PopupPicture.Live,
    val x: Float = 0.64f,
    val y: Float = 0.06f,
    val width: Float = 0.28f,
    val height: Float = 0.32f,
) {
    fun placed(): PersistentCamera {
        val w = width.coerceIn(MIN_FRACTION, MAX_FRACTION)
        val h = height.coerceIn(MIN_FRACTION, MAX_FRACTION)
        return copy(
            x = x.coerceIn(0f, 1f - w),
            y = y.coerceIn(0f, 1f - h),
            width = w,
            height = h,
        )
    }

    private companion object {
        const val MIN_FRACTION = 0.08f
        const val MAX_FRACTION = 0.92f
    }
}

/** Where UPort draws its own alert. Fire TV's system banner cannot be moved. */
enum class AlertCorner {
    TopLeft,
    TopCenter,
    TopRight,
    Center,
    BottomLeft,
    BottomCenter,
    BottomRight,
}

/** Profile returned by the UniFi OS console for the signed-in user. */
data class ConsoleAccount(
    val displayName: String,
    val username: String,
    val email: String,
    val role: String,
)

sealed interface SignInResult {
    data class SignedIn(val account: ConsoleAccount) : SignInResult

    data class Failed(val message: String, val untrustedCertificate: Boolean) : SignInResult
}

/** How Live mode chooses LAN versus cloud when the console may be unreachable. */
enum class ReachPreference {
    Auto,
    LanOnly,
    RemoteOnly,
}

/**
 * VPN named by the site file.
 * [Tailscale] is the auth key from the site file. It is not shown after setup.
 * [WireGuard] is leftover storage from older builds and is not used.
 */
enum class SiteVpnKind {
    None,
    Tailscale,
    WireGuard,
}

enum class ProtectEventType {
    Ring,
    Motion,
    SmartDetect,
    Sensor,
    Disconnect,
    Other,
}

enum class AlertKind {
    Doorbell,
    Motion,
    SmartDetect,
    AccessDoor,
}

enum class AccessEventType {
    Unlock,
    Doorbell,
    Visitor,
    Other,
}

sealed interface Availability {
    data object Ready : Availability

    data class Cached(val atEpochMs: Long) : Availability

    data class HomeNetworkOnly(val message: String) : Availability

    data class NeedsSetup(val message: String) : Availability
}

data class Feed<T>(
    val items: List<T>,
    val availability: Availability,
    val detail: String = "",
)

enum class StreamQuality(val api: String, val label: String) {
    High("high", "High"),
    Medium("medium", "Medium"),
    Low("low", "Low"),
}

enum class PtzMove {
    Left,
    Right,
    Up,
    Down,
    ZoomIn,
    ZoomOut,
}

data class Camera(
    val id: String,
    val name: String,
    val online: Boolean,
    val isDoorbell: Boolean,
    val model: String,
    val hasSpeaker: Boolean = false,
    val isPtz: Boolean = false,
)

data class ProtectLight(
    val id: String,
    val name: String,
    val on: Boolean,
    val mode: String,
)

data class ProtectSensor(
    val id: String,
    val name: String,
    val status: String,
    val alarming: Boolean,
)

/** Playback filter. [kind] uses the labels already shown on the recordings screen. */
data class ProtectClipQuery(
    val cameraId: String? = null,
    val kind: String = "All events",
    val startEpochMs: Long? = null,
    val endEpochMs: Long? = null,
)

data class ProtectEvent(
    val id: String,
    val cameraId: String?,
    val cameraName: String,
    val type: ProtectEventType,
    val smartLabels: List<String>,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val summary: String,
    val alarming: Boolean = false,
    /** Protect thumbnail id for this clip. Empty when the console did not send one. */
    val thumbnailId: String = "",
)

/**
 * One on-screen alert. The foreground service draws this over other apps when
 * overlay permission is granted. [streamUrl] is set only for a direct console
 * stream. Remote alerts keep the URL null and may carry a JPEG snapshot.
 */
data class AlertInset(
    val id: String,
    val title: String,
    val subtitle: String,
    val cameraId: String?,
    val cameraName: String = "",
    val kind: AlertKind,
    val streamUrl: String? = null,
    val snapshotJpeg: ByteArray? = null,
    val doorId: String? = null,
    val doorName: String? = null,
    val hasSpeaker: Boolean = false,
    val detections: Set<DetectionType> = emptySet(),
)

data class FootageClip(
    val id: String,
    val cameraId: String,
    val label: String,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val kind: String,
)

data class LiveStreamOffer(
    val url: String?,
    val availability: Availability,
    val message: String,
)

data class PlaybackOffer(
    val url: String?,
    val availability: Availability,
    val message: String,
)

data class WanStatus(
    val up: Boolean?,
    val ispName: String,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val latencyMs: Double?,
    /** Live WAN throughput, in megabits per second. */
    val liveDownMbps: Double? = null,
    val liveUpMbps: Double? = null,
    /** idle, running, or blank when the console did not say. */
    val speedtest: String = "",
)

data class NetworkDevice(
    val id: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val adopted: Boolean,
    /** Access point, Switch, Gateway, or Device. */
    val kind: String = "Device",
    val ip: String = "",
    val mac: String = "",
    val clientCount: Int? = null,
)

data class NetworkClient(
    val id: String,
    val name: String,
    val ip: String,
    val connection: String,
    val online: Boolean,
    val mac: String = "",
    val ssid: String = "",
    /** Access point name once it can be matched, otherwise the AP address. */
    val accessPoint: String = "",
    val signalDbm: Int? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
)

data class NetworkDashboard(
    val wan: WanStatus,
    val devices: List<NetworkDevice>,
    val clients: List<NetworkClient>,
    val availability: Availability,
    val detail: String,
)

data class Door(
    val id: String,
    val name: String,
    val locked: Boolean?,
    val position: String,
)

data class AccessDevice(
    val id: String,
    val name: String,
    /** Hub, Reader, Intercom, or Device. */
    val kind: String,
    val online: Boolean,
)

data class AccessEvent(
    val id: String,
    val doorName: String,
    val actor: String,
    val type: AccessEventType,
    val result: String,
    val epochMs: Long,
)

data class AccessFeed(
    val doors: List<Door>,
    val events: List<AccessEvent>,
    val availability: Availability,
    val unlockAvailability: Availability,
    val detail: String,
    val devices: List<AccessDevice> = emptyList(),
)

data class AppSettings(
    val locationName: String = "UPort",
    val consoleHost: String = "",
    val consoleId: String = "",
    val networkSiteId: String = "default",
    val dataSource: DataSourceMode = DataSourceMode.Demo,
    val reachPreference: ReachPreference = ReachPreference.Auto,
    /**
     * False until this TV answers same-network vs remote during setup.
     * Existing configured sites are treated as already answered.
     */
    val reachChosen: Boolean = false,
    /** Non-secret. The auth key and WireGuard configuration stay in the keystore. */
    val vpnKind: SiteVpnKind = SiteVpnKind.None,
    /** Optional Headscale or custom coordination URL. Not the auth key. */
    val tailscaleLoginServer: String = "",
    val trustConsoleCertificate: Boolean = false,
    val osUsername: String = "",
    val accountName: String = "",
    val accountEmail: String = "",
    val accountRole: String = "",
    /** Set after a site package is imported; console secrets live in the keystore. */
    val siteConfigured: Boolean = false,
    val identityUserId: String = "",
    val residentFirstName: String = "",
    val residentLastName: String = "",
    val residentStatus: String = "",
    val accountGate: AccountGate = AccountGate.SignedOut,
    val signInMode: ConsoleSignInMode = ConsoleSignInMode.ApiKeyOnly,
    /**
     * Legacy, read-only for new code. Mirrors cameras whose [cameraAlertPrefs] override has
     * `popups = false`; it is rewritten from [cameraAlertPrefs] on every save.
     */
    val pipMutedCameraIds: Set<String> = emptySet(),
    val alertStyle: AlertStyle = AlertStyle.Picture,
    val alertCorner: AlertCorner = AlertCorner.TopRight,
    val popupLook: PopupLook = PopupLook.Card,
    val popupSize: PopupSize = PopupSize.Small,
    /** Live stream with a snapshot if it fails, or a snapshot with no stream. */
    val popupPicture: PopupPicture = PopupPicture.Live,
    /** Quality used when a popup opens a live stream. */
    val pipStreamQuality: StreamQuality = StreamQuality.Medium,
    /** How many camera popups may sit on screen together. */
    val maxOpenPopups: Int = PopupLayout.DEFAULT_OPEN,
    /** Untouched popups close after this many seconds. A used popup stays. */
    val alertFadeSeconds: Int = PopupLayout.DEFAULT_FADE_SECONDS,
    /** Epoch millis. Camera popups and their spoken lines stay hidden until then. */
    val alertQuietUntilEpochMs: Long = 0,
    /** Global voice switch. Per camera voice is [CameraAlertPrefs.voice]; see [shouldSpeak]. */
    val voiceAlerts: Boolean = false,
    val defaultAlertPrefs: CameraAlertPrefs = CameraAlertPrefs(),
    val cameraAlertPrefs: Map<String, CameraAlertPrefs> = emptyMap(),
    /** Identity user ids that have finished the per-camera alert wizard. */
    val cameraWizardDoneIds: Set<String> = emptySet(),
    /** When true, alerts speak [customSpeakerPhrases] instead of the built-in lines. */
    val useCustomSpeakerPhrases: Boolean = false,
    val customSpeakerPhrases: List<SpeakerPhrase> = emptyList(),
    /** Cameras drawn all the time. These do not also raise popups. */
    val persistentCameras: List<PersistentCamera> = emptyList(),
    /** Turns off the TV sleep timer and keeps the app screen lit. */
    val keepTvAwake: Boolean = true,
    /** Registers the camera wall as the system screensaver. */
    val cameraScreensaver: Boolean = true,
    /** Full-screen live view and recordings pause while any popup is open. */
    val pausePlaybackOnAlert: Boolean = false,
)

class FeatureBlockedException(message: String) : Exception(message)

fun SiteVpnKind.label(): String = when (this) {
    SiteVpnKind.None -> "None"
    SiteVpnKind.Tailscale -> "Tailscale"
    SiteVpnKind.WireGuard -> "WireGuard"
}

fun ConnectionMode.label(): String = when (this) {
    ConnectionMode.Demo -> "Demo"
    ConnectionMode.OnNetwork -> "On network"
    ConnectionMode.Remote -> "Remote"
    ConnectionMode.Unconfigured -> "Not configured"
}

fun ProtectEvent.matchesKind(kind: String): Boolean {
    val labels = smartLabels.map { it.lowercase() }
    return when (kind) {
        "Motion" -> type == ProtectEventType.Motion
        "Ring" -> type == ProtectEventType.Ring
        "Person" -> "person" in labels
        "Vehicle" -> "vehicle" in labels
        "Package" -> "package" in labels
        "Animal" -> "animal" in labels
        else -> true
    }
}

fun Availability.explain(): String = when (this) {
    Availability.Ready -> ""
    is Availability.Cached -> "Last known"
    is Availability.HomeNetworkOnly -> message
    is Availability.NeedsSetup -> message
}
