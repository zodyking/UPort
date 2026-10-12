package app.apt443.signin

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Downloads a site file from a direct http(s) link the viewer pasted on the phone. */
internal fun fetchRemoteSiteFile(rawUrl: String, maxBytes: Int = 512 * 1024): Result<ByteArray> = runCatching {
    var current = rawUrl.trim()
    if (current.isBlank()) error("Paste a direct download link.")
    repeat(5) {
        val uri = runCatching { URI(current) }.getOrElse { error("That link is not a valid address.") }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") error("Use an http or https download link.")
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) error("That link has no address.")
        if (blockedDownloadHost(host)) error("That address cannot be used for a site file.")
        val connection = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json, application/octet-stream, */*")
            setRequestProperty("User-Agent", "APT443")
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("The link redirected without a new address.")
                current = uri.resolve(location).toString()
                return@repeat
            }
            if (code !in 200..299) error("Download failed (HTTP $code).")
            val body = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) error("That file is too large.")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            if (body.isEmpty()) error("The download was empty.")
            return@runCatching body
        } finally {
            connection.disconnect()
        }
    }
    error("That link redirected too many times.")
}

internal fun blockedDownloadHost(host: String): Boolean {
    val name = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    if (name == "localhost" || name == "0.0.0.0" || name == "::1" || name == "metadata.google.internal") return true
    if (name == "169.254.169.254") return true
    if (name.startsWith("127.")) return true
    return false
}
