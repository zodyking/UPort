@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)

package app.apt443.data.live

import app.apt443.domain.AccessFeed
import app.apt443.domain.AccessRepository
import app.apt443.domain.AlertInset
import app.apt443.domain.AlertKind
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.ConnectionMode
import app.apt443.domain.ConsoleSignInMode
import app.apt443.domain.DataSourceMode
import app.apt443.domain.FeatureBlockedException
import app.apt443.domain.Feed
import app.apt443.domain.FootageClip
import app.apt443.domain.LiveStreamOffer
import app.apt443.domain.NetworkDashboard
import app.apt443.domain.NetworkRepository
import app.apt443.domain.PlaybackOffer
import app.apt443.domain.ProtectClipQuery
import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType
import app.apt443.domain.ProtectLight
import app.apt443.domain.ProtectRepository
import app.apt443.domain.ResidentAccess
import app.apt443.domain.ProtectSensor
import app.apt443.domain.PtzMove
import app.apt443.domain.PopupPicture
import app.apt443.domain.StreamQuality
import app.apt443.domain.WanStatus
import app.apt443.domain.DetectionType
import app.apt443.domain.detectionTypes
import app.apt443.domain.matchesKind
import app.apt443.domain.cameraReplies
import app.apt443.domain.shouldAlert
import app.apt443.data.settings.SecretKeys
import app.apt443.data.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val HOME_VIDEO =
    "Live video is available on the home network, or over Tailscale to that network."

private const val HOME_AIM =
    "Aiming the camera needs the home network, or Tailscale to that network."

private const val HOME_LIGHT =
    "Turning a light on needs the home network, or Tailscale to that network."

private const val NEEDS_ADMIN =
    "Recording playback needs the site connection on the home network."

/** Collapses a subscribe-time fetch and the regular poll into one console call. */
private const val OPTIONAL_FEED_GAP_MS = 10_000L

/**
 * Doorbell unlock reads the door list with a one-shot collect. Ignore subscribers that
 * do not stay, so that read does not start the Access screen's log and reader refresh.
 */
private const val ACCESS_LISTEN_MS = 400L

private data class SessionCameras(val cameras: List<Camera>, val fromSession: Boolean)

