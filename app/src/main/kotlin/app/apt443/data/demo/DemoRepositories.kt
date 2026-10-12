package app.apt443.data.demo

import app.apt443.domain.AccessDevice
import app.apt443.domain.AccessEvent
import app.apt443.domain.AccessEventType
import app.apt443.domain.AccessFeed
import app.apt443.domain.AccessRepository
import app.apt443.domain.AlertInset
import app.apt443.domain.AlertKind
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.ConnectionMode
import app.apt443.domain.DetectionType
import app.apt443.domain.Door
import app.apt443.domain.FeatureBlockedException
import app.apt443.domain.Feed
import app.apt443.domain.FootageClip
import app.apt443.domain.LiveStreamOffer
import app.apt443.domain.NetworkClient
import app.apt443.domain.NetworkDashboard
import app.apt443.domain.NetworkDevice
import app.apt443.domain.NetworkRepository
import app.apt443.domain.PlaybackOffer
import app.apt443.domain.ProtectClipQuery
import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType
import app.apt443.domain.ResidentAccess
import app.apt443.domain.ProtectLight
import app.apt443.domain.ProtectRepository
import app.apt443.domain.ProtectSensor
import app.apt443.domain.PtzMove
import app.apt443.domain.StreamQuality
import app.apt443.domain.WanStatus
import app.apt443.domain.matchesKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

class DemoProtectRepository : ProtectRepository {
    private val now = System.currentTimeMillis()
    private val alerts = MutableSharedFlow<AlertInset>(extraBufferCapacity = 4)

    private val cameraList = listOf(
        Camera("entry", "Entry", online = true, isDoorbell = true, model = "G4 Doorbell", hasSpeaker = true),
        Camera("lobby", "Lobby", online = true, isDoorbell = false, model = "G5 PTZ", isPtz = true),
        Camera("hall", "Hall", online = true, isDoorbell = false, model = "G5 Bullet"),
        Camera("parking", "Parking", online = false, isDoorbell = false, model = "G5 Dome"),
    )

    private val lightList = listOf(
        ProtectLight("yard", "Yard", on = false, mode = "Off"),
    )

    private val sensorList = listOf(
        ProtectSensor("front-sensor", "Front door", status = "Closed", alarming = false),
    )

    private val eventList = listOf(
        ProtectEvent("evt-ring", "entry", "Entry", ProtectEventType.Ring, emptyList(), now - 120_000, null, "Doorbell"),
        ProtectEvent(
            "evt-person",
            "lobby",
            "Lobby",
            ProtectEventType.SmartDetect,
            listOf("person"),
            now - 900_000,
            now - 840_000,
            "Person",
        ),
        ProtectEvent("evt-vehicle", "parking", "Parking", ProtectEventType.SmartDetect, listOf("vehicle"), now - 3_600_000, now - 3_500_000, "Vehicle"),
        ProtectEvent("evt-motion", "hall", "Hall", ProtectEventType.Motion, emptyList(), now - 7_200_000, now - 7_140_000, "Motion"),
        ProtectEvent("evt-animal", "lobby", "Lobby", ProtectEventType.SmartDetect, listOf("animal"), now - 14_400_000, now - 14_340_000, "Animal"),
        ProtectEvent("evt-package", "entry", "Entry", ProtectEventType.SmartDetect, listOf("package"), now - 86_400_000, now - 86_340_000, "Package"),
        ProtectEvent("evt-person-2", "hall", "Hall", ProtectEventType.SmartDetect, listOf("person"), now - 1_800_000, now - 1_740_000, "Person"),
        ProtectEvent("evt-vehicle-2", "parking", "Parking", ProtectEventType.SmartDetect, listOf("vehicle"), now - 600_000, now - 540_000, "Vehicle"),
    )

    override val connectionMode: Flow<ConnectionMode> = flowOf(ConnectionMode.Demo)

    override fun cameras(): Flow<Feed<Camera>> = flowOf(
        Feed(cameraList, Availability.Ready, "Sample cameras for UPort"),
    )

