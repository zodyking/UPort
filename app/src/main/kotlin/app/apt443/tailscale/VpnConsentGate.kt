package app.apt443.tailscale

import android.content.Context
import android.net.VpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One system VPN-permission dialog. Later starts reuse the grant. */
class VpnConsentGate(context: Context) {
    private val appContext = context.applicationContext
    private val gate = Mutex()
    private val _request = MutableStateFlow<CompletableDeferred<Boolean>?>(null)
    val request: StateFlow<CompletableDeferred<Boolean>?> = _request

    fun prepared(): Boolean = VpnService.prepare(appContext) == null

    suspend fun awaitPrepared(): Boolean {
        if (prepared()) return true
        return gate.withLock {
            if (prepared()) return@withLock true
            val waiter = CompletableDeferred<Boolean>()
            _request.value = waiter
            try {
                withTimeoutOrNull(CONSENT_TIMEOUT_MS) { waiter.await() } == true && prepared()
            } finally {
                if (_request.value === waiter) _request.value = null
            }
        }
    }

    fun complete(granted: Boolean) {
        _request.value?.complete(granted)
    }

    private companion object {
        const val CONSENT_TIMEOUT_MS = 180_000L
    }
}
