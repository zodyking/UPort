package app.apt443.data.live

import app.apt443.domain.FootageClip

/** Start and end instants passed to the console export endpoint, in epoch milliseconds. */
data class ExportWindow(val startMs: Long, val endMs: Long)

/**
 * Picks a span the NVR can actually mux.
 *
 * Protect needs a few seconds on either side of the event, and open events with no end time
 * need a real clip length — twenty seconds was too short and came back empty on many cameras.
 */
fun exportWindowFor(clip: FootageClip, nowMs: Long = System.currentTimeMillis()): ExportWindow {
    val eventStart = clip.startEpochMs.coerceAtLeast(0L)
    val start = (eventStart - PRE_ROLL_MS).coerceAtLeast(0L)
    val eventEnd = clip.endEpochMs?.takeIf { it > eventStart }
    val naturalEnd = when {
        eventEnd != null -> eventEnd + POST_ROLL_MS
        else -> eventStart + DEFAULT_OPEN_MS
    }
    val minEnd = start + MIN_CLIP_MS
    val cappedEnd = naturalEnd.coerceAtLeast(minEnd).coerceAtMost(start + MAX_CLIP_MS)
    val end = cappedEnd.coerceAtMost(nowMs + CLOCK_SKEW_MS)
    return ExportWindow(startMs = start, endMs = end.coerceAtLeast(minEnd))
}

private const val PRE_ROLL_MS = 5_000L
private const val POST_ROLL_MS = 10_000L
private const val DEFAULT_OPEN_MS = 60_000L
private const val MIN_CLIP_MS = 45_000L
private const val MAX_CLIP_MS = 5L * 60_000L
private const val CLOCK_SKEW_MS = 5_000L
