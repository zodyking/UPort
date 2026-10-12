package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStillTest {
    @Test
    fun readsTheThumbnailStringAndANestedThumbnailId() {
        val nested = """
            [{"id":"e1","type":"motion","start":1700000000000,"camera":"cam","thumbnail":{"id":"e-abc"}}]
        """.trimIndent()
        assertEquals("e-abc", parseEvents(nested).single().thumbnailId)
        val meta = """
            [{"id":"e2","type":"motion","start":1700000000000,"metadata":{"thumbnail":"meta-1"}}]
        """.trimIndent()
        assertEquals("meta-1", parseEvents(meta).single().thumbnailId)
    }

    @Test
    fun consoleSnapshotUrlUsesTheEventRouteAndDropsTheLegacyPrefix() {
        assertEquals("abc", consoleStillId("e-abc"))
        assertEquals("thumb-1", consoleStillId("thumb-1"))
        assertEquals(
            "https://nvr.local/proxy/protect/api/events/abc/thumbnail?w=640",
            consoleEventThumbnailUrl("nvr.local", "e-abc"),
        )
        assertEquals("/events/evt-1/thumbnail?w=640", eventThumbnailPath("evt-1"))
        assertNull(consoleEventThumbnailUrl(" ", "e-"))
    }

    @Test
    fun aMissingSnapshotDoesNotSignInAgain() {
        assertFalse(thumbnailNeedsLogin("Thumbnail HTTP 404"))
        assertFalse(thumbnailNeedsLogin("Thumbnail HTTP 429"))
        assertFalse(thumbnailNeedsLogin("Thumbnail was not a picture."))
        assertTrue(thumbnailNeedsLogin("Thumbnail HTTP 401"))
        assertTrue(thumbnailNeedsLogin("Thumbnail HTTP 403"))
        assertEquals(404, thumbnailHttpStatus("Thumbnail HTTP 404"))
        assertEquals(429, thumbnailHttpStatus("Thumbnail HTTP 429"))
    }

    @Test
    fun aJsonDocumentIsNotAJpeg() {
        assertFalse(isJpeg("""{"error":"no"}""".toByteArray()))
        assertTrue(isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }
}
