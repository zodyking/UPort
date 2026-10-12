package app.apt443.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPermissionsTest {
    @Test
    fun fireTvFullScreenReadyWithoutOverlay() {
        val state = fireTv(overlay = false, accessibility = false)
        assertTrue(state.alertsReady)
        assertEquals(AlertDeliveryMode.FullScreen, state.deliveryMode)
    }

    @Test
    fun needsSetupWhenNotificationsMissing() {
        val state = fireTv(overlay = false, accessibility = false, notifications = false)
        assertFalse(state.alertsReady)
        assertEquals(AlertDeliveryMode.NeedsSetup, state.deliveryMode)
    }

    @Test
    fun cornerOverlayPreferredWhenGranted() {
        assertEquals(
            AlertDeliveryMode.CornerOverlay,
            fireTv(overlay = true, accessibility = false).deliveryMode,
        )
    }

    @Test
    fun accessibilityOverlayWinsAndMakesCornerAlertsReady() {
        val state = fireTv(overlay = false, accessibility = true)
        assertEquals(AlertDeliveryMode.AccessibilityOverlay, state.deliveryMode)
        assertTrue(state.cornerOverlayReady)
        // Even with the system overlay also granted, the accessibility window is the one used.
        assertEquals(
            AlertDeliveryMode.AccessibilityOverlay,
            fireTv(overlay = true, accessibility = true).deliveryMode,
        )
    }

    @Test
    fun onlyNonFireDevicesAreOfferedTheOverlayPermission() {
        assertFalse(fireTv(overlay = false, accessibility = false).overlayGrantable)
        val other = fireTv(overlay = false, accessibility = false).copy(isFireTv = false)
        assertTrue(other.overlayGrantable)
    }

    @Test
    fun accessibilityOverlayStillReadyWithoutNotifications() {
        val state = fireTv(overlay = false, accessibility = true, notifications = false)
        assertTrue(state.alertsReady)
        assertEquals(AlertDeliveryMode.AccessibilityOverlay, state.deliveryMode)
    }

    private fun fireTv(
        overlay: Boolean,
        accessibility: Boolean,
        notifications: Boolean = true,
    ) = AlertPermissionState(
        overlay = overlay,
        accessibility = accessibility,
        notifications = notifications,
        fullScreenIntent = true,
        isFireTv = true,
    )
}
