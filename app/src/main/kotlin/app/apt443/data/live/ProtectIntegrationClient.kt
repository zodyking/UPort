package app.apt443.data.live

import app.apt443.domain.AppSettings
import app.apt443.domain.IdentityUser
import app.apt443.domain.Camera
import app.apt443.domain.ConsoleAccount
import app.apt443.domain.ConnectionMode
import app.apt443.domain.FootageClip
import app.apt443.domain.ProtectClipQuery
import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType
import app.apt443.domain.ProtectLight
import app.apt443.domain.ProtectSensor
import app.apt443.domain.PtzMove
import app.apt443.domain.SignInResult
import app.apt443.domain.ResidentAccess
import app.apt443.domain.StreamQuality
import app.apt443.data.settings.SecretKeys
import app.apt443.data.settings.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Official Protect Integration API.
 *
 * Local:  https://<console>/proxy/protect/integration/v1
 * Remote: https://api.ui.com/v1/connector/consoles/{consoleId}/proxy/protect/integration/v1
 *
 * Source: developer.ui.com Protect integration (v7.x pages checked Oct 2026),
 * including cameras, events, snapshots, RTSPS, talkback, and subscribe/events.
 * WebSocket is direct-to-console only. Ubiquiti documents that WebSocket
 * endpoints are not supported via the UniFi Cloud Connector.
 */
class ProtectIntegrationClient(
    private val http: UnifiHttp,
    private val secrets: SecretStore,
) {
    suspend fun getCameras(settings: AppSettings, mode: ConnectionMode): Result<List<Camera>> {
        val key = keyFor(mode) ?: return Result.failure(IllegalStateException(missingKey(mode)))
        val url = url(settings, mode, "/cameras")
        return http.text(settings, url, "GET", apiKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Protect cameras HTTP ${response.code}")
            parseCameras(response.body)
        }
    }

    suspend fun getEvents(
        settings: AppSettings,
        mode: ConnectionMode,
        limit: Int = 50,
        query: ProtectClipQuery = ProtectClipQuery(),
    ): Result<List<ProtectEvent>> {
        val key = keyFor(mode) ?: return Result.failure(IllegalStateException(missingKey(mode)))
        val url = url(settings, mode, eventsPath(limit, query))
        return http.text(settings, url, "GET", apiKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Protect events HTTP ${response.code}")
            parseEvents(response.body)
        }
    }

    suspend fun getLights(settings: AppSettings, mode: ConnectionMode): Result<List<ProtectLight>> {
        val key = keyFor(mode) ?: return Result.failure(IllegalStateException(missingKey(mode)))
        val url = url(settings, mode, "/lights")
        return http.text(settings, url, "GET", apiKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Protect lights HTTP ${response.code}")
            parseLights(response.body)
        }
    }

    suspend fun getSensors(settings: AppSettings, mode: ConnectionMode): Result<List<ProtectSensor>> {
        val key = keyFor(mode) ?: return Result.failure(IllegalStateException(missingKey(mode)))
        val url = url(settings, mode, "/sensors")
        return http.text(settings, url, "GET", apiKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Protect sensors HTTP ${response.code}")
            parseSensors(response.body)
        }
    }

    /**
     * PATCH /lights/{id} with lightMode on or off.
     * Official Integration update used by the viewer to turn a floodlight on.
     */
    suspend fun setLightMode(settings: AppSettings, lightId: String, on: Boolean): Result<Unit> {
        if (consoleHost(settings).isBlank()) {
            return Result.failure(IllegalStateException("Console host is required for lights."))
        }
        val key = secrets.get(SecretKeys.PROTECT_API_KEY)
            ?: return Result.failure(IllegalStateException("Protect integration API key is not set."))
        val url = "https://${consoleHost(settings)}/proxy/protect/integration/v1/lights/$lightId"
        val body = JSONObject().put("lightMode", if (on) "on" else "off").toString()
        return http.text(settings, url, "PATCH", apiKeyHeader(key), body).mapCatching { response ->
            if (response.code !in 200..299) error("Light HTTP ${response.code}")
        }
    }

    /**
     * POST /cameras/{id}/ptz/move with one axis.
     * Pan and tilt are -1..1. Zoom is 0..1. Each press sends a single step.
     */
    suspend fun movePtz(settings: AppSettings, cameraId: String, move: PtzMove): Result<Unit> {
        return postPtz(settings, cameraId, "/ptz/move", ptzBody(move))
    }

    /** POST /cameras/{id}/ptz/goto/{slot}. Home is -1. Saved views start at 0. */
    suspend fun gotoPtz(settings: AppSettings, cameraId: String, slot: Int): Result<Unit> {
        return postPtz(settings, cameraId, "/ptz/goto/$slot", null)
    }

    /**
     * JPEG Protect saved for one event. Official path:
     * GET /events/{id}/thumbnail
     * The bytes stay in memory. This does not write a file.
     */
    suspend fun eventThumbnail(settings: AppSettings, mode: ConnectionMode, eventId: String): Result<ByteArray> {
        val path = eventThumbnailPath(eventId) ?: return Result.failure(IllegalStateException("Event id is missing."))
        val key = keyFor(mode) ?: return Result.failure(IllegalStateException(missingKey(mode)))
        val url = url(settings, mode, path)
        return http.bytes(settings, url, imageKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Thumbnail HTTP ${response.code}")
            if (!isJpeg(response.bytes)) error("Thumbnail was not a picture.")
            response.bytes
        }
    }

    suspend fun snapshot(
        settings: AppSettings,
        mode: ConnectionMode,
        cameraId: String,
        highQuality: Boolean = false,
    ): Result<ByteArray> {
        val key = keyFor(mode) ?: return Result.failure(IllegalStateException(missingKey(mode)))
        val quality = if (highQuality) "true" else "false"
        val url = url(settings, mode, "/cameras/$cameraId/snapshot?highQuality=$quality")
        return http.bytes(settings, url, apiKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Snapshot HTTP ${response.code}")
            if (response.bytes.isEmpty()) error("Snapshot response was empty")
            response.bytes
        }
    }

    /**
     * Reads the public RTSPS streams, then creates high, medium, and low when
     * none of those tiers is present. The console host and token stay as returned.
     * rtsps on port 7441 is rewritten to rtsp on port 7447 so ExoPlayer can open it.
     */
    suspend fun createRtsps(
        settings: AppSettings,
        cameraId: String,
        quality: StreamQuality = StreamQuality.High,
    ): Result<String> {
        if (consoleHost(settings).isBlank()) {
            return Result.failure(IllegalStateException("Console host is required for RTSPS."))
        }
        val key = secrets.get(SecretKeys.PROTECT_API_KEY)
            ?: return Result.failure(IllegalStateException("Protect integration API key is not set."))
        val url = "https://${consoleHost(settings)}/proxy/protect/integration/v1/cameras/$cameraId/rtsps-stream"
        val headers = apiKeyHeader(key)
        val host = consoleHost(settings)
        val existing = http.text(settings, url, "GET", headers).getOrNull()
        if (existing != null && existing.code in 200..299) {
            pickStream(existing.body, quality, host)?.let { return Result.success(it) }
        }
        val body = JSONObject()
            .put("qualities", JSONArray().put("high").put("medium").put("low"))
            .toString()
        val created = http.text(settings, url, "POST", headers, body)
        val fromPost = created.getOrNull()?.takeIf { it.code in 200..299 }?.let { pickStream(it.body, quality, host) }
        if (fromPost != null) return Result.success(fromPost)
        val again = http.text(settings, url, "GET", headers).getOrNull()
        if (again != null && again.code in 200..299) {
            pickStream(again.body, quality, host)?.let { return Result.success(it) }
        }
        val posted = created.getOrNull()
        val detail = when {
            posted != null && posted.code !in 200..299 -> "RTSPS HTTP ${posted.code}"
            else -> created.exceptionOrNull()?.message ?: "RTSPS response did not include a playable URL."
        }
        return Result.failure(IllegalStateException(detail))
    }

    fun subscribeEventsUrl(settings: AppSettings): String {
        return "wss://${consoleHost(settings)}/proxy/protect/integration/v1/subscribe/events"
    }

    suspend fun listIdentityUsers(settings: AppSettings): Result<List<IdentityUser>> {
        val key = secrets.get(SecretKeys.PROTECT_API_KEY)
            ?: return Result.failure(IllegalStateException("API key is not set."))
        val headers = apiKeyHeader(key)
        val url = url(settings, ConnectionMode.OnNetwork, "/ulp-users")
        val response = http.text(settings, url, "GET", headers).getOrElse { error ->
            return Result.failure(IllegalStateException(error.message ?: "Could not load identity users."))
        }
        if (response.code !in 200..299) {
            return Result.failure(
                IllegalStateException("Identity users were not available (HTTP ${response.code})."),
            )
        }
        val listed = parseIdentityUsers(response.body)
        if (listed.isEmpty()) {
            return Result.failure(IllegalStateException("No identity users were returned for this site."))
        }
        val enriched = listed.map { user ->
            if (user.email.isNotBlank()) user
            else fetchIdentityUser(settings, headers, user.id).getOrNull() ?: user
        }
        return Result.success(enriched)
    }

    private suspend fun fetchIdentityUser(
        settings: AppSettings,
        headers: Map<String, String>,
        userId: String,
    ): Result<IdentityUser> {
        val url = url(settings, ConnectionMode.OnNetwork, "/ulp-users/$userId")
        val response = http.text(settings, url, "GET", headers).getOrElse { return Result.failure(it) }
        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("Identity user HTTP ${response.code}"))
        }
        return parseIdentityUsers(response.body).firstOrNull { it.id == userId }?.let { Result.success(it) }
            ?: parseIdentityUsers(response.body).firstOrNull()?.let { Result.success(it.copy(id = userId)) }
            ?: Result.failure(IllegalStateException("Identity user was empty."))
    }

    /**
     * POST /cameras/{id}/talkback-session
     * Returns an RTP URL plus the codec the camera expects. Audio is sent to that
     * URL directly, so this stays on the console network.
     */
    suspend fun createTalkback(settings: AppSettings, cameraId: String): Result<TalkbackSession> {
        if (consoleHost(settings).isBlank()) {
            return Result.failure(IllegalStateException("Console host is required for the camera speaker."))
        }
        val key = secrets.get(SecretKeys.PROTECT_API_KEY)
            ?: return Result.failure(IllegalStateException("Protect integration API key is not set."))
        val url = "https://${consoleHost(settings)}/proxy/protect/integration/v1/cameras/$cameraId/talkback-session"
        val headers = apiKeyHeader(key) + mapOf("Content-Type" to "application/json")
        return http.text(settings, url, "POST", headers, "{}").mapCatching { response ->
            if (response.code !in 200..299) error("Talkback HTTP ${response.code}")
            parseTalkback(response.body, cameraId)
        }
    }

    private suspend fun keyFor(mode: ConnectionMode): String? {
        return if (mode == ConnectionMode.Remote) {
            secrets.get(SecretKeys.SITE_MANAGER_KEY)
        } else {
            secrets.get(SecretKeys.PROTECT_API_KEY)
        }
    }

    private fun missingKey(mode: ConnectionMode): String {
        return if (mode == ConnectionMode.Remote) {
            "Site Manager API key is not set."
        } else {
            "Protect integration API key is not set."
        }
    }

    private fun url(settings: AppSettings, mode: ConnectionMode, path: String): String {
        return if (mode == ConnectionMode.Remote) {
            val id = settings.consoleId.trim()
            if (id.isBlank()) error("Console ID is required for the cloud connector.")
            "https://api.ui.com/v1/connector/consoles/$id/proxy/protect/integration/v1$path"
        } else {
            val host = consoleHost(settings)
            if (host.isBlank()) error("Console host is required.")
            "https://$host/proxy/protect/integration/v1$path"
        }
    }

    private suspend fun postPtz(
        settings: AppSettings,
        cameraId: String,
        path: String,
        body: String?,
    ): Result<Unit> {
        if (consoleHost(settings).isBlank()) {
            return Result.failure(IllegalStateException("Console host is required to aim the camera."))
        }
        val key = secrets.get(SecretKeys.PROTECT_API_KEY)
            ?: return Result.failure(IllegalStateException("Protect integration API key is not set."))
        val url = "https://${consoleHost(settings)}/proxy/protect/integration/v1/cameras/$cameraId$path"
        return http.text(settings, url, "POST", apiKeyHeader(key), body).mapCatching { response ->
            if (response.code !in 200..299) error("PTZ HTTP ${response.code}")
        }
    }
}

