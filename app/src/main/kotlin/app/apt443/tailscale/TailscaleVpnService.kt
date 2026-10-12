package app.apt443.tailscale

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.apt443.MainActivity
import libtailscale.IPNService
import libtailscale.Libtailscale
import libtailscale.VPNServiceBuilder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TailscaleVpnService : VpnService(), IPNService {
    private val serviceId = UUID.randomUUID().toString()
    private val closed = AtomicBoolean(false)

    override fun id(): String = serviceId

    override fun protect(fd: Int): Boolean = super.protect(fd)

    override fun updateVpnStatus(status: Boolean) {
        established.set(status)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                close()
                return START_NOT_STICKY
            }
            else -> {
                closed.set(false)
                showOngoing()
                Thread({ Libtailscale.requestVPN(this) }, "apt443-tailscale-vpn").start()
                return START_STICKY
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        established.set(false)
        disconnectVPN()
        Thread({ Libtailscale.serviceDisconnect(this) }, "apt443-tailscale-down").start()
    }

    override fun disconnectVPN() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        established.set(false)
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        established.set(false)
        if (!closed.get()) close()
        super.onDestroy()
    }

    override fun newBuilder(): VPNServiceBuilder {
        val builder = Builder()
            .setConfigureIntent(openApp())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setSession("UPort")
        }
        val underlying = PhysicalNetwork.pick(this)
        if (underlying != null) {
            runCatching { builder.setUnderlyingNetworks(arrayOf(underlying)) }
        }
        return TailscaleVpnBuilder(builder)
    }

    private fun showOngoing() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tailscale", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    description = "Tailscale connection for this TV"
                },
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(app.apt443.R.drawable.ic_stat_alert)
            .setContentTitle("Tailscale")
            .setContentText("Connected to Tailscale")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    companion object {
        const val ACTION_START = "app.apt443.tailscale.START"
        const val ACTION_STOP = "app.apt443.tailscale.STOP"
        private const val CHANNEL = "apt443-tailscale"
        private const val NOTIFICATION_ID = 4431

        val established = AtomicBoolean(false)

        fun start(context: Context) {
            val intent = Intent(context, TailscaleVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TailscaleVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

private class TailscaleVpnBuilder(
    private val builder: VpnService.Builder,
) : VPNServiceBuilder {
    override fun setMTU(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun addDNSServer(address: String?) {
        if (!address.isNullOrBlank()) builder.addDnsServer(address)
    }

    override fun addSearchDomain(domain: String?) {
        if (!domain.isNullOrBlank()) builder.addSearchDomain(domain)
    }

    override fun addRoute(address: String?, prefix: Int) {
        if (address.isNullOrBlank()) return
        if (TailscaleRoutes.overlapsLocalLan(address, prefix)) return
        builder.addRoute(address, prefix)
    }

    override fun excludeRoute(address: String?, prefix: Int) {
        if (address.isNullOrBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val ip = java.net.InetAddress.getByName(address)
        builder.excludeRoute(android.net.IpPrefix(ip, prefix))
    }

    override fun addAddress(address: String?, prefix: Int) {
        if (!address.isNullOrBlank()) builder.addAddress(address, prefix)
    }

    override fun establish(): libtailscale.ParcelFileDescriptor? {
        val fd: ParcelFileDescriptor = builder.establish() ?: return null
        return object : libtailscale.ParcelFileDescriptor {
            override fun detach(): Int = fd.detachFd()
        }
    }
}