    override fun lights(): Flow<Feed<ProtectLight>> = flowOf(
        Feed(lightList, Availability.Ready, "Sample lights"),
    )

    override fun sensors(): Flow<Feed<ProtectSensor>> = flowOf(
        Feed(sensorList, Availability.Ready, "Sample sensors"),
    )

    override fun events(): Flow<Feed<ProtectEvent>> = flowOf(
        Feed(eventList, Availability.Ready, "Sample events"),
    )

    override fun alerts(): Flow<AlertInset> = alerts

    override fun clips(cameraId: String): Flow<Feed<FootageClip>> = flowOf(
        Feed(
            eventList.filter { it.cameraId == cameraId }.map { event ->
                FootageClip(event.id, cameraId, event.summary, event.startEpochMs, event.endEpochMs, event.type.name)
            },
            Availability.Ready,
            "Sample timeline. Playback stays empty until Live mode has a recording URL.",
        ),
    )

    override suspend fun liveStream(cameraId: String, quality: StreamQuality): LiveStreamOffer {
        val name = cameraList.firstOrNull { it.id == cameraId }?.name ?: "Camera"
        return LiveStreamOffer(
            url = null,
            availability = Availability.HomeNetworkOnly("Demo mode has no ${quality.label.lowercase()} stream for $name."),
            message = "Switch to Live on the home network to open RTSPS.",
        )
    }

    override suspend fun footage(query: ProtectClipQuery): Feed<ProtectEvent> {
        val matched = eventList.filter { event ->
            (query.cameraId == null || event.cameraId == query.cameraId) &&
                event.matchesKind(query.kind) &&
                (query.startEpochMs == null || event.startEpochMs >= query.startEpochMs) &&
                (query.endEpochMs == null || event.startEpochMs <= query.endEpochMs)
        }
        return Feed(matched, Availability.Ready, "Sample events")
    }

    override suspend fun nudge(cameraId: String, move: PtzMove): Result<String> {
        val camera = cameraList.firstOrNull { it.id == cameraId }
        if (camera == null || !camera.isPtz) {
            return Result.failure(IllegalStateException("This camera does not pan."))
        }
        val direction = when (move) {
            PtzMove.Left -> "left"
            PtzMove.Right -> "right"
            PtzMove.Up -> "up"
            PtzMove.Down -> "down"
            PtzMove.ZoomIn -> "closer"
            PtzMove.ZoomOut -> "wider"
        }
        return Result.success("Preview only. ${camera.name} would aim $direction.")
    }

    override suspend fun gotoPreset(cameraId: String, slot: Int): Result<String> {
        val camera = cameraList.firstOrNull { it.id == cameraId }
        if (camera == null || !camera.isPtz) {
            return Result.failure(IllegalStateException("This camera does not pan."))
        }
        val label = if (slot < 0) "home" else "view ${slot + 1}"
        return Result.success("Preview only. ${camera.name} would go to $label.")
    }

    override suspend fun setLight(lightId: String, on: Boolean): Result<String> {
        val light = lightList.firstOrNull { it.id == lightId }
            ?: return Result.failure(IllegalStateException("That light is not in the preview."))
        val mode = if (on) "on" else "off"
        return Result.success("Preview only. ${light.name} would turn $mode.")
    }

    override suspend fun previewJpeg(cameraId: String, highQuality: Boolean): ByteArray? = null

    override suspend fun eventStill(eventId: String, thumbnailId: String): ByteArray? = null

    override suspend fun playback(clip: FootageClip, onProgress: (Float) -> Unit): PlaybackOffer {
        return PlaybackOffer(
            url = null,
            availability = Availability.HomeNetworkOnly("Demo mode does not play recordings."),
            message = "Recording files need Live mode on the home network.",
        )
    }

