package app.apt443.tailscale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleRoutesTest {
    private val home = listOf("192.168.0.226" to 24)

    @Test
    fun localLanRouteStaysOffTheTunnel() {
        assertTrue(TailscaleRoutes.overlapsLocalLan("192.168.0.0", 24, home))
        assertTrue(TailscaleRoutes.overlapsLocalLan("192.168.0.1", 32, home))
    }

    @Test
    fun tailscalePeersAndOtherSubnetsStayOnTheTunnel() {
        assertFalse(TailscaleRoutes.overlapsLocalLan("100.74.110.112", 32, home))
        assertFalse(TailscaleRoutes.overlapsLocalLan("10.0.0.0", 8, home))
        assertFalse(TailscaleRoutes.overlapsLocalLan("0.0.0.0", 0, home))
    }
}
