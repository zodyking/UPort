package app.apt443.alert

import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** TV family, from the build and system features. Fire OS is not treated as every Android TV. */
enum class TvFamily {
    FireTv,
    GoogleTv,
    AndroidTv,
}

enum class TriState {
    Yes,
    No,
    Unreadable,
}

enum class DiagnosisTone {
    Ok,
    NeedsAction,
    Unknown,
}

enum class DiagnosisAction {
    OpenOverlaySettings,
    OpenAccessibilitySettings,
}

enum class IpKind {
    Wifi,
    Ethernet,
    Other,
}

data class IpCandidate(
    val address: String,
    val kind: IpKind,
    val active: Boolean,
)

data class ChosenIp(
    val address: String,
    val kind: IpKind,
    val active: Boolean,
) {
    val sourceLabel: String = when (kind) {
        IpKind.Wifi -> "Wi-Fi"
        IpKind.Ethernet -> "Ethernet"
        IpKind.Other -> "another adapter"
    }
}

data class StatusLine(
    val text: String,
    val tone: DiagnosisTone,
)

/**
 * Live facts for one diagnosis pass. Callers must read these when the screen opens,
 * not from a flag captured at process start.
 */
data class DiagnosisFacts(
    val family: TvFamily,
    val drawOverlays: TriState,
    val appOpsAllow: TriState,
    val serviceEnabled: TriState,
    val accessibilityOn: TriState,
    val adbOn: TriState,
    val ip: String?,
    val ipSource: String?,
    val packageName: String,
) {
    val readerOn: Boolean
        get() = serviceEnabled == TriState.Yes && accessibilityOn == TriState.Yes

    /** True only when a check we trust says popups can draw outside this app. */
    val canDrawOverApps: Boolean
        get() = drawOverlays == TriState.Yes || readerOn
}

data class AlertDiagnosisReport(
    val family: TvFamily,
    val familyLabel: String,
    val lines: List<StatusLine>,
    val whatToDo: List<String>,
    val commandIntro: String,
    val commands: List<String>,
    val commandNote: String,
    val systemAction: DiagnosisAction?,
    val canDrawOverApps: Boolean,
)

object AlertDiagnosis {
    const val ADB_PORT = 5555
    const val IP_PLACEHOLDER = "<tv-ip>"
    const val ALERT_READER_CLASS = "app.apt443.alert.Apt443AccessibilityService"

    /**
     * Reads every check from the device. Does not consult [Apt443AccessibilityService.running].
     * A failed read becomes [TriState.Unreadable] and never throws.
     */
    fun read(context: Context): AlertDiagnosisReport = diagnose(readFacts(context))

    internal fun readFacts(context: Context): DiagnosisFacts {
        val family = detectTvFamily(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            amazonFireTvFeature = context.hasFeature("amazon.hardware.fire_tv"),
            amazonHardwareTvFeature = context.hasFeature("com.amazon.hardware.tv"),
            googleTvFeature = context.hasFeature("com.google.android.feature.AMATI_EXPERIENCE"),
        )
        val chosen = runCatching { chooseFromDevice(context) }.getOrNull()
        return DiagnosisFacts(
            family = family,
            drawOverlays = readDrawOverlays(context),
            appOpsAllow = readAppOps(context),
            serviceEnabled = readServiceEnabled(context),
            accessibilityOn = readAccessibilityMaster(context),
            adbOn = readAdb(context),
            ip = chosen?.address,
            ipSource = chosen?.sourceLabel,
            packageName = context.packageName.ifBlank { "app.apt443" },
        )
    }
}

fun diagnose(facts: DiagnosisFacts): AlertDiagnosisReport {
    val label = familyLabel(facts.family)
    return AlertDiagnosisReport(
        family = facts.family,
        familyLabel = label,
        lines = listOf(
            overlayLine(facts),
            readerLine(facts),
            adbLine(facts),
            addressLine(facts),
        ),
        whatToDo = whatToDo(facts),
        commandIntro = commandIntro(facts),
        commands = diagnosisCommands(facts.packageName, facts.ip),
        commandNote = "The accessibility command replaces the list of enabled readers with UPort only.",
        systemAction = systemAction(facts),
        canDrawOverApps = facts.canDrawOverApps,
    )
}

