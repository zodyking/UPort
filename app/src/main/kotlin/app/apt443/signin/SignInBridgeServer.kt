package app.apt443.signin

import app.apt443.domain.IdentityUser
import app.apt443.domain.TenantLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * LAN phone pages for site setup (file upload) and tenant sign-in (name + email).
 */
class SignInBridgeServer(
    private val linkSlug: () -> String,
    private val mode: () -> SignInFlowMode,
    private val onSiteUpload: suspend (ByteArray, String?) -> Result<String>,
    private val loadTenants: suspend () -> Result<List<IdentityUser>>,
    private val onTenantVerify: suspend (userId: String, email: String) -> Result<String>,
    private val onResetSite: suspend () -> Unit,
) {
    private val urlState = MutableStateFlow<String?>(null)
    private val statusState = MutableStateFlow("Preparing the code…")
    private val problemState = MutableStateFlow(false)
    val url: StateFlow<String?> = urlState.asStateFlow()
    val status: StateFlow<String> = statusState.asStateFlow()
    val problem: StateFlow<Boolean> = problemState.asStateFlow()

    @Volatile private var slug: String = "tv"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var tenants: List<IdentityUser> = emptyList()
    @Volatile private var tenantsError: String = ""
    @Volatile private var pendingUserId: String = ""
    @Volatile private var lastPackage: ByteArray? = null
    @Volatile private var lastFileName: String = ""
    private var worker: Thread? = null

    fun updateStatus(message: String, problem: Boolean = false) {
        statusState.value = message
        problemState.value = problem
    }

    fun refreshTenants() {
        if (mode() != SignInFlowMode.TenantSignIn) return
        thread(name = "apt443-tenants", isDaemon = true) {
            val result = runBlocking { loadTenants() }
            result.fold(
                onSuccess = { list ->
                    tenants = list
                    tenantsError = if (list.isEmpty()) "No residents with email were found on this console." else ""
                    if (list.isNotEmpty()) {
                        updateStatus("Scan the code and choose your name.")
                    }
                },
                onFailure = { error ->
                    tenants = emptyList()
                    tenantsError = error.message ?: "Could not load residents."
                    updateStatus(tenantsError, problem = true)
                },
            )
        }
    }

    fun start() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = thread(name = "apt443-signin", isDaemon = true) { runServer() }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        pendingUserId = ""
        urlState.value = null
    }

    private fun runServer() {
        val socket = try {
            openSocket()
        } catch (_: Exception) {
            updateStatus("This TV could not open a sign-in page.", problem = true)
            return
        }
        server = socket
        val endpoint = advertise(socket.localPort)
        if (endpoint == null) {
            updateStatus("This TV has no home-network address yet.", problem = true)
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }
        slug = PhoneLinks.slug(linkSlug())
        urlState.value = PhoneLinks.url(endpoint, slug)
        when (mode()) {
            SignInFlowMode.SiteSetup -> updateStatus("Scan the code to import a site file.")
            SignInFlowMode.TenantSignIn -> {
                updateStatus("Loading residents…")
                refreshTenants()
            }
        }
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
        throw last ?: IOException("No sign-in port free")
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
                try {
                    writeHtml(socket, 200, pageForMode(error = "Something went wrong. Try again."))
                } catch (_: Exception) {
                }
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        val request = readRequest(socket) ?: run {
            writeHtml(socket, 200, pageForMode())
            return
        }
        val path = normalizePath(request.path)
        if (!PhoneLinks.matches(path, slug)) {
            writeHtml(socket, 200, simplePage("Open the address shown on the TV."))
            return
        }
        when {
            request.method.equals("GET", ignoreCase = true) && queryParam(request.path, "download") == "1" -> {
                serveSiteFile(socket)
            }
            request.method.equals("GET", ignoreCase = true) && queryParam(request.path, "watch") == "1" -> {
                val name = if (mode() == SignInFlowMode.SiteSetup) "setup" else "signin"
                writeText(socket, 200, name)
            }
            request.method.equals("GET", ignoreCase = true) -> {
                val filter = queryParam(request.path, "q")
                writeHtml(socket, 200, pageForMode(filter = filter))
            }
            request.method.equals("POST", ignoreCase = true) -> submit(socket, request)
            else -> writeHtml(socket, 200, pageForMode())
        }
    }

    private fun submit(socket: Socket, request: HttpRequest) {
        when (mode()) {
            SignInFlowMode.SiteSetup -> submitSetup(socket, request)
            SignInFlowMode.TenantSignIn -> submitTenant(socket, request)
        }
    }

    private fun submitSetup(socket: Socket, request: HttpRequest) {
        val multipart = request.contentType.contains("multipart/form-data", ignoreCase = true)
        val fields = if (multipart) {
            decodeMultipartFields(request.bodyBytes, request.contentType)
        } else {
            decodeForm(request.bodyText)
        }
        val action = fields["action"].orEmpty().ifBlank { if (multipart) "upload" else "create" }
        val panel = when (action) {
            "link" -> "link"
            "upload" -> "file"
            else -> "create"
        }
        val packed = when (action) {
            "create" -> SitePackage.fromSetupFields(fields).map { it.toJsonBytes() }
            "link" -> fetchRemoteSiteFile(fields["fileUrl"].orEmpty())
            else -> {
                val file = extractSiteFile(request)
                if (file == null || file.isEmpty()) {
                    Result.failure(IllegalStateException("Choose a .unvr site file to upload."))
                } else {
                    Result.success(file)
                }
            }
        }
        val bytes = packed.getOrElse { error ->
            writeHtml(socket, 200, setupPage(error = error.message ?: "Import failed.", panel = panel, fields = fields))
            return
        }
        val result = runBlocking { onSiteUpload(bytes, fields["tvReach"]) }
        result.fold(
            onSuccess = { message ->
                val name = SitePackage.parse(bytes).getOrNull()?.siteName ?: "UPort"
                lastPackage = bytes
                lastFileName = SitePackage.fileNameFor(name)
                val loaded = runBlocking { loadTenants() }
                loaded.fold(
                    onSuccess = { list ->
                        tenants = list
                        tenantsError = if (list.isEmpty()) "No residents with email were found on this console." else ""
                    },
                    onFailure = { error ->
                        tenants = emptyList()
                        tenantsError = error.message ?: "Could not load residents."
                    },
                )
                writeHtml(
                    socket,
                    200,
                    tenantPage(
                        filter = "",
                        error = "",
                        banner = message,
                    ),
                )
            },
            onFailure = { error ->
                writeHtml(socket, 200, setupPage(error = error.message ?: "Import failed.", panel = panel, fields = fields))
            },
        )
    }

    private fun submitTenant(socket: Socket, request: HttpRequest) {
        val fields = if (request.contentType.contains("multipart/form-data")) {
            decodeMultipartFields(request.bodyBytes, request.contentType)
        } else {
            decodeForm(request.bodyText)
        }
        val action = fields["action"].orEmpty()
        when (action) {
            "reset" -> {
                runBlocking { onResetSite() }
                tenants = emptyList()
                tenantsError = ""
                pendingUserId = ""
                lastPackage = null
                lastFileName = ""
                writeHtml(socket, 200, setupPage(error = ""))
            }
            "pick" -> {
                val userId = fields["user"].orEmpty()
                if (userId.isBlank()) {
                    writeHtml(socket, 200, tenantPage(filter = "", error = "Choose your name from the list."))
                    return
                }
                pendingUserId = userId
                writeHtml(socket, 200, tenantEmailPage(userId, error = ""))
            }
            "verify" -> {
                val userId = fields["user"].orEmpty().ifBlank { pendingUserId }
                val email = fields["email"].orEmpty()
                if (userId.isBlank()) {
                    writeHtml(socket, 200, tenantPage(filter = "", error = "Choose your name first."))
                    return
                }
                val result = runBlocking { onTenantVerify(userId, email) }
                result.fold(
                    onSuccess = { message ->
                        pendingUserId = ""
                        writeHtml(socket, 200, simplePage(message))
                    },
                    onFailure = { error ->
                        writeHtml(socket, 200, tenantEmailPage(userId, error = error.message ?: "Try again."))
                    },
                )
            }
            else -> writeHtml(socket, 200, tenantPage(filter = fields["q"].orEmpty(), error = ""))
        }
    }

    private fun pageForMode(filter: String = "", error: String = ""): String = when (mode()) {
        SignInFlowMode.SiteSetup -> setupPage(error = error)
        SignInFlowMode.TenantSignIn -> tenantPage(filter = filter, error = error)
    }

    private fun setupPage(
        error: String,
        panel: String = "create",
        fields: Map<String, String> = emptyMap(),
    ): String {
        val html = StringBuilder()
        pageHead(html, "Set up TV", bodyClass = "setup", pageMode = "setup")
        html.append("<h1>Set up this TV</h1>")
        html.append("<p class=\"lead\">Enter the console details, upload a site file, or paste a direct download link.</p>")
        if (error.isNotBlank()) html.append("<p class=\"error\">").append(escape(error)).append("</p>")
        html.append("<div class=\"tabs\" role=\"tablist\">")
        html.append(tabButton("create", "Details", panel))
        html.append(tabButton("file", "File", panel))
        html.append(tabButton("link", "Link", panel))
        html.append("</div>")
        html.append("<section id=\"create\" class=\"panel").append(if (panel == "create") " on" else "").append("\">")
        html.append("<form method=\"post\" action=\"\">")
        html.append("<input type=\"hidden\" name=\"action\" value=\"create\">")
        field(html, "siteName", "Site name", fields, "UPort", "text")
        field(html, "consoleHost", "Console address", fields, "192.168.0.1", "text")
        field(html, "apiKey", "API key", fields, "", "password")
        field(html, "osUsername", "Local admin username", fields, "", "text")
        field(html, "osPassword", "Local admin password", fields, "", "password")
        val trusted = fields["trustConsoleCertificate"].orEmpty().let {
            it.equals("on", true) || it.equals("true", true) || it == "1"
        }
        html.append("<label class=\"check\"><input type=\"checkbox\" name=\"trustConsoleCertificate\" value=\"on\"")
        if (trusted) html.append(" checked")
        html.append("> Trust the console certificate</label>")
        vpnFields(html, fields)
        html.append("<button type=\"submit\">Connect this TV</button>")
        html.append("</form></section>")
        html.append("<section id=\"file\" class=\"panel").append(if (panel == "file") " on" else "").append("\">")
        html.append("<form method=\"post\" enctype=\"multipart/form-data\" action=\"\">")
        html.append("<input type=\"hidden\" name=\"action\" value=\"upload\">")
        html.append("<label for=\"sitefile\">Site file (.unvr)</label>")
        html.append("<input id=\"sitefile\" name=\"sitefile\" type=\"file\" accept=\".unvr,.apt443,.json,application/octet-stream,application/json\">")
        html.append("<p class=\"note\">Older .apt443 and .json copies still open. A Tailscale auth key in the file is saved on this TV.</p>")
        html.append("<button type=\"submit\">Upload and connect</button>")
        html.append("</form></section>")
        html.append("<section id=\"link\" class=\"panel").append(if (panel == "link") " on" else "").append("\">")
        html.append("<form method=\"post\" action=\"\">")
        html.append("<input type=\"hidden\" name=\"action\" value=\"link\">")
        field(html, "fileUrl", "Direct download link", fields, "https://example.com/site.unvr", "url")
        html.append("<button type=\"submit\">Download and connect</button>")
        html.append("</form></section>")
        html.append("<p class=\"note\">Stay on the same Wi‑Fi as the TV while you send the file. The local admin is a console account, not a ui.com login. A reusable Tailscale auth key makes this TV join your tailnet. On the home network, the console address is its home address. Away from home, use the console's own Tailscale IP or MagicDNS name. The key stays on the TV and is not shown again.</p>")
        html.append("<script>")
        html.append("function showPanel(id){")
        html.append("document.querySelectorAll('.panel').forEach(function(p){p.classList.toggle('on',p.id===id)});")
        html.append("document.querySelectorAll('.tabs button').forEach(function(b){b.classList.toggle('on',b.getAttribute('data-panel')===id)});")
        html.append("}")
        html.append("</script>")
        pageEnd(html)
        return html.toString()
    }

    private fun tabButton(id: String, label: String, selected: String): String {
        val on = if (id == selected) " on" else ""
        return "<button type=\"button\" class=\"$on\" data-panel=\"$id\" onclick=\"showPanel('$id')\">$label</button>"
    }

    private fun vpnFields(html: StringBuilder, fields: Map<String, String>) {
        val vpn = fields["vpn"].orEmpty().ifBlank { "none" }
        html.append("<label for=\"vpn\">Tailscale</label>")
        html.append("<select id=\"vpn\" name=\"vpn\">")
        vpnOption(html, "none", "None", vpn)
        vpnOption(html, "tailscale", "Tailscale", vpn)
        html.append("</select>")
        field(html, "tailscaleAuthKey", "Tailscale auth key", fields, "", "password")
        field(html, "tailscaleLoginServer", "Tailscale login server (optional)", fields, "https://", "url")
        html.append("<p class=\"note\">Use a reusable auth key. This TV joins Tailscale itself. On the same network as the console, enter the console's home address (often 192.168.0.1). A 100.x address works only when that machine is the console itself. Leave the login server blank for a normal Tailscale account.</p>")
    }

    private fun vpnOption(html: StringBuilder, value: String, label: String, selected: String) {
        html.append("<option value=\"").append(value).append("\"")
        if (value == selected) html.append(" selected")
        html.append(">").append(label).append("</option>")
    }

    private fun field(
        html: StringBuilder,
        name: String,
        label: String,
        fields: Map<String, String>,
        placeholder: String,
        type: String,
    ) {
        val value = fields[name].orEmpty()
        html.append("<label for=\"").append(name).append("\">").append(escape(label)).append("</label>")
        html.append("<input id=\"").append(name).append("\" name=\"").append(name).append("\" type=\"").append(type).append("\"")
        html.append(" autocomplete=\"off\"")
        if (placeholder.isNotBlank()) html.append(" placeholder=\"").append(escape(placeholder)).append("\"")
        html.append(" value=\"").append(escape(value)).append("\"")
        html.append(">")
    }

    private fun tenantPage(
        filter: String,
        error: String,
        banner: String = "",
    ): String {
        val html = StringBuilder()
        pageHead(html, "Sign in", pageMode = "signin")
        html.append("<h1>Sign in</h1>")
        if (banner.isNotBlank()) html.append("<p class=\"lead\">").append(escape(banner)).append("</p>")
        if (lastPackage != null) {
            html.append("<p class=\"lead\">Save a copy of this site file, then choose your name.</p>")
            html.append("<p><a class=\"button\" href=\"?download=1\">Download .unvr site file</a></p>")
        }
        html.append("<p class=\"lead\">Choose your name, then confirm the email on file for your account.</p>")
        if (tenantsError.isNotBlank()) {
            html.append("<p class=\"error\">").append(escape(tenantsError)).append("</p>")
        }
        if (error.isNotBlank()) html.append("<p class=\"error\">").append(escape(error)).append("</p>")
        html.append("<form method=\"get\" action=\"\">")
        html.append("<label for=\"q\">Filter names</label>")
        html.append("<input id=\"q\" name=\"q\" type=\"search\" value=\"").append(escape(filter)).append("\" placeholder=\"Tom\">")
        html.append("<button type=\"submit\" class=\"secondary\">Filter</button>")
        html.append("</form>")
        val visible = tenants.filter { TenantLabel.matchesFilter(it, filter) }
        html.append("<form method=\"post\" action=\"\">")
        html.append("<input type=\"hidden\" name=\"action\" value=\"pick\">")
        if (visible.isEmpty()) {
            html.append("<p>No names match. Clear the filter or ask the property owner to check Identity users.</p>")
        } else {
            html.append("<div class=\"list\">")
            for (user in visible) {
                html.append("<button type=\"submit\" name=\"user\" value=\"")
                html.append(escape(user.id)).append("\" class=\"name\">")
                html.append(escape(user.shortLabel))
                html.append("</button>")
            }
            html.append("</div>")
        }
        html.append("</form>")
        html.append("<form method=\"post\" action=\"\">")
        html.append("<input type=\"hidden\" name=\"action\" value=\"reset\">")
        html.append("<button type=\"submit\" class=\"secondary\">Set up this TV again</button>")
        html.append("</form>")
        html.append("<p class=\"note\">Set up again clears the saved console and starts from the site form.</p>")
        pageEnd(html)
        return html.toString()
    }

    private fun tenantEmailPage(userId: String, error: String): String {
        val user = tenants.firstOrNull { it.id == userId }
        val label = user?.shortLabel ?: "your account"
        val html = StringBuilder()
        pageHead(html, "Confirm email", pageMode = "signin")
        html.append("<h1>Confirm email</h1>")
        html.append("<p class=\"lead\">Enter the email on file for <strong>").append(escape(label)).append("</strong>.</p>")
        if (error.isNotBlank()) html.append("<p class=\"error\">").append(escape(error)).append("</p>")
        html.append("<form method=\"post\" action=\"\">")
        html.append("<input type=\"hidden\" name=\"action\" value=\"verify\">")
        html.append("<input type=\"hidden\" name=\"user\" value=\"").append(escape(userId)).append("\">")
        html.append("<label for=\"email\">Email</label>")
        html.append("<input id=\"email\" name=\"email\" type=\"email\" autocomplete=\"email\" required>")
        html.append("<button type=\"submit\">Sign in</button>")
        html.append("</form>")
        html.append("<p class=\"note\"><a href=\"?\">Pick a different name</a></p>")
        pageEnd(html)
        return html.toString()
    }

    private fun pageHead(html: StringBuilder, title: String, bodyClass: String = "", pageMode: String = "") {
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        html.append("<title>").append(escape(title)).append(" · UPort</title><style>")
        html.append(PHONE_CSS)
        html.append("</style></head><body")
        if (pageMode.isNotBlank()) html.append(" data-mode=\"").append(pageMode).append("\"")
        if (bodyClass.isNotBlank()) html.append(" class=\"").append(bodyClass).append("\"")
        html.append("><main>")
    }

    private fun pageEnd(html: StringBuilder) {
        html.append("</main><script>")
        html.append("setInterval(function(){var here=document.body.getAttribute('data-mode');if(!here)return;")
        html.append("fetch(location.pathname+'?watch=1',{cache:'no-store'}).then(function(r){return r.text()}).then(function(mode){")
        html.append("if(mode&&mode!==here)location.reload();}).catch(function(){});},2500);")
        html.append("</script></body></html>")
    }

    private fun serveSiteFile(socket: Socket) {
        val bytes = lastPackage
        if (bytes == null) {
            writeHtml(socket, 200, simplePage("Create or upload a site file on this page first."))
            return
        }
        val name = lastFileName.ifBlank { "apt443-site.${SitePackage.EXTENSION}" }.replace("\"", "")
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Disposition: attachment; filename=\"").append(name).append("\"\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun simplePage(message: String): String {
        val html = StringBuilder()
        pageHead(html, "UPort")
        html.append("<h1>").append(escape(message)).append("</h1>")
        pageEnd(html)
        return html.toString()
    }

    private fun writeText(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(" OK\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun extractSiteFile(request: HttpRequest): ByteArray? {
        if (request.contentType.contains("multipart/form-data")) {
            return extractMultipartFile(request.bodyBytes, request.contentType, "sitefile")
        }
        val fields = decodeForm(request.bodyText)
        return fields["package"]?.toByteArray(Charsets.UTF_8)
    }

    private fun readRequest(socket: Socket): HttpRequest? {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val headerBuffer = ByteArrayOutputStream()
        var b: Int
        var state = 0
        while (true) {
            b = input.read()
            if (b < 0) return null
            headerBuffer.write(b)
            when (state) {
                0 -> state = if (b == '\r'.code) 1 else 0
                1 -> state = if (b == '\n'.code) 2 else 0
                2 -> state = if (b == '\r'.code) 3 else 1
                3 -> if (b == '\n'.code) break else state = 1
            }
            if (headerBuffer.size() > 32_768) return null
        }
        val headerText = String(headerBuffer.toByteArray(), Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val bodyBytes = readBody(input, headers)
        return HttpRequest(
            method = parts[0],
            path = parts[1],
            contentType = headers["content-type"] ?: "",
            bodyBytes = bodyBytes,
            bodyText = bodyBytes.toString(Charsets.UTF_8),
        )
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

    private fun queryParam(path: String, name: String): String {
        val query = path.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty() || !path.contains('?')) return ""
        return query.split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.let { urlDecode(it) }
            .orEmpty()
    }

    private fun writeHtml(socket: Socket, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(" OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val encoding = headers["transfer-encoding"].orEmpty()
        if (encoding.contains("chunked", ignoreCase = true)) return readChunked(input)
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        if (length == 0) return byteArrayOf()
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buf, read, length - read)
            if (count < 0) break
            read += count
        }
        return buf.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY) {
            val line = readAsciiLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) break
            val room = (MAX_BODY - out.size()).coerceAtLeast(0)
            val take = minOf(size, room)
            val buf = ByteArray(take)
            var read = 0
            while (read < take) {
                val count = input.read(buf, read, take - read)
                if (count < 0) break
                read += count
            }
            out.write(buf, 0, read)
            var skip = size - take
            while (skip > 0) {
                val dumped = input.read()
                if (dumped < 0) break
                skip--
            }
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < 256) {
            val value = input.read()
            if (value < 0) return if (line.size() == 0) null else line.toString(Charsets.US_ASCII.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) line.write(value)
        }
        return line.toString(Charsets.US_ASCII.name())
    }

    private fun decodeForm(body: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        for (part in body.split('&')) {
            if (part.isEmpty()) continue
            val name = urlDecode(part.substringBefore('='))
            val value = urlDecode(part.substringAfter('=', missingDelimiterValue = ""))
            if (name.isNotEmpty()) fields[name] = value
        }
        return fields
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

    private fun advertise(port: Int): String? {
        lanIpv4()?.let { return "$it:$port" }
        repeat(8) { attempt ->
            emulatorPublished()?.let { published ->
                if (published.substringAfter(':').toIntOrNull() == port) return published
            }
            if (attempt < 7) Thread.sleep(500)
        }
        return null
    }

    private fun emulatorPublished(): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("10.0.2.2", HOST_BRIDGE_PORT), 700)
            socket.getOutputStream().apply {
                write("GET /lan HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n".toByteArray())
                flush()
            }
            val payload = socket.getInputStream().bufferedReader().readText()
                .substringAfter("\r\n\r\n", missingDelimiterValue = "")
                .trim()
            payload.takeIf { PUBLISHED.matches(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun lanIpv4(): String? {
        val preferred = mutableListOf<String>()
        val others = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
            val name = iface.name.lowercase()
            if (name.contains("p2p") || name.contains("dummy") || name.contains("wifi-aware")) continue
            for (address in iface.inetAddresses) {
                if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = address.hostAddress ?: continue
                if (emulatorNat(host)) continue
                if (host.startsWith("192.168.") || host.startsWith("10.")) preferred.add(host) else others.add(host)
            }
        }
        return preferred.firstOrNull() ?: others.firstOrNull()
    }

    private fun emulatorNat(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts[0] == "10" && parts[1] == "0" && parts[2] == "2"
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val contentType: String,
        val bodyBytes: ByteArray,
        val bodyText: String,
    )

    private companion object {
        const val PREFERRED_PORT = 8080
        const val FIRST_PORT = 8743
        const val LAST_PORT = 8752
        const val HOST_BRIDGE_PORT = 8742
        const val MAX_BODY = 512 * 1024
        val PUBLISHED = Regex("""\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}""")
        const val PHONE_CSS = """
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;color:#1C2430;font:16px/1.45 system-ui,sans-serif;background:#F3F5F8}
body.setup{align-items:flex-start}
main{width:100%;max-width:26rem;margin:0 auto;padding:1.5rem 1.25rem 2.5rem;text-align:center}
h1{margin:0;font-size:1.75rem;font-weight:600}
.lead{margin:.45rem 0 0;color:#5E6A75}
.note{margin:1rem 0 0;color:#8B97A3;font-size:.8125rem}
.error{margin:.75rem 0;color:#E03131;text-align:left}
.tabs{display:flex;gap:.25rem;margin-top:1.25rem;padding:.25rem;background:#E6EBF1;border-radius:10px}
.tabs button{flex:1;margin:0;min-height:40px;background:transparent;color:#5E6A75;font-size:.85rem}
.tabs button.on{background:#fff;color:#1C2430}
.panel{display:none}
.panel.on{display:block}
form{display:flex;flex-direction:column;margin-top:.75rem;background:#fff;border:1px solid #D5DCE4;border-radius:12px;padding:1.1rem 1.15rem 1.2rem;text-align:left}
label{margin-top:.85rem;font-size:.875rem;color:#5E6A75}
label:first-of-type{margin-top:0}
label.check{display:flex;align-items:center;gap:.6rem;margin-top:1rem;color:#1C2430}
label.check input{width:1.15rem;min-height:0;height:1.15rem;margin:0}
input,select,textarea{width:100%;margin-top:.35rem;border:1px solid #D5DCE4;border-radius:8px;padding:.7rem .8rem;font:16px sans-serif;box-sizing:border-box;background:#fff}
input,select{min-height:48px}
textarea{min-height:8rem;font-family:ui-monospace,monospace}
.legend{display:block;margin-top:.85rem;font-size:.875rem;color:#5E6A75}
.choice label.check{margin-top:.45rem}
button,a.button{display:flex;align-items:center;justify-content:center;margin-top:1rem;min-height:48px;border:0;border-radius:8px;background:#006FFF;color:#fff;font:inherit;font-weight:600;text-decoration:none}
button.secondary{background:#E7F0FF;color:#1C2430}
button.name{width:100%;text-align:center}
.list{display:flex;flex-direction:column;gap:.5rem;margin-top:.5rem}
a{color:#006FFF}
"""
    }
}

