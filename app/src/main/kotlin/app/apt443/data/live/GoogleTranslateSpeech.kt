package app.apt443.data.live

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Free Google Translate speech, the same engine Home Assistant's gTTS provider uses.
 * Language is English on translate.google.com. No cloud API key.
 */
class GoogleTranslateSpeech(private val context: Context) {
    private val cookies = MemorySpeechCookies()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** One spoken line at a time. Doorbell Say and TV voice alerts share this turn. */
    suspend fun <T> oneAtATime(block: suspend () -> T): T = speechTurn.withLock { block() }

    suspend fun mp3(text: String): ByteArray = withContext(Dispatchers.IO) {
        val line = text.trim().take(180)
        if (line.isEmpty()) error("Choose a message first.")
        val file = File(context.cacheDir, "gtts-${line.hashCode().toUInt().toString(16)}.mp3")
        if (file.length() > 200) return@withContext file.readBytes()
        val bytes = runCatching { fetch(line) }.getOrElse {
            warmup()
            fetch(line)
        }
        file.writeBytes(bytes)
        bytes
    }

    suspend fun playOnTv(text: String) = oneAtATime { playUnlocked(text) }

    private suspend fun playUnlocked(text: String) {
        val bytes = mp3(text)
        val file = File(context.cacheDir, "apt443-voice-now.mp3")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                cont.invokeOnCancellation { runCatching { player.release() } }
                player.setOnCompletionListener {
                    it.release()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { media, _, _ ->
                    media.release()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                try {
                    player.setDataSource(file.absolutePath)
                    player.prepare()
                    player.start()
                } catch (error: Exception) {
                    player.release()
                    if (cont.isActive) cont.resumeWithException(error)
                }
            }
        }
    }

    private fun fetch(line: String): ByteArray {
        val encoded = URLEncoder.encode(line, "UTF-8")
        val url = "https://translate.google.com/translate_tts" +
            "?ie=UTF-8&q=$encoded&tl=en&total=1&idx=0&textlen=${line.length}&client=tw-ob&ttsspeed=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://translate.google.com/")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Google speech HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: error("Google speech returned no audio.")
            if (bytes.size < 200) error("Google speech returned no audio.")
            return bytes
        }
    }

    private fun warmup() {
        val request = Request.Builder()
            .url("https://translate.google.com/")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    private companion object {
        /** Shared across every [GoogleTranslateSpeech] so Say and voice alerts never overlap. */
        val speechTurn = Mutex()
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

private class MemorySpeechCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain } }
        this.cookies.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
}