class LiveProtectRepository(
    private val settingsFlow: Flow<AppSettings>,
    private val monitor: ConnectionMonitor,
    private val client: ProtectIntegrationClient,
    private val legacy: ProtectLegacySession,
    private val http: UnifiHttp,
    private val secrets: SecretStore,
    private val announcer: TalkbackAnnouncer,
    private val recordings: RecordingCache,
    private val scope: CoroutineScope,
) : ProtectRepository {
    private val cameraFeed = MutableStateFlow(emptyFeed<Camera>("Waiting for the console."))
    private val lightFeed = MutableStateFlow(emptyFeed<ProtectLight>("Waiting for the console."))
    private val sensorFeed = MutableStateFlow(emptyFeed<ProtectSensor>("Waiting for the console."))
    private val eventFeed = MutableStateFlow(emptyFeed<ProtectEvent>("Waiting for the console."))
    private val alertFlow = MutableSharedFlow<AlertInset>(extraBufferCapacity = 8)
    private val seen = LinkedHashSet<String>()
    private var seeded = false
    private var socket: WebSocket? = null
    private val stillPace = ConsolePace(gapMs = 400)
    private val optionalFeedGate = Mutex()
    private var lightsAtMs = 0L
    private var sensorsAtMs = 0L

    override val connectionMode = monitor.mode

    init {
        scope.launch {
            combine(settingsFlow, monitor.mode) { settings, mode -> settings to mode }
                .collectLatest { (settings, _) ->
                    stopSocket()
                    seeded = false
                    seen.clear()
                    if (settings.dataSource != DataSourceMode.Live || !settings.siteConfigured) return@collectLatest
                    while (isActive) {
                        val latest = settingsFlow.first()
                        val liveMode = monitor.mode.value
                        refresh(latest, liveMode)
                        if (liveMode == ConnectionMode.OnNetwork) {
                            openSocket(latest)
                        } else {
                            stopSocket()
                        }
                        delay(if (liveMode == ConnectionMode.Remote) 25_000 else 45_000)
                    }
                }
        }
        scope.launch {
            lightFeed.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { listening ->
                    if (!listening) return@collectLatest
                    val current = settingsFlow.first()
                    if (current.dataSource != DataSourceMode.Live || !current.siteConfigured) return@collectLatest
                    refreshLights(current, monitor.mode.value)
                }
        }
        scope.launch {
            sensorFeed.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { listening ->
                    if (!listening) return@collectLatest
                    val current = settingsFlow.first()
                    if (current.dataSource != DataSourceMode.Live || !current.siteConfigured) return@collectLatest
                    refreshSensors(current, monitor.mode.value)
                }
        }
    }

    override fun cameras(): Flow<Feed<Camera>> = cameraFeed

    override fun lights(): Flow<Feed<ProtectLight>> = lightFeed

    override fun sensors(): Flow<Feed<ProtectSensor>> = sensorFeed

    override fun events(): Flow<Feed<ProtectEvent>> = eventFeed

    override fun alerts(): Flow<AlertInset> = alertFlow

    override fun clips(cameraId: String): Flow<Feed<FootageClip>> {
        return kotlinx.coroutines.flow.flow {
            eventFeed.collect { feed ->
                emit(
                    Feed(
                        items = clipsFromEvents(feed.items, cameraId),
                        availability = feed.availability,
                        detail = feed.detail,
                    ),
                )
            }
        }
    }

    override suspend fun liveStream(cameraId: String, quality: StreamQuality): LiveStreamOffer {
        val settings = currentSettings()
        val mode = monitor.mode.value
        if (mode != ConnectionMode.OnNetwork) {
            return LiveStreamOffer(null, Availability.HomeNetworkOnly(HOME_VIDEO), HOME_VIDEO)
        }
        return client.createRtsps(settings, cameraId, quality).fold(
            onSuccess = { url -> LiveStreamOffer(url, Availability.Ready, quality.label) },
            onFailure = { error ->
                LiveStreamOffer(null, Availability.NeedsSetup(error.message ?: "RTSPS failed"), error.message ?: "RTSPS failed")
            },
        )
    }

    override suspend fun previewJpeg(cameraId: String, highQuality: Boolean): ByteArray? {
        if (cameraId.isBlank()) return null
        val mode = monitor.mode.value
        if (mode != ConnectionMode.OnNetwork && mode != ConnectionMode.Remote) return null
        return client.snapshot(currentSettings(), mode, cameraId, highQuality).getOrNull()
    }

    override suspend fun eventStill(eventId: String, thumbnailId: String): ByteArray? = stillPace.run {
        if (eventId.isBlank()) return@run null
        val settings = currentSettings()
        val mode = monitor.mode.value
        if (mode == ConnectionMode.OnNetwork || mode == ConnectionMode.Remote) {
            val primary = client.eventThumbnail(settings, mode, eventId)
            if (primary.isSuccess) return@run primary.getOrNull()
            val message = primary.exceptionOrNull()?.message.orEmpty()
            if (message.contains("429")) return@run null
        }
        if (mode != ConnectionMode.OnNetwork || !canExportRecordings(settings)) return@run null
        val stillId = eventId.trim().ifBlank { consoleStillId(thumbnailId) }
        if (stillId.isBlank()) return@run null
        legacy.thumbnail(settings, stillId).getOrNull()
    }

    override suspend fun footage(query: ProtectClipQuery): Feed<ProtectEvent> {
        val mode = monitor.mode.value
        if (mode != ConnectionMode.OnNetwork && mode != ConnectionMode.Remote) {
            return emptyFeed(monitor.detail.value)
        }
        val settings = currentSettings()
        if (mode == ConnectionMode.OnNetwork && settings.signInMode == ConsoleSignInMode.LocalUserAndApiKey) {
            val listed = legacy.listEvents(settings, query)
            if (listed.isSuccess) {
                val live = eventFeed.value.items
                val matched = namedEvents(
                    mergeEvents(listed.getOrThrow(), live),
                    cameraFeed.value.items,
                ).matching(query)
                return Feed(matched, Availability.Ready, monitor.detail.value)
            }
        }
        return client.getEvents(settings, mode, limit = 100, query = query).fold(
            onSuccess = { list ->
                val merged = mergeEvents(list, eventFeed.value.items)
                Feed(namedEvents(merged, cameraFeed.value.items).matching(query), Availability.Ready, monitor.detail.value)
            },
            onFailure = { error ->
                val message = error.message ?: "Events failed"
                if (message.contains("404")) {
                    Feed(
                        namedEvents(eventFeed.value.items, cameraFeed.value.items).matching(query),
                        Availability.Ready,
                        monitor.detail.value,
                    )
                } else {
                    Feed(emptyList(), Availability.NeedsSetup(message), message)
                }
            },
        )
    }

    override suspend fun nudge(cameraId: String, move: PtzMove): Result<String> {
        val camera = cameraFeed.value.items.firstOrNull { it.id == cameraId }
        if (camera != null && !camera.isPtz) {
            return Result.failure(IllegalStateException("${camera.name} does not pan."))
        }
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return Result.failure(IllegalStateException(HOME_AIM))
        }
        return client.movePtz(currentSettings(), cameraId, move).map { move.label() }
    }

    override suspend fun gotoPreset(cameraId: String, slot: Int): Result<String> {
        val camera = cameraFeed.value.items.firstOrNull { it.id == cameraId }
        if (camera != null && !camera.isPtz) {
            return Result.failure(IllegalStateException("${camera.name} does not pan."))
        }
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return Result.failure(IllegalStateException(HOME_AIM))
        }
        val label = if (slot < 0) "Home" else "View ${slot + 1}"
        return client.gotoPtz(currentSettings(), cameraId, slot).map { label }
    }

    override suspend fun setLight(lightId: String, on: Boolean): Result<String> {
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return Result.failure(IllegalStateException(HOME_LIGHT))
        }
        val light = lightFeed.value.items.firstOrNull { it.id == lightId }
        return client.setLightMode(currentSettings(), lightId, on).map {
            val name = light?.name ?: "Light"
            val mode = if (on) "On" else "Off"
            lightFeed.value = lightFeed.value.copy(
                items = lightFeed.value.items.map { item ->
                    if (item.id == lightId) item.copy(on = on, mode = mode) else item
                },
            )
            "$name is ${mode.lowercase()}."
        }
    }

    override suspend fun playback(clip: FootageClip, onProgress: (Float) -> Unit): PlaybackOffer {
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return PlaybackOffer(
                null,
                Availability.HomeNetworkOnly("Recording export stays on the home network."),
                "Recording export stays on the home network.",
            )
        }
        val settings = currentSettings()
        if (!canExportRecordings(settings)) {
            return PlaybackOffer(
                null,
                Availability.NeedsSetup(NEEDS_ADMIN),
                NEEDS_ADMIN,
            )
        }
        if (clip.startEpochMs <= 0L) {
            return PlaybackOffer(
                null,
                Availability.NeedsSetup("This clip has no recording time."),
                "This clip has no recording time.",
            )
        }
        val window = exportWindowFor(clip)
        val url = legacy.exportUrl(settings, clip.cameraId, window.startMs, window.endMs).getOrElse { error ->
            val message = error.message ?: "The console did not return this recording."
            return PlaybackOffer(null, Availability.NeedsSetup(message), message)
        }
        return recordings.clip(settings, url, onProgress).fold(
            onSuccess = { file ->
                PlaybackOffer(file.toURI().toString(), Availability.Ready, "Unofficial Protect export")
            },
            onFailure = { error ->
                val message = error.message ?: "The console did not return this recording."
                PlaybackOffer(null, Availability.NeedsSetup(message), message)
            },
        )
    }

    /**
     * Export needs the local UniFi OS session, not the integration key. Saving a console
     * password in Settings does not change [AppSettings.signInMode], so a stored password
     * counts too; otherwise export stays locked out for people who added one that way.
     */
    private suspend fun canExportRecordings(settings: AppSettings): Boolean {
        if (settings.signInMode == ConsoleSignInMode.LocalUserAndApiKey) return true
        if (settings.osUsername.isBlank()) return false
        return !secrets.get(SecretKeys.OS_PASSWORD).isNullOrBlank()
    }

    override suspend fun previewAlert(cameraId: String?) {
        val settings = currentSettings()
        val mode = monitor.mode.value
        val camera = cameraFeed.value.items.firstOrNull { it.id == cameraId }
            ?: cameraFeed.value.items.firstOrNull { it.isDoorbell }
            ?: cameraFeed.value.items.firstOrNull()
        var inset = AlertInset(
            id = "preview-${System.currentTimeMillis()}",
            title = "Test alert",
            subtitle = popupSubtitle(settings, mode),
            cameraId = camera?.id,
            cameraName = camera?.name ?: "Camera",
            kind = AlertKind.Doorbell,
            hasSpeaker = settings.cameraReplies(camera?.id, camera?.hasSpeaker == true),
            detections = setOf(DetectionType.Ring),
        )
        inset = withPopupPicture(settings, mode, camera?.id, inset)
        alertFlow.emit(inset)
    }

    private suspend fun loadCameras(settings: AppSettings, mode: ConnectionMode): Result<SessionCameras> {
        val official = runCatching { client.getCameras(settings, mode) }.getOrElse { Result.failure(it) }
        official.onSuccess { return Result.success(SessionCameras(it, fromSession = false)) }
        val message = official.exceptionOrNull()?.message.orEmpty()
        val missingKey = message.contains("API key is not set", ignoreCase = true)
        if (missingKey && mode == ConnectionMode.OnNetwork && settings.osUsername.isNotBlank()) {
            return legacy.camerasFromSession(settings).map { SessionCameras(it, fromSession = true) }
        }
        return official.map { SessionCameras(it, fromSession = false) }
    }

    private suspend fun refresh(settings: AppSettings, mode: ConnectionMode) {
        if (mode != ConnectionMode.OnNetwork && mode != ConnectionMode.Remote) {
            val detail = monitor.detail.value
            cameraFeed.value = emptyFeed(detail)
            lightFeed.value = emptyFeed(detail)
            sensorFeed.value = emptyFeed(detail)
            eventFeed.value = emptyFeed(detail)
            return
        }
        val cameras = loadCameras(settings, mode)
        cameras.fold(
            onSuccess = { list ->
                val detail = if (list.fromSession) {
                    "Signed in. Live video is still waiting on the site connection."
                } else {
                    monitor.detail.value
                }
                cameraFeed.value = Feed(list.cameras, Availability.Ready, detail)
            },
            onFailure = { error ->
                val message = error.message ?: "Cameras failed"
                val availability = if (cameraFeed.value.items.isEmpty()) {
                    Availability.NeedsSetup(message)
                } else {
                    Availability.Cached(System.currentTimeMillis())
                }
                cameraFeed.value = Feed(cameraFeed.value.items, availability, message)
            },
        )
        // Let the live grid open its streams before lights, sensors, and event history.
        delay(1_200)
        refreshLights(settings, mode)
        refreshSensors(settings, mode)
        val events = runCatching { client.getEvents(settings, mode) }.getOrElse { Result.failure(it) }
        events.fold(
            onSuccess = { list ->
                eventFeed.value = Feed(list, Availability.Ready, monitor.detail.value)
                if (!seeded) {
                    list.forEach { seen.add(it.id) }
                    seeded = true
                } else {
                    list.forEach { event -> considerAlert(settings, mode, event) }
                }
            },
            onFailure = { error ->
                val message = error.message ?: "Events failed"
                val availability = if (eventFeed.value.items.isEmpty()) {
                    Availability.NeedsSetup(message)
                } else {
                    Availability.Cached(System.currentTimeMillis())
                }
                eventFeed.value = Feed(eventFeed.value.items, availability, message)
            },
        )
    }

    private fun openSocket(settings: AppSettings) {
        if (socket != null) return
        scope.launch {
            val key = secrets.get(SecretKeys.PROTECT_API_KEY) ?: return@launch
            val url = client.subscribeEventsUrl(settings)
            socket = http.openWebSocket(
                settings,
                url,
                apiKeyHeader(key),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val event = parseSubscribeItem(text) ?: return
                        scope.launch {
                            rememberSocketEvent(event)
                            considerAlert(settings, ConnectionMode.OnNetwork, event)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        socket = null
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        socket = null
                    }
                },
            )
        }
    }

    private fun stopSocket() {
        socket?.close(1000, "mode change")
        socket = null
    }

    private fun popupSubtitle(settings: AppSettings, mode: ConnectionMode): String {
        if (settings.popupPicture == PopupPicture.Snapshot) return ""
        if (mode == ConnectionMode.OnNetwork) return ""
        return "Live video needs the home network."
    }

    /** Live popups request the chosen stream and keep a snapshot for when it fails. */
    private suspend fun withPopupPicture(
        settings: AppSettings,
        mode: ConnectionMode,
        cameraId: String?,
        inset: AlertInset,
    ): AlertInset {
        if (cameraId.isNullOrBlank()) return inset
        val live = settings.popupPicture == PopupPicture.Live && mode == ConnectionMode.OnNetwork
        val stream = if (live) {
            client.createRtsps(settings, cameraId, settings.pipStreamQuality).getOrNull()
        } else {
            null
        }
        val shot = client.snapshot(settings, mode, cameraId, highQuality = false).getOrNull()
        return inset.copy(streamUrl = stream, snapshotJpeg = shot)
    }

    private suspend fun considerAlert(
        settings: AppSettings,
        mode: ConnectionMode,
        event: ProtectEvent,
    ) {
        if (!settings.siteConfigured) return
        if (!event.type.opensInset() && !event.alarming) return
        // The single gate for popups: overlay and notification both consume alertFlow.
        if (!currentSettings().shouldAlert(event)) return
        if (!seen.add(event.id)) return
        trimSeen()
        val camera = cameraFeed.value.items.firstOrNull { it.id == event.cameraId }
        val cameraName = camera?.name ?: event.cameraName
        var inset = AlertInset(
            id = event.id,
            title = alertPhrase(event, cameraName),
            subtitle = popupSubtitle(settings, mode),
            cameraId = event.cameraId,
            cameraName = cameraName,
            kind = event.type.toAlertKind(),
            hasSpeaker = settings.cameraReplies(camera?.id, camera?.hasSpeaker == true),
            detections = event.detectionTypes(),
        )
        inset = withPopupPicture(settings, mode, event.cameraId, inset)
        alertFlow.emit(inset)
    }

    private fun rememberSocketEvent(event: ProtectEvent) {
        val items = eventFeed.value.items
        if (items.any { it.id == event.id }) return
        eventFeed.value = Feed(
            items = listOf(event) + items.take(99),
            availability = Availability.Ready,
            detail = eventFeed.value.detail.ifBlank { monitor.detail.value },
        )
    }

    private fun trimSeen() {
        while (seen.size > 200) {
            val first = seen.first()
            seen.remove(first)
        }
    }

    override suspend fun announce(cameraId: String, message: String): Result<String> {
        if (cameraId.isBlank() || message.isBlank()) {
            return Result.failure(IllegalStateException("Choose a message for a camera with a speaker."))
        }
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return Result.failure(IllegalStateException("Speaker messages need the home network."))
        }
        val camera = cameraFeed.value.items.firstOrNull { it.id == cameraId }
        if (camera != null && !camera.hasSpeaker) {
            return Result.failure(IllegalStateException("${camera.name} has no speaker."))
        }
        val settings = currentSettings()
        val name = camera?.name ?: "the camera"
        // Speech is prepared while the talkback request is in flight. A udp:// or rtp://
        // session is the speaker. A WebSocket is only used when that address is missing.
        return when (val outcome = announcer.play(message) {
            val session = talkbackSession(settings, cameraId).getOrElse { error ->
                return@play Result.failure(error)
            }
            val socket = if (session.destination != null) {
                null
            } else {
                openSpeakerSocket(settings, session).getOrElse { error ->
                    return@play Result.failure(labelSpeakerError(error))
                }
            }
            if (socket == null && session.destination == null) {
                Result.failure(IllegalStateException("This camera did not open a speaker connection."))
            } else {
                Result.success(session to socket)
            }
        }) {
            SpeakOutcome.Spoken -> Result.success("Played on $name.")
            is SpeakOutcome.Rejected -> Result.failure(outcome.error)
            is SpeakOutcome.Failed -> Result.failure(labelSpeakerError(outcome.error))
        }
    }

    /**
     * Opens a talkback WebSocket only when the session did not give a udp or rtp address.
     * Opening the socket is not playback; frames still have to be accepted.
     */
    private suspend fun openSpeakerSocket(
        settings: AppSettings,
        session: TalkbackSession,
    ): Result<TalkbackSocket?> {
        val webSocket = session.speakerWebSocket ?: return Result.success(null)
        val apiKey = secrets.get(SecretKeys.PROTECT_API_KEY)
        val opened = TalkbackSocket.open { listener ->
            legacy.talkbackSocket(settings, webSocket, apiKey, listener)
        } ?: return Result.failure(
            IllegalStateException("The console did not open the speaker connection."),
        )
        return Result.success(opened)
    }

    /**
     * Prefer the integration API when a key is saved — it does not need the console password.
     * Fall back to bootstrap and the private session when local admin credentials work.
     */
    private suspend fun talkbackSession(settings: AppSettings, cameraId: String): Result<TalkbackSession> {
        val hasApiKey = !secrets.get(SecretKeys.PROTECT_API_KEY).isNullOrBlank()
        val local = canUseLocalSession(settings)
        // Bootstrap (codec, rate, camera address) and the session POST share no login state
        // when the POST uses the integration key, so they run together. A cached bootstrap
        // returns immediately and the POST is the only round trip.
        return coroutineScope {
            val formatDeferred = async {
                if (local) legacy.talkbackFromBootstrap(settings, cameraId).getOrNull() else null
            }
            val publicDeferred = async {
                if (hasApiKey) client.createTalkback(settings, cameraId) else null
            }
            val public = publicDeferred.await()
            val format = formatDeferred.await()
            if (public?.isSuccess == true) {
                return@coroutineScope Result.success(mergeTalkback(public.getOrThrow(), format))
            }

        var bootstrap: Result<TalkbackSession>? = null
        var private: Result<TalkbackSession>? = null
        if (local) {
            bootstrap = if (format != null) Result.success(format) else legacy.talkbackFromBootstrap(settings, cameraId)
            private = legacy.createTalkback(settings, cameraId)
            if (private.isSuccess) {
                return@coroutineScope Result.success(mergeTalkback(private.getOrThrow(), format))
            }
            if (bootstrap.isSuccess) return@coroutineScope bootstrap
        }

        if (public != null) {
            if (public.isSuccess) return@coroutineScope public
            if (bootstrap != null || private != null) {
                return@coroutineScope Result.failure(
                    combineTalkbackErrors(
                        bootstrap ?: Result.failure(IllegalStateException("")),
                        private ?: Result.failure(IllegalStateException("")),
                        public,
                    ),
                )
            }
            return@coroutineScope public
        }

        return@coroutineScope Result.failure(
            IllegalStateException(
                "Camera speaker needs the site connection.",
            ),
        )
        }
    }

    private fun mergeTalkback(session: TalkbackSession, format: TalkbackSession?): TalkbackSession {
        if (format == null) return session
        return session.copy(
            url = session.url.ifBlank { format.url },
            codec = format.codec.ifBlank { session.codec },
            samplingRate = format.samplingRate.takeIf { it > 0 } ?: session.samplingRate,
            bitsPerSample = format.bitsPerSample.takeIf { it > 0 } ?: session.bitsPerSample,
            channels = format.channels.takeIf { it > 0 } ?: session.channels,
            bindAddr = session.bindAddr.ifBlank { format.bindAddr },
            bindPort = session.bindPort.takeIf { it > 0 } ?: format.bindPort,
            cameraHost = session.cameraHost.ifBlank { format.cameraHost },
            cameraId = session.cameraId.ifBlank { format.cameraId },
        )
    }

    private suspend fun canUseLocalSession(settings: AppSettings): Boolean = canExportRecordings(settings)

    private fun combineTalkbackErrors(
        bootstrap: Result<TalkbackSession>,
        private: Result<TalkbackSession>,
        public: Result<TalkbackSession>,
    ): IllegalStateException {
        val bootstrapMsg = bootstrap.exceptionOrNull()?.message.orEmpty()
        val privateMsg = private.exceptionOrNull()?.message.orEmpty()
        val publicMsg = public.exceptionOrNull()?.message.orEmpty()
        val detail = when {
            bootstrapMsg.contains("UniFi OS login HTTP", ignoreCase = true) ||
                privateMsg.contains("UniFi OS login HTTP", ignoreCase = true) ->
                humanizeLoginFailure(bootstrapMsg.ifBlank { privateMsg })
            bootstrapMsg.startsWith("Talkback HTTP") || privateMsg.startsWith("Talkback HTTP") ||
                publicMsg.startsWith("Talkback HTTP") ->
                "The camera speaker refused the request. Playback can still work when talkback is blocked."
            bootstrapMsg.isNotBlank() && !bootstrapMsg.startsWith("Talkback HTTP") -> bootstrapMsg
            privateMsg.startsWith("Talkback HTTP") && publicMsg.startsWith("Talkback HTTP") -> {
                val privateCode = privateMsg.removePrefix("Talkback HTTP ").trim()
                val publicCode = publicMsg.removePrefix("Talkback HTTP ").trim()
                "Talkback HTTP failed on both console routes (private $privateCode, public $publicCode)."
            }
            bootstrapMsg.isNotBlank() -> bootstrapMsg
            privateMsg.isNotBlank() -> privateMsg
            publicMsg.isNotBlank() -> publicMsg
            else -> "Could not open a camera speaker session."
        }
        return IllegalStateException(detail)
    }

    private fun labelSpeakerError(error: Throwable): Throwable {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("Google speech") -> error
            message.startsWith("Talkback") -> error
            message.contains("UniFi OS login HTTP 403", ignoreCase = true) ->
                IllegalStateException(humanizeLoginFailure(message), error)
            else -> IllegalStateException(
                "Camera speaker: ${message.ifBlank { "could not play." }}",
                error,
            )
        }
    }

    private fun humanizeLoginFailure(raw: String): String =
        if (raw.contains("403")) {
            "The console rejected the saved sign-in (HTTP 403)."
        } else {
            raw.ifBlank { "Console sign-in failed." }
        }

    private suspend fun refreshLights(settings: AppSettings, mode: ConnectionMode) {
        if (mode != ConnectionMode.OnNetwork && mode != ConnectionMode.Remote) return
        if (lightFeed.subscriptionCount.value == 0) return
        optionalFeedGate.withLock {
            if (lightFeed.subscriptionCount.value == 0) return@withLock
            if (System.currentTimeMillis() - lightsAtMs < OPTIONAL_FEED_GAP_MS) return@withLock
            publishOptional(caught { client.getLights(settings, mode) }, lightFeed)
            lightsAtMs = System.currentTimeMillis()
        }
    }

    private suspend fun refreshSensors(settings: AppSettings, mode: ConnectionMode) {
        if (mode != ConnectionMode.OnNetwork && mode != ConnectionMode.Remote) return
        if (sensorFeed.subscriptionCount.value == 0) return
        optionalFeedGate.withLock {
            if (sensorFeed.subscriptionCount.value == 0) return@withLock
            if (System.currentTimeMillis() - sensorsAtMs < OPTIONAL_FEED_GAP_MS) return@withLock
            publishOptional(caught { client.getSensors(settings, mode) }, sensorFeed)
            sensorsAtMs = System.currentTimeMillis()
        }
    }

    private suspend fun currentSettings(): AppSettings = settingsFlow.first()
}

