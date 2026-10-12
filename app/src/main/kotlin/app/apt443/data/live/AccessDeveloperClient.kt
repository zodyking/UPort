package app.apt443.data.live

import app.apt443.domain.AccessDevice
import app.apt443.domain.AccessEvent
import app.apt443.domain.AccessEventType
import app.apt443.domain.AppSettings
import app.apt443.domain.ConsoleSignInMode
import app.apt443.domain.Door
import app.apt443.domain.ResidentAccess
import app.apt443.data.settings.SecretKeys
import app.apt443.data.settings.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Official UniFi Access developer API on a UniFi OS console.
 *
 * Base: https://<console>/proxy/access/api/v1/developer
 * Auth: Authorization: Bearer <token> created in Access > Settings > Security.
 * Unlock: PUT /doors/{id}/unlock  (Access API reference section 7.9, permission edit:space).
 *
 * The cloud connector docs checked for this build cover Protect and Network,
 * not Access. A local console admin session uses the Access proxy, the same
 * way Network uses the controller session. The developer token is the fallback.
 */
class AccessDeveloperClient(
    private val http: UnifiHttp,
    private val secrets: SecretStore,
    private val legacy: ProtectLegacySession,
) {
    /**
     * Doors, groups, policies, and cameras for one Identity user.
     * Access API sections 3.5, 3.12, 3.18, 7.3, and 10.4.
     */
    suspend fun grantsFor(settings: AppSettings, email: String, userId: String): ResidentAccess {
        if (email.isBlank() && userId.isBlank()) return ResidentAccess()
        val users = loadUsers(settings).getOrElse {
            return ResidentAccess(detail = it.message ?: "Access permissions are not available.")
        }
        val user = findAccessUser(users, email, userId)
            ?: return ResidentAccess(detail = "No Access profile matches this person.")
        val matchedId = user.optString("id").ifBlank { userId }
        val policies = accessPolicies(user)
        val doorNames = doorNamesFor(settings, policies)
        val groups = groupsFor(settings, matchedId)
        val cameras = cameraNamesFor(settings, matchedId)
        return ResidentAccess(
            groups = groups,
            policies = policies.map { it.name }.distinct(),
            doors = doorNames,
            cameras = cameras,
        )
    }

    private suspend fun loadUsers(settings: AppSettings): Result<List<JSONObject>> {
        val collected = mutableListOf<JSONObject>()
        for (page in 1..8) {
            val body = getJson(
                settings,
                "/users?expand%5B%5D=access_policy&page_size=100&page_num=$page",
            ).getOrElse { return Result.failure(it) }
            val pageUsers = accessUserObjects(body)
            if (pageUsers.isEmpty()) break
            collected += pageUsers
            if (pageUsers.size < 100) break
        }
        return Result.success(collected)
    }

    private suspend fun doorNamesFor(settings: AppSettings, policies: List<AccessPolicySlice>): List<String> {
        val doors = doors(settings).getOrElse { emptyList() }
        val byId = doors.associate { it.id to it.name }
        val names = linkedSetOf<String>()
        for (policy in policies) {
            for (resource in policy.resources) {
                when (resource.type) {
                    "door" -> names += byId[resource.id] ?: resource.name.ifBlank { "Door" }
                    "door_group" -> names += doorsInGroup(settings, resource.id, doors)
                    else -> if (resource.name.isNotBlank()) names += resource.name
                }
            }
        }
        return names.filter { it.isNotBlank() }
    }

    private suspend fun doorsInGroup(settings: AppSettings, groupId: String, doors: List<Door>): List<String> {
        val body = getJson(settings, "/door_groups/$groupId").getOrNull() ?: return emptyList()
        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return emptyList()
        val type = data.optString("type")
        val name = data.optString("name")
        if (type.equals("building", true) || name.equals("All Doors", true)) {
            return doors.map { it.name }.ifEmpty { listOf(name.ifBlank { "All doors" }) }
        }
        val resources = data.optJSONArray("resources") ?: return listOf(name).filter { it.isNotBlank() }
        return buildList {
            for (i in 0 until resources.length()) {
                val item = resources.optJSONObject(i) ?: continue
                val doorName = item.optString("name").ifBlank {
                    doors.firstOrNull { it.id == item.optString("id") }?.name.orEmpty()
                }
                if (doorName.isNotBlank()) add(doorName)
            }
            if (isEmpty() && name.isNotBlank()) add(name)
        }
    }

    private suspend fun groupsFor(settings: AppSettings, userId: String): List<String> {
        if (userId.isBlank()) return emptyList()
        val body = getJson(settings, "/user_groups").getOrNull() ?: return emptyList()
        val groups = accessArray(body) ?: return emptyList()
        val names = mutableListOf<String>()
        val limit = minOf(groups.length(), 12)
        for (i in 0 until limit) {
            val group = groups.optJSONObject(i) ?: continue
            val id = group.optString("id")
            if (id.isBlank()) continue
            val members = getJson(settings, "/user_groups/$id/users").getOrNull() ?: continue
            if (accessUserObjects(members).any { it.optString("id") == userId }) {
                names += group.optString("name").ifBlank { group.optString("full_name") }.ifBlank { "Group" }
            }
        }
        return names
    }

    private suspend fun cameraNamesFor(settings: AppSettings, userId: String): List<String> {
        if (userId.isBlank()) return emptyList()
        val body = getJson(settings, "/users/$userId/identity/assignments").getOrNull() ?: return emptyList()
        return cameraNamesFromAssignments(body)
    }

    suspend fun doors(settings: AppSettings): Result<List<Door>> {
        val session = sessionJson(
            settings,
            listOf(
                "/proxy/access/api/v2/dashboard/locations",
                "/proxy/access/api/v2/door",
                "/proxy/access/api/v2/doors",
            ),
        )
        if (session.isSuccess) {
            val parsed = parseDoors(session.getOrThrow())
            if (parsed.isNotEmpty()) return Result.success(parsed)
        }
        val developer = getJson(settings, "/doors").mapCatching { parseDoors(it) }
        if (developer.isSuccess && (developer.getOrThrow().isNotEmpty() || session.isFailure)) return developer
        return if (session.isSuccess) Result.success(emptyList()) else session.map { emptyList() }
    }

    suspend fun devices(settings: AppSettings): Result<List<AccessDevice>> {
        val session = sessionJson(
            settings,
            listOf(
                "/proxy/access/api/v2/device",
                "/proxy/access/api/v2/devices",
            ),
        )
        if (session.isSuccess) {
            val parsed = parseAccessDevices(session.getOrThrow())
            if (parsed.isNotEmpty()) return Result.success(parsed)
        }
        return if (session.isSuccess) Result.success(emptyList()) else session.map { emptyList() }
    }

    suspend fun logs(settings: AppSettings): Result<List<AccessEvent>> {
        val session = sessionJson(
            settings,
            listOf(
                "/proxy/access/api/v2/system_log?topic=door_openings&page_size=40",
                "/proxy/access/api/v2/system_logs?topic=access&page_size=40",
            ),
        )
        if (session.isSuccess) {
            val parsed = parseLogs(session.getOrThrow())
            if (parsed.isNotEmpty()) return Result.success(parsed)
        }
        val developer = getJson(settings, "/system_logs?topic=access").mapCatching { parseLogs(it) }
        if (developer.isSuccess && (developer.getOrThrow().isNotEmpty() || session.isFailure)) return developer
        return if (session.isSuccess) Result.success(emptyList()) else session.map { emptyList() }
    }

    suspend fun unlock(settings: AppSettings, doorId: String): Result<String> {
        if (canUseSession(settings)) {
            val host = consoleHost(settings)
            val attempts = listOf(
                "https://$host/proxy/access/api/v2/dashboard/locations/$doorId/unlock" to """{"duration":5}""",
                "https://$host/proxy/access/api/v2/door/$doorId/unlock" to "{}",
            )
            var last: Throwable? = null
            for ((url, body) in attempts) {
                val sent = legacy.authorizedWrite(settings, url, "PUT", body)
                if (sent.isSuccess) return Result.success("Unlock sent.")
                last = sent.exceptionOrNull()
            }
            if (secrets.get(SecretKeys.ACCESS_TOKEN).isNullOrBlank()) {
                return Result.failure(last ?: IllegalStateException("Unlock did not send."))
            }
        }
        val token = secrets.get(SecretKeys.ACCESS_TOKEN)
            ?: return Result.failure(IllegalStateException("Access needs the local console admin from the site file."))
        val host = consoleHost(settings)
        if (host.isBlank()) return Result.failure(IllegalStateException("Console host is required."))
        val url = "https://$host/proxy/access/api/v1/developer/doors/$doorId/unlock"
        val headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $token",
        )
        return http.text(settings, url, "PUT", headers, "{}").mapCatching { response ->
            if (response.code !in 200..299) error("Unlock HTTP ${response.code}")
            val root = runCatching { JSONObject(response.body) }.getOrNull()
            val code = root?.optString("code").orEmpty()
            if (code.isNotBlank() && !code.equals("SUCCESS", true)) {
                error(root?.optString("msg").ifNullOrBlank { "Unlock was rejected ($code)." })
            }
            root?.optString("msg").ifNullOrBlank { "Unlock sent." }
        }
    }

    private fun canUseSession(settings: AppSettings): Boolean {
        return settings.signInMode == ConsoleSignInMode.LocalUserAndApiKey &&
            settings.osUsername.isNotBlank() &&
            consoleHost(settings).isNotBlank()
    }

    private suspend fun sessionJson(settings: AppSettings, paths: List<String>): Result<String> {
        if (!canUseSession(settings)) {
            return Result.failure(IllegalStateException("Access needs the local console admin from the site file."))
        }
        val host = consoleHost(settings)
        var last: Throwable = IllegalStateException("Access did not answer.")
        for (path in paths) {
            val result = legacy.authorizedText(settings, "https://$host$path")
            if (result.isSuccess) return result
            last = result.exceptionOrNull() ?: last
        }
        return Result.failure(last)
    }

    private suspend fun getJson(settings: AppSettings, path: String): Result<String> {
        val token = secrets.get(SecretKeys.ACCESS_TOKEN)
            ?: return Result.failure(IllegalStateException("Access API token is not set."))
        val host = consoleHost(settings)
        if (host.isBlank()) return Result.failure(IllegalStateException("Console host is required."))
        val url = "https://$host/proxy/access/api/v1/developer$path"
        val headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $token",
        )
        return http.text(settings, url, "GET", headers).mapCatching { response ->
            if (response.code !in 200..299) error("Access HTTP ${response.code}")
            response.body
        }
    }
}

