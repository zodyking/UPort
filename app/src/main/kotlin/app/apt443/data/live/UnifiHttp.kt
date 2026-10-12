package app.apt443.data.live

import app.apt443.domain.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class HttpText(val code: Int, val body: String, val headers: Map<String, String>)

data class HttpBytes(val code: Int, val bytes: ByteArray, val contentType: String?)

data class HttpDownload(val code: Int, val contentType: String?, val bytes: Long)

sealed interface ProbeResult {
    data object Reachable : ProbeResult
    data class Untrusted(val message: String) : ProbeResult
    data class Unreachable(val reason: String = "") : ProbeResult
}

/**
 * Shared cookie jar for the unofficial UniFi OS session used by playback.
 * Integration API calls use an API key and do not need this jar.
 */
class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val existing = store[host].orEmpty().associateBy { it.name }.toMutableMap()
        cookies.forEach { existing[it.name] = it }
        store[host] = existing.values.toList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host].orEmpty().filter { it.matches(url) }
    }

    fun headerFor(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
    }
}

/**
 * HTTPS client for one console. Trust-on-demand is limited to the configured
 * host and is off unless Settings enables it. Do not use this for public sites.
 */
class UnifiHttp {
    val cookies = MemoryCookieJar()

    private val strictClient = baseBuilder().build()
    private val trustedByHost = ConcurrentHashMap<String, OkHttpClient>()
    private val probeByKey = ConcurrentHashMap<String, OkHttpClient>()

    private fun baseBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .cookieJar(cookies)
            // UniFi OS cookie login can abort as HTTP 499 over HTTP/2.
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
    }

    fun clientFor(settings: AppSettings): OkHttpClient {
        val host = consoleHost(settings)
        if (!settings.trustConsoleCertificate || host.isBlank()) return strictClient
        return trustedHostClient(host)
    }

    suspend fun probe(settings: AppSettings): ProbeResult = withContext(Dispatchers.IO) {
        val host = consoleHost(settings)
        if (host.isBlank()) return@withContext ProbeResult.Unreachable("Enter a console address.")
        val url = "https://$host/proxy/network/integration/v1/info"
        try {
            val response = probeClient(settings)
                .newCall(Request.Builder().url(url).get().build())
                .execute()
            response.close()
            // 401/403 still means the console answered.
            ProbeResult.Reachable
        } catch (ssl: javax.net.ssl.SSLException) {
            ProbeResult.Untrusted(
                "The console answered, but its certificate is not trusted. " +
                    "Enable trust for this console in Settings, or install its certificate.",
            )
        } catch (error: Exception) {
            ProbeResult.Unreachable(probeFailure(error))
        }
    }

    suspend fun text(
        settings: AppSettings,
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
    ): Result<HttpText> = withContext(Dispatchers.IO) {
        execute(settings, url, method, headers, jsonBody).mapCatching { response ->
            response.use {
                val headerMap = buildMap {
                    for (i in 0 until it.headers.size) {
                        put(it.headers.name(i), it.headers.value(i))
                    }
                }
                HttpText(it.code, it.body?.string().orEmpty(), headerMap)
            }
        }
    }

    /**
     * Status check that closes the body without reading it.
     * Used so a recording export is not pulled onto the TV just to see if it exists.
     */
    suspend fun status(
        settings: AppSettings,
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<Int> = withContext(Dispatchers.IO) {
        execute(settings, url, method, headers, null).mapCatching { response ->
            response.use { it.code }
        }
    }

    suspend fun bytes(
        settings: AppSettings,
        url: String,
        headers: Map<String, String>,
    ): Result<HttpBytes> = withContext(Dispatchers.IO) {
        execute(settings, url, "GET", headers, null).mapCatching { response ->
            response.use {
                HttpBytes(
                    code = it.code,
                    bytes = it.body?.bytes() ?: ByteArray(0),
                    contentType = it.body?.contentType()?.toString(),
                )
            }
        }
    }

    /**
     * Streams a response body straight to [target]. Recording exports are muxed while they
     * download, so they arrive chunked with no content length; [onProgress] reports a total of
     * -1 in that case. The body is never held in memory.
     */
    suspend fun download(
        settings: AppSettings,
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<HttpDownload> = withContext(Dispatchers.IO) {
        execute(settings, url, "GET", headers, null).mapCatching { response ->
            response.use { answer ->
                val contentType = answer.body?.contentType()?.toString()
                val body = answer.body
                if (answer.code !in 200..299 || body == null) {
                    HttpDownload(answer.code, contentType, 0L)
                } else {
                    val total = body.contentLength()
                    target.parentFile?.mkdirs()
                    var read = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(STREAM_BUFFER)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                read += count
                                onProgress(read, total)
                            }
                        }
                    }
                    HttpDownload(answer.code, contentType, read)
                }
            }
        }
    }

    fun openWebSocket(
        settings: AppSettings,
        url: String,
        headers: Map<String, String>,
        listener: WebSocketListener,
    ): WebSocket {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return clientFor(settings).newWebSocket(builder.build(), listener)
    }

    private fun execute(
        settings: AppSettings,
        url: String,
        method: String,
        headers: Map<String, String>,
        jsonBody: String?,
    ): Result<Response> {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            val body = jsonBody?.toRequestBody(JSON)
            builder.method(method, if (method == "GET" || method == "HEAD") null else body ?: "{}".toRequestBody(JSON))
            Result.success(clientFor(settings).newCall(builder.build()).execute())
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * One client per host. Building a client allocates a connection pool and dispatcher;
     * doing that on every console call dropped keep-alive and leaked idle threads.
     * Trust is still limited to this host.
     */
    private fun trustedHostClient(host: String): OkHttpClient {
        val bareHost = host.substringBefore(':')
        return trustedByHost.computeIfAbsent(bareHost) { trusted ->
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            baseBuilder()
                .sslSocketFactory(context.socketFactory, trust)
                .hostnameVerifier { hostname, _ ->
                    hostname.equals(trusted, ignoreCase = true)
                }
                .build()
        }
    }

    /** Reachability checks share one short-timeout client instead of allocating one per probe. */
    private fun probeClient(settings: AppSettings): OkHttpClient {
        val host = consoleHost(settings)
        val key = host + "\u0000" + settings.trustConsoleCertificate
        return probeByKey.computeIfAbsent(key) {
            clientFor(settings).newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
        }
    }

    private fun probeFailure(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "The connection timed out."
        is java.net.ConnectException -> "The connection was refused."
        is java.net.NoRouteToHostException,
        is java.net.UnknownHostException,
        -> "There is no route to that address."
        else -> if (error.javaClass.simpleName.contains("timeout", ignoreCase = true)) {
            "The connection timed out."
        } else {
            "The connection failed."
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val STREAM_BUFFER = 64 * 1024
    }
}

fun consoleHost(settings: AppSettings): String {
    return settings.consoleHost
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
}

fun apiKeyHeader(key: String): Map<String, String> {
    return mapOf(
        "Accept" to "application/json",
        "X-API-Key" to key,
    )
}

/** Picture bytes. JSON Accept makes Protect answer with a document instead of the JPEG. */
fun imageKeyHeader(key: String): Map<String, String> {
    return mapOf(
        "Accept" to "image/jpeg, */*",
        "X-API-Key" to key,
    )
}