class LiveNetworkRepository(
    private val settingsFlow: Flow<AppSettings>,
    private val monitor: ConnectionMonitor,
    private val client: NetworkIntegrationClient,
    private val scope: CoroutineScope,
) : NetworkRepository {
    private val state = MutableStateFlow(blankDashboard("Waiting for the console."))

    init {
        scope.launch {
            settingsFlow.collectLatest { settings ->
                if (settings.dataSource != DataSourceMode.Live || !settings.siteConfigured) return@collectLatest
                state.subscriptionCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .collectLatest { listening ->
                        if (!listening) return@collectLatest
                        var tick = 0
                        while (isActive) {
                            val latest = settingsFlow.first()
                            val running = state.value.wan.speedtest == "running"
                            refresh(latest, monitor.mode.value, includeInventory = tick % 4 == 0 || running)
                            tick += 1
                            delay(if (running) 4_000 else 8_000)
                        }
                    }
            }
        }
    }

    override fun dashboard(): Flow<NetworkDashboard> = state

    override suspend fun startSpeedTest(): Result<String> {
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return Result.failure(IllegalStateException("A speed test stays on the home network."))
        }
        val settings = settingsFlow.first()
        return client.startSpeedTest(settings).onSuccess {
            state.value = state.value.copy(wan = state.value.wan.copy(speedtest = "running"))
        }
    }

    private suspend fun refresh(settings: AppSettings, mode: ConnectionMode, includeInventory: Boolean = true) {
        if (mode != ConnectionMode.OnNetwork) {
            state.value = blankDashboard("Network details stay on the home network.")
                .copy(availability = Availability.HomeNetworkOnly("Network details stay on the home network."))
            return
        }
        val devices = if (includeInventory) {
            runCatching { client.devices(settings, mode) }.getOrElse { Result.failure(it) }
        } else {
            Result.success(state.value.devices)
        }
        val clients = if (includeInventory) {
            runCatching { client.clients(settings, mode) }.getOrElse { Result.failure(it) }
        } else {
            Result.success(state.value.clients)
        }
        val wan = client.isp(settings)
        val previous = state.value
        val failed = devices.isFailure && clients.isFailure && wan.isFailure
        val deviceList = devices.getOrElse { previous.devices }
        val clientList = nameAccessPoints(clients.getOrElse { previous.clients }, deviceList)
        state.update {
            NetworkDashboard(
                wan = wan.getOrElse { previous.wan },
                devices = deviceList,
                clients = clientList,
                availability = when {
                    !failed -> Availability.Ready
                    previous.devices.isNotEmpty() || previous.wan.up != null -> Availability.Cached(System.currentTimeMillis())
                    else -> Availability.NeedsSetup(devices.exceptionOrNull()?.message ?: "Network status failed")
                },
                detail = when {
                    deviceList.isEmpty() && devices.isFailure -> "Devices did not load."
                    clientList.isEmpty() && clients.isFailure -> "Clients did not load."
                    else -> ""
                },
            )
        }
    }
}

