package app.apt443.domain

/** One RTSP address typed or pasted for a camera Protect will not stream. */
fun sanitizeRtsp(raw: String): String? {
    val url = raw.trim()
    if (url.length !in 12..500) return null
    val scheme = url.substringBefore(':').lowercase()
    if (scheme != "rtsp" && scheme != "rtsps") return null
    if (!url.startsWith("$scheme://", ignoreCase = true)) return null
    if (url.any { it.isISOControl() || it.isWhitespace() }) return null
    return url
}

/** Hides a password embedded in the address. The saved value is unchanged. */
fun redactRtsp(url: String): String {
    return url.replace(Regex("((?i)rtsps?://[^:/@]+):[^@]+@"), "$1:•••@")
}

fun encodeManualRtsp(urls: Map<String, String>): String {
    return urls.entries.mapNotNull { (id, url) ->
        val cameraId = id.trim()
        val clean = sanitizeRtsp(url) ?: return@mapNotNull null
        if (cameraId.isEmpty() || cameraId.contains('\n') || cameraId.contains('\u001f')) return@mapNotNull null
        cameraId + "\u001f" + clean
    }.take(64).joinToString("\n")
}

fun decodeManualRtsp(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.lineSequence().mapNotNull { line ->
        val parts = line.split('\u001f', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val cameraId = parts[0].trim()
        val url = sanitizeRtsp(parts[1]) ?: return@mapNotNull null
        if (cameraId.isEmpty()) return@mapNotNull null
        cameraId to url
    }.toMap()
}

fun AppSettings.manualRtsp(cameraId: String): String? {
    return manualRtspByCamera[cameraId]?.takeIf { it.isNotBlank() }
}