/**
 * Unofficial UniFi OS session + Protect bootstrap / video export.
 *
 * Sources (reverse-engineered, not the Integration API):
 * - POST /api/auth/login
 * - GET  /proxy/protect/api/bootstrap
 * - GET  /proxy/protect/api/video/export?camera=&start=&end=
 *   hjdhjd/unifi-protect docs/ProtectApi.md
 *
 * Used only for historical MP4 export. Live cameras and events prefer the
 * official Integration API. Failures stay failures.
 */
private data class BootstrapSnapshot(val host: String, val body: String, val atMs: Long)

private const val BOOTSTRAP_SNAPSHOT_MS = 60_000L

class ProtectLegacySession(
    private val http: UnifiHttp,
    private val secrets: SecretStore,
) {
    @Volatile
    private var csrf: String? = null

    @Volatile
    private var bootstrapSnapshot: BootstrapSnapshot? = null

    suspend fun signIn(settings: AppSettings, password: String): SignInResult {
        val host = consoleHost(settings)
        if (host.isBlank()) {
            return SignInResult.Failed("Enter the console address.", untrustedCertificate = false)
        }
        if (settings.osUsername.isBlank() || password.isBlank()) {
            return SignInResult.Failed(
                "Enter the username and password you use on the console.",
                untrustedCertificate = false,
            )
        }
        return login(settings, password).fold(
            onSuccess = { SignInResult.SignedIn(it) },
            onFailure = { error ->
                val untrusted = generateSequence<Throwable>(error) { it.cause }.any { it is javax.net.ssl.SSLException }
                val http = error.message?.let { message ->
                    Regex("HTTP (\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                val message = when {
                    untrusted -> "This console uses its own certificate. Trust it to sign in."
                    http == 401 || http == 403 -> "That username or password was not accepted."
                    http == 499 -> "Console dropped the sign-in connection. Connect with the Protect API key instead."
                    http != null -> "The console refused sign-in (HTTP $http)."
                    else -> "Can't reach that console from this TV. Check the address. Away from home, this TV uses the saved Tailscale auth key."
                }
                SignInResult.Failed(message, untrusted)
            },
        )
    }

    suspend fun login(settings: AppSettings, passwordOverride: String? = null): Result<ConsoleAccount> {
        val host = consoleHost(settings)
        val password = passwordOverride ?: secrets.get(SecretKeys.OS_PASSWORD)
        if (host.isBlank() || settings.osUsername.isBlank() || password.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("Sign-in needs the console address, username, and password."),
            )
        }
        val body = JSONObject()
            .put("username", settings.osUsername)
            .put("password", password)
            .put("rememberMe", true)
            .toString()
        val result = http.text(
            settings,
            "https://$host/api/auth/login",
            "POST",
            mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
            body,
        )
        return result.mapCatching { response ->
            captureCsrf(response.headers)
            if (response.code !in 200..299) error("UniFi OS login HTTP ${response.code}")
            parseAccount(response.body, settings.osUsername)
        }
    }

    /**
     * Camera list from the local Protect bootstrap after a console sign-in.
     * Used when a Protect integration API key has not been added yet.
     */
    suspend fun camerasFromSession(settings: AppSettings): Result<List<Camera>> {
        return bootstrap(settings).mapCatching { parseCameras(it) }
    }

    /**
     * Talkback settings from bootstrap, the same path Home Assistant uses when the session
     * POST endpoints are missing on the console.
     */
    suspend fun talkbackFromBootstrap(settings: AppSettings, cameraId: String): Result<TalkbackSession> {
        freshBootstrap(settings)?.let { body ->
            val parsed = runCatching { talkbackFromBootstrapBody(body, cameraId) }
            if (parsed.isSuccess) return parsed
        }
        return bootstrap(settings).mapCatching { body ->
            talkbackFromBootstrapBody(body, cameraId)
        }
    }

    private suspend fun bootstrap(settings: AppSettings): Result<String> {
        val host = consoleHost(settings)
        if (host.isBlank()) {
            return Result.failure(IllegalStateException("Console host is required."))
        }
        // A second login can return 403 while the cookie from setup still plays recordings.
        // Ask bootstrap with that session first, and sign in again only if the console rejects it.
        if (csrf == null) login(settings)
        val url = "https://$host/proxy/protect/api/bootstrap"
        var response = http.text(settings, url, "GET", sessionHeaders()).getOrElse { return Result.failure(it) }
        if (response.code == 401 || response.code == 403) {
            csrf = null
            login(settings).getOrElse { return Result.failure(it) }
            response = http.text(settings, url, "GET", sessionHeaders()).getOrElse { return Result.failure(it) }
        }
        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("Protect bootstrap HTTP ${response.code}"))
        }
        captureCsrf(response.headers)
        bootstrapSnapshot = BootstrapSnapshot(
            host = host,
            body = response.body,
            atMs = System.currentTimeMillis(),
        )
        return Result.success(response.body)
    }

    /** Recent bootstrap body for this console, so talkback does not download it again. */
    private fun freshBootstrap(settings: AppSettings): String? {
        val snapshot = bootstrapSnapshot ?: return null
        if (snapshot.host != consoleHost(settings)) return null
        if (System.currentTimeMillis() - snapshot.atMs > BOOTSTRAP_SNAPSHOT_MS) return null
        return snapshot.body
    }

    /** POST or PATCH with the same console cookie playback uses. */
    suspend fun authorizedWrite(
        settings: AppSettings,
        url: String,
        method: String,
        body: String,
    ): Result<String> {
        if (csrf == null) login(settings)
        val first = http.text(settings, url, method, sessionHeaders(), body)
        val response = first.getOrElse { return Result.failure(it) }
        captureCsrf(response.headers)
        if (response.code == 401 || response.code == 403) {
            login(settings)
            return http.text(settings, url, method, sessionHeaders(), body).mapCatching { retry ->
                captureCsrf(retry.headers)
                if (retry.code !in 200..299) error("Console HTTP ${retry.code}")
                retry.body
            }
        }
        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("Console HTTP ${response.code}"))
        }
        return Result.success(response.body)
    }

    suspend fun authorizedText(settings: AppSettings, url: String): Result<String> {
        if (csrf == null) {
            login(settings).getOrElse { return Result.failure(it) }
        }
        val first = http.text(settings, url, "GET", sessionHeaders())
        val response = first.getOrElse { return Result.failure(it) }
        if (response.code == 401 || response.code == 403) {
            csrf = null
            login(settings).getOrElse { return Result.failure(it) }
            return http.text(settings, url, "GET", sessionHeaders()).mapCatching { retry ->
                if (retry.code !in 200..299) error("Network HTTP ${retry.code}")
                retry.body
            }
        }
        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("Network HTTP ${response.code}"))
        }
        return Result.success(response.body)
    }

    private fun sessionHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        csrf?.let { put("X-CSRF-Token", it) }
    }

    private fun captureCsrf(headers: Map<String, String>) {
        headers.entries.firstOrNull {
            it.key.equals("x-csrf-token", true) || it.key.equals("x-updated-csrf-token", true)
        }?.value?.takeIf { it.isNotBlank() }?.let { csrf = it }
    }

    /**
     * Private talkback session. Home Assistant uses this local-admin route when
     * the public integration talkback call is missing on the console.
     */
    suspend fun createTalkback(settings: AppSettings, cameraId: String): Result<TalkbackSession> {
        val host = consoleHost(settings)
        if (host.isBlank()) {
            return Result.failure(IllegalStateException("Console host is required for the camera speaker."))
        }
        if (csrf == null) login(settings)
        val url = "https://$host/proxy/protect/api/cameras/$cameraId/talkback-session"
        return postTalkback(settings, url, cameraId)
    }

    /**
     * Opens the console's talkback WebSocket for [cameraId]. Audio frames are written to it as
     * binary messages, which is the route that works when the camera's own UDP port is not
     * reachable from this TV.
     */
    fun talkbackSocket(
        settings: AppSettings,
        sessionUrl: String,
        apiKey: String?,
        listener: okhttp3.WebSocketListener,
    ): okhttp3.WebSocket? {
        val host = consoleHost(settings)
        if (host.isBlank()) return null
        val absolute = when {
            sessionUrl.startsWith("wss://") || sessionUrl.startsWith("ws://") -> sessionUrl
            sessionUrl.startsWith("/") -> "wss://$host$sessionUrl"
            else -> return null
        }
        val headers = buildMap {
            if (!apiKey.isNullOrBlank()) put("X-API-KEY", apiKey)
            csrf?.let { put("X-CSRF-Token", it) }
        }
        return runCatching { http.openWebSocket(settings, absolute, headers, listener) }.getOrNull()
    }

    /** Groups and camera rights for one person, from the same console session playback uses. */
    suspend fun residentProfile(settings: AppSettings, email: String, userId: String): Result<ResidentAccess> {
        return bootstrap(settings).mapCatching { residentFromBootstrap(it, email, userId) }
    }

    private suspend fun postTalkback(
        settings: AppSettings,
        url: String,
        cameraId: String,
    ): Result<TalkbackSession> {
        val first = http.text(settings, url, "POST", sessionHeaders(), "{}")
        val response = first.getOrElse { return Result.failure(it) }
        captureCsrf(response.headers)
        if (response.code == 401 || response.code == 403) {
            csrf = null
            login(settings).getOrElse { return Result.failure(it) }
            return http.text(settings, url, "POST", sessionHeaders(), "{}").mapCatching { retry ->
                captureCsrf(retry.headers)
                if (retry.code !in 200..299) error("Talkback HTTP ${retry.code}")
                parseTalkback(retry.body, cameraId)
            }
        }
        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("Talkback HTTP ${response.code}"))
        }
        return runCatching { parseTalkback(response.body, cameraId) }
    }

    /** NVR event history from the local console session. */
    suspend fun listEvents(settings: AppSettings, query: ProtectClipQuery): Result<List<ProtectEvent>> {
        val host = consoleHost(settings)
        if (host.isBlank()) {
            return Result.failure(IllegalStateException("Console host is required."))
        }
        if (csrf == null) {
            login(settings).getOrElse { return Result.failure(it) }
        }
        val end = (query.endEpochMs ?: System.currentTimeMillis()) + 60_000L
        val start = query.startEpochMs ?: (end - 30L * 86_400_000L)
        // Each page is only a slice. Walking the window from whichever edge the console
        // returned keeps yesterday attached to today instead of jumping back two days.
        val collected = LinkedHashMap<String, ProtectEvent>()
        var windowStart = start
        var windowEnd = end
        var failure: Throwable? = null
        var pages = 0
        while (pages < EVENT_PAGE_LIMIT && windowStart < windowEnd) {
            val page = fetchEventPage(
                settings,
                host,
                windowStart,
                windowEnd,
                limit = EVENT_PAGE_SIZE,
                cameraId = query.cameraId,
            ).getOrElse {
                failure = it
                break
            }
            page.forEach { collected[it.id] = it }
            pages += 1
            val next = nextEventWindow(page, windowStart, windowEnd, EVENT_PAGE_SIZE) ?: break
            windowStart = next.first
            windowEnd = next.second
        }
        if (collected.isEmpty() && failure != null) return Result.failure(failure)
        return Result.success(collected.values.toList())
    }

    private suspend fun fetchEventPage(
        settings: AppSettings,
        host: String,
        start: Long,
        end: Long,
        limit: Int,
        cameraId: String?,
    ): Result<List<ProtectEvent>> {
        val params = mutableListOf("start=$start", "end=$end", "limit=$limit")
        cameraId?.takeIf { it.isNotBlank() }?.let { params += "cameras=$it" }
        val url = "https://$host/proxy/protect/api/events?" + params.joinToString("&")
        val first = http.text(settings, url, "GET", sessionHeaders())
        val response = first.getOrElse { return Result.failure(it) }
        if (response.code == 401 || response.code == 403) {
            csrf = null
            login(settings).getOrElse { return Result.failure(it) }
            return http.text(settings, url, "GET", sessionHeaders()).mapCatching { retry ->
                if (retry.code !in 200..299) error("Protect events HTTP ${retry.code}")
                parseEvents(retry.body)
            }
        }
        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("Protect events HTTP ${response.code}"))
        }
        return Result.success(parseEvents(response.body))
    }

    /**
     * JPEG the Protect console shows on a clip.
     * GET /proxy/protect/api/events/{id}/thumbnail
     * Older payloads stored that same id as `e-{eventId}` on `/thumbnails/`. A missing
     * picture is HTTP 404 and does not sign in again. The bytes stay in memory.
     */
    suspend fun thumbnail(settings: AppSettings, thumbnailId: String): Result<ByteArray> {
        val url = consoleEventThumbnailUrl(consoleHost(settings), thumbnailId)
            ?: return Result.failure(IllegalStateException("This clip has no snapshot yet."))
        if (csrf == null) login(settings).getOrElse { return Result.failure(it) }
        val loaded = loadThumbnail(settings, url)
        if (loaded.isSuccess || !thumbnailNeedsLogin(loaded.exceptionOrNull()?.message)) return loaded
        csrf = null
        login(settings).getOrElse { return Result.failure(it) }
        return loadThumbnail(settings, url)
    }

    private suspend fun loadThumbnail(settings: AppSettings, url: String): Result<ByteArray> {
        return http.bytes(settings, url, stillHeaders()).mapCatching { response ->
            if (response.code !in 200..299) error("Thumbnail HTTP ${response.code}")
            if (!isJpeg(response.bytes)) error("Thumbnail was not a picture.")
            response.bytes
        }
    }

    private fun stillHeaders(): Map<String, String> = buildMap {
        put("Accept", "image/jpeg, */*")
        csrf?.let { put("X-CSRF-Token", it) }
    }

    /**
     * Picks a recording export the TV can actually decode.
     *
     * Channel 0 is the camera's full-resolution stream, which on newer cameras is 4K HEVC and
     * exhausts the handful of hardware decoders a Fire TV stick has. Channel 1 is asked for
     * first because it looks the same on a TV and decodes everywhere. Each variant is probed
     * in turn because a camera only has the channels and recording types it was set up with.
     */
    suspend fun exportUrl(settings: AppSettings, cameraId: String, startMs: Long, endMs: Long): Result<String> {
        val host = consoleHost(settings)
        if (host.isBlank()) {
            return Result.failure(IllegalStateException("Console host is required."))
        }
        if (csrf == null) {
            login(settings).getOrElse { return Result.failure(it) }
        }
        var lastCode: Int? = null
        var lastError: Throwable? = null
        var reauthorized = false
        for (variant in EXPORT_VARIANTS) {
            val url = exportEndpoint(host, cameraId, startMs, endMs, variant)
            var code = probeExport(settings, url).getOrElse {
                lastError = it
                continue
            }
            if ((code == 401 || code == 403) && !reauthorized) {
                reauthorized = true
                csrf = null
                login(settings).getOrElse { return Result.failure(it) }
                code = probeExport(settings, url).getOrElse {
                    lastError = it
                    continue
                }
            }
            if (code in 200..299) return Result.success(url)
            // Range probe on an empty span comes back 416; another channel may still have video.
            if (code == 416) continue
            lastCode = code
        }
        val message = when {
            lastCode == 401 || lastCode == 403 ->
                "The console refused the recording export (HTTP $lastCode). Sign in with a local console admin."
            lastCode == 404 -> "The console has no recording for this time range."
            lastCode != null -> "Recording export HTTP $lastCode"
            else -> lastError?.message ?: "The console did not answer the recording export."
        }
        return Result.failure(IllegalStateException(message))
    }

    /**
     * Writes an export to [target]. ExoPlayer's own data source would send the session
     * cookies but not the CSRF token, so the download is done here where the token lives.
     * Returns the number of bytes written.
     */
    suspend fun downloadExport(
        settings: AppSettings,
        url: String,
        target: File,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<Long> {
        if (csrf == null) {
            login(settings).getOrElse { return Result.failure(it) }
        }
        var answer = http.download(settings, url, exportHeaders(), target, onProgress)
            .getOrElse { return Result.failure(it) }
        if (answer.code == 401 || answer.code == 403) {
            csrf = null
            login(settings).getOrElse { return Result.failure(it) }
            answer = http.download(settings, url, exportHeaders(), target, onProgress)
                .getOrElse { return Result.failure(it) }
        }
        if (answer.code !in 200..299) {
            return Result.failure(IllegalStateException("Recording export HTTP ${answer.code}"))
        }
        val type = answer.contentType.orEmpty()
        if (type.startsWith("text/") || type.contains("html", ignoreCase = true)) {
            return Result.failure(
                IllegalStateException("The console returned a web page instead of video. Sign in again."),
            )
        }
        return Result.success(answer.bytes)
    }

    private fun exportEndpoint(
        host: String,
        cameraId: String,
        startMs: Long,
        endMs: Long,
        variant: ExportVariant,
    ): String = buildString {
        append("https://$host/proxy/protect/api/video/export")
        append("?camera=$cameraId&start=$startMs&end=$endMs")
        variant.channel?.let { append("&channel=$it&lens=0") }
        variant.type?.let { append("&type=$it") }
        append("&filename=apt443-$startMs.mp4")
    }

    private fun exportHeaders(): Map<String, String> = buildMap {
        put("Accept", "video/mp4")
        csrf?.let { put("X-CSRF-Token", it) }
    }

    private suspend fun probeExport(settings: AppSettings, url: String): Result<Int> {
        val headers = buildMap {
            putAll(exportHeaders())
            put("Range", "bytes=0-0")
        }
        return http.status(settings, url, "GET", headers)
    }

    private data class ExportVariant(val channel: Int?, val type: String?)

    private companion object {
        /** Ordered best-for-a-TV first; the last entry is the bare request older builds sent. */
        val EXPORT_VARIANTS = listOf(
            ExportVariant(channel = 1, type = null),
            ExportVariant(channel = 0, type = null),
            ExportVariant(channel = 1, type = "rotating"),
            ExportVariant(channel = null, type = null),
        )
    }
}

