package app.apt443.data.live

import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackWindowTest {
    @Test
    fun aNewestPageContinuesJustBeforeItsOldestClip() {
        val end = 1_800_000_000_000L
        val start = end - 30L * 86_400_000L
        val newestPage = List(300) { index -> event("e$index", end - (300 - index) * 60_000L) }
        val next = nextEventWindow(newestPage, start, end, pageSize = 300)
        assertEquals(start, next?.first)
        assertEquals(newestPage.minOf { it.startEpochMs } - 1, next?.second)
    }

    @Test
    fun anOldestPageContinuesJustAfterItsNewestClip() {
        val end = 1_800_000_000_000L
        val start = end - 30L * 86_400_000L
        val oldestPage = List(300) { index -> event("e$index", start + index * 60_000L) }
        val next = nextEventWindow(oldestPage, start, end, pageSize = 300)
        assertEquals(oldestPage.maxOf { it.startEpochMs } + 1, next?.first)
        assertEquals(end, next?.second)
    }

    @Test
    fun aShortPageDoesNotOpenAGap() {
        val end = 1_800_000_000_000L
        assertNull(nextEventWindow(listOf(event("now", end - 30_000L)), end - 86_400_000L, end, pageSize = 300))
    }

    @Test
    fun laterEventsReplaceOlderCopies() {
        val first = listOf(event("a", 10L), event("b", 20L))
        val second = listOf(event("b", 25L), event("c", 30L))
        val merged = mergeEvents(first, second)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(25L, merged.first { it.id == "b" }.startEpochMs)
    }

    @Test
    fun wanHealthCarriesLiveThroughputAndTheLastTest() {
        val wan = parseControllerWan(
            """
            {"data":[{
              "subsystem":"wan",
              "status":"ok",
              "isp_name":"Example",
              "latency":11,
              "rx_bytes-r":1250000,
              "tx_bytes-r":250000,
              "xput_down":900.5,
              "xput_up":40.0,
              "speedtest_status":"idle"
            }]}
            """.trimIndent(),
        )
        assertEquals(true, wan.up)
        assertEquals(900.5, wan.downloadMbps!!, 0.1)
        assertEquals(10.0, wan.liveDownMbps!!, 0.1)
        assertEquals(2.0, wan.liveUpMbps!!, 0.1)
        assertEquals("idle", wan.speedtest)
    }

    private fun event(id: String, start: Long) = ProtectEvent(
        id = id,
        cameraId = "cam",
        cameraName = "Door",
        type = ProtectEventType.Motion,
        smartLabels = emptyList(),
        startEpochMs = start,
        endEpochMs = null,
        summary = "Motion",
    )
}
