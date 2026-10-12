package app.apt443

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import app.apt443.alert.AlertOverlayService
import app.apt443.alert.AptVisibility
import app.apt443.alert.BootReceiver
import app.apt443.data.AppContainer
import app.apt443.screensaver.StayAwake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class Apt443Application : Application() {
    lateinit var container: AppContainer
        private set

    private val systemScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action in BootReceiver.WAKE_ACTIONS) AlertOverlayService.start(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        systemScope.launch {
            container.settings
                .map { it.keepTvAwake to it.cameraScreensaver }
                .distinctUntilChanged()
                .collect { (awake, screensaver) -> StayAwake.apply(this@Apt443Application, awake, screensaver) }
        }
        val wake = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, wake, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(wakeReceiver, wake)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) AptVisibility.mainResumed = true
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) AptVisibility.mainResumed = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        AlertOverlayService.start(this)
    }

    /** Re-writes the sleep and screensaver settings from the saved toggles, e.g. after boot. */
    fun refreshStayAwake() {
        systemScope.launch {
            val saved = container.settings.first()
            StayAwake.apply(this@Apt443Application, saved.keepTvAwake, saved.cameraScreensaver)
        }
    }
}
