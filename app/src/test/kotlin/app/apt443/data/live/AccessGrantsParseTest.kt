package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessGrantsParseTest {
    @Test
    fun matchesUserByEmailAndReadsDoorPolicy() {
        val body = """
            {"code":"SUCCESS","data":[
              {"id":"u1","email":"tom@home.test","access_policies":[
                {"name":"Home","resources":[{"id":"d1","type":"door","name":"Front"}]}
              ]}
            ]}
        """.trimIndent()
        val user = findAccessUser(accessUserObjects(body), "Tom@home.test", "")
        val policies = accessPolicies(user!!)
        assertEquals("u1", user.optString("id"))
        assertEquals("Home", policies.single().name)
        assertEquals("door", policies.single().resources.single().type)
    }

    @Test
    fun readsAssignedCameras() {
        val body = """
            {"code":"SUCCESS","data":{"camera":[{"id":"c1","name":"Entry","deleted":false},{"id":"c2","name":"Old","deleted":true}],"wifi":[]}}
        """.trimIndent()
        assertEquals(listOf("Entry"), cameraNamesFromAssignments(body))
    }

    @Test
    fun readsGroupsAndCamerasFromProtectBootstrap() {
        val body = """
            {
              "users":[{"id":"person","email":"tom@home.test","groups":["g1"],
                "allPermissions":[{"rawPermission":"camera:read","objectId":"cam-front"}]}],
              "groups":[{"uniqueId":"g1","name":"Residents"}],
              "cameras":[{"id":"cam-front","name":"Front door"}]
            }
        """.trimIndent()
        val access = residentFromBootstrap(body, "Tom@home.test", "")
        assertEquals(listOf("Residents"), access.groups)
        assertEquals(listOf("Front door"), access.cameras)
        assertEquals(listOf("View cameras"), access.policies)
    }

    @Test
    fun readsSessionDoorsAndReaders() {
        val doors = """
            {"code":"SUCCESS","data":{"locations":[
              {"id":"door-1","name":"Front","door_position_status":"close","lock_relay_status":"lock"}
            ]}}
        """.trimIndent()
        val readers = """
            {"data":[
              {"id":"hub-1","name":"Entry hub","type":"UAH","is_online":true},
              {"id":"reader-1","name":"Front reader","type":"UA-Lite","is_connected":false}
            ]}
        """.trimIndent()
        val door = parseDoors(doors).single()
        assertEquals("door-1", door.id)
        assertEquals(true, door.locked)
        val devices = parseAccessDevices(readers)
        assertEquals("Hub", devices[0].kind)
        assertEquals(true, devices[0].online)
        assertEquals("Reader", devices[1].kind)
        assertEquals(false, devices[1].online)
    }

    @Test
    fun skipsUnmatchedEmail() {
        val users = accessUserObjects("""{"data":[{"id":"u1","email":"amy@home.test"}]}""")
        assertTrue(findAccessUser(users, "tom@home.test", "other") == null)
    }
}
