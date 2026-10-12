package app.apt443

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.apt443.alert.AlertOverlayService
import app.apt443.alert.AlertPermissions
import app.apt443.ui.Apt443Root
import app.apt443.ui.NavigationBus
import app.apt443.ui.theme.Apt443Theme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AlertOverlayService.start(this)
        deliverCamera(intent)
        if (!AlertPermissions.notificationsGranted(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                AlertPermissions.REQUEST_NOTIFICATIONS,
            )
        }
        val container = (application as Apt443Application).container
        lifecycleScope.launch {
            container.settings
                .map { it.keepTvAwake }
                .distinctUntilChanged()
                .collect { awake ->
                    if (awake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }
        setContent {
            Apt443Theme {
                Apt443Root(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverCamera(intent)
    }

    private fun deliverCamera(intent: Intent?) {
        val cameraId = intent?.getStringExtra(EXTRA_CAMERA_ID) ?: return
        NavigationBus.open(cameraId)
    }

    companion object {
        const val EXTRA_CAMERA_ID = "cameraId"
    }
}
