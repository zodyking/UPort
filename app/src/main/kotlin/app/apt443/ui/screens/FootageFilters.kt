package app.apt443.ui.screens

import app.apt443.domain.ProtectClipQuery
import app.apt443.domain.ProtectEvent
import app.apt443.domain.matchesKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure filter and sort rules for the Playback screen. No Android classes. */

/** Rolling windows offered before the custom range. */
enum class ClipPreset(val label: String, val millis: Long) {
    Hour("Last hour", 3_600_000L),
    Day("Last 24 hours", 86_400_000L),
    Week("Last 7 days", 7L * 86_400_000L),
    Month("Last 30 days", 30L * 86_400_000L),
    Quarter("Last 90 days", 90L * 86_400_000L),
}

val CLIP_EVENT_KINDS = listOf("All events", "Motion", "Person", "Vehicle", "Ring", "Package", "Animal")

const val ALL_CAMERAS = "All cameras"

internal val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
internal val HOUR_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("h a")
internal val MINUTE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("mm")

enum class ClipSort(val label: String) {
    Newest("Newest first"),
    Oldest("Oldest first"),
}

/**
 * A span of calendar days with an optional time of day applied to each of them.
 *
 * [from] and [to] are inclusive dates. A [dayStart] later than [dayEnd] wraps past midnight,
 * which is how an overnight window such as 10pm to 6am is expressed.
 */
data class ClipDateRange(
    val from: LocalDate,
    val to: LocalDate,
    val dayStart: LocalTime = LocalTime.MIN,
    val dayEnd: LocalTime = LocalTime.MAX,
) {
    val wrapsMidnight: Boolean get() = dayStart > dayEnd

    val wholeDay: Boolean
        get() = dayStart == LocalTime.MIN && (dayEnd == LocalTime.MAX || dayEnd == END_OF_DAY)

    /** Keeps [from] no later than [to] so a range is never backwards after one edit. */
    fun normalized(): ClipDateRange =
        if (from.isAfter(to)) copy(from = to, to = from) else this

    fun label(): String {
        val days = if (from == to) {
            DAY.format(from)
        } else {
            "${DAY.format(from)} - ${DAY.format(to)}"
        }
        return if (wholeDay) days else "$days, ${CLOCK.format(dayStart)}-${CLOCK.format(dayEnd)}"
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
        val END_OF_DAY: LocalTime = LocalTime.of(23, 59)
    }
}

/** Either a rolling preset or an explicit calendar range. */
sealed interface ClipWindow {
    data class Preset(val preset: ClipPreset) : ClipWindow

    data class Range(val range: ClipDateRange) : ClipWindow

    fun label(): String = when (this) {
        is Preset -> preset.label
        is Range -> range.label()
    }
}

data class ClipFilters(
    val window: ClipWindow = ClipWindow.Preset(ClipPreset.Month),
    val cameraId: String? = null,
    val kind: String = "All events",
    val sort: ClipSort = ClipSort.Newest,
) {
    /**
     * Console query for these filters. The whole span is requested even for an overnight
     * time-of-day window, because the console filters by instant and the per-day cut is
     * applied afterwards by [apply].
     */
    fun toQuery(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): ProtectClipQuery = when (window) {
        is ClipWindow.Preset -> ProtectClipQuery(
            cameraId = cameraId,
            kind = kind,
            startEpochMs = nowMs - window.preset.millis,
            endEpochMs = nowMs,
        )
        is ClipWindow.Range -> {
            val range = window.range.normalized()
            ProtectClipQuery(
                cameraId = cameraId,
                kind = kind,
                startEpochMs = range.from.atStartOfDay(zone).toInstant().toEpochMilli(),
                endEpochMs = LocalDateTime.of(range.to, LocalTime.MAX).atZone(zone).toInstant().toEpochMilli(),
            )
        }
    }

    /** Applies the time-of-day cut and the sort the console cannot do. */
    fun apply(events: List<ProtectEvent>, zone: ZoneId = ZoneId.systemDefault()): List<ProtectEvent> {
        val kept = events.filter { event ->
            event.startEpochMs > 0L &&
                event.matchesKind(kind) &&
                (cameraId == null || event.cameraId == cameraId) &&
                withinTimeOfDay(event.startEpochMs, zone)
        }
        return when (sort) {
            ClipSort.Newest -> kept.sortedByDescending { it.startEpochMs }
            ClipSort.Oldest -> kept.sortedBy { it.startEpochMs }
        }
    }

    private fun withinTimeOfDay(epochMs: Long, zone: ZoneId): Boolean {
        val range = (window as? ClipWindow.Range)?.range?.normalized() ?: return true
        if (range.wholeDay) return true
        val clock = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        return if (range.wrapsMidnight) {
            clock >= range.dayStart || clock <= range.dayEnd
        } else {
            clock >= range.dayStart && clock <= range.dayEnd
        }
    }
}

