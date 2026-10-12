package app.apt443.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TenantLabelTest {
    @Test
    fun shortLabel() {
        assertEquals("Tom F.", TenantLabel.short("Tom", "Foster"))
        assertEquals("Tom", TenantLabel.short("Tom", ""))
    }

    @Test
    fun filterMatchesFirstName() {
        val user = IdentityUser("1", "Thomas", "Foster", "t@example.com")
        assertTrue(TenantLabel.matchesFilter(user, "tho"))
        assertFalse(TenantLabel.matchesFilter(user, "zzz"))
    }

    @Test
    fun prefixSpeakerMessage() {
        val settings = AppSettings(residentFirstName = "Tom")
        assertEquals("Tom said: Hello", settings.prefixSpeakerMessage("Hello"))
    }
}
