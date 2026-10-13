package app.apt443.alert

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.Apt443Application
import app.apt443.domain.AlertStyle
import app.apt443.domain.PopupLook
import app.apt443.domain.AppSettings
import app.apt443.domain.prefixSpeakerMessage
import app.apt443.domain.speakerPhrases
import app.apt443.ui.components.AlertInsetHost
import app.apt443.ui.components.AlertPopupGroup
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptCard
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.theme.Apt443Theme
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType

/**
 * Same alert card used inside the app, shown after UPort leaves the screen
 * when a system overlay window is not allowed.
 */
class AlertActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        // Picture-in-picture is opt-in. The system, not the app, decides where a PiP window
        // sits, so entering it by default parked every alert in the corner Fire TV prefers and
        // quietly overrode the viewer's placement setting.
        val picture = intent.getBooleanExtra(EXTRA_PICTURE, false)
        val fireTvSetup = intent.getBooleanExtra(EXTRA_FIRE_TV_SETUP, false)
        val container = (application as Apt443Application).container
        setContent {
            val context = LocalContext.current
            val settings by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val client = androidx.compose.runtime.remember(
                settings.consoleHost,
                settings.trustConsoleCertificate,
            ) { container.http.clientFor(settings) }
            val slots by AlertController.slots.collectAsStateWithLifecycle()
            Apt443Theme {
                if (slots.isEmpty()) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    val compact = settings.alertStyle == AlertStyle.Notification
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (fireTvSetup) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(AptSpace.lg),
                                verticalArrangement = Arrangement.spacedBy(AptSpace.md),
                            ) {
                                AptCard(
                                    title = "Alerts over other apps",
                                    subtitle = "Turn this on once so doorbell alerts appear while Netflix is playing.",
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(AptSpace.xs)) {
                                        AlertPermissions.accessibilitySteps().forEachIndexed { index, step ->
                                            Text(
                                                text = "${index + 1}. $step",
                                                fontSize = AptType.body,
                                                color = AptColors.Secondary,
                                            )
                                        }
                                    }
                                    AptButton(
                                        text = "Open Accessibility settings",
                                        style = AptButtonStyle.Primary,
                                        icon = AptIconKind.Settings,
                                        onClick = { AlertPermissions.openAccessibilitySettings(context) },
                                    )
                                }
                            }
                        }
                        AlertInsetHost(
                            corner = settings.alertCorner,
                            // In PiP the whole window already is the alert, so there is no
                            // corner left to honour; everywhere else the setting applies.
                            fill = inPip,
                        ) {
                            AlertPopupGroup(
                                slots = slots,
                                prompt = null,
                                corner = settings.alertCorner,
                                compact = compact,
                                size = settings.popupSize,
                                minimal = settings.popupLook == PopupLook.Minimal,
                                client = client,
                                fill = inPip,
                                onAnnounce = { cameraId, message ->
                                    container.protect.announce(
                                        cameraId,
                                        settings.prefixSpeakerMessage(message),
                                    )
                                },
                                phrasesFor = { forDoor -> settings.speakerPhrases(forDoor) },
                            )
                        }
                    }
                }
            }
        }
        if (picture && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    companion object {
        const val EXTRA_PICTURE = "app.apt443.alert.PICTURE"
        const val EXTRA_FIRE_TV_SETUP = "app.apt443.alert.FIRE_TV_SETUP"
    }
}
