package app.apt443.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.apt443.Apt443Application

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in WAKE_ACTIONS) {
            (context.applicationContext as? Apt443Application)?.refreshStayAwake()
            AlertOverlayService.start(context)
        }
    }

    companion object {
        const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        const val HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"

        val WAKE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_DREAMING_STOPPED,
            QUICKBOOT,
            HTC_QUICKBOOT,
        )
    }
}