internal fun parseAccount(body: String, username: String): ConsoleAccount {
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return ConsoleAccount(displayName = username, username = username, email = "", role = "")
    val sso = json.optJSONObject("sso_account") ?: json.optJSONObject("ssoAccount")
    val email = json.optString("email").ifBlank { sso?.optString("email").orEmpty() }
    val user = json.optString("username").ifBlank { sso?.optString("username").orEmpty() }.ifBlank { username }
    val full = json.optString("full_name").ifBlank { json.optString("fullName") }.ifBlank {
        sso?.optString("full_name").orEmpty().ifBlank { sso?.optString("fullName").orEmpty() }
    }
    val combined = listOf(
        json.optString("first_name").ifBlank { json.optString("firstName") },
        json.optString("last_name").ifBlank { json.optString("lastName") },
    ).filter { it.isNotBlank() }.joinToString(" ")
    val display = full.ifBlank { combined }.ifBlank { email }.ifBlank { user }
    val group = json.optJSONArray("groups")?.optJSONObject(0)?.let { item ->
        item.optString("name").ifBlank { item.optString("unique_id") }
    }.orEmpty()
    val role = json.optString("role").ifBlank { group }.ifBlank {
        when {
            json.optBoolean("isOwner") || json.optBoolean("is_owner") -> "Owner"
            json.optBoolean("super_admin") || json.optBoolean("isSuperAdmin") -> "Super Admin"
            else -> ""
        }
    }
    return ConsoleAccount(displayName = display, username = user, email = email, role = role)
}