fun familyLabel(family: TvFamily): String = when (family) {
    TvFamily.FireTv -> "Fire TV"
    TvFamily.GoogleTv -> "Google TV"
    TvFamily.AndroidTv -> "Android TV"
}

/**
 * Fire OS first, then Google TV, otherwise Android TV.
 * Amazon features, manufacturer, model, and fingerprint can each identify a Fire TV.
 * Google TV is the AMATI feature, a Google build, or a Chromecast / Google TV model.
 */
fun detectTvFamily(
    manufacturer: String,
    model: String,
    fingerprint: String,
    brand: String = "",
    amazonFireTvFeature: Boolean = false,
    amazonHardwareTvFeature: Boolean = false,
    googleTvFeature: Boolean = false,
): TvFamily {
    if (isFireOs(manufacturer, model, fingerprint, brand, amazonFireTvFeature, amazonHardwareTvFeature)) {
        return TvFamily.FireTv
    }
    if (isGoogleTv(manufacturer, model, fingerprint, brand, googleTvFeature)) {
        return TvFamily.GoogleTv
    }
    return TvFamily.AndroidTv
}

fun alertReaderComponent(packageName: String): String {
    val pkg = packageName.ifBlank { "app.apt443" }
    return "$pkg/$pkg.alert.Apt443AccessibilityService"
}

/** True when the secure setting lists this app's alert reader, including the short component form. */
fun alertReaderListed(enabledServices: String?, packageName: String = "app.apt443"): Boolean {
    if (enabledServices.isNullOrBlank()) return false
    val pkg = packageName.ifBlank { "app.apt443" }
    val flat = alertReaderComponent(pkg)
    val relative = "$pkg/.alert.Apt443AccessibilityService"
    return enabledServices.split(':').any { raw ->
        val entry = raw.trim()
        entry == flat || entry == relative
    }
}

fun diagnosisCommands(packageName: String, ip: String?): List<String> {
    val pkg = packageName.ifBlank { "app.apt443" }
    val host = ip?.takeIf { isUsableIpv4(it) } ?: AlertDiagnosis.IP_PLACEHOLDER
    return listOf(
        "adb connect $host:${AlertDiagnosis.ADB_PORT}",
        "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
        "adb shell settings put secure enabled_accessibility_services ${alertReaderComponent(pkg)}",
        "adb shell settings put secure accessibility_enabled 1",
    )
}

/**
 * Picks one IPv4 address for `adb connect`.
 * Skips loopback and link-local. The active network wins, then Wi-Fi, then Ethernet.
 * A private address wins over a public one only after those two rules.
 */
fun chooseTvAddress(candidates: List<IpCandidate>): ChosenIp? {
    val best = candidates
        .asSequence()
        .filter { isUsableIpv4(it.address) }
        .sortedWith(
            compareByDescending<IpCandidate> { it.active }
                .thenBy { it.kind.ordinal }
                .thenByDescending { isPrivateIpv4(it.address) },
        )
        .firstOrNull() ?: return null
    return ChosenIp(best.address, best.kind, best.active)
}

fun isUsableIpv4(address: String): Boolean {
    val match = IPV4.matchEntire(address) ?: return false
    val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
    if (parts.any { it !in 0..255 }) return false
    if (parts[0] == 0 || parts[0] == 127 || parts[0] >= 224) return false
    if (parts[0] == 169 && parts[1] == 254) return false
    return true
}

fun isPrivateIpv4(address: String): Boolean {
    if (!isUsableIpv4(address)) return false
    val parts = address.split('.').map { it.toInt() }
    return when (parts[0]) {
        10 -> true
        172 -> parts[1] in 16..31
        192 -> parts[1] == 168
        else -> false
    }
}

/** Interface name to Wi-Fi / Ethernet. Loopback, VPN, and mobile interfaces are ignored. */
fun interfaceKind(name: String): IpKind? {
    val n = name.lowercase()
    if (n == "lo" || n.startsWith("dummy") || n.startsWith("tun") || n.startsWith("rmnet") ||
        n.contains("vpn") || n.startsWith("p2p") || n.startsWith("awdl")
    ) {
        return null
    }
    return when {
        n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("swlan") -> IpKind.Wifi
        n.startsWith("eth") || n.startsWith("en") -> IpKind.Ethernet
        else -> null
    }
}

