package app.apt443.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLayoutTest {
    @Test
    fun wideCameraDetectedFromModel() {
        val camera = Camera("1", "Driveway", true, false, "G6 Instant")
        assertTrue(camera.isWideAngle())
        assertEquals(32f / 9f, camera.tileAspectRatio())
    }

    @Test
    fun standardCameraUsesSixteenByNine() {
        val camera = Camera("2", "Lobby", true, false, "G5 Bullet")
        assertFalse(camera.isWideAngle())
        assertEquals(16f / 9f, camera.tileAspectRatio())
    }

    @Test
    fun displayLabelSanitizesNullString() {
        assertEquals("Camera", "null".displayLabel("Camera"))
        assertEquals("Entry", "Entry".displayLabel("Camera"))
    }
}