class LiveAccessRepository(
    private val settingsFlow: Flow<AppSettings>,
    private val monitor: ConnectionMonitor,
    private val client: AccessDeveloperClient,
    private val legacy: ProtectLegacySession,
    private val scope: CoroutineScope,
) : AccessRepository {
    private val state = MutableStateFlow(
        AccessFeed(
            doors = emptyList(),
            events = emptyList(),
            availability = Availability.NeedsSetup("Waiting for the console."),
            unlockAvailability = Availability.HomeNetworkOnly("Unlock stays on the home network."),
            detail = "",
        ),
    )

    private val refreshGate = Mutex()

    init {
        scope.launch {
            settingsFlow.collectLatest { settings ->
                if (settings.dataSource != DataSourceMode.Live || !settings.siteConfigured) return@collectLatest
                coroutineScope {
                    launch {
                        while (isActive) {
                            if (state.subscriptionCount.value == 0) {
                                refresh(settingsFlow.first(), monitor.mode.value, doorsOnly = true)
                            }
                            delay(20_000)
                        }
                    }
                    launch {
                        state.subscriptionCount
                            .map { it > 0 }
                            .distinctUntilChanged()
                            .debounce(ACCESS_LISTEN_MS)
                            .collectLatest { listening ->
                                if (!listening) return@collectLatest
                                while (isActive) {
                                    refresh(settingsFlow.first(), monitor.mode.value, doorsOnly = false)
                                    delay(20_000)
                                }
                            }
                    }
                }
            }
        }
    }

    override fun feed(): Flow<AccessFeed> = state

    override suspend fun unlock(doorId: String, confirmed: Boolean): Result<String> {
        if (!confirmed) {
            return Result.failure(FeatureBlockedException("Unlock needs a second confirmation."))
        }
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return Result.failure(
                FeatureBlockedException(
                    "Door unlock needs this TV to reach the console.",
                ),
            )
        }
        return client.unlock(currentSettings(), doorId)
    }

    override suspend fun grantsFor(email: String, userId: String): ResidentAccess {
        val settings = currentSettings()
        if (monitor.mode.value != ConnectionMode.OnNetwork) {
            return ResidentAccess(detail = "Access permissions load on the home network.")
        }
        val fromAccess = client.grantsFor(settings, email, userId)
        val fromProtect = legacy.residentProfile(settings, email, userId).getOrElse {
            ResidentAccess(detail = it.message.orEmpty())
        }
        return fromAccess.merged(fromProtect)
    }

    private suspend fun refresh(settings: AppSettings, mode: ConnectionMode, doorsOnly: Boolean = false) {
        refreshGate.withLock {
            refreshLocked(settings, mode, doorsOnly)
        }
    }

    private suspend fun refreshLocked(settings: AppSettings, mode: ConnectionMode, doorsOnly: Boolean) {
        if (mode != ConnectionMode.OnNetwork) {
            val cached = state.value
            state.value = cached.copy(
                availability = Availability.HomeNetworkOnly("Access stays on the home network."),
                unlockAvailability = Availability.HomeNetworkOnly("Unlock stays on the home network."),
                detail = "",
            )
            return
        }
        if (doorsOnly) {
            val doors = client.doors(settings)
            val previous = state.value
            val doorList = doors.getOrElse { previous.doors }
            state.value = previous.copy(
                doors = doorList,
                availability = when {
                    doors.isSuccess -> Availability.Ready
                    previous.doors.isNotEmpty() -> Availability.Cached(System.currentTimeMillis())
                    else -> previous.availability
                },
                unlockAvailability = Availability.Ready,
            )
            return
        }
        val loaded = coroutineScope {
            val doors = async { client.doors(settings) }
            val logs = async { client.logs(settings) }
            val devices = async { client.devices(settings) }
            Triple(doors.await(), logs.await(), devices.await())
        }
        val doors = loaded.first
        val logs = loaded.second
        val devices = loaded.third
        val previous = state.value
        val doorList = doors.getOrElse { previous.doors }
        val eventList = logs.getOrElse { previous.events }
        val deviceList = devices.getOrElse { previous.devices }
        val failed = doors.isFailure && logs.isFailure && devices.isFailure
        state.value = AccessFeed(
            doors = doorList,
            events = eventList,
            devices = deviceList,
            availability = when {
                !failed -> Availability.Ready
                previous.doors.isNotEmpty() -> Availability.Cached(System.currentTimeMillis())
                else -> Availability.NeedsSetup(doors.exceptionOrNull()?.message ?: "Access failed")
            },
            unlockAvailability = Availability.Ready,
            detail = when {
                doorList.isEmpty() && doors.isFailure -> "Doors did not load."
                deviceList.isEmpty() && devices.isFailure -> "Readers did not load."
                else -> ""
            },
        )
    }

    private suspend fun currentSettings(): AppSettings = settingsFlow.first()
}

