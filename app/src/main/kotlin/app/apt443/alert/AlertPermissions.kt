package app.apt443.alert

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import app.apt443.ui.components.TvDevice
import app.apt443.ui.components.canDrawOverlays

/** How alerts can appear when another app is on screen. */
enum class AlertDeliveryMode {
    /** Floating corner card from an enabled accessibility overlay. Works on Fire TV. */
    AccessibilityOverlay,
    /** Floating corner card via SYSTEM_ALERT_WINDOW. */
    CornerOverlay,
    /** Full-screen alert activity plus notification fallback. */
    FullScreen,
    /** Needs notification or full-screen intent permission. */
    NeedsSetup,
}

data class AlertPermissionState(
    val overlay: Boolean,
    val accessibility: Boolean,
    val notifications: Boolean,
    val fullScreenIntent: Boolean,
    val isFireTv: Boolean,
) {
    val cornerOverlayReady: Boolean get() = accessibility || overlay

    val fullScreenAlertsReady: Boolean
        get() = notifications && (fullScreenIntent || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

    val alertsReady: Boolean get() = cornerOverlayReady || fullScreenAlertsReady

    /**
     * True when the system offers no way to grant "draw over other apps". Fire TV ships without
     * that Settings screen, so the accessibility overlay is the only corner-alert route there.
     */
    val overlayGrantable: Boolean get() = !isFireTv

    val deliveryMode: AlertDeliveryMode = when {
        accessibility -> AlertDeliveryMode.AccessibilityOverlay
        overlay -> AlertDeliveryMode.CornerOverlay
        fullScreenAlertsReady -> AlertDeliveryMode.FullScreen
        else -> AlertDeliveryMode.NeedsSetup
    }
}

object AlertPermissions {
    fun read(context: Context): AlertPermissionState = AlertPermissionState(
        overlay = context.canDrawOverlays(),
        accessibility = Apt443AccessibilityService.enabled(context),
        notifications = context.notificationsGranted(),
        fullScreenIntent = context.fullScreenIntentGranted(),
        isFireTv = TvDevice.isFireTv(),
    )

    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun fullScreenIntentGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return NotificationManagerCompat.from(context).canUseFullScreenIntent()
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openOverlaySettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intents = buildList {
            add(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        for (intent in intents) {
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                runCatching {
                    context.startActivity(intent)
                    return
                }
            }
        }
    }

    /**
     * Opens the accessibility list, where the viewer switches "UPort alerts" on. This is the
     * route that works on every TV, including the ones with no overlay permission screen.
     */
    /** True when the OS lists this app's accessibility service as installable. */
    fun accessibilityServiceListed(context: Context): Boolean {
        val intent = Intent(AccessibilityService.SERVICE_INTERFACE)
        val services = context.packageManager.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return services.any { it.serviceInfo.packageName == context.packageName }
    }

    /** One-line ADB command to enable the service when Fire TV hides it from Settings. */
    fun fireTvAdbEnableCommand(): String =
        "adb shell settings put secure enabled_accessibility_services " +
            "app.apt443/app.apt443.alert.Apt443AccessibilityService"

    fun openAccessibilitySettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /** Opens the most useful settings screen for this device without adb. */
    fun openBestSetupScreen(context: Context) {
        val state = read(context)
        when {
            !state.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                runCatching {
                    val activity = context as? android.app.Activity
                    activity?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                }
            }
            !state.fullScreenIntent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                openFullScreenIntentSettings(context)
            }
            state.overlayGrantable && !state.overlay -> openOverlaySettings(context)
            else -> openAccessibilitySettings(context)
        }
    }

    fun statusLine(state: AlertPermissionState): String = when (state.deliveryMode) {
        AlertDeliveryMode.AccessibilityOverlay ->
            "Alerts over other apps are on. They appear while Netflix or live TV is playing."
        AlertDeliveryMode.CornerOverlay -> "Alerts over other apps are enabled."
        AlertDeliveryMode.FullScreen ->
            "Alerts take over the whole screen. Turn on alerts over other apps below to keep watching."
        AlertDeliveryMode.NeedsSetup -> when {
            !state.notifications -> "Allow notifications so doorbell alerts can appear."
            !state.fullScreenIntent -> "Allow full-screen alerts so doorbell calls can appear over other apps."
            state.isFireTv -> "Turn on UPort alerts under Accessibility for popups over other apps."
            else -> "Finish alert setup to show doorbell calls over other apps."
        }
    }

    fun nextSetupAction(state: AlertPermissionState): String = when {
        !state.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            "Allow notifications"
        !state.fullScreenIntent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            "Allow full-screen alerts"
        else -> "Turn on alerts over other apps"
    }

    /** The on-screen steps for the accessibility toggle, in the order the viewer will see them. */
    fun accessibilitySteps(): List<String> = listOf(
        "Open Settings, then Accessibility.",
        "Pick UPort alerts. It may sit under Downloaded apps or Services.",
        "Switch it On and confirm.",
        "Come back here and press Test alert.",
    )

    const val REQUEST_NOTIFICATIONS = 444
}

private fun Context.notificationsGranted(): Boolean = AlertPermissions.notificationsGranted(this)

private fun Context.fullScreenIntentGranted(): Boolean = AlertPermissions.fullScreenIntentGranted(this)