private fun overlayLine(facts: DiagnosisFacts): StatusLine {
    if (facts.drawOverlays == TriState.Yes || facts.readerOn) {
        return StatusLine("Alerts can draw over other apps.", DiagnosisTone.Ok)
    }
    val draw = facts.drawOverlays
    val ops = facts.appOpsAllow
    return when {
        draw == TriState.Yes ->
            StatusLine("Alerts can draw over other apps.", DiagnosisTone.Ok)
        draw == TriState.Unreadable && ops == TriState.Yes ->
            StatusLine(
                "Drawing over other apps looks allowed, but this app could not confirm it.",
                DiagnosisTone.Unknown,
            )
        draw == TriState.Unreadable && ops == TriState.Unreadable ->
            StatusLine(
                "This app cannot read whether it may draw over other apps.",
                DiagnosisTone.Unknown,
            )
        draw == TriState.Unreadable && ops == TriState.No ->
            StatusLine(
                "This app is not allowed to draw over other apps. The system check could not be read.",
                DiagnosisTone.NeedsAction,
            )
        draw == TriState.No && ops == TriState.Unreadable ->
            StatusLine(
                "This app is not allowed to draw over other apps. One check could not be read.",
                DiagnosisTone.NeedsAction,
            )
        draw == TriState.Unreadable ->
            StatusLine(
                "This app cannot read whether it may draw over other apps.",
                DiagnosisTone.Unknown,
            )
        else ->
            StatusLine(
                "This app is not allowed to draw over other apps.",
                DiagnosisTone.NeedsAction,
            )
    }
}

private fun readerLine(facts: DiagnosisFacts): StatusLine {
    val listed = facts.serviceEnabled
    val master = facts.accessibilityOn
    if (listed == TriState.Yes && master == TriState.Yes) {
        return StatusLine("The alert reader is on.", DiagnosisTone.Ok)
    }
    if (listed == TriState.Unreadable && master == TriState.Unreadable) {
        return StatusLine(
            "This app cannot read whether the alert reader is on.",
            DiagnosisTone.Unknown,
        )
    }
    if (listed == TriState.Unreadable) {
        return StatusLine(
            "This app cannot read whether the alert reader is turned on.",
            DiagnosisTone.Unknown,
        )
    }
    if (master == TriState.Unreadable) {
        return StatusLine(
            "This app cannot read whether accessibility is turned on.",
            DiagnosisTone.Unknown,
        )
    }
    val accessibilityOff = master == TriState.No
    if (facts.drawOverlays == TriState.Yes) {
        val text = if (accessibilityOff) {
            "The alert reader is off. Accessibility is turned off. Popups can still draw over other apps."
        } else {
            "The alert reader is off. Popups can still draw over other apps."
        }
        return StatusLine(text, DiagnosisTone.Ok)
    }
    val text = if (accessibilityOff) {
        "The alert reader is off, so popups only show inside this app. Accessibility is turned off on this TV."
    } else {
        "The alert reader is off, so popups only show inside this app."
    }
    return StatusLine(text, DiagnosisTone.NeedsAction)
}

private fun adbLine(facts: DiagnosisFacts): StatusLine {
    val needed = !facts.canDrawOverApps
    return when (facts.adbOn) {
        TriState.Yes -> StatusLine("A computer can send commands to this TV.", DiagnosisTone.Ok)
        TriState.No -> when {
            needed && facts.family == TvFamily.FireTv ->
                StatusLine(
                    "ADB debugging is off, so this TV cannot receive the command.",
                    DiagnosisTone.NeedsAction,
                )
            needed ->
                StatusLine(
                    "ADB debugging is off, so this TV cannot receive the fallback command.",
                    DiagnosisTone.NeedsAction,
                )
            else ->
                StatusLine("ADB debugging is off. It is not needed right now.", DiagnosisTone.Ok)
        }
        TriState.Unreadable -> StatusLine(
            "This app cannot read whether ADB debugging is on.",
            if (needed) DiagnosisTone.Unknown else DiagnosisTone.Ok,
        )
    }
}