private fun <T> emptyFeed(detail: String) = Feed<T>(emptyList(), Availability.NeedsSetup(detail), detail)

private fun blankDashboard(detail: String) = NetworkDashboard(
    wan = WanStatus(null, "WAN", null, null, null),
    devices = emptyList(),
    clients = emptyList(),
    availability = Availability.NeedsSetup(detail),
    detail = detail,
)

private fun alertPhrase(event: ProtectEvent, cameraName: String): String {
    val place = cameraName.trim().ifBlank { "the camera" }
    val labels = event.smartLabels.map { it.lowercase() }
    return when {
        event.type == ProtectEventType.Ring -> "Someone is at $place"
        "person" in labels -> "Person detected at $place"
        "vehicle" in labels -> "Vehicle detected at $place"
        "package" in labels -> "Package detected at $place"
        "animal" in labels -> "Animal detected at $place"
        event.type == ProtectEventType.Motion || event.type == ProtectEventType.SmartDetect ->
            "Motion detected at $place"
        event.alarming -> "Alarm at $place"
        else -> "Alert at $place"
    }
}

private fun namedEvents(events: List<ProtectEvent>, cameras: List<Camera>): List<ProtectEvent> {
    val names = cameras.associate { it.id to it.name }
    return events.map { event ->
        val name = names[event.cameraId].orEmpty()
        if (name.isBlank() || name == event.cameraName) event else event.copy(cameraName = name)
    }
}

