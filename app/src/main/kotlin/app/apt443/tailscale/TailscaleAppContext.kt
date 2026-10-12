package app.apt443.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import app.apt443.data.settings.KeystoreSecretStore
import libtailscale.AppContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/** Bridges the Tailscale engine to this app. Hardware attestation, Taildrop, and log upload stay off. */
class TailscaleAppContext(
    context: Context,
    private val prefs: KeystoreSecretStore,
) : AppContext {
    private val appContext = context.applicationContext

    override fun log(tag: String?, line: String?) {
        Log.d(tag ?: "Tailscale", line.orEmpty())
    }

    override fun encryptToPref(key: String?, value: String?) {
        if (key.isNullOrBlank() || value == null) return
        prefs.putNow(key, value)
    }

    override fun decryptFromPref(key: String?): String? {
        if (key.isNullOrBlank()) return ""
        return prefs.getNow(key).orEmpty()
    }

    override fun getStateStoreKeysJSON(): String {
        val prefix = "statestore-"
        val names = prefs.keysNow().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        return JSONArray(names).toString()
    }

    override fun getOSVersion(): String = Build.VERSION.RELEASE ?: ""

    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

    override fun getDeviceName(): String {
        val global = runCatching {
            android.provider.Settings.Global.getString(appContext.contentResolver, "device_name")
        }.getOrNull()
        if (!global.isNullOrBlank()) return global
        return listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "UPort TV" }
    }

    override fun getInstallSource(): String = "sideload"

    override fun shouldUseGoogleDNSFallback(): Boolean = false

    override fun isChromeOS(): Boolean = false

    override fun isClientLoggingEnabled(): Boolean = false

    override fun getInterfacesAsJson(): String {
        val list = JSONArray()
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        for (nif in interfaces) {
            try {
                val addrs = JSONArray()
                for (ia in nif.interfaceAddresses) {
                    val host = ia.address?.hostAddress ?: continue
                    addrs.put(JSONObject().put("ip", host).put("prefixLen", ia.networkPrefixLength.toInt()))
                }
                list.put(
                    JSONObject()
                        .put("name", nif.name)
                        .put("index", nif.index)
                        .put("mtu", nif.mtu)
                        .put("up", nif.isUp)
                        .put("broadcast", nif.supportsMulticast())
                        .put("loopback", nif.isLoopback)
                        .put("pointToPoint", nif.isPointToPoint)
                        .put("multicast", nif.supportsMulticast())
                        .put("addrs", addrs),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return list.toString()
    }

    override fun getPlatformDNSConfig(): String {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return ""
        val network = PhysicalNetwork.pick(appContext) ?: return ""
        val props = manager.getLinkProperties(network) ?: return ""
        val servers = props.dnsServers.mapNotNull { it.hostAddress }.joinToString(" ")
        val domains = props.domains.orEmpty()
        return "$servers\n$domains".trim()
    }

    override fun getSyspolicyStringValue(key: String?): String = throw noPolicy()

    override fun getSyspolicyBooleanValue(key: String?): Boolean = throw noPolicy()

    override fun getSyspolicyStringArrayJSONValue(key: String?): String = throw noPolicy()

    override fun hardwareAttestationKeySupported(): Boolean = false

    override fun hardwareAttestationKeyCreate(): String = throw unsupportedHardware()

    override fun hardwareAttestationKeyRelease(id: String?) = throw unsupportedHardware()

    override fun hardwareAttestationKeyPublic(id: String?): ByteArray = throw unsupportedHardware()

    override fun hardwareAttestationKeySign(id: String?, data: ByteArray?): ByteArray = throw unsupportedHardware()

    override fun hardwareAttestationKeyLoad(id: String?) = throw unsupportedHardware()

    override fun bindSocketToNetwork(fd: Int): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = PhysicalNetwork.pick(appContext) ?: return false
        val parcel = ParcelFileDescriptor.fromFd(fd)
        return try {
            network.bindSocket(parcel.fileDescriptor)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { parcel.detachFd() }
        }
    }

    override fun getUserCACertsPEM(): ByteArray = ByteArray(0)

    private fun noPolicy(): Exception = Exception("no such key")

    private fun unsupportedHardware(): Exception = Exception("hardware-backed keys are not supported on this device")
}
