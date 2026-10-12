package app.apt443.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.apt443.domain.AccountGate
import app.apt443.domain.AlertCorner
import app.apt443.domain.AlertStyle
import app.apt443.domain.AppSettings
import app.apt443.domain.PopupLayout
import app.apt443.domain.PopupLook
import app.apt443.domain.PopupPicture
import app.apt443.domain.PopupSize
import app.apt443.domain.StreamQuality
import app.apt443.domain.coerceFadeSeconds
import app.apt443.domain.coerceOpenCount
import app.apt443.domain.CameraAlertPrefs
import app.apt443.domain.ConsoleSignInMode
import app.apt443.domain.legacyMutedIds
import app.apt443.domain.migrateLegacyMutes
import app.apt443.domain.DataSourceMode
import app.apt443.domain.ReachPreference
import app.apt443.domain.SiteVpnKind
import app.apt443.domain.decodeSpeakerPhrases
import app.apt443.domain.encodeSpeakerPhrases
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    context: Context,
) {
    private val store = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("apt443-settings") },
    )

    val settings: Flow<AppSettings> = store.data.map { prefs -> prefs.toSettings() }

    suspend fun save(settings: AppSettings) {
        store.edit { prefs ->
            prefs[Keys.LOCATION] = settings.locationName.ifBlank { "UPort" }
            prefs[Keys.HOST] = settings.consoleHost.trim()
            prefs[Keys.CONSOLE_ID] = settings.consoleId.trim()
            prefs[Keys.SITE_ID] = settings.networkSiteId.ifBlank { "default" }
            prefs[Keys.DATA_SOURCE] = settings.dataSource.name
            prefs[Keys.REACH] = settings.reachPreference.name
            prefs[Keys.REACH_CHOSEN] = settings.reachChosen.toString()
            prefs[Keys.VPN_KIND] = settings.vpnKind.name
            prefs[Keys.TAILSCALE_LOGIN] = settings.tailscaleLoginServer.trim()
            prefs[Keys.TRUST] = settings.trustConsoleCertificate.toString()
            prefs[Keys.OS_USER] = settings.osUsername.trim()
            prefs[Keys.ACCOUNT_NAME] = settings.accountName.trim()
            prefs[Keys.ACCOUNT_EMAIL] = settings.accountEmail.trim()
            prefs[Keys.ACCOUNT_ROLE] = settings.accountRole.trim()
            prefs[Keys.SITE_CONFIGURED] = settings.siteConfigured.toString()
            prefs[Keys.IDENTITY_USER_ID] = settings.identityUserId.trim()
            prefs[Keys.RESIDENT_FIRST_NAME] = settings.residentFirstName.trim()
            prefs[Keys.RESIDENT_LAST_NAME] = settings.residentLastName.trim()
            prefs[Keys.RESIDENT_STATUS] = settings.residentStatus.trim()
            prefs[Keys.ACCOUNT_GATE] = settings.accountGate.name
            prefs[Keys.SIGN_IN_MODE] = settings.signInMode.name
            prefs[Keys.ALERT_STYLE] = settings.alertStyle.name
            prefs[Keys.ALERT_CORNER] = settings.alertCorner.name
            prefs[Keys.POPUP_LOOK] = settings.popupLook.name
            prefs[Keys.POPUP_SIZE] = settings.popupSize.name
            prefs[Keys.POPUP_PICTURE] = settings.popupPicture.name
            prefs[Keys.PIP_QUALITY] = settings.pipStreamQuality.name
            prefs[Keys.MAX_POPUPS] = coerceOpenCount(settings.maxOpenPopups).toString()
            prefs[Keys.FADE_SECONDS] = coerceFadeSeconds(settings.alertFadeSeconds).toString()
            prefs[Keys.QUIET_UNTIL] = settings.alertQuietUntilEpochMs.coerceAtLeast(0L).toString()
            prefs[Keys.VOICE_ALERTS] = settings.voiceAlerts.toString()
            prefs[Keys.WIZARD_DONE] = settings.cameraWizardDoneIds.filter { it.isNotBlank() }.sorted().joinToString(",")
            prefs[Keys.CUSTOM_PHRASES] = settings.useCustomSpeakerPhrases.toString()
            prefs[Keys.SPEAKER_PHRASES] = encodeSpeakerPhrases(settings.customSpeakerPhrases)
            prefs[Keys.PERSISTENT] = PersistentCodec.encode(settings.persistentCameras)
            prefs[Keys.KEEP_AWAKE] = settings.keepTvAwake.toString()
            prefs[Keys.CAMERA_SCREENSAVER] = settings.cameraScreensaver.toString()
            prefs[Keys.PAUSE_ON_ALERT] = settings.pausePlaybackOnAlert.toString()
            writeAlertPrefs(prefs, settings.defaultAlertPrefs, settings.cameraAlertPrefs)
        }
    }

    /** Atomic read-modify-write of only the alert preferences, so concurrent saves cannot clobber them. */
    suspend fun updateAlertPrefs(transform: (StoredAlertPrefs) -> StoredAlertPrefs) {
        store.edit { prefs ->
            val current = prefs.toSettings()
            val next = transform(StoredAlertPrefs(current.defaultAlertPrefs, current.cameraAlertPrefs))
            writeAlertPrefs(prefs, next.default, next.cameras)
        }
    }

    private fun writeAlertPrefs(
        prefs: MutablePreferences,
        default: CameraAlertPrefs,
        cameras: Map<String, CameraAlertPrefs>,
    ) {
        prefs[Keys.ALERT_PREFS] = AlertPrefsCodec.encode(StoredAlertPrefs(default, cameras))
        prefs[Keys.PIP_MUTED] = legacyMutedIds(cameras).sorted().joinToString(",")
    }

    private fun Preferences.toSettings(): AppSettings {
        val legacyMuted = (this[Keys.PIP_MUTED] ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val stored = AlertPrefsCodec.decode(this[Keys.ALERT_PREFS])
        val overrides = migrateLegacyMutes(legacyMuted, stored.cameras)
        return AppSettings(
            locationName = this[Keys.LOCATION] ?: "UPort",
            consoleHost = this[Keys.HOST] ?: "",
            consoleId = this[Keys.CONSOLE_ID] ?: "",
            networkSiteId = this[Keys.SITE_ID] ?: "default",
            dataSource = enumOr(this[Keys.DATA_SOURCE], DataSourceMode.Demo),
            reachPreference = enumOr(this[Keys.REACH], ReachPreference.Auto),
            reachChosen = when (this[Keys.REACH_CHOSEN]) {
                "true" -> true
                "false" -> false
                else -> this[Keys.SITE_CONFIGURED] == "true"
            },
            vpnKind = enumOr(this[Keys.VPN_KIND], SiteVpnKind.None),
            tailscaleLoginServer = this[Keys.TAILSCALE_LOGIN] ?: "",
            trustConsoleCertificate = this[Keys.TRUST] == "true",
            osUsername = this[Keys.OS_USER] ?: "",
            accountName = this[Keys.ACCOUNT_NAME] ?: "",
            accountEmail = this[Keys.ACCOUNT_EMAIL] ?: "",
            accountRole = this[Keys.ACCOUNT_ROLE] ?: "",
            siteConfigured = this[Keys.SITE_CONFIGURED] == "true",
            identityUserId = this[Keys.IDENTITY_USER_ID] ?: "",
            residentFirstName = this[Keys.RESIDENT_FIRST_NAME] ?: "",
            residentLastName = this[Keys.RESIDENT_LAST_NAME] ?: "",
            residentStatus = this[Keys.RESIDENT_STATUS] ?: "",
            accountGate = enumOr(this[Keys.ACCOUNT_GATE], AccountGate.SignedOut),
            signInMode = enumOr(this[Keys.SIGN_IN_MODE], ConsoleSignInMode.ApiKeyOnly),
            pipMutedCameraIds = legacyMutedIds(overrides),
            alertStyle = enumOr(this[Keys.ALERT_STYLE], AlertStyle.Picture),
            alertCorner = enumOr(this[Keys.ALERT_CORNER], AlertCorner.TopRight),
            popupLook = enumOr(this[Keys.POPUP_LOOK], PopupLook.Card),
            popupSize = enumOr(this[Keys.POPUP_SIZE], PopupSize.Small),
            popupPicture = enumOr(this[Keys.POPUP_PICTURE], PopupPicture.Live),
            pipStreamQuality = enumOr(this[Keys.PIP_QUALITY], StreamQuality.Medium),
            maxOpenPopups = coerceOpenCount(this[Keys.MAX_POPUPS]?.toIntOrNull() ?: PopupLayout.DEFAULT_OPEN),
            alertFadeSeconds = coerceFadeSeconds(this[Keys.FADE_SECONDS]?.toIntOrNull() ?: PopupLayout.DEFAULT_FADE_SECONDS),
            alertQuietUntilEpochMs = this[Keys.QUIET_UNTIL]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            voiceAlerts = this[Keys.VOICE_ALERTS] == "true",
            defaultAlertPrefs = stored.default,
            cameraAlertPrefs = overrides,
            cameraWizardDoneIds = (this[Keys.WIZARD_DONE] ?: "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet(),
            useCustomSpeakerPhrases = this[Keys.CUSTOM_PHRASES] == "true",
            customSpeakerPhrases = decodeSpeakerPhrases(this[Keys.SPEAKER_PHRASES]),
            persistentCameras = PersistentCodec.decode(this[Keys.PERSISTENT]),
            keepTvAwake = this[Keys.KEEP_AWAKE] != "false",
            cameraScreensaver = this[Keys.CAMERA_SCREENSAVER] != "false",
            pausePlaybackOnAlert = this[Keys.PAUSE_ON_ALERT] == "true",
        ).normalize()
    }

    private fun AppSettings.normalize(): AppSettings {
        var gate = accountGate
        var source = dataSource
        when (gate) {
            AccountGate.Demo -> {
                gate = AccountGate.SignedOut
                source = DataSourceMode.Demo
            }
            AccountGate.Console -> source = DataSourceMode.Live
            AccountGate.SignedOut -> source = if (siteConfigured) DataSourceMode.Live else DataSourceMode.Demo
        }
        return copy(accountGate = gate, dataSource = source)
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    private object Keys {
        val LOCATION = stringPreferencesKey("location_name")
        val HOST = stringPreferencesKey("console_host")
        val CONSOLE_ID = stringPreferencesKey("console_id")
        val SITE_ID = stringPreferencesKey("network_site_id")
        val DATA_SOURCE = stringPreferencesKey("data_source")
        val REACH = stringPreferencesKey("reach_preference")
        val REACH_CHOSEN = stringPreferencesKey("reach_chosen")
        val VPN_KIND = stringPreferencesKey("vpn_kind")
        val TAILSCALE_LOGIN = stringPreferencesKey("tailscale_login_server")
        val TRUST = stringPreferencesKey("trust_console_certificate")
        val OS_USER = stringPreferencesKey("os_username")
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        val ACCOUNT_ROLE = stringPreferencesKey("account_role")
        val SITE_CONFIGURED = stringPreferencesKey("site_configured")
        val IDENTITY_USER_ID = stringPreferencesKey("identity_user_id")
        val RESIDENT_FIRST_NAME = stringPreferencesKey("resident_first_name")
        val RESIDENT_LAST_NAME = stringPreferencesKey("resident_last_name")
        val RESIDENT_STATUS = stringPreferencesKey("resident_status")
        val ACCOUNT_GATE = stringPreferencesKey("account_gate")
        val SIGN_IN_MODE = stringPreferencesKey("sign_in_mode")
        val PIP_MUTED = stringPreferencesKey("pip_muted_cameras")
        val ALERT_STYLE = stringPreferencesKey("alert_style")
        val ALERT_CORNER = stringPreferencesKey("alert_corner")
        val POPUP_LOOK = stringPreferencesKey("popup_look")
        val POPUP_SIZE = stringPreferencesKey("popup_size")
        val POPUP_PICTURE = stringPreferencesKey("popup_picture")
        val PIP_QUALITY = stringPreferencesKey("pip_stream_quality")
        val MAX_POPUPS = stringPreferencesKey("max_open_popups")
        val FADE_SECONDS = stringPreferencesKey("alert_fade_seconds")
        val QUIET_UNTIL = stringPreferencesKey("alert_quiet_until")
        val VOICE_ALERTS = stringPreferencesKey("voice_alerts")
        val ALERT_PREFS = stringPreferencesKey("alert_prefs")
        val WIZARD_DONE = stringPreferencesKey("camera_wizard_done")
        val CUSTOM_PHRASES = stringPreferencesKey("use_custom_speaker_phrases")
        val SPEAKER_PHRASES = stringPreferencesKey("speaker_phrases")
        val PERSISTENT = stringPreferencesKey("persistent_cameras")
        val KEEP_AWAKE = stringPreferencesKey("keep_tv_awake")
        val CAMERA_SCREENSAVER = stringPreferencesKey("camera_screensaver")
        val PAUSE_ON_ALERT = stringPreferencesKey("pause_playback_on_alert")
    }
}
