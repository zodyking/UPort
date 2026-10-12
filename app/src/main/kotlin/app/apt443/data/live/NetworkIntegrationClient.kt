package app.apt443.data.live

import app.apt443.domain.AppSettings
import app.apt443.domain.ConnectionMode
import app.apt443.domain.ConsoleSignInMode
import app.apt443.domain.NetworkClient
import app.apt443.domain.NetworkDevice
import app.apt443.domain.WanStatus
import app.apt443.data.settings.SecretKeys
import app.apt443.data.settings.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Official Network Integration API plus Site Manager for WAN metrics.
 *
 * Local base:  https://<console>/proxy/network/integration/v1
 * Remote base: https://api.ui.com/v1/connector/consoles/{id}/proxy/network/integration/v1
 * Site Manager (cloud only): https://api.ui.com/v1/hosts , /devices , /isp-metrics
 *
 * Sources: developer.ui.com Network integration and Site Manager v1 (checked Oct 2026).
 * When no Network integration key is saved, a local console admin session uses
 * the same controller routes as Home Assistant's UniFi Network integration.
 */
class NetworkIntegrationClient(
    private val http: UnifiHttp,
    private val secrets: SecretStore,
    private val legacy: ProtectLegacySession,
) {
    suspend fun devices(settings: AppSettings, mode: ConnectionMode): Result<List<NetworkDevice>> {
        if (mode != ConnectionMode.OnNetwork) {
            return Result.failure(IllegalStateException("Network details stay on the home network."))
        }
        if (canUseSession(settings)) {
            val local = sessionOrKey(settings, "stat/device", ::parseControllerDevices)
            if (local.isSuccess) return local
        }
        val key = secrets.get(SecretKeys.NETWORK_API_KEY)
        if (key.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Network needs the local console admin from the site file."))
        }
        val site = settings.networkSiteId.ifBlank { "default" }
        val url = "https://${consoleHost(settings)}/proxy/network/integration/v1/sites/$site/devices"
        return http.text(settings, url, "GET", apiKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Network devices HTTP ${response.code}")
            parseDevices(response.body)
        }
    }

    suspend fun clients(settings: AppSettings, mode: ConnectionMode): Result<List<NetworkClient>> {
        if (mode != ConnectionMode.OnNetwork) {
            return Result.failure(IllegalStateException("Network details stay on the home network."))
        }
        if (canUseSession(settings)) {
            val local = sessionOrKey(settings, "stat/sta", ::parseControllerClients)
            if (local.isSuccess) return local
        }
        val key = secrets.get(SecretKeys.NETWORK_API_KEY)
        if (key.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Network needs the local console admin from the site file."))
        }
        val site = settings.networkSiteId.ifBlank { "default" }
        val url = "https://${consoleHost(settings)}/proxy/network/integration/v1/sites/$site/clients?filter=active"
        return http.text(settings, url, "GET", apiKeyHeader(key)).mapCatching { response ->
            if (response.code !in 200..299) error("Network clients HTTP ${response.code}")
            parseClients(response.body).filter { it.online }
        }
    }

    /** Asks the gateway to run its built-in speed test. The result arrives on the next health read. */
    suspend fun startSpeedTest(settings: AppSettings): Result<String> {
        if (!canUseSession(settings)) {
            return Result.failure(IllegalStateException("A speed test needs the local console admin from the site file."))
        }
        val url = controllerUrl(settings, "cmd/devmgr")
        val body = JSONObject().put("cmd", "speedtest").toString()
        return legacy.authorizedWrite(settings, url, "POST", body).map { "Speed test started." }
    }

    suspend fun isp(settings: AppSettings): Result<WanStatus> {
        val key = secrets.get(SecretKeys.SITE_MANAGER_KEY)
        if (key.isNullOrBlank()) {
            if (!canUseSession(settings)) {
                return Result.failure(IllegalStateException("Site Manager API key is not set."))
            }
            val url = controllerUrl(settings, "stat/health")
            return legacy.authorizedText(settings, url).mapCatching { parseControllerWan(it) }
        }
        val hostFilter = settings.consoleId.trim()
        val url = if (hostFilter.isBlank()) {
            "https://api.ui.com/v1/isp-metrics"
        } else {
            "https://api.ui.com/v1/isp-metrics?hostIds[]=$hostFilter"
        }
        return http.text(
            settings,
            url,
            "GET",
            mapOf("Accept" to "application/json", "X-API-KEY" to key),
        ).mapCatching { response ->
            if (response.code !in 200..299) error("ISP metrics HTTP ${response.code}")
            parseWan(response.body)
        }
    }

    private suspend fun <T> sessionOrKey(
        settings: AppSettings,
        path: String,
        parse: (String) -> T,
    ): Result<T> {
        if (!canUseSession(settings)) {
            return Result.failure(
                IllegalStateException("Network needs a local console admin sign-in, or a Network integration key."),
            )
        }
        return legacy.authorizedText(settings, controllerUrl(settings, path)).mapCatching { parse(it) }
    }

    private fun canUseSession(settings: AppSettings): Boolean {
        return settings.signInMode == ConsoleSignInMode.LocalUserAndApiKey &&
            settings.osUsername.isNotBlank() &&
            consoleHost(settings).isNotBlank()
    }

    private fun controllerUrl(settings: AppSettings, path: String): String {
        val site = settings.networkSiteId.ifBlank { "default" }
        return "https://${consoleHost(settings)}/proxy/network/api/s/$site/$path"
    }
}

