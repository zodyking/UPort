package app.apt443.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAlertSetupServerTest {
    @Test
    fun setupPathMatchesFourDigitCode() {
        assertTrue(AlertSetupPaths.matchesCode("/apt443/4821", "4821"))
        assertFalse(AlertSetupPaths.matchesCode("/apt443/4821", "1234"))
        assertFalse(AlertSetupPaths.matchesCode("/apt443/12", "1234"))
    }

    @Test
    fun setupPathAllowsTrailingSlash() {
        assertTrue(AlertSetupPaths.matchesCode("/apt443/4821/", "4821"))
    }

    @Test
    fun publishedUrlFormat() {
        assertEquals(
            "http://192.168.0.50:8743/apt443/4821",
            AlertSetupPaths.publicUrl("192.168.0.50", 8743, "4821"),
        )
    }
}