private fun List<ProtectEvent>.matching(query: ProtectClipQuery): List<ProtectEvent> {
    return filter { event ->
        !event.cameraId.isNullOrBlank() &&
            event.startEpochMs > 0L &&
            event.matchesKind(query.kind) &&
            (query.cameraId == null || event.cameraId == query.cameraId) &&
            (query.startEpochMs == null || event.startEpochMs >= query.startEpochMs) &&
            (query.endEpochMs == null || event.startEpochMs <= query.endEpochMs)
    }
}

private inline fun <T> caught(block: () -> Result<List<T>>): Result<List<T>> {
    return runCatching { block() }.getOrElse { Result.failure(it) }
}

private fun <T> publishOptional(result: Result<List<T>>, feed: MutableStateFlow<Feed<T>>) {
    result.fold(
        onSuccess = { list -> feed.value = Feed(list, Availability.Ready, "") },
        onFailure = { error ->
            val current = feed.value
            if (current.items.isEmpty()) {
                feed.value = Feed(emptyList(), Availability.NeedsSetup(error.message ?: "Unavailable"), "")
            } else {
                feed.value = current.copy(availability = Availability.Cached(System.currentTimeMillis()))
            }
        },
    )
}

private fun PtzMove.label(): String = when (this) {
    PtzMove.Left -> "Aimed left."
    PtzMove.Right -> "Aimed right."
    PtzMove.Up -> "Aimed up."
    PtzMove.Down -> "Aimed down."
    PtzMove.ZoomIn -> "Zoomed in."
    PtzMove.ZoomOut -> "Zoomed out."
}

private fun ProtectEventType.opensInset(): Boolean {
    return this == ProtectEventType.Ring ||
        this == ProtectEventType.Motion ||
        this == ProtectEventType.SmartDetect
}

private fun ProtectEventType.toAlertKind(): AlertKind {
    return when (this) {
        ProtectEventType.Ring -> AlertKind.Doorbell
        ProtectEventType.SmartDetect -> AlertKind.SmartDetect
        else -> AlertKind.Motion
    }
}

/** Spaces console calls so snapshot requests do not arrive in a burst. */
private class ConsolePace(private val gapMs: Long) {
    private val gate = Mutex()
    private var nextAtNs = 0L

    suspend fun <T> run(block: suspend () -> T): T = gate.withLock {
        val waitNs = nextAtNs - System.nanoTime()
        if (waitNs > 0) delay(waitNs / 1_000_000)
        try {
            block()
        } finally {
            nextAtNs = System.nanoTime() + gapMs * 1_000_000
        }
    }
}