internal fun parseDevices(body: String): List<NetworkDevice> {
    val array = firstArray(body, "data", "devices") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val mac = item.optString("macAddress").ifBlank { item.optString("mac") }
            val id = item.optString("id").ifBlank { mac }
            if (id.isBlank()) continue
            val status = item.optString("status").ifBlank { item.optString("state") }
            val online = item.optBoolean(
                "isOnline",
                status.equals("online", true) || status.equals("connected", true),
            )
            val type = item.optString("deviceType").ifBlank { item.optString("type") }
            val model = item.optString("model").ifBlank { item.optString("shortname") }
            add(
                NetworkDevice(
                    id = id,
                    name = item.optString("name").ifBlank { model }.ifBlank { "Device" },
                    model = model,
                    online = online,
                    adopted = !status.equals("pending", true),
                    kind = networkDeviceKind(type, model),
                    ip = item.optString("ipAddress").ifBlank { item.optString("ip") },
                    mac = mac,
                    clientCount = item.optInt("clientCount", -1).takeIf { it >= 0 }
                        ?: item.optInt("num_sta", -1).takeIf { it >= 0 },
                ),
            )
        }
    }
}

internal fun parseClients(body: String): List<NetworkClient> {
    val array = firstArray(body, "data", "clients") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val mac = item.optString("macAddress").ifBlank { item.optString("mac") }
            val id = item.optString("id").ifBlank { mac }
            if (id.isBlank()) continue
            val access = item.optJSONObject("access")
            val wifi = access?.optJSONObject("wifi") ?: item.optJSONObject("wifi")
            val type = access?.optString("type").orEmpty().ifBlank { item.optString("type") }
            val status = item.optString("status")
            val connected = !status.equals("offline", true) && !status.equals("disconnected", true)
            add(
                NetworkClient(
                    id = id,
                    name = item.optString("name").ifBlank { mac }.ifBlank { "Client" },
                    ip = item.optString("ipAddress").ifBlank { item.optString("ip") },
                    connection = if (type.contains("wire", true) && !type.contains("wireless", true)) "Wired" else "Wi-Fi",
                    online = connected,
                    mac = mac,
                    ssid = wifi?.optString("ssid").orEmpty().ifBlank { access?.optString("ssid").orEmpty() },
                    accessPoint = item.optString("uplinkDeviceId"),
                    signalDbm = wifi?.optInt("signal", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE },
                    downMbps = rateMbps(wifi?.optDoubleOrNull("rxRate") ?: wifi?.optDoubleOrNull("rx_rate")),
                    upMbps = rateMbps(wifi?.optDoubleOrNull("txRate") ?: wifi?.optDoubleOrNull("tx_rate")),
                ),
            )
        }
    }
}

internal fun parseControllerDevices(body: String): List<NetworkDevice> {
    val array = firstArray(body, "data") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val mac = item.optString("mac")
            val id = mac.ifBlank { item.optString("_id") }
            if (id.isBlank()) continue
            val state = item.optInt("state", -1)
            val type = item.optString("type")
            val model = item.optString("model")
            add(
                NetworkDevice(
                    id = id,
                    name = item.optString("name").ifBlank { model }.ifBlank { "Device" },
                    model = model,
                    online = state == 1,
                    adopted = item.optBoolean("adopted", true),
                    kind = networkDeviceKind(type, model),
                    ip = item.optString("ip"),
                    mac = mac,
                    clientCount = item.optInt("num_sta", -1).takeIf { it >= 0 },
                ),
            )
        }
    }
}

internal fun parseControllerClients(body: String): List<NetworkClient> {
    val array = firstArray(body, "data") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val mac = item.optString("mac")
            val id = mac.ifBlank { item.optString("_id") }
            if (id.isBlank()) continue
            val wired = item.optBoolean("is_wired")
            val signal = item.optInt("signal", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?: item.optInt("rssi", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            add(
                NetworkClient(
                    id = id,
                    name = item.optString("name")
                        .ifBlank { item.optString("hostname") }
                        .ifBlank { item.optString("oui") }
                        .ifBlank { mac }
                        .ifBlank { "Client" },
                    ip = item.optString("ip").ifBlank { item.optString("last_ip") },
                    connection = if (wired) "Wired" else "Wi-Fi",
                    online = true,
                    mac = mac,
                    ssid = item.optString("essid"),
                    accessPoint = item.optString("ap_mac"),
                    signalDbm = signal,
                    downMbps = rateMbps(item.optDoubleOrNull("rx_rate")),
                    upMbps = rateMbps(item.optDoubleOrNull("tx_rate")),
                ),
            )
        }
    }
}

