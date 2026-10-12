package app.apt443.alert

import android.content.Context
import kotlinx.coroutines.delay

enum class AlertVerifyResult {
    Running,
    CornerOverlay,
    FullScreenReady,
    InAppOnly,
    NeedsPermission,
}

object AlertSetupVerifier {
    suspend fun verify(context: Context, triggerPreview: suspend () -> Unit): AlertVerifyResult {
        val before = AlertPermissions.read(context)
        if (!before.alertsReady) return AlertVerifyResult.NeedsPermission

        triggerPreview()
        delay(VERIFY_WAIT_MS)

        val after = AlertPermissions.read(context)
        val alertVisible = AlertController.visible.value != null
        return when {
            !alertVisible -> AlertVerifyResult.NeedsPermission
            after.cornerOverlayReady -> AlertVerifyResult.CornerOverlay
            AptVisibility.mainResumed -> AlertVerifyResult.InAppOnly
            after.fullScreenAlertsReady -> AlertVerifyResult.FullScreenReady
            else -> AlertVerifyResult.NeedsPermission
        }
    }

    fun message(result: AlertVerifyResult): String = when (result) {
        AlertVerifyResult.Running -> "Testing alert…"
        AlertVerifyResult.CornerOverlay ->
            "Verified. Alerts over other apps work while Netflix or live TV is playing."
        AlertVerifyResult.FullScreenReady ->
            "Verified. Alerts will open full screen over other apps."
        AlertVerifyResult.InAppOnly ->
            "Alert works inside UPort. Popups over other apps still need the alert check."
        AlertVerifyResult.NeedsPermission ->
            "The test alert could not be confirmed. Use the alert check on this page."
    }

    private const val VERIFY_WAIT_MS = 1_500L
}
