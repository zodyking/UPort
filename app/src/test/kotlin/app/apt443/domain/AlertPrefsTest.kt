package app.apt443.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPrefsTest {
    private fun event(
        type: ProtectEventType,
        labels: List<String> = emptyList(),
        cameraId: String? = "cam1",
    ) = ProtectEvent("e", cameraId, "Cam", type, labels, 1L, null, "")

    @Test
    fun mapsBasicEventTypes() {
        assertEquals(setOf(DetectionType.Ring), event(ProtectEventType.Ring).detectionTypes())
        assertEquals(setOf(DetectionType.Motion), event(ProtectEventType.Motion).detectionTypes())
        assertEquals(setOf(DetectionType.Sensor), event(ProtectEventType.Sensor).detectionTypes())
        assertEquals(setOf(DetectionType.Other), event(ProtectEventType.Disconnect).detectionTypes())
        assertEquals(setOf(DetectionType.Other), event(ProtectEventType.Other).detectionTypes())
    }

    @Test
    fun mapsSmartLabelsCaseInsensitively() {
        val types = event(
            ProtectEventType.SmartDetect,
            listOf("PERSON", "Vehicle", "animal", "package", "face", "licensePlate"),
        ).detectionTypes()
        assertEquals(
            setOf(
                DetectionType.Person,
                DetectionType.Vehicle,
                DetectionType.Animal,
                DetectionType.Package,
                DetectionType.Face,
                DetectionType.LicensePlate,
            ),
            types,
        )
    }

    @Test
    fun mapsPlateAliasesAndUnknownLabels() {
        assertEquals(
            setOf(DetectionType.LicensePlate),
            event(ProtectEventType.SmartDetect, listOf("license_plate", "plate")).detectionTypes(),
        )
        assertEquals(
            setOf(DetectionType.Person, DetectionType.Other),
            event(ProtectEventType.SmartDetect, listOf("person", "alrmSmoke")).detectionTypes(),
        )
        assertEquals(setOf(DetectionType.Other), event(ProtectEventType.SmartDetect).detectionTypes())
    }

    @Test
    fun defaultsAllowEverything() {
        val settings = AppSettings()
        assertTrue(settings.shouldAlert(event(ProtectEventType.Motion)))
        assertTrue(settings.shouldAlert(event(ProtectEventType.Ring)))
        assertTrue(settings.shouldAlert(event(ProtectEventType.SmartDetect, listOf("person"))))
    }

    @Test
    fun popupsOffBlocksEvenRings() {
        val settings = AppSettings(cameraAlertPrefs = mapOf("cam1" to CameraAlertPrefs(popups = false)))
        assertFalse(settings.shouldAlert(event(ProtectEventType.Ring)))
        assertFalse(settings.shouldAlert(event(ProtectEventType.Motion)))
        assertTrue(settings.shouldAlert(event(ProtectEventType.Motion, cameraId = "cam2")))
    }

    @Test
    fun disabledDetectionBlocksOnlyThatType() {
        val prefs = CameraAlertPrefs(detections = DetectionType.entries.toSet() - DetectionType.Motion)
        val settings = AppSettings(cameraAlertPrefs = mapOf("cam1" to prefs))
        assertFalse(settings.shouldAlert(event(ProtectEventType.Motion)))
        assertTrue(settings.shouldAlert(event(ProtectEventType.SmartDetect, listOf("person"))))
        assertTrue(settings.shouldAlert(event(ProtectEventType.Ring)))
    }

    @Test
    fun ringHonorsItsToggle() {
        val prefs = CameraAlertPrefs(detections = DetectionType.entries.toSet() - DetectionType.Ring)
        val settings = AppSettings(cameraAlertPrefs = mapOf("cam1" to prefs))
        assertFalse(settings.shouldAlert(event(ProtectEventType.Ring)))
    }

    @Test
    fun anyEnabledDetectionIsEnough() {
        val prefs = CameraAlertPrefs(detections = setOf(DetectionType.Vehicle))
        val settings = AppSettings(defaultAlertPrefs = prefs)
        assertTrue(settings.shouldAlert(event(ProtectEventType.SmartDetect, listOf("person", "vehicle"))))
        assertFalse(settings.shouldAlert(event(ProtectEventType.SmartDetect, listOf("person"))))
    }

    @Test
    fun overrideWinsOverDefault() {
        val settings = AppSettings(
            defaultAlertPrefs = CameraAlertPrefs(popups = false),
            cameraAlertPrefs = mapOf("cam1" to CameraAlertPrefs()),
        )
        assertTrue(settings.shouldAlert(event(ProtectEventType.Motion)))
        assertFalse(settings.shouldAlert(event(ProtectEventType.Motion, cameraId = "other")))
    }

    @Test
    fun nullCameraUsesDefaults() {
        val settings = AppSettings(
            defaultAlertPrefs = CameraAlertPrefs(detections = setOf(DetectionType.Sensor)),
            cameraAlertPrefs = mapOf("cam1" to CameraAlertPrefs(popups = false)),
        )
        assertTrue(settings.shouldAlert(event(ProtectEventType.Sensor, cameraId = null)))
        assertFalse(settings.shouldAlert(event(ProtectEventType.Motion, cameraId = null)))
        assertEquals(settings.defaultAlertPrefs, settings.alertPrefsFor(null))
        assertEquals(settings.defaultAlertPrefs, settings.alertPrefsFor(""))
    }

    @Test
    fun unsetCameraRulesStartAtMotion() {
        val fresh = AppSettings()
        assertEquals(setOf(DetectionType.Motion), wizardTriggerSelection(fresh, "cam1"))
        val saved = fresh.copy(
            cameraAlertPrefs = mapOf("cam1" to CameraAlertPrefs(detections = setOf(DetectionType.Person))),
        )
        assertEquals(setOf(DetectionType.Person), wizardTriggerSelection(saved, "cam1"))
    }

    @Test
    fun residentSignInKeepsLocalAdmin() {
        val settings = AppSettings(
            osUsername = "owner",
            signInMode = ConsoleSignInMode.ApiKeyOnly,
            siteConfigured = true,
        )
        val user = IdentityUser("u1", "Tom", "Foster", "tom@home.test", "ACTIVE")
        val next = settings.withResidentSignIn(user, hasOsPassword = true)
        assertEquals(ConsoleSignInMode.LocalUserAndApiKey, next.signInMode)
        assertEquals("Tom Foster", next.accountName)
        assertEquals("tom@home.test", next.accountEmail)
        assertEquals("Foster", next.residentLastName)
        assertEquals("ACTIVE", next.residentStatus)
        assertEquals(ConsoleSignInMode.ApiKeyOnly, settings.withResidentSignIn(user, hasOsPassword = false).signInMode)
    }

    @Test
    fun unsetSpokenDetectionsStillSpeakEveryEvent() {
        val settings = AppSettings(voiceAlerts = true, cameraAlertPrefs = mapOf("cam1" to CameraAlertPrefs()))
        assertTrue(settings.shouldSpeak("cam1", setOf(DetectionType.Motion)))
    }

    @Test
    fun spokenDetectionsLimitWhatIsSaid() {
        val prefs = CameraAlertPrefs(spokenDetections = setOf(DetectionType.Person))
        val settings = AppSettings(voiceAlerts = true, cameraAlertPrefs = mapOf("cam1" to prefs))
        assertTrue(settings.shouldSpeak("cam1", setOf(DetectionType.Person)))
        assertFalse(settings.shouldSpeak("cam1", setOf(DetectionType.Motion)))
    }

    @Test
    fun cameraRepliesNeedASpeakerAndTheCameraOption() {
        val replies = CameraAlertPrefs(voice = true, voiceTarget = VoiceTarget.CameraSpeaker)
        val both = CameraAlertPrefs(voice = true, voiceTarget = VoiceTarget.Both)
        val tvOnly = CameraAlertPrefs(voice = true, voiceTarget = VoiceTarget.Tv)
        val quiet = CameraAlertPrefs(voice = false, voiceTarget = VoiceTarget.CameraSpeaker)
        val settings = AppSettings(
            cameraAlertPrefs = mapOf("porch" to replies, "yard" to both, "hall" to tvOnly, "quiet" to quiet),
        )
        assertTrue(settings.cameraReplies("porch", hardwareSpeaker = true))
        assertTrue(settings.cameraReplies("yard", hardwareSpeaker = true))
        assertFalse(settings.cameraReplies("hall", hardwareSpeaker = true))
        assertFalse(settings.cameraReplies("quiet", hardwareSpeaker = true))
        assertFalse(settings.cameraReplies("porch", hardwareSpeaker = false))
        assertFalse(settings.shouldSpeak("porch", setOf(DetectionType.Motion)))
        assertTrue(settings.copy(voiceAlerts = true).shouldSpeak("yard", setOf(DetectionType.Motion)))
    }

    @Test
    fun speakNeedsGlobalAndCameraVoice() {
        val prefs = mapOf("quiet" to CameraAlertPrefs(voice = false))
        assertFalse(AppSettings(voiceAlerts = false).shouldSpeak("cam1"))
        assertTrue(AppSettings(voiceAlerts = true).shouldSpeak("cam1"))
        assertFalse(AppSettings(voiceAlerts = true, cameraAlertPrefs = prefs).shouldSpeak("quiet"))
        assertTrue(AppSettings(voiceAlerts = true, cameraAlertPrefs = prefs).shouldSpeak(null))
    }

    @Test
    fun legacyMutesBecomePopupsOffWithoutOverridingExplicitPrefs() {
        val explicit = CameraAlertPrefs(voice = false)
        val merged = migrateLegacyMutes(setOf("a", "b", ""), mapOf("b" to explicit))
        assertEquals(CameraAlertPrefs(popups = false), merged["a"])
        assertEquals(explicit, merged["b"])
        assertFalse(merged.containsKey(""))
        assertEquals(setOf("a"), legacyMutedIds(merged))
    }

    @Test
    fun migrationIsNoOpWithoutLegacyIds() {
        val overrides = mapOf("x" to CameraAlertPrefs(popups = false))
        assertEquals(overrides, migrateLegacyMutes(emptySet(), overrides))
    }

    @Test
    fun persistentCameraDoesNotAlsoPopup() {
        val settings = AppSettings(persistentCameras = listOf(PersistentCamera(cameraId = "front")))
        assertTrue(settings.watchesPersistently("front"))
        assertFalse(settings.shouldAlert("front", setOf(DetectionType.Person)))
        assertTrue(settings.shouldAlert("door", setOf(DetectionType.Person)))
    }

    @Test
    fun persistentPlacementStaysOnScreen() {
        val shoved = PersistentCamera(cameraId = "a", x = 2f, y = -1f, width = 4f, height = 0.01f).placed()
        assertTrue(shoved.x >= 0f)
        assertTrue(shoved.y >= 0f)
        assertTrue(shoved.x + shoved.width <= 1f)
        assertTrue(shoved.y + shoved.height <= 1f)
        assertTrue(shoved.width >= 0.08f)
        assertTrue(shoved.height >= 0.08f)
    }

    @Test
    fun camerasWithoutSavedSetupNeedTheWizard() {
        val front = Camera("front", "Front yard", online = true, isDoorbell = false, model = "G4")
        val door = Camera("door", "Door", online = true, isDoorbell = true, model = "G4")
        val settings = AppSettings(cameraAlertPrefs = mapOf("door" to CameraAlertPrefs()))
        assertEquals(listOf(front), settings.camerasNeedingSetup(listOf(door, front, door.copy(id = ""))))
    }

    @Test
    fun headlineNamesTheMostImportantDetection() {
        assertEquals("Person detected", setOf(DetectionType.Motion, DetectionType.Person).headline())
        assertEquals("Doorbell ring", setOf(DetectionType.Ring).headline())
        assertEquals("Alert", emptySet<DetectionType>().headline())
    }
}