internal fun multipartBoundary(contentType: String): String {
    val raw = contentType.substringAfter("boundary=", "").trim()
    if (raw.isBlank()) return ""
    return raw.removePrefix("\"").substringBefore("\"").substringBefore(";").trim()
}

internal fun extractMultipartFile(body: ByteArray, contentType: String, fieldName: String): ByteArray? {
    val boundary = multipartBoundary(contentType)
    if (boundary.isBlank()) return null
    for (part in splitMultipart(body, "--$boundary".toByteArray())) {
        val split = headerSplit(part) ?: continue
        val header = part.copyOfRange(0, split.first).toString(Charsets.UTF_8)
        if (!dispositionName(header).equals(fieldName, ignoreCase = true)) continue
        val payload = stripBoundaryNewline(part.copyOfRange(split.second, part.size))
        if (payload.isNotEmpty()) return payload
    }
    return null
}

private fun decodeMultipartFields(body: ByteArray, contentType: String): Map<String, String> {
    val boundary = multipartBoundary(contentType)
    if (boundary.isBlank()) return emptyMap()
    val fields = linkedMapOf<String, String>()
    for (part in splitMultipart(body, "--$boundary".toByteArray())) {
        val split = headerSplit(part) ?: continue
        val header = part.copyOfRange(0, split.first).toString(Charsets.UTF_8)
        if (header.contains("filename=", ignoreCase = true)) continue
        val name = dispositionName(header)
        if (name.isBlank()) continue
        val value = stripBoundaryNewline(part.copyOfRange(split.second, part.size))
            .toString(Charsets.UTF_8)
            .trimEnd('\r', '\n')
        fields[name] = value
    }
    return fields
}

