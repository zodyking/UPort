package app.apt443.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.apt443.Apt443Application
import app.apt443.data.live.GoogleTranslateSpeech
import app.apt443.MainActivity
import app.apt443.R
import app.apt443.domain.AlertInset
import app.apt443.domain.AlertKind
import app.apt443.domain.AlertStyle
import app.apt443.domain.AppSettings
import app.apt443.domain.PopupDeck
import app.apt443.domain.PopupLook
import app.apt443.domain.admit
import app.apt443.domain.alertsSilenced
import app.apt443.domain.coerceFadeSeconds
import app.apt443.domain.dismiss
import app.apt443.domain.dismissNewest
import app.apt443.domain.engage
import app.apt443.domain.expire
import app.apt443.domain.prefixSpeakerMessage
import app.apt443.domain.watchesPersistently
import app.apt443.domain.shouldSpeak
import app.apt443.ui.components.canDrawOverlays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sticky foreground service that draws one alert over whatever is on the TV.
 * This is a system overlay window, not Activity picture-in-picture.
 */
class AlertOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dismissJob: Job? = null
    private var deck = PopupDeck()
    private var spokenIds = ArrayDeque<String>()
    private var window: AlertWindowHost? = null
    private var latestSettings = AppSettings()
    private lateinit var speech: GoogleTranslateSpeech

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startInForeground()
        val container = (application as Apt443Application).container
        speech = container.speech
        AlertController.onIgnore = { id -> if (id.isNullOrBlank()) dismissNewest() else dismiss(id) }
        AlertController.onSilence = { clearDeck() }
        AlertController.onWatch = { cameraId -> watch(cameraId) }
        AlertController.onUnlock = { doorId -> container.access.unlock(doorId, confirmed = true) }
        AlertController.onAnnounce = { cameraId, message ->
            container.protect.announce(cameraId, latestSettings.prefixSpeakerMessage(message))
        }
        AlertController.onInteract = { id -> engage(id) }
        scope.launch {
            container.settings.collect { settings ->
                latestSettings = settings
                if (!settings.siteConfigured || settings.alertsSilenced(System.currentTimeMillis())) {
                    clearDeck()
                } else {
                    armTimers()
                }
            }
        }
        scope.launch {
            // Popup filtering (AppSettings.shouldAlert) already happened in the repository.
            container.protect.alerts().collect { incoming ->
                present(enrich(incoming))
            }
        }
        scope.launch {
            combine(container.settings, Apt443AccessibilityService.attached) { settings, _ -> settings }
                .collect { settings ->
                    val pinned = settings.persistentCameras.map { it.cameraId }.toSet()
                    val covered = deck.slots.filter { it.alert.cameraId in pinned }
                    if (covered.isNotEmpty()) {
                        covered.forEach { deck = deck.dismiss(it.alert.id) }
                        finishDismiss()
                    }
                    val showHere = settings.siteConfigured &&
                        settings.persistentCameras.isNotEmpty() &&
                        !Apt443AccessibilityService.running &&
                        canDrawOverlays()
                    if (showHere) {
                        PersistentOverlays.show(this@AlertOverlayService, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    } else if (!Apt443AccessibilityService.running) {
                        PersistentOverlays.stop(this@AlertOverlayService)
                    }
                }
        }
        scope.launch {
            combine(AptVisibility.foreground, DreamVisibility.active) { inApp, dreaming ->
                inApp to dreaming
            }.collect { (inApp, dreaming) ->
                when {
                    dreaming -> {
                        removeWindow()
                        NotificationManagerCompat.from(this@AlertOverlayService).cancel(ALERT_NOTIFICATION_ID)
                    }
                    inApp -> {
                        hideWindow()
                        NotificationManagerCompat.from(this@AlertOverlayService).cancel(ALERT_NOTIFICATION_ID)
                    }
                    deck.slots.isNotEmpty() -> showOutsideTheApp(latestSettings)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_DISMISS -> dismissNewest()
            ACTION_WATCH -> watch(intent.getStringExtra(EXTRA_CAMERA_ID))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        dismissJob?.cancel()
        PersistentOverlays.stop(this)
        removeWindow()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.watch_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, WATCH_NOTIFICATION_ID, notification, type)
    }

    private suspend fun enrich(alert: AlertInset): AlertInset {
        val needsDoor = alert.kind == AlertKind.Doorbell || alert.kind == AlertKind.AccessDoor
        if (!needsDoor || !alert.doorId.isNullOrBlank()) return alert
        val container = (application as Apt443Application).container
        val door = runCatching { container.access.feed().first().doors.firstOrNull() }.getOrNull()
            ?: return alert
        return alert.copy(doorId = door.id, doorName = door.name)
    }

    private fun present(alert: AlertInset) {
        val now = System.currentTimeMillis()
        val settings = latestSettings
        if (!settings.siteConfigured || settings.alertsSilenced(now)) return
        if (settings.watchesPersistently(alert.cameraId)) return
        deck = deck.admit(alert, now, settings.maxOpenPopups)
        if (deck.slots.none { it.alert.id == alert.id }) return
        publishDeck()
        if (rememberSpoken(alert.id)) speak(alert, settings)
        showOutsideTheApp(settings)
        armTimers()
    }

    /**
     * The open app draws its own card. A second window here would cover that screen and take
     * the remote. Outside the app, the accessibility overlay is the Fire TV path.
     */
    private fun showOutsideTheApp(settings: AppSettings) {
        if (DreamVisibility.active.value) {
            NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
            removeWindow()
            return
        }
        if (AptVisibility.mainResumed) {
            NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
            hideWindow()
            return
        }
        when {
            Apt443AccessibilityService.running -> {
                NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
                removeWindow()
            }
            canDrawOverlays() && ensureWindow(settings) -> {
                NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
            }
            else -> {
                hideWindow()
                deck.slots.lastOrNull()?.alert?.let { presentWithoutOverlay(it) }
            }
        }
    }

    /** Says the detection on this TV. Camera speakers only play a reply the viewer picks. */
    private fun speak(alert: AlertInset, settings: AppSettings) {
        if (!settings.shouldSpeak(alert.cameraId, alert.detections)) return
        val line = alert.title.trim()
        if (line.isBlank()) return
        scope.launch {
            runCatching { speech.playOnTv(line) }
                .onSuccess { AlertController.clearVoiceFailure() }
                .onFailure {
                    Log.w(TAG, "TV announcement failed", it)
                    AlertController.reportVoiceFailure(
                        it.message ?: "TV voice failed.",
                    )
                }
        }
    }

    private fun engage(id: String) {
        deck = deck.engage(id)
        publishDeck()
        armTimers()
    }

    private fun armTimers() {
        dismissJob?.cancel()
        val fadeMs = coerceFadeSeconds(latestSettings.alertFadeSeconds) * 1_000L
        val now = System.currentTimeMillis()
        val wait = deck.slots
            .filter { !it.engaged }
            .minOfOrNull { (fadeMs - (now - it.openedAtMs)).coerceAtLeast(0L) }
            ?: return
        dismissJob = scope.launch {
            delay(wait)
            val fade = coerceFadeSeconds(latestSettings.alertFadeSeconds) * 1_000L
            deck = deck.expire(System.currentTimeMillis(), fade)
            publishDeck()
            if (deck.slots.isEmpty()) {
                hideWindow()
                NotificationManagerCompat.from(this@AlertOverlayService).cancel(ALERT_NOTIFICATION_ID)
            }
            armTimers()
        }
    }

    private fun dismiss(id: String) {
        deck = deck.dismiss(id)
        finishDismiss()
    }

    private fun dismissNewest() {
        deck = deck.dismissNewest()
        finishDismiss()
    }

    private fun clearDeck() {
        deck = PopupDeck()
        finishDismiss()
    }

    private fun finishDismiss() {
        publishDeck()
        if (deck.slots.isNotEmpty()) {
            armTimers()
            return
        }
        dismissJob?.cancel()
        hideWindow()
        NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
    }

    /** Returns true the first time this event is spoken. */
    private fun rememberSpoken(id: String): Boolean {
        if (id in spokenIds) return false
        spokenIds.addLast(id)
        while (spokenIds.size > SPOKEN_MEMORY) spokenIds.removeFirst()
        return true
    }

    private fun publishDeck() {
        AlertController.publish(deck.slots)
    }

    private fun presentWithoutOverlay(alert: AlertInset) {
        // When APT 443 is on screen, the in-app card from [AlertController] is enough.
        // Launching [AlertActivity] as well stacks a second copy of the same alert.
        if (AptVisibility.mainResumed) return
        launchAlertActivity()
        postAlertNotification(alert)
    }

    private fun launchAlertActivity() {
        val permissions = AlertPermissions.read(this)
        val intent = Intent(this, AlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlertActivity.EXTRA_PICTURE, latestSettings.alertStyle == AlertStyle.Picture)
            putExtra(
                AlertActivity.EXTRA_FIRE_TV_SETUP,
                permissions.isFireTv && !permissions.accessibility,
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "AlertActivity launch failed", it) }
    }

    private fun watch(cameraId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!cameraId.isNullOrBlank()) putExtra(MainActivity.EXTRA_CAMERA_ID, cameraId)
        }
        runCatching { startActivity(intent) }
        if (cameraId.isNullOrBlank()) clearDeck() else dismissSlotsFor(cameraId)
    }

    private fun dismissSlotsFor(cameraId: String) {
        deck.slots.filter { it.alert.cameraId == cameraId }.forEach { deck = deck.dismiss(it.alert.id) }
        finishDismiss()
    }

    private fun ensureWindow(settings: AppSettings): Boolean {
        val host = window ?: AlertWindowHost(
            this,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        ).also { window = it }
        val interactive = QuietPromptController.prompt.value != null ||
            settings.popupLook != PopupLook.Minimal
        if (host.show(settings, deck.slots.size.coerceAtLeast(1), interactive)) return true
        window = null
        return false
    }

    private fun hideWindow() {
        window?.hide()
    }

    private fun postAlertNotification(alert: AlertInset) {
        val title = alert.title.trim().ifBlank { getString(R.string.app_name) }
        val body = alert.subtitle.trim().ifBlank {
            alert.cameraName.trim().ifBlank { getString(R.string.watch_notification) }
        }
        val dismissPending = servicePendingIntent(
            requestCode = 1,
            action = ACTION_DISMISS,
        )
        val watchPending = servicePendingIntent(
            requestCode = 2,
            action = ACTION_WATCH,
            cameraId = alert.cameraId,
        )
        val openIntent = if (latestSettings.alertStyle == AlertStyle.Picture) {
            Intent(this, AlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlertActivity.EXTRA_PICTURE, true)
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (!alert.cameraId.isNullOrBlank()) {
                    putExtra(MainActivity.EXTRA_CAMERA_ID, alert.cameraId)
                }
            }
        }
        val openPending = PendingIntent.getActivity(
            this,
            3,
            openIntent,
            pendingIntentFlags(),
        )
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title).setSummaryText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openPending)
            .setFullScreenIntent(openPending, true)
            .addAction(0, getString(R.string.alert_action_watch), watchPending)
            .addAction(0, getString(R.string.alert_action_ignore), dismissPending)
        alert.snapshotJpeg?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@let
            builder.setLargeIcon(bitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
                    .setSummaryText(body),
            )
        }
        NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    private fun servicePendingIntent(
        requestCode: Int,
        action: String,
        cameraId: String? = null,
    ): PendingIntent {
        val intent = Intent(this, AlertOverlayService::class.java).apply {
            this.action = action
            if (!cameraId.isNullOrBlank()) putExtra(EXTRA_CAMERA_ID, cameraId)
        }
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun removeWindow() {
        window?.remove()
        window = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val watch = NotificationChannel(
            WATCH_CHANNEL,
            getString(R.string.watch_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.watch_notification)
        }
        val alerts = NotificationChannel(
            ALERT_CHANNEL,
            getString(R.string.alert_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(watch)
        manager.createNotificationChannel(alerts)
    }

    companion object {
        private const val TAG = "AlertOverlayService"
        private const val WATCH_CHANNEL = "apt443_watch"
        private const val ALERT_CHANNEL = "apt443_alerts"
        private const val WATCH_NOTIFICATION_ID = 443
        private const val ALERT_NOTIFICATION_ID = 444
        private const val SPOKEN_MEMORY = 32
        private const val ACTION_DISMISS = "app.apt443.alert.DISMISS"
        private const val ACTION_WATCH = "app.apt443.alert.WATCH"
        private const val EXTRA_CAMERA_ID = "cameraId"

        fun start(context: Context) {
            val intent = Intent(context, AlertOverlayService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
