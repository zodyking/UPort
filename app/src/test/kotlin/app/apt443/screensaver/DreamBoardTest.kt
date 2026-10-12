package app.apt443.screensaver

import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamBoardTest {
    @Test
    fun gridFillsLeftToRightAndKeepsEmptySlots() {
        assertEquals(listOf(listOf("a")), dreamGrid(listOf("a")))
        assertEquals(listOf(listOf("a", "b")), dreamGrid(listOf("a", "b")))
        assertEquals(listOf(listOf("a", "b", "c")), dreamGrid(listOf("a", "b", "c")))
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("d", null, null)),
            dreamGrid(listOf("a", "b", "c", "d")),
        )
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("d", "e", null)),
            dreamGrid(listOf("a", "b", "c", "d", "e")),
        )
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("d", "e", "f")),
            dreamGrid(listOf("a", "b", "c", "d", "e", "f")),
        )
        assertEquals(
            listOf(
                listOf("a", "b", "c"),
                listOf("d", "e", "f"),
                listOf("g", null, null),
            ),
            dreamGrid(listOf("a", "b", "c", "d", "e", "f", "g")),
        )
        assertEquals(
            listOf(
                listOf("a", "b", "c"),
                listOf("d", "e", "f"),
                listOf("g", "h", null),
            ),
            dreamGrid(listOf("a", "b", "c", "d", "e", "f", "g", "h")),
        )
        val nine = ('a'..'i').map { it.toString() }
        val grid = dreamGrid(nine)
        assertEquals(3, grid.size)
        assertTrue(grid.all { it.size == 3 && it.none { cell -> cell == null } })
    }

    @Test
    fun cycleAdvancesOnlyThroughOrderedSets() {
        val ids = (1..12).map { "c$it" }
        val first = reconcileDreamBoard(DreamBoard(), ids, emptySet(), 0)
        assertEquals(ids.take(9), first.visibleIds)
        val second = cycleDreamBoard(first, ids)
        assertEquals(ids.drop(9), second.visibleIds)
        assertEquals(1, second.pageIndex)
        val wrapped = cycleDreamBoard(second, ids)
        assertEquals(ids.take(9), wrapped.visibleIds)
        assertEquals(0, wrapped.pageIndex)
    }

    @Test
    fun offScreenDetectionReplacesTheQuietestIdleCamera() {
        val ids = (1..12).map { "c$it" }
        val seeded = reconcileDreamBoard(DreamBoard(), ids, emptySet(), 0).copy(
            lastActiveAt = mapOf("c1" to 100L, "c2" to 50L, "c3" to 10L),
        )
        val swapped = reconcileDreamBoard(seeded, ids, setOf("c10", "c11"), 1_000L)
        assertEquals("c10", swapped.visibleIds[3])
        assertEquals("c11", swapped.visibleIds[4])
        assertEquals("c1", swapped.visibleIds[0])
        assertFalse(swapped.visibleIds.contains("c4"))
        assertFalse(swapped.visibleIds.contains("c5"))
        assertEquals(0, swapped.pageIndex)
    }

    @Test
    fun visibleDetectingCamerasStayWhenThePageIsFull() {
        val ids = (1..12).map { "c$it" }
        val showing = ids.take(9)
        val board = DreamBoard(visibleIds = showing)
        val active = showing.toSet() + "c10"
        val next = reconcileDreamBoard(board, ids, active, 5_000L)
        assertEquals(showing, next.visibleIds)
        assertFalse(next.visibleIds.contains("c10"))
    }

    @Test
    fun activeSignalUsesOpenSlotsAndLiveEvents() {
        val now = 100_000L
        val linger = 10_000L
        val active = activeDreamCameraIds(
            slotCameraIds = setOf("slot-cam"),
            events = listOf(
                event("open", "open-cam", start = now - 20_000L, end = null),
                event("fresh", "fresh-cam", start = now - 30_000L, end = now - 2_000L),
                event("cold", "cold-cam", start = now - 80_000L, end = now - 40_000L),
                event("stale", "stale-cam", start = now - 10 * 60_000L, end = null),
                event("drop", "drop-cam", type = ProtectEventType.Disconnect, start = now - 1_000L, end = null),
            ),
            nowMs = now,
            lingerMs = linger,
        )
        assertEquals(setOf("slot-cam", "open-cam", "fresh-cam"), active)
    }

    private fun event(
        id: String,
        cameraId: String,
        type: ProtectEventType = ProtectEventType.Motion,
        start: Long,
        end: Long?,
    ) = ProtectEvent(
        id = id,
        cameraId = cameraId,
        cameraName = cameraId,
        type = type,
        smartLabels = emptyList(),
        startEpochMs = start,
        endEpochMs = end,
        summary = "",
    )
}
