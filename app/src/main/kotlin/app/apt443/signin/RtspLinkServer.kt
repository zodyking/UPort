package app.apt443.signin

import app.apt443.domain.sanitizeRtsp
import app.apt443.net.LanAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A one-camera page on the TV's Wi-Fi. The phone pastes an RTSP address and this TV saves it.
 */
class RtspLinkServer(
    private val cameraName: String,
    private val onSave: (String) -> Unit,
) {
    private val urlState = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = urlState.asStateFlow()
    private val statusState = MutableStateFlow("Scan the code with your phone.")
    val status: StateFlow<String> = statusState.asStateFlow()
    private var worker: Thread? = null
    private var socket: ServerSocket? = null

    fun start() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = thread(name = "uport-rtsp-link", isDaemon = true) { runServer() }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        urlState.value = null
    }

    private fun runServer() {
        val server = openSocket() ?: run {
            statusState.value = "This TV could not open a page for the phone."
            return
        }
        socket = server
        val endpoint = LanAddress.publish(server.localPort) ?: LanAddress.emulatorPublished(server.localPort)
        if (endpoint == null) {
            statusState.value = "This TV has no home-network address yet."
            runCatching { server.close() }
            return
        }
        urlState.value = "http://$endpoint/stream"
        statusState.value = "Scan the code, then paste the camera's RTSP address."
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            try {
                handle(client)
            } catch (_: Exception) {
                runCatching { write(client, 200, page("Something went wrong. Try again.", error = true)) }
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 8_000
        val request = read(client) ?: run {
            write(client, 200, page(null, error = false))
            return
        }
        val path = request.path.substringBefore('?')
        if (path != "/stream" && path != "/") {
            write(client, 200, page("Open the address shown on the TV.", error = true))
            return
        }
        if (!request.method.equals("POST", ignoreCase = true)) {
            write(client, 200, page(null, error = false))
            return
        }
        val fields = decodeForm(request.body)
        val clean = sanitizeRtsp(fields["url"].orEmpty())
        if (clean == null) {
            write(client, 200, page("Paste an address that starts with rtsp://", error = true))
            return
        }
        onSave(clean)
        statusState.value = "Saved. You can put the phone down."
        write(client, 200, savedPage())
    }

    private fun openSocket(): ServerSocket? {
        for (port in 8090..8094) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun page(message: String?, error: Boolean): String {
        val note = if (message.isNullOrBlank()) {
            ""
        } else {
            val kind = if (error) "error" else "ok"
            "<p class=\"$kind\">${escape(message)}</p>"
        }
        return """
            <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>UPort stream</title>
            <style>
            body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#F3F5F8;color:#1C2430;font:16px/1.45 system-ui,sans-serif}
            main{width:min(28rem,calc(100% - 2rem));background:#fff;border-radius:16px;padding:1.5rem}
            h1{font-size:1.4rem;margin:0 0 .4rem} p{margin:.4rem 0 1rem;color:#3C4A5C}
            label{display:block;font-weight:600;margin-bottom:.4rem}
            input{width:100%;box-sizing:border-box;font:1rem ui-monospace,monospace;padding:.7rem;border:1px solid #C5CED8;border-radius:10px}
            button{margin-top:1rem;width:100%;font:1rem system-ui;padding:.8rem;border:0;border-radius:10px;background:#0860FA;color:#fff}
            .error{color:#9B2C2C} .ok{color:#176B3A}
            </style></head><body><main>
            <h1>${escape(cameraName)}</h1>
            <p>Paste the camera's RTSP address. It is saved on the TV only.</p>
            $note
            <form method="post" action="/stream">
            <label for="url">RTSP address</label>
            <input id="url" name="url" type="url" inputmode="url" autocomplete="off" placeholder="rtsp://192.168.0.20:554/stream" required>
            <button type="submit">Save on the TV</button>
            </form></main></body></html>
        """.trimIndent()
    }

    private fun savedPage(): String = """
        <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Saved</title>
        <style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#F3F5F8;color:#1C2430;font:16px/1.45 system-ui,sans-serif}main{padding:1.5rem}</style>
        </head><body><main><h1>Saved</h1><p>The TV has the stream. You can close this page.</p></main></body></html>
    """.trimIndent()

    private fun read(client: Socket): HttpRequest? {
        val input = client.getInputStream()
        val header = ByteArray(4096)
        var used = 0
        while (used < header.size) {
            val n = input.read(header, used, header.size - used)
            if (n < 0) break
            used += n
            val end = indexOf(header, used, "\r\n\r\n".toByteArray())
            if (end >= 0) {
                val text = header.copyOf(end).toString(Charsets.ISO_8859_1)
                val line = text.lineSequence().firstOrNull().orEmpty()
                val method = line.substringBefore(' ')
                val path = line.substringAfter(' ').substringBefore(' ')
                val length = text.lineSequence()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0
                if (length !in 0..2_048) return null
                val already = used - (end + 4)
                val body = ByteArray(length)
                if (already > 0) header.copyInto(body, 0, end + 4, minOf(used, end + 4 + length))
                var filled = minOf(already, length)
                while (filled < length) {
                    val n2 = input.read(body, filled, length - filled)
                    if (n2 < 0) break
                    filled += n2
                }
                return HttpRequest(method, path, body.copyOf(filled).toString(Charsets.UTF_8))
            }
        }
        return null
    }

    private fun write(client: Socket, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun decodeForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split('&').mapNotNull { part ->
            val name = urlDecode(part.substringBefore('='))
            val value = urlDecode(part.substringAfter('=', ""))
            if (name.isEmpty()) null else name to value
        }.toMap()
    }

    @Suppress("DEPRECATION")
    private fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")

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

    private fun indexOf(data: ByteArray, length: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        for (i in 0..length - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private data class HttpRequest(val method: String, val path: String, val body: String)
}
