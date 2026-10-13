package app.apt443.data.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import app.apt443.tailscale.PhysicalNetwork
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fires when Wi-Fi or Ethernet changes. The VPN itself is ignored, so Tailscale
 * coming up does not look like the TV switched networks.
 */
class LinkWatch(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ConnectivityManager::class.java)
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()
    private var signature = PhysicalNetwork.signature(appContext)

    fun start() {
        val connectivity = manager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = publish()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish()
        }
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { error -> Log.w(TAG, "Network watch was not registered", error) }
    }

    private fun publish() {
        val next = PhysicalNetwork.signature(appContext)
        if (next == signature) return
        signature = next
        _changes.tryEmit(Unit)
    }

    private companion object {
        const val TAG = "UPortLink"
    }
}
