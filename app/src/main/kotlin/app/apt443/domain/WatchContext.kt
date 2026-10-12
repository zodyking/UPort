package app.apt443.domain

enum class WatchKind {
    Hdmi,
    LiveTv,
    App,
}

/** Foreground TV, HDMI, or a known video app. Home and settings are not a watch session. */
data class WatchContext(val label: String, val kind: WatchKind)

/**
 * Whether the viewer should be offered a silence prompt for this foreground window.
 * Only the package and class names are used. Nothing on screen is read.
 */
fun watchContext(packageName: String, className: String = ""): WatchContext? {
    val pkg = packageName.trim().lowercase()
    val cls = className.trim().lowercase()
    if (pkg.isBlank()) return null
    val blob = "$pkg $cls"
    if (isOwnOrSystem(pkg)) return null
    if (isHdmi(blob)) return WatchContext("HDMI", WatchKind.Hdmi)
    if (isLiveTv(blob)) return WatchContext("Live TV", WatchKind.LiveTv)
    if (isShell(pkg, cls)) return null
    val app = KNOWN_APPS.firstOrNull { (token, _) -> token in pkg }
    return app?.let { WatchContext(it.second, WatchKind.App) }
}

/** How long a Yes lasts before "Still watching?" and how long that answer stores quiet. */
const val QUIET_HOLD_MS: Long = 30 * 60_000L

data class QuietPrompt(
    val label: String,
    val packageName: String,
    val recheck: Boolean = false,
)

/**
 * One question when a watch app comes to the front. Yes stays quiet while that
 * package stays in front, then asks again every [QUIET_HOLD_MS]. No holds until
 * they leave. Home never asks. Our own windows are ignored so the prompt does
 * not cancel itself.
 *
 * [releaseSilence] tells the caller to clear the stored quiet time. [extendSilence]
 * tells the caller to push that time forward so an unanswered re-check stays quiet.
 */
data class QuietSession(
    val prompt: QuietPrompt? = null,
    val declinedPackage: String? = null,
    val silencedPackage: String? = null,
    val silencedLabel: String = "",
    val recheckAtMs: Long = 0L,
    val releaseSilence: Boolean = false,
    val extendSilence: Boolean = false,
) {
    fun receive(
        packageName: String,
        className: String,
        ourPackage: String,
        silenced: Boolean,
        nowMs: Long = 0L,
    ): QuietSession {
        val pkg = packageName.trim()
        if (pkg.isBlank() || pkg.equals(ourPackage, ignoreCase = true) || isOwnOrSystem(pkg.lowercase())) {
            return this
        }
        // Yes or No is the only way an open question goes away. A system window,
        // the launcher, or another app must not dismiss it first. A quiet period
        // already set in Settings still replaces the first question.
        if (prompt != null) {
            if (!prompt.recheck && silenced) {
                return copy(prompt = null, releaseSilence = false, extendSilence = false)
            }
            return copy(releaseSilence = false, extendSilence = false)
        }
        val watch = watchContext(pkg, className)
        if (watch == null) {
            return QuietSession(releaseSilence = silencedPackage != null)
        }
        if (silencedPackage != null && silencedPackage != pkg) {
            return QuietSession(prompt = QuietPrompt(watch.label, pkg), releaseSilence = true)
        }
        if (silencedPackage == pkg) return whileWatching(watch, pkg, nowMs)
        if (silenced) return copy(prompt = null, releaseSilence = false, extendSilence = false)
        if (declinedPackage == pkg) return copy(prompt = null, releaseSilence = false, extendSilence = false)
        if (prompt?.packageName == pkg && !prompt.recheck) {
            return copy(releaseSilence = false, extendSilence = false)
        }
        return QuietSession(prompt = QuietPrompt(watch.label, pkg))
    }

    fun declined(): QuietSession = copy(
        prompt = null,
        declinedPackage = prompt?.packageName ?: declinedPackage,
        silencedPackage = null,
        silencedLabel = "",
        recheckAtMs = 0L,
        releaseSilence = prompt?.recheck == true,
        extendSilence = false,
    )

    fun accepted(nowMs: Long): QuietSession {
        val pkg = prompt?.packageName ?: return this
        return copy(
            prompt = null,
            declinedPackage = null,
            silencedPackage = pkg,
            silencedLabel = prompt.label,
            recheckAtMs = nowMs + QUIET_HOLD_MS,
            releaseSilence = false,
            extendSilence = false,
        )
    }

    /** Shows the re-check once [recheckAtMs] has arrived and the same app is still quiet. */
    fun due(nowMs: Long): QuietSession {
        val pkg = silencedPackage ?: return this
        if (prompt != null || silencedLabel.isBlank() || recheckAtMs <= 0L || nowMs < recheckAtMs) return this
        return copy(
            prompt = QuietPrompt(silencedLabel, pkg, recheck = true),
            releaseSilence = false,
            extendSilence = true,
        )
    }

    private fun whileWatching(watch: WatchContext, pkg: String, nowMs: Long): QuietSession {
        if (prompt?.recheck == true && prompt.packageName == pkg) {
            return copy(releaseSilence = false, extendSilence = false)
        }
        if (recheckAtMs > 0L && nowMs >= recheckAtMs) {
            return copy(
                prompt = QuietPrompt(watch.label, pkg, recheck = true),
                silencedLabel = watch.label,
                releaseSilence = false,
                extendSilence = true,
            )
        }
        return copy(prompt = null, releaseSilence = false, extendSilence = false)
    }
}

private fun isOwnOrSystem(pkg: String): Boolean =
    pkg == "app.apt443" || pkg == "android" || pkg == "com.android.systemui"

private fun isShell(pkg: String, cls: String): Boolean {
    if ("launcher" in pkg) return true
    if (pkg in SHELL_PACKAGES) return true
    if ("settings" in pkg && !isHdmi("$pkg $cls")) return true
    return false
}

private fun isHdmi(blob: String): Boolean =
    "hdmi" in blob || "inputplayer" in blob || "passthrough" in blob || "tv.input" in blob

private fun isLiveTv(blob: String): Boolean =
    "livetv" in blob || "live.tv" in blob || ".tuner" in blob || "tv.tuner" in blob

private val SHELL_PACKAGES = setOf(
    "com.amazon.venezia",
    "com.android.vending",
    "com.amazon.tv.launcher",
    "com.amazon.adam",
)

private val KNOWN_APPS = listOf(
    "netflix" to "Netflix",
    "youtube" to "YouTube",
    "disney" to "Disney+",
    "hulu" to "Hulu",
    "amazon.avod" to "Prime Video",
    "amazonvideo" to "Prime Video",
    "hbomax" to "Max",
    "hbo.hbonow" to "Max",
    "peacock" to "Peacock",
    "sling" to "Sling",
    "pluto" to "Pluto TV",
    "tubitv" to "Tubi",
    "plex" to "Plex",
    "org.xbmc.kodi" to "Kodi",
    "paramount" to "Paramount+",
    "crunchyroll" to "Crunchyroll",
    "apple.atve" to "Apple TV",
    "discoveryplus" to "Discovery+",
)