/** Groups and camera rights for one person inside a Protect bootstrap payload. */
internal fun residentFromBootstrap(body: String, email: String, userId: String): ResidentAccess {
    val root = runCatching { JSONObject(body) }.getOrNull()
        ?: return ResidentAccess(detail = "The console profile could not be read.")
    val users = bootstrapArray(root, "users")
        ?: return ResidentAccess(detail = "The console did not list users.")
    val wantedEmail = email.trim().lowercase()
    val wantedId = userId.trim()
    var match: JSONObject? = null
    for (i in 0 until users.length()) {
        val item = users.optJSONObject(i) ?: continue
        val id = item.optString("id").ifBlank { item.optString("uniqueId") }
        val itemEmail = item.optString("email").trim().lowercase()
        if ((wantedId.isNotBlank() && id == wantedId) ||
            (wantedEmail.isNotBlank() && itemEmail == wantedEmail)
        ) {
            match = item
            break
        }
    }
    val user = match ?: return ResidentAccess(detail = "No console profile matches this person.")
    val groupNames = mutableMapOf<String, String>()
    bootstrapArray(root, "groups")?.let { groups ->
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            val name = group.optString("name").ifBlank { group.optString("uniqueId") }
            if (name.isBlank()) continue
            listOf(group.optString("id"), group.optString("uniqueId")).filter { it.isNotBlank() }
                .forEach { groupNames[it] = name }
        }
    }
    val cameraNames = mutableMapOf<String, String>()
    bootstrapArray(root, "cameras")?.let { cameras ->
        for (i in 0 until cameras.length()) {
            val camera = cameras.optJSONObject(i) ?: continue
            val name = camera.optString("name").ifBlank { "Camera" }
            listOf(camera.optString("id"), camera.optString("mac")).filter { it.isNotBlank() }
                .forEach { cameraNames[it] = name }
        }
    }
    val groups = linkedSetOf<String>()
    val membership = user.optJSONArray("groups")
    if (membership != null) {
        for (i in 0 until membership.length()) {
            val raw = membership.optJSONObject(i)
            val id = raw?.optString("id")?.ifBlank { raw.optString("uniqueId") }
                ?: membership.optString(i)
            val name = raw?.optString("name").orEmpty().ifBlank { groupNames[id].orEmpty() }.ifBlank { id }
            if (name.isNotBlank()) groups += name
        }
    }
    user.optString("role").takeIf { it.isNotBlank() }?.let { groups += it }
    val cameras = linkedSetOf<String>()
    val policies = linkedSetOf<String>()
    val permissions = user.optJSONArray("allPermissions") ?: user.optJSONArray("permissions")
    var seesCameras = false
    if (permissions != null) {
        for (i in 0 until permissions.length()) {
            val objectPerm = permissions.optJSONObject(i)
            val raw = objectPerm?.optString("rawPermission")?.ifBlank { objectPerm.optString("permission") }
                ?: permissions.optString(i)
            if (!raw.contains("camera", ignoreCase = true)) continue
            seesCameras = true
            val objectId = objectPerm?.optString("objectId").orEmpty().ifBlank { objectPerm?.optString("id").orEmpty() }
            val named = cameraNames[objectId]
            if (!named.isNullOrBlank()) cameras += named
            policies += if (raw.contains("write", true)) "Control cameras" else "View cameras"
        }
    }
    if (seesCameras && cameras.isEmpty()) cameras += "All cameras"
    return ResidentAccess(
        groups = groups.toList(),
        policies = policies.toList(),
        cameras = cameras.toList(),
    )
}

