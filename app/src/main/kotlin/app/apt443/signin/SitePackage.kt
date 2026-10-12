package app.apt443.signin

import app.apt443.domain.ReachPreference
import app.apt443.domain.SiteVpnKind
import org.json.JSONObject

/**
 * Console credentials shipped in a `.unvr` file (plain JSON — treat like a secret).
 * Includes the local UniFi OS admin for recording export and other session features.
 * The single [apiKey] is used for Protect integration and Access developer API.
 * Identity / resident users are never included.
 *
 * An optional Tailscale auth key is part of this file and is moved into the Android
 * keystore on import. [vpnSpecified] is false when an older file never mentioned a VPN,
 * so import does not erase a key already saved on the TV.
 */
data class SitePackage(
    val siteName: String,
    val consoleHost: String,
    val apiKey: String,
    val osUsername: String,
    val osPassword: String,
    val networkApiKey: String = "",
    val trustConsoleCertificate: Boolean = false,
    val vpn: SiteVpnKind = SiteVpnKind.None,
    val vpnSpecified: Boolean = false,
    val tailscaleAuthKey: String = "",
    val tailscaleLoginServer: String = "",
    val tvReach: ReachPreference? = null,
) {
    /** Safe for signed-in UI. Never includes the auth key. */
    fun vpnLabel(): String = when (vpn) {
        SiteVpnKind.None -> "None"
        SiteVpnKind.Tailscale -> "Tailscale"
        SiteVpnKind.WireGuard -> "Tailscale"
    }

    fun toJsonBytes(): ByteArray = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("siteName", siteName)
        .put("consoleHost", consoleHost)
        .put("apiKey", apiKey)
        .put("osUsername", osUsername)
        .put("osPassword", osPassword)
        .put("networkApiKey", networkApiKey)
        .put("trustConsoleCertificate", trustConsoleCertificate)
        .put("vpn", vpn.wireValue())
        .apply {
            if (tailscaleAuthKey.isNotBlank()) put("tailscaleAuthKey", tailscaleAuthKey)
            if (tailscaleLoginServer.isNotBlank()) put("tailscaleLoginServer", tailscaleLoginServer)
            tvReach?.let { put("tvReach", it.wireValue()) }
        }
        .toString(2)
        .toByteArray(Charsets.UTF_8)

    companion object {
        const val FORMAT = "apt443-site"
        const val ALT_FORMAT = "uport-site"
        const val VERSION = 1
        const val EXTENSION = "unvr"

        fun fileNameFor(siteName: String): String {
            val slug = siteName.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(48)
            return if (slug.isBlank()) "apt443-site.$EXTENSION" else "$slug.$EXTENSION"
        }

        fun fromSetupFields(fields: Map<String, String>): Result<SitePackage> {
            val trust = fields["trustConsoleCertificate"].orEmpty()
            val json = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("siteName", fields["siteName"].orEmpty().trim().ifBlank { "UPort" })
                .put("consoleHost", fields["consoleHost"].orEmpty().trim())
                .put("apiKey", fields["apiKey"].orEmpty().trim())
                .put("osUsername", fields["osUsername"].orEmpty().trim())
                .put("osPassword", fields["osPassword"].orEmpty())
                .put("networkApiKey", fields["networkApiKey"].orEmpty().trim())
                .put(
                    "trustConsoleCertificate",
                    trust.equals("on", true) || trust.equals("true", true) || trust == "1",
                )
                .put("vpn", fields["vpn"].orEmpty().trim().ifBlank { "none" })
                .put("tailscaleAuthKey", fields["tailscaleAuthKey"].orEmpty())
                .put("tailscaleLoginServer", fields["tailscaleLoginServer"].orEmpty().trim())
            return parse(json.toString().toByteArray(Charsets.UTF_8))
        }

        fun parseTvReach(raw: String?): Result<ReachPreference?> = runCatching {
            when (raw?.trim()?.lowercase()) {
                null, "" -> null
                "auto" -> ReachPreference.Auto
                "same-network", "lan", "lanonly", "on-network" -> ReachPreference.LanOnly
                "remote", "remoteonly" -> ReachPreference.RemoteOnly
                else -> error("Choose same network or remote for this TV.")
            }
        }

        fun parse(bytes: ByteArray): Result<SitePackage> = runCatching {
            val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF").trim()
            if (text.isBlank()) error("The file is empty.")
            if (!text.startsWith("{")) {
                error("This is not a UPort site file. Choose the .unvr file from this TV.")
            }
            val root = JSONObject(text)
            val format = root.optString("format", FORMAT)
            val knownFormat = format == FORMAT || format == ALT_FORMAT
            if (!knownFormat && !root.has("consoleHost")) {
                error("This is not a UPort site file.")
            }
            val host = root.optString("consoleHost").trim()
            val key = root.optString("apiKey").trim()
                .ifBlank { root.optString("protectApiKey").trim() }
                .ifBlank { root.optString("accessToken").trim() }
            val osUser = root.optString("osUsername").trim()
            val osPass = root.optString("osPassword").trim()
            if (host.isBlank()) error("Console address is missing.")
            if (key.isBlank()) error("API key is missing.")
            if (osUser.isBlank()) error("Local console admin username is missing.")
            if (osPass.isBlank()) error("Local console admin password is missing.")
            val vpnFields = readVpn(root)
            val reachRaw = if (root.has("tvReach")) root.optString("tvReach") else ""
            val reach = parseTvReach(reachRaw).getOrElse { throw it }
            SitePackage(
                siteName = root.optString("siteName", "UPort").trim().ifBlank { "UPort" },
                consoleHost = host,
                apiKey = key,
                osUsername = osUser,
                osPassword = osPass,
                networkApiKey = root.optString("networkApiKey", "").trim(),
                trustConsoleCertificate = root.optBoolean("trustConsoleCertificate", false),
                vpn = vpnFields.kind,
                vpnSpecified = vpnFields.specified,
                tailscaleAuthKey = vpnFields.authKey,
                tailscaleLoginServer = vpnFields.loginServer,
                tvReach = reach,
            )
        }

        private fun readVpn(root: JSONObject): VpnFields {
            val specified = root.has("vpn") || root.has("tailscaleAuthKey")
            val raw = root.optString("vpn").trim().lowercase()
            if (raw == "wireguard" || raw == "teleport" || root.has("wireguardConfig") || root.has("teleportConfig")) {
                error("This app uses Tailscale only.")
            }
            val authKey = root.optString("tailscaleAuthKey").trim()
            val loginServer = root.optString("tailscaleLoginServer").trim()
            val kind = when (raw) {
                "", "none" -> if (raw != "none" && authKey.isNotBlank()) SiteVpnKind.Tailscale else SiteVpnKind.None
                "tailscale" -> SiteVpnKind.Tailscale
                else -> error("VPN must be none or tailscale.")
            }
            if (!specified || kind == SiteVpnKind.None) {
                return VpnFields(SiteVpnKind.None, specified && raw == "none", "", "")
            }
            if (authKey.isBlank()) error("Tailscale auth key is missing.")
            if (!tailscaleKeyLooksValid(authKey)) error("Tailscale auth key could not be read.")
            if (loginServer.isNotBlank() && !allowedLoginServer(loginServer)) {
                error("Tailscale login server must be an http or https address.")
            }
            return VpnFields(kind, true, authKey, loginServer)
        }

        fun tailscaleKeyLooksValid(authKey: String): Boolean {
            val key = authKey.trim()
            return key.isNotBlank() && key.length <= 2_000 && key.none { it.isWhitespace() }
        }

        private fun allowedLoginServer(value: String): Boolean {
            if (value.any { it.isWhitespace() }) return false
            val scheme = when {
                value.startsWith("https://") -> "https://"
                value.startsWith("http://") -> "http://"
                else -> return false
            }
            return value.removePrefix(scheme).trim('/').isNotBlank()
        }

    }
}

private data class VpnFields(
    val kind: SiteVpnKind,
    val specified: Boolean,
    val authKey: String,
    val loginServer: String,
)

private fun SiteVpnKind.wireValue(): String = when (this) {
    SiteVpnKind.None -> "none"
    SiteVpnKind.Tailscale -> "tailscale"
    SiteVpnKind.WireGuard -> "wireguard"
}

private fun ReachPreference.wireValue(): String = when (this) {
    ReachPreference.Auto -> "auto"
    ReachPreference.LanOnly -> "same-network"
    ReachPreference.RemoteOnly -> "remote"
}
