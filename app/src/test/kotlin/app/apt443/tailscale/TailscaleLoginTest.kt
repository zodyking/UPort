package app.apt443.tailscale

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleLoginTest {
    @Test
    fun startBodyCarriesTheAuthKeyAndWantRunning() {
        val key = "tskey-auth-example"
        val masked = TailscaleLogin.maskedPrefsJson("https://control.example")
        val prefs = JSONObject(masked).put("CorpDNS", true)
        val start = JSONObject(TailscaleLogin.startJson(prefs.toString(), key))
        assertEquals(key, start.getString("AuthKey"))
        assertTrue(start.getJSONObject("UpdatePrefs").getBoolean("WantRunning"))
        assertEquals("https://control.example", start.getJSONObject("UpdatePrefs").getString("ControlURL"))
        assertTrue(JSONObject(masked).getBoolean("ControlURLSet"))
        assertTrue(JSONObject(masked).getBoolean("LoggedOutSet"))
        assertFalse(JSONObject(masked).getBoolean("LoggedOut"))
    }

    @Test
    fun blankLoginServerOmitsControlUrlAndAuthKey() {
        val masked = JSONObject(TailscaleLogin.maskedPrefsJson("  "))
        assertFalse(masked.has("ControlURL"))
        val start = JSONObject(TailscaleLogin.startJson("""{"LoggedOut":false}""", "  "))
        assertFalse(start.has("AuthKey"))
        assertTrue(start.getJSONObject("UpdatePrefs").getBoolean("WantRunning"))
    }

    @Test
    fun notifyStateAndPublicErrorsHideTheKey() {
        assertEquals(6, TailscaleLogin.stateFromNotify("""{"State":6}"""))
        assertNull(TailscaleLogin.stateFromNotify("""{"Version":"1"}"""))
        val key = "tskey-auth-secret"
        assertEquals(TailscaleLogin.AUTH_REJECTED, TailscaleLogin.safeFailure("rejected $key", key))
        assertEquals(TailscaleLogin.AUTH_REJECTED, TailscaleLogin.safeFailure("invalid authkey", key))
        assertEquals(
            TailscaleLogin.CONSOLE_UNREACHABLE,
            TailscaleLogin.CONSOLE_UNREACHABLE,
        )
        assertFalse(TailscaleLogin.CONSOLE_UNREACHABLE.contains(key))
        val missed = TailscaleLogin.consoleUnreachable(
            "100.74.110.112",
            "The connection timed out.",
            "192.168.0.1",
        )
        assertTrue(missed.contains("100.74.110.112"))
        assertTrue(missed.contains("192.168.0.1"))
        assertFalse(missed.contains(key))
        assertFalse(TailscaleLogin.PERMISSION_DENIED.contains(key))
        assertEquals(
            TailscaleLogin.LIBRARY_MISSING,
            TailscaleLogin.safeFailure("libtailscale.Libtailscale", key),
        )
    }
}
