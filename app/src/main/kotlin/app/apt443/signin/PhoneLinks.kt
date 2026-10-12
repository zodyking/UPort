package app.apt443.signin

import android.content.Context
import android.provider.Settings

/** Short LAN paths for phone QR codes: http://<tv-ip>:<port>/unifi/<tv-name> */
object PhoneLinks {
    const val PREFIX = "/unifi"

    fun slug(name: String): String {
        val cleaned = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
        return cleaned.ifBlank { "tv" }
    }

    fun path(slug: String): String = "$PREFIX/$slug"

    fun url(hostPort: String, slug: String): String = "http://$hostPort${path(slug)}"

    fun matches(path: String, slug: String): Boolean {
        val base = path(slug)
        return path == base || path.startsWith("$base/")
    }
}

fun Context.tvLinkSlug(fallback: String = ""): String {
    val named = listOf(
        Settings.Global.getString(contentResolver, "device_name"),
        Settings.System.getString(contentResolver, "device_name"),
    ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    return PhoneLinks.slug(named.ifBlank { fallback })
}