internal fun networkDeviceKind(type: String, model: String): String {
    val value = "${type.lowercase()} ${model.lowercase()}"
    return when {
        value.contains("uap") || value.contains("access point") -> "Access point"
        value.contains("usw") || value.contains("switch") -> "Switch"
        value.contains("ugw") || value.contains("udm") || value.contains("uxg") || value.contains("gateway") -> "Gateway"
        else -> "Device"
    }
}

/** Controller rates are kilobits per second. Values already in Mbps stay as they are. */
internal fun rateMbps(raw: Double?): Double? {
    if (raw == null || raw.isNaN() || raw <= 0.0) return null
    return if (raw > 2_000.0) raw / 1_000.0 else raw
}

/** Replace AP MAC addresses with the access point's name when the device list has it. */
internal fun nameAccessPoints(clients: List<NetworkClient>, devices: List<NetworkDevice>): List<NetworkClient> {
    if (clients.isEmpty() || devices.isEmpty()) return clients
    return clients.map { client ->
        val token = client.accessPoint.trim()
        if (token.isBlank()) return@map client
        val match = devices.firstOrNull { device ->
            device.mac.equals(token, true) || device.id.equals(token, true)
        } ?: return@map client
        client.copy(accessPoint = match.name)
    }
}

internal fun parseControllerWan(body: String): WanStatus {
    val array = firstArray(body, "data") ?: return WanStatus(null, "WAN", null, null, null)
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        if (!item.optString("subsystem").equals("wan", true)) continue
        val status = item.optString("status")
        val test = item.optString("speedtest_status").ifBlank { item.optString("speedtestStatus") }
        return WanStatus(
            up = status.equals("ok", true),
            ispName = item.optString("isp_name").ifBlank { item.optString("gw_name") }.ifBlank { "WAN" },
            downloadMbps = item.optDoubleOrNull("xput_down", "xput_download", "speedtest_download"),
            uploadMbps = item.optDoubleOrNull("xput_up", "xput_upload", "speedtest_upload"),
            latencyMs = item.optDoubleOrNull("latency", "speedtest_ping"),
            liveDownMbps = bytesPerSecToMbps(item.optDoubleOrNull("rx_bytes-r", "rx_bytes_r")),
            liveUpMbps = bytesPerSecToMbps(item.optDoubleOrNull("tx_bytes-r", "tx_bytes_r")),
            speedtest = test.lowercase(),
        )
    }
    return WanStatus(null, "WAN", null, null, null)
}

/** Gateway counters are bytes per second. */
internal fun bytesPerSecToMbps(raw: Double?): Double? {
    if (raw == null || raw.isNaN() || raw < 0.0) return null
    return raw * 8.0 / 1_000_000.0
}

private fun parseWan(body: String): WanStatus {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
    val obj = when (root) {
        is JSONObject -> {
            root.optJSONArray("data")?.optJSONObject(0)
                ?: root.optJSONArray("metrics")?.optJSONObject(0)
                ?: root
        }
        is JSONArray -> root.optJSONObject(0) ?: JSONObject()
        else -> JSONObject()
    }
    val down = obj.optDoubleOrNull("download_kbps", "downloadKbps", "wanDownloadKbps")
    val up = obj.optDoubleOrNull("upload_kbps", "uploadKbps", "wanUploadKbps")
    return WanStatus(
        up = obj.optBooleanOrNull("wanUp", "up", "internetUp"),
        ispName = obj.optString("ispName").ifBlank { obj.optString("isp").ifBlank { "WAN" } },
        downloadMbps = down?.div(1000.0),
        uploadMbps = up?.div(1000.0),
        latencyMs = obj.optDoubleOrNull("latencyMs", "latency", "avgLatency"),
    )
}

private fun firstArray(body: String, vararg keys: String): JSONArray? {
    val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
    if (root is JSONArray) return root
    if (root is JSONObject) {
        keys.forEach { key -> root.optJSONArray(key)?.let { return it } }
    }
    return null
}

private fun JSONObject.optDoubleOrNull(vararg keys: String): Double? {
    keys.forEach { key ->
        if (has(key) && !isNull(key)) {
            val value = optDouble(key, Double.NaN)
            if (!value.isNaN()) return value
        }
    }
    return null
}

private fun JSONObject.optBooleanOrNull(vararg keys: String): Boolean? {
    keys.forEach { key ->
        if (has(key) && !isNull(key)) return optBoolean(key)
    }
    return null
}