private fun dispositionName(header: String): String {
    val quoted = Regex("name=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(header)?.groupValues?.getOrNull(1)
    if (!quoted.isNullOrBlank()) return quoted
    return Regex("name=([^;\\s]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.getOrNull(1).orEmpty()
}

private fun headerSplit(part: ByteArray): Pair<Int, Int>? {
    val crlf = indexOf(part, "\r\n\r\n".toByteArray())
    if (crlf >= 0) return crlf to (crlf + 4)
    val lf = indexOf(part, "\n\n".toByteArray())
    if (lf >= 0) return lf to (lf + 2)
    return null
}

private fun stripBoundaryNewline(bytes: ByteArray): ByteArray {
    var end = bytes.size
    if (end >= 2 && bytes[end - 2] == '\r'.code.toByte() && bytes[end - 1] == '\n'.code.toByte()) end -= 2
    else if (end >= 1 && bytes[end - 1] == '\n'.code.toByte()) end -= 1
    return if (end == bytes.size) bytes else bytes.copyOf(end)
}

private fun splitMultipart(body: ByteArray, marker: ByteArray): List<ByteArray> {
    val parts = mutableListOf<ByteArray>()
    var start = indexOf(body, marker, 0)
    if (start < 0) return parts
    start += marker.size
    while (start < body.size) {
        // The closing boundary is "--" plus the marker. There is no part after it.
        if (body[start] == '-'.code.toByte()) break
        val next = indexOf(body, marker, start)
        val end = if (next < 0) body.size else next
        if (end > start) parts.add(body.copyOfRange(start, end))
        if (next < 0) break
        start = next + marker.size
    }
    return parts
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    for (i in from..haystack.size - needle.size) {
        var ok = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                ok = false
                break
            }
        }
        if (ok) return i
    }
    return -1
}

private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
    if (size < suffix.size) return false
    for (i in suffix.indices) {
        if (this[size - suffix.size + i] != suffix[i]) return false
    }
    return true
}
