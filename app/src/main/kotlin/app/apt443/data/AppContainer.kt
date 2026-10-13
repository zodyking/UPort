@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.apt443.data

import android.content.Context
import app.apt443.data.demo.DemoAccessRepository
import app.apt443.data.demo.DemoNetworkRepository
import app.apt443.data.demo.DemoProtectRepository
import app.apt443.data.live.AccessDeveloperClient
import app.apt443.data.live.ConnectionMonitor
import app.apt443.data.live.LiveAccessRepository
import app.apt443.data.live.LiveNetworkRepository
import app.apt443.data.live.LiveProtectRepository
import app.apt443.data.live.NetworkIntegrationClient
import app.apt443.data.live.ProtectIntegrationClient
import app.apt443.data.live.GoogleTranslateSpeech
import app.apt443.data.live.TalkbackAnnouncer
import app.apt443.data.live.ProtectLegacySession
import app.apt443.data.live.RecordingCache
import app.apt443.data.live.UnifiHttp
import app.apt443.domain.Availability
import app.apt443.domain.AccountGate
import app.apt443.domain.IdentityUser
import app.apt443.domain.TenantLabel
import app.apt443.signin.ConsoleDiscovery
import app.apt443.signin.ConsoleSignIn
import app.apt443.signin.SitePackage
import app.apt443.data.remote.VpnReach
import app.apt443.data.remote.VpnStatus
import app.apt443.data.settings.KeystoreSecretStore
import app.apt443.data.settings.SecretKeys
import app.apt443.data.settings.SecretStore
import app.apt443.data.settings.SettingsRepository
import app.apt443.domain.AccessRepository
import app.apt443.domain.AppSettings
import app.apt443.domain.signInModeKeepingLocalAdmin
import app.apt443.domain.withResidentSignIn
import app.apt443.domain.CameraAlertPrefs
import app.apt443.domain.PersistentCamera
import app.apt443.domain.ConnectionMode
import app.apt443.domain.ConsoleAccount
import app.apt443.domain.DataSourceMode
import app.apt443.domain.NetworkRepository
import app.apt443.domain.ProtectClipQuery
import app.apt443.domain.ProtectRepository
import app.apt443.domain.PtzMove
import app.apt443.domain.ConsoleSignInMode
import app.apt443.domain.ReachPreference
import app.apt443.domain.SiteVpnKind
import app.apt443.domain.SignInResult
import app.apt443.domain.StreamQuality
import app.apt443.domain.manualRtsp
import app.apt443.domain.sanitizeRtsp
import app.apt443.data.live.LinkWatch
import app.apt443.data.live.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import app.apt443.tailscale.TailscaleLogin
import app.apt443.ui.player.StreamEpoch
import app.apt443.ui.player.StreamUrlCache

