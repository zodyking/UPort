package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityUsersParseTest {
    @Test
    fun parsesUlpUsers() {
        val body = """
            {"data":[{"id":"u1","firstName":"Tom","lastName":"Foster","email":"tom@home.test","status":"ACTIVE"}]}
        """.trimIndent()
        val users = parseIdentityUsers(body)
        assertEquals(1, users.size)
        assertEquals("Tom F.", users[0].shortLabel)
        assertEquals("Tom Foster", users[0].fullName)
        assertEquals("ACTIVE", users[0].status)
        assertEquals("tom@home.test", users[0].email)
    }

    @Test
    fun parsesBareUlpArrayAndSkipsEmailOnlyNames() {
        val body = """
            [
              {"id":"u1","firstName":"Tom","lastName":"Foster","email":"tom@home.test","status":"ACTIVE"},
              {"id":"u2","firstName":"Amy","lastName":"Ng","email":"amy@home.test","status":"ACTIVE"},
              {"id":"u3","name":"owner@home.test","firstName":null,"lastName":null,"email":"owner@home.test"}
            ]
        """.trimIndent()
        val users = parseIdentityUsers(body)
        assertEquals(listOf("Amy N.", "Tom F."), users.map { it.shortLabel })
    }
}