private fun addressLine(facts: DiagnosisFacts): StatusLine {
    val ip = facts.ip
    if (ip != null) {
        val where = facts.ipSource?.let { " on $it" }.orEmpty()
        return StatusLine("This TV's address is $ip$where.", DiagnosisTone.Ok)
    }
    return StatusLine(
        "This TV has no network address yet.",
        if (facts.canDrawOverApps) DiagnosisTone.Unknown else DiagnosisTone.NeedsAction,
    )
}

private fun whatToDo(facts: DiagnosisFacts): List<String> {
    if (facts.canDrawOverApps) {
        return listOf("Nothing to change. Alerts can draw over other apps.")
    }
    val steps = mutableListOf<String>()
    if (facts.drawOverlays != TriState.Yes) {
        steps += when (facts.family) {
            TvFamily.FireTv ->
                "This Fire TV usually cannot grant drawing over other apps from Settings. ADB is the path that works."
            TvFamily.GoogleTv ->
                "On this Google TV, the system permission screen may work. Open Settings, Apps, Special app access, Display over other apps, and allow UPort. If that does not stick, send the ADB commands below."
            TvFamily.AndroidTv ->
                "On this Android TV, the system permission screen may work. Allow UPort to display over other apps. If that screen is missing or does not stick, send the ADB commands below."
        }
        if (facts.drawOverlays == TriState.No && facts.appOpsAllow == TriState.Yes) {
            steps += "One check still says this app cannot draw over other apps. Send the commands below, then press Check again."
        } else if (facts.drawOverlays == TriState.Unreadable && facts.appOpsAllow == TriState.Unreadable) {
            steps += "This app could not read every draw-over check. The commands below still grant it."
        } else if (facts.drawOverlays == TriState.Unreadable || facts.appOpsAllow == TriState.Unreadable) {
            steps += "This app could not read one draw-over check. The commands below still grant it."
        }
    }
    if (!facts.readerOn) {
        steps += when {
            facts.serviceEnabled == TriState.Unreadable || facts.accessibilityOn == TriState.Unreadable ->
                "This app could not read the alert reader. The commands below turn it on anyway."
            facts.accessibilityOn == TriState.No ->
                "Turn accessibility on, and turn on the UPort alert reader. The commands below do both."
            else ->
                "Turn on the UPort alert reader so popups can show outside this app. The commands below do that."
        }
        if (facts.family == TvFamily.FireTv) {
            steps += "If Fire TV does not list UPort alerts under Accessibility, the commands below turn the reader on."
        } else if (facts.serviceEnabled != TriState.Unreadable && facts.accessibilityOn != TriState.Unreadable) {
            steps += "You can also open Settings, then Accessibility, and switch on UPort alerts."
        }
    }
    when (facts.adbOn) {
        TriState.No -> steps += if (facts.family == TvFamily.FireTv) {
            "ADB debugging is off, so this TV cannot receive the command. Turn it on in Developer options, then send the commands from a computer on the same network."
        } else {
            "ADB debugging is off, so this TV cannot receive the fallback command. Turn it on in Developer options if the system screen did not fix it."
        }
        TriState.Unreadable -> steps +=
            "This app cannot read whether ADB debugging is on. If a computer cannot connect, turn on ADB debugging, then send the commands."
        TriState.Yes -> Unit
    }
    if (facts.ip == null) {
        steps += "This TV has no network address yet. Connect Wi-Fi or Ethernet and press Check again. The connect command uses <tv-ip> until an address appears."
    }
    return steps
}

private fun commandIntro(facts: DiagnosisFacts): String = when {
    facts.canDrawOverApps -> "Commands, if you need to turn this on again."
    facts.ip == null -> "Send these from a computer on the same network. This TV has no network address yet, so the first line uses <tv-ip>."
    else -> "Send these from a computer on the same network."
}

private fun systemAction(facts: DiagnosisFacts): DiagnosisAction? {
    if (facts.canDrawOverApps || facts.family == TvFamily.FireTv) return null
    if (facts.drawOverlays != TriState.Yes) return DiagnosisAction.OpenOverlaySettings
    if (!facts.readerOn) return DiagnosisAction.OpenAccessibilitySettings
    return null
}

