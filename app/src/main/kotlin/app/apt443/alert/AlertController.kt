package app.apt443.alert

import app.apt443.domain.AlertInset
import app.apt443.domain.AlertKind
import app.apt443.domain.PopupSlot
import app.apt443.domain.QuietPrompt
import app.apt443.domain.popupRank
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The foreground service owns show and dismiss. In-app UI reads [slots] when overlay
 * permission is missing and the app is in the foreground.
 */
object AlertController {
    private val openSlots = MutableStateFlow<List<PopupSlot>>(emptyList())
    val slots: StateFlow<List<PopupSlot>> = openSlots

    private val alerts = MutableStateFlow<AlertInset?>(null)
    val visible: StateFlow<AlertInset?> = alerts

    private val voiceFailures = MutableStateFlow<String?>(null)
    val lastVoiceFailure: StateFlow<String?> = voiceFailures

    @Volatile
    var onIgnore: (String?) -> Unit = {}

    @Volatile
    var onSilence: () -> Unit = {}

    @Volatile
    var onWatch: (String?) -> Unit = {}

    @Volatile
    var onUnlock: suspend (String) -> Result<String> = {
        Result.failure(IllegalStateException("Unlock is not ready."))
    }

    @Volatile
    var onInteract: (String) -> Unit = {}

    @Volatile
    var onAnnounce: suspend (cameraId: String, message: String) -> Result<String> = { _, _ ->
        Result.failure(IllegalStateException("Speaker messages are not ready."))
    }

    fun publish(alert: AlertInset?) {
        publish(if (alert == null) emptyList() else listOf(PopupSlot(alert, openedAtMs = 0)))
    }

    fun publish(next: List<PopupSlot>) {
        openSlots.value = next
        alerts.value = next.lastOrNull()?.alert
    }

    fun reportVoiceFailure(message: String) {
        voiceFailures.value = message
    }

    fun clearVoiceFailure() {
        voiceFailures.value = null
    }

    fun ignore(id: String? = null) = onIgnore(id)

    fun silence() = onSilence()

    fun watch(cameraId: String?) = onWatch(cameraId)

    suspend fun unlock(doorId: String): Result<String> = onUnlock(doorId)

    suspend fun announce(cameraId: String, message: String): Result<String> = onAnnounce(cameraId, message)

    fun interact(id: String) = onInteract(id)
}

/** Foreground-app silence prompt. The accessibility service owns when it appears. */
object QuietPromptController {
    private val current = MutableStateFlow<QuietPrompt?>(null)
    val prompt: StateFlow<QuietPrompt?> = current

    @Volatile
    var onDecline: () -> Unit = {}

    @Volatile
    var onAccept: () -> Unit = {}

    fun publish(next: QuietPrompt?) {
        current.value = next
    }

    fun decline() = onDecline()

    fun accept() = onAccept()
}

fun AlertKind.overlayRank(): Int = popupRank()

object AptVisibility {
    private val resumed = MutableStateFlow(false)

    /** True while the main screen is in front. The corner window stays hidden so it cannot cover that screen. */
    val foreground: StateFlow<Boolean> = resumed.asStateFlow()

    var mainResumed: Boolean
        get() = resumed.value
        set(value) {
            resumed.value = value
        }
}

object DreamVisibility {
    private val showing = MutableStateFlow(false)

    /** True while the camera dream is attached. Corner alerts and always-on cameras stay hidden. */
    val active: StateFlow<Boolean> = showing.asStateFlow()

    var showingDream: Boolean
        get() = showing.value
        set(value) {
            showing.value = value
        }
}