internal fun parseDoors(body: String): List<Door> {
    val array = accessArray(body) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val relay = item.optString("door_lock_relay_status").ifBlank { item.optString("lock_relay_status") }
            add(
                Door(
                    id = id,
                    name = item.optString("name").ifBlank { item.optString("full_name") }.ifBlank { "Door" },
                    locked = when (relay.lowercase()) {
                        "lock", "locked" -> true
                        "unlock", "unlocked" -> false
                        else -> null
                    },
                    position = item.optString("door_position_status"),
                ),
            )
        }
    }
}

private fun parseLogs(body: String): List<AccessEvent> {
    val array = accessArray(body) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val eventName = item.optString("event").ifBlank { item.optString("topic") }
            val data = item.optJSONObject("data")
            val actor = data?.optJSONObject("actor")?.optString("name").orEmpty()
            val door = data?.optJSONObject("location")?.optString("name")
                ?: item.optString("door_name")
            val result = data?.optJSONObject("object")?.optString("result").orEmpty()
            val id = item.optString("event_object_id").ifBlank { item.optString("id") }.ifBlank { "$i-$eventName" }
            add(
                AccessEvent(
                    id = id,
                    doorName = door.ifBlank { "Door" },
                    actor = actor.ifBlank { "Unknown" },
                    type = accessType(eventName),
                    result = result.ifBlank { eventName.ifBlank { "Event" } },
                    epochMs = item.optLong("timestamp", item.optLong("time", 0L)).let { raw ->
                        if (raw in 1..10_000_000_000L) raw * 1000 else raw
                    },
                ),
            )
        }
    }
}

