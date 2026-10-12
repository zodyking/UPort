package app.apt443.screensaver

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Fire OS keeps a separate sleep timer from the screensaver. With [keepTvAwake] on, sleep is
 * turned off. With [cameraScreensaver] on, the camera wall starts after a few minutes with no
 * remote instead of Amazon's screensaver. Writing these settings needs WRITE_SECURE_SETTINGS,
 * which only ADB can grant; failures are logged and never thrown.
 */
object StayAwake {
    const val IDLE_BEFORE_CAMERAS_MS = 300_000

    /** Fire TV's stock sleep timer, restored when keep awake is turned off. */
    const val DEFAULT_SLEEP_TIMEOUT_MS = 840_000

    private const val AMAZON_SCREENSAVER = "com.amazon.ftv.screensaver/.app.services.ScreensaverService"

    /** Last applied screensaver choice. [CameraDream] reads it before the saved settings load. */
    @Volatile
    var cameraScreensaverOn: Boolean = true
        private set

    fun apply(context: Context, keepTvAwake: Boolean, cameraScreensaver: Boolean) {
        cameraScreensaverOn = cameraScreensaver
        val resolver = context.contentResolver
        resolver.secureInt("sleep_timeout", if (keepTvAwake) 0 else DEFAULT_SLEEP_TIMEOUT_MS)
        val component = ComponentName(context, CameraDream::class.java)
        val dream = component.flattenToString()
        val ours = setOf(dream, component.flattenToShortString())
        if (cameraScreensaver) {
            resolver.secureInt("amazon_ambient_enabled", 0)
            resolver.secureInt("screensaver_enabled", 1)
            resolver.secureInt("screensaver_activate_on_sleep", 1)
            resolver.secureInt("screensaver_activate_on_dock", 1)
            resolver.secureString("screensaver_components", dream)
            resolver.secureString("screensaver_default_component", dream)
            resolver.secureInt("contextual_screen_off_timeout", IDLE_BEFORE_CAMERAS_MS)
            runCatching { Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, IDLE_BEFORE_CAMERAS_MS) }
                .onFailure { Log.w(TAG, "Could not write screen_off_timeout", it) }
        } else if (isAmazon()) {
            resolver.secureString("screensaver_components", AMAZON_SCREENSAVER)
            resolver.secureString("screensaver_default_component", AMAZON_SCREENSAVER)
        } else {
            resolver.clearIfOurs("screensaver_components", ours)
            resolver.clearIfOurs("screensaver_default_component", ours)
        }
    }

    private fun isAmazon(): Boolean = Build.MANUFACTURER.equals("amazon", ignoreCase = true)

    private fun ContentResolver.secureInt(name: String, value: Int) {
        runCatching {
            if (Settings.Secure.getInt(this, name, Int.MIN_VALUE) != value) Settings.Secure.putInt(this, name, value)
        }.onFailure { Log.w(TAG, "Could not write $name", it) }
    }

    private fun ContentResolver.secureString(name: String, value: String?) {
        runCatching {
            if (Settings.Secure.getString(this, name) != value) Settings.Secure.putString(this, name, value)
        }.onFailure { Log.w(TAG, "Could not write $name", it) }
    }

    private fun ContentResolver.clearIfOurs(name: String, ours: Set<String>) {
        val current = runCatching { Settings.Secure.getString(this, name) }.getOrNull() ?: return
        val listed = current.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val kept = listed.filterNot { it in ours }
        if (kept.size == listed.size) return
        secureString(name, kept.joinToString(",").ifEmpty { null })
    }

    private const val TAG = "StayAwake"
}
