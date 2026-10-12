package app.apt443.data.live

import app.apt443.domain.FootageClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipExportTest {
    @Test
    fun openEventsGetAFullMinuteNotTwentySeconds() {
        val start = 1_000_000L
        val now = start + 120_000L
        val window = exportWindowFor(
            FootageClip("1", "cam", "Motion", start, null, "Motion"),
            nowMs = now,
        )
        assertEquals(start - 5_000L, window.startMs)
        assertTrue(window.endMs - window.startMs >= 45_000L)
        assertEquals(start + 60_000L, window.endMs)
    }

    @Test
    fun closedEventsGetPreAndPostRoll() {
        val start = 1_000_000L
        val end = start + 30_000L
        val window = exportWindowFor(
            FootageClip("1", "cam", "Person", start, end, "Person"),
            nowMs = end + 60_000L,
        )
        assertEquals(start - 5_000L, window.startMs)
        assertEquals(end + 10_000L, window.endMs)
    }

    @Test
    fun veryLongEventsAreCappedAtFiveMinutes() {
        val start = 1_000_000L
        val end = start + 20 * 60_000L
        val window = exportWindowFor(
            FootageClip("1", "cam", "Motion", start, end, "Motion"),
            nowMs = end + 60_000L,
        )
        assertTrue(window.endMs - window.startMs <= 5 * 60_000L + 5_000L)
    }
}