internal data class AccessResourceSlice(val id: String, val type: String, val name: String)

internal data class AccessPolicySlice(val name: String, val resources: List<AccessResourceSlice>)

internal fun accessUserObjects(body: String): List<JSONObject> {
    val array = accessArray(body) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { add(it) }
        }
    }
}

internal fun findAccessUser(users: List<JSONObject>, email: String, userId: String): JSONObject? {
    val wantedId = userId.trim()
    val wantedEmail = email.trim()
    if (wantedId.isNotBlank()) {
        users.firstOrNull { it.optString("id").equals(wantedId, true) }?.let { return it }
    }
    if (wantedEmail.isBlank()) return null
    return users.firstOrNull { user ->
        val candidates = listOf(
            user.optString("email"),
            user.optString("user_email"),
            user.optJSONObject("touch_pass")?.optString("user_email").orEmpty(),
        )
        candidates.any { it.equals(wantedEmail, true) && it.isNotBlank() }
    }
}

internal fun accessPolicies(user: JSONObject): List<AccessPolicySlice> {
    val array = user.optJSONArray("access_policies") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val policy = array.optJSONObject(i) ?: continue
            val resources = policy.optJSONArray("resources")
            val slices = buildList {
                if (resources == null) return@buildList
                for (r in 0 until resources.length()) {
                    val item = resources.optJSONObject(r) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        AccessResourceSlice(
                            id = id,
                            type = item.optString("type"),
                            name = item.optString("name"),
                        ),
                    )
                }
            }
            add(AccessPolicySlice(name = policy.optString("name").ifBlank { "Access policy" }, resources = slices))
        }
    }
}

