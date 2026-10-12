package app.apt443.domain

/** How long a viewer can silence camera alerts from the watching prompt. */
enum class QuietDuration(val label: String, val millis: Long) {
    HalfHour("30 min", 30 * 60_000L),
    OneHour("1 hour", 60 * 60_000L),
    TwoHours("2 hours", 2 * 60 * 60_000L),
    ThreeHours("3 hours", 3 * 60 * 60_000L),
}

/** One popup on screen. [openedAtMs] is the fade clock and is not restarted by later motion. */
data class PopupSlot(
    val alert: AlertInset,
    val openedAtMs: Long,
    val engaged: Boolean = false,
)

data class PopupDeck(val slots: List<PopupSlot> = emptyList())

/** Width and height of one popup, in dp. */
data class PopupFrame(val widthDp: Float, val heightDp: Float)

fun AlertKind.popupRank(): Int = when (this) {
    AlertKind.Doorbell, AlertKind.AccessDoor -> 30
    AlertKind.SmartDetect -> 20
    AlertKind.Motion -> 10
}

/**
 * Adds [incoming] without restarting another card's fade clock.
 * Motion on a camera that already has a card refreshes that card in place.
 * A card the viewer has used stays until they close it, or until a strong enough
 * new event needs the last free slot.
 */
fun PopupDeck.admit(incoming: AlertInset, nowMs: Long, maxOpen: Int): PopupDeck {
    val cap = coerceOpenCount(maxOpen)
    val sameId = slots.indexOfFirst { it.alert.id == incoming.id }
    if (sameId >= 0) {
        return copy(slots = slots.replaced(sameId, slots[sameId].copy(alert = incoming)))
    }
    val cameraKey = incoming.cameraId?.takeIf { it.isNotBlank() }
    if (cameraKey != null) {
        val sameCamera = slots.indexOfFirst { it.alert.cameraId == cameraKey }
        if (sameCamera >= 0) {
            val current = slots[sameCamera]
            return copy(slots = slots.replaced(sameCamera, current.copy(alert = incoming)))
        }
    }
    val fresh = PopupSlot(alert = incoming, openedAtMs = nowMs, engaged = false)
    if (slots.size < cap) return copy(slots = slots + fresh)
    val victim = evictIndex(incoming) ?: return this
    return copy(slots = slots.filterIndexed { index, _ -> index != victim } + fresh)
}

fun PopupDeck.engage(id: String): PopupDeck =
    copy(slots = slots.map { if (it.alert.id == id) it.copy(engaged = true) else it })

fun PopupDeck.dismiss(id: String): PopupDeck =
    copy(slots = slots.filterNot { it.alert.id == id })

fun PopupDeck.dismissNewest(): PopupDeck = slots.lastOrNull()?.let { dismiss(it.alert.id) } ?: this

/** Drops untouched cards whose fade window has elapsed. Engaged cards stay. */
fun PopupDeck.expire(nowMs: Long, fadeMs: Long): PopupDeck =
    copy(
        slots = slots.filter { slot ->
            slot.engaged || nowMs - slot.openedAtMs < fadeMs
        },
    )

private fun PopupDeck.evictIndex(incoming: AlertInset): Int? {
    val incomingRank = incoming.kind.popupRank()
    val indexed = slots.withIndex().toList()
    val weaker = indexed.filter { it.value.alert.kind.popupRank() <= incomingRank }
    val pool = weaker.filter { !it.value.engaged }.ifEmpty { weaker }
    if (pool.isEmpty()) return null
    return pool.minWith(compareBy<IndexedValue<PopupSlot>> { it.value.alert.kind.popupRank() }
        .thenBy { it.value.openedAtMs })
        .index
}

private fun List<PopupSlot>.replaced(index: Int, slot: PopupSlot): List<PopupSlot> =
    mapIndexed { i, current -> if (i == index) slot else current }

fun coerceOpenCount(raw: Int): Int = raw.coerceIn(PopupLayout.MIN_OPEN, PopupLayout.MAX_OPEN)

fun coerceFadeSeconds(raw: Int): Int = PopupLayout.FADE_CHOICES.minBy { kotlin.math.abs(it - raw) }

fun AppSettings.alertsSilenced(nowMs: Long): Boolean = alertQuietUntilEpochMs > nowMs

/**
 * Width of one popup so [panes] of them sit in a row on a TV without covering the picture.
 * Sized in dp against the full screen, which on this Fire TV is 960 by 540.
 */
object PopupLayout {
    const val MIN_OPEN = 1
    const val MAX_OPEN = 3
    const val DEFAULT_OPEN = 2
    const val DEFAULT_FADE_SECONDS = 10
    val FADE_CHOICES = listOf(5, 10, 15, 30, 60)
    const val GAP_DP = 8f
    private const val CARD_MIN_DP = 150f
    const val OVERSCAN_X_DP = 96f
    const val OVERSCAN_Y_DP = 54f

