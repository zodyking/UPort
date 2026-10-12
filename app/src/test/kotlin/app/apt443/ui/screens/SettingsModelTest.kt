package app.apt443.ui.screens

import app.apt443.domain.AlertCorner
import app.apt443.domain.AppSettings
import app.apt443.domain.Camera
import app.apt443.domain.CameraAlertPrefs
import app.apt443.domain.DetectionType
import app.apt443.ui.player.StreamPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    private val doorbell = Camera("door", "Front Door", online = true, isDoorbell = true, model = "G4 Doorbell")
    private val bullet = Camera("yard", "Back Yard", online = true, isDoorbell = false, model = "G5 Bullet")

    @Test
    fun doorbellChipsIncludeRingButNeverSensor() {
        val chips = detectionChipsFor(doorbell)
        assertTrue(DetectionType.Ring in chips)
        assertFalse(DetectionType.Sensor in chips)
    }

    @Test
    fun cameraChipsHideRingAndSensor() {
        val chips = detectionChipsFor(bullet)
        assertFalse(DetectionType.Ring in chips)
        assertFalse(DetectionType.Sensor in chips)
        assertTrue(DetectionType.Person in chips)
    }

    @Test
    fun defaultChipsOfferEveryType() {
        assertEquals(DetectionType.entries.toList(), defaultDetectionChips())
    }

    @Test
    fun toggledFlipsOneDetection() {
        val prefs = CameraAlertPrefs()
        val off = prefs.toggled(DetectionType.Person)
        assertFalse(off.allows(DetectionType.Person))
        assertTrue(off.allows(DetectionType.Vehicle))
        assertTrue(off.toggled(DetectionType.Person).allows(DetectionType.Person))
    }

    @Test
    fun overrideSummaryIsNullWhenIdenticalToDefaults() {
        val defaults = CameraAlertPrefs()
        assertNull(overrideSummary(defaults, defaults, detectionChipsFor(bullet)))
    }

    @Test
    fun overrideSummaryNamesSingleChanges() {
        val defaults = CameraAlertPrefs()
        val prefs = CameraAlertPrefs(popups = false)
        assertEquals("Popups off", overrideSummary(prefs, defaults, detectionChipsFor(bullet)))

        val vehicleOff = defaults.toggled(DetectionType.Vehicle)
        assertEquals("Vehicle off", overrideSummary(vehicleOff, defaults, detectionChipsFor(bullet)))
    }

    @Test
    fun overrideSummaryCountsMultipleDetectionChanges() {
        val defaults = CameraAlertPrefs()
        val prefs = defaults.toggled(DetectionType.Vehicle).toggled(DetectionType.Animal).copy(voice = false)
        assertEquals("Voice off, 2 detections changed", overrideSummary(prefs, defaults, detectionChipsFor(bullet)))
    }

    @Test
    fun overrideSummaryIgnoresDetectionsNotOfferedForCamera() {
        val defaults = CameraAlertPrefs()
        val prefs = defaults.toggled(DetectionType.Ring)
        assertNull(overrideSummary(prefs, defaults, detectionChipsFor(bullet)))
        assertEquals("Doorbell ring off", overrideSummary(prefs, defaults, detectionChipsFor(doorbell)))
    }

    @Test
    fun cameraAlertRowReportsOverrideState() {
        val settings = AppSettings(cameraAlertPrefs = mapOf("door" to CameraAlertPrefs(voice = false)))
        val overridden = cameraAlertRow(doorbell, settings)
        assertTrue(overridden.hasOverride)
        assertEquals("Voice off", overridden.summary)

        val inherited = cameraAlertRow(bullet, settings)
        assertFalse(inherited.hasOverride)
        assertNull(inherited.summary)
        assertEquals(settings.defaultAlertPrefs, inherited.prefs)
    }

    @Test
    fun cornerGridMapsSevenPlacements() {
        val found = (0..2).flatMap { row -> (0..2).mapNotNull { column -> cornerAt(row, column) } }
        assertEquals(AlertCorner.entries.toSet(), found.toSet())
        assertEquals(7, found.size)
        assertNull(cornerAt(1, 0))
        assertNull(cornerAt(1, 2))
    }

    @Test
    fun everyCornerHasALabel() {
        AlertCorner.entries.forEach { assertTrue(it.label().isNotBlank()) }
    }

    @Test
    fun tileStatusFollowsPhaseUnlessOffline() {
        assertEquals(TileStatus.Offline, tileStatus(StreamPhase.Live, online = false))
        assertEquals(TileStatus.Connecting, tileStatus(null, online = true))
        assertEquals(TileStatus.Live, tileStatus(StreamPhase.Live, online = true))
        assertEquals(TileStatus.Reconnecting, tileStatus(StreamPhase.Reconnecting, online = true))
        assertEquals(TileStatus.Snapshots, tileStatus(StreamPhase.SnapshotOnly, online = true))
        assertEquals(TileStatus.Unavailable, tileStatus(StreamPhase.Failed, online = true))
    }

    @Test
    fun gridColumnsScaleWithWidth() {
        assertEquals(2, gridColumnsFor(600))
        assertEquals(3, gridColumnsFor(800))
        assertEquals(4, gridColumnsFor(1300))
    }
}
