package app.apt443.ui.player

import app.apt443.domain.StreamQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle of one live tile.
 * [Connecting] first attempt; [Live] first frame rendered; [Reconnecting] after any drop or
 * failed start; [SnapshotOnly] after a decoder failure (snapshots refresh, live is re-probed);
 * [Failed] no URL is offered (demo, off network, setup missing) or the tile is disabled. No spinner.
 */
enum class StreamPhase { Connecting, Live, Reconnecting, SnapshotOnly, Failed }

sealed interface RetryStep {
    val delayMs: Long

    /** Try live again after [delayMs]. When [refetchUrl] is true, drop the cached URL first. */
    data class RetryLive(override val delayMs: Long, val refetchUrl: Boolean) : RetryStep

    /** Show snapshots every [refreshEveryMs]; probe live again after [delayMs]. */
    data class SnapshotOnly(override val delayMs: Long, val refreshEveryMs: Long) : RetryStep

    /** Nothing to play. Ask for a URL again after [delayMs] without showing a spinner. */
    data class Idle(override val delayMs: Long) : RetryStep
}

/**
 * Pure retry state machine for [LiveStreamTile]. Not thread-safe; drive it from one coroutine.
 */
class StreamRetryPolicy(
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val jitterFraction: Double = 0.2,
    val refetchAfterFailures: Int = 2,
    val snapshotRefreshMs: Long = 3_000L,
    val snapshotLiveRetryMs: Long = 60_000L,
    val unavailableRetryMs: Long = 30_000L,
    val connectTimeoutMs: Long = 20_000L,
    val stallTimeoutMs: Long = 10_000L,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    init {
        require(backoffMs.isNotEmpty())
        require(refetchAfterFailures >= 1)
    }

    var phase: StreamPhase = StreamPhase.Connecting
        private set

    var consecutiveFailures: Int = 0
        private set

    /** Increments for every live attempt; use it as the player key. */
    var attempt: Int = 0
        private set

    val maxBackoffMs: Long get() = backoffMs.last()

    fun beginAttempt(): Int {
        attempt++
        if (phase == StreamPhase.Failed || phase == StreamPhase.Live) {
            phase = if (consecutiveFailures == 0) StreamPhase.Connecting else StreamPhase.Reconnecting
        }
        return attempt
    }

    fun onLive() {
        phase = StreamPhase.Live
        consecutiveFailures = 0
    }

    fun onPlaybackFailure(decoderFailure: Boolean): RetryStep {
        if (decoderFailure || phase == StreamPhase.SnapshotOnly) {
            phase = StreamPhase.SnapshotOnly
            consecutiveFailures = 0
            return RetryStep.SnapshotOnly(snapshotLiveRetryMs, snapshotRefreshMs)
        }
        consecutiveFailures++
        phase = StreamPhase.Reconnecting
        return RetryStep.RetryLive(
            delayMs = backoffFor(consecutiveFailures),
            refetchUrl = consecutiveFailures % refetchAfterFailures == 0,
        )
    }

    /**
     * No URL came back. [terminal] means the repository answered but offered nothing
     * (availability not Ready); otherwise the request itself failed and is retried with backoff.
     */
    fun onUrlUnavailable(terminal: Boolean): RetryStep {
        if (terminal) {
            phase = StreamPhase.Failed
            consecutiveFailures = 0
            return RetryStep.Idle(unavailableRetryMs)
        }
        if (phase == StreamPhase.SnapshotOnly) {
            return RetryStep.SnapshotOnly(snapshotLiveRetryMs, snapshotRefreshMs)
        }
        consecutiveFailures++
        phase = StreamPhase.Reconnecting
        return RetryStep.RetryLive(delayMs = backoffFor(consecutiveFailures), refetchUrl = true)
    }

    /** Tile went to the background or was disabled and is coming back. SnapshotOnly is kept. */
    fun onResume() {
        consecutiveFailures = 0
        if (phase != StreamPhase.SnapshotOnly) phase = StreamPhase.Connecting
    }

    fun onDisabled() {
        phase = StreamPhase.Failed
        consecutiveFailures = 0
    }

    /** Base delay for the n-th consecutive failure (1-based) with +/- jitter, never above the cap. */
    fun backoffFor(failures: Int): Long {
        val index = (failures - 1).coerceIn(0, backoffMs.lastIndex)
        val base = backoffMs[index]
        val swing = (random().coerceIn(0.0, 1.0) * 2.0 - 1.0) * jitterFraction
        return (base + base * swing).toLong().coerceIn(0L, maxBackoffMs)
    }

    companion object {
        val DEFAULT_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    }
}

/** Per camera + quality RTSP URL cache. Thread-safe. */
class StreamUrlStore(
    private val ttlMs: Long = 10 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val url: String, val atMs: Long)

    private val entries = HashMap<Pair<String, StreamQuality>, Entry>()

    @Synchronized
    fun get(cameraId: String, quality: StreamQuality): String? {
        val key = cameraId to quality
        val entry = entries[key] ?: return null
        if (clock() - entry.atMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.url
    }

    @Synchronized
    fun put(cameraId: String, quality: StreamQuality, url: String) {
        entries[cameraId to quality] = Entry(url, clock())
    }

    @Synchronized
    fun invalidate(cameraId: String, quality: StreamQuality) {
        entries.remove(cameraId to quality)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

/** Bumped when the TV's network changes so every player drops its dead socket and starts again. */
object StreamEpoch {
    private val ticks = MutableStateFlow(0)
    val epoch: StateFlow<Int> = ticks

    fun bump() {
        ticks.value++
    }
}

/** Process-wide cache used by [LiveStreamTile]. Call [StreamUrlStore.clear] after console/key changes. */
object StreamUrlCache {
    val store = StreamUrlStore()

    fun get(cameraId: String, quality: StreamQuality): String? = store.get(cameraId, quality)

    fun put(cameraId: String, quality: StreamQuality, url: String) = store.put(cameraId, quality, url)

    fun invalidate(cameraId: String, quality: StreamQuality) = store.invalidate(cameraId, quality)

    fun clear() = store.clear()
}