/** Default custom range when the viewer first opens the picker: today, all day. */
fun defaultClipRange(today: LocalDate): ClipDateRange = ClipDateRange(from = today, to = today)

enum class DateQuickPick(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    LastWeek("Last 7 days"),
}

/** One-tap spans for the custom date picker. */
fun quickClipRange(pick: DateQuickPick, today: LocalDate): ClipDateRange = when (pick) {
    DateQuickPick.Today -> ClipDateRange(from = today, to = today)
    DateQuickPick.Yesterday -> {
        val day = today.minusDays(1)
        ClipDateRange(from = day, to = day)
    }
    DateQuickPick.LastWeek -> ClipDateRange(from = today.minusDays(6), to = today)
}

/** Short line for the picker footer, e.g. "Mar 1 – Mar 5 (all day)". */
fun ClipDateRange.summaryLabel(): String {
    val days = label().substringBefore(",")
    return if (wholeDay) "$days (all day)" else label()
}

/** Step a date field by [delta] units, clamped so a range cannot run past today. */
fun shiftDate(date: LocalDate, unit: DateField, delta: Int, today: LocalDate): LocalDate {
    val moved = when (unit) {
        DateField.Year -> date.plusYears(delta.toLong())
        DateField.Month -> date.plusMonths(delta.toLong())
        DateField.Day -> date.plusDays(delta.toLong())
    }
    return when {
        moved.isAfter(today) -> today
        moved.isBefore(today.minusYears(MAX_HISTORY_YEARS)) -> today.minusYears(MAX_HISTORY_YEARS)
        else -> moved
    }
}

/**
 * Step a clock field, wrapping within the day so holding a direction keeps working.
 * The first step also snaps onto the minute grid, so editing the end of a whole day
 * (23:59:59.999) lands on a round time instead of carrying stray seconds forever.
 */
fun shiftTime(time: LocalTime, unit: TimeField, delta: Int): LocalTime {
    val step = MINUTE_STEP.toInt()
    val base = LocalTime.of(time.hour, time.minute / step * step)
    return when (unit) {
        TimeField.Hour -> base.plusHours(delta.toLong())
        TimeField.Minute -> base.plusMinutes(delta.toLong() * MINUTE_STEP)
    }
}

enum class DateField(val label: String) {
    Year("Year"),
    Month("Month"),
    Day("Day"),
}

enum class TimeField(val label: String) {
    Hour("Hour"),
    Minute("Minute"),
}

/**
 * Indexes whose snapshots should be requested. [first] and [last] are the visible grid rows.
 * [extra] is one more row so the next line is ready without asking for the whole list.
 */
fun visibleClipIndexes(first: Int, last: Int, total: Int, extra: Int): IntRange {
    if (total <= 0) return IntRange.EMPTY
    val start = first.coerceIn(0, total - 1)
    val end = (last + extra).coerceIn(start, total - 1)
    return start..end
}

private const val MAX_HISTORY_YEARS = 5L
private const val MINUTE_STEP = 15L