private fun isFireOs(
    manufacturer: String,
    model: String,
    fingerprint: String,
    brand: String,
    amazonFireTvFeature: Boolean,
    amazonHardwareTvFeature: Boolean,
): Boolean {
    if (amazonFireTvFeature || amazonHardwareTvFeature) return true
    if (manufacturer.equals("amazon", ignoreCase = true) || brand.equals("amazon", ignoreCase = true)) return true
    if (model.startsWith("AFT", ignoreCase = true) || model.startsWith("KF", ignoreCase = true)) return true
    val fp = fingerprint.lowercase()
    return fp.startsWith("amazon/") || fp.contains("fireos") || fp.contains("/amazon")
}

private fun isGoogleTv(
    manufacturer: String,
    model: String,
    fingerprint: String,
    brand: String,
    googleTvFeature: Boolean,
): Boolean {
    if (googleTvFeature) return true
    if (manufacturer.equals("google", ignoreCase = true) || brand.equals("google", ignoreCase = true)) return true
    val mdl = model.lowercase()
    if (mdl.contains("chromecast") || mdl.contains("google tv")) return true
    val fp = fingerprint.lowercase()
    return fp.contains("amati") || fp.startsWith("google/")
}

private fun readDrawOverlays(context: Context): TriState = runCatching {
    if (Settings.canDrawOverlays(context)) TriState.Yes else TriState.No
}.getOrDefault(TriState.Unreadable)

private fun readAppOps(context: Context): TriState = runCatching {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return TriState.Unreadable
    @Suppress("DEPRECATION")
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            Process.myUid(),
            context.packageName,
        )
    } else {
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            Process.myUid(),
            context.packageName,
        )
    }
    if (mode == AppOpsManager.MODE_ALLOWED) TriState.Yes else TriState.No
}.getOrDefault(TriState.Unreadable)

private fun readServiceEnabled(context: Context): TriState = runCatching {
    val raw = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )
    val listed = alertReaderListed(raw, context.packageName) ||
        alertReaderListed(raw, "app.apt443")
    if (listed) TriState.Yes else TriState.No
}.getOrDefault(TriState.Unreadable)

private fun readAccessibilityMaster(context: Context): TriState = runCatching {
    val value = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    if (value == 1) TriState.Yes else TriState.No
}.getOrDefault(TriState.Unreadable)

private fun readAdb(context: Context): TriState = runCatching {
    val value = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED)
    if (value == 1) TriState.Yes else TriState.No
}.getOrDefault(TriState.Unreadable)

private fun Context.hasFeature(name: String): Boolean =
    runCatching { packageManager.hasSystemFeature(name) }.getOrDefault(false)

private fun chooseFromDevice(context: Context): ChosenIp? {
    val fromLink = runCatching { linkCandidates(context) }.getOrDefault(emptyList())
    chooseTvAddress(fromLink)?.let { return it }
    val fromNic = runCatching { interfaceCandidates() }.getOrDefault(emptyList())
    return chooseTvAddress(fromNic)
}

private fun linkCandidates(context: Context): List<IpCandidate> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val active = runCatching { cm.activeNetwork }.getOrNull()
    val networks = runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks
    }.getOrNull() ?: return emptyList()
    val out = ArrayList<IpCandidate>()
    for (network in networks) {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> IpKind.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> IpKind.Ethernet
            else -> continue
        }
        val links = runCatching { cm.getLinkProperties(network)?.linkAddresses }.getOrNull() ?: continue
        for (link in links) {
            val host = ipv4Literal(link.address) ?: continue
            out += IpCandidate(host, kind, network == active)
        }
    }
    return out
}

private fun interfaceCandidates(): List<IpCandidate> {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    val out = ArrayList<IpCandidate>()
    for (nic in interfaces) {
        if (!nic.isUp || nic.isLoopback) continue
        val kind = interfaceKind(nic.name) ?: continue
        for (address in nic.inetAddresses) {
            val host = ipv4Literal(address) ?: continue
            out += IpCandidate(host, kind, active = false)
        }
    }
    return out
}

private fun ipv4Literal(address: InetAddress?): String? {
    if (address !is Inet4Address) return null
    val host = address.hostAddress?.substringBefore('%') ?: return null
    return host.takeIf { isUsableIpv4(it) }
}

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
