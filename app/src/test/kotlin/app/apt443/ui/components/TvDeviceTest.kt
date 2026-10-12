package app.apt443.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDeviceTest {
    @Test
    fun fireTvFromAmazonManufacturer() {
        assertTrue(TvDevice.looksLikeFireTv(manufacturer = "Amazon", model = "SomeDevice"))
    }

    @Test
    fun fireTvFromAftModel() {
        assertTrue(TvDevice.looksLikeFireTv(manufacturer = "unknown", model = "AFTMM"))
    }

    @Test
    fun notFireTvFromGoogleTv() {
        assertFalse(TvDevice.looksLikeFireTv(manufacturer = "Google", model = "Chromecast"))
    }
}