private fun bootstrapArray(root: JSONObject, key: String): JSONArray? {
    root.optJSONArray(key)?.let { return it }
    for (parent in listOf("nvr", "bootstrap", "data")) {
        root.optJSONObject(parent)?.optJSONArray(key)?.let { return it }
    }
    return null
}

internal fun parseIdentityUsers(body: String): List<IdentityUser> {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return emptyList()
    val found = mutableListOf<JSONObject>()
    collectIdentityUsers(root, found)
    return found.mapNotNull { identityUserOrNull(it) }
        .distinctBy { it.id }
        .sortedBy { it.shortLabel.lowercase() }
}

private fun collectIdentityUsers(node: Any?, into: MutableList<JSONObject>) {
    when (node) {
        is JSONArray -> {
            for (i in 0 until node.length()) collectIdentityUsers(node.opt(i), into)
        }
        is JSONObject -> {
            if (isIdentityUser(node)) {
                into.add(node)
                return
            }
            val keys = node.keys()
            while (keys.hasNext()) collectIdentityUsers(node.opt(keys.next()), into)
        }
    }
}

private fun isIdentityUser(item: JSONObject): Boolean {
    val id = item.optString("id").ifBlank { item.optString("userId") }
    if (id.isBlank()) return false
    return item.has("firstName") || item.has("first_name") ||
        item.has("lastName") || item.has("last_name") ||
        item.has("fullName") || item.has("full_name") ||
        item.has("email")
}

private fun identityUserOrNull(item: JSONObject): IdentityUser? {
    val id = item.optString("id").ifBlank { item.optString("userId") }
    if (id.isBlank()) return null
    val status = item.optString("status")
    if (status.equals("DEACTIVATED", true) ||
        status.equals("DISABLED", true) ||
        status.equals("INACTIVE", true) ||
        status.equals("DELETED", true)
    ) {
        return null
    }
    val first = personName(item.optString("firstName").ifBlank { item.optString("first_name") })
    val last = personName(item.optString("lastName").ifBlank { item.optString("last_name") })
    val full = personName(
        item.optString("fullName")
            .ifBlank { item.optString("full_name") }
            .ifBlank { item.optString("name") },
    )
    val split = splitFullName(full, first, last)
    if (split.first.isBlank() && split.second.isBlank()) return null
    val email = item.optString("email")
        .ifBlank { item.optJSONObject("contact")?.optString("email").orEmpty() }
        .trim()
    return IdentityUser(
        id = id,
        firstName = split.first,
        lastName = split.second,
        email = email,
        status = status.ifBlank { "ACTIVE" },
    )
}

