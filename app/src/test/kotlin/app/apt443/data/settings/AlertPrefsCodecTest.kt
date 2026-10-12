package app.apt443.data.settings

import app.apt443.domain.CameraAlertPrefs
import app.apt443.domain.DetectionType
import app.apt443.domain.VoiceTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertPrefsCodecTest {
    @Test
    fun roundTripsDefaultsAndOverrides() {
        val stored = StoredAlertPrefs(
            default = CameraAlertPrefs(voice = false, detections = setOf(DetectionType.Ring, DetectionType.Person)),
            cameras = mapOf(
                "cam\"1" to CameraAlertPrefs(popups = false),
                "cam2" to CameraAlertPrefs(detections = emptySet()),
            ),
        )
        assertEquals(stored, AlertPrefsCodec.decode(AlertPrefsCodec.encode(stored)))
    }

    @Test
    fun blankOrBrokenJsonFallsBackToDefaults() {
        assertEquals(StoredAlertPrefs(), AlertPrefsCodec.decode(null))
        assertEquals(StoredAlertPrefs(), AlertPrefsCodec.decode(""))
        assertEquals(StoredAlertPrefs(), AlertPrefsCodec.decode("{not json"))
    }

    @Test
    fun missingFieldsDefaultToEnabled() {
        val decoded = AlertPrefsCodec.decode("""{"cameras":{"c":{}}}""")
        assertEquals(CameraAlertPrefs(), decoded.default)
        assertEquals(CameraAlertPrefs(), decoded.cameras["c"])
    }

    @Test
    fun voiceTargetSurvivesARoundTripAndDefaultsToTheTv() {
        val stored = StoredAlertPrefs(
            default = CameraAlertPrefs(voiceTarget = VoiceTarget.Both),
            cameras = mapOf("porch" to CameraAlertPrefs(voiceTarget = VoiceTarget.CameraSpeaker)),
        )
        assertEquals(stored, AlertPrefsCodec.decode(AlertPrefsCodec.encode(stored)))
        // Settings written before camera speakers were an option keep speaking on the TV.
        val legacy = AlertPrefsCodec.decode("""{"default":{"popups":true,"voice":true}}""")
        assertEquals(VoiceTarget.Tv, legacy.default.voiceTarget)
        assertEquals(VoiceTarget.Tv, AlertPrefsCodec.decode("""{"default":{"voiceTarget":"Yelling"}}""").default.voiceTarget)
    }

    @Test
    fun unknownDetectionNamesAreIgnored() {
        val decoded = AlertPrefsCodec.decode(
            """{"default":{"off":["Motion","Hovercraft"]},"cameras":{"c":{"detections":["Person","Ghost"]}}}""",
        )
        assertEquals(DetectionType.entries.toSet() - DetectionType.Motion, decoded.default.detections)
        assertEquals(setOf(DetectionType.Person), decoded.cameras["c"]?.detections)
    }
}
