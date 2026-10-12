package app.apt443.signin

import app.apt443.net.LanAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Short-lived LAN page so a phone can download the current `.unvr` site file from the TV.
 */
class SiteExportBridgeServer(
    private val linkSlug: () -> String,
    private val fileName: () -> String,
    private val loadBytes: suspend () -> ByteArray,
) {
    private val urlState = MutableStateFlow<String?>(null)
    private val statusState = MutableStateFlow("Preparing export…")
    val url: StateFlow<String?> = urlState.asStateFlow()
    val status: StateFlow<String> = statusState.asStateFlow()

    @Volatile private var slug: String = "tv"
    @Volatile private var server: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = thread(name = "apt443-site-export", isDaemon = true) { runServer() }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        worker = null
        urlState.value = null
        statusState.value = "Export closed."
    }

    private fun runServer() {
        val socket = try {
            openSocket()
        } catch (_: Exception) {
            statusState.value = "This TV could not start site export."
            return
        }
        server = socket
        slug = PhoneLinks.slug(linkSlug())
        val endpoint = LanAddress.publish(socket.localPort) ?: LanAddress.emulatorPublished(socket.localPort)
        if (endpoint == null) {
            statusState.value = "This TV has no home-network address yet."
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }
        urlState.value = PhoneLinks.url(endpoint, slug)
        statusState.value = "Scan the code to download the site file on your phone."
        acceptLoop(socket)
    }

    private fun openSocket(): ServerSocket {
        var last: Exception? = null
        val ports = listOf(PREFERRED_PORT) + (FIRST_PORT..LAST_PORT).toList()
        for (port in ports) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (error: Exception) {
                last = error
            }
        }
        throw last ?: IOException("No export port free")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            try {
                handle(socket)
            } catch (_: Throwable) {
                writeHtml(socket, simplePage("Something went wrong. Try again from the TV."))
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val request = readRequest(socket) ?: run {
            writeHtml(socket, simplePage("Open the address shown on the TV."))
            return
        }
        val path = normalizePath(request.path)
        val base = PhoneLinks.path(slug)
        if (!PhoneLinks.matches(path, slug)) {
            writeHtml(socket, simplePage("Open the full link shown on the TV."))
            return
        }
        when {
            path == "$base/download" -> serveDownload(socket)
            else -> writeHtml(socket, exportPage(base))
        }
    }

    private fun serveDownload(socket: Socket) {
        val bytes = runBlocking { loadBytes() }
        val name = fileName().ifBlank { "apt443-site.${SitePackage.EXTENSION}" }
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Disposition: attachment; filename=\"")
            append(name.replace("\"", ""))
            append("\"\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun exportPage(basePath: String): String {
        val download = "$basePath/download"
        return buildString {
            append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>Download site file · UPort</title><style>")
            append(PHONE_CSS)
            append("</style></head><body><main>")
            append("<h1>Site file</h1>")
            append("<p class=\"lead\">Save this file somewhere safe and keep it private.</p>")
            append("<p><a class=\"button\" href=\"").append(download).append("\">Download .unvr site file</a></p>")
            append("<p class=\"note\">Only works on your home Wi‑Fi while the TV shows the export code.</p>")
            append("</main></body></html>")
        }
    }

    private fun simplePage(message: String): String = buildString {
        append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>UPort</title><style>").append(PHONE_CSS).append("</style></head><body><main>")
        append("<h1>").append(escape(message)).append("</h1>")
        append("</main></body></html>")
    }

    private fun readRequest(socket: Socket): Request? {
        socket.soTimeout = 12_000
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        while (true) {
            val line = input.readLine() ?: return null
            if (line.isEmpty()) break
        }
        return Request(method = parts[0], path = parts[1])
    }

    private fun normalizePath(target: String): String {
        var path = target.substringBefore('?').trim()
        if (!path.startsWith("/")) path = "/$path"
        if (path.length > 1) path = path.trimEnd('/')
        return path
    }

    private fun writeHtml(socket: Socket, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private data class Request(val method: String, val path: String)

    private companion object {
        const val PREFERRED_PORT = 8080
        const val FIRST_PORT = 8763
        const val LAST_PORT = 8772
        const val PHONE_CSS = """
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;color:#1C2430;font:16px/1.45 system-ui,sans-serif;background:#F3F5F8}
main{width:100%;max-width:24rem;margin:0 auto;padding:1.5rem 1.25rem;text-align:center}
h1{margin:0;font-size:1.75rem;font-weight:600}
.lead{color:#4A5568;margin:1rem 0}
.note{font-size:.875rem;color:#6B7280;margin-top:1.25rem}
a.button{display:inline-block;margin-top:1rem;padding:.75rem 1.25rem;background:#1C64F2;color:#fff;text-decoration:none;border-radius:.5rem;font-weight:600}
"""
    }
}
