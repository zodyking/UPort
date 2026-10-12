package app.apt443.alert

import app.apt443.net.LanAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serves a phone-friendly link while alert setup is open on the TV:
 * http://192.168.x.x:8743/apt443/1234
 */
class LocalAlertSetupServer(
    private val onVerified: () -> Unit,
) {
    private val urlState = MutableStateFlow<String?>(null)
    private val statusState = MutableStateFlow("Preparing phone link…")
    private val verifiedState = MutableStateFlow(false)

    val url: StateFlow<String?> = urlState.asStateFlow()
    val status: StateFlow<String> = statusState.asStateFlow()
    val verified: StateFlow<Boolean> = verifiedState.asStateFlow()

    @Volatile private var code: String = ""
    @Volatile private var server: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            verifiedState.value = false
            code = newCode()
            worker = thread(name = "apt443-alert-setup", isDaemon = true) { runServer() }
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
        statusState.value = "Phone link closed."
    }

    fun refreshCode() {
        code = newCode()
        verifiedState.value = false
        publishUrl(server?.localPort ?: FIRST_PORT)
        statusState.value = "Open this link on your phone."
    }

    private fun runServer() {
        val socket = try {
            openSocket()
        } catch (_: Exception) {
            statusState.value = "Could not open the phone link on this TV."
            urlState.value = null
            return
        }
        server = socket
        publishUrl(socket.localPort)
        statusState.value = "Open this link on your phone."
        acceptLoop(socket)
    }

    private fun publishUrl(port: Int) {
        val endpoint = LanAddress.publish(port) ?: LanAddress.emulatorPublished(port)
        urlState.value = endpoint?.let { hostPort ->
            AlertSetupPaths.publicUrl(hostPort.substringBefore(':'), port, code)
        }
    }

    private fun openSocket(): ServerSocket {
        var last: Exception? = null
        for (port in FIRST_PORT..LAST_PORT) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (error: Exception) {
                last = error
            }
        }
        throw last ?: IOException("No alert setup port free")
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
            } catch (_: Exception) {
                writeHtml(socket, simplePage("UPort", "Open the full address shown on your TV."))
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val request = readRequest(socket) ?: run {
            writeHtml(socket, simplePage("UPort", "Open the full address shown on your TV."))
            return
        }
        val path = normalizePath(request.path)
        when {
            !AlertSetupPaths.matchesCode(path, code) && path != PATH_PREFIX -> {
                writeHtml(socket, simplePage("UPort", "That code does not match the TV. Use the full link shown on the TV."))
            }
            path == PATH_PREFIX -> {
                writeHtml(socket, simplePage("UPort", "Add the 4-digit code from your TV to the address."))
            }
            AlertSetupPaths.matchesCode(path, code) -> {
                verifiedState.value = true
                statusState.value = "Phone linked. Finish any remaining steps on the TV."
                onVerified()
                writeHtml(
                    socket,
                    simplePage(
                        title = "UPort linked",
                        message = "Your phone reached the TV. Return to the TV to finish alert setup.",
                    ),
                )
            }
        }
    }

    private fun readRequest(socket: Socket): Request? {
        socket.soTimeout = 8_000
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
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            val scheme = path.indexOf("://")
            val slash = if (scheme < 0) -1 else path.indexOf('/', startIndex = scheme + 3)
            path = if (slash < 0) "/" else path.substring(slash)
        }
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

    private fun simplePage(title: String, message: String): String = buildString {
        append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(escape(title)).append("</title>")
        append("<style>body{font-family:system-ui,sans-serif;max-width:28rem;margin:2rem auto;padding:0 1rem;}")
        append("h1{font-size:1.5rem}p{line-height:1.5;color:#444}</style></head><body>")
        append("<h1>").append(escape(title)).append("</h1>")
        append("<p>").append(escape(message)).append("</p>")
        append("</body></html>")
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

    private fun newCode(): String = (SecureRandom().nextInt(9000) + 1000).toString()

    private data class Request(val method: String, val path: String)

    companion object {
        const val PATH_PREFIX = "/apt443"
        private const val FIRST_PORT = 8743
        private const val LAST_PORT = 8752
    }
}

internal object AlertSetupPaths {
    fun publicUrl(host: String, port: Int, code: String): String =
        "http://$host:$port${LocalAlertSetupServer.PATH_PREFIX}/$code"

    fun matchesCode(path: String, code: String): Boolean {
        val submitted = path.removePrefix(LocalAlertSetupServer.PATH_PREFIX).removePrefix("/").trimEnd('/')
        return submitted.length == 4 && submitted.all { it.isDigit() } && submitted == code
    }
}