private fun personName(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank() || trimmed.equals("null", true) || trimmed.contains('@')) return ""
    return trimmed
}

private fun splitFullName(full: String, first: String, last: String): Pair<String, String> {
    if (first.isNotBlank() || last.isNotBlank()) return Pair(first, last)
    val parts = full.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> Pair("", "")
        parts.size == 1 -> Pair(parts[0], "")
        else -> Pair(parts.first(), parts.last())
    }
}

internal fun parseCameras(body: String): List<Camera> {
    val array = jsonArray(body, "data", "cameras") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("mac") }
            if (id.isBlank()) continue
            val name = item.optString("name").ifBlank { "Camera" }
            val state = item.optString("state")
            val online = item.optBoolean("isConnected", state.equals("CONNECTED", true) || state.isBlank())
            val featureFlags = item.optJSONObject("featureFlags")
            val doorbell = item.optBoolean("isDoorbell") || featureFlags?.optBoolean("isDoorbell") == true
            val hasSpeaker = when {
                featureFlags?.has("hasSpeaker") == true -> featureFlags.optBoolean("hasSpeaker")
                item.has("hasSpeaker") -> item.optBoolean("hasSpeaker")
                else -> doorbell
            }
            add(
                Camera(
                    id = id,
                    name = name,
                    online = online,
                    isDoorbell = doorbell,
                    model = item.optString("type").ifBlank { item.optString("marketName") },
                    hasSpeaker = hasSpeaker,
                    isPtz = featureFlags?.optBoolean("isPtz") == true || item.optBoolean("ptzControlEnabled"),
                ),
            )
        }
    }
}

internal fun parseEvents(body: String): List<ProtectEvent> {
    val array = eventArray(body) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val rawType = item.optString("type")
            val type = protectType(rawType)
            val alarming = rawType.equals("sensoralarm", ignoreCase = true)
            val labels = eventLabels(item)
            val cameraObject = item.optJSONObject("camera")
            val cameraId = item.optString("camera")
                .ifBlank { item.optString("cameraId") }
                .ifBlank { cameraObject?.optString("id").orEmpty() }
                .ifBlank { item.optString("device") }
                .ifBlank { item.optString("sensor") }
                .ifBlank { null }
            val start = epochMillis(item.optLong("start", 0L))
            val endRaw = if (item.isNull("end")) 0L else epochMillis(item.optLong("end"))
            add(
                ProtectEvent(
                    id = id,
                    cameraId = cameraId,
                    cameraName = item.optString("cameraName")
                        .ifBlank { cameraObject?.optString("name").orEmpty() }
                        .trim()
                        .takeUnless { it.equals("null", ignoreCase = true) }
                        ?: "Camera",
                    type = type,
                    smartLabels = labels,
                    startEpochMs = start,
                    endEpochMs = endRaw.takeIf { it > start },
                    summary = eventSummary(type, labels, alarming),
                    alarming = alarming,
                    thumbnailId = eventThumbnailId(item),
                ),
            )
        }
    }
}

internal fun parseSubscribeItem(body: String): ProtectEvent? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val item = root.optJSONObject("item") ?: root
    val id = item.optString("id")
    if (id.isBlank()) return null
    val rawType = item.optString("type")
    val type = protectType(rawType)
    val alarming = rawType.equals("sensoralarm", ignoreCase = true)
    val labels = eventLabels(item)
    val device = item.optString("device").ifBlank { item.optString("camera") }.ifBlank { item.optString("sensor") }.ifBlank { null }
    val start = epochMillis(item.optLong("start", 0L))
    val endRaw = if (item.isNull("end")) 0L else epochMillis(item.optLong("end"))
    return ProtectEvent(
        id = id,
        cameraId = device,
        cameraName = device ?: "Camera",
        type = type,
        smartLabels = labels,
        startEpochMs = start,
        endEpochMs = endRaw.takeIf { it > start },
        summary = eventSummary(type, labels, alarming),
        alarming = alarming,
        thumbnailId = eventThumbnailId(item),
    )
}

internal fun eventThumbnailId(item: JSONObject): String {
    val meta = item.optJSONObject("metadata")
    return sequenceOf(
        item.opt("thumbnailId"),
        item.opt("thumbnail"),
        meta?.opt("thumbnailId"),
        meta?.opt("thumbnail"),
    ).map { thumbnailToken(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
}

/** A thumbnail string, or the id nested on a thumbnail object. JSON text is not an id. */
internal fun thumbnailToken(value: Any?): String {
    val raw = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONObject -> {
            val nested = value.opt("id") ?: value.opt("thumbnailId")
            if (nested is String) nested else ""
        }
        else -> ""
    }.trim()
    if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.startsWith("{")) return ""
    return raw
}

/**
 * Id for GET /events/{id}/thumbnail.
 * Protect used to store the same clip as `e-{eventId}` on the retired thumbnails route.
 */
internal fun consoleStillId(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.removePrefix("e-").trim()
}

/** Path under the Protect API for one clip snapshot. Width stays small so the JPEG fits in memory. */
internal fun eventThumbnailPath(id: String): String? {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) return null
    return "/events/$trimmed/thumbnail?w=640"
}

/** Private console URL for the snapshot shown on a playback clip. */
internal fun consoleEventThumbnailUrl(host: String, eventOrThumbnailId: String): String? {
    val path = eventThumbnailPath(consoleStillId(eventOrThumbnailId)) ?: return null
    val trimmedHost = host.trim()
    if (trimmedHost.isEmpty()) return null
    return "https://$trimmedHost/proxy/protect/api$path"
}