    /** Headline bar under the picture on the Card look. */
    const val CARD_BAR_DP = 48f

    /** Door / Say / Ignore row that opens when the viewer presses Down on a Card popup. */
    const val ACTION_ROW_DP = 44f
    private const val CHROME_DP = CARD_BAR_DP + ACTION_ROW_DP

    /** Minimal width as a multiple of its height, so odd streams stay recognisable. */
    const val MINIMAL_MIN_ASPECT = 0.6f
    const val MINIMAL_MAX_ASPECT = 2.2f
    private const val MINIMAL_MIN_HEIGHT_DP = 60f

    fun cardWidthDp(
        screenWidthDp: Float,
        screenHeightDp: Float,
        panes: Int,
        compact: Boolean,
        size: PopupSize = PopupSize.Small,
    ): Float {
        val count = panes.coerceIn(1, MAX_OPEN + 1)
        val gaps = GAP_DP * (count - 1)
        val availableW = (screenWidthDp - OVERSCAN_X_DP - gaps).coerceAtLeast(CARD_MIN_DP)
        val share = availableW / count
        val preferred = if (compact) 280f else screenWidthDp * size.fraction
        val cap = if (compact) 320f else size.maxDp
        var width = minOf(preferred, share).coerceIn(CARD_MIN_DP, cap)
        if (!compact) {
            val maxHeight = (screenHeightDp - OVERSCAN_Y_DP).coerceAtLeast(160f)
            val maxWidth = ((maxHeight - CHROME_DP).coerceAtLeast(80f)) * 16f / 9f
            width = minOf(width, maxWidth)
        }
        return width
    }

    /** Card look: 16:9 picture plus the headline bar, plus the action row while it is open. */
    fun cardHeightDp(
        cardWidthDp: Float,
        compact: Boolean,
        minimal: Boolean = false,
        actionsOpen: Boolean = false,
    ): Float = when {
        compact -> 72f
        minimal -> cardWidthDp * 9f / 16f
        else -> cardWidthDp * 9f / 16f + CARD_BAR_DP + if (actionsOpen) ACTION_ROW_DP else 0f
    }

    /**
     * Every Minimal popup of one [size] shares this height, whatever the camera or source.
     * A 16:9 stream comes out as wide as the Card picture of the same size.
     */
    fun minimalHeightDp(
        size: PopupSize,
        screenWidthDp: Float = 960f,
        screenHeightDp: Float = 540f,
    ): Float {
        val width = minOf(screenWidthDp * size.fraction, size.maxDp)
        val maxHeight = (screenHeightDp - OVERSCAN_Y_DP).coerceAtLeast(MINIMAL_MIN_HEIGHT_DP)
        return (width * 9f / 16f).coerceIn(MINIMAL_MIN_HEIGHT_DP, maxHeight)
    }

    /** Width follows the stream shape, clamped to [MINIMAL_MIN_ASPECT]..[MINIMAL_MAX_ASPECT] of the height. */
    fun minimalWidthDp(heightDp: Float, aspect: Float): Float {
        val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        return heightDp * ratio.coerceIn(MINIMAL_MIN_ASPECT, MINIMAL_MAX_ASPECT)
    }

    /**
     * One row of Minimal popups at a shared height. When the row plus [extraDp] (the prompt
     * card and its gap) would run past the overscan-safe width, every card shrinks by the
     * same factor so the heights stay equal.
     */
    fun minimalRowDp(
        aspects: List<Float>,
        size: PopupSize,
        screenWidthDp: Float = 960f,
        screenHeightDp: Float = 540f,
        extraDp: Float = 0f,
    ): List<PopupFrame> {
        if (aspects.isEmpty()) return emptyList()
        val height = minimalHeightDp(size, screenWidthDp, screenHeightDp)
        val widths = aspects.map { minimalWidthDp(height, it) }
        val gaps = GAP_DP * (aspects.size - 1)
        val available = screenWidthDp - OVERSCAN_X_DP - gaps - extraDp
        val total = widths.sum()
        val scale = if (total > available && available > 0f) available / total else 1f
        return widths.map { PopupFrame(widthDp = it * scale, heightDp = height * scale) }
    }

    /** Room for the "silence alerts?" card when it sits beside a minimal popup. */
    const val MINIMAL_PROMPT_DP = 240f

    fun groupWidthDp(cardWidthDp: Float, panes: Int): Float {
        val count = panes.coerceAtLeast(1)
        return cardWidthDp * count + GAP_DP * (count - 1)
    }
}
