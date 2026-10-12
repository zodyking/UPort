package app.apt443.screensaver

import app.apt443.domain.ProtectEvent
import app.apt443.domain.ProtectEventType

internal const val DREAM_PAGE_SIZE = 9
internal const val DREAM_CYCLE_MS = 12_000L

/** Open event-feed rows older than this are stale, not a live detection. */
internal const val DREAM_OPEN_EVENT_CAP_MS = 3 * 60_000L

internal data class DreamBoard(
    val pageIndex: Int = 0,
    val visibleIds: List<String> = emptyList(),
    val lastActiveAt: Map<String, Long> = emptyMap(),
)

/** One column for a single camera, two for a pair, otherwise three. */
internal fun dreamColumns(count: Int): Int = when {
    count <= 1 -> 1
    count == 2 -> 2
    else -> 3
}

/**
 * Left-to-right cells, at most three columns. A short last row keeps empty slots
 * so a leftover camera stays in its column instead of stretching across the row.
 */
internal fun dreamGrid(cameraIds: List<String>): List<List<String?>> {
    if (cameraIds.isEmpty()) return emptyList()
    val columns = dreamColumns(cameraIds.size)
    return cameraIds.chunked(columns).map { row ->
        List(columns) { index -> row.getOrNull(index) }
    }
}

/**
 * Cameras the sleep screen should treat as detecting right now.
 *
 * Signal: open popup slots from [app.apt443.alert.AlertController] (passed in as
 * [slotCameraIds]) plus the existing Protect event feed. A slot means that alert
 * is still on the deck. An event counts while it is still open, or for [lingerMs]
 * after it ends — the same fade window the popup deck uses. Motion, smart detect,
 * doorbell rings, and alarming events count. Disconnects do not.
 */
internal fun activeDreamCameraIds(
    slotCameraIds: Set<String>,
    events: List<ProtectEvent>,
    nowMs: Long,
    lingerMs: Long,
): Set<String> {
    val fromEvents = events.mapNotNull { event ->
        val cameraId = event.cameraId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (event.isLiveDetection(nowMs, lingerMs)) cameraId else null
    }
    return slotCameraIds.filter { it.isNotBlank() }.toSet() + fromEvents
}

internal fun reconcileDreamBoard(
    board: DreamBoard,
    cameraIds: List<String>,
    activeIds: Set<String>,
    nowMs: Long,
): DreamBoard {
    val known = cameraIds.toSet()
    val stamped = board.lastActiveAt.filterKeys { it in known }.toMutableMap()
    activeIds.filter { it in known }.forEach { stamped[it] = nowMs }
    if (cameraIds.size <= DREAM_PAGE_SIZE) {
        return DreamBoard(pageIndex = 0, visibleIds = cameraIds, lastActiveAt = stamped)
    }
    var visible = board.visibleIds.filter { it in known }
    if (visible.isEmpty()) {
        visible = cameraIds.take(DREAM_PAGE_SIZE)
    } else if (visible.size < DREAM_PAGE_SIZE) {
        val already = visible.toSet()
        visible = (visible + cameraIds.filter { it !in already }).take(DREAM_PAGE_SIZE)
    } else {
        visible = visible.take(DREAM_PAGE_SIZE)
    }
    if (activeIds.none { it in known }) {
        return board.copy(visibleIds = visible, lastActiveAt = stamped)
    }
    val showing = visible.toMutableList()
    val onScreen = showing.toSet()
    val incoming = cameraIds
        .filter { it in activeIds && it !in onScreen }
        .sortedByDescending { stamped[it] ?: 0L }
    val idle = showing
        .filter { it !in activeIds }
        .sortedBy { stamped[it] ?: Long.MIN_VALUE }
        .toMutableList()
    for (cameraId in incoming) {
        if (idle.isEmpty()) break
        val victim = idle.removeAt(0)
        val index = showing.indexOf(victim)
        if (index >= 0) showing[index] = cameraId
    }
    return board.copy(visibleIds = showing, lastActiveAt = stamped)
}

/** Remember that these cameras are still detecting, so a later swap can see who went quiet first. */
internal fun noteDreamActivity(board: DreamBoard, activeIds: Set<String>, nowMs: Long): DreamBoard {
    if (activeIds.isEmpty()) return board
    val stamped = board.lastActiveAt.toMutableMap()
    activeIds.forEach { stamped[it] = nowMs }
    return board.copy(lastActiveAt = stamped)
}

/** Next ordered group of nine. Used only while nothing is detecting. */
internal fun cycleDreamBoard(board: DreamBoard, cameraIds: List<String>): DreamBoard {
    if (cameraIds.size <= DREAM_PAGE_SIZE) {
        return board.copy(pageIndex = 0, visibleIds = cameraIds)
    }
    val pages = cameraIds.chunked(DREAM_PAGE_SIZE)
    val next = (board.pageIndex + 1) % pages.size
    return board.copy(pageIndex = next, visibleIds = pages[next])
}

private fun ProtectEvent.isLiveDetection(nowMs: Long, lingerMs: Long): Boolean {
    val detected = type == ProtectEventType.Ring ||
        type == ProtectEventType.Motion ||
        type == ProtectEventType.SmartDetect ||
        alarming
    if (!detected) return false
    val end = endEpochMs
    if (end == null) {
        val age = nowMs - startEpochMs
        return age in 0..DREAM_OPEN_EVENT_CAP_MS
    }
    if (end > nowMs) return startEpochMs <= nowMs
    return nowMs - end <= lingerMs && startEpochMs <= nowMs
}