/** HTTP status from a thumbnail failure message, when this client wrote one. */
internal fun thumbnailHttpStatus(message: String?): Int? {
    return Regex("""HTTP (\d{3})""").find(message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
}

/** Only an expired session signs in again. HTTP 404 means this clip has no snapshot yet. */
internal fun thumbnailNeedsLogin(message: String?): Boolean {
    val code = thumbnailHttpStatus(message)
    return code == 401 || code == 403
}

internal fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

private const val EVENT_PAGE_SIZE = 300
private const val EVENT_PAGE_LIMIT = 8

/**
 * The next slice of the same window, with no gap.
 *
 * A full page that reaches [windowEnd] is the newest slice, so the following request ends
 * just before its oldest clip. A full page that starts at [windowStart] is the oldest slice,
 * so the following request starts just after its newest clip.
 */
internal fun nextEventWindow(
    events: List<ProtectEvent>,
    windowStart: Long,
    windowEnd: Long,
    pageSize: Int,
): Pair<Long, Long>? {
    if (events.size < pageSize) return null
    val oldest = events.minOf { it.startEpochMs }
    val newest = events.maxOf { it.startEpochMs }
    val touchesEnd = newest >= windowEnd - 180_000L
    val touchesStart = oldest <= windowStart + 180_000L
    return when {
        touchesEnd && oldest > windowStart + 1_000L -> windowStart to (oldest - 1)
        touchesStart && newest < windowEnd - 1_000L -> (newest + 1) to windowEnd
        else -> null
    }
}

/** Later pages replace earlier copies of the same event. */
internal fun mergeEvents(first: List<ProtectEvent>, second: List<ProtectEvent>): List<ProtectEvent> {
    val byId = LinkedHashMap<String, ProtectEvent>()
    first.forEach { byId[it.id] = it }
    second.forEach { byId[it.id] = it }
    return byId.values.toList()
}

/** Seconds from older payloads become milliseconds. A missing time stays 0. */
internal fun epochMillis(raw: Long): Long {
    if (raw <= 0L) return 0L
    return if (raw < 10_000_000_000L) raw * 1000L else raw
}

private fun eventLabels(item: JSONObject): List<String> {
    val direct = stringList(item.optJSONArray("smartDetectTypes"))
    if (direct.isNotEmpty()) return direct
    return stringList(item.optJSONObject("metadata")?.optJSONArray("smartDetectTypes"))
}

/**
 * Everything the camera told us about where to send speaker audio.
 *
 * Protect returns these fields as `talkbackSettings`. [bindAddr] and [bindPort] are the socket
 * the camera is listening on, which is the same destination Home Assistant and homebridge send
 * to. [url] is the console's own rendering of that address and is preferred when present.
 */
data class TalkbackSession(
    val url: String,
    val codec: String,
    val samplingRate: Int,
    val bitsPerSample: Int,
    val channels: Int = 1,
    val bindAddr: String = "",
    val bindPort: Int = 0,
    val quality: Int = 0,
    val cameraId: String = "",
    /** Camera IP from bootstrap when [bindAddr] is a wildcard. */
    val cameraHost: String = "",
) {
    /**
     * WebSocket URL, used only when the session did not give a udp or rtp address.
     * uiprotect streams to [url] directly and does not treat a socket open as playback.
     */
    val speakerWebSocket: String?
        get() {
        val raw = url.trim()
        return when {
            raw.startsWith("wss://") || raw.startsWith("ws://") -> raw
            raw.startsWith("/") && raw.contains("talkback") -> raw
            else -> null
        }
        }

    /** Camera UDP port. Web, RTSP, and console URLs are not speaker targets. */
    val destination: Pair<String, Int>?
        get() {
            udpTarget(url)?.let { return it }
            val host = bindAddr.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: cameraHost.takeIf { it.isNotBlank() }
                ?: return null
            val port = bindPort.takeIf { it > 0 } ?: return null
            return host to port
        }
}

private fun udpTarget(raw: String): Pair<String, Int>? {
    if (raw.isBlank()) return null
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme in setOf("ws", "wss", "http", "https", "rtsp", "rtsps")) return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port.takeIf { it > 0 }
        ?: if (scheme == "udp" || scheme == "rtp") 7004 else return null
    return host to port
}

private fun bootstrapCamerasArray(body: String): JSONArray? {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
    if (root is JSONArray) return root
    if (root !is JSONObject) return null
    root.optJSONArray("cameras")?.let { return it }
    root.optJSONObject("data")?.optJSONArray("cameras")?.let { return it }
    root.optJSONArray("data")?.let { return it }
    return null
}

internal fun findCameraJson(body: String, cameraId: String): JSONObject? {
    val array = bootstrapCamerasArray(body) ?: return null
    val wanted = cameraId.trim()
    if (wanted.isBlank()) return null
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optString("id")
        val mac = item.optString("mac")
        if (id.equals(wanted, ignoreCase = true) || mac.equals(wanted, ignoreCase = true)) {
            return item
        }
    }
    return null
}

internal fun cameraIdsInBootstrap(body: String): List<String> = buildList {
    val array = bootstrapCamerasArray(body) ?: return emptyList()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        item.optString("id").takeIf { it.isNotBlank() }?.let { add(it) }
    }
}

/** Builds a talkback session from one bootstrap camera object. */
internal fun parseTalkbackFromCamera(item: JSONObject, cameraId: String = item.optString("id")): TalkbackSession? {
    val settings = item.optJSONObject("talkbackSettings") ?: return null
    val bindPort = settings.optInt("bindPort", 0).takeIf { it > 0 }
        ?: settings.optInt("bind_port", 0).takeIf { it > 0 }
        ?: DEFAULT_TALKBACK_PORT
    val bindAddr = settings.optString("bindAddr").ifBlank { settings.optString("bind_addr") }
    val cameraHost = item.optString("host")
        .ifBlank { item.optString("ip") }
        .ifBlank { item.optString("connectionHost") }
    val url = settings.optString("url")
        .ifBlank { settings.optString("rtpUrl") }
        .ifBlank { settings.optString("address") }
    if (url.isBlank() && cameraHost.isBlank() && bindAddr.isBlank()) return null
    val rate = settings.optInt("samplingRate", 0).takeIf { it > 0 }
        ?: settings.optInt("sampling_rate", 0)
    val bits = settings.optInt("bitsPerSample", 0).takeIf { it > 0 }
        ?: settings.optInt("bits_per_sample", 0)
    val channels = settings.optInt("channels", 0).takeIf { it > 0 }
        ?: settings.optInt("channel_count", 0)
    return TalkbackSession(
        url = url,
        codec = settings.optString("codec")
            .ifBlank { settings.optString("typeFmt") }
            .ifBlank { "aac" },
        samplingRate = if (rate > 0) rate else 22_050,
        bitsPerSample = if (bits > 0) bits else 16,
        channels = if (channels > 0) channels else 1,
        bindAddr = bindAddr,
        bindPort = bindPort,
        quality = settings.optInt("quality", 0),
        cameraId = cameraId.ifBlank { item.optString("id").ifBlank { item.optString("mac") } },
        cameraHost = cameraHost,
    )
}

private const val DEFAULT_TALKBACK_PORT = 7004

internal fun talkbackFromBootstrapBody(body: String, cameraId: String): TalkbackSession {
    val camera = findCameraJson(body, cameraId)
        ?: run {
            val known = cameraIdsInBootstrap(body)
            val hint = if (known.isEmpty()) {
                "The console returned no cameras in bootstrap."
            } else {
                "Known camera ids: ${known.take(3).joinToString()}" +
                    if (known.size > 3) " (+${known.size - 3} more)" else ""
            }
            error("Camera $cameraId was not found in bootstrap. $hint")
        }
    return parseTalkbackFromCamera(camera, cameraId)
        ?: error("Camera has no speaker address in bootstrap talkback settings.")
}

private fun parseTalkback(body: String, cameraId: String = ""): TalkbackSession {
    val root = JSONObject(body)
    val json = root.optJSONObject("data")
        ?: root.optJSONObject("talkbackSession")
        ?: root
    val settings = json.optJSONObject("talkbackSettings") ?: json
    val url = listOf(
        json.optString("websocketUrl"),
        json.optString("webSocketUrl"),
        json.optString("wsUrl"),
        json.optString("sessionUrl"),
        json.optString("url"),
        json.optString("rtpUrl"),
        json.optString("address"),
    ).firstOrNull { it.isNotBlank() }.orEmpty()
    val bindAddr = settings.optString("bindAddr").ifBlank { settings.optString("bind_addr") }
    val bindPort = settings.optInt("bindPort", 0).takeIf { it > 0 }
        ?: settings.optInt("bind_port", 0)
    if (url.isBlank() && (bindAddr.isBlank() || bindPort <= 0)) {
        error("Talkback response did not include a speaker address.")
    }
    val rate = settings.optInt("samplingRate", 0).takeIf { it > 0 }
        ?: settings.optInt("sampling_rate", 0)
    val bits = settings.optInt("bitsPerSample", 0).takeIf { it > 0 }
        ?: settings.optInt("bits_per_sample", 0)
    val channels = settings.optInt("channels", 0).takeIf { it > 0 }
        ?: settings.optInt("channel_count", 0)
    return TalkbackSession(
        url = url,
        codec = settings.optString("codec").ifBlank { json.optString("codec") }.ifBlank { "aac" },
        samplingRate = if (rate > 0) rate else 22_050,
        bitsPerSample = if (bits > 0) bits else 16,
        channels = if (channels > 0) channels else 1,
        bindAddr = bindAddr,
        bindPort = bindPort,
        quality = settings.optInt("quality", 0),
        cameraId = cameraId,
    )
}