    override suspend fun previewAlert(cameraId: String?) {
        val camera = cameraList.firstOrNull { it.id == cameraId } ?: cameraList.firstOrNull()
        alerts.emit(
            AlertInset(
                id = "preview-${System.currentTimeMillis()}",
                title = "Test alert",
                subtitle = "",
                cameraId = camera?.id ?: "entry",
                cameraName = camera?.name ?: "Entry",
                kind = if (camera?.isDoorbell == true) AlertKind.Doorbell else AlertKind.Motion,
                doorId = if (camera?.isDoorbell == true) "front" else null,
                doorName = if (camera?.isDoorbell == true) "Front door" else null,
                hasSpeaker = camera?.hasSpeaker == true,
                detections = setOf(if (camera?.isDoorbell == true) DetectionType.Ring else DetectionType.Motion),
            ),
        )
    }

    override suspend fun announce(cameraId: String, message: String): Result<String> {
        val camera = cameraList.firstOrNull { it.id == cameraId }
        if (camera == null || !camera.hasSpeaker) {
            return Result.failure(IllegalStateException("This camera has no speaker."))
        }
        return Result.success("Preview only. “$message” would play on ${camera.name}.")
    }
}

class DemoNetworkRepository : NetworkRepository {
    override fun dashboard(): Flow<NetworkDashboard> = flowOf(
        NetworkDashboard(
            wan = WanStatus(
                up = true,
                ispName = "Example ISP",
                downloadMbps = 840.0,
                uploadMbps = 40.0,
                latencyMs = 9.0,
                liveDownMbps = 18.4,
                liveUpMbps = 2.1,
            ),
            devices = listOf(
                NetworkDevice("udm", "UPort Gateway", "UCG", online = true, adopted = true, kind = "Gateway"),
                NetworkDevice("usw", "Hall Switch", "USW", online = true, adopted = true, kind = "Switch"),
                NetworkDevice("u7", "Living AP", "U7", online = true, adopted = true, kind = "Access point", clientCount = 6),
            ),
            clients = listOf(
                NetworkClient("tv", "Living room TV", "10.1.4.40", "Wi-Fi", online = true),
                NetworkClient("phone", "Phone", "10.1.4.22", "Wi-Fi", online = true),
                NetworkClient("laptop", "Laptop", "10.1.4.18", "Wired", online = false),
            ),
            availability = Availability.Ready,
            detail = "Sample network status",
        ),
    )

    override suspend fun startSpeedTest(): Result<String> {
        return Result.success("Preview only. A speed test runs on the home console.")
    }
}

class DemoAccessRepository : AccessRepository {
    override fun feed(): Flow<AccessFeed> = flowOf(
        AccessFeed(
            doors = listOf(
                Door("front", "Front door", locked = true, position = "closed"),
                Door("garage", "Garage", locked = true, position = "closed"),
            ),
            devices = listOf(
                AccessDevice("hub", "Entry hub", "Hub", online = true),
                AccessDevice("reader", "Front reader", "Reader", online = true),
            ),
            events = listOf(
                AccessEvent("a1", "Front door", "Resident", AccessEventType.Unlock, "Access granted", System.currentTimeMillis() - 600_000),
                AccessEvent("a2", "Front door", "Visitor", AccessEventType.Doorbell, "Doorbell", System.currentTimeMillis() - 3_600_000),
            ),
            availability = Availability.Ready,
            unlockAvailability = Availability.HomeNetworkOnly(
                "Demo mode never unlocks a door.",
            ),
            detail = "Sample access history",
        ),
    )

    override suspend fun unlock(doorId: String, confirmed: Boolean): Result<String> {
        if (!confirmed) {
            return Result.failure(FeatureBlockedException("Unlock needs a second confirmation."))
        }
        return Result.failure(FeatureBlockedException("Demo mode never unlocks a door."))
    }

    override suspend fun grantsFor(email: String, userId: String): ResidentAccess {
        return ResidentAccess(
            groups = listOf("Residents"),
            policies = listOf("Home"),
            doors = listOf("Front door"),
            cameras = listOf("Entry"),
        )
    }
}
