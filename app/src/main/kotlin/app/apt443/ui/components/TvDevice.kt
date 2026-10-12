package app.apt443.ui.components

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Fire TV and Fire OS device detection for setup hints and overlay fallbacks. */
object TvDevice {
    fun isFireTv(): Boolean = looksLikeFireTv(Build.MANUFACTURER, Build.MODEL)

    internal fun looksLikeFireTv(manufacturer: String, model: String): Boolean =
        manufacturer.equals("amazon", ignoreCase = true) ||
            model.startsWith("AFT", ignoreCase = true) ||
            model.startsWith("KF", ignoreCase = true)

    fun isAppInForeground(context: Context): Boolean {
        val processes = context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?: return false
        return processes.any {
            it.processName == context.packageName &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }
    }
}