class AppContainer(private val context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsRepository = SettingsRepository(context)
    val secrets: SecretStore = KeystoreSecretStore(context)
    val http = UnifiHttp()
    val vpnReach = VpnReach(context, secrets)
    val vpnConsent = vpnReach.consent
    val speech = GoogleTranslateSpeech(context.applicationContext)

    val settings: Flow<AppSettings> = settingsRepository.settings

    private val monitor = ConnectionMonitor(settings, http, vpnReach)
    private val legacy = ProtectLegacySession(http, secrets)
    private val consoleSignIn = ConsoleSignIn(http, legacy)
    private val protectClient = ProtectIntegrationClient(http, secrets)
    private val demoProtect = DemoProtectRepository()
    private val liveProtect = LiveProtectRepository(
        settingsFlow = settings,
        monitor = monitor,
        client = protectClient,
        legacy = legacy,
        http = http,
        secrets = secrets,
        announcer = TalkbackAnnouncer(context.applicationContext, speech),
        recordings = RecordingCache(context.applicationContext, legacy),
        scope = appScope,
    )
    private val demoNetwork = DemoNetworkRepository()
    private val liveNetwork = LiveNetworkRepository(
        settingsFlow = settings,
        monitor = monitor,
        client = NetworkIntegrationClient(http, secrets, legacy),
        scope = appScope,
    )
    private val demoAccess = DemoAccessRepository()
    private val liveAccess = LiveAccessRepository(
        settingsFlow = settings,
        monitor = monitor,
        client = AccessDeveloperClient(http, secrets, legacy),
        legacy = legacy,
        scope = appScope,
    )

    val protect: ProtectRepository = SwitchingProtect(settings, demoProtect, liveProtect)
    val network: NetworkRepository = SwitchingNetwork(settings, demoNetwork, liveNetwork)
    val access: AccessRepository = SwitchingAccess(settings, demoAccess, liveAccess)
    val connectionDetail: Flow<String> = monitor.detail
    val vpnStatus: Flow<VpnStatus> = vpnReach.status
    private val _startup = MutableStateFlow<Startup>(Startup.Loading("Loading"))
    val startup: StateFlow<Startup> = _startup
    private val rtspKnown = ConcurrentHashMap<String, Boolean>()
    private val rtspTriedAt = ConcurrentHashMap<String, Long>()
    private val _camerasNeedingRtsp = MutableStateFlow<Set<String>>(emptySet())
    /** Cameras Protect listed that have no playable stream and no address saved on this TV. */
    val camerasNeedingRtsp: StateFlow<Set<String>> = _camerasNeedingRtsp

    init {
        monitor.start(appScope)
        appScope.launch {
            val current = settings.first()
            if (current.vpnKind == SiteVpnKind.WireGuard) {
                secrets.clear(SecretKeys.TELEPORT_CONFIG)
                saveSettings(current.copy(vpnKind = SiteVpnKind.None))
            }
            val latest = settings.first()
            if (latest.siteConfigured && latest.consoleHost.isNotBlank() && !latest.reachChosen) {
                saveSettings(latest.copy(reachPreference = ReachPreference.Auto, reachChosen = true))
            }
        }
        val link = LinkWatch(context)
        link.start()
        val reconnects = Channel<Unit>(Channel.CONFLATED)
        reconnects.trySend(Unit)
        appScope.launch {
            settings
                .map { Triple(it.siteConfigured, it.consoleHost, it.vpnKind) }
                .distinctUntilChanged()
                .collect { reconnects.trySend(Unit) }
        }
        appScope.launch {
            link.changes.debounce(1_200).collect { reconnects.trySend(Unit) }
        }
        appScope.launch {
            protect.cameras().collect { feed ->
                probeMissingStreams(feed.items)
            }
        }
        appScope.launch {
            reconnects.receiveAsFlow().collectLatest {
                reconnect()
            }
        }
        appScope.launch {
            settings
                .map { Triple(it.consoleHost, it.dataSource, it.accountGate) }
                .distinctUntilChanged()
                .collect { StreamUrlCache.clear() }
        }
        appScope.launch {
            monitor.mode.collect { StreamUrlCache.clear() }
        }
    }

    /**
     * The TV joined a different link, or came back from one. Drop the sockets and
     * stream addresses from the old link, point Tailscale at the new one, and
     * bring the console connection back.
     */
    private suspend fun reconnect() {
        val current = settings.first()
        if (!current.siteConfigured) {
            vpnReach.stopOurTunnel(clearStatus = true)
            _startup.value = Startup.Idle
            return
        }
        http.evictConnections()
        StreamUrlCache.clear()
        StreamEpoch.bump()
        if (current.vpnKind == SiteVpnKind.Tailscale) vpnReach.onNetworkChanged()
        val wasReady = _startup.value is Startup.Ready
        if (wasReady) {
            repeat(4) { attempt ->
                if (attempt > 0) delay(1_500)
                val reached = probeConsole(current.consoleHost, current.trustConsoleCertificate)
                if (reached !is ProbeResult.Unreachable) return
            }
        }
        revive()
    }

    private suspend fun revive() {
        while (true) {
            val current = settings.first()
            if (!current.siteConfigured) {
                _startup.value = Startup.Idle
                return
            }
            try {
                if (current.vpnKind == SiteVpnKind.Tailscale) {
                    _startup.value = if (vpnReach.prepared()) {
                        Startup.Loading("Connecting to Tailscale.")
                    } else {
                        Startup.Loading("Allow the VPN connection on this TV.")
                    }
                    val joined = vpnReach.ensureSavedTunnel(current, askPermission = !vpnReach.prepared())
                    if (joined.isFailure) {
                        Log.w(TAG, "Tailscale did not reconnect", joined.exceptionOrNull())
                        delay(5_000)
                        continue
                    }
                }
                _startup.value = Startup.Loading("Connecting to the console.")
                val reached = probeConsole(current.consoleHost, current.trustConsoleCertificate)
                if (reached !is ProbeResult.Unreachable) {
                    _startup.value = Startup.Ready
                    return
                }
                Log.w(TAG, "Console did not answer: ${reached.reason}")
            } catch (error: Exception) {
                Log.w(TAG, "Startup reconnect failed", error)
            }
            _startup.value = Startup.Loading("Connecting to the console.")
            delay(5_000)
        }
    }

    suspend fun saveSettings(settings: AppSettings) {
        settingsRepository.save(settings)
    }

    /** Sets or clears (null) one camera's alert override. Atomic; safe to call from `rememberCoroutineScope`. */
    suspend fun setCameraAlertPrefs(cameraId: String, prefs: CameraAlertPrefs?) {
        if (cameraId.isBlank()) return
        settingsRepository.updateAlertPrefs { current ->
            val cameras = if (prefs == null) current.cameras - cameraId else current.cameras + (cameraId to prefs)
            current.copy(cameras = cameras)
        }
    }

    suspend fun setDefaultAlertPrefs(prefs: CameraAlertPrefs) {
        settingsRepository.updateAlertPrefs { it.copy(default = prefs) }
    }

    suspend fun upsertPersistent(camera: PersistentCamera) {
        if (camera.cameraId.isBlank()) return
        val current = settings.first()
        val next = current.persistentCameras.filterNot { it.cameraId == camera.cameraId } + camera.placed()
        saveSettings(current.copy(persistentCameras = next))
    }

    suspend fun removePersistent(cameraId: String) {
        if (cameraId.isBlank()) return
        val current = settings.first()
        saveSettings(current.copy(persistentCameras = current.persistentCameras.filterNot { it.cameraId == cameraId }))
    }

    suspend fun markCameraWizardDone(userId: String) {
        if (userId.isBlank()) return
        val current = settings.first()
        if (userId in current.cameraWizardDoneIds) return
        saveSettings(current.copy(cameraWizardDoneIds = current.cameraWizardDoneIds + userId))
    }

    /** Saves or clears the RTSP address this TV uses for one camera. */
    suspend fun setManualRtsp(cameraId: String, url: String?) {
        if (cameraId.isBlank()) return
        val clean = if (url == null) null else sanitizeRtsp(url) ?: return
        val current = settings.first()
        val next = current.manualRtspByCamera.toMutableMap()
        if (clean == null) next.remove(cameraId) else next[cameraId] = clean
        saveSettings(current.copy(manualRtspByCamera = next))
        StreamQuality.entries.forEach { StreamUrlCache.invalidate(cameraId, it) }
        StreamEpoch.bump()
        if (clean == null) {
            rtspKnown.remove(cameraId)
            rtspTriedAt.remove(cameraId)
        } else {
            _camerasNeedingRtsp.value = _camerasNeedingRtsp.value - cameraId
        }
    }

    private suspend fun probeMissingStreams(cameras: List<app.apt443.domain.Camera>) {
        val current = settings.first()
        if (!current.siteConfigured || current.dataSource != DataSourceMode.Live) {
            _camerasNeedingRtsp.value = emptySet()
            return
        }
        val missing = mutableSetOf<String>()
        val listed = cameras.map { it.id }.filter { it.isNotBlank() }.toSet()
        rtspKnown.keys.retainAll(listed)
        rtspTriedAt.keys.retainAll(listed)
        for (camera in cameras) {
            val id = camera.id
            if (id.isBlank()) continue
            if (settings.first().manualRtsp(id) != null) continue
            when (rtspKnown[id]) {
                true -> continue
                false -> missing.add(id)
                null -> {
                    val now = System.currentTimeMillis()
                    val last = rtspTriedAt[id] ?: 0L
                    if (now - last < 60_000L) continue
                    rtspTriedAt[id] = now
                    val offer = protect.liveStream(id, StreamQuality.Low)
                    if (settings.first().manualRtsp(id) != null) continue
                    when {
                        !offer.url.isNullOrBlank() -> rtspKnown[id] = true
                        offer.availability is Availability.HomeNetworkOnly -> rtspTriedAt.remove(id)
                        rtspIsMissing(offer.message) -> {
                            rtspKnown[id] = false
                            missing.add(id)
                        }
                    }
                }
            }
        }
        _camerasNeedingRtsp.value = missing
    }

    /** Protect answered, and this camera has no stream it will create. */
    private fun rtspIsMissing(message: String): Boolean {
        if (message.contains("did not include a playable URL", ignoreCase = true)) return true
        val code = Regex("RTSPS HTTP (\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return false
        return code == 400 || code == 403 || code == 404 || code == 409 || code == 422
    }

    /** Clears the site file so the TV returns to the setup form. Resident sign-in is cleared too. */
    suspend fun resetSite() {
        secrets.clear(SecretKeys.PROTECT_API_KEY)
        secrets.clear(SecretKeys.NETWORK_API_KEY)
        secrets.clear(SecretKeys.ACCESS_TOKEN)
        secrets.clear(SecretKeys.OS_PASSWORD)
        secrets.clear(SecretKeys.TAILSCALE_AUTH_KEY)
        secrets.clear(SecretKeys.TELEPORT_CONFIG)
        vpnReach.stopOurTunnel(clearStatus = true)
        val current = settings.first()
        saveSettings(
            current.copy(
                siteConfigured = false,
                consoleHost = "",
                osUsername = "",
                reachPreference = ReachPreference.Auto,
                reachChosen = false,
                vpnKind = SiteVpnKind.None,
                tailscaleLoginServer = "",
                accountGate = AccountGate.SignedOut,
                dataSource = DataSourceMode.Demo,
                accountName = "",
                accountEmail = "",
                accountRole = "",
                identityUserId = "",
                residentFirstName = "",
                residentLastName = "",
                residentStatus = "",
                cameraWizardDoneIds = emptySet(),
                persistentCameras = emptyList(),
                manualRtspByCamera = emptyMap(),
            ),
        )
        rtspKnown.clear()
        rtspTriedAt.clear()
        _camerasNeedingRtsp.value = emptySet()
    }

    suspend fun signIn(
        settings: AppSettings,
        apiKey: String,
        password: String,
        mode: ConsoleSignInMode,
    ): SignInResult {
        return consoleSignIn.signIn(settings, apiKey, password, mode)
    }

    suspend fun discoverConsole(
        savedHost: String,
        apiKey: String?,
        trustCertificate: Boolean,
    ): String {
        return ConsoleDiscovery.locate(http, context, savedHost, apiKey, trustCertificate)
            ?: ConsoleDiscovery.DEFAULT_CONSOLE_HOST
    }

    /**
     * Opens a console session when a local admin password is stored.
     * Does not replace the signed-in resident's name, email, or role.
     */
    suspend fun refreshAccount(): Result<ConsoleAccount> {
        val current = settings.first()
        return legacy.login(current)
    }

    /** Restores full access when a previous resident sign-in marked the TV as API-key only. */
    suspend fun healLocalAdminMode() {
        val current = settings.first()
        val hasPassword = !secrets.get(SecretKeys.OS_PASSWORD).isNullOrBlank()
        val healed = current.signInModeKeepingLocalAdmin(hasPassword)
        if (healed != current.signInMode) {
            saveSettings(current.copy(signInMode = healed))
        }
    }

    /** Reloads the signed-in resident from Identity. Does not touch console secrets. */
    suspend fun refreshResident() {
        healLocalAdminMode()
        val current = settings.first()
        if (current.identityUserId.isBlank()) return
        val user = fetchIdentityUsers().getOrNull()?.firstOrNull { it.id == current.identityUserId } ?: return
        val latest = settings.first()
        saveSettings(
            latest.copy(
                accountName = user.fullName,
                accountEmail = user.email.trim().ifBlank { latest.accountEmail },
                residentFirstName = user.firstName.trim().ifBlank { latest.residentFirstName },
                residentLastName = user.lastName.trim(),
                residentStatus = user.status.ifBlank { latest.residentStatus },
            ),
        )
    }

    suspend fun consoleReachable(host: String, trustCertificate: Boolean): Boolean {
        return probeConsole(host, trustCertificate) !is ProbeResult.Unreachable
    }

    private suspend fun probeConsole(host: String, trustCertificate: Boolean): ProbeResult {
        if (host.isBlank()) return ProbeResult.Unreachable("Enter a console address.")
        val settings = AppSettings(
            consoleHost = host,
            trustConsoleCertificate = trustCertificate,
            dataSource = DataSourceMode.Live,
        )
        var probe = http.probe(settings)
        if (probe is ProbeResult.Unreachable && probe.reason.contains("timed out", ignoreCase = true)) {
            delay(1_500)
            probe = http.probe(settings)
        }
        return probe
    }

    private suspend fun consoleMissMessage(draft: AppSettings, reason: String): String {
        val entered = draft.consoleHost.trim()
        val gateway = ConsoleDiscovery.gatewayIp(context)
        val lanConsole = if (
            !gateway.isNullOrBlank() &&
            !gateway.equals(entered, ignoreCase = true) &&
            probeConsole(gateway, trustCertificate = true) !is ProbeResult.Unreachable
        ) {
            gateway
        } else {
            null
        }
        return TailscaleLogin.consoleUnreachable(entered, reason, lanConsole)
    }

    suspend fun hasAccessCode(): Boolean = !secrets.get(SecretKeys.ACCESS_CODE).isNullOrBlank()

    suspend fun saveAccessCode(code: String) {
        secrets.put(SecretKeys.ACCESS_CODE, code)
    }

    suspend fun accessCodeMatches(code: String): Boolean {
        val saved = secrets.get(SecretKeys.ACCESS_CODE) ?: return false
        return saved == code
    }

    suspend fun isSiteConfigured(): Boolean {
        val saved = settings.first()
        return saved.siteConfigured &&
            saved.consoleHost.isNotBlank() &&
            !secrets.get(SecretKeys.PROTECT_API_KEY).isNullOrBlank()
    }

    suspend fun prepareVpn(): VpnStatus = vpnReach.prepare(settings.first())

    suspend fun applySitePackage(bytes: ByteArray, tvReachRaw: String? = null): Result<String> {
        val pack = SitePackage.parse(bytes).getOrElse { return Result.failure(it) }
        SitePackage.parseTvReach(tvReachRaw).getOrElse { return Result.failure(it) }
        secrets.put(SecretKeys.PROTECT_API_KEY, pack.apiKey)
        secrets.put(SecretKeys.ACCESS_TOKEN, pack.apiKey)
        secrets.put(SecretKeys.NETWORK_API_KEY, pack.apiKey)
        secrets.put(SecretKeys.OS_PASSWORD, pack.osPassword)
        if (pack.vpnSpecified) {
            when (pack.vpn) {
                SiteVpnKind.None -> {
                    secrets.clear(SecretKeys.TAILSCALE_AUTH_KEY)
                    secrets.clear(SecretKeys.TELEPORT_CONFIG)
                }
                SiteVpnKind.Tailscale -> {
                    secrets.put(SecretKeys.TAILSCALE_AUTH_KEY, pack.tailscaleAuthKey)
                    secrets.clear(SecretKeys.TELEPORT_CONFIG)
                }
                SiteVpnKind.WireGuard -> secrets.clear(SecretKeys.TAILSCALE_AUTH_KEY)
            }
        }
        val previous = settings.first()
        val kind = if (pack.vpnSpecified) pack.vpn else previous.vpnKind
        val draft = AppSettings(
            locationName = pack.siteName,
            consoleHost = pack.consoleHost,
            osUsername = pack.osUsername,
            trustConsoleCertificate = pack.trustConsoleCertificate,
            dataSource = DataSourceMode.Live,
            siteConfigured = true,
            accountGate = AccountGate.SignedOut,
            signInMode = ConsoleSignInMode.LocalUserAndApiKey,
            reachPreference = ReachPreference.Auto,
            reachChosen = true,
            vpnKind = if (kind == SiteVpnKind.WireGuard) SiteVpnKind.None else kind,
            tailscaleLoginServer = if (pack.vpn == SiteVpnKind.Tailscale) pack.tailscaleLoginServer else "",
        )
        saveSettings(draft.copy(siteConfigured = false, dataSource = DataSourceMode.Demo))
        if (draft.vpnKind == SiteVpnKind.Tailscale) {
            val joined = vpnReach.registerForSetup(draft)
            if (joined.isFailure) {
                return Result.failure(joined.exceptionOrNull() ?: IllegalStateException(TailscaleLogin.TIMED_OUT))
            }
            val reached = probeConsole(draft.consoleHost, draft.trustConsoleCertificate)
            if (reached is ProbeResult.Unreachable) {
                return Result.failure(IllegalStateException(consoleMissMessage(draft, reached.reason)))
            }
        }
        saveSettings(draft)
        val probe = consoleSignIn.signIn(
            draft,
            pack.apiKey,
            pack.osPassword,
            ConsoleSignInMode.LocalUserAndApiKey,
        )
        return when (probe) {
            is SignInResult.SignedIn -> Result.success("Connected to ${pack.siteName}. Residents can sign in on the TV.")
            is SignInResult.Failed -> Result.failure(IllegalStateException(probe.message))
        }
    }

    suspend fun fetchIdentityUsers(): Result<List<IdentityUser>> {
        val saved = settings.first()
        return protectClient.listIdentityUsers(saved)
    }

    suspend fun completeTenantSignIn(userId: String, email: String): Result<String> {
        val users = fetchIdentityUsers().getOrElse { return Result.failure(it) }
        val user = users.firstOrNull { it.id == userId }
            ?: return Result.failure(IllegalStateException("Choose a name from the list."))
        if (user.email.isBlank()) {
            return Result.failure(IllegalStateException("This name has no email on file yet."))
        }
        if (!TenantLabel.emailsEqual(email, user.email)) {
            return Result.failure(IllegalStateException("That email does not match this name."))
        }
        val saved = settings.first()
        val hasPassword = !secrets.get(SecretKeys.OS_PASSWORD).isNullOrBlank()
        saveSettings(saved.withResidentSignIn(user, hasPassword))
        return Result.success("Welcome, ${user.firstName.ifBlank { user.shortLabel }}.")
    }

    suspend fun exportSitePackageBytes(): ByteArray {
        val saved = settings.first()
        val kind = if (saved.vpnKind == SiteVpnKind.Tailscale) SiteVpnKind.Tailscale else SiteVpnKind.None
        return SitePackage(
            siteName = saved.locationName,
            consoleHost = saved.consoleHost,
            apiKey = secrets.get(SecretKeys.PROTECT_API_KEY).orEmpty(),
            osUsername = saved.osUsername,
            osPassword = secrets.get(SecretKeys.OS_PASSWORD).orEmpty(),
            networkApiKey = secrets.get(SecretKeys.NETWORK_API_KEY).orEmpty(),
            trustConsoleCertificate = saved.trustConsoleCertificate,
            vpn = kind,
            vpnSpecified = true,
            tailscaleAuthKey = if (kind == SiteVpnKind.Tailscale) {
                secrets.get(SecretKeys.TAILSCALE_AUTH_KEY).orEmpty()
            } else {
                ""
            },
            tailscaleLoginServer = if (kind == SiteVpnKind.Tailscale) saved.tailscaleLoginServer else "",
            tvReach = ReachPreference.Auto,
        ).toJsonBytes()
    }

    private companion object {
        const val TAG = "AptStartup"
    }
}

private class SwitchingProtect(
    private val settings: Flow<AppSettings>,
    private val demo: ProtectRepository,
    private val live: ProtectRepository,
) : ProtectRepository {
    private fun source() = settings.map { if (it.dataSource == DataSourceMode.Demo) demo else live }.distinctUntilChanged()

    override val connectionMode: Flow<ConnectionMode> = source().flatMapLatest { it.connectionMode }
    override fun cameras() = source().flatMapLatest { it.cameras() }
    override fun lights() = source().flatMapLatest { it.lights() }
    override fun sensors() = source().flatMapLatest { it.sensors() }
    override fun events() = source().flatMapLatest { it.events() }
    override fun alerts() = source().flatMapLatest { it.alerts() }
    override fun clips(cameraId: String) = source().flatMapLatest { it.clips(cameraId) }
    override suspend fun liveStream(cameraId: String, quality: StreamQuality) = pick().liveStream(cameraId, quality)
    override suspend fun previewJpeg(cameraId: String, highQuality: Boolean) = pick().previewJpeg(cameraId, highQuality)
    override suspend fun eventStill(eventId: String, thumbnailId: String) = pick().eventStill(eventId, thumbnailId)
    override suspend fun footage(query: ProtectClipQuery) = pick().footage(query)
    override suspend fun playback(clip: app.apt443.domain.FootageClip, onProgress: (Float) -> Unit) =
        pick().playback(clip, onProgress)
    override suspend fun previewAlert(cameraId: String?) = pick().previewAlert(cameraId)
    override suspend fun announce(cameraId: String, message: String) = pick().announce(cameraId, message)
    override suspend fun nudge(cameraId: String, move: PtzMove) = pick().nudge(cameraId, move)
    override suspend fun gotoPreset(cameraId: String, slot: Int) = pick().gotoPreset(cameraId, slot)
    override suspend fun setLight(lightId: String, on: Boolean) = pick().setLight(lightId, on)

    private suspend fun pick() = if (settings.first().dataSource == DataSourceMode.Demo) demo else live
}

private class SwitchingNetwork(
    private val settings: Flow<AppSettings>,
    private val demo: NetworkRepository,
    private val live: NetworkRepository,
) : NetworkRepository {
    private val source = settings
        .map { if (it.dataSource == DataSourceMode.Demo) demo else live }
        .distinctUntilChanged()

    override fun dashboard() = source.flatMapLatest { it.dashboard() }

    override suspend fun startSpeedTest(): Result<String> {
        val picked = if (settings.first().dataSource == DataSourceMode.Demo) demo else live
        return picked.startSpeedTest()
    }
}

private class SwitchingAccess(
    private val settings: Flow<AppSettings>,
    private val demo: AccessRepository,
    private val live: AccessRepository,
) : AccessRepository {
    private fun source() = settings.map { if (it.dataSource == DataSourceMode.Demo) demo else live }.distinctUntilChanged()

    override fun feed() = source().flatMapLatest { it.feed() }
    override suspend fun unlock(doorId: String, confirmed: Boolean) = pick().unlock(doorId, confirmed)
    override suspend fun grantsFor(email: String, userId: String) = pick().grantsFor(email, userId)

    private suspend fun pick() = if (settings.first().dataSource == DataSourceMode.Demo) demo else live
}