internal fun pickStream(body: String, quality: StreamQuality, consoleHost: String? = null): String? {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
    val obj = when (root) {
        is JSONObject -> root.optJSONObject("rtspsStreams")
            ?: root.optJSONObject("data")?.optJSONObject("rtspsStreams")
            ?: root.optJSONObject("data")
            ?: root
        else -> null
    }
    // Never fall back to a higher tier: medium/low must not silently return HEVC high.
    val order = when (quality) {
        StreamQuality.High -> listOf("high", "medium", "low")
        StreamQuality.Medium -> listOf("medium", "low")
        StreamQuality.Low -> listOf("low")
    }
    if (obj != null) {
        for (key in order) {
            val direct = obj.optString(key)
            if (direct.startsWith("rtsp")) return playableRtsp(direct, consoleHost)
            val nested = obj.optJSONObject(key)
            val nestedUrl = nested?.optString("url").orEmpty().ifBlank { nested?.optString("rtspUrl").orEmpty() }
            if (nestedUrl.startsWith("rtsp")) return playableRtsp(nestedUrl, consoleHost)
        }
    }
    if (quality != StreamQuality.High) return null
    return findUrl(root)?.let { playableRtsp(it, consoleHost) }
}

internal fun stripSrtp(url: String): String {
    var result = url.replace(Regex("[?&]enableSrtp(=[^&]*)?", RegexOption.IGNORE_CASE), "")
    result = result.replace("?&", "?")
    return result.trimEnd('?', '&')
}

/** Same console host and token, in the RTSP form ExoPlayer can open. */
internal fun playableRtsp(url: String, consoleHost: String? = null): String {
    val stripped = stripSrtp(url)
    var tcp = Regex("^rtsps://", RegexOption.IGNORE_CASE).replace(stripped, "rtsp://")
    tcp = tcp.replace(Regex(":7441(?=/|\\?|#|$)"), ":7447")
    val host = consoleHost?.trim()?.substringBefore(':')?.takeIf { it.isNotBlank() }
    if (host != null) {
        tcp = tcp.replace(Regex("^rtsp://[^/:]+", RegexOption.IGNORE_CASE), "rtsp://$host")
    }
    return tcp
}

private fun findUrl(node: Any?): String? {
    when (node) {
        is String -> if (node.startsWith("rtsp")) return node
        is JSONObject -> {
            val keys = node.keys()
            while (keys.hasNext()) {
                val value = node.opt(keys.next())
                val found = findUrl(value)
                if (found != null) return found
            }
        }
        is JSONArray -> {
            for (i in 0 until node.length()) {
                val found = findUrl(node.opt(i))
                if (found != null) return found
            }
        }
    }
    return null
}

private fun eventArray(body: String): JSONArray? {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
    if (root is JSONArray) return root
    if (root !is JSONObject) return null
    root.optJSONArray("data")?.let { return it }
    root.optJSONArray("events")?.let { return it }
    root.optJSONObject("data")?.optJSONArray("events")?.let { return it }
    return null
}

private fun jsonArray(body: String, vararg keys: String): JSONArray? {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
    if (root is JSONArray) return root
    if (root is JSONObject && keys.isNotEmpty()) {
        var node: Any? = root
        for (key in keys) {
            node = when (node) {
                is JSONObject -> node.opt(key)
                else -> return null
            }
        }
        return node as? JSONArray
    }
    if (root is JSONObject) {
        keys.forEach { key -> root.optJSONArray(key)?.let { return it } }
    }
    return null
}

private fun stringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) add(value)
        }
    }
}

internal fun protectType(raw: String): ProtectEventType {
    return when (raw.lowercase()) {
        "ring" -> ProtectEventType.Ring
        "motion" -> ProtectEventType.Motion
        "smartdetect", "smart_detect", "smartdetectzone" -> ProtectEventType.SmartDetect
        "sensoropened", "sensorclosed", "sensoralarm" -> ProtectEventType.Sensor
        "disconnect" -> ProtectEventType.Disconnect
        else -> ProtectEventType.Other
    }
}

internal fun eventSummary(type: ProtectEventType, labels: List<String>, alarming: Boolean = false): String {
    return when (type) {
        ProtectEventType.Ring -> "Doorbell"
        ProtectEventType.Motion -> "Motion"
        ProtectEventType.SmartDetect -> labels.joinToString().ifBlank { "Smart detection" }
        ProtectEventType.Sensor -> if (alarming) "Sensor alarm" else "Sensor"
        ProtectEventType.Disconnect -> "Camera disconnected"
        ProtectEventType.Other -> "Protect event"
    }
}

internal fun parseLights(body: String): List<ProtectLight> {
    val array = jsonArray(body, "data", "lights") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("mac") }
            if (id.isBlank()) continue
            val mode = item.optString("lightMode").lowercase()
            val on = item.optBoolean("isLightOn") || mode == "on" || mode == "always" || mode == "alwayson"
            add(
                ProtectLight(
                    id = id,
                    name = item.optString("name").ifBlank { "Light" },
                    on = on,
                    mode = when (mode) {
                        "on", "always", "alwayson" -> "On"
                        "motion" -> "Motion"
                        "off" -> "Off"
                        else -> if (on) "On" else "Off"
                    },
                ),
            )
        }
    }
}

internal fun parseSensors(body: String): List<ProtectSensor> {
    val array = jsonArray(body, "data", "sensors") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("mac") }
            if (id.isBlank()) continue
            val leak = item.optBoolean("isLeakDetected") || item.optBoolean("leakDetected")
            val alarm = item.optBoolean("isAlarmTriggered") || item.optBoolean("alarmTriggered") || leak
            val status = when {
                alarm && leak -> "Leak"
                alarm -> "Alarm"
                item.has("isOpened") -> if (item.optBoolean("isOpened")) "Open" else "Closed"
                else -> item.optString("state").ifBlank { "Unknown" }
            }
            add(
                ProtectSensor(
                    id = id,
                    name = item.optString("name").ifBlank { "Sensor" },
                    status = status,
                    alarming = alarm,
                ),
            )
        }
    }
}

private fun eventsPath(limit: Int, query: ProtectClipQuery): String {
    val params = mutableListOf("limit=$limit")
    val types = when (query.kind) {
        "Motion" -> "motion"
        "Ring" -> "ring"
        "Person", "Vehicle", "Package", "Animal" -> "smartDetect"
        else -> ""
    }
    if (types.isNotBlank()) params += "types=$types"
    query.cameraId?.takeIf { it.isNotBlank() }?.let { params += "cameraIds=$it" }
    query.startEpochMs?.let { params += "start=$it" }
    query.endEpochMs?.let { params += "end=$it" }
    return "/events?" + params.joinToString("&")
}

private fun ptzBody(move: PtzMove): String {
    val body = JSONObject()
    when (move) {
        PtzMove.Left -> body.put("pan", -0.4)
        PtzMove.Right -> body.put("pan", 0.4)
        PtzMove.Up -> body.put("tilt", 0.4)
        PtzMove.Down -> body.put("tilt", -0.4)
        PtzMove.ZoomIn -> body.put("zoom", 0.7)
        PtzMove.ZoomOut -> body.put("zoom", 0.25)
    }
    return body.toString()
}

internal fun clipsFromEvents(events: List<ProtectEvent>, cameraId: String): List<FootageClip> {
    return events.filter { it.cameraId == null || it.cameraId == cameraId }.map { event ->
        FootageClip(
            id = event.id,
            cameraId = event.cameraId ?: cameraId,
            label = event.summary,
            startEpochMs = event.startEpochMs,
            endEpochMs = event.endEpochMs,
            kind = event.type.name,
        )
    }
}
