package app.apt443.ui.screens

import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class FootageFiltersTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun eventAt(dateTime: LocalDateTime, id: String = dateTime.toString()): ProtectEvent =
        ProtectEvent(
            id = id,
            cameraId = "cam-1",
            cameraName = "Front",
            type = ProtectEventType.Motion,
            smartLabels = emptyList(),
            startEpochMs = dateTime.atZone(zone).toInstant().toEpochMilli(),
            endEpochMs = null,
            summary = "Motion",
        )

    @Test
    fun presetWindowQueriesRollingSpan() {
        val now = 1_000_000_000L
        val query = ClipFilters(window = ClipWindow.Preset(ClipPreset.Day)).toQuery(now, zone)
        assertEquals(now - ClipPreset.Day.millis, query.startEpochMs)
        assertEquals(now, query.endEpochMs)
    }

    @Test
    fun customRangeSendsBothEnds() {
        val range = ClipDateRange(from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 3))
        val query = ClipFilters(window = ClipWindow.Range(range)).toQuery(0L, zone)
        val start = LocalDateTime.of(2026, 3, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(start, query.startEpochMs)
        assertTrue(query.endEpochMs!! > start)
    }

    @Test
    fun backwardsRangeIsRepaired() {
        val range = ClipDateRange(from = LocalDate.of(2026, 3, 9), to = LocalDate.of(2026, 3, 2))
        val fixed = range.normalized()
        assertEquals(LocalDate.of(2026, 3, 2), fixed.from)
        assertEquals(LocalDate.of(2026, 3, 9), fixed.to)
    }

    @Test
    fun visibleSnapshotsStayOnScreen() {
        assertEquals(0..5, visibleClipIndexes(first = 0, last = 2, total = 40, extra = 3))
        assertEquals(8..14, visibleClipIndexes(first = 8, last = 11, total = 40, extra = 3))
        assertEquals(37..39, visibleClipIndexes(first = 37, last = 39, total = 40, extra = 3))
    }

    @Test
    fun daytimeWindowKeepsOnlyThatPartOfEachDay() {
        val range = ClipDateRange(
            from = LocalDate.of(2026, 3, 1),
            to = LocalDate.of(2026, 3, 2),
            dayStart = LocalTime.of(9, 0),
            dayEnd = LocalTime.of(17, 0),
        )
        val filters = ClipFilters(window = ClipWindow.Range(range))
        val inside = eventAt(LocalDateTime.of(2026, 3, 1, 12, 0), "inside")
        val tooEarly = eventAt(LocalDateTime.of(2026, 3, 2, 7, 0), "early")
        val kept = filters.apply(listOf(inside, tooEarly), zone)
        assertEquals(listOf("inside"), kept.map { it.id })
    }

    @Test
    fun overnightWindowWrapsPastMidnight() {
        val range = ClipDateRange(
            from = LocalDate.of(2026, 3, 1),
            to = LocalDate.of(2026, 3, 2),
            dayStart = LocalTime.of(22, 0),
            dayEnd = LocalTime.of(6, 0),
        )
        assertTrue(range.wrapsMidnight)
        val filters = ClipFilters(window = ClipWindow.Range(range))
        val lateNight = eventAt(LocalDateTime.of(2026, 3, 1, 23, 30), "late")
        val earlyMorning = eventAt(LocalDateTime.of(2026, 3, 2, 5, 0), "early")
        val afternoon = eventAt(LocalDateTime.of(2026, 3, 1, 14, 0), "afternoon")
        val kept = filters.apply(listOf(lateNight, earlyMorning, afternoon), zone).map { it.id }
        assertTrue(kept.containsAll(listOf("late", "early")))
        assertFalse(kept.contains("afternoon"))
    }

    @Test
    fun wholeDayRangeDoesNotFilterByClock() {
        val range = ClipDateRange(from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 1))
        assertTrue(range.wholeDay)
        val filters = ClipFilters(window = ClipWindow.Range(range))
        val middleOfNight = eventAt(LocalDateTime.of(2026, 3, 1, 3, 0), "night")
        assertEquals(listOf("night"), filters.apply(listOf(middleOfNight), zone).map { it.id })
    }

    @Test
    fun sortOrdersBothWays() {
        val older = eventAt(LocalDateTime.of(2026, 3, 1, 1, 0), "older")
        val newer = eventAt(LocalDateTime.of(2026, 3, 1, 9, 0), "newer")
        val events = listOf(older, newer)
        assertEquals(
            listOf("newer", "older"),
            ClipFilters(sort = ClipSort.Newest).apply(events, zone).map { it.id },
        )
        assertEquals(
            listOf("older", "newer"),
            ClipFilters(sort = ClipSort.Oldest).apply(events, zone).map { it.id },
        )
    }

    @Test
    fun cameraFilterKeepsOnlyThatCamera() {
        val mine = eventAt(LocalDateTime.of(2026, 3, 1, 9, 0), "mine")
        val other = mine.copy(id = "other", cameraId = "cam-2")
        val kept = ClipFilters(cameraId = "cam-1").apply(listOf(mine, other), zone)
        assertEquals(listOf("mine"), kept.map { it.id })
    }

    @Test
    fun kindFilterUsesSmartLabels() {
        val person = eventAt(LocalDateTime.of(2026, 3, 1, 9, 0), "person")
            .copy(type = ProtectEventType.SmartDetect, smartLabels = listOf("person"))
        val motion = eventAt(LocalDateTime.of(2026, 3, 1, 10, 0), "motion")
        val kept = ClipFilters(kind = "Person").apply(listOf(person, motion), zone)
        assertEquals(listOf("person"), kept.map { it.id })
    }

    @Test
    fun eventsWithoutATimeAreDropped() {
        val broken = eventAt(LocalDateTime.of(2026, 3, 1, 9, 0), "broken").copy(startEpochMs = 0L)
        assertTrue(ClipFilters().apply(listOf(broken), zone).isEmpty())
    }

    @Test
    fun dateStepsStopAtToday() {
        val today = LocalDate.of(2026, 3, 10)
        assertEquals(today, shiftDate(today, DateField.Day, 1, today))
        assertEquals(LocalDate.of(2026, 3, 9), shiftDate(today, DateField.Day, -1, today))
        assertEquals(today, shiftDate(today, DateField.Month, 6, today))
    }

    @Test
    fun dateStepsStopAtTheHistoryLimit() {
        val today = LocalDate.of(2026, 3, 10)
        val floor = shiftDate(today, DateField.Year, -50, today)
        assertEquals(today.minusYears(5), floor)
    }

    @Test
    fun timeStepsWrapWithinTheDay() {
        assertEquals(LocalTime.of(0, 30), shiftTime(LocalTime.of(23, 30), TimeField.Hour, 1))
        assertEquals(LocalTime.of(9, 15), shiftTime(LocalTime.of(9, 0), TimeField.Minute, 1))
        assertEquals(LocalTime.of(8, 45), shiftTime(LocalTime.of(9, 0), TimeField.Minute, -1))
    }

    @Test
    fun rangeLabelMentionsClockOnlyWhenItIsNotAWholeDay() {
        val wholeDay = ClipDateRange(from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 1))
        assertFalse(wholeDay.label().contains("-"))
        val slice = wholeDay.copy(dayStart = LocalTime.of(9, 0), dayEnd = LocalTime.of(17, 0))
        assertTrue(slice.label().contains("9:00 AM"))
    }

    @Test
    fun defaultRangeIsTodayAllDay() {
        val today = LocalDate.of(2026, 3, 10)
        val range = defaultClipRange(today)
        assertEquals(today, range.from)
        assertEquals(today, range.to)
        assertTrue(range.wholeDay)
        assertNull(ClipFilters(window = ClipWindow.Range(range)).apply(emptyList(), zone).firstOrNull())
    }
}
