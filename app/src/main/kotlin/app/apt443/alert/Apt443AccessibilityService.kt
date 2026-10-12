package app.apt443.alert

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.apt443.Apt443Application
import app.apt443.domain.AppSettings
import app.apt443.domain.PopupLook
import app.apt443.domain.QUIET_HOLD_MS
import app.apt443.domain.QuietPrompt
import app.apt443.domain.QuietSession
import app.apt443.domain.alertsSilenced
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val QUIET_WAKE_EARLY_MS = 1_000L

/**
 * Draws alerts over other apps on devices that cannot grant "draw over other apps".
 *
 * Fire TV ships no Settings screen for `SYSTEM_ALERT_WINDOW`, so that permission can never be
 * granted there and the only window type an app can still place above another app is
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], which an enabled accessibility
 * service may use without any extra permission. The foreground package name is used only to
 * offer a silence prompt while TV, HDMI, or a known video app is in front. Screen contents
 * are not read.
 */
class Apt443AccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: AlertWindowHost? = null
    private var latestSettings = AppSettings()
    private var settingsReady = false
    private var session = QuietSession()
    private var promptVisible = false
    private var recheckJob: Job? = null
    private val quietWrites = Mutex()
    private val quietEpoch = AtomicInteger(0)
    private var wantedQuietUntil = 0L
    private var quietWritePending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        setRunning(true)
        host = AlertWindowHost(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        PersistentOverlays.show(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        AlertOverlayService.start(this)
        val container = (application as Apt443Application).container
        QuietPromptController.onDecline = { onQuietDecline() }
        QuietPromptController.onAccept = { acceptQuiet() }
        scope.launch {
            container.settings.collect { settings ->
                settingsReady = true
                val pendingOurWrite = quietWritePending && settings.alertQuietUntilEpochMs != wantedQuietUntil
                if (!pendingOurWrite) latestSettings = settings
                val resumed = session.silencedPackage != null &&
                    settings.alertQuietUntilEpochMs == 0L &&
                    wantedQuietUntil != 0L &&
                    !pendingOurWrite
                if (resumed) {
                    quietEpoch.incrementAndGet()
                    wantedQuietUntil = 0L
                    recheckJob?.cancel()
                    session = QuietSession()
                    QuietPromptController.publish(null)
                }
            }
        }
        scope.launch {
            combine(
                AlertController.slots,
                QuietPromptController.prompt,
                container.settings,
                AptVisibility.foreground,
                DreamVisibility.active,
            ) { slots, prompt, settings, inApp, dreaming ->
                RenderRequest(slots.size, prompt, settings, inApp, dreaming)
            }.collect { request ->
                render(request.count, request.prompt, request.settings, request.inApp, request.dreaming)
            }
        }
    }

    private fun onQuietDecline() {
        val next = session.declined()
        session = next.copy(releaseSilence = false, extendSilence = false)
        QuietPromptController.publish(null)
        if (!next.releaseSilence) return
        recheckJob?.cancel()
        writeQuietUntil(0L)
    }

    private fun acceptQuiet() {
        val now = System.currentTimeMillis()
        session = session.accepted(now)
        QuietPromptController.publish(null)
        AlertController.silence()
        writeQuietUntil(now + QUIET_HOLD_MS)
        scheduleRecheck()
    }

    private fun render(
        slotCount: Int,
        prompt: QuietPrompt?,
        settings: AppSettings,
        inApp: Boolean,
        dreaming: Boolean,
    ) {
        val window = host ?: return
        if (dreaming) {
            promptVisible = false
            window.remove()
            return
        }
        val panes = slotCount + if (prompt != null) 1 else 0
        if (inApp || panes == 0) {
            promptVisible = false
            window.hide()
            return
        }
        val interactive = prompt != null || settings.popupLook != PopupLook.Minimal
        window.show(settings, panes, interactive)
        if (prompt != null && !promptVisible) window.claimFocus()
        promptVisible = prompt != null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!settingsReady || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foreground = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        val now = System.currentTimeMillis()
        val silenced = latestSettings.alertsSilenced(now)
        applyQuiet(session.receive(foreground, className, packageName, silenced, now))
    }

    private fun applyQuiet(next: QuietSession) {
        val release = next.releaseSilence
        val extend = next.extendSilence
        session = next.copy(releaseSilence = false, extendSilence = false)
        QuietPromptController.publish(session.prompt)
        when {
            release -> {
                recheckJob?.cancel()
                writeQuietUntil(0L)
            }
            extend -> holdSilence()
            session.silencedPackage != null && session.prompt?.recheck == true -> Unit
            session.silencedPackage != null && session.prompt == null && session.recheckAtMs > 0L -> scheduleRecheck()
            else -> recheckJob?.cancel()
        }
    }

    private fun scheduleRecheck() {
        recheckJob?.cancel()
        val at = session.recheckAtMs
        val pkg = session.silencedPackage ?: return
        if (at <= 0L) return
        recheckJob = scope.launch {
            val wait = at - QUIET_WAKE_EARLY_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (session.silencedPackage != pkg || session.prompt != null) return@launch
            applyQuiet(session.due(maxOf(System.currentTimeMillis(), at)))
        }
    }

    /** Keeps stored quiet ahead of the clock while the re-check is still unanswered. */
    private fun holdSilence() {
        val pkg = session.silencedPackage ?: return
        writeQuietUntil(System.currentTimeMillis() + QUIET_HOLD_MS)
        recheckJob?.cancel()
        recheckJob = scope.launch {
            delay(QUIET_HOLD_MS - QUIET_WAKE_EARLY_MS)
            if (session.silencedPackage == pkg && session.prompt?.recheck == true) holdSilence()
        }
    }

    private fun writeQuietUntil(untilMs: Long) {
        wantedQuietUntil = untilMs
        latestSettings = latestSettings.copy(alertQuietUntilEpochMs = untilMs)
        val epoch = quietEpoch.incrementAndGet()
        quietWritePending = true
        val container = (application as Apt443Application).container
        scope.launch {
            quietWrites.withLock {
                try {
                    if (epoch != quietEpoch.get()) return@withLock
                    val latest = container.settings.first()
                    if (epoch != quietEpoch.get()) return@withLock
                    if (latest.alertQuietUntilEpochMs != untilMs) {
                        container.saveSettings(latest.copy(alertQuietUntilEpochMs = untilMs))
                    }
                } finally {
                    if (epoch == quietEpoch.get()) quietWritePending = false
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private data class RenderRequest(
        val count: Int,
        val prompt: QuietPrompt?,
        val settings: AppSettings,
        val inApp: Boolean,
        val dreaming: Boolean,
    )

    private fun teardown() {
        if (!running && host == null) return
        setRunning(false)
        PersistentOverlays.stop(this)
        host?.remove()
        host = null
        scope.cancel()
    }

    companion object {
        private val attachedState = MutableStateFlow(false)

        /** True while this service is connected. Always-on cameras follow it onto the accessibility overlay. */
        val attached: StateFlow<Boolean> = attachedState.asStateFlow()

        /** True while the service is connected and able to place an overlay window. */
        @Volatile
        var running: Boolean = false
            private set

        fun setRunning(value: Boolean) {
            running = value
            attachedState.value = value
        }

        /**
         * Whether the viewer has switched this service on. Read from the system list rather than
         * [running] so Settings can report the real state before the service has started.
         */
        fun enabled(context: Context): Boolean {
            val expected = ComponentName(context, Apt443AccessibilityService::class.java)
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull().orEmpty()
            if (enabled.isBlank()) return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            return splitter.any { entry ->
                ComponentName.unflattenFromString(entry.trim())?.let {
                    it.packageName == expected.packageName && it.className == expected.className
                } == true
            }
        }
    }
}
