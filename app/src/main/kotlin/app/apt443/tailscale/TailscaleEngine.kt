package app.apt443.tailscale

import android.content.Context
import android.util.Log
import app.apt443.data.settings.KeystoreSecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libtailscale.Application
import libtailscale.InputStream
import libtailscale.Libtailscale
import libtailscale.ShareFileHelper
import org.json.JSONObject
import java.io.ByteArrayInputStream

class TailscaleEngine(
    context: Context,
    private val prefs: KeystoreSecretStore,
) {
    private val appContext = context.applicationContext
    val consent = VpnConsentGate(appContext)
    private val gate = Mutex()
    private val state = MutableStateFlow(-1)
    private var backend: Application? = null
    private val bridge = TailscaleAppContext(appContext, prefs)

    suspend fun ensureRunning(
        authKey: String,
        loginServer: String,
        askPermission: Boolean,
        replaceExisting: Boolean = false,
    ): Result<Unit> {
        if (askPermission) {
            if (!consent.awaitPrepared()) return Result.failure(IllegalStateException(TailscaleLogin.PERMISSION_DENIED))
        } else if (!consent.prepared()) {
            return Result.failure(IllegalStateException(TailscaleLogin.PERMISSION_DENIED))
        }
        return gate.withLock {
            val alreadyUp = state.value == TailscaleLogin.STATE_RUNNING && TailscaleVpnService.established.get()
            if (alreadyUp && !replaceExisting) {
                return@withLock Result.success(Unit)
            }
            runCatching { startBackend() }.getOrElse { error ->
                return@withLock Result.failure(IllegalStateException(TailscaleLogin.safeFailure(error, authKey)))
            }
            TailscaleVpnService.start(appContext)
            val loggedIn = runCatching { login(authKey, loginServer) }
            if (loggedIn.isFailure) {
                return@withLock Result.failure(
                    IllegalStateException(TailscaleLogin.safeFailure(loggedIn.exceptionOrNull()?.message, authKey)),
                )
            }
            awaitRunning()
        }
    }

    suspend fun logout() {
        gate.withLock {
            runCatching { call("POST", "/localapi/v0/logout", null) }
            TailscaleVpnService.stop(appContext)
            prefs.clearAllNow()
            state.value = -1
        }
    }

    fun tunnelUp(): Boolean = state.value == TailscaleLogin.STATE_RUNNING && TailscaleVpnService.established.get()

    private fun startBackend() {
        if (backend != null) return
        val dir = appContext.filesDir.resolve("tailscale").apply { mkdirs() }.absolutePath
        val app = Libtailscale.start(dir, dir, false, bridge)
        backend = app
        Libtailscale.setShareFileHelper(UnusedFiles)
        app.watchNotifications(TailscaleLogin.NOTIFY_INITIAL_STATE) { notification ->
            val json = if (notification == null) "" else String(notification, Charsets.UTF_8)
            TailscaleLogin.stateFromNotify(json)?.let { state.value = it }
        }
    }

    private fun login(authKey: String, loginServer: String) {
        val edited = call("PATCH", "/localapi/v0/prefs", TailscaleLogin.maskedPrefsJson(loginServer))
        val prefsJson = if (edited.trim().startsWith("{")) edited else JSONObject().put("LoggedOut", false).toString()
        call("POST", "/localapi/v0/start", TailscaleLogin.startJson(prefsJson, authKey))
        runCatching { call("POST", "/localapi/v0/login-interactive", null) }
            .onFailure { error -> Log.d(TAG, "login-interactive: ${TailscaleLogin.safeFailure(error.message, authKey)}") }
    }

    private suspend fun awaitRunning(): Result<Unit> {
        val deadline = System.nanoTime() + WAIT_NS
        while (System.nanoTime() < deadline) {
            val current = state.value
            if (current == TailscaleLogin.STATE_NEEDS_MACHINE_AUTH) {
                return Result.failure(IllegalStateException(TailscaleLogin.NEEDS_APPROVAL))
            }
            if (current == TailscaleLogin.STATE_RUNNING && TailscaleVpnService.established.get()) {
                return Result.success(Unit)
            }
            delay(300)
        }
        return when (state.value) {
            TailscaleLogin.STATE_NEEDS_MACHINE_AUTH -> Result.failure(IllegalStateException(TailscaleLogin.NEEDS_APPROVAL))
            TailscaleLogin.STATE_NEEDS_LOGIN -> Result.failure(IllegalStateException(TailscaleLogin.AUTH_REJECTED))
            TailscaleLogin.STATE_RUNNING -> Result.failure(IllegalStateException(TailscaleLogin.TUNNEL_FAILED))
            else -> Result.failure(IllegalStateException(TailscaleLogin.TIMED_OUT))
        }
    }

    private fun call(method: String, endpoint: String, body: String?): String {
        val app = backend ?: error("Tailscale is not started.")
        val stream = body?.let { BytesInput(it.toByteArray(Charsets.UTF_8)) }
        val response = app.callLocalAPI(45_000L, method, endpoint, stream)
        val bytes = response.bodyBytes() ?: ByteArray(0)
        return bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "AptTailscale"
        const val WAIT_NS = 75_000_000_000L
    }
}

private object UnusedFiles : ShareFileHelper {
    override fun openFileWriter(fileName: String?, offset: Long): libtailscale.OutputStream =
        throw Exception("not supported")

    override fun getFileURI(fileName: String?): String = throw Exception("not supported")

    override fun renameFile(oldPath: String?, targetName: String?): String = throw Exception("not supported")

    override fun listFilesJSON(suffix: String?): String = "[]"

    override fun openFileReader(name: String?): InputStream = throw Exception("not supported")

    override fun deleteFile(uri: String?) = throw Exception("not supported")

    override fun getFileInfo(fileName: String?): String = throw Exception("not supported")
}

private class BytesInput(bytes: ByteArray) : InputStream {
    private val source = ByteArrayInputStream(bytes)

    override fun read(): ByteArray? {
        val buffer = ByteArray(4096)
        val count = source.read(buffer)
        if (count < 0) return null
        return buffer.copyOf(count)
    }

    override fun close() {
        source.close()
    }
}
