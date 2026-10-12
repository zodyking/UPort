package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Test

class EventFlagParseTest {
    @Test
    fun readsTheConsoleSnapshotWhenFavoriteIsPresent() {
        val body = """
            {"data":[
              {"id":"e1","type":"smartDetectZone","start":1700000000000,"end":1700000005000,
               "camera":"cam","thumbnailId":"thumb-1","isFavorite":true,"smartDetectTypes":["person"]}
            ]}
        """.trimIndent()
        val event = parseEvents(body).single()
        assertEquals("thumb-1", event.thumbnailId)
        assertEquals("e1", event.id)
    }
}