internal fun cameraNamesFromAssignments(body: String): List<String> {
    val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return emptyList()
    val cameras = data.optJSONArray("camera") ?: return emptyList()
    return buildList {
        for (i in 0 until cameras.length()) {
            val item = cameras.optJSONObject(i) ?: continue
            if (item.optBoolean("deleted", false)) continue
            val name = item.optString("name").ifBlank { item.optString("short_name") }
            if (name.isNotBlank()) add(name)
        }
    }
}

private fun accessArray(body: String): JSONArray? {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
    return when (root) {
        is JSONArray -> root
        is JSONObject -> root.optJSONArray("data")
            ?: root.optJSONArray("hits")
            ?: root.optJSONArray("events")
            ?: root.optJSONObject("data")?.let { data ->
                data.optJSONArray("locations")
                    ?: data.optJSONArray("doors")
                    ?: data.optJSONArray("devices")
                    ?: data.optJSONArray("hits")
                    ?: data.optJSONArray("events")
            }
        else -> null
    }
}

internal fun parseAccessDevices(body: String): List<AccessDevice> {
    val array = accessArray(body) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("mac") }
            if (id.isBlank()) continue
            val type = item.optString("type").ifBlank { item.optString("device_type") }.ifBlank { item.optString("model") }
            val online = when {
                item.has("is_online") -> item.optBoolean("is_online")
                item.has("is_connected") -> item.optBoolean("is_connected")
                item.has("online") -> item.optBoolean("online")
                else -> !item.optString("state").equals("offline", true)
            }
            add(
                AccessDevice(
                    id = id,
                    name = item.optString("name").ifBlank { item.optString("alias") }.ifBlank { "Reader" },
                    kind = accessDeviceKind(type),
                    online = online,
                ),
            )
        }
    }
}

private fun accessDeviceKind(type: String): String {
    val value = type.lowercase()
    return when {
        "hub" in value || value.startsWith("uah") -> "Hub"
        "reader" in value || "lite" in value -> "Reader"
        "intercom" in value -> "Intercom"
        else -> "Device"
    }
}

private fun accessType(eventName: String): AccessEventType {
    val value = eventName.lowercase()
    return when {
        "doorbell" in value -> AccessEventType.Doorbell
        "visitor" in value -> AccessEventType.Visitor
        "unlock" in value -> AccessEventType.Unlock
        else -> AccessEventType.Other
    }
}

private fun String?.ifNullOrBlank(fallback: () -> String): String {
    return if (this.isNullOrBlank()) fallback() else this
}
